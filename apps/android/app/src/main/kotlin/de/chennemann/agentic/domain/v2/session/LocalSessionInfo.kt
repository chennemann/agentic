package de.chennemann.agentic.domain.v2.session

data class LocalSessionInfo(
    val id: String,
    val projectId: String,
    val workspace: String,
    val title: String,
    val pinned: Boolean,
    val parentId: String? = null,
    val updatedAt: Long? = null,
    val lastReadAt: Long? = null,
    val archivedAt: Long? = null,
)
