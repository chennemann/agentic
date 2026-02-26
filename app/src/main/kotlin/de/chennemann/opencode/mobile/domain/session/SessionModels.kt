package de.chennemann.opencode.mobile.domain.session

import kotlinx.serialization.json.JsonObject

data class SessionProject(
    val id: String,
    val worktree: String,
    val name: String,
    val sandboxes: List<String> = emptyList(),
)

data class SessionSummary(
    val id: String,
    val title: String,
    val version: String,
    val directory: String,
    val parentId: String? = null,
    val updatedAt: Long? = null,
    val archivedAt: Long? = null,
)

data class SessionMessage(
    val id: String,
    val role: String,
    val text: String,
    val parts: List<JsonObject>,
    val createdAt: Long? = null,
    val completedAt: Long? = null,
)

data class SessionStreamEvent(
    val directory: String,
    val type: String,
    val properties: JsonObject,
    val id: String?,
    val retry: Int?,
)
