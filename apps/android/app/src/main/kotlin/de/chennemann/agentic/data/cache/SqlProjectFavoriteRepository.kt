package de.chennemann.agentic.data.cache

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.domain.preferences.ProjectFavoriteRepository
import de.chennemann.agentic.t3.contract.T3Json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

class SqlProjectFavoriteRepository(
    private val database: AgenticDb,
    private val dispatcher: CoroutineDispatcher,
) : ProjectFavoriteRepository {
    override fun observe(environmentId: String): Flow<Set<String>> =
        database.agenticT3Queries
            .selectPreference(environmentId, PreferenceKey)
            .asFlow()
            .mapToOneOrNull(dispatcher)
            .map(::decode)

    override suspend fun setFavorite(
        environmentId: String,
        projectId: String,
        favorite: Boolean,
    ) = withContext(dispatcher) {
        val current = decode(
            database.agenticT3Queries
                .selectPreference(environmentId, PreferenceKey)
                .executeAsOneOrNull(),
        )
        val next = if (favorite) current + projectId else current - projectId
        if (next.isEmpty()) {
            database.agenticT3Queries.deletePreference(environmentId, PreferenceKey)
        } else {
            database.agenticT3Queries.upsertPreference(
                environmentId,
                PreferenceKey,
                T3Json.encodeToString(next.sorted()),
            )
        }
        Unit
    }

    private fun decode(payload: String?): Set<String> = payload
        ?.let {
            runCatching {
                T3Json.decodeFromString<List<String>>(it)
                    .filter(String::isNotBlank)
                    .toSet()
            }.getOrNull()
        }
        .orEmpty()

    private companion object {
        const val PreferenceKey = "favorite-projects-v1"
    }
}
