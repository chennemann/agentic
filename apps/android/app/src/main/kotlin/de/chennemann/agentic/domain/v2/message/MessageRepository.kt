package de.chennemann.agentic.domain.v2.message

import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun observeMessages(serverUrl: String, sessionId: String): Flow<List<LocalMessage>>

    fun observeToolCalls(serverUrl: String, sessionId: String): Flow<List<LocalMessageToolCall>>

    fun observeMessagesWithToolCalls(serverUrl: String, sessionId: String): Flow<List<LocalMessageWithToolCalls>>

    suspend fun selectMessage(id: String): LocalMessage?

    suspend fun upsertMessage(message: LocalMessage)

    suspend fun upsertToolCall(toolCall: LocalMessageToolCall)

    suspend fun replaceToolCalls(messageId: String, toolCalls: List<LocalMessageToolCall>)

    suspend fun upsertMessageWithToolCalls(message: LocalMessage, toolCalls: List<LocalMessageToolCall>)

    suspend fun deleteMessage(id: String)

    suspend fun deleteMessageByRemote(serverUrl: String, sessionId: String, remoteId: String)

    suspend fun deleteMessagesBySession(serverUrl: String, sessionId: String)
}
