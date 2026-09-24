package com.windergoodlife.smsrelay.ui

import com.windergoodlife.smsrelay.repository.ConnectionProgress

enum class ConnectionStatus(val title: String, val symbol: String, val busy: Boolean = false) {
    IDLE("연결 전", "○"),
    PERMISSION("권한 확인 중", "…", true),
    CONNECTING("연결 중", "↻", true),
    CONNECTED("연결됨", "✓"),
    FAILED("연결 실패", "!")
}

data class ConnectionStatusDisplay(
    val status: ConnectionStatus = ConnectionStatus.IDLE,
    val detail: String = "연결을 누르면 필요한 권한을 안내합니다"
)

/** One ordered display state prevents late progress or resume events from hiding the result. */
class ConnectionStatusTracker {
    var display = ConnectionStatusDisplay()
        private set

    fun awaitingPermission() {
        if (display.status != ConnectionStatus.CONNECTING)
            display = ConnectionStatusDisplay(ConnectionStatus.PERMISSION, "문자 수신·읽기 권한을 허용해 주세요")
    }

    fun startConnection(): Boolean {
        if (display.status == ConnectionStatus.CONNECTING) return false
        display = ConnectionStatusDisplay(ConnectionStatus.CONNECTING, "연결 준비 중")
        return true
    }

    fun progress(step: ConnectionProgress) {
        if (display.status == ConnectionStatus.CONNECTING)
            display = ConnectionStatusDisplay(ConnectionStatus.CONNECTING, step.message)
    }

    fun connected() {
        if (display.status == ConnectionStatus.CONNECTING)
            display = ConnectionStatusDisplay(ConnectionStatus.CONNECTED, "수신되는 문자를 관리자 화면으로 전달합니다")
    }

    fun failed(detail: String) {
        display = ConnectionStatusDisplay(ConnectionStatus.FAILED, detail)
    }

    fun permissionsRefreshed(granted: Boolean) {
        if (!granted && display.status == ConnectionStatus.CONNECTED)
            failed("SMS 권한이 해제되었습니다. 연결을 눌러 다시 허용해 주세요")
    }

    fun shouldAutoConnect(): Boolean = display.status == ConnectionStatus.IDLE
}
