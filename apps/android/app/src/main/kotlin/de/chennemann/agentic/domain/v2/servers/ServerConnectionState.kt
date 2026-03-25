package de.chennemann.agentic.domain.servers

sealed interface ServerConnectionState {
    data object Idle : ServerConnectionState

    data object Connecting : ServerConnectionState

    data object Connected : ServerConnectionState

    data object Refreshing : ServerConnectionState

    data object Disconnected : ServerConnectionState
}
