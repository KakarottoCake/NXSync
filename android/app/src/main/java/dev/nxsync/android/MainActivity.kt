package dev.nxsync.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var isConnectedState = mutableStateOf(false)
    private var statusState = mutableStateOf("Idle")
    private var showAuthDialogState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isConnectedState.value = GoogleAuthorization.isConnected(this)
        setContent { NXSyncScreen() }
    }

    @Composable
    private fun NXSyncScreen() {
        val preferences = getSharedPreferences("nxsync", MODE_PRIVATE)
        var directory by remember { mutableStateOf(preferences.getString("eden_tree_uri", null)) }
        var connected by isConnectedState
        var status by statusState
        var showAuthDialog by showAuthDialogState

        val workInfos by WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(SyncScheduler.WORK_NAME_MANUAL)
            .observeAsState()

        val activeWork = workInfos?.firstOrNull()
        val progressData = activeWork?.progress
        val isWorkRunning = activeWork?.state == WorkInfo.State.RUNNING

        val currentProgress = progressData?.getInt("current", 0) ?: 0
        val totalProgress = progressData?.getInt("total", 0) ?: 0
        val workStatus = progressData?.getString("status")

        if (!workStatus.isNullOrEmpty() && isWorkRunning) {
            status = workStatus
        } else if (activeWork?.state == WorkInfo.State.SUCCEEDED && !workStatus.isNullOrEmpty()) {
            status = workStatus
        }

        val progressFraction = if (totalProgress > 0) currentProgress.toFloat() / totalProgress.toFloat() else 0f

        val folderPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri: Uri? ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                preferences.edit().putString("eden_tree_uri", uri.toString()).apply()
                directory = uri.toString()
                if (connected) {
                    SyncScheduler.schedule(this)
                    status = "Sync scheduled"
                }
            }
        }

        MaterialTheme {
            Surface(color = Color(0xFF0D121A), contentColor = Color(0xFFE7EDF7)) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("NXSync", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(28.dp))
                    Text(if (directory == null) "Eden folder: Not selected" else "Eden folder: Ready")
                    Text("Status: $status", color = Color(0xFF91A1B7))

                    if (isWorkRunning && totalProgress > 0) {
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = Color(0xFF4ADE80),
                            trackColor = Color(0xFF1E293B),
                        )
                    } else if (isWorkRunning) {
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = Color(0xFF4ADE80),
                            trackColor = Color(0xFF1E293B),
                        )
                    }

                    Spacer(Modifier.height(22.dp))
                    Button(
                        onClick = {
                            if (!connected) {
                                showAuthDialog = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (connected) "Google Drive Connected" else "Connect Google Drive") }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { folderPicker.launch(null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Choose Eden save folder") }
                    if (directory != null && connected) {
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                SyncScheduler.syncNow(this@MainActivity)
                                status = "Starting sync..."
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Sync now") }
                    }
                }

                if (showAuthDialog) {
                    Dialog(
                        onDismissRequest = { showAuthDialog = false },
                        properties = DialogProperties(usePlatformDefaultWidth = false),
                    ) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            AndroidView(
                                factory = { context ->
                                    WebView(context).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.userAgentString =
                                            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

                                        webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(
                                                view: WebView?,
                                                request: WebResourceRequest?,
                                            ): Boolean {
                                                val url = request?.url?.toString() ?: ""
                                                if (url.contains("code=")) {
                                                    val uri = Uri.parse(url)
                                                    val code = uri.getQueryParameter("code")
                                                    if (!code.isNullOrEmpty()) {
                                                        showAuthDialog = false
                                                        statusState.value = "Exchanging token..."
                                                        lifecycleScope.launch {
                                                            val success = GoogleAuthorization.exchangeCodeForRefreshToken(
                                                                this@MainActivity,
                                                                code,
                                                            )
                                                            if (success) {
                                                                isConnectedState.value = true
                                                                statusState.value = "Google Drive Connected!"
                                                                Toast.makeText(
                                                                    this@MainActivity,
                                                                    "Google Drive Connected Successfully!",
                                                                    Toast.LENGTH_SHORT,
                                                                ).show()
                                                                if (directory != null) {
                                                                    SyncScheduler.schedule(this@MainActivity)
                                                                }
                                                            } else {
                                                                statusState.value = "Token exchange failed"
                                                                Toast.makeText(
                                                                    this@MainActivity,
                                                                    "Authorization Failed",
                                                                    Toast.LENGTH_LONG,
                                                                ).show()
                                                            }
                                                        }
                                                        return true
                                                    }
                                                }
                                                return super.shouldOverrideUrlLoading(view, request)
                                            }
                                        }
                                        loadUrl(GoogleAuthorization.getAuthUrl())
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}
