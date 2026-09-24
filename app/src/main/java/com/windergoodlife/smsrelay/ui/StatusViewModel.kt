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
import com.windergoodlife.smsrelay.repository.RelayConnectionException
import com.windergoodlife.smsrelay.repository.RelayConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StatusViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SmsRelayApp
    private val timeFmt = SimpleDateFormat("MM.dd HH:mm", Locale.getDefault())
    val pendingCount: LiveData<Int> = app.repository.pendingFlow().asLiveData()
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
        status.awaitingPermission()
        _display.value = status.display
    }

    fun shouldAutoConnect(): Boolean = status.shouldAutoConnect()

    fun permissionDeclined(settingsRequired: Boolean) {
        status.failed(if (settingsRequired) "권한 요청이 차단되어 있습니다. 환경설정에서 SMS 권한을 허용해 주세요"
            else "문자를 전달하려면 SMS 권한이 필요합니다. 연결을 눌러 다시 허용해 주세요")
        _display.value = status.display
    }

    fun connect() {
        if (!hasSmsPermission()) { permissionDeclined(false); return }
        if (!status.startConnection()) return
        _display.value = status.display
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val health = app.repository.connectionHealth()
                    RelayConnectionManager(app.tokenStore).connect(
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
                check(app.startRelayIfReady()) { "SMS 권한을 확인한 뒤 다시 연결해 주세요" }
            }
            result.onSuccess {
                status.connected()
                _display.value = status.display
                // Recover only messages after the original consent/connection checkpoint.
                withContext(Dispatchers.IO) { runCatching { app.inboxSync.syncFromLastCheckpoint() } }
            }.onFailure {
                val detail = when {
                    it is RelayConnectionException && it.code == 401 -> "저장된 연결 정보를 사용할 수 없습니다. 관리자에게 이 휴대폰의 연결 상태를 확인해 주세요"
                    it is RelayConnectionException && it.code == 403 -> "사용 중지된 휴대폰입니다. 관리자에게 연결 상태를 확인해 주세요"
                    it is RelayConnectionException && it.code == 429 -> "연결 요청이 많습니다. 잠시 후 다시 연결해 주세요"
                    it is RelayConnectionException && it.code == 400 -> "휴대폰 정보를 확인하지 못했습니다. 앱을 다시 실행한 뒤 연결해 주세요"
                    it is RelayConnectionException -> "서버에 연결하지 못했습니다. 잠시 후 다시 연결해 주세요"
                    it is IOException -> "인터넷 연결을 확인한 뒤 다시 연결해 주세요"
                    it is IllegalStateException -> it.message ?: "연결 상태를 확인한 뒤 다시 연결해 주세요"
                    else -> "연결하지 못했습니다. 앱을 다시 실행한 뒤 연결해 주세요"
                }
                status.failed(detail)
                _display.value = status.display
            }
        }
    }
}
