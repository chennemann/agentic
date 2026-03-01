package de.chennemann.agentic.domain.v2

import de.chennemann.agentic.domain.v2.projects.LocalProjectInfo
import de.chennemann.agentic.domain.v2.projects.ProjectService
import de.chennemann.agentic.domain.v2.servers.LocalServerInfo
import de.chennemann.agentic.domain.v2.servers.ServerRepository
import de.chennemann.agentic.domain.v2.session.LocalSessionInfo
import de.chennemann.agentic.domain.v2.session.SessionService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SynchronizationServiceTest {
    @Test
    fun syncServerDelegatesToProjectAndSessionServices() = runTest {
        val servers = FakeServerRepository()
        val projects = FakeProjectService()
        val sessions = FakeSessionService()
        val service = DefaultSynchronizationService(servers, projects, sessions)

        servers.insertServer(LocalServerInfo(id = "server-1", url = "  https://example.test  "))
        projects.nextProjects = listOf(
            LocalProjectInfo(
                id = "p1",
                serverId = "server-1",
                name = "Alpha",
                path = "/repo/a",
                pinned = false,
            ),
            LocalProjectInfo(
                id = "p2",
                serverId = "server-1",
                name = "Beta",
                path = "/repo/b",
                pinned = true,
            ),
        )

        service.syncServer("  server-1  ")

        assertEquals(listOf("server-1|https://example.test"), projects.syncCalls)
        assertEquals(
            listOf(
                "p1|/repo/a|https://example.test",
                "p2|/repo/b|https://example.test",
            ),
            sessions.syncCalls,
        )
    }

    @Test
    fun syncServerSkipsWhenIdBlankOrServerMissingOrUrlBlank() = runTest {
        val servers = FakeServerRepository()
        val projects = FakeProjectService()
        val sessions = FakeSessionService()
        val service = DefaultSynchronizationService(servers, projects, sessions)

        servers.insertServer(LocalServerInfo(id = "server-blank-url", url = "   "))

        service.syncServer("   ")
        service.syncServer("missing")
        service.syncServer("server-blank-url")

        assertTrue(projects.syncCalls.isEmpty())
        assertTrue(sessions.syncCalls.isEmpty())
    }
}

private class FakeServerRepository : ServerRepository {
    private val records = linkedMapOf<String, LocalServerInfo>()

    override fun observeServers(): Flow<List<LocalServerInfo>> {
        return flowOf(records.values.toList())
    }

    override suspend fun selectServer(id: String): LocalServerInfo? {
        return records[id]
    }

    override suspend fun selectServerByUrl(url: String): LocalServerInfo? {
        return records.values.firstOrNull { it.url == url }
    }

    override suspend fun insertServer(server: LocalServerInfo) {
        records[server.id] = server
    }

    override suspend fun updateServer(server: LocalServerInfo) {
        records[server.id] = server
    }

    override suspend fun deleteServer(id: String) {
        records.remove(id)
    }
}

private class FakeProjectService : ProjectService {
    val syncCalls = mutableListOf<String>()
    var nextProjects: List<LocalProjectInfo> = emptyList()

    override fun observeProjects(serverId: String?): Flow<List<LocalProjectInfo>> {
        return flowOf(emptyList())
    }

    override suspend fun togglePinnedById(projectId: String): Boolean {
        return true
    }

    override suspend fun syncServerProjects(serverId: String, baseUrl: String): List<LocalProjectInfo> {
        syncCalls += "$serverId|$baseUrl"
        return nextProjects
    }
}

private class FakeSessionService : SessionService {
    val syncCalls = mutableListOf<String>()

    override fun sessionsOfProject(projectKey: String) = flowOf(emptyList<LocalSessionInfo>())

    override suspend fun syncSessionsOfProject(projectId: String, projectPath: String, baseUrl: String) {
        syncCalls += "$projectId|$projectPath|$baseUrl"
    }
}
