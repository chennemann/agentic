package de.chennemann.agentic.domain.pullrequests

import de.chennemann.agentic.t3.contract.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How a source-control host names its change requests; unknown hosts get the neutral term. */
data class ChangeRequestTerms(val shortLabel: String, val singular: String, val plural: String) {
    val capitalizedSingular: String get() = singular.replaceFirstChar { it.uppercase() }
    val capitalizedPlural: String get() = plural.replaceFirstChar { it.uppercase() }
}

private val PullRequestTerms = ChangeRequestTerms("PR", "pull request", "pull requests")
private val MergeRequestTerms = ChangeRequestTerms("MR", "merge request", "merge requests")
internal val NeutralChangeRequestTerms = ChangeRequestTerms("change request", "change request", "change requests")

fun changeRequestTerms(kind: String?): ChangeRequestTerms = when (kind?.lowercase()) {
    "gitlab" -> MergeRequestTerms
    "github", "gitcafe", "forgejo", "bitbucket", "azure-devops" -> PullRequestTerms
    null -> PullRequestTerms
    else -> NeutralChangeRequestTerms
}

/** One term for a list that may span hosts: shared when every host agrees, otherwise neutral. */
fun changeRequestTerms(kinds: Collection<String?>): ChangeRequestTerms =
    kinds.map(::changeRequestTerms).distinct().singleOrNull() ?: if (kinds.isEmpty()) PullRequestTerms else NeutralChangeRequestTerms

/** Narrowings beyond state and involvement; empty fields filter nothing. */
data class PullRequestListFilterState(
    val draft: String? = null,
    val review: String? = null,
    val checks: String? = null,
    val labels: List<String> = emptyList(),
    val author: String? = null
) {
    val activeCount: Int get() = listOfNotNull(draft, review, checks, author).size + labels.size
    val isEmpty: Boolean get() = activeCount == 0

    fun toJson(): JsonObject? = if (isEmpty) null else buildJsonObject {
        draft?.let { put("draft", it) }
        review?.let { put("review", it) }
        checks?.let { put("checks", it) }
        if (labels.isNotEmpty()) put("labels", JsonArray(labels.take(MaxQualifierValues).map { JsonArray(listOf(JsonPrimitive(it.take(200)))) }))
        author?.let { put("author", it.take(200)) }
    }

    fun toggleLabel(name: String): PullRequestListFilterState {
        val existing = labels.firstOrNull { it.equals(name, ignoreCase = true) }
        return copy(labels = if (existing != null) labels - existing else (labels + name).take(MaxQualifierValues))
    }

    companion object {
        const val MaxQualifierValues = 10
    }
}

/** `author:me` names whoever is signed in to the host being read. */
fun resolvePullRequestAuthorFilter(author: String, viewer: String?): String {
    val trimmed = author.trim()
    if (!Regex("^@?me$", RegexOption.IGNORE_CASE).matches(trimmed)) return trimmed
    return viewer?.takeIf { it.isNotBlank() } ?: trimmed
}

/** A host that cannot narrow answers unnarrowed, so rows are narrowed again on the device. */
fun PullRequestEntry.matches(filters: PullRequestListFilterState, viewer: String?): Boolean {
    if (filters.draft == "only" && !isDraft) return false
    if (filters.draft == "hide" && isDraft) return false
    filters.author?.let { author ->
        if (!(this.author?.login.orEmpty()).equals(resolvePullRequestAuthorFilter(author, viewer), ignoreCase = true)) return false
    }
    if (filters.review == "none" && reviewDecision != null) return false
    if (filters.review != null && filters.review != "none" && reviewDecision != null && reviewDecision != filters.review) return false
    if (filters.checks != null && checksState != null && checksState != filters.checks) return false
    val names = labels.map { it.name.lowercase() }.toSet()
    return filters.labels.all { it.lowercase() in names }
}

internal fun PullRequestEntry.statKey() = "$projectId:$repository:$number"
internal fun PullRequestDiffStat.statKey() = "$projectId:$repository:$number"

/**
 * Diff stats the host answered and each row's `updatedAt` as its list last answered it. Stats carry
 * no head, so a row whose `updatedAt` moved (a push, among other changes) drops its cached stat.
 */
data class PullRequestStatsCache(
    val stats: Map<String, PullRequestDiffStat> = emptyMap(),
    val updatedAt: Map<String, String> = emptyMap()
) {
    /** Records the listed rows and returns the rows that still need a stats read. */
    fun withListed(entries: List<PullRequestEntry>): Pair<PullRequestStatsCache, List<PullRequestEntry>> {
        val stats = stats.toMutableMap()
        val updatedAt = updatedAt.toMutableMap()
        entries.forEach { entry ->
            val key = entry.statKey()
            if (updatedAt[key] != entry.updatedAt) {
                stats.remove(key)
                updatedAt[key] = entry.updatedAt
            }
        }
        val missing = entries.filter { it.additions + it.deletions == 0 && it.statKey() !in stats }
            .distinctBy { it.statKey() }.take(500)
        return PullRequestStatsCache(stats, updatedAt) to missing
    }

    fun withAnswered(answered: List<PullRequestDiffStat>) = copy(stats = stats + answered.associateBy { it.statKey() })

    /** Fills rows the listing left without counts; rows that carry their own counts keep them. */
    fun fill(entries: List<PullRequestEntry>): List<PullRequestEntry> = entries.map { entry ->
        val stat = stats[entry.statKey()]
        if (stat == null || entry.additions + entry.deletions != 0 || stat.additions + stat.deletions == 0) entry
        else entry.copy(additions = stat.additions, deletions = stat.deletions)
    }
}

/** Live checks win when they were observed no earlier than the detail they patch. */
fun resolveDetailChecks(detail: PullRequestDetail?, checks: PullRequestChecks?, checksAreNewer: Boolean): PullRequestDetail? {
    if (detail == null || checks == null) return detail
    val detailObserved = detail.observedAt
    val checksObserved = checks.observedAt
    val newer = if (detailObserved != null && checksObserved != null) checksObserved >= detailObserved else checksAreNewer
    return if (!newer) detail else detail.copy(state = if (detail.state == "merged") "merged" else checks.state, checks = checks.checks)
}

/** Checks still running are worth a tighter poll than a settled head. */
fun checksRefreshIntervalMillis(checks: List<PullRequestCheck>): Long =
    if (checks.isEmpty() || checks.any { it.status == "pending" || it.status == "action-required" }) 45_000 else 60_000

private fun sameLogin(one: String?, other: String?) = one != null && other != null && one.trim().equals(other.trim(), ignoreCase = true)

fun PullRequestDetail.canEditChangeRequest(): Boolean {
    if (capabilities.edit?.changeRequest != true) return false
    return viewerPermissions.editChangeRequest ?: (sameLogin(viewer, author?.login) || "merge" in viewerPermissions.actions)
}

/** Hosts do not support rewriting review summaries through comment mutations. */
fun PullRequestDetail.canEditComment(kind: String, author: String?, canEdit: Boolean?): Boolean {
    if (capabilities.edit?.comment != true) return false
    if (kind != "issue-comment" && kind != "review-comment") return false
    return canEdit ?: sameLogin(viewer, author)
}

fun PullRequestDetail.canManageLabels(): Boolean = capabilities.labels && viewerPermissions.labels != false

val PullRequestReactionOrder = listOf("thumbs-up", "thumbs-down", "laugh", "hooray", "confused", "heart", "rocket", "eyes")

/** Applies the viewer's own reaction change ahead of the host so the tap shows at once. */
fun List<PullRequestReaction>.withViewerReaction(content: String, reacted: Boolean): List<PullRequestReaction> {
    val byContent = associateBy { it.content }.toMutableMap()
    val current = byContent[content]
    when {
        current == null -> if (reacted) byContent[content] = PullRequestReaction(content, 1, emptyList(), true)
        current.viewerHasReacted == reacted -> Unit
        else -> {
            val count = current.count + if (reacted) 1 else -1
            if (count <= 0) byContent.remove(content) else byContent[content] = current.copy(count = count, viewerHasReacted = reacted)
        }
    }
    return PullRequestReactionOrder.mapNotNull { byContent[it] } + byContent.values.filter { it.content !in PullRequestReactionOrder }
}

/** Reaction subject: null reacts to the change request itself, otherwise a comment id. */
fun PullRequestActivity.withViewerReaction(subjectId: String?, content: String, reacted: Boolean): PullRequestActivity = if (subjectId == null) {
    copy(reactions = reactions.orEmpty().withViewerReaction(content, reacted))
} else {
    copy(
        comments = comments.map { comment ->
            comment.reactions?.takeIf { comment.id == subjectId }?.let { comment.copy(reactions = it.withViewerReaction(content, reacted)) } ?: comment
        },
        reviewThreads = reviewThreads.map { thread ->
            thread.copy(comments = thread.comments.map { comment ->
                comment.reactions?.takeIf { comment.id == subjectId }?.let { comment.copy(reactions = it.withViewerReaction(content, reacted)) } ?: comment
            })
        }
    )
}

private val HtmlHint = Regex("<(?:!--|/?[a-zA-Z])")
private val Fence = Regex("^\\s{0,3}(```+|~~~+)")
private val InlineCode = Regex("(`+)[\\s\\S]*?\\1")
private val HtmlComment = Regex("<!--[\\s\\S]*?-->")
private val Anchor = Regex("<a\\b([^>]*)>([\\s\\S]*?)</a\\s*>", RegexOption.IGNORE_CASE)
private val Image = Regex("<img\\b([^>]*)/?>", RegexOption.IGNORE_CASE)
private val KnownTag = Regex(
    "</?(?:a|br|p|div|details|summary|b|strong|i|em|sub|sup|span|picture|source|kbd|ins|del|hr|center|small|g-emoji)\\b[^>]*>",
    RegexOption.IGNORE_CASE
)

private fun attribute(attributes: String, name: String): String? =
    Regex("\\b$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(attributes)
        ?.let { match -> match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } }
        ?.replace("&amp;", "&")

private fun markdownUrl(url: String) = url.trim().replace(" ", "%20").replace("(", "%28").replace(")", "%29").replace("\n", "")

private fun linkLabel(text: String) = text.replace(Regex("\\s+"), " ").trim().replace("[", "\\[").replace("]", "\\]")

/**
 * Host comments mix Markdown with inline HTML. Links, images, and layout tags become Markdown so a
 * tapped `<a href>` opens; code blocks and code spans keep their literal tags.
 */
fun pullRequestMarkdown(body: String): String {
    if (!HtmlHint.containsMatchIn(body)) return body
    val output = StringBuilder()
    val prose = StringBuilder()
    var fence: String? = null
    fun flush() {
        if (prose.isEmpty()) return
        output.append(convertInlineHtml(prose.toString()))
        prose.clear()
    }
    val lines = body.split("\n")
    lines.forEachIndexed { index, line ->
        val suffix = if (index < lines.lastIndex) "\n" else ""
        val marker = Fence.find(line)?.groupValues?.get(1)
        val open = fence
        when {
            open != null -> {
                output.append(line).append(suffix)
                if (marker != null && marker[0] == open[0] && marker.length >= open.length && line.trim().all { it == marker[0] }) fence = null
            }
            marker != null -> {
                flush()
                fence = marker
                output.append(line).append(suffix)
            }
            else -> prose.append(line).append(suffix)
        }
    }
    flush()
    return output.toString()
}

private fun convertInlineHtml(text: String): String {
    val result = StringBuilder()
    var end = 0
    InlineCode.findAll(text).forEach { code ->
        result.append(convertHtml(text.substring(end, code.range.first)))
        result.append(code.value)
        end = code.range.last + 1
    }
    result.append(convertHtml(text.substring(end)))
    return result.toString()
}

private fun convertHtml(text: String): String {
    if (!HtmlHint.containsMatchIn(text)) return text
    var converted = HtmlComment.replace(text, "")
    converted = Anchor.replace(converted) { match ->
        val href = attribute(match.groupValues[1], "href")?.trim()
        val label = Image.replace(match.groupValues[2]) { image -> attribute(image.groupValues[1], "alt").orEmpty() }
            .let { KnownTag.replace(it, "") }
            .let(::linkLabel)
            .ifBlank { href.orEmpty() }
        if (href != null && Regex("^(?:https?|mailto):", RegexOption.IGNORE_CASE).containsMatchIn(href)) "[$label](${markdownUrl(href)})" else label
    }
    converted = Image.replace(converted) { match ->
        val src = attribute(match.groupValues[1], "src") ?: return@replace ""
        val alt = linkLabel(attribute(match.groupValues[1], "alt").orEmpty()).ifBlank { "image" }
        if (Regex("^https?:", RegexOption.IGNORE_CASE).containsMatchIn(src)) "[$alt](${markdownUrl(src)})" else alt
    }
    return KnownTag.replace(converted) { match ->
        val tag = match.value.lowercase()
        when {
            tag.startsWith("<summary") -> "\n\n**"
            tag.startsWith("</summary") -> "**\n\n"
            tag.startsWith("<br") -> "  \n"
            tag.startsWith("<hr") -> "\n\n---\n\n"
            Regex("^</?(?:p|div|details|center)\\b").containsMatchIn(tag) -> "\n\n"
            Regex("^</?(?:b|strong)\\b").containsMatchIn(tag) -> "**"
            Regex("^</?(?:i|em)\\b").containsMatchIn(tag) -> "*"
            else -> ""
        }
    }
}
