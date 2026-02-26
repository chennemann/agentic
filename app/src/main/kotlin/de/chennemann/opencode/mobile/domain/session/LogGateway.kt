package de.chennemann.opencode.mobile.domain.session

interface LogGateway {
    fun log(
        level: LogLevel,
        unit: LogUnit,
        tag: String,
        event: String,
        message: String,
        context: Map<String, String> = emptyMap(),
        error: Throwable? = null,
    )

    fun debug(
        unit: LogUnit,
        tag: String,
        event: String,
        message: String,
        context: Map<String, String> = emptyMap(),
    ) {
        log(LogLevel.debug, unit, tag, event, message, context)
    }

    fun info(
        unit: LogUnit,
        tag: String,
        event: String,
        message: String,
        context: Map<String, String> = emptyMap(),
    ) {
        log(LogLevel.info, unit, tag, event, message, context)
    }

    fun warn(
        unit: LogUnit,
        tag: String,
        event: String,
        message: String,
        context: Map<String, String> = emptyMap(),
        error: Throwable? = null,
    ) {
        log(LogLevel.warn, unit, tag, event, message, context, error)
    }

    fun error(
        unit: LogUnit,
        tag: String,
        event: String,
        message: String,
        context: Map<String, String> = emptyMap(),
        error: Throwable? = null,
    ) {
        log(LogLevel.error, unit, tag, event, message, context, error)
    }
}
