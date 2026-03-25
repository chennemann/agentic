package de.chennemann.agentic.domain.projects

data class LocalProjectInfo(
    val id: String,
    val serverId: String,
    val name: String,
    val path: String,
    val pinned: Boolean,
)
