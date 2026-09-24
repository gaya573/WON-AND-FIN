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
    private data class Message(val id: Long, val date: Long)
    private class Fixture {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        val repository = mock(SmsRepository::class.java)
        val store = mock(DeviceTokenStore::class.java)
        val inbox = mutableListOf(Message(1L, 50L), Message(2L, 100L))
        val queries = mutableListOf<Pair<String, List<Long>>>()
        val saved = mutableSetOf<Long>()
        var providerCursor: Long? = null
        var timeCursor = 100L
        var now = 200L
        var beforeSave: (Long) -> Unit = {}
        var nullSnapshot = false
        var nullMessages = false

        suspend fun setup() {
            `when`(context.contentResolver).thenReturn(resolver)
            `when`(store.getLastSyncTime()).thenAnswer { timeCursor }
            `when`(store.getLastInboxSmsId()).thenAnswer { providerCursor }
            doAnswer { providerCursor = it.getArgument<Long>(0); timeCursor = it.getArgument<Long>(1); null }
                .`when`(store).commitInboxCheckpoint(anyLong(), anyLong())
            `when`(repository.saveIncoming(anyString(), anyString(), anyLong())).thenAnswer {
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
                        (if (selection.startsWith("date")) it.date >= bounds[0] else it.id > bounds[0]) && it.id <= bounds[1]
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
                if (columns[it.getArgument<Int>(0)] == "_id") messages[index].id else messages[index].date
            }
            `when`(cursor.getString(anyInt())).thenAnswer {
                if (columns[it.getArgument<Int>(0)] == "address") "synthetic sender" else "synthetic message ${messages[index].id}"
            }
            return cursor
        }

        fun manager() = InboxSyncManager(context, repository, store) { now }
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
        assertEquals(0, fixture.manager().syncFromLastCheckpoint())
        fixture.nullSnapshot = false
        fixture.nullMessages = true
        assertEquals(0, fixture.manager().syncFromLastCheckpoint())
        verify(fixture.store, never()).commitInboxCheckpoint(anyLong(), anyLong())
    }

    @Test fun `provider reset cannot rewind checkpoint or reimport completed history`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.setup()
        fixture.providerCursor = 20L
        assertEquals(0, fixture.manager().syncFromLastCheckpoint())
        assertTrue(fixture.saved.isEmpty())
        assertTrue(fixture.queries.isEmpty())
        verify(fixture.store, never()).commitInboxCheckpoint(anyLong(), anyLong())
    }
}
