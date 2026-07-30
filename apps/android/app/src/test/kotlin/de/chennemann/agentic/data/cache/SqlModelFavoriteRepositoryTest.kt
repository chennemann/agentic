package de.chennemann.agentic.data.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.domain.preferences.FavoriteModelId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SqlModelFavoriteRepositoryTest {
    @Test
    fun `favorites preserve order and stay scoped to an environment`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val database = AgenticDb(driver)
        val repository = SqlModelFavoriteRepository(
            database = database,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val first = FavoriteModelId("provider", "first")
        val second = FavoriteModelId("provider", "second")

        repository.setFavorite("environment-a", first, true)
        repository.setFavorite("environment-a", second, true)
        repository.setFavorite("environment-b", second, true)

        assertEquals(listOf(first, second), repository.observe("environment-a").first())
        assertEquals(listOf(second), repository.observe("environment-b").first())

        repository.setFavorite("environment-a", first, false)
        assertEquals(listOf(second), repository.observe("environment-a").first())
        driver.close()
    }

    @Test
    fun `malformed favorite preference is treated as empty`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val database = AgenticDb(driver)
        database.agenticT3Queries.upsertPreference(
            "environment",
            "favorite-models-v1",
            "not-json",
        )
        val repository = SqlModelFavoriteRepository(
            database = database,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        assertEquals(emptyList<FavoriteModelId>(), repository.observe("environment").first())
        driver.close()
    }
}
