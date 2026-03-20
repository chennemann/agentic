package de.chennemann.agentic.domain.session

sealed interface ConnectionState {
    data object Idle : ConnectionState

    data object Loading : ConnectionState

    data class Connected(val version: String) : ConnectionState

    data class Failed(val reason: String) : ConnectionState
}
