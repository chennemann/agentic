package de.chennemann.agentic.ui.components

import android.util.TypedValue
import android.view.KeyEvent
import android.widget.EditText
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import de.chennemann.agentic.domain.composer.COMPOSER_INPUT_MAX_CHARS
import de.chennemann.agentic.domain.composer.ComposerToken
import de.chennemann.agentic.domain.composer.PASTED_TEXT_ATTACHMENT_THRESHOLD_BYTES
import de.chennemann.agentic.domain.composer.composerTokens
import de.chennemann.agentic.ui.chat.ChatPickerUi
import de.chennemann.agentic.ui.chat.ChatUiEvent
import de.chennemann.agentic.ui.chat.ComposerUiState
import de.chennemann.agentic.ui.chat.HardwareKey
import de.chennemann.agentic.ui.chat.HardwareKeyStroke
import de.chennemann.agentic.ui.chat.HardwareKeyboardBindings
import de.chennemann.agentic.ui.chat.KeyboardAction
import de.chennemann.agentic.ui.chat.KeyboardFocus
import de.chennemann.agentic.ui.chat.VoiceInputStatusUi
import expo.modules.t3composereditor.T3ComposerEditorView
import org.json.JSONArray
import org.json.JSONObject

/** The Android editor owns live IME edits; controlled echoes must never replace newer typing. */
private class ComposerBridge {
    var view: T3ComposerEditorView? = null
    var eventCount = 0
    var value = ""
    var lastControlled: String? = null
    var lastSelectionRequest: Long? = null
    var confirmedTokens: List<ComposerToken> = emptyList()
    var lastRenderedValue: String? = null
    var appliedTheme: String? = null
    var appliedFontSize = -1f
    var appliedLineHeight = -1f

    fun accept(payload: Map<String, Any>): Pair<Int, Int> {
        eventCount = (payload["eventCount"] as? Number)?.toInt() ?: eventCount
        value = payload["value"] as? String ?: value
        val selection = payload["selection"] as? Map<*, *>
        return ((selection?.get("start") as? Number)?.toInt() ?: value.length) to
            ((selection?.get("end") as? Number)?.toInt() ?: value.length)
    }
}

@Composable
fun NativeComposerEditor(
    state: ComposerUiState,
    onEvent: (ChatUiEvent) -> Unit,
    onFocus: () -> Unit,
    focusRequested: Boolean?,
    modifier: Modifier = Modifier,
    turnRunning: Boolean = false
) {
    val bridge = remember(state.draftIdentity) { ComposerBridge() }
    val events by rememberUpdatedState(onEvent)
    val focusedCallback by rememberUpdatedState(onFocus)
    var height by remember(state.draftIdentity) { mutableStateOf(48f) }
    var focused by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val border = MaterialTheme.colorScheme.outline.toArgb()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val metrics = remember(configuration) { context.resources.displayMetrics }
    val typography = MaterialTheme.typography.bodyLarge
    val fontSize = TypedValue.deriveDimension(
        TypedValue.COMPLEX_UNIT_SP,
        with(density) {
            typography.fontSize.toPx()
        },
        metrics
    )
    val lineHeight = with(density) { typography.lineHeight.toPx() } / metrics.density
    val contentHeightScale by rememberUpdatedState(metrics.density / density.density)
    val enabled by rememberUpdatedState(state.enabled)
    val running by rememberUpdatedState(turnRunning)
    val canSend by rememberUpdatedState(
        !state.sending && (state.draft.isNotBlank() || state.imageAttachments.isNotEmpty())
    )
    val placeholder = when (state.voiceInputStatus) {
        VoiceInputStatusUi.RECORDING -> "Recording…"
        VoiceInputStatusUi.TRANSCRIBING -> "Transcribing…"
        else -> "Message"
    }
    val theme = remember(primary, border) {
        JSONObject().put("text", "#ffffff").put("placeholder", "#b8b8b8")
            .put(
                "selection",
                colorHex(primary)
            ).put("chipText", "#ffffff").put("chipBorder", colorHex(border)).toString()
    }
    LaunchedEffect(focusRequested, bridge.view, state.enabled) {
        if (focusRequested == true && state.enabled) bridge.view?.focusEditor()
        if (focusRequested == false) {
            bridge.view?.let { view ->
                // Give the Android container focus so clearing EditText cannot immediately refocus it.
                view.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                view.isFocusableInTouchMode = true
                view.requestFocus()
                view.blurEditor()
                view.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
        }
    }
    AndroidView(
        modifier = modifier.height(height.dp).padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics {
                contentDescription = "Message"
                text = AnnotatedString(if (state.draft.isEmpty()) placeholder else state.draft)
                editableText = AnnotatedString(state.draft)
                this.focused = focused
                onClick {
                    bridge.view?.focusEditor()
                    true
                }
                setText {
                    events(ChatUiEvent.DraftChanged(it.text))
                    true
                }
            },
        factory = { context ->
            T3ComposerEditorView(context).also { view ->
                bridge.view = view
                view.setTextPasteThresholdBytes(PASTED_TEXT_ATTACHMENT_THRESHOLD_BYTES)
                view.setMaxInputChars(COMPOSER_INPUT_MAX_CHARS)
                view.onComposerChange = { payload ->
                    val (start, end) = bridge.accept(payload)
                    events(ChatUiEvent.ComposerEdited(bridge.value, start, end))
                }
                view.onComposerSelectionChange = { payload ->
                    val old = bridge.value
                    val (start, end) = bridge.accept(payload)
                    if (old != bridge.value) {
                        events(ChatUiEvent.ComposerEdited(bridge.value, start, end))
                    } else {
                        events(ChatUiEvent.ComposerSelectionChanged(bridge.value, start, end))
                    }
                }
                view.onComposerFocus = {
                    focused = true
                    focusedCallback()
                }
                view.onComposerBlur = { focused = false }
                view.onComposerContentSizeChange = { payload ->
                    height =
                        (
                            (payload["height"] as? Number)?.toFloat()?.times(
                                contentHeightScale
                            )?.plus(16) ?: 48f
                            ).coerceIn(48f, 280f)
                }
                view.onComposerPasteImages = { payload ->
                    @Suppress("UNCHECKED_CAST")
                    val uris = payload["uris"] as? List<String> ?: emptyList()
                    events(ChatUiEvent.ImagesSelected(uris))
                }
                view.onComposerPasteText = { payload ->
                    val (start, end) = bridge.accept(payload)
                    events(ChatUiEvent.ComposerTextPasted(bridge.value, start, end, payload["text"] as? String ?: ""))
                }
                view.onComposerPasteContext = { payload ->
                    val (start, end) = bridge.accept(payload)
                    events(
                        ChatUiEvent.ComposerContextPasted(
                            bridge.value,
                            start,
                            end,
                            payload["text"] as? String ?: "",
                            payload["html"] as? String ?: ""
                        )
                    )
                }
                view.onComposerContextPress = { payload ->
                    (payload["source"] as? String)?.let { events(ChatUiEvent.ComposerContextRequested(it)) }
                }
                (view.getChildAt(0) as EditText).setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) {
                        false
                    } else {
                        val key = when (keyCode) {
                            KeyEvent.KEYCODE_ENTER -> HardwareKey.ENTER
                            KeyEvent.KEYCODE_ESCAPE -> HardwareKey.ESCAPE
                            KeyEvent.KEYCODE_N -> HardwareKey.N
                            KeyEvent.KEYCODE_K -> HardwareKey.K
                            else -> HardwareKey.UNKNOWN
                        }
                        val focus = if (keyCode == KeyEvent.KEYCODE_N ||
                            keyCode == KeyEvent.KEYCODE_K
                        ) {
                            KeyboardFocus.GLOBAL
                        } else {
                            KeyboardFocus.COMPOSER
                        }
                        val stroke = HardwareKeyStroke(
                            key,
                            focus,
                            event.isCtrlPressed,
                            event.isMetaPressed,
                            event.isShiftPressed,
                            event.isAltPressed
                        )
                        val action = HardwareKeyboardBindings.map(
                            stroke,
                            enabled && canSend,
                            running
                        )
                        val intent = when (action) {
                            KeyboardAction.SEND -> ChatUiEvent.MessageSubmitted
                            KeyboardAction.STOP_TURN -> ChatUiEvent.TurnInterruptRequested
                            KeyboardAction.NEW_TASK -> ChatUiEvent.NewThreadRequested
                            KeyboardAction.OPEN_NAVIGATION -> ChatUiEvent.PickerRequested(
                                ChatPickerUi.NAVIGATION
                            )
                            else -> null
                        }
                        if (intent != null) {
                            events(intent)
                            true
                        } else {
                            false
                        }
                    }
                }
            }
        },
        update = { view ->
            if (bridge.appliedTheme != theme) {
                view.setThemeJson(theme)
                bridge.appliedTheme = theme
            }
            if (bridge.appliedFontSize != fontSize) {
                view.setFontSize(fontSize)
                bridge.appliedFontSize = fontSize
            }
            if (bridge.appliedLineHeight != lineHeight) {
                view.setLineHeight(lineHeight)
                bridge.appliedLineHeight = lineHeight
            }
            view.setPlaceholder(placeholder)
            view.setClipboardFragment(state.clipboardFragment)
            view.setEditable(state.enabled)
            view.setReadOnly(
                state.sending || state.voiceInputStatus != VoiceInputStatusUi.IDLE
            )
            // Compose can recompose for focus before the synchronous Kotlin draft echo arrives.
            // An unchanged parent value must not replace typing that already lives in EditText.
            // A changed parent value is either that echo or an intentional edit (send/reset/insert).
            val newSelection = state.selectionRequest?.requestId?.let { it != bridge.lastSelectionRequest } == true
            val awaitingEcho = !newSelection && state.draft == bridge.lastRenderedValue && state.draft != bridge.value
            val value = if (awaitingEcho) bridge.value else state.draft
            bridge.lastRenderedValue = state.draft
            val tokens = JSONArray()
            val confirmed = composerTokens(value, bridge.confirmedTokens)
            bridge.confirmedTokens = confirmed
            confirmed.forEach { token ->
                val item = state.contextItems.firstOrNull {
                    it.id == (token.contextId ?: "${token.kind}:${token.value}")
                }
                val unavailable = token.contextId != null && (item == null || !item.available)
                tokens.put(
                    JSONObject().put("type", if (token.contextId != null) "context" else token.kind)
                        .put("source", token.source).put("start", token.start).put("end", token.end)
                        .put("label", (item?.label ?: token.label) + if (unavailable) " · unavailable" else "")
                        .put("detail", item?.detail.orEmpty()).put("accent", colorHex(primary))
                        .put(
                            "symbol",
                            when (token.kind) {
                                "skill" -> "cube"
                                "terminal" -> "terminal"
                                "image" -> "photo"
                                else -> "doc"
                            }
                        )
                )
            }
            val request = state.selectionRequest?.takeIf {
                it.requestId != bridge.lastSelectionRequest && !awaitingEcho
            }
            val document = JSONObject().put("value", value).put("tokensJson", tokens.toString())
                .put("mostRecentEventCount", bridge.eventCount).put("isNativeEcho", value == bridge.value)
            if (request !=
                null
            ) {
                document.put("selection", JSONObject().put("start", request.start).put("end", request.end))
            } else if (bridge.lastControlled ==
                null
            ) {
                document.put("selection", JSONObject().put("start", value.length).put("end", value.length))
            }
            val json = document.toString()
            if (json != bridge.lastControlled) {
                view.setControlledDocumentJson(json)
                bridge.lastControlled = json
                bridge.value = value
                if (request != null) bridge.lastSelectionRequest = request.requestId
            }
        },
        onRelease = { view ->
            view.blurEditor()
            view.onComposerChange = {}
            view.onComposerSelectionChange = {}
            view.onComposerFocus = {}
            view.onComposerBlur =
                {}
            view.onComposerPasteText = {}
            view.onComposerPasteImages = {}
            view.onComposerPasteContext = {}
            view.onComposerContextPress =
                {}
            view.onComposerContentSizeChange = {}
            bridge.view = null
        }
    )
}

private fun colorHex(color: Int): String = "#%06x".format(color and 0xffffff)
