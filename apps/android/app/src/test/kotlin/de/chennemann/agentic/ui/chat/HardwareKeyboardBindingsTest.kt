package de.chennemann.agentic.ui.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HardwareKeyboardBindingsTest {
    @Test
    fun `documented shortcuts map to the same public UI events as touch`() {
        assertEquals(ChatUiEvent.MessageSubmitted, action(HardwareKey.ENTER, KeyboardFocus.COMPOSER, ctrl = true)?.toChatUiEvent())
        assertEquals(ChatUiEvent.TurnInterruptRequested, action(HardwareKey.ESCAPE, KeyboardFocus.COMPOSER)?.toChatUiEvent())
        assertEquals(ChatUiEvent.NewThreadRequested, action(HardwareKey.N, KeyboardFocus.GLOBAL, ctrl = true)?.toChatUiEvent())
        assertEquals(
            ChatUiEvent.PickerRequested(ChatPickerUi.NAVIGATION),
            action(HardwareKey.K, KeyboardFocus.GLOBAL, meta = true)?.toChatUiEvent(),
        )
        assertEquals(KeyboardAction.NEWLINE, action(HardwareKey.ENTER, KeyboardFocus.COMPOSER, shift = true))
    }

    @Test
    fun `availability focus modifiers and repeats guard shortcuts`() {
        assertNull(action(HardwareKey.ENTER, KeyboardFocus.COMPOSER, ctrl = true, canSend = false))
        assertNull(action(HardwareKey.ESCAPE, KeyboardFocus.COMPOSER, turnRunning = false))
        assertNull(action(HardwareKey.N, KeyboardFocus.COMPOSER, ctrl = true))
        assertNull(action(HardwareKey.N, KeyboardFocus.GLOBAL, ctrl = true, repeat = true))
        assertNull(action(HardwareKey.K, KeyboardFocus.GLOBAL, ctrl = true, alt = true))
    }

    private fun action(
        key: HardwareKey,
        focus: KeyboardFocus,
        ctrl: Boolean = false,
        meta: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
        repeat: Boolean = false,
        canSend: Boolean = true,
        turnRunning: Boolean = true,
    ) = HardwareKeyboardBindings.map(
        HardwareKeyStroke(key, focus, ctrl, meta, shift, alt, repeat),
        canSend,
        turnRunning,
    )
}
