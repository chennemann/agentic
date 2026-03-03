package de.chennemann.agentic.domain.v2.fixtures

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.chennemann.agentic.data.v2.SqlDelightServerRepository
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.di.DispatcherProvider
import de.chennemann.agentic.di.TestDispatcherProvider
import de.chennemann.agentic.domain.v2.OpenCodeHealthCheck
import de.chennemann.agentic.domain.v2.OpenCodeProject
import de.chennemann.agentic.domain.v2.OpenCodeServerAdapter
import de.chennemann.agentic.domain.v2.OpenCodeSession
import de.chennemann.agentic.domain.v2.servers.DefaultServerService
import de.chennemann.agentic.domain.v2.servers.ServerInfo
import de.chennemann.agentic.domain.v2.servers.ServerRepository
import kotlinx.coroutines.CoroutineDispatcher
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
): OpenCodeHealthCheck {
    return OpenCodeHealthCheck(
        healthy = healthy,
        version = version,
    )
}

class OpenCodeServerAdapterFixture(
    var defaultHealthResult: Result<OpenCodeHealthCheck> = Result.success(healthCheckFixture()),
) : OpenCodeServerAdapter {
    private val healthChecksByUrl = linkedMapOf<String, Result<OpenCodeHealthCheck>>()
    val healthCheckRequests = mutableListOf<String>()

    fun givenHealthCheck(url: String, result: Result<OpenCodeHealthCheck>) {
        healthChecksByUrl[url] = result
    }

    override suspend fun healthCheckWithUrl(baseUrl: String): OpenCodeHealthCheck {
        healthCheckRequests += baseUrl
        return (healthChecksByUrl[baseUrl] ?: defaultHealthResult).getOrThrow()
    }

    override suspend fun allProjects(baseUrl: String): List<OpenCodeProject> {
        return emptyList()
    }

    override suspend fun allSessionsOfAGivenProject(baseUrl: String, path: String): List<OpenCodeSession> {
        return emptyList()
    }
}

class ServerServiceTestEnvironment(
    private val driver: JdbcSqliteDriver,
    val db: AgenticDb,
    val dispatchers: DispatcherProvider,
    val adapter: OpenCodeServerAdapterFixture,
    val repository: ServerRepository,
    val service: DefaultServerService,
) : AutoCloseable {
    suspend fun seedServer(server: ServerInfo.ConnectedServerInfo = connectedServerFixture()): ServerInfo.ConnectedServerInfo {
        repository.insertServer(server)
        return server
    }

    suspend fun allPersistedServers(): List<ServerInfo.ConnectedServerInfo> {
        return repository.observeServers().first()
    }

    override fun close() {
        driver.close()
    }
}

fun serverServiceTestEnvironment(
    dispatcher: CoroutineDispatcher,
    adapter: OpenCodeServerAdapterFixture = OpenCodeServerAdapterFixture(),
): ServerServiceTestEnvironment {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    AgenticDb.Schema.synchronous().create(driver)
    val db = AgenticDb(driver)
    val dispatchers = TestDispatcherProvider(dispatcher)
    val repository = SqlDelightServerRepository(db, dispatchers)
    return ServerServiceTestEnvironment(
        driver = driver,
        db = db,
        dispatchers = dispatchers,
        adapter = adapter,
        repository = repository,
        service = DefaultServerService(adapter, repository),
    )
}
