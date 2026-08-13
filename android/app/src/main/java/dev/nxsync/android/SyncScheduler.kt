package dev.nxsync.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private val connected = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedule(context: Context) {
        val periodic = PeriodicWorkRequestBuilder<SaveSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(connected)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "eden-save-sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
        syncNow(context)
    }

    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SaveSyncWorker>()
            .setConstraints(connected)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}

