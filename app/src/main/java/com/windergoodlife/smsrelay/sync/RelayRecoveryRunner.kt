package com.windergoodlife.smsrelay.sync

import com.windergoodlife.smsrelay.diagnostics.*
import com.windergoodlife.smsrelay.repository.SmsRepository
import kotlinx.coroutines.CancellationException

/** One bounded pass; scheduling is deliberately outside so a successful continuation is not retry. */
class RelayRecoveryRunner(
    private val recover: suspend (Long) -> InboxSyncManager.RecoveryBatch,
    private val drain: suspend (Long) -> SmsRepository.DrainResult,
    private val state: (SyncPhase) -> Unit,
    private val logs: ConnectionLogSink,
    private val nanoTime: () -> Long = System::nanoTime
) {
    enum class Decision { COMPLETE, CONTINUE, RETRY }

    suspend fun run(): Decision {
        val deadline = nanoTime() + 60_000_000_000L
        var recovery: InboxSyncManager.RecoveryBatch? = null
        var recoveryFailure: InboxRecoveryException? = null
        state(SyncPhase.RECOVERING)
        logs.record(ConnectionDiagnostic(ConnectionLogStage.INBOX, ConnectionLogOutcome.STARTED))
        try {
            recovery = recover(deadline)
            recoveryFailure = recovery.failure
            logs.record(ConnectionDiagnostic(ConnectionLogStage.INBOX,
                if (recoveryFailure == null) ConnectionLogOutcome.SUCCEEDED else ConnectionLogOutcome.FAILED,
                count = recovery.inserted, reason = recoveryFailure?.reason, retryable = recoveryFailure?.retryable))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            recoveryFailure = failure as? InboxRecoveryException
                ?: InboxRecoveryException(ConnectionFailureReason.LOCAL_STORAGE, true)
            logs.record(ConnectionDiagnostic(ConnectionLogStage.INBOX, ConnectionLogOutcome.FAILED,
                reason = recoveryFailure.reason, retryable = recoveryFailure.retryable))
        }

        state(SyncPhase.SENDING)
        val result = try { drain(deadline) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            logs.record(ConnectionDiagnostic(ConnectionLogStage.UPLOAD, ConnectionLogOutcome.FAILED,
                reason = ConnectionFailureReason.LOCAL_STORAGE, retryable = true))
            state(SyncPhase.RETRYING)
            return Decision.RETRY
        }
        logs.record(ConnectionDiagnostic(ConnectionLogStage.SYNC,
            if (result.retryableFailure || result.blocked || result.permanentFailures > 0) ConnectionLogOutcome.FAILED else ConnectionLogOutcome.SUCCEEDED,
            count = result.sent, retryable = result.retryableFailure))
        val phase = when {
            recoveryFailure?.reason == ConnectionFailureReason.PROVIDER_RESET -> SyncPhase.PROVIDER_RESET
            recoveryFailure?.reason == ConnectionFailureReason.PERMISSION_DENIED -> SyncPhase.PERMISSION
            result.blocked || result.permanentFailures > 0 -> SyncPhase.BLOCKED
            recoveryFailure != null || result.retryableFailure -> SyncPhase.RETRYING
            recovery?.hasMore == true || result.remaining > 0 -> SyncPhase.WAITING
            else -> SyncPhase.CURRENT
        }
        state(phase)
        return when {
            // Finish pages from healthy independent providers before backing off an unfinished MMS.
            recovery?.hasMore == true && !result.blocked && !result.retryableFailure -> Decision.CONTINUE
            recoveryFailure?.retryable == true || result.retryableFailure -> Decision.RETRY
            result.blocked -> Decision.COMPLETE
            // Continue the Room backlog even if a provider reset requires operator attention.
            result.remaining > 0 || (recoveryFailure == null && recovery?.hasMore == true) -> Decision.CONTINUE
            else -> Decision.COMPLETE
        }
    }
}
