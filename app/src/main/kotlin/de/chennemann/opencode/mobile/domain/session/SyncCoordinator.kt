package de.chennemann.opencode.mobile.domain.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SyncCoordinator {
    private val queued = linkedMapOf<String, Job>()
    private val active = linkedSetOf<String>()
    private val guard = Mutex()

    fun schedule(scope: CoroutineScope, id: String, wait: Long, block: suspend () -> Unit) {
        queued[id]?.cancel()
        queued[id] = scope.launch {
            delay(wait)
            block()
        }
    }

    suspend fun begin(id: String): Boolean {
        return guard.withLock {
            if (active.contains(id)) return@withLock false
            active.add(id)
            true
        }
    }

    suspend fun end(id: String) {
        guard.withLock {
            active.remove(id)
        }
    }

    fun cancelAll() {
        queued.values.forEach { it.cancel() }
        queued.clear()
    }
}
