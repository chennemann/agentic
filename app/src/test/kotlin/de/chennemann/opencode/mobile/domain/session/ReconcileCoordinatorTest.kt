package de.chennemann.opencode.mobile.domain.session

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReconcileCoordinatorTest {
    @Test
    fun runsCallbackOnInterval() = runBlocking {
        val reconcile = ReconcileCoordinator(interval = 20)
        var count = 0

        val job = reconcile.start(this) {
            count += 1
        }

        delay(120)
        job.cancel()

        assertTrue(count >= 1)
    }
}
