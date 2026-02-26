package de.chennemann.opencode.mobile.domain.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SessionStreamCoordinator(
    private val conn: ConnectionGateway,
    private val feed: StreamGateway,
    private val net: ConnectivityGateway,
    private val log: LogGateway,
) {
    fun start(scope: CoroutineScope, onEvent: suspend (SessionStreamEvent) -> Unit): Job {
        return scope.launch {
            var attempt = 0
            var cursor = runCatching { feed.streamCursor() }.getOrNull()
            while (isActive) {
                val endpoint = conn.endpoint.value
                log.debug(
                    unit = LogUnit.stream,
                    tag = SessionLogTag,
                    event = "sse_connect",
                    message = "Connecting to stream",
                    context = mapOf(
                        "attempt" to "${attempt + 1}",
                        "endpoint" to endpoint,
                        "cursor" to cursor.orEmpty(),
                    ),
                )
                val result = runCatching {
                    feed.streamEvents(cursor, { chunk ->
                        log.debug(
                            unit = LogUnit.stream,
                            tag = SessionLogTag,
                            event = "sse_raw",
                            message = "Stream chunk",
                            context = mapOf(
                                "length" to "${chunk.length}",
                                "head" to chunk.take(140).replace("\n", "\\n"),
                            ),
                        )
                    }) { event ->
                        log.debug(
                            unit = LogUnit.stream,
                            tag = SessionLogTag,
                            event = "sse_event",
                            message = "Stream event",
                            context = mapOf(
                                "type" to event.type,
                                "directory" to event.directory,
                                "id" to event.id.orEmpty(),
                            ),
                        )
                        if (!event.id.isNullOrBlank()) {
                            cursor = event.id
                            runCatching { feed.setStreamCursor(cursor) }
                        }
                        onEvent(event)
                    }
                }
                if (result.isSuccess) {
                    log.debug(
                        unit = LogUnit.stream,
                        tag = SessionLogTag,
                        event = "sse_end",
                        message = "Stream ended normally, reconnecting",
                    )
                    attempt = 0
                    continue
                }
                val reason = result.exceptionOrNull()?.message ?: "unknown stream error"
                log.warn(
                    unit = LogUnit.stream,
                    tag = SessionLogTag,
                    event = "sse_error",
                    message = "Stream failed",
                    context = mapOf(
                        "attempt" to "${attempt + 1}",
                        "reason" to reason,
                    ),
                    error = result.exceptionOrNull(),
                )
                attempt += 1
                val seen = net.changed.value
                val mode = if (!net.online.value) "offline; waiting for network change" else "waiting for network change before reconnect"
                log.debug(
                    unit = LogUnit.stream,
                    tag = SessionLogTag,
                    event = "sse_wait",
                    message = mode,
                )
                net.changed.first { it > seen }
                log.debug(
                    unit = LogUnit.stream,
                    tag = SessionLogTag,
                    event = "sse_retry",
                    message = "Network changed, retrying stream",
                )
                delay(StreamRestartDelay)
            }
        }
    }
}

private const val SessionLogTag = "SessionService"
private const val StreamRestartDelay = 3000L
