package de.chennemann.agentic.domain.message

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class MessageDecorator {
    fun render(parts: Collection<MessagePart>?): String {
        val text = parts
            ?.filter { it.type == "text" }
            ?.map { it.text }
            ?.filter { it.isNotBlank() }
            ?.joinToString("\n")
        if (text == null || text.isBlank()) return "(streaming...)"
        return text
    }

    fun decorate(role: String, text: String, parts: List<MessagePart>): MessageRender {
        if (role == "user") return MessageRender(text, emptyList())
        if (parts.isEmpty()) return MessageRender(text, emptyList())
        return MessageRender(
            text = render(parts),
            toolCalls = toolCalls(parts),
        )
    }

    private fun toolCalls(parts: List<MessagePart>): List<ToolCallRender> {
        return parts
            .filter { it.type == "tool" }
            .map { part ->
                val tool = part.tool ?: "tool"
                ToolCallRender(
                    id = part.id,
                    title = toolTitle(tool),
                    subtitle = toolSubtitle(tool, part),
                    status = part.status,
                    sessionId = toolSessionId(tool, part),
                    details = toolDetails(tool, part),
                )
            }
    }

    private fun toolSessionId(tool: String, part: MessagePart): String? {
        if (tool != "task") return null
        val metadata = part.metadata
        return metadata?.string("sessionId")
            ?: metadata?.string("session_id")
            ?: part.output?.lineSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("session_id:") }
                ?.substringAfter(':')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
    }

    private fun toolTitle(tool: String): String {
        return when (tool) {
            "read" -> "Read"
            "list" -> "List"
            "glob" -> "Glob"
            "grep" -> "Grep"
            "webfetch" -> "Webfetch"
            "task" -> "Agent"
            "bash" -> "Shell"
            "edit" -> "Edit"
            "write" -> "Write"
            "apply_patch" -> "Patch"
            "todowrite" -> "To-dos"
            "todoread" -> "Read to-dos"
            "question" -> "Questions"
            else -> tool
        }
    }

    private fun toolSubtitle(tool: String, part: MessagePart): String? {
        val input = part.input
        val metadata = part.metadata
        return when (tool) {
            "read" -> input?.string("filePath")?.filename()
            "list" -> input?.string("path")
            "glob" -> input?.string("path") ?: input?.string("pattern")
            "grep" -> input?.string("path") ?: input?.string("pattern")
            "webfetch" -> input?.string("url")
            "task" -> input?.string("description")
            "bash" -> input?.string("command")
            "edit", "write" -> input?.string("filePath")?.filename()
            "apply_patch" -> {
                val files = metadata?.array("files")?.size ?: 0
                if (files > 0) "$files files" else null
            }

            else -> null
        }
    }

    private fun toolDetails(tool: String, part: MessagePart): List<String> {
        val details = mutableListOf<String>()
        val input = part.input
        val metadata = part.metadata
        part.status?.let { details.add("Status: $it") }

        if (tool == "bash") {
            val command = input?.string("command") ?: metadata?.string("command")
            command?.let { details.add("Command: ${it.line()}") }
            part.output?.let { details.add("Output: ${it.line()}") }
        }

        if (tool == "read") {
            input?.string("filePath")?.let { details.add("File: ${it.line()}") }
            input?.string("offset")?.let { details.add("Offset: ${it.line()}") }
            input?.string("limit")?.let { details.add("Limit: ${it.line()}") }
            val loaded = metadata?.strings("loaded") ?: emptyList()
            loaded.take(5).forEach { details.add("Loaded: ${it.line()}") }
            if (loaded.size > 5) {
                details.add("Loaded: +${loaded.size - 5} more")
            }
        }

        if (tool == "list") {
            input?.string("path")?.let { details.add("Path: ${it.line()}") }
        }

        if (tool == "glob") {
            input?.string("path")?.let { details.add("Path: ${it.line()}") }
            input?.string("pattern")?.let { details.add("Pattern: ${it.line()}") }
        }

        if (tool == "grep") {
            input?.string("path")?.let { details.add("Path: ${it.line()}") }
            input?.string("pattern")?.let { details.add("Pattern: ${it.line()}") }
            input?.string("include")?.let { details.add("Include: ${it.line()}") }
        }

        if (tool == "webfetch") {
            input?.string("format")?.let { details.add("Format: ${it.line()}") }
        }

        if (tool == "task") {
            toolSessionId(tool, part)?.let { details.add("Session: ${it.line()}") }
        }

        if (tool == "edit" || tool == "write") {
            input?.string("filePath")?.let { details.add("File: ${it.line()}") }
        }

        if (tool == "apply_patch") {
            val files = metadata?.array("files") ?: emptyList()
            details.addAll(
                files
                    .mapNotNull {
                        if (it !is JsonObject) return@mapNotNull null
                        val obj = it
                        obj.string("relativePath") ?: obj.string("filePath")
                    }
                    .take(5)
                    .map { "File: ${it.line()}" }
            )
            if (files.size > 5) {
                details.add("Files: +${files.size - 5} more")
            }
        }

        if (tool != "bash") {
            part.title?.let { details.add("Title: ${it.line()}") }
            part.output?.let { details.add("Output: ${it.line()}") }
        }

        return details.distinct()
    }

    private fun String.line(): String {
        val value = lineSequence().firstOrNull()?.trim().orEmpty()
        if (value.length <= 160) return value
        return value.take(157) + "..."
    }

    private fun String.filename(): String {
        return replace('\\', '/')
            .substringAfterLast('/')
            .ifBlank { this }
    }

    private fun JsonObject.string(key: String): String? {
        return get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.array(key: String): JsonArray {
        return get(key)?.jsonArray ?: JsonArray(emptyList())
    }

    private fun JsonObject.strings(key: String): List<String> {
        return array(key)
            .mapNotNull {
                if (it !is JsonPrimitive) return@mapNotNull null
                it.contentOrNull
            }
            .filter { it.isNotBlank() }
    }
}
