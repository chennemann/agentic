package de.chennemann.agentic.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute : NavKey

@Serializable
data object AgentChatRoute : AppRoute

@Serializable
data object WorkspaceHubRoute : AppRoute

@Serializable
data object LogsRoute : AppRoute

@Serializable
data class SessionSelectionBottomSheetRoute(val projectKey: String) : AppRoute
