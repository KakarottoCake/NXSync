package dev.nxsync.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks

private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

object GoogleAuthorization {
    private fun request(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()

    fun connect(
        activity: Activity,
        resolutionLauncher: ActivityResultLauncher<IntentSenderRequest>,
        onConnected: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        Identity.getAuthorizationClient(activity)
            .authorize(request())
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    resolutionLauncher.launch(
                        IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build(),
                    )
                } else {
                    onConnected()
                }
            }
            .addOnFailureListener(onError)
    }

    fun finishResolution(context: Context, data: Intent?): Boolean {
        if (data == null) return false
        val result = Identity.getAuthorizationClient(context)
            .getAuthorizationResultFromIntent(data)
        return !result.accessToken.isNullOrBlank()
    }

    // Background authorization succeeds silently after the user has granted
    // drive.file. A required resolution is left for the foreground UI.
    fun accessToken(context: Context): String? {
        val result = Tasks.await(
            Identity.getAuthorizationClient(context).authorize(request()),
        )
        return if (result.hasResolution()) null else result.accessToken
    }
}

