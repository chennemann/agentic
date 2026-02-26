package de.chennemann.opencode.mobile.domain.v2.projects

data class LocalProjectInfo(
    val id: String,
    val serverId: String,
    val name: String,
    val path: String,
    val pinned: Boolean,
)
