package to.eyed.thragg.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * What the editor canvas tells a screen reader.
 *
 * The editor is a `Canvas`: it draws glyphs and has no children, so to
 * TalkBack it is a blank rectangle that swallows every gesture. That is the
 * single worst accessibility fact about this app, and the fix is not "make the
 * editor a Compose text field" — the whole point of the canvas is that a
 * 200 000-line buffer never becomes 200 000 nodes.
 *
 * So the canvas carries a description that is rebuilt as the caret moves: what
 * this is, where the caret is, and the line it is on. That is the same three
 * facts a sighted user reads off the status bar and the gutter, and it is what
 * makes arrow-key navigation followable — every move re-announces the line you
 * landed on, because the description changed.
 *
 * Kept as a pure function so the sentence can be checked on the host, where
 * there is no TalkBack to ask.
 */
internal fun editorAnnouncement(
    fileName: String,
    /** Zero-based, as the editor holds it; announced one-based, as people count. */
    row: Int,
    col: Int,
    /** The text of that line, as [EditorState.line] returns it. */
    lineText: String,
    /** How many characters are selected on one line, or 0. */
    selectionChars: Int = 0,
    /** How many lines a multi-line selection spans, or 0 — counted, not read. */
    selectionLines: Int = 0,
    /** How many carets there are; more than one is worth saying. */
    caretCount: Int = 1,
): String = buildString {
    append("Editor")
    // A multibuffer and a scratch buffer have no one file to name, and
    // "Editor, . Line 1" reads as a bug rather than as a blank.
    if (fileName.isNotBlank()) {
        append(", ")
        append(fileName)
    }
    append(". Line ")
    append(row + 1)
    append(", column ")
    append(col + 1)
    append(". ")
    // A blank line said as nothing at all sounds like the reader stopped.
    val trimmed = lineText.trimEnd('\n', '\r')
    append(if (trimmed.isBlank()) "Blank line." else trimmed)
    when {
        selectionLines > 0 -> append(" $selectionLines lines selected.")
        selectionChars == 1 -> append(" 1 character selected.")
        selectionChars > 1 -> append(" $selectionChars characters selected.")
    }
    if (caretCount > 1) {
        append(" ")
        append("$caretCount cursors.")
    }
}

/**
 * The same for a diagnostic under the caret, which is announced separately —
 * see the live region in [EditorPane].
 */
internal fun diagnosticAnnouncement(diagnostic: Diagnostic): String {
    val severity = diagnostic.severity.name.lowercase().replaceFirstChar { it.uppercase() }
    return "$severity: ${diagnostic.firstLine}"
}

/**
 * [editorAnnouncement] as a modifier, read from the state on every frame the
 * caret moves.
 *
 * The reads happen inside the `semantics` lambda, which Compose re-runs when
 * the snapshot state it touched changes — so this costs nothing per frame and
 * updates exactly when the announcement would differ.
 */
@Composable
internal fun Modifier.editorSemantics(state: EditorState, fileName: String): Modifier =
    this.semantics {
        contentDescription = editorAnnouncement(
            fileName = fileName,
            row = state.cursorRow,
            col = state.cursorCol,
            lineText = state.line(state.cursorRow),
            // Counting characters across lines would mean reading the whole
            // buffer; for a multi-line selection the number of *lines* is the
            // useful fact anyway, and it is the one Zed's own status bar says.
            selectionChars = state.selectionRange()
                ?.takeIf { !it.isMultiLine }
                ?.let { it.endCol - it.startCol } ?: 0,
            selectionLines = state.selectionRange()
                ?.takeIf { it.isMultiLine }
                ?.let { it.endRow - it.startRow + 1 } ?: 0,
            caretCount = state.extraCarets.size + 1,
        )
    }

/**
 * The diagnostic under the caret, announced when it changes.
 *
 * A live region rather than part of the canvas's own description: a
 * screen-reader user moving the caret hears the line they landed on from
 * [editorSemantics], and hearing "error: expected `;`" appended to every one
 * of those would bury it. As its own polite region it is announced once, when
 * it appears — which is the same moment the status bar's message changes for a
 * sighted user.
 *
 * Zero-sized and drawn nowhere: the region exists to be *spoken*, and the
 * visible half of this is the status bar's own diagnostic message.
 */
@Composable
internal fun EditorDiagnosticLiveRegion(state: EditorState) {
    val diagnostic = state.diagnosticAtCursor() ?: return
    Box(
        modifier = Modifier
            .size(0.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = diagnosticAnnouncement(diagnostic)
            }
    )
}
