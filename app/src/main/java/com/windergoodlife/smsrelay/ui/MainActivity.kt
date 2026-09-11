package com.windergoodlife.smsrelay.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.windergoodlife.smsrelay.SmsRelayApp
import com.windergoodlife.smsrelay.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val vm: StatusViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshStaticRows()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SmsRelayApp
        if (!app.tokenStore.isConfigured()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.deviceLabel.text = "device: ${app.tokenStore.getDeviceId()} · ${app.tokenStore.getBaseUrl()}"

        ensurePermissions()
        vm.ensureWorkers()
        vm.syncFromCheckpoint()

        vm.pendingCount.observe(this) { binding.rowPending.text = "대기중          ${it}건" }
        vm.failedCount.observe(this) { binding.rowFailed.text = "실패            ${it}건" }
        vm.authErrorCount.observe(this) { binding.rowAuthError.text = "인증오류        ${it}건" }
        vm.latestSms.observe(this) {
            binding.rowLastSms.text = "마지막 SMS      ${vm.formatTime(it?.receivedAt)}"
        }
        vm.latestSent.observe(this) {
            binding.rowLastUpload.text = "마지막 전송     ${vm.formatTime(it?.lastAttemptAt)}"
        }
        vm.message.observe(this) { binding.statusMessage.text = it }
        vm.serverOk.observe(this) { ok ->
            binding.rowServer.text = when (ok) {
                true -> "서버 상태       ● 정상"
                false -> "서버 상태       ● 오류"
                null -> "서버 상태       ● 미확인"
            }
        }

        binding.btnPing.setOnClickListener { vm.ping() }
        binding.btnRetry.setOnClickListener { vm.retryPending() }
        binding.btnSync.setOnClickListener { vm.syncInbox() }
        binding.btnBattery.setOnClickListener { openBatterySettings() }
        binding.btnHealth.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("상태 점검")
                .setMessage(vm.healthCheckText())
                .setPositiveButton("확인", null)
                .show()
        }
        binding.btnSetup.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        refreshStaticRows()
        vm.ping()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) refreshStaticRows()
    }

    private fun refreshStaticRows() {
        binding.rowSmsPermission.text =
            if (vm.hasSmsPermission()) "SMS 권한        ● 허용" else "SMS 권한        ● 부족"
        binding.rowBattery.text =
            if (vm.isBatteryUnrestricted()) "배터리 최적화   ● 제외됨" else "배터리 최적화   ● 제한 가능"
    }

    private fun ensurePermissions() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.RECEIVE_SMS
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.READ_SMS
        }
        if (need.isNotEmpty()) permissionLauncher.launch(need.toTypedArray())
    }

    private fun openBatterySettings() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "배터리 설정을 열 수 없습니다", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
