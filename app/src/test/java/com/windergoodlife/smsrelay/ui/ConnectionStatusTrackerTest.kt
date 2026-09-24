package com.windergoodlife.smsrelay.ui

import com.windergoodlife.smsrelay.repository.ConnectionProgress
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConnectionStatusTrackerTest {
    @Test fun `one click waits for permission then continues connection without another tap`() {
        val flow = ConnectionPermissionFlow()
        val status = ConnectionStatusTracker()
        assertEquals(ConnectionPermissionFlow.Action.REQUEST_SMS, flow.connectTapped(false))
        status.awaitingPermission()
        assertEquals("권한 확인 중", status.display.status.title)
        assertTrue(status.display.status.busy)
        assertEquals(ConnectionPermissionFlow.Action.CONNECT, flow.permissionResult(true, false))
        assertTrue(status.startConnection())
        assertEquals("연결 중", status.display.status.title)
        assertFalse(status.startConnection())
    }

    @Test fun `permission denial is an actionable sticky failure across resume`() {
        val status = ConnectionStatusTracker()
        status.awaitingPermission()
        status.failed("SMS 권한을 허용해 주세요")
        status.permissionsRefreshed(false)
        status.permissionsRefreshed(true)
        assertEquals("연결 실패", status.display.status.title)
        assertEquals("SMS 권한을 허용해 주세요", status.display.detail)
        assertFalse(status.display.status.busy)
        assertFalse(status.shouldAutoConnect())
    }

    @Test fun `network progress advances before a terminal success and ignores late progress`() {
        val status = ConnectionStatusTracker()
        status.startConnection()
        ConnectionProgress.values().forEach {
            status.progress(it)
            assertEquals(it.message, status.display.detail)
            assertEquals(ConnectionStatus.CONNECTING, status.display.status)
        }
        status.connected()
        val success = status.display
        status.progress(ConnectionProgress.CHECKING_SERVER)
        status.permissionsRefreshed(true)
        assertEquals(success, status.display)
        assertEquals("연결됨", status.display.status.title)
        assertFalse(status.display.status.busy)
    }

    @Test fun `failed handshake cannot be overwritten by late progress or success`() {
        val status = ConnectionStatusTracker()
        status.startConnection()
        status.progress(ConnectionProgress.REPORTING_STATUS)
        status.failed("서버 연결을 확인해 주세요")
        val failure = status.display
        status.progress(ConnectionProgress.CHECKING_SERVER)
        status.connected()
        assertEquals(failure, status.display)
        assertTrue(status.startConnection())
    }

    @Test fun `permission state alone cannot report connected`() {
        val status = ConnectionStatusTracker()
        status.awaitingPermission()
        status.permissionsRefreshed(true)
        status.connected()
        assertEquals(ConnectionStatus.PERMISSION, status.display.status)
    }

    @Test fun `only initial state auto reconnects so rotation preserves a result`() {
        val status = ConnectionStatusTracker()
        assertEquals("연결 전", status.display.status.title)
        assertTrue(status.shouldAutoConnect())
        status.awaitingPermission()
        assertFalse(status.shouldAutoConnect())
        status.startConnection()
        assertFalse(status.shouldAutoConnect())
        status.connected()
        assertFalse(status.shouldAutoConnect())
    }

    @Test fun `revoking SMS permission clears a previously connected state`() {
        val status = ConnectionStatusTracker()
        status.startConnection()
        status.connected()
        status.permissionsRefreshed(false)
        assertEquals(ConnectionStatus.FAILED, status.display.status)
        assertTrue(status.display.detail.contains("SMS 권한"))
        assertFalse(status.display.status.busy)
    }
}
