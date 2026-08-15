package de.chennemann.agentic.ui.files

import de.chennemann.agentic.domain.orchestration.WorkspaceFileEntry
import de.chennemann.agentic.domain.orchestration.WorkspaceFileListing
import de.chennemann.agentic.domain.orchestration.WorkspaceFilePreview
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

    @Test
    fun `open maps a truncated text preview without transport state`() = runTest(dispatcher) {
        val preview = WorkspaceFilePreview.Text("README.md", "# Hello", 2_000_000, true, true)
        val browser = FakeWorkspaceFilesBrowser(Result.success(listing()), Result.success(preview))
        val viewModel = WorkspaceFilesViewModel(browser)
        viewModel.load("thread-1")
        advanceUntilIdle()

        viewModel.open("README.md")
        advanceUntilIdle()

        assertEquals(preview, viewModel.state.value.preview)
        assertEquals("README.md", viewModel.state.value.selectedPath)
        assertFalse(viewModel.state.value.previewLoading)
        assertEquals(null, viewModel.state.value.previewErrorMessage)
        assertEquals("README.md", browser.previewPath)
    }

    @Test
    fun `failed reload preserves the last successful preview`() = runTest(dispatcher) {
        val preview = WorkspaceFilePreview.Text("README.md", "hello", 5, false, true)
        val browser = FakeWorkspaceFilesBrowser(Result.success(listing()), Result.success(preview))
        val viewModel = WorkspaceFilesViewModel(browser)
        viewModel.load("thread-1")
        advanceUntilIdle()
        viewModel.open("README.md")
        advanceUntilIdle()

        browser.previewResult = Result.failure(IllegalStateException("Read timed out"))
        viewModel.open("README.md")
        advanceUntilIdle()

        assertEquals(preview, viewModel.state.value.preview)
        assertEquals("Read timed out", viewModel.state.value.previewErrorMessage)
        assertFalse(viewModel.state.value.previewLoading)
    }
}

private class FakeWorkspaceFilesBrowser(
    var result: Result<WorkspaceFileListing>,
    var previewResult: Result<WorkspaceFilePreview> = Result.failure(IllegalStateException("No preview configured")),
) : WorkspaceFilesBrowser {
    var threadId: String? = null
    var previewPath: String? = null
    override suspend fun list(threadId: String): WorkspaceFileListing {
        this.threadId = threadId
        return result.getOrThrow()
    }

    override suspend fun preview(threadId: String, relativePath: String): WorkspaceFilePreview {
        this.threadId = threadId
        previewPath = relativePath
        return previewResult.getOrThrow()
    }
}

private fun listing(truncated: Boolean = false) = WorkspaceFileListing(
    cwd = "/worktree",
    entries = listOf(WorkspaceFileEntry("src", true)),
    truncated = truncated,
)
