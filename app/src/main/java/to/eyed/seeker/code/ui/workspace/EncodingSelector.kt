package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.core.BufferEncoding
import to.eyed.seeker.code.core.CoreBridge
import to.eyed.seeker.code.core.EncodingMatch
import to.eyed.seeker.code.core.encodingLabel
import to.eyed.seeker.code.core.matchEncodings
import to.eyed.seeker.code.core.parseEncodingNames
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.revealItem

/** Which of the Unicode encodings may carry a byte-order mark. */
private val BomCapable = setOf("UTF-8", "UTF-16LE", "UTF-16BE")

/**
 * Zed's encoding selector — every encoding the engine knows, filtered as you
 * type, with "(current)" after the one in use (`encoding_selector.rs:
 * 137-149, 218-228`) — with one verb more than Zed's.
 *
 * Zed's picker does one thing: confirming a row *reopens* the file decoded
 * in that encoding (`encoding_selector.rs:293-303`), which is how a file
 * that came up as mojibake is put right, and it refuses to open at all while
 * the buffer is dirty, since a reload would throw the edits away
 * (`encoding_selector.rs:58-66`, "Save file to change encoding"). The other
 * direction — *write* the file in a different encoding — Zed leaves to
 * nothing in particular. Here it is the footer's second button, because on a
 * device where the file is going to a Windows toolchain it is the more
 * common wish; and because it is a save, it is the one verb that stays
 * available while the buffer is dirty.
 *
 * Enter and a tap on a row reopen, as in Zed. While the buffer is dirty they
 * only move the selection, and a line under the list says why.
 */
@Composable
fun EncodingSelector(
    /** The encoding in use, for the "(current)" mark and the BOM default. */
    current: BufferEncoding?,
    /** Whether the buffer has unsaved edits, which makes reopening unsafe. */
    isDirty: Boolean,
    /** Re-read the file in this encoding (by WHATWG name). */
    onReopen: (String) -> Unit,
    /** Write the file in this encoding, now. */
    onSaveWith: (BufferEncoding) -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalZedTheme.current
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var names by remember { mutableStateOf(emptyList<String>()) }
    var selected by remember { mutableIntStateOf(0) }
    var withBom by remember { mutableStateOf(current?.hasBom == true) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focus.requestFocus()
        // One JNI call, off the main thread, for a list that never changes.
        names = withContext(Dispatchers.IO) { parseEncodingNames(CoreBridge.availableEncodings()) }
    }

    val results = remember(names, query.text) { matchEncodings(names, query.text) }

    // Start on the encoding in use, as the theme selector does with the
    // theme: Enter straight away is then a reopen that changes nothing.
    LaunchedEffect(names) {
        selected = results.indexOfFirst { it.name == current?.name }.coerceAtLeast(0)
    }
    LaunchedEffect(query.text) { selected = 0 }
    LaunchedEffect(selected, results) {
        if (selected in results.indices) listState.revealItem(selected)
    }

    fun move(delta: Int) {
        if (results.isEmpty()) return
        val size = results.size
        selected = ((selected + delta) % size + size) % size
    }

    val chosen = results.getOrNull(selected)?.name
    val canReopen = chosen != null && !isDirty
    val bomApplies = chosen in BomCapable

    /**
     * Reopen in [name]. Enter passes the highlighted row; a tap passes its
     * own row, because `chosen` is the composition's value and a tap that
     * moves the highlight and reopens in one gesture would read the row
     * highlighted *before* the tap — a tap on Windows-1254 reopened in
     * whatever was current.
     */
    fun reopen(name: String? = chosen) {
        if (name == null || isDirty) return
        onReopen(name)
        onDismiss()
    }

    fun saveWith() {
        val name = chosen ?: return
        onSaveWith(BufferEncoding(name, withBom && bomApplies))
        onDismiss()
    }

    PickerModal(
        onDismiss = onDismiss,
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                event.key == Key.DirectionDown -> { move(1); true }
                event.key == Key.DirectionUp -> { move(-1); true }
                event.isCtrlPressed && event.key == Key.N -> { move(1); true }
                event.isCtrlPressed && event.key == Key.P -> { move(-1); true }
                event.key == Key.Tab -> {
                    move(if (event.isShiftPressed) -1 else 1)
                    true
                }
                // Ctrl+S in the picker is the save verb, the way Ctrl+S is
                // everywhere else; Enter stays Zed's reopen.
                event.isCtrlPressed && event.key == Key.S -> { saveWith(); true }
                event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                    reopen()
                    true
                }
                event.key == Key.Escape -> { onDismiss(); true }
                else -> false
            }
        },
    ) {
        PickerQueryField(
            query = query,
            onQueryChange = { query = it },
            // Zed's own placeholder (encoding_selector.rs:235-237).
            placeholder = "Reopen with encoding...",
            focusRequester = focus,
        )

        if (results.isEmpty()) {
            PickerEmptyState(if (names.isEmpty()) "Loading encodings" else "No matches")
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PickerListPadding,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            ) {
                itemsIndexed(results, key = { _, it -> it.name }) { index, match ->
                    EncodingRow(
                        match = match,
                        isCurrent = match.name == current?.name,
                        isSelected = index == selected,
                        onClick = {
                            selected = index
                            reopen(match.name)
                        },
                    )
                }
            }
        }

        if (isDirty) {
            // Zed's toast, as a line: the reopen is off, and this is why.
            Text(
                text = "Save file to change encoding — reopening would drop the unsaved edits",
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text.muted"),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        // The picker footer the theme selector wears (theme_selector.rs:
        // 536-546): a 1px `border.variant` rule, 8px padding, 8px gaps.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(theme.color("border.variant")),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        ) {
            // The byte-order mark, which only the Unicode encodings have a
            // place for; greyed for the rest rather than hidden, so the row
            // does not jump as the selection moves.
            GhostButton(
                text = if (withBom && bomApplies) "✓ With BOM" else "With BOM",
                isPrimary = false,
                enabled = bomApplies,
                onClick = { withBom = !withBom },
            )
            Box(modifier = Modifier.weight(1f))
            GhostButton(text = "Cancel", isPrimary = false, onClick = onDismiss)
            GhostButton(
                text = "Save with encoding",
                isPrimary = isDirty,
                enabled = chosen != null,
                onClick = { saveWith() },
            )
            GhostButton(
                text = "Reopen",
                isPrimary = !isDirty,
                enabled = canReopen,
                onClick = { reopen() },
            )
        }
    }
}

@Composable
private fun EncodingRow(
    match: EncodingMatch,
    isCurrent: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    PickerListItem(isSelected = isSelected, onClick = onClick) {
        Text(
            text = highlightedLabel(
                encodingLabel(match.name, false),
                match.positions,
                theme.color("text.accent"),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("text"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isCurrent) {
            // Zed writes "(current)" into the label (encoding_selector.rs:
            // 144-146); the end slot keeps the name searchable as typed.
            Text(
                text = "current",
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text.muted"),
            )
        }
    }
}

/** Matched characters in `text.accent`, as Zed's `HighlightedLabel` draws them. */
private fun highlightedLabel(label: String, positions: List<Int>, color: Color): AnnotatedString {
    if (positions.isEmpty()) return AnnotatedString(label)
    val marked = positions.toHashSet()
    return buildAnnotatedString {
        label.forEachIndexed { index, character ->
            if (index in marked) {
                withStyle(SpanStyle(color = color)) { append(character) }
            } else {
                append(character)
            }
        }
    }
}
