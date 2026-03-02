package de.chennemann.agentic.di

import de.chennemann.agentic.data.AndroidLogGateway
import de.chennemann.agentic.data.LocalLogRepository
import de.chennemann.agentic.data.MdnsService
import de.chennemann.agentic.data.MdnsGateway
import de.chennemann.agentic.data.NetworkService
import de.chennemann.agentic.data.v2.OpenApiServerAdapter
import de.chennemann.agentic.data.ServerRepository
import de.chennemann.agentic.data.ServerService
import de.chennemann.agentic.data.ServerGateway
import de.chennemann.agentic.data.SessionCacheRepository
import de.chennemann.agentic.data.v2.SqlDelightMessageRepository
import de.chennemann.agentic.data.v2.SqlDelightProjectRepository
import de.chennemann.agentic.data.v2.SqlDelightServerRepository
import de.chennemann.agentic.data.v2.SqlDelightSessionRepository
import de.chennemann.agentic.domain.v2.OpenCodeServerAdapter
import de.chennemann.agentic.domain.v2.message.DefaultMessageService
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
import de.chennemann.agentic.domain.v2.DefaultSynchronizationService
import de.chennemann.agentic.domain.v2.projects.ProjectRepository
import de.chennemann.agentic.domain.v2.projects.DefaultProjectService
import de.chennemann.agentic.domain.v2.session.SessionRepository
import de.chennemann.agentic.domain.v2.session.DefaultSessionService
import de.chennemann.agentic.domain.v2.servers.DefaultServerService
import de.chennemann.agentic.domain.v2.message.MessageRepository as MessageRepositoryV2
import de.chennemann.agentic.domain.v2.message.MessageService as MessageServiceV2
import de.chennemann.agentic.domain.v2.projects.ProjectService as ProjectServiceV2
import de.chennemann.agentic.domain.v2.session.SessionService as SessionServiceV2
import de.chennemann.agentic.domain.v2.servers.ServerRepository as ServerRepositoryV2
import de.chennemann.agentic.domain.v2.servers.ServerService as ServerServiceV2
import de.chennemann.agentic.domain.v2.SynchronizationService as SynchronizationServiceV2
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
    single<OpenCodeServerAdapter> { OpenApiServerAdapter(get()) }
    single<ServerGateway> { ServerService(get(), get()) }
    single { ServerRepository(get(), get(), get(), get(), get(), get()) }
    single { SessionCacheRepository(get(), get()) }
    single<SessionRepository> { SqlDelightSessionRepository(get(), get()) }
    single<ProjectRepository> { SqlDelightProjectRepository(get(), get()) }
    single<ServerRepositoryV2> { SqlDelightServerRepository(get(), get()) }
    single<MessageRepositoryV2> { SqlDelightMessageRepository(get(), get()) }
    single<SynchronizationServiceV2> { DefaultSynchronizationService(get(), get(), get()) }
    single<ServerServiceV2> { DefaultServerService(get(), get(), get()) }
    single<ProjectServiceV2> { DefaultProjectService(get(), get()) }
    single<SessionServiceV2> { DefaultSessionService(get(), get()) }
    single<MessageServiceV2> { DefaultMessageService(get()) }
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
    viewModel { ConversationViewModel(get(), get()) }
    viewModel { (projectKey: String) -> SessionSelectionViewModel(projectKey, get(), get(), get()) }
    viewModel { ManageViewModel(get(), get(), get(), get()) }
    viewModel { LogsViewModel(get(), get()) }
}
