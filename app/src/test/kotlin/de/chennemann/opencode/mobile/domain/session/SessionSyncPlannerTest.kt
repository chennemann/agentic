package de.chennemann.opencode.mobile.domain.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionSyncPlannerTest {
    private val planner = SessionSyncPlanner()

    @Test
    fun plansUpsertsSortsAndRemovals() {
        val cached = listOf(
            MessageState(id = "1", role = "assistant", text = "old", sort = "1"),
            MessageState(id = "2", role = "assistant", text = "bye", sort = "2"),
        )
        val sticky = linkedMapOf("1" to "z-100")
        val incoming = listOf(
            IncomingMessage(id = "1", role = "assistant", text = "new"),
            IncomingMessage(id = "3", role = "user", text = "hi"),
        )

        val value = planner.plan(
            incoming = incoming,
            cached = cached,
            sticky = sticky,
            remoteSort = { "r-${it.toString().padStart(20, '0')}" },
            knownSort = { null },
            claimPendingSort = { "z-200" },
            retainRemoved = { false },
            complete = true,
        )

        assertEquals(2, value.upserts.size)
        assertEquals("z-100", value.sorts["1"])
        assertEquals("z-200", value.sorts["3"])
        assertEquals(listOf("2"), value.removedIds)
        assertTrue(value.claimed)
    }

    @Test
    fun skipsRemovalsWhenNotComplete() {
        val value = planner.plan(
            incoming = emptyList(),
            cached = listOf(MessageState(id = "1", role = "assistant", text = "x", sort = "1")),
            sticky = null,
            remoteSort = { "r-${it.toString().padStart(20, '0')}" },
            knownSort = { null },
            claimPendingSort = { null },
            retainRemoved = { false },
            complete = false,
        )

        assertEquals(0, value.removedIds.size)
    }

    @Test
    fun usesRemoteOrderToRepairSortWhenMessagesWereMissed() {
        val incoming = listOf(
            IncomingMessage(id = "9", role = "user", text = "older user"),
            IncomingMessage(id = "10", role = "assistant", text = "older assistant"),
            IncomingMessage(id = "11", role = "user", text = "newer user"),
            IncomingMessage(id = "12", role = "assistant", text = "newer assistant"),
        )
        val cached = listOf(
            MessageState(id = "11", role = "user", text = "newer user", sort = "z-00000000000000000009"),
            MessageState(id = "12", role = "assistant", text = "newer assistant", sort = "z-00000000000000000010"),
        )

        val value = planner.plan(
            incoming = incoming,
            cached = cached,
            sticky = null,
            remoteSort = { "r-${it.toString().padStart(20, '0')}" },
            knownSort = { null },
            claimPendingSort = { null },
            retainRemoved = { false },
            complete = true,
        )

        assertEquals("r-00000000000000000000", value.sorts["9"])
        assertEquals("r-00000000000000000001", value.sorts["10"])
        assertEquals("r-00000000000000000002", value.sorts["11"])
        assertEquals("r-00000000000000000003", value.sorts["12"])
    }
}
