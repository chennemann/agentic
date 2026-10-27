package de.chennemann.agentic.ui.pullrequests

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleStartEffect
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
    onDiscuss: () -> Unit,
    extra: PullRequestExtraActions = PullRequestExtraActions()
) {
    var environmentMenu by remember { mutableStateOf(false) }
    val detail = state.detail
    val selected = state.selectedId != null
    BackHandler(selected) { if (!state.writing) onCloseDetail() }
    val currentExtra by rememberUpdatedState(extra)
    LifecycleStartEffect(Unit) {
        currentExtra.onVisible(true)
        onStopOrDispose { currentExtra.onVisible(false) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column {
                    Text(state.title, maxLines = 1)
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
                if (detail != null) Detail(state, onChanges, onComment, onReview, onAction, onResolve, onReply, onOpenThread, onDiscuss, extra)
            } else {
                var query by rememberSaveable(state.environmentId) { mutableStateOf(state.query) }
                OutlinedTextField(query, { query = it.take(200); onSearch(query) }, Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    label = { Text("Search ${state.plural}") }, singleLine = true)
                ChoiceRow(listOf("open", "merged", "closed", "all"), state.filter, onFilter)
                ChoiceRow(listOf("all", "authored", "reviewing"), state.involvement, onInvolvement)
                ListFilters(state, extra)
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.listError?.let { item { ErrorNotice(it, onRefresh) } }
                    items(state.warnings) { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (state.errors.isNotEmpty()) item(key = "errors") { ListErrors(state.errors, state.plural) }
                    if (!state.loading && state.rows.isEmpty() && state.listError == null) item {
                        Text(if (state.environments.isEmpty()) "Pair an environment to see ${state.plural}." else "No ${state.plural} match these filters.", Modifier.padding(20.dp))
                    }
                    items(state.rows, key = { it.id }) { row ->
                        OutlinedCard(onClick = { onOpen(row.id) }, modifier = Modifier.fillMaxWidth().testTag("pull-request-row")) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(row.title, style = MaterialTheme.typography.titleMedium)
                                Text(row.subtitle, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(row.status, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    if (row.changes.isNotBlank()) Text(row.changes, style = MaterialTheme.typography.labelMedium)
                                }
                                if (row.labels.isNotBlank()) Text(row.labels, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (state.more) item { TextButton(onClick = onMore, enabled = !state.loading) { Text("Load more ${state.plural}") } }
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

private val DraftOptions = listOf(null to "All drafts", "only" to "Drafts only", "hide" to "Hide drafts")
private val ReviewOptions = listOf(null to "All reviews", "approved" to "Approved", "changes-requested" to "Changes requested",
    "review-required" to "Review required", "none" to "No reviews")
private val CheckOptions = listOf(null to "All checks", "passing" to "Passing", "failing" to "Failing")

@Composable
private fun ListFilters(state: PullRequestsUiState, extra: PullRequestExtraActions) {
    val filters = state.filters
    var search by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MenuChip(if (filters.labels.isEmpty()) "Labels" else "Labels ${filters.labels.size}", filters.labels.isNotEmpty()) { close ->
            DropdownMenuItem(text = { Text("Search labels\u2026") }, onClick = { close(); search = "labels" })
            DropdownMenuItem(text = { Text("All labels") }, enabled = filters.labels.isNotEmpty(), onClick = {
                close(); filters.labels.forEach(extra.onLabelFilter)
            })
            state.labelSuggestions.forEach { label ->
                DropdownMenuItem(text = { Text(label) }, leadingIcon = { Checkbox(filters.labels.any { it.equals(label, true) }, null) },
                    onClick = { extra.onLabelFilter(label) })
            }
        }
        MenuChip(filters.author?.let { if (it.equals("me", true)) "By you" else it } ?: "Author", filters.author != null) { close ->
            DropdownMenuItem(text = { Text("Search authors\u2026") }, onClick = { close(); search = "authors" })
            DropdownMenuItem(text = { Text("All authors") }, onClick = { close(); extra.onAuthorFilter(null) })
            DropdownMenuItem(text = { Text("Me") }, onClick = { close(); extra.onAuthorFilter("me") })
            state.authorSuggestions.forEach { login -> DropdownMenuItem(text = { Text(login) }, onClick = { close(); extra.onAuthorFilter(login) }) }
        }
        OptionChip("Drafts", DraftOptions, filters.draft, extra.onDraftFilter)
        OptionChip("Reviews", ReviewOptions, filters.review, extra.onReviewFilter)
        OptionChip("Checks", CheckOptions, filters.checks, extra.onChecksFilter)
        if (filters.count > 0) TextButton(onClick = extra.onResetFilters) { Text("Reset filters") }
    }
    search?.let { kind ->
        HostOptionsDialog(kind, state, extra, onPick = { value ->
            if (kind == "labels") extra.onLabelFilter(value) else { extra.onAuthorFilter(value); search = null; extra.onDismissHostOptions() }
        }, onDismiss = { search = null; extra.onDismissHostOptions() })
    }
}

@Composable
private fun MenuChip(label: String, active: Boolean, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(active, { open = true }, label = { Text(label) })
        DropdownMenu(open, { open = false }) { content { open = false } }
    }
}

@Composable
private fun OptionChip(label: String, options: List<Pair<String?, String>>, selected: String?, onSelect: (String?) -> Unit) {
    MenuChip(options.firstOrNull { it.first == selected && selected != null }?.second ?: label, selected != null) { close ->
        options.forEach { (value, title) ->
            DropdownMenuItem(text = { Text(title) }, leadingIcon = { RadioButton(value == selected, null) }, onClick = { close(); onSelect(value) })
        }
    }
}

/** Searches the host for a label or author no loaded row carries. */
@Composable
private fun HostOptionsDialog(kind: String, state: PullRequestsUiState, extra: PullRequestExtraActions, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable(kind) { mutableStateOf("") }
    LaunchedEffect(kind) { extra.onSearchHostOptions(kind, "") }
    val loaded = if (kind == "labels") state.labelSuggestions else state.authorSuggestions
    val choices = (loaded.filter { it.contains(query.trim(), ignoreCase = true) } + state.hostOptions).distinctBy { it.lowercase() }.take(100)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (kind == "labels") "Labels" else "Authors") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it.take(100); extra.onSearchHostOptions(kind, query) }, Modifier.fillMaxWidth(),
                    label = { Text(if (kind == "labels") "Search labels" else "Search authors") }, singleLine = true)
                if (state.hostOptionsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.hostOptionsError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(choices, key = { it.lowercase() }) { value ->
                        val picked = if (kind == "labels") state.filters.labels.any { it.equals(value, true) } else state.filters.author.equals(value, true)
                        ListItem(headlineContent = { Text(value) }, leadingContent = { Checkbox(picked, null) },
                            modifier = Modifier.fillMaxWidth().clickable { onPick(value) })
                    }
                    if (choices.isEmpty() && !state.hostOptionsLoading) item { Text("No matches.", style = MaterialTheme.typography.bodySmall) }
                    if (state.hostOptionsTruncated) item { Text("Keep typing to narrow the host's answers.", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

@Composable
private fun ListErrors(messages: List<String>, plural: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }) {
            Text("${messages.size} ${if (messages.size == 1) "error" else "errors"} loading $plural \u00B7 ${if (expanded) "Hide" else "Show"}",
                color = MaterialTheme.colorScheme.error)
        }
        if (expanded) Text(messages.joinToString("\n"), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
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
    onDiscuss: () -> Unit,
    extra: PullRequestExtraActions
) {
    val detail = requireNotNull(state.detail)
    val uri = LocalUriHandler.current
    var section by rememberSaveable(state.selectedId) { mutableStateOf("Overview") }
    var actionMenu by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<PrActionUi?>(null) }
    var editing by remember { mutableStateOf(false) }
    var editingComment by remember { mutableStateOf<PrCommentUi?>(null) }
    var managingLabels by remember { mutableStateOf(false) }
    val onEditComment: (PrCommentUi) -> Unit = { editingComment = it }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(detail.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(detail.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(detail.status, style = MaterialTheme.typography.labelMedium)
        detail.breakdown?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { runCatching { uri.openUri(detail.url) } }) { Text("Open on host") }
            if (detail.canEdit) TextButton(onClick = { editing = true }, enabled = !state.writing) { Text("Edit") }
            if (detail.actions.isNotEmpty()) Box {
                TextButton(onClick = { actionMenu = true }, enabled = !state.writing) { Text("Actions") }
                DropdownMenu(actionMenu, { actionMenu = false }) {
                    detail.actions.forEach { action -> DropdownMenuItem(text = { Text(action.label) }, onClick = {
                        actionMenu = false; confirmation = action
                    }) }
                }
            }
        }
        if (!state.canWrite) Text("To comment, review, or change this ${detail.noun}, pair again with source-control:write permission.",
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
                    state.reactions?.let { reactions -> item(key = "reactions") { Reactions(reactions, detail.canReact && !state.writing) { content, reacted -> extra.onReact(null, content, reacted) } } }
                    if (detail.labelChips.isNotEmpty() || detail.canManageLabels) item(key = "labels") {
                        Labels(detail.labelChips, detail.canManageLabels && !state.writing) { managingLabels = true; extra.onLoadLabels() }
                    }
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
                    if (detail.checkSummary.isNotBlank()) item(key = "check-summary") { Text(detail.checkSummary, style = MaterialTheme.typography.labelLarge) }
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
                    items(state.comments, key = { "comment:${it.id}" }) { comment -> Comment(comment, detail.canReact && !state.writing, !state.writing, onEditComment, extra.onReact) }
                    items(state.threads, key = { "thread:${it.id}" }) { thread ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(thread.path, style = MaterialTheme.typography.titleSmall)
                                Text(if (thread.resolved) "Resolved" else "Unresolved")
                                thread.comments.forEach { Comment(it, detail.canReact && !state.writing, !state.writing, onEditComment, extra.onReact) }
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
            text = { Text("Apply this action to ${detail.subtitle}? This changes the ${detail.noun} on its host.") },
            confirmButton = { TextButton(onClick = { confirmation = null; onAction(action.id) }, enabled = !state.writing) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } })
    }
    if (editing) EditDialog("Edit ${detail.noun}", detail.rawTitle, detail.rawBody, titled = true, onDismiss = { editing = false }) { title, body ->
        editing = false; extra.onEdit(title, body)
    }
    editingComment?.let { comment ->
        EditDialog("Edit comment", "", comment.rawBody, titled = false, onDismiss = { editingComment = null }) { _, body ->
            editingComment = null; extra.onEditComment(comment.id, body)
        }
    }
    if (managingLabels) LabelsDialog(state, extra) { managingLabels = false }
}

@Composable
private fun Comment(comment: PrCommentUi, canReact: Boolean, canEdit: Boolean, onEdit: (PrCommentUi) -> Unit, onReact: (String?, String, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(comment.author, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (comment.canEdit) TextButton(onClick = { onEdit(comment) }, enabled = canEdit) { Text("Edit") }
        }
        if (comment.context.isNotBlank()) Text(comment.context, style = MaterialTheme.typography.labelSmall)
        StreamingMarkdownText(comment.body, streaming = false)
        comment.reactions?.let { reactions -> Reactions(reactions, canReact) { content, reacted -> onReact(comment.id, content, reacted) } }
        HorizontalDivider()
    }
}

private val ReactionContents = listOf("thumbs-up", "thumbs-down", "laugh", "hooray", "confused", "heart", "rocket", "eyes")

@Composable
private fun Reactions(reactions: List<PrReactionUi>, canReact: Boolean, onReact: (String, Boolean) -> Unit) {
    if (reactions.isEmpty() && !canReact) return
    var picker by remember { mutableStateOf(false) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        reactions.forEach { reaction ->
            FilterChip(reaction.reacted, { onReact(reaction.content, !reaction.reacted) }, enabled = canReact,
                label = { Text("${reaction.emoji} ${reaction.count}") },
                modifier = Modifier.semantics { contentDescription = reaction.description })
        }
        if (canReact) Box {
            AssistChip({ picker = true }, label = { Text("+") }, modifier = Modifier.semantics { contentDescription = "Add reaction" })
            DropdownMenu(picker, { picker = false }) {
                ReactionContents.forEach { content ->
                    val reacted = reactions.any { it.content == content && it.reacted }
                    DropdownMenuItem(text = { Text("${reactionEmoji(content)}  ${content.replace('-', ' ')}") },
                        trailingIcon = { if (reacted) Text("\u2713") }, onClick = { picker = false; onReact(content, !reacted) })
                }
            }
        }
    }
}

@Composable
private fun Labels(labels: List<PrLabelUi>, canManage: Boolean, onManage: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Labels", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (canManage) TextButton(onClick = onManage) { Text("Manage labels") }
        }
        if (labels.isEmpty()) Text("No labels", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEach { label -> AssistChip({}, label = { Text(label.name) }, leadingIcon = { LabelDot(label.color) }) }
        }
    }
}

@Composable
private fun LabelDot(color: Long?) {
    Box(Modifier.size(10.dp).background(color?.let { Color(it) } ?: MaterialTheme.colorScheme.outline, CircleShape))
}

@Composable
private fun LabelsDialog(state: PullRequestsUiState, extra: PullRequestExtraActions, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val candidates = state.labelCandidates.filter { "${it.name} ${it.description}".contains(query.trim(), ignoreCase = true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Labels") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it.take(100) }, Modifier.fillMaxWidth(), label = { Text("Search labels") }, singleLine = true)
                if (state.labelsLoading || state.writing) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.labelsError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = extra.onLoadLabels) { Text("Retry") }
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(candidates, key = { it.name }) { candidate ->
                        ListItem(headlineContent = { Text(candidate.name) },
                            supportingContent = candidate.description.takeIf { it.isNotBlank() }?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                            leadingContent = { Checkbox(candidate.applied, null) }, trailingContent = { LabelDot(candidate.color) },
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !state.writing) { extra.onToggleLabel(candidate.name, !candidate.applied) })
                    }
                    if (state.labelsLoaded && candidates.isEmpty()) item {
                        Text(if (query.isBlank()) "This repository has no labels." else "No matching labels.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.labelsTruncated) item {
                        Text("This repository has more labels than are listed here. Apply the rest on the host.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

@Composable
private fun EditDialog(title: String, initialTitle: String, initialBody: String, titled: Boolean, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var heading by rememberSaveable { mutableStateOf(initialTitle) }
    var body by rememberSaveable { mutableStateOf(initialBody) }
    val valid = if (titled) heading.isNotBlank() && heading.trim().length <= 1024 else body.isNotBlank()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (titled) OutlinedTextField(heading, { heading = it.take(1024) }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
                OutlinedTextField(body, { body = it.take(65_536) }, Modifier.fillMaxWidth(), label = { Text(if (titled) "Description" else "Comment") }, minLines = 5)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(heading, body) }, enabled = valid && (heading != initialTitle || body != initialBody)) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
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
