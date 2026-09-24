package com.windergoodlife.smsrelay.ui

import com.windergoodlife.smsrelay.ui.ConnectionPermissionFlow.Action.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConnectionPermissionFlowTest {
    @Test fun `one tap then permission grant continues connection without second tap`() {
        val flow = ConnectionPermissionFlow()
        assertEquals(REQUEST_SMS, flow.connectTapped(false))
        assertEquals(CONNECT, flow.permissionResult(true, false))
        assertFalse(flow.pendingConnect)
        assertEquals(NONE, flow.permissionResult(true, false))
    }

    @Test fun `duplicate tap while prompt visible cannot start duplicate connection`() {
        val flow = ConnectionPermissionFlow()
        assertEquals(REQUEST_SMS, flow.connectTapped(false))
        assertEquals(NONE, flow.connectTapped(false))
        assertEquals(CONNECT, flow.permissionResult(true, false))
    }

    @Test fun `denial never connects and settings only required when Android blocks another prompt`() {
        val flow = ConnectionPermissionFlow()
        flow.connectTapped(false)
        assertEquals(DECLINED, flow.permissionResult(false, true))
        assertEquals(REQUEST_SMS, flow.connectTapped(false))
        assertEquals(SETTINGS_REQUIRED, flow.permissionResult(false, false))
        assertFalse(flow.pendingConnect)
    }

    @Test fun `restored pending intent continues after permissions granted during recreation`() {
        val original = ConnectionPermissionFlow()
        original.connectTapped(false)
        val restored = ConnectionPermissionFlow(original.pendingConnect)
        assertEquals(CONNECT, restored.resume(true))
        assertEquals(NONE, restored.permissionResult(true, false))
    }

    @Test fun `permissions granted without Connect intent never enroll automatically`() {
        val flow = ConnectionPermissionFlow()
        assertEquals(NONE, flow.resume(true))
        assertEquals(NONE, flow.permissionResult(true, false))
    }

    @Test fun `already allowed permissions connect on first tap`() {
        assertEquals(CONNECT, ConnectionPermissionFlow().connectTapped(true))
    }
}
