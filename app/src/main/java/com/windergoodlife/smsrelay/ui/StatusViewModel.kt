package com.windergoodlife.smsrelay.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.windergoodlife.smsrelay.SmsRelayApp
import com.windergoodlife.smsrelay.data.SmsEntity
import com.windergoodlife.smsrelay.repository.RelayConnectionManager
import com.windergoodlife.smsrelay.diagnostics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StatusViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SmsRelayApp
    private val timeFmt = SimpleDateFormat("MM.dd HH:mm", Locale.getDefault())
    val pendingCount: LiveData<Int> = app.repository.pendingFlow().asLiveData()
    val blockedCount: LiveData<Int> = app.repository.blockedFlow().asLiveData()
    val syncPhase = app.syncState.phase.asLiveData()
    val latestSent: LiveData<SmsEntity?> = app.repository.latestSentFlow().asLiveData()
    private val status = ConnectionStatusTracker()
    private val _display = MutableLiveData(status.display)
    val display: LiveData<ConnectionStatusDisplay> = _display

    fun formatTime(epochMs: Long?): String = if (epochMs == null || epochMs <= 0) "-" else timeFmt.format(Date(epochMs))

    fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    private fun isBatteryUnrestricted(): Boolean =
        (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(app.packageName)

    fun refreshPermissionState() {
        status.permissionsRefreshed(hasSmsPermission())
        _display.value = status.display
    }

    fun awaitingPermission() {
        if (status.display.status != ConnectionStatus.PERMISSION)
            app.connectionLogs.record(ConnectionDiagnostic(ConnectionLogStage.PERMISSION, ConnectionLogOutcome.WAITING))
        status.awaitingPermission()
        _display.value = status.display
    }

    fun shouldAutoConnect(): Boolean = status.shouldAutoConnect()

    fun permissionDeclined(settingsRequired: Boolean) {
        app.connectionLogs.record(ConnectionDiagnostic(ConnectionLogStage.PERMISSION, ConnectionLogOutcome.FAILED,
            reason = if (settingsRequired) ConnectionFailureReason.SETTINGS_REQUIRED else ConnectionFailureReason.PERMISSION_DENIED))
        status.failed(if (settingsRequired) "권한 요청이 차단되어 있습니다. 환경설정에서 SMS 권한을 허용해 주세요"
            else "문자를 전달하려면 SMS 권한이 필요합니다. 연결을 눌러 다시 허용해 주세요")
        _display.value = status.display
    }

    fun connect() {
        if (!hasSmsPermission()) { permissionDeclined(false); return }
        if (!status.startConnection()) return
        _display.value = status.display
        app.connectionLogs.record(ConnectionDiagnostic(ConnectionLogStage.PERMISSION, ConnectionLogOutcome.SUCCEEDED))
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val health = app.repository.connectionHealth()
                    RelayConnectionManager(app.tokenStore, app.connectionLogs).connect(
                        "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(120),
                        hasSmsPermission(), isBatteryUnrestricted(), health.first, health.second, health.third
                    ) { progress ->
                        withContext(Dispatchers.Main) {
                            status.progress(progress)
                            _display.value = status.display
                        }
                    }
                    check(hasSmsPermission()) { "문자 수신 권한을 허용한 뒤 다시 연결해 주세요" }
                    app.repository.refreshApi()
                    app.repository.recoverAuthenticationFailures()
                }
                check(app.startRelayIfReady(connectionConfirmed = true)) { "SMS 권한을 확인한 뒤 다시 연결해 주세요" }
            }
            result.onSuccess {
                app.connectionLogs.record(ConnectionDiagnostic(ConnectionLogStage.COMPLETE, ConnectionLogOutcome.SUCCEEDED))
                status.connected()
                _display.value = status.display
                // startRelayIfReady schedules the same recovery used by boot/network restoration.
            }.onFailure {
                app.connectionLogs.record(connectionFailure(ConnectionLogStage.COMPLETE, it))
                status.failed(connectionFailureMessage(it))
                _display.value = status.display
            }
        }
    }
}
