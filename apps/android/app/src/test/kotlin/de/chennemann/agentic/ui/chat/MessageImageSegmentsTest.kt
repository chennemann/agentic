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
    fun `audio linked or embedded on its own line plays inline at the resolved path`() {
        val segments = splitMessageImages(
            "Here is the take:\n\n[Voice take](file:///Users/me/out/take%201.mp3)\n![](./clips/intro.wav)\nDone",
        )
        assertEquals(
            listOf(
                MessageSegment.Text("Here is the take:"),
                MessageSegment.Audio("Voice take", "/Users/me/out/take 1.mp3"),
                MessageSegment.Audio("intro.wav", "./clips/intro.wav"),
                MessageSegment.Text("Done"),
            ),
            segments,
        )
    }

    @Test
    fun `audio links inside text, with line targets or on the web stay links`() {
        listOf(
            "Listen to [take](out/take.mp3) now",
            "[take](out/take.mp3:12)",
            "[take](https://example.com/take.mp3)",
            "[notes](docs/notes.md)",
            "```\n[take](out/take.mp3)\n```",
        ).forEach { content ->
            assertEquals(listOf(MessageSegment.Text(content)), splitMessageImages(content), content)
        }
    }

    @Test
    fun `relative paths with titles are recognized`() {
        assertEquals(
            listOf(MessageSegment.Image("", "docs/shot.jpg")),
            splitMessageImages("![](docs/shot.jpg \"Title\")"),
        )
    }
}
