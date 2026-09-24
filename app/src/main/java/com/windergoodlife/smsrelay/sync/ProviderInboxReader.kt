package com.windergoodlife.smsrelay.sync

/** A read-only source. Its checkpoint is separate from every other provider. */
interface ProviderInboxReader {
    suspend fun readPage(afterId: Long, limit: Int = 50): ProviderMessagePage
}

data class ProviderInboxMessage(
    val id: Long,
    val sender: String,
    val text: String,
    val receivedAtMs: Long,
    val hasAttachment: Boolean = false,
    val textMissing: Boolean = false
)

/** Commit scannedThrough only after all messages in this completed prefix are durable. */
data class ProviderMessagePage(
    val messages: List<ProviderInboxMessage>,
    val hasMore: Boolean,
    val scannedThrough: Long,
    val pendingIncomplete: Boolean = false
)
