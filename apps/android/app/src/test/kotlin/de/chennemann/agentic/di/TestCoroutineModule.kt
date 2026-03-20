package de.chennemann.agentic.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

class TestDispatcherProvider(
    private val dispatcher: CoroutineDispatcher,
) : DispatcherProvider {
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
    override val mainImmediate: CoroutineDispatcher = dispatcher
}

fun testCoroutineModule(
    dispatcher: TestDispatcher = StandardTestDispatcher(),
): Module {
    return module {
        single<DispatcherProvider> { TestDispatcherProvider(dispatcher) }
        single<CoroutineScope>(named(AppScopeName)) {
            CoroutineScope(SupervisorJob() + dispatcher)
        }
    }
}
