package de.chennemann.agentic.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import de.chennemann.agentic.data.auth.AndroidClientCertificateSelector
import de.chennemann.agentic.data.auth.ClientCertificateStore
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.orchestration.FileLinkTarget
import de.chennemann.agentic.t3.contract.ProjectScript
import de.chennemann.agentic.t3.contract.ProjectScriptIcon
import de.chennemann.agentic.ui.chat.ChatViewModel
import de.chennemann.agentic.ui.chat.ChatUiEvent
import de.chennemann.agentic.ui.chat.ProjectScriptUi
import de.chennemann.agentic.ui.chat.T3ChatScreen
import de.chennemann.agentic.ui.files.WorkspaceFilesScreen
import de.chennemann.agentic.ui.files.WorkspaceFilesViewModel
import de.chennemann.agentic.ui.onboarding.OnboardingUiEvent
import de.chennemann.agentic.ui.onboarding.OnboardingViewModel
import de.chennemann.agentic.ui.onboarding.T3OnboardingScreen
import de.chennemann.agentic.ui.review.ReviewDiffScreen
import de.chennemann.agentic.ui.review.ReviewDiffViewModel
import de.chennemann.agentic.ui.terminal.TerminalScreen
import de.chennemann.agentic.ui.terminal.TerminalViewModel
import de.chennemann.agentic.ui.updater.StorUpdaterScreen
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.context.GlobalContext

@Composable
fun AppNavHost() {
    val environments: EnvironmentRepository = GlobalContext.get().get()
    val clientCertificateSelector: AndroidClientCertificateSelector = GlobalContext.get().get()
    val clientCertificates: ClientCertificateStore = GlobalContext.get().get()
    val activity = requireNotNull(LocalContext.current.findActivity())
    val scope = rememberCoroutineScope()
    val activeEnvironment by environments.activeEnvironment.collectAsStateWithLifecycle()
    var manualOnboarding by rememberSaveable { mutableStateOf(false) }
    val target = if (activeEnvironment == null || manualOnboarding) OnboardingRoute else ChatRoute

    val stack = rememberAppBackStack(target)

    NavDisplay(
        backStack = stack,
        entryDecorators = rememberAppEntryDecorators(),
        onBack = { if (stack.size > 1) stack.removeLastOrNull() },
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
                        onChooseClientCertificate = { certificate ->
                            scope.launch {
                                clientCertificateSelector.choose(
                                    activity = activity,
                                    serverUrl = certificate.serverUrl,
                                    preselectedAlias = clientCertificates.aliasFor(certificate.serverUrl),
                                )?.let {
                                    viewModel.onEvent(OnboardingUiEvent.ClientCertificateSelected(it))
                                }
                            }
                        },
                    )
                }

                ChatRoute -> NavEntry(route) {
                    val viewModel: ChatViewModel = koinViewModel()
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val onEvent: (ChatUiEvent) -> Unit = {
                        if (it == ChatUiEvent.PairEnvironmentRequested) {
                            manualOnboarding = true
                        } else {
                            viewModel.onEvent(it)
                        }
                    }
                    val onOpenFiles: (String) -> Unit = { threadId -> stack.add(WorkspaceFilesRoute(threadId)) }
                    val onOpenFileLink: (String, FileLinkTarget) -> Unit = { threadId, target ->
                        stack.add(WorkspaceFilesRoute(threadId, target.path, target.line))
                    }
                    val onOpenUpdater: () -> Unit = { stack.add(StorUpdaterRoute) }
                    val onOpenUsage: () -> Unit = { viewModel.onEvent(ChatUiEvent.PickerDismissed); stack.add(UsageRoute) }
                    val onOpenTerminal: (String) -> Unit = { threadId -> stack.add(TerminalRoute(threadId)) }
                    val onRunProjectScript: (String, ProjectScriptUi) -> Unit = { threadId, script ->
                        stack.add(
                            TerminalRoute(
                                threadId,
                                ProjectScript(
                                    id = script.id,
                                    name = script.name,
                                    command = script.command,
                                    icon = ProjectScriptIcon.PLAY,
                                    runOnWorktreeCreate = script.setup,
                                ),
                            ),
                        )
                    }
                    T3ChatScreen(
                        state = state,
                        composerDraft = viewModel.draft,
                        onEvent = onEvent,
                        onOpenFiles = onOpenFiles,
                        onOpenFileLink = onOpenFileLink,
                        onOpenUpdater = onOpenUpdater,
                        onOpenUsage = onOpenUsage,
                        onOpenTerminal = onOpenTerminal,
                        onOpenChanges = { threadId -> stack.add(ReviewDiffRoute(threadId)) },
                        onRunProjectScript = onRunProjectScript,
                    )
                }

                is WorkspaceFilesRoute -> NavEntry(route) {
                    val viewModel: WorkspaceFilesViewModel = koinViewModel()
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val linkedPath = route.path
                    LaunchedEffect(route) {
                        if (linkedPath == null) viewModel.load(route.threadId) else viewModel.show(route.threadId, linkedPath, route.line)
                    }
                    WorkspaceFilesScreen(
                        state = state,
                        onBack = { stack.removeLastOrNull() },
                        onRefresh = viewModel::refresh,
                        onOpenFile = { viewModel.open(it) },
                        // A linked file has no tree behind it, so closing it returns to the chat.
                        onClosePreview = {
                            if (linkedPath == null) viewModel.closePreview() else stack.removeLastOrNull()
                        },
                    )
                }

                UsageRoute -> NavEntry(route) {
                    val viewModel: de.chennemann.agentic.ui.usage.UsageViewModel = koinViewModel()
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    de.chennemann.agentic.ui.usage.UsageScreen(state, { stack.removeLastOrNull() }, viewModel::selectMetric, viewModel::selectDays, viewModel::selectEnvironment, viewModel::selectAll, viewModel::refresh, viewModel::redeem, viewModel::savePrice, viewModel::enableKeychain)
                }

                StorUpdaterRoute -> NavEntry(route) {
                    StorUpdaterScreen(onBack = { stack.removeLastOrNull() })
                }

                is ReviewDiffRoute -> NavEntry(route) {
                    val viewModel: ReviewDiffViewModel = koinViewModel()
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    LaunchedEffect(route.threadId) { viewModel.load(route.threadId) }
                    ReviewDiffScreen(state, { stack.removeLastOrNull() }, viewModel::refresh, viewModel::select)
                }

                is TerminalRoute -> NavEntry(route) {
                    val viewModel: TerminalViewModel = koinViewModel()
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    LaunchedEffect(route) { viewModel.load(route.threadId, route.script) }
                    TerminalScreen(
                        state = state,
                        onBack = { stack.removeLastOrNull() },
                        onCreate = viewModel::create,
                        onSelect = viewModel::select,
                        onReconnect = viewModel::reconnect,
                        onClear = viewModel::clear,
                        onRestart = viewModel::restart,
                        onClose = viewModel::close,
                    )
                }

                else -> error("Unknown application route")
            }
        },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
