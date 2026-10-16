package expo.modules.t3terminal

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText

/** Keep remote backspace working after upstream's TextWatcher empties the local editor. */
internal class TerminalInputView(context: Context, private val onDelete: (String) -> Unit) : EditText(context) {
    var onPaste: () -> Unit = {}

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
            if (isEnabled) onPaste()
            return true
        }
        return super.onTextContextMenuItem(id)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val target = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(target, false) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (text.isNullOrEmpty() && beforeLength > 0 && afterLength == 0) {
                    if (isEnabled) onDelete("\u007f".repeat(beforeLength.coerceAtMost(4096)))
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
                deleteSurroundingText(beforeLength, afterLength)
        }
    }
}
