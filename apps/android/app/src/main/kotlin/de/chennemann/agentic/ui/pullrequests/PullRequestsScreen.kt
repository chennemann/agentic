package de.chennemann.agentic.ui.pullrequests

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import de.chennemann.agentic.streamingmarkdown.StreamingMarkdownText
import de.chennemann.agentic.ui.review.*
import expo.modules.t3reviewdiff.T3ReviewDiffView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRequestsScreen(
    state: PullRequestsUiState,
    onBack: () -> Unit,
    onSelectEnvironment: (String) -> Unit,
    onFilter: (String) -> Unit,
    onInvolvement: (String) -> Unit,
    onSearch: (String) -> Unit,
    onRefresh: () -> Unit,
    onMore: () -> Unit,
    onOpen: (String) -> Unit,
    onCloseDetail: () -> Unit,
    onChanges: (Boolean) -> Unit,
    onComment: (String) -> Unit,
    onReview: (String, String) -> Unit,
    onAction: (String) -> Unit,
    onResolve: (String, Boolean) -> Unit,
    onReply: (String, String) -> Unit,
    onOpenThread: (String) -> Unit,
    onDiscuss: () -> Unit
) {
    var environmentMenu by remember { mutableStateOf(false) }
    val detail = state.detail
    val selected = state.selectedId != null
    BackHandler(selected) { if (!state.writing) onCloseDetail() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column {
                    Text("Pull requests", maxLines = 1)
                    Text(state.environments.firstOrNull { it.id == state.environmentId }?.label ?: "Choose environment",
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } },
                navigationIcon = { TextButton(onClick = if (selected) onCloseDetail else onBack, enabled = !state.writing) { Text("Back") } },
                actions = {
                    if (!selected) Box {
                        TextButton(onClick = { environmentMenu = true }) { Text("Environment") }
                        DropdownMenu(environmentMenu, { environmentMenu = false }) {
                            state.environments.forEach { environment -> DropdownMenuItem(text = { Text(environment.label) },
                                onClick = { environmentMenu = false; onSelectEnvironment(environment.id) }) }
                        }
                    }
                    TextButton(onClick = onRefresh, enabled = !state.loading && !state.writing && !state.detailLoading) { Text("Refresh") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).testTag("pull-requests-screen")) {
            if (selected) {
                if (state.detailLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.detailError?.let { ErrorNotice(it, onRefresh) }
                state.actionError?.let { ErrorNotice(it, onRefresh) }
                if (detail != null) Detail(state, onChanges, onComment, onReview, onAction, onResolve, onReply, onOpenThread, onDiscuss)
            } else {
                var query by rememberSaveable(state.environmentId) { mutableStateOf(state.query) }
                OutlinedTextField(query, { query = it.take(200); onSearch(query) }, Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    label = { Text("Search pull requests") }, singleLine = true)
                ChoiceRow(listOf("open", "merged", "closed", "all"), state.filter, onFilter)
                ChoiceRow(listOf("all", "authored", "reviewing"), state.involvement, onInvolvement)
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.listError?.let { item { ErrorNotice(it, onRefresh) } }
                    items(state.warnings) { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (!state.loading && state.rows.isEmpty() && state.listError == null) item {
                        Text(if (state.environments.isEmpty()) "Pair an environment to see pull requests." else "No pull requests match these filters.", Modifier.padding(20.dp))
                    }
                    items(state.rows, key = { it.id }) { row ->
                        OutlinedCard(onClick = { onOpen(row.id) }, modifier = Modifier.fillMaxWidth().testTag("pull-request-row")) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(row.title, style = MaterialTheme.typography.titleMedium)
                                Text(row.subtitle, style = MaterialTheme.typography.bodySmall)
                                Text(row.status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                if (row.labels.isNotBlank()) Text(row.labels, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (state.more) item { TextButton(onClick = onMore, enabled = !state.loading) { Text("Load more pull requests") } }
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option -> FilterChip(selected == option, { onSelect(option) }, label = { Text(option.replaceFirstChar { it.uppercase() }) }) }
    }
}

@Composable
private fun ErrorNotice(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun ColumnScope.Detail(
    state: PullRequestsUiState,
    onChanges: (Boolean) -> Unit,
    onComment: (String) -> Unit,
    onReview: (String, String) -> Unit,
    onAction: (String) -> Unit,
    onResolve: (String, Boolean) -> Unit,
    onReply: (String, String) -> Unit,
    onOpenThread: (String) -> Unit,
    onDiscuss: () -> Unit
) {
    val detail = requireNotNull(state.detail)
    val uri = LocalUriHandler.current
    var section by rememberSaveable(state.selectedId) { mutableStateOf("Overview") }
    var actionMenu by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<PrActionUi?>(null) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(detail.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(detail.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(detail.status, style = MaterialTheme.typography.labelMedium)
        Row {
            TextButton(onClick = { runCatching { uri.openUri(detail.url) } }) { Text("Open on host") }
            if (detail.actions.isNotEmpty()) Box {
                TextButton(onClick = { actionMenu = true }, enabled = !state.writing) { Text("Actions") }
                DropdownMenu(actionMenu, { actionMenu = false }) {
                    detail.actions.forEach { action -> DropdownMenuItem(text = { Text(action.label) }, onClick = {
                        actionMenu = false; confirmation = action
                    }) }
                }
            }
        }
        if (!state.canWrite) Text("To comment, review, or change this pull request, pair again with source-control:write permission.",
            style = MaterialTheme.typography.bodySmall)
    }
    val sections = listOf("Overview", "Checks", "Activity") + if (detail.canDiff) listOf("Changes") else emptyList()
    ChoiceRow(sections, section) { section = it }
    LaunchedEffect(section, state.selectedId, state.diffLoaded, state.detailLoading) {
        if (section == "Changes" && !state.diffLoaded && !state.detailLoading) onChanges(false)
    }
    when (section) {
        "Changes" -> {
            state.diffError?.let { ErrorNotice(it) { onChanges(false) } }
            if (!state.diffLoaded) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else {
                if (state.diffTruncated) Text("The host returned a partial patch. Some files or lines may be omitted.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                if (state.moreDiff) TextButton(onClick = { onChanges(true) }) { Text("Load more changes") }
                if (state.document.files.isEmpty() && state.diffError == null) Text("No patch content returned by this host.", Modifier.padding(16.dp))
                else PullRequestDiffSurface(state.document, "${state.selectedId}:${state.document.hashCode()}", Modifier.weight(1f))
            }
        }
        else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (section) {
                "Overview" -> {
                    item { StreamingMarkdownText(detail.body.ifBlank { "No description." }, streaming = false) }
                    if (detail.labels.isNotBlank()) item { Text("Labels: ${detail.labels}") }
                    if (detail.reviewers.isNotBlank()) item { Text("Reviewers: ${detail.reviewers}") }
                    item { TextButton(onClick = onDiscuss, enabled = !state.writing) { Text("Discuss with an agent") } }
                    if (state.linkedThreads.isNotEmpty()) item { Text("Linked conversations", style = MaterialTheme.typography.titleMedium) }
                    items(state.linkedThreads, key = { it.id }) { thread -> TextButton(onClick = { onOpenThread(thread.id) }, enabled = !state.writing) { Text(thread.title) } }
                    if (detail.canComment || detail.verdicts.isNotEmpty()) item {
                        CommentEditor(state.selectedId.orEmpty(), state.submitted, detail.canComment, detail.verdicts, state.writing, onComment, onReview)
                    }
                }
                "Checks" -> {
                    if (detail.checks.isEmpty()) item { Text("No checks reported.") }
                    items(detail.checks, key = { "${it.name}:${it.url}" }) { check ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(check.name, style = MaterialTheme.typography.titleSmall)
                                Text(check.status, color = if (check.status == "failure") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                if (check.description.isNotBlank()) Text(check.description)
                                check.url?.let { url -> TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text("View check") } }
                            }
                        }
                    }
                }
                "Activity" -> {
                    state.activityError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                    if (!state.activityLoaded && state.activityError == null) item { CircularProgressIndicator() }
                    if (state.activityTruncated) item { Text("Activity is incomplete. Open on host to see the full conversation.") }
                    items(state.comments, key = { "comment:${it.id}" }) { comment -> Comment(comment) }
                    items(state.threads, key = { "thread:${it.id}" }) { thread ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(thread.path, style = MaterialTheme.typography.titleSmall)
                                Text(if (thread.resolved) "Resolved" else "Unresolved")
                                thread.comments.forEach { Comment(it) }
                                if (thread.truncated) Text("More comments available on the host.", style = MaterialTheme.typography.bodySmall)
                                if (thread.canResolve) TextButton(onClick = { onResolve(thread.id, !thread.resolved) }, enabled = !state.writing) { Text(if (thread.resolved) "Reopen discussion" else "Resolve discussion") }
                                if (thread.canReply) CommentEditor("${state.selectedId}:${thread.id}", state.submitted, true, emptyList(), state.writing,
                                    { onReply(thread.id, it) }, { _, _ -> })
                            }
                        }
                    }
                    if (state.commits.isNotEmpty()) item { Text("Commits", style = MaterialTheme.typography.titleMedium) }
                    items(state.commits) { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (state.activityLoaded && state.comments.isEmpty() && state.threads.isEmpty() && state.commits.isEmpty()) item { Text("No activity yet.") }
                }
            }
        }
    }
    confirmation?.let { action ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(action.label) },
            text = { Text("Apply this action to ${detail.subtitle}? This changes the pull request on its host.") },
            confirmButton = { TextButton(onClick = { confirmation = null; onAction(action.id) }, enabled = !state.writing) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } })
    }
}

@Composable
private fun Comment(comment: PrCommentUi) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(comment.author, style = MaterialTheme.typography.titleSmall)
        if (comment.context.isNotBlank()) Text(comment.context, style = MaterialTheme.typography.labelSmall)
        StreamingMarkdownText(comment.body, streaming = false)
        HorizontalDivider()
    }
}

@Composable
private fun CommentEditor(key: String, submitted: Int, canComment: Boolean, verdicts: List<String>, busy: Boolean,
    onComment: (String) -> Unit, onReview: (String, String) -> Unit) {
    var body by rememberSaveable(key) { mutableStateOf("") }
    var verdict by rememberSaveable(key) { mutableStateOf("comment") }
    var confirm by remember { mutableStateOf(false) }
    var lastSubmission by remember(key) { mutableIntStateOf(submitted) }
    LaunchedEffect(submitted) { if (submitted != lastSubmission) { body = ""; lastSubmission = submitted } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(body, { body = it.take(65_536) }, Modifier.fillMaxWidth(), label = { Text("Write a comment or review") }, minLines = 3, enabled = !busy)
        if (verdicts.isNotEmpty()) ChoiceRow(verdicts, verdict) { verdict = it }
        Row {
            if (canComment) TextButton(onClick = { onComment(body) }, enabled = !busy && body.isNotBlank()) { Text("Comment") }
            if (verdict in verdicts) TextButton(onClick = { confirm = true }, enabled = !busy && (verdict != "comment" || body.isNotBlank())) { Text("Submit review") }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp))
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Submit ${verdict.replace('-', ' ')} review?") },
        text = { Text("Your review will be visible on the pull request.") },
        confirmButton = { TextButton(onClick = { confirm = false; onReview(verdict, body) }) { Text("Submit") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

@Composable
private fun PullRequestDiffSurface(document: ReviewDocument, revision: String, modifier: Modifier) {
    val appearance = rememberReviewAppearance(false)
    val binding = remember { ReviewDiffBinding() }
    var payload by remember(document, appearance.tokenColors) { mutableStateOf<ReviewPayload?>(null) }
    var collapsed by remember(revision) { mutableStateOf(emptyList<String>()) }
    var viewed by rememberSaveable(revision) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(document, appearance.tokenColors) { payload = withContext(Dispatchers.Default) { reviewPayload(document, appearance.tokenColors, appearance.italicTokens) } }
    payload?.let { ready ->
        AndroidView(factory = { T3ReviewDiffView(it) }, modifier = modifier.fillMaxWidth().testTag("pull-request-diff"),
            onRelease = { it.cleanup(); binding.release() }, update = { view ->
                view.onToggleFile = { event -> (event["fileId"] as? String)?.let { id -> collapsed = if (id in collapsed) collapsed - id else collapsed + id } }
                view.onToggleViewedFile = { event -> (event["fileId"] as? String)?.let { id -> viewed = if (id in viewed) viewed - id else viewed + id } }
                binding.bind(view, revision, appearance, ready, collapsed, viewed)
            })
    }
}
