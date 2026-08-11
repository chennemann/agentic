package de.chennemann.agentic.ui.chat

internal fun shouldDismissKeyboardOnHistoryScroll(
    keyboardOpenedManually: Boolean,
    messageArrivedSinceKeyboardOpened: Boolean,
): Boolean = !keyboardOpenedManually || messageArrivedSinceKeyboardOpened
