package de.chennemann.agentic.data.v2

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.di.DispatcherProvider
import de.chennemann.agentic.domain.v2.message.LocalMessage
import de.chennemann.agentic.domain.v2.message.LocalMessageToolCall
import de.chennemann.agentic.domain.v2.message.LocalMessageWithToolCalls
import de.chennemann.agentic.domain.v2.message.MessageRepository
import de.chennemann.agentic.domain.v2.message.messageRoleOf
import de.chennemann.agentic.domain.v2.message.toolCallStatusOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

class SqlDelightMessageRepository(
    private val db: AgenticDb,
    private val dispatchers: DispatcherProvider,
) : MessageRepository {
    override fun observeMessages(serverUrl: String, sessionId: String): Flow<List<LocalMessage>> {
        val normalizedServerUrl = normalizeServerUrl(serverUrl)
        val normalizedSessionId = normalizeSessionId(sessionId)
        return db.messageQueries
            .listMessage(
                server_url = normalizedServerUrl,
                session_id = normalizedSessionId,
                mapper = ::mapMessage,
            )
            .asFlow()
            .mapToList(dispatchers.io)
    }

    override fun observeToolCalls(serverUrl: String, sessionId: String): Flow<List<LocalMessageToolCall>> {
        val normalizedServerUrl = normalizeServerUrl(serverUrl)
        val normalizedSessionId = normalizeSessionId(sessionId)
        return db.messageToolCallQueries
            .listMessageToolCallBySession(
                server_url = normalizedServerUrl,
                session_id = normalizedSessionId,
                mapper = ::mapToolCall,
            )
            .asFlow()
            .mapToList(dispatchers.io)
    }

    override fun observeMessagesWithToolCalls(serverUrl: String, sessionId: String): Flow<List<LocalMessageWithToolCalls>> {
        return combine(
            observeMessages(serverUrl, sessionId),
            observeToolCalls(serverUrl, sessionId),
        ) { messages, toolCalls ->
            val callsByMessage = toolCalls.groupBy { it.messageId }
            messages.map {
                LocalMessageWithToolCalls(
                    message = it,
                    toolCalls = callsByMessage[it.id].orEmpty(),
                )
            }
        }
    }

    override suspend fun selectMessage(id: String): LocalMessage? {
        val messageId = normalizeMessageId(id)
        return withContext(dispatchers.io) {
            db.messageQueries
                .selectMessageById(messageId, mapper = ::mapMessage)
                .executeAsOneOrNull()
        }
    }

    override suspend fun upsertMessage(message: LocalMessage) {
        val value = normalizeMessage(message)
        withContext(dispatchers.io) {
            upsertMessageInternal(value)
        }
    }

    override suspend fun upsertToolCall(toolCall: LocalMessageToolCall) {
        val value = normalizeToolCall(toolCall)
        withContext(dispatchers.io) {
            upsertToolCallInternal(value)
        }
    }

    override suspend fun replaceToolCalls(messageId: String, toolCalls: List<LocalMessageToolCall>) {
        val normalizedMessageId = normalizeMessageId(messageId)
        val normalizedCalls = normalizeToolCalls(toolCalls, normalizedMessageId)
        withContext(dispatchers.io) {
            db.transaction {
                db.messageToolCallQueries.deleteMessageToolCallByMessage(normalizedMessageId)
                normalizedCalls.forEach {
                    upsertToolCallInternal(it)
                }
            }
        }
    }

    override suspend fun upsertMessageWithToolCalls(message: LocalMessage, toolCalls: List<LocalMessageToolCall>) {
        val normalizedMessage = normalizeMessage(message)
        val normalizedCalls = normalizeToolCalls(toolCalls, normalizedMessage.id)
        withContext(dispatchers.io) {
            db.transaction {
                upsertMessageInternal(normalizedMessage)
                db.messageToolCallQueries.deleteMessageToolCallByMessage(normalizedMessage.id)
                normalizedCalls.forEach {
                    upsertToolCallInternal(it)
                }
            }
        }
    }

    override suspend fun deleteMessage(id: String) {
        val messageId = normalizeMessageId(id)
        withContext(dispatchers.io) {
            db.transaction {
                db.messageToolCallQueries.deleteMessageToolCallByMessage(messageId)
                db.messageQueries.deleteMessageById(messageId)
            }
        }
    }

    override suspend fun deleteMessageByRemote(serverUrl: String, sessionId: String, remoteId: String) {
        val normalizedServerUrl = normalizeServerUrl(serverUrl)
        val normalizedSessionId = normalizeSessionId(sessionId)
        val normalizedRemoteId = normalizeRemoteId(remoteId)
        withContext(dispatchers.io) {
            db.transaction {
                db.messageToolCallQueries.deleteMessageToolCallByRemote(
                    server_url = normalizedServerUrl,
                    session_id = normalizedSessionId,
                    remote_id = normalizedRemoteId,
                )
                db.messageQueries.deleteMessageByRemote(
                    server_url = normalizedServerUrl,
                    session_id = normalizedSessionId,
                    remote_id = normalizedRemoteId,
                )
            }
        }
    }

    override suspend fun deleteMessagesBySession(serverUrl: String, sessionId: String) {
        val normalizedServerUrl = normalizeServerUrl(serverUrl)
        val normalizedSessionId = normalizeSessionId(sessionId)
        withContext(dispatchers.io) {
            db.transaction {
                db.messageToolCallQueries.deleteMessageToolCallBySession(
                    server_url = normalizedServerUrl,
                    session_id = normalizedSessionId,
                )
                db.messageQueries.deleteMessageBySession(
                    server_url = normalizedServerUrl,
                    session_id = normalizedSessionId,
                )
            }
        }
    }

    private suspend fun upsertMessageInternal(message: LocalMessage) {
        db.messageQueries.upsertMessage(
            id = message.id,
            server_url = message.serverUrl,
            session_id = message.sessionId,
            remote_id = message.remoteId,
            step_index = message.stepIndex,
            role = message.role.value,
            sort_key = message.sortKey,
            text = message.text,
            created_at = message.createdAt,
            completed_at = message.completedAt,
        )
    }

    private suspend fun upsertToolCallInternal(toolCall: LocalMessageToolCall) {
        db.messageToolCallQueries.upsertMessageToolCall(
            id = toolCall.id,
            message_id = toolCall.messageId,
            tool_name = toolCall.toolName,
            title = toolCall.title,
            subtitle = toolCall.subtitle,
            status = toolCall.status.value,
            target = toolCall.target,
            output_preview = toolCall.outputPreview,
            error_message = toolCall.errorMessage,
            session_id = toolCall.sessionId,
            started_at = toolCall.startedAt,
            completed_at = toolCall.completedAt,
        )
    }

    private fun normalizeMessage(message: LocalMessage): LocalMessage {
        val id = normalizeMessageId(message.id)
        val serverUrl = normalizeServerUrl(message.serverUrl)
        val sessionId = normalizeSessionId(message.sessionId)
        val remoteId = normalizeRemoteId(message.remoteId)
        val sortKey = normalizeSortKey(message.sortKey)
        val text = message.text.trim()
        require(text.isNotBlank()) { "message.text must not be blank" }
        require(message.stepIndex >= 0L) { "message.stepIndex must be >= 0" }
        return message.copy(
            id = id,
            serverUrl = serverUrl,
            sessionId = sessionId,
            remoteId = remoteId,
            sortKey = sortKey,
            text = text,
        )
    }

    private fun normalizeToolCalls(
        toolCalls: List<LocalMessageToolCall>,
        messageId: String,
    ): List<LocalMessageToolCall> {
        return toolCalls
            .asSequence()
            .map { normalizeToolCall(it, messageId) }
            .distinctBy { it.id }
            .toList()
    }

    private fun normalizeToolCall(
        toolCall: LocalMessageToolCall,
        forcedMessageId: String? = null,
    ): LocalMessageToolCall {
        val id = normalizeToolCallId(toolCall.id)
        val messageId = forcedMessageId ?: normalizeMessageId(toolCall.messageId)
        val toolName = toolCall.toolName.trim()
        require(toolName.isNotBlank()) { "toolCall.toolName must not be blank" }
        val title = toolCall.title.trim()
        require(title.isNotBlank()) { "toolCall.title must not be blank" }
        return toolCall.copy(
            id = id,
            messageId = messageId,
            toolName = toolName,
            title = title,
            subtitle = toolCall.subtitle?.trim()?.ifBlank { null },
            target = toolCall.target?.trim()?.ifBlank { null },
            outputPreview = toolCall.outputPreview?.trim()?.ifBlank { null },
            errorMessage = toolCall.errorMessage?.trim()?.ifBlank { null },
            sessionId = toolCall.sessionId?.trim()?.ifBlank { null },
        )
    }
}

private fun mapMessage(
    id: String,
    server_url: String,
    session_id: String,
    remote_id: String,
    step_index: Long,
    role: String,
    sort_key: String,
    text: String,
    created_at: Long?,
    completed_at: Long,
): LocalMessage {
    return LocalMessage(
        id = id,
        serverUrl = server_url,
        sessionId = session_id,
        remoteId = remote_id,
        stepIndex = step_index,
        role = messageRoleOf(role),
        sortKey = sort_key,
        text = text,
        createdAt = created_at,
        completedAt = completed_at,
    )
}

private fun mapToolCall(
    id: String,
    message_id: String,
    tool_name: String,
    title: String,
    subtitle: String?,
    status: String,
    target: String?,
    output_preview: String?,
    error_message: String?,
    session_id: String?,
    started_at: Long?,
    completed_at: Long?,
): LocalMessageToolCall {
    return LocalMessageToolCall(
        id = id,
        messageId = message_id,
        toolName = tool_name,
        title = title,
        subtitle = subtitle,
        status = toolCallStatusOf(status),
        target = target,
        outputPreview = output_preview,
        errorMessage = error_message,
        sessionId = session_id,
        startedAt = started_at,
        completedAt = completed_at,
    )
}

private fun normalizeServerUrl(value: String): String {
    val normalized = value.trim()
    require(normalized.isNotBlank()) { "serverUrl must not be blank" }
    return normalized
}

private fun normalizeSessionId(value: String): String {
    val normalized = value.trim()
    require(normalized.isNotBlank()) { "sessionId must not be blank" }
    return normalized
}

private fun normalizeMessageId(value: String): String {
    val normalized = value.trim()
    require(normalized.isNotBlank()) { "message id must not be blank" }
    return normalized
}

private fun normalizeRemoteId(value: String): String {
    val normalized = value.trim()
    require(normalized.isNotBlank()) { "remoteId must not be blank" }
    return normalized
}

private fun normalizeSortKey(value: String): String {
    val normalized = value.trim()
    require(normalized.isNotBlank()) { "sortKey must not be blank" }
    return normalized
}

private fun normalizeToolCallId(value: String): String {
    val normalized = value.trim()
    require(normalized.isNotBlank()) { "toolCall id must not be blank" }
    return normalized
}
