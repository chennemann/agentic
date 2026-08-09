package de.chennemann.agentic.ui.chat

enum class HardwareKey { ENTER, ESCAPE, N, K, UNKNOWN }
enum class KeyboardFocus { GLOBAL, COMPOSER }
enum class KeyboardAction { SEND, NEWLINE, STOP_TURN, NEW_TASK, OPEN_NAVIGATION }

data class HardwareKeyStroke(
    val key: HardwareKey,
    val focus: KeyboardFocus,
    val ctrl: Boolean = false,
    val meta: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val repeat: Boolean = false,
)

object HardwareKeyboardBindings {
    fun map(stroke: HardwareKeyStroke, canSend: Boolean, turnRunning: Boolean): KeyboardAction? {
        if (stroke.repeat || stroke.alt) return null
        val command = stroke.ctrl || stroke.meta
        return when {
            stroke.focus == KeyboardFocus.COMPOSER && stroke.key == HardwareKey.ENTER && command &&
                !stroke.shift && canSend -> KeyboardAction.SEND
            stroke.focus == KeyboardFocus.COMPOSER && stroke.key == HardwareKey.ENTER && stroke.shift &&
                !command -> KeyboardAction.NEWLINE
            stroke.focus == KeyboardFocus.COMPOSER && stroke.key == HardwareKey.ESCAPE && !command &&
                !stroke.shift && turnRunning -> KeyboardAction.STOP_TURN
            stroke.focus == KeyboardFocus.GLOBAL && stroke.key == HardwareKey.N && command &&
                !stroke.shift -> KeyboardAction.NEW_TASK
            stroke.focus == KeyboardFocus.GLOBAL && stroke.key == HardwareKey.K && command &&
                !stroke.shift -> KeyboardAction.OPEN_NAVIGATION
            else -> null
        }
    }
}

fun KeyboardAction.toChatUiEvent(): ChatUiEvent? = when (this) {
    KeyboardAction.SEND -> ChatUiEvent.MessageSubmitted
    KeyboardAction.STOP_TURN -> ChatUiEvent.TurnInterruptRequested
    KeyboardAction.NEW_TASK -> ChatUiEvent.NewThreadRequested
    KeyboardAction.OPEN_NAVIGATION -> ChatUiEvent.PickerRequested(ChatPickerUi.NAVIGATION)
    KeyboardAction.NEWLINE -> null
}
