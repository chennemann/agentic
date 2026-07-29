package de.chennemann.agentic.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.ui.chat.ChatViewModel
import de.chennemann.agentic.ui.chat.ChatUiEvent
import de.chennemann.agentic.ui.chat.T3ChatScreen
import de.chennemann.agentic.ui.onboarding.OnboardingViewModel
import de.chennemann.agentic.ui.onboarding.OnboardingUiEvent
import de.chennemann.agentic.ui.onboarding.T3OnboardingScreen
import org.koin.androidx.compose.koinViewModel
import org.koin.core.context.GlobalContext

@Composable
fun AppNavHost() {
    val environments: EnvironmentRepository = GlobalContext.get().get()
    val activeEnvironment by environments.activeEnvironment.collectAsStateWithLifecycle()
    val stack = rememberNavBackStack(OnboardingRoute)
    var manualOnboarding by remember { mutableStateOf(false) }
    val target = if (activeEnvironment == null || manualOnboarding) OnboardingRoute else ChatRoute

    LaunchedEffect(target) {
        if (stack.lastOrNull() != target) {
            stack.clear()
            stack.add(target)
        }
    }

    NavDisplay(
        backStack = stack,
        onBack = {},
        entryProvider = { route ->
            when (route) {
                OnboardingRoute -> NavEntry(route) {
                    val viewModel: OnboardingViewModel = koinViewModel()
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    T3OnboardingScreen(
                        state = state,
                        onEvent = {
                            if (it == OnboardingUiEvent.CloseRequested) {
                                manualOnboarding = false
                            } else {
                                viewModel.onEvent(it)
                            }
                        },
                    )
                }

                ChatRoute -> NavEntry(route) {
                    val viewModel: ChatViewModel = koinViewModel()
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    T3ChatScreen(
                        state = state,
                        onEvent = {
                            if (it == ChatUiEvent.PairEnvironmentRequested) {
                                manualOnboarding = true
                            } else {
                                viewModel.onEvent(it)
                            }
                        },
                    )
                }

                else -> error("Unknown application route")
            }
        },
    )
}
