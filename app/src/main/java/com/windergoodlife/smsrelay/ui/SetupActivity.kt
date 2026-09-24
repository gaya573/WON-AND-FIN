package com.windergoodlife.smsrelay.ui

import android.Manifest
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
import com.windergoodlife.smsrelay.databinding.ActivitySetupBinding

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
