package com.antigravity.adblock

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads, manages, and live-synchronizes the ad domain blocklist with GitHub.
 * ponytail: O(1) HashSet lookup, cached locally on internal storage, automatic online sync from GitHub raw URL.
 */
object BlocklistManager {

    private const val GITHUB_RAW_URL =
        "https://raw.githubusercontent.com/axelbrux99-cyber/antigravity-adblock-android/main/app/src/main/assets/blocklist.txt"
    private const val CACHE_FILE_NAME = "blocklist_cached.txt"

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

    /**
     * Loads the blocklist. Tries the cached GitHub update first; falls back to packaged assets.
     */
    fun load(context: Context) {
        if (loaded) return
        val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            runCatching {
                cacheFile.bufferedReader().use { load(it) }
            }.onFailure {
                loadFromAssets(context)
            }
        } else {
            loadFromAssets(context)
        }
    }

    private fun loadFromAssets(context: Context) {
        runCatching {
            context.assets.open("blocklist.txt").bufferedReader().use { load(it) }
        }
    }

    /**
     * Downloads the latest blocklist directly from GitHub when online.
     * Atomically swaps the in-memory blocklist and caches to local disk.
     */
    suspend fun updateFromGitHub(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(GITHUB_RAW_URL)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "AntigravityAdBlock-Android")
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("GitHub returned HTTP ${connection.responseCode}")
            }

            val text = connection.inputStream.bufferedReader().use { it.readText() }
            if (text.length < 50) error("Received empty blocklist from GitHub")

            // Parse text to new set
            val newSet = HashSet<String>(8192)
            text.lineSequence()
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { newSet.add(it) }

            if (newSet.isEmpty()) error("No valid domains parsed from GitHub")

            // Cache to disk
            val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
            cacheFile.writeText(text)

            // Atomically update in-memory set
            blockedDomains = newSet
            loaded = true

            // Record timestamp in SharedPreferences
            context.getSharedPreferences("antigravity", Context.MODE_PRIVATE).edit()
                .putLong("last_blocklist_sync", System.currentTimeMillis())
                .apply()

            Log.i("AG", "Successfully updated blocklist from GitHub: ${newSet.size} domains")
            newSet.size
        }
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
