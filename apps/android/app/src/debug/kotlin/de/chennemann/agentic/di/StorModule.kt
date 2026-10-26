package de.chennemann.agentic.di

import org.koin.dsl.module
import de.chennemann.agentic.ui.updater.StorUpdaterViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel

val storModule = module {
    viewModel { StorUpdaterViewModel(androidContext(), get()) }
}
