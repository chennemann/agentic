package de.chennemann.agentic.data

import android.util.Log
import de.chennemann.agentic.domain.session.LogGateway
import de.chennemann.agentic.domain.session.LogLevel
import de.chennemann.agentic.domain.session.LogRecord
import de.chennemann.agentic.domain.session.LogRedactor
import de.chennemann.agentic.domain.session.LogStoreGateway
import de.chennemann.agentic.domain.session.LogUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class AndroidLogGateway(
    private val store: LogStoreGateway,
    private val scope: CoroutineScope,
    private val redactor: LogRedactor,
) : LogGateway {
    init {
        scope.launch {
            store.prune()
        }
    }

    override fun log(
        level: LogLevel,
        unit: LogUnit,
        tag: String,
        event: String,
        message: String,
        context: Map<String, String>,
        error: Throwable?,
    ) {
        val safeMessage = redactor.redact(message)
        val payload = promote(redactor.redact(context))
        val safeThrowable = redactor.throwable(error)
        val line = format(event, safeMessage, payload.context)
        when (level) {
            LogLevel.debug -> Log.d(tag, line)
            LogLevel.info -> Log.i(tag, line)
            LogLevel.warn -> Log.w(tag, line)
            LogLevel.error -> Log.e(tag, line)
        }
        scope.launch {
            store.append(
                LogRecord(
                    createdAt = System.currentTimeMillis(),
                    level = level,
                    unit = unit,
                    tag = tag,
                    event = event,
                    projectId = payload.projectId,
                    projectName = payload.projectName,
                    sessionId = payload.sessionId,
                    sessionTitle = payload.sessionTitle,
                    message = safeMessage,
                    context = payload.context,
                    throwable = safeThrowable,
                    redacted = true,
                )
            )
        }
    }
}

private fun format(event: String, message: String, context: Map<String, String>): String {
    if (context.isEmpty()) return "$event: $message"
    val meta = context.entries.joinToString(" ") { "${it.key}=${it.value}" }
    return "$event: $message $meta"
}

private fun promote(context: Map<String, String>): LogPayload {
    val projectId = first(context, ProjectIdKeys)
    val projectName = first(context, ProjectNameKeys)
    val sessionId = first(context, SessionIdKeys)
    val sessionTitle = first(context, SessionTitleKeys)
    val keep = context.filterKeys {
        it !in ProjectIdKeys && it !in ProjectNameKeys && it !in SessionIdKeys && it !in SessionTitleKeys
    }
    return LogPayload(projectId, projectName, sessionId, sessionTitle, keep)
}

private fun first(context: Map<String, String>, keys: Set<String>): String? {
    return keys.firstNotNullOfOrNull { key ->
        context[key]?.trim()?.takeIf { it.isNotBlank() }
    }
}

private data class LogPayload(
    val projectId: String?,
    val projectName: String?,
    val sessionId: String?,
    val sessionTitle: String?,
    val context: Map<String, String>,
)

private val ProjectIdKeys = setOf("project_id", "projectId", "project")
private val ProjectNameKeys = setOf("project_name", "projectName")
private val SessionIdKeys = setOf("session_id", "sessionId", "session")
private val SessionTitleKeys = setOf("session_title", "sessionTitle")
