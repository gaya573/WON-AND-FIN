package com.windergoodlife.smsrelay.network.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SmsIngestRequest(
    val uniqueKey: String,
    val sender: String,
    val message: String,
    val receivedAt: String,
    val deviceId: String
)

@JsonClass(generateAdapter = true)
data class SmsAckResponse(
    val success: Boolean? = null,
    val messageId: String? = null,
    val receivedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class HeartbeatRequest(
    val deviceId: String,
    val appVersion: String,
    val pendingCount: Int,
    val failedCount: Int,
    val smsPermission: Boolean,
    val batteryUnrestricted: Boolean,
    val lastSmsAt: String? = null
)

@JsonClass(generateAdapter = true)
data class PingResponse(
    val success: Boolean? = null,
    val serverTime: String? = null
)
