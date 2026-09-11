package com.windergoodlife.smsrelay.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.windergoodlife.smsrelay.R
import com.windergoodlife.smsrelay.SmsRelayApp
import com.windergoodlife.smsrelay.databinding.ActivitySetupBinding
import com.windergoodlife.smsrelay.repository.RelayLoginException
import com.windergoodlife.smsrelay.service.RelayForegroundService
import com.windergoodlife.smsrelay.worker.HeartbeatWorker
import com.windergoodlife.smsrelay.worker.PendingSmsWorker
import kotlinx.coroutines.launch
import java.io.IOException

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
            // The ongoing notification is the only sign the relay is alive on a phone nobody
            // looks at, so it is requested together with SMS rather than left to chance.
            val wanted = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                wanted += Manifest.permission.POST_NOTIFICATIONS
            }
            permissionLauncher.launch(wanted.toTypedArray())
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
        val guidance = AutoStartGuide.forThisDevice()
        binding.autoStartSteps.text = guidance.steps.joinToString("\n") { "· $it" }
        binding.btnAutoStart.text = guidance.title
        binding.btnAutoStart.setOnClickListener {
            if (!AutoStartGuide.openSettings(this, guidance)) {
                Toast.makeText(this, "이 기기에서는 아래 안내대로 직접 설정하세요", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnSave.setOnClickListener { verifyAndRegister() }
    }

    /**
     * Registers the phone only after the server confirms the password.
     *
     * <p>Nothing is stored until the call succeeds. Saving first and discovering the mistake later,
     * when messages silently fail to upload, leaves the operator with no way to tell what is wrong.
     */
    private fun verifyAndRegister() {
        val base = binding.inputBaseUrl.text?.toString()?.trim().orEmpty()
        val deviceId = binding.inputDeviceId.text?.toString()?.trim().orEmpty()
        val password = binding.inputToken.text?.toString()?.trim().orEmpty()
        if (!base.startsWith("https://")) {
            showError("Base URL은 https:// 만 허용됩니다")
            return
        }
        if (deviceId.isBlank() || password.isBlank()) {
            showError("중계폰 이름과 비밀번호를 입력하세요")
            return
        }

        setBusy(true)
        binding.setupMessage.setTextColor(getColor(R.color.muted))
        binding.setupMessage.text = "서버에 확인하는 중…"

        lifecycleScope.launch {
            val app = application as SmsRelayApp
            val result = runCatching {
                app.repository.verifyRelayPassword(base, deviceId, password)
            }
            setBusy(false)
            result.onSuccess { response ->
                val token = response.deviceToken
                if (token.isNullOrBlank()) {
                    showError("서버가 토큰을 주지 않았습니다. 관리자에게 문의하세요")
                    return@onSuccess
                }
                app.tokenStore.save(base, deviceId, token)
                app.repository.refreshApi()
                PendingSmsWorker.enqueue(this@SetupActivity)
                HeartbeatWorker.enqueuePeriodic(this@SetupActivity)
                RelayForegroundService.start(this@SetupActivity)
                binding.setupMessage.setTextColor(getColor(R.color.ok))
                binding.setupMessage.text = "등록 완료 — ${response.displayName ?: deviceId}"
                Toast.makeText(this@SetupActivity, "등록 완료", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this@SetupActivity, MainActivity::class.java))
                finish()
            }.onFailure { error ->
                showError(describe(error))
            }
        }
    }

    /** Tells the operator which of the two things went wrong: the password or the connection. */
    private fun describe(error: Throwable): String = when {
        error is RelayLoginException && error.code == 401 -> "비밀번호가 맞지 않습니다"
        error is RelayLoginException && error.code == 400 -> "중계폰 이름을 확인하세요"
        error is RelayLoginException -> "서버 오류 (${error.code})"
        error is IOException -> "서버에 연결할 수 없습니다. 주소와 네트워크를 확인하세요"
        else -> error.message ?: "등록에 실패했습니다"
    }

    private fun showError(message: String) {
        binding.setupMessage.setTextColor(getColor(R.color.bad))
        binding.setupMessage.text = message
    }

    private fun setBusy(busy: Boolean) {
        binding.btnSave.isEnabled = !busy
        binding.btnSave.text = if (busy) "검증 중…" else getString(R.string.setup_save)
    }
}
