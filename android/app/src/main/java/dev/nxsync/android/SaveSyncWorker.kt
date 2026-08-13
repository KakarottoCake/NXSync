package dev.nxsync.android

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
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

            val total = titleDirectories.size
            if (total == 0) {
                setProgress(workDataOf(
                    "status" to "No game save folders found in selected Eden folder.",
                    "current" to 0,
                    "total" to 0,
                    "is_syncing" to false,
                ))
                return@withContext Result.success()
            }

            val drive = DriveGateway(token)
            var uploaded = 0
            var synced = 0
            var current = 0

            for (directory in titleDirectories) {
                if (isStopped) return@withContext Result.retry()
                current++
                val titleId = directory.name!!.uppercase()

                setProgress(workDataOf(
                    "status" to "Syncing [$current/$total]: $titleId",
                    "current" to current,
                    "total" to total,
                    "is_syncing" to true,
                ))

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
                    val didUpload = drive.push(titleId, archive)
                    if (didUpload) uploaded++ else synced++
                } finally {
                    temp.delete()
                }
            }

            val finalStatus = "Sync complete! ($uploaded uploaded, $synced up to date)"
            setProgress(workDataOf(
                "status" to finalStatus,
                "current" to total,
                "total" to total,
                "is_syncing" to false,
            ))

            Result.success()
        } catch (_: IOException) {
            Result.retry()
        } catch (e: Exception) {
            setProgress(workDataOf(
                "status" to "Sync error: ${e.message}",
                "current" to 0,
                "total" to 0,
                "is_syncing" to false,
            ))
            Result.failure()
        }
    }
}
