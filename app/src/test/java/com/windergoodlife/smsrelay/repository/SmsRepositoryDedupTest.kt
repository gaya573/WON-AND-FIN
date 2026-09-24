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
        val enqueued = mutableListOf<Long>()
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
            `when`(dao.findByUniqueKey(anyString())).thenAnswer { call -> rows.values.find { it.uniqueKey == call.getArgument<String>(0) } }
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

        fun repository(enqueue: suspend (Long) -> Unit = enqueued::add) =
            SmsRepository(context, dao, api, store, enqueueUpload = enqueue)

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

    @Test fun `provider receive time and broadcast sent time share one row in either arrival order`() = runBlocking<Unit> {
        for (providerFirst in listOf(false, true)) {
            val fixture = Fixture()
            fixture.configureDao()
            val repository = fixture.repository()
            val saveProvider: suspend () -> Unit = { repository.saveProviderMessage("01000000000", "synthetic message", 18_345L, 12_345L, true) }
            val receive: suspend () -> Unit = { repository.saveIncoming("01000000000", "synthetic message", 12_345L) }
            if (providerFirst) { saveProvider(); receive() } else { receive(); saveProvider() }
            assertEquals(1, fixture.rows.size)
            val saved = fixture.rows.values.single()
            assertEquals(SmsKeys.canonicalKey("01000000000", "synthetic message", 12_345L), saved.uniqueKey)
            assertEquals(if (providerFirst) 18_345L else 12_345L, saved.receivedAt)
            assertEquals(listOf(saved.id, saved.id), fixture.enqueued)
            repository.uploadOne(saved.id)
            repository.uploadOne(saved.id)
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test fun `provider and receiver racing during queue persistence still share the original row`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.configureDao()
        val queued = CompletableDeferred<Unit>()
        val repository = fixture.repository { id -> fixture.enqueued += id; queued.await() }
        val provider = async(start = CoroutineStart.UNDISPATCHED) {
            repository.saveProviderMessage("01000000000", "synthetic message", 18_345L, 12_345L, true)
        }
        val receiver = async(start = CoroutineStart.UNDISPATCHED) {
            repository.saveIncoming("01000000000", "synthetic message", 12_345L)
        }
        assertEquals(1, fixture.rows.size)
        assertEquals(listOf(1L, 1L), fixture.enqueued)
        queued.complete(Unit)
        assertEquals(1L, provider.await())
        assertNull(receiver.await())
    }

    @Test fun `legacy provider SENT key and acknowledgement survive the timestamp upgrade`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.configureDao()
        val original = fixture.row("SENT").copy(
            uniqueKey = SmsKeys.uniqueKey("01000000000", "synthetic message", 18_345L),
            receivedAt = 18_345L, serverMessageId = "old-ack", httpLastStatus = 200)
        fixture.rows[1L] = original
        assertNull(fixture.repository().saveProviderMessage("01000000000", "synthetic message", 18_345L, 12_345L, true))
        assertEquals(mapOf(1L to original), fixture.rows)
        assertTrue(fixture.enqueued.isEmpty())
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun `same body with overlapping receive and sent times remains two distinct new messages`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.configureDao()
        val repository = fixture.repository()
        // The second message arrives first; its service-centre time equals the first one's DATE.
        repository.saveIncoming("01000000000", "synthetic message", 18_345L)
        repository.saveProviderMessage("01000000000", "synthetic message", 18_345L, 12_345L, true)
        repository.saveProviderMessage("01000000000", "synthetic message", 24_345L, 18_345L, true)
        assertEquals(2, fixture.rows.size)
        assertEquals(setOf(SmsKeys.canonicalKey("01000000000", "synthetic message", 12_345L),
            SmsKeys.canonicalKey("01000000000", "synthetic message", 18_345L)), fixture.rows.values.map { it.uniqueKey }.toSet())
    }

    @Test fun `provider without sent time keeps its legacy receive time key`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.configureDao()
        val repository = fixture.repository()
        repository.saveProviderMessage("01000000000", "synthetic message", 18_345L, 0L, true)
        repository.saveProviderMessage("01000000000", "synthetic message", 18_345L, 0L, true)
        assertEquals(1, fixture.rows.size)
        assertEquals(SmsKeys.uniqueKey("01000000000", "synthetic message", 18_345L), fixture.rows.values.single().uniqueKey)
    }

    @Test fun `provider without sent time and matching broadcast timestamp deduplicate in either order`() = runBlocking<Unit> {
        for (providerFirst in listOf(false, true)) {
            val fixture = Fixture()
            fixture.configureDao()
            val repository = fixture.repository()
            val provider: suspend () -> Unit = {
                repository.saveProviderMessage("01000000000", "synthetic message", 12_345L, 0L, true)
            }
            val receiver: suspend () -> Unit = {
                repository.saveIncoming("01000000000", "synthetic message", 12_345L)
            }
            if (providerFirst) { provider(); receiver() } else { receiver(); provider() }
            assertEquals(1, fixture.rows.size)
            val original = fixture.rows.values.single()
            repository.uploadOne(original.id)
            provider()
            receiver()
            repository.uploadOne(original.id)
            assertEquals(1, fixture.requests.size)
            assertEquals(original.uniqueKey, fixture.rows.values.single().uniqueKey)
            assertEquals("SENT", fixture.rows.values.single().status)
        }
    }

    @Test fun `MMS and Samsung source identities never collapse into legacy SMS or each other`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.configureDao()
        val original = fixture.row("SENT").copy(serverMessageId = "legacy-ack")
        fixture.rows[1L] = original
        val repository = fixture.repository()
        for (source in listOf("mms", "samsung_im", "samsung_ft")) {
            repository.saveOtherProviderMessage(source, 1L, original.sender, original.message, original.receivedAt, true)
        }
        assertEquals(4, fixture.rows.size)
        assertEquals(original, fixture.rows[1L])
        assertEquals(4, fixture.rows.values.map { it.uniqueKey }.toSet().size)
        assertTrue(fixture.rows.values.all { it.uniqueKey.length <= 80 })
        repository.uploadPending()
        assertEquals(3, fixture.requests.size)
        fixture.enqueued.clear()
        for (source in listOf("mms", "samsung_im", "samsung_ft")) {
            assertNull(repository.saveOtherProviderMessage(source, 1L, original.sender,
                "synthetic changed attachment description", original.receivedAt, true))
        }
        assertTrue(fixture.enqueued.isEmpty())
        assertEquals(original, fixture.rows[1L])
        repository.uploadPending()
        assertEquals(3, fixture.requests.size)
    }
}
