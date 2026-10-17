package de.chennemann.agentic.ui.files

import de.chennemann.agentic.domain.orchestration.WorkspaceFileEntry

/** How a workspace path is presented, following IDE source-set conventions. */
enum class FileTreeCategory { Production, Test, Auxiliary, BuildOutput }

/** One visible tree line. Compacted directory chains share a row keyed by their deepest directory. */
data class FileTreeRow(
    val key: String,
    val label: String,
    val depth: Int,
    val isDirectory: Boolean,
    val expanded: Boolean,
    val category: FileTreeCategory,
    /** True for the topmost row of a build output subtree, where the UI labels the exclusion. */
    val outputRoot: Boolean = false,
)

/**
 * Directory expansion expressed as deviations from the defaults: production directories start expanded
 * (unless [productionExpanded] is cleared by "collapse all") and every other category starts collapsed.
 */
data class FileTreeExpansion(
    val productionExpanded: Boolean = true,
    val toggled: Set<String> = emptySet(),
) {
    fun toggle(key: String) = copy(toggled = if (key in toggled) toggled - key else toggled + key)
}

class FileTree private constructor(
    private val roots: List<Node>,
    private val nodes: Collection<Node>,
) {
    /** Flattens the visible tree. A non-blank [query] shows matches with their ancestors fully expanded. */
    fun rows(
        expansion: FileTreeExpansion = FileTreeExpansion(),
        showBuildOutput: Boolean = false,
        query: String = "",
    ): List<FileTreeRow> {
        val needle = query.trim()
        val visible = { node: Node -> showBuildOutput || node.category != FileTreeCategory.BuildOutput }
        val matched = if (needle.isEmpty()) {
            null
        } else {
            buildSet<Node> {
                nodes.forEach { node ->
                    if (!visible(node) || !node.path.contains(needle, ignoreCase = true)) return@forEach
                    var current: Node? = node
                    while (current != null && add(current)) current = current.parent
                }
            }
        }
        val included: (Node) -> Boolean = if (matched == null) visible else { node -> node in matched }
        val rows = mutableListOf<FileTreeRow>()
        fun emit(children: List<Node>, depth: Int) {
            children.forEach { start ->
                if (!included(start)) return@forEach
                var node = start
                val names = mutableListOf(start.name)
                while (node.isDirectory) {
                    val next = node.children.filter(included).singleOrNull() ?: break
                    if (!next.isDirectory || next.category != node.category || next.isSourceRoot || next.inSourceRoot != node.inSourceRoot) break
                    names += next.name
                    node = next
                }
                val expanded = node.isDirectory && (matched != null || expansion.isExpanded(node))
                rows += FileTreeRow(
                    key = node.path,
                    label = names.joinToString(if (start.inSourceRoot) "." else "/"),
                    depth = depth,
                    isDirectory = node.isDirectory,
                    expanded = expanded,
                    category = node.category,
                    outputRoot = node.category == FileTreeCategory.BuildOutput &&
                        start.parent?.category != FileTreeCategory.BuildOutput,
                )
                if (expanded) emit(node.children, depth + 1)
            }
        }
        emit(roots, 0)
        return rows
    }

    private fun FileTreeExpansion.isExpanded(node: Node): Boolean =
        (node.category == FileTreeCategory.Production && productionExpanded) != (node.path in toggled)

    private class Node(
        val path: String,
        val name: String,
        val isDirectory: Boolean,
        val parent: Node?,
        segments: List<String>,
    ) {
        val category = categorize(segments, isDirectory)
        val isSourceRoot = isDirectory && sourceRootIndex(segments) == segments.lastIndex
        val inSourceRoot = sourceRootIndex(segments).let { it in 0 until segments.lastIndex }
        val children = mutableListOf<Node>()
    }

    companion object {
        fun from(entries: List<WorkspaceFileEntry>): FileTree {
            val roots = mutableListOf<Node>()
            val byPath = HashMap<String, Node>()
            fun node(path: String, isDirectory: Boolean): Node = byPath[path] ?: run {
                val parentPath = path.substringBeforeLast('/', "")
                val parent = if (parentPath.isEmpty()) null else node(parentPath, true)
                Node(path, path.substringAfterLast('/'), isDirectory, parent, path.split('/')).also {
                    byPath[path] = it
                    (parent?.children ?: roots) += it
                }
            }
            entries.forEach { entry ->
                val path = entry.path.replace('\\', '/').trim('/')
                if (path.isNotEmpty()) node(path, entry.isDirectory)
            }
            fun sort(nodes: MutableList<Node>) {
                nodes.sortWith(compareBy<Node> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                nodes.forEach { sort(it.children) }
            }
            sort(roots)
            return FileTree(roots, byPath.values)
        }
    }
}

/** Classifies a path from its segments; the last segment is the entry itself. */
internal fun categorize(segments: List<String>, isDirectory: Boolean): FileTreeCategory {
    val directories = if (isDirectory) segments else segments.dropLast(1)
    val sourceIndex = segments.indexOf("src").takeIf { it >= 0 } ?: Int.MAX_VALUE
    return when {
        directories.withIndex().any { (index, name) ->
            name in AlwaysBuildOutputDirectories || (name in BuildOutputDirectories && index < sourceIndex)
        } -> FileTreeCategory.BuildOutput
        directories.any(::isTestDirectory) || (!isDirectory && isTestFile(segments.last())) -> FileTreeCategory.Test
        directories.withIndex().any { (index, name) ->
            name.startsWith('.') || (segments.getOrNull(index - 1) == "src" && name.contains("debug", ignoreCase = true))
        } -> FileTreeCategory.Auxiliary
        else -> FileTreeCategory.Production
    }
}

/** Index of the JVM source root (`src/<set>/kotlin` or `src/<set>/java`) within [segments], or -1. */
private fun sourceRootIndex(segments: List<String>): Int =
    segments.indices.firstOrNull { index ->
        index >= 2 && segments[index] in SourceRootNames && segments[index - 2] == "src"
    } ?: -1

private fun isTestDirectory(name: String): Boolean =
    name.lowercase() in TestDirectories ||
        (name.length > 4 && (name.endsWith("Test") || name.endsWith("Tests"))) ||
        (name.startsWith("test") && name.getOrNull(4)?.isUpperCase() == true)

private fun isTestFile(name: String): Boolean {
    val base = name.substringBefore('.')
    return ".test." in name || ".spec." in name || base.endsWith("_test") || name.startsWith("test_") ||
        (base.length > 4 && (base.endsWith("Test") || base.endsWith("Tests")))
}

private val SourceRootNames = setOf("kotlin", "java")
private val TestDirectories = setOf("test", "tests", "__tests__", "e2e", "androidtest", "testfixtures")

/** Output directory names that only count outside source sets, so a `build` package stays production code. */
private val BuildOutputDirectories = setOf("build", "out", "dist", "target", "obj", "coverage")

private val AlwaysBuildOutputDirectories = setOf(
    "node_modules", ".gradle", ".kotlin", ".cxx", ".externalNativeBuild", ".next", ".nuxt", ".turbo",
    ".dart_tool", "__pycache__", ".pytest_cache", ".mypy_cache", ".venv", ".parcel-cache", ".svelte-kit",
)
