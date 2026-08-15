package de.chennemann.agentic.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute : NavKey

@Serializable
data object OnboardingRoute : AppRoute

@Serializable
data object ChatRoute : AppRoute

@Serializable
data class WorkspaceFilesRoute(val threadId: String) : AppRoute

@Serializable
data class TerminalRoute(val threadId: String) : AppRoute
