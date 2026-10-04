package de.chennemann.agentic.data.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.domain.preferences.AccentPreference
import de.chennemann.agentic.domain.preferences.ThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SqlInterfacePreferencesRepositoryTest {
    @Test
    fun `preferences persist reactively and correct unsupported scales`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val database = AgenticDb(driver)
        val first = SqlInterfacePreferencesRepository(database, Dispatchers.Unconfined)

        first.setFollowUpBehavior(de.chennemann.agentic.domain.preferences.FollowUpBehavior.QUEUE)
        first.setTheme(ThemePreference.LIGHT)
        first.setAccent(AccentPreference.BLUE)
        first.setInterfaceScale(9f)
        first.setCodeScale(1.15f)

        val recreated = SqlInterfacePreferencesRepository(database, Dispatchers.Unconfined).preferences.first()
        assertEquals(de.chennemann.agentic.domain.preferences.FollowUpBehavior.QUEUE, recreated.followUpBehavior)
        assertEquals(ThemePreference.LIGHT, recreated.theme)
        assertEquals(AccentPreference.BLUE, recreated.accent)
        assertEquals(1f, recreated.interfaceScale)
        assertEquals(1.15f, recreated.codeScale)
        driver.close()
    }

    @Test
    fun `preferences saved before accent selection retain the default accent`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val database = AgenticDb(driver)
        database.agenticT3Queries.upsertPreference(
            "__global__",
            "interface-v1",
            """{"theme":"DARK","interfaceScale":1.0,"codeScale":1.0}""",
        )

        val preferences = SqlInterfacePreferencesRepository(database, Dispatchers.Unconfined).preferences.first()

        assertEquals(de.chennemann.agentic.domain.preferences.FollowUpBehavior.STEER, preferences.followUpBehavior)
        assertEquals(ThemePreference.DARK, preferences.theme)
        assertEquals(AccentPreference.DEFAULT, preferences.accent)
        driver.close()
    }
}
