package de.chennemann.agentic.di

import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import de.chennemann.agentic.data.AndroidNetworkMonitor
import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.auth.KeystoreCredentialStore
import de.chennemann.agentic.data.cache.SqlComposerDraftRepository
import de.chennemann.agentic.data.cache.SqlCommandOutbox
import de.chennemann.agentic.data.cache.SqlSharedTextImportRepository
import de.chennemann.agentic.data.cache.SqlInterfacePreferencesRepository
import de.chennemann.agentic.data.cache.SqlEnvironmentCacheInspector
import de.chennemann.agentic.data.shortcuts.AndroidDynamicShortcutPublisher
import de.chennemann.agentic.data.shortcuts.AndroidShortcutRouteInbox
import de.chennemann.agentic.data.cache.SqlEnvironmentRepository
import de.chennemann.agentic.data.cache.SqlModelFavoriteRepository
import de.chennemann.agentic.data.cache.SqlOrchestrationRepository
import de.chennemann.agentic.data.t3.AndroidClientMetadata
import de.chennemann.agentic.data.t3.EnvironmentAuthClient
import de.chennemann.agentic.data.t3.EnvironmentConfigClient
import de.chennemann.agentic.data.t3.EnvironmentMetadataClient
import de.chennemann.agentic.data.t3.KtorT3Client
import de.chennemann.agentic.data.t3.OrchestrationCommandClient
import de.chennemann.agentic.data.t3.OrchestrationSnapshotClient
import de.chennemann.agentic.data.t3.OrchestrationStreamClient
import de.chennemann.agentic.data.voice.AndroidAudioRecorder
import de.chennemann.agentic.data.voice.KeystoreGroqApiKeyStore
import de.chennemann.agentic.data.voice.KtorGroqTranscriptionClient
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.domain.connection.ConnectionSupervisor
import de.chennemann.agentic.domain.connection.ConnectionController
import de.chennemann.agentic.domain.connection.NetworkMonitor
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.EnvironmentRemover
import de.chennemann.agentic.domain.environment.EnvironmentCacheRemover
import de.chennemann.agentic.domain.environment.EnvironmentService
import de.chennemann.agentic.domain.environment.EnvironmentSelector
import de.chennemann.agentic.domain.environment.EnvironmentCacheInspector
import de.chennemann.agentic.domain.environment.EnvironmentCacheActions
import de.chennemann.agentic.domain.environment.EnvironmentCacheService
import de.chennemann.agentic.domain.environment.RegisteredEnvironmentRemover
import de.chennemann.agentic.domain.orchestration.ChatActions
import de.chennemann.agentic.domain.orchestration.ChatService
import de.chennemann.agentic.domain.orchestration.CommandDispatcher
import de.chennemann.agentic.domain.orchestration.CommandOutbox
import de.chennemann.agentic.domain.sharing.SharedTextImportRepository
import de.chennemann.agentic.domain.shortcuts.DynamicShortcutPublisher
import de.chennemann.agentic.domain.shortcuts.ShortcutRouteInbox
import de.chennemann.agentic.domain.shortcuts.DefaultShortcutCoordinator
import de.chennemann.agentic.domain.shortcuts.ShortcutCoordinator
import de.chennemann.agentic.domain.orchestration.DurableCommandDispatcher
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.domain.orchestration.PendingCommandReplayer
import de.chennemann.agentic.domain.orchestration.ProjectActions
import de.chennemann.agentic.domain.orchestration.ProjectService
import de.chennemann.agentic.ui.chat.workflow.CacheAdministration
import de.chennemann.agentic.ui.chat.workflow.DefaultCacheAdministration
import de.chennemann.agentic.ui.chat.workflow.DefaultProjectWorkflow
import de.chennemann.agentic.ui.chat.workflow.DefaultSharedTextWorkflow
import de.chennemann.agentic.ui.chat.workflow.DefaultShortcutWorkflow
import de.chennemann.agentic.ui.chat.workflow.ProjectWorkflow
import de.chennemann.agentic.ui.chat.workflow.SharedTextWorkflow
import de.chennemann.agentic.ui.chat.workflow.ShortcutWorkflow
import de.chennemann.agentic.domain.orchestration.ThreadService
import de.chennemann.agentic.domain.orchestration.ThreadActions
import de.chennemann.agentic.domain.preferences.ComposerDraftRepository
import de.chennemann.agentic.domain.preferences.ModelFavoriteRepository
import de.chennemann.agentic.domain.preferences.InterfacePreferencesRepository
import de.chennemann.agentic.domain.voice.AudioRecorder
import de.chennemann.agentic.domain.voice.AudioTranscriptionClient
import de.chennemann.agentic.domain.voice.GroqApiKeyStore
import de.chennemann.agentic.domain.voice.GroqVoiceInputService
import de.chennemann.agentic.domain.voice.VoiceInputService
import de.chennemann.agentic.t3.contract.PortableJson
import de.chennemann.agentic.ui.chat.ChatViewModel
import de.chennemann.agentic.ui.onboarding.OnboardingViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {
    single<DispatcherProvider> { DefaultDispatcherProvider() }
    single<CoroutineScope>(named(AppScopeName)) {
        CoroutineScope(SupervisorJob() + get<DispatcherProvider>().default)
    }
    single {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(PortableJson)
            }
            engine {
                config {
                    connectTimeout(10, TimeUnit.SECONDS)
                    readTimeout(0, TimeUnit.MILLISECONDS)
                    writeTimeout(30, TimeUnit.SECONDS)
                }
            }
        }
    }
    single {
        AgenticDb(
            AndroidSqliteDriver(
                AgenticDb.Schema,
                get(),
                "agentic-t3.db",
            ),
        )
    }
    single<CredentialStore> { KeystoreCredentialStore(get()) }
    single<GroqApiKeyStore> { KeystoreGroqApiKeyStore(get()) }
    single<AudioRecorder> { AndroidAudioRecorder(get()) }
    single<AudioTranscriptionClient> { KtorGroqTranscriptionClient(get()) }
    single<VoiceInputService> {
        GroqVoiceInputService(
            recorder = get(),
            apiKeys = get(),
            transcriptions = get(),
            ioDispatcher = get<DispatcherProvider>().io,
        )
    }
    single<NetworkMonitor> { AndroidNetworkMonitor(get()) }
    single {
        KtorT3Client(get())
    }
    single<EnvironmentMetadataClient> { get<KtorT3Client>() }
    single<EnvironmentAuthClient> { get<KtorT3Client>() }
    single<EnvironmentConfigClient> { get<KtorT3Client>() }
    single<OrchestrationSnapshotClient> { get<KtorT3Client>() }
    single<OrchestrationCommandClient> { get<KtorT3Client>() }
    single<OrchestrationStreamClient> { get<KtorT3Client>() }
    single<EnvironmentRepository> {
        SqlEnvironmentRepository(
            database = get(),
            dispatcher = get<DispatcherProvider>().io,
            scope = get(named(AppScopeName)),
        )
    }
    single<OrchestrationRepository> {
        SqlOrchestrationRepository(
            database = get(),
            dispatcher = get<DispatcherProvider>().io,
            scope = get(named(AppScopeName)),
        )
    }
    single<ModelFavoriteRepository> {
        SqlModelFavoriteRepository(
            database = get(),
            dispatcher = get<DispatcherProvider>().io,
        )
    }
    single<ComposerDraftRepository> {
        SqlComposerDraftRepository(
            database = get(),
            dispatcher = get<DispatcherProvider>().io,
        )
    }
    single<CommandOutbox> { SqlCommandOutbox(get(), get<DispatcherProvider>().io) }
    single<SharedTextImportRepository> { SqlSharedTextImportRepository(get(), get<DispatcherProvider>().io) }
    single<InterfacePreferencesRepository> { SqlInterfacePreferencesRepository(get(), get<DispatcherProvider>().io) }
    single<EnvironmentCacheInspector> { SqlEnvironmentCacheInspector(get(), get<DispatcherProvider>().io) }
    single<EnvironmentCacheActions> { EnvironmentCacheService(get(), get(), get(), get()) }
    single<DynamicShortcutPublisher> { AndroidDynamicShortcutPublisher(get()) }
    single<ShortcutRouteInbox> { AndroidShortcutRouteInbox() }
    single<ShortcutCoordinator>(createdAtStart = true) {
        DefaultShortcutCoordinator(get(), get(), get(), get(), get(), get(), get(named(AppScopeName)))
    }
    single {
        DurableCommandDispatcher(
            environments = get(),
            credentials = get(),
            client = get(),
            outbox = get(),
        )
    }
    single<CommandDispatcher> { get<DurableCommandDispatcher>() }
    single<PendingCommandReplayer> { get<DurableCommandDispatcher>() }
    single {
        AndroidClientMetadata(
            label = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim(),
            os = "Android ${android.os.Build.VERSION.RELEASE}",
        )
    }
    single {
        EnvironmentService(get(), get(), get(), get(), get(), get())
    }
    single<EnvironmentSelector> { get<EnvironmentService>() }
    single<EnvironmentCacheRemover> {
        EnvironmentCacheRemover { get<OrchestrationRepository>().clearEnvironment(it) }
    }
    single<EnvironmentRemover> { RegisteredEnvironmentRemover(get(), get(), get()) }
    single {
        ThreadService(get(), get(), get())
    }
    single<ThreadActions> { get<ThreadService>() }
    single {
        ChatService(get())
    }
    single<ChatActions> { get<ChatService>() }
    single { ProjectService(get(), get()) }
    single<ProjectActions> { get<ProjectService>() }
    factory<ProjectWorkflow> { DefaultProjectWorkflow(get(), get(), get()) }
    factory<SharedTextWorkflow> {
        DefaultSharedTextWorkflow(get(), get(), get(), get(), get(), get())
    }
    factory<ShortcutWorkflow> {
        DefaultShortcutWorkflow(get())
    }
    factory<CacheAdministration> { DefaultCacheAdministration(get()) }
    single(createdAtStart = true) {
        ConnectionSupervisor(
            environments = get(),
            orchestration = get(),
            credentials = get(),
            metadata = get(),
            config = get(),
            snapshots = get(),
            streams = get(),
            network = get(),
            pendingCommands = get(),
            scope = get(named(AppScopeName)),
        )
    }
    single<ConnectionController> { get<ConnectionSupervisor>() }
    viewModel { OnboardingViewModel(get(), get(), get()) }
    viewModel {
        ChatViewModel(
            environments = get(),
            repository = get(),
            connection = get(),
            environmentService = get(),
            environmentRemover = get(),
            projectWorkflow = get(),
            threads = get(),
            chat = get(),
            composerDrafts = get(),
            modelFavorites = get(),
            mappingDispatcher = get<DispatcherProvider>().default,
            groqApiKeys = get(),
            voiceInput = get(),
            commandOutbox = get(),
            sharedTextWorkflow = get(),
            shortcutWorkflow = get(),
            interfacePreferences = get(),
            cacheAdministration = get(),
        )
    }
}
