package de.chennemann.agentic.domain.session

interface MessageGateway {
    suspend fun messages(sessionId: String, directory: String, limit: Int? = 400): List<SessionMessage>

    suspend fun updatedAt(sessionId: String, directory: String): Long?

    suspend fun status(directory: String): Map<String, String>

    suspend fun sendMessage(sessionId: String, directory: String, text: String, agent: String)

    suspend fun sendCommand(sessionId: String, directory: String, name: String, arguments: String, agent: String)
}
