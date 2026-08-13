package dev.nxsync.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var isConnectedState = mutableStateOf(false)
    private var statusState = mutableStateOf("Idle")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isConnectedState.value = GoogleAuthorization.isConnected(this)
        setContent { NXSyncScreen() }
    }

    private fun startGoogleAuth() {
        statusState.value = "Waiting for Google login in browser..."
        lifecycleScope.launch {
            GoogleAuthorization.startLoopbackAuth(this@MainActivity) { success, errorDetail ->
                if (success) {
                    isConnectedState.value = true
                    statusState.value = "Google Drive Connected!"
                    Toast.makeText(this@MainActivity, "Google Drive Connected Successfully!", Toast.LENGTH_SHORT).show()
                    val prefs = getSharedPreferences("nxsync", MODE_PRIVATE)
                    if (prefs.getString("eden_tree_uri", null) != null) {
                        SyncScheduler.schedule(this@MainActivity)
                    }
                } else {
                    val msg = "Auth failed: ${errorDetail ?: "Cancelled"}"
                    statusState.value = msg
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Composable
    private fun NXSyncScreen() {
        val preferences = getSharedPreferences("nxsync", MODE_PRIVATE)
        var directory by remember { mutableStateOf(preferences.getString("eden_tree_uri", null)) }
        var connected by isConnectedState
        var status by statusState
        var manualTokenInput by remember { mutableStateOf("") }

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
                    Spacer(Modifier.height(20.dp))
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

                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = {
                            if (!connected) {
                                startGoogleAuth()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (connected) "Google Drive Connected" else "1. Auto Connect via Browser") }

                    if (!connected) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Or paste Auth Code / Refresh Token:",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF91A1B7),
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = manualTokenInput,
                            onValueChange = { manualTokenInput = it },
                            placeholder = { Text("Paste code or 1//... token") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF4ADE80),
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedTextColor = Color(0xFFE7EDF7),
                                unfocusedTextColor = Color(0xFFE7EDF7),
                            ),
                        )
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = {
                                if (manualTokenInput.isNotBlank()) {
                                    status = "Verifying token..."
                                    lifecycleScope.launch {
                                        val (ok, err) = GoogleAuthorization.exchangeManualCodeOrToken(this@MainActivity, manualTokenInput)
                                        if (ok) {
                                            isConnectedState.value = true
                                            status = "Google Drive Connected!"
                                            Toast.makeText(this@MainActivity, "Connected to Google Drive!", Toast.LENGTH_SHORT).show()
                                            if (directory != null) {
                                                SyncScheduler.schedule(this@MainActivity)
                                            }
                                        } else {
                                            val msg = err ?: "Invalid token or code"
                                            status = msg
                                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("2. Submit Token / Code") }
                    }

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { folderPicker.launch(null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Choose Eden save folder") }
                    if (directory != null && connected) {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                SyncScheduler.syncNow(this@MainActivity)
                                status = "Starting sync..."
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Sync now") }
                    }
                }
            }
        }
    }
}
