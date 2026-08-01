package de.chennemann.agentic.data.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.chennemann.agentic.db.AgenticDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SqlComposerDraftRepositoryTest {
    @Test
    fun `drafts survive repository recreation and remain scoped by environment and thread`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val database = AgenticDb(driver)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = SqlComposerDraftRepository(database, dispatcher)

        repository.setDraft("environment-a", "thread-1", "first draft")
        repository.setDraft("environment-a", "thread-2", "second draft")
        repository.setDraft("environment-b", "thread-1", "other environment")

        val recreated = SqlComposerDraftRepository(database, dispatcher)
        assertEquals("first draft", recreated.observe("environment-a", "thread-1").first())
        assertEquals("second draft", recreated.observe("environment-a", "thread-2").first())
        assertEquals("other environment", recreated.observe("environment-b", "thread-1").first())

        recreated.setDraft("environment-a", "thread-1", "")
        assertEquals("", recreated.observe("environment-a", "thread-1").first())
        driver.close()
    }
}
