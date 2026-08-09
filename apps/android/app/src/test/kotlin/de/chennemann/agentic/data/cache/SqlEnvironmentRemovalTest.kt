package de.chennemann.agentic.data.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.chennemann.agentic.db.AgenticDb
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SqlEnvironmentRemovalTest {
    @Test
    fun `removal deletes only the targeted environment data`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val database = AgenticDb(driver)
        val queries = database.agenticT3Queries
        listOf("one", "two").forEach { id ->
            queries.upsertEnvironment(id, "Environment $id", "https://$id.test/", "os", "arch", "1", 0, null)
            queries.upsertProjection(id, "shell", "shell", 1, 1, "{}", 1)
            queries.upsertPreference(id, "provider-defined-preference", "{}")
            queries.enqueueCommand(id, "command-$id", "{}", 1)
        }
        val repository = SqlEnvironmentRepository(
            database = database,
            dispatcher = StandardTestDispatcher(testScheduler),
            scope = backgroundScope,
        )

        repository.remove("one")

        assertNull(queries.selectProjection("one", "shell", "shell").executeAsOneOrNull())
        assertNull(queries.selectPreference("one", "provider-defined-preference").executeAsOneOrNull())
        assertEquals(emptyList<Any>(), queries.selectOutbox("one").executeAsList())
        assertEquals("Environment two", queries.selectAllEnvironments().executeAsList().single().label)
        assertEquals("{}", queries.selectProjection("two", "shell", "shell").executeAsOne().payload_json)
        assertEquals("{}", queries.selectPreference("two", "provider-defined-preference").executeAsOne())
        assertEquals("command-two", queries.selectOutbox("two").executeAsOne().command_id)
        driver.close()
    }
}
