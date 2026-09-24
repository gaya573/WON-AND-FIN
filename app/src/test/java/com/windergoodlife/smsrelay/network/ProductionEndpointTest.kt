package com.windergoodlife.smsrelay.network

import com.windergoodlife.smsrelay.BuildConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProductionEndpointTest {
    @Test fun `fresh installs use the Node mobile API origin`() {
        assertEquals("https://chat.dealerhub.co.kr", BuildConfig.DEFAULT_BASE_URL)
        assertEquals(BuildConfig.DEFAULT_BASE_URL, RelayEndpoint.resolve(null))
        assertEquals(BuildConfig.DEFAULT_BASE_URL, RelayEndpoint.resolve(" "))
    }

    @Test fun `only the exact prior production origin migrates to Node`() {
        assertEquals(BuildConfig.DEFAULT_BASE_URL, RelayEndpoint.resolve("https://api.dealerhub.co.kr"))
        assertEquals(BuildConfig.DEFAULT_BASE_URL, RelayEndpoint.resolve(" https://api.dealerhub.co.kr/ "))
        assertEquals(BuildConfig.DEFAULT_BASE_URL, RelayEndpoint.resolve("https://chat.dealerhub.co.kr"))
    }

    @Test fun `custom HTTPS origins and similar looking addresses remain unchanged`() {
        for (custom in listOf("https://relay.example.test", "https://api.dealerhub.co.kr.example.test",
            "https://api.dealerhub.co.kr:8443", "https://api.dealerhub.co.kr/custom", "https://api.wonder.p-e.kr")) {
            assertEquals(custom, RelayEndpoint.resolve(custom))
        }
    }

    @Test fun `invalid or cleartext saved origins are not silently redirected`() {
        for (invalid in listOf("http://relay.example.test", "not-a-url", "file:///invalid"))
            assertThrows(IllegalArgumentException::class.java) { RelayEndpoint.resolve(invalid) }
    }
}
