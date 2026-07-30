package de.chennemann.agentic.data.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.domain.orchestration.ProjectionSource
import de.chennemann.agentic.t3.contract.OrchestrationProject
import de.chennemann.agentic.t3.contract.OrchestrationShellSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetail
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationThreadShell
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SqlOrchestrationRepositoryTest {
    @Test
    fun `last focused thread and cached detail restore after repository restart`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val database = AgenticDb(driver)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = SqlOrchestrationRepository(database, dispatcher, backgroundScope)
        repository.loadCached(EnvironmentId)
        repository.setShellSnapshot(
            environmentId = EnvironmentId,
            snapshot = shellSnapshot(
                projects = listOf(project("Project")),
                threads = listOf(threadShell()),
            ),
            source = ProjectionSource.LIVE,
        )
        repository.focusThread(EnvironmentId, ThreadId)
        repository.setThreadSnapshot(
            environmentId = EnvironmentId,
            snapshot = threadSnapshot(),
            source = ProjectionSource.LIVE,
        )

        val restored = SqlOrchestrationRepository(database, dispatcher, backgroundScope)
        restored.loadCached(EnvironmentId)

        assertEquals(ThreadId, restored.focusedThreadId.value)
        assertEquals(ProjectId, restored.selectedProjectId.value)
        assertEquals(ThreadId, restored.focusedThread.value.value?.thread?.id)
        driver.close()
    }

    @Test
    fun `live shell removes a restored thread that no longer exists`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val database = AgenticDb(driver)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = SqlOrchestrationRepository(database, dispatcher, backgroundScope)
        repository.loadCached(EnvironmentId)
        repository.setShellSnapshot(
            environmentId = EnvironmentId,
            snapshot = shellSnapshot(
                projects = listOf(project("Project")),
                threads = listOf(threadShell()),
            ),
            source = ProjectionSource.LIVE,
        )
        repository.focusThread(EnvironmentId, ThreadId)

        val restored = SqlOrchestrationRepository(database, dispatcher, backgroundScope)
        restored.loadCached(EnvironmentId)
        restored.setShellSnapshot(
            environmentId = EnvironmentId,
            snapshot = shellSnapshot(),
            source = ProjectionSource.LIVE,
        )

        assertEquals(null, restored.focusedThreadId.value)
        val relaunched = SqlOrchestrationRepository(database, dispatcher, backgroundScope)
        relaunched.loadCached(EnvironmentId)
        assertEquals(null, relaunched.focusedThreadId.value)
        driver.close()
    }

    @Test
    fun `stream projection is immediate while cache writes are coalesced`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val database = AgenticDb(driver)
        val repository = SqlOrchestrationRepository(
            database = database,
            dispatcher = StandardTestDispatcher(testScheduler),
            scope = backgroundScope,
        )
        repository.loadCached(EnvironmentId)
        repository.setShellSnapshot(
            environmentId = EnvironmentId,
            snapshot = shellSnapshot(),
            source = ProjectionSource.LIVE,
        )

        repository.applyShellItem(
            EnvironmentId,
            OrchestrationShellStreamItem.ProjectUpserted(
                sequence = 10,
                project = project("First"),
            ),
        )
        repository.applyShellItem(
            EnvironmentId,
            OrchestrationShellStreamItem.ProjectUpserted(
                sequence = 20,
                project = project("Latest"),
            ),
        )

        assertEquals("Latest", repository.shell.value.value?.projects?.single()?.title)
        assertEquals(0, cachedShellSequence(database))

        advanceTimeBy(499)
        runCurrent()
        assertEquals(0, cachedShellSequence(database))

        advanceTimeBy(1)
        runCurrent()
        assertEquals(20, cachedShellSequence(database))
        driver.close()
    }

    private fun cachedShellSequence(database: AgenticDb): Long =
        database.agenticT3Queries.selectProjection<Long>(
            EnvironmentId,
            "shell",
            "current",
        ) { _, _, _, _, sequence, _, _ -> sequence ?: -1 }.executeAsOne()

    private fun shellSnapshot(
        projects: List<OrchestrationProject> = emptyList(),
        threads: List<OrchestrationThreadShell> = emptyList(),
    ) = OrchestrationShellSnapshot(
        projects = projects,
        threads = threads,
        snapshotSequence = 0,
        updatedAt = Timestamp,
    )

    private fun project(title: String) = OrchestrationProject(
        id = ProjectId,
        title = title,
        workspaceRoot = "/workspace",
        createdAt = Timestamp,
        updatedAt = Timestamp,
    )

    private fun threadShell() = OrchestrationThreadShell(
        id = ThreadId,
        projectId = ProjectId,
        title = "Thread",
        createdAt = Timestamp,
        updatedAt = Timestamp,
    )

    private fun threadSnapshot() = OrchestrationThreadDetailSnapshot(
        snapshotSequence = 1,
        thread = OrchestrationThreadDetail(
            id = ThreadId,
            projectId = ProjectId,
            title = "Thread",
            createdAt = Timestamp,
            updatedAt = Timestamp,
        ),
    )

    private companion object {
        const val EnvironmentId = "environment"
        const val ProjectId = "project"
        const val ThreadId = "thread"
        const val Timestamp = "2026-01-01T00:00:00Z"
    }
}
