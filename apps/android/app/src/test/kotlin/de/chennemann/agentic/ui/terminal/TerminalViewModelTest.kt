package de.chennemann.agentic.ui.terminal

import de.chennemann.agentic.domain.orchestration.TerminalContext
import de.chennemann.agentic.domain.orchestration.TerminalSessions
import de.chennemann.agentic.t3.contract.ProjectScript
import de.chennemann.agentic.t3.contract.ProjectScriptIcon
import de.chennemann.agentic.t3.contract.TerminalAttachEvent
import de.chennemann.agentic.t3.contract.TerminalMetadataEvent
import de.chennemann.agentic.t3.contract.TerminalSessionSnapshot
import de.chennemann.agentic.t3.contract.TerminalSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val sessions = FakeSessions()
    private val displays = FakeDisplays()
    private lateinit var vm: TerminalViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = TerminalViewModel(sessions, displays)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `script opens an ordinary interactive session and does not relaunch on recomposition`() = runTest(dispatcher) {
        val script = ProjectScript("dev", "Dev", "pnpm dev", ProjectScriptIcon.DEBUG, false)
        vm.load("thread-1", script)
        advanceUntilIdle()
        vm.load("thread-1", script)
        advanceUntilIdle()
        assertEquals(1, sessions.scriptLaunches)
        assertEquals("term-1", vm.state.value.selectedId)
        assertTrue(vm.state.value.canInput)
        assertEquals("started", displays.latest.text)
    }

    @Test
    fun `clear keeps the current display and does not trigger a resize`() = runTest(dispatcher) {
        connect()
        val screen = displays.latest
        sessions.sizes.clear()
        sessions.events().send(TerminalAttachEvent.Cleared("thread-1", "term-1", 2))
        advanceUntilIdle()
        assertSame(screen, vm.state.value.display)
        assertTrue(sessions.sizes.isEmpty())
        assertEquals("", screen.text)
        assertTrue(vm.state.value.canInput)
    }

    @Test
    fun `raw input preserves control characters and is sent in order without implicit Enter`() = runTest(dispatcher) {
        connect()
        sessions.writeGate = CompletableDeferred()
        vm.send("ls")
        vm.send("\t")
        vm.send("\u0003")
        vm.send("\r")
        runCurrent()
        assertEquals(listOf("ls"), sessions.writes.map { it.second })
        sessions.writeGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("ls", "\t", "\u0003", "\r"), sessions.writes.map { it.second })
    }

    @Test
    fun `switch discards queued input and old emulator callbacks`() = runTest(dispatcher) {
        connect()
        val old = displays.latest
        vm.send("old")
        vm.select("term-2")
        runCurrent()
        sessions.events("term-2").send(TerminalAttachEvent.Snapshot(snapshot("term-2")))
        advanceUntilIdle()
        old.onInput("late")
        advanceUntilIdle()
        assertTrue(old.detached)
        assertTrue(sessions.writes.isEmpty())
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun `parser receives split escapes and large output once without tail truncation`() = runTest(dispatcher) {
        connect()
        val screen = displays.latest
        val large = "x".repeat(600_000)
        listOf("\u001b[", "31m", large).forEachIndexed { index, data ->
            sessions.events().send(TerminalAttachEvent.Output("thread-1", "term-1", data, index + 2L))
        }
        advanceUntilIdle()
        assertSame(screen, vm.state.value.display)
        assertEquals("started\u001b[31m$large", screen.text)
        assertEquals(4, screen.chunks.size)
    }

    @Test
    fun `duplicate and foreign events are ignored and activity is not status`() = runTest(dispatcher) {
        connect()
        sessions.events().send(TerminalAttachEvent.Output("thread-1", "term-1", "new", 2))
        sessions.events().send(TerminalAttachEvent.Output("thread-1", "term-1", "duplicate", 2))
        sessions.events().send(TerminalAttachEvent.Output("other", "term-1", "wrong", 3))
        sessions.events().send(TerminalAttachEvent.Activity("thread-1", "term-1", true, "vim", 3))
        advanceUntilIdle()
        assertEquals("startednew", displays.latest.text)
        assertEquals("running", vm.state.value.status)
    }

    @Test
    fun `resize follows measured viewport coalesces and is resent after restart`() = runTest(dispatcher) {
        connect()
        sessions.sizes.clear()
        displays.latest.onResize(90, 30)
        displays.latest.onResize(92, 18)
        advanceUntilIdle()
        assertEquals(listOf(92 to 18), sessions.sizes)
        sessions.events().send(
            TerminalAttachEvent.Restarted("thread-1", "term-1", snapshot().copy(history = "fresh", sequence = 2), 2)
        )
        advanceUntilIdle()
        assertEquals("fresh", displays.latest.text)
        assertEquals(listOf(92 to 18, 92 to 18), sessions.sizes)
    }

    @Test
    fun `exit disables input and preserves visible output`() = runTest(dispatcher) {
        connect()
        sessions.events().send(TerminalAttachEvent.Exited("thread-1", "term-1", 7, sequence = 2))
        advanceUntilIdle()
        vm.send("ignored")
        advanceUntilIdle()
        assertFalse(vm.state.value.canInput)
        assertEquals("Exited (7)", vm.state.value.status)
        assertEquals("started", displays.latest.text)
        assertTrue(sessions.writes.isEmpty())
    }

    @Test
    fun `resize requests stay ordered while an RPC is suspended`() = runTest(dispatcher) {
        connect()
        sessions.sizes.clear()
        sessions.resizeGate = CompletableDeferred()
        displays.latest.onResize(90, 30)
        advanceTimeBy(100)
        runCurrent()
        displays.latest.onResize(92, 18)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(90 to 30), sessions.sizes)
        sessions.resizeGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(90 to 30, 92 to 18), sessions.sizes)
    }

    @Test
    fun `missing output sequence disconnects instead of silently corrupting the screen`() = runTest(dispatcher) {
        connect()
        sessions.events().send(TerminalAttachEvent.Output("thread-1", "term-1", "missing preceding bytes", 4))
        advanceUntilIdle()
        assertEquals("started", displays.latest.text)
        assertFalse(vm.state.value.canInput)
        assertEquals("Disconnected", vm.state.value.status)
    }

    @Test
    fun `disconnect is actionable cancellation is silent and reconnect replaces history`() = runTest(dispatcher) {
        connect()
        sessions.events().close(IllegalStateException("secret authenticated URL"))
        advanceUntilIdle()
        assertFalse(vm.state.value.canInput)
        assertEquals("Disconnected", vm.state.value.status)
        assertFalse(vm.state.value.errorMessage!!.contains("secret"))
        sessions.streams.clear()
        vm.reconnect()
        runCurrent()
        sessions.events().send(TerminalAttachEvent.Snapshot(snapshot().copy(history = "recovered")))
        advanceUntilIdle()
        assertTrue(vm.state.value.canInput)
        assertEquals("recovered", displays.latest.text)
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun `large paste obeys RPC limits without splitting Unicode`() = runTest(dispatcher) {
        connect()
        val data = "a".repeat(16383) + "\uD83D\uDE00" + "b".repeat(60000)
        vm.send(data)
        advanceUntilIdle()
        assertEquals(data, sessions.writes.joinToString("") { it.second })
        assertTrue(sessions.writes.all { it.second.length <= 65536 && !it.second.last().isHighSurrogate() })
    }

    @Test
    fun `removed selected terminal releases screen and disables input`() = runTest(dispatcher) {
        connect()
        val old = displays.latest
        sessions.metadata.emit(TerminalMetadataEvent.Remove("thread-1", "term-1"))
        advanceUntilIdle()
        assertNull(vm.state.value.selectedId)
        assertNull(vm.state.value.display)
        assertTrue(old.detached)
        assertFalse(vm.state.value.canInput)
    }

    @Test
    fun `close releases the selected screen even before metadata removal arrives`() = runTest(dispatcher) {
        connect()
        val old = displays.latest
        vm.close()
        advanceUntilIdle()
        assertNull(vm.state.value.selectedId)
        assertTrue(vm.state.value.terminals.isEmpty())
        assertTrue(old.detached)
    }

    @Test
    fun `restart after a broken attach starts a fresh subscription`() = runTest(dispatcher) {
        connect()
        sessions.events().close(IllegalStateException("disconnected"))
        advanceUntilIdle()
        sessions.streams.clear()
        vm.restart()
        advanceUntilIdle()
        assertTrue(vm.state.value.canInput)
        assertNull(vm.state.value.errorMessage)
        assertEquals("term-1", vm.state.value.selectedId)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.connect() {
        sessions.metadata.emit(TerminalMetadataEvent.Snapshot(listOf(summary())))
        vm.load("thread-1")
        runCurrent()
        sessions.events().send(TerminalAttachEvent.Snapshot(snapshot()))
        advanceUntilIdle()
    }
}

private class FakeDisplays : TerminalDisplayFactory {
    val all = mutableListOf<FakeDisplay>()
    val latest get() = all.last()
    override suspend fun create(
        history: String,
        cols: Int,
        rows: Int,
        onInput: (String) -> Unit,
        onResize: (Int, Int) -> Unit
    ) = FakeDisplay(onInput, onResize).also {
        it.append(history)
        all += it
    }
}
private class FakeDisplay(val onInput: (String) -> Unit, val onResize: (Int, Int) -> Unit) : TerminalDisplay {
    val chunks = mutableListOf<String>()
    val text get() = chunks.joinToString("")
    var detached = false
    override suspend fun append(data: String) {
        chunks += data
    }
    override suspend fun clear() {
        chunks.clear()
    }
    override fun detach() {
        detached = true
    }
}
private class FakeSessions : TerminalSessions {
    val metadata = MutableSharedFlow<TerminalMetadataEvent>(replay = 1).apply {
        tryEmit(TerminalMetadataEvent.Snapshot(emptyList()))
    }
    val streams = mutableMapOf<String, Channel<TerminalAttachEvent>>()
    val writes = mutableListOf<Pair<String, String>>()
    val sizes = mutableListOf<Pair<Int, Int>>()
    var writeGate: CompletableDeferred<Unit>? = null
    var resizeGate: CompletableDeferred<Unit>? = null
    var scriptLaunches = 0
    fun events(id: String = "term-1") = streams.getOrPut(id) { Channel(Channel.UNLIMITED) }
    override suspend fun context(threadId: String) = TerminalContext("https://t3.test", "token", threadId, "/workspace")
    override fun inventory(context: TerminalContext): Flow<TerminalMetadataEvent> = metadata
    override fun attach(context: TerminalContext, terminalId: String) = events(terminalId).receiveAsFlow()
    override suspend fun resize(context: TerminalContext, terminalId: String, cols: Int, rows: Int) {
        sizes +=
            cols to rows
        resizeGate?.await()
    }
    override suspend fun write(context: TerminalContext, terminalId: String, data: String) {
        writes +=
            terminalId to data
        writeGate?.await()
    }
    override suspend fun open(context: TerminalContext, terminalId: String) = snapshot(terminalId)
    override suspend fun clear(context: TerminalContext, terminalId: String) = Unit
    override suspend fun restart(context: TerminalContext, terminalId: String) = snapshot(terminalId)
    override suspend fun close(context: TerminalContext, terminalId: String) = Unit
    override suspend fun launchScript(
        context: TerminalContext,
        terminals: Collection<TerminalSummary>,
        script: ProjectScript
    ): Pair<String, TerminalSessionSnapshot> {
        scriptLaunches++
        return "term-1" to snapshot()
    }
}
private fun snapshot(id: String = "term-1") = TerminalSessionSnapshot(
    threadId = "thread-1",
    terminalId = id,
    cwd = "/workspace",
    status = "running",
    history = "started",
    label = "Shell",
    updatedAt = "now",
    sequence = 1
)
private fun summary() = TerminalSummary(
    threadId = "thread-1",
    terminalId = "term-1",
    cwd = "/workspace",
    status = "running",
    label = "Shell",
    updatedAt = "now"
)
