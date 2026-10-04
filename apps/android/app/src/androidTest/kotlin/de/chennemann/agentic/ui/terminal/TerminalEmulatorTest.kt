package de.chennemann.agentic.ui.terminal

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import de.chennemann.agentic.ui.theme.MobileTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import org.connectbot.terminal.VTermKey
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Executes the shipped JNI emulator, Compose renderer and Android input connection. */
class TerminalEmulatorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var emulator: TerminalEmulator
    private var selection: SelectionController? = null
    private val sent = CopyOnWriteArrayList<String>()

    @After fun releaseNativeScreen() {
        if (::emulator.isInitialized) emulator.close()
    }

    @Test fun closingNativeScreenIsIdempotentAndQueuedWorkBecomesHarmless() {
        show()
        write("allocate native screen\u001b[6n")
        emulator.pasteText("queued paste")
        compose.runOnIdle {
            emulator.close()
            emulator.close()
            sent.clear()
        }
        write("ignored after close")
        emulator.resize(30, 100)
        emulator.dispatchKey(0, VTermKey.ENTER)
        emulator.dispatchCharacter(0, 'x'.code)
        emulator.pasteText("ignored after close")
        compose.waitForIdle()
        assertTrue(sent.isEmpty())
    }

    private fun show(
        screen: TerminalEmulator = TerminalEmulatorFactory.create(onKeyboardInput = {
            sent +=
                it.toString(Charsets.UTF_8)
        })
    ) {
        emulator = screen
        compose.setContent {
            Terminal(
                emulator,
                Modifier.fillMaxSize().testTag("native-terminal"),
                forcedSize = 80 to 24,
                keyboardEnabled = true,
                showSoftKeyboard = false,
                onSelectionControllerAvailable = { selection = it }
            )
        }
        compose.waitForIdle()
    }

    private fun write(text: String) = emulator.writeInput(text.toByteArray())
    private fun text(): String = compose.runOnIdle {
        selection!!.selectAll()
        selection!!.copySelection().also { selection!!.clearSelection() }
    }
    private fun awaitText(expected: String) {
        compose.waitUntil(15000) { text().contains(expected) }
    }

    @Test fun cursorMovementEraseAndSplitAnsiAreInterpreted() {
        show()
        write("progress 10%\r\u001b[2Kdone\r\n\u001b[")
        write("31mRED\u001b[0m\r\nabcdef\u001b[3DXYZ")
        awaitText("abcXYZ")
        val rendered = text()
        assertTrue(rendered.contains("done"))
        assertTrue(rendered.contains("RED"))
        assertFalse(rendered.contains("progress"))
        assertFalse(rendered.contains("\u001b"))
    }

    @Test fun alternateScreenRestoresShellAndUnicodeSurvives() {
        show()
        write("shell café \u754c e\u0301 \uD83D\uDE00\r\n")
        awaitText("shell café")
        write("\u001b[?1049h\u001b[2J\u001b[Heditor")
        awaitText("editor")
        assertFalse(text().contains("shell café"))
        write("\u001b[?1049l")
        awaitText("shell café")
        assertTrue(text().contains("\u754c"))
        assertTrue(text().contains("\uD83D\uDE00"))
    }

    @Test fun applicationKeysControlAndBracketedPasteUseTerminalModes() {
        show()
        write("\u001b[?1h\u001b[?2004h")
        emulator.dispatchKey(0, VTermKey.UP)
        emulator.dispatchCharacter(4, 'c'.code)
        emulator.dispatchKey(0, VTermKey.TAB)
        emulator.pasteText("echo hello\nworld")
        compose.waitUntil(15000) { sent.joinToString("").contains("\u001b[201~") }
        val data = sent.joinToString("")
        assertTrue(data.startsWith("\u001bOA\u0003\t"))
        assertTrue(data.contains("\u001b[200~echo hello"))
        assertTrue(data.endsWith("\u001b[201~"))
    }

    @Test fun scrollbackIsBoundedAndSelectionIncludesRecentHistory() {
        show()
        write((1..1500).joinToString("\r\n") { "line-$it" })
        awaitText("line-1500")
        val rendered = text()
        assertTrue(rendered.contains("line-1499"))
        assertFalse(rendered.lineSequence().any { it.trim() == "line-1" })
    }

    @Test fun clearErasesHistoryWithoutResettingApplicationKeyMode() {
        val display = ConnectBotTerminalDisplay(80, 24, { sent += it }, { _, _ -> })
        show(display.emulator)
        display.finishReplay()
        write("\u001b[?25l" + (1..100).joinToString("\r\n") { "old-line-$it" } + "\u001b[?1h")
        awaitText("old-line-100")
        val before = compose.onNodeWithTag("native-terminal").captureToImage().asAndroidBitmap()
        runBlocking { display.clear() }
        compose.waitUntil(15000) { !text().contains("old-line-") }
        assertFalse(before.sameAs(compose.onNodeWithTag("native-terminal").captureToImage().asAndroidBitmap()))
        write("AFTER_CLEAR")
        awaitText("AFTER_CLEAR")
        assertFalse(text().contains("old-line-"))
        emulator.dispatchKey(0, VTermKey.UP)
        compose.waitUntil(15000) { sent.joinToString("").contains("\u001bOA") }
        display.detach()
    }

    @Test fun pinchMagnifiesWithoutResizingOrCorruptingScreen() {
        emulator = TerminalEmulatorFactory.create()
        compose.setContent {
            Terminal(
                emulator,
                Modifier.fillMaxSize().testTag("zoom-terminal"),
                showSoftKeyboard = false,
                onSelectionControllerAvailable = { selection = it }
            )
        }
        write("\u001b[?25l" + (1..30).joinToString("\r\n") { "ZOOM_CONTENT_$it" })
        awaitText("ZOOM_CONTENT_30")
        val initialColumns = emulator.dimensions.columns
        val node = compose.onNodeWithTag("zoom-terminal")
        val before = node.captureToImage().asAndroidBitmap()
        node.performTouchInput {
            down(0, Offset(centerX - 60, centerY))
            down(1, Offset(centerX + 60, centerY))
            moveTo(0, Offset(centerX - 120, centerY))
            moveTo(1, Offset(centerX + 120, centerY))
            moveTo(0, Offset(centerX - 200, centerY))
            moveTo(1, Offset(centerX + 200, centerY))
        }
        compose.waitForIdle()
        assertFalse(before.sameAs(node.captureToImage().asAndroidBitmap()))
        node.performTouchInput {
            up(0)
            up(1)
        }
        compose.waitForIdle()
        assertTrue(initialColumns == emulator.dimensions.columns)
        assertTrue(text().contains("ZOOM_CONTENT_30"))
    }

    @Test fun replayDoesNotReplyToHistoricalQueriesButLiveQueriesDo() {
        lateinit var display: ConnectBotTerminalDisplay
        compose.runOnIdle {
            display = ConnectBotTerminalDisplay(80, 24, { sent += it }, { _, _ -> })
        }
        runBlocking { display.append("\u001b[6n") }
        compose.runOnIdle { display.finishReplay() }
        compose.waitForIdle()
        assertTrue(sent.isEmpty())
        runBlocking { display.append("\u001b[6n") }
        compose.waitUntil(15000) { sent.isNotEmpty() }
        assertTrue(sent.joinToString("").endsWith("R"))
        compose.runOnIdle { display.detach() }
    }

    @Test fun androidImeCommitsDeletesAndToolbarSendsRawKeys() {
        val display = ConnectBotTerminalDisplay(80, 24, { sent += it }, { _, _ -> })
        display.finishReplay()
        compose.setContent {
            MobileTheme {
                TerminalScreen(
                    TerminalUiState(selectedId = "term-1", display = display, status = "running", canInput = true),
                    onBack = {
                    },
                    onCreate = {},
                    onSelect = {},
                    onReconnect = {},
                    onClear = {},
                    onRestart = {},
                    onClose = {}
                )
            }
        }
        compose.onNodeWithText("Keyboard", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            val view = findIme(compose.activity.window.decorView)
            assertNotNull("Native terminal input view", view)
            val connection = view!!.onCreateInputConnection(EditorInfo())!!
            connection.commitText("café", 1)
            connection.deleteSurroundingText(1, 0)
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        compose.onNodeWithText("Ctrl+C").performScrollTo().performClick()
        compose.onNodeWithText("Tab").performScrollTo().performClick()
        compose.waitUntil(15000) { sent.joinToString("").contains("\u0003") && sent.joinToString("").endsWith("\t") }
        val data = sent.joinToString("")
        assertTrue(data.contains("café"))
        assertTrue(data.contains("\u007f") || data.contains("\b"))
        assertTrue(data.contains("\r"))
        display.detach()
    }

    @Test fun terminalMenuKeepsKeyboardAndGridStable() {
        val sizes = CopyOnWriteArrayList<Pair<Int, Int>>()
        val display = ConnectBotTerminalDisplay(80, 24, {}, { columns, rows -> sizes += columns to rows })
        emulator = display.emulator
        display.finishReplay()
        compose.setContent {
            MobileTheme {
                TerminalScreen(
                    TerminalUiState(selectedId = "term-1", display = display, status = "running", canInput = true),
                    onBack = {},
                    onCreate = {},
                    onSelect = {},
                    onReconnect = {},
                    onClear = { runBlocking { display.clear() } },
                    onRestart = {},
                    onClose = {}
                )
            }
        }
        compose.onNodeWithText("Keyboard", useUnmergedTree = true).performClick()
        // Allow the real platform IME animation to settle before observing menu resizing.
        compose.waitUntil(15000) { emulator.dimensions.rows < 35 }
        Thread.sleep(1500)
        compose.waitForIdle()
        sizes.clear()
        compose.onNodeWithText("More").performClick()
        Thread.sleep(1000)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Clear screen").assertDoesNotExist()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Clear screen").performClick()
        Thread.sleep(1500)
        compose.waitForIdle()
        assertTrue("Menu unexpectedly resized the terminal: $sizes", sizes.isEmpty())
        display.detach()
    }

    private fun findIme(view: View): View? {
        if (view.javaClass.simpleName == "ImeInputView") return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findIme(view.getChildAt(index))?.let { return it }
        return null
    }
}
