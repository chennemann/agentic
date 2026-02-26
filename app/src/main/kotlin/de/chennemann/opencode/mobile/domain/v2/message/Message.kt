package de.chennemann.opencode.mobile.domain.v2.message

enum class LocalMessageRole(
    val value: String,
) {
    USER("user"),
    ASSISTANT("assistant"),
}

enum class LocalToolCallStatus(
    val value: String,
) {
    PENDING("pending"),
    RUNNING("running"),
    COMPLETED("completed"),
    ERROR("error"),
    UNKNOWN("unknown"),
}

data class LocalMessage(
    val id: String,
    val serverUrl: String,
    val sessionId: String,
    val remoteId: String,
    val stepIndex: Long,
    val role: LocalMessageRole,
    val sortKey: String,
    val text: String,
    val createdAt: Long? = null,
    val completedAt: Long,
)

data class LocalMessageToolCall(
    val id: String,
    val messageId: String,
    val toolName: String,
    val title: String,
    val subtitle: String? = null,
    val status: LocalToolCallStatus = LocalToolCallStatus.UNKNOWN,
    val target: String? = null,
    val outputPreview: String? = null,
    val errorMessage: String? = null,
    val sessionId: String? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
)

data class LocalMessageWithToolCalls(
    val message: LocalMessage,
    val toolCalls: List<LocalMessageToolCall>,
)

internal fun messageRoleOf(value: String): LocalMessageRole {
    return when (value.trim().lowercase()) {
        LocalMessageRole.USER.value -> LocalMessageRole.USER
        else -> LocalMessageRole.ASSISTANT
    }
}

internal fun toolCallStatusOf(value: String?): LocalToolCallStatus {
    return when (value?.trim()?.lowercase()) {
        LocalToolCallStatus.PENDING.value -> LocalToolCallStatus.PENDING
        LocalToolCallStatus.RUNNING.value -> LocalToolCallStatus.RUNNING
        LocalToolCallStatus.COMPLETED.value -> LocalToolCallStatus.COMPLETED
        LocalToolCallStatus.ERROR.value -> LocalToolCallStatus.ERROR
        LocalToolCallStatus.UNKNOWN.value,
        null,
        "",
        -> LocalToolCallStatus.UNKNOWN

        else -> LocalToolCallStatus.UNKNOWN
    }
}
