package de.chennemann.agentic.ui.chat.workflow

import de.chennemann.agentic.domain.environment.EnvironmentCacheActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CacheAdministrationState(
    val environmentId: String? = null,
    val categories: List<String> = emptyList(),
    val clearableBytes: Long = 0,
    val busy: Boolean = false,
    val cleared: Boolean = false,
    val error: String? = null,
)

sealed interface CacheAdministrationIntent {
    data class Inspect(val environmentId: String) : CacheAdministrationIntent
    data object Clear : CacheAdministrationIntent
    data object Dismiss : CacheAdministrationIntent
}

interface CacheAdministration {
    val state: StateFlow<CacheAdministrationState>
    suspend fun accept(intent: CacheAdministrationIntent)
}

class DefaultCacheAdministration(private val cache: EnvironmentCacheActions) : CacheAdministration {
    private val mutableState = MutableStateFlow(CacheAdministrationState())
    override val state = mutableState.asStateFlow()

    override suspend fun accept(intent: CacheAdministrationIntent) {
        when (intent) {
            is CacheAdministrationIntent.Inspect -> execute(
                CacheAdministrationState(environmentId = intent.environmentId, busy = true),
                { cache.usage(intent.environmentId) },
                { usage ->
                    copy(
                        busy = false,
                        categories = usage.categories.map {
                            "${it.category}: ${it.entries} entries${if (it.protected) " (protected)" else ""}"
                        },
                        clearableBytes = usage.clearableBytes,
                    )
                },
                "Cache inspection failed.",
            )
            CacheAdministrationIntent.Clear -> {
                val id = state.value.environmentId ?: return
                if (state.value.busy) return
                execute(
                    state.value.copy(busy = true, cleared = false, error = null),
                    { cache.clear(id) },
                    { copy(busy = false, cleared = true, clearableBytes = 0) },
                    "Cache clearing failed.",
                )
            }
            CacheAdministrationIntent.Dismiss -> if (!state.value.busy) mutableState.value = CacheAdministrationState()
        }
    }

    private suspend fun <T> execute(
        start: CacheAdministrationState,
        operation: suspend () -> T,
        success: CacheAdministrationState.(T) -> CacheAdministrationState,
        failureMessage: String,
    ) {
        mutableState.value = start
        try {
            mutableState.value = mutableState.value.success(operation())
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            mutableState.value = mutableState.value.copy(busy = false, error = cause.message ?: failureMessage)
        }
    }
}
