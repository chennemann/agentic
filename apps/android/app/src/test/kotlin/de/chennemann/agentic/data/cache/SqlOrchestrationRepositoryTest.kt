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
import de.chennemann.agentic.t3.contract.T3Json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SqlOrchestrationRepositoryTest {
    @Test
    fun `older history persists its cursor and rejects replaced or differently focused snapshots`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val database = AgenticDb(driver)
        val repository = SqlOrchestrationRepository(database, StandardTestDispatcher(testScheduler), backgroundScope)
        repository.loadCached(EnvironmentId)
        repository.focusThread(EnvironmentId, ThreadId)
        val page = de.chennemann.agentic.t3.contract.OrchestrationThreadDetailPage("cursor", true, 10, 10)
        val initial = threadSnapshot().copy(snapshotSequence = 10, page = page)
        repository.setThreadSnapshot(EnvironmentId, initial, ProjectionSource.LIVE)
        val generation = repository.focusedThread.value.historyGeneration
        val older = initial.copy(page = page.copy(beforeCursor = "older-cursor"))
        assertEquals(false, repository.mergeThreadHistory("other-environment", older, "cursor", generation))
        assertEquals(true, repository.mergeThreadHistory(EnvironmentId, older, "cursor", generation))
        val restarted = SqlOrchestrationRepository(database, StandardTestDispatcher(testScheduler), backgroundScope)
        restarted.loadCached(EnvironmentId)
        assertEquals("older-cursor", restarted.focusedThread.value.value?.page?.beforeCursor)
        repository.setThreadSnapshot(EnvironmentId, initial, ProjectionSource.LIVE)
        assertEquals(false, repository.mergeThreadHistory(EnvironmentId, older, "cursor", generation))
        repository.focusThread(EnvironmentId, "different-thread")
        assertEquals(false, repository.mergeThreadHistory(EnvironmentId, older, "cursor", generation))
        driver.close()
    }

    @Test
    fun `shell metadata refreshes the conversation without changing its content cursor`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val database = AgenticDb(driver)
        val repository = SqlOrchestrationRepository(database, StandardTestDispatcher(testScheduler), backgroundScope)
        repository.loadCached(EnvironmentId)
        repository.setShellSnapshot(EnvironmentId, shellSnapshot(threads = listOf(threadShell())), ProjectionSource.LIVE)
        repository.focusThread(EnvironmentId, ThreadId)
        val message = de.chennemann.agentic.t3.contract.OrchestrationMessage("live", "turn", "assistant", "streaming", true, Timestamp, Timestamp)
        val initial = threadSnapshot().let { it.copy(thread = it.thread.copy(messages = listOf(message))) }
        repository.setThreadSnapshot(EnvironmentId, initial, ProjectionSource.LIVE)
        repository.applyShellItem(EnvironmentId, OrchestrationShellStreamItem.ThreadUpserted(2, threadShell().copy(title = "New title")))
        assertEquals("New title", repository.focusedThread.value.value?.thread?.title)
        assertEquals(listOf(message), repository.focusedThread.value.value?.thread?.messages)
        assertEquals(1, repository.focusedThread.value.sequence)
        driver.close()
    }

    @Test
    fun `older shell metadata cannot replace a newer detail snapshot and restores consistently`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val database = AgenticDb(driver)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = SqlOrchestrationRepository(database, dispatcher, backgroundScope)
        repository.loadCached(EnvironmentId)
        repository.setShellSnapshot(EnvironmentId, shellSnapshot(threads = listOf(threadShell())), ProjectionSource.LIVE)
        repository.focusThread(EnvironmentId, ThreadId)
        val initial = threadSnapshot().let { it.copy(thread = it.thread.copy(title = "Newer detail")) }
        repository.setThreadSnapshot(EnvironmentId, initial, ProjectionSource.LIVE)
        assertEquals("Newer detail", repository.focusedThread.value.value?.thread?.title)

        repository.applyShellItem(EnvironmentId, OrchestrationShellStreamItem.ThreadUpserted(2, threadShell().copy(title = "Latest shell")))
        repository.setThreadSnapshot(EnvironmentId, initial, ProjectionSource.LIVE)
        val restarted = SqlOrchestrationRepository(database, dispatcher, backgroundScope)
        restarted.loadCached(EnvironmentId)
        assertEquals("Latest shell", restarted.focusedThread.value.value?.thread?.title)
        assertEquals(1, restarted.focusedThread.value.sequence)
        driver.close()
    }
    @Test
    fun `switching between cached threads never emits an empty focused projection`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val database = AgenticDb(driver)
        val repository = SqlOrchestrationRepository(
            database = database,
            dispatcher = StandardTestDispatcher(testScheduler),
            scope = backgroundScope,
        )
        repository.loadCached(EnvironmentId)
        repository.focusThread(EnvironmentId, "thread-one")
        repository.setThreadSnapshot(EnvironmentId, threadSnapshot("thread-one"), ProjectionSource.LIVE)
        repository.focusThread(EnvironmentId, "thread-two")
        repository.setThreadSnapshot(EnvironmentId, threadSnapshot("thread-two"), ProjectionSource.LIVE)
        repository.focusThread(EnvironmentId, "thread-one")
        val observedThreadIds = mutableListOf<String?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.focusedThread.collect { observedThreadIds += it.value?.thread?.id }
        }
        observedThreadIds.clear()

        repository.focusThread(EnvironmentId, "thread-two")
        runCurrent()

        assertEquals(listOf("thread-two"), observedThreadIds)
        assertEquals("thread-two", repository.focusedThread.value.value?.thread?.id)
        driver.close()
    }

    @Test
    fun `reloading the active environment does not invalidate the restored thread`() = runTest {
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
        repository.setThreadSnapshot(EnvironmentId, threadSnapshot(), ProjectionSource.LIVE)
        val observedThreadIds = mutableListOf<String?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.focusedThreadId.collect(observedThreadIds::add)
        }
        observedThreadIds.clear()

        repository.loadCached(EnvironmentId)
        runCurrent()

        assertEquals(emptyList<String?>(), observedThreadIds)
        assertEquals(ThreadId, repository.focusedThreadId.value)
        assertEquals(ThreadId, repository.focusedThread.value.value?.thread?.id)
        driver.close()
    }

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

    @Test
    fun `oversized persisted thread projection is evicted instead of restored`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val database = AgenticDb(driver)
        database.agenticT3Queries.upsertPreference(
            EnvironmentId,
            "last-thread",
            ThreadId,
        )
        database.agenticT3Queries.upsertProjection(
            EnvironmentId,
            "thread",
            ThreadId,
            1,
            1,
            T3Json.encodeToString(oversizedThreadSnapshot()),
            1,
        )

        val repository = SqlOrchestrationRepository(
            database = database,
            dispatcher = StandardTestDispatcher(testScheduler),
            scope = backgroundScope,
        )
        repository.loadCached(EnvironmentId)

        assertEquals(ThreadId, repository.focusedThreadId.value)
        assertNull(repository.focusedThread.value.value)
        assertNull(cachedThreadPayload(database))
        driver.close()
    }

    @Test
    fun `oversized live thread projection is not persisted`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val database = AgenticDb(driver)
        val repository = SqlOrchestrationRepository(
            database = database,
            dispatcher = StandardTestDispatcher(testScheduler),
            scope = backgroundScope,
        )
        repository.loadCached(EnvironmentId)
        repository.focusThread(EnvironmentId, ThreadId)

        repository.setThreadSnapshot(
            environmentId = EnvironmentId,
            snapshot = oversizedThreadSnapshot(),
            source = ProjectionSource.LIVE,
        )

        assertNull(cachedThreadPayload(database))
        driver.close()
    }

    private fun cachedShellSequence(database: AgenticDb): Long =
        database.agenticT3Queries.selectProjection<Long>(
            EnvironmentId,
            "shell",
            "current",
        ) { sequence, _ -> sequence ?: -1 }.executeAsOne()

    private fun cachedThreadPayload(database: AgenticDb): String? =
        database.agenticT3Queries.selectProjection<String>(
            EnvironmentId,
            "thread",
            ThreadId,
        ) { _, payload -> payload }.executeAsOneOrNull()

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

    private fun threadSnapshot(
        id: String = ThreadId,
        checkpoints: List<JsonElement> = emptyList(),
    ) = OrchestrationThreadDetailSnapshot(
        snapshotSequence = 1,
        thread = OrchestrationThreadDetail(
            id = id,
            projectId = ProjectId,
            title = "Thread",
            createdAt = Timestamp,
            updatedAt = Timestamp,
            checkpoints = checkpoints,
        ),
    )

    private fun oversizedThreadSnapshot() = threadSnapshot(
        checkpoints = listOf(JsonPrimitive("x".repeat(OversizedPayloadCharacters))),
    )

    private companion object {
        const val EnvironmentId = "environment"
        const val ProjectId = "project"
        const val ThreadId = "thread"
        const val Timestamp = "2026-01-01T00:00:00Z"
        const val OversizedPayloadCharacters = 1_600_000
    }
}
