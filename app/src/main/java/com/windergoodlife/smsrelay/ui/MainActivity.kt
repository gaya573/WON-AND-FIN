package com.windergoodlife.smsrelay.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.windergoodlife.smsrelay.SmsRelayApp
import com.windergoodlife.smsrelay.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val vm: StatusViewModel by viewModels()
    private var permissionFlow = ConnectionPermissionFlow()
    private val smsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val granted = vm.hasSmsPermission()
        val canAskAgain = listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS).any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED && shouldShowRequestPermissionRationale(it)
        }
        handle(permissionFlow.permissionResult(granted, canAskAgain))
        refreshPermissionLabel()
    }
    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionFlow = ConnectionPermissionFlow(savedInstanceState?.getBoolean("pending_connect") == true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.keepContentInsideSystemBars()
        binding.btnSetup.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        binding.btnConnect.setOnClickListener {
            if (vm.connecting.value != true) handle(permissionFlow.connectTapped(vm.hasSmsPermission()))
        }
        vm.connectionStatus.observe(this) { binding.rowServer.text = it }
        vm.message.observe(this) { binding.statusMessage.text = it }
        vm.connecting.observe(this) {
            binding.btnConnect.isEnabled = !it
            binding.btnConnect.text = if (it) "연결 중…" else "연결"
        }
        vm.pendingCount.observe(this) {
            binding.rowPending.text = if (it > 0) "전송 대기 $it 건" else "전송 대기 없음"
        }
        vm.latestSent.observe(this) {
            binding.rowLastUpload.text = if (it == null) "아직 전달한 문자가 없습니다" else "마지막 전달 ${vm.formatTime(it.lastAttemptAt)}"
        }
        // Previously connected installations can reconnect; fresh installs wait for the Connect tap.
        if (!permissionFlow.pendingConnect && (application as SmsRelayApp).tokenStore.isConfigured() && vm.hasSmsPermission()) vm.connect()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            refreshPermissionLabel()
            handle(permissionFlow.resume(vm.hasSmsPermission()))
            vm.refreshPermissionState()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending_connect", permissionFlow.pendingConnect)
        super.onSaveInstanceState(outState)
    }

    private fun refreshPermissionLabel() {
        binding.rowPermissions.text = if (vm.hasSmsPermission()) "SMS 권한 허용됨" else "연결할 때 SMS 권한을 요청합니다"
    }

    private fun handle(action: ConnectionPermissionFlow.Action) {
        when (action) {
            ConnectionPermissionFlow.Action.REQUEST_SMS -> smsLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
            ConnectionPermissionFlow.Action.CONNECT -> {
                vm.connect()
                val prompts = getSharedPreferences("permission_prompts", MODE_PRIVATE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                    !prompts.getBoolean("notifications_requested", false)) {
                    prompts.edit().putBoolean("notifications_requested", true).apply()
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            ConnectionPermissionFlow.Action.DECLINED -> vm.permissionDeclined(false)
            ConnectionPermissionFlow.Action.SETTINGS_REQUIRED -> vm.permissionDeclined(true)
            ConnectionPermissionFlow.Action.NONE -> Unit
        }
    }
}
