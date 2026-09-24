package com.windergoodlife.smsrelay.sync

import com.windergoodlife.smsrelay.diagnostics.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Event-driven capture only. No polling or broad drain, and no cancellation of an active page. */
class SmsInboxChangeMonitor(
    scope: CoroutineScope,
    private val capture: suspend () -> InboxSyncManager.RecoveryBatch,
    private val logs: ConnectionLogSink,
    private val coalesce: suspend () -> Unit = { delay(300L) }
) {
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val job = scope.launch {
        for (signal in changes) {
            coalesce()
            // Changes before this snapshot are covered by this page; changes during it stay queued.
            changes.tryReceive()
            try {
                do {
                    logs.record(ConnectionDiagnostic(ConnectionLogStage.SMS_PROVIDER, ConnectionLogOutcome.STARTED))
                    val page = capture()
                    logs.record(ConnectionDiagnostic(ConnectionLogStage.SMS_PROVIDER, ConnectionLogOutcome.SUCCEEDED,
                        count = page.inserted))
                    if (page.hasMore) coalesce()
                } while (page.hasMore)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val classified = failure as? InboxRecoveryException
                logs.record(ConnectionDiagnostic(ConnectionLogStage.SMS_PROVIDER, ConnectionLogOutcome.FAILED,
                    reason = classified?.reason ?: ConnectionFailureReason.LOCAL_STORAGE,
                    retryable = classified?.retryable ?: true))
                // Normal periodic recovery remains the durable retry fallback; no tight failure loop.
            }
        }
    }

    fun changed() { changes.trySend(Unit) }

    fun close() { changes.close(); job.cancel() }
}
