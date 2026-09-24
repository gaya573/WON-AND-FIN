package com.windergoodlife.smsrelay.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.windergoodlife.smsrelay.SmsRelayApp
import com.windergoodlife.smsrelay.diagnostics.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Persists to Room before scheduling upload, independently of the Activity lifecycle. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Fixed text only, before application lookup, extras access, or goAsync.
        Log.i("SmsRelay", "SmsReceiver.onReceive entered")
        val app = SmsRelayApp.get()
        handleSmsReceive(intent, app.connectionLogs,
            startAsync = { val pending = goAsync(); { pending.finish() } },
            decode = Telephony.Sms.Intents::getMessagesFromIntent,
            dispatch = { block -> CoroutineScope(Dispatchers.IO).launch { block() }; Unit },
            persist = { sender, body, timestamp -> app.repository.saveIncoming(sender, body, timestamp); Unit })
    }
}

/** Injectable boundaries let tests verify the actual receiver path without broadcasting an SMS. */
internal fun handleSmsReceive(
    intent: Intent?,
    logs: ConnectionLogSink,
    startAsync: () -> (() -> Unit),
    decode: (Intent) -> Array<out SmsMessage?>?,
    dispatch: (suspend () -> Unit) -> Unit,
    persist: suspend (String, String, Long) -> Unit
) {
    fun record(stage: ConnectionLogStage, outcome: ConnectionLogOutcome,
        reason: ConnectionFailureReason? = null, count: Int? = null) {
        // A diagnostic failure cannot suppress SMS capture.
        runCatching { logs.record(ConnectionDiagnostic(stage, outcome, reason = reason, count = count)) }
    }
    record(ConnectionLogStage.SMS_CALLBACK, ConnectionLogOutcome.STARTED)
    val action = intent?.action
    if (action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
        record(ConnectionLogStage.SMS_CALLBACK, ConnectionLogOutcome.IGNORED,
            if (action == null) ConnectionFailureReason.SMS_ACTION_MISSING else ConnectionFailureReason.SMS_ACTION_OTHER)
        return
    }
    record(ConnectionLogStage.SMS_RECEIVE, ConnectionLogOutcome.STARTED)
    val receivedIntent = checkNotNull(intent)
    val finish = try { startAsync() } catch (_: Exception) {
        record(ConnectionLogStage.SMS_ASYNC, ConnectionLogOutcome.FAILED, ConnectionFailureReason.SMS_ASYNC_UNAVAILABLE)
        return
    }
    fun finishSafely() { runCatching { finish() } }
    try {
        @Suppress("DEPRECATION")
        val pduCount = runCatching { (receivedIntent.extras?.get("pdus") as? Array<*>)?.size }.getOrNull()
        record(ConnectionLogStage.SMS_PDU,
            if (pduCount != null) ConnectionLogOutcome.SUCCEEDED else ConnectionLogOutcome.FAILED,
            if (pduCount == null) ConnectionFailureReason.SMS_PDU_UNAVAILABLE else null, pduCount)
        record(ConnectionLogStage.SMS_DECODE, ConnectionLogOutcome.STARTED)
        val messages = try { decode(receivedIntent) } catch (_: Exception) { null }
        val decodedCount = messages?.count { it != null } ?: 0
        if (messages.isNullOrEmpty() || decodedCount != messages.size) {
            record(ConnectionLogStage.SMS_DECODE, ConnectionLogOutcome.FAILED,
                ConnectionFailureReason.SMS_DECODE, decodedCount)
            finishSafely()
            return
        }
        record(ConnectionLogStage.SMS_DECODE, ConnectionLogOutcome.SUCCEEDED, count = decodedCount)
        val bySender = linkedMapOf<String, Pair<StringBuilder, Long>>()
        for (part in messages) {
            val sms = checkNotNull(part)
            val sender = sms.displayOriginatingAddress ?: sms.originatingAddress ?: ""
            val body = sms.displayMessageBody ?: sms.messageBody ?: ""
            val timestamp = if (sms.timestampMillis > 0L) sms.timestampMillis else System.currentTimeMillis()
            val previous = bySender[sender]
            if (previous == null) bySender[sender] = StringBuilder(body) to timestamp
            else previous.first.append(body)
        }
        record(ConnectionLogStage.SMS_GROUPS, ConnectionLogOutcome.SUCCEEDED, count = bySender.size)
        dispatch {
            try {
                bySender.forEach { (sender, pair) -> persist(sender, pair.first.toString(), pair.second) }
            } catch (_: Exception) {
                // Repository logs distinguish Room failure from WorkManager scheduling failure.
                record(ConnectionLogStage.SMS_RECEIVE, ConnectionLogOutcome.FAILED, ConnectionFailureReason.SMS_PROCESSING)
            } finally { finishSafely() }
        }
    } catch (_: Exception) {
        record(ConnectionLogStage.SMS_RECEIVE, ConnectionLogOutcome.FAILED, ConnectionFailureReason.SMS_PROCESSING)
        finishSafely()
    }
}
