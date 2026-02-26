package de.chennemann.opencode.mobile.navigation

sealed interface NavEvent {
    data class NavigateTo(val route: AppRoute) : NavEvent

    data object NavigateBack : NavEvent
}
