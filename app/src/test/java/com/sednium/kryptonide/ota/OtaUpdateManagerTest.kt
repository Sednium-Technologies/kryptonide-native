package com.sednium.kryptonide.ota

import org.junit.Assert.*
import org.junit.Test

class OtaUpdateManagerTest {

    @Test
    fun testParseVersionParts() {
        assertEquals(Triple(4, 0, 0), OtaUpdateManager.parseVersionParts("4.0"))
        assertEquals(Triple(4, 0, 0), OtaUpdateManager.parseVersionParts("v4.0"))
        assertEquals(Triple(4, 0, 0), OtaUpdateManager.parseVersionParts("V4.0.0"))
        assertEquals(Triple(4, 0, 1), OtaUpdateManager.parseVersionParts("v4.0.1"))
        assertEquals(Triple(4, 1, 0), OtaUpdateManager.parseVersionParts("4.1"))
        assertEquals(Triple(5, 0, 0), OtaUpdateManager.parseVersionParts("release-5.0.0"))
        assertEquals(Triple(5, 1, 2), OtaUpdateManager.parseVersionParts("v5.1.2-beta"))
        assertEquals(Triple(4, 0, 0), OtaUpdateManager.parseVersionParts("v4.0.0+build40"))
    }

    @Test
    fun testIsNewerVersion() {
        val current = "4.0"

        // Same version (should be false - avoid self-update loop)
        assertFalse(OtaUpdateManager.isNewerVersion(current, "4.0"))
        assertFalse(OtaUpdateManager.isNewerVersion(current, "v4.0"))
        assertFalse(OtaUpdateManager.isNewerVersion(current, "v4.0.0"))
        assertFalse(OtaUpdateManager.isNewerVersion(current, "v4.0.0-release"))
        assertFalse(OtaUpdateManager.isNewerVersion(current, "release-4.0.0"))

        // Older version (should be false)
        assertFalse(OtaUpdateManager.isNewerVersion(current, "3.9"))
        assertFalse(OtaUpdateManager.isNewerVersion(current, "v3.9.9"))
        assertFalse(OtaUpdateManager.isNewerVersion(current, "v1.0.0"))

        // Newer patch version (should be true)
        assertTrue(OtaUpdateManager.isNewerVersion(current, "v4.0.1"))
        assertTrue(OtaUpdateManager.isNewerVersion(current, "4.0.2"))
        assertTrue(OtaUpdateManager.isNewerVersion(current, "release-4.0.1"))

        // Newer minor version (should be true)
        assertTrue(OtaUpdateManager.isNewerVersion(current, "v4.1"))
        assertTrue(OtaUpdateManager.isNewerVersion(current, "4.1.0"))
        assertTrue(OtaUpdateManager.isNewerVersion(current, "v4.2.1"))

        // Newer major version (should be true)
        assertTrue(OtaUpdateManager.isNewerVersion(current, "v5.0"))
        assertTrue(OtaUpdateManager.isNewerVersion(current, "v5.0.0"))
        assertTrue(OtaUpdateManager.isNewerVersion(current, "v5.0-beta"))
        assertTrue(OtaUpdateManager.isNewerVersion(current, "10.0.0"))

        // Comparison from patch base
        assertFalse(OtaUpdateManager.isNewerVersion("4.1.2", "v4.1.1"))
        assertFalse(OtaUpdateManager.isNewerVersion("4.1.2", "v4.1.2"))
        assertTrue(OtaUpdateManager.isNewerVersion("4.1.2", "v4.1.3"))
    }

    @Test
    fun testBuildBrowserNewIssueUrl() {
        val title = "Crash on Send"
        val body = "Detailed description with symbols: & = ?"
        val labels = listOf("bug", "ai-model")

        val url = GitHubIssuesManager.buildBrowserNewIssueUrl(title, body, labels)

        assertTrue(url.startsWith("https://github.com/Sednium-Technologies/kryptonide-native/issues/new"))
        assertTrue(url.contains("title=Crash+on+Send") || url.contains("title=Crash%20on%20Send"))
        assertTrue(url.contains("labels=bug%2Cai-model") || url.contains("labels=bug,ai-model"))
        assertTrue(url.contains("symbols"))
    }

    @Test
    fun testRepoUrlMatchesSpecification() {
        assertEquals("Sednium-Technologies", OtaUpdateManager.REPO_OWNER)
        assertEquals("kryptonide-native", OtaUpdateManager.REPO_NAME)
        assertEquals("https://github.com/Sednium-Technologies/kryptonide-native", OtaUpdateManager.GITHUB_REPO_URL)
        assertEquals("https://github.com/Sednium-Technologies/kryptonide-native/issues", GitHubIssuesManager.ISSUES_WEB_URL)
    }

    @Test
    fun testOtaCheckResultClasses() {
        val upToDate = OtaCheckResult.UpToDate("4.0", "v3.0.0")
        assertEquals("4.0", upToDate.currentVersion)
        assertEquals("v3.0.0", upToDate.latestTag)

        val err = OtaCheckResult.Error("Network error", isNetworkError = true)
        assertTrue(err.isNetworkError)
        assertEquals("Network error", err.message)
    }
}
