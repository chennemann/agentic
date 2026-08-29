package de.chennemann.agentic.domain.preferences

import kotlinx.coroutines.flow.Flow

interface ProjectFavoriteRepository {
    fun observe(environmentId: String): Flow<Set<String>>

    suspend fun setFavorite(
        environmentId: String,
        projectId: String,
        favorite: Boolean,
    )
}
