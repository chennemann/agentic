package de.chennemann.opencode.mobile.ui.logs

import de.chennemann.opencode.mobile.di.DispatcherProvider
import de.chennemann.opencode.mobile.domain.session.LogEntry
import de.chennemann.opencode.mobile.domain.session.LogFacet
import de.chennemann.opencode.mobile.domain.session.LogFilter
import de.chennemann.opencode.mobile.domain.session.LogLevel
import de.chennemann.opencode.mobile.domain.session.LogProjectOption
import de.chennemann.opencode.mobile.domain.session.LogSessionOption
import de.chennemann.opencode.mobile.domain.session.LogStoreGateway
import de.chennemann.opencode.mobile.domain.session.LogUnit
import de.chennemann.opencode.mobile.navigation.NavEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModelTest {
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun backRequestedEmitsNavigateBackAction() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val store = StubLogStore()
        val viewModel = LogsViewModel(store, lanes(main, worker))

        val nav = async { viewModel.nav.first() }
        advanceUntilIdle()
        viewModel.onEvent(LogsEvent.BackRequested)
        advanceUntilIdle()

        assertEquals(NavEvent.NavigateBack, nav.await())
    }

    @Test
    fun appliesFilterFromEntryAndRemovesSelectedFilter() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val store = StubLogStore()
        val viewModel = LogsViewModel(store, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }

        advanceUntilIdle()
        viewModel.onEvent(LogsEvent.FilterAppliedFromEntry(LogsFilterKey.unit, LogUnit.ui.key))
        viewModel.onEvent(LogsEvent.FilterAppliedFromEntry(LogsFilterKey.level, LogLevel.error.key))
        viewModel.onEvent(LogsEvent.FilterAppliedFromEntry(LogsFilterKey.event, "session.failed"))
        viewModel.onEvent(LogsEvent.FilterAppliedFromEntry(LogsFilterKey.project, "project-1"))
        viewModel.onEvent(LogsEvent.FilterAppliedFromEntry(LogsFilterKey.session, "session-1"))
        advanceUntilIdle()

        var value = viewModel.state.value
        assertEquals(LogUnit.ui, value.selectedUnit)
        assertEquals(LogLevel.error, value.selectedLevel)
        assertEquals("session.failed", value.selectedEvent)
        assertEquals("project-1", value.selectedProjectId)
        assertEquals("session-1", value.selectedSessionId)

        viewModel.onEvent(LogsEvent.FilterRemoved(LogsFilterKey.session))
        advanceUntilIdle()

        value = viewModel.state.value
        assertNull(value.selectedSessionId)
        collect.cancel()
    }

    @Test
    fun resetsFiltersWhilePreservingQuery() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val store = StubLogStore()
        val viewModel = LogsViewModel(store, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }

        advanceUntilIdle()
        viewModel.onEvent(LogsEvent.FromChanged(1234L))
        viewModel.onEvent(LogsEvent.UntilChanged(5678L))
        viewModel.onEvent(LogsEvent.UnitChanged(LogUnit.network))
        viewModel.onEvent(LogsEvent.QueryChanged("socket"))
        advanceUntilIdle()

        viewModel.onEvent(LogsEvent.FiltersResetRequested)
        advanceUntilIdle()

        val value = viewModel.state.value
        assertEquals("socket", value.query)
        assertNotNull(value.selectedFrom)
        assertNotEquals(1234L, value.selectedFrom)
        assertNull(value.selectedUntil)
        assertNull(value.selectedUnit)
        collect.cancel()
    }

    @Test
    fun queryAndDateChangesPropagateToStoreFilter() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val store = StubLogStore()
        val viewModel = LogsViewModel(store, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }

        advanceUntilIdle()
        viewModel.onEvent(LogsEvent.QueryChanged("timeout"))
        viewModel.onEvent(LogsEvent.FromChanged(111L))
        viewModel.onEvent(LogsEvent.UntilChanged(999L))
        advanceUntilIdle()

        val filter = store.filters.last()
        assertEquals("timeout", filter.query)
        assertEquals(111L, filter.from)
        assertEquals(999L, filter.until)
        collect.cancel()
    }

    private fun lanes(main: TestDispatcher, worker: TestDispatcher): DispatcherProvider {
        return object : DispatcherProvider {
            override val io = worker
            override val default = worker
            override val mainImmediate = main
        }
    }
}

private class StubLogStore : LogStoreGateway {
    private val rows = MutableStateFlow(
        listOf(
            LogEntry(
                id = 1L,
                createdAt = 10L,
                level = LogLevel.info,
                unit = LogUnit.ui,
                tag = "tag",
                event = "event",
                projectId = "project-1",
                projectName = "Project",
                sessionId = "session-1",
                sessionTitle = "Session",
                message = "hello",
                context = emptyMap(),
                throwable = null,
            )
        )
    )

    private val facet = MutableStateFlow(
        LogFacet(
            projects = listOf(LogProjectOption(id = "project-1", name = "Project")),
            sessions = listOf(LogSessionOption(id = "session-1", title = "Session")),
            events = listOf("event"),
        )
    )

    val filters = mutableListOf<LogFilter>()

    override suspend fun append(record: de.chennemann.opencode.mobile.domain.session.LogRecord) = Unit

    override fun observe(filter: LogFilter): Flow<List<LogEntry>> {
        filters += filter
        return rows
    }

    override fun observeFacet(): Flow<LogFacet> {
        return facet
    }

    override suspend fun prune(now: Long) = Unit

    override suspend fun clear() = Unit
}
