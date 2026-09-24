package com.windergoodlife.smsrelay.repository

import com.windergoodlife.smsrelay.BuildConfig
import com.windergoodlife.smsrelay.network.ApiClient
import com.windergoodlife.smsrelay.network.RelayEndpoint
import com.windergoodlife.smsrelay.network.SmsApi
import com.windergoodlife.smsrelay.network.dto.RelayConnectRequest
import com.windergoodlife.smsrelay.network.dto.HeartbeatRequest
import com.windergoodlife.smsrelay.security.DeviceTokenStore
import com.windergoodlife.smsrelay.diagnostics.*
import retrofit2.Response
import okhttp3.ResponseBody
import com.squareup.moshi.Moshi

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
        var identity: DeviceTokenStore.ConnectionIdentity
        val api: SmsApi
        try {
            val baseUrl = RelayEndpoint.resolve(store.getBaseUrl())
            identity = store.prepareConnection(baseUrl, displayName)
            api = client(baseUrl)
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
        // Recover only after the server attests that no registry row exists. A generic 410,
        // 401 or 403 can never reset an installation or bypass a known device's authentication.
        onProgress(ConnectionProgress.CHECKING_SERVER)
        try {
            ping(api, identity)
        } catch (failure: RelayConnectionException) {
            if (failure.code != 410 || !failure.deviceNotRegistered) throw failure
            onProgress(ConnectionProgress.RECHECKING_REGISTRATION)
            log(ConnectionDiagnostic(ConnectionLogStage.AUTO_REGISTER, ConnectionLogOutcome.STARTED))
            try { identity = store.recoverUnregisteredIdentity(identity) }
            catch (storageFailure: Exception) {
                log(connectionFailure(ConnectionLogStage.AUTO_REGISTER, storageFailure))
                throw storageFailure
            }
            request(ConnectionLogStage.AUTO_REGISTER,
                { api.connect(identity.token, RelayConnectRequest(identity.deviceId, identity.displayName)) },
                { it?.success == true && it.connected && it.deviceId == identity.deviceId },
                logStart = false)
            onProgress(ConnectionProgress.CHECKING_SERVER)
            // Outside the catch above: a second 401 fails instead of recursively enrolling.
            ping(api, identity)
        }
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

    private suspend fun ping(api: SmsApi, identity: DeviceTokenStore.ConnectionIdentity) =
        request(ConnectionLogStage.PING,
            { api.ping("Bearer ${identity.token}", identity.token, identity.deviceId) },
            { it?.success == true })

    private suspend fun <T> request(stage: ConnectionLogStage, call: suspend () -> Response<T>, accepted: (T?) -> Boolean, logStart: Boolean = true) {
        if (logStart) log(ConnectionDiagnostic(stage, ConnectionLogOutcome.STARTED))
        try {
            val response = call()
            if (!response.isSuccessful) throw RelayConnectionException(response.code(),
                stage == ConnectionLogStage.PING && response.code() == 410 && isUnregisteredResponse(response.errorBody()))
            if (!accepted(response.body())) throw ConnectionVerificationException(response.code())
            log(ConnectionDiagnostic(stage, ConnectionLogOutcome.SUCCEEDED, response.code()))
        } catch (failure: Exception) {
            log(connectionFailure(stage, failure))
            throw failure
        }
    }

    private fun log(event: ConnectionDiagnostic) { runCatching { diagnostics.record(event) } }

    private fun isUnregisteredResponse(body: ResponseBody?): Boolean = runCatching {
        body?.use {
            val source = it.source()
            source.request(4097)
            if (source.buffer.size > 4096) return@use false
            Moshi.Builder().build().adapter(Map::class.java).fromJson(source.buffer.readUtf8())
                ?.get("message") == "DEVICE_NOT_REGISTERED"
        } == true
    }.getOrDefault(false)
}

enum class ConnectionProgress(val message: String) {
    REGISTERING("휴대폰 등록 중"),
    RECHECKING_REGISTRATION("자동등록 확인 중"),
    CHECKING_SERVER("서버 응답 확인 중"),
    REPORTING_STATUS("연결 상태 전송 중")
}

class RelayConnectionException(val code: Int, val deviceNotRegistered: Boolean = false) : Exception("relay connection failed: $code")
class ConnectionVerificationException(val httpStatus: Int) : IllegalStateException("Connection acknowledgement rejected")
