package com.windergoodlife.smsrelay.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.windergoodlife.smsrelay.BuildConfig
import com.windergoodlife.smsrelay.SmsRelayApp
import com.windergoodlife.smsrelay.diagnostics.ConnectionLogFormatter
import com.windergoodlife.smsrelay.databinding.ActivitySetupBinding
import kotlinx.coroutines.launch

/** Only Android consent/settings live here. Connection credentials are managed by the app. */
class SetupActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySetupBinding
    private val smsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshStatus() }
    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.keepContentInsideSystemBars()
        binding.btnRequestSms.setOnClickListener {
            smsPermissionLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
        }
        binding.btnNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
        binding.btnBatterySetup.setOnClickListener {
            openSettings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName")))
        }
        val guidance = AutoStartGuide.forThisDevice()
        binding.autoStartSteps.text = guidance.steps.joinToString("\n") { "· $it" }
        binding.btnAutoStart.setOnClickListener {
            if (!AutoStartGuide.openSettings(this, guidance)) openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName")))
        }
        binding.btnAppPermissions.setOnClickListener {
            openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName")))
        }
        binding.logsDescription.text = "앱 ${BuildConfig.VERSION_NAME} · 최근 6건 표시 · 최대 50건 보관\n복사하면 보관된 로그 전체를 확인할 수 있습니다."
        val logs = (application as SmsRelayApp).connectionLogs
        binding.btnCopyLogs.setOnClickListener {
            val text = "SMS Relay ${BuildConfig.VERSION_NAME}\n" + ConnectionLogFormatter.format(logs.entries.value)
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("최근 연결 로그", text))
            Toast.makeText(this, "연결 로그를 복사했습니다", Toast.LENGTH_SHORT).show()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                logs.entries.collect { entries ->
                    binding.connectionLogs.text = ConnectionLogFormatter.format(entries.takeLast(6))
                    binding.btnCopyLogs.isEnabled = entries.isNotEmpty()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) refreshStatus()
    }

    private fun refreshStatus() {
        val sms = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val battery = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        binding.smsStatus.text = if (sms) "허용됨" else "허용 필요"
        binding.notificationStatus.text = if (notifications) "허용됨" else "허용 필요"
        binding.batteryStatus.text = if (battery) "제한 없음" else "배터리 제한 해제 권장"
    }

    private fun openSettings(intent: Intent) {
        try { startActivity(intent) }
        catch (_: Exception) { Toast.makeText(this, "휴대폰 설정에서 SMS Relay 앱의 권한과 배터리 설정을 확인해 주세요", Toast.LENGTH_LONG).show() }
    }
}
