package com.windergoodlife.smsrelay.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SmsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: SmsEntity): Long

    @Query("SELECT * FROM sms_messages WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): SmsEntity?

    @Query("SELECT * FROM sms_messages WHERE uniqueKey = :uniqueKey LIMIT 1")
    suspend fun findByUniqueKey(uniqueKey: String): SmsEntity?

    @Query(
        """
        SELECT * FROM sms_messages
        WHERE status IN ('PENDING', 'FAILED', 'SENDING')
        ORDER BY receivedAt ASC
        LIMIT :limit
        """
    )
    suspend fun findRetryable(limit: Int = 50): List<SmsEntity>

    @Query("SELECT COUNT(*) FROM sms_messages WHERE status IN ('PENDING', 'FAILED', 'SENDING')")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sms_messages WHERE status = 'FAILED'")
    fun observeFailedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sms_messages WHERE status = 'ERROR_AUTH'")
    fun observeAuthErrorCount(): Flow<Int>

    @Query("SELECT * FROM sms_messages ORDER BY receivedAt DESC LIMIT 1")
    fun observeLatest(): Flow<SmsEntity?>

    @Query("SELECT * FROM sms_messages WHERE status = 'SENT' ORDER BY lastAttemptAt DESC LIMIT 1")
    fun observeLatestSent(): Flow<SmsEntity?>

    @Query(
        """
        UPDATE sms_messages
        SET status = :status,
            retryCount = :retryCount,
            lastAttemptAt = :lastAttemptAt,
            serverMessageId = :serverMessageId,
            httpLastStatus = :httpLastStatus
        WHERE id = :id
        """
    )
    suspend fun updateUploadResult(
        id: Long,
        status: String,
        retryCount: Int,
        lastAttemptAt: Long,
        serverMessageId: String?,
        httpLastStatus: Int?
    )

    @Query("UPDATE sms_messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("SELECT COUNT(*) FROM sms_messages WHERE status IN ('PENDING', 'FAILED', 'SENDING')")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM sms_messages WHERE status = 'FAILED'")
    suspend fun failedCount(): Int

    @Query("SELECT receivedAt FROM sms_messages ORDER BY receivedAt DESC LIMIT 1")
    suspend fun latestReceivedAt(): Long?
}
