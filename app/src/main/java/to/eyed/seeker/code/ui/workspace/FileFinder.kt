package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.core.FileMatch
import to.eyed.seeker.code.core.ProjectSession
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.rem
import to.eyed.seeker.code.ui.theme.revealItem

private const val MAX_RESULTS = 50

/**
 * How long a keystroke waits before the worktree is matched. The match is a
 * blocking call that takes the engine's project mutex, so firing on every
 * keystroke queues stale searches that contend with the fresh one. Shorter
 * than project search's 250ms: this reads a snapshot already in memory, not
 * the disk.
 */
private const val QUERY_DEBOUNCE_MS = 120L

/**
 * Elevated surfaces are `rounded_lg` = 8px with a 1px `border.variant`
 * (crates/ui/src/traits/styled_ext.rs:6-12).
 */
private val ModalRadius = 8.dp

/**
 * The shell every picker in this app wears: the file finder, the command
 * palette and the theme selector are the same widget in Zed, so they are the
 * same widget here.
 *
 * Zed's picker is one widget with several contents: `DEFAULT_MODAL_WIDTH` is
 * `rems(34)` and `DEFAULT_MODAL_MAX_HEIGHT` `rems(24)` — 544 and 384 at the
 * default 16px rem, scaling with the UI font (crates/picker/src/picker.rs:45-46,
 * crates/picker/src/shape.rs:207-215). The container is a `ModalSurface`:
 * `elevated_surface.background`, `rounded_lg`, 1px `border.variant`
 * (crates/ui/src/styles/elevation.rs:58-77; styled_ext.rs:6-12).
 *
 * [modifier] lands on the surface itself, which is where a caller hangs the
 * key handling it needs to see before the text field does. Tapping outside
 * dismisses: the content fills the window so that nothing is ever "outside" it
 * as far as the dialog is concerned, and the dimmed area has to close us by
 * hand.
 */
@Composable
internal fun PickerModal(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val theme = LocalZedTheme.current
    Dialog(
        onDismissRequest = onDismiss,
        // A picker has to stay usable with the soft keyboard up — on a phone
        // it is the *only* way to type — and a dialog only learns where the IME
        // is once it stops fitting the system windows itself.
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            Surface(
                shape = RoundedCornerShape(ModalRadius),
                color = theme.color(
                    "elevated_surface.background",
                    MaterialTheme.colorScheme.surface,
                ),
                border = BorderStroke(
                    1.dp,
                    theme.color("border.variant", MaterialTheme.colorScheme.outlineVariant),
                ),
                modifier = modifier
                    .widthIn(max = rem(34f))
                    .heightIn(max = rem(24f))
                    .fillMaxWidth()
                    // Swallow taps, or they would reach the dismiss handler
                    // above and close the picker from inside it. `clickable`
                    // would do it too, and would ripple the whole panel.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(content = content)
            }
        }
    }
}

/**
 * The picker's query row, exactly as Zed draws it: a bare single-line editor
 * in a 36px row (`h_9`) with 10px of horizontal padding (`px_2p5`) — no box,
 * no border, no background of its own (crates/picker/src/render.rs:106-122) —
 * separated from what follows by a 1px `Divider::horizontal()`, whose default
 * colour is `border.variant` (render.rs:123-126;
 * crates/ui/src/components/divider.rs:19-31, 46-54).
 */
@Composable
internal fun PickerQueryField(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 10.dp)
            .pointerHoverIcon(PointerIcon.Text),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = theme.color("text")),
            cursorBrush = SolidColor(theme.cursor),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
        if (query.text.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text.placeholder"),
                maxLines = 1,
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(theme.color("border.variant")),
    )
}

/**
 * One picker row: Zed's `ListItem` with `.inset(true)` and
 * `ListItemSpacing::Sparse`. Inset draws 4px of surface either side of the
 * row (`px(Base04)`, crates/ui/src/components/list/list_item.rs:307-310); the
 * row itself is `rounded_sm` with 6px inside (`px(Base06)`), 4px vertical
 * (`py_1`) and a 6px gap between slots (list_item.rs:363-368, 405-407, 429).
 * With the 14px label's φ line box that lands the row at ~31px, Zed's number.
 * Hover, press and selection are the ghost ramp — `ghost_element.hover` /
 * `.active` / `.selected` (list_item.rs:380-385) — swapped instantly, no
 * ripple, as everywhere in Zed.
 */
@Composable
internal fun PickerListItem(
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    val theme = LocalZedTheme.current
    // Resolved once per row lifetime, not per recomposition: a picker list
    // repaints every row on each keystroke, and `theme.color` is a map read.
    val selectedFill = remember(theme) { theme.color("ghost_element.selected") }
    val pressedFill = remember(theme) { theme.color("ghost_element.active") }
    val hoverFill = remember(theme) { theme.color("ghost_element.hover") }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    isSelected -> selectedFill
                    pressed && enabled -> pressedFill
                    hovered && enabled -> hoverFill
                    else -> Color.Transparent
                }
            )
            .then(
                if (enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        content = content,
    )
}

/**
 * A picker with nothing to show: Zed renders the message as a disabled inset
 * row inside 8px of vertical padding, `Color::Muted`
 * (crates/picker/src/render.rs:257-268). The paddings below are that row's,
 * flattened: 4+6 horizontal, 8+4 vertical.
 */
@Composable
internal fun PickerEmptyState(text: String) {
    val theme = LocalZedTheme.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = theme.color("text.muted"),
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
    )
}

/**
 * The vertical padding Zed's picker list carries — `py_1` = 4px
 * (crates/picker/src/picker.rs:1503).
 */
internal val PickerListPadding = PaddingValues(vertical = 4.dp)

/**
 * Fuzzy file finder, in the shape every editor uses: type to filter, arrows
 * to move, Enter to open.
 *
 * Matching happens in the engine against the worktree snapshot already in
 * memory, so a keystroke costs one coarse JNI call and no directory walk.
 * The query runs off the main thread — it is blocking on the engine side —
 * behind a short debounce, so a typing burst becomes one call rather than a
 * queue of stale ones on the project mutex, and results are only published
 * if the query hasn't moved on, so a slow result can't overwrite a newer
 * one.
 *
 * Keyboard-first by design (see docs/SHORTCUTS.md), but every row is also a
 * touch target and shows a hand cursor under a mouse.
 */
@Composable
fun FileFinder(
    project: ProjectSession,
    onOpen: (FileMatch) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var results by remember { mutableStateOf(emptyList<FileMatch>()) }
    var selected by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }

    LaunchedEffect(query.text, project) {
        val text = query.text
        // The empty query is the finder opening; its first paint must not
        // wait on a debounce that exists for typing.
        if (text.isNotEmpty()) delay(QUERY_DEBOUNCE_MS)
        val found = withContext(Dispatchers.Default) { project.findFiles(text, MAX_RESULTS) }
        // The user may have typed on while this ran; a stale answer must not
        // replace a fresher one.
        if (text == query.text) {
            results = found
            selected = 0
        }
    }

    LaunchedEffect(selected) {
        if (selected in results.indices) listState.revealItem(selected)
    }

    fun move(delta: Int) {
        if (results.isEmpty()) return
        val size = results.size
        selected = ((selected + delta) % size + size) % size
    }

    fun openSelected() {
        results.getOrNull(selected)?.let(onOpen)
    }

    PickerModal(
        onDismiss = onDismiss,
        // Arrows and Enter must reach us even though the text field has focus,
        // so they are intercepted before it sees them.
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionDown -> { move(1); true }
                Key.DirectionUp -> { move(-1); true }
                Key.Enter, Key.NumPadEnter -> { openSelected(); true }
                Key.Escape -> { onDismiss(); true }
                else -> false
            }
        },
    ) {
        PickerQueryField(
            query = query,
            onQueryChange = { query = it },
            // Zed's own placeholder (file_finder.rs:1801-1803).
            placeholder = "Search project files...",
            focusRequester = focus,
        )

        if (results.isEmpty()) {
            PickerEmptyState(if (query.text.isEmpty()) "No files" else "No matches")
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PickerListPadding,
                // Whatever is left of the modal's 384dp, and no more: the
                // picker's height is Zed's number, not the result count's.
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            ) {
                itemsIndexed(
                    results,
                    // A project can hold the same relative path in more than
                    // one folder, so the folder is part of a row's identity.
                    key = { _, match -> "${'$'}{match.worktree}:${'$'}{match.path}" },
                ) { index, match ->
                    ResultRow(
                        match = match,
                        isSelected = index == selected,
                        onClick = { onOpen(match) },
                    )
                }
            }
        }
    }
}

/**
 * One found file, on one line, as Zed lays it out: the file's icon, then the
 * name, then the directory it is in — the name at the default size in `text`,
 * the directory in `LabelSize::Small` `text.muted`, 6px apart (`gap_1p5`,
 * file_finder.rs:2196-2203, 1419-1423). The name gives way in the middle and
 * the path at its start, which is what `truncate_middle`/`truncate_start`
 * do there.
 */
@Composable
private fun ResultRow(
    match: FileMatch,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    // The engine matched against the full path; Zed splits the positions at
    // the file name's start and highlights each label with its own share
    // (file_finder.rs:1426-1451). Our `path` ends in `name`, so the split
    // point is simply everything before it — trailing separator included,
    // which is also what Zed shows.
    val directoryLength = (match.path.length - match.name.length).coerceAtLeast(0)
    val highlight = theme.color("text.accent")
    PickerListItem(isSelected = isSelected, onClick = onClick) {
        // Zed's file finder shows the file's icon (`file_finder.file_icons`,
        // true by default), and it is the same icon the panel draws — so a
        // file found here and a file seen there are visibly the same thing.
        EntryIconMark(
            name = match.name,
            isDir = false,
            isExpanded = false,
            color = theme.color("icon.muted", MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Text(
            text = highlightedSlice(
                text = match.name,
                positions = match.positions,
                start = directoryLength,
                end = match.path.length,
                color = highlight,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("text"),
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        if (directoryLength > 0) {
            Text(
                text = highlightedSlice(
                    text = match.path.substring(0, directoryLength),
                    positions = match.positions,
                    start = 0,
                    end = directoryLength,
                    color = highlight,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text.muted"),
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        // Which folder of the project the hit is in. The engine leaves this
        // empty for a single-folder project, exactly as Zed hides its
        // `path_prefix` until there is more than one worktree to tell apart
        // (file_finder.rs), so an ordinary project's rows are unchanged.
        if (match.worktreeName.isNotEmpty()) {
            Text(
                text = match.worktreeName,
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted"),
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        }
    }
}

/**
 * [text] with the matched characters recoloured. [positions] are UTF-16
 * offsets into the *full path*; the ones inside `[start, end)` belong to
 * this label and are shifted to be relative to it. The highlight is a colour
 * change to `text.accent` and nothing else — Zed's `HighlightedLabel` does
 * not embolden (crates/ui/src/components/label/highlighted_label.rs:208-218).
 */
private fun highlightedSlice(
    text: String,
    positions: List<Int>,
    start: Int,
    end: Int,
    color: Color,
): AnnotatedString {
    val marked = positions.mapNotNullTo(HashSet()) { position ->
        if (position in start until end) position - start else null
    }
    if (marked.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        text.forEachIndexed { index, character ->
            if (index in marked) {
                withStyle(SpanStyle(color = color)) { append(character) }
            } else {
                append(character)
            }
        }
    }
}
