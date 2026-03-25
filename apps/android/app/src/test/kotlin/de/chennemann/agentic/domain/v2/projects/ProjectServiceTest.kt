package de.chennemann.agentic.domain.projects

import de.chennemann.agentic.domain.remote.ServerProject
import de.chennemann.agentic.domain.fixtures.DomainV2TestEnvironment
import de.chennemann.agentic.domain.fixtures.ServerAdapterFixture
import de.chennemann.agentic.domain.fixtures.connectedServerFixture
import de.chennemann.agentic.domain.fixtures.domainV2TestEnvironment
import de.chennemann.agentic.domain.fixtures.localProjectFixture
import de.chennemann.agentic.domain.servers.DefaultServerService
import de.chennemann.agentic.domain.servers.ServerInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectServiceTest {
    @Test
    fun projectsFiltersByConnectedServer() = environmentTest {
        seedServer(
            connectedServerFixture(
                id = "server-1",
                url = "https://example.test",
                lastConnectedAt = 1L,
            )
        )
        seedServer(
            connectedServerFixture(
                id = "server-2",
                url = "https://other.test",
                lastConnectedAt = 0L,
            )
        )
        assertTrue(serverService.connect("https://example.test"))
        seedProject(
            localProjectFixture(
                id = "p1",
                serverId = "server-1",
                name = "Main",
                path = "/repo/main",
            )
        )
        seedProject(
            localProjectFixture(
                id = "p2",
                serverId = "server-2",
                name = "Other",
                path = "/repo/other",
            )
        )

        val observed = service.projects.first()

        assertEquals(listOf("p1"), observed.map { it.id })
    }

    @Test
    fun togglePinnedByIdFlipsPinnedAndPersists() = environmentTest {
        seedServer(
            connectedServerFixture(
                id = "server-1",
                url = "https://example.test",
                lastConnectedAt = 1L,
            )
        )
        seedProject(
            localProjectFixture(
                id = "p1",
                serverId = "server-1",
                name = "Main",
                path = "/repo/main",
                pinned = false,
            )
        )

        val toggled = service.togglePinnedById(" p1 ")
        val stored = repository.selectProject("p1")

        assertTrue(toggled)
        assertEquals(true, stored?.pinned)
    }

    @Test
    fun togglePinnedByIdReturnsFalseForMissingOrBlankId() = environmentTest {
        assertFalse(service.togglePinnedById("   "))
        assertFalse(service.togglePinnedById("missing"))
        assertTrue(persistedProjects().isEmpty())
    }

    @Test
    fun removeByIdDeletesProject() = environmentTest {
        seedServer(
            connectedServerFixture(
                id = "server-1",
                url = "https://example.test",
                lastConnectedAt = 1L,
            )
        )
        seedProject(
            localProjectFixture(
                id = "p1",
                serverId = "server-1",
                name = "Main",
                path = "/repo/main",
                pinned = false,
            )
        )

        val removed = service.removeById(" p1 ")

        assertTrue(removed)
        assertTrue(persistedProjects().isEmpty())
    }

    @Test
    fun removeByIdReturnsFalseForMissingOrBlankId() = environmentTest {
        assertFalse(service.removeById("   "))
        assertFalse(service.removeById("missing"))
        assertTrue(persistedProjects().isEmpty())
    }

    @Test
    fun syncServerProjectsPersistsValidRowsAndReturnsSyncedProjects() = environmentTest {
        seedServer(
            connectedServerFixture(
                id = "server-1",
                url = "https://example.test",
                lastConnectedAt = 1L,
            )
        )
        adapter.givenProjects(
            url = "https://example.test",
            projects = listOf(
                ServerProject(id = "p1", worktree = "/repo/a", name = "Alpha", sandboxes = emptyList()),
                ServerProject(id = "p2", worktree = "/repo/b", name = "   ", sandboxes = emptyList()),
                ServerProject(id = "   ", worktree = "/repo/c", name = "Invalid", sandboxes = emptyList()),
                ServerProject(id = "p3", worktree = "   ", name = "Invalid", sandboxes = emptyList()),
            )
        )

        val synced = service.syncServerProjects(" server-1 ", " https://example.test ")
        val persisted = persistedProjects("server-1")

        assertEquals(listOf("https://example.test"), adapter.projectRequests)
        assertEquals(listOf("p1", "p2"), synced.map { it.id })
        assertEquals(2, persisted.size)
        assertEquals(
            LocalProjectInfo(
                id = "p1",
                serverId = "server-1",
                name = "Alpha",
                path = "/repo/a",
                pinned = false,
            ),
            repository.selectProject("p1"),
        )
        assertEquals(
            LocalProjectInfo(
                id = "p2",
                serverId = "server-1",
                name = "/repo/b",
                path = "/repo/b",
                pinned = false,
            ),
            repository.selectProject("p2"),
        )
    }

    @Test
    fun syncServerProjectsKeepsPinnedStateWhenUpdating() = environmentTest {
        seedServer(
            connectedServerFixture(
                id = "server-1",
                url = "https://example.test",
                lastConnectedAt = 1L,
            )
        )
        seedProject(
            localProjectFixture(
                id = "p1",
                serverId = "server-1",
                name = "Old",
                path = "/repo/old",
                pinned = true,
            )
        )
        adapter.givenProjects(
            url = "https://example.test",
            projects = listOf(
                ServerProject(id = "p1", worktree = "/repo/new", name = "New", sandboxes = emptyList()),
            )
        )

        val synced = service.syncServerProjects("server-1", "https://example.test")
        val stored = repository.selectProject("p1")
        val persisted = persistedProjects("server-1")

        assertEquals(listOf("p1"), synced.map { it.id })
        assertEquals(1, persisted.size)
        assertEquals(true, stored?.pinned)
        assertEquals("/repo/new", stored?.path)
        assertEquals("New", stored?.name)
    }
}

private fun environmentTest(
    adapter: ServerAdapterFixture = ServerAdapterFixture(),
    testBlock: suspend EnvironmentContext.() -> Unit,
) = runTest {
    val environment = domainV2TestEnvironment(
        dispatcher = StandardTestDispatcher(testScheduler),
        adapter = adapter,
    )

    try {
        EnvironmentContext(environment).testBlock()
    } finally {
        environment.close()
    }
}

private class EnvironmentContext(
    private val environment: DomainV2TestEnvironment,
) {
    val service: DefaultProjectService = environment.projectService
    val adapter: ServerAdapterFixture = environment.adapter
    val serverService: DefaultServerService = environment.serverService
    val repository: ProjectRepository = environment.projectRepository

    suspend fun seedServer(
        server: ServerInfo.ConnectedServerInfo = connectedServerFixture(),
    ): ServerInfo.ConnectedServerInfo {
        return environment.seedServer(server)
    }

    suspend fun seedProject(
        project: LocalProjectInfo = localProjectFixture(),
    ): LocalProjectInfo {
        return environment.seedProject(project)
    }

    suspend fun persistedProjects(serverId: String? = null): List<LocalProjectInfo> {
        return environment.allPersistedProjects(serverId)
    }
}
