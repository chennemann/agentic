package de.chennemann.agentic.streamingmarkdown

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SimpleCodeLexersTest {
    @Test
    fun highlights_kotlin_keywords_strings_numbers_and_comments() {
        val source = "val answer = \"not // a comment\" + 42 // result"

        val tokens = CodeLexerRegistry.Default.tokenize("kotlin", source)

        assertEquals(source, tokens.joinToString(separator = "") { it.value })
        assertTrue(tokens.any { it == CodeToken("val", CodeTokenKind.KEYWORD) })
        assertTrue(tokens.any { it == CodeToken("\"not // a comment\"", CodeTokenKind.STRING) })
        assertTrue(tokens.any { it == CodeToken("42", CodeTokenKind.NUMBER) })
        assertTrue(tokens.any { it == CodeToken("// result", CodeTokenKind.COMMENT) })
    }

    @Test
    fun supports_language_aliases() {
        val tokens = CodeLexerRegistry.Default.tokenize("TS", "const count = 1")

        assertTrue(tokens.any { it == CodeToken("const", CodeTokenKind.KEYWORD) })
    }

    @Test
    fun treats_json_comment_markers_as_plain_text() {
        val source = "{\"url\": \"https://example.com\"}"

        val tokens = CodeLexerRegistry.Default.tokenize("json", source)

        assertEquals(source, tokens.joinToString(separator = "") { it.value })
        assertTrue(tokens.none { it.kind == CodeTokenKind.COMMENT })
    }
}
