package com.windergoodlife.smsrelay.worker

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UploadSchedulingTest {
    @Test fun `supported old Android versions use regular work without a missing foreground delegate`() {
        (26..30).forEach { assertFalse(supportsExpeditedUpload(it)) }
        (31..36).forEach { assertTrue(supportsExpeditedUpload(it)) }
    }
}
