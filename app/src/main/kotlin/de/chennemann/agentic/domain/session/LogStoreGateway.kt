package de.chennemann.agentic.domain.session

import kotlinx.coroutines.flow.Flow

enum class LogLevel(val key: String) {
    debug("debug"),
    info("info"),
    warn("warn"),
    error("error");

    companion object {
        fun from(key: String): LogLevel {
            return entries.firstOrNull { it.key == key } ?: info
        }
    }
}

data class LogRecord(
    val createdAt: Long,
    val level: LogLevel,
    val unit: LogUnit,
    val tag: String,
    val event: String,
    val projectId: String?,
    val projectName: String?,
    val sessionId: String?,
    val sessionTitle: String?,
    val message: String,
    val context: Map<String, String>,
    val throwable: String?,
    val redacted: Boolean,
)

data class LogEntry(
    val id: Long,
    val createdAt: Long,
    val level: LogLevel,
    val unit: LogUnit,
    val tag: String,
    val event: String,
    val projectId: String?,
    val projectName: String?,
    val sessionId: String?,
    val sessionTitle: String?,
    val message: String,
    val context: Map<String, String>,
    val throwable: String?,
)

data class LogFilter(
    val unit: LogUnit? = null,
    val level: LogLevel? = null,
    val event: String? = null,
    val projectId: String? = null,
    val sessionId: String? = null,
    val from: Long? = null,
    val until: Long? = null,
    val query: String = "",
    val limit: Long = 500,
)

data class LogProjectOption(
    val id: String,
    val name: String,
)

data class LogSessionOption(
    val id: String,
    val title: String,
)

data class LogFacet(
    val projects: List<LogProjectOption>,
    val sessions: List<LogSessionOption>,
    val events: List<String>,
)

interface LogStoreGateway {
    suspend fun append(record: LogRecord)

    fun observe(filter: LogFilter): Flow<List<LogEntry>>

    fun observeFacet(): Flow<LogFacet>

    suspend fun prune(now: Long = System.currentTimeMillis())

    suspend fun clear()
}
