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

class InboxSyncManagerTest {
    private val context = mock(Context::class.java)
    private val resolver = mock(ContentResolver::class.java)
    private val repository = mock(SmsRepository::class.java)
    private val store = mock(DeviceTokenStore::class.java)
    private var clock = 100L
    private val queryBounds = mutableListOf<Pair<Long, Long>>()
    private val messages = mutableListOf(50L)

    private fun setupProvider() {
        `when`(context.contentResolver).thenReturn(resolver)
        // Uri.parse and permission checks are Android stubs; the ContentResolver is synthetic.
        `when`(resolver.query(isNull<Uri>(), any(), anyString(), any(), anyString())).thenAnswer { call ->
            val args = call.getArgument<Array<String>>(3)
            val lower = args[0].toLong()
            val upper = args[1].toLong()
            queryBounds += lower to upper
            val snapshot = messages.filter { it > lower && it <= upper }
            val cursor = mock(Cursor::class.java)
            var index = -1
            `when`(cursor.moveToNext()).thenAnswer { ++index < snapshot.size }
            `when`(cursor.getColumnIndex("address")).thenReturn(0)
            `when`(cursor.getColumnIndex("body")).thenReturn(1)
            `when`(cursor.getColumnIndex("date")).thenReturn(2)
            `when`(cursor.getColumnIndex("date_sent")).thenReturn(-1)
            `when`(cursor.getString(0)).thenReturn("synthetic-sender")
            `when`(cursor.getString(1)).thenReturn("synthetic-message")
            `when`(cursor.getLong(2)).thenAnswer { snapshot[index] }
            cursor
        }
    }

    @Test fun `arrival during scan is recovered by the following scan`() = runBlocking<Unit> {
        setupProvider()
        `when`(repository.saveProviderMessage(anyString(), anyString(), anyLong(), eq(0L), eq(true))).thenAnswer {
            if (it.getArgument<Long>(2) == 50L) { messages += 150L; clock = 200L }
            it.getArgument<Long>(2)
        }
        val manager = InboxSyncManager(context, repository, store) { clock }
        assertEquals(1, manager.syncSince(1L))
        verify(store).setLastSyncTime(100L)
        assertEquals(1, manager.syncSince(100L))
        assertEquals(listOf(1L to 100L, 100L to 200L), queryBounds)
        verify(repository).saveProviderMessage("synthetic-sender", "synthetic-message", 150L, 0L, true)
    }

    @Test fun `manual short rescan does not skip older automatic backlog`() = runBlocking<Unit> {
        setupProvider()
        `when`(repository.saveProviderMessage(anyString(), anyString(), anyLong(), eq(0L), eq(true))).thenReturn(1L)
        InboxSyncManager(context, repository, store) { clock }.syncRecentMinutes(10)
        verify(store, never()).setLastSyncTime(anyLong())
    }

    @Test fun `failed persistence leaves checkpoint for retry`() = runBlocking<Unit> {
        setupProvider()
        `when`(repository.saveProviderMessage(anyString(), anyString(), anyLong(), eq(0L), eq(true))).thenThrow(IllegalStateException("synthetic disk failure"))
        val manager = InboxSyncManager(context, repository, store) { clock }
        try { manager.syncSince(1L); fail("expected failure") } catch (_: IllegalStateException) { }
        verify(store, never()).setLastSyncTime(anyLong())
    }
}
