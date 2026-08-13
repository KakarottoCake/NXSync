package dev.nxsync.android

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SaveSyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val preferences = applicationContext.getSharedPreferences("nxsync", Context.MODE_PRIVATE)
        val tree = preferences.getString("eden_tree_uri", null)
        if (tree == null) {
            setProgress(workDataOf("status" to "Eden save folder not selected.", "current" to 0, "total" to 0, "is_syncing" to false))
            return@withContext Result.failure()
        }

        val token = GoogleAuthorization.accessToken(applicationContext)
        if (token == null) {
            setProgress(workDataOf("status" to "Google Drive not authenticated. Please tap Connect.", "current" to 0, "total" to 0, "is_syncing" to false))
            return@withContext Result.failure()
        }

        val root = DocumentFile.fromTreeUri(applicationContext, Uri.parse(tree))
        if (root == null || !root.exists()) {
            setProgress(workDataOf("status" to "Cannot access selected folder. Please re-select Eden folder.", "current" to 0, "total" to 0, "is_syncing" to false))
            return@withContext Result.failure()
        }

        val titleDirectories = findTitleDirectories(root)
        val total = titleDirectories.size
        if (total == 0) {
            setProgress(workDataOf(
                "status" to "No game save folders (16-char hex IDs) found in selected folder. Re-select your Eden save folder.",
                "current" to 0,
                "total" to 0,
                "is_syncing" to false,
            ))
            return@withContext Result.success()
        }

        try {
            val folderId = GoogleAuthorization.getFolderId(applicationContext)
            val drive = DriveGateway(token, folderId)
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
        } catch (e: Exception) {
            e.printStackTrace()
            val errorMsg = "Sync failed: " + (e.message ?: e.javaClass.simpleName)
            setProgress(workDataOf(
                "status" to errorMsg,
                "current" to 0,
                "total" to 0,
                "is_syncing" to false,
            ))
            Result.failure()
        }
    }

    private fun findTitleDirectories(root: DocumentFile, depth: Int = 0): List<DocumentFile> {
        if (depth > 4) return emptyList()
        val titlePattern = Regex("^[0-9A-Fa-f]{16}$")
        val results = mutableListOf<DocumentFile>()

        if (root.isDirectory) {
            if (titlePattern.matches(root.name.orEmpty())) {
                results.add(root)
                return results
            }
            val children = root.listFiles()
            for (child in children) {
                if (child.isDirectory) {
                    if (titlePattern.matches(child.name.orEmpty())) {
                        results.add(child)
                    } else {
                        results.addAll(findTitleDirectories(child, depth + 1))
                    }
                }
            }
        }
        return results
    }
}
