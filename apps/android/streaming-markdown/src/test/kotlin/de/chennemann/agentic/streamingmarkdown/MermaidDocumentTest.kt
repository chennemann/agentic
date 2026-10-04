package de.chennemann.agentic.streamingmarkdown

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MermaidDocumentTest {
    @Test
    fun `diagram source cannot terminate script or html`() {
        val source = "</script><script>alert('injected')</script> → 世界"
        val document = mermaidDocument(source, false)
        assertFalse(document.contains(source))
        assertTrue(document.contains("securityLevel:'strict'"))
        assertTrue(document.contains("connect-src 'none'"))
    }

    @Test
    fun `unfinished mermaid fence is distinguished from complete code`() {
        val unfinished = parseMarkdownBlocks("```mermaid\ngraph TD\nA-->B").single() as MarkdownBlock.Code
        assertFalse(unfinished.closed)
        val complete = parseMarkdownBlocks("```mermaid\ngraph TD\nA-->B\n```").single() as MarkdownBlock.Code
        assertTrue(complete.closed)
    }
}
