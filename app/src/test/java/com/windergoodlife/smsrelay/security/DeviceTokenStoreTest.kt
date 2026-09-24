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
        `when`(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Boolean>(1) }
        `when`(prefs.getString(anyString(), isNull())).thenAnswer { values[it.getArgument<String>(0)] as? String }
        `when`(editor.putString(anyString(), anyString())).thenAnswer { values[it.getArgument<String>(0)] = it.getArgument<String>(1); editor }
        `when`(editor.putLong(anyString(), anyLong())).thenAnswer { values[it.getArgument<String>(0)] = it.getArgument<Long>(1); editor }
        `when`(editor.putBoolean(anyString(), anyBoolean())).thenAnswer { values[it.getArgument<String>(0)] = it.getArgument<Boolean>(1); editor }
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

    @Test fun `new connection persists one identity but cannot start workers before confirmation`() {
        val values = mutableMapOf<String, Any>()
        val prefs = preferences(values)
        val store = DeviceTokenStore(prefs) { 123_000L }
        val first = store.prepareConnection("https://api.dealerhub.co.kr", "Test phone")
        assertTrue(first.needsEnrollment)
        assertEquals(4, java.util.UUID.fromString(first.deviceId).version())
        assertTrue(first.token.matches(Regex("frt_[A-Za-z0-9_-]{43}")))
        assertFalse(store.isConfigured())
        // Process restart after a timeout must retain the original identity and still be pending.
        val restarted = DeviceTokenStore(prefs) { 456_000L }
        val retry = restarted.prepareConnection("https://api.dealerhub.co.kr", "Test phone")
        assertEquals(first.deviceId, retry.deviceId)
        assertEquals(first.token, retry.token)
        assertTrue(retry.needsEnrollment)
        assertFalse(restarted.isConfigured())
        assertEquals(123_000L, restarted.getLastSyncTime())
        restarted.confirmConnection(retry.deviceId)
        assertEquals(1L, restarted.getConnectionGeneration())
        assertTrue(restarted.isConfigured())
        assertFalse(restarted.prepareConnection("https://api.dealerhub.co.kr", "Test phone").needsEnrollment)
        assertFalse(retry.toString().contains(retry.token))
    }

    @Test fun `legacy credentials keep identity and checkpoint and normalize only API host`() {
        val values = mutableMapOf<String, Any>("base_url" to "https://old.example.test", "device_id" to "relay-01", "device_token" to "legacy-token", "last_sync_time" to 100L)
        val store = DeviceTokenStore(preferences(values))
        assertTrue(store.isConfigured())
        val identity = store.prepareConnection("https://api.dealerhub.co.kr", "Phone")
        assertFalse(identity.needsEnrollment)
        assertEquals("relay-01", identity.deviceId)
        assertEquals("legacy-token", identity.token)
        assertEquals(100L, store.getLastSyncTime())
        assertEquals("https://api.dealerhub.co.kr", store.getBaseUrl())
    }

    @Test fun `missing old credential cannot silently create a new identity`() {
        val values = mutableMapOf<String, Any>("device_id" to "disabled-old-phone")
        val store = DeviceTokenStore(preferences(values))
        assertThrows(IllegalStateException::class.java) { store.prepareConnection("https://api.dealerhub.co.kr", "Phone") }
        assertEquals("disabled-old-phone", store.getDeviceId())
    }

    @Test fun `automatic retry preserves the exact credentials and checkpoint across restart`() {
        val id = "5e0b22c9-4ea3-45ec-ae3f-51e1b479105d"
        val token = "frt_" + "A".repeat(43)
        val values = mutableMapOf<String, Any>("base_url" to "https://api.dealerhub.co.kr", "device_id" to id,
            "device_token" to token, "last_sync_time" to 4000L, "connection_confirmed" to true)
        val prefs = preferences(values)
        val store = DeviceTokenStore(prefs) { 123000L }
        val identity = store.prepareConnection("https://api.dealerhub.co.kr", "Phone")
        store.recoverUnregisteredIdentity(identity)
        assertFalse(store.isConfigured())
        assertEquals(id, store.getDeviceId())
        assertEquals(token, store.getDeviceToken())
        assertEquals(4000L, store.getLastSyncTime())
        val restarted = DeviceTokenStore(prefs) { 456000L }
        val retry = restarted.prepareConnection("https://api.dealerhub.co.kr", "Phone")
        assertTrue(retry.needsEnrollment)
        assertEquals(id, retry.deviceId)
        assertEquals(token, retry.token)
        assertEquals(4000L, restarted.getLastSyncTime())
    }

    @Test fun `retry rejects a stale snapshot instead of overwriting a changed identity`() {
        val store = DeviceTokenStore(preferences(mutableMapOf())) { 1000L }
        val before = store.prepareConnection("https://api.dealerhub.co.kr", "Phone")
        store.save("https://api.dealerhub.co.kr", before.deviceId, "changed-token")
        assertThrows(IllegalStateException::class.java) { store.recoverUnregisteredIdentity(before) }
        assertEquals("changed-token", store.getDeviceToken())
        assertTrue(store.isConfigured())
    }

    @Test fun `only canonical current format can attempt same identity automatic registration`() {
        val id = "5e0b22c9-4ea3-45ec-ae3f-51e1b479105d"
        val token = "frt_" + "A".repeat(43)
        assertTrue(DeviceTokenStore.ConnectionIdentity(id, token, "Phone", false).canEnrollAutomatically())
        assertFalse(DeviceTokenStore.ConnectionIdentity("relay-old", token, "Phone", false).canEnrollAutomatically())
        assertFalse(DeviceTokenStore.ConnectionIdentity(id.uppercase(), token, "Phone", false).canEnrollAutomatically())
        assertFalse(DeviceTokenStore.ConnectionIdentity(id, "old-token", "Phone", false).canEnrollAutomatically())
        assertFalse(DeviceTokenStore.ConnectionIdentity(id, "frt_" + "a".repeat(43), "Phone", false).canEnrollAutomatically())
    }

    @Test fun `confirmed unknown legacy identity gets one persisted candidate without losing checkpoint or other data`() {
        val values = mutableMapOf<String, Any>("base_url" to "https://api.dealerhub.co.kr", "device_id" to "legacy-phone",
            "device_token" to "legacy-secret", "last_sync_time" to 4000L, "connection_confirmed" to true, "other_setting" to "keep")
        val prefs = preferences(values)
        val store = DeviceTokenStore(prefs) { 123000L }
        val legacy = store.prepareConnection("https://api.dealerhub.co.kr", "Phone")
        val candidate = store.recoverUnregisteredIdentity(legacy)
        assertTrue(candidate.canEnrollAutomatically())
        assertNotEquals(legacy.deviceId, candidate.deviceId)
        assertNotEquals(legacy.token, candidate.token)
        assertFalse(store.isConfigured())
        assertEquals(4000L, store.getLastSyncTime())
        assertEquals("keep", values["other_setting"])
        val restarted = DeviceTokenStore(prefs) { 456000L }
        val retry = restarted.prepareConnection("https://api.dealerhub.co.kr", "Phone")
        assertEquals(candidate.deviceId, retry.deviceId)
        assertEquals(candidate.token, retry.token)
        assertTrue(retry.needsEnrollment)
        assertEquals(4000L, restarted.getLastSyncTime())
        assertThrows(IllegalStateException::class.java) { restarted.recoverUnregisteredIdentity(legacy) }
        assertEquals(candidate.deviceId, restarted.getDeviceId())
    }
}
