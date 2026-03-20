package de.chennemann.agentic.domain.message

data class ToolCallRender(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val status: String? = null,
    val sessionId: String? = null,
    val details: List<String>,
)

data class MessageRender(
    val text: String,
    val toolCalls: List<ToolCallRender>,
)
