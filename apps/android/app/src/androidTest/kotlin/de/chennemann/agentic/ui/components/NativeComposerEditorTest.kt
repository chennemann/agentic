package de.chennemann.agentic.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.chennemann.agentic.MainActivity
import de.chennemann.agentic.domain.composer.composerFileLink
import de.chennemann.agentic.ui.chat.ChatUiEvent
import de.chennemann.agentic.ui.chat.ComposerUiState
import de.chennemann.agentic.ui.theme.MobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NativeComposerEditorTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun editorRespectsTheApplicationInterfaceScale() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                val density = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit.Density(density.density * 1.25f, density.fontScale)
                ) {
                    MobileTheme {
                        NativeComposerEditor(ComposerUiState(draft = "Scaled text"), {
                        }, {}, null, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val metrics = compose.activity.resources.displayMetrics
            val expected =
                android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 16f, metrics) * 1.25f
            assertEquals(expected, editor().textSize, 1f)
        }
    }

    @Test
    fun imageClipboardPasteAddsAnAttachmentWithoutInsertingItsUriIntoThePrompt() {
        val state = mutableStateOf(ComposerUiState(draft = "Describe this"))
        val events = mutableListOf<ChatUiEvent>()
        mount(state, events)
        val resolver = compose.activity.contentResolver
        val uri = resolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "composer-paste-test.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            }
        )!!
        try {
            compose.runOnIdle {
                compose.activity.getSystemService(
                    ClipboardManager::class.java
                ).setPrimaryClip(ClipData.newUri(resolver, "Image", uri))
                editor().onTextContextMenuItem(android.R.id.paste)
            }
            compose.waitForIdle()
            assertEquals(listOf(uri.toString()), events.filterIsInstance<ChatUiEvent.ImagesSelected>().single().uris)
            assertEquals("Describe this", state.value.draft)
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun composerRendersFileSkillAndPastedTextChipsWithTheApplicationControls() {
        val file = composerFileLink("src/Main.kt")
        val paste = de.chennemann.agentic.domain.composer.composerContextReference("file", "paste", "pasted-text.txt")
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MobileTheme {
                    T3MessageComposer(
                        state = ComposerUiState(
                            draft = "Review $file with ${'$'}android and $paste \nThen @con",
                            contextItems = listOf(
                                de.chennemann.agentic.ui.chat.ComposerContextItemUi(
                                    "paste",
                                    "file",
                                    "pasted-text.txt",
                                    "36 KiB"
                                ),
                                de.chennemann.agentic.ui.chat.ComposerContextItemUi(
                                    "skill:android",
                                    "skill",
                                    "Android",
                                    ""
                                )
                            ),
                            suggestions = listOf(
                                de.chennemann.agentic.ui.chat.ComposerSuggestionUi(
                                    "path:config.kt",
                                    "path",
                                    "config.kt",
                                    "src/config.kt",
                                    "src/config.kt"
                                )
                            )
                        ),
                        turnRunning = false,
                        onEvent = {},
                        projectLabel = "Agentic",
                        threadTitle = "Composer context"
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val editor = editor()
            assertEquals(
                3,
                editor.text.getSpans(0, editor.length(), android.text.style.ReplacementSpan::class.java).size
            )
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val target = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "composer-chips.png")
            target.outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(
                    android.graphics.Bitmap.CompressFormat.PNG,
                    100,
                    it
                )
            }
        }
    }

    @Test
    fun unchangedParentDoesNotOverwriteTypingAndExplicitSendResetClearsTheEditor() {
        val state = mutableStateOf(ComposerUiState(draft = "initial"))
        mount(state, mirrorEdits = false)
        compose.runOnIdle {
            editor().text.append(" typed")
            editor().requestFocus()
            state.value = state.value.copy(sending = true)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("initial typed", editor().text.toString()) }
        compose.runOnIdle {
            state.value =
                state.value.copy(
                    draft = "",
                    selectionRequest = de.chennemann.agentic.ui.chat.ComposerSelectionUi(0, 0, 1),
                    sending = false
                )
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("", editor().text.toString()) }
    }

    @Test
    fun chipDeletesAsOneUnitThroughTheSoftwareKeyboardAndUndoRestoresIt() {
        val source = composerFileLink("src/Main.kt")
        val state = mutableStateOf(ComposerUiState(draft = "$source next"))
        mount(state)
        compose.runOnIdle {
            val editor = editor()
            assertEquals(
                1,
                editor.text.getSpans(0, editor.length(), android.text.style.ReplacementSpan::class.java).size
            )
            editor.setSelection(source.length)
            editor.onCreateInputConnection(EditorInfo())!!.deleteSurroundingText(1, 0)
        }
        compose.waitForIdle()
        assertEquals(" next", state.value.draft)
        compose.runOnIdle { editor().onTextContextMenuItem(android.R.id.undo) }
        compose.waitForIdle()
        assertEquals("$source next", state.value.draft)
    }

    @Test
    fun largeUnicodePasteCapturesTheExactSelectionWithoutInsertingThousandsOfCharacters() {
        val state = mutableStateOf(ComposerUiState(draft = "before replace after"))
        val events = mutableListOf<ChatUiEvent>()
        mount(state, events)
        compose.runOnIdle {
            val editor = editor()
            val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Paste", "日".repeat(12000)))
            editor.setSelection(7, 14)
            editor.onTextContextMenuItem(android.R.id.paste)
        }
        compose.waitForIdle()
        val paste = events.filterIsInstance<ChatUiEvent.ComposerTextPasted>().single()
        assertEquals(7, paste.start)
        assertEquals(14, paste.end)
        assertEquals("日".repeat(12000), paste.text)
        assertEquals("before replace after", state.value.draft)
    }

    @Test
    fun normalPasteAndReadOnlyKeepNativeEditingAndSelection() {
        val state = mutableStateOf(ComposerUiState(draft = "hello world"))
        mount(state)
        compose.runOnIdle {
            val editor = editor()
            editor.requestFocus()
            editor.setSelection(6, 11)
            compose.activity.getSystemService(
                ClipboardManager::class.java
            ).setPrimaryClip(ClipData.newPlainText("Paste", "Android"))
            editor.onTextContextMenuItem(android.R.id.paste)
        }
        compose.waitForIdle()
        assertEquals("hello Android", state.value.draft)
        compose.runOnIdle { state.value = state.value.copy(sending = true) }
        compose.waitForIdle()
        compose.runOnIdle {
            val editor = editor()
            assertTrue(editor.hasFocus())
            val start = editor.selectionStart
            editor.text.insert(start, "blocked")
            assertEquals("hello Android", editor.text.toString())
            assertEquals(start, editor.selectionStart)
        }
    }

    @Test
    fun blurRequestOnAnUnfocusedEditorDoesNotMoveFocusIntoIt() {
        mount(mutableStateOf(ComposerUiState(draft = "idle")), focusRequested = false)
        compose.runOnIdle {
            val focused = compose.activity.window.decorView.findFocus()
            assertTrue("Unexpected focus on $focused", generateSequence(focused) { it.parent as? View }.none { it is EditText || it.javaClass.simpleName == "T3ComposerEditorView" })
        }
    }

    @Test
    fun switchingDraftsResetsNativeUndoHistory() {
        val state = mutableStateOf(ComposerUiState(draft = "private previous draft", draftIdentity = "first"))
        mount(state)
        compose.runOnIdle {
            editor().requestFocus()
            editor().text.append(" edited")
        }
        compose.runOnIdle { state.value = ComposerUiState(draft = "private previous draft edited", draftIdentity = "second") }
        compose.runOnIdle {
            assertEquals("private previous draft edited", editor().text.toString())
            editor().onTextContextMenuItem(android.R.id.undo)
            assertEquals("private previous draft edited", editor().text.toString())
        }
    }

    private fun mount(
        state: androidx.compose.runtime.MutableState<ComposerUiState>,
        events: MutableList<ChatUiEvent> = mutableListOf(),
        mirrorEdits: Boolean = true,
        focusRequested: Boolean? = null
    ) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MobileTheme {
                    NativeComposerEditor(state.value, { event ->
                        events += event
                        if (mirrorEdits) {
                            when (event) {
                                is ChatUiEvent.ComposerEdited -> state.value = state.value.copy(draft = event.value)
                                is ChatUiEvent.DraftChanged -> state.value = state.value.copy(draft = event.value)
                                else -> Unit
                            }
                        }
                    }, {}, focusRequested, Modifier.fillMaxWidth())
                }
            }
        }
        compose.waitForIdle()
    }

    private fun editor(): EditText =
        descendants(compose.activity.window.decorView).filterIsInstance<EditText>().single()

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else {
        emptyList()
    }
}
