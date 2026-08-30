package to.eyed.seeker.code.ui.git

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.GitSession
import to.eyed.seeker.code.core.ProjectSession
import to.eyed.seeker.code.core.StashEntry
import to.eyed.seeker.code.core.StashList
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.revealItem
import to.eyed.seeker.code.ui.workspace.PickerEmptyState
import to.eyed.seeker.code.ui.workspace.PickerListItem
import to.eyed.seeker.code.ui.workspace.PickerListPadding
import to.eyed.seeker.code.ui.workspace.PickerModal

/**
 * The stash picker — Zed's `StashList` (git_ui/src/stash_picker.rs), worn as
 * this app's one picker chrome ([PickerModal]), opened by `git::ViewStash`.
 *
 * What is visible follows Zed: "Select a stash…" as the placeholder, each row
 * `#N: message` over `branch • when`, the newest first; `Enter` applies the
 * selected stash and `Ctrl` `Enter` pops it (Zed's `menu::Confirm` /
 * `SecondaryConfirm`, stash_picker.rs:481-491); `Ctrl` `Shift` `Backspace`
 * drops it (`stash_picker::DropStashItem`, default-linux.json:1252), after a
 * prompt, as Zed prompts. Each row carries pop and drop buttons for a finger,
 * and the footer repeats the verbs. The picker stays up after a drop, with
 * the list re-read, and closes after an apply or a pop, whose outcome — git's
 * refusal on a conflict — is worded in a prompt titled as Zed titles it.
 */
@Composable
fun StashPicker(project: ProjectSession, onDismiss: () -> Unit) {
    val theme = LocalZedTheme.current
    val scope = rememberCoroutineScope()
    val session = remember(project) { GitSession(project) }

    var query by remember { mutableStateOf(TextFieldValue("")) }
    /** Null until the first listing lands: "loading", not "no stashes". */
    var listing by remember { mutableStateOf<StashList?>(null) }
    var selected by remember { mutableIntStateOf(0) }
    /** A drop waiting on the user — Zed's "Drop stash?" prompt. */
    var dropAsk by remember { mutableStateOf<StashEntry?>(null) }
    /** What git said when it refused, under Zed's title for the verb. */
    var failure by remember { mutableStateOf<Pair<String, String>?>(null) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val now = remember { System.currentTimeMillis() / 1000 }

    fun reload() {
        scope.launch { listing = withContext(Dispatchers.IO) { session.stashList() } }
    }

    LaunchedEffect(session) {
        focus.requestFocus()
        reload()
    }

    val rows = remember(listing, query.text) {
        stashPickerRows(listing?.entries ?: emptyList(), query.text)
    }
    LaunchedEffect(query.text) { selected = 0 }
    LaunchedEffect(selected, rows) {
        if (selected in rows.indices) listState.revealItem(selected)
    }

    fun move(delta: Int) {
        if (rows.isEmpty()) return
        val size = rows.size
        selected = ((selected + delta) % size + size) % size
    }

    /**
     * One stash command through the panel's single-flight, so it cannot run
     * through the middle of a pull. [title] is Zed's prompt title for the
     * verb (stash_picker.rs:312, 347, 363).
     */
    fun run(title: String, action: () -> String?, onSuccess: () -> Unit) {
        val started = GitOps.run(project.id, { action() }) { refusal ->
            if (refusal == null) onSuccess() else failure = title to refusal
        }
        if (!started) failure = title to "Still running the last git command…"
    }

    fun apply(entry: StashEntry) =
        run("Failed to apply stash", { session.stashApply(entry.index) }, onDismiss)

    fun pop(entry: StashEntry) =
        run("Failed to pop stash", { session.stashPop(entry.index) }, onDismiss)

    fun drop(entry: StashEntry) =
        run("Failed to drop stash", { session.stashDrop(entry.index) }, ::reload)

    fun confirm(secondary: Boolean) {
        val entry = rows.getOrNull(selected) ?: return
        if (secondary) pop(entry) else apply(entry)
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
                // `ctrl-shift-backspace`: DropStashItem (default-linux.json:1252).
                event.isCtrlPressed && event.isShiftPressed && event.key == Key.Backspace -> {
                    rows.getOrNull(selected)?.let { dropAsk = it }
                    true
                }
                event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                    confirm(secondary = event.isCtrlPressed)
                    true
                }
                event.key == Key.Escape -> {
                    onDismiss()
                    true
                }
                else -> false
            }
        },
    ) {
        // The query row, in the branch picker's shape.
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
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = theme.color("text")),
                cursorBrush = SolidColor(theme.cursor),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
            if (query.text.isEmpty()) {
                Text(
                    // Zed's placeholder (stash_picker.rs:378).
                    text = "Select a stash…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.color("text.placeholder"),
                    maxLines = 1,
                )
            }
        }
        HorizontalDivider(color = theme.color("border.variant"))

        listing?.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("error"),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        when {
            listing == null -> PickerEmptyState("Reading the stash…")
            // Zed's `no_matches_text` (stash_picker.rs:624).
            rows.isEmpty() -> PickerEmptyState("No stashes found")
            else -> LazyColumn(
                state = listState,
                contentPadding = PickerListPadding,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                itemsIndexed(rows, key = { _, entry -> entry.sha }) { index, entry ->
                    StashRow(
                        entry = entry,
                        isSelected = index == selected,
                        now = now,
                        onClick = {
                            selected = index
                            apply(entry)
                        },
                        onPop = { pop(entry) },
                        onDrop = { dropAsk = entry },
                    )
                }
            }
        }

        // Zed's footer: Drop, Pop, Apply, with their chords (stash_picker.rs:628-700).
        if (rows.isNotEmpty()) {
            HorizontalDivider(color = theme.color("border.variant"))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FooterButton("Drop", "Ctrl Shift Backspace") { rows.getOrNull(selected)?.let { dropAsk = it } }
                FooterButton("Pop", "Ctrl Enter") { confirm(secondary = true) }
                FooterButton("Apply", "Enter") { confirm(secondary = false) }
            }
        }
    }

    dropAsk?.let { entry ->
        AlertDialog(
            onDismissRequest = { dropAsk = null },
            title = { Text("Drop ${stashTitle(entry)}?") },
            text = { Text("The stash is deleted. Git keeps no copy of what it held.") },
            confirmButton = {
                TextButton(onClick = {
                    dropAsk = null
                    drop(entry)
                }) { Text("Drop") }
            },
            dismissButton = { TextButton(onClick = { dropAsk = null }) { Text("Cancel") } },
        )
    }

    failure?.let { (title, detail) ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(title) },
            text = { Text(detail) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text("OK") } },
        )
    }
}

/** Zed's `format_message`: `#N: message` (stash_picker.rs:494-496). */
internal fun stashTitle(entry: StashEntry): String = "#${entry.index}: ${entry.message}"

/**
 * The rows for a query: every entry, newest first, when the query is empty;
 * otherwise those whose message, branch or index contains it, case blind —
 * a plain match where Zed fuzzy-matches, the list being a handful of rows.
 */
internal fun stashPickerRows(entries: List<StashEntry>, query: String): List<StashEntry> {
    val needle = query.trim()
    if (needle.isEmpty()) return entries.sortedBy { it.index }
    return entries
        .filter { entry ->
            stashTitle(entry).contains(needle, ignoreCase = true) ||
                entry.branch?.contains(needle, ignoreCase = true) == true
        }
        .sortedBy { it.index }
}

/**
 * One row (`render_match`, stash_picker.rs:497-622): the open-box glyph,
 * `#N: message` over `branch • when`, and on hover — or when selected, for
 * a finger that never hovers — the pop and drop buttons.
 */
@Composable
private fun StashRow(
    entry: StashEntry,
    isSelected: Boolean,
    now: Long,
    onClick: () -> Unit,
    onPop: () -> Unit,
    onDrop: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val muted = theme.color("text.muted")
    PickerListItem(isSelected = isSelected, onClick = onClick, interactionSource = interaction) {
        Image(
            painter = painterResource(R.drawable.ic_ui_git_commit),
            contentDescription = null,
            colorFilter = ColorFilter.tint(muted),
            modifier = Modifier.size(14.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stashTitle(entry),
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                entry.branch?.let { branch ->
                    Text(
                        text = branch,
                        style = MaterialTheme.typography.labelMedium,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelMedium,
                        color = muted.copy(alpha = 0.5f),
                    )
                }
                Text(
                    text = relativeTime(entry.timestamp, now),
                    style = MaterialTheme.typography.labelMedium,
                    color = muted,
                    maxLines = 1,
                )
            }
        }
        if (hovered || isSelected) {
            PickerIconButton(icon = R.drawable.ic_ui_expand_up, label = "Pop Stash", onClick = onPop)
            PickerIconButton(icon = R.drawable.ic_ui_trash, label = "Drop Stash", onClick = onDrop)
        }
    }
}

@Composable
private fun PickerIconButton(icon: Int, label: String, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(if (hovered) theme.color("ghost_element.hover") else Color.Transparent)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(theme.color("text.muted")),
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun FooterButton(label: String, chord: String, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .background(if (hovered) theme.color("ghost_element.hover") else Color.Transparent)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = theme.color("text"))
        Text(text = chord, style = MaterialTheme.typography.labelSmall, color = theme.color("text.muted"))
    }
}
