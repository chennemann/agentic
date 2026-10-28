package de.chennemann.agentic.ui.components

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class HtmlRenderMessagesTest {
    @Test fun `published screenshot notification opens a single image without a request id`() {
        // Keep the wire envelope independent of the helpers below: changing the
        // expected method or requiring an RPC request id must break this test.
        val message = requireNotNull(parseHtmlMessage("""
            {
              "jsonrpc": "2.0",
              "method": "t3/notifications/open-image",
              "params": {
                "src": "data:image/png;base64,YQ==",
                "name": "Clip selection screen",
                "images": [{"src": "data:image/png;base64,YQ==", "name": "Clip selection screen"}],
                "index": 0
              }
            }
        """.trimIndent()))
        assertFalse(message.containsKey("id"))
        assertEquals(
            HtmlGalleryRequest(listOf(HtmlGalleryImage("data:image/png;base64,YQ==", "Clip selection screen")), 0),
            readHtmlGalleryRequest(message),
        )
    }

    @Test fun `unrelated messages cannot open the screenshot viewer`() {
        val notification = request(listOf(image()))
        listOf("ui/open-link", "ui/notifications/size-changed", "t3/open-images").forEach { method ->
            assertNull(readHtmlGalleryRequest(buildJsonObject {
                notification.forEach { (key, value) -> put(key, value) }
                put("method", method)
            }))
        }
        assertNull(readHtmlGalleryRequest(buildJsonObject {
            notification.forEach { (key, value) -> put(key, value) }
            put("jsonrpc", "1.0")
        }))
    }

    private fun image(source: String = "data:image/png;base64,YQ==", name: String = "Screen.png") = buildJsonObject {
        put("src", source); put("name", name)
    }
    private fun request(images: List<JsonElement>, index: JsonElement = JsonPrimitive(0)) = buildJsonObject {
        put("jsonrpc", "2.0"); put("method", "t3/notifications/open-image")
        put("params", buildJsonObject { put("images", JsonArray(images)); put("index", index) })
    }

    @Test fun `keeps the complete ordered gallery and selected middle image`() {
        val images = listOf(image(name = "First.png"), image("https://example.com/second.png", "Second.png"), image(name = "Hidden.png"))
        val result = requireNotNull(readHtmlGalleryRequest(request(images, JsonPrimitive(1))))
        assertEquals(1, result.index)
        assertEquals(listOf("First.png", "Second.png", "Hidden.png"), result.images.map { it.name })
    }

    @Test fun `rejects arbitrary schemes malformed entries invalid indexes and oversized lists`() {
        listOf("file:///tmp/image.png", "content://images/1", "javascript:alert(1)", "/tmp/image.png").forEach {
            assertNull(readHtmlGalleryRequest(request(listOf(image(it)))))
        }
        assertNull(readHtmlGalleryRequest(request(emptyList())))
        assertNull(readHtmlGalleryRequest(request(List(201) { image() })))
        assertNull(readHtmlGalleryRequest(request(listOf(image(name = "")))))
        assertNull(readHtmlGalleryRequest(request(listOf(image(), JsonPrimitive("bad")))))
        listOf(JsonPrimitive(-1), JsonPrimitive(1), JsonPrimitive(0.5), JsonPrimitive("0")).forEach {
            assertNull(readHtmlGalleryRequest(request(listOf(image()), it)))
        }
        assertNull(parseHtmlMessage("not json"))
    }
}
