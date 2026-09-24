package com.windergoodlife.smsrelay.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.windergoodlife.smsrelay.SmsRelayApp
import java.util.concurrent.TimeUnit

class HeartbeatWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = SmsRelayApp.get()
        // Periodic fallback also recovers provider-only messages after a killed process.
        if (app.tokenStore.isConfigured()) PendingSmsWorker.enqueue(applicationContext)
        val smsOk = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val unrestricted = pm.isIgnoringBatteryOptimizations(applicationContext.packageName)
        val ok = app.repository.sendHeartbeat(smsOk, unrestricted)
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE = "sms-relay-heartbeat"

        fun enqueuePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            // Android minimum periodic interval is 15 minutes; exact cadence is not guaranteed.
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
