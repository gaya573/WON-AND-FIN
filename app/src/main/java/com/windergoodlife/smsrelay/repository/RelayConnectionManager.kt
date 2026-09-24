package com.windergoodlife.smsrelay.repository

import com.windergoodlife.smsrelay.BuildConfig
import com.windergoodlife.smsrelay.network.ApiClient
import com.windergoodlife.smsrelay.network.SmsApi
import com.windergoodlife.smsrelay.network.dto.RelayConnectRequest
import com.windergoodlife.smsrelay.network.dto.HeartbeatRequest
import com.windergoodlife.smsrelay.security.DeviceTokenStore

class RelayConnectionManager(
    private val store: DeviceTokenStore,
    private val client: (String) -> SmsApi = ApiClient::createForBaseUrl
) {
    suspend fun connect(
        displayName: String, smsPermission: Boolean, batteryUnrestricted: Boolean,
        pendingCount: Int = 0, failedCount: Int = 0, lastSmsAt: String? = null
    ) {
        check(smsPermission) { "문자 수신 권한을 허용해 주세요" }
        val identity = store.prepareConnection(BuildConfig.DEFAULT_BASE_URL, displayName)
        val api = client(BuildConfig.DEFAULT_BASE_URL)
        if (identity.needsEnrollment) {
            val response = api.connect(identity.token, RelayConnectRequest(identity.deviceId, identity.displayName))
            if (!response.isSuccessful) throw RelayConnectionException(response.code())
            val ack = response.body()
            check(ack?.success == true && ack.connected && ack.deviceId == identity.deviceId) {
                "서버의 연결 확인을 받지 못했습니다. 다시 연결해 주세요"
            }
        }
        // Legacy credentials only verify; an invalid or disabled old phone is never silently re-enrolled.
        val ping = api.ping("Bearer ${identity.token}", identity.token, identity.deviceId)
        if (!ping.isSuccessful) throw RelayConnectionException(ping.code())
        check(ping.body()?.success == true) { "서버의 응답을 확인하지 못했습니다. 다시 연결해 주세요" }
        val heartbeat = api.heartbeat(
            "Bearer ${identity.token}", identity.token,
            HeartbeatRequest(identity.deviceId, BuildConfig.VERSION_NAME, pendingCount, failedCount,
                smsPermission, batteryUnrestricted, lastSmsAt), identity.deviceId
        )
        if (!heartbeat.isSuccessful) throw RelayConnectionException(heartbeat.code())
        check(heartbeat.body()?.success == true) { "서버의 연결 확인을 받지 못했습니다. 다시 연결해 주세요" }
        // An interrupted enrollment stays pending until both checks pass; no workers can start early.
        store.confirmConnection(identity.deviceId)
    }
}

class RelayConnectionException(val code: Int) : Exception("relay connection failed: $code")
