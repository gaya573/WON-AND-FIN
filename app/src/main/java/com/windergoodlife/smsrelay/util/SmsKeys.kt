package com.windergoodlife.smsrelay.util

import java.security.MessageDigest

object SmsKeys {
    fun normalizeSender(sender: String): String =
        sender.filter { it.isDigit() || it == '+' }

    /**
     * Deterministic unique key for dedupe between BroadcastReceiver and Inbox sync.
     * Legacy format retained unchanged for existing rows and server acknowledgements.
     */
    fun uniqueKey(sender: String, message: String, receivedAtMs: Long): String {
        val bucket = receivedAtMs / 1000L
        val raw = normalizeSender(sender) + "\n" + message + "\n" + bucket
        return sha256(raw)
    }

    /** Namespaces known service-centre timestamps away from legacy provider receive-time keys. */
    fun canonicalKey(sender: String, message: String, sentAtMs: Long): String =
        "sms-v2:" + uniqueKey(sender, message, sentAtMs)

    /** Provider IDs are scoped by transport and installation on the server. No SMS key is changed. */
    fun providerKey(source: String, id: Long, sender: String, receivedAtMs: Long): String {
        require(source in listOf("mms", "samsung_im", "samsung_ft") && id > 0 && receivedAtMs > 0)
        val prefix = when (source) { "samsung_im" -> "samsung-im-v1"; "samsung_ft" -> "samsung-ft-v1"; else -> "mms-v1" }
        return "$prefix:" + sha256("$id\n${normalizeSender(sender)}\n$receivedAtMs")
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
