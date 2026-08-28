package de.chennemann.agentic.streamingmarkdown

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class MarkdownBlocksTest {
    @Test
    fun parses_fenced_code_with_language() {
        val blocks = parseMarkdownBlocks("Before\n\n```kotlin\nval answer = 42\n```\n\nAfter")

        assertEquals(3, blocks.size)
        assertEquals(
            MarkdownBlock.Code(language = "kotlin", content = "val answer = 42"),
            blocks[1],
        )
    }

    @Test
    fun parses_ordered_and_unordered_lists() {
        val blocks = parseMarkdownBlocks("- first\n  - nested\n\n1. one\n2. two")

        val unordered = assertInstanceOf(MarkdownBlock.ListBlock::class.java, blocks[0])
        assertEquals(false, unordered.ordered)
        assertEquals(listOf(0, 1), unordered.items.map(MarkdownListItem::level))
        assertEquals(listOf("first", "nested"), unordered.items.map { plainText(it.runs) })

        val ordered = assertInstanceOf(MarkdownBlock.ListBlock::class.java, blocks[1])
        assertEquals(true, ordered.ordered)
        assertEquals(listOf("1.", "2."), ordered.items.map(MarkdownListItem::marker))
    }

    @Test
    fun parses_gfm_table_and_normalizes_short_rows() {
        val blocks = parseMarkdownBlocks(
            "| Name | Value |\n| :--- | ---: |\n| alpha | `1` |\n| beta |",
        )

        val table = assertInstanceOf(MarkdownBlock.Table::class.java, blocks.single())
        assertEquals(listOf("Name", "Value"), table.header.map(::plainText))
        assertEquals(listOf("alpha", "1"), table.rows[0].map(::plainText))
        assertEquals(listOf("beta", ""), table.rows[1].map(::plainText))
        assertEquals(MarkdownKind.INLINE_CODE, table.rows[0][1].single().kind)
    }

    @Test
    fun keeps_escaped_table_separators_inside_the_cell() {
        val table = parseMarkdownBlocks("A | B\n--- | ---\none \\| two | three").single() as MarkdownBlock.Table

        assertEquals("one | two", plainText(table.rows.single().first()))
    }

    private fun plainText(runs: List<MarkdownRun>): String = runs.joinToString("") { it.value }
}
