package com.windergoodlife.smsrelay.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class DeviceTokenStore(context: Context) {
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "sms_relay_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        // Fallback keeps the app bootable if Keystore is temporarily unavailable.
        context.applicationContext.getSharedPreferences("sms_relay_prefs_fallback", Context.MODE_PRIVATE)
    }

    fun isConfigured(): Boolean =
        !getBaseUrl().isNullOrBlank() &&
            !getDeviceId().isNullOrBlank() &&
            !getDeviceToken().isNullOrBlank()

    fun getBaseUrl(): String? = prefs.getString(KEY_BASE_URL, null)?.trim()?.trimEnd('/')

    fun getDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)?.trim()

    fun getDeviceToken(): String? = prefs.getString(KEY_DEVICE_TOKEN, null)?.trim()

    fun getLastSyncTime(): Long = prefs.getLong(KEY_LAST_SYNC, 0L)

    fun setLastSyncTime(epochMs: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC, epochMs).apply()
    }

    fun save(baseUrl: String, deviceId: String, deviceToken: String) {
        require(baseUrl.startsWith("https://")) { "HTTPS only" }
        prefs.edit()
            .putString(KEY_BASE_URL, baseUrl.trim().trimEnd('/'))
            .putString(KEY_DEVICE_ID, deviceId.trim())
            .putString(KEY_DEVICE_TOKEN, deviceToken.trim())
            .apply()
    }

    fun clearToken() {
        prefs.edit().remove(KEY_DEVICE_TOKEN).apply()
    }

    companion object {
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_LAST_SYNC = "last_sync_time"
    }
}
