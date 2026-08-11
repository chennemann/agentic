package de.chennemann.agentic.ui.chat

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatKeyboardScrollPolicyTest {
    @Test
    fun keyboard_opened_before_latest_message_is_dismissed_on_history_scroll() {
        assertTrue(
            shouldDismissKeyboardOnHistoryScroll(
                keyboardOpenedManually = true,
                messageArrivedSinceKeyboardOpened = true,
            ),
        )
    }

    @Test
    fun keyboard_opened_after_latest_message_stays_open_on_history_scroll() {
        assertFalse(
            shouldDismissKeyboardOnHistoryScroll(
                keyboardOpenedManually = true,
                messageArrivedSinceKeyboardOpened = false,
            ),
        )
    }

    @Test
    fun keyboard_not_opened_manually_is_dismissed_on_history_scroll() {
        assertTrue(
            shouldDismissKeyboardOnHistoryScroll(
                keyboardOpenedManually = false,
                messageArrivedSinceKeyboardOpened = false,
            ),
        )
    }
}
