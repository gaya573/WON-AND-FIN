package com.windergoodlife.smsrelay.sync

import android.Manifest
import android.content.Context
import com.windergoodlife.smsrelay.diagnostics.*
import com.windergoodlife.smsrelay.repository.SmsRepository
import com.windergoodlife.smsrelay.security.DeviceTokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Each provider owns a cursor; a failed or unfinished MMS must not hide a new chat message. */
class MessageInboxSyncManager(
    private val smsRecover: suspend (Int, Long, Boolean) -> InboxSyncManager.RecoveryBatch,
    private val sources: List<Source>,
    private val checkpoint: (String) -> Long,
    private val commit: (String, Long) -> Unit,
    private val save: suspend (String, ProviderInboxMessage, Boolean) -> Long?,
    private val logs: ConnectionLogSink
) {
    data class Source(val key: String, val stage: ConnectionLogStage, val reader: ProviderInboxReader)
    private val mutex = Mutex()
    val observesSamsungChat: Boolean get() = sources.any { it.key == "samsung_im" }

    suspend fun recoverBatch(limit: Int = 50,
        deadlineNanos: Long = System.nanoTime() + 30_000_000_000L,
        enqueueCaptured: Boolean = false): InboxSyncManager.RecoveryBatch = mutex.withLock {
        require(limit in 1..250)
        var inserted = 0
        var scanned = 0
        var hasMore = false
        var failure: InboxRecoveryException? = null
        fun remember(problem: InboxRecoveryException) {
            if (failure == null || problem.retryable) failure = problem
        }
        try {
            val sms = smsRecover(limit, deadlineNanos, enqueueCaptured)
            inserted += sms.inserted
            scanned += sms.scanned
            hasMore = sms.hasMore
            sms.failure?.let(::remember)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) {
            remember(problem as? InboxRecoveryException
                ?: InboxRecoveryException(ConnectionFailureReason.PROVIDER_UNAVAILABLE, true))
        }
        for (source in sources) {
            currentCoroutineContext().ensureActive()
            if (System.nanoTime() >= deadlineNanos) { hasMore = true; break }
            diagnostic(ConnectionDiagnostic(source.stage, ConnectionLogOutcome.STARTED))
            try {
                val previous = checkpoint(source.key)
                val page = source.reader.readPage(previous, minOf(limit, 50))
                check(page.scannedThrough >= previous)
                var last = previous
                var added = 0
                for (message in page.messages) {
                    currentCoroutineContext().ensureActive()
                    check(message.id > last && message.id <= page.scannedThrough)
                    if (save(source.key, message, enqueueCaptured) != null) added++
                    last = message.id
                }
                // A failed insert/queue wake never acknowledges the provider page. Re-reading
                // a successfully saved prefix is safe because source keys remain identical.
                commit(source.key, page.scannedThrough)
                inserted += added
                scanned += page.messages.size
                if (page.pendingIncomplete) {
                    val problem = InboxRecoveryException(ConnectionFailureReason.PROVIDER_UNAVAILABLE, true)
                    remember(problem)
                    diagnostic(ConnectionDiagnostic(source.stage, ConnectionLogOutcome.WAITING,
                        reason = problem.reason, count = added, retryable = true))
                } else {
                    hasMore = hasMore || page.hasMore
                    diagnostic(ConnectionDiagnostic(source.stage, ConnectionLogOutcome.SUCCEEDED, count = added))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) {
                val classified = if (problem is SecurityException ||
                    (problem is MmsInboxReadException && problem.reason == MmsReadFailure.PERMISSION_DENIED))
                    InboxRecoveryException(ConnectionFailureReason.PERMISSION_DENIED, false)
                else InboxRecoveryException(ConnectionFailureReason.PROVIDER_UNAVAILABLE, true)
                remember(classified)
                diagnostic(ConnectionDiagnostic(source.stage, ConnectionLogOutcome.FAILED,
                    reason = classified.reason, retryable = classified.retryable))
            }
        }
        InboxSyncManager.RecoveryBatch(inserted, scanned, hasMore, failure)
    }

    private fun diagnostic(event: ConnectionDiagnostic) { runCatching { logs.record(event) } }

    companion object {
        fun create(context: Context, repository: SmsRepository, store: DeviceTokenStore,
            logs: ConnectionLogSink): MessageInboxSyncManager {
            val sms = InboxSyncManager(context, repository, store, includeFullHistory = true)
            val sources = mutableListOf(Source("mms", ConnectionLogStage.MMS_PROVIDER, MmsInboxReader(context)))
            // Only the installed exported Samsung provider's existing READ_SMS contract is used.
            // Unsupported devices keep SMS/MMS support without requesting a privileged permission.
            if (supportsSamsungChat(context)) {
                sources += Source("samsung_im", ConnectionLogStage.CHAT_PROVIDER, SamsungRcsInboxReader(context))
                sources += Source("samsung_ft", ConnectionLogStage.CHAT_FILE_PROVIDER,
                    SamsungRcsInboxReader(context, SamsungRcsInboxReader.Kind.FILE))
            }
            return MessageInboxSyncManager({ limit, deadline, enqueue -> sms.recoverBatch(limit, deadline, enqueue) },
                sources, store::getProviderCheckpoint, store::commitProviderCheckpoint,
                { source, message, enqueue -> repository.saveOtherProviderMessage(source, message.id,
                    message.sender, message.text, message.receivedAtMs, enqueue) }, logs)
        }

        fun supportsSamsungChat(context: Context): Boolean = runCatching {
            val provider = context.packageManager.resolveContentProvider("im", 0)
            provider != null && provider.enabled && provider.exported &&
                provider.packageName == "com.android.providers.telephony" &&
                provider.readPermission == Manifest.permission.READ_SMS
        }.getOrDefault(false)
    }
}
