package com.windergoodlife.smsrelay.repository

import android.content.Context
import com.windergoodlife.smsrelay.data.SmsDao
import com.windergoodlife.smsrelay.data.SmsEntity
import com.windergoodlife.smsrelay.network.SmsApi
import com.windergoodlife.smsrelay.network.dto.SmsAckResponse
import com.windergoodlife.smsrelay.network.dto.SmsIngestRequest
import com.windergoodlife.smsrelay.security.DeviceTokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import retrofit2.Response

class SmsRepositoryDrainTest {
    private class Fixture(total: Int) {
        val dao = mock(SmsDao::class.java)
        val api = mock(SmsApi::class.java)
        val token = mock(DeviceTokenStore::class.java)
        val rows = (1L..total).associateWith { SmsEntity(it, "key-$it", "sender", "body", 1000) }.toMutableMap()
        var calls = 0
        var respond: () -> Response<SmsAckResponse> = { Response.success(SmsAckResponse(true, "ack")) }
        suspend fun setup(): SmsRepository {
            `when`(token.isConfigured()).thenReturn(true)
            `when`(token.getDeviceToken()).thenReturn("synthetic-token")
            `when`(token.getDeviceId()).thenReturn("synthetic-phone")
            fun pending() = rows.values.filter { it.status in listOf("PENDING", "SENDING", "FAILED") }
            `when`(dao.findRetryable(anyInt())).thenAnswer { pending().take(it.getArgument(0)) }
            `when`(dao.pendingCount()).thenAnswer { pending().size }
            `when`(dao.findById(anyLong())).thenAnswer { rows[it.getArgument<Long>(0)] }
            doAnswer {
                val id = it.getArgument<Long>(0)
                rows[id] = rows.getValue(id).copy(status = it.getArgument(1))
                null
            }.`when`(dao).updateUploadResult(anyLong(), anyString(), anyInt(), anyLong(), nullable(String::class.java), nullable(Int::class.javaObjectType))
            `when`(api.uploadMessage(anyString(), anyString(), anyString(), any(SmsIngestRequest::class.java)
                ?: SmsIngestRequest("", "", "", "", ""), anyString())).thenAnswer { calls++; respond() }
            return SmsRepository(mock(Context::class.java), dao, api, token)
        }
    }

    @Test fun `fifty-one healthy messages finish in one worker pass`() = runBlocking<Unit> {
        val fixture = Fixture(51)
        val result = fixture.setup().drainPending()
        assertEquals(SmsRepository.DrainResult(51, 0, false, false), result)
        assertTrue(fixture.rows.values.all { it.status == "SENT" })
    }

    @Test fun `bounded drain reports continuation and invalid payload does not block its healthy suffix`() = runBlocking<Unit> {
        val fixture = Fixture(51)
        fixture.respond = { if (fixture.calls == 1) Response.error(400, "{}".toResponseBody()) else Response.success(SmsAckResponse(true, "ack")) }
        val repository = fixture.setup()
        val result = repository.drainPending(maxBatches = 1)
        assertEquals(49, result.sent)
        assertEquals(1, result.remaining)
        assertFalse(result.retryableFailure)
        assertFalse(result.blocked)
        assertEquals(1, result.permanentFailures)
        assertEquals("ERROR_PAYLOAD", fixture.rows.getValue(1).status)
        assertEquals(1, repository.drainPending().sent)
    }

    @Test fun `temporary failure stops at first request instead of hammering every queued message`() = runBlocking<Unit> {
        for (code in listOf(404, 408, 410, 422, 429, 503)) {
            val fixture = Fixture(51)
            fixture.respond = { Response.error(code, "{}".toResponseBody()) }
            val result = fixture.setup().drainPending()
            assertTrue(result.retryableFailure)
            assertEquals(1, fixture.calls)
            assertEquals(51, result.remaining)
        }
    }

    @Test fun `cancelled in-flight upload keeps original key and is never marked SENT`() {
        val fixture = Fixture(1)
        fixture.respond = { throw CancellationException() }
        assertThrows(CancellationException::class.java) { runBlocking { fixture.setup().drainPending() } }
        assertEquals("key-1", fixture.rows.getValue(1).uniqueKey)
        assertEquals("SENDING", fixture.rows.getValue(1).status)
    }
}
