package com.windergoodlife.smsrelay.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class ConnectionWakeSchedulingTest {
    @Test fun `confirmed connection bypasses retry backoff without losing later ordinary wakes`() {
        val context = mock(Context::class.java)
        val manager = mock(WorkManager::class.java)
        val operation = mock(Operation::class.java)
        val placeholder = mock(OneTimeWorkRequest::class.java)
        `when`(manager.enqueueUniqueWork(anyString(), any(ExistingWorkPolicy::class.java) ?: ExistingWorkPolicy.KEEP,
            any(OneTimeWorkRequest::class.java) ?: placeholder)).thenReturn(operation)
        PendingSmsWorker.enqueue(context, connectionConfirmed = true, manager = manager)
        PendingSmsWorker.enqueue(context, manager = manager)

        val sequence = inOrder(manager)
        sequence.verify(manager).enqueueUniqueWork(eq("pending-sms-flush") ?: "", eq(ExistingWorkPolicy.REPLACE) ?: ExistingWorkPolicy.KEEP,
            any(OneTimeWorkRequest::class.java) ?: placeholder)
        sequence.verify(manager).enqueueUniqueWork(eq("pending-sms-flush") ?: "", eq(ExistingWorkPolicy.APPEND_OR_REPLACE) ?: ExistingWorkPolicy.KEEP,
            any(OneTimeWorkRequest::class.java) ?: placeholder)
        sequence.verifyNoMoreInteractions()
    }
}
