package com.antigravity.adblock

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.antigravity.adblock.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var isUpdating = false

    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpn()
        } else {
            binding.toggleProtection.isChecked = false
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        // Notification permission result handled cleanly
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermission()

        val prefs = getSharedPreferences("antigravity", Context.MODE_PRIVATE)
        val wasEnabled = prefs.getBoolean("vpn_enabled", false)
        binding.toggleProtection.isChecked = wasEnabled
        updateUI(wasEnabled)

        binding.toggleProtection.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) requestVpnPermission() else stopVpn()
        }

        startCounterUpdates()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnLauncher.launch(intent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        getSharedPreferences("antigravity", Context.MODE_PRIVATE).edit()
            .putBoolean("vpn_enabled", true).apply()
        AdBlockVpnService.blockedCount.set(0)
        startForegroundService(
            Intent(this, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_START
            }
        )
        updateUI(true)
    }

    private fun stopVpn() {
        getSharedPreferences("antigravity", Context.MODE_PRIVATE).edit()
            .putBoolean("vpn_enabled", false).apply()
        startService(
            Intent(this, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_STOP
            }
        )
        updateUI(false)
    }

    private fun updateUI(active: Boolean) {
        if (active) {
            binding.statusText.text = "🛡️ Protection Active"
            binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.color_active))
        } else {
            binding.statusText.text = "⭕ Protection Off"
            binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.color_disabled))
        }
        BlocklistManager.load(applicationContext)
        binding.domainCount.text = "${BlocklistManager.size()} domains in blocklist"
    }

    private fun startCounterUpdates() {
        isUpdating = true
        handler.post(object : Runnable {
            override fun run() {
                val count = AdBlockVpnService.blockedCount.get()
                binding.blockedCounter.text = count.toString()
                if (isUpdating) handler.postDelayed(this, 1000)
            }
        })
    }

    override fun onDestroy() {
        isUpdating = false
        super.onDestroy()
    }
}
