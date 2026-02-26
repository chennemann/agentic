package de.chennemann.opencode.mobile.streamingmarkdown

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamingMarkdownTimingTest {
    @Test
    fun measures_incremental_append_path_against_full_reparse() {
        val chunks = buildList {
            repeat(400) {
                add("line-$it `code-$it` plain-$it\n")
            }
        }
        val full = chunks.joinToString(separator = "")

        val incremental = kotlin.system.measureNanoTime {
            val parser = StreamingMarkdownParser()
            parser.start()
            chunks.forEach(parser::write)
            parser.end()
        }
        val snapshot = kotlin.system.measureNanoTime {
            parseMarkdown(full)
        }

        val parser = StreamingMarkdownParser()
        parser.start()
        chunks.forEach(parser::write)
        assertEquals(parseMarkdown(full), parser.end())
        assertTrue(incremental > 0)
        assertTrue(snapshot > 0)
    }
}
