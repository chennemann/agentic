package de.chennemann.agentic.domain.sessions

data class LocalSessionRecord(
    val id: String,
    val projectId: String,
    val title: String,
    val path: String,
    val pinned: Boolean,
    val parentId: String? = null,
    val updatedAt: Long? = null,
    val archivedAt: Long? = null,
)
