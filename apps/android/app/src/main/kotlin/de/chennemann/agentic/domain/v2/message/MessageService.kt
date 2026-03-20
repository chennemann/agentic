package de.chennemann.agentic.domain.v2.message

import kotlinx.coroutines.flow.Flow

interface MessageService {
    fun messagesOfSession(serverUrl: String, sessionId: String): Flow<List<LocalMessageWithToolCalls>>

    suspend fun saveCompletedMessage(
        message: LocalMessage,
        toolCalls: List<LocalMessageToolCall> = emptyList(),
    )

    suspend fun deleteMessage(messageId: String)

    suspend fun deleteMessageByRemote(serverUrl: String, sessionId: String, remoteId: String)

    suspend fun clearSession(serverUrl: String, sessionId: String)
}

class DefaultMessageService(
    private val messageRepository: MessageRepository,
) : MessageService {
    override fun messagesOfSession(serverUrl: String, sessionId: String): Flow<List<LocalMessageWithToolCalls>> {
        return messageRepository.observeMessagesWithToolCalls(serverUrl, sessionId)
    }

    override suspend fun saveCompletedMessage(message: LocalMessage, toolCalls: List<LocalMessageToolCall>) {
        val calls = toolCalls.map { it.copy(messageId = message.id) }
        messageRepository.upsertMessageWithToolCalls(message, calls)
    }

    override suspend fun deleteMessage(messageId: String) {
        messageRepository.deleteMessage(messageId)
    }

    override suspend fun deleteMessageByRemote(serverUrl: String, sessionId: String, remoteId: String) {
        messageRepository.deleteMessageByRemote(serverUrl, sessionId, remoteId)
    }

    override suspend fun clearSession(serverUrl: String, sessionId: String) {
        messageRepository.deleteMessagesBySession(serverUrl, sessionId)
    }
}
