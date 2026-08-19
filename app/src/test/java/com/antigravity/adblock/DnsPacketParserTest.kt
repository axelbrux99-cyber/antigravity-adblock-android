package com.antigravity.adblock

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class DnsPacketParserTest {

    private fun buildQueryPacket(domain: String, txId: Short = 0x1234.toShort(), qr: Int = 0, qdCount: Short = 1): ByteArray {
        val labels = domain.split(".")
        val qnameBytes = mutableListOf<Byte>()
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            qnameBytes.add(bytes.size.toByte())
            for (b in bytes) qnameBytes.add(b)
        }
        qnameBytes.add(0.toByte()) // null terminator

        val totalSize = 12 + qnameBytes.size + 4 // 12 header + qname + 2 qtype + 2 qclass
        val buf = ByteBuffer.allocate(totalSize)
        buf.putShort(txId)
        val flags = if (qr == 1) 0x8180.toShort() else 0x0100.toShort() // standard query (RD=1) or response
        buf.putShort(flags)
        buf.putShort(qdCount)
        buf.putShort(0.toShort()) // ANCOUNT
        buf.putShort(0.toShort()) // NSCOUNT
        buf.putShort(0.toShort()) // ARCOUNT

        for (b in qnameBytes) buf.put(b)
        buf.putShort(1.toShort()) // QTYPE = A (1)
        buf.putShort(1.toShort()) // QCLASS = IN (1)

        return buf.array()
    }

    @Test
    fun testParseStandardDomainQuery() {
        val packet = buildQueryPacket("google.com")
        val domain = DnsPacketParser.parseDomain(packet)
        assertEquals("google.com", domain)
    }

    @Test
    fun testParseMultiLevelSubdomainQuery() {
        val packet = buildQueryPacket("ad.service.doubleclick.net")
        val domain = DnsPacketParser.parseDomain(packet)
        assertEquals("ad.service.doubleclick.net", domain)
    }

    @Test
    fun testRejectResponsePacket() {
        val responsePacket = buildQueryPacket("google.com", qr = 1)
        val domain = DnsPacketParser.parseDomain(responsePacket)
        assertNull("DNS response packets should not be parsed as queries", domain)
    }

    @Test
    fun testRejectZeroQdCount() {
        val packet = buildQueryPacket("google.com", qdCount = 0)
        val domain = DnsPacketParser.parseDomain(packet)
        assertNull("DNS packet with QDCOUNT=0 should return null", domain)
    }

    @Test
    fun testRejectShortPackets() {
        assertNull(DnsPacketParser.parseDomain(ByteArray(0)))
        assertNull(DnsPacketParser.parseDomain(ByteArray(12)))
        assertNull(DnsPacketParser.parseDomain(ByteArray(16)))
    }

    @Test
    fun testRejectOverlongLabelLength() {
        val badPacket = buildQueryPacket("google.com")
        badPacket[12] = 64.toByte() // corrupt label length to 64 (> 63)
        assertNull(DnsPacketParser.parseDomain(badPacket))
    }

    @Test
    fun testRejectCompressionPointerInQuery() {
        val badPacket = buildQueryPacket("google.com")
        badPacket[12] = 0xC0.toByte() // compression pointer marker
        assertNull(DnsPacketParser.parseDomain(badPacket))
    }

    @Test
    fun testBuildBlockedResponseStructure() {
        val query = buildQueryPacket("doubleclick.net", txId = 0x5678.toShort())
        val response = DnsPacketParser.buildBlockedResponse(query)

        assertTrue("Response must have valid length", response.size >= 12 + 4)
        val buf = ByteBuffer.wrap(response)

        // Transaction ID
        assertEquals(0x5678.toShort(), buf.short)
        // Flags: 0x8180 (QR=1, RD=1, RA=1, RCODE=0)
        assertEquals(0x8180.toShort(), buf.short)
        // QDCOUNT = 1
        assertEquals(1.toShort(), buf.short)
        // ANCOUNT = 1
        assertEquals(1.toShort(), buf.short)
        // NSCOUNT = 0
        assertEquals(0.toShort(), buf.short)
        // ARCOUNT = 0
        assertEquals(0.toShort(), buf.short)

        // Skip question section
        val questionBytes = response.copyOfRange(12, response.size - 16)
        assertTrue(questionBytes.isNotEmpty())

        // Answer record (last 16 bytes)
        val ansBuf = ByteBuffer.wrap(response, response.size - 16, 16)
        assertEquals(0xC00C.toShort(), ansBuf.short) // Name pointer to offset 12
        assertEquals(1.toShort(), ansBuf.short)      // Type A (1)
        assertEquals(1.toShort(), ansBuf.short)      // Class IN (1)
        assertEquals(10, ansBuf.int)                 // TTL 10s
        assertEquals(4.toShort(), ansBuf.short)      // RDLENGTH 4
        assertEquals(0, ansBuf.get().toInt())        // 0.0.0.0
        assertEquals(0, ansBuf.get().toInt())
        assertEquals(0, ansBuf.get().toInt())
        assertEquals(0, ansBuf.get().toInt())
    }

    @Test
    fun testBuildBlockedResponseShortOrMalformedQuery() {
        assertEquals(0, DnsPacketParser.buildBlockedResponse(ByteArray(0)).size)
        assertEquals(0, DnsPacketParser.buildBlockedResponse(ByteArray(10)).size)
        assertEquals(0, DnsPacketParser.buildBlockedResponse(ByteArray(16)).size)
    }
}
