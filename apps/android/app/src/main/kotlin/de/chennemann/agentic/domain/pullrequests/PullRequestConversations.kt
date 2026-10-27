package de.chennemann.agentic.domain.pullrequests

import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.orchestration.ThreadActions
import de.chennemann.agentic.domain.preferences.ComposerDraftRepository
import de.chennemann.agentic.domain.preferences.projectDraftId
import de.chennemann.agentic.domain.composer.ComposerDocument
import de.chennemann.agentic.domain.composer.COMPOSER_INPUT_MAX_CHARS
import kotlinx.coroutines.flow.first

class PullRequestConversations(
    private val environments: EnvironmentRepository,
    private val threads: ThreadActions,
    private val drafts: ComposerDraftRepository
) {
    suspend fun open(environmentId: String, projectId: String, threadId: String) {
        environments.select(environmentId)
        threads.selectProject(projectId)
        threads.selectThread(threadId)
    }

    /** Prepare a prompt for the user's chosen model without sending or overwriting an unsent draft. */
    suspend fun discuss(environmentId: String, projectId: String, url: String, noun: String = "pull request") {
        val id = projectDraftId(projectId)
        val current = drafts.observeDocument(environmentId, id).first()
        drafts.setDocument(environmentId, id, preparePullRequestDraft(current, url, noun))
        environments.select(environmentId)
        threads.selectProject(projectId)
        threads.selectThread(null)
    }
}

internal fun preparePullRequestDraft(current: ComposerDocument, url: String, noun: String = "pull request"): ComposerDocument {
    if (url in current.text) return current
    val request = "Please help me review this $noun: $url"
    val text = listOf(current.text, request).filter { it.isNotBlank() }.joinToString("\n\n")
    require(text.length <= COMPOSER_INPUT_MAX_CHARS) { "The existing draft is too long to add the pull request." }
    return current.withText(text)
}
