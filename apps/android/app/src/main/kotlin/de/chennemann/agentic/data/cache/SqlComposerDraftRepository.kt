package de.chennemann.agentic.data.cache

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.domain.composer.ComposerDocument
import de.chennemann.agentic.domain.composer.decodeComposerDocument
import de.chennemann.agentic.domain.preferences.ComposerDraftRepository
import de.chennemann.agentic.t3.contract.T3Json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

class SqlComposerDraftRepository(
    private val database: AgenticDb,
    private val dispatcher: CoroutineDispatcher,
    private val payloads: ComposerPastePayloads? = null
) : ComposerDraftRepository {
    private val writeMutex = kotlinx.coroutines.sync.Mutex()

    override fun observe(environmentId: String, threadId: String): Flow<String> =
        observeDocument(environmentId, threadId).map { it.text }

    override fun observeDocument(environmentId: String, threadId: String): Flow<ComposerDocument> =
        database.agenticT3Queries
            .selectPreference(environmentId, preferenceKey(threadId))
            .asFlow()
            .mapToOneOrNull(dispatcher)
            .map { payload ->
                withContext(dispatcher) {
                    val document = decodeComposerDocument(payload)
                    payloads?.restore(document) ?: document
                }
            }

    override suspend fun setDraft(environmentId: String, threadId: String, draft: String) {
        val document = if (draft.isEmpty()) {
            ComposerDocument()
        } else {
            observeDocument(
                environmentId,
                threadId
            ).first().withText(draft)
        }
        setDocument(environmentId, threadId, document)
    }

    override suspend fun setDocument(environmentId: String, threadId: String, document: ComposerDocument) =
        withContext(dispatcher) {
            writeMutex.lock()
            try {
                val retained = payloads?.store(document.retained()) ?: document.retained()
                if (retained.text.isEmpty()) {
                    database.agenticT3Queries.deletePreference(environmentId, preferenceKey(threadId))
                } else {
                    database.agenticT3Queries.upsertPreference(
                        environmentId,
                        preferenceKey(threadId),
                        T3Json.encodeToString(retained)
                    )
                }
                payloads?.prune(database.agenticT3Queries.selectComposerDraftPayloads().executeAsList())
                Unit
            } finally {
                writeMutex.unlock()
            }
        }

    private fun preferenceKey(threadId: String) = "$PREFERENCE_PREFIX$threadId"

    private companion object {
        const val PREFERENCE_PREFIX = "composer-draft-v1:"
    }
}
