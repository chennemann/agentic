package de.chennemann.agentic.ui.terminal

import de.chennemann.agentic.domain.orchestration.TerminalContext
import de.chennemann.agentic.domain.orchestration.TerminalSessions
import de.chennemann.agentic.t3.contract.ProjectScript
import de.chennemann.agentic.t3.contract.ProjectScriptIcon
import de.chennemann.agentic.t3.contract.TerminalAttachEvent
import de.chennemann.agentic.t3.contract.TerminalMetadataEvent
import de.chennemann.agentic.t3.contract.TerminalSessionSnapshot
import de.chennemann.agentic.t3.contract.TerminalSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `advertised script enters its ordinary terminal session with visible output`() = runTest(dispatcher) {
        val sessions = ScriptTerminalSessions()
        val viewModel = TerminalViewModel(sessions)

        viewModel.load("thread-1", script())
        advanceUntilIdle()

        assertEquals("dev", sessions.launchedScriptId)
        assertEquals("term-1", viewModel.state.value.selectedId)
        assertEquals("server started", viewModel.state.value.output)
        assertEquals("running", viewModel.state.value.status)
    }

    @Test
    fun `script launch failure remains actionable on the terminal surface`() = runTest(dispatcher) {
        val sessions = ScriptTerminalSessions(failure = IllegalStateException("Workspace is missing."))
        val viewModel = TerminalViewModel(sessions)

        viewModel.load("thread-1", script())
        advanceUntilIdle()

        assertEquals("error", viewModel.state.value.status)
        assertEquals("Workspace is missing.", viewModel.state.value.errorMessage)
    }

    private fun script() = ProjectScript("dev", "Dev", "pnpm dev", ProjectScriptIcon.DEBUG, false)
}

private class ScriptTerminalSessions(private val failure: Throwable? = null) : TerminalSessions {
    var launchedScriptId: String? = null

    override suspend fun context(threadId: String) = TerminalContext("https://t3.test", "token", threadId, "/workspace")

    override fun inventory(context: TerminalContext): Flow<TerminalMetadataEvent> =
        flowOf(TerminalMetadataEvent.Snapshot(emptyList()))

    override fun attach(context: TerminalContext, terminalId: String): Flow<TerminalAttachEvent> = emptyFlow()

    override suspend fun launchScript(
        context: TerminalContext,
        terminals: Collection<TerminalSummary>,
        script: ProjectScript,
    ): Pair<String, TerminalSessionSnapshot> {
        failure?.let { throw it }
        launchedScriptId = script.id
        return "term-1" to snapshot(history = "server started")
    }

    override suspend fun open(context: TerminalContext, terminalId: String) = snapshot()
    override suspend fun write(context: TerminalContext, terminalId: String, data: String) = Unit
    override suspend fun clear(context: TerminalContext, terminalId: String) = Unit
    override suspend fun restart(context: TerminalContext, terminalId: String) = snapshot()
    override suspend fun close(context: TerminalContext, terminalId: String) = Unit

    private fun snapshot(history: String = "") = TerminalSessionSnapshot(
        threadId = "thread-1",
        terminalId = "term-1",
        cwd = "/workspace",
        status = "running",
        history = history,
        label = "Dev",
        updatedAt = "now",
    )
}
