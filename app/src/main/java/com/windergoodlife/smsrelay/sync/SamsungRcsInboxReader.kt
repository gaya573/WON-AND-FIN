package com.windergoodlife.smsrelay.sync

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Samsung's exported Telephony ImProvider uses READ_SMS, like the SMS provider.
 * Optional: other vendors need not expose this authority. No privileged role or
 * private database access is used. Attachment bytes are never opened or uploaded.
 */
class SamsungRcsInboxReader(
    private val resolver: ContentResolver,
    private val kind: Kind = Kind.CHAT
) : ProviderInboxReader {
    constructor(context: Context, kind: Kind = Kind.CHAT) : this(context.contentResolver, kind)

    enum class Kind(val path: String) { CHAT("chat"), FILE("ft") }

    override suspend fun readPage(afterId: Long, limit: Int): ProviderMessagePage {
        require(afterId >= 0 && limit in 1..250)
        val projection = if (kind == Kind.CHAT)
            arrayOf("_id", "address", "body", "date", "type", "hidden")
        else arrayOf("_id", "address", "date", "type", "hidden", "content_type")
        val cursor = resolver.query(
            Uri.parse("content://im/${kind.path}"), projection,
            "type = 1 AND hidden = 0 AND _id > ?", arrayOf(afterId.toString()), "_id ASC"
        ) ?: throw IllegalStateException("Samsung message provider unavailable")
        val messages = mutableListOf<ProviderInboxMessage>()
        var through = afterId
        cursor.use { rows ->
            val idIndex = rows.getColumnIndex("_id")
            val senderIndex = rows.getColumnIndex("address")
            val dateIndex = rows.getColumnIndex("date")
            val typeIndex = rows.getColumnIndex("type")
            val hiddenIndex = rows.getColumnIndex("hidden")
            val bodyIndex = rows.getColumnIndex(if (kind == Kind.CHAT) "body" else "content_type")
            check(listOf(idIndex, senderIndex, dateIndex, typeIndex, hiddenIndex, bodyIndex).all { it >= 0 }) {
                "Samsung message columns unavailable"
            }
            while (rows.moveToNext()) {
                currentCoroutineContext().ensureActive()
                if (messages.size >= limit) return ProviderMessagePage(messages, true, through)
                val id = rows.getLong(idIndex)
                check(id > through) { "Samsung message order unavailable" }
                // Fail closed if an OEM ignores the selection: never relay drafts or sent rows.
                check(rows.getInt(typeIndex) == 1 && rows.getInt(hiddenIndex) == 0) {
                    "Samsung message selection unavailable"
                }
                val sender = rows.getString(senderIndex).orEmpty().trim()
                val receivedAt = rows.getLong(dateIndex)
                val rawText = rows.getString(bodyIndex).orEmpty()
                if (sender.isBlank() || receivedAt <= 0 || (kind == Kind.CHAT && rawText.isBlank())) {
                    return ProviderMessagePage(messages, false, through, pendingIncomplete = true)
                }
                val body = if (kind == Kind.CHAT) rawText else attachmentLabel(rawText)
                messages += ProviderInboxMessage(id, sender, body, receivedAt,
                    hasAttachment = kind == Kind.FILE, textMissing = kind == Kind.FILE)
                through = id
            }
        }
        return ProviderMessagePage(messages, false, through)
    }

    companion object {
        private fun attachmentLabel(mime: String): String = when {
            mime.startsWith("image/", ignoreCase = true) -> "[채팅 사진 첨부 · 파일 내용은 연동되지 않음]"
            mime.startsWith("video/", ignoreCase = true) -> "[채팅 동영상 첨부 · 파일 내용은 연동되지 않음]"
            mime.startsWith("audio/", ignoreCase = true) -> "[채팅 음성 첨부 · 파일 내용은 연동되지 않음]"
            else -> "[채팅 파일 첨부 · 파일 내용은 연동되지 않음]"
        }
    }
}
