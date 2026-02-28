package de.chennemann.opencode.mobile.domain.v2.session

import de.chennemann.opencode.mobile.domain.v2.OpenCodeHealthCheck
import de.chennemann.opencode.mobile.domain.v2.OpenCodeProject
import de.chennemann.opencode.mobile.domain.v2.OpenCodeServerAdapter
import de.chennemann.opencode.mobile.domain.v2.OpenCodeSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionServiceTest {
    @Test
    fun sessionsOfProjectDelegatesToRepository() {
        val repository = FakeSessionRepository()
        val adapter = FakeOpenCodeServerAdapter()
        val service = DefaultSessionService(repository, adapter)

        val observed = service.sessionsOfProject("project-1")

        assertSame(repository.projectSessionsFlow, observed)
        assertEquals("project-1", repository.lastProjectKey)
    }

    @Test
    fun syncSessionsOfProjectPersistsValidRows() = runTest {
        val repository = FakeSessionRepository()
        val adapter = FakeOpenCodeServerAdapter().apply {
            sessionsByPath["/repo/a"] = listOf(
                OpenCodeSession(
                    id = "s1",
                    projectId = "p1",
                    directory = "/repo/a",
                    title = "Session A",
                    version = "1",
                ),
                OpenCodeSession(
                    id = "s2",
                    projectId = "p1",
                    directory = "/repo/a",
                    title = "   ",
                    version = "1",
                ),
                OpenCodeSession(
                    id = "   ",
                    projectId = "p1",
                    directory = "/repo/a",
                    title = "Invalid",
                    version = "1",
                ),
                OpenCodeSession(
                    id = "s3",
                    projectId = "p1",
                    directory = "   ",
                    title = "Invalid",
                    version = "1",
                ),
            )
        }
        val service = DefaultSessionService(repository, adapter)

        service.syncSessionsOfProject(" p1 ", " /repo/a ", " https://example.test ")

        assertEquals(listOf("https://example.test|/repo/a"), adapter.sessionRequests)
        assertEquals(2, repository.insertCalls)
        assertEquals(0, repository.updateCalls.size)
        assertEquals(
            LocalSessionRecord(
                id = "s1",
                projectId = "p1",
                title = "Session A",
                path = "/repo/a",
                pinned = false,
            ),
            repository.stored["s1"],
        )
        assertEquals(
            LocalSessionRecord(
                id = "s2",
                projectId = "p1",
                title = "/repo/a",
                path = "/repo/a",
                pinned = false,
            ),
            repository.stored["s2"],
        )
    }

    @Test
    fun syncSessionsOfProjectKeepsPinnedOnUpdate() = runTest {
        val repository = FakeSessionRepository().apply {
            stored["s1"] = LocalSessionRecord(
                id = "s1",
                projectId = "p1",
                title = "Old",
                path = "/repo/old",
                pinned = true,
            )
        }
        val adapter = FakeOpenCodeServerAdapter().apply {
            sessionsByPath["/repo/new"] = listOf(
                OpenCodeSession(
                    id = "s1",
                    projectId = "p1",
                    directory = "/repo/new",
                    title = "New",
                    version = "2",
                )
            )
        }
        val service = DefaultSessionService(repository, adapter)

        service.syncSessionsOfProject("p1", "/repo/new", "https://example.test")

        assertEquals(0, repository.insertCalls)
        assertEquals(1, repository.updateCalls.size)
        assertEquals(true, repository.stored["s1"]?.pinned)
        assertEquals("/repo/new", repository.stored["s1"]?.path)
        assertEquals("New", repository.stored["s1"]?.title)
    }

    @Test
    fun syncSessionsOfProjectSkipsBlankInputs() = runTest {
        val repository = FakeSessionRepository()
        val adapter = FakeOpenCodeServerAdapter()
        val service = DefaultSessionService(repository, adapter)

        service.syncSessionsOfProject("   ", "/repo/a", "https://example.test")
        service.syncSessionsOfProject("p1", "   ", "https://example.test")
        service.syncSessionsOfProject("p1", "/repo/a", "   ")

        assertTrue(adapter.sessionRequests.isEmpty())
        assertEquals(0, repository.insertCalls)
        assertTrue(repository.updateCalls.isEmpty())
    }
}

private class FakeSessionRepository : SessionRepository {
    val projectSessionsFlow = flowOf(emptyList<LocalSessionInfo>())
    val stored = linkedMapOf<String, LocalSessionRecord>()
    var lastProjectKey: String? = null
    var insertCalls: Int = 0
    val updateCalls = mutableListOf<LocalSessionRecord>()

    override fun sessionsOfProject(projectKey: String): Flow<List<LocalSessionInfo>> {
        lastProjectKey = projectKey
        return projectSessionsFlow
    }

    override fun observeStoredSessions(projectId: String?): Flow<List<LocalSessionRecord>> {
        return flowOf(stored.values.toList())
    }

    override suspend fun selectStoredSession(id: String): LocalSessionRecord? {
        return stored[id]
    }

    override suspend fun insertStoredSession(session: LocalSessionRecord) {
        insertCalls += 1
        stored[session.id] = session
    }

    override suspend fun updateStoredSession(session: LocalSessionRecord) {
        updateCalls += session
        stored[session.id] = session
    }

    override suspend fun deleteStoredSession(id: String) {
        stored.remove(id)
    }
}

private class FakeOpenCodeServerAdapter : OpenCodeServerAdapter {
    val sessionsByPath = linkedMapOf<String, List<OpenCodeSession>>()
    val sessionRequests = mutableListOf<String>()

    override suspend fun healthCheckWithUrl(baseUrl: String): OpenCodeHealthCheck {
        return OpenCodeHealthCheck(healthy = true, version = "1.0.0")
    }

    override suspend fun allProjects(baseUrl: String): List<OpenCodeProject> {
        return emptyList()
    }

    override suspend fun allSessionsOfAGivenProject(baseUrl: String, path: String): List<OpenCodeSession> {
        sessionRequests += "$baseUrl|$path"
        return sessionsByPath[path].orEmpty()
    }
}
