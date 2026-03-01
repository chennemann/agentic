package de.chennemann.agentic.domain.v2.projects

import de.chennemann.agentic.domain.v2.OpenCodeHealthCheck
import de.chennemann.agentic.domain.v2.OpenCodeProject
import de.chennemann.agentic.domain.v2.OpenCodeServerAdapter
import de.chennemann.agentic.domain.v2.OpenCodeSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProjectServiceTest {
    @Test
    fun observeProjectsDelegatesToRepository() {
        val repository = FakeProjectRepository()
        val adapter = FakeOpenCodeServerAdapter()
        val service = DefaultProjectService(repository, adapter)

        val observed = service.observeProjects("server-1")

        assertSame(repository.flow, observed)
        assertEquals("server-1", repository.lastObservedServerId)
    }

    @Test
    fun togglePinnedByIdFlipsPinnedAndPersists() = runTest {
        val repository = FakeProjectRepository(
            initial = linkedMapOf(
                "p1" to LocalProjectInfo(
                    id = "p1",
                    serverId = "server-1",
                    name = "Main",
                    path = "/repo/main",
                    pinned = false,
                )
            )
        )
        val adapter = FakeOpenCodeServerAdapter()
        val service = DefaultProjectService(repository, adapter)

        val toggled = service.togglePinnedById(" p1 ")

        assertTrue(toggled)
        assertEquals(true, repository.projects["p1"]?.pinned)
        assertEquals(listOf("p1"), repository.updateCalls.map { it.id })
    }

    @Test
    fun togglePinnedByIdReturnsFalseForMissingOrBlankId() = runTest {
        val repository = FakeProjectRepository()
        val adapter = FakeOpenCodeServerAdapter()
        val service = DefaultProjectService(repository, adapter)

        assertFalse(service.togglePinnedById("   "))
        assertFalse(service.togglePinnedById("missing"))
        assertTrue(repository.updateCalls.isEmpty())
    }

    @Test
    fun syncServerProjectsPersistsValidRowsAndReturnsSyncedProjects() = runTest {
        val repository = FakeProjectRepository()
        val adapter = FakeOpenCodeServerAdapter().apply {
            projects = listOf(
                OpenCodeProject(id = "p1", worktree = "/repo/a", name = "Alpha", sandboxes = emptyList()),
                OpenCodeProject(id = "p2", worktree = "/repo/b", name = "   ", sandboxes = emptyList()),
                OpenCodeProject(id = "   ", worktree = "/repo/c", name = "Invalid", sandboxes = emptyList()),
                OpenCodeProject(id = "p3", worktree = "   ", name = "Invalid", sandboxes = emptyList()),
            )
        }
        val service = DefaultProjectService(repository, adapter)

        val synced = service.syncServerProjects(" server-1 ", " https://example.test ")

        assertEquals(listOf("https://example.test"), adapter.projectRequests)
        assertEquals(listOf("p1", "p2"), synced.map { it.id })
        assertEquals(2, repository.insertCalls)
        assertEquals(0, repository.updateCalls.size)
        assertEquals(
            LocalProjectInfo(
                id = "p1",
                serverId = "server-1",
                name = "Alpha",
                path = "/repo/a",
                pinned = false,
            ),
            repository.projects["p1"],
        )
        assertEquals(
            LocalProjectInfo(
                id = "p2",
                serverId = "server-1",
                name = "/repo/b",
                path = "/repo/b",
                pinned = false,
            ),
            repository.projects["p2"],
        )
    }

    @Test
    fun syncServerProjectsKeepsPinnedStateWhenUpdating() = runTest {
        val repository = FakeProjectRepository(
            initial = linkedMapOf(
                "p1" to LocalProjectInfo(
                    id = "p1",
                    serverId = "server-1",
                    name = "Old",
                    path = "/repo/old",
                    pinned = true,
                )
            )
        )
        val adapter = FakeOpenCodeServerAdapter().apply {
            projects = listOf(
                OpenCodeProject(id = "p1", worktree = "/repo/new", name = "New", sandboxes = emptyList()),
            )
        }
        val service = DefaultProjectService(repository, adapter)

        val synced = service.syncServerProjects("server-1", "https://example.test")

        assertEquals(listOf("p1"), synced.map { it.id })
        assertEquals(0, repository.insertCalls)
        assertEquals(1, repository.updateCalls.size)
        assertEquals(true, repository.projects["p1"]?.pinned)
        assertEquals("/repo/new", repository.projects["p1"]?.path)
        assertEquals("New", repository.projects["p1"]?.name)
    }
}

private class FakeProjectRepository(
    initial: LinkedHashMap<String, LocalProjectInfo> = linkedMapOf(),
) : ProjectRepository {
    val projects = initial
    val flow = flowOf(projects.values.toList())
    val updateCalls = mutableListOf<LocalProjectInfo>()
    var insertCalls: Int = 0
    var lastObservedServerId: String? = null

    override fun observeProjects(serverId: String?): Flow<List<LocalProjectInfo>> {
        lastObservedServerId = serverId
        return flow
    }

    override suspend fun selectProject(id: String): LocalProjectInfo? {
        return projects[id]
    }

    override suspend fun insertProject(project: LocalProjectInfo) {
        insertCalls += 1
        projects[project.id] = project
    }

    override suspend fun updateProject(project: LocalProjectInfo) {
        updateCalls += project
        projects[project.id] = project
    }

    override suspend fun deleteProject(id: String) {
        projects.remove(id)
    }
}

private class FakeOpenCodeServerAdapter : OpenCodeServerAdapter {
    var projects: List<OpenCodeProject> = emptyList()
    val projectRequests = mutableListOf<String>()

    override suspend fun healthCheckWithUrl(baseUrl: String): OpenCodeHealthCheck {
        return OpenCodeHealthCheck(healthy = true, version = "1.0.0")
    }

    override suspend fun allProjects(baseUrl: String): List<OpenCodeProject> {
        projectRequests += baseUrl
        return projects
    }

    override suspend fun allSessionsOfAGivenProject(baseUrl: String, path: String): List<OpenCodeSession> {
        return emptyList()
    }
}
