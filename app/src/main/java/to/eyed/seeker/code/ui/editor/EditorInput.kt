package to.eyed.seeker.code.ui.editor

import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Soft-keyboard input for the editor (phase-2 IME spike).
 *
 * Strategy: the IME never sees the buffer — it sees a per-line *shadow*, a
 * [SpannableStringBuilder] holding only the cursor's line. All IME
 * operations (commitText, composing text/regions, deleteSurroundingText —
 * including GBoard swipe typing and autocorrect) are executed against the
 * shadow by Android's own battle-tested [BaseInputConnection]. After every
 * operation the shadow is diffed against the engine's line and the delta is
 * sent through the JNI bridge as a minimal byte-range edit.
 *
 * Line-structure changes (Enter, backspace at column 0, cursor taps,
 * hardware-key edits, undo) re-seed the shadow from the new cursor line and
 * restart the IME session, which clears any composing state — the same
 * thing classic editors do when the caret leaves the composing region.
 */
internal fun Modifier.editorTextInput(state: EditorState): Modifier =
    this then EditorTextInputElement(state)

private data class EditorTextInputElement(
    val state: EditorState,
) : ModifierNodeElement<EditorTextInputNode>() {
    override fun create() = EditorTextInputNode(state)

    override fun update(node: EditorTextInputNode) {
        node.state = state
    }
}

internal class EditorTextInputNode(
    var state: EditorState,
) : Modifier.Node(), PlatformTextInputModifierNode, FocusEventModifierNode {

    private var sessionJob: Job? = null
    private var sessionView: View? = null
    private var activeConnection: EditorInputConnection? = null

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused) startSession() else stopSession()
    }

    override fun onDetach() {
        stopSession()
    }

    private fun startSession() {
        if (sessionJob != null) return
        sessionJob = coroutineScope.launch {
            establishTextInputSession {
                sessionView = view
                state.onCursorChangedExternally = { restartInput() }
                try {
                    startInputMethod { outAttributes ->
                        // In a vim mode that is not insert, every character
                        // typed is a command: no autocorrect, no suggestion
                        // strip composing words out of `d` and `w`. The
                        // password variation is the documented way to make
                        // a soft keyboard commit one character at a time.
                        val raw = state.vim?.wantsRawInput == true
                        outAttributes.inputType = if (raw) {
                            EditorInfo.TYPE_CLASS_TEXT or
                                EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                                EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        } else {
                            EditorInfo.TYPE_CLASS_TEXT or
                                EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or
                                EditorInfo.TYPE_TEXT_FLAG_AUTO_CORRECT
                        }
                        outAttributes.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                            EditorInfo.IME_FLAG_NO_ENTER_ACTION
                        val lineLength = state.currentLine().length
                        val range = state.selectionRange()
                        if (range != null && !range.isMultiLine) {
                            outAttributes.initialSelStart =
                                range.startCol.coerceAtMost(lineLength)
                            outAttributes.initialSelEnd =
                                range.endCol.coerceAtMost(lineLength)
                        } else {
                            val col = state.cursorCol.coerceAtMost(lineLength)
                            outAttributes.initialSelStart = col
                            outAttributes.initialSelEnd = col
                        }
                        // The platform may keep issuing calls on the old
                        // connection after a restart; close it so a stale
                        // shadow can never clobber the buffer.
                        activeConnection?.close()
                        EditorInputConnection(view, state).also { activeConnection = it }
                    }
                } finally {
                    state.onCursorChangedExternally = null
                    activeConnection?.close()
                    activeConnection = null
                    sessionView = null
                }
            }
        }
    }

    private fun stopSession() {
        sessionJob?.cancel()
        sessionJob = null
    }

    /**
     * The cursor moved or the buffer changed outside the IME write path:
     * make the platform create a fresh input connection seeded from the new
     * cursor line.
     */
    private fun restartInput() {
        val view = sessionView ?: return
        activeConnection?.close()
        val imm = view.context.getSystemService(InputMethodManager::class.java)
        imm?.restartInput(view)
    }
}

/**
 * [BaseInputConnection] in editable mode over a shadow of the cursor's
 * line. Base handles the composing spans and text mutation; we sync the
 * result into the engine after each (batch of) operation(s).
 */
private class EditorInputConnection(
    private val view: View,
    private val state: EditorState,
) : BaseInputConnection(view, true) {

    private var row = state.cursorRow
    private val shadow = SpannableStringBuilder(state.currentLine())
    private var batchDepth = 0

    /**
     * Once closed (session restart or end), this connection's shadow is
     * stale and must never be synced into the buffer again — the platform
     * can and does issue late calls on replaced connections.
     */
    private var closed = false

    init {
        // Seed a single-line selection into the shadow so IME input
        // replaces it natively; multi-line selections are collapsed lazily
        // in prepareForEdit().
        val range = state.selectionRange()
        if (range != null && !range.isMultiLine && range.startRow == row) {
            Selection.setSelection(
                shadow,
                range.startCol.coerceAtMost(shadow.length),
                range.endCol.coerceAtMost(shadow.length),
            )
        } else {
            Selection.setSelection(shadow, state.cursorCol.coerceAtMost(shadow.length))
        }
    }

    fun close() {
        closed = true
    }

    /**
     * A multi-line selection can't live in the one-line shadow: on the
     * first mutating IME op, delete it in the engine and re-seed the shadow
     * from the collapsed cursor line, so the op applies there — no
     * keystroke is lost.
     */
    private fun prepareForEdit() {
        if (state.selectionRange()?.isMultiLine != true) return
        state.deleteSelection()
        row = state.cursorRow
        shadow.replace(0, shadow.length, state.currentLine())
        Selection.setSelection(shadow, state.cursorCol.coerceAtMost(shadow.length))
    }

    override fun getEditable(): Editable = shadow

    override fun beginBatchEdit(): Boolean {
        batchDepth++
        return true
    }

    override fun endBatchEdit(): Boolean {
        batchDepth = (batchDepth - 1).coerceAtLeast(0)
        if (batchDepth == 0) sync()
        return batchDepth > 0
    }

    /**
     * What a soft keyboard has composed so far while the vim layer is in
     * command, so a keyboard that composes rather than commits still hands
     * the layer one character at a time.
     */
    private var rawComposing = ""

    /**
     * In every vim mode but insert the typed text is keystrokes for the
     * layer, not text for the buffer — the shadow never sees it. True when
     * the layer took it.
     */
    private fun offerToVim(text: CharSequence?): Boolean {
        val vim = state.vim ?: return false
        if (!vim.wantsRawInput) return false
        if (text != null && text.isNotEmpty()) vim.handleTyped(text.toString())
        return true
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (closed) return false
        if (state.vim?.wantsRawInput == true) {
            val fresh = text?.toString().orEmpty().let {
                if (rawComposing.isNotEmpty() && it.startsWith(rawComposing)) it.substring(rawComposing.length) else it
            }
            rawComposing = ""
            offerToVim(fresh)
            return true
        }
        // A soft keyboard's Enter arrives here, not as a key event, so a
        // popup that confirms on Enter has to be offered it here or it never
        // sees one — the completion menu was inserting line breaks on a
        // phone while working perfectly under a hardware keyboard.
        if (text != null && text.length == 1 && text[0] == '\n' &&
            state.onImeNewline?.invoke() == true
        ) {
            return true
        }
        prepareForEdit()
        return super.commitText(text, newCursorPosition).also { maybeSync() }
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (closed) return false
        if (state.vim?.wantsRawInput == true) {
            val whole = text?.toString().orEmpty()
            val fresh = if (whole.startsWith(rawComposing)) whole.substring(rawComposing.length) else whole
            rawComposing = whole
            offerToVim(fresh)
            return true
        }
        prepareForEdit()
        return super.setComposingText(text, newCursorPosition).also { maybeSync() }
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        if (closed) return false
        return super.setComposingRegion(start, end).also { maybeSync() }
    }

    override fun finishComposingText(): Boolean {
        if (closed) return false
        if (state.vim?.wantsRawInput == true) {
            rawComposing = ""
            return true
        }
        return super.finishComposingText().also { maybeSync() }
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        if (closed) return false
        return super.setSelection(start, end).also { maybeSync() }
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (closed) return false
        state.vim?.let { vim ->
            if (vim.wantsRawInput) {
                // The soft keyboard's Backspace, which in normal mode is the
                // `h`-like motion Zed binds it to (vim.json:10) and on the
                // command line erases a character.
                repeat(beforeLength.coerceAtLeast(1)) { vim.handleKey("backspace") }
                return true
            }
        }
        prepareForEdit()
        val selStart = Selection.getSelectionStart(shadow)
        if (beforeLength > 0 && selStart == 0 && Selection.getSelectionEnd(shadow) == 0) {
            // Backspace at column 0 joins with the previous line, which the
            // one-line shadow can't express. Hand it to the same command the
            // hardware key uses rather than to the join primitive: the
            // primitive moves one caret, and with a column of them the rest
            // would be left naming rows that had shifted up under them.
            // (Any beforeLength beyond the newline itself is dropped; IMEs
            // ask for 1 here in practice.)
            closed = true
            // backspace() reports the cursor change, which is what restarts
            // this connection on the joined line — the same route a hardware
            // backspace at column 0 already depends on.
            state.backspace()
            return true
        }
        return super.deleteSurroundingText(beforeLength, afterLength).also { maybeSync() }
    }

    override fun getExtractedText(
        request: ExtractedTextRequest?,
        flags: Int,
    ): ExtractedText = ExtractedText().apply {
        text = shadow.toString()
        startOffset = 0
        selectionStart = Selection.getSelectionStart(shadow)
        selectionEnd = Selection.getSelectionEnd(shadow)
    }

    override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
        // Dispatch through the view so Compose's key-input pipeline (the
        // editor's onKeyEvent handler) sees soft-keyboard key events too
        // (GBoard sends backspace this way when the field looks empty).
        view.dispatchKeyEvent(event)
        return true
    }

    private fun maybeSync() {
        if (batchDepth == 0) sync()
    }

    private fun sync() {
        if (closed) return
        val selEnd = Selection.getSelectionEnd(shadow).coerceIn(0, shadow.length)
        val structural = state.applyLineDiff(row, shadow.toString(), selEnd)
        if (structural) {
            // This connection's line no longer exists as seeded; force a
            // fresh connection for the new cursor line.
            closed = true
            view.context.getSystemService(InputMethodManager::class.java)
                ?.restartInput(view)
        } else {
            reportSelection()
        }
    }

    private fun reportSelection() {
        val imm = view.context.getSystemService(InputMethodManager::class.java) ?: return
        imm.updateSelection(
            view,
            Selection.getSelectionStart(shadow),
            Selection.getSelectionEnd(shadow),
            getComposingSpanStart(shadow),
            getComposingSpanEnd(shadow),
        )
    }
}
