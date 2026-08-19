package com.antigravity.adblock

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    companion object {
        private const val VPN_PERMISSION_REQUEST = 100
    }

    private lateinit var toggleSwitch: SwitchMaterial
    private lateinit var statusText: TextView
    private lateinit var counterText: TextView
    private lateinit var domainCountText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var isUpdating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toggleSwitch    = findViewById(R.id.toggle_protection)
        statusText      = findViewById(R.id.status_text)
        counterText     = findViewById(R.id.blocked_counter)
        domainCountText = findViewById(R.id.domain_count)

        val prefs = getSharedPreferences("antigravity", MODE_PRIVATE)
        val wasEnabled = prefs.getBoolean("vpn_enabled", false)
        toggleSwitch.isChecked = wasEnabled
        updateUI(wasEnabled)

        toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) requestVpnPermission() else stopVpn()
        }

        startCounterUpdates()
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_PERMISSION_REQUEST)
        } else {
            startVpn()
        }
    }

    @Deprecated("Using deprecated API for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION_REQUEST) {
            if (resultCode == Activity.RESULT_OK) {
                startVpn()
            } else {
                toggleSwitch.isChecked = false
                Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startVpn() {
        getSharedPreferences("antigravity", MODE_PRIVATE).edit()
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
        getSharedPreferences("antigravity", MODE_PRIVATE).edit()
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
            statusText.text = "🛡️ Protection Active"
            statusText.setTextColor(getColor(R.color.color_active))
        } else {
            statusText.text = "⭕ Protection Off"
            statusText.setTextColor(getColor(R.color.color_disabled))
        }
        BlocklistManager.load(applicationContext)
        domainCountText.text = "${BlocklistManager.size()} domains in blocklist"
    }

    private fun startCounterUpdates() {
        isUpdating = true
        handler.post(object : Runnable {
            override fun run() {
                val count = AdBlockVpnService.blockedCount.get()
                counterText.text = count.toString()
                if (isUpdating) handler.postDelayed(this, 1000)
            }
        })
    }

    override fun onDestroy() {
        isUpdating = false
        super.onDestroy()
    }
}
