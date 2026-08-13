package dev.nxsync.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val API = "https://www.googleapis.com/drive/v3"
private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
private const val FOLDER_ID = "1-6xIz7Jqfu4lb4Ccmq_Eoy-4PIB3zHVW"

data class RemoteSave(
    val id: String,
    val sha256: String,
    val modifiedUnix: Long,
)

class DriveGateway(private val accessToken: String) {
    fun push(titleId: String, archive: PreparedArchive): Boolean {
        val name = "$titleId.zip"
        val remote = find(name)
        if (remote?.sha256.equals(archive.sha256, ignoreCase = true)) return false
        if (remote != null && archive.modifiedUnix <= remote.modifiedUnix) return false

        val properties = JSONObject()
            .put("nxsync_sha256", archive.sha256)
            .put("nxsync_source_modified_unix", archive.modifiedUnix.toString())
            .put("nxsync_title_id", titleId)
        val metadata = JSONObject()
            .put("name", name)
            .put("appProperties", properties)
        
        if (remote == null && FOLDER_ID.isNotEmpty()) {
            metadata.put("parents", JSONArray().put(FOLDER_ID))
        }

        val endpoint = if (remote == null) {
            "$UPLOAD/files?uploadType=resumable"
        } else {
            "$UPLOAD/files/${remote.id}?uploadType=resumable"
        }
        val response = request(
            endpoint,
            if (remote == null) "POST" else "PATCH",
            metadata.toString().toByteArray(),
            "application/json; charset=UTF-8",
        )
        val session = response.getLocationHeader() ?: error("Drive did not return an upload session location header")
        uploadFile(session, archive.file)
        return true
    }

    private fun find(name: String): RemoteSave? {
        var query = "name = '$name' and trashed = false"
        if (FOLDER_ID.isNotEmpty()) {
            query += " and '$FOLDER_ID' in parents"
        }
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val response = request(
            "$API/files?q=$encoded&pageSize=1&spaces=drive" +
                "&fields=files(id,appProperties)",
            "GET",
        )
        val files = JSONObject(response.body).optJSONArray("files") ?: JSONArray()
        if (files.length() == 0) return null
        val file = files.getJSONObject(0)
        val properties = file.optJSONObject("appProperties") ?: JSONObject()
        return RemoteSave(
            id = file.getString("id"),
            sha256 = properties.optString("nxsync_sha256"),
            modifiedUnix = properties.optLong("nxsync_source_modified_unix"),
        )
    }

    private data class Response(
        val body: String,
        val headers: Map<String, String>,
    ) {
        fun getLocationHeader(): String? {
            return headers.entries
                .firstOrNull { it.key.equals("Location", ignoreCase = true) }
                ?.value
        }
    }

    private fun request(
        endpoint: String,
        method: String,
        body: ByteArray? = null,
        contentType: String? = null,
    ): Response {
        val connection = URI(endpoint).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 300_000
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        if (body != null) {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", contentType)
            connection.outputStream.use { it.write(body) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) error("Google Drive HTTP $status: $responseBody")
        val headers = connection.headerFields
            .filterKeys { it != null }
            .mapValues { it.value.firstOrNull().orEmpty() }
        connection.disconnect()
        return Response(responseBody, headers)
    }

    private fun uploadFile(endpoint: String, file: File) {
        val connection = URI(endpoint).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "PUT"
        connection.connectTimeout = 15_000
        connection.readTimeout = 300_000
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(file.length())
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.setRequestProperty("Content-Type", "application/zip")
        file.inputStream().use { input ->
            connection.outputStream.use { output -> input.copyTo(output) }
        }
        val status = connection.responseCode
        if (status !in 200..299) {
            val message = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            error("Google Drive HTTP $status: $message")
        }
        connection.inputStream.close()
        connection.disconnect()
    }
}
