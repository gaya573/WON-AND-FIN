package com.windergoodlife.smsrelay.network

import com.windergoodlife.smsrelay.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object RelayEndpoint {
    private const val PREVIOUS_PRODUCTION = "https://api.dealerhub.co.kr"

    fun resolve(savedBaseUrl: String?): String {
        val saved = savedBaseUrl?.trim()?.trimEnd('/').orEmpty()
        val selected = if (saved.isBlank() || saved == PREVIOUS_PRODUCTION)
            BuildConfig.DEFAULT_BASE_URL else saved
        require(selected.toHttpUrlOrNull()?.isHttps == true) { "Invalid HTTPS base URL" }
        return selected
    }
}
