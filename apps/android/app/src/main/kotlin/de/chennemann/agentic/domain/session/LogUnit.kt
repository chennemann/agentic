package de.chennemann.agentic.domain.session

enum class LogUnit(val key: String, val label: String) {
    sync("sync", "Sync"),
    stream("stream", "Stream"),
    message("message", "Message"),
    workspace("workspace", "Workspace"),
    network("network", "Network"),
    navigation("navigation", "Navigation"),
    cache("cache", "Cache"),
    security("security", "Security"),
    ui("ui", "UI"),
    system("system", "System");

    companion object {
        fun from(key: String): LogUnit {
            return entries.firstOrNull { it.key == key } ?: system
        }
    }
}
