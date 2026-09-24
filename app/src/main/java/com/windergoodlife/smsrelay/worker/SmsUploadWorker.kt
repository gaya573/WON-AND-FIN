package com.windergoodlife.smsrelay.worker

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OutOfQuotaPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.windergoodlife.smsrelay.SmsRelayApp
import com.windergoodlife.smsrelay.repository.SmsRepository
import java.util.concurrent.TimeUnit

class SmsUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_LOCAL_ID, -1L)
        if (id <= 0L) return Result.failure()
        val repo = SmsRelayApp.get().repository
        return when (repo.uploadOne(id)) {
            SmsRepository.UploadOutcome.Success -> Result.success()
            SmsRepository.UploadOutcome.Retry -> Result.retry()
            SmsRepository.UploadOutcome.AuthError, SmsRepository.UploadOutcome.GiveUp -> Result.failure()
        }
    }

    companion object {
        private const val KEY_LOCAL_ID = "local_id"
        private const val UNIQUE_PREFIX = "sms-upload-"

        fun enqueue(context: Context, localId: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<SmsUploadWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_LOCAL_ID to localId))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                // An authentication code is worth nothing once Doze has sat on it for fifteen
                // minutes, so this jumps the queue; if the expedited quota is spent it still runs,
                // just normally.
                .apply {
                    if (supportsExpeditedUpload(Build.VERSION.SDK_INT))
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_PREFIX + localId,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
