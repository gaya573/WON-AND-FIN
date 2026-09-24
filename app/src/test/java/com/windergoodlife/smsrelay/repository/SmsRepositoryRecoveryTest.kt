package com.windergoodlife.smsrelay.repository

import android.content.Context
import com.windergoodlife.smsrelay.data.SmsDao
import com.windergoodlife.smsrelay.data.SmsEntity
import com.windergoodlife.smsrelay.network.SmsApi
import com.windergoodlife.smsrelay.network.dto.SmsAckResponse
import com.windergoodlife.smsrelay.network.dto.SmsIngestRequest
import com.windergoodlife.smsrelay.security.DeviceTokenStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class SmsRepositoryRecoveryTest {
    @Test fun `recovery with 51 messages remains scheduled after first batch`() = runBlocking<Unit> {
        val dao = mock(SmsDao::class.java)
        val api = mock(SmsApi::class.java)
        val token = mock(DeviceTokenStore::class.java)
        `when`(token.isConfigured()).thenReturn(true)
        `when`(token.getDeviceToken()).thenReturn("synthetic-token")
        `when`(token.getDeviceId()).thenReturn("test-phone")
        val pending = (1L..51L).associateWith {
            SmsEntity(it, "key-$it", "synthetic-sender", "synthetic-message", 10_000L)
        }.toMutableMap()
        `when`(dao.findRetryable(50)).thenAnswer { pending.values.take(50) }
        `when`(dao.findById(anyLong())).thenAnswer { pending[it.getArgument<Long>(0)] }
        `when`(dao.pendingCount()).thenAnswer { pending.size }
        doAnswer { if (it.getArgument<String>(1) == "SENT") pending.remove(it.getArgument<Long>(0)); null }
            .`when`(dao).updateUploadResult(anyLong(), anyString(), anyInt(), anyLong(), nullable(String::class.java), nullable(Int::class.javaObjectType))
        `when`(api.uploadMessage(anyString(), anyString(), anyString(), any(SmsIngestRequest::class.java) ?: SmsIngestRequest("", "", "", "", "")))
            .thenReturn(Response.success(SmsAckResponse(true, "synthetic-ack")))
        val repository = SmsRepository(mock(Context::class.java), dao, api, token)
        assertFalse(repository.flushPendingBatch())
        assertEquals(1, pending.size)
        assertTrue(repository.flushPendingBatch())
        assertTrue(pending.isEmpty())
    }

    @Test fun `message rejected before token renewal can be sent after successful renewal`() = runBlocking<Unit> {
        val dao = mock(SmsDao::class.java)
        val api = mock(SmsApi::class.java)
        val token = mock(DeviceTokenStore::class.java)
        `when`(token.isConfigured()).thenReturn(true)
        `when`(token.getDeviceToken()).thenReturn("synthetic-token")
        `when`(token.getDeviceId()).thenReturn("test-phone")
        var message = SmsEntity(1L, "key", "sender", "synthetic-message", 10_000L)
        `when`(dao.findById(1L)).thenAnswer { message }
        doAnswer { message = message.copy(status = it.getArgument<String>(1)); null }
            .`when`(dao).updateUploadResult(anyLong(), anyString(), anyInt(), anyLong(), nullable(String::class.java), nullable(Int::class.javaObjectType))
        `when`(dao.recoverAuthenticationFailures()).thenAnswer {
            if (message.status == "ERROR_AUTH") { message = message.copy(status = "PENDING"); 1 } else 0
        }
        `when`(api.uploadMessage(anyString(), anyString(), anyString(), any(SmsIngestRequest::class.java) ?: SmsIngestRequest("", "", "", "", "")))
            .thenReturn(Response.error(401, "{}".toResponseBody()))
            .thenReturn(Response.success(SmsAckResponse(true, "synthetic-ack")))
        val repository = SmsRepository(mock(Context::class.java), dao, api, token)
        assertEquals(SmsRepository.UploadOutcome.AuthError, repository.uploadOne(1L))
        assertEquals("ERROR_AUTH", message.status)
        assertEquals(1, repository.recoverAuthenticationFailures())
        assertEquals(SmsRepository.UploadOutcome.Success, repository.uploadOne(1L))
        assertEquals("SENT", message.status)
    }
}
