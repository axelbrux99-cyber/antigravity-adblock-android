package com.antigravity.adblock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts VPN service after device reboot if protection was enabled.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("antigravity", Context.MODE_PRIVATE)
        if (prefs.getBoolean("vpn_enabled", false)) {
            val vpnIntent = Intent(context, AdBlockVpnService::class.java)
            context.startForegroundService(vpnIntent)
        }
    }
}
