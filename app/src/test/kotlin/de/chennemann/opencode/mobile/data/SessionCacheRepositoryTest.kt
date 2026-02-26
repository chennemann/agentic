package de.chennemann.opencode.mobile.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.async.coroutines.synchronous
import de.chennemann.opencode.mobile.db.AgenticDb
import de.chennemann.opencode.mobile.di.DispatcherProvider
import de.chennemann.opencode.mobile.domain.session.MessageState
import de.chennemann.opencode.mobile.domain.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCacheRepositoryTest {
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun observeMessagesCompletesWhileMainPaused() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(TestCoroutineScheduler())
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val repo = SessionCacheRepository(
            db = db(),
            dispatchers = lanes(main, worker),
        )

        val save = async {
            repo.upsertMessage(
                server = "http://localhost:4096",
                sessionId = "s1",
                message = MessageState(
                    id = "m1",
                    role = "assistant",
                    text = "hello",
                    sort = "0001",
                    createdAt = 1,
                    completedAt = 2,
                ),
                updatedAt = 3,
            )
        }
        advanceUntilIdle()
        assertTrue(save.isCompleted)
        save.await()

        val observe = async {
            repo.observeMessages("http://localhost:4096", "s1").first()
        }

        advanceUntilIdle()
        assertTrue(observe.isCompleted)
        assertEquals(1, observe.await().size)
    }

    @Test
    fun updatesHiddenProjectsSet() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(TestCoroutineScheduler())
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val repo = SessionCacheRepository(
            db = db(),
            dispatchers = lanes(main, worker),
        )
        val server = "http://localhost:4096"

        val hide = async {
            repo.setProjectHidden(server, "/repo/main", true)
        }
        advanceUntilIdle()
        assertTrue(hide.isCompleted)
        hide.await()
        assertEquals(setOf("/repo/main"), repo.hiddenProjects(server))

        val show = async {
            repo.setProjectHidden(server, "/repo/main", false)
        }
        advanceUntilIdle()
        assertTrue(show.isCompleted)
        show.await()
        assertEquals(emptySet<String>(), repo.hiddenProjects(server))
    }

    @Test
    fun normalizesFavoritesHiddenAndQuickPins() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(TestCoroutineScheduler())
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val database = db()
        val repo = SessionCacheRepository(
            db = database,
            dispatchers = lanes(main, worker),
        )
        val server = "http://localhost:4096"

        database.settingsQueries.upsertSetting(
            "project_favorite:$server",
            " /repo/main \n/repo/main\n\n /repo/aux ",
        )
        database.settingsQueries.upsertSetting(
            "project_hidden:$server",
            " /repo/hide\n/repo/hide \n\n/repo/other",
        )
        database.settingsQueries.upsertSetting(
            "session_quick_include:$server",
            " s2 \n\n s1 \ns1",
        )
        database.settingsQueries.upsertSetting(
            "session_quick_exclude:$server",
            " s4\n\n s3\ns3 ",
        )

        assertEquals(setOf("/repo/main", "/repo/aux"), repo.projectFavorites(server))
        assertEquals(setOf("/repo/hide", "/repo/other"), repo.hiddenProjects(server))
        assertEquals(setOf("s1", "s2"), repo.sessionQuickPins(server).include)
        assertEquals(setOf("s3", "s4"), repo.sessionQuickPins(server).exclude)

        val write = async {
            repo.setSessionQuickPins(
                server = server,
                include = setOf(" s2", "", "s1", "s1 "),
                exclude = setOf("", " s3", "s3 "),
            )
        }
        advanceUntilIdle()
        assertTrue(write.isCompleted)
        write.await()

        assertEquals("s1\ns2", database.settingsQueries.selectSetting("session_quick_include:$server").executeAsOneOrNull())
        assertEquals("s3", database.settingsQueries.selectSetting("session_quick_exclude:$server").executeAsOneOrNull())
    }

    @Test
    fun mapsRecentSessionCacheToDomainModel() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(TestCoroutineScheduler())
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val database = db()
        val repo = SessionCacheRepository(
            db = database,
            dispatchers = lanes(main, worker),
        )
        val server = "http://localhost:4096"

        database.sessionCacheQueries.upsertSessionCache(
            server,
            "s-old",
            "/repo/old",
            "/repo/old",
            "Old",
            "1",
            10,
            100,
        )
        database.sessionCacheQueries.upsertSessionCache(
            server,
            "s-recent",
            "/repo/main",
            "/repo/main",
            "Recent",
            "2",
            20,
            200,
        )

        val recent = repo.recentSession()
        assertNotNull(recent)
        assertEquals(server, recent?.server)
        assertEquals("/repo/main", recent?.project)
        assertEquals("s-recent", recent?.session?.id)
        assertEquals("Recent", recent?.session?.title)
        assertEquals("2", recent?.session?.version)
        assertEquals("/repo/main", recent?.session?.directory)
        assertEquals(null, recent?.session?.updatedAt)
    }

    @Test
    fun loadsProjectSessionsFromCacheInUpdatedOrder() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(TestCoroutineScheduler())
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val repo = SessionCacheRepository(
            db = db(),
            dispatchers = lanes(main, worker),
        )
        val server = "http://localhost:4096"

        val write = async {
            repo.upsertSessionSnapshot(
                server = server,
                project = "/repo/main",
                session = SessionState(
                    id = "s1",
                    title = "One",
                    version = "1",
                    directory = "/repo/main",
                    updatedAt = 100,
                ),
            )
            repo.upsertSessionSnapshot(
                server = server,
                project = "/repo/main",
                session = SessionState(
                    id = "s2",
                    title = "Two",
                    version = "1",
                    directory = "/repo/main",
                    updatedAt = 200,
                ),
            )
        }
        advanceUntilIdle()
        assertTrue(write.isCompleted)
        write.await()

        val read = async {
            repo.listProjectSessions(server, "/repo/main", limit = 1)
        }
        advanceUntilIdle()
        assertTrue(read.isCompleted)
        assertEquals(listOf("s2"), read.await().map { it.id })
    }

    @Test
    fun deletesSessionFromCache() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(TestCoroutineScheduler())
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val repo = SessionCacheRepository(
            db = db(),
            dispatchers = lanes(main, worker),
        )
        val server = "http://localhost:4096"

        val write = async {
            repo.upsertSessionSnapshot(
                server = server,
                project = "/repo/main",
                session = SessionState(
                    id = "s1",
                    title = "One",
                    version = "1",
                    directory = "/repo/main",
                    updatedAt = 100,
                ),
            )
            repo.deleteSession(server, "s1")
        }
        advanceUntilIdle()
        assertTrue(write.isCompleted)
        write.await()

        val read = async {
            repo.listProjectSessions(server, "/repo/main")
        }
        advanceUntilIdle()
        assertTrue(read.isCompleted)
        assertTrue(read.await().isEmpty())
    }

    @Test
    fun deleteMessageAndSessionCleanupOnlyAffectTargetScope() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(TestCoroutineScheduler())
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val repo = SessionCacheRepository(
            db = db(),
            dispatchers = lanes(main, worker),
        )
        val server = "http://localhost:4096"
        val otherServer = "http://localhost:4097"

        val seed = async {
            repo.upsertSessionSnapshot(
                server = server,
                project = "/repo/main",
                session = SessionState(
                    id = "s1",
                    title = "One",
                    version = "1",
                    directory = "/repo/main",
                    updatedAt = 100,
                ),
            )
            repo.upsertSessionSnapshot(
                server = server,
                project = "/repo/main",
                session = SessionState(
                    id = "s2",
                    title = "Two",
                    version = "1",
                    directory = "/repo/main",
                    updatedAt = 200,
                ),
            )
            repo.upsertMessage(
                server = server,
                sessionId = "s1",
                message = MessageState(
                    id = "m1",
                    role = "assistant",
                    text = "one",
                    sort = "0001",
                    createdAt = 1,
                    completedAt = 2,
                ),
                updatedAt = 3,
            )
            repo.upsertMessage(
                server = server,
                sessionId = "s1",
                message = MessageState(
                    id = "m2",
                    role = "assistant",
                    text = "two",
                    sort = "0002",
                    createdAt = 2,
                    completedAt = 3,
                ),
                updatedAt = 4,
            )
            repo.upsertMessage(
                server = server,
                sessionId = "s2",
                message = MessageState(
                    id = "m3",
                    role = "assistant",
                    text = "other-session",
                    sort = "0001",
                    createdAt = 1,
                    completedAt = 2,
                ),
                updatedAt = 3,
            )
            repo.upsertMessage(
                server = otherServer,
                sessionId = "s1",
                message = MessageState(
                    id = "m4",
                    role = "assistant",
                    text = "other-server",
                    sort = "0001",
                    createdAt = 1,
                    completedAt = 2,
                ),
                updatedAt = 3,
            )
        }
        advanceUntilIdle()
        assertTrue(seed.isCompleted)
        seed.await()

        val deletes = async {
            repo.deleteMessage(server, "s1", "missing")
            repo.deleteMessage(server, "s1", "m1")
            repo.deleteSession(server, "missing")
            repo.deleteSession(server, "s1")
            repo.deleteSessionMessages(server, "missing")
            repo.deleteSessionMessages(server, "s1")
        }
        advanceUntilIdle()
        assertTrue(deletes.isCompleted)
        deletes.await()

        assertEquals(listOf("s2"), repo.listProjectSessions(server, "/repo/main").map { it.id })
        assertTrue(repo.listMessages(server, "s1").isEmpty())
        assertEquals(listOf("m3"), repo.listMessages(server, "s2").map { it.id })
        assertEquals(listOf("m4"), repo.listMessages(otherServer, "s1").map { it.id })
    }

    @Test
    fun syncProjectSessionsRemovesStaleCachedSessions() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(TestCoroutineScheduler())
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val repo = SessionCacheRepository(
            db = db(),
            dispatchers = lanes(main, worker),
        )
        val server = "http://localhost:4096"

        val seed = async {
            repo.upsertSessionSnapshot(
                server = server,
                project = "/repo/main",
                session = SessionState(
                    id = "s-stale",
                    title = "Stale",
                    version = "1",
                    directory = "/repo/main",
                    updatedAt = 100,
                ),
            )
            repo.upsertMessage(
                server = server,
                sessionId = "s-stale",
                message = MessageState(
                    id = "m1",
                    role = "assistant",
                    text = "old",
                    sort = "0001",
                    createdAt = 1,
                    completedAt = 2,
                ),
                updatedAt = 3,
            )
        }
        advanceUntilIdle()
        assertTrue(seed.isCompleted)
        seed.await()

        val sync = async {
            repo.syncProjectSessions(
                server = server,
                project = "/repo/main",
                sessions = listOf(
                    SessionState(
                        id = "s-new",
                        title = "New",
                        version = "1",
                        directory = "/repo/main",
                        updatedAt = 200,
                    )
                ),
            )
        }
        advanceUntilIdle()
        assertTrue(sync.isCompleted)
        sync.await()

        val sessions = async { repo.listProjectSessions(server, "/repo/main") }
        val messages = async { repo.listMessages(server, "s-stale") }
        advanceUntilIdle()
        assertTrue(sessions.isCompleted)
        assertTrue(messages.isCompleted)
        assertEquals(listOf("s-new"), sessions.await().map { it.id })
        assertTrue(messages.await().isEmpty())
    }

    private fun db(): AgenticDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.synchronous().create(driver)
        return AgenticDb(driver)
    }

    private fun lanes(main: TestDispatcher, worker: TestDispatcher): DispatcherProvider {
        return object : DispatcherProvider {
            override val io = worker
            override val default = worker
            override val mainImmediate = main
        }
    }
}
