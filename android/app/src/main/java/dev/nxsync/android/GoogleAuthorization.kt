package dev.nxsync.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder

private const val CLIENT_ID = "99491436094-o26b6pcetir1hdnkrm2fjgeuhnpojoqk.apps.googleusercontent.com"
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

    private var activeServer: ServerSocket? = null

    fun saveRefreshToken(context: Context, refreshToken: String): Boolean {
        val token = refreshToken.trim()
        if (token.isEmpty()) return false
        val prefs = context.getSharedPreferences("nxsync", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("google_refresh_token", token)
            .remove("google_access_token")
            .apply()
        return true
    }

    suspend fun exchangeManualCodeOrToken(context: Context, input: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return@withContext false

        // If user pasted a refresh token directly (e.g. starting with 1// or 1/ or long token)
        if (trimmed.startsWith("1/") || trimmed.length > 50) {
            if (saveRefreshToken(context, trimmed)) {
                val access = accessToken(context)
                if (!access.isNullOrEmpty()) {
                    return@withContext true
                }
            }
        }

        // Otherwise try exchanging as authorization code with standard redirect URIs
        val redirectUris = listOf("http://localhost", "http://127.0.0.1", "nxsync://oauth")
        for (redirectUri in redirectUris) {
            if (exchangeCode(context, trimmed, redirectUri)) {
                return@withContext true
            }
        }
        return@withContext false
    }

    suspend fun startLoopbackAuth(context: Context, onResult: (Boolean, String?) -> Unit) = withContext(Dispatchers.IO) {
        try {
            activeServer?.close()
            val loopback = InetAddress.getByName("127.0.0.1")
            val server = ServerSocket(0, 50, loopback)
            activeServer = server
            val port = server.localPort
            val redirectUri = "http://127.0.0.1:$port"

            val authUrl = "https://accounts.google.com/o/oauth2/v2/auth?" +
                "client_id=" + URLEncoder.encode(CLIENT_ID, "UTF-8") +
                "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8") +
                "&response_type=code" +
                "&scope=" + URLEncoder.encode(SCOPE, "UTF-8") +
                "&access_type=offline" +
                "&prompt=consent"

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            server.soTimeout = 180_000
            val client = server.accept()
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine().orEmpty()

            var authCode: String? = null
            if (requestLine.contains("code=")) {
                val path = requestLine.split(" ").getOrNull(1).orEmpty()
                val dummyUri = Uri.parse("http://127.0.0.1$path")
                authCode = dummyUri.getQueryParameter("code")
            }

            val writer = PrintWriter(client.getOutputStream())
            writer.println("HTTP/1.1 200 OK")
            writer.println("Content-Type: text/html; charset=UTF-8")
            writer.println()
            writer.println("<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width, initial-scale=1'></head>" +
                "<body style='background:#0d121a;color:#4ade80;font-family:sans-serif;text-align:center;padding:40px;'>" +
                "<h1 style='font-size:26px;'>NXSync Connected!</h1>" +
                "<p style='color:#e7edf7;font-size:16px;'>Google Drive authorized successfully.</p>" +
                "<p style='color:#91a1b7;'>Close this tab and return to NXSync.</p>" +
                "</body></html>")
            writer.flush()
            client.close()
            server.close()

            if (!authCode.isNullOrEmpty()) {
                val success = exchangeCode(context, authCode, redirectUri)
                withContext(Dispatchers.Main) { onResult(success, if (success) null else "Token exchange rejected by Google") }
            } else {
                withContext(Dispatchers.Main) { onResult(false, "Authorization code missing from callback") }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) { onResult(false, e.message ?: "Authentication failed") }
        }
    }

    fun isConnected(context: Context): Boolean {
        val prefs = context.getSharedPreferences("nxsync", Context.MODE_PRIVATE)
        return !prefs.getString("google_refresh_token", null).isNullOrEmpty()
    }

    suspend fun exchangeCode(context: Context, code: String, redirectUri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URI("https://oauth2.googleapis.com/token").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val body = "client_id=" + URLEncoder.encode(CLIENT_ID, "UTF-8") +
                "&client_secret=" + URLEncoder.encode(getClientSecret(), "UTF-8") +
                "&code=" + URLEncoder.encode(code.trim(), "UTF-8") +
                "&grant_type=authorization_code" +
                "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8")

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
