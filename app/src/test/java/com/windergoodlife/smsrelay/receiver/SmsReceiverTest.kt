package com.windergoodlife.smsrelay.receiver

import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsMessage
import com.windergoodlife.smsrelay.diagnostics.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class SmsReceiverTest {
    private fun intent(action: String? = Telephony.Sms.Intents.SMS_RECEIVED_ACTION, pdus: Any? = arrayOf(byteArrayOf(1))): Intent {
        val intent = mock(Intent::class.java)
        val extras = mock(Bundle::class.java)
        `when`(intent.action).thenReturn(action)
        `when`(intent.extras).thenReturn(extras)
        @Suppress("DEPRECATION")
        `when`(extras.get("pdus")).thenReturn(pdus)
        return intent
    }
    private fun sms(sender: String, body: String): SmsMessage = mock(SmsMessage::class.java).also {
        `when`(it.displayOriginatingAddress).thenReturn(sender)
        `when`(it.displayMessageBody).thenReturn(body)
        `when`(it.timestampMillis).thenReturn(123456L)
    }

    @Test fun `callback and SMS_RECEIVED entry are recorded before goAsync can fail`() {
        val events = mutableListOf<ConnectionDiagnostic>()
        handleSmsReceive(intent(), events::add, startAsync = {
            assertEquals(listOf(ConnectionLogStage.SMS_CALLBACK, ConnectionLogStage.SMS_RECEIVE), events.map { it.stage })
            throw IllegalStateException("sensitive exception")
        }, decode = { error("must not decode") }, dispatch = { error("must not dispatch") }, persist = { _, _, _ -> fail("must not save") })
        assertEquals(ConnectionFailureReason.SMS_ASYNC_UNAVAILABLE, events.last().reason)
        val buffer = ConnectionLogBuffer()
        events.forEach { buffer.record(it, 1L) }
        val copied = ConnectionLogFormatter.format(buffer.snapshot())
        assertTrue(copied.contains("SMS_RECEIVED"))
        assertFalse(copied.contains("sensitive exception"))
    }

    @Test fun `unexpected and missing action are fixed categories without logging raw action`() {
        for (action in listOf(null, "private.raw.action")) {
            val events = mutableListOf<ConnectionDiagnostic>()
            handleSmsReceive(intent(action), events::add, { error("must not start") }, { error("must not decode") },
                { error("must not dispatch") }, { _, _, _ -> fail("must not save") })
            assertEquals(ConnectionLogOutcome.IGNORED, events.last().outcome)
            assertEquals(if (action == null) ConnectionFailureReason.SMS_ACTION_MISSING else ConnectionFailureReason.SMS_ACTION_OTHER, events.last().reason)
            assertFalse(events.toString().contains("private.raw.action"))
        }
    }

    @Test fun `multipart PDU decode count and sender groups differ and save precedes async finish`() = runBlocking<Unit> {
        val events = mutableListOf<ConnectionDiagnostic>()
        var finishes = 0
        var work: (suspend () -> Unit)? = null
        val saved = mutableListOf<String>()
        handleSmsReceive(intent(pdus = arrayOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))), events::add,
            { { finishes++ } },
            { arrayOf(sms("private sender A", "private body A"), sms("private sender A", "private body B"), sms("private sender B", "private body C")) },
            { work = it }, { _, body, _ -> assertEquals(0, finishes); saved += body })
        assertEquals(0, finishes)
        assertEquals(3, events.single { it.stage == ConnectionLogStage.SMS_PDU }.count)
        assertEquals(3, events.single { it.stage == ConnectionLogStage.SMS_DECODE && it.outcome == ConnectionLogOutcome.SUCCEEDED }.count)
        assertEquals(2, events.single { it.stage == ConnectionLogStage.SMS_GROUPS }.count)
        checkNotNull(work).invoke()
        assertEquals(listOf("private body Aprivate body B", "private body C"), saved)
        assertEquals(1, finishes)
        assertFalse(events.toString().contains("private"))
        val buffer = ConnectionLogBuffer()
        events.forEach { buffer.record(it, 1L) }
        assertEquals(events, ConnectionLogBuffer(buffer.serialize()).snapshot().map { it.event })
    }

    @Test fun `null malformed and throwing decode finish pending result and never persist`() {
        val decoders: List<(Intent) -> Array<out SmsMessage?>?> = listOf({ null }, { emptyArray() },
            { arrayOf(sms("secret", "secret"), null) }, { throw IllegalArgumentException("private PDU") })
        for (decode in decoders) {
            val events = mutableListOf<ConnectionDiagnostic>()
            var finishes = 0
            handleSmsReceive(intent(pdus = null), events::add, { { finishes++ } }, decode,
                { error("must not dispatch") }, { _, _, _ -> fail("must not persist") })
            assertEquals(1, finishes)
            assertEquals(ConnectionFailureReason.SMS_PDU_UNAVAILABLE, events.first { it.stage == ConnectionLogStage.SMS_PDU }.reason)
            assertEquals(ConnectionFailureReason.SMS_DECODE, events.last().reason)
            assertFalse(events.toString().contains("private PDU"))
        }
    }

    @Test fun `persistence failure always finishes and only logs fixed reason`() {
        var finishes = 0
        val events = mutableListOf<ConnectionDiagnostic>()
        handleSmsReceive(intent(), events::add, { { finishes++ } }, { arrayOf(sms("secret", "secret")) },
            { runBlocking { it() } }, { _, _, _ -> throw IllegalStateException("secret storage error") })
        assertEquals(1, finishes)
        assertEquals(ConnectionFailureReason.SMS_PROCESSING, events.last().reason)
        assertFalse(events.toString().contains("secret"))
    }

    @Test fun `diagnostic sink failure cannot prevent capture or async finish`() {
        var saved = 0
        var finishes = 0
        handleSmsReceive(intent(), { throw IllegalStateException("log storage") }, { { finishes++ } },
            { arrayOf(sms("secret", "secret")) }, { runBlocking { it() } }, { _, _, _ -> saved++ })
        assertEquals(1, saved)
        assertEquals(1, finishes)
    }
}
