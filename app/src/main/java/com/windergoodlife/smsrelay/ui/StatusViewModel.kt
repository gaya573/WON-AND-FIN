package com.windergoodlife.smsrelay.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.windergoodlife.smsrelay.SmsRelayApp
import com.windergoodlife.smsrelay.data.SmsEntity
import com.windergoodlife.smsrelay.worker.HeartbeatWorker
import com.windergoodlife.smsrelay.worker.PendingSmsWorker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StatusViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SmsRelayApp
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    val pendingCount: LiveData<Int> = app.repository.pendingFlow().asLiveData()
    val failedCount: LiveData<Int> = app.repository.failedFlow().asLiveData()
    val authErrorCount: LiveData<Int> = app.repository.authErrorFlow().asLiveData()
    val latestSms: LiveData<SmsEntity?> = app.repository.latestFlow().asLiveData()
    val latestSent: LiveData<SmsEntity?> = app.repository.latestSentFlow().asLiveData()

    private val _message = MutableLiveData<String>("")
    val message: LiveData<String> = _message

    private val _serverOk = MutableLiveData<Boolean?>(null)
    val serverOk: LiveData<Boolean?> = _serverOk

    fun formatTime(epochMs: Long?): String =
        if (epochMs == null || epochMs <= 0L) "-" else timeFmt.format(Date(epochMs))

    fun hasSmsPermission(): Boolean {
        val ctx = getApplication<Application>()
        val recv = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val read = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        return recv && read
    }

    fun isBatteryUnrestricted(): Boolean {
        val ctx = getApplication<Application>()
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    fun ping() {
        viewModelScope.launch {
            val (ok, detail) = app.repository.ping()
            _serverOk.value = ok
            _message.value = if (ok) "서버 정상 ($detail)" else "서버 오류 ($detail)"
        }
    }

    fun retryPending() {
        viewModelScope.launch {
            PendingSmsWorker.enqueue(getApplication())
            val sent = app.repository.uploadPending()
            _message.value = "재전송 시도 완료 (이번 회차 SENT≈$sent)"
        }
    }

    fun syncInbox() {
        viewModelScope.launch {
            val n = app.inboxSync.syncRecentMinutes(10)
            _message.value = "최근 SMS 동기화: 신규 $n 건"
        }
    }

    fun healthCheckText(): String {
        val lines = mutableListOf<String>()
        lines += if (app.tokenStore.isConfigured()) "설정: 완료" else "설정: 미완료 → 초기 설정 필요"
        lines += if (hasSmsPermission()) "SMS 권한: 허용" else "SMS 권한: 부족"
        lines += if (isBatteryUnrestricted()) "배터리: Unrestricted" else "배터리: 최적화 대상 (제외 권장)"
        lines += "강제 종료 복구: 불가 — 앱을 다시 실행해야 함"
        lines += "Doze: heartbeat/전송 지연 가능"
        return lines.joinToString("\n")
    }

    fun ensureWorkers() {
        if (app.tokenStore.isConfigured()) {
            PendingSmsWorker.enqueue(getApplication())
            HeartbeatWorker.enqueuePeriodic(getApplication())
        }
    }

    fun syncFromCheckpoint() {
        viewModelScope.launch {
            val n = app.inboxSync.syncFromLastCheckpoint()
            _message.value = "체크포인트 동기화: 신규 $n 건"
        }
    }
}
