package com.windergoodlife.smsrelay.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sms_messages",
    indices = [Index(value = ["uniqueKey"], unique = true)]
)
data class SmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uniqueKey: String,
    val sender: String,
    val message: String,
    val receivedAt: Long,
    val status: String = SmsStatus.PENDING.name,
    val retryCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val serverMessageId: String? = null,
    val httpLastStatus: Int? = null
)
