package com.windergoodlife.smsrelay.network

import com.windergoodlife.smsrelay.diagnostics.*
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.UUID

/** No URL, headers, payload or exception text is copied into diagnostics. */
class RequestDiagnosticsInterceptor(private val sink: ConnectionLogSink) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val stage = when (chain.request().url.encodedPath) {
            "/api/sms-relay/connect" -> ConnectionLogStage.REGISTER
            "/api/sms-relay/ping" -> ConnectionLogStage.PING
            "/api/sms-relay/heartbeat" -> ConnectionLogStage.HEARTBEAT
            "/api/sms-relay/messages" -> ConnectionLogStage.UPLOAD
            else -> return chain.proceed(chain.request())
        }
        val requestId = UUID.randomUUID().toString()
        val request = chain.request().newBuilder().header("X-Request-ID", requestId).build()
        val start = System.nanoTime()
        fun elapsed() = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(0)
        return try {
            val response = chain.proceed(request)
            log(ConnectionDiagnostic(stage,
                if (response.isSuccessful) ConnectionLogOutcome.SUCCEEDED else ConnectionLogOutcome.FAILED,
                response.code, if (response.isSuccessful) null else ConnectionFailureReason.SERVER_RESPONSE,
                safeRequestId(response.header("X-Request-ID")) ?: requestId, elapsed(),
                retryable = !response.isSuccessful && response.code !in listOf(400, 401, 403)))
            response
        } catch (failure: IOException) {
            log(connectionFailure(stage, failure).copy(requestId = requestId, elapsedMs = elapsed(), retryable = true))
            throw failure
        }
    }

    private fun log(event: ConnectionDiagnostic) { runCatching { sink.record(event) } }
}
