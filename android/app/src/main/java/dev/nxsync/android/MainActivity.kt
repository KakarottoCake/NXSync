package dev.nxsync.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NXSyncScreen() }
    }

    @Composable
    private fun NXSyncScreen() {
        val preferences = getSharedPreferences("nxsync", MODE_PRIVATE)
        var directory by remember { mutableStateOf(preferences.getString("eden_tree_uri", null)) }
        var connected by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("Idle") }

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
                SyncScheduler.schedule(this)
                status = "Sync scheduled"
            }
        }
        val authorizationResolution = rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            connected = result.resultCode == Activity.RESULT_OK &&
                GoogleAuthorization.finishResolution(this, result.data)
            status = if (connected) "Idle" else "Google authorization cancelled"
            if (connected && directory != null) SyncScheduler.schedule(this)
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
                    Spacer(Modifier.height(22.dp))
                    Button(
                        onClick = {
                            GoogleAuthorization.connect(
                                this@MainActivity,
                                authorizationResolution,
                                onConnected = {
                                    connected = true
                                    status = "Idle"
                                    if (directory != null) {
                                        SyncScheduler.schedule(this@MainActivity)
                                    }
                                },
                                onError = { status = it.message ?: "Authorization failed" },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (connected) "Google Drive connected" else "Connect Google Drive") }
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
                                status = "Sync scheduled"
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Sync now") }
                    }
                }
            }
        }
    }
}
