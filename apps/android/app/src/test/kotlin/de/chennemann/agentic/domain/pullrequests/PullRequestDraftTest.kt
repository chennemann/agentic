package de.chennemann.agentic.domain.pullrequests

import de.chennemann.agentic.domain.composer.ComposerDocument
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PullRequestDraftTest {
    @Test
    fun `discussing a pull request preserves existing text and composer context`() {
        val current = ComposerDocument("My unsent question", records = listOf(buildJsonObject { put("contextId", "file-1") }))
        val next = preparePullRequestDraft(current, "https://host.example/owner/repo/pull/42")
        assertTrue(next.text.startsWith("My unsent question\n\n"))
        assertTrue(next.text.endsWith("https://host.example/owner/repo/pull/42"))
        assertEquals(current.records, next.records)
        assertEquals(next, preparePullRequestDraft(next, "https://host.example/owner/repo/pull/42"))
    }
}
