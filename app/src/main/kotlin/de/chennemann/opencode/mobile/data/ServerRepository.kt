package de.chennemann.opencode.mobile.data

import de.chennemann.opencode.mobile.db.AgenticDb
import de.chennemann.opencode.mobile.di.DispatcherProvider
import de.chennemann.opencode.mobile.domain.session.CommandGateway
import de.chennemann.opencode.mobile.domain.session.CommandState
import de.chennemann.opencode.mobile.domain.session.ConnectivityGateway
import de.chennemann.opencode.mobile.domain.session.ConnectionGateway
import de.chennemann.opencode.mobile.domain.session.MessageGateway
import de.chennemann.opencode.mobile.domain.session.ProjectGateway
import de.chennemann.opencode.mobile.domain.session.SessionMessage
import de.chennemann.opencode.mobile.domain.session.SessionProject
import de.chennemann.opencode.mobile.domain.session.SessionStreamEvent
import de.chennemann.opencode.mobile.domain.session.SessionSummary
import de.chennemann.opencode.mobile.domain.session.ConnectionState
import de.chennemann.opencode.mobile.domain.session.LogGateway
import de.chennemann.opencode.mobile.domain.session.LogUnit
import de.chennemann.opencode.mobile.domain.session.StreamGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ServerRepository(
    private val db: AgenticDb,
    private val mdns: MdnsGateway,
    private val service: ServerGateway,
    private val network: ConnectivityGateway,
    private val dispatchers: DispatcherProvider,
    private val log: LogGateway,
) : ConnectionGateway, ProjectGateway, MessageGateway, StreamGateway, CommandGateway {
    private val state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val url = MutableStateFlow(DefaultUrl)
    private val discovered = MutableStateFlow<String?>(null)

    override val status: StateFlow<ConnectionState> = state.asStateFlow()
    override val endpoint: StateFlow<String> = url.asStateFlow()
    override val found: StateFlow<String?> = discovered.asStateFlow()

    override fun start(scope: CoroutineScope) {
        scope.launch {
            load()
            refresh(true)
        }
        scope.launch {
            network.changed.drop(1).collect {
                refresh(false)
            }
        }
        scope.launch {
            mdns.discover().collect { entry ->
                discovered.value = normalizeUrl(entry.url) ?: entry.url
            }
        }
    }

    override suspend fun setUrl(next: String) {
        val value = normalizeUrl(next) ?: return
        url.value = value
        withContext(dispatchers.io) {
            db.settingsQueries.upsertSetting(UrlKey, value)
        }
    }

    override suspend fun refresh(loading: Boolean) {
        val endpoint = url.value
        if (loading) state.value = ConnectionState.Loading
        val result = runCatching {
            withContext(dispatchers.io) {
                service.health(endpoint)
            }
        }
        result.exceptionOrNull()?.let {
            log.error(
                unit = LogUnit.network,
                tag = LogTag,
                event = "health_failed",
                message = "Health check failed",
                context = mapOf("endpoint" to endpoint),
                error = it,
            )
        }
        state.value = withContext(dispatchers.default) {
            result.fold(
                onSuccess = {
                    if (it.healthy) ConnectionState.Connected(it.version)
                    else ConnectionState.Failed("Server is unhealthy")
                },
                onFailure = { ConnectionState.Failed(it.message ?: "Connection failed") },
            )
        }
    }

    override suspend fun projects(): List<SessionProject> {
        val rows = withContext(dispatchers.io) {
            service.projects(url.value)
        }
        return withContext(dispatchers.default) {
            rows.map {
                SessionProject(
                    id = it.id,
                    worktree = it.worktree,
                    name = it.name,
                    sandboxes = it.sandboxes,
                )
            }
        }
    }

    override suspend fun sessions(worktree: String, limit: Int?): List<SessionSummary> {
        val rows = withContext(dispatchers.io) {
            service.sessions(url.value, worktree, limit)
        }
        return withContext(dispatchers.default) {
            rows.map {
                SessionSummary(
                    id = it.id,
                    title = it.title,
                    version = it.version,
                    directory = it.directory,
                    parentId = it.parentId,
                    updatedAt = it.updatedAt,
                    archivedAt = it.archivedAt,
                )
            }
        }
    }

    override suspend fun archiveSession(sessionId: String, directory: String) {
        withContext(dispatchers.io) {
            service.archiveSession(url.value, sessionId, directory)
        }
    }

    override suspend fun renameSession(sessionId: String, directory: String, title: String) {
        withContext(dispatchers.io) {
            service.renameSession(url.value, sessionId, directory, title)
        }
    }

    override suspend fun createSession(worktree: String, title: String): SessionSummary {
        val row = withContext(dispatchers.io) {
            service.createSession(url.value, worktree, title)
        }
        return withContext(dispatchers.default) {
            SessionSummary(
                id = row.id,
                title = row.title,
                version = row.version,
                directory = row.directory,
            )
        }
    }

    override suspend fun commands(directory: String): List<CommandState> {
        val rows = withContext(dispatchers.io) {
            service.commands(url.value, directory)
        }
        return withContext(dispatchers.default) {
            rows.map {
                CommandState(
                    name = it.name,
                    description = it.description,
                    source = it.source,
                )
            }
        }
    }

    override suspend fun messages(sessionId: String, directory: String, limit: Int?): List<SessionMessage> {
        val rows = withContext(dispatchers.io) {
            service.sessionMessages(url.value, sessionId, directory, limit)
        }
        return withContext(dispatchers.default) {
            rows.map {
                SessionMessage(
                    id = it.id,
                    role = it.role,
                    text = it.text,
                    parts = it.parts,
                    createdAt = it.createdAt,
                    completedAt = it.completedAt,
                )
            }
        }
    }

    override suspend fun updatedAt(sessionId: String, directory: String): Long? {
        return withContext(dispatchers.io) {
            service.sessionUpdatedAt(url.value, sessionId, directory)
        }
    }

    override suspend fun status(directory: String): Map<String, String> {
        return withContext(dispatchers.io) {
            service.sessionStatus(url.value, directory)
        }
    }

    override suspend fun streamEvents(lastEventId: String?, onRawEvent: suspend (String) -> Unit, onEvent: suspend (SessionStreamEvent) -> Unit): String? {
        return withContext(dispatchers.io) {
            service.streamEvents(url.value, lastEventId, onRawEvent) {
                onEvent(
                    SessionStreamEvent(
                        directory = it.directory,
                        type = it.type,
                        properties = it.properties,
                        id = it.id,
                        retry = it.retry,
                    )
                )
            }
        }
    }

    override suspend fun sendMessage(sessionId: String, directory: String, text: String, agent: String) {
        withContext(dispatchers.io) {
            service.sendMessage(url.value, sessionId, directory, text, agent)
        }
    }

    override suspend fun sendCommand(sessionId: String, directory: String, name: String, arguments: String, agent: String) {
        withContext(dispatchers.io) {
            service.sendCommand(url.value, sessionId, directory, name, arguments, agent)
        }
    }

    override suspend fun streamCursor(): String? {
        return withContext(dispatchers.io) {
            db.settingsQueries.selectSetting(eventCursorKey(url.value)).executeAsOneOrNull()
        }
    }

    override suspend fun setStreamCursor(value: String?) {
        val key = eventCursorKey(url.value)
        withContext(dispatchers.io) {
            if (value.isNullOrBlank()) {
                db.settingsQueries.deleteSetting(key)
                return@withContext
            }
            db.settingsQueries.upsertSetting(key, value)
        }
    }

    private suspend fun load() {
        val value = withContext(dispatchers.io) {
            db.settingsQueries.selectSetting(UrlKey).executeAsOneOrNull()
        }
        if (value == null) return
        val normalized = normalizeUrl(value) ?: return
        url.value = normalized
        if (normalized == value) return
        withContext(dispatchers.io) {
            db.settingsQueries.upsertSetting(UrlKey, normalized)
        }
    }
}

private const val LogTag = "ServerRepository"

private fun normalizeUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return null
    val withProtocol = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
        trimmed
    } else {
        "http://$trimmed"
    }
    return withProtocol.replace(Regex("/+$"), "")
}

private const val DefaultUrl = "http://opencode.local:4096"
private const val UrlKey = "server_url"
private fun eventCursorKey(url: String): String {
    return "event_cursor:$url"
}
