package com.windergoodlife.smsrelay.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Locale

/**
 * Per-manufacturer autostart settings.
 *
 * <p>Samsung, Xiaomi, and several Chinese OEMs sleep apps far more aggressively than stock Doze,
 * and exempting the app from battery optimisation is not enough — their own "autostart" or
 * "protected app" list is separate and off by default. A phone that looks correctly configured can
 * still go quiet for hours, which is the failure nobody notices until a code never arrives.
 *
 * <p>These screens are not public API, so every entry is attempted and silently skipped when the
 * activity is absent. The instructions are shown either way, since on some builds the only route is
 * by hand.
 */
object AutoStartGuide {

    data class Guidance(val title: String, val steps: List<String>, val intents: List<Intent>)

    fun forThisDevice(): Guidance {
        val maker = Build.MANUFACTURER.lowercase(Locale.ROOT)
        return when {
            maker.contains("samsung") -> Guidance(
                "삼성 기기 설정",
                listOf(
                    "설정 → 배터리 → 백그라운드 사용 제한 → '절전 예외 앱'에 이 앱 추가",
                    "설정 → 배터리 → 백그라운드 사용 제한 → '잠자는 앱'과 '자동 잠자기 앱'에서 이 앱 제거",
                    "설정 → 애플리케이션 → 이 앱 → 배터리 → '제한 없음' 선택",
                ),
                listOf(
                    intent("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                    intent("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                ),
            )

            maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco") -> Guidance(
                "샤오미 기기 설정",
                listOf(
                    "보안 → 자동 실행 → 이 앱 켜기",
                    "설정 → 앱 → 앱 관리 → 이 앱 → 배터리 세이버 → '제한 없음'",
                    "최근 앱 목록에서 이 앱을 아래로 당겨 자물쇠 표시",
                ),
                listOf(
                    intent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                ),
            )

            maker.contains("huawei") || maker.contains("honor") -> Guidance(
                "화웨이 기기 설정",
                listOf(
                    "휴대폰 관리 → 앱 실행 관리 → 이 앱 → 수동 관리로 바꾸고 3개 항목 모두 켜기",
                    "설정 → 배터리 → 앱 실행 → 자동 관리 끄기",
                ),
                listOf(
                    intent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                    intent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                ),
            )

            maker.contains("oppo") || maker.contains("realme") || maker.contains("oneplus") -> Guidance(
                "OPPO·원플러스 기기 설정",
                listOf(
                    "설정 → 배터리 → 앱 배터리 관리 → 이 앱 → 백그라운드 실행 허용",
                    "휴대폰 관리자 → 자동 실행 관리 → 이 앱 켜기",
                ),
                listOf(
                    intent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                    intent("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                ),
            )

            maker.contains("vivo") -> Guidance(
                "vivo 기기 설정",
                listOf(
                    "i매니저 → 앱 관리자 → 자동 실행 → 이 앱 켜기",
                    "설정 → 배터리 → 백그라운드 전력 소모 관리 → 이 앱 허용",
                ),
                listOf(
                    intent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                ),
            )

            else -> Guidance(
                "절전 예외 설정",
                listOf(
                    "설정 → 앱 → 이 앱 → 배터리 → 제한 없음",
                    "제조사 자체 절전 기능이 있다면 이 앱을 예외로 등록",
                ),
                emptyList(),
            )
        }
    }

    /** Opens the first settings screen this build actually has. Returns false when none resolve. */
    fun openSettings(context: Context, guidance: Guidance): Boolean {
        for (candidate in guidance.intents) {
            if (context.packageManager.resolveActivity(candidate, 0) == null) continue
            return try {
                context.startActivity(candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: Exception) {
                Log.i("SmsRelay", "autostart screen refused ${e.javaClass.simpleName}")
                false
            }
        }
        return false
    }

    private fun intent(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))
}
