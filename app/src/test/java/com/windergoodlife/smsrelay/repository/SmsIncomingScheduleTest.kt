package com.windergoodlife.smsrelay.repository

import android.content.Context
import com.windergoodlife.smsrelay.data.SmsDao
import com.windergoodlife.smsrelay.data.SmsEntity
import com.windergoodlife.smsrelay.diagnostics.*
import com.windergoodlife.smsrelay.network.SmsApi
import com.windergoodlife.smsrelay.security.DeviceTokenStore
import com.windergoodlife.smsrelay.util.SmsKeys
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class SmsIncomingScheduleTest {
    @Test fun `receiver schedules an unsent message already inserted by inbox recovery`() = runBlocking<Unit> {
        for (status in listOf("PENDING", "FAILED", "SENDING")) {
            val dao = mock(SmsDao::class.java)
            val store = mock(DeviceTokenStore::class.java)
            val original = SmsEntity(42L, SmsKeys.uniqueKey("synthetic sender", "synthetic message", 1234L),
                "synthetic sender", "synthetic message", 1234L, status)
            `when`(dao.insertIgnore(any(SmsEntity::class.java) ?: original)).thenReturn(-1L)
            `when`(dao.findByUniqueKey(original.uniqueKey)).thenReturn(original)
            `when`(store.isConfigured()).thenReturn(true)
            val scheduled = mutableListOf<Long>()
            val api = mock(SmsApi::class.java)
            val repository = SmsRepository(mock(Context::class.java), dao, api, store, enqueueUpload = scheduled::add)

            assertNull(repository.saveIncoming(original.sender, original.message, original.receivedAt))

            assertEquals(listOf(42L), scheduled, "a durable $status row still needs its immediate receiver wake")
            verify(dao, never()).updateStatus(anyLong(), anyString())
            verifyNoInteractions(api)
        }
    }

    @Test fun `duplicate acknowledged or permanently blocked messages remain unscheduled`() = runBlocking<Unit> {
        for (status in listOf("SENT", "ERROR_AUTH", "ERROR_PAYLOAD")) {
            val dao = mock(SmsDao::class.java)
            val store = mock(DeviceTokenStore::class.java)
            val original = SmsEntity(42L, SmsKeys.uniqueKey("synthetic sender", "synthetic message", 1234L),
                "synthetic sender", "synthetic message", 1234L, status)
            `when`(dao.insertIgnore(any(SmsEntity::class.java) ?: original)).thenReturn(-1L)
            `when`(dao.findByUniqueKey(original.uniqueKey)).thenReturn(original)
            `when`(store.isConfigured()).thenReturn(true)
            val scheduled = mutableListOf<Long>()
            val repository = SmsRepository(mock(Context::class.java), dao, mock(SmsApi::class.java), store,
                enqueueUpload = scheduled::add)

            assertNull(repository.saveIncoming(original.sender, original.message, original.receivedAt))

            assertTrue(scheduled.isEmpty(), "$status must keep its existing outcome")
            verify(dao, never()).updateStatus(anyLong(), anyString())
        }
    }

    @Test fun `queue persistence failure keeps the saved message and emits only safe stage diagnostics`() = runBlocking<Unit> {
        val dao = mock(SmsDao::class.java)
        val store = mock(DeviceTokenStore::class.java)
        val original = SmsEntity(0L, SmsKeys.uniqueKey("private sender", "private body", 1234L),
            "private sender", "private body", 1234L, "PENDING")
        `when`(dao.insertIgnore(any(SmsEntity::class.java) ?: original)).thenReturn(42L)
        `when`(store.isConfigured()).thenReturn(true)
        val events = mutableListOf<ConnectionDiagnostic>()
        val repository = SmsRepository(mock(Context::class.java), dao, mock(SmsApi::class.java), store,
            ConnectionLogSink(events::add), enqueueUpload = { throw IllegalStateException("private scheduling secret") })

        try {
            repository.saveIncoming(original.sender, original.message, original.receivedAt)
            fail<Unit>("scheduling failure should remain visible to the receiver")
        } catch (_: IllegalStateException) { }

        assertEquals(listOf(ConnectionLogStage.LOCAL_STORE, ConnectionLogStage.LOCAL_STORE,
            ConnectionLogStage.UPLOAD_QUEUE, ConnectionLogStage.UPLOAD_QUEUE), events.map { it.stage })
        assertEquals(ConnectionLogOutcome.SUCCEEDED, events[1].outcome)
        assertEquals(ConnectionFailureReason.WORK_SCHEDULING, events.last().reason)
        val logs = ConnectionLogBuffer()
        events.forEachIndexed { index, event -> logs.record(event, index + 1L) }
        assertFalse(logs.serialize().contains("private"))
        assertFalse(ConnectionLogFormatter.format(logs.snapshot()).contains("private"))
        verify(dao).insertIgnore(original.copy(uniqueKey = SmsKeys.canonicalKey(original.sender, original.message, original.receivedAt)))
        verify(dao, never()).updateStatus(anyLong(), anyString())
    }

    @Test fun `inbox recovery retains its bounded queue drain without one worker per duplicate`() = runBlocking<Unit> {
        val dao = mock(SmsDao::class.java)
        val original = SmsEntity(42L, SmsKeys.uniqueKey("synthetic sender", "synthetic message", 1234L),
            "synthetic sender", "synthetic message", 1234L, "PENDING")
        `when`(dao.insertIgnore(any(SmsEntity::class.java) ?: original)).thenReturn(-1L)
        `when`(dao.findByUniqueKey(original.uniqueKey)).thenReturn(original)
        val scheduled = mutableListOf<Long>()
        val repository = SmsRepository(mock(Context::class.java), dao, mock(SmsApi::class.java),
            mock(DeviceTokenStore::class.java), enqueueUpload = scheduled::add)

        assertNull(repository.saveRecovered(original.sender, original.message, original.receivedAt))

        assertTrue(scheduled.isEmpty())
    }
}
