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
import com.windergoodlife.smsrelay.network.dto.SmsIngestRequest
import com.windergoodlife.smsrelay.security.DeviceTokenStore
import com.windergoodlife.smsrelay.util.SafeLog
import com.windergoodlife.smsrelay.util.SmsKeys
import com.windergoodlife.smsrelay.worker.SmsUploadWorker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SmsRepository(
    private val context: Context,
    private val dao: SmsDao,
    private var api: SmsApi,
    private val tokenStore: DeviceTokenStore
) {
    // Both the individual worker and backlog worker use the application repository.
    // Hold this through the acknowledgement write so a second worker sees SENT.
    private val uploadMutex = Mutex()

    fun refreshApi() {
        api = ApiClient.recreate(tokenStore)
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
        if (localId != null && localId > 0L && tokenStore.isConfigured()) {
            SmsUploadWorker.enqueue(context, localId)
        }
        return localId
    }

    suspend fun uploadOne(localId: Long): UploadOutcome = uploadMutex.withLock {
        uploadOneLocked(localId)
    }

    private suspend fun uploadOneLocked(localId: Long): UploadOutcome {
        val entity = dao.findById(localId) ?: return UploadOutcome.GiveUp
        if (entity.status == SmsStatus.SENT.name) return UploadOutcome.Success
        if (entity.status == SmsStatus.ERROR_AUTH.name || entity.status == SmsStatus.ERROR_PAYLOAD.name) {
            return UploadOutcome.GiveUp
        }
        if (!tokenStore.isConfigured()) {
            Log.w(TAG, "upload skipped: not configured id=$localId")
            return UploadOutcome.Retry
        }

        val connectionGeneration = tokenStore.getConnectionGeneration()
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
                ),
                deviceId = deviceId
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
                    // Recovery may retain the token while an old request is still in flight.
                    fun connectionChanged() = tokenStore.getDeviceToken() != token ||
                        tokenStore.getConnectionGeneration() != connectionGeneration
                    val recovered = connectionChanged()
                    dao.updateUploadResult(
                        id = entity.id,
                        status = if (recovered) SmsStatus.FAILED.name else SmsStatus.ERROR_AUTH.name,
                        retryCount = entity.retryCount + 1,
                        lastAttemptAt = System.currentTimeMillis(),
                        serverMessageId = null,
                        httpLastStatus = code
                    )
                    // Cover confirmation occurring between the check and the database write.
                    val retry = recovered || connectionChanged()
                    if (retry && !recovered) dao.retryAuthenticationFailure(entity.id)
                    Log.e(TAG, "authentication rejected id=${entity.id} http=$code recovered=$retry")
                    if (retry) UploadOutcome.Retry else UploadOutcome.AuthError
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

    suspend fun recoverAuthenticationFailures(): Int = dao.recoverAuthenticationFailures()

    suspend fun connectionHealth(): Triple<Int, Int, String?> = Triple(
        dao.pendingCount(), dao.failedCount(), dao.latestReceivedAt()?.let(::formatIso)
    )

    /** A recovery worker must keep draining even when there are more than one batch of rows. */
    suspend fun flushPendingBatch(): Boolean {
        uploadPending()
        return dao.pendingCount() == 0
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
                ),
                deviceId = deviceId
            )
            Log.i(TAG, "heartbeat http=${response.code()} pending=$pending")
            response.isSuccessful && response.body()?.success == true
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
                deviceToken = token,
                deviceId = tokenStore.getDeviceId()
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
