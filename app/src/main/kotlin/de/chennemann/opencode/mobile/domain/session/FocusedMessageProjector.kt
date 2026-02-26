package de.chennemann.opencode.mobile.domain.session

import de.chennemann.opencode.mobile.domain.message.MessageDecorator
import de.chennemann.opencode.mobile.domain.message.MessagePart

class FocusedMessageProjector(
    private val decorator: MessageDecorator,
) {
    private data class CachedMessage(
        val role: String,
        val text: String,
        val sort: String,
        val createdAt: Long?,
        val completedAt: Long?,
        val parts: Map<String, MessagePart>?,
        val message: MessageState,
    )

    private val cache = linkedMapOf<String, CachedMessage>()

    @Synchronized
    fun project(
        key: String,
        base: List<MessageState>,
        staged: Map<String, MessageState>,
        pending: List<MessageState>?,
        parts: Map<String, Map<String, MessagePart>>,
    ): List<MessageState> {
        val prefix = "$key::"
        val merged = base.associateBy { it.id }.toMutableMap().apply {
            staged
                .filterKeys { it.startsWith(prefix) }
                .values
                .forEach { this[it.id] = it }
        }.values.toList()
            .let {
                if (pending.isNullOrEmpty()) it else it + pending
            }

        val keep = linkedSetOf<String>()
        return merged
            .sortedBy { it.sort }
            .map { message ->
                val id = messageKey(key, message.id)
                keep.add(id)
                val messageParts = parts[id]
                val cached = cache[id]
                if (
                    cached != null &&
                    cached.role == message.role &&
                    cached.text == message.text &&
                    cached.sort == message.sort &&
                    cached.createdAt == message.createdAt &&
                    cached.completedAt == message.completedAt &&
                    cached.parts === messageParts
                ) {
                    return@map cached.message
                }
                val rendered = decorator.decorate(
                    message.role,
                    message.text,
                    messageParts?.values?.toList() ?: emptyList(),
                )
                val nextToolCalls = rendered.toolCalls.map { call ->
                    ToolCallState(
                        id = call.id,
                        title = call.title,
                        subtitle = call.subtitle,
                        status = call.status,
                        sessionId = call.sessionId,
                        details = call.details,
                    )
                }
                val projected = message.copy(
                    text = rendered.text,
                    toolCalls = mergeToolCalls(cached?.message?.toolCalls, nextToolCalls),
                )
                cache[id] = CachedMessage(
                    role = message.role,
                    text = message.text,
                    sort = message.sort,
                    createdAt = message.createdAt,
                    completedAt = message.completedAt,
                    parts = messageParts,
                    message = projected,
                )
                projected
            }
            .also {
                cache.keys
                    .filter { it.startsWith(prefix) && !keep.contains(it) }
                    .forEach(cache::remove)
            }
    }

    private fun messageKey(key: String, messageId: String): String {
        return "$key::$messageId"
    }

    private fun mergeToolCalls(previous: List<ToolCallState>?, current: List<ToolCallState>): List<ToolCallState> {
        if (previous.isNullOrEmpty()) return current
        if (current.isEmpty()) return previous
        val merged = linkedMapOf<String, ToolCallState>()
        previous.forEach { merged[it.id] = it }
        current.forEach { merged[it.id] = it }
        return merged.values.toList()
    }
}
