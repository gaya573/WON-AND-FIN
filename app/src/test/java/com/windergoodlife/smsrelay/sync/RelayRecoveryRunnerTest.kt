package com.windergoodlife.smsrelay.sync

import com.windergoodlife.smsrelay.diagnostics.*
import com.windergoodlife.smsrelay.repository.SmsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RelayRecoveryRunnerTest {
    @Test fun `one invalid payload cannot strand remaining healthy messages or provider pages`() = runBlocking<Unit> {
        for ((remaining, more) in listOf(1 to false, 0 to true)) {
            val phases = mutableListOf<SyncPhase>()
            val runner = RelayRecoveryRunner({ InboxSyncManager.RecoveryBatch(250, 250, more) },
                { SmsRepository.DrainResult(249, remaining, false, false, permanentFailures = 1) },
                phases::add, ConnectionLogSink { })
            assertEquals(RelayRecoveryRunner.Decision.CONTINUE, runner.run())
            assertEquals(SyncPhase.BLOCKED, phases.last())
        }
    }
    @Test fun `healthy backlog continues without retry backoff and finished queue completes`() = runBlocking<Unit> {
        for ((remaining, more, expected) in listOf(
            Triple(1, false, RelayRecoveryRunner.Decision.CONTINUE),
            Triple(0, true, RelayRecoveryRunner.Decision.CONTINUE),
            Triple(0, false, RelayRecoveryRunner.Decision.COMPLETE))) {
            val phases = mutableListOf<SyncPhase>()
            val runner = RelayRecoveryRunner({ InboxSyncManager.RecoveryBatch(1, 1, more) },
                { SmsRepository.DrainResult(50, remaining, false, false) }, phases::add, ConnectionLogSink { })
            assertEquals(expected, runner.run())
            assertEquals(listOf(SyncPhase.RECOVERING, SyncPhase.SENDING), phases.take(2))
            assertFalse(phases.contains(SyncPhase.RETRYING))
        }
    }

    @Test fun `provider reset is visible while already persisted messages still upload`() = runBlocking<Unit> {
        var drained = false
        val phases = mutableListOf<SyncPhase>()
        val events = mutableListOf<ConnectionDiagnostic>()
        val runner = RelayRecoveryRunner({ throw InboxRecoveryException(ConnectionFailureReason.PROVIDER_RESET, false) },
            { drained = true; SmsRepository.DrainResult(4, 0, false, false) }, phases::add, ConnectionLogSink(events::add))
        assertEquals(RelayRecoveryRunner.Decision.COMPLETE, runner.run())
        assertTrue(drained)
        assertEquals(SyncPhase.PROVIDER_RESET, phases.last())
        assertTrue(events.any { it.reason == ConnectionFailureReason.PROVIDER_RESET && it.retryable == false })
    }

    @Test fun `storage failure is not hidden by a successful queue flush`() = runBlocking<Unit> {
        val phases = mutableListOf<SyncPhase>()
        val events = mutableListOf<ConnectionDiagnostic>()
        val runner = RelayRecoveryRunner({ throw IllegalStateException("private message body") },
            { SmsRepository.DrainResult(2, 0, false, false) }, phases::add, ConnectionLogSink(events::add))
        assertEquals(RelayRecoveryRunner.Decision.RETRY, runner.run())
        assertEquals(SyncPhase.RETRYING, phases.last())
        assertTrue(events.any { it.reason == ConnectionFailureReason.LOCAL_STORAGE })
        assertFalse(events.toString().contains("private message body"))
    }

    @Test fun `temporary upload failure retries but rejected authentication stops auto drain`() = runBlocking<Unit> {
        for (retryable in listOf(true, false)) {
            val phases = mutableListOf<SyncPhase>()
            val runner = RelayRecoveryRunner({ InboxSyncManager.RecoveryBatch(0, 0, false) },
                { SmsRepository.DrainResult(0, 25, retryable, !retryable) }, phases::add, ConnectionLogSink { })
            assertEquals(if (retryable) RelayRecoveryRunner.Decision.RETRY else RelayRecoveryRunner.Decision.COMPLETE, runner.run())
            assertEquals(if (retryable) SyncPhase.RETRYING else SyncPhase.BLOCKED, phases.last())
        }
    }

    @Test fun `cancellation propagates without a fake recovery failure or queue upload`() {
        var drained = false
        val events = mutableListOf<ConnectionDiagnostic>()
        val runner = RelayRecoveryRunner({ throw CancellationException() },
            { drained = true; SmsRepository.DrainResult(0, 0, false, false) }, {}, ConnectionLogSink(events::add))
        assertThrows(CancellationException::class.java) { runBlocking { runner.run() } }
        assertFalse(drained)
        assertTrue(events.none { it.outcome == ConnectionLogOutcome.FAILED })
    }
}
