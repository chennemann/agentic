package de.chennemann.agentic.data.cache

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.domain.sharing.PendingSharedText
import de.chennemann.agentic.domain.sharing.SharedTextImportRepository
import de.chennemann.agentic.domain.sharing.SharedTextParseResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SqlSharedTextImportRepository(
    private val database: AgenticDb,
    private val dispatcher: CoroutineDispatcher,
) : SharedTextImportRepository {
    private val mutableError = MutableStateFlow<String?>(null)
    override val error: Flow<String?> = mutableError
    override val pending: Flow<PendingSharedText?> = database.agenticT3Queries
        .selectPendingSharedText()
        .asFlow()
        .mapToOneOrNull(dispatcher)
        .map { row -> row?.let { PendingSharedText(it.fingerprint, it.content) } }

    override suspend fun receive(result: SharedTextParseResult) = withContext(dispatcher) {
        when (result) {
            is SharedTextParseResult.Accepted -> {
                database.agenticT3Queries.insertSharedTextReceipt(
                    result.fingerprint,
                    result.text,
                    System.currentTimeMillis(),
                )
                mutableError.value = null
            }
            is SharedTextParseResult.Rejected -> mutableError.value = result.message
        }
    }

    override suspend fun markImported(fingerprint: String) = withContext(dispatcher) {
        database.agenticT3Queries.setSharedTextReceiptStatus("imported", fingerprint)
        Unit
    }

    override suspend fun discard(fingerprint: String) = withContext(dispatcher) {
        database.agenticT3Queries.setSharedTextReceiptStatus("discarded", fingerprint)
        Unit
    }

    override suspend fun clearError() {
        mutableError.value = null
    }
}
