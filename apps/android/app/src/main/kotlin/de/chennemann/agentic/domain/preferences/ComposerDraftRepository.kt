package de.chennemann.agentic.domain.preferences

import de.chennemann.agentic.domain.composer.ComposerDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface ComposerDraftRepository {
    fun observeDocument(environmentId: String, threadId: String): Flow<ComposerDocument> =
        observe(environmentId, threadId).map { ComposerDocument(it) }

    suspend fun setDocument(environmentId: String, threadId: String, document: ComposerDocument) =
        setDraft(environmentId, threadId, document.text)

    fun observe(environmentId: String, threadId: String): Flow<String>

    suspend fun setDraft(environmentId: String, threadId: String, draft: String)
}

/** Stable identity for the unsent composer belonging to a project. */
fun projectDraftId(projectId: String): String = "new-project:$projectId"
