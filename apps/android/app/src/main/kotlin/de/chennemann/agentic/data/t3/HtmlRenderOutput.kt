package de.chennemann.agentic.data.t3

import de.chennemann.agentic.domain.attachments.HtmlRenderPage
import de.chennemann.agentic.domain.attachments.htmlFramePixels
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Mirrors T3's shared/toolOutput reader: only successful T3 HTML tool results publish pages. */
internal fun readHtmlRenderOutput(payload: JsonObject): HtmlRenderPage? {
    val tool = (payload["toolName"] as? JsonPrimitive)?.content ?: return null
    if (!isHtmlRenderTool(tool)) return null
    var failed = false
    var exceeded = false
    var nodes = 128
    var bytes = 16_384
    fun read(value: JsonElement?, depth: Int = 0): JsonObject? {
        if (depth > 4 || --nodes < 0) { exceeded = true; return null }
        return when (value) {
            is JsonPrimitive -> {
                if (!value.isString) return null
                bytes -= value.content.toByteArray().size
                if (bytes < 0) { exceeded = true; return null }
                read(runCatching { Json.parseToJsonElement(value.content) }.getOrNull(), depth + 1)
            }
            is JsonArray -> {
                if (value.size > 32) { exceeded = true; return null }
                var result: JsonObject? = null
                value.forEach { block ->
                    val text = (block as? JsonObject)?.get("text")
                    val parsed = read((text as? JsonObject)?.get("text") ?: text, depth + 1)
                    if (result == null) result = parsed
                }
                result
            }
            is JsonObject -> {
                if ((value["isError"] as? JsonPrimitive)?.booleanOrNull == true ||
                    (value["is_error"] as? JsonPrimitive)?.booleanOrNull == true ||
                    (value["_tag"] as? JsonPrimitive)?.content == "OrchestratorMcpFailure" ||
                    value["error"]?.let { it != JsonNull } == true) failed = true
                val nested = value["structuredContent"]?.takeUnless { it == JsonNull } ?: value["content"]
                if (nested != null) read(nested, depth + 1) else value
            }
            else -> null
        }
    }
    val output = read(payload["output"]) ?: return null
    if (failed || exceeded) return null
    val page = output["htmlRender"] as? JsonObject ?: return null
    val id = (page["attachmentId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.isNotEmpty() && it.length <= 256 } ?: return null
    val title = (page["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val height = (page["height"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: return null
    val measurements = (page["heights"] as? JsonArray)?.takeIf { it.size in 1..24 }?.let { entries ->
        val pairs = entries.mapNotNull { entry ->
            val pair = (entry as? JsonArray)?.takeIf { it.size == 2 } ?: return@mapNotNull null
            val width = (pair[0] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 1..10_000 } ?: return@mapNotNull null
            val measured = (pair[1] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: return@mapNotNull null
            width to measured.htmlFramePixels()
        }
        pairs.takeIf { it.size == entries.size }?.sortedBy { it.first }
    }.orEmpty()
    return HtmlRenderPage(id, title.trim().take(200).ifEmpty { "HTML" }, height.htmlFramePixels(), measurements)
}

private fun isHtmlRenderTool(value: String): Boolean {
    val label = value.replace(Regex("\\s+(complete|completed)\\s*$", RegexOption.IGNORE_CASE), "").trim()
    val mcp = Regex("^mcp__(.+?)__(.+)$", RegexOption.IGNORE_CASE).matchEntire(label)
    if (mcp != null) return mcp.groupValues[1].lowercase() in setOf("t3-code", "t3_code", "t3code") && mcp.groupValues[2] == "html_render"
    return label == "html_render" ||
        Regex("^(t3-code|t3_code|t3code)([.:/]|\\s*·\\s*)html_render$", RegexOption.IGNORE_CASE).matches(label) ||
        Regex("^(mcp[-_]{1,2})?t3[-_ ]?code(__|[-_.:/ ])html_render$", RegexOption.IGNORE_CASE).matches(label) ||
        (label.startsWith("t3-code-", ignoreCase = true) && label.endsWith("_html_render"))
}
