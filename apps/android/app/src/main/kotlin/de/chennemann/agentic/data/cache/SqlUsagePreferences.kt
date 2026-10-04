package de.chennemann.agentic.data.cache

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import de.chennemann.agentic.db.AgenticDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class SqlUsagePreferences(private val database: AgenticDb, private val dispatcher: CoroutineDispatcher) {
    suspend fun read(): Pair<String, Int> = withContext(dispatcher) {
        val raw = database.agenticT3Queries.selectPreference(
            "__global__",
            "usage-v1"
        ).asFlow().mapToOneOrNull(dispatcher).first().orEmpty().split(':')
        (raw.firstOrNull() ?: "COST") to (raw.getOrNull(1)?.toIntOrNull()?.takeIf { it in listOf(1, 7, 30, 90) } ?: 7)
    }
    suspend fun save(metric: String, days: Int) = withContext(dispatcher) {
        database.agenticT3Queries.upsertPreference("__global__", "usage-v1", "$metric:$days")
    }
}
