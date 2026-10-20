package de.chennemann.agentic.data.t3

import de.chennemann.agentic.domain.attachments.HtmlRenderPage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HtmlRenderOutputTest {
    private val result = Json.parseToJsonElement("""{"htmlRender":{"attachmentId":"page.html","title":" Chart ","height":420,"heights":[[728,400],[360,640]]}}""")
    private fun read(output: JsonElement, tool: String = "mcp__t3-code__html_render") = readHtmlRenderOutput(buildJsonObject {
        put("toolName", tool); put("output", output)
    })

    @Test fun `reads compact structured text and MCP content envelopes`() {
        val expected = HtmlRenderPage("page.html", "Chart", 420, listOf(360 to 640, 728 to 400))
        val content = JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", result.toString()) }))
        listOf(result, JsonPrimitive(result.toString()), content,
            buildJsonObject { put("structuredContent", result); put("content", JsonArray(emptyList())) },
            buildJsonObject { put("content", content) },
        ).forEach { assertEquals(expected, read(it)) }
    }

    @Test fun `recognizes provider neutral T3 names and rejects unrelated tools`() {
        listOf("html_render", "t3-code.html_render", "t3_code/html_render", "mcp__t3code__html_render", "t3-code:html_render", "mcp_t3_code_html_render", "t3-code-thread_123_html_render").forEach {
            assertEquals("page.html", read(result, it)?.attachmentId, it)
        }
        listOf("html_preview", "other.html_render", "mcp__other__html_render", "exec").forEach { assertNull(read(result, it)) }
    }

    @Test fun `rejects errors even when they carry a page reference`() {
        listOf("isError", "is_error").forEach { flag ->
            assertNull(read(buildJsonObject { put(flag, true); put("structuredContent", result) }))
        }
        assertNull(read(buildJsonObject { put("error", "failed"); put("structuredContent", result) }))
        assertNull(read(buildJsonObject { put("_tag", "OrchestratorMcpFailure"); put("structuredContent", result) }))
        val content = JsonArray(listOf(
            buildJsonObject { put("text", result.toString()) },
            buildJsonObject { put("text", """{"isError":true}""") },
        ))
        assertNull(read(content))
    }

    @Test fun `rejects malformed oversized and deeply nested envelopes`() {
        assertNull(read(JsonPrimitive("not JSON")))
        assertNull(read(JsonPrimitive(" ".repeat(16_385) + result)))
        var nested: JsonElement = result
        repeat(6) { nested = buildJsonObject { put("content", nested) } }
        assertNull(read(nested))
        assertNull(read(Json.parseToJsonElement("""{"htmlRender":{"attachmentId":"page","title":"x","height":"420"}}""")))
        assertNull(read(Json.parseToJsonElement("""{"htmlRender":{"attachmentId":"","title":"x","height":420}}""")))
    }

    @Test fun `inline height follows content beyond published and desktop frame limits`() {
        val page = requireNotNull(read(result))
        assertEquals(640, page.frameHeight(360))
        assertEquals(700, page.frameHeight(360, 700))
        assertEquals(900, page.copy(height = 420, heights = listOf(360 to 800, 728 to 600)).frameHeight(360, 900))
        assertEquals(20, page.frameHeight(360, 20))
        assertEquals(9000, page.frameHeight(360, 9000))
        assertEquals(900, page.copy(heights = emptyList()).frameHeight(360, 900))
        assertEquals(420, page.copy(heights = emptyList()).frameHeight(360))
    }
}
