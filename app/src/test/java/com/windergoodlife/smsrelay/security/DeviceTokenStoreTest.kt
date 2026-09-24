package com.windergoodlife.smsrelay.security

import android.content.SharedPreferences
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class DeviceTokenStoreTest {
    private fun preferences(values: MutableMap<String, Any>): SharedPreferences {
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(prefs.getLong(anyString(), anyLong())).thenAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Long>(1) }
        `when`(prefs.getString(anyString(), isNull())).thenAnswer { values[it.getArgument<String>(0)] as? String }
        `when`(editor.putString(anyString(), anyString())).thenAnswer { values[it.getArgument<String>(0)] = it.getArgument<String>(1); editor }
        `when`(editor.putLong(anyString(), anyLong())).thenAnswer { values[it.getArgument<String>(0)] = it.getArgument<Long>(1); editor }
        `when`(editor.commit()).thenReturn(true)
        return prefs
    }

    @Test fun `first setup starts now rather than historical inbox`() {
        val values = mutableMapOf<String, Any>()
        val store = DeviceTokenStore(preferences(values)) { 123_000L }
        store.save("https://example.test", "test-phone", "synthetic-token")
        assertEquals(123_000L, store.getLastSyncTime())
        assertTrue(store.isConfigured())
    }

    @Test fun `token renewal preserves pending inbox recovery checkpoint`() {
        val values = mutableMapOf<String, Any>("last_sync_time" to 4_000L)
        val store = DeviceTokenStore(preferences(values)) { 123_000L }
        store.save("https://example.test", "test-phone", "renewed-token")
        assertEquals(4_000L, store.getLastSyncTime())
    }

    @Test fun `upgrade with zero checkpoint does not backfill pre-setup messages`() {
        val store = DeviceTokenStore(preferences(mutableMapOf("last_sync_time" to 0L))) { 123_000L }
        assertEquals(123_000L, store.getLastSyncTime())
    }

    @Test fun `failed secure storage cannot silently save a plaintext token`() {
        val store = DeviceTokenStore(null) { 123_000L }
        assertFalse(store.isConfigured())
        assertThrows(IllegalStateException::class.java) { store.save("https://example.test", "phone", "token") }
    }

    @Test fun `overlapping scans cannot move checkpoint backwards`() {
        val store = DeviceTokenStore(preferences(mutableMapOf("last_sync_time" to 200L)))
        store.setLastSyncTime(100L)
        assertEquals(200L, store.getLastSyncTime())
    }
}
