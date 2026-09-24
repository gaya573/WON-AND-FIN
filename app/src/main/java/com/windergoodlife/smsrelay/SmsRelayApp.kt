package com.windergoodlife.smsrelay

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.windergoodlife.smsrelay.data.SmsDatabase
import com.windergoodlife.smsrelay.diagnostics.ConnectionLogStore
import com.windergoodlife.smsrelay.network.ApiClient
import com.windergoodlife.smsrelay.repository.SmsRepository
import com.windergoodlife.smsrelay.security.DeviceTokenStore
import com.windergoodlife.smsrelay.service.RelayForegroundService
import com.windergoodlife.smsrelay.sync.MessageInboxSyncManager
import com.windergoodlife.smsrelay.sync.SyncStateStore
import com.windergoodlife.smsrelay.worker.HeartbeatWorker
import com.windergoodlife.smsrelay.worker.PendingSmsWorker

class SmsRelayApp : Application() {

    val connectionLogs: ConnectionLogStore by lazy { ConnectionLogStore(this) }
    val syncState: SyncStateStore by lazy { SyncStateStore(this) }

    lateinit var tokenStore: DeviceTokenStore
        private set
    lateinit var database: SmsDatabase
        private set
    lateinit var repository: SmsRepository
        private set
    lateinit var inboxSync: MessageInboxSyncManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        tokenStore = DeviceTokenStore(this)
        database = SmsDatabase.get(this)
        ApiClient.diagnostics = connectionLogs
        val api = ApiClient.create(tokenStore)
        repository = SmsRepository(this, database.smsDao(), api, tokenStore, connectionLogs)
        inboxSync = MessageInboxSyncManager.create(this, repository, tokenStore, connectionLogs)

        startRelayIfReady()
    }

    fun hasSmsPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun startRelayIfReady(connectionConfirmed: Boolean = false): Boolean {
        if (tokenStore.isConfigured() && hasSmsPermission()) {
            PendingSmsWorker.enqueue(this, connectionConfirmed)
            HeartbeatWorker.enqueuePeriodic(this)
            RelayForegroundService.start(this)
            return true
        }
        return false
    }

    companion object {
        @Volatile
        private var instance: SmsRelayApp? = null

        fun get(): SmsRelayApp =
            instance ?: throw IllegalStateException("SmsRelayApp not initialized")
    }
}
