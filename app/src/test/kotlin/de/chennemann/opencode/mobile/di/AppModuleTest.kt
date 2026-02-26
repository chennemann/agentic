package de.chennemann.opencode.mobile.di

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.chennemann.opencode.mobile.data.CommandInfo
import de.chennemann.opencode.mobile.data.GlobalStreamEvent
import de.chennemann.opencode.mobile.data.Health
import de.chennemann.opencode.mobile.data.MdnsEntry
import de.chennemann.opencode.mobile.data.MdnsGateway
import de.chennemann.opencode.mobile.data.ProjectInfo
import de.chennemann.opencode.mobile.data.ServerGateway
import de.chennemann.opencode.mobile.data.ServerRepository
import de.chennemann.opencode.mobile.data.SessionCacheRepository
import de.chennemann.opencode.mobile.data.SessionInfo
import de.chennemann.opencode.mobile.data.SessionMessageInfo
import de.chennemann.opencode.mobile.db.AgenticDb
import de.chennemann.opencode.mobile.domain.session.CommandGateway
import de.chennemann.opencode.mobile.domain.session.ConnectionGateway
import de.chennemann.opencode.mobile.domain.session.ConnectivityGateway
import de.chennemann.opencode.mobile.domain.session.LogGateway
import de.chennemann.opencode.mobile.domain.session.MessageGateway
import de.chennemann.opencode.mobile.domain.session.ProjectGateway
import de.chennemann.opencode.mobile.domain.session.SessionCacheGateway
import de.chennemann.opencode.mobile.domain.session.SessionService
import de.chennemann.opencode.mobile.domain.session.SessionServiceApi
import de.chennemann.opencode.mobile.domain.session.StreamGateway
import de.chennemann.opencode.mobile.ui.chat.ConversationViewModel
import de.chennemann.opencode.mobile.ui.logs.LogsViewModel
import de.chennemann.opencode.mobile.ui.manage.ManageViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class AppModuleTest {
    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun resolvesSessionServiceApiAndViewModels() {
        val koin = startTestKoin()

        val api = koin.get<SessionServiceApi>()
        assertTrue(api is SessionService)
        assertSame(api, koin.get<SessionService>())

        val conversation = koin.get<ConversationViewModel>()
        val manage = koin.get<ManageViewModel>()
        val logs = koin.get<LogsViewModel>()
        assertNotNull(conversation)
        assertNotNull(manage)
        assertNotNull(logs)
        assertSame(api, viewModelService(conversation))
        assertSame(api, viewModelService(manage))
    }

    @Test
    fun resolvesRepositoryAndGatewayBindings() {
        val koin = startTestKoin()

        val repo = koin.get<ServerRepository>()
        assertSame(repo, koin.get<ConnectionGateway>())
        assertSame(repo, koin.get<ProjectGateway>())
        assertSame(repo, koin.get<CommandGateway>())
        assertSame(repo, koin.get<MessageGateway>())
        assertSame(repo, koin.get<StreamGateway>())

        val cache = koin.get<SessionCacheRepository>()
        assertSame(cache, koin.get<SessionCacheGateway>())
    }

    private fun startTestKoin(): Koin {
        return startKoin {
            allowOverride(true)
            modules(
                appModule,
                testCoroutineModule(),
                module {
                    single { db() }
                    single<MdnsGateway> { FakeMdnsGateway() }
                    single<ConnectivityGateway> { FakeConnectivityGateway() }
                    single<ServerGateway> { FakeServerGateway() }
                    single<LogGateway> { FakeLogGateway() }
                    single<CoroutineRolloutFlag> {
                        object : CoroutineRolloutFlag {
                            override val useMigratedExecution = true
                        }
                    }
                }
            )
        }.koin
    }

    private fun viewModelService(viewModel: Any): SessionServiceApi {
        val field = viewModel.javaClass.getDeclaredField("service")
        field.isAccessible = true
        return field.get(viewModel) as SessionServiceApi
    }

    private fun db(): AgenticDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.synchronous().create(driver)
        return AgenticDb(driver)
    }
}

private class FakeMdnsGateway : MdnsGateway {
    override fun discover(): Flow<MdnsEntry> {
        return emptyFlow()
    }
}

private class FakeConnectivityGateway : ConnectivityGateway {
    private val onlineState = MutableStateFlow(true)
    private val changedState = MutableStateFlow(0L)

    override val online: StateFlow<Boolean> = onlineState.asStateFlow()
    override val changed: StateFlow<Long> = changedState.asStateFlow()
}

private class FakeLogGateway : LogGateway {
    override fun log(
        level: de.chennemann.opencode.mobile.domain.session.LogLevel,
        unit: de.chennemann.opencode.mobile.domain.session.LogUnit,
        tag: String,
        event: String,
        message: String,
        context: Map<String, String>,
        error: Throwable?,
    ) {
    }
}

private class FakeServerGateway : ServerGateway {
    override suspend fun health(baseUrl: String): Health {
        return Health(healthy = true, version = "test")
    }

    override suspend fun projects(baseUrl: String): List<ProjectInfo> {
        return listOf(
            ProjectInfo(
                id = "p1",
                worktree = "/repo/main",
                name = "Main",
                sandboxes = listOf("/repo/main/s1"),
            )
        )
    }

    override suspend fun sessions(baseUrl: String, worktree: String, limit: Int?): List<SessionInfo> {
        return emptyList()
    }

    override suspend fun archiveSession(baseUrl: String, sessionId: String, directory: String) = Unit

    override suspend fun renameSession(baseUrl: String, sessionId: String, directory: String, title: String) = Unit

    override suspend fun createSession(baseUrl: String, worktree: String, title: String): SessionInfo {
        return SessionInfo(
            id = "created",
            title = title,
            version = "1",
            directory = worktree,
        )
    }

    override suspend fun commands(baseUrl: String, directory: String): List<CommandInfo> {
        return emptyList()
    }

    override suspend fun sessionMessages(baseUrl: String, sessionId: String, directory: String, limit: Int?): List<SessionMessageInfo> {
        return emptyList()
    }

    override suspend fun sessionUpdatedAt(baseUrl: String, sessionId: String, directory: String): Long? {
        return null
    }

    override suspend fun sessionStatus(baseUrl: String, directory: String): Map<String, String> {
        return emptyMap()
    }

    override suspend fun streamEvents(
        baseUrl: String,
        lastEventId: String?,
        onRawEvent: suspend (String) -> Unit,
        onEvent: suspend (GlobalStreamEvent) -> Unit,
    ): String? {
        onRawEvent("noop")
        onEvent(
            GlobalStreamEvent(
                directory = "global",
                type = "server.heartbeat",
                properties = JsonObject(emptyMap()),
                id = null,
                retry = null,
            )
        )
        return lastEventId
    }

    override suspend fun sendMessage(baseUrl: String, sessionId: String, directory: String, text: String, agent: String) = Unit

    override suspend fun sendCommand(baseUrl: String, sessionId: String, directory: String, name: String, arguments: String, agent: String) = Unit
}
