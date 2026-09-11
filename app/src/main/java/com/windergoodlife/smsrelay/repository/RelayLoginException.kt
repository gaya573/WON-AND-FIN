package com.windergoodlife.smsrelay.repository

/** Carries the HTTP status so the setup screen can say what the operator should fix. */
class RelayLoginException(val code: Int) : Exception("relay login failed: $code")
