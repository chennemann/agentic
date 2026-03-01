package de.chennemann.agentic.domain.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface SessionServiceApi {
    val state: StateFlow<SessionUiState>

    fun start(scope: CoroutineScope)

    fun updateUrl(value: String)

    fun useDiscovered()

    fun refresh()

    fun selectProject(worktree: String)

    fun toggleProjectFavorite(worktree: String)

    fun removeProject(worktree: String)

    fun toggleSessionQuickPin(session: SessionState, systemPinned: Boolean)

    suspend fun createSessionAndFocus(worktree: String): Boolean

    fun openSession(session: SessionState)

    fun send(text: String, agent: String)

    fun loadMoreMessages()

    fun archiveSession(session: SessionState)

    fun renameSession(session: SessionState, title: String)

    suspend fun cachedSessionsForProject(worktree: String, limit: Int? = null): List<SessionState>

    suspend fun sessionsForProject(worktree: String, limit: Int? = null): List<SessionState>
}
