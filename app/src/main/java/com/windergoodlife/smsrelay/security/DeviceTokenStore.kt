package com.windergoodlife.smsrelay.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class DeviceTokenStore internal constructor(
    private val prefs: SharedPreferences?,
    private val now: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context) : this(openSecurePreferences(context.applicationContext))

    fun isConfigured(): Boolean =
        !getBaseUrl().isNullOrBlank() &&
            !getDeviceId().isNullOrBlank() &&
            !getDeviceToken().isNullOrBlank()

    fun getBaseUrl(): String? = prefs?.getString(KEY_BASE_URL, null)?.trim()?.trimEnd('/')

    fun getDeviceId(): String? = prefs?.getString(KEY_DEVICE_ID, null)?.trim()

    fun getDeviceToken(): String? = prefs?.getString(KEY_DEVICE_TOKEN, null)?.trim()

    @Synchronized
    fun getLastSyncTime(): Long {
        val secure = requireSecurePreferences()
        val previous = secure.getLong(KEY_LAST_SYNC, 0L)
        if (previous > 0L) return previous
        // Also covers upgrades that have never completed an inbox scan.
        val start = now()
        check(secure.edit().putLong(KEY_LAST_SYNC, start).commit()) { "동기화 시작시간을 저장하지 못했습니다" }
        return start
    }

    @Synchronized
    fun setLastSyncTime(epochMs: Long) {
        val secure = requireSecurePreferences()
        val next = maxOf(secure.getLong(KEY_LAST_SYNC, 0L), epochMs)
        check(secure.edit().putLong(KEY_LAST_SYNC, next).commit()) { "동기화 기록을 저장하지 못했습니다" }
    }

    @Synchronized
    fun save(baseUrl: String, deviceId: String, deviceToken: String) {
        require(baseUrl.startsWith("https://")) { "HTTPS only" }
        val secure = requireSecurePreferences()
        val editor = secure.edit()
            .putString(KEY_BASE_URL, baseUrl.trim().trimEnd('/'))
            .putString(KEY_DEVICE_ID, deviceId.trim())
            .putString(KEY_DEVICE_TOKEN, deviceToken.trim())
        if (secure.getLong(KEY_LAST_SYNC, 0L) <= 0L) editor.putLong(KEY_LAST_SYNC, now())
        check(editor.commit()) { "연결 정보를 안전하게 저장하지 못했습니다" }
    }

    fun clearToken() {
        prefs?.edit()?.remove(KEY_DEVICE_TOKEN)?.apply()
    }

    private fun requireSecurePreferences(): SharedPreferences =
        checkNotNull(prefs) { "보안 저장소를 열지 못했습니다. 휴대폰 잠금을 해제한 뒤 앱을 다시 실행하세요" }

    companion object {
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_LAST_SYNC = "last_sync_time"

        private fun openSecurePreferences(context: Context): SharedPreferences? = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val secure = EncryptedSharedPreferences.create(
                context, "sms_relay_secure_prefs", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            migrateLegacyPreferences(context, secure)
            secure
        } catch (_: Exception) {
            // Keep the setup screen available, but never store a token without encryption.
            null
        }

        private fun migrateLegacyPreferences(context: Context, secure: SharedPreferences) {
            val legacy = context.getSharedPreferences("sms_relay_prefs_fallback", Context.MODE_PRIVATE)
            if (legacy.all.isEmpty()) return
            if (secure.getString(KEY_DEVICE_TOKEN, null).isNullOrBlank()) {
                val editor = secure.edit()
                for (key in listOf(KEY_BASE_URL, KEY_DEVICE_ID, KEY_DEVICE_TOKEN)) {
                    legacy.getString(key, null)?.let { editor.putString(key, it) }
                }
                val checkpoint = legacy.getLong(KEY_LAST_SYNC, 0L)
                if (checkpoint > 0L) editor.putLong(KEY_LAST_SYNC, checkpoint)
                check(editor.commit()) { "Secure migration failed" }
            }
            // Delete the plaintext copy only after the encrypted write has succeeded.
            check(legacy.edit().clear().commit()) { "Legacy token cleanup failed" }
        }
    }
}
