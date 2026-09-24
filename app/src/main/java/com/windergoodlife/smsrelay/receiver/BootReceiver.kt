package com.windergoodlife.smsrelay.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.windergoodlife.smsrelay.SmsRelayApp
import com.windergoodlife.smsrelay.service.RelayForegroundService
import com.windergoodlife.smsrelay.worker.HeartbeatWorker
import com.windergoodlife.smsrelay.worker.PendingSmsWorker

/**
 * After reboot, flush PENDING rows. Requires the user to have completed setup once.
 * Force-stop cannot be recovered from here.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i("SmsRelay", "boot completed")
        try {
            val app = context.applicationContext
            if (app is SmsRelayApp) app.startRelayIfReady()
        } catch (e: Exception) {
            Log.w("SmsRelay", "boot enqueue fail ${e.javaClass.simpleName}")
        }
    }
}
