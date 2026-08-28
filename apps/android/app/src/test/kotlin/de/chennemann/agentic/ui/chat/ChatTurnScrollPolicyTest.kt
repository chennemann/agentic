package de.chennemann.agentic.ui.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ChatTurnScrollPolicyTest {
    @Test
    fun fast_upward_fling_targets_nearest_preceding_user_message() {
        val target = fastUpwardTurnBoundaryTarget(
            velocityY = 5_000f,
            minimumVelocityY = 3_000f,
            firstVisibleItemIndex = 7,
            firstVisibleItemScrollOffset = 0,
            userMessageIndices = listOf(0, 3, 6, 9),
        )

        assertEquals(6, target)
    }

    @Test
    fun partially_hidden_user_message_is_aligned_before_moving_to_an_earlier_turn() {
        val target = fastUpwardTurnBoundaryTarget(
            velocityY = 5_000f,
            minimumVelocityY = 3_000f,
            firstVisibleItemIndex = 6,
            firstVisibleItemScrollOffset = 100,
            userMessageIndices = listOf(0, 3, 6, 9),
        )

        assertEquals(6, target)
    }

    @Test
    fun aligned_user_message_allows_the_next_fling_to_continue_to_the_previous_turn() {
        val target = fastUpwardTurnBoundaryTarget(
            velocityY = 5_000f,
            minimumVelocityY = 3_000f,
            firstVisibleItemIndex = 6,
            firstVisibleItemScrollOffset = 0,
            userMessageIndices = listOf(0, 3, 6, 9),
        )

        assertEquals(3, target)
    }

    @Test
    fun slow_or_downward_flings_keep_native_scrolling() {
        val indices = listOf(0, 3, 6)

        assertNull(
            fastUpwardTurnBoundaryTarget(
                velocityY = 2_999f,
                minimumVelocityY = 3_000f,
                firstVisibleItemIndex = 6,
                firstVisibleItemScrollOffset = 0,
                userMessageIndices = indices,
            ),
        )
        assertNull(
            fastUpwardTurnBoundaryTarget(
                velocityY = -5_000f,
                minimumVelocityY = 3_000f,
                firstVisibleItemIndex = 6,
                firstVisibleItemScrollOffset = 0,
                userMessageIndices = indices,
            ),
        )
    }

    @Test
    fun native_fling_delta_is_unchanged_until_the_boundary_is_visible() {
        assertEquals(
            -180f,
            limitDeltaToTurnBoundary(
                requestedDelta = -180f,
                distanceToBoundary = null,
            ),
        )
    }

    @Test
    fun native_fling_delta_is_clamped_to_the_visible_boundary() {
        assertEquals(
            -80f,
            limitDeltaToTurnBoundary(
                requestedDelta = -180f,
                distanceToBoundary = -80f,
            ),
        )
        assertEquals(
            -40f,
            limitDeltaToTurnBoundary(
                requestedDelta = -40f,
                distanceToBoundary = -80f,
            ),
        )
    }

    @Test
    fun aligned_boundary_consumes_no_more_scroll_distance() {
        assertEquals(
            0f,
            limitDeltaToTurnBoundary(
                requestedDelta = -180f,
                distanceToBoundary = 0f,
            ),
        )
    }

    @Test
    fun scrolling_toward_newer_messages_is_never_clamped() {
        assertEquals(
            180f,
            limitDeltaToTurnBoundary(
                requestedDelta = 180f,
                distanceToBoundary = -80f,
            ),
        )
    }
}
