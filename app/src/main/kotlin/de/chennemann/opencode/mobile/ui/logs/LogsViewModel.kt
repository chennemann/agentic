package de.chennemann.opencode.mobile.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.opencode.mobile.di.DispatcherProvider
import de.chennemann.opencode.mobile.domain.session.LogFacet
import de.chennemann.opencode.mobile.domain.session.LogFilter
import de.chennemann.opencode.mobile.domain.session.LogLevel
import de.chennemann.opencode.mobile.domain.session.LogStoreGateway
import de.chennemann.opencode.mobile.domain.session.LogUnit
import de.chennemann.opencode.mobile.navigation.NavEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModel(
    private val store: LogStoreGateway,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private data class LocalState(
        val unit: LogUnit? = null,
        val level: LogLevel? = null,
        val event: String? = null,
        val projectId: String? = null,
        val sessionId: String? = null,
        val from: Long? = startOfPreviousDay(),
        val until: Long? = null,
        val query: String = "",
    )

    private val lane = dispatchers.default.limitedParallelism(1)
    private val local = MutableStateFlow(LocalState())
    private val navFlow = MutableSharedFlow<NavEvent>(extraBufferCapacity = 1)

    val nav = navFlow.asSharedFlow()
    private val facet = store.observeFacet()
        .flowOn(lane)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = LogFacet(emptyList(), emptyList(), emptyList()),
        )

    val state: StateFlow<LogsUiState> = local
        .flatMapLatest {
            store.observe(
                LogFilter(
                    unit = it.unit,
                    level = it.level,
                    event = it.event,
                    projectId = it.projectId,
                    sessionId = it.sessionId,
                    from = it.from,
                    until = it.until,
                    query = it.query,
                    limit = MaxRows,
                )
            ).map { rows ->
                it to rows
            }
        }
        .combine(facet) { pair, facet ->
            val filter = pair.first
            val rows = pair.second
                LogsUiState(
                    units = LogUnit.entries.filterNot { value -> value == LogUnit.system },
                    levels = LogLevel.entries,
                    facet = facet,
                    selectedUnit = filter.unit,
                    selectedLevel = filter.level,
                    selectedProjectId = filter.projectId,
                    selectedSessionId = filter.sessionId,
                    selectedEvent = filter.event,
                    selectedFrom = filter.from,
                    selectedUntil = filter.until,
                    query = filter.query,
                    rows = rows,
                )
        }
        .flowOn(lane)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = LogsUiState(
                units = LogUnit.entries.filterNot { it == LogUnit.system },
                levels = LogLevel.entries,
                facet = LogFacet(emptyList(), emptyList(), emptyList()),
                selectedUnit = null,
                selectedLevel = null,
                selectedProjectId = null,
                selectedSessionId = null,
                selectedEvent = null,
                selectedFrom = startOfPreviousDay(),
                selectedUntil = null,
                query = "",
                rows = emptyList(),
            ),
        )

    fun onEvent(event: LogsEvent) {
        when (event) {
            is LogsEvent.UnitChanged -> {
                local.value = local.value.copy(unit = event.unit)
            }

            is LogsEvent.LevelChanged -> {
                local.value = local.value.copy(level = event.level)
            }

            is LogsEvent.ProjectChanged -> {
                local.value = local.value.copy(projectId = event.id)
            }

            is LogsEvent.SessionChanged -> {
                local.value = local.value.copy(sessionId = event.id)
            }

            is LogsEvent.EventChanged -> {
                local.value = local.value.copy(event = event.value)
            }

            is LogsEvent.FromChanged -> {
                local.value = local.value.copy(from = event.value)
            }

            is LogsEvent.UntilChanged -> {
                local.value = local.value.copy(until = event.value)
            }

            is LogsEvent.FilterAppliedFromEntry -> {
                local.value = when (event.key) {
                    LogsFilterKey.from -> local.value
                    LogsFilterKey.until -> local.value
                    LogsFilterKey.unit -> local.value.copy(unit = LogUnit.from(event.value))
                    LogsFilterKey.level -> local.value.copy(level = LogLevel.from(event.value))
                    LogsFilterKey.event -> local.value.copy(event = event.value)
                    LogsFilterKey.project -> local.value.copy(projectId = event.value)
                    LogsFilterKey.session -> local.value.copy(sessionId = event.value)
                }
            }

            is LogsEvent.FilterRemoved -> {
                local.value = when (event.key) {
                    LogsFilterKey.from -> local.value.copy(from = null)
                    LogsFilterKey.until -> local.value.copy(until = null)
                    LogsFilterKey.unit -> local.value.copy(unit = null)
                    LogsFilterKey.level -> local.value.copy(level = null)
                    LogsFilterKey.event -> local.value.copy(event = null)
                    LogsFilterKey.project -> local.value.copy(projectId = null)
                    LogsFilterKey.session -> local.value.copy(sessionId = null)
                }
            }

            LogsEvent.FiltersResetRequested -> {
                local.value = LocalState(query = local.value.query)
            }

            is LogsEvent.QueryChanged -> {
                local.value = local.value.copy(query = event.value)
            }

            LogsEvent.BackRequested -> {
                navFlow.tryEmit(NavEvent.NavigateBack)
            }
        }
    }
}

private const val MaxRows = 500L

private fun startOfPreviousDay(): Long {
    return LocalDate.now()
        .minusDays(1)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}
