package de.chennemann.agentic.ui.chat.workflow

import de.chennemann.agentic.domain.environment.CacheCategoryUsage
import de.chennemann.agentic.domain.environment.EnvironmentCacheActions
import de.chennemann.agentic.domain.environment.EnvironmentCacheUsage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CacheAdministrationTest {
    @Test
    fun `inspection and clearing expose one observable workflow state`() = runTest {
        val actions = RecordingCacheActions()
        val workflow: CacheAdministration = DefaultCacheAdministration(actions)

        workflow.accept(CacheAdministrationIntent.Inspect("environment"))

        assertEquals("environment", workflow.state.value.environmentId)
        assertEquals(listOf("messages: 2 entries", "outbox: 1 entries (protected)"), workflow.state.value.categories)
        assertEquals(120, workflow.state.value.clearableBytes)
        assertFalse(workflow.state.value.busy)

        workflow.accept(CacheAdministrationIntent.Clear)

        assertEquals(listOf("environment"), actions.cleared)
        assertEquals(0, workflow.state.value.clearableBytes)
        assertTrue(workflow.state.value.cleared)
    }

    @Test
    fun `failed clear remains retryable through the same interface`() = runTest {
        val actions = RecordingCacheActions()
        val workflow: CacheAdministration = DefaultCacheAdministration(actions)
        workflow.accept(CacheAdministrationIntent.Inspect("environment"))
        actions.failure = IllegalStateException("Protected work remains")

        workflow.accept(CacheAdministrationIntent.Clear)

        assertEquals("Protected work remains", workflow.state.value.error)
        assertFalse(workflow.state.value.busy)
        actions.failure = null
        workflow.accept(CacheAdministrationIntent.Clear)
        assertTrue(workflow.state.value.cleared)
    }
}

private class RecordingCacheActions : EnvironmentCacheActions {
    val cleared = mutableListOf<String>()
    var failure: Exception? = null

    override suspend fun usage(environmentId: String) = EnvironmentCacheUsage(
        environmentId,
        listOf(
            CacheCategoryUsage("messages", 2, 120, false),
            CacheCategoryUsage("outbox", 1, 30, true),
        ),
    )

    override suspend fun clear(environmentId: String) {
        failure?.let { throw it }
        cleared += environmentId
    }
}
