package de.chennemann.agentic.domain.orchestration

import java.net.URLDecoder

/** A file a chat link points at, with the line and column the author referenced. */
data class FileLinkTarget(val path: String, val line: Int? = null, val column: Int? = null)

/**
 * Recognizes markdown link destinations that name files rather than web pages or app routes.
 * Mirrors T3's `parseMarkdownFileLink` (packages/client-runtime/src/markdownLinks.ts) so every client
 * treats the same links as files.
 */
fun parseMarkdownFileLink(href: String): FileLinkTarget? {
    val normalized = href.trim().let { if (it.startsWith("<") && it.endsWith(">")) it.substring(1, it.length - 1) else it }
    if (normalized.isEmpty() || normalized.startsWith("#") || normalized.startsWith("//")) return null
    val source = (if (normalized.startsWith("file:", ignoreCase = true)) parseFileUrl(normalized) else null)
        ?: splitSearchAndHash(normalized)
    // A percent-encoded drive colon (`/c%3A/`) only becomes strippable once decoded.
    val path = stripSlashPrefixedWindowsDrive(percentDecode(source.path.trim()))
    val hash = percentDecode(source.hash.trim())
    if (path.isEmpty() || hasExternalScheme(path)) return null
    val position = splitFilePathPosition(path, hash)
    return position.takeIf { looksLikeFilePath(it.path, path) }
}

/**
 * Picks path-shaped inline code, which mostly holds identifiers, commands and refs (`node.meta`,
 * `origin/main`), so it demands an unambiguous path prefix, a file extension or a `:line` suffix.
 * Mirrors T3's `inlineCodeFilePathCandidate`; the candidate still goes through [parseMarkdownFileLink].
 */
fun inlineCodeFilePathCandidate(codeText: String): String? {
    val trimmed = codeText.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it == '`' }) return null
    val candidate = if (isWindowsAbsolutePath(trimmed)) trimmed else trimmed.replace('\\', '/')
    val hasPosition = PositionSuffix.containsMatchIn(candidate)
    if (!hasPosition && candidate.none { it == '/' || it == '\\' }) return null
    val explicitPath = RelativePathPrefix.containsMatchIn(candidate) || candidate.startsWith("/") || isWindowsAbsolutePath(candidate)
    if (!explicitPath) {
        val withoutPosition = candidate.replace(PositionSuffix, "")
        if (looksLikeHostname(withoutPosition.substringBefore('/'), hasPosition)) return null
        val basename = withoutPosition.trimEnd('/', '\\').split('/', '\\').last()
        if (VersionSuffix.containsMatchIn(basename)) return null
        if (!hasPosition && !FileExtension.containsMatchIn(basename)) return null
    }
    return candidate
}

/**
 * Resolves a link written inside the workspace file [documentPath] the way markdown resolves relative
 * links: against the document's own directory. Absolute and home paths are returned unchanged, and a
 * link climbing above the workspace keeps its `..` so opening it is rejected.
 */
fun resolveDocumentLink(target: FileLinkTarget, documentPath: String): FileLinkTarget {
    val path = target.path
    if (isAbsoluteEnvironmentPath(path) || path.startsWith("~")) return target
    val segments = mutableListOf<String>()
    "${documentPath.substringBeforeLast('/', "")}/$path".replace('\\', '/').split('/').forEach { segment ->
        when {
            segment.isEmpty() || segment == "." -> Unit
            segment == ".." && segments.isNotEmpty() && segments.last() != ".." -> segments.removeAt(segments.lastIndex)
            else -> segments += segment
        }
    }
    return target.copy(path = segments.joinToString("/"))
}

/** The path of [path] inside [workspaceRoot], `.` for the root itself, or null when it lies outside. */
fun workspaceRelativeFilePath(path: String, workspaceRoot: String?): String? {
    if (workspaceRoot.isNullOrEmpty()) return null
    val normalizedPath = stripSlashPrefixedWindowsDrive(path.replace('\\', '/'))
    val normalizedRoot = stripSlashPrefixedWindowsDrive(workspaceRoot.replace('\\', '/')).trimEnd('/')
    val caseInsensitive = isWindowsAbsolutePath(stripSlashPrefixedWindowsDrive(workspaceRoot))
    val pathForCompare = if (caseInsensitive) normalizedPath.lowercase() else normalizedPath
    val rootForCompare = if (caseInsensitive) normalizedRoot.lowercase() else normalizedRoot
    if (pathForCompare.trimEnd('/') == rootForCompare) return "."
    if (!pathForCompare.startsWith("$rootForCompare/")) return null
    return normalizedPath.substring(normalizedRoot.length + 1)
}

internal fun splitFilePathPosition(path: String, hash: String = ""): FileLinkTarget {
    val suffix = PositionSuffixCapture.find(path)
    val match = suffix ?: PositionHash.find(hash) ?: return FileLinkTarget(path)
    val line = match.groupValues[1].toIntOrNull()
    val column = match.groups[2]?.value?.toIntOrNull()
    return FileLinkTarget(
        path = if (suffix != null) path.dropLast(suffix.value.length) else path,
        line = line?.takeIf { it > 0 },
        column = column?.takeIf { it > 0 },
    )
}

private data class LinkParts(val path: String, val hash: String)

/** Turns a `file:` URL into a still percent-encoded host path; a non-localhost authority becomes a UNC share. */
private fun parseFileUrl(href: String): LinkParts? {
    val rest = href.substring("file:".length)
    val hashIndex = rest.indexOf('#')
    val hash = if (hashIndex >= 0) rest.substring(hashIndex) else ""
    val location = (if (hashIndex >= 0) rest.substring(0, hashIndex) else rest).substringBefore('?')
    val authorityEnd = if (location.startsWith("//")) location.indexOf('/', 2).let { if (it < 0) location.length else it } else 0
    val host = if (authorityEnd > 0) location.substring(2, authorityEnd) else ""
    if (host.any { !it.isLetterOrDigit() && it !in ".-_" }) return null
    val pathname = location.substring(authorityEnd)
    val path = if (host.isEmpty() || host.equals("localhost", ignoreCase = true)) {
        pathname
    } else {
        "\\\\$host${pathname.replace('/', '\\')}"
    }
    return if (path.isEmpty()) null else LinkParts(stripSlashPrefixedWindowsDrive(path), hash)
}

private fun splitSearchAndHash(value: String): LinkParts {
    val hashIndex = value.indexOf('#')
    val pathWithSearch = if (hashIndex >= 0) value.substring(0, hashIndex) else value
    return LinkParts(pathWithSearch.substringBefore('?'), if (hashIndex >= 0) value.substring(hashIndex) else "")
}

private fun percentDecode(value: String): String =
    try {
        URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        value
    }

private fun stripSlashPrefixedWindowsDrive(path: String): String =
    if (SlashPrefixedWindowsDrive.containsMatchIn(path)) path.substring(1) else path

private fun isWindowsAbsolutePath(value: String): Boolean = value.startsWith("\\\\") || WindowsDrive.containsMatchIn(value)

private fun hasExternalScheme(path: String): Boolean {
    if (isWindowsAbsolutePath(path)) return false
    val match = ExternalScheme.find(path) ?: return false
    val rest = match.groupValues[2]
    return rest.startsWith("//") || !PositionOnly.matches(rest)
}

/** Only a `:line` suffix the author wrote counts as evidence; a `#L` anchor never turns `/chat/settings` into a file. */
private fun looksLikeFilePath(path: String, authoredPath: String): Boolean {
    if (isWindowsAbsolutePath(path) || RelativePathPrefix.containsMatchIn(path)) return true
    if (path.startsWith("/")) return looksLikePosixFilesystemPath(authoredPath)
    if (path in ExtensionlessFileNames) return true
    return RelativeFilePath.matches(authoredPath) || RelativeFileName.matches(path)
}

private fun looksLikeHostname(segment: String, hasPosition: Boolean): Boolean {
    if (segment.startsWith(".")) return false
    val lowered = segment.lowercase()
    if (lowered == "localhost" || NumericDotted.matches(segment)) return true
    val labels = lowered.split('.')
    if (labels.size < 2) return false
    val lastLabel = labels.last()
    return lastLabel in GenericHostnameTlds || (!hasPosition && lastLabel in CountryHostnameTlds)
}

private fun looksLikePosixFilesystemPath(path: String): Boolean {
    if (!path.startsWith("/")) return false
    if (PosixFileRootPrefixes.any { path.startsWith(it) }) return true
    if (PositionSuffix.containsMatchIn(path)) return true
    val basename = path.substringAfterLast('/')
    return basename in ExtensionlessFileNames || FileExtension.containsMatchIn(basename)
}

private val SlashPrefixedWindowsDrive = Regex("^/[A-Za-z]:[\\\\/]")
private val WindowsDrive = Regex("^[A-Za-z]:([/\\\\]|$)")
private val RelativePathPrefix = Regex("^(~/|\\.{1,2}/)")
private val RelativeFilePath =
    Regex("(?:[A-Za-z0-9._-]+(?: +[A-Za-z0-9._-]+)*/)+[A-Za-z0-9._-]+(?: +[A-Za-z0-9._-]+)*(?::\\d+){0,2}")
private val RelativeFileName = Regex("[A-Za-z0-9._-]+(?: +[A-Za-z0-9._-]+)*\\.[A-Za-z0-9_-]+(?::\\d+){0,2}")
private val ExternalScheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):(.*)$")
private val PositionSuffix = Regex(":\\d+(?::\\d+)?$")
private val PositionSuffixCapture = Regex(":(\\d+)(?::(\\d+))?$")
private val PositionHash = Regex("^#L(\\d+)(?:C(\\d+))?$", RegexOption.IGNORE_CASE)
private val PositionOnly = Regex("\\d+(?::\\d+)?")
private val FileExtension = Regex("\\.[A-Za-z0-9_-]+$")

// A final dot between digits marks a version or model id (`glm-5.3`, `Qwen2.5-Coder`), not an extension.
private val VersionSuffix = Regex("\\d\\.\\d[^.]*$")
private val NumericDotted = Regex("\\d+(?:\\.\\d+)+")

// These allowlists keep dotted directories such as `conf.d/` and names such as `Makefile.in:12` from reading as hosts.
private val GenericHostnameTlds = setOf(
    "com", "net", "org", "io", "dev", "app", "ai", "co", "edu", "gov", "mil", "info", "biz", "xyz", "me", "tv", "cc",
    "gg", "chat", "cloud", "site", "online", "tech", "store", "link",
)

// Country codes also name file extensions, so a :line suffix makes `.pl` and `.pt` files more likely than hosts.
private val CountryHostnameTlds = setOf(
    "uk", "de", "fr", "nl", "se", "no", "fi", "dk", "pl", "ch", "at", "be", "es", "it", "pt", "eu", "us", "ca", "au",
    "nz", "jp", "kr", "cn", "br", "ru", "mx", "ie", "cz", "tr", "sg", "hk",
)

// Standard OS and dev-container roots; app-route-ish prefixes like /app/ or /chat/ never read as files.
private val PosixFileRootPrefixes = listOf(
    "/Users/", "/home/", "/tmp/", "/var/", "/etc/", "/opt/", "/mnt/", "/Volumes/", "/private/", "/root/", "/usr/",
    "/bin/", "/sbin/", "/lib/", "/lib64/", "/srv/", "/dev/", "/proc/", "/sys/", "/run/", "/boot/", "/media/",
    "/workspace/", "/workspaces/",
)

private val ExtensionlessFileNames = setOf(
    "Makefile", "makefile", "GNUmakefile", "Dockerfile", "Containerfile", "Justfile", "justfile", "Rakefile", "Gemfile",
    "Procfile", "Brewfile", "Caddyfile", "Vagrantfile", "Jenkinsfile", "Podfile", "Fastfile", "BUILD", "WORKSPACE",
    "LICENSE", "LICENCE", "COPYING", "NOTICE", "AUTHORS", "CONTRIBUTORS", "CHANGELOG", "README", "CODEOWNERS",
)
