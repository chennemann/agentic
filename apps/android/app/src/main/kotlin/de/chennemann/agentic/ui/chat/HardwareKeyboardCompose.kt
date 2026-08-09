package de.chennemann.agentic.ui.chat

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

fun KeyEvent.toHardwareKeyStroke(focus: KeyboardFocus): HardwareKeyStroke? {
    if (type != KeyEventType.KeyDown) return null
    val mapped = when (key) {
        Key.Enter, Key.NumPadEnter -> HardwareKey.ENTER
        Key.Escape -> HardwareKey.ESCAPE
        Key.N -> HardwareKey.N
        Key.K -> HardwareKey.K
        else -> HardwareKey.UNKNOWN
    }
    return HardwareKeyStroke(
        key = mapped,
        focus = focus,
        ctrl = isCtrlPressed,
        meta = isMetaPressed,
        shift = isShiftPressed,
        alt = isAltPressed,
        repeat = nativeKeyEvent.repeatCount > 0,
    )
}
