package com.windergoodlife.smsrelay.sync

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class SamsungRcsInboxReaderTest {
    private data class Row(val id: Long, val body: String = "synthetic message", val date: Long = 1000,
        val sender: String = "synthetic-sender", val type: Int = 1, val hidden: Int = 0)

    private class Fixture(rows: List<Row>) {
        val resolver = mock(ContentResolver::class.java)
        val cursor = mock(Cursor::class.java)
        var selection = ""
        var after = ""
        var projection = emptyArray<String>()
        init {
            var index = -1
            val columns = listOf("_id", "address", "body", "date", "type", "hidden", "content_type")
            columns.forEachIndexed { i, name -> `when`(cursor.getColumnIndex(name)).thenReturn(i) }
            `when`(cursor.moveToNext()).thenAnswer { ++index < rows.size }
            `when`(cursor.getLong(0)).thenAnswer { rows[index].id }
            `when`(cursor.getLong(3)).thenAnswer { rows[index].date }
            `when`(cursor.getInt(4)).thenAnswer { rows[index].type }
            `when`(cursor.getInt(5)).thenAnswer { rows[index].hidden }
            `when`(cursor.getString(1)).thenAnswer { rows[index].sender }
            `when`(cursor.getString(2)).thenAnswer { rows[index].body }
            `when`(cursor.getString(6)).thenAnswer { rows[index].body }
            `when`(resolver.query(isNull<Uri>(), any(), anyString(), any(), anyString())).thenAnswer { call ->
                projection = call.getArgument(1)
                selection = call.getArgument(2)
                after = call.getArgument<Array<String>>(3).single()
                assertEquals("_id ASC", call.getArgument<String>(4))
                cursor
            }
        }
    }

    @Test fun `same timestamp messages stay distinct and paging uses provider ids`() = runBlocking<Unit> {
        val f = Fixture(listOf(Row(7), Row(9), Row(11)))
        val page = SamsungRcsInboxReader(f.resolver).readPage(5, 2)
        assertEquals(listOf(7L, 9L), page.messages.map { it.id })
        assertEquals(9L, page.scannedThrough)
        assertTrue(page.hasMore)
        assertFalse(page.pendingIncomplete)
        assertEquals("5", f.after)
        assertEquals("type = 1 AND hidden = 0 AND _id > ?", f.selection)
        verify(f.cursor).close()
    }

    @Test fun `incomplete message does not advance the completed prefix`() = runBlocking<Unit> {
        val f = Fixture(listOf(Row(1), Row(2, body = ""), Row(3)))
        val page = SamsungRcsInboxReader(f.resolver).readPage(0, 50)
        assertEquals(listOf(1L), page.messages.map { it.id })
        assertEquals(1L, page.scannedThrough)
        assertTrue(page.pendingIncomplete)
        assertFalse(page.hasMore)
        verify(f.cursor).close()
    }

    @Test fun `incomplete first row is retryable and checkpoint stays unchanged`() = runBlocking<Unit> {
        val f = Fixture(listOf(Row(8, sender = "")))
        val page = SamsungRcsInboxReader(f.resolver).readPage(7, 50)
        assertTrue(page.messages.isEmpty())
        assertEquals(7L, page.scannedThrough)
        assertTrue(page.pendingIncomplete)
    }

    @Test fun `provider ignoring inbound selection fails without returning outgoing text`() {
        val f = Fixture(listOf(Row(1, type = 2)))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { SamsungRcsInboxReader(f.resolver).readPage(0, 50) }
        }
        verify(f.cursor).close()
    }

    @Test fun `attachment becomes an honest fixed label without reading file bytes`() = runBlocking<Unit> {
        val f = Fixture(listOf(Row(1, body = "image/jpeg")))
        val page = SamsungRcsInboxReader(f.resolver, SamsungRcsInboxReader.Kind.FILE).readPage(0, 50)
        val item = page.messages.single()
        assertTrue(item.hasAttachment)
        assertTrue(item.textMissing)
        assertTrue(item.text.contains("파일 내용은 연동되지 않음"))
        assertTrue(item.text.contains("사진"))
        assertFalse(f.projection.contains("file_name"))
        assertFalse(f.projection.contains("_data"))
        verify(f.resolver, never()).openInputStream(any())
    }

    @Test fun `empty result is complete but unavailable provider is an error`() = runBlocking<Unit> {
        val f = Fixture(emptyList())
        val page = SamsungRcsInboxReader(f.resolver).readPage(7, 50)
        assertTrue(page.messages.isEmpty())
        assertEquals(7L, page.scannedThrough)
        assertFalse(page.pendingIncomplete)
        assertFalse(page.hasMore)
        val missing = mock(ContentResolver::class.java)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { SamsungRcsInboxReader(missing).readPage(0, 50) }
        }
    }

    @Test fun `out of order provider rows cannot produce a successful checkpoint`() {
        val f = Fixture(listOf(Row(9), Row(8)))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { SamsungRcsInboxReader(f.resolver).readPage(0, 50) }
        }
        verify(f.cursor).close()
    }
}
