package de.chennemann.agentic.di

import de.chennemann.agentic.data.AndroidLogGateway
import de.chennemann.agentic.data.LocalLogRepository
import de.chennemann.agentic.data.MdnsService
import de.chennemann.agentic.data.MdnsGateway
import de.chennemann.agentic.data.NetworkService
import de.chennemann.agentic.data.store.OpenApiServerAdapter
import de.chennemann.agentic.data.ServerRepository
import de.chennemann.agentic.data.ServerService
import de.chennemann.agentic.data.ServerGateway
import de.chennemann.agentic.data.SessionCacheRepository
import de.chennemann.agentic.data.store.SqlDelightMessageRepository
import de.chennemann.agentic.data.store.SqlDelightProjectRepository
import de.chennemann.agentic.data.store.SqlDelightServerRepository
import de.chennemann.agentic.data.store.SqlDelightSessionRepository
import de.chennemann.agentic.domain.remote.ServerAdapter
import de.chennemann.agentic.domain.messages.DefaultMessageService
import de.chennemann.agentic.domain.message.MessageDecorator
import de.chennemann.agentic.domain.message.MessagePartParser
import de.chennemann.agentic.domain.session.CommandGateway
import de.chennemann.agentic.domain.session.ConnectivityGateway
import de.chennemann.agentic.domain.session.ConnectionGateway
import de.chennemann.agentic.domain.session.FocusedMessageProjector
import de.chennemann.agentic.domain.session.LogGateway
import de.chennemann.agentic.domain.session.LogRedactor
import de.chennemann.agentic.domain.session.LogStoreGateway
import de.chennemann.agentic.domain.session.MessageGateway
import de.chennemann.agentic.domain.session.ProjectGateway
import de.chennemann.agentic.domain.session.ReconcileCoordinator
import de.chennemann.agentic.domain.session.SessionCacheGateway
import de.chennemann.agentic.domain.session.SessionEventReducer
import de.chennemann.agentic.domain.session.SessionService
import de.chennemann.agentic.domain.session.SessionServiceApi
import de.chennemann.agentic.domain.session.SessionSyncPlanner
import de.chennemann.agentic.domain.session.SessionStreamCoordinator
import de.chennemann.agentic.domain.session.StreamGateway
import de.chennemann.agentic.domain.sync.DefaultSynchronizationService
import de.chennemann.agentic.domain.projects.ProjectRepository
import de.chennemann.agentic.domain.projects.DefaultProjectService
import de.chennemann.agentic.domain.sessions.SessionRepository
import de.chennemann.agentic.domain.sessions.DefaultSessionService
import de.chennemann.agentic.domain.servers.DefaultServerService
import de.chennemann.agentic.domain.messages.MessageRepository
import de.chennemann.agentic.domain.messages.MessageService
import de.chennemann.agentic.domain.projects.ProjectService
import de.chennemann.agentic.domain.sessions.SessionService as SessionCatalogService
import de.chennemann.agentic.domain.servers.ServerRepository as ServerCatalogRepository
import de.chennemann.agentic.domain.servers.ServerService as ServerCatalogService
import de.chennemann.agentic.domain.sync.SynchronizationService
import de.chennemann.agentic.ui.chat.ConversationViewModel
import de.chennemann.agentic.ui.chat.SessionSelectionViewModel
import de.chennemann.agentic.ui.manage.ManageViewModel
import de.chennemann.agentic.ui.logs.LogsViewModel
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import de.chennemann.agentic.db.AgenticDb
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

val appModule = module {
    single {
        Json {
            ignoreUnknownKeys = true
        }
    }
    single {
        OkHttp.create {
            config {
                connectTimeout(5, TimeUnit.SECONDS)
                readTimeout(75, TimeUnit.SECONDS)
                writeTimeout(30, TimeUnit.SECONDS)
            }
        }
    }
    single {
        AgenticDb(
            AndroidSqliteDriver(
                AgenticDb.Schema.synchronous(),
                get(),
                "app.db",
            )
        )
    }
    single<MdnsGateway> { MdnsService(get()) }
    single { NetworkService(get<android.content.Context>()) }
    single<DispatcherProvider> { DefaultDispatcherProvider() }
    single<CoroutineRolloutFlag> { DefaultCoroutineRolloutFlag() }
    single<CoroutineScope>(named(AppScopeName)) {
        CoroutineScope(SupervisorJob() + get<DispatcherProvider>().default)
    }
    single<ConnectivityGateway> { get<NetworkService>() }
    single { LogRedactor() }
    single<LogStoreGateway> { LocalLogRepository(get(), get(), get()) }
    single<LogGateway> { AndroidLogGateway(get(), get(named(AppScopeName)), get()) }
    single<ServerAdapter> { OpenApiServerAdapter(get()) }
    single<ServerGateway> { ServerService(get(), get()) }
    single { ServerRepository(get(), get(), get(), get(), get(), get()) }
    single { SessionCacheRepository(get(), get()) }
    single<SessionRepository> { SqlDelightSessionRepository(get(), get()) }
    single<ProjectRepository> { SqlDelightProjectRepository(get(), get()) }
    single<ServerCatalogRepository> { SqlDelightServerRepository(get(), get()) }
    single<MessageRepository> { SqlDelightMessageRepository(get(), get()) }
    single<SynchronizationService> { DefaultSynchronizationService(get(), get(), get()) }
    single<ServerCatalogService> { DefaultServerService(get(), get()) }
    single<ProjectService> { DefaultProjectService(get(), get(), get()) }
    single<SessionCatalogService> { DefaultSessionService(get(), get()) }
    single<MessageService> { DefaultMessageService(get()) }
    single<ConnectionGateway> { get<ServerRepository>() }
    single<ProjectGateway> { get<ServerRepository>() }
    single<CommandGateway> { get<ServerRepository>() }
    single<MessageGateway> { get<ServerRepository>() }
    single<StreamGateway> { get<ServerRepository>() }
    single<SessionCacheGateway> { get<SessionCacheRepository>() }
    single { MessagePartParser() }
    single { MessageDecorator() }
    single { FocusedMessageProjector(get()) }
    single { SessionSyncPlanner() }
    single { SessionEventReducer() }
    single { SessionStreamCoordinator(get(), get(), get(), get()) }
    single { ReconcileCoordinator() }
    single(createdAtStart = true) {
        SessionService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
            .also { it.start(get(named(AppScopeName))) }
    }
    single<SessionServiceApi> { get<SessionService>() }
    viewModel { ConversationViewModel(get(), get(), get()) }
    viewModel { (projectKey: String) -> SessionSelectionViewModel(projectKey, get(), get(), get()) }
    viewModel { ManageViewModel(get(), get(), get(), get()) }
    viewModel { LogsViewModel(get(), get()) }
}
