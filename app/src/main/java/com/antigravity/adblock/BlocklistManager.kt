package com.antigravity.adblock

import android.content.Context

/**
 * Loads and manages the ad domain blocklist from assets/blocklist.txt.
 * ponytail: HashSet lookup is O(1) — fast enough for 10K+ domains without trie overhead.
 */
object BlocklistManager {

    private val blockedDomains = HashSet<String>(8192)
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        context.assets.open("blocklist.txt").bufferedReader().use { reader ->
            reader.lineSequence()
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { blockedDomains.add(it) }
        }
        loaded = true
    }

    /**
     * Returns true if [domain] or any of its parent domains are in the blocklist.
     * e.g. "sub.doubleclick.net" matches "doubleclick.net"
     */
    fun isBlocked(domain: String): Boolean {
        val lower = domain.lowercase().trimEnd('.')
        if (blockedDomains.contains(lower)) return true
        // Walk up: sub.example.com → example.com → com
        var dotIdx = lower.indexOf('.')
        while (dotIdx != -1) {
            val parent = lower.substring(dotIdx + 1)
            if (blockedDomains.contains(parent)) return true
            dotIdx = lower.indexOf('.', dotIdx + 1)
        }
        return false
    }

    fun size(): Int = blockedDomains.size
}
