package com.antigravity.adblock

import android.content.Context

/**
 * Loads and manages the ad domain blocklist from assets/blocklist.txt.
 * ponytail: HashSet lookup is O(1) — fast enough for 10K+ domains without trie overhead.
 */
object BlocklistManager {

    @Volatile
    private var blockedDomains: Set<String> = emptySet()
    @Volatile
    private var loaded = false

    @Synchronized
    fun load(reader: java.io.BufferedReader) {
        val set = HashSet<String>(8192)
        reader.lineSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { set.add(it) }
        blockedDomains = set
        loaded = true
    }

    fun loadFromStream(inputStream: java.io.InputStream) {
        inputStream.bufferedReader().use { load(it) }
    }

    fun load(context: Context) {
        if (loaded) return
        context.assets.open("blocklist.txt").bufferedReader().use { load(it) }
    }

    /**
     * Returns true if [domain] or any of its parent domains are in the blocklist.
     * e.g. "sub.doubleclick.net" matches "doubleclick.net"
     */
    fun isBlocked(domain: String?): Boolean {
        val lower = domain?.lowercase()?.trimEnd('.') ?: return false
        if (lower.isEmpty()) return false
        val domains = blockedDomains
        if (domains.contains(lower)) return true
        // Walk up: sub.example.com → example.com → com
        var dotIdx = lower.indexOf('.')
        while (dotIdx != -1) {
            val parent = lower.substring(dotIdx + 1)
            if (parent.isNotEmpty() && domains.contains(parent)) return true
            dotIdx = lower.indexOf('.', dotIdx + 1)
        }
        return false
    }

    fun size(): Int = blockedDomains.size

    @Synchronized
    fun clear() {
        blockedDomains = emptySet()
        loaded = false
    }
}
