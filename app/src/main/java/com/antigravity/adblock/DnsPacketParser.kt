package com.antigravity.adblock

import java.nio.ByteBuffer

/**
 * Minimal DNS packet parser and response builder.
 * Only handles A/AAAA queries — all we need for ad blocking.
 * ponytail: raw ByteBuffer manipulation, no external DNS library needed.
 */
object DnsPacketParser {

    /** Extract queried domain name from a raw DNS query payload. Returns null on parse error. */
    fun parseDomain(dnsPayload: ByteArray): String? = runCatching {
        val buf = ByteBuffer.wrap(dnsPayload)
        buf.position(12) // Skip header (6 x 2-byte fields)
        buildString {
            while (true) {
                val len = buf.get().toInt() and 0xFF
                if (len == 0) break
                if (isNotEmpty()) append('.')
                repeat(len) { append(buf.get().toInt().toChar()) }
            }
        }.ifEmpty { null }
    }.getOrNull()

    /**
     * Build a DNS response that resolves [domain] to 0.0.0.0 (blocked).
     * Copies transaction ID and question from original query.
     */
    fun buildBlockedResponse(queryPayload: ByteArray): ByteArray {
        val query = ByteBuffer.wrap(queryPayload)
        val txId0 = query.get()
        val txId1 = query.get()

        // Rebuild question section
        val questionStart = 12
        val questionBytes = extractQuestion(queryPayload)

        val response = ByteBuffer.allocate(questionStart + questionBytes.size + 16)
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
        response.put(0); response.put(0); response.put(0); response.put(0) // 0.0.0.0

        return response.array().copyOf(response.position())
    }

    private fun extractQuestion(dnsPayload: ByteArray): ByteArray {
        var i = 12
        while (i < dnsPayload.size && dnsPayload[i] != 0.toByte()) {
            i += (dnsPayload[i].toInt() and 0xFF) + 1
        }
        i += 5 // null byte + QTYPE (2) + QCLASS (2)
        return dnsPayload.copyOfRange(12, i.coerceAtMost(dnsPayload.size))
    }
}
