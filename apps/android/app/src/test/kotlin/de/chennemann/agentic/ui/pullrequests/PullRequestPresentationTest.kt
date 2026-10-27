package de.chennemann.agentic.ui.pullrequests

import de.chennemann.agentic.t3.contract.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PullRequestPresentationTest {
    @Test
    fun `merge request hosts name their actions with their own term`() {
        val ui = detail().toUi(canWrite = true, breakdown = PullRequestChangeBreakdown(
            PullRequestLineCount(10, 2), PullRequestLineCount(4, 0), PullRequestLineCount(), PullRequestLineCount(), PullRequestLineCount(3, 1)))
        assertEquals("merge request", ui.noun)
        assertEquals("Close merge request", ui.actions.single().label)
        assertEquals("Code +10 \u22122 \u00B7 Tests +4 \u22120 \u00B7 Docs +3 \u22121", ui.breakdown)
        assertEquals("1 passed \u00B7 1 pending", ui.checkSummary)
        assertEquals(0xFFD73A4AL, ui.labelChips.single().color)
    }

    @Test
    fun `reaction descriptions count the viewer only where the host left room`() {
        assertEquals("You and sam reacted with heart", reactionDescription(PullRequestReaction("heart", 2, listOf("sam"), true)))
        assertEquals("a, b, c, and 2 others reacted with thumbs up", reactionDescription(PullRequestReaction("thumbs-up", 5, listOf("a", "b", "c", "d"), false)))
        assertEquals("3 people reacted with eyes", reactionDescription(PullRequestReaction("eyes", 3, emptyList(), false)))
        assertNull(labelColor("nope"))
    }
}

private fun detail() = PullRequestDetail(
    PullRequestCapabilities(true, true, listOf("close"), emptyList(), PullRequestReviewCapabilities(true, true, true, emptyList())),
    PullRequestViewerPermissions(listOf("close"), true, true, emptyList()),
    "project", "Project", "group/app", 7, "Title", "Body", "https://gitlab.com/group/app/-/merge_requests/7", null,
    "open", false, "mergeable", 17, 3, 4, "feature", "main",
    listOf(PullRequestCheck("ci", "success"), PullRequestCheck("lint", "pending")), listOf(PullRequestLabel("bug", "#d73a4a")), emptyList(),
    PullRequestMergeCapabilities(false, false, false), provider = "gitlab"
)
