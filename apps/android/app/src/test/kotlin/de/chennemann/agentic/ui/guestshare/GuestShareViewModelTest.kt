package de.chennemann.agentic.ui.guestshare

import de.chennemann.agentic.domain.guestshare.GuestShareFixture
import de.chennemann.agentic.domain.guestshare.GuestShareJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuestShareViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val fixture = GuestShareFixture()

    @BeforeEach fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a second approve tap while the first is in flight sends nothing more`() = runTest(dispatcher) {
        fixture.focus(GuestShareJson)
        val viewModel = GuestShareViewModel(fixture.service)
        val collector = backgroundScope.launch { viewModel.state.collect {} }
        runCurrent()
        fixture.dispatchGate = CompletableDeferred()
        viewModel.onEvent(GuestShareUiEvent.ApproveRequested("g-1"))
        runCurrent()
        assertTrue(viewModel.state.value.pendingMessages.single().busy)
        viewModel.onEvent(GuestShareUiEvent.ApproveRequested("g-1"))
        viewModel.onEvent(GuestShareUiEvent.RejectRequested("g-1"))
        runCurrent()
        fixture.dispatchGate!!.complete(Unit)
        runCurrent()
        assertEquals(1, fixture.calls.size)
        assertEquals(false, viewModel.state.value.pendingMessages.single().busy)
        collector.cancel()
    }

    @Test
    fun `sheet explains a missing permission instead of offering a link`() = runTest(dispatcher) {
        fixture.permissions = listOf("orchestration:operate")
        val viewModel = GuestShareViewModel(fixture.service)
        val collector = backgroundScope.launch { viewModel.state.collect {} }
        viewModel.onEvent(GuestShareUiEvent.SheetRequested("thread-1"))
        runCurrent()
        assertEquals(
            GuestShareSheetPhase.Unavailable(GuestShareMissingPermissionReason),
            viewModel.state.value.sheet?.phase
        )
        viewModel.onEvent(GuestShareUiEvent.CreateRequested)
        runCurrent()
        assertTrue(fixture.calls.none { it.first == "guestShare.create" })
        viewModel.onEvent(GuestShareUiEvent.SheetDismissed)
        runCurrent()
        assertNull(viewModel.state.value.sheet)
        collector.cancel()
    }

    @Test
    fun `available environment creates and shows the link`() = runTest(dispatcher) {
        val viewModel = GuestShareViewModel(fixture.service)
        val collector = backgroundScope.launch { viewModel.state.collect {} }
        viewModel.onEvent(GuestShareUiEvent.SheetRequested("thread-1"))
        runCurrent()
        assertEquals(GuestShareSheetPhase.Ready, viewModel.state.value.sheet?.phase)
        viewModel.onEvent(GuestShareUiEvent.CreateRequested)
        runCurrent()
        assertEquals(
            GuestShareSheetPhase.Created("https://abc.t3.example/guest#share-1.sig"),
            viewModel.state.value.sheet?.phase
        )
        collector.cancel()
    }
}
