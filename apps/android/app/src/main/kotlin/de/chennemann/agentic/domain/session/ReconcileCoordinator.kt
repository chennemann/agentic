package de.chennemann.agentic.domain.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ReconcileCoordinator(
    private val interval: Long = 15000,
) {
    fun start(scope: CoroutineScope, block: suspend () -> Unit): Job {
        return scope.launch {
            while (isActive) {
                block()
                delay(interval)
            }
        }
    }
}
