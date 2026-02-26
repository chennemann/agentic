package de.chennemann.opencode.mobile.domain.session

import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MutationPipelineTest {
    @Test
    fun preservesMutationOrdering() = runTest {
        val pipeline = MutationPipeline(this, StandardTestDispatcher(testScheduler))
        val order = mutableListOf<Int>()

        pipeline.launch { order += 1 }
        pipeline.launch { order += 2 }
        pipeline.launch { order += 3 }
        advanceUntilIdle()
        pipeline.cancel()

        assertEquals(listOf(1, 2, 3), order)
    }

    @Test
    fun cancelsPendingMutations() = runTest {
        val pipeline = MutationPipeline(this, StandardTestDispatcher(testScheduler))
        var value = 0

        pipeline.launch {
            delay(100)
            value += 1
        }
        pipeline.launch {
            value += 10
        }
        pipeline.cancel()
        advanceUntilIdle()

        assertEquals(0, value)
    }

    @Test
    fun dropsDuplicateEventKeysWhilePending() = runTest {
        val pipeline = MutationPipeline(this, StandardTestDispatcher(testScheduler))
        var value = 0

        pipeline.launch("sse:e1") { value += 1 }
        pipeline.launch("sse:e1") { value += 10 }
        advanceUntilIdle()
        pipeline.launch("sse:e1") { value += 1 }
        advanceUntilIdle()
        pipeline.cancel()

        assertEquals(2, value)
    }
}
