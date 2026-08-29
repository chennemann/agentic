package de.chennemann.agentic.data.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.chennemann.agentic.db.AgenticDb
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SqlProjectFavoriteRepositoryTest {
    @Test
    fun `project favorites survive repository recreation and remain environment scoped`() = runTest {
        val databaseFile = Files.createTempFile("agentic-project-favorites-", ".db")
        try {
            val firstDriver = JdbcSqliteDriver("jdbc:sqlite:$databaseFile")
            AgenticDb.Schema.create(firstDriver)
            val first = SqlProjectFavoriteRepository(
                AgenticDb(firstDriver),
                StandardTestDispatcher(testScheduler),
            )

            first.setFavorite("environment-a", "project-a", true)
            first.setFavorite("environment-b", "project-b", true)
            firstDriver.close()

            val recreatedDriver = JdbcSqliteDriver("jdbc:sqlite:$databaseFile")
            val recreated = SqlProjectFavoriteRepository(
                AgenticDb(recreatedDriver),
                StandardTestDispatcher(testScheduler),
            )
            assertEquals(setOf("project-a"), recreated.observe("environment-a").first())
            assertEquals(setOf("project-b"), recreated.observe("environment-b").first())
            recreatedDriver.close()
        } finally {
            Files.deleteIfExists(databaseFile)
        }
    }
}
