package com.windergoodlife.smsrelay.repository

import com.windergoodlife.smsrelay.BuildConfig
import com.windergoodlife.smsrelay.network.ApiClient
import com.windergoodlife.smsrelay.network.SmsApi
import com.windergoodlife.smsrelay.network.dto.RelayConnectRequest
import com.windergoodlife.smsrelay.network.dto.HeartbeatRequest
import com.windergoodlife.smsrelay.security.DeviceTokenStore
import com.windergoodlife.smsrelay.diagnostics.*
import retrofit2.Response

class RelayConnectionManager(
    private val store: DeviceTokenStore,
    private val diagnostics: ConnectionLogSink = ConnectionLogSink { },
    private val client: (String) -> SmsApi = ApiClient::createForBaseUrl
) {
    suspend fun connect(
        displayName: String, smsPermission: Boolean, batteryUnrestricted: Boolean,
        pendingCount: Int = 0, failedCount: Int = 0, lastSmsAt: String? = null,
        onProgress: suspend (ConnectionProgress) -> Unit = {}
    ) {
        check(smsPermission) { "문자 수신 권한을 허용해 주세요" }
        log(ConnectionDiagnostic(ConnectionLogStage.PREPARING, ConnectionLogOutcome.STARTED))
        val identity: DeviceTokenStore.ConnectionIdentity
        val api: SmsApi
        try {
            identity = store.prepareConnection(BuildConfig.DEFAULT_BASE_URL, displayName)
            api = client(BuildConfig.DEFAULT_BASE_URL)
            log(ConnectionDiagnostic(ConnectionLogStage.PREPARING, ConnectionLogOutcome.SUCCEEDED))
        } catch (failure: Exception) {
            log(connectionFailure(ConnectionLogStage.PREPARING, failure))
            throw failure
        }
        if (identity.needsEnrollment) {
            onProgress(ConnectionProgress.REGISTERING)
            request(ConnectionLogStage.REGISTER,
                { api.connect(identity.token, RelayConnectRequest(identity.deviceId, identity.displayName)) },
                { it?.success == true && it.connected && it.deviceId == identity.deviceId })
        }
        // Legacy credentials only verify; an invalid or disabled old phone is never silently re-enrolled.
        onProgress(ConnectionProgress.CHECKING_SERVER)
        request(ConnectionLogStage.PING,
            { api.ping("Bearer ${identity.token}", identity.token, identity.deviceId) },
            { it?.success == true })
        onProgress(ConnectionProgress.REPORTING_STATUS)
        request(ConnectionLogStage.HEARTBEAT, {
            api.heartbeat("Bearer ${identity.token}", identity.token,
                HeartbeatRequest(identity.deviceId, BuildConfig.VERSION_NAME, pendingCount, failedCount,
                    smsPermission, batteryUnrestricted, lastSmsAt), identity.deviceId)
        }, { it?.success == true })
        // An interrupted enrollment stays pending until both checks pass; no workers can start early.
        log(ConnectionDiagnostic(ConnectionLogStage.COMPLETE, ConnectionLogOutcome.STARTED))
        try { store.confirmConnection(identity.deviceId) }
        catch (failure: Exception) {
            log(connectionFailure(ConnectionLogStage.COMPLETE, failure))
            throw failure
        }
    }

    private suspend fun <T> request(stage: ConnectionLogStage, call: suspend () -> Response<T>, accepted: (T?) -> Boolean) {
        log(ConnectionDiagnostic(stage, ConnectionLogOutcome.STARTED))
        try {
            val response = call()
            if (!response.isSuccessful) throw RelayConnectionException(response.code())
            if (!accepted(response.body())) throw ConnectionVerificationException(response.code())
            log(ConnectionDiagnostic(stage, ConnectionLogOutcome.SUCCEEDED, response.code()))
        } catch (failure: Exception) {
            log(connectionFailure(stage, failure))
            throw failure
        }
    }

    private fun log(event: ConnectionDiagnostic) { runCatching { diagnostics.record(event) } }
}

enum class ConnectionProgress(val message: String) {
    REGISTERING("휴대폰 등록 중"),
    CHECKING_SERVER("서버 응답 확인 중"),
    REPORTING_STATUS("연결 상태 전송 중")
}

class RelayConnectionException(val code: Int) : Exception("relay connection failed: $code")
class ConnectionVerificationException(val httpStatus: Int) : IllegalStateException("Connection acknowledgement rejected")
