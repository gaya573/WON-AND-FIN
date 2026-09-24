package com.windergoodlife.smsrelay.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SyncPhase(val label: String) {
    WAITING("문자 동기화 대기"), RECOVERING("누락 문자 확인 중"), SENDING("대기 문자 전송 중"),
    CURRENT("누락 문자 확인 완료"), RETRYING("동기화 실패 · 자동 재시도 예정"),
    PERMISSION("SMS 권한이 필요합니다"), PROVIDER_RESET("문자함이 변경되었습니다 · 환경설정에서 로그를 확인해 주세요"),
    BLOCKED("전송 확인 필요 · 연결 로그를 확인해 주세요")
}

/** Connection verification is independent of recovery and queue delivery. */
class SyncStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("relay_sync_state", Context.MODE_PRIVATE)
    private val restored = runCatching { SyncPhase.valueOf(prefs.getString("phase", "WAITING") ?: "WAITING") }
        .getOrDefault(SyncPhase.WAITING).let {
            if (it == SyncPhase.RECOVERING || it == SyncPhase.SENDING) SyncPhase.WAITING else it
        }
    private val state = MutableStateFlow(restored)
    val phase = state.asStateFlow()

    fun update(value: SyncPhase) {
        state.value = value
        runCatching { prefs.edit().putString("phase", value.name).apply() }
    }
}
