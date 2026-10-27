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
    val column: Int? = null,
) : AppRoute

@Serializable
data class TerminalRoute(val threadId: String, val script: ProjectScript? = null) : AppRoute

@Serializable
data object UsageRoute : AppRoute

@Serializable
data object PullRequestsRoute : AppRoute

@Serializable
data class ReviewDiffRoute(val threadId: String) : AppRoute

/** Server settings of the active environment; [projectId] offers that project's overrides too. */
@Serializable
data class ServerSettingsRoute(val projectId: String? = null) : AppRoute

@Serializable
data object ThreadSearchRoute : AppRoute

/** A file a conversation shares: a message attachment, or a path on the environment host. */
@Serializable
data class SharedFileRoute(
    val name: String,
    val attachmentId: String? = null,
    val mimeType: String? = null,
    val threadId: String? = null,
    val path: String? = null,
) : AppRoute
