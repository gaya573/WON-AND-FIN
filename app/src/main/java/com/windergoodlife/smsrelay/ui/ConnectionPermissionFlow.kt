package com.windergoodlife.smsrelay.ui

/** Keeps one explicit Connect intent across the asynchronous Android permission prompt. */
class ConnectionPermissionFlow(pending: Boolean = false) {
    var pendingConnect: Boolean = pending
        private set

    enum class Action { NONE, REQUEST_SMS, CONNECT, DECLINED, SETTINGS_REQUIRED }

    fun connectTapped(smsGranted: Boolean): Action {
        if (pendingConnect) return Action.NONE
        pendingConnect = true
        return if (smsGranted) consumeGranted() else Action.REQUEST_SMS
    }

    fun permissionResult(smsGranted: Boolean, canAskAgain: Boolean): Action {
        if (!pendingConnect) return Action.NONE
        if (smsGranted) return consumeGranted()
        pendingConnect = false
        return if (canAskAgain) Action.DECLINED else Action.SETTINGS_REQUIRED
    }

    fun resume(smsGranted: Boolean): Action =
        if (pendingConnect && smsGranted) consumeGranted() else Action.NONE

    private fun consumeGranted(): Action {
        pendingConnect = false
        return Action.CONNECT
    }
}
