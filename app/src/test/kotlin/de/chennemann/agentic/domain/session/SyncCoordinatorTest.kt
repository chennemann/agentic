package de.chennemann.agentic.domain.session

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncCoordinatorTest {
    @Test
    fun beginEndGuardsConcurrentWork() = runBlocking {
        val sync = SyncCoordinator()

        assertTrue(sync.begin("s1"))
        assertFalse(sync.begin("s1"))
        sync.end("s1")
        assertTrue(sync.begin("s1"))
    }

    @Test
    fun scheduleReplacesPendingJob() = runBlocking {
        val sync = SyncCoordinator()
        var value = 0

        sync.schedule(this, "s1", 25) {
            value = 1
        }
        sync.schedule(this, "s1", 25) {
            value = 2
        }

        delay(80)

        assertEquals(2, value)
    }
}
