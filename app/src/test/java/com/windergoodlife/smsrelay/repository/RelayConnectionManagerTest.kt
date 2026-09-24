package com.windergoodlife.smsrelay.repository

import com.windergoodlife.smsrelay.network.SmsApi
import com.windergoodlife.smsrelay.network.dto.*
import com.windergoodlife.smsrelay.security.DeviceTokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import retrofit2.Response
import java.io.IOException

class RelayConnectionManagerTest {
    private val store = mock(DeviceTokenStore::class.java)
    private val api = mock(SmsApi::class.java)
    private val identity = DeviceTokenStore.ConnectionIdentity("5e0b22c9-4ea3-45ec-ae3f-51e1b479105d", "frt_" + "a".repeat(43), "Test phone", true)
    private val manager = RelayConnectionManager(store) { api }
    private fun request() = any(RelayConnectRequest::class.java) ?: RelayConnectRequest("", "")
    private fun heartbeat() = any(HeartbeatRequest::class.java) ?: HeartbeatRequest("", "", 0, 0, true, false)

    private suspend fun ready(enrollment: Boolean = true) {
        `when`(store.prepareConnection(anyString(), anyString())).thenReturn(
            DeviceTokenStore.ConnectionIdentity(identity.deviceId, identity.token, identity.displayName, enrollment))
        `when`(api.connect(anyString(), request())).thenReturn(Response.success(RelayConnectResponse(true, true, identity.deviceId, identity.displayName)))
        `when`(api.ping(anyString(), anyString(), anyString())).thenReturn(Response.success(PingResponse(true)))
        `when`(api.heartbeat(anyString(), anyString(), heartbeat(), anyString())).thenReturn(Response.success(RelayHeartbeatResponse(true)))
    }

    @Test fun `new phone confirms only after enrollment ping and successful heartbeat`() = runBlocking<Unit> {
        ready()
        manager.connect("Test phone", true, false)
        val order = inOrder(store, api)
        order.verify(store).prepareConnection("https://api.dealerhub.co.kr", "Test phone")
        order.verify(api).connect(identity.token, RelayConnectRequest(identity.deviceId, identity.displayName))
        order.verify(api).ping("Bearer ${identity.token}", identity.token, identity.deviceId)
        order.verify(api).heartbeat(anyString(), anyString(), heartbeat(), anyString())
        order.verify(store).confirmConnection(identity.deviceId)
    }

    @Test fun `legacy valid phone verifies without reenrollment`() = runBlocking<Unit> {
        ready(false)
        manager.connect("Test phone", true, true)
        verify(api, never()).connect(anyString(), request())
        verify(store).confirmConnection(identity.deviceId)
    }

    @Test fun `invalid old credential never silently enrolls a new phone`() = runBlocking<Unit> {
        ready(false)
        `when`(api.ping(anyString(), anyString(), anyString())).thenReturn(Response.error(401, "{}".toResponseBody()))
        try { manager.connect("Test phone", true, false); fail("must fail") } catch (error: RelayConnectionException) { assertEquals(401, error.code) }
        verify(api, never()).connect(anyString(), request())
        verify(store, never()).confirmConnection(anyString())
    }

    @Test fun `disabled phone does not bypass disable with a new identity`() = runBlocking<Unit> {
        ready(false)
        `when`(api.ping(anyString(), anyString(), anyString())).thenReturn(Response.error(403, "{}".toResponseBody()))
        try { manager.connect("Test phone", true, false); fail("must fail") } catch (error: RelayConnectionException) { assertEquals(403, error.code) }
        verify(api, never()).connect(anyString(), request())
        verify(store, never()).confirmConnection(anyString())
    }

    @Test fun `uncertain enrollment retries same persisted identity`() = runBlocking<Unit> {
        ready()
        `when`(api.connect(anyString(), request())).thenAnswer { throw IOException("synthetic timeout") }
            .thenReturn(Response.success(RelayConnectResponse(true, true, identity.deviceId, identity.displayName)))
        try { manager.connect("Test phone", true, false); fail("must fail") } catch (_: IOException) { }
        verify(store, never()).confirmConnection(anyString())
        manager.connect("Test phone", true, false)
        verify(api, times(2)).connect(identity.token, RelayConnectRequest(identity.deviceId, identity.displayName))
    }

    @Test fun `HTTP 200 false heartbeat is not connected`() = runBlocking<Unit> {
        ready()
        `when`(api.heartbeat(anyString(), anyString(), heartbeat(), anyString())).thenReturn(Response.success(RelayHeartbeatResponse(false)))
        try { manager.connect("Test phone", true, false); fail("must fail") } catch (_: IllegalStateException) { }
        verify(store, never()).confirmConnection(anyString())
    }

    @Test fun `HTTP 200 empty heartbeat is not connected`() = runBlocking<Unit> {
        ready()
        `when`(api.heartbeat(anyString(), anyString(), heartbeat(), anyString())).thenReturn(Response.success(null))
        try { manager.connect("Test phone", true, false); fail("must fail") } catch (_: IllegalStateException) { }
        verify(store, never()).confirmConnection(anyString())
    }

    @Test fun `no SMS consent means no stored credentials and no network call`() = runBlocking<Unit> {
        try { manager.connect("Test phone", false, false); fail("must fail") } catch (_: IllegalStateException) { }
        verifyNoInteractions(store, api)
    }

    @Test fun `mismatched enrollment acknowledgement remains pending`() = runBlocking<Unit> {
        ready()
        `when`(api.connect(anyString(), request())).thenReturn(Response.success(RelayConnectResponse(true, true, "other-device")))
        try { manager.connect("Test phone", true, false); fail("must fail") } catch (_: IllegalStateException) { }
        verify(store, never()).confirmConnection(anyString())
        verify(api, never()).ping(anyString(), anyString(), anyString())
    }
}
