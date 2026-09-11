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

/**
 * Optional inbox backfill when READ_SMS is granted (company sideload devices).
 *
 * Play Store policy restricts SMS permissions for most apps — document that this
 * build targets managed relay phones only. See README.
 */
class InboxSyncManager(
    private val context: Context,
    private val repository: SmsRepository
) {
    private val tokenStore: DeviceTokenStore
        get() = com.windergoodlife.smsrelay.SmsRelayApp.get().tokenStore

    suspend fun syncSince(lastSyncExclusiveMs: Long): Int {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "inbox sync skipped: READ_SMS denied")
            return 0
        }
        var inserted = 0
        val uri = Uri.parse("content://sms/inbox")
        val projection = arrayOf("_id", "address", "body", "date")
        val selection = "date > ?"
        val args = arrayOf(lastSyncExclusiveMs.toString())
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
            var maxDate = lastSyncExclusiveMs
            while (c.moveToNext()) {
                val sender = if (idxAddress >= 0) c.getString(idxAddress).orEmpty() else ""
                val body = if (idxBody >= 0) c.getString(idxBody).orEmpty() else ""
                val date = if (idxDate >= 0) c.getLong(idxDate) else System.currentTimeMillis()
                if (body.isBlank()) continue
                val id = repository.saveIncoming(sender, body, date)
                if (id != null) {
                    inserted++
                    Log.i(TAG, "inbox insert sender=${SafeLog.maskSender(sender)}")
                }
                if (date > maxDate) maxDate = date
            }
            tokenStore.setLastSyncTime(maxOf(maxDate, System.currentTimeMillis()))
        }
        Log.i(TAG, "inbox sync inserted=$inserted")
        return inserted
    }

    suspend fun syncRecentMinutes(minutes: Int = 10): Int {
        val since = System.currentTimeMillis() - minutes * 60_000L
        return syncSince(since)
    }

    suspend fun syncFromLastCheckpoint(): Int {
        val since = tokenStore.getLastSyncTime()
        return syncSince(since)
    }

    companion object {
        private const val TAG = "SmsRelay"
    }
}
