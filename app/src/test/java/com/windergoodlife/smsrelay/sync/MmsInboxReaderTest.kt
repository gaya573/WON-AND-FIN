package com.windergoodlife.smsrelay.sync

import android.database.Cursor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.Charset

class MmsInboxReaderTest {
    private class TrackingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }

    private class Fixture : MmsReadAccess {
        val inbox = mutableListOf<Map<String, Any?>>()
        val addresses = mutableMapOf<Long, List<Map<String, Any?>>>()
        val parts = mutableMapOf<Long, List<Map<String, Any?>>>()
        val streams = mutableMapOf<Long, InputStream>()
        val opened = mutableListOf<Long>()
        val cursors = mutableListOf<Cursor>()
        var nullUri: String? = null
        var queryFailure: RuntimeException? = null
        var permitted = true

        fun message(id: Long, date: Long = 1_800_000_000L, type: Int = 132) {
            inbox += mapOf("_id" to id, "date" to date, "msg_box" to 1, "m_type" to type)
            addresses[id] = listOf(mapOf("address" to "synthetic-sender", "type" to 137))
            parts[id] = listOf(part(id * 10, "text/plain", "synthetic $id"))
        }

        fun reader() = MmsInboxReader(this, { permitted })
        fun verifyClosed() { cursors.forEach { verify(it).close() } }

        override fun query(uri: String, projection: Array<String>, selection: String?, arguments: Array<String>?, sortOrder: String): Cursor? {
            queryFailure?.let { throw it }
            if (uri == nullUri) return null
            assertEquals("_id ASC", sortOrder)
            val source = if (uri == "content://mms/inbox") {
                assertEquals("_id > ? AND msg_box = ? AND (m_type = ? OR m_type = ?)", selection)
                assertEquals(listOf("1", "130", "132"), arguments!!.drop(1))
                inbox.filter { (it.getValue("_id") as Long) > arguments[0].toLong() &&
                    it["msg_box"] == 1 && it["m_type"] in listOf(130, 132) }.sortedBy { it["_id"] as Long }
            } else {
                val id = uri.removePrefix("content://mms/").substringBefore('/').toLong()
                if (uri.endsWith("/addr")) {
                    assertEquals("type = ?", selection)
                    assertEquals(listOf("137"), arguments!!.toList())
                    addresses[id].orEmpty()
                } else {
                    assertTrue(uri.endsWith("/part"))
                    assertNull(selection)
                    assertNull(arguments)
                    parts[id].orEmpty().sortedBy { it["_id"] as Long }
                }
            }
            return cursor(source, projection).also(cursors::add)
        }

        override fun openPart(partId: Long): InputStream? {
            opened += partId
            return streams[partId]
        }
    }

    @Test fun `equal date messages paginate by source id with milliseconds intact`() = runBlocking<Unit> {
        val f = Fixture().apply { message(7); message(8) }
        val first = f.reader().readPage(0, 1)
        assertEquals(listOf(7L), first.messages.map { it.id })
        assertEquals(1_800_000_000_000L, first.messages.single().receivedAtMs)
        assertEquals(7L, first.scannedThrough)
        assertTrue(first.hasMore)
        val next = f.reader().readPage(first.scannedThrough, 1)
        assertEquals(listOf(8L), next.messages.map { it.id })
        assertEquals(8L, next.scannedThrough)
        assertFalse(next.hasMore)
        f.verifyClosed()
    }

    @Test fun `multipart text follows part ids and ignores smil layout`() = runBlocking<Unit> {
        val f = Fixture().apply {
            message(1)
            parts[1] = listOf(part(13, "text/plain", "두 번째"), part(11, "application/smil", "layout"),
                part(12, "text/plain", "첫 번째"))
        }
        val message = f.reader().readPage(0).messages.single()
        assertEquals("첫 번째\n두 번째", message.text)
        assertEquals("synthetic-sender", message.sender)
        assertFalse(message.hasAttachment)
        f.verifyClosed()
    }

    @Test fun `file backed korean text uses declared charset and closes stream`() = runBlocking<Unit> {
        val body = "합법적인 MMS 문자 읽기"
        for (charset in listOf(106 to "UTF-8", 38 to "EUC-KR", 1015 to "UTF-16")) {
            val stream = TrackingStream(body.toByteArray(Charset.forName(charset.second)))
            val f = Fixture().apply {
                message(1)
                parts[1] = listOf(part(10, "text/plain", null, true, charset.first))
                streams[10] = stream
            }
            assertEquals(body, f.reader().readPage(0).messages.single().text)
            assertTrue(stream.closed)
            assertEquals(listOf(10L), f.opened)
            f.verifyClosed()
        }
    }

    @Test fun `image only message remains visible without opening binary attachment`() = runBlocking<Unit> {
        val f = Fixture().apply { message(1); parts[1] = listOf(part(10, "image/jpeg", null, true)) }
        val message = f.reader().readPage(0).messages.single()
        assertEquals(MmsInboxReader.ATTACHMENT_PLACEHOLDER, message.text)
        assertTrue(message.hasAttachment)
        assertTrue(message.textMissing)
        assertTrue(f.opened.isEmpty())
        f.verifyClosed()
    }

    @Test fun `download notification blocks after completed prefix without skipping later messages`() = runBlocking<Unit> {
        val f = Fixture().apply { message(1); message(2, type = 130); message(3) }
        val pending = f.reader().readPage(0)
        assertEquals(listOf(1L), pending.messages.map { it.id })
        assertEquals(1L, pending.scannedThrough)
        assertTrue(pending.pendingIncomplete)
        assertTrue(pending.hasMore)
        f.inbox[1] = f.inbox[1] + ("m_type" to 132)
        assertEquals(listOf(2L, 3L), f.reader().readPage(pending.scannedThrough).messages.map { it.id })
        f.verifyClosed()
    }

    @Test fun `missing parts or sender or undownloaded attachment are pending not completed`() = runBlocking<Unit> {
        for (variant in 0..2) {
            val f = Fixture().apply {
                message(9)
                when (variant) {
                    0 -> parts[9] = emptyList()
                    1 -> addresses[9] = listOf(mapOf("address" to "insert-address-token", "type" to 137))
                    else -> parts[9] = listOf(part(90, "image/jpeg", null, false))
                }
            }
            val page = f.reader().readPage(8)
            assertTrue(page.messages.isEmpty())
            assertEquals(8L, page.scannedThrough)
            assertTrue(page.pendingIncomplete)
            f.verifyClosed()
        }
    }

    @Test fun `null provider result cannot return a completed page`() {
        val f = Fixture().apply { message(1); nullUri = "content://mms/1/part" }
        val failure = assertThrows(MmsInboxReadException::class.java) { runBlocking { f.reader().readPage(0) } }
        assertEquals(MmsReadFailure.PROVIDER_UNAVAILABLE, failure.reason)
        f.verifyClosed()
    }

    @Test fun `permission denied does not query and provider security error is sanitized`() {
        val f = Fixture().apply { permitted = false }
        assertEquals(MmsReadFailure.PERMISSION_DENIED,
            assertThrows(MmsInboxReadException::class.java) { runBlocking { f.reader().readPage(0) } }.reason)
        assertTrue(f.cursors.isEmpty())
        f.permitted = true
        f.queryFailure = SecurityException("synthetic private path")
        val failure = assertThrows(MmsInboxReadException::class.java) { runBlocking { f.reader().readPage(0) } }
        assertEquals("PERMISSION_DENIED", failure.message)
        assertNull(failure.cause)
    }

    @Test fun `invalid charset or malformed text never becomes corrupted completed message`() {
        for ((charset, bytes, reason) in listOf(
            Triple(9_999, "text".toByteArray(), MmsReadFailure.UNSUPPORTED_CHARSET),
            Triple(106, byteArrayOf(0xc3.toByte(), 0x28), MmsReadFailure.INVALID_TEXT))) {
            val stream = TrackingStream(bytes)
            val f = Fixture().apply {
                message(1); parts[1] = listOf(part(10, "text/plain", null, true, charset)); streams[10] = stream
            }
            val failure = assertThrows(MmsInboxReadException::class.java) { runBlocking { f.reader().readPage(0) } }
            assertEquals(reason, failure.reason)
            assertTrue(stream.closed)
            f.verifyClosed()
        }
    }

    @Test fun `oversized body and overflowing timestamp stop instead of truncating or wrapping`() {
        val body = Fixture().apply { message(1); parts[1] = listOf(part(10, "text/plain", "a".repeat(32_001))) }
        assertEquals(MmsReadFailure.CONTENT_TOO_LARGE,
            assertThrows(MmsInboxReadException::class.java) { runBlocking { body.reader().readPage(0) } }.reason)
        val time = Fixture().apply { message(1, Long.MAX_VALUE) }
        assertEquals(MmsReadFailure.INVALID_METADATA,
            assertThrows(MmsInboxReadException::class.java) { runBlocking { time.reader().readPage(0) } }.reason)
        body.verifyClosed()
        time.verifyClosed()
    }

    @Test fun `byte cap closes stream before retaining oversized text`() {
        val stream = TrackingStream(ByteArray(128_001) { 65 })
        val f = Fixture().apply { message(1); parts[1] = listOf(part(10, "text/plain", null, true)); streams[10] = stream }
        assertEquals(MmsReadFailure.CONTENT_TOO_LARGE,
            assertThrows(MmsInboxReadException::class.java) { runBlocking { f.reader().readPage(0) } }.reason)
        assertTrue(stream.closed)
        f.verifyClosed()
    }

    @Test fun `cancellation is not converted to a recoverable provider failure`() {
        val f = Fixture().apply { queryFailure = CancellationException("synthetic cancellation") }
        assertThrows(CancellationException::class.java) { runBlocking { f.reader().readPage(0) } }
    }

    companion object {
        private fun part(id: Long, mime: String, text: String?, data: Boolean = false, charset: Int = 106) =
            mapOf("_id" to id, "ct" to mime, "text" to text, "_data" to if (data) "synthetic-private-path" else null,
                "chset" to charset)

        private fun cursor(rows: List<Map<String, Any?>>, columns: Array<String>): Cursor {
            val result = mock(Cursor::class.java)
            var index = -1
            `when`(result.moveToNext()).thenAnswer { ++index < rows.size }
            `when`(result.getColumnIndex(anyString())).thenAnswer { columns.indexOf(it.getArgument<String>(0)) }
            `when`(result.getString(anyInt())).thenAnswer { rows[index][columns[it.getArgument<Int>(0)]] as String? }
            `when`(result.getLong(anyInt())).thenAnswer { (rows[index][columns[it.getArgument<Int>(0)]] as Number).toLong() }
            `when`(result.getInt(anyInt())).thenAnswer { (rows[index][columns[it.getArgument<Int>(0)]] as Number).toInt() }
            return result
        }
    }
}
