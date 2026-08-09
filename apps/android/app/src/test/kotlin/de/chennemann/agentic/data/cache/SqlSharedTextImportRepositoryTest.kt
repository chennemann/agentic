package de.chennemann.agentic.data.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.domain.sharing.SharedTextParseResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SqlSharedTextImportRepositoryTest {
    @Test
    fun `receipt deduplicates delivery survives recreation and remains discarded`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val database = AgenticDb(driver)
        val accepted = SharedTextParseResult.Accepted("same-fingerprint", "shared text")
        val first = SqlSharedTextImportRepository(database, Dispatchers.Unconfined)

        first.receive(accepted)
        first.receive(accepted)
        assertEquals("shared text", first.pending.first()?.text)

        val recreated = SqlSharedTextImportRepository(database, Dispatchers.Unconfined)
        assertEquals("same-fingerprint", recreated.pending.first()?.fingerprint)
        recreated.discard("same-fingerprint")
        recreated.receive(accepted)
        assertNull(recreated.pending.first())
        recreated.receive(SharedTextParseResult.Rejected("Unsupported share"))
        assertEquals("Unsupported share", recreated.error.first())
        recreated.clearError()
        assertNull(recreated.error.first())
        driver.close()
    }
}
