package de.chennemann.agentic.domain.message

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class MessagePartParser {
    fun parseParts(parts: List<JsonObject>): List<MessagePart> {
        return parts.mapNotNull(::parsePart)
    }

    fun parsePart(part: JsonObject): MessagePart? {
        val id = part["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val type = part["type"]?.jsonPrimitive?.contentOrNull ?: return null
        if (type == "text") {
            return MessagePart(
                id = id,
                type = type,
                text = part["text"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
        if (type == "reasoning") {
            return MessagePart(
                id = id,
                type = type,
                text = part["text"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
        if (type == "tool") {
            val tool = part["tool"]?.jsonPrimitive?.contentOrNull ?: "tool"
            val state = part.objectValue("state")
            val status = state?.get("status")?.jsonPrimitive?.contentOrNull ?: "running"
            val title = state?.get("title")?.jsonPrimitive?.contentOrNull
            val output = state?.get("output")?.jsonPrimitive?.contentOrNull
            val input = state?.objectValue("input")
            val metadata = state?.objectValue("metadata") ?: part.objectValue("metadata")
            val time = state?.objectValue("time")
            val text = listOfNotNull(title?.line(), output?.line()).joinToString("\n")
            return MessagePart(
                id = id,
                type = type,
                text = text,
                tool = tool,
                status = status,
                title = title,
                output = output,
                input = input,
                metadata = metadata,
                startedAt = time?.long("start"),
                completedAt = time?.long("end"),
            )
        }
        if (type == "step-start") {
            return MessagePart(id = id, type = type, text = "")
        }
        if (type == "step-finish") {
            val reason = part["reason"]?.jsonPrimitive?.contentOrNull ?: "done"
            return MessagePart(id = id, type = type, text = reason)
        }
        if (type == "patch") {
            val files = part["files"]
                ?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList()
            val text = if (files.isEmpty()) {
                "changed files"
            } else {
                "${files.size} files: ${files.take(3).joinToString(", ")}" + if (files.size > 3) "..." else ""
            }
            return MessagePart(id = id, type = type, text = text)
        }
        return MessagePart(id = id, type = type, text = "[$type]")
    }

    private fun String.line(): String {
        val value = lineSequence().firstOrNull()?.trim().orEmpty()
        if (value.length <= 160) return value
        return value.take(157) + "..."
    }

    private fun JsonObject.long(key: String): Long? {
        return get(key)?.jsonPrimitive?.contentOrNull?.toLongOrNull()
    }

    private fun JsonObject.objectValue(key: String): JsonObject? {
        return get(key) as? JsonObject
    }
}
