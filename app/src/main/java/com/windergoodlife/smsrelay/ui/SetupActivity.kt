package com.windergoodlife.smsrelay.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.windergoodlife.smsrelay.SmsRelayApp
import com.windergoodlife.smsrelay.databinding.ActivitySetupBinding
import com.windergoodlife.smsrelay.worker.HeartbeatWorker
import com.windergoodlife.smsrelay.worker.PendingSmsWorker

class SetupActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySetupBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val ok = result.values.all { it }
        binding.setupMessage.text = if (ok) "SMS 권한 허용됨" else "SMS 권한이 필요합니다"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val store = (application as SmsRelayApp).tokenStore
        store.getBaseUrl()?.let { binding.inputBaseUrl.setText(it) }
        store.getDeviceId()?.let { binding.inputDeviceId.setText(it) }

        binding.btnRequestSms.setOnClickListener {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
            )
        }
        binding.btnBatterySetup.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        binding.btnSave.setOnClickListener { saveAndContinue() }
    }

    private fun saveAndContinue() {
        val base = binding.inputBaseUrl.text?.toString()?.trim().orEmpty()
        val deviceId = binding.inputDeviceId.text?.toString()?.trim().orEmpty()
        val token = binding.inputToken.text?.toString()?.trim().orEmpty()
        if (!base.startsWith("https://")) {
            binding.setupMessage.text = "Base URL은 https:// 만 허용됩니다"
            return
        }
        if (deviceId.isBlank() || token.isBlank()) {
            binding.setupMessage.text = "Device ID와 Token을 입력하세요"
            return
        }
        try {
            val app = application as SmsRelayApp
            app.tokenStore.save(base, deviceId, token)
            app.repository.refreshApi()
            PendingSmsWorker.enqueue(this)
            HeartbeatWorker.enqueuePeriodic(this)
            Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } catch (e: Exception) {
            binding.setupMessage.text = e.message ?: "저장 실패"
        }
    }
}
