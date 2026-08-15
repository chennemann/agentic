package de.chennemann.agentic.ui.files

import de.chennemann.agentic.domain.orchestration.WorkspaceFileEntry
import de.chennemann.agentic.domain.orchestration.WorkspaceFileListing
import de.chennemann.agentic.domain.orchestration.WorkspaceFilesBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceFilesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterEach fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `load exposes the active workspace listing and truncation`() = runTest(dispatcher) {
        val browser = FakeWorkspaceFilesBrowser(Result.success(listing(truncated = true)))
        val viewModel = WorkspaceFilesViewModel(browser)

        viewModel.load("thread-1")
        advanceUntilIdle()

        assertEquals("/worktree", viewModel.state.value.listing?.cwd)
        assertEquals(listOf(WorkspaceFileEntry("src", true)), viewModel.state.value.listing?.entries)
        assertEquals(true, viewModel.state.value.listing?.truncated)
        assertFalse(viewModel.state.value.loading)
        assertEquals("thread-1", browser.threadId)
    }

    @Test
    fun `failed refresh preserves the last successful listing`() = runTest(dispatcher) {
        val browser = FakeWorkspaceFilesBrowser(Result.success(listing()))
        val viewModel = WorkspaceFilesViewModel(browser)
        viewModel.load("thread-1")
        advanceUntilIdle()

        browser.result = Result.failure(IllegalStateException("Index timed out"))
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(listing(), viewModel.state.value.listing)
        assertEquals("Index timed out", viewModel.state.value.errorMessage)
        assertFalse(viewModel.state.value.loading)
    }
}

private class FakeWorkspaceFilesBrowser(var result: Result<WorkspaceFileListing>) : WorkspaceFilesBrowser {
    var threadId: String? = null
    override suspend fun list(threadId: String): WorkspaceFileListing {
        this.threadId = threadId
        return result.getOrThrow()
    }
}

private fun listing(truncated: Boolean = false) = WorkspaceFileListing(
    cwd = "/worktree",
    entries = listOf(WorkspaceFileEntry("src", true)),
    truncated = truncated,
)
