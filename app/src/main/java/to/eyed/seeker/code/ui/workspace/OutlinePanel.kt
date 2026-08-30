package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.CoreBridge
import to.eyed.seeker.code.ui.editor.Caret
import to.eyed.seeker.code.ui.editor.EditorState
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.revealItem

/**
 * One drawn row of the outline tree: an entry, where it sits in the flat
 * outline the engine handed over, and what the disclosure triangle should be
 * doing.
 *
 * [index] is the row's identity for the whole panel — the collapsed set, the
 * selection and the reveal are all indices into the engine's list, so nothing
 * has to compare labels, which repeat.
 */
internal data class OutlineRowEntry(
    val entry: OutlineEntry,
    val index: Int,
    val hasChildren: Boolean,
    val isExpanded: Boolean,
)

/**
 * Each entry's parent, by index, or -1 for a root — the tree the engine's flat
 * `depth` column implies.
 *
 * A stack rather than a scan: the outline is in source order and a symbol's
 * parent is the nearest earlier entry shallower than it, which is exactly what
 * popping everything at or below its depth leaves on top.
 */
internal fun outlineParents(items: List<OutlineEntry>): IntArray {
    val parents = IntArray(items.size) { -1 }
    val stack = ArrayDeque<Int>()
    for ((index, entry) in items.withIndex()) {
        while (stack.isNotEmpty() && items[stack.last()].depth >= entry.depth) stack.removeLast()
        parents[index] = stack.lastOrNull() ?: -1
        stack.addLast(index)
    }
    return parents
}

/**
 * The rows to draw: every entry whose ancestors are all expanded, in source
 * order — Zed's outline panel, which is a disclosure tree over the same
 * symbols the picker lists (outline_panel.rs).
 *
 * One pass, and it relies on source order: a child always comes after its
 * parent, so by the time a row is reached its parent's visibility is settled.
 */
internal fun flattenOutline(
    items: List<OutlineEntry>,
    collapsed: Set<Int>,
): List<OutlineRowEntry> {
    if (items.isEmpty()) return emptyList()
    val parents = outlineParents(items)
    val hasChildren = BooleanArray(items.size)
    for (parent in parents) if (parent >= 0) hasChildren[parent] = true
    val hidden = BooleanArray(items.size)
    val rows = ArrayList<OutlineRowEntry>(items.size)
    for (index in items.indices) {
        val parent = parents[index]
        hidden[index] = parent >= 0 && (hidden[parent] || parent in collapsed)
        if (hidden[index]) continue
        rows.add(
            OutlineRowEntry(
                entry = items[index],
                index = index,
                hasChildren = hasChildren[index],
                isExpanded = index !in collapsed,
            )
        )
    }
    return rows
}

/**
 * The deepest symbol whose item contains [caretRow], the last such in source
 * order breaking depth ties — the picker's rule ([initialOutlineSelection]),
 * except that a caret outside every symbol is **null** rather than the first
 * row: this one drives the panel's selection as the caret moves, and jumping
 * to the top of the file every time the caret steps into a blank line between
 * two functions would be a tic, not a reveal.
 */
internal fun outlineIndexAt(items: List<OutlineEntry>, caretRow: Int): Int? {
    var best: Int? = null
    var bestDepth = -1
    for ((index, entry) in items.withIndex()) {
        if (caretRow in entry.row..entry.endRow && entry.depth >= bestDepth) {
            best = index
            bestDepth = entry.depth
        }
    }
    return best
}

/**
 * What the caret moving means for the panel — Zed's `auto_reveal_entries`
 * (assets/settings/default.json:974-976): select the symbol the caret is in
 * and open every ancestor that was folded over it, so the row is actually on
 * screen rather than merely selected inside a collapsed parent.
 *
 * Returns null when the caret is in no symbol at all, which leaves the
 * selection and the folds exactly as the user left them.
 */
internal fun outlineReveal(
    items: List<OutlineEntry>,
    caretRow: Int,
    collapsed: Set<Int>,
): OutlineRevealResult? {
    val index = outlineIndexAt(items, caretRow) ?: return null
    val parents = outlineParents(items)
    var opened = collapsed
    var walk = parents[index]
    while (walk >= 0) {
        opened = opened - walk
        walk = parents[walk]
    }
    return OutlineRevealResult(index, opened)
}

/** [outlineReveal]'s answer: which row to select, and the folds it needed open. */
internal data class OutlineRevealResult(val index: Int, val collapsed: Set<Int>)

/** How long the panel lets the buffer settle before re-reading its symbols. */
private const val OUTLINE_DEBOUNCE_MS = 250L

/** Zed's `outline_panel.indent_size`, 20px (default.json:972). */
private val IndentSize = 20.dp

/** A row is Zed's `ListItem` at the density the project panel already uses. */
private val RowHeight = 26.dp

/** The disclosure triangle's box. */
private val ChevronBox = 16.dp

/**
 * The outline panel — Zed's `outline_panel` crate as a dock panel: the active
 * file's symbol tree, expandable, following the caret, with the picker's
 * filter box over it.
 *
 * It is deliberately *not* a second copy of [OutlinePicker]. The picker is
 * modal, previews as you arrow through it and puts everything back on Escape;
 * this stays open beside the editor, keeps its folds, and every jump is a jump
 * you keep — which is the difference between "find me a symbol" and "show me
 * the shape of this file".
 *
 * Zed's `auto_reveal_entries` is on and not configurable here: the panel is
 * narrow enough that a selection that does not follow the caret would be a
 * second thing to steer.
 */
@Composable
fun OutlinePanel(
    editor: EditorState?,
    /** Bumped when Ctrl+Shift+B is pressed, to put the caret back in the filter. */
    focusToken: Int,
    /** A jump landed; on a compact screen the panel hands the work area back. */
    onNavigated: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var items by remember { mutableStateOf(emptyList<OutlineEntry>()) }
    var loaded by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf(emptySet<Int>()) }
    var selected by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    LaunchedEffect(focusToken) { focus.requestFocus() }

    // The symbols come from the last parsed tree, so they are re-read when the
    // buffer's revision moves — debounced, because a reparse per keystroke on
    // a 5000-line file is the kind of work that makes typing stutter.
    val revision = editor?.revision ?: 0
    LaunchedEffect(editor, revision) {
        val session = editor?.sessionOrNull
        if (session == null) {
            items = emptyList()
            loaded = true
            return@LaunchedEffect
        }
        if (loaded) delay(OUTLINE_DEBOUNCE_MS)
        items = withContext(Dispatchers.Default) { parseOutline(CoreBridge.bufferOutline(session.id)) }
        loaded = true
    }

    // A new file is a new tree: nothing about the last one's folds or its
    // selection means anything here.
    LaunchedEffect(editor) {
        collapsed = emptySet()
        selected = -1
        query = TextFieldValue("")
    }

    val filtering = query.text.isNotBlank()
    val rows = remember(items, collapsed, query.text) {
        if (filtering) {
            // A filtered outline is a *list*, as the picker's is: the tree
            // structure of a search result is noise, and hiding a match
            // because its parent did not match would be a bug. Filtered with
            // the indices kept, since the index is the row's identity here.
            val words = outlineQueryWords(query.text)
            items.withIndex()
                .filter { (_, entry) -> outlineMatches(entry, words) }
                .map { (index, entry) ->
                    OutlineRowEntry(
                        entry = entry,
                        index = index,
                        hasChildren = false,
                        isExpanded = true,
                    )
                }
        } else {
            flattenOutline(items, collapsed)
        }
    }

    // Zed's auto_reveal_entries. Off while filtering: the caret moving under a
    // search would keep yanking the list away from what was typed.
    val caretRow = editor?.cursorRow ?: 0
    LaunchedEffect(items, caretRow, filtering) {
        if (filtering || items.isEmpty()) return@LaunchedEffect
        val reveal = outlineReveal(items, caretRow, collapsed) ?: return@LaunchedEffect
        collapsed = reveal.collapsed
        selected = reveal.index
    }

    // Keep the selected row on screen once it has been chosen for us.
    LaunchedEffect(selected, rows) {
        val at = rows.indexOfFirst { it.index == selected }
        if (at >= 0) listState.revealItem(at)
    }

    fun jumpTo(entry: OutlineEntry) {
        val open = editor ?: return
        // Clamped like go-to-line: the outline can be one reparse stale, and
        // a caret past a line's end is a crash later, not a jump now.
        val row = entry.row.coerceIn(0, (open.lineCount - 1).coerceAtLeast(0))
        val col = entry.col.coerceIn(0, open.line(row).length)
        val caret = Caret(row, col)
        // `setCarets` opens a fold over the target ([HiddenCaret.Reveal]),
        // which is the whole point of jumping to a symbol inside one.
        open.setCarets(listOf(caret), caret)
        onNavigated()
    }

    fun toggle(index: Int) {
        collapsed = if (index in collapsed) collapsed - index else collapsed + index
    }

    /**
     * Enter and a tap both jump, whether or not the row has children — Zed's
     * `outline_panel::OpenSelectedEntry` (default-linux.json:960). Folding is
     * the triangle's job and the arrow keys', never the row's: a `mod` you
     * cannot jump to because it happens to contain something would be a tree
     * you can only read the leaves of.
     */
    fun activate(row: OutlineRowEntry) {
        selected = row.index
        jumpTo(row.entry)
    }

    fun move(delta: Int) {
        if (rows.isEmpty()) return
        val at = rows.indexOfFirst { it.index == selected }
        val next = when {
            at < 0 -> if (delta > 0) 0 else rows.lastIndex
            else -> (at + delta).coerceIn(0, rows.lastIndex)
        }
        selected = rows[next].index
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.color("panel.background"))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> { move(1); true }
                    Key.DirectionUp -> { move(-1); true }
                    // Zed's OutlinePanel context: left collapses the selected
                    // entry, right expands it (default-linux.json:953-954).
                    Key.DirectionLeft -> {
                        val row = rows.firstOrNull { it.index == selected }
                        if (row != null && row.hasChildren && row.isExpanded) {
                            toggle(row.index)
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionRight -> {
                        val row = rows.firstOrNull { it.index == selected }
                        if (row != null && row.hasChildren && !row.isExpanded) {
                            toggle(row.index)
                            true
                        } else {
                            false
                        }
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        val row = rows.firstOrNull { it.index == selected }
                        if (row == null) move(1) else activate(row)
                        true
                    }
                    // Zed's `escape` in the OutlinePanel context is
                    // `menu::Cancel` (default-linux.json:952).
                    Key.Escape -> { onDismiss(); true }
                    else -> false
                }
            },
    ) {
        // The picker's own field, so the two ways into the outline filter
        // identically — and Zed's own placeholder.
        PickerQueryField(
            query = query,
            onQueryChange = { query = it },
            placeholder = "Search buffer symbols...",
            focusRequester = focus,
        )

        if (rows.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    text = when {
                        editor == null -> "Open a file to see its symbols"
                        !loaded -> "Reading symbols…"
                        filtering -> "No matches"
                        else -> "No symbols in this file"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.color("text.muted"),
                )
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(rows, key = { _, row -> row.index }) { _, row ->
                    OutlinePanelRow(
                        row = row,
                        isSelected = row.index == selected,
                        // A filtered list is flat, so it is drawn flat.
                        indented = !filtering,
                        onToggle = { toggle(row.index) },
                        onClick = { activate(row) },
                    )
                }
            }
        }
    }
}

/**
 * One symbol: a disclosure triangle where there are children, then the label,
 * indented by depth — Zed's outline rows, at `indent_size` per level
 * (default.json:971-972).
 */
@Composable
private fun OutlinePanelRow(
    row: OutlineRowEntry,
    isSelected: Boolean,
    indented: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .background(
                when {
                    isSelected -> theme.color("element.selected", Color.Transparent)
                    hovered -> theme.color("element.hover", Color.Transparent)
                    else -> Color.Transparent
                }
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "Go to ${row.entry.label}",
                onClick = onClick,
            )
            .padding(
                start = 4.dp + if (indented) IndentSize * row.entry.depth else 0.dp,
                end = 6.dp,
            ),
    ) {
        if (row.hasChildren) {
            Box(
                modifier = Modifier
                    .size(ChevronBox)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = if (row.isExpanded) {
                            "Collapse ${row.entry.label}"
                        } else {
                            "Expand ${row.entry.label}"
                        },
                        onClick = onToggle,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // One glyph, turned: Zed's disclosure triangle points down
                // when open and right when shut (ui/src/components/
                // disclosure.rs), and a quarter turn of the chevron is that
                // without a second asset to keep in step.
                Image(
                    painter = painterResource(R.drawable.ic_ui_chevron_down),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(theme.color("text.muted")),
                    modifier = Modifier
                        .size(12.dp)
                        .rotate(if (row.isExpanded) 0f else -90f),
                )
            }
        } else {
            Box(modifier = Modifier.width(ChevronBox))
        }
        Text(
            text = row.entry.label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isSelected) FontWeight.Medium else null,
            color = if (isSelected) theme.color("text") else theme.color("text.muted"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
