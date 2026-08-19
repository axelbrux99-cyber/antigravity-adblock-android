package com.antigravity.adblock

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class BlocklistManagerTest {

    @Before
    fun setUp() {
        BlocklistManager.clear()
        val rules = """
            # Sample test blocklist
            doubleclick.net
            googleadservices.com
            unityads.unity3d.com
            ads.unity.com
            admaster.co.id
            coinhive.com
            
            # Subdomain testing
            tracker.analytics.example.com
        """.trimIndent()
        BlocklistManager.load(BufferedReader(StringReader(rules)))
    }

    @Test
    fun testDirectDomainMatch() {
        assertTrue(BlocklistManager.isBlocked("doubleclick.net"))
        assertTrue(BlocklistManager.isBlocked("googleadservices.com"))
        assertTrue(BlocklistManager.isBlocked("coinhive.com"))
    }

    @Test
    fun testSubdomainMatch() {
        assertTrue(BlocklistManager.isBlocked("ad.doubleclick.net"))
        assertTrue(BlocklistManager.isBlocked("sub.ad.doubleclick.net"))
        assertTrue(BlocklistManager.isBlocked("deep.nested.googleadservices.com"))
    }

    @Test
    fun testUnblockedDomains() {
        assertFalse(BlocklistManager.isBlocked("google.com"))
        assertFalse(BlocklistManager.isBlocked("unity3d.com")) // Not blocked as whole engine domain
        assertFalse(BlocklistManager.isBlocked("idx.co.id"))    // False positive removed
        assertFalse(BlocklistManager.isBlocked("example.com"))
        assertFalse(BlocklistManager.isBlocked("analytics.example.com")) // Only tracker.analytics.example.com blocked
    }

    @Test
    fun testSpecificSubdomainBlocking() {
        assertTrue(BlocklistManager.isBlocked("unityads.unity3d.com"))
        assertTrue(BlocklistManager.isBlocked("sub.unityads.unity3d.com"))
        assertTrue(BlocklistManager.isBlocked("tracker.analytics.example.com"))
        assertTrue(BlocklistManager.isBlocked("sub.tracker.analytics.example.com"))
    }

    @Test
    fun testCaseInsensitiveAndTrailingDot() {
        assertTrue(BlocklistManager.isBlocked("DOUBLECLICK.NET"))
        assertTrue(BlocklistManager.isBlocked("Ad.DoubleClick.Net."))
        assertTrue(BlocklistManager.isBlocked("doubleclick.net."))
    }

    @Test
    fun testNullAndEmptyInputs() {
        assertFalse(BlocklistManager.isBlocked(null))
        assertFalse(BlocklistManager.isBlocked(""))
        assertFalse(BlocklistManager.isBlocked("   "))
    }

    @Test
    fun testBlocklistSize() {
        assertEquals(7, BlocklistManager.size())
    }
}
