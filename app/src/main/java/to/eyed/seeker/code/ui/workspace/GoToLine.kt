package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.ui.editor.Caret
import to.eyed.seeker.code.ui.editor.EditorState
import to.eyed.seeker.code.ui.theme.rem

/** A line, and optionally a column, both as the user counts them: from 1. */
internal data class GoToLineTarget(val line: Int, val column: Int?)

/**
 * Read what was typed into a target, or null if it is not one yet.
 *
 * `42` and `42:8` are Zed's forms. A comma is accepted for the same thing
 * because a soft keyboard puts the colon behind a modifier key and the digit
 * row already carries the comma — the cost of allowing it is nothing, and on a
 * phone it is the difference between one keypress and three.
 *
 * A trailing separator (`42:`) is a line with no column rather than an error:
 * it is the state the field is in halfway through typing `42:8`, and blanking
 * the preview at that moment would make the caret jump back and forth.
 */
internal fun parseGoToLine(input: String): GoToLineTarget? {
    val text = input.trim()
    if (text.isEmpty()) return null
    val parts = text.split(':', ',')
    if (parts.size > 2) return null
    val line = parts[0].trim().toIntOrNull()?.takeIf { it >= 1 } ?: return null
    val columnText = parts.getOrNull(1)?.trim()
    if (columnText.isNullOrEmpty()) return GoToLineTarget(line, null)
    val column = columnText.toIntOrNull()?.takeIf { it >= 1 } ?: return null
    return GoToLineTarget(line, column)
}

/**
 * Where [target] actually lands: a row and a UTF-16 column, both 0-based and
 * both clamped to the buffer.
 *
 * Clamped rather than refused, which is what every editor with this command
 * does: `9999` in a 300-line file means the end of the file, and telling
 * somebody their number is too big helps nobody.
 */
internal fun goToLinePosition(
    target: GoToLineTarget,
    lineCount: Int,
    lengthOfRow: (Int) -> Int,
): Pair<Int, Int> {
    val row = (target.line - 1).coerceIn(0, (lineCount - 1).coerceAtLeast(0))
    val column = ((target.column ?: 1) - 1).coerceIn(0, lengthOfRow(row))
    return row to column
}

/**
 * Go to line — Zed's `go_to_line::Toggle`, on `Ctrl` `G`.
 *
 * A small panel over the editor rather than a dialog, and deliberately: the
 * caret moves *while* you type, and a dialog's scrim would dim the file you
 * are watching go past. Escape puts the caret, the selection and the viewport
 * back exactly where they were; Enter keeps the move.
 *
 * The dress is Zed's exactly (go_to_line.rs:327-347): `rems(24)` wide, an
 * elevated surface — `elevated_surface.background`, `rounded_lg` 8px, 1px
 * `border.variant` (styled_ext.rs:6-12) — a bare query editor padded `px_2`
 * `py_1` over a 1px `border.variant` underline, and a muted status line in
 * the same padding underneath.
 *
 * [modifier] is where the caller places it — `Modifier.align(Alignment.TopCenter)`
 * inside the work area, which is where Zed's own goes.
 */
@Composable
internal fun GoToLine(
    editor: EditorState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember(editor) { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }

    // What Escape restores. Captured once per editor, before anything moves:
    // the whole caret set rather than the cursor's row and column, so a
    // cancelled jump gives back the selection and the extra carets too.
    val original = remember(editor) {
        Triple(editor.caretsInOrder(), editor.primaryCaret(), editor.scrollY)
    }
    // …and the folds, because the preview opens the one over the line it is
    // showing. Nothing here is undoable, so a cancelled jump has to hand back
    // the folds it opened along with the caret it moved.
    val originalFolds = remember(editor) { editor.folds }

    LaunchedEffect(editor) { focus.requestFocus() }

    fun moveTo(target: GoToLineTarget) {
        val (row, column) = goToLinePosition(target, editor.lineCount) { editor.line(it).length }
        val caret = Caret(row, column)
        // `setCarets` is the one door: it drops the extra carets, clears the
        // selection, opens the fold over the line asked for
        // ([HiddenCaret.Reveal]) and scrolls the caret into view, which is
        // the whole of what this command means. A jump that stopped at the
        // fold's chip row would leave the caret on a line the panel never
        // named, and the next character typed would go into it.
        editor.setCarets(listOf(caret), caret)
    }

    fun restore() {
        val (carets, primary, scrollY) = original
        editor.setCarets(carets, primary)
        // The folds the preview opened close again; a pane that had none
        // finds nothing to add and stops there.
        editor.foldRanges(originalFolds)
        // …and the viewport with it. `setCarets` only scrolls far enough to
        // show the caret, which after a jump to line 4000 is not where the
        // reader was.
        editor.scrollToY(scrollY)
    }

    fun cancel() {
        restore()
        onDismiss()
    }

    // Where the caret was when the panel opened, as the user counts it. The
    // placeholder is that position in the form the field wants back, which is
    // exactly Zed's placeholder (go_to_line.rs:127-131); the status line
    // echoes it in words until the query parses (go_to_line.rs:136-141).
    val openedAt = original.second
    val target = parseGoToLine(query.text)
    val statusText = when {
        target == null -> "Current Line: ${openedAt.headRow + 1} of ${editor.lineCount} " +
            "(column ${openedAt.headCol + 1})"
        target.column != null -> "Go to line ${target.line}, character ${target.column}"
        else -> "Go to line ${target.line}"
    }

    Column(
        modifier = modifier
            .padding(8.dp)
            // `rems(24)` = 384dp at the default UI font (go_to_line.rs:328) —
            // narrowed to what a phone actually has when it has less.
            .widthIn(max = rem(24f))
            .clip(RoundedCornerShape(8.dp))
            // Zed's elevated surface and its hairline, by their Material
            // names. This panel floats over the buffer but is not IN it — it
            // is chrome the shell raises, drawn outside `ZedSurface`
            // (CodeScreen.kt:813) — so its ground is the M3 ladder's raised
            // rung and its inks are solved against that.
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> {
                        cancel()
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        // The caret is already where the preview put it; Enter
                        // only agrees with it. An unparseable field leaves the
                        // caret alone, so this closes without moving anything.
                        onDismiss()
                        true
                    }
                    else -> false
                }
            }
    ) {
        // The query line: `px_2` `py_1` around a bare editor — no box, no
        // fill of its own (go_to_line.rs:333-340). The two buttons ride in
        // the same line because Zed's Enter and Escape don't exist on a soft
        // keyboard; they wear the 22dp ghost-button dress, and the chords and
        // the IME's Go key remain the other routes to both verbs.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .pointerHoverIcon(PointerIcon.Text),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { value ->
                        query = value
                        val typed = parseGoToLine(value.text)
                        // A half-typed or emptied field puts the caret back
                        // rather than leaving it wherever the last valid
                        // number happened to land.
                        if (typed == null) restore() else moveTo(typed)
                    },
                    singleLine = true,
                    // Deliberately *not* `KeyboardType.Number`: the numeric pad
                    // is a nicer target for the digits and on most IMEs it has
                    // no colon at all, which would leave the column half of
                    // this command unreachable by touch. The ordinary keyboard
                    // has every character, and [parseGoToLine] refuses the rest.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    // The soft keyboard's own Go key never reaches the panel's
                    // key handler, so it is answered here as well.
                    keyboardActions = KeyboardActions(onGo = { onDismiss() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    // `primary`, not the theme's `cursor` key: that one is the
                    // buffer's caret, tuned against `editor.background`.
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (query.text.isEmpty()) {
                    Text(
                        text = "${openedAt.headRow + 1}:${openedAt.headCol + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Action("↵", "Go to the line", onClick = onDismiss)
            Action("✕", "Cancel", onClick = ::cancel)
        }
        // The underline between the query and the status line: 1px
        // `border.variant` (go_to_line.rs:335-336).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        // Zed's status line: what the query means right now, muted, in the
        // same `px_2` `py_1` (go_to_line.rs:342-346).
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * One of the two verbs, dressed as Zed's ghost `IconButton`: 22px tall
 * (`ButtonSize::Default`, button_like.rs:469), `rounded_sm`, 4px side padding
 * (button_like.rs:798-803), transparent until the ghost ramp colours it
 * (button_like.rs:242-247, 298-303). Sub-40dp on purpose — the 2026-08-17
 * density decision — and never the only route: Enter, Escape and the IME's Go
 * key do the same two things.
 */
@Composable
private fun Action(glyph: String, description: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .height(22.dp)
            .widthIn(min = 22.dp)
            .clip(RoundedCornerShape(4.dp))
            // The ghost ramp is the state layer the ripple draws; the clip
            // keeps it inside the 4dp corners. Nothing is painted at rest.
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClickLabel = description,
                onClick = onClick,
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
