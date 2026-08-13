package dev.nxsync.android

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class SaveSyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val preferences =
            applicationContext.getSharedPreferences("nxsync", Context.MODE_PRIVATE)
        val tree = preferences.getString("eden_tree_uri", null)
            ?: return@withContext Result.failure()
        try {
            val token = GoogleAuthorization.accessToken(applicationContext)
                ?: return@withContext Result.failure()
            val root = DocumentFile.fromTreeUri(applicationContext, Uri.parse(tree))
                ?: return@withContext Result.failure()
            val titlePattern = Regex("^[0-9A-Fa-f]{16}$")
            val titleDirectories = if (titlePattern.matches(root.name.orEmpty())) {
                listOf(root)
            } else {
                root.listFiles().filter {
                    it.isDirectory && titlePattern.matches(it.name.orEmpty())
                }
            }
            val drive = DriveGateway(token)
            for (directory in titleDirectories) {
                if (isStopped) return@withContext Result.retry()
                val titleId = directory.name!!.uppercase()
                val temp = kotlin.io.path.createTempFile(
                    applicationContext.cacheDir.toPath(),
                    "$titleId-",
                    ".zip",
                ).toFile()
                try {
                    val archive = SafArchive.create(
                        applicationContext.contentResolver,
                        directory,
                        temp,
                    )
                    drive.push(titleId, archive)
                } finally {
                    temp.delete()
                }
            }
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Exception) {
            Result.failure()
        }
    }
}

