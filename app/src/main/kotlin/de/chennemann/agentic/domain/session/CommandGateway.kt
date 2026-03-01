package de.chennemann.agentic.domain.session

interface CommandGateway {
    suspend fun commands(directory: String): List<CommandState>
}
