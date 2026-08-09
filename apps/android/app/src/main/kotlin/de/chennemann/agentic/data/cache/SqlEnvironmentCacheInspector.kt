package de.chennemann.agentic.data.cache

import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.domain.environment.CacheCategoryUsage
import de.chennemann.agentic.domain.environment.EnvironmentCacheInspector
import de.chennemann.agentic.domain.environment.EnvironmentCacheUsage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class SqlEnvironmentCacheInspector(private val database: AgenticDb, private val dispatcher: CoroutineDispatcher) :
    EnvironmentCacheInspector {
    override suspend fun usage(environmentId: String) = withContext(dispatcher) {
        val projections = database.agenticT3Queries.selectEnvironmentProjectionUsage(environmentId).executeAsList().map {
            CacheCategoryUsage("Projections: ${it.cache_kind}", it.entry_count, it.byte_count, false)
        }
        val preferences = database.agenticT3Queries.selectEnvironmentPreferenceUsage(environmentId).executeAsOne()
        val drafts = database.agenticT3Queries.selectEnvironmentDraftUsage(environmentId).executeAsOne()
        val outbox = database.agenticT3Queries.selectEnvironmentOutboxCount(environmentId).executeAsOne()
        EnvironmentCacheUsage(
            environmentId,
            projections + listOf(
                CacheCategoryUsage("Preferences", preferences.entry_count, preferences.byte_count, false),
                CacheCategoryUsage("Unsent drafts", drafts.entry_count, drafts.byte_count, true),
                CacheCategoryUsage("Pending command outbox", outbox, 0, true),
            ),
        )
    }

    override suspend fun clear(environmentId: String) = withContext(dispatcher) {
        database.transaction {
            database.agenticT3Queries.deleteEnvironmentProjections(environmentId)
            database.agenticT3Queries.deleteEnvironmentClearablePreferences(environmentId)
        }
        Unit
    }
}
