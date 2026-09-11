package com.windergoodlife.smsrelay.network

import com.windergoodlife.smsrelay.network.dto.HeartbeatRequest
import com.windergoodlife.smsrelay.network.dto.PingResponse
import com.windergoodlife.smsrelay.network.dto.RelayLoginRequest
import com.windergoodlife.smsrelay.network.dto.RelayLoginResponse
import com.windergoodlife.smsrelay.network.dto.SmsAckResponse
import com.windergoodlife.smsrelay.network.dto.SmsIngestRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface SmsApi {
    /** Trades the shared relay password for a device token. No token needed to call this. */
    @POST("/api/sms-relay/login")
    suspend fun login(@Body body: RelayLoginRequest): Response<RelayLoginResponse>

    @POST("/api/sms-relay/messages")
    suspend fun uploadMessage(
        @Header("Authorization") authorization: String,
        @Header("X-Idempotency-Key") idempotencyKey: String,
        @Header("X-Device-Token") deviceToken: String,
        @Body body: SmsIngestRequest
    ): Response<SmsAckResponse>

    @POST("/api/sms-relay/heartbeat")
    suspend fun heartbeat(
        @Header("Authorization") authorization: String,
        @Header("X-Device-Token") deviceToken: String,
        @Body body: HeartbeatRequest
    ): Response<Unit>

    @POST("/api/sms-relay/ping")
    suspend fun ping(
        @Header("Authorization") authorization: String,
        @Header("X-Device-Token") deviceToken: String
    ): Response<PingResponse>
}
