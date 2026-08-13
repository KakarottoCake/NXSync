package dev.nxsync.android

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

private const val CLIENT_ID = "99491436094-o26b6pcetir1hdnkrm2fjgeuhnpojoqk.apps.googleusercontent.com"
private const val REDIRECT_URI = "http://localhost"
private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
private const val SECRET_B64 = "R09DU1BYLURfWmFjNENwSDcxWHAyRHJnLW1jUW51Q1pIMQ=="

object GoogleAuthorization {

    private fun getClientSecret(): String {
        return try {
            String(Base64.decode(SECRET_B64, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            ""
        }
    }

    fun getAuthUrl(): String {
        return "https://accounts.google.com/o/oauth2/v2/auth?" +
            "client_id=" + URLEncoder.encode(CLIENT_ID, "UTF-8") +
            "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8") +
            "&response_type=code" +
            "&scope=" + URLEncoder.encode(SCOPE, "UTF-8") +
            "&access_type=offline" +
            "&prompt=consent"
    }

    fun isConnected(context: Context): Boolean {
        val prefs = context.getSharedPreferences("nxsync", Context.MODE_PRIVATE)
        return !prefs.getString("google_refresh_token", null).isNullOrEmpty()
    }

    suspend fun exchangeCodeForRefreshToken(context: Context, code: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URI("https://oauth2.googleapis.com/token").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val body = "client_id=" + URLEncoder.encode(CLIENT_ID, "UTF-8") +
                "&client_secret=" + URLEncoder.encode(getClientSecret(), "UTF-8") +
                "&code=" + URLEncoder.encode(code, "UTF-8") +
                "&grant_type=authorization_code" +
                "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8")

            conn.outputStream.use { it.write(body.toByteArray()) }

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            if (responseCode in 200..299) {
                val json = JSONObject(text)
                val refreshToken = json.optString("refresh_token", "")
                val accessToken = json.optString("access_token", "")

                if (refreshToken.isNotEmpty()) {
                    val prefs = context.getSharedPreferences("nxsync", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("google_refresh_token", refreshToken)
                        .putString("google_access_token", accessToken)
                        .apply()
                    return@withContext true
                }
            }
            return@withContext false
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    fun accessToken(context: Context): String? {
        val prefs = context.getSharedPreferences("nxsync", Context.MODE_PRIVATE)
        val refreshToken = prefs.getString("google_refresh_token", null) ?: return null

        return try {
            val url = URI("https://oauth2.googleapis.com/token").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val body = "client_id=" + URLEncoder.encode(CLIENT_ID, "UTF-8") +
                "&client_secret=" + URLEncoder.encode(getClientSecret(), "UTF-8") +
                "&refresh_token=" + URLEncoder.encode(refreshToken, "UTF-8") +
                "&grant_type=refresh_token"

            conn.outputStream.use { it.write(body.toByteArray()) }

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            if (responseCode in 200..299) {
                val json = JSONObject(text)
                val token = json.optString("access_token", "")
                if (token.isNotEmpty()) token else null
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
