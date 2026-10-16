package de.chennemann.agentic.domain.composer

import de.chennemann.agentic.t3.contract.T3Json
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposerDocumentTest {
    @Test
    fun `triggers follow the caret and ignore selections and email addresses`() {
        assertEquals(ComposerTrigger("path", "src", 7, 11), composerTrigger("Review @src then test", 11))
        assertEquals("skill", composerTrigger("Use $" + "android", 12)?.kind)
        assertEquals("command", composerTrigger("Details\n/comp", 13)?.kind)
        assertNull(composerTrigger("name@example.com", 16))
        assertNull(composerTrigger("@src", 1, 4))
        assertNull(composerTrigger("Review " + composerFileLink("src/Main.kt") + " ", 13))
    }

    @Test
    fun `inline tokens preserve paths unicode and upstream skill boundaries`() {
        val path = "src/a b/[ü](test)+#.kt"
        val link = composerFileLink(path)
        val file = composerTokens("Inspect $link ").single()
        assertEquals(path, file.value)
        assertEquals("[ü](test)+#.kt", file.label)
        assertEquals(link, file.source)
        assertTrue(composerTokens("$" + "android").isEmpty())
        assertEquals("android", composerTokens("$" + "android ").single().value)
        assertTrue(composerTokens("$20 $20k $1e6 @scope/package ").isEmpty())
        val confirmed = composerTokens("$" + "android ")
        assertEquals(confirmed, composerTokens("$" + "android", confirmed))
        assertTrue(composerTokens("[website](https://example.org) ").isEmpty())
        assertTrue(composerTokens("[other](src/File.kt) ").isEmpty())
    }

    @Test
    fun `context references retain identity and prune deleted attachment payloads on persistence`() {
        val file = pastedTextFile("日".repeat(12000), emptyList())
        assertTrue(file.text.toByteArray().size > PASTED_TEXT_ATTACHMENT_THRESHOLD_BYTES)
        val reference = composerContextReference("file", file.id, file.name)
        val document = ComposerDocument("Review $reference please", listOf(pastedTextRecord(file)), listOf(file))
        assertEquals(document, decodeComposerDocument(T3Json.encodeToString(document)))
        assertEquals(file.id, composerTokens(document.text).single().contextId)
        assertTrue(document.copy(text = "removed").retained().pastes.isEmpty())
        assertTrue(document.copy(text = "removed").retained().records.isEmpty())
        assertEquals("pasted-text-2.txt", pastedTextFile("second", listOf(file)).name)
    }

    @Test
    fun `existing plain text drafts remain readable`() {
        assertEquals(
            ComposerDocument("previous draft"),
            decodeComposerDocument(T3Json.encodeToString("previous draft"))
        )
        assertEquals(ComposerDocument(), decodeComposerDocument("invalid"))
    }
}
