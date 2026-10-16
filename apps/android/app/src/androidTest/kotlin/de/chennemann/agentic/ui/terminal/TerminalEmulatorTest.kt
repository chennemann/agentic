package de.chennemann.agentic.ui.terminal

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import de.chennemann.agentic.ui.theme.MobileTheme
import expo.modules.t3terminal.T3TerminalView
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Runs the vendored Ghostty JNI, upstream Canvas renderer, and Android input connection. */
class TerminalEmulatorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var display: GhosttyTerminalDisplay
    private val sent = CopyOnWriteArrayList<String>()
    private val sizes = CopyOnWriteArrayList<Pair<Int, Int>>()
    private val viewGeneration = mutableStateOf(0)
    private val canInput = mutableStateOf(true)

    @After fun releaseNativeScreen() {
        if (::display.isInitialized) display.detach()
    }

    private fun show(history: String = "") {
        display = runBlocking {
            GhosttyTerminalDisplayFactory().create(history, 80, 24, { sent += it }, { cols, rows ->
                sizes += cols to rows
            }) as GhosttyTerminalDisplay
        }
        compose.setContent {
            MobileTheme {
                key(viewGeneration.value) {
                    TerminalScreen(
                        TerminalUiState(
                            selectedId = "term-1",
                            display = display,
                            status = "running",
                            canInput = canInput.value
                        ),
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
        }
        compose.waitForIdle()
    }

    private fun write(text: String) = runBlocking { display.append(text) }
    private fun text(): String = display.session.visibleText()
    private fun awaitText(expected: String) = compose.waitUntil(15000) { text().contains(expected) }
    private fun nativeView(): T3TerminalView = find(compose.activity.window.decorView) {
        it is T3TerminalView
    } as T3TerminalView
    private fun find(view: View, predicate: (View) -> Boolean): View? {
        if (predicate(view)) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                find(view.getChildAt(index), predicate)?.let {
                    return it
                }
            }
        }
        return null
    }

    @Test fun cursorMovementEraseAndSplitAnsiAreInterpreted() {
        show()
        write("progress 10%\r\u001b[2Kdone\r\n\u001b[")
        write("31mRED\u001b[0m\r\nabcdef\u001b[3DXYZ")
        awaitText("abcXYZ")
        assertTrue(text().contains("done"))
        assertTrue(text().contains("RED"))
        assertFalse(text().contains("progress"))
        assertFalse(text().contains("\u001b"))
    }

    @Test fun alternateScreenRestoresShellAndUnicodeSurvives() {
        show()
        write("shell caf\u00e9 \u754c e\u0301 \uD83D\uDE00\r\n")
        awaitText("shell caf\u00e9")
        write("\u001b[?1049h\u001b[2J\u001b[Heditor")
        awaitText("editor")
        assertFalse(text().contains("shell caf\u00e9"))
        write("\u001b[?1049l")
        awaitText("shell caf\u00e9")
        assertTrue(text().contains("\u754c"))
        assertTrue(text().contains("\uD83D\uDE00"))
    }

    @Test fun replayDoesNotReplyToHistoricalQueriesButLiveQueriesDo() {
        show("\u001b[6n")
        assertTrue(sent.isEmpty())
        write("\u001b[6n")
        assertTrue(sent.joinToString("").endsWith("R"))
    }

    @Test fun clearKeepsTheNativeSessionAndErasesScrollback() {
        show()
        write("\u001b[?25l" + (1..100).joinToString("\r\n") { "old-line-$it" })
        awaitText("old-line-100")
        val native = compose.runOnIdle { nativeView() }
        val before = compose.onNodeWithTag("terminal-emulator").captureToImage().asAndroidBitmap()
        runBlocking { display.clear() }
        compose.waitForIdle()
        assertFalse(before.sameAs(compose.onNodeWithTag("terminal-emulator").captureToImage().asAndroidBitmap()))
        compose.runOnIdle {
            assertTrue(native === nativeView())
            native.selectAll()
            assertFalse(native.selectionText().orEmpty().contains("old-line-"))
        }
        write("AFTER_CLEAR")
        awaitText("AFTER_CLEAR")
    }

    @Test fun nativeSelectionIncludesScrollbackAndIsBounded() {
        show()
        write((1..10500).joinToString("\r\n") { "line-$it" })
        awaitText("line-10500")
        compose.runOnIdle {
            nativeView().selectAll()
            val selected = nativeView().selectionText().orEmpty()
            assertTrue(selected.contains("line-10500"))
            assertTrue(selected.contains("line-10499"))
            assertFalse(selected.lineSequence().any { it.trim() == "line-1" })
        }
    }

    @Test fun recreatingTheAndroidViewPreservesLiveNativeState() {
        show()
        write("shell\u001b[?1049h\u001b[2J\u001b[Heditor")
        awaitText("editor")
        val old = compose.runOnIdle { nativeView() }
        compose.runOnIdle { viewGeneration.value++ }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(old === nativeView()) }
        awaitText("editor")
        write("\u001b[?1049l")
        awaitText("shell")
    }

    @Test fun androidImeCommitsDeletesAndSendsEnterAndControl() {
        show()
        compose.onNodeWithText("Keyboard", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            val view = find(compose.activity.window.decorView) { it is EditText }
            assertNotNull(view)
            val connection = view!!.onCreateInputConnection(EditorInfo())!!
            connection.commitText("caf\u00e9", 1)
            connection.deleteSurroundingText(1, 0)
            connection.deleteSurroundingTextInCodePoints(1, 0)
            connection.performEditorAction(EditorInfo.IME_ACTION_SEND)
            connection.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON))
        }
        compose.waitForIdle()
        assertEquals("caf\u00e9\u007f\u007f\r\u0003", sent.joinToString(""))
    }

    @Test fun toolbarModifiersAndPasteMatchUpstreamMobileEncoding() {
        show()
        compose.runOnIdle {
            display.ctrl = true
            display.input("c")
            display.alt = true
            display.input("x")
            display.paste("echo a\n\u001b[201~b")
        }
        compose.onNodeWithText("Tab", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals("\u0003\u001bxecho a\r [201~b\t", sent.joinToString(""))
        assertFalse(display.ctrl)
        assertFalse(display.alt)
    }

    @Test fun disconnectedSurfaceIgnoresImeInput() {
        show()
        val connection = compose.runOnIdle {
            find(compose.activity.window.decorView) { it is EditText }!!.onCreateInputConnection(EditorInfo())!!
        }
        compose.runOnIdle { canInput.value = false }
        compose.waitForIdle()
        compose.runOnIdle {
            connection.commitText("ignored", 1)
        }
        assertTrue(sent.isEmpty())
    }

    @Test fun closingDuringParsingIsSafeAndLateWorkIsHarmless() {
        show()
        runBlocking {
            val parsing = launch(Dispatchers.Default) { display.session.feed("output\r\n".repeat(20000)) }
            launch(Dispatchers.Default) { display.detach() }
            parsing.join()
        }
        display.detach()
        write("ignored\u001b[6n")
        assertTrue(text().isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test fun menuAndClearKeepKeyboardDimensionsStable() {
        show()
        compose.onNodeWithText("Keyboard", useUnmergedTree = true).performClick()
        compose.waitUntil(15000) { sizes.size >= 2 }
        Thread.sleep(1500)
        compose.waitForIdle()
        sizes.clear()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Clear screen").performClick()
        Thread.sleep(1500)
        compose.waitForIdle()
        assertTrue("Menu unexpectedly resized the terminal: $sizes", sizes.isEmpty())
    }
}
