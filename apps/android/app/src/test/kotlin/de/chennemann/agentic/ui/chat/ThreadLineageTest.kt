package de.chennemann.agentic.ui.chat

import de.chennemann.agentic.t3.contract.OrchestrationThreadLineage
import de.chennemann.agentic.t3.contract.OrchestrationThreadShell
import de.chennemann.agentic.t3.contract.T3Json
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThreadLineageTest {
    @Test
    fun `delegated descendants group under visible parent while forks stay independent`() {
        val root = thread("root")
        val child = thread("child", "root")
        val nested = thread("nested", "child").copy(hasPendingApprovals = true)
        val fork = thread("fork", "root", "fork")
        val model = ThreadLineage(listOf(root, child, nested, fork))
        assertEquals(listOf("root", "fork"), model.conversations.map { it.id })
        assertEquals(listOf("child", "nested"), model.subagents["root"]?.map { it.id })
        assertTrue(model.hasAttention("root"))
        assertTrue(nested.toPickerItem().needsAttention)
    }

    @Test
    fun `missing parents and cyclic lineage remain accessible`() {
        val threads = listOf(thread("orphan", "missing"), thread("a", "b"), thread("b", "a"))
        val model = ThreadLineage(threads)
        assertEquals(threads, model.conversations)
        assertTrue(model.subagents.isEmpty())
    }

    @Test
    fun `lineage survives projection cache serialization`() {
        val original = thread("child", "root").copy(creationSource = "provider")
        val restored = T3Json.decodeFromString<OrchestrationThreadShell>(T3Json.encodeToString(original))
        assertEquals(original, restored)
    }

    private fun thread(id: String, parent: String? = null, relationship: String = "subagent") = OrchestrationThreadShell(
        id = id,
        projectId = "project",
        title = id,
        createdAt = "2026-10-26T00:00:00Z",
        updatedAt = "2026-10-26T00:00:00Z",
        lineage = OrchestrationThreadLineage(parent, relationship.takeIf { parent != null }, parent ?: id),
    )
}
