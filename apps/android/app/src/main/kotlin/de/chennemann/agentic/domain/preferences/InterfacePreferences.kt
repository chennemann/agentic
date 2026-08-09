package de.chennemann.agentic.domain.preferences

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
enum class ThemePreference { SYSTEM, LIGHT, DARK }

@Serializable
data class InterfacePreferences(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val interfaceScale: Float = 1f,
    val codeScale: Float = 1f,
)

interface InterfacePreferencesRepository {
    val preferences: Flow<InterfacePreferences>
    suspend fun setTheme(theme: ThemePreference)
    suspend fun setInterfaceScale(scale: Float)
    suspend fun setCodeScale(scale: Float)
}

val SupportedDisplayScales = listOf(0.9f, 1f, 1.15f)

fun InterfacePreferences.validated() = copy(
    interfaceScale = interfaceScale.takeIf { it in SupportedDisplayScales } ?: 1f,
    codeScale = codeScale.takeIf { it in SupportedDisplayScales } ?: 1f,
)
