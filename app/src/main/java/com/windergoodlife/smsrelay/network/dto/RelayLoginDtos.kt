package com.windergoodlife.smsrelay.network.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RelayLoginRequest(
    val deviceId: String,
    val displayName: String,
    val password: String
)

@JsonClass(generateAdapter = true)
data class RelayLoginResponse(
    val deviceId: String?,
    val displayName: String?,
    val deviceToken: String?
)
