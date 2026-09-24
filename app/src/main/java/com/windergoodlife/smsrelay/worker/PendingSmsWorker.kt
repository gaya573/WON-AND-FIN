package com.windergoodlife.smsrelay.worker

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OutOfQuotaPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.windergoodlife.smsrelay.SmsRelayApp
import com.windergoodlife.smsrelay.sync.RelayRecoveryRunner
import com.windergoodlife.smsrelay.sync.SyncPhase
import com.windergoodlife.smsrelay.diagnostics.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class PendingSmsWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = SmsRelayApp.get()
        if (!app.tokenStore.isConfigured()) return Result.success()
        if (!app.hasSmsPermission()) {
            app.syncState.update(SyncPhase.PERMISSION)
            app.connectionLogs.record(ConnectionDiagnostic(ConnectionLogStage.INBOX, ConnectionLogOutcome.FAILED,
                reason = ConnectionFailureReason.PERMISSION_DENIED, retryable = false))
            return Result.success()
        }
        return try {
            val runner = RelayRecoveryRunner(
                { deadline -> app.inboxSync.recoverBatch(deadlineNanos = deadline) },
                { deadline -> app.repository.drainPending(deadlineNanos = deadline) },
                app.syncState::update, app.connectionLogs)
            when (runner.run()) {
                RelayRecoveryRunner.Decision.COMPLETE -> Result.success()
                RelayRecoveryRunner.Decision.RETRY -> Result.retry()
                RelayRecoveryRunner.Decision.CONTINUE -> {
                    // Await persistence before declaring this page finished. KEEP would lose it
                    // because the current unique work is still RUNNING.
                    withContext(Dispatchers.IO) {
                        schedule(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE).result.get()
                    }
                    Result.success()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            app.syncState.update(SyncPhase.RETRYING)
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE = "pending-sms-flush"

        fun enqueue(context: Context, connectionConfirmed: Boolean = false,
            manager: WorkManager = WorkManager.getInstance(context)) {
            // Preserve a connect/network wake even if the running worker has already decided to
            // stop (e.g. an old authentication failure). WorkManager serializes this chain.
            // Only a successfully verified manual connection resets a stale failure backoff.
            // Normal start/network/heartbeat wakes and page continuations keep their follow-ups.
            schedule(context, if (connectionConfirmed) ExistingWorkPolicy.REPLACE
                else ExistingWorkPolicy.APPEND_OR_REPLACE, manager)
        }

        internal fun schedule(context: Context, policy: ExistingWorkPolicy,
            manager: WorkManager = WorkManager.getInstance(context)): androidx.work.Operation {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<PendingSmsWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .apply {
                    if (supportsExpeditedUpload(Build.VERSION.SDK_INT))
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
                .build()
            return manager.enqueueUniqueWork(
                UNIQUE,
                policy,
                request
            )
        }
    }
}
