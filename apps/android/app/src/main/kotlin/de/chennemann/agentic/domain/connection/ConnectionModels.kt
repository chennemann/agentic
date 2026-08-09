package de.chennemann.agentic.domain.connection

import kotlinx.coroutines.flow.StateFlow

sealed interface ConnectionState {
    data object NoEnvironment : ConnectionState

    data object Cached : ConnectionState

    data object Connecting : ConnectionState

    data object Synchronizing : ConnectionState

    data object Live : ConnectionState

    data class Backoff(
        val retryInMillis: Long,
        val message: String,
    ) : ConnectionState

    data class BlockedAuthentication(
        val message: String,
    ) : ConnectionState

    data class UnsupportedProtocol(
        val message: String,
    ) : ConnectionState

    data class Error(
        val message: String,
    ) : ConnectionState
}

interface NetworkMonitor {
    val online: StateFlow<Boolean>
}

interface ConnectionController {
    val state: StateFlow<ConnectionState>

    fun wake()

    fun retryPendingCommands()
}
