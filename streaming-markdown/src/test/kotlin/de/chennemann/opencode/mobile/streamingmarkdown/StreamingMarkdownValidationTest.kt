package de.chennemann.opencode.mobile.streamingmarkdown

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamingMarkdownValidationTest {
    @Test
    fun incremental_stream_matches_snapshot_parse() {
        val parser = StreamingMarkdownParser()
        val chunks = listOf("alpha `co", "de` beta\n", "next `x", "y` end")
        val full = chunks.joinToString(separator = "")

        parser.start()
        chunks.forEach(parser::write)

        assertEquals(parseMarkdown(full), parser.end())
    }

    @Test
    fun long_stream_keeps_run_count_compact() {
        val parser = StreamingMarkdownParser()

        parser.start()
        repeat(5000) {
            parser.write("a")
        }
        parser.write(" `")
        repeat(5000) {
            parser.write("b")
        }

        val runs = parser.end()
        assertEquals(2, runs.size)
        assertTrue(runs[0].value.length >= 5001)
        assertTrue(runs[1].value.length >= 5000)
    }
}
