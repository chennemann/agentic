package de.chennemann.agentic.domain.v2.session

data class LocalSessionRecord(
    val id: String,
    val projectId: String,
    val title: String,
    val path: String,
    val pinned: Boolean,
)
