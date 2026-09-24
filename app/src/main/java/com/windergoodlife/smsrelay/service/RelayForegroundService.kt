package com.windergoodlife.smsrelay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.windergoodlife.smsrelay.R
import com.windergoodlife.smsrelay.SmsRelayApp
import com.windergoodlife.smsrelay.ui.MainActivity
import com.windergoodlife.smsrelay.worker.PendingSmsWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the relay process alive so authentication codes still arrive when the screen is off.
 *
 * <p>The SMS broadcast itself reaches a sleeping app, so receiving is not the fragile part —
 * uploading is. Doze defers network work, and an idle process with no component running is a
 * candidate for the system to kill outright, at which point a code sits in Room until somebody
 * opens the app. A foreground service removes both risks and, as a side effect, gives the operator
 * a permanent line on screen telling them the relay is alive and whether anything is stuck, which
 * is the only feedback they get on a phone nobody looks at.
 *
 * <p>The type is {@code specialUse} rather than {@code dataSync} because Android 15 caps dataSync
 * services at six hours a day and these phones must run continuously.
 */
class RelayForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clock = SimpleDateFormat("HH:mm", Locale.KOREA)
    private var observingNetwork = false
    private var online = false
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (usable && !online && SmsRelayApp.get().tokenStore.isConfigured()) PendingSmsWorker.enqueue(this@RelayForegroundService)
            online = usable
        }
        override fun onLost(network: Network) { online = false }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat(buildNotification(null, 0, 0))
        observeState()
        try {
            connectivity.registerDefaultNetworkCallback(networkCallback)
            observingNetwork = true
        } catch (_: Exception) {
            Log.w(TAG, "network recovery observer unavailable; periodic recovery remains scheduled")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (observingNetwork) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        scope.cancel()
        super.onDestroy()
    }

    private fun observeState() {
        val dao = SmsRelayApp.get().database.smsDao()
        scope.launch {
            combine(
                dao.observeLatest(),
                dao.observePendingCount(),
                dao.observeFailedCount()
            ) { latest, pending, failed -> Triple(latest?.receivedAt, pending, failed) }
                .collect { (receivedAt, pending, failed) ->
                    notificationManager().notify(
                        NOTIFICATION_ID,
                        buildNotification(receivedAt, pending, failed)
                    )
                }
        }
    }

    private fun buildNotification(receivedAt: Long?, pending: Int, failed: Int): Notification {
        val last = receivedAt?.let { "마지막 수신 ${clock.format(Date(it))}" } ?: "수신 대기 중"
        val queue = when {
            failed > 0 -> "전송 실패 ${failed}건"
            pending > 0 -> "전송 대기 ${pending}건"
            else -> "전송 완료"
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("$last · $queue")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            // Missing notification permission must not take the relay down with it; SMS still
            // lands in Room and uploads on the next opportunity.
            Log.w(TAG, "foreground start refused ${e.javaClass.simpleName}")
            stopSelf()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "중계 상태",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "중계폰이 켜져 있는지 보여줍니다"
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val TAG = "SmsRelay"
        private const val CHANNEL_ID = "relay-status"
        private const val NOTIFICATION_ID = 1001

        /** Safe to call repeatedly; the system keeps a single instance. */
        fun start(context: Context) {
            val intent = Intent(context, RelayForegroundService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "foreground service start failed ${e.javaClass.simpleName}")
            }
        }
    }
}
