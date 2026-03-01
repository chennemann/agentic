package de.chennemann.agentic.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.db.ListAppLog
import de.chennemann.agentic.di.DispatcherProvider
import de.chennemann.agentic.domain.session.LogEntry
import de.chennemann.agentic.domain.session.LogFacet
import de.chennemann.agentic.domain.session.LogFilter
import de.chennemann.agentic.domain.session.LogLevel
import de.chennemann.agentic.domain.session.LogProjectOption
import de.chennemann.agentic.domain.session.LogRecord
import de.chennemann.agentic.domain.session.LogSessionOption
import de.chennemann.agentic.domain.session.LogStoreGateway
import de.chennemann.agentic.domain.session.LogUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class LocalLogRepository(
    private val db: AgenticDb,
    private val dispatchers: DispatcherProvider,
    private val json: Json,
) : LogStoreGateway {
    private var writes = 0L

    override suspend fun append(record: LogRecord) {
        withContext(dispatchers.io) {
            db.appLogQueries.insertAppLog(
                created_at = record.createdAt,
                level = record.level.key,
                logical_unit = record.unit.key,
                tag = record.tag,
                event = record.event,
                project_id = record.projectId,
                project_name = record.projectName,
                session_id = record.sessionId,
                session_title = record.sessionTitle,
                message = record.message,
                context_json = encode(record.context),
                throwable = record.throwable,
                redacted = if (record.redacted) 1 else 0,
            )
            writes += 1
            if (writes % PruneStride == 0L) {
                prune(record.createdAt)
            }
        }
    }

    override fun observe(filter: LogFilter): Flow<List<LogEntry>> {
        val term = filter.query.trim().ifBlank { null }
        return db.appLogQueries
            .listAppLog(
                logical_unit = filter.unit?.key,
                level = filter.level?.key,
                event = filter.event,
                project_id = filter.projectId,
                session_id = filter.sessionId,
                from_at = filter.from,
                until_at = filter.until,
                search = term,
                limit = filter.limit,
            )
            .asFlow()
            .mapToList(dispatchers.io)
            .map { rows -> rows.map(::entry) }
    }

    override fun observeFacet(): Flow<LogFacet> {
        val projects = db.appLogQueries
            .listAppLogProjectFacet { projectId, projectName ->
                LogProjectOption(
                    id = projectId,
                    name = projectName ?: projectId,
                )
            }
            .asFlow()
            .mapToList(dispatchers.io)
        val sessions = db.appLogQueries
            .listAppLogSessionFacet { sessionId, sessionTitle ->
                LogSessionOption(
                    id = sessionId,
                    title = sessionTitle ?: sessionId,
                )
            }
            .asFlow()
            .mapToList(dispatchers.io)
        val events = db.appLogQueries
            .listAppLogEventFacet()
            .asFlow()
            .mapToList(dispatchers.io)
        return combine(projects, sessions, events) { p, s, e ->
            LogFacet(
                projects = p,
                sessions = s,
                events = e,
            )
        }
    }

    override suspend fun prune(now: Long) {
        withContext(dispatchers.io) {
            db.appLogQueries.deleteAppLogBefore(now - RetainMs)
            val count = db.appLogQueries.countAppLog().executeAsOne()
            val overflow = count - MaxRows
            if (overflow > 0) {
                db.appLogQueries.deleteAppLogOverflow(MaxRows)
            }
        }
    }

    override suspend fun clear() {
        withContext(dispatchers.io) {
            db.appLogQueries.deleteAppLogAll()
        }
    }

    private fun encode(context: Map<String, String>): String {
        return buildJsonObject {
            context.forEach { put(it.key, it.value) }
        }.toString()
    }

    private fun decode(value: String): Map<String, String> {
        return runCatching {
            json.parseToJsonElement(value).jsonObject.mapValues { it.value.jsonPrimitive.content }
        }.getOrDefault(emptyMap())
    }

    private fun entry(value: ListAppLog): LogEntry {
        return LogEntry(
            id = value.id,
            createdAt = value.created_at,
            level = LogLevel.from(value.level),
            unit = LogUnit.from(value.logical_unit),
            tag = value.tag,
            event = value.event,
            projectId = value.project_id,
            projectName = value.project_name,
            sessionId = value.session_id,
            sessionTitle = value.session_title,
            message = value.message,
            context = decode(value.context_json),
            throwable = value.throwable,
        )
    }
}

private const val RetainMs = 7 * 24 * 60 * 60 * 1000L
private const val MaxRows = 5000L
private const val PruneStride = 200L
