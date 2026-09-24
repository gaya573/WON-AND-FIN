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
import com.windergoodlife.smsrelay.util.SafeLog
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
        val projection = arrayOf("_id", "address", "body", "date")
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
            while (c.moveToNext()) {
                val sender = if (idxAddress >= 0) c.getString(idxAddress).orEmpty() else ""
                val body = if (idxBody >= 0) c.getString(idxBody).orEmpty() else ""
                val date = if (idxDate >= 0) c.getLong(idxDate) else snapshotEnd
                if (body.isBlank()) continue
                val id = repository.saveIncoming(sender, body, date)
                if (id != null) {
                    inserted++
                    Log.i(TAG, "inbox insert sender=${SafeLog.maskSender(sender)}")
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

    suspend fun syncFromLastCheckpoint(): Int = recoveryMutex.withLock {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) return@withLock 0

        val snapshotEnd = now()
        val uri = Uri.parse("content://sms/inbox")
        val snapshotId = try {
            context.contentResolver.query(uri, arrayOf("_id"), null, null, "_id DESC")?.use { cursor ->
                if (!cursor.moveToNext()) 0L else {
                    val column = cursor.getColumnIndex("_id")
                    if (column < 0) null else cursor.getLong(column)
                }
            }
        } catch (_: SecurityException) {
            null
        } ?: return@withLock 0

        val previousId = tokenStore.getLastInboxSmsId()
        // A reset provider must not silently rewind our cursor and replay its entire history.
        if (previousId != null && snapshotId < previousId) {
            Log.w(TAG, "inbox recovery postponed: provider cursor moved backwards")
            return@withLock 0
        }
        if (previousId == snapshotId) return@withLock 0

        val selection: String
        val args: Array<String>
        if (previousId == null) {
            // Upgrade from the existing time cursor; do not extend collection before consent.
            // Include the boundary, with Room's unique key preserving already saved messages.
            selection = "date >= ? AND _id <= ?"
            args = arrayOf(tokenStore.getLastSyncTime().toString(), snapshotId.toString())
        } else {
            // Provider insertion order catches late rows and equal SMS timestamps as well.
            selection = "_id > ? AND _id <= ?"
            args = arrayOf(previousId.toString(), snapshotId.toString())
        }
        val cursor = try {
            context.contentResolver.query(uri, arrayOf("_id", "address", "body", "date"), selection, args, "_id ASC")
        } catch (_: SecurityException) {
            null
        } ?: return@withLock 0

        var inserted = 0
        cursor.use { rows ->
            val addressColumn = rows.getColumnIndex("address")
            val bodyColumn = rows.getColumnIndex("body")
            val dateColumn = rows.getColumnIndex("date")
            check(addressColumn >= 0 && bodyColumn >= 0 && dateColumn >= 0) { "Incomplete SMS provider response" }
            while (rows.moveToNext()) {
                val body = rows.getString(bodyColumn).orEmpty()
                if (body.isBlank()) continue
                val sender = rows.getString(addressColumn).orEmpty()
                if (repository.saveIncoming(sender, body, rows.getLong(dateColumn)) != null) inserted++
            }
            tokenStore.commitInboxCheckpoint(snapshotId, snapshotEnd)
        }
        Log.i(TAG, "inbox recovery inserted=$inserted")
        inserted
    }

    companion object {
        private const val TAG = "SmsRelay"
    }
}
