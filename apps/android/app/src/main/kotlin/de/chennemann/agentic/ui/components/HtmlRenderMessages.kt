package de.chennemann.agentic.ui.components

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal data class HtmlGalleryImage(val source: String, val name: String)
internal data class HtmlGalleryRequest(val images: List<HtmlGalleryImage>, val index: Int)

internal fun readHtmlGalleryRequest(message: JsonObject): HtmlGalleryRequest? {
    if (message.text("jsonrpc") != "2.0" || message.text("method") != "t3/notifications/open-image") return null
    val params = message["params"] as? JsonObject ?: return null
    val images = (params["images"] as? JsonArray)?.takeIf { it.size in 1..200 } ?: return null
    val index = (params["index"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in images.indices } ?: return null
    val gallery = images.map { item ->
        val image = item as? JsonObject ?: return null
        val source = image.text("src")?.takeIf { it.matchesImageSource() } ?: return null
        val name = image.text("name")?.takeIf { it.length in 1..1000 } ?: return null
        HtmlGalleryImage(source, name)
    }
    return HtmlGalleryRequest(gallery, index)
}

private fun String.matchesImageSource() = Regex("^(https?://|data:image/)", RegexOption.IGNORE_CASE).containsMatchIn(this)

internal fun parseHtmlMessage(value: String): JsonObject? = runCatching {
    Json.parseToJsonElement(value) as? JsonObject
}.getOrNull()

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
