package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import to.eyed.seeker.code.core.CoreBridge
import to.eyed.seeker.code.ui.editor.Caret
import to.eyed.seeker.code.ui.editor.EditorState
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.revealItem

/** One row of the buffer's outline, as the engine reports it. */
internal data class OutlineEntry(
    val label: String,
    val depth: Int,
    val row: Int,
    val col: Int,
    /** The last row of the item, for finding the symbol containing the caret. */
    val endRow: Int,
)

/**
 * Zed's outline picker — `outline::Toggle` on Ctrl+Shift+O
 * (default-linux.json:621), and where a tap on the breadcrumbs lands, which
 * is Zed's own wiring for them (the crumbs are a button into the outline).
 *
 * Arrowing through the list *previews*: the editor's caret follows the
 * selected symbol, exactly as Zed's picker scrolls its editor while you
 * browse; Escape puts the caret, the selection and the viewport back where
 * they were, Enter keeps the landing. Filtering is a plain
 * every-word-matches-somewhere test rather than Zed's fuzzy scorer.
 *
 * Deliberate deviation: Zed refuses to open at all on a file with no
 * symbols (outline.rs:59-62); ours opens and says so — on a phone the
 * picker is also how you *discover* the feature, and a chord that silently
 * does nothing reads as broken.
 */
@Composable
fun OutlinePicker(
    editor: EditorState,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var items by remember { mutableStateOf(emptyList<OutlineEntry>()) }
    var loaded by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    // What Escape restores — the whole caret set and the viewport, captured
    // before the preview moves anything (GoToLine's contract).
    val original = remember(editor) {
        Triple(editor.caretsInOrder(), editor.primaryCaret(), editor.scrollY)
    }
    // The folds too: browsing the list opens the block each symbol lives in,
    // and a cancelled browse owes the file back exactly as it was.
    val originalFolds = remember(editor) { editor.folds }

    LaunchedEffect(Unit) { focus.requestFocus() }

    // Nothing moves until the user browses or types — Zed opens with the
    // deepest symbol containing the caret pre-selected and navigate=false
    // (outline.rs:379-401); Enter straight after opening keeps you where
    // you are, on the symbol you were already in.
    var browsed by remember { mutableStateOf(false) }

    // The outline is read once per opening: it comes from the last parsed
    // tree, and the buffer cannot change while a modal has the keyboard.
    LaunchedEffect(editor) {
        val id = editor.session.id
        val loadedItems = withContext(Dispatchers.Default) {
            parseOutline(CoreBridge.bufferOutline(id))
        }
        items = loadedItems
        selected = initialOutlineSelection(loadedItems, original.second.headRow)
        loaded = true
    }

    val shown = remember(items, query.text) { filterOutline(items, query.text) }

    // Typing re-picks from the top of the narrowed list, as Zed re-picks its
    // best match per query (outline.rs:385-397); arrow browsing alone keeps
    // the index.
    LaunchedEffect(query.text) {
        if (query.text.isNotEmpty()) {
            selected = 0
            browsed = true
        }
    }
    LaunchedEffect(shown) {
        if (selected >= shown.size) selected = 0
    }

    fun navigateTo(entry: OutlineEntry) {
        // Clamped like go-to-line: the outline can be one reparse stale, and
        // a caret past a line's end is a crash later, not a jump now.
        val row = entry.row.coerceIn(0, editor.lineCount - 1)
        val col = entry.col.coerceIn(0, editor.line(row).length)
        val caret = Caret(row, col)
        // A symbol inside a folded block is exactly the symbol this picker is
        // for; `setCarets` opens the fold over it ([HiddenCaret.Reveal])
        // rather than leaving the caret on the chip row above it.
        editor.setCarets(listOf(caret), caret)
    }

    // The preview: the caret follows the browsed symbol.
    LaunchedEffect(selected, shown, browsed) {
        if (!browsed) return@LaunchedEffect
        val entry = shown.getOrNull(selected) ?: return@LaunchedEffect
        navigateTo(entry)
        if (selected in shown.indices) listState.revealItem(selected)
    }

    fun restore() {
        val (carets, primary, scrollY) = original
        editor.setCarets(carets, primary)
        editor.foldRanges(originalFolds)
        editor.scrollToY(scrollY)
    }

    fun cancel() {
        restore()
        onDismiss()
    }

    fun confirm() {
        val entry = shown.getOrNull(selected) ?: return cancel()
        navigateTo(entry)
        onDismiss()
    }

    fun move(delta: Int) {
        if (shown.isEmpty()) return
        browsed = true
        val size = shown.size
        selected = ((selected + delta) % size + size) % size
    }

    PickerModal(
        onDismiss = ::cancel,
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionDown -> { move(1); true }
                Key.DirectionUp -> { move(-1); true }
                Key.Enter, Key.NumPadEnter -> { confirm(); true }
                Key.Escape -> { cancel(); true }
                else -> false
            }
        },
    ) {
        PickerQueryField(
            query = query,
            onQueryChange = { query = it },
            // Zed's own placeholder (crates/outline/src/outline.rs).
            placeholder = "Search buffer symbols...",
            focusRequester = focus,
        )

        if (shown.isEmpty()) {
            PickerEmptyState(
                when {
                    !loaded -> "Reading symbols…"
                    query.text.isEmpty() -> "No symbols in this file"
                    else -> "No matches"
                }
            )
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PickerListPadding,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            ) {
                itemsIndexed(shown, key = { index, entry -> "${entry.row}:${entry.col}:$index" }) { index, entry ->
                    OutlineRow(
                        entry = entry,
                        isSelected = index == selected,
                        onClick = {
                            selected = index
                            confirm()
                        },
                    )
                }
            }
        }
    }
}

/**
 * One symbol, indented by its depth as Zed's outline rows are; the label at
 * the default size, `text` when selected and `text.muted` otherwise, like a
 * tree entry.
 */
@Composable
private fun OutlineRow(
    entry: OutlineEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    PickerListItem(isSelected = isSelected, onClick = onClick) {
        Text(
            text = entry.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSelected) theme.color("text") else theme.color("text.muted"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = (entry.depth * 12).dp),
        )
    }
}

/**
 * Zed's opening selection: the deepest symbol whose item contains the caret,
 * the last such in source order breaking depth ties (outline.rs:379-381);
 * the first row when the caret sits outside every symbol.
 */
internal fun initialOutlineSelection(items: List<OutlineEntry>, caretRow: Int): Int {
    var best = 0
    var bestDepth = -1
    for ((index, entry) in items.withIndex()) {
        if (caretRow in entry.row..entry.endRow && entry.depth >= bestDepth) {
            best = index
            bestDepth = entry.depth
        }
    }
    return best
}

/** A query split into the words that must each match; empty matches everything. */
internal fun outlineQueryWords(query: String): List<String> =
    query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

/** Every query word must appear somewhere in the label, case-insensitively. */
internal fun outlineMatches(entry: OutlineEntry, words: List<String>): Boolean =
    words.all { word -> entry.label.contains(word, ignoreCase = true) }

/**
 * [outlineMatches] over a whole outline — the picker's filter. The panel
 * filters with the indices kept, so it uses the two halves directly.
 */
internal fun filterOutline(items: List<OutlineEntry>, query: String): List<OutlineEntry> {
    val words = outlineQueryWords(query)
    if (words.isEmpty()) return items
    return items.filter { entry -> outlineMatches(entry, words) }
}

/**
 * The engine's outline — a JSON array of `{label, depth, row, col_utf16}`.
 * Defensive for the same reasons as the breadcrumb parser: null or garbage
 * is an empty outline, never a crash.
 */
internal fun parseOutline(json: String?): List<OutlineEntry> {
    if (json.isNullOrEmpty()) return emptyList()
    return try {
        val array = JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val label = item.getString("label")
                if (label.isBlank()) continue
                add(
                    OutlineEntry(
                        label = label,
                        depth = item.getInt("depth"),
                        row = item.getInt("row"),
                        col = item.getInt("col_utf16"),
                        endRow = item.getInt("end_row"),
                    )
                )
            }
        }
    } catch (_: JSONException) {
        emptyList()
    }
}
