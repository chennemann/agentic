package de.chennemann.opencode.mobile.domain.v2.projects

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
        val service = DefaultProjectService(repository)

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
        val service = DefaultProjectService(repository)

        val toggled = service.togglePinnedById(" p1 ")

        assertTrue(toggled)
        assertEquals(true, repository.projects["p1"]?.pinned)
        assertEquals(listOf("p1"), repository.updateCalls.map { it.id })
    }

    @Test
    fun togglePinnedByIdReturnsFalseForMissingOrBlankId() = runTest {
        val repository = FakeProjectRepository()
        val service = DefaultProjectService(repository)

        assertFalse(service.togglePinnedById("   "))
        assertFalse(service.togglePinnedById("missing"))
        assertTrue(repository.updateCalls.isEmpty())
    }
}

private class FakeProjectRepository(
    initial: LinkedHashMap<String, LocalProjectInfo> = linkedMapOf(),
) : ProjectRepository {
    val projects = initial
    val flow = flowOf(projects.values.toList())
    val updateCalls = mutableListOf<LocalProjectInfo>()
    var lastObservedServerId: String? = null

    override fun observeProjects(serverId: String?): Flow<List<LocalProjectInfo>> {
        lastObservedServerId = serverId
        return flow
    }

    override suspend fun selectProject(id: String): LocalProjectInfo? {
        return projects[id]
    }

    override suspend fun insertProject(project: LocalProjectInfo) {
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
