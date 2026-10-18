package de.chennemann.agentic.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import de.chennemann.agentic.t3.contract.ProjectScript

@Serializable
sealed interface AppRoute : NavKey

@Serializable
data object OnboardingRoute : AppRoute

@Serializable
data object ChatRoute : AppRoute

@Serializable
data object StorUpdaterRoute : AppRoute

@Serializable
data class WorkspaceFilesRoute(
    val threadId: String,
    /** Opens this file directly, as linked from chat, instead of the file tree. */
    val path: String? = null,
    val line: Int? = null,
) : AppRoute

@Serializable
data class TerminalRoute(val threadId: String, val script: ProjectScript? = null) : AppRoute

@Serializable
data object UsageRoute : AppRoute

@Serializable
data class ReviewDiffRoute(val threadId: String) : AppRoute
