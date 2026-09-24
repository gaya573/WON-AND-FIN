package com.windergoodlife.smsrelay.network.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RelayConnectRequest(val deviceId: String, val displayName: String)

@JsonClass(generateAdapter = true)
data class RelayConnectResponse(
    val success: Boolean = false,
    val connected: Boolean = false,
    val deviceId: String? = null,
    val displayName: String? = null
)

@JsonClass(generateAdapter = true)
data class RelayHeartbeatResponse(val success: Boolean = false)
