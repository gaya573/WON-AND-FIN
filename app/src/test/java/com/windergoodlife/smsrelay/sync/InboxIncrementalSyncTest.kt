package com.windergoodlife.smsrelay.sync

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.windergoodlife.smsrelay.repository.SmsRepository
import com.windergoodlife.smsrelay.security.DeviceTokenStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class InboxIncrementalSyncTest {
    private data class Message(val id: Long, val date: Long, val sentAt: Long = 0L)
    private class Fixture {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        val repository = mock(SmsRepository::class.java)
        val store = mock(DeviceTokenStore::class.java)
        val inbox = mutableListOf(Message(1L, 50L), Message(2L, 100L))
        val queries = mutableListOf<Pair<String, List<Long>>>()
        val saved = mutableSetOf<Long>()
        var providerCursor: Long? = null
        var upgradeSnapshot: Long? = null
        var timeCursor = 100L
        var now = 200L
        var beforeSave: (Long) -> Unit = {}
        var nullSnapshot = false
        var nullMessages = false
        var historyCursor = 0L
        var historyComplete = false

        suspend fun setup() {
            `when`(context.contentResolver).thenReturn(resolver)
            `when`(store.getLastSyncTime()).thenAnswer { timeCursor }
            `when`(store.getLastInboxSmsId()).thenAnswer { providerCursor }
            `when`(store.getInboxUpgradeSnapshot()).thenAnswer { upgradeSnapshot }
            `when`(store.getProviderCheckpoint("sms_history")).thenAnswer { historyCursor }
            `when`(store.isFullSmsHistoryComplete()).thenAnswer { historyComplete }
            doAnswer { historyCursor = it.getArgument<Long>(1); null }.`when`(store).commitProviderCheckpoint(anyString(), anyLong())
            doAnswer {
                historyComplete = true
                providerCursor = maxOf(providerCursor ?: 0L, it.getArgument<Long>(0))
                timeCursor = maxOf(timeCursor, it.getArgument<Long>(1)); null
            }.`when`(store).finishFullSmsHistory(anyLong(), anyLong())
            doAnswer { upgradeSnapshot = it.getArgument<Long>(0); null }.`when`(store).beginInboxUpgrade(anyLong())
            doAnswer { upgradeSnapshot = null; null }.`when`(store).finishInboxUpgrade()
            doAnswer { providerCursor = it.getArgument<Long>(0); timeCursor = it.getArgument<Long>(1); null }
                .`when`(store).commitInboxCheckpoint(anyLong(), anyLong())
            `when`(repository.saveRecovered(anyString(), anyString(), anyLong())).thenAnswer {
                val id = it.getArgument<String>(1).removePrefix("synthetic message ").toLong()
                beforeSave(id)
                if (saved.add(id)) id else null
            }
            `when`(resolver.query(isNull<Uri>(), any(), nullable(String::class.java), any(), anyString())).thenAnswer { call ->
                val selection = call.getArgument<String?>(2)
                val projection = call.getArgument<Array<String>>(1)
                if (selection == null) {
                    if (nullSnapshot) null else cursor(inbox.sortedByDescending { it.id }, projection)
                } else {
                    val bounds = call.getArgument<Array<String>>(3).map { it.toLong() }
                    queries += selection to bounds
                    if (nullMessages) null else cursor(inbox.filter {
                        (if (selection.startsWith("date")) it.date >= bounds[0] else it.id > bounds[0]) &&
                            (bounds.size != 3 || it.id > bounds[1]) && it.id <= bounds.last()
                    }.sortedBy { it.id }, projection)
                }
            }
        }

        private fun cursor(messages: List<Message>, columns: Array<String>): Cursor {
            val cursor = mock(Cursor::class.java)
            var index = -1
            `when`(cursor.moveToNext()).thenAnswer { ++index < messages.size }
            `when`(cursor.getColumnIndex(anyString())).thenAnswer { columns.indexOf(it.getArgument<String>(0)) }
            `when`(cursor.getLong(anyInt())).thenAnswer {
                when (columns[it.getArgument<Int>(0)]) {
                    "_id" -> messages[index].id
                    "date_sent" -> messages[index].sentAt
                    else -> messages[index].date
                }
            }
            `when`(cursor.getString(anyInt())).thenAnswer {
                if (columns[it.getArgument<Int>(0)] == "address") "synthetic sender" else "synthetic message ${messages[index].id}"
            }
            return cursor
        }

        fun manager(fullHistory: Boolean = false) = InboxSyncManager(context, repository, store, fullHistory) { now }
    }

    @Test fun `upgrade honors existing time boundary and subsequent reconnect skips completed provider rows`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.setup()
        assertEquals(1, fixture.manager().syncFromLastCheckpoint())
        assertEquals(setOf(2L), fixture.saved)
        assertEquals(2L, fixture.providerCursor)
        fixture.now = 300L
        assertEquals(0, fixture.manager().syncFromLastCheckpoint())
        assertEquals(listOf("date >= ? AND _id <= ?" to listOf(100L, 2L)), fixture.queries)
    }

    @Test fun `observer captures one bounded page with separate received and sent timestamps`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.setup()
        fixture.inbox.clear()
        fixture.inbox += Message(1L, 150L, 120L)
        fixture.inbox += Message(2L, 160L, 125L)
        `when`(fixture.repository.saveProviderMessage(anyString(), anyString(), anyLong(), anyLong(), eq(true))).thenReturn(1L)
        val page = fixture.manager().recoverBatch(limit = 1, enqueueCaptured = true)
        assertEquals(1, page.scanned)
        assertTrue(page.hasMore)
        assertEquals(1L, fixture.providerCursor)
        verify(fixture.repository).saveProviderMessage("synthetic sender", "synthetic message 1", 150L, 120L, true)
        verify(fixture.repository, never()).saveRecovered(anyString(), anyString(), anyLong())
    }

    @Test fun `equal timestamps and late inserted messages are recovered by provider id`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.setup()
        fixture.manager().syncFromLastCheckpoint()
        fixture.inbox += Message(3L, 100L)
        fixture.inbox += Message(4L, 90L)
        assertEquals(2, fixture.manager().syncFromLastCheckpoint())
        assertEquals(setOf(2L, 3L, 4L), fixture.saved)
        assertEquals("_id > ? AND _id <= ?" to listOf(2L, 4L), fixture.queries.last())
    }

    @Test fun `arrivals during the query remain beyond its captured provider snapshot`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.setup()
        fixture.beforeSave = { if (it == 2L) fixture.inbox += Message(3L, 100L) }
        fixture.manager().syncFromLastCheckpoint()
        assertEquals(2L, fixture.providerCursor)
        assertEquals(1, fixture.manager().syncFromLastCheckpoint())
        assertEquals(setOf(2L, 3L), fixture.saved)
    }

    @Test fun `persistence failure keeps cursor and retry deduplicates the already saved prefix`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.setup()
        fixture.inbox += Message(3L, 110L)
        fixture.beforeSave = { if (it == 3L) throw IllegalStateException("synthetic disk failure") }
        try { fixture.manager().syncFromLastCheckpoint(); fail("expected persistence failure") } catch (_: IllegalStateException) { }
        assertNull(fixture.providerCursor)
        assertEquals(100L, fixture.timeCursor)
        assertEquals(setOf(2L), fixture.saved)
        fixture.beforeSave = {}
        assertEquals(1, fixture.manager().syncFromLastCheckpoint())
        assertEquals(setOf(2L, 3L), fixture.saved)
        assertEquals(3L, fixture.providerCursor)
    }

    @Test fun `missing provider results cannot advance the checkpoint`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.setup()
        fixture.nullSnapshot = true
        assertThrows(InboxRecoveryException::class.java) { runBlocking { fixture.manager().syncFromLastCheckpoint() } }
        fixture.nullSnapshot = false
        fixture.nullMessages = true
        assertThrows(InboxRecoveryException::class.java) { runBlocking { fixture.manager().syncFromLastCheckpoint() } }
        verify(fixture.store, never()).commitInboxCheckpoint(anyLong(), anyLong())
    }

    @Test fun `provider reset cannot rewind checkpoint or reimport completed history`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.setup()
        fixture.providerCursor = 20L
        val failure = assertThrows(InboxRecoveryException::class.java) { runBlocking { fixture.manager().syncFromLastCheckpoint() } }
        assertEquals(com.windergoodlife.smsrelay.diagnostics.ConnectionFailureReason.PROVIDER_RESET, failure.reason)
        assertFalse(failure.retryable)
        assertTrue(fixture.saved.isEmpty())
        assertTrue(fixture.queries.isEmpty())
        verify(fixture.store, never()).commitInboxCheckpoint(anyLong(), anyLong())
    }

    @Test fun `bounded upgrade pages preserve original consent and snapshot across process restart`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.setup()
        fixture.inbox += Message(3, 150)
        fixture.inbox += Message(4, 25) // Historical row interspersed by provider id.
        fixture.inbox += Message(5, 180)
        val first = fixture.manager().recoverBatch(limit = 1)
        assertEquals(1, first.inserted)
        assertTrue(first.hasMore)
        assertEquals(2L, fixture.providerCursor)
        assertEquals(100L, fixture.timeCursor)
        fixture.inbox += Message(6, 190)
        val second = fixture.manager().recoverBatch(limit = 1)
        assertTrue(second.hasMore)
        assertEquals(3L, fixture.providerCursor)
        val third = fixture.manager().recoverBatch(limit = 1)
        assertTrue(third.hasMore) // Fresh arrival beyond the original snapshot is a later pass.
        assertEquals(setOf(2L, 3L, 5L), fixture.saved)
        assertNull(fixture.upgradeSnapshot)
        assertEquals(1, fixture.manager().recoverBatch().inserted)
        assertEquals(setOf(2L, 3L, 5L, 6L), fixture.saved)
    }

    @Test fun `completed partial page survives next page failure without skipping its unsaved suffix`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.setup()
        fixture.inbox += Message(3, 110)
        fixture.manager().recoverBatch(limit = 1)
        fixture.beforeSave = { if (it == 3L) throw IllegalStateException("synthetic disk failure") }
        assertThrows(IllegalStateException::class.java) { runBlocking { fixture.manager().recoverBatch() } }
        assertEquals(2L, fixture.providerCursor)
        fixture.beforeSave = {}
        assertEquals(1, fixture.manager().recoverBatch().inserted)
        assertEquals(setOf(2L, 3L), fixture.saved)
    }

    @Test fun `deleting an unscanned newest row during upgrade does not block its remaining pages`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.setup()
        fixture.inbox += Message(3, 110)
        fixture.inbox += Message(4, 120)
        assertTrue(fixture.manager().recoverBatch(limit = 1).hasMore)
        fixture.inbox.removeAll { it.id == 4L }
        assertEquals(1, fixture.manager().recoverBatch().inserted)
        assertEquals(setOf(2L, 3L), fixture.saved)
        assertEquals(3L, fixture.providerCursor)
        assertNull(fixture.upgradeSnapshot)
        assertFalse(fixture.manager().recoverBatch().hasMore)
    }

    @Test fun `authorized full history scans before old time boundary once and preserves existing saved messages`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.setup()
        fixture.providerCursor = 2
        fixture.saved += 2L // Existing acknowledged legacy row; recovery must reuse it.
        val first = fixture.manager(true).recoverBatch(limit = 1)
        assertEquals(1, first.inserted)
        assertTrue(first.hasMore)
        assertEquals(1L, fixture.historyCursor)
        assertEquals(2L, fixture.providerCursor)
        assertFalse(fixture.historyComplete)
        val second = fixture.manager(true).recoverBatch(limit = 1)
        assertEquals(0, second.inserted)
        assertTrue(fixture.historyComplete)
        assertEquals(setOf(1L, 2L), fixture.saved)
        val queriesBefore = fixture.queries.size
        assertEquals(0, fixture.manager(true).recoverBatch().inserted)
        assertEquals(queriesBefore, fixture.queries.size)
        fixture.inbox += Message(3, 20) // New insertion with older reported receive time.
        assertEquals(1, fixture.manager(true).recoverBatch().inserted)
        assertEquals("_id > ? AND _id <= ?" to listOf(2L, 3L), fixture.queries.last())
    }

    @Test fun `full history storage failure does not mark history complete or alter previous incremental cursor`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.setup()
        fixture.providerCursor = 2
        fixture.beforeSave = { throw IllegalStateException("synthetic disk failure") }
        assertThrows(IllegalStateException::class.java) { runBlocking { fixture.manager(true).recoverBatch() } }
        assertEquals(0L, fixture.historyCursor)
        assertFalse(fixture.historyComplete)
        assertEquals(2L, fixture.providerCursor)
    }
}
