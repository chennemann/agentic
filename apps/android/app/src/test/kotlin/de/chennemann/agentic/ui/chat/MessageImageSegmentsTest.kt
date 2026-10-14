package de.chennemann.agentic.ui.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MessageImageSegmentsTest {
    @Test
    fun `messages without images stay a single text segment`() {
        assertEquals(listOf(MessageSegment.Text("Hello **world**")), splitMessageImages("Hello **world**"))
    }

    @Test
    fun `standalone image lines become inline images between text`() {
        val segments = splitMessageImages("Before\n\n![Screen](D:\\shots\\home.png)\n\nAfter")
        assertEquals(
            listOf(
                MessageSegment.Text("Before"),
                MessageSegment.Image("Screen", "D:\\shots\\home.png"),
                MessageSegment.Text("After"),
            ),
            segments,
        )
    }

    @Test
    fun `images inside code fences and remote images stay text`() {
        val content = "```\n![a](x.png)\n```\n![remote](https://example.com/a.png)"
        assertEquals(listOf(MessageSegment.Text(content)), splitMessageImages(content))
    }

    @Test
    fun `relative paths with titles are recognized`() {
        assertEquals(
            listOf(MessageSegment.Image("", "docs/shot.jpg")),
            splitMessageImages("![](docs/shot.jpg \"Title\")"),
        )
    }
}
