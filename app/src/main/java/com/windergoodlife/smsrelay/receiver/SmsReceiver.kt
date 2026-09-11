package com.windergoodlife.smsrelay.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.windergoodlife.smsrelay.SmsRelayApp
import com.windergoodlife.smsrelay.util.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manifest-registered SMS receiver. Does not depend on Activity lifecycle.
 * Persists to Room first, then enqueues WorkManager upload.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val pending = goAsync()
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            pending.finish()
            return
        }

        // Merge multipart PDUs that share the same originating address.
        val bySender = linkedMapOf<String, Pair<StringBuilder, Long>>()
        for (sms in messages) {
            val sender = sms.displayOriginatingAddress ?: sms.originatingAddress ?: ""
            val body = sms.displayMessageBody ?: sms.messageBody ?: ""
            val ts = if (sms.timestampMillis > 0L) sms.timestampMillis else System.currentTimeMillis()
            val existing = bySender[sender]
            if (existing == null) {
                bySender[sender] = StringBuilder(body) to ts
            } else {
                existing.first.append(body)
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = SmsRelayApp.get().repository
                bySender.forEach { (sender, pair) ->
                    val (body, ts) = pair
                    Log.i("SmsRelay", "recv sender=${SafeLog.maskSender(sender)}")
                    repo.saveIncoming(sender, body.toString(), ts)
                }
            } catch (e: Exception) {
                Log.e("SmsRelay", "recv persist fail ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }
}
