package com.windergoodlife.smsrelay.data

enum class SmsStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED,
    ERROR_AUTH,
    ERROR_PAYLOAD
}
