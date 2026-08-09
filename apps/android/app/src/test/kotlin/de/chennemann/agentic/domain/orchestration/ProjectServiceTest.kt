package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.DispatchResult
import de.chennemann.agentic.t3.contract.EnvironmentClientConfig
import de.chennemann.agentic.t3.contract.OrchestrationProject
import de.chennemann.agentic.t3.contract.OrchestrationShellSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProjectServiceTest {
    @Test
    fun `removal completes after projected disappearance and returns confirmed fallback`() = runTest {
        val repository = ProjectRepository(
            listOf(project("remove-me", "/srv/remove"), project("fallback", "/srv/fallback")),
        )
        val dispatcher = ConfirmingProjectDispatcher(repository)

        val fallback = ProjectService(repository, dispatcher).remove("remove-me")

        val command = dispatcher.command as ClientOrchestrationCommand.DeleteProject
        assertEquals("remove-me", command.projectId)
        assertEquals(false, command.force)
        assertEquals("fallback", fallback)
        assertEquals(listOf("fallback"), repository.shell.value.value?.projects?.map { it.id })
    }

    @Test
    fun `removal rejection preserves project for confirmed retry`() = runTest {
        val repository = ProjectRepository(listOf(project("project-1", "/srv/one")))
        val dispatcher = ConfirmingProjectDispatcher(repository, fail = true)

        val failure = runCatching { ProjectService(repository, dispatcher).remove("project-1") }.exceptionOrNull()

        assertInstanceOf(IllegalStateException::class.java, failure)
        assertEquals(listOf("project-1"), repository.shell.value.value?.projects?.map { it.id })
        assertEquals("project-1", (dispatcher.command as ClientOrchestrationCommand.DeleteProject).projectId)
    }

    @Test
    fun `removal rejects project outside current environment without dispatch`() = runTest {
        val repository = ProjectRepository(listOf(project("current", "/srv/current")))
        val dispatcher = ConfirmingProjectDispatcher(repository)

        val failure = runCatching { ProjectService(repository, dispatcher).remove("other-environment") }.exceptionOrNull()

        assertInstanceOf(IllegalArgumentException::class.java, failure)
        assertEquals(null, dispatcher.command)
        assertEquals(listOf("current"), repository.shell.value.value?.projects?.map { it.id })
    }

    @Test
    fun `rename completes only after server projection confirms the new title`() = runTest {
        val repository = ProjectRepository(listOf(project("project-1", "/srv/one", "Previous name")))
        val dispatcher = ConfirmingProjectDispatcher(repository)

        ProjectService(repository, dispatcher).rename("project-1", "  Provider neutral   name ")

        val command = dispatcher.command as ClientOrchestrationCommand.UpdateProjectMetadata
        assertEquals("project-1", command.projectId)
        assertEquals("Provider neutral name", command.title)
        assertEquals("Provider neutral name", repository.shell.value.value?.projects?.single()?.title)
    }

    @Test
    fun `invalid rename does not dispatch or change the prior title`() = runTest {
        val repository = ProjectRepository(listOf(project("project-1", "/srv/one", "Previous name")))
        val dispatcher = ConfirmingProjectDispatcher(repository)

        val failure = runCatching { ProjectService(repository, dispatcher).rename("project-1", "   ") }.exceptionOrNull()

        assertInstanceOf(IllegalArgumentException::class.java, failure)
        assertEquals(null, dispatcher.command)
        assertEquals("Previous name", repository.shell.value.value?.projects?.single()?.title)
    }

    @Test
    fun `rename rejection preserves previous projected name for retry`() = runTest {
        val repository = ProjectRepository(listOf(project("project-1", "/srv/one", "Previous name")))
        val dispatcher = ConfirmingProjectDispatcher(repository, fail = true)

        val failure = runCatching {
            ProjectService(repository, dispatcher).rename("project-1", "Retry name")
        }.exceptionOrNull()

        assertInstanceOf(IllegalStateException::class.java, failure)
        assertEquals("Previous name", repository.shell.value.value?.projects?.single()?.title)
        assertEquals("project-1", (dispatcher.command as ClientOrchestrationCommand.UpdateProjectMetadata).projectId)
    }

    @Test
    fun `rename rejects a project outside the current environment inventory`() = runTest {
        val repository = ProjectRepository(listOf(project("current-project", "/srv/current")))
        val dispatcher = ConfirmingProjectDispatcher(repository)

        val failure = runCatching {
            ProjectService(repository, dispatcher).rename("other-environment-project", "Wrong target")
        }.exceptionOrNull()

        assertInstanceOf(IllegalArgumentException::class.java, failure)
        assertEquals(null, dispatcher.command)
        assertEquals("current-project", repository.shell.value.value?.projects?.single()?.id)
    }

    @Test
    fun `server-confirmed repository project enters observable inventory`() = runTest {
        val repository = ProjectRepository()
        val dispatcher = ConfirmingProjectDispatcher(repository)
        val service = ProjectService(repository, dispatcher)

        val projectId = service.create(" https://example.test/team/provider-neutral.git ")

        val command = dispatcher.command as ClientOrchestrationCommand.CreateProject
        assertEquals(projectId, command.projectId)
        assertEquals("provider-neutral", command.title)
        assertEquals("https://example.test/team/provider-neutral.git", command.workspaceRoot)
        assertEquals(projectId, repository.shell.value.value?.projects?.single()?.id)
    }

    @Test
    fun `invalid input is rejected before dispatch and existing inventory is preserved`() = runTest {
        val existing = project("existing", "/srv/existing")
        val repository = ProjectRepository(listOf(existing))
        val dispatcher = ConfirmingProjectDispatcher(repository)
        val service = ProjectService(repository, dispatcher)

        val failure = runCatching { service.create("relative/path") }.exceptionOrNull()

        assertInstanceOf(IllegalArgumentException::class.java, failure)
        assertEquals(null, dispatcher.command)
        assertEquals(listOf(existing), repository.shell.value.value?.projects)
    }

    @Test
    fun `dispatch failure leaves prior inventory unchanged`() = runTest {
        val existing = project("existing", "/srv/existing")
        val repository = ProjectRepository(listOf(existing))
        val dispatcher = ConfirmingProjectDispatcher(repository, fail = true)

        val failure = runCatching { ProjectService(repository, dispatcher).create("/srv/new") }.exceptionOrNull()

        assertInstanceOf(IllegalStateException::class.java, failure)
        assertEquals(listOf(existing), repository.shell.value.value?.projects)
    }

    @Test
    fun `absolute server paths and common repository URLs validate provider-neutrally`() {
        assertEquals("/srv/code", validateProjectSource(" /srv/code "))
        assertEquals("C:\\code\\repo", validateProjectSource("C:\\code\\repo"))
        assertEquals("git@example.test:team/repo.git", validateProjectSource("git@example.test:team/repo.git"))
        assertTrue(validateProjectSource("ssh://example.test/repo.git").startsWith("ssh://"))
    }
}

private class ConfirmingProjectDispatcher(
    private val repository: ProjectRepository,
    private val fail: Boolean = false,
) : CommandDispatcher {
    var command: ClientOrchestrationCommand? = null

    override suspend fun dispatch(command: ClientOrchestrationCommand): DispatchResult {
        this.command = command
        if (fail) error("Server rejected project")
        when (command) {
            is ClientOrchestrationCommand.CreateProject -> {
                repository.confirm(project(command.projectId, command.workspaceRoot, command.title))
            }
            is ClientOrchestrationCommand.UpdateProjectMetadata -> {
                repository.rename(command.projectId, requireNotNull(command.title))
            }
            is ClientOrchestrationCommand.DeleteProject -> repository.remove(command.projectId)
            else -> error("Unexpected command")
        }
        return DispatchResult(1)
    }
}

private class ProjectRepository(projects: List<OrchestrationProject> = emptyList()) : OrchestrationRepository {
    override val clientConfig = MutableStateFlow(ProjectionState<EnvironmentClientConfig>())
    override val shell = MutableStateFlow(
        ProjectionState(
            value = OrchestrationShellSnapshot(projects, emptyList(), 0, "2026-08-06T00:00:00Z"),
            sequence = 0,
            source = ProjectionSource.LIVE,
            synchronized = true,
        ),
    )
    override val focusedThread = MutableStateFlow(ProjectionState<OrchestrationThreadDetailSnapshot>())
    override val focusedThreadId = MutableStateFlow<String?>(null)
    override val selectedProjectId = MutableStateFlow<String?>(null)

    fun confirm(project: OrchestrationProject) {
        shell.value = shell.value.copy(
            value = shell.value.value?.copy(projects = shell.value.value!!.projects + project),
        )
    }

    fun rename(projectId: String, title: String) {
        shell.value = shell.value.copy(
            value = shell.value.value?.copy(
                projects = shell.value.value!!.projects.map {
                    if (it.id == projectId) it.copy(title = title) else it
                },
            ),
        )
    }

    fun remove(projectId: String) {
        shell.value = shell.value.copy(
            value = shell.value.value?.copy(
                projects = shell.value.value!!.projects.filterNot { it.id == projectId },
            ),
        )
    }

    override suspend fun loadCached(environmentId: String) = Unit
    override suspend fun setClientConfig(environmentId: String, config: EnvironmentClientConfig, source: ProjectionSource) = Unit
    override suspend fun setShellSnapshot(environmentId: String, snapshot: OrchestrationShellSnapshot, source: ProjectionSource) = Unit
    override suspend fun applyShellItem(environmentId: String, item: OrchestrationShellStreamItem) = Reduction.Ignored(shell.value)
    override suspend fun focusThread(environmentId: String, threadId: String?) = Unit
    override suspend fun selectProject(environmentId: String, projectId: String?) = Unit
    override suspend fun setThreadSnapshot(
        environmentId: String,
        snapshot: OrchestrationThreadDetailSnapshot,
        source: ProjectionSource,
    ) = Unit
    override suspend fun applyThreadItem(environmentId: String, threadId: String, item: OrchestrationThreadStreamItem) =
        Reduction.Ignored(focusedThread.value)
    override suspend fun clearEnvironment(environmentId: String) = Unit
}

private fun project(id: String, root: String, title: String = id) = OrchestrationProject(
    id = id,
    title = title,
    workspaceRoot = root,
    createdAt = "2026-08-06T00:00:00Z",
    updatedAt = "2026-08-06T00:00:00Z",
)
