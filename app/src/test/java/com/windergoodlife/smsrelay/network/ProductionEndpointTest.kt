package com.windergoodlife.smsrelay.network

import com.windergoodlife.smsrelay.BuildConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProductionEndpointTest {
    @Test fun `fresh installs use the verified production API`() {
        assertEquals("https://api.dealerhub.co.kr", BuildConfig.DEFAULT_BASE_URL)
    }
}
