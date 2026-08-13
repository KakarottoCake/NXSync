package dev.nxsync.android

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class PreparedArchive(
    val file: File,
    val sha256: String,
    val modifiedUnix: Long,
)

object SafArchive {
    fun create(
        resolver: ContentResolver,
        directory: DocumentFile,
        output: File,
    ): PreparedArchive {
        var modifiedMillis = directory.lastModified()
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            fun add(current: DocumentFile, relative: String) {
                current.listFiles()
                    .sortedBy { it.name.orEmpty() }
                    .forEach { child ->
                        val name = child.name ?: return@forEach
                        val path = if (relative.isEmpty()) name else "$relative/$name"
                        modifiedMillis = maxOf(modifiedMillis, child.lastModified())
                        if (child.isDirectory) {
                            add(child, path)
                        } else if (child.isFile) {
                            val entry = ZipEntry(path)
                            entry.time = child.lastModified()
                            zip.putNextEntry(entry)
                            resolver.openInputStream(child.uri)?.use { it.copyTo(zip) }
                                ?: error("Cannot open $path")
                            zip.closeEntry()
                        }
                    }
            }
            add(directory, "")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(output).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (modifiedMillis <= 0) {
            // Some SAF providers do not expose timestamps. Use the snapshot
            // time so changed content is not permanently treated as older.
            modifiedMillis = System.currentTimeMillis()
        }
        return PreparedArchive(
            file = output,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            modifiedUnix = modifiedMillis / 1000,
        )
    }
}
