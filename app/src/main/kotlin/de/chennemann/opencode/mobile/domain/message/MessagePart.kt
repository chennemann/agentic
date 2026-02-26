package de.chennemann.opencode.mobile.domain.message

import kotlinx.serialization.json.JsonObject

data class MessagePart(
    val id: String,
    val type: String,
    val text: String,
    val tool: String? = null,
    val status: String? = null,
    val title: String? = null,
    val output: String? = null,
    val input: JsonObject? = null,
    val metadata: JsonObject? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
)
