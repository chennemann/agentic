package de.chennemann.agentic.data.cache

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.domain.preferences.InterfacePreferences
import de.chennemann.agentic.domain.preferences.InterfacePreferencesRepository
import de.chennemann.agentic.domain.preferences.ThemePreference
import de.chennemann.agentic.domain.preferences.validated
import de.chennemann.agentic.t3.contract.T3Json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

class SqlInterfacePreferencesRepository(private val database: AgenticDb, private val dispatcher: CoroutineDispatcher) :
    InterfacePreferencesRepository {
    override val preferences: Flow<InterfacePreferences> = database.agenticT3Queries
        .selectPreference(GlobalScope, PreferenceKey).asFlow().mapToOneOrNull(dispatcher)
        .map { raw -> raw?.let { runCatching { T3Json.decodeFromString<InterfacePreferences>(it) }.getOrNull() }?.validated() ?: InterfacePreferences() }

    override suspend fun setTheme(theme: ThemePreference) = update { copy(theme = theme) }
    override suspend fun setInterfaceScale(scale: Float) = update { copy(interfaceScale = scale).validated() }
    override suspend fun setCodeScale(scale: Float) = update { copy(codeScale = scale).validated() }

    private suspend fun update(transform: InterfacePreferences.() -> InterfacePreferences) = withContext(dispatcher) {
        database.agenticT3Queries.upsertPreference(GlobalScope, PreferenceKey, T3Json.encodeToString(preferences.first().transform()))
        Unit
    }

    private companion object { const val GlobalScope = "__global__"; const val PreferenceKey = "interface-v1" }
}
