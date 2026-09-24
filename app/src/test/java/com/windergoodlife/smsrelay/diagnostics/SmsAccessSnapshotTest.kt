package com.windergoodlife.smsrelay.diagnostics

import android.Manifest
import android.app.AppOpsManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import com.windergoodlife.smsrelay.data.LocalSmsCounts
import com.windergoodlife.smsrelay.data.SmsDao
import com.windergoodlife.smsrelay.security.DeviceTokenStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class SmsAccessSnapshotTest {
    private class Fixture {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        val dao = mock(SmsDao::class.java)
        val store = mock(DeviceTokenStore::class.java)
        init { `when`(context.contentResolver).thenReturn(resolver) }
        fun collector(op: SmsAppOp = SmsAppOp.ALLOWED) = SmsAccessSnapshotCollector(context, dao, store, { op }, { 1_800_000_000_000L })
        fun cursor(count: Int, value: Long): Cursor = mock(Cursor::class.java).also {
            `when`(it.count).thenReturn(count)
            `when`(it.moveToFirst()).thenReturn(count > 0)
            `when`(it.getColumnIndexOrThrow(anyString())).thenReturn(0)
            `when`(it.getLong(0)).thenReturn(value)
        }
    }

    @Test fun `denied permission and every non allowed AppOps never query SMS provider`() = runBlocking<Unit> {
        for (mode in SmsAppOp.entries.filter { it != SmsAppOp.ALLOWED }) {
            val f = Fixture()
            val snapshot = f.collector(mode).collect()
            assertEquals(InboxAccess.APP_OP_NOT_ALLOWED, snapshot.inboxAccess)
            assertNull(snapshot.inboxCount)
            verifyNoInteractions(f.resolver)
        }
        val f = Fixture()
        `when`(f.context.checkPermission(eq(Manifest.permission.READ_SMS), anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED)
        assertEquals(InboxAccess.PERMISSION_DENIED, f.collector().collect().inboxAccess)
        verifyNoInteractions(f.resolver)
    }

    @Test fun `snapshot queries metadata only and reports cursor relation without IDs or cursor writes`() = runBlocking<Unit> {
        val f = Fixture()
        val projections = mutableListOf<List<String>>()
        `when`(f.resolver.query(isNull<Uri>(), any(), isNull(), isNull(), anyString())).thenAnswer {
            projections += it.getArgument<Array<String>>(1).toList()
            if (it.getArgument<String>(4) == "date DESC") f.cursor(12, 1_799_999_999_000L) else f.cursor(12, 987654321L)
        }
        `when`(f.dao.diagnosticCounts()).thenReturn(LocalSmsCounts(10, 2, 1, 0, 8))
        `when`(f.store.getLastInboxSmsId()).thenReturn(987654320L)
        val snapshot = f.collector().collect()
        assertEquals(InboxAccess.AVAILABLE, snapshot.inboxAccess)
        assertEquals(12, snapshot.inboxCount)
        assertEquals(InboxCursorRelation.AHEAD, snapshot.cursorRelation)
        assertEquals(listOf(listOf("_id", "date"), listOf("_id", "date"), listOf("_id")), projections)
        assertFalse(snapshot.format().contains("98765432"))
        assertTrue(snapshot.format().contains("전송 완료 8"))
        verify(f.store, never()).getLastSyncTime()
        verify(f.store, never()).commitInboxCheckpoint(anyLong(), anyLong())
        verify(f.store, never()).setLastSyncTime(anyLong())
    }

    @Test fun `empty result and provider failure are distinct without exception data`() = runBlocking<Unit> {
        val f = Fixture()
        val emptyCursor = f.cursor(0, 0L)
        `when`(f.resolver.query(isNull<Uri>(), any(), isNull(), isNull(), anyString())).thenReturn(emptyCursor)
        val empty = f.collector().collect()
        assertEquals(InboxAccess.EMPTY, empty.inboxAccess)
        assertEquals(0, empty.inboxCount)
        assertTrue(empty.format().contains("새 문자 없음으로 단정할 수 없음"))
        assertTrue(empty.format().contains("문자함 최신 수신시각: 문자 없음"))
        `when`(f.resolver.query(isNull<Uri>(), any(), isNull(), isNull(), anyString())).thenThrow(SecurityException("secret provider URI and SQL"))
        val denied = f.collector().collect()
        assertEquals(InboxAccess.UNAVAILABLE, denied.inboxAccess)
        assertNull(denied.inboxCount)
        assertFalse(denied.format().contains("secret"))
        assertTrue(denied.format().contains("확인 불가"))
    }

    @Test fun `AppOps lookup failure uses fixed unavailable mode without querying provider`() = runBlocking<Unit> {
        val f = Fixture()
        val snapshot = SmsAccessSnapshotCollector(f.context, f.dao, f.store,
            { throw SecurityException("private package detail") }).collect()
        assertEquals(SmsAppOp.UNAVAILABLE, snapshot.readAppOp)
        assertEquals(SmsAppOp.UNAVAILABLE, snapshot.receiveAppOp)
        assertFalse(snapshot.format().contains("private"))
        verifyNoInteractions(f.resolver)
    }
}
