package com.antigravity.adblock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * DNS-intercept VPN service.
 * Creates a local TUN interface, reads raw IP packets, intercepts UDP port 53 (DNS),
 * checks domain against blocklist — blocks or forwards to 1.1.1.1.
 * ponytail: DNS-only intercept, no full traffic proxying. Simple and sufficient for ad blocking.
 */
class AdBlockVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.antigravity.adblock.START"
        const val ACTION_STOP  = "com.antigravity.adblock.STOP"
        const val CHANNEL_ID   = "antigravity_vpn"
        const val NOTIF_ID     = 1
        const val DNS_UPSTREAM = "1.1.1.1"
        const val DNS_PORT     = 53
        val blockedCount = AtomicLong(0)

        data class IpHeader(
            val srcIp: ByteArray,
            val dstIp: ByteArray,
            val srcPort: Int,
            val dstPort: Int,
            val protocol: Int,
            val dnsOffset: Int,
            val dnsLength: Int
        )

        internal fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
            var sum = 0
            var i = offset
            while (i < offset + length - 1) {
                sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                i += 2
            }
            if ((length and 1) != 0) {
                sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
            }
            while (sum ushr 16 != 0) {
                sum = (sum and 0xFFFF) + (sum ushr 16)
            }
            return sum.inv() and 0xFFFF
        }

        internal fun parseIpv4Header(buf: ByteBuffer): IpHeader? {
            if (buf.remaining() < 28) return null
            val version = (buf.get(0).toInt() ushr 4) and 0xF
            if (version != 4) return null
            val ihl = (buf.get(0).toInt() and 0xF) * 4
            if (ihl < 20 || ihl + 8 > buf.limit()) return null
            val totalLength = ((buf.get(2).toInt() and 0xFF) shl 8) or (buf.get(3).toInt() and 0xFF)
            val protocol = buf.get(9).toInt() and 0xFF
            val srcIp = ByteArray(4).also { buf.position(12); buf.get(it) }
            val dstIp = ByteArray(4).also { buf.get(it) }
            buf.position(ihl)
            val srcPort = buf.getShort().toInt() and 0xFFFF
            val dstPort = buf.getShort().toInt() and 0xFFFF
            val udpLen = buf.getShort().toInt() and 0xFFFF
            if (udpLen < 8) return null
            val dnsOffset = ihl + 8
            val dnsLength = minOf(udpLen - 8, totalLength - ihl - 8, buf.limit() - dnsOffset)
            if (dnsLength <= 0) return null
            return IpHeader(srcIp, dstIp, srcPort, dstPort, protocol, dnsOffset, dnsLength)
        }

        /**
         * Build a complete IPv4 + UDP + DNS response packet to write back to TUN.
         * Swaps src/dst for the response direction.
         */
        internal fun buildIpv4UdpResponse(req: IpHeader, dns: ByteArray): ByteArray {
            val udpLen = 8 + dns.size
            val ipLen  = 20 + udpLen
            val buf = ByteBuffer.allocate(ipLen)

            // IPv4 header
            buf.put(0x45.toByte())                  // Version=4, IHL=5 (20 bytes)
            buf.put(0.toByte())                     // DSCP/ECN
            buf.putShort(ipLen.toShort())
            buf.putShort(0.toShort())               // ID
            buf.putShort(0x4000.toShort())          // Flags: Don't Fragment
            buf.put(64.toByte())                    // TTL
            buf.put(17.toByte())                    // Protocol: UDP
            buf.putShort(0.toShort())               // Checksum placeholder
            buf.put(req.dstIp)                      // src ← original dst
            buf.put(req.srcIp)                      // dst ← original src
            // Fill IP checksum
            val ipChecksum = calculateChecksum(buf.array(), 0, 20)
            buf.putShort(10, ipChecksum.toShort())

            // UDP header
            buf.putShort(req.dstPort.toShort())     // src port
            buf.putShort(req.srcPort.toShort())     // dst port
            buf.putShort(udpLen.toShort())
            buf.putShort(0.toShort())               // UDP checksum (optional in IPv4)

            buf.put(dns)
            return buf.array()
        }
    }

    // ─── Notification ──────────────────────────────────────────────────────

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ad Block VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Antigravity AdBlock")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        serviceScope.cancel()
        vpnInterface?.close()
        vpnInterface = null
        getSharedPreferences("antigravity", MODE_PRIVATE).edit()
            .putBoolean("vpn_enabled", false).apply()
        super.onDestroy()
    }
}
