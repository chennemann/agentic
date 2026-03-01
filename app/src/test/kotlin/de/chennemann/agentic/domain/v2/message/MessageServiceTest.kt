package de.chennemann.agentic.domain.v2.message

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class MessageServiceTest {
    @Test
    fun messagesOfSessionDelegatesToRepository() {
        val repository = FakeMessageRepository()
        val service = DefaultMessageService(repository)

        val flow = service.messagesOfSession("https://example.test", "session-1")

        assertSame(repository.messagesFlow, flow)
        assertEquals("https://example.test", repository.lastObservedServerUrl)
        assertEquals("session-1", repository.lastObservedSessionId)
    }

    @Test
    fun saveCompletedMessagePersistsMessageAndAlignsToolCallMessageIds() = runTest {
        val repository = FakeMessageRepository()
        val service = DefaultMessageService(repository)
        val message = LocalMessage(
            id = "msg-1",
            serverUrl = "https://example.test",
            sessionId = "session-1",
            remoteId = "remote-1",
            stepIndex = 0,
            role = LocalMessageRole.ASSISTANT,
            sortKey = "r-0001",
            text = "Done",
            createdAt = 10,
            completedAt = 20,
        )
        val toolCalls = listOf(
            LocalMessageToolCall(
                id = "call-1",
                messageId = "other",
                toolName = "bash",
                title = "Shell",
            ),
            LocalMessageToolCall(
                id = "call-2",
                messageId = "another",
                toolName = "read",
                title = "Read",
            ),
        )

        service.saveCompletedMessage(message, toolCalls)

        assertEquals(message, repository.lastSavedMessage)
        assertEquals(listOf("msg-1", "msg-1"), repository.lastSavedToolCalls.map { it.messageId })
    }
}

private class FakeMessageRepository : MessageRepository {
    val messagesFlow = flowOf(
        listOf(
            LocalMessageWithToolCalls(
                message = LocalMessage(
                    id = "msg-1",
                    serverUrl = "https://example.test",
                    sessionId = "session-1",
                    remoteId = "remote-1",
                    stepIndex = 0,
                    role = LocalMessageRole.ASSISTANT,
                    sortKey = "r-0001",
                    text = "Hello",
                    createdAt = 1,
                    completedAt = 2,
                ),
                toolCalls = emptyList(),
            )
        )
    )

    var lastObservedServerUrl: String? = null
    var lastObservedSessionId: String? = null
    var lastSavedMessage: LocalMessage? = null
    var lastSavedToolCalls: List<LocalMessageToolCall> = emptyList()

    override fun observeMessages(serverUrl: String, sessionId: String): Flow<List<LocalMessage>> {
        return flowOf(emptyList())
    }

    override fun observeToolCalls(serverUrl: String, sessionId: String): Flow<List<LocalMessageToolCall>> {
        return flowOf(emptyList())
    }

    override fun observeMessagesWithToolCalls(serverUrl: String, sessionId: String): Flow<List<LocalMessageWithToolCalls>> {
        lastObservedServerUrl = serverUrl
        lastObservedSessionId = sessionId
        return messagesFlow
    }

    override suspend fun selectMessage(id: String): LocalMessage? {
        return null
    }

    override suspend fun upsertMessage(message: LocalMessage) {
        lastSavedMessage = message
    }

    override suspend fun upsertToolCall(toolCall: LocalMessageToolCall) {
    }

    override suspend fun replaceToolCalls(messageId: String, toolCalls: List<LocalMessageToolCall>) {
        lastSavedToolCalls = toolCalls
    }

    override suspend fun upsertMessageWithToolCalls(message: LocalMessage, toolCalls: List<LocalMessageToolCall>) {
        lastSavedMessage = message
        lastSavedToolCalls = toolCalls
    }

    override suspend fun deleteMessage(id: String) {
    }

    override suspend fun deleteMessageByRemote(serverUrl: String, sessionId: String, remoteId: String) {
    }

    override suspend fun deleteMessagesBySession(serverUrl: String, sessionId: String) {
    }
}
