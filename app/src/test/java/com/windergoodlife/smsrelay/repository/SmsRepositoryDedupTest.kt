package com.windergoodlife.smsrelay.repository

import android.content.Context
import com.windergoodlife.smsrelay.data.SmsDao
import com.windergoodlife.smsrelay.data.SmsEntity
import com.windergoodlife.smsrelay.network.SmsApi
import com.windergoodlife.smsrelay.network.dto.SmsAckResponse
import com.windergoodlife.smsrelay.network.dto.SmsIngestRequest
import com.windergoodlife.smsrelay.security.DeviceTokenStore
import com.windergoodlife.smsrelay.util.SmsKeys
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import retrofit2.Response
import java.io.IOException

class SmsRepositoryDedupTest {
    private class Fixture {
        val dao = mock(SmsDao::class.java)
        val store = mock(DeviceTokenStore::class.java)
        val context = mock(Context::class.java)
        val rows = linkedMapOf<Long, SmsEntity>()
        val requests = mutableListOf<Pair<String, SmsIngestRequest>>()
        var respond: suspend () -> Response<SmsAckResponse> = {
            Response.success(SmsAckResponse(true, "synthetic-server-id"))
        }
        private val api = object : SmsApi by mock(SmsApi::class.java) {
            override suspend fun uploadMessage(
                authorization: String, idempotencyKey: String, deviceToken: String,
                body: SmsIngestRequest, deviceId: String?
            ): Response<SmsAckResponse> {
                requests += idempotencyKey to body
                return respond()
            }
        }

        init {
            `when`(store.isConfigured()).thenReturn(true)
            `when`(store.getDeviceToken()).thenReturn("synthetic-secret")
            `when`(store.getDeviceId()).thenReturn("synthetic-phone")
        }

        suspend fun configureDao() {
            `when`(dao.findById(anyLong())).thenAnswer { rows[it.getArgument<Long>(0)] }
            `when`(dao.findRetryable(anyInt())).thenAnswer { call ->
                rows.values.filter { it.status in listOf("PENDING", "FAILED", "SENDING") }
                    .take(call.getArgument<Int>(0))
            }
            `when`(dao.insertIgnore(any(SmsEntity::class.java) ?: row())).thenAnswer { call ->
                val candidate = call.getArgument<SmsEntity>(0)
                if (rows.values.any { it.uniqueKey == candidate.uniqueKey }) -1L
                else {
                    val id = (rows.keys.maxOrNull() ?: 0L) + 1L
                    rows[id] = candidate.copy(id = id)
                    id
                }
            }
            doAnswer { call ->
                val id = call.getArgument<Long>(0)
                rows[id] = rows.getValue(id).copy(
                    status = call.getArgument<String>(1), retryCount = call.getArgument<Int>(2),
                    lastAttemptAt = call.getArgument<Long>(3), serverMessageId = call.getArgument<String?>(4),
                    httpLastStatus = call.getArgument<Int?>(5)
                )
                null
            }.`when`(dao).updateUploadResult(anyLong(), anyString(), anyInt(), anyLong(), nullable(String::class.java), nullable(Int::class.javaObjectType))
            `when`(dao.recoverAuthenticationFailures()).thenAnswer {
                val failed = rows.values.filter { it.status == "ERROR_AUTH" }
                failed.forEach { rows[it.id] = it.copy(status = "PENDING", httpLastStatus = null) }
                failed.size
            }
        }

        fun repository() = SmsRepository(context, dao, api, store)

        fun row(status: String = "PENDING") = SmsEntity(
            1L, SmsKeys.uniqueKey("010-0000-0000", "synthetic message", 12_345L),
            "010-0000-0000", "synthetic message", 12_345L, status
        )
    }

    @Test fun `individual and backlog workers send a shared pending message only once`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.configureDao()
        fixture.rows[1L] = fixture.row()
        val serverResponse = CompletableDeferred<Unit>()
        fixture.respond = { serverResponse.await(); Response.success(SmsAckResponse(true, "accepted")) }
        val repository = fixture.repository()
        val individual = async(start = CoroutineStart.UNDISPATCHED) { repository.uploadOne(1L) }
        assertEquals("SENDING", fixture.rows[1L]?.status)
        val backlog = async(start = CoroutineStart.UNDISPATCHED) { repository.uploadPending() }
        assertEquals(1, fixture.requests.size, "the backlog must wait for the current request and ACK write")
        serverResponse.complete(Unit)
        assertEquals(SmsRepository.UploadOutcome.Success, individual.await())
        backlog.await()
        assertEquals(1, fixture.requests.size)
        assertEquals("SENT", fixture.rows[1L]?.status)
        assertEquals("accepted", fixture.rows[1L]?.serverMessageId)
    }

    @Test fun `a restarted repository never resends a persisted success`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.configureDao()
        fixture.rows[1L] = fixture.row()
        assertEquals(SmsRepository.UploadOutcome.Success, fixture.repository().uploadOne(1L))
        val restarted = fixture.repository()
        assertEquals(SmsRepository.UploadOutcome.Success, restarted.uploadOne(1L))
        assertEquals(0, restarted.uploadPending())
        assertEquals(1, fixture.requests.size)
    }

    @Test fun `rescanning an acknowledged message keeps the original success and skips enqueue`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.configureDao()
        fixture.rows[1L] = fixture.row()
        val repository = fixture.repository()
        repository.uploadOne(1L)
        val acknowledged = fixture.rows.getValue(1L)
        assertNull(repository.saveIncoming("01000000000", "synthetic message", 12_345L))
        assertEquals(acknowledged, fixture.rows.getValue(1L))
        assertEquals(1, fixture.rows.size)
        repository.uploadPending()
        assertEquals(1, fixture.requests.size)
        verifyNoInteractions(fixture.context)
    }

    @Test fun `reconnection retries authentication failures without changing acknowledged history`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.configureDao()
        val accepted = fixture.row("SENT").copy(serverMessageId = "old-ack", httpLastStatus = 200)
        fixture.rows[1L] = accepted
        fixture.rows[2L] = fixture.row("ERROR_AUTH").copy(id = 2L, uniqueKey = "other-key")
        val repository = fixture.repository()
        assertEquals(1, repository.recoverAuthenticationFailures())
        repository.uploadPending()
        assertEquals(accepted, fixture.rows[1L])
        assertEquals(listOf("other-key"), fixture.requests.map { it.first })
    }

    @Test fun `an uncertain response retries the unchanged server idempotency key`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.configureDao()
        fixture.rows[1L] = fixture.row()
        fixture.respond = { throw IOException("synthetic connection interruption") }
        val repository = fixture.repository()
        assertEquals(SmsRepository.UploadOutcome.Retry, repository.uploadOne(1L))
        fixture.respond = { Response.success(SmsAckResponse(true, "same-server-id")) }
        assertEquals(SmsRepository.UploadOutcome.Success, repository.uploadOne(1L))
        assertEquals(2, fixture.requests.size)
        assertEquals(fixture.requests[0], fixture.requests[1])
        assertEquals("SENT", fixture.rows[1L]?.status)
        repository.uploadOne(1L)
        assertEquals(2, fixture.requests.size)
    }
}
