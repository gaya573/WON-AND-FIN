package com.windergoodlife.smsrelay.diagnostics

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import com.windergoodlife.smsrelay.data.LocalSmsCounts
import com.windergoodlife.smsrelay.data.SmsDao
import com.windergoodlife.smsrelay.security.DeviceTokenStore
import com.windergoodlife.smsrelay.sync.MessageInboxSyncManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class SmsAppOp { ALLOWED, IGNORED, ERRORED, DEFAULT, FOREGROUND, UNAVAILABLE }
enum class InboxAccess { AVAILABLE, EMPTY, PERMISSION_DENIED, APP_OP_NOT_ALLOWED, UNAVAILABLE }
enum class InboxCursorRelation { AHEAD, EQUAL, BEHIND, NO_CHECKPOINT, UNAVAILABLE }
enum class MessageSource(val label: String) { MMS("MMS 수신함"), SAMSUNG_CHAT("삼성 채팅 수신함"), SAMSUNG_FILE("삼성 채팅 첨부") }
data class ProviderAccessSnapshot(val source: MessageSource, val available: Boolean,
    val count: Int? = null, val latestAt: Long? = null,
    val cursorRelation: InboxCursorRelation = InboxCursorRelation.UNAVAILABLE)

/** Only aggregate metadata is copied. No SMS body, sender, ID, key, or credential is returned. */
data class SmsAccessSnapshot(
    val capturedAt: Long,
    val sdk: Int,
    val readPermission: Boolean,
    val receivePermission: Boolean,
    val readAppOp: SmsAppOp,
    val receiveAppOp: SmsAppOp,
    val inboxAccess: InboxAccess,
    val inboxCount: Int? = null,
    val latestInboxAt: Long? = null,
    val cursorRelation: InboxCursorRelation = InboxCursorRelation.UNAVAILABLE,
    val upgradeInProgress: Boolean? = null,
    val configured: Boolean? = null,
    val localCounts: LocalSmsCounts? = null,
    val providers: List<ProviderAccessSnapshot> = emptyList(),
    val fullSmsHistoryComplete: Boolean? = null
) {
    fun format(): String {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX").withZone(ZoneId.systemDefault())
        fun at(value: Long) = time.format(Instant.ofEpochMilli(value))
        fun permission(value: Boolean) = if (value) "GRANTED (허용)" else "DENIED (거부)"
        fun flag(value: Boolean?) = value?.toString() ?: "확인 불가"
        val query = when (inboxAccess) {
            InboxAccess.AVAILABLE -> "조회됨 (전송 성공 여부와 별개)"
            InboxAccess.EMPTY -> "조회 결과 0건 (새 문자 없음으로 단정할 수 없음)"
            InboxAccess.PERMISSION_DENIED -> "미조회: READ_SMS 권한 거부"
            InboxAccess.APP_OP_NOT_ALLOWED -> "미조회: READ_SMS AppOps 허용 확인 안 됨"
            InboxAccess.UNAVAILABLE -> "조회 실패 또는 확인 불가"
        }
        return buildList {
            add("진단 스냅샷 ${at(capturedAt)} · Android SDK $sdk")
            add("READ_SMS ${permission(readPermission)} · RECEIVE_SMS ${permission(receivePermission)}")
            add("AppOps READ_SMS=$readAppOp · RECEIVE_SMS=$receiveAppOp")
            add("AppOps: ALLOWED 허용, IGNORED 무시, ERRORED 거부, DEFAULT 기본 정책, FOREGROUND 사용 중 정책, UNAVAILABLE 확인 불가")
            add("AppOps는 조회 시점의 사전 검사이며 실제 수신·접근 성공을 보장하지 않습니다.")
            add("SMS 문자함: $query · 조회 건수 ${inboxCount ?: "확인 불가"}")
            add("SMS 문자함 최신 수신시각: ${latestInboxAt?.let(::at) ?: if (inboxAccess == InboxAccess.EMPTY) "문자 없음" else "확인 불가"}")
            add("문자함 최대 ID와 복구 지점 관계: $cursorRelation (AHEAD 뒤에 행 있음 / EQUAL 동일 / BEHIND 앞섬 / NO_CHECKPOINT 지점 없음 / UNAVAILABLE 확인 불가)")
            add("초기 복구 진행 중=${flag(upgradeInProgress)} · 연결 설정 완료=${flag(configured)}")
            add("SMS 전체 과거분 확인 완료=${flag(fullSmsHistoryComplete)}")
            providers.forEach { source ->
                add("${source.source.label}: ${if (source.available) "조회됨" else "미지원 또는 조회 실패"} · 건수 ${source.count ?: "확인 불가"} · 최신 ${source.latestAt?.let(::at) ?: if (source.count == 0) "수신 없음" else "확인 불가"} · 복구 지점 관계 ${source.cursorRelation}")
            }
            add(localCounts?.let { "로컬 저장: 전체 ${it.total} · 전송 대기 ${it.pending} · 재시도 실패 ${it.failed} · 차단 ${it.blocked} · 전송 완료 ${it.sent}" }
                ?: "로컬 저장 건수: 확인 불가")
        }.joinToString("\n")
    }
}

/** Call from an IO dispatcher. All operations are read-only and never change collection cursors. */
class SmsAccessSnapshotCollector(
    private val context: Context,
    private val dao: SmsDao,
    private val tokenStore: DeviceTokenStore,
    private val appOp: (String) -> SmsAppOp = { readSmsAppOp(context, it) },
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun collect(): SmsAccessSnapshot {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val receive = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        val readOp = runCatching { appOp(AppOpsManager.OPSTR_READ_SMS) }.getOrDefault(SmsAppOp.UNAVAILABLE)
        val receiveOp = runCatching { appOp(AppOpsManager.OPSTR_RECEIVE_SMS) }.getOrDefault(SmsAppOp.UNAVAILABLE)
        var snapshot = SmsAccessSnapshot(now(), Build.VERSION.SDK_INT, read, receive, readOp, receiveOp,
            when { !read -> InboxAccess.PERMISSION_DENIED; readOp != SmsAppOp.ALLOWED -> InboxAccess.APP_OP_NOT_ALLOWED; else -> InboxAccess.UNAVAILABLE },
            upgradeInProgress = runCatching { tokenStore.getInboxUpgradeSnapshot() != null }.getOrNull(),
            configured = runCatching { tokenStore.isConfigured() }.getOrNull(),
            localCounts = runCatching { dao.diagnosticCounts() }.getOrNull())
        if (!read || readOp != SmsAppOp.ALLOWED) return snapshot
        snapshot = snapshot.copy(providers = collectProviders(),
            fullSmsHistoryComplete = runCatching { tokenStore.isFullSmsHistoryComplete() }.getOrNull())
        // Metadata only. Do not use getLastSyncTime(): it initializes a missing cursor.
        try {
            val uri = Uri.parse("content://sms/inbox")
            val metadata = context.contentResolver.query(uri, arrayOf("_id", "date"), null, null, "date DESC")
                ?.use { rows -> rows.count to if (rows.moveToFirst()) rows.getLong(rows.getColumnIndexOrThrow("date")).takeIf { it > 0 } else null }
                ?: return snapshot
            snapshot = snapshot.copy(inboxAccess = if (metadata.first == 0) InboxAccess.EMPTY else InboxAccess.AVAILABLE,
                inboxCount = metadata.first, latestInboxAt = metadata.second)
            val maxId = context.contentResolver.query(uri, arrayOf("_id"), null, null, "_id DESC")
                ?.use { rows -> if (rows.moveToFirst()) rows.getLong(rows.getColumnIndexOrThrow("_id")) else 0L }
                ?: return snapshot
            val checkpoint = tokenStore.getLastInboxSmsId()
            snapshot = snapshot.copy(cursorRelation = when {
                checkpoint == null -> InboxCursorRelation.NO_CHECKPOINT
                maxId > checkpoint -> InboxCursorRelation.AHEAD
                maxId < checkpoint -> InboxCursorRelation.BEHIND
                else -> InboxCursorRelation.EQUAL
            })
        } catch (_: Exception) {
            // Exception messages from providers may contain SQL, URI or SMS data; never copy them.
        }
        return snapshot
    }

    private fun collectProviders(): List<ProviderAccessSnapshot> {
        val sources = mutableListOf(providerMetadata(MessageSource.MMS, "mms", "content://mms/inbox", null, 1000L))
        if (MessageInboxSyncManager.supportsSamsungChat(context)) {
            sources += providerMetadata(MessageSource.SAMSUNG_CHAT, "samsung_im", "content://im/chat", "type = 1 AND hidden = 0", 1L)
            sources += providerMetadata(MessageSource.SAMSUNG_FILE, "samsung_ft", "content://im/ft", "type = 1 AND hidden = 0", 1L)
        } else {
            sources += ProviderAccessSnapshot(MessageSource.SAMSUNG_CHAT, false)
            sources += ProviderAccessSnapshot(MessageSource.SAMSUNG_FILE, false)
        }
        return sources
    }

    private fun providerMetadata(source: MessageSource, key: String, uri: String, selection: String?,
        dateMultiplier: Long): ProviderAccessSnapshot = try {
        context.contentResolver.query(Uri.parse(uri), arrayOf("_id", "date"), selection, null, "_id ASC")?.use { rows ->
            val id = rows.getColumnIndexOrThrow("_id")
            val date = rows.getColumnIndexOrThrow("date")
            var maxId = 0L
            var latest = 0L
            while (rows.moveToNext()) {
                maxId = maxOf(maxId, rows.getLong(id))
                val value = rows.getLong(date)
                if (value > 0 && value <= Long.MAX_VALUE / dateMultiplier) latest = maxOf(latest, value * dateMultiplier)
            }
            val saved = tokenStore.getProviderCheckpoint(key)
            ProviderAccessSnapshot(source, true, rows.count, latest.takeIf { it > 0 }, when {
                maxId > saved -> InboxCursorRelation.AHEAD
                maxId < saved -> InboxCursorRelation.BEHIND
                else -> InboxCursorRelation.EQUAL
            })
        } ?: ProviderAccessSnapshot(source, false)
    } catch (_: Exception) { ProviderAccessSnapshot(source, false) }
}

@Suppress("DEPRECATION")
internal fun readSmsAppOp(context: Context, op: String): SmsAppOp = try {
    require(op == AppOpsManager.OPSTR_READ_SMS || op == AppOpsManager.OPSTR_RECEIVE_SMS)
    val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    // check*, unlike note*, does not record access or change permission settings.
    val mode = when {
        Build.VERSION.SDK_INT >= 36 -> ops.checkOpRawNoThrow(op, Process.myUid(), context.packageName, null)
        Build.VERSION.SDK_INT >= 29 -> ops.unsafeCheckOpRawNoThrow(op, Process.myUid(), context.packageName)
        else -> ops.checkOpNoThrow(op, Process.myUid(), context.packageName)
    }
    when (mode) {
        AppOpsManager.MODE_ALLOWED -> SmsAppOp.ALLOWED
        AppOpsManager.MODE_IGNORED -> SmsAppOp.IGNORED
        AppOpsManager.MODE_ERRORED -> SmsAppOp.ERRORED
        AppOpsManager.MODE_DEFAULT -> SmsAppOp.DEFAULT
        AppOpsManager.MODE_FOREGROUND -> SmsAppOp.FOREGROUND
        else -> SmsAppOp.UNAVAILABLE
    }
} catch (_: Exception) { SmsAppOp.UNAVAILABLE }
