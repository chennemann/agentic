package de.chennemann.agentic.streamingmarkdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamingMarkdownTextTest {
    @Test
    fun maps_run_boundaries_to_span_ranges() {
        val runs = listOf(
            MarkdownRun(MarkdownKind.TEXT, "a "),
            MarkdownRun(MarkdownKind.INLINE_CODE, "b"),
            MarkdownRun(MarkdownKind.TEXT, " c"),
        )

        val text = toAnnotatedString(runs = runs)

        assertEquals("a b c", text.text)
        assertEquals(1, text.spanStyles.size)
        assertEquals(2, text.spanStyles[0].start)
        assertEquals(3, text.spanStyles[0].end)
    }

    @Test
    fun applies_inline_code_style_overrides() {
        val runs = listOf(
            MarkdownRun(MarkdownKind.TEXT, "a "),
            MarkdownRun(MarkdownKind.INLINE_CODE, "b"),
        )
        val style = SpanStyle(background = Color.Red)

        val text = toAnnotatedString(runs = runs, inlineCode = style)

        assertEquals(style.background, text.spanStyles.first().item.background)
    }

    @Test
    fun keeps_stream_state_stable_for_rapid_chunk_updates() {
        val state = StreamingMarkdownState(streaming = true)

        val one = state.update("a `b")
        val two = state.update("a `bc")
        val three = state.update("a `bc` d")

        assertEquals(
            listOf(
                MarkdownRun(MarkdownKind.TEXT, "a "),
                MarkdownRun(MarkdownKind.INLINE_CODE, "b"),
            ),
            one,
        )
        assertEquals(
            listOf(
                MarkdownRun(MarkdownKind.TEXT, "a "),
                MarkdownRun(MarkdownKind.INLINE_CODE, "bc"),
            ),
            two,
        )
        assertEquals(
            listOf(
                MarkdownRun(MarkdownKind.TEXT, "a "),
                MarkdownRun(MarkdownKind.INLINE_CODE, "bc"),
                MarkdownRun(MarkdownKind.TEXT, " d"),
            ),
            three,
        )
    }

    @Test
    fun reparses_when_input_is_not_append_only() {
        val state = StreamingMarkdownState(streaming = true)

        state.update("a `b` c")
        val next = state.update("x `y` z")

        assertEquals(
            listOf(
                MarkdownRun(MarkdownKind.TEXT, "x "),
                MarkdownRun(MarkdownKind.INLINE_CODE, "y"),
                MarkdownRun(MarkdownKind.TEXT, " z"),
            ),
            next,
        )
    }

    @Test
    fun supports_non_streaming_snapshot_mode() {
        val state = StreamingMarkdownState(streaming = false)

        val runs = state.update("x `y` z")

        assertTrue(runs.any { it.kind == MarkdownKind.INLINE_CODE })
        assertFalse(runs.isEmpty())
    }

    @Test
    fun renders_emphasis_and_strong_segments_from_plain_text_runs() {
        val text = toAnnotatedString(
            runs = listOf(
                MarkdownRun(MarkdownKind.TEXT, "a *b* **c** d"),
            ),
        )

        assertEquals("a b c d", text.text)
        assertEquals(2, text.spanStyles.size)
        assertEquals(2, text.spanStyles[0].start)
        assertEquals(3, text.spanStyles[0].end)
        assertEquals(4, text.spanStyles[1].start)
        assertEquals(5, text.spanStyles[1].end)
    }

    @Test
    fun keeps_escaped_asterisks_as_literal_text() {
        val text = toAnnotatedString(
            runs = listOf(
                MarkdownRun(MarkdownKind.TEXT, "a \\*b\\* c"),
            ),
        )

        assertEquals("a *b* c", text.text)
        assertEquals(0, text.spanStyles.size)
    }

    @Test
    fun renders_markdown_links_and_adds_url_annotation() {
        val text = toAnnotatedString(
            runs = listOf(
                MarkdownRun(MarkdownKind.TEXT, "visit [site](https://example.com) now"),
            ),
        )

        assertEquals("visit site now", text.text)
        val annotation = text.getStringAnnotations(tag = "URL", start = 0, end = text.length).single()
        assertEquals("https://example.com", annotation.item)
    }

    @Test
    fun renders_autolinks_and_adds_url_annotation() {
        val text = toAnnotatedString(
            runs = listOf(
                MarkdownRun(MarkdownKind.TEXT, "open https://example.com/path"),
            ),
        )

        assertEquals("open https://example.com/path", text.text)
        val annotation = text.getStringAnnotations(tag = "URL", start = 0, end = text.length).single()
        assertEquals("https://example.com/path", annotation.item)
    }

    @Test
    fun parses_fenced_code_blocks_without_rendering_fence_delimiters() {
        val runs = parseMarkdownDocument("a\n```kotlin\nval x = 1\n```\nb")

        assertEquals(
            listOf(
                MarkdownRun(MarkdownKind.TEXT, "a\n"),
                MarkdownRun(MarkdownKind.BLOCK_CODE, "val x = 1\n"),
                MarkdownRun(MarkdownKind.TEXT, "b"),
            ),
            runs,
        )
    }

    @Test
    fun uses_document_parser_for_streaming_state_when_fence_exists() {
        val state = StreamingMarkdownState(streaming = true)

        val runs = state.update("```\ncode\n```")

        assertEquals(
            listOf(
                MarkdownRun(MarkdownKind.BLOCK_CODE, "code\n"),
            ),
            runs,
        )
    }
}
