package de.chennemann.agentic.data.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.chennemann.agentic.db.AgenticDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SqlEnvironmentCacheInspectorTest {
    @Test
    fun `usage is isolated and pending work is explicitly protected`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val db = AgenticDb(driver)
        db.agenticT3Queries.upsertProjection("target", "shell", "shell", 1, 1, "12345", 1)
        db.agenticT3Queries.upsertProjection("other", "shell", "shell", 1, 1, "much-larger-other-payload", 1)
        db.agenticT3Queries.upsertPreference("target", "composer-draft-v1:thread", "draft")
        db.agenticT3Queries.upsertPreference("target", "selected-project-v1", "pref")
        db.agenticT3Queries.enqueueCommand("target", "command", "{}", 1)

        val usage = SqlEnvironmentCacheInspector(db, Dispatchers.Unconfined).usage("target")

        assertEquals(9, usage.clearableBytes)
        assertTrue(usage.categories.single { it.category == "Unsent drafts" }.protected)
        assertTrue(!usage.categories.single { it.category == "Preferences" }.protected)
        assertEquals(1, usage.categories.single { it.category == "Pending command outbox" }.entries)
        driver.close()
    }

    @Test
    fun `clear preserves a draft inserted after inspection while deleting only target cache`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val db = AgenticDb(driver)
        db.agenticT3Queries.upsertProjection("target", "shell", "shell", 1, 1, "target", 1)
        db.agenticT3Queries.upsertProjection("other", "shell", "shell", 1, 1, "other", 1)
        db.agenticT3Queries.upsertPreference("target", "selected-project-v1", "target-pref")
        db.agenticT3Queries.upsertPreference("other", "selected-project-v1", "other-pref")
        db.agenticT3Queries.upsertPreference("__global__", "interface-v1", "global-pref")
        db.agenticT3Queries.enqueueCommand("target", "command", "{}", 1)
        val inspector = SqlEnvironmentCacheInspector(db, Dispatchers.Unconfined)

        inspector.usage("target")
        db.agenticT3Queries.upsertPreference("target", "composer-draft-v1:late-thread", "late-draft")

        inspector.clear("target")

        assertTrue(db.agenticT3Queries.selectEnvironmentProjectionUsage("target").executeAsList().isEmpty())
        assertEquals(null, db.agenticT3Queries.selectPreference("target", "selected-project-v1").executeAsOneOrNull())
        assertEquals("late-draft", db.agenticT3Queries.selectPreference("target", "composer-draft-v1:late-thread").executeAsOne())
        assertEquals("other-pref", db.agenticT3Queries.selectPreference("other", "selected-project-v1").executeAsOne())
        assertEquals("global-pref", db.agenticT3Queries.selectPreference("__global__", "interface-v1").executeAsOne())
        assertEquals(1, db.agenticT3Queries.selectEnvironmentOutboxCount("target").executeAsOne())
        driver.close()
    }
}
