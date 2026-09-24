package com.windergoodlife.smsrelay

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.windergoodlife.smsrelay.data.SmsDatabase
import com.windergoodlife.smsrelay.network.ApiClient
import com.windergoodlife.smsrelay.repository.SmsRepository
import com.windergoodlife.smsrelay.security.DeviceTokenStore
import com.windergoodlife.smsrelay.service.RelayForegroundService
import com.windergoodlife.smsrelay.sync.InboxSyncManager
import com.windergoodlife.smsrelay.worker.HeartbeatWorker
import com.windergoodlife.smsrelay.worker.PendingSmsWorker

class SmsRelayApp : Application() {

    lateinit var tokenStore: DeviceTokenStore
        private set
    lateinit var database: SmsDatabase
        private set
    lateinit var repository: SmsRepository
        private set
    lateinit var inboxSync: InboxSyncManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        tokenStore = DeviceTokenStore(this)
        database = SmsDatabase.get(this)
        val api = ApiClient.create(tokenStore)
        repository = SmsRepository(this, database.smsDao(), api, tokenStore)
        inboxSync = InboxSyncManager(this, repository)

        startRelayIfReady()
    }

    fun startRelayIfReady(): Boolean {
        val smsPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        if (tokenStore.isConfigured() && smsPermission) {
            PendingSmsWorker.enqueue(this)
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
