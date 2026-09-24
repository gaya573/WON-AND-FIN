package com.windergoodlife.smsrelay.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.windergoodlife.smsrelay.BuildConfig
import com.windergoodlife.smsrelay.security.DeviceTokenStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    fun create(tokenStore: DeviceTokenStore): SmsApi {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        // Base URL is resolved per-call via a dynamic interceptor-free Retrofit; repository
        // rebuilds when needed. Default placeholder satisfies Retrofit construction.
        val base = tokenStore.getBaseUrl()?.takeIf { it.startsWith("https://") }
            ?: BuildConfig.DEFAULT_BASE_URL

        require(base.toHttpUrlOrNull() != null) { "Invalid HTTPS base URL" }

        return Retrofit.Builder()
            .baseUrl("$base/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(SmsApi::class.java)
    }

    fun recreate(tokenStore: DeviceTokenStore): SmsApi = create(tokenStore)

    /** Builds a client against a URL the operator is still typing, before anything is saved. */
    fun createForBaseUrl(baseUrl: String): SmsApi {
        require(baseUrl.startsWith("https://")) { "Invalid HTTPS base URL" }
        require(baseUrl.toHttpUrlOrNull() != null) { "Invalid HTTPS base URL" }

        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl("$baseUrl/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(SmsApi::class.java)
    }
}
