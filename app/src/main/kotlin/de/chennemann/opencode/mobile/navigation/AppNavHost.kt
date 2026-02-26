package de.chennemann.opencode.mobile.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import de.chennemann.opencode.mobile.ui.chat.AgentChatScreen
import de.chennemann.opencode.mobile.ui.chat.ConversationViewModel
import de.chennemann.opencode.mobile.ui.chat.SessionSelectionBottomSheet
import de.chennemann.opencode.mobile.ui.chat.SessionSelectionViewModel
import de.chennemann.opencode.mobile.ui.logs.LogsScreen
import de.chennemann.opencode.mobile.ui.logs.LogsViewModel
import de.chennemann.opencode.mobile.ui.manage.ManageScreen
import de.chennemann.opencode.mobile.ui.manage.ManageViewModel
import kotlinx.coroutines.flow.Flow
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AppNavHost() {
    val stack = rememberNavBackStack(AgentChatRoute)
    val owner = checkNotNull(LocalViewModelStoreOwner.current)
    NavDisplay(
        backStack = stack,
        sceneStrategy = BottomSheetSceneStrategy(),
        onBack = {
            dispatchNavAction(
                stack = stack,
                action = NavEvent.NavigateBack,
            )
        },
        entryProvider = { key ->
            when (key) {
                is AgentChatRoute -> NavEntry(key) {
                    val conversationModel: ConversationViewModel = koinViewModel(viewModelStoreOwner = owner)
                    val conversationState by conversationModel.state.collectAsStateWithLifecycle()

                    CollectNavigation(conversationModel.nav) {
                        dispatchNavAction(stack, it)
                    }

                    AgentChatScreen(
                        state = conversationState,
                        onEvent = conversationModel::onEvent,
                    )
                }

                is SessionSelectionBottomSheetRoute -> NavEntry(
                    key = key,
                    metadata = BottomSheetSceneStrategy.bottomSheet(),
                ) {
                    val model: SessionSelectionViewModel = koinViewModel(
                        key = "quick-switch-${key.projectKey}",
                        viewModelStoreOwner = owner,
                        parameters = { parametersOf(key.projectKey) },
                    )
                    val menu by model.state.collectAsStateWithLifecycle()
                    LaunchedEffect(model) {
                        model.refresh()
                    }
                    CollectNavigation(model.nav) {
                        dispatchNavAction(stack, it)
                    }
                    SessionSelectionBottomSheet(
                        menu = menu,
                        onEvent = model::onEvent,
                    )
                }

                is WorkspaceHubRoute -> NavEntry(key) {
                    val model: ManageViewModel = koinViewModel()
                    val state by model.state.collectAsStateWithLifecycle()
                    CollectNavigation(model.nav) {
                        dispatchNavAction(stack, it)
                    }
                    ManageScreen(
                        state = state,
                        onEvent = model::onEvent,
                    )
                }

                is LogsRoute -> NavEntry(key) {
                    val model: LogsViewModel = koinViewModel()
                    val state by model.state.collectAsStateWithLifecycle()
                    CollectNavigation(model.nav) {
                        dispatchNavAction(stack, it)
                    }
                    LogsScreen(
                        state = state,
                        onEvent = model::onEvent,
                    )
                }

                else -> error("Unknown route: $key")
            }
        },
    )
}

@Composable
private fun CollectNavigation(
    navEvents: Flow<NavEvent>,
    onAction: (NavEvent) -> Unit,
) {
    LaunchedEffect(navEvents) {
        navEvents.collect(onAction)
    }
}

internal fun dispatchNavAction(
    stack: MutableList<NavKey>,
    action: NavEvent,
) {
    when (action) {
        is NavEvent.NavigateTo -> {
            when (action.route) {
                AgentChatRoute -> returnToAgentChatRoot(stack)
                WorkspaceHubRoute -> stack.add(WorkspaceHubRoute)
                LogsRoute -> stack.add(LogsRoute)
                is SessionSelectionBottomSheetRoute -> {
                    val sheet = action.route
                    val current = stack.lastOrNull()
                    if (current is SessionSelectionBottomSheetRoute && current.projectKey == sheet.projectKey) {
                        return
                    }
                    if (current is SessionSelectionBottomSheetRoute) {
                        stack.removeLastOrNull()
                    }
                    stack.add(sheet)
                }
            }
        }

        NavEvent.NavigateBack -> {
            stack.removeLastOrNull()
        }
    }
}

internal fun returnToAgentChatRoot(stack: MutableList<NavKey>) {
    while (stack.isNotEmpty() && stack.lastOrNull() != AgentChatRoute) {
        stack.removeLastOrNull()
    }
    if (stack.isEmpty()) {
        stack.add(AgentChatRoute)
    }
}
