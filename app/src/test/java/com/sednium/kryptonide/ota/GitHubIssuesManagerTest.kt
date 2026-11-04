package com.sednium.kryptonide.ota

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class GitHubIssuesManagerTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun testRepositoryConstants() {
        assertEquals("Sednium-Technologies", GitHubIssuesManager.REPO_OWNER)
        assertEquals("kryptonide-native", GitHubIssuesManager.REPO_NAME)
        assertEquals(
            "https://github.com/Sednium-Technologies/kryptonide-native/issues",
            GitHubIssuesManager.ISSUES_WEB_URL
        )
    }

    @Test
    fun testBuildBrowserNewIssueUrlEncoding() {
        val title = "Crash on App Launch: NPE"
        val body = "Steps to reproduce:\n1. Open App\n2. Tap AI\nResult: Crash & freeze!"
        val labels = listOf("bug", "priority-high", "ai-engine")

        val url = GitHubIssuesManager.buildBrowserNewIssueUrl(title, body, labels)
        assertTrue(url.startsWith("https://github.com/Sednium-Technologies/kryptonide-native/issues/new?"))

        val query = url.substringAfter('?')
        val params = query.split('&').associate {
            val (k, v) = it.split('=')
            k to URLDecoder.decode(v, StandardCharsets.UTF_8.toString())
        }

        assertEquals(title, params["title"])
        assertEquals(body, params["body"])
        assertEquals("bug,priority-high,ai-engine", params["labels"])
    }

    @Test
    fun testParseGitHubIssuesJsonFiltersOutPullRequests() {
        val sampleApiResponse = """
        [
          {
            "number": 101,
            "title": "Fix stylus drawing offset",
            "body": "Stylus offset happens on API 34",
            "state": "open",
            "user": { "login": "developer1" },
            "html_url": "https://github.com/Sednium-Technologies/kryptonide-native/issues/101",
            "comments": 3,
            "created_at": "2026-11-01T10:00:00Z",
            "labels": [{ "name": "bug" }, { "name": "ui" }]
          },
          {
            "number": 102,
            "title": "PR: Upgrade gradle dependencies",
            "body": "This is a pull request",
            "state": "open",
            "user": { "login": "bot" },
            "pull_request": { "url": "https://api.github.com/repos/Sednium-Technologies/kryptonide-native/pulls/102" },
            "html_url": "https://github.com/Sednium-Technologies/kryptonide-native/pull/102",
            "comments": 0,
            "created_at": "2026-11-01T10:05:00Z",
            "labels": [{ "name": "dependencies" }]
          },
          {
            "number": 103,
            "title": "Needle-3 on-device inference speedup",
            "body": "Inference takes <15ms on cactus quants",
            "state": "closed",
            "user": { "login": "lead-arch" },
            "html_url": "https://github.com/Sednium-Technologies/kryptonide-native/issues/103",
            "comments": 7,
            "created_at": "2026-11-01T08:00:00Z",
            "labels": [{ "name": "feature" }, { "name": "needle-3" }]
          }
        ]
        """.trimIndent()

        val array = json.parseToJsonElement(sampleApiResponse).jsonArray
        val issues = array.mapNotNull { element ->
            val obj = element.jsonObject
            if (obj.containsKey("pull_request")) return@mapNotNull null

            val number = obj["number"]?.jsonPrimitive?.intOrNull ?: 0
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "Issue #$number"
            val issueBody = obj["body"]?.jsonPrimitive?.contentOrNull ?: ""
            val state = obj["state"]?.jsonPrimitive?.contentOrNull ?: "open"
            val author = obj["user"]?.jsonObject?.get("login")?.jsonPrimitive?.contentOrNull ?: "anonymous"
            val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull ?: ""
            val comments = obj["comments"]?.jsonPrimitive?.intOrNull ?: 0
            val createdAt = obj["created_at"]?.jsonPrimitive?.contentOrNull ?: ""

            val labels = obj["labels"]?.jsonArray?.mapNotNull {
                it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            } ?: emptyList()

            IssueItem(
                number = number,
                title = title,
                body = issueBody,
                state = state,
                author = author,
                labels = labels,
                htmlUrl = htmlUrl,
                comments = comments,
                createdAt = createdAt
            )
        }

        // PR #102 should be filtered out
        assertEquals(2, issues.size)

        val first = issues[0]
        assertEquals(101, first.number)
        assertEquals("Fix stylus drawing offset", first.title)
        assertEquals("open", first.state)
        assertEquals("developer1", first.author)
        assertEquals(listOf("bug", "ui"), first.labels)
        assertEquals(3, first.comments)

        val second = issues[1]
        assertEquals(103, second.number)
        assertEquals("closed", second.state)
        assertEquals(listOf("feature", "needle-3"), second.labels)
    }

    @Test
    fun testIssueItemDataClass() {
        val item = IssueItem(
            number = 42,
            title = "Sample Issue",
            body = "Description text",
            state = "open",
            author = "ankush",
            labels = listOf("enhancement"),
            htmlUrl = "https://github.com/Sednium-Technologies/kryptonide-native/issues/42",
            comments = 5,
            createdAt = "2026-11-01"
        )

        assertEquals(42, item.number)
        assertEquals("Sample Issue", item.title)
        assertEquals("open", item.state)
        assertEquals(1, item.labels.size)
        assertEquals("enhancement", item.labels[0])
    }
}
