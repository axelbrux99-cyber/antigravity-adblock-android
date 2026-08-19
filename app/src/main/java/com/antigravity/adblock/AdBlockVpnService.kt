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

    private var vpnInterface: ParcelFileDescriptor? = null
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, buildNotification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, buildNotification("Starting…"))
        }
        BlocklistManager.load(applicationContext)
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        val builder = Builder()
            .setSession("Antigravity AdBlock")
            .addAddress("10.0.0.2", 32)
            .addDnsServer("10.0.0.2")
            .addRoute("10.0.0.2", 32)
            .addRoute(DNS_UPSTREAM, 32)
            .addRoute("1.0.0.1", 32)
            .addRoute("8.8.8.8", 32)
            .addRoute("8.8.4.4", 32)
            .addRoute("9.9.9.9", 32)
            .addRoute("149.112.112.112", 32)
            .addRoute("208.67.222.222", 32)
            .addRoute("208.67.220.220", 32)
            .addRoute("94.140.14.14", 32)
            .addRoute("94.140.15.15", 32)
            .setMtu(1500)
            .setBlocking(false)

        runCatching {
            builder.addAddress("fd00::2", 128)
            builder.addDnsServer("fd00::2")
            builder.addRoute("fd00::2", 128)
            builder.addRoute("2606:4700:4700::1111", 128)
            builder.addRoute("2001:4860:4860::8888", 128)
        }

        vpnInterface = builder.establish() ?: run {
            Log.e("AG", "Failed to establish VPN interface")
            stopSelf()
            return
        }

        val fd = vpnInterface!!.fileDescriptor
        val input  = FileInputStream(fd)
        val output = FileOutputStream(fd)

        serviceScope.launch {
            val packet = ByteArray(32767)
            updateNotification("🛡️ Protecting — ${BlocklistManager.size()} domains blocked")

            while (isActive) {
                val length = runCatching { input.read(packet) }.getOrDefault(-1)
                if (length <= 0) {
                    delay(5)
                    continue
                }

                val buf = ByteBuffer.wrap(packet, 0, length)
                val ipHeader = parseIpv4Header(buf) ?: continue

                // Only handle UDP port 53 (DNS)
                if (ipHeader.protocol != 17 || ipHeader.dstPort != DNS_PORT) {
                    // Forward all non-DNS traffic directly (bypass VPN)
                    // ponytail: non-DNS traffic flows normally; we only intercept DNS
                    continue
                }

                val dnsPayload = packet.copyOfRange(ipHeader.dnsOffset, ipHeader.dnsOffset + ipHeader.dnsLength)
                val domain = DnsPacketParser.parseDomain(dnsPayload)

                if (domain != null && BlocklistManager.isBlocked(domain)) {
                    // Build blocked response and write back into TUN
                    val blockedDns = DnsPacketParser.buildBlockedResponse(dnsPayload)
                    val response   = buildIpv4UdpResponse(ipHeader, blockedDns)
                    runCatching { output.write(response) }
                    val count = blockedCount.incrementAndGet()
                    if (count % 10 == 0L) {
                        updateNotification("🛡️ Blocked $count requests")
                    }
                } else {
                    // Forward DNS query to 1.1.1.1 and relay response
                    launch {
                        val response = forwardDns(dnsPayload) ?: return@launch
                        val ipResponse = buildIpv4UdpResponse(ipHeader, response)
                        runCatching { output.write(ipResponse) }
                    }
                }
            }
        }
    }

    /** Forward DNS query to upstream (1.1.1.1), return raw DNS response bytes. */
    private suspend fun forwardDns(query: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            DatagramSocket().use { socket ->
                protect(socket)   // Bypass our own VPN for upstream DNS
                socket.soTimeout = 3000
                val upstream = InetAddress.getByName(DNS_UPSTREAM)
                socket.send(DatagramPacket(query, query.size, upstream, DNS_PORT))
                val buf = ByteArray(4096)
                val response = DatagramPacket(buf, buf.size)
                socket.receive(response)
                buf.copyOf(response.length)
            }
        }.getOrNull()
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
