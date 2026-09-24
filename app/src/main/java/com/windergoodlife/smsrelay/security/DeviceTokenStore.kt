package com.windergoodlife.smsrelay.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

class DeviceTokenStore internal constructor(
    private val prefs: SharedPreferences?,
    private val now: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context) : this(openSecurePreferences(context.applicationContext))

    fun isConfigured(): Boolean =
        !getBaseUrl().isNullOrBlank() &&
            !getDeviceId().isNullOrBlank() &&
            !getDeviceToken().isNullOrBlank() &&
            prefs?.getBoolean(KEY_CONNECTED, true) == true

    fun getBaseUrl(): String? = prefs?.getString(KEY_BASE_URL, null)?.trim()?.trimEnd('/')

    fun getDeviceId(): String? = prefs?.getString(KEY_DEVICE_ID, null)?.trim()

    fun getDeviceToken(): String? = prefs?.getString(KEY_DEVICE_TOKEN, null)?.trim()

    fun getConnectionGeneration(): Long = prefs?.getLong(KEY_CONNECTION_GENERATION, 0L) ?: 0L

    /** Save the same installation identity before a request so an uncertain retry cannot rotate it. */
    @Synchronized
    fun prepareConnection(baseUrl: String, displayName: String): ConnectionIdentity {
        require(baseUrl.startsWith("https://")) { "HTTPS only" }
        val secure = requireSecurePreferences()
        val existingId = getDeviceId()
        val existingToken = getDeviceToken()
        if (!existingId.isNullOrBlank() && !existingToken.isNullOrBlank()) {
            check(secure.edit().putString(KEY_BASE_URL, baseUrl).commit()) { "연결 정보를 저장하지 못했습니다" }
            return ConnectionIdentity(
                existingId, existingToken,
                secure.getString(KEY_DISPLAY_NAME, null) ?: displayName,
                !secure.getBoolean(KEY_CONNECTED, true)
            )
        }
        check(existingId.isNullOrBlank() && existingToken.isNullOrBlank()) {
            "기존 연결 정보가 손상되었습니다. 관리자에게 이 휴대폰의 연결 상태를 확인해 주세요"
        }
        val identity = ConnectionIdentity(
            UUID.randomUUID().toString(),
            "frt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }),
            displayName.take(120),
            true
        )
        val editor = secure.edit()
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_DEVICE_ID, identity.deviceId)
            .putString(KEY_DEVICE_TOKEN, identity.token)
            .putString(KEY_DISPLAY_NAME, identity.displayName)
            .putBoolean(KEY_CONNECTED, false)
        if (secure.getLong(KEY_LAST_SYNC, 0L) <= 0L) editor.putLong(KEY_LAST_SYNC, now())
        check(editor.commit()) { "연결 정보를 안전하게 저장하지 못했습니다" }
        return identity
    }

    @Synchronized
    fun recoverUnregisteredIdentity(identity: ConnectionIdentity): ConnectionIdentity {
        val secure = requireSecurePreferences()
        check(getDeviceId() == identity.deviceId && getDeviceToken() == identity.token) {
            "연결 정보가 변경되었습니다. 다시 연결해 주세요"
        }
        // Called only after the server positively attests that this identity has no registry row.
        // Current-format pairs are retained; old manual identities receive one persisted candidate.
        val candidate = if (identity.canEnrollAutomatically())
            ConnectionIdentity(identity.deviceId, identity.token, identity.displayName, true)
        else ConnectionIdentity(UUID.randomUUID().toString(),
            "frt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }),
            identity.displayName.take(120), true)
        val editor = secure.edit().putString(KEY_DEVICE_ID, candidate.deviceId)
            .putString(KEY_DEVICE_TOKEN, candidate.token).putString(KEY_DISPLAY_NAME, candidate.displayName)
            .putBoolean(KEY_CONNECTED, false)
        if (secure.getLong(KEY_LAST_SYNC, 0L) <= 0L) editor.putLong(KEY_LAST_SYNC, now())
        check(editor.commit()) { "연결 상태를 저장하지 못했습니다" }
        return candidate
    }

    @Synchronized
    fun confirmConnection(deviceId: String) {
        val secure = requireSecurePreferences()
        check(getDeviceId() == deviceId) { "연결 정보가 변경되었습니다. 다시 연결해 주세요" }
        check(secure.edit().putBoolean(KEY_CONNECTED, true)
            .putLong(KEY_CONNECTION_GENERATION, getConnectionGeneration() + 1).commit()) { "연결 상태를 저장하지 못했습니다" }
    }

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
            .putBoolean(KEY_CONNECTED, true)
        if (secure.getLong(KEY_LAST_SYNC, 0L) <= 0L) editor.putLong(KEY_LAST_SYNC, now())
        check(editor.commit()) { "연결 정보를 안전하게 저장하지 못했습니다" }
    }

    fun clearToken() {
        prefs?.edit()?.remove(KEY_DEVICE_TOKEN)?.putBoolean(KEY_CONNECTED, false)?.apply()
    }

    private fun requireSecurePreferences(): SharedPreferences =
        checkNotNull(prefs) { "보안 저장소를 열지 못했습니다. 휴대폰 잠금을 해제한 뒤 앱을 다시 실행하세요" }

    companion object {
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_LAST_SYNC = "last_sync_time"
        private const val KEY_CONNECTED = "connection_confirmed"
        private const val KEY_CONNECTION_GENERATION = "connection_generation"
        private const val KEY_DISPLAY_NAME = "display_name"

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

    // Never use the default data-class toString, which would print the private token.
    class ConnectionIdentity(
        val deviceId: String,
        val token: String,
        val displayName: String,
        val needsEnrollment: Boolean
    ) {
        fun canEnrollAutomatically(): Boolean {
            if (!deviceId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))) return false
            if (!token.matches(Regex("frt_[A-Za-z0-9_-]{43}"))) return false
            val encoded = token.substring(4)
            return runCatching {
                val raw = Base64.getUrlDecoder().decode(encoded)
                raw.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(raw) == encoded
            }.getOrDefault(false)
        }
    }
}
