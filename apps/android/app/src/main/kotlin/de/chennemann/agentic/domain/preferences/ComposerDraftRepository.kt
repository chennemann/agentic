package de.chennemann.agentic.domain.preferences

import kotlinx.coroutines.flow.Flow

interface ComposerDraftRepository {
    fun observe(
        environmentId: String,
        threadId: String,
    ): Flow<String>

    suspend fun setDraft(
        environmentId: String,
        threadId: String,
        draft: String,
    )
}
