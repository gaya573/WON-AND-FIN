package com.windergoodlife.smsrelay.sync

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Reads incoming MMS only. The caller persists the returned prefix before committing its cursor. */
class MmsInboxReader internal constructor(
    private val access: MmsReadAccess,
    private val canReadSms: () -> Boolean,
    private val nanoTime: () -> Long = System::nanoTime
) : ProviderInboxReader {
    constructor(context: Context) : this(ResolverMmsReadAccess(context.contentResolver), {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    })

    override suspend fun readPage(afterId: Long, limit: Int): ProviderMessagePage = withContext(Dispatchers.IO) {
        require(afterId >= 0 && limit in 1..50)
        if (!canReadSms()) throw MmsInboxReadException(MmsReadFailure.PERMISSION_DENIED)
        val deadline = nanoTime() + 20_000_000_000L
        val messages = mutableListOf<ProviderInboxMessage>()
        var through = afterId
        var more = false
        var incomplete = false
        try {
            val cursor = access.query("content://mms/inbox", arrayOf("_id", "date", "msg_box", "m_type"),
                "_id > ? AND msg_box = ? AND (m_type = ? OR m_type = ?)",
                arrayOf(afterId.toString(), "1", "130", "132"), "_id ASC")
                ?: throw MmsInboxReadException(MmsReadFailure.PROVIDER_UNAVAILABLE)
            cursor.use { rows ->
                val idColumn = rows.required("_id")
                val dateColumn = rows.required("date")
                val boxColumn = rows.required("msg_box")
                val typeColumn = rows.required("m_type")
                while (rows.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    if (messages.size >= limit || nanoTime() >= deadline) { more = true; break }
                    val id = rows.getLong(idColumn)
                    if (id <= through || rows.getInt(boxColumn) != 1)
                        throw MmsInboxReadException(MmsReadFailure.INVALID_METADATA)
                    val receivedAt = receivedMillis(rows.getLong(dateColumn))
                    // A notification indication is a download placeholder, not the final message.
                    if (rows.getInt(typeColumn) == 130) { more = true; incomplete = true; break }
                    if (rows.getInt(typeColumn) != 132)
                        throw MmsInboxReadException(MmsReadFailure.INVALID_METADATA)
                    val sender = readSender(id)
                    val body = readParts(id, deadline)
                    if (sender == null || body == null) { more = true; incomplete = true; break }
                    messages += ProviderInboxMessage(id = id, sender = sender, text = body.text,
                        receivedAtMs = receivedAt, hasAttachment = body.hasAttachment, textMissing = body.textMissing)
                    through = id
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: SecurityException) {
            throw MmsInboxReadException(MmsReadFailure.PERMISSION_DENIED)
        } catch (failure: java.io.IOException) {
            throw MmsInboxReadException(MmsReadFailure.PROVIDER_UNAVAILABLE)
        } catch (failure: IllegalArgumentException) {
            throw MmsInboxReadException(MmsReadFailure.INVALID_METADATA)
        } catch (failure: IllegalStateException) {
            throw MmsInboxReadException(MmsReadFailure.PROVIDER_UNAVAILABLE)
        } catch (failure: RuntimeException) {
            throw MmsInboxReadException(MmsReadFailure.PROVIDER_UNAVAILABLE)
        }
        ProviderMessagePage(messages.toList(), more, through, pendingIncomplete = incomplete)
    }

    private suspend fun readSender(messageId: Long): String? {
        val cursor = access.query("content://mms/$messageId/addr", arrayOf("address", "type"),
            "type = ?", arrayOf("137"), "_id ASC")
            ?: throw MmsInboxReadException(MmsReadFailure.PROVIDER_UNAVAILABLE)
        return cursor.use { rows ->
            val address = rows.required("address")
            val type = rows.required("type")
            var sender: String? = null
            var count = 0
            while (rows.moveToNext()) {
                currentCoroutineContext().ensureActive()
                if (++count > 32) throw MmsInboxReadException(MmsReadFailure.INVALID_METADATA)
                if (rows.getInt(type) != 137) continue
                val candidate = rows.getString(address)?.trim().orEmpty()
                if (candidate.isBlank() || candidate == "insert-address-token") continue
                if (sender != null && sender != candidate)
                    throw MmsInboxReadException(MmsReadFailure.INVALID_METADATA)
                sender = candidate
            }
            sender
        }
    }

    private data class Body(val text: String, val hasAttachment: Boolean, val textMissing: Boolean)

    private suspend fun readParts(messageId: Long, deadline: Long): Body? {
        val cursor = access.query("content://mms/$messageId/part", arrayOf("_id", "ct", "text", "_data", "chset"),
            null, null, "_id ASC") ?: throw MmsInboxReadException(MmsReadFailure.PROVIDER_UNAVAILABLE)
        return cursor.use { rows ->
            val id = rows.required("_id")
            val mime = rows.required("ct")
            val text = rows.required("text")
            val data = rows.required("_data")
            val charset = rows.required("chset")
            val segments = mutableListOf<String>()
            var attachment = false
            var missing = false
            var count = 0
            var previousId = -1L
            var textLength = 0
            while (rows.moveToNext()) {
                currentCoroutineContext().ensureActive()
                if (nanoTime() >= deadline) throw MmsInboxReadException(MmsReadFailure.TIME_BUDGET)
                if (++count > 128) throw MmsInboxReadException(MmsReadFailure.CONTENT_TOO_LARGE)
                val partId = rows.getLong(id)
                if (partId <= previousId) throw MmsInboxReadException(MmsReadFailure.INVALID_METADATA)
                previousId = partId
                when (rows.getString(mime)?.substringBefore(';')?.trim()?.lowercase()) {
                    "application/smil" -> Unit
                    "text/plain" -> {
                        val value = if (!rows.getString(data).isNullOrBlank()) {
                            // Never follow or return the provider's private filesystem path.
                            val stream = access.openPart(partId) ?: return@use null
                            stream.use { decodePart(it, rows.getInt(charset), deadline) }
                        } else rows.getString(text)
                        if (value == null) missing = true
                        else if (value.isNotBlank()) {
                            textLength += value.length + if (segments.isEmpty()) 0 else 1
                            if (textLength > MAX_TEXT_CHARS) throw MmsInboxReadException(MmsReadFailure.CONTENT_TOO_LARGE)
                            segments += value
                        }
                    }
                    null, "" -> missing = true
                    else -> {
                        // Only downloaded attachment metadata is represented; never read binary content.
                        if (rows.getString(data).isNullOrBlank()) missing = true else attachment = true
                    }
                }
            }
            if (missing || (segments.isEmpty() && !attachment)) return@use null
            val plain = segments.joinToString("\n")
            val result = if (attachment) {
                if (plain.isEmpty()) ATTACHMENT_PLACEHOLDER else "$plain\n$ATTACHMENT_PLACEHOLDER"
            } else plain
            if (result.length > MAX_TEXT_CHARS) throw MmsInboxReadException(MmsReadFailure.CONTENT_TOO_LARGE)
            Body(result, attachment, plain.isEmpty())
        }
    }

    private suspend fun decodePart(stream: InputStream, mibEnum: Int, deadline: Long): String {
        val buffer = ByteArray(4096)
        val bytes = ByteArrayOutputStream()
        while (true) {
            currentCoroutineContext().ensureActive()
            if (nanoTime() >= deadline) throw MmsInboxReadException(MmsReadFailure.TIME_BUDGET)
            val read = stream.read(buffer)
            if (read < 0) break
            if (read == 0) throw MmsInboxReadException(MmsReadFailure.PROVIDER_UNAVAILABLE)
            if (bytes.size() + read > MAX_TEXT_BYTES) throw MmsInboxReadException(MmsReadFailure.CONTENT_TOO_LARGE)
            bytes.write(buffer, 0, read)
        }
        val name = when (mibEnum) {
            0, 106 -> "UTF-8"
            3 -> "US-ASCII"
            in 4..12 -> "ISO-8859-${mibEnum - 3}"
            17 -> "Shift_JIS"
            18 -> "EUC-JP"
            37 -> "ISO-2022-KR"
            38 -> "EUC-KR"
            39 -> "ISO-2022-JP"
            113 -> "GBK"
            114 -> "GB18030"
            1000, 1013 -> "UTF-16BE"
            1014 -> "UTF-16LE"
            1015 -> "UTF-16"
            2025 -> "GB2312"
            2026 -> "Big5"
            else -> throw MmsInboxReadException(MmsReadFailure.UNSUPPORTED_CHARSET)
        }
        try {
            return Charset.forName(name).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: CharacterCodingException) {
            throw MmsInboxReadException(MmsReadFailure.INVALID_TEXT)
        }
    }

    private fun Cursor.required(name: String): Int = getColumnIndex(name).also {
        if (it < 0) throw MmsInboxReadException(MmsReadFailure.INVALID_METADATA)
    }

    private fun receivedMillis(seconds: Long): Long {
        if (seconds <= 0 || seconds > Long.MAX_VALUE / 1000)
            throw MmsInboxReadException(MmsReadFailure.INVALID_METADATA)
        return seconds * 1000
    }

    companion object {
        const val ATTACHMENT_PLACEHOLDER = "[MMS 첨부파일 · 파일 내용은 제공되지 않습니다.]"
        private const val MAX_TEXT_CHARS = 32_000
        private const val MAX_TEXT_BYTES = 128_000
    }
}

internal interface MmsReadAccess {
    fun query(uri: String, projection: Array<String>, selection: String?, arguments: Array<String>?, sortOrder: String): Cursor?
    fun openPart(partId: Long): InputStream?
}

private class ResolverMmsReadAccess(private val resolver: ContentResolver) : MmsReadAccess {
    override fun query(uri: String, projection: Array<String>, selection: String?, arguments: Array<String>?, sortOrder: String): Cursor? =
        resolver.query(Uri.parse(uri), projection, selection, arguments, sortOrder)
    override fun openPart(partId: Long): InputStream? = resolver.openInputStream(Uri.parse("content://mms/part/$partId"))
}

enum class MmsReadFailure { PERMISSION_DENIED, PROVIDER_UNAVAILABLE, INVALID_METADATA, TIME_BUDGET,
    CONTENT_TOO_LARGE, UNSUPPORTED_CHARSET, INVALID_TEXT }

/** Fixed codes only: do not propagate provider exceptions containing addresses, bodies, or paths. */
class MmsInboxReadException(val reason: MmsReadFailure) : Exception(reason.name)
