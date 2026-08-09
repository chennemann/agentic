package de.chennemann.agentic.domain.shortcuts

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.Flow

sealed interface ShortcutRoute {
    val environmentId: String

    data class NewTask(override val environmentId: String, val projectId: String?) : ShortcutRoute
    data class Thread(override val environmentId: String, val projectId: String, val threadId: String) : ShortcutRoute
}

sealed interface ShortcutParseResult {
    data class Valid(val route: ShortcutRoute) : ShortcutParseResult
    data class Invalid(val message: String) : ShortcutParseResult
}

object ShortcutRouteCodec {
    fun encode(route: ShortcutRoute): String = when (route) {
        is ShortcutRoute.NewTask -> "agentic://shortcut/new?environmentId=${route.environmentId.encoded()}" +
            route.projectId?.let { "&projectId=${it.encoded()}" }.orEmpty()
        is ShortcutRoute.Thread -> "agentic://shortcut/thread?environmentId=${route.environmentId.encoded()}" +
            "&projectId=${route.projectId.encoded()}&threadId=${route.threadId.encoded()}"
    }

    fun parse(value: String?): ShortcutParseResult = try {
        val uri = URI(value ?: return ShortcutParseResult.Invalid("Shortcut route is missing."))
        if (uri.scheme != "agentic" || uri.host != "shortcut") return ShortcutParseResult.Invalid("Shortcut route is malformed.")
        val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { part ->
            val pieces = part.split('=', limit = 2)
            pieces[0].decoded() to pieces.getOrElse(1) { "" }.decoded()
        }
        val environmentId = query["environmentId"]?.takeIf { it.isNotBlank() }
            ?: return ShortcutParseResult.Invalid("Shortcut environment is missing.")
        when (uri.path) {
            "/new" -> ShortcutParseResult.Valid(ShortcutRoute.NewTask(environmentId, query["projectId"]?.takeIf(String::isNotBlank)))
            "/thread" -> {
                val projectId = query["projectId"]?.takeIf(String::isNotBlank)
                val threadId = query["threadId"]?.takeIf(String::isNotBlank)
                if (projectId == null || threadId == null) ShortcutParseResult.Invalid("Shortcut thread identity is incomplete.")
                else ShortcutParseResult.Valid(ShortcutRoute.Thread(environmentId, projectId, threadId))
            }
            else -> ShortcutParseResult.Invalid("Shortcut route is unknown.")
        }
    } catch (_: Exception) {
        ShortcutParseResult.Invalid("Shortcut route is malformed.")
    }

    private fun String.encoded() = URLEncoder.encode(this, StandardCharsets.UTF_8)
    private fun String.decoded() = URLDecoder.decode(this, StandardCharsets.UTF_8)
}

data class DynamicShortcutSpec(val id: String, val label: String, val route: ShortcutRoute)

interface DynamicShortcutPublisher {
    fun publish(shortcuts: List<DynamicShortcutSpec>)
}

interface ShortcutRouteInbox {
    val routes: Flow<ShortcutParseResult>
    fun receive(result: ShortcutParseResult)
}
