package de.chennemann.agentic.navigation

sealed interface NavEvent {
    data class NavigateTo(val route: AppRoute) : NavEvent

    data object NavigateBack : NavEvent
}
