package com.windergoodlife.smsrelay.sync

import com.windergoodlife.smsrelay.diagnostics.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SmsInboxChangeMonitorTest {
    @Test fun `bursts coalesce without polling and an event during capture gets a followup`() = runBlocking<Unit> {
        var captures = 0
        val firstCapture = CompletableDeferred<Unit>()
        val firstFinished = CompletableDeferred<Unit>()
        val secondFinished = CompletableDeferred<Unit>()
        val monitor = SmsInboxChangeMonitor(this, {
            captures++
            if (captures == 1) { firstCapture.complete(Unit); firstFinished.await() }
            else secondFinished.complete(Unit)
            InboxSyncManager.RecoveryBatch(1, 1, false)
        }, ConnectionLogSink { }, coalesce = { yield() })
        yield()
        assertEquals(0, captures, "an idle observer never polls the provider")
        repeat(100) { monitor.changed() }
        firstCapture.await()
        assertEquals(1, captures)
        repeat(100) { monitor.changed() }
        assertEquals(1, captures, "an in-flight capture is not cancelled or run concurrently")
        firstFinished.complete(Unit)
        secondFinished.await()
        repeat(3) { yield() }
        assertEquals(2, captures)
        monitor.close()
    }

    @Test fun `bounded remaining pages continue without another provider notification`() = runBlocking<Unit> {
        var captures = 0
        val finished = CompletableDeferred<Unit>()
        val monitor = SmsInboxChangeMonitor(this, {
            captures++
            if (captures == 3) finished.complete(Unit)
            InboxSyncManager.RecoveryBatch(50, 50, captures < 3)
        }, ConnectionLogSink { }, coalesce = { yield() })
        monitor.changed()
        finished.await()
        repeat(3) { yield() }
        assertEquals(3, captures)
        monitor.close()
    }

    @Test fun `failed capture logs a fixed reason and waits for another event instead of spinning`() = runBlocking<Unit> {
        val events = mutableListOf<ConnectionDiagnostic>()
        var captures = 0
        val monitor = SmsInboxChangeMonitor(this, {
            captures++
            throw IllegalStateException("private body and token")
        }, ConnectionLogSink(events::add), coalesce = { yield() })
        monitor.changed()
        repeat(5) { yield() }
        assertEquals(1, captures)
        assertEquals(ConnectionFailureReason.LOCAL_STORAGE, events.last().reason)
        assertFalse(events.toString().contains("private"))
        monitor.changed()
        repeat(5) { yield() }
        assertEquals(2, captures)
        monitor.close()
    }
}
