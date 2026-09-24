package com.windergoodlife.smsrelay.sync

import com.windergoodlife.smsrelay.diagnostics.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MessageInboxSyncManagerTest {
    private class Fixture {
        val cursors = mutableMapOf<String, Long>()
        val saved = mutableSetOf<Pair<String, Long>>()
        val events = mutableListOf<ConnectionDiagnostic>()
        val pages = mutableMapOf<String, suspend (Long, Int) -> ProviderMessagePage>()
        val calls = mutableListOf<Triple<String, Long, Int>>()
        var saving: (String, ProviderInboxMessage) -> Unit = { _, _ -> }
        var sms: suspend () -> InboxSyncManager.RecoveryBatch = { InboxSyncManager.RecoveryBatch(0, 0, false) }
        fun manager(): MessageInboxSyncManager = MessageInboxSyncManager({ _, _, _ -> sms() },
            listOf("mms", "samsung_im", "samsung_ft").map { key ->
                MessageInboxSyncManager.Source(key, ConnectionLogStage.MMS_PROVIDER, object : ProviderInboxReader {
                    override suspend fun readPage(afterId: Long, limit: Int): ProviderMessagePage {
                        calls += Triple(key, afterId, limit)
                        return pages[key]?.invoke(afterId, limit) ?: ProviderMessagePage(emptyList(), false, afterId)
                    }
                })
            }, { cursors[it] ?: 0L }, { key, value -> cursors[key] = value },
            { key, message, _ -> saving(key, message); if (saved.add(key to message.id)) message.id else null },
            ConnectionLogSink(events::add))
    }
    private fun message(id: Long) = ProviderInboxMessage(id, "synthetic sender", "synthetic text", 1000L + id)

    @Test fun `independent source IDs use bounded pages and persist checkpoints across manager restart`() = runBlocking<Unit> {
        val f = Fixture()
        for (key in listOf("mms", "samsung_im", "samsung_ft")) {
            f.pages[key] = { after, _ -> if (after == 0L) ProviderMessagePage(listOf(message(1)), false, 1)
                else ProviderMessagePage(emptyList(), false, after) }
        }
        val first = f.manager().recoverBatch(limit = 250)
        assertEquals(3, first.inserted)
        assertEquals(3, f.saved.size)
        assertTrue(f.calls.all { it.third == 50 })
        assertEquals(0, f.manager().recoverBatch().inserted)
        assertTrue(f.calls.takeLast(3).all { it.second == 1L })
        assertNull(first.failure)
    }

    @Test fun `incomplete MMS persists completed prefix while chat continues without an immediate MMS loop`() = runBlocking<Unit> {
        val f = Fixture()
        f.pages["mms"] = { _, _ -> ProviderMessagePage(listOf(message(1)), true, 1, pendingIncomplete = true) }
        f.pages["samsung_im"] = { _, _ -> ProviderMessagePage(listOf(message(3)), false, 3) }
        val result = f.manager().recoverBatch()
        assertEquals(2, result.inserted)
        assertEquals(1L, f.cursors["mms"])
        assertEquals(3L, f.cursors["samsung_im"])
        assertFalse(result.hasMore)
        assertTrue(result.failure?.retryable == true)
        assertTrue(f.events.any { it.outcome == ConnectionLogOutcome.WAITING })
    }

    @Test fun `provider failure does not prevent other providers or hide their normal continuation`() = runBlocking<Unit> {
        val f = Fixture()
        f.sms = { throw InboxRecoveryException(ConnectionFailureReason.PROVIDER_RESET, false) }
        f.pages["mms"] = { _, _ -> throw MmsInboxReadException(MmsReadFailure.PROVIDER_UNAVAILABLE) }
        f.pages["samsung_im"] = { _, _ -> ProviderMessagePage(listOf(message(3)), true, 3) }
        val result = f.manager().recoverBatch()
        assertEquals(1, result.inserted)
        assertTrue(result.hasMore)
        assertTrue(result.failure?.retryable == true)
        assertNull(f.cursors["mms"])
        assertEquals(3L, f.cursors["samsung_im"])
    }

    @Test fun `failed persistence leaves cursor and retry reuses the saved prefix`() = runBlocking<Unit> {
        val f = Fixture()
        f.pages["mms"] = { _, _ -> ProviderMessagePage(listOf(message(1), message(2)), false, 2) }
        f.saving = { source, message -> if (source == "mms" && message.id == 2L) error("synthetic failure") }
        assertNotNull(f.manager().recoverBatch().failure)
        assertNull(f.cursors["mms"])
        assertEquals(setOf("mms" to 1L), f.saved)
        f.saving = { _, _ -> }
        assertEquals(1, f.manager().recoverBatch().inserted)
        assertEquals(2L, f.cursors["mms"])
        assertEquals(2, f.saved.size)
    }

    @Test fun `cancellation never acknowledges provider page or continues other sources`() = runBlocking<Unit> {
        val f = Fixture()
        f.pages["mms"] = { _, _ -> ProviderMessagePage(listOf(message(1)), false, 1) }
        f.saving = { _, _ -> throw CancellationException() }
        assertThrows(CancellationException::class.java) { runBlocking { f.manager().recoverBatch() } }
        assertTrue(f.cursors.isEmpty())
        assertEquals(1, f.calls.size)
    }

    @Test fun `wrapped MMS permission failure reports permission denied without repeated automatic retry`() = runBlocking<Unit> {
        val f = Fixture()
        f.pages["mms"] = { _, _ -> throw MmsInboxReadException(MmsReadFailure.PERMISSION_DENIED) }
        val result = f.manager().recoverBatch()
        assertEquals(ConnectionFailureReason.PERMISSION_DENIED, result.failure?.reason)
        assertFalse(result.failure!!.retryable)
        assertEquals(3, f.calls.size)
    }
}
