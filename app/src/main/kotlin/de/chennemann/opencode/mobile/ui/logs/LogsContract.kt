package de.chennemann.opencode.mobile.ui.logs

import de.chennemann.opencode.mobile.domain.session.LogEntry
import de.chennemann.opencode.mobile.domain.session.LogFacet
import de.chennemann.opencode.mobile.domain.session.LogLevel
import de.chennemann.opencode.mobile.domain.session.LogUnit

data class LogsUiState(
    val units: List<LogUnit>,
    val levels: List<LogLevel>,
    val facet: LogFacet,
    val selectedUnit: LogUnit?,
    val selectedLevel: LogLevel?,
    val selectedProjectId: String?,
    val selectedSessionId: String?,
    val selectedEvent: String?,
    val selectedFrom: Long?,
    val selectedUntil: Long?,
    val query: String,
    val rows: List<LogEntry>,
)

sealed interface LogsEvent {
    data class UnitChanged(val unit: LogUnit?) : LogsEvent

    data class LevelChanged(val level: LogLevel?) : LogsEvent

    data class ProjectChanged(val id: String?) : LogsEvent

    data class SessionChanged(val id: String?) : LogsEvent

    data class EventChanged(val value: String?) : LogsEvent

    data class FromChanged(val value: Long?) : LogsEvent

    data class UntilChanged(val value: Long?) : LogsEvent

    data class QueryChanged(val value: String) : LogsEvent

    data class FilterAppliedFromEntry(val key: LogsFilterKey, val value: String) : LogsEvent

    data class FilterRemoved(val key: LogsFilterKey) : LogsEvent

    data object FiltersResetRequested : LogsEvent

    data object BackRequested : LogsEvent
}

enum class LogsFilterKey {
    from,
    until,
    unit,
    level,
    event,
    project,
    session,
}
