package com.windergoodlife.smsrelay.util

import java.security.MessageDigest

object SmsKeys {
    fun normalizeSender(sender: String): String =
        sender.filter { it.isDigit() || it == '+' }

    /**
     * Deterministic unique key for dedupe between BroadcastReceiver and Inbox sync.
     * Uses second-bucket receivedAt so slight timestamp skew still collapses duplicates.
     */
    fun uniqueKey(sender: String, message: String, receivedAtMs: Long): String {
        val bucket = receivedAtMs / 1000L
        val raw = normalizeSender(sender) + "\n" + message + "\n" + bucket
        return sha256(raw)
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

object SafeLog {
    fun maskSender(sender: String): String {
        val digits = SmsKeys.normalizeSender(sender)
        if (digits.length <= 4) return "****"
        return digits.take(4) + "****" + digits.takeLast(2)
    }

    fun shortKey(uniqueKey: String): String =
        if (uniqueKey.length <= 8) uniqueKey else uniqueKey.take(8)
}
