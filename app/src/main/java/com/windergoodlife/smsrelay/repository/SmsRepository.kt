package com.windergoodlife.smsrelay.repository

import android.content.Context
import android.util.Log
import com.windergoodlife.smsrelay.BuildConfig
import com.windergoodlife.smsrelay.data.SmsDao
import com.windergoodlife.smsrelay.data.SmsEntity
import com.windergoodlife.smsrelay.data.SmsStatus
import com.windergoodlife.smsrelay.network.ApiClient
import com.windergoodlife.smsrelay.network.SmsApi
import com.windergoodlife.smsrelay.network.dto.HeartbeatRequest
import com.windergoodlife.smsrelay.network.dto.RelayLoginRequest
import com.windergoodlife.smsrelay.network.dto.RelayLoginResponse
import com.windergoodlife.smsrelay.network.dto.SmsIngestRequest
import com.windergoodlife.smsrelay.security.DeviceTokenStore
import com.windergoodlife.smsrelay.util.SafeLog
import com.windergoodlife.smsrelay.util.SmsKeys
import com.windergoodlife.smsrelay.worker.SmsUploadWorker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SmsRepository(
    private val context: Context,
    private val dao: SmsDao,
    private var api: SmsApi,
    private val tokenStore: DeviceTokenStore
) {
    fun refreshApi() {
        api = ApiClient.recreate(tokenStore)
    }

    /**
     * Asks the server whether the relay password is right, against the base URL the operator just
     * typed rather than the stored one, so nothing is persisted before the answer comes back.
     */
    suspend fun verifyRelayPassword(
        baseUrl: String,
        deviceId: String,
        password: String
    ): RelayLoginResponse {
        val probe = ApiClient.createForBaseUrl(baseUrl)
        val response = probe.login(RelayLoginRequest(deviceId, deviceId, password))
        if (!response.isSuccessful) {
            Log.i(TAG, "relay login rejected status=${response.code()}")
            throw RelayLoginException(response.code())
        }
        return response.body() ?: throw RelayLoginException(response.code())
    }

    /**
     * Persist first, then enqueue upload. Returns local id when newly inserted, null if duplicate.
     */
    suspend fun saveIncoming(sender: String, message: String, receivedAtMs: Long): Long? {
        val key = SmsKeys.uniqueKey(sender, message, receivedAtMs)
        val entity = SmsEntity(
            uniqueKey = key,
            sender = sender,
            message = message,
            receivedAt = receivedAtMs,
            status = SmsStatus.PENDING.name
        )
        val rowId = dao.insertIgnore(entity)
        if (rowId == -1L) {
            Log.i(TAG, "dup key=${SafeLog.shortKey(key)} sender=${SafeLog.maskSender(sender)}")
            return null
        }
        val localId = if (rowId > 0L) rowId else dao.findByUniqueKey(key)?.id
        Log.i(TAG, "saved id=$localId key=${SafeLog.shortKey(key)} sender=${SafeLog.maskSender(sender)} status=PENDING")
        if (localId != null && localId > 0L) {
            SmsUploadWorker.enqueue(context, localId)
        }
        return localId
    }

    suspend fun uploadOne(localId: Long): UploadOutcome {
        val entity = dao.findById(localId) ?: return UploadOutcome.GiveUp
        if (entity.status == SmsStatus.SENT.name) return UploadOutcome.Success
        if (entity.status == SmsStatus.ERROR_AUTH.name || entity.status == SmsStatus.ERROR_PAYLOAD.name) {
            return UploadOutcome.GiveUp
        }
        if (!tokenStore.isConfigured()) {
            Log.w(TAG, "upload skipped: not configured id=$localId")
            return UploadOutcome.Retry
        }

        val token = tokenStore.getDeviceToken().orEmpty()
        val deviceId = tokenStore.getDeviceId().orEmpty()
        val now = System.currentTimeMillis()
        dao.updateUploadResult(
            id = entity.id,
            status = SmsStatus.SENDING.name,
            retryCount = entity.retryCount,
            lastAttemptAt = now,
            serverMessageId = entity.serverMessageId,
            httpLastStatus = entity.httpLastStatus
        )

        return try {
            val response = api.uploadMessage(
                authorization = "Bearer $token",
                idempotencyKey = entity.uniqueKey,
                deviceToken = token,
                body = SmsIngestRequest(
                    uniqueKey = entity.uniqueKey,
                    sender = entity.sender,
                    message = entity.message,
                    receivedAt = formatIso(entity.receivedAt),
                    deviceId = deviceId
                )
            )
            val code = response.code()
            when {
                response.isSuccessful -> {
                    val ack = response.body()
                    if (ack?.success == true && !ack.messageId.isNullOrBlank()) {
                        dao.updateUploadResult(
                            id = entity.id,
                            status = SmsStatus.SENT.name,
                            retryCount = entity.retryCount,
                            lastAttemptAt = System.currentTimeMillis(),
                            serverMessageId = ack.messageId,
                            httpLastStatus = code
                        )
                        Log.i(
                            TAG,
                            "SENT id=${entity.id} key=${SafeLog.shortKey(entity.uniqueKey)} http=$code"
                        )
                        UploadOutcome.Success
                    } else {
                        dao.updateUploadResult(
                            id = entity.id,
                            status = SmsStatus.FAILED.name,
                            retryCount = entity.retryCount + 1,
                            lastAttemptAt = System.currentTimeMillis(),
                            serverMessageId = null,
                            httpLastStatus = code
                        )
                        Log.w(TAG, "ACK missing id=${entity.id} http=$code retry=${entity.retryCount + 1}")
                        UploadOutcome.Retry
                    }
                }
                code == 401 || code == 403 -> {
                    dao.updateUploadResult(
                        id = entity.id,
                        status = SmsStatus.ERROR_AUTH.name,
                        retryCount = entity.retryCount + 1,
                        lastAttemptAt = System.currentTimeMillis(),
                        serverMessageId = null,
                        httpLastStatus = code
                    )
                    Log.e(TAG, "ERROR_AUTH id=${entity.id} http=$code")
                    UploadOutcome.AuthError
                }
                code == 400 -> {
                    dao.updateUploadResult(
                        id = entity.id,
                        status = SmsStatus.ERROR_PAYLOAD.name,
                        retryCount = entity.retryCount + 1,
                        lastAttemptAt = System.currentTimeMillis(),
                        serverMessageId = null,
                        httpLastStatus = code
                    )
                    Log.e(TAG, "ERROR_PAYLOAD id=${entity.id} http=$code")
                    UploadOutcome.GiveUp
                }
                else -> {
                    dao.updateUploadResult(
                        id = entity.id,
                        status = SmsStatus.FAILED.name,
                        retryCount = entity.retryCount + 1,
                        lastAttemptAt = System.currentTimeMillis(),
                        serverMessageId = null,
                        httpLastStatus = code
                    )
                    Log.w(TAG, "FAILED id=${entity.id} http=$code retry=${entity.retryCount + 1}")
                    UploadOutcome.Retry
                }
            }
        } catch (e: Exception) {
            dao.updateUploadResult(
                id = entity.id,
                status = SmsStatus.FAILED.name,
                retryCount = entity.retryCount + 1,
                lastAttemptAt = System.currentTimeMillis(),
                serverMessageId = null,
                httpLastStatus = entity.httpLastStatus
            )
            Log.w(TAG, "network fail id=${entity.id} retry=${entity.retryCount + 1} ${e.javaClass.simpleName}")
            UploadOutcome.Retry
        }
    }

    suspend fun uploadPending(limit: Int = 50): Int {
        var sent = 0
        dao.findRetryable(limit).forEach { row ->
            when (uploadOne(row.id)) {
                UploadOutcome.Success -> sent++
                UploadOutcome.AuthError -> return sent
                else -> Unit
            }
        }
        return sent
    }

    suspend fun sendHeartbeat(smsPermission: Boolean, batteryUnrestricted: Boolean): Boolean {
        if (!tokenStore.isConfigured()) return false
        val token = tokenStore.getDeviceToken().orEmpty()
        val deviceId = tokenStore.getDeviceId().orEmpty()
        return try {
            val pending = dao.pendingCount()
            val failed = dao.failedCount()
            val lastSmsAt = dao.latestReceivedAt()?.let { SmsRepository.formatIso(it) }
            val response = api.heartbeat(
                authorization = "Bearer $token",
                deviceToken = token,
                body = HeartbeatRequest(
                    deviceId = deviceId,
                    appVersion = BuildConfig.VERSION_NAME,
                    pendingCount = pending,
                    failedCount = failed,
                    smsPermission = smsPermission,
                    batteryUnrestricted = batteryUnrestricted,
                    lastSmsAt = lastSmsAt
                )
            )
            Log.i(TAG, "heartbeat http=${response.code()} pending=$pending")
            response.isSuccessful
        } catch (e: Exception) {
            Log.w(TAG, "heartbeat fail ${e.javaClass.simpleName}")
            false
        }
    }

    suspend fun ping(): Pair<Boolean, String> {
        if (!tokenStore.isConfigured()) return false to "미설정"
        return try {
            refreshApi()
            val token = tokenStore.getDeviceToken().orEmpty()
            val response = api.ping(
                authorization = "Bearer $token",
                deviceToken = token
            )
            val ok = response.isSuccessful && response.body()?.success != false
            ok to "HTTP ${response.code()}"
        } catch (e: Exception) {
            false to e.javaClass.simpleName
        }
    }

    fun pendingFlow() = dao.observePendingCount()
    fun failedFlow() = dao.observeFailedCount()
    fun authErrorFlow() = dao.observeAuthErrorCount()
    fun latestFlow() = dao.observeLatest()
    fun latestSentFlow() = dao.observeLatestSent()

    companion object {
        private const val TAG = "SmsRelay"
        private val ISO: DateTimeFormatter =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())

        fun formatIso(epochMs: Long): String = ISO.format(Instant.ofEpochMilli(epochMs))
    }

    enum class UploadOutcome {
        Success, Retry, AuthError, GiveUp
    }
}
