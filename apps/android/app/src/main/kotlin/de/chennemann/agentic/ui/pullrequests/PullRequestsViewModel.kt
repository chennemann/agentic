package de.chennemann.agentic.ui.pullrequests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.pullrequests.ChangeRequestTerms
import de.chennemann.agentic.domain.pullrequests.PullRequestListFilterState
import de.chennemann.agentic.domain.pullrequests.PullRequestsRepository
import de.chennemann.agentic.domain.pullrequests.PullRequestsService
import de.chennemann.agentic.domain.pullrequests.PullRequestsSnapshot
import de.chennemann.agentic.domain.pullrequests.canEditChangeRequest
import de.chennemann.agentic.domain.pullrequests.canEditComment
import de.chennemann.agentic.domain.pullrequests.canManageLabels
import de.chennemann.agentic.domain.pullrequests.changeRequestTerms
import de.chennemann.agentic.domain.pullrequests.checksRefreshIntervalMillis
import de.chennemann.agentic.domain.pullrequests.matches
import de.chennemann.agentic.domain.pullrequests.pullRequestMarkdown
import de.chennemann.agentic.t3.contract.PullRequestChangeBreakdown
import de.chennemann.agentic.t3.contract.PullRequestCheck
import de.chennemann.agentic.t3.contract.PullRequestDetail
import de.chennemann.agentic.t3.contract.PullRequestReaction
import de.chennemann.agentic.ui.review.ReviewDocument
import de.chennemann.agentic.ui.review.parseReviewDiff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// UI values contain no wire payloads or transport clients.
data class PrEnvironmentUi(val id: String, val label: String)
data class PrRowUi(val id: String, val title: String, val subtitle: String, val status: String, val labels: String, val changes: String = "")
data class PrCheckUi(val name: String, val status: String, val description: String, val url: String?)
data class PrReactionUi(val content: String, val emoji: String, val count: Int, val reacted: Boolean, val description: String)
data class PrCommentUi(
    val id: String, val author: String, val body: String, val context: String,
    val canEdit: Boolean = false, val rawBody: String = body, val reactions: List<PrReactionUi>? = null
)
data class PrThreadUi(val id: String, val path: String, val comments: List<PrCommentUi>, val resolved: Boolean, val canResolve: Boolean, val canReply: Boolean, val truncated: Boolean)
data class PrLinkedThreadUi(val id: String, val title: String)
data class PrActionUi(val id: String, val label: String, val action: String, val method: String? = null)
data class PrLabelUi(val name: String, val color: Long? = null)
data class PrLabelCandidateUi(val name: String, val description: String, val color: Long?, val applied: Boolean)
data class PrDetailUi(
    val title: String, val subtitle: String, val body: String, val url: String, val status: String,
    val checks: List<PrCheckUi>, val labels: String, val reviewers: String,
    val actions: List<PrActionUi>, val verdicts: List<String>, val canComment: Boolean, val canDiff: Boolean,
    val noun: String = "pull request", val canEdit: Boolean = false, val rawTitle: String = title, val rawBody: String = body,
    val canManageLabels: Boolean = false, val canReact: Boolean = false, val labelChips: List<PrLabelUi> = emptyList(),
    val breakdown: String? = null, val checkSummary: String = ""
)
data class PrFiltersUi(
    val draft: String? = null, val review: String? = null, val checks: String? = null,
    val labels: List<String> = emptyList(), val author: String? = null, val count: Int = 0
)
data class PullRequestsUiState(
    val environments: List<PrEnvironmentUi> = emptyList(), val environmentId: String? = null,
    val filter: String = "open", val involvement: String = "all", val query: String = "",
    val rows: List<PrRowUi> = emptyList(), val loading: Boolean = false, val listError: String? = null,
    val warnings: List<String> = emptyList(), val more: Boolean = false,
    val selectedId: String? = null, val detail: PrDetailUi? = null,
    val detailLoading: Boolean = false, val detailError: String? = null,
    val comments: List<PrCommentUi> = emptyList(), val threads: List<PrThreadUi> = emptyList(),
    val commits: List<String> = emptyList(), val activityError: String? = null, val activityLoaded: Boolean = false,
    val activityTruncated: Boolean = false, val linkedThreads: List<PrLinkedThreadUi> = emptyList(),
    val document: ReviewDocument = ReviewDocument(), val diffLoaded: Boolean = false, val diffError: String? = null,
    val moreDiff: Boolean = false, val diffTruncated: Boolean = false,
    val writing: Boolean = false, val canWrite: Boolean = false, val actionError: String? = null, val submitted: Int = 0,
    val title: String = "Pull requests", val plural: String = "pull requests", val errors: List<String> = emptyList(),
    val filters: PrFiltersUi = PrFiltersUi(), val labelSuggestions: List<String> = emptyList(), val authorSuggestions: List<String> = emptyList(),
    val hostOptions: List<String> = emptyList(), val hostOptionsLoading: Boolean = false, val hostOptionsError: String? = null,
    val hostOptionsTruncated: Boolean = false, val reactions: List<PrReactionUi>? = null,
    val labelCandidates: List<PrLabelCandidateUi> = emptyList(), val labelsLoading: Boolean = false, val labelsError: String? = null,
    val labelsLoaded: Boolean = false, val labelsTruncated: Boolean = false
)

class PullRequestsViewModel(
    private val environments: EnvironmentRepository,
    private val repository: PullRequestsRepository,
    private val service: PullRequestsService,
    private val conversations: de.chennemann.agentic.domain.pullrequests.PullRequestConversations
) : ViewModel() {
    private val localError = MutableStateFlow<String?>(null)
    private val submitted = MutableStateFlow(0)
    private val hostOptionsKind = MutableStateFlow<String?>(null)
    private var listJob: Job? = null
    private var detailJob: Job? = null
    private var diffJob: Job? = null
    private var watchJob: Job? = null
    private var actionJob: Job? = null
    private var optionsJob: Job? = null
    private var checksJob: Job? = null
    private var screenVisible = false
    val state = combine(repository.state, environments.environments, localError, submitted, hostOptionsKind) { snapshot, environments, error, sent, optionsKind ->
        val detail = snapshot.detail
        val patch = snapshot.patch
        val entries = snapshot.list?.entries.orEmpty()
        val listTerms = changeRequestTerms(snapshot.list?.providers.orEmpty().map { it.kind } + entries.map { it.provider }.distinct())
        val detailTerms = changeRequestTerms(detail?.provider ?: entries.firstOrNull { it.ref().key == snapshot.ref?.key }?.provider)
        PullRequestsUiState(
            environments = environments.map { PrEnvironmentUi(it.id, it.label) }, environmentId = snapshot.environmentId,
            filter = snapshot.filter, involvement = snapshot.involvement, query = snapshot.query,
            rows = rows(snapshot),
            loading = snapshot.loading, listError = snapshot.listError,
            warnings = snapshot.list?.providers.orEmpty().filterNot { it.configured }.map { "${it.host}: ${it.detail ?: "Source control is not configured."}" },
            more = snapshot.list?.let { it.nextCursors.isNotEmpty() || (it.truncated && snapshot.limit < 500) } == true,
            selectedId = snapshot.ref?.key, detail = detail?.toUi(snapshot.canWrite, detailTerms, snapshot.breakdown), detailLoading = snapshot.detailLoading,
            detailError = snapshot.detailError,
            comments = snapshot.activity?.comments.orEmpty().map {
                PrCommentUi(it.id, it.author?.login ?: "Unknown author", pullRequestMarkdown(it.body),
                    listOfNotNull(it.reviewState, it.path, it.createdAt, it.editedAt?.let { "edited" }).joinToString(" - "),
                    snapshot.canWrite && detail?.canEditComment(it.kind, it.author?.login, it.canEdit) == true, it.body,
                    it.reactions?.toUi())
            },
            threads = snapshot.activity?.reviewThreads.orEmpty().map { thread -> PrThreadUi(thread.id,
                "${thread.path}${thread.line?.let { ":$it" }.orEmpty()}${if (thread.isOutdated) " (outdated)" else ""}",
                thread.comments.map {
                    PrCommentUi(it.id, it.author?.login ?: "Unknown author", pullRequestMarkdown(it.body), if (it.editedAt != null) "edited" else "",
                        snapshot.canWrite && detail?.canEditComment("review-comment", it.author?.login, it.canEdit) == true, it.body,
                        it.reactions?.toUi())
                }, thread.isResolved,
                snapshot.canWrite && detail?.capabilities?.review?.resolve == true && detail.viewerPermissions.resolve && thread.canResolve != false,
                snapshot.canWrite && detail?.capabilities?.review?.reply == true && detail.viewerPermissions.comment,
                thread.nextCommentsCursor != null) },
            commits = snapshot.activity?.commits.orEmpty().map { "${it.oid.take(8)} ${it.messageHeadline}" },
            activityError = snapshot.activityError, activityLoaded = snapshot.activity != null,
            activityTruncated = snapshot.activity?.let { it.commentsTruncated || it.reviewThreadsTruncated } == true,
            linkedThreads = snapshot.threads.filter { it.archivedAt == null }.map { PrLinkedThreadUi(it.id, it.title) },
            document = withContext(Dispatchers.Default) { parseReviewDiff(patch) }, diffLoaded = snapshot.diffLoaded,
            diffError = snapshot.diffError, moreDiff = snapshot.nextDiffCursor != null, diffTruncated = snapshot.diffTruncated,
            writing = snapshot.writing, canWrite = snapshot.canWrite,
            actionError = error ?: snapshot.actionError, submitted = sent,
            title = listTerms.capitalizedPlural, plural = listTerms.plural,
            errors = snapshot.list?.errors.orEmpty().map { "${it.projectTitle}: ${it.message}" },
            filters = snapshot.filters.let { PrFiltersUi(it.draft, it.review, it.checks, it.labels, it.author, it.activeCount) },
            labelSuggestions = (snapshot.filters.labels + entries.flatMap { entry -> entry.labels.map { it.name } }.sortedBy { it.lowercase() })
                .distinctBy { it.lowercase() }.take(60),
            authorSuggestions = (listOfNotNull(snapshot.filters.author?.takeUnless { it.equals("me", ignoreCase = true) }) +
                entries.mapNotNull { it.author?.login }.sortedBy { it.lowercase() }).distinctBy { it.lowercase() }.take(60),
            hostOptions = snapshot.filterOptions?.let { options -> if (optionsKind == "authors") options.authors.map { it.login } else options.labels.map { it.name } }.orEmpty(),
            hostOptionsLoading = snapshot.filterOptionsLoading, hostOptionsError = snapshot.filterOptionsError,
            hostOptionsTruncated = snapshot.filterOptions?.truncated == true,
            reactions = snapshot.activity?.reactions?.toUi(),
            labelCandidates = snapshot.labelCandidates?.candidates.orEmpty().map { PrLabelCandidateUi(it.name, it.description.orEmpty(), labelColor(it.color), it.isApplied) },
            labelsLoading = snapshot.labelsLoading, labelsError = snapshot.labelsError, labelsLoaded = snapshot.labelCandidates != null,
            labelsTruncated = snapshot.labelCandidates?.truncated == true
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PullRequestsUiState())

    init {
        viewModelScope.launch {
            environments.environments.collect { saved ->
                if (saved.isEmpty()) {
                    listJob?.cancel(); detailJob?.cancel(); diffJob?.cancel(); watchJob?.cancel(); checksJob?.cancel()
                    repository.clear()
                    return@collect
                }
                val selected = repository.state.value.environmentId
                if (saved.none { it.id == selected }) saved.firstOrNull { it.active }?.let { selectEnvironment(it.id) }
                    ?: saved.firstOrNull()?.let { selectEnvironment(it.id) }
                else if (repository.state.value.list == null && !repository.state.value.loading) reload()
            }
        }
        repository.state.value.environmentId?.let { reload(); startWatch() }
    }

    fun selectEnvironment(id: String) {
        if (repository.state.value.writing) return
        detailJob?.cancel(); diffJob?.cancel(); listJob?.cancel(); checksJob?.cancel()
        repository.selectEnvironment(id)
        localError.value = null
        reload()
        startWatch()
    }

    private fun startWatch() {
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            while (true) {
                try {
                    repository.refreshes().distinctUntilChanged().drop(1).collect {
                        if (!repository.state.value.writing) reload()
                    }
                    delay(5_000)
                } catch (cause: CancellationException) { throw cause
                } catch (_: Exception) { delay(5_000) }
            }
        }
    }

    /** Re-reads only the checks while an open change request is on screen. */
    private fun startChecks() {
        checksJob?.cancel()
        if (!screenVisible || repository.state.value.ref == null) return
        checksJob = viewModelScope.launch {
            while (true) {
                delay(checksRefreshIntervalMillis(repository.state.value.detail?.checks.orEmpty()))
                val current = repository.state.value
                if (current.ref == null) return@launch
                if (current.detail?.state != "open" || current.writing || current.detailLoading) continue
                try {
                    if (!repository.refreshChecks()) return@launch
                } catch (cause: CancellationException) { throw cause
                } catch (_: Exception) { /* A failed read keeps polling. */ }
            }
        }
    }

    fun visible(value: Boolean) {
        screenVisible = value
        if (value) startChecks() else checksJob?.cancel()
    }

    fun filter(value: String) = list(value, repository.state.value.involvement, repository.state.value.query)
    fun involvement(value: String) = list(repository.state.value.filter, value, repository.state.value.query)
    fun search(value: String) = list(repository.state.value.filter, repository.state.value.involvement, value, debounce = true)
    fun draftFilter(value: String?) = filters { it.copy(draft = value) }
    fun reviewFilter(value: String?) = filters { it.copy(review = value) }
    fun checksFilter(value: String?) = filters { it.copy(checks = value) }
    fun labelFilter(name: String) = filters { it.toggleLabel(name) }
    fun authorFilter(value: String?) = filters { it.copy(author = value?.trim()?.takeIf(String::isNotEmpty)) }
    fun resetFilters() = filters { PullRequestListFilterState() }
    private fun filters(transform: (PullRequestListFilterState) -> PullRequestListFilterState) {
        val s = repository.state.value
        list(s.filter, s.involvement, s.query, filters = transform(s.filters))
    }
    private fun list(filter: String, involvement: String, query: String, more: Boolean = false, debounce: Boolean = false,
        filters: PullRequestListFilterState = repository.state.value.filters) {
        listJob?.cancel()
        repository.configure(filter, involvement, query, filters)
        listJob = viewModelScope.launch { if (debounce) delay(250); repository.list(filter, involvement, query, more, filters) }
    }
    fun more() { val s = repository.state.value; list(s.filter, s.involvement, s.query, more = true) }

    fun searchHostOptions(kind: String, query: String) {
        optionsJob?.cancel()
        if (hostOptionsKind.value != kind) repository.clearFilterOptions()
        hostOptionsKind.value = kind
        optionsJob = viewModelScope.launch { delay(250); repository.filterOptions(kind, query) }
    }
    fun dismissHostOptions() {
        optionsJob?.cancel()
        hostOptionsKind.value = null
        repository.clearFilterOptions()
    }

    /** An explicit refresh asks the host again, even when invalidation is refused. */
    fun refresh() {
        localError.value = null
        viewModelScope.launch {
            try { repository.invalidate()
            } catch (cause: CancellationException) { throw cause
            } catch (_: Exception) { /* Reads below still return what the server can see. */ }
            reload()
        }
    }
    private fun reload() {
        diffJob?.cancel()
        val s = repository.state.value
        list(s.filter, s.involvement, s.query)
        s.ref?.let { ref -> detailJob?.cancel(); detailJob = viewModelScope.launch { repository.open(ref) } }
    }
    fun open(id: String) {
        val ref = repository.state.value.list?.entries?.firstOrNull { it.ref().key == id }?.ref() ?: return
        localError.value = null
        detailJob?.cancel(); diffJob?.cancel()
        detailJob = viewModelScope.launch { repository.open(ref) }
        startChecks()
    }
    fun back() { detailJob?.cancel(); diffJob?.cancel(); checksJob?.cancel(); repository.close(); reload() }
    fun changes(more: Boolean = false) { diffJob?.cancel(); diffJob = viewModelScope.launch { repository.diff(more) } }
    fun comment(body: String) = act(clearDraft = true) { service.comment(body) }
    fun review(verdict: String, body: String) = act(clearDraft = true) { service.review(verdict, body) }
    fun action(id: String) {
        val option = state.value.detail?.actions?.firstOrNull { it.id == id } ?: return
        act { service.action(option.action, option.method) }
    }
    fun resolve(id: String, resolved: Boolean) = act { service.resolve(id, resolved) }
    fun reply(id: String, body: String) = act(clearDraft = true) { service.reply(id, body) }
    fun edit(title: String, body: String) = act { service.update(title, body) }
    fun editComment(id: String, body: String) = act { service.updateComment(id, body) }
    fun react(subjectId: String?, content: String, reacted: Boolean) = act { service.react(subjectId, content, reacted) }
    fun loadLabels() { viewModelScope.launch { repository.labelCandidates() } }
    fun toggleLabel(name: String, applied: Boolean) = act { service.setLabel(name, applied) }
    private fun act(clearDraft: Boolean = false, block: suspend () -> Boolean) {
        if (actionJob?.isActive == true) return
        localError.value = null
        actionJob = viewModelScope.launch {
            try { val completed = block(); if (clearDraft && completed) submitted.value++
            } catch (cause: CancellationException) { throw cause
            } catch (_: Exception) { localError.value = "The action could not be completed. Refresh and check your permissions." }
        }
    }
    fun discuss(onOpened: () -> Unit) {
        viewModelScope.launch {
            try {
                val snapshot = repository.state.value
                val environmentId = snapshot.environmentId ?: return@launch
                val ref = snapshot.ref ?: return@launch
                val detail = snapshot.detail ?: return@launch
                conversations.discuss(environmentId, ref.projectId, detail.url, changeRequestTerms(detail.provider).singular)
                onOpened()
            } catch (cause: CancellationException) { throw cause
            } catch (_: Exception) { localError.value = "The conversation draft could not be prepared." }
        }
    }
    fun openThread(id: String, onOpened: () -> Unit) {
        viewModelScope.launch {
            try {
                val environmentId = repository.state.value.environmentId ?: return@launch
                val thread = repository.state.value.threads.firstOrNull { it.id == id } ?: return@launch
                conversations.open(environmentId, thread.projectId, thread.id)
                onOpened()
            } catch (cause: CancellationException) { throw cause
            } catch (_: Exception) { localError.value = "The linked conversation could not be opened." }
        }
    }

    val extraActions = PullRequestExtraActions(
        onVisible = ::visible, onDraftFilter = ::draftFilter, onReviewFilter = ::reviewFilter, onChecksFilter = ::checksFilter,
        onLabelFilter = ::labelFilter, onAuthorFilter = ::authorFilter, onResetFilters = ::resetFilters,
        onSearchHostOptions = ::searchHostOptions, onDismissHostOptions = ::dismissHostOptions,
        onEdit = ::edit, onEditComment = ::editComment, onReact = ::react, onLoadLabels = ::loadLabels, onToggleLabel = ::toggleLabel
    )
}

/** Intents beyond the original screen contract, bundled so callers wire them once. */
data class PullRequestExtraActions(
    val onVisible: (Boolean) -> Unit = {},
    val onDraftFilter: (String?) -> Unit = {},
    val onReviewFilter: (String?) -> Unit = {},
    val onChecksFilter: (String?) -> Unit = {},
    val onLabelFilter: (String) -> Unit = {},
    val onAuthorFilter: (String?) -> Unit = {},
    val onResetFilters: () -> Unit = {},
    val onSearchHostOptions: (kind: String, query: String) -> Unit = { _, _ -> },
    val onDismissHostOptions: () -> Unit = {},
    val onEdit: (title: String, body: String) -> Unit = { _, _ -> },
    val onEditComment: (id: String, body: String) -> Unit = { _, _ -> },
    val onReact: (subjectId: String?, content: String, reacted: Boolean) -> Unit = { _, _, _ -> },
    val onLoadLabels: () -> Unit = {},
    val onToggleLabel: (name: String, applied: Boolean) -> Unit = { _, _ -> }
)

private fun rows(snapshot: PullRequestsSnapshot): List<PrRowUi> {
    val list = snapshot.list ?: return emptyList()
    return snapshot.stats.fill(list.entries).filter { entry ->
        val hostSearch = list.providers.firstOrNull { it.host == entry.host }?.searchesOnHost == true
        (hostSearch || snapshot.query.isBlank() || "${entry.title} ${entry.repository} ${entry.author?.login}".contains(snapshot.query.trim(), ignoreCase = true)) &&
            entry.matches(snapshot.filters, list.viewers[entry.host])
    }.map { entry ->
        PrRowUi(entry.ref().key, entry.title,
            "${entry.repository} #${entry.number} - ${entry.author?.login ?: "Unknown author"}",
            listOfNotNull(if (entry.isDraft) "draft" else entry.state, entry.checksState, entry.reviewDecision).joinToString(" / "),
            entry.labels.joinToString { it.name },
            if (entry.additions + entry.deletions == 0) "" else "+${entry.additions} \u2212${entry.deletions}")
    }
}

private val ReactionEmoji = mapOf(
    "thumbs-up" to "\uD83D\uDC4D", "thumbs-down" to "\uD83D\uDC4E", "laugh" to "\uD83D\uDE04", "hooray" to "\uD83C\uDF89",
    "confused" to "\uD83D\uDE15", "heart" to "\u2764\uFE0F", "rocket" to "\uD83D\uDE80", "eyes" to "\uD83D\uDC40"
)
internal fun reactionEmoji(content: String) = ReactionEmoji[content] ?: content

/** Hosts omit the viewer from actors; "You" is added only where the count has room for it. */
internal fun reactionDescription(reaction: PullRequestReaction): String {
    val names = if (reaction.viewerHasReacted && reaction.actors.size < reaction.count) listOf("You") + reaction.actors else reaction.actors
    val shown = names.take(minOf(3, reaction.count))
    val others = (reaction.count - shown.size).coerceAtLeast(0)
    val parts = shown + if (others == 0) emptyList() else listOf(
        if (shown.isNotEmpty()) "$others ${if (others == 1) "other" else "others"}" else "$others ${if (others == 1) "person" else "people"}")
    val joined = when (parts.size) {
        0 -> ""
        1 -> parts[0]
        2 -> "${parts[0]} and ${parts[1]}"
        else -> parts.dropLast(1).joinToString(", ") + ", and " + parts.last()
    }
    return "$joined reacted with ${reaction.content.replace('-', ' ')}"
}

private fun List<PullRequestReaction>.toUi() = map { PrReactionUi(it.content, reactionEmoji(it.content), it.count, it.viewerHasReacted, reactionDescription(it)) }

internal fun labelColor(color: String?): Long? {
    val hex = color?.removePrefix("#") ?: return null
    if (!Regex("^[0-9a-fA-F]{6}$").matches(hex)) return null
    return 0xFF000000L or hex.toLong(16)
}

internal fun checkSummary(checks: List<PullRequestCheck>): String {
    if (checks.isEmpty()) return ""
    val passed = checks.count { it.status == "success" }
    val failed = checks.count { it.status == "failure" || it.status == "cancelled" }
    val pending = checks.count { it.status == "pending" || it.status == "action-required" }
    val skipped = checks.size - passed - failed - pending
    return listOfNotNull(
        passed.takeIf { it > 0 }?.let { "$it passed" }, failed.takeIf { it > 0 }?.let { "$it failed" },
        pending.takeIf { it > 0 }?.let { "$it pending" }, skipped.takeIf { it > 0 }?.let { "$it skipped" }
    ).joinToString(" \u00B7 ")
}

internal fun breakdownSummary(breakdown: PullRequestChangeBreakdown?): String? {
    breakdown ?: return null
    val parts = listOf("Code" to breakdown.code, "Tests" to breakdown.tests, "Mocks" to breakdown.mocks, "Generated" to breakdown.generated, "Docs" to breakdown.docs)
        .filter { (_, count) -> count.additions != 0 || count.deletions != 0 }
        .map { (label, count) -> "$label +${count.additions} \u2212${count.deletions}" }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" \u00B7 ")
}

internal fun PullRequestDetail.toUi(canWrite: Boolean, terms: ChangeRequestTerms = changeRequestTerms(provider), breakdown: PullRequestChangeBreakdown? = null): PrDetailUi {
    val noun = terms.singular
    val actions = if (!canWrite) emptyList() else capabilities.actions.intersect(viewerPermissions.actions.toSet()).flatMap { action ->
        when (action) {
            "merge", "enable-auto-merge" -> if (state != "open" || isDraft) emptyList() else capabilities.mergeMethods.filter {
                when (it) { "merge" -> mergeCapabilities.merge; "squash" -> mergeCapabilities.squash; "rebase" -> mergeCapabilities.rebase; else -> false }
            }.map { PrActionUi("$action:$it", "${if (action == "merge") "Merge" else "Auto-merge"} ($it)", action, it) }
            "update-branch" -> if (state != "open") emptyList() else capabilities.updateMethods.intersect(viewerPermissions.updateMethods.toSet()).map { PrActionUi("$action:$it", "Update branch ($it)", action, it) }
            "ready" -> if (isDraft && state == "open") listOf(PrActionUi(action, "Mark ready for review", action)) else emptyList()
            "draft" -> if (!isDraft && state == "open") listOf(PrActionUi(action, "Convert to draft", action)) else emptyList()
            "close" -> if (state == "open") listOf(PrActionUi(action, "Close $noun", action)) else emptyList()
            "reopen" -> if (state == "closed") listOf(PrActionUi(action, "Reopen $noun", action)) else emptyList()
            "disable-auto-merge" -> if (autoMergeEnabled == true) listOf(PrActionUi(action, "Disable auto-merge", action)) else emptyList()
            "delete-branch" -> if (state == "merged" || state == "closed") listOf(PrActionUi(action, "Delete branch", action)) else emptyList()
            "revert" -> if (state == "merged") listOf(PrActionUi(action, "Revert $noun", action)) else emptyList()
            "approve-workflows" -> listOf(PrActionUi(action, "Approve workflows", action))
            else -> emptyList()
        }
    }
    return PrDetailUi(title, "$repository #$number - $headBranch to $baseBranch", pullRequestMarkdown(body), url,
        "${if (isDraft) "draft" else state} / $mergeability / +$additions -$deletions / $changedFiles files",
        checks.map { PrCheckUi(it.name, it.status, it.description.orEmpty(), it.url) },
        labels.joinToString { it.name }, reviewers.joinToString { it.login }, actions,
        if (canWrite) capabilities.review.verdicts.intersect(viewerPermissions.verdicts.toSet()).toList() else emptyList(),
        canWrite && capabilities.comment && viewerPermissions.comment, capabilities.diff,
        noun = noun, canEdit = canWrite && canEditChangeRequest(), rawTitle = title, rawBody = body,
        canManageLabels = canWrite && canManageLabels(), canReact = canWrite && capabilities.reactions,
        labelChips = labels.map { PrLabelUi(it.name, labelColor(it.color)) },
        breakdown = breakdownSummary(breakdown), checkSummary = checkSummary(checks))
}
