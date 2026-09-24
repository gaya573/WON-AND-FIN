package com.windergoodlife.smsrelay.network

import com.windergoodlife.smsrelay.diagnostics.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import java.net.SocketTimeoutException

class RequestDiagnosticsInterceptorTest {
    @Test fun `request and response share safe correlation id without headers or body disclosure`() {
        val events = mutableListOf<ConnectionDiagnostic>()
        val chain = mock(Interceptor.Chain::class.java)
        val request = Request.Builder().url("https://example.invalid/api/sms-relay/messages?secret=private")
            .header("Authorization", "Bearer private-token").build()
        `when`(chain.request()).thenReturn(request)
        var sentId: String? = null
        `when`(chain.proceed(any(Request::class.java) ?: request)).thenAnswer {
            val sent = it.getArgument<Request>(0)
            sentId = sent.header("X-Request-ID")
            Response.Builder().request(sent).protocol(Protocol.HTTP_1_1).code(503).message("private body")
                .header("X-Request-ID", sentId!!.uppercase()).body("private message".toResponseBody()).build()
        }
        RequestDiagnosticsInterceptor(ConnectionLogSink(events::add)).intercept(chain).close()
        assertNotNull(safeRequestId(sentId))
        assertEquals(sentId, events.single().requestId)
        assertEquals(503, events.single().httpStatus)
        assertEquals(true, events.single().retryable)
        assertFalse(events.toString().contains("private"))
    }

    @Test fun `network failure keeps generated correlation but discards exception text`() {
        val events = mutableListOf<ConnectionDiagnostic>()
        val chain = mock(Interceptor.Chain::class.java)
        val request = Request.Builder().url("https://example.invalid/api/sms-relay/ping").build()
        `when`(chain.request()).thenReturn(request)
        `when`(chain.proceed(any(Request::class.java) ?: request)).thenThrow(SocketTimeoutException("private token and phone"))
        assertThrows(SocketTimeoutException::class.java) { RequestDiagnosticsInterceptor(ConnectionLogSink(events::add)).intercept(chain) }
        assertNotNull(events.single().requestId)
        assertEquals(ConnectionFailureReason.TIMEOUT, events.single().reason)
        assertFalse(events.toString().contains("private"))
    }

    @Test fun `log buffer sanitizes correlation and numeric fields and restores old format`() {
        val buffer = ConnectionLogBuffer("1|PING|FAILED|401|SERVER_RESPONSE")
        buffer.record(ConnectionDiagnostic(ConnectionLogStage.UPLOAD, ConnectionLogOutcome.FAILED, requestId = "phone-private",
            elapsedMs = -1, count = -2, retryable = true), 2)
        assertNull(buffer.snapshot().last().event.requestId)
        assertNull(buffer.snapshot().last().event.elapsedMs)
        assertNull(buffer.snapshot().last().event.count)
        assertFalse(buffer.serialize().contains("phone-private"))
        assertEquals(buffer.snapshot(), ConnectionLogBuffer(buffer.serialize()).snapshot())
    }
}
