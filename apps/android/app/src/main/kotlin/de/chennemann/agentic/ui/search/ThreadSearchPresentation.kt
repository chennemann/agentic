package de.chennemann.agentic.ui.search

import de.chennemann.agentic.domain.search.ThreadMessageAuthor
import de.chennemann.agentic.domain.search.ThreadMessageMatch
import de.chennemann.agentic.domain.search.normalizeThreadSearchQuery
import de.chennemann.agentic.t3.contract.OrchestrationShellSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationThreadShell
import de.chennemann.agentic.ui.chat.ThreadActivityUi
import de.chennemann.agentic.ui.chat.activity
import java.time.Instant

data class ThreadSearchSnippetUi(
    val author: String,
    val fromUser: Boolean,
    val text: String,
    val highlights: List<IntRange> = emptyList(),
)

data class ThreadSearchResultUi(
    val threadId: String,
    val title: String,
    val titleHighlights: List<IntRange> = emptyList(),
    val projectLabel: String,
    val settled: Boolean = false,
    val activity: ThreadActivityUi? = null,
    val snippet: ThreadSearchSnippetUi? = null,
)

internal const val TitleResultLimit = 30

private fun OrchestrationThreadShell.searchable(): Boolean =
    archivedAt == null && lineage?.relationshipToParent != "subagent"

/**
 * Title and project matches from the local shell, ranked as T3's palette ranks them in spirit: the
 * whole title first, then titles starting with the query, then every word at a word start, then
 * anywhere in the title, then matches that need the project name. Recent activity breaks ties.
 */
internal fun rankThreadTitleMatches(
    query: String,
    shell: OrchestrationShellSnapshot?,
    limit: Int = TitleResultLimit,
): List<ThreadSearchResultUi> {
    val normalized = normalizeThreadSearchQuery(query)
    if (normalized.isEmpty() || shell == null) return emptyList()
    val tokens = normalized.split(' ')
    val projects = shell.projects.associate { it.id to it.title }
    return shell.threads.asSequence()
        .filter { it.searchable() }
        .mapNotNull { thread ->
            val score = titleScore(normalized, tokens, thread.title, projects[thread.projectId].orEmpty())
                ?: return@mapNotNull null
            Triple(thread, score, thread.recency())
        }
        .sortedWith(compareBy<Triple<OrchestrationThreadShell, Int, Long>> { it.second }.thenByDescending { it.third }.thenBy { it.first.id })
        .take(limit)
        .map { (thread) -> thread.toResult(projects, tokens) }
        .toList()
}

/**
 * Message hits for threads the title matches did not already list, in the server's order. Hits for
 * threads the shell does not hold, archived threads, and sub-agents are left out.
 */
internal fun presentMessageMatches(
    query: String,
    matches: List<ThreadMessageMatch>,
    shell: OrchestrationShellSnapshot?,
    shownThreadIds: Set<String>,
): List<ThreadSearchResultUi> {
    if (shell == null) return emptyList()
    val tokens = normalizeThreadSearchQuery(query).split(' ').filter { it.isNotEmpty() }
    val threads = shell.threads.filter { it.searchable() }.associateBy { it.id }
    val projects = shell.projects.associate { it.id to it.title }
    return matches.asSequence()
        .filter { it.threadId !in shownThreadIds }
        .distinctBy { it.threadId }
        .mapNotNull { match ->
            val thread = threads[match.threadId] ?: return@mapNotNull null
            thread.toResult(projects, tokens).copy(
                snippet = ThreadSearchSnippetUi(
                    author = if (match.author == ThreadMessageAuthor.USER) "You" else "Agent",
                    fromUser = match.author == ThreadMessageAuthor.USER,
                    text = match.snippet,
                    highlights = highlightRanges(match.snippet, tokens),
                ),
            )
        }
        .toList()
}

private fun OrchestrationThreadShell.toResult(projects: Map<String, String>, tokens: List<String>) = ThreadSearchResultUi(
    threadId = id,
    title = title,
    titleHighlights = highlightRanges(title, tokens),
    projectLabel = projects[projectId].orEmpty(),
    settled = settledOverride == "settled" || (settledAt != null && settledOverride != "active"),
    activity = activity(),
)

private fun titleScore(query: String, tokens: List<String>, title: String, project: String): Int? {
    if (tokens.any { !title.contains(it, ignoreCase = true) && !project.contains(it, ignoreCase = true) }) return null
    return when {
        title.equals(query, ignoreCase = true) -> 0
        title.startsWith(query, ignoreCase = true) -> 1
        tokens.all { title.containsAtWordStart(it) } -> 2
        tokens.all { title.contains(it, ignoreCase = true) } -> 3
        else -> 4
    }
}

private fun String.containsAtWordStart(token: String): Boolean {
    var index = indexOf(token, ignoreCase = true)
    while (index >= 0) {
        if (index == 0 || !this[index - 1].isLetterOrDigit()) return true
        index = indexOf(token, index + 1, ignoreCase = true)
    }
    return false
}

/** Merged, case-insensitive ranges of every query word in [text]. */
internal fun highlightRanges(text: String, tokens: List<String>): List<IntRange> {
    val ranges = tokens.filter { it.isNotEmpty() }.flatMap { token ->
        generateSequence(text.indexOf(token, ignoreCase = true).takeIf { it >= 0 }) { previous ->
            text.indexOf(token, previous + token.length, ignoreCase = true).takeIf { it >= 0 }
        }.map { it until it + token.length }.toList()
    }.sortedBy { it.first }
    return ranges.fold(mutableListOf()) { merged, range ->
        val last = merged.lastOrNull()
        if (last != null && range.first <= last.last + 1) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
        } else {
            merged += range
        }
        merged
    }
}

private fun OrchestrationThreadShell.recency(): Long =
    listOfNotNull(latestUserMessageAt, createdAt).firstNotNullOfOrNull {
        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
    } ?: 0L
