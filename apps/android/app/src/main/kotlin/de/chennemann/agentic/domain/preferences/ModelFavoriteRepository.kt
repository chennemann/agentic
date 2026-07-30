package de.chennemann.agentic.domain.preferences

import kotlinx.coroutines.flow.Flow

data class FavoriteModelId(
    val providerInstanceId: String,
    val modelSlug: String,
)

interface ModelFavoriteRepository {
    fun observe(environmentId: String): Flow<List<FavoriteModelId>>

    suspend fun setFavorite(
        environmentId: String,
        model: FavoriteModelId,
        favorite: Boolean,
    )
}
