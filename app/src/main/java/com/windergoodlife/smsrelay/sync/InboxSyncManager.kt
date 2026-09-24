package com.windergoodlife.smsrelay.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.windergoodlife.smsrelay.repository.SmsRepository
import com.windergoodlife.smsrelay.security.DeviceTokenStore
import com.windergoodlife.smsrelay.diagnostics.ConnectionFailureReason
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Optional inbox backfill when READ_SMS is granted (company sideload devices).
 *
 * Play Store policy restricts SMS permissions for most apps — document that this
 * build targets managed relay phones only. See README.
 */
class InboxSyncManager(
    private val context: Context,
    private val repository: SmsRepository,
    private val tokenStore: DeviceTokenStore = com.windergoodlife.smsrelay.SmsRelayApp.get().tokenStore,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val recoveryMutex = Mutex()

    suspend fun syncSince(lastSyncExclusiveMs: Long, advanceCheckpoint: Boolean = true): Int {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "inbox sync skipped: READ_SMS denied")
            return 0
        }
        // Capture before querying, so arrivals during a scan remain eligible for the next scan.
        val snapshotEnd = now()
        if (snapshotEnd <= lastSyncExclusiveMs) return 0
        var inserted = 0
        val uri = Uri.parse("content://sms/inbox")
        val projection = arrayOf("_id", "address", "body", "date", "date_sent")
        val selection = "date > ? AND date <= ?"
        val args = arrayOf(lastSyncExclusiveMs.toString(), snapshotEnd.toString())
        val cursor: Cursor? = try {
            context.contentResolver.query(uri, projection, selection, args, "date ASC")
        } catch (e: SecurityException) {
            Log.w(TAG, "inbox query blocked ${e.javaClass.simpleName}")
            return 0
        }
        cursor?.use { c ->
            val idxAddress = c.getColumnIndex("address")
            val idxBody = c.getColumnIndex("body")
            val idxDate = c.getColumnIndex("date")
            val idxSent = c.getColumnIndex("date_sent")
            while (c.moveToNext()) {
                val sender = if (idxAddress >= 0) c.getString(idxAddress).orEmpty() else ""
                val body = if (idxBody >= 0) c.getString(idxBody).orEmpty() else ""
                val date = if (idxDate >= 0) c.getLong(idxDate) else snapshotEnd
                if (body.isBlank()) continue
                val id = saveProvider(sender, body, date, if (idxSent >= 0) c.getLong(idxSent) else 0L, true)
                if (id != null) {
                    inserted++
                    Log.i(TAG, "inbox insert")
                }
            }
            if (advanceCheckpoint) tokenStore.setLastSyncTime(snapshotEnd)
        }
        Log.i(TAG, "inbox sync inserted=$inserted")
        return inserted
    }

    suspend fun syncRecentMinutes(minutes: Int = 10): Int {
        val since = now() - minutes * 60_000L
        // A partial manual rescan must not skip an older automatic recovery backlog.
        return syncSince(since, advanceCheckpoint = false)
    }

    suspend fun syncFromLastCheckpoint(): Int = recoverBatch().inserted

    data class RecoveryBatch(val inserted: Int, val scanned: Int, val hasMore: Boolean)

    /** Commit only a successfully persisted bounded page, keeping the first-consent time boundary. */
    suspend fun recoverBatch(limit: Int = 250, deadlineNanos: Long = System.nanoTime() + 30_000_000_000L,
        enqueueCaptured: Boolean = false): RecoveryBatch = recoveryMutex.withLock {
        require(limit > 0)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) throw InboxRecoveryException(ConnectionFailureReason.PERMISSION_DENIED, false)

        val snapshotEnd = now()
        val uri = Uri.parse("content://sms/inbox")
        val providerMaxId = try {
            context.contentResolver.query(uri, arrayOf("_id"), null, null, "_id DESC")?.use { cursor ->
                if (!cursor.moveToNext()) 0L else {
                    val column = cursor.getColumnIndex("_id")
                    if (column < 0) null else cursor.getLong(column)
                }
            }
        } catch (_: SecurityException) {
            throw InboxRecoveryException(ConnectionFailureReason.PERMISSION_DENIED, false)
        } catch (_: Exception) {
            throw InboxRecoveryException(ConnectionFailureReason.PROVIDER_UNAVAILABLE, true)
        } ?: throw InboxRecoveryException(ConnectionFailureReason.PROVIDER_UNAVAILABLE, true)

        val previousId = tokenStore.getLastInboxSmsId()
        val upgradeSnapshot = tokenStore.getInboxUpgradeSnapshot()
        val snapshotId = upgradeSnapshot ?: providerMaxId
        // A reset provider must not silently rewind our cursor and replay its entire history.
        if (previousId != null && providerMaxId < previousId) {
            throw InboxRecoveryException(ConnectionFailureReason.PROVIDER_RESET, false)
        }
        if (previousId == snapshotId) {
            if (upgradeSnapshot != null) tokenStore.finishInboxUpgrade()
            return@withLock RecoveryBatch(0, 0, providerMaxId > snapshotId)
        }

        val selection: String
        val args: Array<String>
        if (previousId == null) {
            if (upgradeSnapshot == null) tokenStore.beginInboxUpgrade(snapshotId)
            // Upgrade from the existing time cursor; do not extend collection before consent.
            // Include the boundary, with Room's unique key preserving already saved messages.
            selection = "date >= ? AND _id <= ?"
            args = arrayOf(tokenStore.getLastSyncTime().toString(), snapshotId.toString())
        } else if (upgradeSnapshot != null) {
            // A page boundary must not turn the initial consent-limited scan into an all-time scan.
            selection = "date >= ? AND _id > ? AND _id <= ?"
            args = arrayOf(tokenStore.getLastSyncTime().toString(), previousId.toString(), snapshotId.toString())
        } else {
            // Provider insertion order catches late rows and equal SMS timestamps as well.
            selection = "_id > ? AND _id <= ?"
            args = arrayOf(previousId.toString(), snapshotId.toString())
        }
        val cursor = try {
            context.contentResolver.query(uri, arrayOf("_id", "address", "body", "date", "date_sent"), selection, args, "_id ASC")
        } catch (_: SecurityException) {
            throw InboxRecoveryException(ConnectionFailureReason.PERMISSION_DENIED, false)
        } catch (_: Exception) {
            throw InboxRecoveryException(ConnectionFailureReason.PROVIDER_UNAVAILABLE, true)
        } ?: throw InboxRecoveryException(ConnectionFailureReason.PROVIDER_UNAVAILABLE, true)

        var inserted = 0
        var scanned = 0
        var hasMore = false
        var lastSavedId: Long? = null
        cursor.use { rows ->
            val idColumn = rows.getColumnIndex("_id")
            val addressColumn = rows.getColumnIndex("address")
            val bodyColumn = rows.getColumnIndex("body")
            val dateColumn = rows.getColumnIndex("date")
            val sentColumn = rows.getColumnIndex("date_sent")
            if (idColumn < 0 || addressColumn < 0 || bodyColumn < 0 || dateColumn < 0)
                throw InboxRecoveryException(ConnectionFailureReason.PROVIDER_UNAVAILABLE, true)
            while (rows.moveToNext()) {
                currentCoroutineContext().ensureActive()
                if (scanned >= limit || (scanned > 0 && System.nanoTime() >= deadlineNanos)) {
                    hasMore = true
                    break
                }
                val rowId = rows.getLong(idColumn)
                if (rowId <= (lastSavedId ?: previousId ?: -1L) || rowId > snapshotId)
                    throw InboxRecoveryException(ConnectionFailureReason.PROVIDER_UNAVAILABLE, true)
                val body = rows.getString(bodyColumn).orEmpty()
                if (body.isNotBlank()) {
                    val sender = rows.getString(addressColumn).orEmpty()
                    if (saveProvider(sender, body, rows.getLong(dateColumn),
                            if (sentColumn >= 0) rows.getLong(sentColumn) else 0L, enqueueCaptured) != null) inserted++
                }
                scanned++
                lastSavedId = rowId
            }
            // While upgrading the time cursor, retain its consent boundary across partial pages.
            tokenStore.commitInboxCheckpoint(if (hasMore) checkNotNull(lastSavedId) else minOf(snapshotId, providerMaxId),
                if (hasMore) tokenStore.getLastSyncTime() else snapshotEnd)
            if (!hasMore && (previousId == null || upgradeSnapshot != null)) tokenStore.finishInboxUpgrade()
        }
        Log.i(TAG, "inbox recovery inserted=$inserted")
        RecoveryBatch(inserted, scanned, hasMore || providerMaxId > snapshotId)
    }

    private suspend fun saveProvider(sender: String, body: String, date: Long, sentAt: Long, enqueue: Boolean): Long? =
        if (sentAt > 0L) repository.saveProviderMessage(sender, body, date, sentAt, enqueue)
        else if (enqueue) repository.saveProviderMessage(sender, body, date, 0L, true)
        else repository.saveRecovered(sender, body, date)

    companion object {
        private const val TAG = "SmsRelay"
    }
}

class InboxRecoveryException(val reason: ConnectionFailureReason, val retryable: Boolean) :
    IllegalStateException("Inbox recovery unavailable")
