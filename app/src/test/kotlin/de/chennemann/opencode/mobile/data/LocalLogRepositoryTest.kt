package de.chennemann.opencode.mobile.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.async.coroutines.synchronous
import de.chennemann.opencode.mobile.db.AgenticDb
import de.chennemann.opencode.mobile.di.DispatcherProvider
import de.chennemann.opencode.mobile.domain.session.LogFilter
import de.chennemann.opencode.mobile.domain.session.LogLevel
import de.chennemann.opencode.mobile.domain.session.LogRecord
import de.chennemann.opencode.mobile.domain.session.LogUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalLogRepositoryTest {
    @Test
    fun appendAndFilterByUnitLevelAndQuery() = runTest {
        val lane = StandardTestDispatcher(testScheduler)
        val repo = LocalLogRepository(db(), lanes(lane), Json { ignoreUnknownKeys = true })
        repo.append(record(1, LogLevel.info, LogUnit.sync, "sync_ok", "Sync completed"))
        repo.append(record(2, LogLevel.error, LogUnit.network, "health_failed", "Network failed"))

        val all = repo.observe(LogFilter(limit = 50)).first()
        assertEquals(2, all.size)

        val byUnit = repo.observe(LogFilter(unit = LogUnit.sync, limit = 50)).first()
        assertEquals(listOf("sync_ok"), byUnit.map { it.event })

        val byLevel = repo.observe(LogFilter(level = LogLevel.error, limit = 50)).first()
        assertEquals(listOf("health_failed"), byLevel.map { it.event })

        val byQuery = repo.observe(LogFilter(query = "completed", limit = 50)).first()
        assertEquals(listOf("sync_ok"), byQuery.map { it.event })
    }

    @Test
    fun pruneDropsOldRowsAndRespectsLimit() = runTest {
        val lane = StandardTestDispatcher(testScheduler)
        val repo = LocalLogRepository(db(), lanes(lane), Json { ignoreUnknownKeys = true })
        repeat(5105) {
            repo.append(
                record(
                    createdAt = 1000L + it,
                    level = LogLevel.info,
                    unit = LogUnit.sync,
                    event = "sync_ok",
                    message = "entry-$it",
                )
            )
        }
        repo.append(record(createdAt = 1L, level = LogLevel.warn, unit = LogUnit.cache, event = "old", message = "old"))

        repo.prune(now = 8 * 24 * 60 * 60 * 1000L)
        val rows = repo.observe(LogFilter(limit = 6000)).first()

        assertTrue(rows.size <= 5000)
        assertTrue(rows.none { it.event == "old" })
    }

    @Test
    fun clearRemovesAllRows() = runTest {
        val lane = StandardTestDispatcher(testScheduler)
        val repo = LocalLogRepository(db(), lanes(lane), Json { ignoreUnknownKeys = true })
        repo.append(record(1, LogLevel.info, LogUnit.ui, "ui_render", "Rendered"))

        repo.clear()
        val rows = repo.observe(LogFilter(limit = 10)).first()

        assertTrue(rows.isEmpty())
    }

    private fun record(
        createdAt: Long,
        level: LogLevel,
        unit: LogUnit,
        event: String,
        message: String,
    ): LogRecord {
        return LogRecord(
            createdAt = createdAt,
            level = level,
            unit = unit,
            tag = "Test",
            event = event,
            projectId = "p1",
            projectName = "Project One",
            sessionId = "s1",
            sessionTitle = "Session One",
            message = message,
            context = mapOf("k" to "v"),
            throwable = null,
            redacted = true,
        )
    }

    private fun db(): AgenticDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.synchronous().create(driver)
        return AgenticDb(driver)
    }

    private fun lanes(worker: TestDispatcher): DispatcherProvider {
        return object : DispatcherProvider {
            override val io = worker
            override val default = worker
            override val mainImmediate = Dispatchers.Unconfined
        }
    }
}
