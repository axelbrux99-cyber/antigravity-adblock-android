package com.antigravity.adblock

import java.nio.ByteBuffer

/**
 * Minimal DNS packet parser and response builder.
 * Only handles A/AAAA queries — all we need for ad blocking.
 * ponytail: raw ByteBuffer manipulation, no external DNS library needed.
 */
object DnsPacketParser {

    private const val HEADER_SIZE = 12
    private const val MIN_DNS_PACKET_SIZE = 17 // 12 header + at least 1 label (1 len + 1 char) + 1 null + 2 qtype + 2 qclass
    private const val MAX_LABEL_LENGTH = 63
    private const val MAX_DOMAIN_LENGTH = 253

    /** Extract queried domain name from a raw DNS query payload. Returns null on parse error or non-query. */
    fun parseDomain(dnsPayload: ByteArray): String? = runCatching {
        if (dnsPayload.size < MIN_DNS_PACKET_SIZE) return null

        // QR bit is bit 7 of byte 2 (0 = query, 1 = response)
        val qr = (dnsPayload[2].toInt() and 0x80) ushr 7
        if (qr != 0) return null

        // QDCOUNT (number of questions) must be >= 1
        val qdCount = ((dnsPayload[4].toInt() and 0xFF) shl 8) or (dnsPayload[5].toInt() and 0xFF)
        if (qdCount < 1) return null

        val buf = ByteBuffer.wrap(dnsPayload)
        buf.position(HEADER_SIZE)

        var totalDomainLength = 0
        buildString {
            while (buf.hasRemaining()) {
                val len = buf.get().toInt() and 0xFF
                if (len == 0) break

                // Compression pointers (0xC0) or invalid label lengths
                if ((len and 0xC0) != 0 || len !in 1..MAX_LABEL_LENGTH || buf.remaining() < len) {
                    return null
                }

                if (isNotEmpty()) append('.')
                totalDomainLength += len + 1
                if (totalDomainLength > MAX_DOMAIN_LENGTH) return null

                val labelBytes = ByteArray(len)
                buf.get(labelBytes)
                append(String(labelBytes, Charsets.US_ASCII))
            }
        }.ifEmpty { null }
    }.getOrNull()

    /**
     * Build a DNS response that resolves [domain] to 0.0.0.0 (blocked).
     * Copies transaction ID and question from original query.
     */
    fun buildBlockedResponse(queryPayload: ByteArray): ByteArray {
        if (queryPayload.size < MIN_DNS_PACKET_SIZE) return ByteArray(0)

        val txId0 = queryPayload[0]
        val txId1 = queryPayload[1]

        val questionBytes = extractQuestion(queryPayload)
        if (questionBytes.isEmpty()) return ByteArray(0)

        val response = ByteBuffer.allocate(HEADER_SIZE + questionBytes.size + 16)
        // Header
        response.put(txId0)
        response.put(txId1)
        response.put(0x81.toByte())   // QR=1 (response), Opcode=0, AA=0, TC=0, RD=1
        response.put(0x80.toByte())   // RA=1, Z=0, RCODE=0 (no error)
        response.putShort(1)          // QDCOUNT = 1
        response.putShort(1)          // ANCOUNT = 1
        response.putShort(0)          // NSCOUNT = 0
        response.putShort(0)          // ARCOUNT = 0
        // Question
        response.put(questionBytes)
        // Answer — A record, 0.0.0.0, TTL 10s
        response.put(0xC0.toByte())   // Name: pointer
        response.put(0x0C.toByte())   // → offset 12 (question)
        response.putShort(1)          // Type: A
        response.putShort(1)          // Class: IN
        response.putInt(10)           // TTL: 10 seconds (short so real DNS resumes quickly if unblocked)
        response.putShort(4)          // RDLENGTH: 4 bytes
        response.put(0.toByte())
        response.put(0.toByte())
        response.put(0.toByte())
        response.put(0.toByte())      // 0.0.0.0

        return response.array().copyOf(response.position())
    }

    private fun extractQuestion(dnsPayload: ByteArray): ByteArray {
        if (dnsPayload.size < MIN_DNS_PACKET_SIZE) return ByteArray(0)
        var i = HEADER_SIZE
        while (i < dnsPayload.size) {
            val len = dnsPayload[i].toInt() and 0xFF
            if (len == 0) {
                // End of QNAME: null byte + QTYPE (2) + QCLASS (2) = 5 bytes
                val questionEnd = i + 5
                return if (questionEnd <= dnsPayload.size) {
                    dnsPayload.copyOfRange(HEADER_SIZE, questionEnd)
                } else {
                    ByteArray(0)
                }
            }
            if ((len and 0xC0) != 0 || len !in 1..MAX_LABEL_LENGTH) {
                return ByteArray(0)
            }
            i += len + 1
        }
        return ByteArray(0)
    }
}
