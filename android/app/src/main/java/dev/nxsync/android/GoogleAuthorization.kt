package dev.nxsync.android

import android.content.Context
import android.content.Intent
import android.net.Uri
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

object GoogleAuthorization {

    fun getClientId(context: Context): String {
        val prefs = context.getSharedPreferences("nxsync", Context.MODE_PRIVATE)
        return prefs.getString("google_client_id", "") ?: ""
    }

    fun getClientSecret(context: Context): String {
        val prefs = context.getSharedPreferences("nxsync", Context.MODE_PRIVATE)
        return prefs.getString("google_client_secret", "") ?: ""
    }

    fun getFolderId(context: Context): String {
        val prefs = context.getSharedPreferences("nxsync", Context.MODE_PRIVATE)
        return prefs.getString("google_folder_id", "") ?: ""
    }

    fun saveConfig(context: Context, clientId: String, clientSecret: String, folderId: String) {
        val prefs = context.getSharedPreferences("nxsync", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("google_client_id", clientId.trim())
            .putString("google_client_secret", clientSecret.trim())
            .putString("google_folder_id", folderId.trim())
            .apply()
    }

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

    private var activeServer: ServerSocket? = null

    suspend fun startLoopbackAuth(context: Context, onResult: (Boolean, String?) -> Unit) = withContext(Dispatchers.IO) {
        val clientId = getClientId(context)
        if (clientId.isEmpty()) {
            withContext(Dispatchers.Main) { onResult(false, "Please enter your Client ID first") }
            return@withContext
        }

        try {
            activeServer?.close()
            val loopback = InetAddress.getByName("127.0.0.1")
            val server = ServerSocket(0, 50, loopback)
            activeServer = server
            val port = server.localPort
            val redirectUri = "http://127.0.0.1:$port"

            val scope = "https://www.googleapis.com/auth/drive.file"
            val authUrl = "https://accounts.google.com/o/oauth2/v2/auth?" +
                "client_id=" + URLEncoder.encode(clientId, "UTF-8") +
                "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8") +
                "&response_type=code" +
                "&scope=" + URLEncoder.encode(scope, "UTF-8") +
                "&access_type=offline" +
                "&prompt=consent"

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            server.soTimeout = 180_000 // 3 min timeout
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
                val (success, errorMsg) = exchangeCode(context, authCode, redirectUri)
                withContext(Dispatchers.Main) { onResult(success, errorMsg) }
            } else {
                withContext(Dispatchers.Main) { onResult(false, "No code received from browser callback") }
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

    suspend fun exchangeCode(context: Context, code: String, redirectUri: String): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val clientId = getClientId(context)
        val clientSecret = getClientSecret(context)

        try {
            val url = URI("https://oauth2.googleapis.com/token").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            var body = "client_id=" + URLEncoder.encode(clientId, "UTF-8") +
                "&code=" + URLEncoder.encode(code.trim(), "UTF-8") +
                "&grant_type=authorization_code" +
                "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8")
            if (clientSecret.isNotEmpty()) {
                body += "&client_secret=" + URLEncoder.encode(clientSecret, "UTF-8")
            }

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
                    return@withContext Pair(true, null)
                }
            }
            return@withContext Pair(false, "Google HTTP $responseCode: $text")
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Pair(false, e.message ?: "Token exchange failed")
        }
    }

    suspend fun exchangeManualCodeOrToken(context: Context, input: String): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return@withContext Pair(false, "Input is empty")

        if (trimmed.startsWith("1/") || trimmed.length > 50) {
            if (saveRefreshToken(context, trimmed)) {
                val access = accessToken(context)
                if (!access.isNullOrEmpty()) {
                    return@withContext Pair(true, null)
                }
            }
        }

        val redirectUris = listOf("http://localhost", "http://127.0.0.1", "nxsync://oauth")
        var lastError = "Invalid code or token"
        for (redirectUri in redirectUris) {
            val (ok, err) = exchangeCode(context, trimmed, redirectUri)
            if (ok) return@withContext Pair(true, null)
            if (err != null) lastError = err
        }
        return@withContext Pair(false, lastError)
    }

    fun accessToken(context: Context): String? {
        val clientId = getClientId(context)
        val clientSecret = getClientSecret(context)
        val prefs = context.getSharedPreferences("nxsync", Context.MODE_PRIVATE)
        val refreshToken = prefs.getString("google_refresh_token", null) ?: return null

        return try {
            val url = URI("https://oauth2.googleapis.com/token").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            var body = "client_id=" + URLEncoder.encode(clientId, "UTF-8") +
                "&refresh_token=" + URLEncoder.encode(refreshToken, "UTF-8") +
                "&grant_type=refresh_token"
            if (clientSecret.isNotEmpty()) {
                body += "&client_secret=" + URLEncoder.encode(clientSecret, "UTF-8")
            }

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
