package de.chennemann.agentic.ui.chat

import de.chennemann.agentic.domain.session.MessageState
import de.chennemann.agentic.domain.session.ToolCallState

class ConversationRenderMapper {
    private data class Turn(
        val id: String,
        val userText: String?,
        val toolCalls: List<ToolCallState>,
        val answerWriting: Boolean,
        val systemTexts: List<String>,
        val startedAt: Long?,
        val completedAt: Long?,
    )

    fun map(messages: List<MessageState>): List<ConversationTurnUiState> {
        val turns = mutableListOf<Turn>()
        var turn: Turn? = null

        messages.forEach { message ->
            if (message.role == "user") {
                turn?.let(turns::add)
                turn = Turn(
                    id = message.id,
                    userText = message.text,
                    toolCalls = emptyList(),
                    answerWriting = false,
                    systemTexts = emptyList(),
                    startedAt = message.createdAt,
                    completedAt = null,
                )
                return@forEach
            }

            val current = turn ?: Turn(
                id = message.id,
                userText = null,
                toolCalls = emptyList(),
                answerWriting = false,
                systemTexts = emptyList(),
                startedAt = message.createdAt,
                completedAt = message.completedAt,
            )
            val toolCalls = current.toolCalls + message.toolCalls
            turn = current.copy(
                toolCalls = toolCalls,
                answerWriting = current.answerWriting || (toolCalls.isNotEmpty() && message.toolCalls.isEmpty()),
                systemTexts = if (message.text.isNotBlank() && message.text != "(streaming...)") {
                    current.systemTexts + message.text
                } else {
                    current.systemTexts
                },
                startedAt = current.startedAt ?: message.createdAt,
                completedAt = message.completedAt ?: current.completedAt,
            )
        }

        turn?.let(turns::add)

        return turns
            .filter {
                it.userText != null ||
                    it.toolCalls.isNotEmpty() ||
                    it.systemTexts.isNotEmpty()
            }
            .map { value ->
            ConversationTurnUiState(
                id = value.id,
                userText = value.userText,
                toolCalls = value.toolCalls,
                answerWriting = value.answerWriting,
                systemTexts = value.systemTexts,
                startedAt = value.startedAt,
                completedAt = value.completedAt,
            )
            }
    }
}
