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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        val clientId = GoogleAuthorization.getClientId(this)
        if (clientId.isEmpty()) {
            Toast.makeText(this, "Please enter and save your Client ID first!", Toast.LENGTH_LONG).show()
            statusState.value = "Client ID missing"
            return
        }
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

        var clientIdInput by remember { mutableStateOf(GoogleAuthorization.getClientId(this)) }
        var clientSecretInput by remember { mutableStateOf(GoogleAuthorization.getClientSecret(this)) }
        var folderIdInput by remember { mutableStateOf(GoogleAuthorization.getFolderId(this)) }
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

        val scrollState = rememberScrollState()

        MaterialTheme {
            Surface(color = Color(0xFF0D121A), contentColor = Color(0xFFE7EDF7)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.Top,
                ) {
                    Text("NXSync", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("Cross-Platform Switch Save Sync", fontSize = 14.sp, color = Color(0xFF91A1B7))
                    Spacer(Modifier.height(20.dp))

                    Text("1. Google API Credentials Setup", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color(0xFF4ADE80))
                    Spacer(Modifier.height(6.dp))

                    OutlinedTextField(
                        value = clientIdInput,
                        onValueChange = { clientIdInput = it },
                        label = { Text("Client ID") },
                        placeholder = { Text("xxxx.apps.googleusercontent.com") },
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

                    OutlinedTextField(
                        value = clientSecretInput,
                        onValueChange = { clientSecretInput = it },
                        label = { Text("Client Secret (Optional for Public Client)") },
                        placeholder = { Text("GOCSPX-...") },
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

                    OutlinedTextField(
                        value = folderIdInput,
                        onValueChange = { folderIdInput = it },
                        label = { Text("Google Drive Folder ID (Optional)") },
                        placeholder = { Text("Folder ID string") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF4ADE80),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor = Color(0xFFE7EDF7),
                            unfocusedTextColor = Color(0xFFE7EDF7),
                        ),
                    )
                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = {
                            GoogleAuthorization.saveConfig(this@MainActivity, clientIdInput, clientSecretInput, folderIdInput)
                            Toast.makeText(this@MainActivity, "Credentials Configuration Saved!", Toast.LENGTH_SHORT).show()
                            status = "Configuration saved"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save Credentials Config") }

                    Spacer(Modifier.height(20.dp))
                    Text("2. Google Drive Authentication", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color(0xFF4ADE80))
                    Spacer(Modifier.height(6.dp))

                    Button(
                        onClick = {
                            if (!connected) {
                                startGoogleAuth()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (connected) "Google Drive Connected ✓" else "Connect via Browser") }

                    if (!connected) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = manualTokenInput,
                            onValueChange = { manualTokenInput = it },
                            label = { Text("Or Paste Refresh Token / Auth Code") },
                            placeholder = { Text("1//... or 4/...") },
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
                        ) { Text("Submit Token / Code") }
                    }

                    Spacer(Modifier.height(20.dp))
                    Text("3. Save Folder & Sync", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color(0xFF4ADE80))
                    Spacer(Modifier.height(6.dp))
                    Text(if (directory == null) "Eden folder: Not selected" else "Eden folder: Ready", fontSize = 14.sp)
                    Text("Status: $status", fontSize = 13.sp, color = Color(0xFF91A1B7))

                    if (isWorkRunning && totalProgress > 0) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = Color(0xFF4ADE80),
                            trackColor = Color(0xFF1E293B),
                        )
                    } else if (isWorkRunning) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = Color(0xFF4ADE80),
                            trackColor = Color(0xFF1E293B),
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { folderPicker.launch(null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Choose Eden save folder") }

                    if (directory != null && connected) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                SyncScheduler.syncNow(this@MainActivity)
                                status = "Starting sync..."
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Sync now") }
                    }
                    Spacer(Modifier.height(30.dp))
                }
            }
        }
    }
}
