package com.windergoodlife.smsrelay.repository

import com.windergoodlife.smsrelay.network.SmsApi
import com.windergoodlife.smsrelay.network.dto.*
import com.windergoodlife.smsrelay.security.DeviceTokenStore
import com.windergoodlife.smsrelay.diagnostics.*
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
    private val identity = DeviceTokenStore.ConnectionIdentity("5e0b22c9-4ea3-45ec-ae3f-51e1b479105d", "frt_" + "A".repeat(43), "Test phone", true)
    private val events = mutableListOf<ConnectionDiagnostic>()
    private val manager = RelayConnectionManager(store, ConnectionLogSink { events += it }) { api }
    private fun request() = any(RelayConnectRequest::class.java) ?: RelayConnectRequest("", "")
    private fun heartbeat() = any(HeartbeatRequest::class.java) ?: HeartbeatRequest("", "", 0, 0, true, false)
    private fun savedIdentity() = any(DeviceTokenStore.ConnectionIdentity::class.java) ?: identity
    private fun missingRegistration(): Response<PingResponse> = Response.error(410, "{\"message\":\"DEVICE_NOT_REGISTERED\",\"status\":410}".toResponseBody())

    private suspend fun ready(enrollment: Boolean = true) {
        `when`(store.prepareConnection(anyString(), anyString())).thenReturn(
            DeviceTokenStore.ConnectionIdentity(identity.deviceId, identity.token, identity.displayName, enrollment))
        `when`(store.recoverUnregisteredIdentity(savedIdentity())).thenReturn(identity)
        `when`(api.connect(anyString(), request())).thenReturn(Response.success(RelayConnectResponse(true, true, identity.deviceId, identity.displayName)))
        `when`(api.ping(anyString(), anyString(), anyString())).thenReturn(Response.success(PingResponse(true)))
        `when`(api.heartbeat(anyString(), anyString(), heartbeat(), anyString())).thenReturn(Response.success(RelayHeartbeatResponse(true)))
    }

    @Test fun `new phone confirms only after enrollment ping and successful heartbeat`() = runBlocking<Unit> {
        ready()
        val progress = mutableListOf<ConnectionProgress>()
        manager.connect("Test phone", true, false) { progress += it }
        assertEquals(listOf(ConnectionProgress.REGISTERING, ConnectionProgress.CHECKING_SERVER, ConnectionProgress.REPORTING_STATUS), progress)
        val order = inOrder(store, api)
        order.verify(store).prepareConnection("https://api.dealerhub.co.kr", "Test phone")
        order.verify(api).connect(identity.token, RelayConnectRequest(identity.deviceId, identity.displayName))
        order.verify(api).ping("Bearer ${identity.token}", identity.token, identity.deviceId)
        order.verify(api).heartbeat(anyString(), anyString(), heartbeat(), anyString())
        order.verify(store).confirmConnection(identity.deviceId)
        assertEquals(listOf(ConnectionLogStage.PREPARING, ConnectionLogStage.REGISTER, ConnectionLogStage.PING, ConnectionLogStage.HEARTBEAT),
            events.filter { it.outcome == ConnectionLogOutcome.SUCCEEDED }.map { it.stage })
        assertEquals(ConnectionDiagnostic(ConnectionLogStage.COMPLETE, ConnectionLogOutcome.STARTED), events.last())
    }

    @Test fun `legacy valid phone verifies without reenrollment`() = runBlocking<Unit> {
        ready(false)
        val progress = mutableListOf<ConnectionProgress>()
        manager.connect("Test phone", true, true) { progress += it }
        assertEquals(listOf(ConnectionProgress.CHECKING_SERVER, ConnectionProgress.REPORTING_STATUS), progress)
        verify(api, never()).connect(anyString(), request())
        verify(store).confirmConnection(identity.deviceId)
    }

    @Test fun `legacy manual identity never blindly enrolls on 401`() = runBlocking<Unit> {
        ready(false)
        `when`(store.prepareConnection(anyString(), anyString())).thenReturn(
            DeviceTokenStore.ConnectionIdentity("relay-old", identity.token, identity.displayName, false))
        `when`(api.ping(anyString(), anyString(), anyString())).thenReturn(Response.error(401, "{}".toResponseBody()))
        val progress = mutableListOf<ConnectionProgress>()
        try { manager.connect("Test phone", true, false) { progress += it }; fail("must fail") } catch (error: RelayConnectionException) { assertEquals(401, error.code) }
        assertEquals(listOf(ConnectionProgress.CHECKING_SERVER), progress)
        verify(api, never()).connect(anyString(), request())
        verify(store, never()).recoverUnregisteredIdentity(savedIdentity())
        assertEquals(ConnectionDiagnostic(ConnectionLogStage.PING, ConnectionLogOutcome.FAILED, 401,
            ConnectionFailureReason.SERVER_RESPONSE), events.last())
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
        val progress = mutableListOf<ConnectionProgress>()
        try { manager.connect("Test phone", true, false) { progress += it }; fail("must fail") } catch (_: IllegalStateException) { }
        assertEquals(ConnectionProgress.REPORTING_STATUS, progress.last())
        assertEquals(ConnectionDiagnostic(ConnectionLogStage.HEARTBEAT, ConnectionLogOutcome.FAILED, 200, ConnectionFailureReason.INVALID_ACK), events.last())
        assertFalse(events.any { it.stage == ConnectionLogStage.HEARTBEAT && it.outcome == ConnectionLogOutcome.SUCCEEDED })
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

    @Test fun `404 is recorded at the exact failed API stage without response data`() = runBlocking<Unit> {
        for (stage in listOf(ConnectionLogStage.REGISTER, ConnectionLogStage.PING, ConnectionLogStage.HEARTBEAT)) {
            reset(api, store)
            events.clear()
            ready()
            val body = "private-token sender SMS-body".toResponseBody()
            when (stage) {
                ConnectionLogStage.REGISTER -> `when`(api.connect(anyString(), request())).thenReturn(Response.error(404, body))
                ConnectionLogStage.PING -> `when`(api.ping(anyString(), anyString(), anyString())).thenReturn(Response.error(404, body))
                else -> `when`(api.heartbeat(anyString(), anyString(), heartbeat(), anyString())).thenReturn(Response.error(404, body))
            }
            try { manager.connect("Test phone", true, false); fail("must fail") } catch (_: RelayConnectionException) { }
            assertEquals(ConnectionDiagnostic(stage, ConnectionLogOutcome.FAILED, 404, ConnectionFailureReason.SERVER_RESPONSE), events.last())
            assertFalse(events.toString().contains("private-token"))
            assertFalse(events.toString().contains(identity.deviceId))
            assertFalse(events.any { it.stage == stage && it.outcome == ConnectionLogOutcome.SUCCEEDED })
            verify(store, never()).confirmConnection(anyString())
        }
    }

    @Test fun `local credential setup failure is recorded before network requests`() = runBlocking<Unit> {
        `when`(store.prepareConnection(anyString(), anyString())).thenThrow(IllegalStateException("private credential"))
        try { manager.connect("Test phone", true, false); fail("must fail") } catch (_: IllegalStateException) { }
        assertEquals(ConnectionDiagnostic(ConnectionLogStage.PREPARING, ConnectionLogOutcome.FAILED, reason = ConnectionFailureReason.LOCAL_SETUP), events.last())
        assertFalse(events.toString().contains("private credential"))
        verifyNoInteractions(api)
    }

    @Test fun `local confirmation failure is not logged as a completed connection`() = runBlocking<Unit> {
        ready()
        doThrow(IllegalStateException("private credential")).`when`(store).confirmConnection(identity.deviceId)
        try { manager.connect("Test phone", true, false); fail("must fail") } catch (_: IllegalStateException) { }
        assertEquals(ConnectionDiagnostic(ConnectionLogStage.COMPLETE, ConnectionLogOutcome.FAILED, reason = ConnectionFailureReason.LOCAL_SETUP), events.last())
        assertFalse(events.any { it.stage == ConnectionLogStage.COMPLETE && it.outcome == ConnectionLogOutcome.SUCCEEDED })
    }

    @Test fun `diagnostic sink failure never changes authentication success`() = runBlocking<Unit> {
        ready()
        RelayConnectionManager(store, ConnectionLogSink { throw IllegalStateException("storage unavailable") }) { api }
            .connect("Test phone", true, false)
        verify(store).confirmConnection(identity.deviceId)
    }

    @Test fun `one connection call repairs missing registration with the exact saved identity`() = runBlocking<Unit> {
        ready(false)
        `when`(api.ping(anyString(), anyString(), anyString()))
            .thenReturn(missingRegistration(), Response.success(PingResponse(true)))
        val progress = mutableListOf<ConnectionProgress>()
        manager.connect("Test phone", true, false) { progress += it }
        assertEquals(listOf(ConnectionProgress.CHECKING_SERVER, ConnectionProgress.RECHECKING_REGISTRATION,
            ConnectionProgress.CHECKING_SERVER, ConnectionProgress.REPORTING_STATUS), progress)
        val order = inOrder(store, api)
        order.verify(store).prepareConnection("https://api.dealerhub.co.kr", "Test phone")
        order.verify(api).ping("Bearer ${identity.token}", identity.token, identity.deviceId)
        order.verify(store).recoverUnregisteredIdentity(savedIdentity())
        order.verify(api).connect(identity.token, RelayConnectRequest(identity.deviceId, identity.displayName))
        order.verify(api).ping("Bearer ${identity.token}", identity.token, identity.deviceId)
        order.verify(api).heartbeat(anyString(), anyString(), heartbeat(), anyString())
        order.verify(store).confirmConnection(identity.deviceId)
        verify(store, times(1)).prepareConnection(anyString(), anyString())
        assertTrue(events.contains(ConnectionDiagnostic(ConnectionLogStage.AUTO_REGISTER, ConnectionLogOutcome.SUCCEEDED, 200)))
    }

    @Test fun `disabled or mismatched same identity registration stops after one attempt`() = runBlocking<Unit> {
        for (code in listOf(401, 403)) {
            reset(api, store)
            events.clear()
            ready(false)
            `when`(api.ping(anyString(), anyString(), anyString())).thenReturn(missingRegistration())
            `when`(api.connect(anyString(), request())).thenReturn(Response.error(code, "{}".toResponseBody()))
            try { manager.connect("Test phone", true, false); fail("must fail") }
            catch (failure: RelayConnectionException) { assertEquals(code, failure.code) }
            verify(api, times(1)).connect(identity.token, RelayConnectRequest(identity.deviceId, identity.displayName))
            verify(api, times(1)).ping(anyString(), anyString(), anyString())
            verify(api, never()).heartbeat(anyString(), anyString(), heartbeat(), anyString())
            verify(store, never()).confirmConnection(anyString())
            assertEquals(ConnectionDiagnostic(ConnectionLogStage.AUTO_REGISTER, ConnectionLogOutcome.FAILED, code, ConnectionFailureReason.SERVER_RESPONSE), events.last())
        }
    }

    @Test fun `second ping 401 cannot loop into registration again`() = runBlocking<Unit> {
        ready(false)
        `when`(api.ping(anyString(), anyString(), anyString())).thenReturn(missingRegistration(), Response.error(401, "{}".toResponseBody()))
        try { manager.connect("Test phone", true, false); fail("must fail") } catch (_: RelayConnectionException) { }
        verify(api, times(1)).connect(identity.token, RelayConnectRequest(identity.deviceId, identity.displayName))
        verify(api, times(2)).ping(anyString(), anyString(), anyString())
        verify(store, times(1)).recoverUnregisteredIdentity(savedIdentity())
        verify(store, never()).confirmConnection(anyString())
    }

    @Test fun `network failure is not an authentication retry or credential reset`() = runBlocking<Unit> {
        ready(false)
        `when`(api.ping(anyString(), anyString(), anyString())).thenAnswer { throw IOException("synthetic offline") }
        try { manager.connect("Test phone", true, false); fail("must fail") } catch (_: IOException) { }
        verify(api, never()).connect(anyString(), request())
        verify(store, never()).recoverUnregisteredIdentity(savedIdentity())
        verify(store, never()).confirmConnection(anyString())
    }

    @Test fun `uncertain automatic registration keeps the existing pair pending`() = runBlocking<Unit> {
        ready(false)
        `when`(api.ping(anyString(), anyString(), anyString())).thenReturn(missingRegistration())
        `when`(api.connect(anyString(), request())).thenAnswer { throw IOException("synthetic timeout") }
        try { manager.connect("Test phone", true, false); fail("must fail") } catch (_: IOException) { }
        verify(store, times(1)).recoverUnregisteredIdentity(savedIdentity())
        verify(store, times(1)).prepareConnection(anyString(), anyString())
        verify(api, times(1)).connect(identity.token, RelayConnectRequest(identity.deviceId, identity.displayName))
        verify(store, never()).confirmConnection(anyString())
        assertEquals(ConnectionDiagnostic(ConnectionLogStage.AUTO_REGISTER, ConnectionLogOutcome.FAILED, reason = ConnectionFailureReason.NETWORK), events.last())
    }

    @Test fun `legacy unknown identity registers the persisted candidate within the same connection call`() = runBlocking<Unit> {
        ready(false)
        val legacy = DeviceTokenStore.ConnectionIdentity("old-phone", "old-malformed-secret", "Phone", false)
        `when`(store.prepareConnection(anyString(), anyString())).thenReturn(legacy)
        `when`(api.ping(anyString(), anyString(), anyString())).thenReturn(missingRegistration(), Response.success(PingResponse(true)))
        manager.connect("Test phone", true, false)
        val order = inOrder(store, api)
        order.verify(store).prepareConnection("https://api.dealerhub.co.kr", "Test phone")
        order.verify(api).ping("Bearer ${legacy.token}", legacy.token, legacy.deviceId)
        order.verify(store).recoverUnregisteredIdentity(legacy)
        order.verify(api).connect(identity.token, RelayConnectRequest(identity.deviceId, identity.displayName))
        order.verify(api).ping("Bearer ${identity.token}", identity.token, identity.deviceId)
        order.verify(api).heartbeat(eq("Bearer ${identity.token}") ?: "", eq(identity.token) ?: "", heartbeat(), eq(identity.deviceId))
        order.verify(store).confirmConnection(identity.deviceId)
    }

    @Test fun `only ping410 with exact bounded JSON marker can start recovery`() = runBlocking<Unit> {
        val cases = listOf(
            410 to "{}",
            410 to "{\"message\":\"DEVICE_NOT_REGISTERED_extra\"}",
            410 to "{\"detail\":\"DEVICE_NOT_REGISTERED\"}",
            410 to "DEVICE_NOT_REGISTERED",
            410 to "{\"message\":\"DEVICE_NOT_REGISTERED\",\"extra\":\"${"x".repeat(4096)}\"}",
            401 to "{\"message\":\"DEVICE_NOT_REGISTERED\"}",
            403 to "{\"message\":\"DEVICE_NOT_REGISTERED\"}"
        )
        for ((code, body) in cases) {
            reset(api, store)
            ready(false)
            `when`(api.ping(anyString(), anyString(), anyString())).thenReturn(Response.error(code, body.toResponseBody()))
            try { manager.connect("Test phone", true, false); fail("must fail") } catch (_: RelayConnectionException) { }
            verify(store, never()).recoverUnregisteredIdentity(savedIdentity())
            verify(api, never()).connect(anyString(), request())
            verify(store, never()).confirmConnection(anyString())
        }
    }

    @Test fun `heartbeat unknown marker cannot change identity after successful ping`() = runBlocking<Unit> {
        ready(false)
        `when`(api.heartbeat(anyString(), anyString(), heartbeat(), anyString())).thenReturn(
            Response.error(410, "{\"message\":\"DEVICE_NOT_REGISTERED\"}".toResponseBody()))
        try { manager.connect("Test phone", true, false); fail("must fail") } catch (_: RelayConnectionException) { }
        verify(store, never()).recoverUnregisteredIdentity(savedIdentity())
        verify(api, never()).connect(anyString(), request())
    }
}
