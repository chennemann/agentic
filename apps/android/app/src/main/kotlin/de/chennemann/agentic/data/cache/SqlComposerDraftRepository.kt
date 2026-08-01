package de.chennemann.agentic.data.cache

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.domain.preferences.ComposerDraftRepository
import de.chennemann.agentic.t3.contract.PortableJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

class SqlComposerDraftRepository(
    private val database: AgenticDb,
    private val dispatcher: CoroutineDispatcher,
) : ComposerDraftRepository {
    override fun observe(
        environmentId: String,
        threadId: String,
    ): Flow<String> = database.agenticT3Queries
        .selectPreference(environmentId, preferenceKey(threadId))
        .asFlow()
        .mapToOneOrNull(dispatcher)
        .map(::decode)

    override suspend fun setDraft(
        environmentId: String,
        threadId: String,
        draft: String,
    ) = withContext(dispatcher) {
        if (draft.isEmpty()) {
            database.agenticT3Queries.deletePreference(environmentId, preferenceKey(threadId))
        } else {
            database.agenticT3Queries.upsertPreference(
                environmentId,
                preferenceKey(threadId),
                PortableJson.encodeToString(draft),
            )
        }
        Unit
    }

    private fun decode(payload: String?): String = payload
        ?.let { runCatching { PortableJson.decodeFromString<String>(it) }.getOrNull() }
        .orEmpty()

    private fun preferenceKey(threadId: String) = "$PreferencePrefix$threadId"

    private companion object {
        const val PreferencePrefix = "composer-draft-v1:"
    }
}
