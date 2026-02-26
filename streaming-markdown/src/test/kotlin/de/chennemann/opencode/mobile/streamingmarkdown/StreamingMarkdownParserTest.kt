package de.chennemann.opencode.mobile.streamingmarkdown

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StreamingMarkdownParserTest {
    @Test
    fun opens_inline_code_mode_on_first_backtick() {
        val parser = StreamingMarkdownParser()

        parser.start()
        parser.write("foo `bar")

        assertEquals(
            listOf(
                MarkdownRun(MarkdownKind.TEXT, "foo "),
                MarkdownRun(MarkdownKind.INLINE_CODE, "bar"),
            ),
            parser.snapshot(),
        )
    }

    @Test
    fun closes_inline_code_mode_on_matching_backtick() {
        val parser = StreamingMarkdownParser()

        parser.start()
        parser.write("foo `bar` baz")

        assertEquals(
            listOf(
                MarkdownRun(MarkdownKind.TEXT, "foo "),
                MarkdownRun(MarkdownKind.INLINE_CODE, "bar"),
                MarkdownRun(MarkdownKind.TEXT, " baz"),
            ),
            parser.snapshot(),
        )
    }

    @Test
    fun preserves_inline_code_mode_across_chunk_boundaries() {
        val parser = StreamingMarkdownParser()

        parser.start()
        parser.write("foo `ba")
        parser.write("r` baz")

        assertEquals(
            listOf(
                MarkdownRun(MarkdownKind.TEXT, "foo "),
                MarkdownRun(MarkdownKind.INLINE_CODE, "bar"),
                MarkdownRun(MarkdownKind.TEXT, " baz"),
            ),
            parser.snapshot(),
        )
    }

    @Test
    fun resets_inline_code_mode_at_newline() {
        val parser = StreamingMarkdownParser()

        parser.start()
        parser.write("foo `bar\nbaz")

        assertEquals(
            listOf(
                MarkdownRun(MarkdownKind.TEXT, "foo "),
                MarkdownRun(MarkdownKind.INLINE_CODE, "bar"),
                MarkdownRun(MarkdownKind.TEXT, "\nbaz"),
            ),
            parser.snapshot(),
        )
    }

    @Test
    fun supports_multiple_inline_code_spans_in_one_line() {
        val parser = StreamingMarkdownParser()

        parser.start()
        parser.write("a `b` c `d` e")

        assertEquals(
            listOf(
                MarkdownRun(MarkdownKind.TEXT, "a "),
                MarkdownRun(MarkdownKind.INLINE_CODE, "b"),
                MarkdownRun(MarkdownKind.TEXT, " c "),
                MarkdownRun(MarkdownKind.INLINE_CODE, "d"),
                MarkdownRun(MarkdownKind.TEXT, " e"),
            ),
            parser.snapshot(),
        )
    }

    @Test
    fun handles_unmatched_trailing_backtick_input_safely() {
        val parser = StreamingMarkdownParser()

        parser.start()
        parser.write("start `code")

        assertEquals(
            listOf(
                MarkdownRun(MarkdownKind.TEXT, "start "),
                MarkdownRun(MarkdownKind.INLINE_CODE, "code"),
            ),
            parser.end(),
        )
    }

    @Test
    fun supports_escaped_backticks_without_toggling_inline_mode() {
        val parser = StreamingMarkdownParser()

        parser.start()
        parser.write("foo \\`bar\\` baz")

        assertEquals(
            listOf(
                MarkdownRun(MarkdownKind.TEXT, "foo `bar` baz"),
            ),
            parser.end(),
        )
    }

    @Test
    fun supports_multi_backtick_delimiters() {
        val parser = StreamingMarkdownParser()

        parser.start()
        parser.write("foo ``bar`` baz")

        assertEquals(
            listOf(
                MarkdownRun(MarkdownKind.TEXT, "foo "),
                MarkdownRun(MarkdownKind.INLINE_CODE, "bar"),
                MarkdownRun(MarkdownKind.TEXT, " baz"),
            ),
            parser.end(),
        )
    }

    @Test
    fun keeps_non_matching_backticks_inside_code() {
        val parser = StreamingMarkdownParser()

        parser.start()
        parser.write("foo ``bar`baz`` end")

        assertEquals(
            listOf(
                MarkdownRun(MarkdownKind.TEXT, "foo "),
                MarkdownRun(MarkdownKind.INLINE_CODE, "bar`baz"),
                MarkdownRun(MarkdownKind.TEXT, " end"),
            ),
            parser.end(),
        )
    }
}
