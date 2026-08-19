package com.antigravity.adblock

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class Ipv4ChecksumTest {

    @Test
    fun testStandardIpv4ChecksumCalculation() {
        // Standard RFC 1071 example IPv4 header (20 bytes)
        // 45 00 00 3c 1c 46 40 00 40 06 [checksum: b1 e6] ac 10 0a 63 ac 10 0a 0c
        val header = byteArrayOf(
            0x45.toByte(), 0x00.toByte(), 0x00.toByte(), 0x3c.toByte(),
            0x1c.toByte(), 0x46.toByte(), 0x40.toByte(), 0x00.toByte(),
            0x40.toByte(), 0x06.toByte(), 0x00.toByte(), 0x00.toByte(),
            0xac.toByte(), 0x10.toByte(), 0x0a.toByte(), 0x63.toByte(),
            0xac.toByte(), 0x10.toByte(), 0x0a.toByte(), 0x0c.toByte()
        )

        val computed = AdBlockVpnService.calculateChecksum(header, 0, 20)
        assertEquals(0xb1e6, computed)

        // When inserting computed checksum back into header, verifying checksum over whole header yields 0
        header[10] = (computed ushr 8).toByte()
        header[11] = (computed and 0xFF).toByte()
        val verify = AdBlockVpnService.calculateChecksum(header, 0, 20)
        assertEquals(0, verify)
    }

    @Test
    fun testOddLengthChecksum() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val csum = AdBlockVpnService.calculateChecksum(data, 0, 3)
        assertTrue(csum in 0..0xFFFF)
    }

    @Test
    fun testParseIpv4UdpHeaderAndBuildResponse() {
        // Construct simulated UDP DNS query IP packet
        val ipLen = 20 + 8 + 17 // 20 IP + 8 UDP + 17 DNS
        val packet = ByteBuffer.allocate(ipLen)

        // IP Header
        packet.put(0x45.toByte())
        packet.put(0.toByte())
        packet.putShort(ipLen.toShort())
        packet.putShort(0x1234.toShort())
        packet.putShort(0x4000.toShort())
        packet.put(64.toByte())
        packet.put(17.toByte()) // UDP
        packet.putShort(0.toShort()) // checksum placeholder
        packet.put(byteArrayOf(10, 0, 0, 2)) // src: 10.0.0.2
        packet.put(byteArrayOf(1, 1, 1, 1))  // dst: 1.1.1.1

        // UDP Header
        packet.putShort(54321.toShort()) // srcPort
        packet.putShort(53.toShort())    // dstPort
        packet.putShort((8 + 17).toShort()) // udpLen
        packet.putShort(0.toShort())

        // Dummy DNS
        packet.put(ByteArray(17) { it.toByte() })

        val buf = ByteBuffer.wrap(packet.array())
        val parsed = AdBlockVpnService.parseIpv4Header(buf)

        assertNotNull(parsed)
        assertEquals(17, parsed!!.protocol)
        assertEquals(54321, parsed.srcPort)
        assertEquals(53, parsed.dstPort)
        assertEquals(28, parsed.dnsOffset)
        assertEquals(17, parsed.dnsLength)
        assertArrayEquals(byteArrayOf(10, 0, 0, 2), parsed.srcIp)
        assertArrayEquals(byteArrayOf(1, 1, 1, 1), parsed.dstIp)

        // Build Response
        val dummyResponseDns = ByteArray(32) { (it + 1).toByte() }
        val responseBytes = AdBlockVpnService.buildIpv4UdpResponse(parsed, dummyResponseDns)

        val respBuf = ByteBuffer.wrap(responseBytes)
        assertEquals(0x45.toByte(), respBuf.get(0))
        val respTotalLen = respBuf.getShort(2).toInt() and 0xFFFF
        assertEquals(20 + 8 + 32, respTotalLen)

        // Check swapped IPs
        val respSrcIp = ByteArray(4).also { respBuf.position(12); respBuf.get(it) }
        val respDstIp = ByteArray(4).also { respBuf.get(it) }
        assertArrayEquals(byteArrayOf(1, 1, 1, 1), respSrcIp)
        assertArrayEquals(byteArrayOf(10, 0, 0, 2), respDstIp)

        // Check swapped Ports
        val respSrcPort = respBuf.getShort(20).toInt() and 0xFFFF
        val respDstPort = respBuf.getShort(22).toInt() and 0xFFFF
        assertEquals(53, respSrcPort)
        assertEquals(54321, respDstPort)

        // Verify IP checksum
        val verifyChecksum = AdBlockVpnService.calculateChecksum(responseBytes, 0, 20)
        assertEquals(0, verifyChecksum)
    }
}
