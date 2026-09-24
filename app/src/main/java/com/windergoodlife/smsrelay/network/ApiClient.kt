package com.windergoodlife.smsrelay.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.windergoodlife.smsrelay.security.DeviceTokenStore
import com.windergoodlife.smsrelay.diagnostics.ConnectionLogSink
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    @Volatile var diagnostics: ConnectionLogSink = ConnectionLogSink { }
    fun create(tokenStore: DeviceTokenStore): SmsApi {
        return createForBaseUrl(RelayEndpoint.resolve(tokenStore.getBaseUrl()))
    }

    fun recreate(tokenStore: DeviceTokenStore): SmsApi = create(tokenStore)

    /** Uses the same resolved origin for registration, health checks, and message uploads. */
    fun createForBaseUrl(baseUrl: String): SmsApi {
        require(baseUrl.startsWith("https://")) { "Invalid HTTPS base URL" }
        require(baseUrl.toHttpUrlOrNull() != null) { "Invalid HTTPS base URL" }

        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val client = OkHttpClient.Builder()
            .addInterceptor(RequestDiagnosticsInterceptor(diagnostics))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
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
