package de.chennemann.agentic.ui.review

import de.chennemann.agentic.domain.orchestration.ReviewChanges
import de.chennemann.agentic.domain.orchestration.ReviewSection
import de.chennemann.agentic.domain.orchestration.ReviewSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewDiffViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach fun setup() = Dispatchers.setMain(dispatcher)

    @AfterEach fun cleanup() = Dispatchers.resetMain()

    private fun snapshot(id: String = "turn:2", text: String = "new") = ReviewSnapshot(
        "Thread",
        listOf(ReviewSection("turn:2", "Turn 2", 1, 2), ReviewSection("all", "All turns", 0, 2, true)),
        id,
        "--- a/a.kt\n+++ b/a.kt\n@@ -1 +1 @@\n-old\n+$text\n"
    )

    @Test fun `opening loads actual patch and reopening same route does not request it twice`() = runTest(dispatcher) {
        var requests = 0
        val vm = ReviewDiffViewModel(
            ReviewChanges { _, _ ->
                requests++
                snapshot()
            },
            dispatcher
        )
        vm.load("thread")
        advanceUntilIdle()
        vm.load("thread")
        assertEquals(1, requests)
        assertEquals("turn:2", vm.state.value.selectedId)
        assertEquals(1, vm.state.value.document.additions)
        assertFalse(vm.state.value.loading)
    }

    @Test fun `late old range cannot replace newly selected changes`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val vm = ReviewDiffViewModel(
            ReviewChanges { _, section ->
                if (section == null) withContext(NonCancellable) { gate.await() }
                snapshot(section ?: "turn:2", section ?: "old response")
            },
            dispatcher
        )
        vm.load("thread")
        runCurrent()
        vm.select("all")
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("all", vm.state.value.selectedId)
        assertTrue(vm.state.value.document.rows.any { it.content == "all" })
    }

    @Test fun `failure clears the previous diff and refresh retries the same range`() = runTest(dispatcher) {
        var fail = false
        val selected = mutableListOf<String?>()
        val vm = ReviewDiffViewModel(
            ReviewChanges { _, section ->
                selected += section
                if (fail) {
                    error("private server detail")
                } else {
                    snapshot(
                        section ?: "turn:2"
                    )
                }
            },
            dispatcher
        )
        vm.load("thread")
        advanceUntilIdle()
        fail = true
        vm.select("all")
        advanceUntilIdle()
        assertTrue(vm.state.value.document.files.isEmpty())
        assertFalse(vm.state.value.error.orEmpty().contains("private"))
        fail = false
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf(null, "all", "all"), selected)
        assertNull(vm.state.value.error)
        assertEquals("all", vm.state.value.selectedId)
    }
}
