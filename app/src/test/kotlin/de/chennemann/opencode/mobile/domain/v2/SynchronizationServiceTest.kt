package de.chennemann.opencode.mobile.domain.v2

import de.chennemann.opencode.mobile.domain.v2.projects.LocalProjectInfo
import de.chennemann.opencode.mobile.domain.v2.projects.ProjectRepository
import de.chennemann.opencode.mobile.domain.v2.servers.LocalServerInfo
import de.chennemann.opencode.mobile.domain.v2.servers.ServerRepository
import de.chennemann.opencode.mobile.domain.v2.session.LocalSessionInfo
import de.chennemann.opencode.mobile.domain.v2.session.LocalSessionRecord
import de.chennemann.opencode.mobile.domain.v2.session.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SynchronizationServiceTest {
    @Test
    fun syncServerLoadsProjectsAndSessionsAndPersistsValidRows() = runTest {
        val serverRepository = FakeServerRepository()
        val projectRepository = FakeProjectRepository()
        val sessionRepository = FakeSessionRepository()
        val adapter = FakeOpenCodeServerAdapter()
        val service = DefaultSynchronizationService(serverRepository, projectRepository, sessionRepository, adapter)

        serverRepository.insertServer(
            LocalServerInfo(
                id = "server-1",
                url = "  https://example.test  ",
            )
        )
        adapter.projects = listOf(
            OpenCodeProject(
                id = "p1",
                worktree = "/repo/a",
                name = "Alpha",
                sandboxes = emptyList(),
            ),
            OpenCodeProject(
                id = "p2",
                worktree = "/repo/b",
                name = "   ",
                sandboxes = emptyList(),
            ),
            OpenCodeProject(
                id = "   ",
                worktree = "/repo/c",
                name = "Invalid",
                sandboxes = emptyList(),
            ),
            OpenCodeProject(
                id = "p3",
                worktree = "   ",
                name = "Invalid",
                sandboxes = emptyList(),
            ),
        )
        adapter.sessionsByPath = mapOf(
            "/repo/a" to listOf(
                OpenCodeSession(
                    id = "s1",
                    projectId = "p1",
                    directory = "/repo/a",
                    title = "Session A",
                    version = "1",
                ),
                OpenCodeSession(
                    id = "   ",
                    projectId = "p1",
                    directory = "/repo/a",
                    title = "Invalid",
                    version = "1",
                ),
            ),
            "/repo/b" to listOf(
                OpenCodeSession(
                    id = "s2",
                    projectId = "p2",
                    directory = "/repo/b",
                    title = "   ",
                    version = "1",
                ),
                OpenCodeSession(
                    id = "s3",
                    projectId = "p2",
                    directory = "   ",
                    title = "Invalid",
                    version = "1",
                ),
            ),
        )

        service.syncServer("  server-1  ")

        assertEquals("https://example.test", adapter.lastProjectsBaseUrl)
        assertEquals(
            listOf(
                "https://example.test|/repo/a",
                "https://example.test|/repo/b",
            ),
            adapter.sessionRequests,
        )
        assertEquals(2, projectRepository.projects.size)
        assertEquals(2, projectRepository.insertCalls)
        assertEquals(0, projectRepository.updateCalls)
        assertEquals(
            LocalProjectInfo(
                id = "p1",
                serverId = "server-1",
                name = "Alpha",
                path = "/repo/a",
                pinned = false,
            ),
            projectRepository.projects["p1"],
        )
        assertEquals(
            LocalProjectInfo(
                id = "p2",
                serverId = "server-1",
                name = "/repo/b",
                path = "/repo/b",
                pinned = false,
            ),
            projectRepository.projects["p2"],
        )
        assertEquals(2, sessionRepository.sessions.size)
        assertEquals(2, sessionRepository.insertCalls)
        assertEquals(0, sessionRepository.updateCalls)
        assertEquals(
            LocalSessionRecord(
                id = "s1",
                projectId = "p1",
                title = "Session A",
                path = "/repo/a",
                pinned = false,
            ),
            sessionRepository.sessions["s1"],
        )
        assertEquals(
            LocalSessionRecord(
                id = "s2",
                projectId = "p2",
                title = "/repo/b",
                path = "/repo/b",
                pinned = false,
            ),
            sessionRepository.sessions["s2"],
        )
    }

    @Test
    fun syncServerUpdatesExistingSessionAndKeepsPinnedState() = runTest {
        val serverRepository = FakeServerRepository()
        val projectRepository = FakeProjectRepository()
        val sessionRepository = FakeSessionRepository()
        val adapter = FakeOpenCodeServerAdapter()
        val service = DefaultSynchronizationService(serverRepository, projectRepository, sessionRepository, adapter)

        serverRepository.insertServer(LocalServerInfo(id = "server-1", url = "https://example.test"))
        projectRepository.projects["p1"] = LocalProjectInfo(
            id = "p1",
            serverId = "server-1",
            name = "Old Name",
            path = "/repo/old",
            pinned = true,
        )
        sessionRepository.sessions["s1"] = LocalSessionRecord(
            id = "s1",
            projectId = "p1",
            title = "Old Session",
            path = "/repo/old",
            pinned = true,
        )
        adapter.projects = listOf(
            OpenCodeProject(
                id = "p1",
                worktree = "/repo/new",
                name = "New Name",
                sandboxes = emptyList(),
            ),
        )
        adapter.sessionsByPath = mapOf(
            "/repo/new" to listOf(
                OpenCodeSession(
                    id = "s1",
                    projectId = "p1",
                    directory = "/repo/new",
                    title = "New Session",
                    version = "2",
                ),
            ),
        )

        service.syncServer("server-1")

        assertEquals(0, projectRepository.insertCalls)
        assertEquals(1, projectRepository.updateCalls)
        assertEquals(0, sessionRepository.insertCalls)
        assertEquals(1, sessionRepository.updateCalls)
        assertEquals(
            LocalSessionRecord(
                id = "s1",
                projectId = "p1",
                title = "New Session",
                path = "/repo/new",
                pinned = true,
            ),
            sessionRepository.sessions["s1"],
        )
    }

    @Test
    fun syncServerSkipsWhenServerDoesNotExist() = runTest {
        val serverRepository = FakeServerRepository()
        val projectRepository = FakeProjectRepository()
        val sessionRepository = FakeSessionRepository()
        val adapter = FakeOpenCodeServerAdapter()
        val service = DefaultSynchronizationService(serverRepository, projectRepository, sessionRepository, adapter)

        service.syncServer("missing")

        assertNull(adapter.lastProjectsBaseUrl)
        assertTrue(adapter.sessionRequests.isEmpty())
        assertTrue(projectRepository.projects.isEmpty())
        assertTrue(sessionRepository.sessions.isEmpty())
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

private class FakeProjectRepository : ProjectRepository {
    val projects = linkedMapOf<String, LocalProjectInfo>()
    var insertCalls: Int = 0
    var updateCalls: Int = 0

    override fun observeProjects(serverId: String?): Flow<List<LocalProjectInfo>> {
        val id = serverId?.trim()?.ifBlank { null }
        val values = if (id == null) {
            projects.values.toList()
        } else {
            projects.values.filter { it.serverId == id }
        }
        return flowOf(values)
    }

    override suspend fun selectProject(id: String): LocalProjectInfo? {
        return projects[id]
    }

    override suspend fun insertProject(project: LocalProjectInfo) {
        insertCalls += 1
        projects[project.id] = project
    }

    override suspend fun updateProject(project: LocalProjectInfo) {
        updateCalls += 1
        projects[project.id] = project
    }

    override suspend fun deleteProject(id: String) {
        projects.remove(id)
    }
}

private class FakeSessionRepository : SessionRepository {
    val sessions = linkedMapOf<String, LocalSessionRecord>()
    var insertCalls: Int = 0
    var updateCalls: Int = 0

    override fun sessionsOfProject(projectKey: String): Flow<List<LocalSessionInfo>> {
        return flowOf(emptyList())
    }

    override fun observeStoredSessions(projectId: String?): Flow<List<LocalSessionRecord>> {
        val id = projectId?.trim()?.ifBlank { null }
        val values = if (id == null) {
            sessions.values.toList()
        } else {
            sessions.values.filter { it.projectId == id }
        }
        return flowOf(values)
    }

    override suspend fun selectStoredSession(id: String): LocalSessionRecord? {
        return sessions[id]
    }

    override suspend fun insertStoredSession(session: LocalSessionRecord) {
        insertCalls += 1
        sessions[session.id] = session
    }

    override suspend fun updateStoredSession(session: LocalSessionRecord) {
        updateCalls += 1
        sessions[session.id] = session
    }

    override suspend fun deleteStoredSession(id: String) {
        sessions.remove(id)
    }
}

private class FakeOpenCodeServerAdapter : OpenCodeServerAdapter {
    var lastProjectsBaseUrl: String? = null
    var projects: List<OpenCodeProject> = emptyList()
    var sessionsByPath: Map<String, List<OpenCodeSession>> = emptyMap()
    val sessionRequests = mutableListOf<String>()

    override suspend fun healthCheckWithUrl(baseUrl: String): OpenCodeHealthCheck {
        return OpenCodeHealthCheck(healthy = true, version = "1.0.0")
    }

    override suspend fun allProjects(baseUrl: String): List<OpenCodeProject> {
        lastProjectsBaseUrl = baseUrl
        return projects
    }

    override suspend fun allSessionsOfAGivenProject(baseUrl: String, path: String): List<OpenCodeSession> {
        sessionRequests += "$baseUrl|$path"
        return sessionsByPath[path].orEmpty()
    }
}
