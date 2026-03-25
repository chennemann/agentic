package de.chennemann.agentic.domain.fixtures

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.chennemann.agentic.data.store.SqlDelightProjectRepository
import de.chennemann.agentic.data.store.SqlDelightServerRepository
import de.chennemann.agentic.data.store.SqlDelightSessionRepository
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.di.DispatcherProvider
import de.chennemann.agentic.di.TestDispatcherProvider
import de.chennemann.agentic.domain.sync.DefaultSynchronizationService
import de.chennemann.agentic.domain.remote.ServerHealthCheck
import de.chennemann.agentic.domain.remote.ServerProject
import de.chennemann.agentic.domain.remote.ServerAdapter
import de.chennemann.agentic.domain.remote.ServerSession
import de.chennemann.agentic.domain.projects.DefaultProjectService
import de.chennemann.agentic.domain.projects.LocalProjectInfo
import de.chennemann.agentic.domain.projects.ProjectRepository
import de.chennemann.agentic.domain.servers.DefaultServerService
import de.chennemann.agentic.domain.servers.ServerInfo
import de.chennemann.agentic.domain.servers.ServerRepository
import de.chennemann.agentic.domain.sessions.DefaultSessionService
import de.chennemann.agentic.domain.sessions.LocalSessionRecord
import de.chennemann.agentic.domain.sessions.SessionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

fun connectedServerFixture(
    id: String = "server-1",
    url: String = "https://example.test",
    lastConnectedAt: Long? = 1L,
): ServerInfo.ConnectedServerInfo {
    return ServerInfo.ConnectedServerInfo(
        id = id,
        url = url,
        lastConnectedAt = lastConnectedAt,
    )
}

fun healthCheckFixture(
    healthy: Boolean = true,
    version: String = "1.0.0",
): ServerHealthCheck {
    return ServerHealthCheck(
        healthy = healthy,
        version = version,
    )
}

fun openCodeProjectFixture(
    id: String = "project-1",
    worktree: String = "/repo/main",
    name: String = "Main",
    sandboxes: List<String> = emptyList(),
): ServerProject {
    return ServerProject(
        id = id,
        worktree = worktree,
        name = name,
        sandboxes = sandboxes,
    )
}

fun localProjectFixture(
    id: String = "project-1",
    serverId: String = "server-1",
    name: String = "Main",
    path: String = "/repo/main",
    pinned: Boolean = false,
): LocalProjectInfo {
    return LocalProjectInfo(
        id = id,
        serverId = serverId,
        name = name,
        path = path,
        pinned = pinned,
    )
}

fun openCodeSessionFixture(
    id: String = "session-1",
    projectId: String = "project-1",
    directory: String = "/repo/main",
    title: String = "Session",
    version: String = "1.0.0",
): ServerSession {
    return ServerSession(
        id = id,
        projectId = projectId,
        directory = directory,
        title = title,
        version = version,
    )
}

fun localSessionRecordFixture(
    id: String = "session-1",
    projectId: String = "project-1",
    title: String = "Session",
    path: String = "/repo/main",
    pinned: Boolean = false,
    parentId: String? = null,
    updatedAt: Long? = null,
    archivedAt: Long? = null,
): LocalSessionRecord {
    return LocalSessionRecord(
        id = id,
        projectId = projectId,
        title = title,
        path = path,
        pinned = pinned,
        parentId = parentId,
        updatedAt = updatedAt,
        archivedAt = archivedAt,
    )
}

class ServerAdapterFixture(
    var defaultHealthResult: Result<ServerHealthCheck> = Result.success(healthCheckFixture()),
    var defaultProjectsResult: Result<List<ServerProject>> = Result.success(emptyList()),
    var defaultSessionsResult: Result<List<ServerSession>> = Result.success(emptyList()),
    var healthCheckDelayMillis: Long = 0,
    var projectsDelayMillis: Long = 0,
    var sessionsDelayMillis: Long = 0,
) : ServerAdapter {
    private val healthChecksByUrl = linkedMapOf<String, Result<ServerHealthCheck>>()
    private val projectsByUrl = linkedMapOf<String, Result<List<ServerProject>>>()
    private val sessionsByTarget = linkedMapOf<String, Result<List<ServerSession>>>()
    val healthCheckRequests = mutableListOf<String>()
    val projectRequests = mutableListOf<String>()
    val sessionRequests = mutableListOf<String>()

    fun givenHealthCheck(url: String, result: Result<ServerHealthCheck>) {
        healthChecksByUrl[url] = result
    }

    fun givenProjects(url: String, projects: List<ServerProject>) {
        projectsByUrl[url] = Result.success(projects)
    }

    fun givenProjects(url: String, result: Result<List<ServerProject>>) {
        projectsByUrl[url] = result
    }

    fun givenSessions(
        url: String,
        path: String,
        sessions: List<ServerSession>,
    ) {
        sessionsByTarget[sessionTarget(url, path)] = Result.success(sessions)
    }

    fun givenSessions(
        url: String,
        path: String,
        result: Result<List<ServerSession>>,
    ) {
        sessionsByTarget[sessionTarget(url, path)] = result
    }

    override suspend fun healthCheckWithUrl(baseUrl: String): ServerHealthCheck {
        if (healthCheckDelayMillis > 0) {
            delay(healthCheckDelayMillis)
        }
        healthCheckRequests += baseUrl
        return (healthChecksByUrl[baseUrl] ?: defaultHealthResult).getOrThrow()
    }

    override suspend fun allProjects(baseUrl: String): List<ServerProject> {
        if (projectsDelayMillis > 0) {
            delay(projectsDelayMillis)
        }
        projectRequests += baseUrl
        return (projectsByUrl[baseUrl] ?: defaultProjectsResult).getOrThrow()
    }

    override suspend fun allSessionsOfAGivenProject(baseUrl: String, path: String): List<ServerSession> {
        if (sessionsDelayMillis > 0) {
            delay(sessionsDelayMillis)
        }
        sessionRequests += sessionTarget(baseUrl, path)
        return (sessionsByTarget[sessionTarget(baseUrl, path)] ?: defaultSessionsResult).getOrThrow()
    }

    private fun sessionTarget(baseUrl: String, path: String): String {
        return "$baseUrl|$path"
    }
}

class DomainV2TestEnvironment(
    private val driver: JdbcSqliteDriver,
    val db: AgenticDb,
    val dispatchers: DispatcherProvider,
    val adapter: ServerAdapterFixture,
    val serverRepository: ServerRepository,
    val projectRepository: ProjectRepository,
    val sessionRepository: SessionRepository,
    val serverService: DefaultServerService,
    val projectService: DefaultProjectService,
    val sessionService: DefaultSessionService,
    val synchronizationService: DefaultSynchronizationService,
) : AutoCloseable {
    suspend fun seedServer(server: ServerInfo.ConnectedServerInfo = connectedServerFixture()): ServerInfo.ConnectedServerInfo {
        serverRepository.insertServer(server)
        return server
    }

    suspend fun seedProject(project: LocalProjectInfo = localProjectFixture()): LocalProjectInfo {
        projectRepository.insertProject(project)
        return project
    }

    suspend fun seedSession(session: LocalSessionRecord = localSessionRecordFixture()): LocalSessionRecord {
        sessionRepository.insertStoredSession(session)
        return session
    }

    suspend fun allPersistedServers(): List<ServerInfo.ConnectedServerInfo> {
        return serverRepository.observeServers().first()
    }

    suspend fun allPersistedProjects(serverId: String? = null): List<LocalProjectInfo> {
        return projectRepository.observeProjects(serverId).first()
    }

    suspend fun allPersistedSessions(projectId: String? = null): List<LocalSessionRecord> {
        return sessionRepository.observeStoredSessions(projectId).first()
    }

    override fun close() {
        driver.close()
    }
}

fun domainV2TestEnvironment(
    dispatcher: CoroutineDispatcher,
    adapter: ServerAdapterFixture = ServerAdapterFixture(),
): DomainV2TestEnvironment {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    AgenticDb.Schema.synchronous().create(driver)
    val db = AgenticDb(driver)
    val dispatchers = TestDispatcherProvider(dispatcher)
    val serverRepository = SqlDelightServerRepository(db, dispatchers)
    val projectRepository = SqlDelightProjectRepository(db, dispatchers)
    val sessionRepository = SqlDelightSessionRepository(db, dispatchers)
    val serverService = DefaultServerService(adapter, serverRepository)
    val projectService = DefaultProjectService(adapter, serverService, projectRepository)
    val sessionService = DefaultSessionService(sessionRepository, adapter)
    return DomainV2TestEnvironment(
        driver = driver,
        db = db,
        dispatchers = dispatchers,
        adapter = adapter,
        serverRepository = serverRepository,
        projectRepository = projectRepository,
        sessionRepository = sessionRepository,
        serverService = serverService,
        projectService = projectService,
        sessionService = sessionService,
        synchronizationService = DefaultSynchronizationService(
            serverRepository = serverRepository,
            projectService = projectService,
            sessionService = sessionService,
        ),
    )
}

typealias ServerServiceTestEnvironment = DomainV2TestEnvironment

fun serverServiceTestEnvironment(
    dispatcher: CoroutineDispatcher,
    adapter: ServerAdapterFixture = ServerAdapterFixture(),
): ServerServiceTestEnvironment {
    return domainV2TestEnvironment(dispatcher, adapter)
}
