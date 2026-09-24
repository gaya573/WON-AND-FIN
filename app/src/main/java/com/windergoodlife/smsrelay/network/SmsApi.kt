package com.windergoodlife.smsrelay.network

import com.windergoodlife.smsrelay.network.dto.HeartbeatRequest
import com.windergoodlife.smsrelay.network.dto.PingResponse
import com.windergoodlife.smsrelay.network.dto.SmsAckResponse
import com.windergoodlife.smsrelay.network.dto.SmsIngestRequest
import com.windergoodlife.smsrelay.network.dto.RelayConnectRequest
import com.windergoodlife.smsrelay.network.dto.RelayConnectResponse
import com.windergoodlife.smsrelay.network.dto.RelayHeartbeatResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface SmsApi {
    @POST("/api/sms-relay/connect")
    suspend fun connect(
        @Header("X-Device-Token") deviceToken: String,
        @Body body: RelayConnectRequest
    ): Response<RelayConnectResponse>

    @POST("/api/sms-relay/messages")
    suspend fun uploadMessage(
        @Header("Authorization") authorization: String,
        @Header("X-Idempotency-Key") idempotencyKey: String,
        @Header("X-Device-Token") deviceToken: String,
        @Body body: SmsIngestRequest,
        @Header("X-Device-Id") deviceId: String? = null
    ): Response<SmsAckResponse>

    @POST("/api/sms-relay/heartbeat")
    suspend fun heartbeat(
        @Header("Authorization") authorization: String,
        @Header("X-Device-Token") deviceToken: String,
        @Body body: HeartbeatRequest,
        @Header("X-Device-Id") deviceId: String? = null
    ): Response<RelayHeartbeatResponse>

    @POST("/api/sms-relay/ping")
    suspend fun ping(
        @Header("Authorization") authorization: String,
        @Header("X-Device-Token") deviceToken: String,
        @Header("X-Device-Id") deviceId: String? = null
    ): Response<PingResponse>
}
