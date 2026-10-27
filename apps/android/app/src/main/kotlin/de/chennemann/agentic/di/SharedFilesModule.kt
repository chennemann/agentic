package de.chennemann.agentic.di

import de.chennemann.agentic.data.t3.KtorT3Client
import de.chennemann.agentic.data.t3.SharedFileAssetClient
import de.chennemann.agentic.domain.attachments.SharedFileLoader
import de.chennemann.agentic.domain.attachments.SharedFilesService
import de.chennemann.agentic.ui.files.SharedFileViewModel
import de.chennemann.agentic.ui.files.SharedMediaViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val sharedFilesModule = module {
    single<SharedFileAssetClient> { get<KtorT3Client>() }
    single<SharedFileLoader> {
        SharedFilesService(
            environments = get(),
            credentials = get(),
            client = get(),
            // Shared through FileProvider (res/xml/shared_file_paths.xml) when opened in another app.
            directory = java.io.File(get<android.content.Context>().cacheDir, "shared-files"),
            ioDispatcher = get<DispatcherProvider>().io,
        )
    }
    viewModel { SharedFileViewModel(get()) }
    viewModel { SharedMediaViewModel(get()) }
}
