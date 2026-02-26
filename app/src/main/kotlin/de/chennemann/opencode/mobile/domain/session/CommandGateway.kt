package de.chennemann.opencode.mobile.domain.session

interface CommandGateway {
    suspend fun commands(directory: String): List<CommandState>
}
