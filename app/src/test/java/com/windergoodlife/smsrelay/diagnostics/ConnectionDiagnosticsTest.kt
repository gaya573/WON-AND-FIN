package com.windergoodlife.smsrelay.diagnostics

import android.content.Context
import android.content.SharedPreferences
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import com.windergoodlife.smsrelay.repository.ConnectionVerificationException
import com.windergoodlife.smsrelay.repository.RelayConnectionException
import com.windergoodlife.smsrelay.ui.connectionFailureMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

class ConnectionDiagnosticsTest {
    private val event = ConnectionDiagnostic(ConnectionLogStage.PING, ConnectionLogOutcome.STARTED)

    @Test fun `network failures use fixed categories without exception messages`() {
        val secret = "frt_sensitive 010-1234-5678 SMS-body https://private/?token=secret"
        val cases = listOf(
            SocketTimeoutException(secret) to ConnectionFailureReason.TIMEOUT,
            InterruptedIOException(secret) to ConnectionFailureReason.TIMEOUT,
            UnknownHostException(secret) to ConnectionFailureReason.DNS,
            SSLException(secret) to ConnectionFailureReason.TLS,
            IOException(secret) to ConnectionFailureReason.NETWORK,
            JsonDataException(secret) to ConnectionFailureReason.INVALID_ACK,
            JsonEncodingException(secret) to ConnectionFailureReason.INVALID_ACK,
            CancellationException(secret) to ConnectionFailureReason.CANCELLED,
            IllegalStateException(secret) to ConnectionFailureReason.LOCAL_SETUP
        )
        for ((failure, reason) in cases) {
            val diagnostic = connectionFailure(ConnectionLogStage.PING, failure)
            assertEquals(reason, diagnostic.reason)
            val buffer = ConnectionLogBuffer()
            buffer.record(diagnostic, 1)
            assertFalse(buffer.serialize().contains(secret))
            assertFalse(ConnectionLogFormatter.format(buffer.snapshot()).contains(secret))
            assertFalse(connectionFailureMessage(failure).contains(secret))
        }
    }

    @Test fun `ring restores only newest fifty typed events`() {
        val buffer = ConnectionLogBuffer()
        (1L..57L).forEach { buffer.record(event, it) }
        val restored = ConnectionLogBuffer(buffer.serialize())
        assertEquals(50, restored.snapshot().size)
        assertEquals(8L, restored.snapshot().first().timestamp)
        assertEquals(57L, restored.snapshot().last().timestamp)
        assertEquals(buffer.snapshot(), restored.snapshot())
    }

    @Test fun `persisted arbitrary text invalid enums and malformed HTTP values are never displayed`() {
        val saved = """
            1|PING|FAILED|404|SERVER_RESPONSE
            token-secret|PING|FAILED|404|SERVER_RESPONSE
            2|private-device-id|FAILED|404|SERVER_RESPONSE
            3|PING|FAILED|999|SERVER_RESPONSE
            4|PING|FAILED|404|SMS-private-body
            5|PING|FAILED|404|SERVER_RESPONSE|frt_sensitive
            -1|PING|FAILED|404|SERVER_RESPONSE
        """.trimIndent()
        val restored = ConnectionLogBuffer(saved)
        assertEquals(1, restored.snapshot().size)
        val copied = ConnectionLogFormatter.format(restored.snapshot())
        assertTrue(copied.contains("HTTP 404"))
        listOf("token-secret", "private-device-id", "SMS-private-body", "frt_sensitive").forEach { assertFalse(copied.contains(it)) }
    }

    @Test fun `invalid HTTP status is removed and missing acknowledgement stays failure despite HTTP200`() {
        assertNull(connectionFailure(ConnectionLogStage.PING, RelayConnectionException(999)).httpStatus)
        val rejected = connectionFailure(ConnectionLogStage.HEARTBEAT, ConnectionVerificationException(200))
        assertEquals(200, rejected.httpStatus)
        assertEquals(ConnectionLogOutcome.FAILED, rejected.outcome)
        assertEquals(ConnectionFailureReason.INVALID_ACK, rejected.reason)
    }

    @Test fun `404 has an actionable server-update message instead of a raw backend response`() {
        assertTrue(connectionFailureMessage(RelayConnectionException(404)).contains("서버 업데이트"))
        assertTrue(connectionFailureMessage(RelayConnectionException(404)).contains("최근 연결 로그"))
    }

    @Test fun `private storage restores the bounded log on app restart`() {
        val context = mock(Context::class.java)
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        var saved = ""
        `when`(context.getSharedPreferences("connection_diagnostics", Context.MODE_PRIVATE)).thenReturn(preferences)
        `when`(preferences.getString("recent", "")).thenAnswer { saved }
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.putString(eq("recent"), anyString())).thenAnswer { saved = it.getArgument(1); editor }
        val first = ConnectionLogStore(context)
        repeat(55) { first.record(event) }
        val restarted = ConnectionLogStore(context)
        assertEquals(50, restarted.entries.value.size)
        assertEquals(first.entries.value, restarted.entries.value)
        verify(editor, times(55)).apply()
    }

    @Test fun `new SMS receive persistence and scheduling stages survive safe log copy`() {
        val events = listOf(
            ConnectionDiagnostic(ConnectionLogStage.SMS_RECEIVE, ConnectionLogOutcome.SUCCEEDED, count = 1),
            ConnectionDiagnostic(ConnectionLogStage.LOCAL_STORE, ConnectionLogOutcome.ALREADY_STORED, count = 1),
            ConnectionDiagnostic(ConnectionLogStage.UPLOAD_QUEUE, ConnectionLogOutcome.FAILED,
                reason = ConnectionFailureReason.WORK_SCHEDULING, retryable = true)
        )
        val buffer = ConnectionLogBuffer()
        events.forEachIndexed { index, event -> buffer.record(event, index + 1L) }
        val restored = ConnectionLogBuffer(buffer.serialize())
        assertEquals(events, restored.snapshot().map { it.event })
        val text = ConnectionLogFormatter.format(restored.snapshot())
        assertTrue(text.contains("새 문자 수신"))
        assertTrue(text.contains("기존 저장 유지"))
        assertTrue(text.contains("전송 작업 예약 실패"))
    }
}
