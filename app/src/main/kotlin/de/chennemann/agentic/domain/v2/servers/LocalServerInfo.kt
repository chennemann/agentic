package de.chennemann.agentic.domain.v2.servers

data class LocalServerInfo(
    val id: String,
    val url: String,
    val lastConnectedAt: Long? = null,
)
