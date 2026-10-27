package de.chennemann.agentic.domain.pullrequests

import de.chennemann.agentic.t3.contract.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PullRequestRulesTest {
    @Test
    fun `change requests use their host's own term`() {
        assertEquals("merge request", changeRequestTerms("gitlab").singular)
        assertEquals("pull request", changeRequestTerms("github").singular)
        assertEquals("change request", changeRequestTerms("some-new-forge").singular)
        assertEquals("Merge requests", changeRequestTerms(listOf("gitlab", "gitlab")).capitalizedPlural)
        assertEquals("change requests", changeRequestTerms(listOf("gitlab", "github")).plural)
    }

    @Test
    fun `filters encode as host qualifiers and are omitted when empty`() {
        assertNull(PullRequestListFilterState().toJson())
        val json = PullRequestListFilterState(draft = "hide", labels = listOf("bug", "ui"), author = "me").toJson()!!
        assertEquals("hide", json["draft"]?.jsonPrimitive?.content)
        assertEquals("ui", json["labels"]?.jsonArray?.get(1)?.jsonArray?.single()?.jsonPrimitive?.content)
        assertEquals("me", json["author"]?.jsonPrimitive?.content)
        assertEquals(listOf("ui"), PullRequestListFilterState(labels = listOf("Bug", "ui")).toggleLabel("bug").labels)
    }

    @Test
    fun `rows narrow on the device for hosts that answer unnarrowed`() {
        val row = entry().copy(isDraft = true, author = PullRequestActor("Viewer"), labels = listOf(PullRequestLabel("Bug")), checksState = "failing")
        assertFalse(row.matches(PullRequestListFilterState(draft = "hide"), null))
        assertTrue(row.matches(PullRequestListFilterState(author = "me"), "viewer"))
        assertFalse(row.matches(PullRequestListFilterState(author = "me"), "someone"))
        assertTrue(row.matches(PullRequestListFilterState(labels = listOf("bug")), null))
        assertFalse(row.matches(PullRequestListFilterState(checks = "passing"), null))
        assertTrue(row.matches(PullRequestListFilterState(review = "approved"), null))
        assertFalse(row.copy(reviewDecision = "approved").matches(PullRequestListFilterState(review = "none"), null))
    }

    @Test
    fun `a row whose updatedAt moved drops its cached stat and asks again`() {
        val listed = entry().copy(additions = 0, deletions = 0)
        val (first, missing) = PullRequestStatsCache().withListed(listOf(listed))
        assertEquals(1, missing.size)
        val answered = first.withAnswered(listOf(PullRequestDiffStat("project", "owner/repo", 42, 5, 1)))
        assertEquals(5, answered.fill(listOf(listed)).single().additions)
        val (same, none) = answered.withListed(listOf(listed))
        assertTrue(none.isEmpty())
        assertEquals(5, same.fill(listOf(listed)).single().additions)
        val pushed = listed.copy(updatedAt = "2026-10-20T10:00:00Z")
        val (moved, again) = same.withListed(listOf(pushed))
        assertEquals(1, again.size)
        assertEquals(0, moved.fill(listOf(pushed)).single().additions)
    }

    @Test
    fun `live checks patch the detail only when observed no earlier`() {
        val detail = detail().copy(observedAt = 10.0)
        val checks = PullRequestChecks("open", listOf(PullRequestCheck("ci", "success")), observedAt = 9.0)
        assertSame(detail, resolveDetailChecks(detail, checks, true))
        assertEquals("success", resolveDetailChecks(detail, checks.copy(observedAt = 11.0), false)?.checks?.single()?.status)
        assertEquals("merged", resolveDetailChecks(detail.copy(state = "merged", observedAt = null), checks, true)?.state)
        assertEquals(45_000, checksRefreshIntervalMillis(listOf(PullRequestCheck("ci", "pending"))))
        assertEquals(60_000, checksRefreshIntervalMillis(listOf(PullRequestCheck("ci", "success"))))
    }

    @Test
    fun `editing follows host capability, explicit grants, then authorship`() {
        val editable = detail().copy(capabilities = detail().capabilities.copy(edit = PullRequestEditCapabilities(changeRequest = true, comment = true)))
        assertTrue(editable.canEditChangeRequest())
        assertFalse(editable.copy(viewerPermissions = editable.viewerPermissions.copy(editChangeRequest = false)).canEditChangeRequest())
        assertFalse(detail().canEditChangeRequest())
        assertTrue(editable.canEditComment("issue-comment", "VIEWER", null))
        assertFalse(editable.canEditComment("issue-comment", "other", null))
        assertTrue(editable.canEditComment("review-comment", "other", true))
        assertFalse(editable.canEditComment("review", "viewer", true))
        assertTrue(detail().copy(capabilities = detail().capabilities.copy(labels = true)).canManageLabels())
    }

    @Test
    fun `viewer reactions apply ahead of the host in a stable order`() {
        val reactions = listOf(PullRequestReaction("heart", 1, listOf("sam"), false))
        val added = reactions.withViewerReaction("thumbs-up", true)
        assertEquals(listOf("thumbs-up", "heart"), added.map { it.content })
        assertEquals(2, reactions.withViewerReaction("heart", true).single().count)
        assertTrue(listOf(PullRequestReaction("eyes", 1, emptyList(), true)).withViewerReaction("eyes", false).isEmpty())
        val activity = PullRequestActivity(listOf(PullRequestComment("c1", "issue-comment", body = "", createdAt = "", reactions = emptyList())),
            1, false, emptyList(), emptyList())
        assertEquals(1, activity.withViewerReaction("c1", "rocket", true).comments.single().reactions?.single()?.count)
        assertEquals(1, activity.withViewerReaction(null, "rocket", true).reactions?.size)
    }

    @Test
    fun `html links in comments become markdown links while code keeps literal tags`() {
        assertEquals("See [the docs](https://example.com/a%20b) now", pullRequestMarkdown("See <a href=\"https://example.com/a b\">the docs</a> now"))
        assertEquals("[logo](https://x.test/i.png)", pullRequestMarkdown("<img src=\"https://x.test/i.png\" alt=\"logo\">"))
        assertEquals("[pic](https://x.test)", pullRequestMarkdown("<a href='https://x.test'><img src=\"y.png\" alt=\"pic\"></a>"))
        assertEquals("unsafe", pullRequestMarkdown("<a href=\"javascript:alert(1)\">unsafe</a>"))
        assertEquals("Use `<a href=\"x\">` here", pullRequestMarkdown("Use `<a href=\"x\">` here"))
        val fenced = "```html\n<a href=\"https://x.test\">x</a>\n```\n<a href=\"https://y.test\">y</a>"
        assertEquals("```html\n<a href=\"https://x.test\">x</a>\n```\n[y](https://y.test)", pullRequestMarkdown(fenced))
        assertEquals("\n\n\n\n**More**\n\nbody\n\n", pullRequestMarkdown("<details><summary>More</summary>body</details>"))
        assertEquals("Vec<String> stays", pullRequestMarkdown("Vec<String> stays"))
    }
}

private fun entry() = PullRequestEntry("github.com", "project", "Project", "owner/repo", 42, "Title", "https://github.com/owner/repo/pull/42",
    null, "feature", "main", "open", false, "mergeable", 2, 1, "2026-10-19T10:00:00Z")

private fun detail() = PullRequestDetail(
    PullRequestCapabilities(true, true, listOf("merge"), listOf("squash"), PullRequestReviewCapabilities(true, true, true, listOf("approve"))),
    PullRequestViewerPermissions(listOf("close"), true, true, listOf("approve")),
    "project", "Project", "owner/repo", 42, "Title", "Body", "https://github.com/owner/repo/pull/42", PullRequestActor("viewer"),
    "open", false, "mergeable", 2, 1, 1, "feature", "main", emptyList(), emptyList(), emptyList(), PullRequestMergeCapabilities(false, true, false),
    viewer = "viewer"
)
