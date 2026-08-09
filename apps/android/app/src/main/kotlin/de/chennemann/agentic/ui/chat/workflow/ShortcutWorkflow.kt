package de.chennemann.agentic.ui.chat.workflow

import de.chennemann.agentic.domain.shortcuts.ShortcutCoordinator
import de.chennemann.agentic.domain.shortcuts.ShortcutFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface ShortcutWorkflow {
    val error: StateFlow<String?>
    fun start(scope: CoroutineScope)
    fun dismissError()
}

class DefaultShortcutWorkflow(
    private val coordinator: ShortcutCoordinator,
) : ShortcutWorkflow {
    private val mutableError = MutableStateFlow<String?>(null)
    override val error = mutableError.asStateFlow()

    override fun start(scope: CoroutineScope) {
        scope.launch {
            coordinator.failures.collect { failure ->
                mutableError.value = when (failure) {
                    ShortcutFailure.INVALID_ROUTE -> "Shortcut route is malformed."
                    ShortcutFailure.ENVIRONMENT_MISSING -> "This shortcut's environment is no longer registered."
                    ShortcutFailure.PROJECT_REQUIRED -> "Choose an available project for this new task."
                    ShortcutFailure.TARGET_MISSING -> "This shortcut's thread or project is no longer available."
                }
            }
        }
    }

    override fun dismissError() {
        mutableError.value = null
    }
}
