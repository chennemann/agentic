package de.chennemann.agentic.domain.v2.servers

sealed interface ServerInfo {

    data object NONE : ServerInfo

    data class ConnectedServerInfo(
        val id: String,
        val url: String,
        val lastConnectedAt: Long? = null,
    ) : ServerInfo

}
