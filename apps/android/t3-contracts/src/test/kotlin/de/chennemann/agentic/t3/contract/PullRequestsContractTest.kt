package de.chennemann.agentic.t3.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PullRequestsContractTest {
    @Test
    fun `detail keeps unreported viewer grants distinct from denials and reads host provider`() {
        val detail = T3Json.decodeFromString<PullRequestDetail>(
            """{"provider":"gitlab","capabilities":{"diff":true,"comment":true,"actions":["merge"],"mergeMethods":["squash"],"search":true,
            "reactions":true,"labels":true,"edit":{"changeRequest":true,"comment":true},"checksAlwaysReport":true,
            "review":{"inlineComment":true,"rangeComment":true,"reply":true,"resolve":true,"verdicts":["approve"]},
            "reviewers":{"request":true,"listCandidates":true}},
            "viewerPermissions":{"actions":["merge"],"comment":true,"resolve":true,"verdicts":["approve"],"requestReviewers":true},
            "projectId":"project","projectTitle":"Project","workspaceRoot":"/work","repository":"group/app","number":7,"title":"Add",
            "body":"","url":"https://gitlab.com/group/app/-/merge_requests/7","author":{"login":"alex","name":null,"avatarUrl":null},
            "state":"open","isDraft":false,"mergeability":"mergeable","additions":1,"deletions":0,"changedFiles":1,"headBranch":"f",
            "baseBranch":"main","createdAt":"2026-10-01T00:00:00.000Z","updatedAt":"2026-10-01T00:00:00.000Z","observedAt":1761000000000,
            "mergedAt":null,"closedAt":null,"reviewers":[],"labels":[{"name":"bug","color":"d73a4a"}],
            "checks":[{"name":"ci","status":"pending","description":null,"url":null,"required":true}],
            "mergeCapabilities":{"merge":true,"squash":true,"rebase":false},"viewer":"alex"}"""
        )
        assertEquals("gitlab", detail.provider)
        assertNull(detail.viewerPermissions.labels)
        assertNull(detail.viewerPermissions.editChangeRequest)
        assertTrue(detail.capabilities.reactions)
        assertEquals("d73a4a", detail.labels.single().color)
        assertEquals(1761000000000.0, detail.observedAt)
    }

    @Test
    fun `activity carries reactions and edit hints on comments and thread replies`() {
        val activity = T3Json.decodeFromString<PullRequestActivity>(
            """{"comments":[{"id":"c1","kind":"issue-comment","author":null,"body":"hi","createdAt":"2026-10-01T00:00:00.000Z",
            "editedAt":"2026-10-02T00:00:00.000Z","url":null,"path":null,"reviewState":null,"canEdit":true,
            "reactions":[{"content":"heart","count":2,"actors":["sam"],"viewerHasReacted":true}]}],
            "commentCount":1,"commentsTruncated":false,"reviewThreads":[{"id":"t1","path":"a.kt","line":3,"side":"right","isResolved":false,
            "isOutdated":false,"comments":[{"id":"r1","author":null,"body":"x","createdAt":"2026-10-01T00:00:00.000Z","url":null}]}],
            "commits":[{"oid":"abc","messageHeadline":"m","committedDate":"2026-10-01T00:00:00.000Z"}],
            "reactions":[{"content":"rocket","count":1,"actors":[],"viewerHasReacted":false}]}"""
        )
        assertEquals(true, activity.comments.single().canEdit)
        assertEquals(2, activity.comments.single().reactions?.single()?.count)
        assertNull(activity.reviewThreads.single().comments.single().reactions)
        assertEquals("rocket", activity.reactions?.single()?.content)
    }

    @Test
    fun `change breakdown from servers before the docs split decodes with empty docs`() {
        val breakdown = T3Json.decodeFromString<PullRequestChangeBreakdown?>(
            """{"code":{"additions":10,"deletions":2},"tests":{"additions":4,"deletions":0},"mocks":{"additions":0,"deletions":0},
            "generated":{"additions":0,"deletions":0}}"""
        )
        assertEquals(0, breakdown?.docs?.additions)
        assertNull(T3Json.decodeFromString<PullRequestChangeBreakdown?>("null"))
    }

    @Test
    fun `label candidates, filter options, stats and checks decode`() {
        val labels = T3Json.decodeFromString<PullRequestLabelCandidateList>(
            """{"candidates":[{"name":"bug","color":null,"description":null,"isApplied":true}],"truncated":false}"""
        )
        assertTrue(labels.candidates.single().isApplied)
        val options = T3Json.decodeFromString<PullRequestFilterOptions>(
            """{"labels":[{"name":"ui","color":"00ff00"}],"authors":[{"login":"sam","name":"Sam","avatarUrl":null,"isBot":false}],"truncated":true}"""
        )
        assertEquals("sam", options.authors.single().login)
        val stats = T3Json.decodeFromString<PullRequestListStatsResult>(
            """{"stats":[{"projectId":"p","repository":"o/r","number":1,"additions":3,"deletions":4}]}"""
        )
        assertEquals(4, stats.stats.single().deletions)
        val checks = T3Json.decodeFromString<PullRequestChecks?>("""{"observedAt":5,"state":"open","checks":[]}""")
        assertFalse(checks?.checks.orEmpty().isNotEmpty())
        assertEquals(5.0, checks?.observedAt)
    }
}
