package de.chennemann.opencode.mobile.domain.session

interface ProjectGateway {
    suspend fun projects(): List<SessionProject>

    suspend fun sessions(worktree: String, limit: Int? = null): List<SessionSummary>

    suspend fun archiveSession(sessionId: String, directory: String)

    suspend fun renameSession(sessionId: String, directory: String, title: String)

    suspend fun createSession(worktree: String, title: String): SessionSummary
}
