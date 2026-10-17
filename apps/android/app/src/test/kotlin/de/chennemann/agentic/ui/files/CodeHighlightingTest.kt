package de.chennemann.agentic.ui.files

import de.chennemann.agentic.streamingmarkdown.CodeToken
import de.chennemann.agentic.streamingmarkdown.CodeTokenKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CodeHighlightingTest {
    @Test
    fun `splits multi-line tokens into display lines without line breaks`() {
        val lines = highlightCodeLines("src/App.kt", "/* a\r\nb */\tval x = 1\n")

        assertEquals(
            listOf(
                listOf(CodeToken("/* a", CodeTokenKind.COMMENT)),
                listOf(
                    CodeToken("b */", CodeTokenKind.COMMENT),
                    CodeToken("    ", CodeTokenKind.PLAIN),
                    CodeToken("val", CodeTokenKind.KEYWORD),
                    CodeToken(" x = ", CodeTokenKind.PLAIN),
                    CodeToken("1", CodeTokenKind.NUMBER),
                ),
                emptyList(),
            ),
            lines,
        )
    }

    @Test
    fun `picks languages from extensions and well-known file names`() {
        assertEquals("kotlin", codeLanguageFor("app/build.gradle.kts"))
        assertEquals("typescript", codeLanguageFor("src/Chat.TSX"))
        assertEquals("shell", codeLanguageFor("apps/android/gradlew"))
        assertEquals("sql", codeLanguageFor("db/Thread.sq"))
        assertEquals(null, codeLanguageFor("LICENSE"))
    }
}
