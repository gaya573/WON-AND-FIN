package com.windergoodlife.smsrelay.diagnostics

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-private bounded diagnostics contain only time and predefined status values. */
class ConnectionLogStore(context: Context) : ConnectionLogSink {
    private val preferences = context.getSharedPreferences("connection_diagnostics", Context.MODE_PRIVATE)
    private val buffer = ConnectionLogBuffer(runCatching { preferences.getString("recent", "") ?: "" }.getOrDefault(""))
    private val _entries = MutableStateFlow(buffer.snapshot())
    val entries: StateFlow<List<ConnectionLogEntry>> = _entries.asStateFlow()

    @Synchronized
    override fun record(event: ConnectionDiagnostic) {
        // A diagnostics storage issue must never change connection or authentication behavior.
        runCatching {
            buffer.record(event, System.currentTimeMillis())
            _entries.value = buffer.snapshot()
            preferences.edit().putString("recent", buffer.serialize()).apply()
        }
    }
}
