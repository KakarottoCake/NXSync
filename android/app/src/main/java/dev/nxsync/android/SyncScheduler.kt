package dev.nxsync.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {
    const val WORK_NAME_MANUAL = "nxsync-manual-sync"
    const val WORK_NAME_PERIODIC = "eden-save-sync"

    private val connected = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedule(context: Context) {
        val periodic = PeriodicWorkRequestBuilder<SaveSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(connected)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
        syncNow(context)
    }

    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SaveSyncWorker>()
            .setConstraints(connected)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_MANUAL,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
