package de.chennemann.agentic.data.cache
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.domain.preferences.ThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
class SqlInterfacePreferencesRepositoryTest {
 @Test fun `preferences persist reactively and correct unsupported scales`() = runTest {
  val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY); AgenticDb.Schema.create(driver); val db=AgenticDb(driver)
  val first=SqlInterfacePreferencesRepository(db, Dispatchers.Unconfined)
  first.setTheme(ThemePreference.LIGHT); first.setInterfaceScale(9f); first.setCodeScale(1.15f)
  val recreated=SqlInterfacePreferencesRepository(db, Dispatchers.Unconfined).preferences.first()
  assertEquals(ThemePreference.LIGHT,recreated.theme); assertEquals(1f,recreated.interfaceScale); assertEquals(1.15f,recreated.codeScale)
  driver.close()
 }
}
