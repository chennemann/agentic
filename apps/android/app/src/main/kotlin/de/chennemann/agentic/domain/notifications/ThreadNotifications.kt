package de.chennemann.agentic.domain.notifications

import de.chennemann.agentic.domain.shortcuts.ShortcutParseResult
import de.chennemann.agentic.domain.shortcuts.ShortcutRoute
import de.chennemann.agentic.domain.shortcuts.ShortcutRouteCodec

enum class ThreadNotificationKind {
    APPROVAL,
    INPUT,
    COMPLETION,
    FAILURE,
}

data class ThreadNotification(
    val environmentId: String,
    val projectId: String,
    val threadId: String,
    val kind: ThreadNotificationKind,
    val title: String,
    val headline: String,
    val updatedAtEpochMillis: Long,
    val route: ShortcutRoute.Thread,
)

sealed interface ThreadNotificationParseResult {
    data class Valid(val notification: ThreadNotification) : ThreadNotificationParseResult

    data class Invalid(val reason: String) : ThreadNotificationParseResult
}

object ThreadNotificationPayloadParser {
    fun parse(payload: Map<String, String>): ThreadNotificationParseResult {
        val environmentId = payload.required("environmentId") ?: return invalid("Environment identity is missing.")
        val projectId = payload.required("projectId") ?: return invalid("Project identity is missing.")
        val threadId = payload.required("threadId") ?: return invalid("Thread identity is missing.")
        val kind = when (payload["kind"]) {
            "approval" -> ThreadNotificationKind.APPROVAL
            "input" -> ThreadNotificationKind.INPUT
            "completion" -> ThreadNotificationKind.COMPLETION
            "failure" -> ThreadNotificationKind.FAILURE
            else -> return invalid("Notification kind is unsupported.")
        }
        val title = payload.required("title") ?: return invalid("Notification title is missing.")
        val headline = payload.required("headline") ?: return invalid("Notification headline is missing.")
        val updatedAt = payload["updatedAtEpochMillis"]?.toLongOrNull()?.takeIf { it >= 0 }
            ?: return invalid("Notification timestamp is invalid.")
        val parsedRoute = ShortcutRouteCodec.parse(payload["deepLink"])
        val route = (parsedRoute as? ShortcutParseResult.Valid)?.route as? ShortcutRoute.Thread
            ?: return invalid("Notification deep link is invalid.")
        if (route.environmentId != environmentId || route.projectId != projectId || route.threadId != threadId) {
            return invalid("Notification deep link does not match its identities.")
        }
        return ThreadNotificationParseResult.Valid(
            ThreadNotification(
                environmentId = environmentId,
                projectId = projectId,
                threadId = threadId,
                kind = kind,
                title = title.take(MAX_TEXT_LENGTH),
                headline = headline.take(MAX_TEXT_LENGTH),
                updatedAtEpochMillis = updatedAt,
                route = route,
            ),
        )
    }

    private fun Map<String, String>.required(key: String) = get(key)?.trim()?.takeIf { it.isNotEmpty() }
    private fun invalid(reason: String) = ThreadNotificationParseResult.Invalid(reason)

    private const val MAX_TEXT_LENGTH = 160
}

fun interface ThreadNotificationPublisher {
    fun publish(notification: ThreadNotification): Boolean
}

class ThreadNotificationDelivery(private val publisher: ThreadNotificationPublisher) {
    fun receive(payload: Map<String, String>): ThreadNotificationParseResult =
        ThreadNotificationPayloadParser.parse(payload).also { result ->
            if (result is ThreadNotificationParseResult.Valid) publisher.publish(result.notification)
        }
}
