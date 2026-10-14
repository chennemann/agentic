package de.chennemann.agentic.domain.preferences

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
enum class ThemePreference { SYSTEM, LIGHT, DARK }

@Serializable
enum class AccentPreference { DEFAULT, BLUE, PURPLE, ORANGE, PINK, TEAL }

@Serializable
enum class FollowUpBehavior {
    STEER,
    QUEUE;

    fun alternate() = if (this == STEER) QUEUE else STEER
}

/** Whether opening an image starts in zoom or annotation mode. */
@Serializable
enum class ImageOpenAction { ZOOM, ANNOTATE }

@Serializable
data class InterfacePreferences(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val accent: AccentPreference = AccentPreference.DEFAULT,
    val interfaceScale: Float = 1f,
    val codeScale: Float = 1f,
    val followUpBehavior: FollowUpBehavior = FollowUpBehavior.STEER,
    val imageOpenAction: ImageOpenAction = ImageOpenAction.ZOOM,
)

interface InterfacePreferencesRepository {
    val preferences: Flow<InterfacePreferences>
    suspend fun setTheme(theme: ThemePreference)
    suspend fun setAccent(accent: AccentPreference)
    suspend fun setInterfaceScale(scale: Float)
    suspend fun setFollowUpBehavior(behavior: FollowUpBehavior)
    suspend fun setCodeScale(scale: Float)
    suspend fun setImageOpenAction(action: ImageOpenAction)
}

val SupportedDisplayScales = listOf(0.9f, 1f, 1.15f)

fun InterfacePreferences.validated() = copy(
    interfaceScale = interfaceScale.takeIf { it in SupportedDisplayScales } ?: 1f,
    codeScale = codeScale.takeIf { it in SupportedDisplayScales } ?: 1f,
)
