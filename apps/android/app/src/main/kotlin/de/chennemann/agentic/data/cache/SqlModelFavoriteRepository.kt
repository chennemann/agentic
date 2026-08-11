package de.chennemann.agentic.data.cache

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.domain.preferences.FavoriteModelId
import de.chennemann.agentic.domain.preferences.ModelFavoriteRepository
import de.chennemann.agentic.t3.contract.T3Json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

class SqlModelFavoriteRepository(
    private val database: AgenticDb,
    private val dispatcher: CoroutineDispatcher,
) : ModelFavoriteRepository {
    override fun observe(environmentId: String): Flow<List<FavoriteModelId>> =
        database.agenticT3Queries
            .selectPreference(environmentId, PreferenceKey)
            .asFlow()
            .mapToOneOrNull(dispatcher)
            .map(::decode)

    override suspend fun setFavorite(
        environmentId: String,
        model: FavoriteModelId,
        favorite: Boolean,
    ) = withContext(dispatcher) {
        val current = decode(
            database.agenticT3Queries
                .selectPreference(environmentId, PreferenceKey)
                .executeAsOneOrNull(),
        )
        val next = if (favorite) {
            (current - model) + model
        } else {
            current - model
        }
        database.agenticT3Queries.upsertPreference(
            environmentId,
            PreferenceKey,
            T3Json.encodeToString(next.map { it.toStored() }),
        )
        Unit
    }

    private fun decode(payload: String?): List<FavoriteModelId> = payload
        ?.let {
            runCatching {
                T3Json.decodeFromString<List<StoredFavoriteModel>>(it)
                    .map(StoredFavoriteModel::toDomain)
                    .distinct()
            }.getOrNull()
        }
        .orEmpty()

    private fun FavoriteModelId.toStored() = StoredFavoriteModel(providerInstanceId, modelSlug)

    @Serializable
    private data class StoredFavoriteModel(
        val providerInstanceId: String,
        val modelSlug: String,
    ) {
        fun toDomain() = FavoriteModelId(providerInstanceId, modelSlug)
    }

    private companion object {
        const val PreferenceKey = "favorite-models-v1"
    }
}
