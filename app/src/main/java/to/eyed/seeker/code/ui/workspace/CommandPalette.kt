package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.ui.theme.LocalAppSettings
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.rem
import to.eyed.seeker.code.ui.theme.revealItem

/**
 * The command palette: every command in the workspace, searchable by name.
 *
 * This is the thing that makes the keyboard optional. A binding you have to
 * know is a binding a phone or a tablet cannot offer at all, so the palette is
 * the one surface where *everything* is reachable — type part of a name, tap
 * the row. It deliberately looks and behaves like the file finder ([FileFinder]),
 * because to a user they are one gesture with two contents — and in Zed they
 * are literally the same `Picker`, which is why both wear [PickerModal].
 *
 * Names are Zed's own action names, humanised: "terminal panel: toggle", not
 * "Toggle terminal". Searching "term" therefore finds everything the terminal
 * can do, which is the whole point of the namespace being in the name.
 *
 * Two states, and one rule for which is which. A command this *build* cannot
 * perform — cloning without a Linux userland, an agent thread in an edition
 * with no agent panel — is not listed at all: that is Zed's
 * `CommandPaletteFilter`, which hides an action rather than offering one that
 * can never work. A command that exists but cannot run *right now* is listed
 * and greyed, never hidden, because a command that vanishes when you cannot
 * use it is a command you never learn exists. `WorkspaceCommand.isOffered`
 * decides the first and `isAvailable` the second, and nothing else may hide a
 * row — `PaletteVisibilityTest` pins that.
 *
 * The chord beside each row is rendered from the keymap in Keybindings.kt, so
 * it cannot drift from what the keyboard actually does; a `command_aliases`
 * entry pointing at the command is printed beside it for the same reason.
 */
@Composable
fun CommandPalette(
    workspace: CommandContext,
    /** Runs the command; false when the workspace refused it after all. */
    onRun: (WorkspaceCommand) -> Boolean,
    onDismiss: () -> Unit,
    /** Where the keyboard was when the palette opened; decides which chords it prints. */
    keyboardFocus: Focus = Focus.Workspace,
    /** Pre-filled query, for a caller handing the palette a search. */
    initialQuery: String = "",
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf(TextFieldValue(initialQuery)) }
    var selected by remember { mutableIntStateOf(0) }
    var recent by remember { mutableStateOf(CommandRecency.known()) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focus.requestFocus()
        // Painted from the in-memory copy first; the disk read only matters
        // once per process, and never on the frame that opens the palette.
        recent = withContext(Dispatchers.IO) { CommandRecency.load(context) }
    }

    // A leading ">" is how VS Code's finder switches to commands, and enough
    // people type it out of habit that swallowing it is kinder than matching
    // nothing. It is also the hook for handing off from the file finder.
    val typed = query.text.removePrefix(">")
    val aliases = LocalAppSettings.current.commandAliases
    // Zed's two query rewrites, in Zed's order (command_palette.rs:470-487):
    // an alias replaces the whole query with an action name, and the result is
    // then normalised so that an action name matches a humanised one.
    val text = remember(typed, aliases) {
        normalizeActionQuery(resolveCommandAlias(typed, aliases))
    }
    val entries = remember(workspace, keyboardFocus, aliases) {
        paletteEntries(workspace, keyboardFocus, aliases)
    }
    val results = remember(entries, text, recent) { matchCommands(entries, text, recent) }

    LaunchedEffect(typed) { selected = 0 }
    LaunchedEffect(selected, results) {
        if (selected in results.indices) listState.revealItem(selected)
    }

    fun move(delta: Int) {
        if (results.isEmpty()) return
        val size = results.size
        selected = ((selected + delta) % size + size) % size
    }

    fun run(match: CommandMatch) {
        if (!match.entry.isEnabled) return
        val command = match.entry.command
        onDismiss()
        // Only what actually ran is worth remembering: a command the workspace
        // refused should not climb to the top of the list for it.
        if (onRun(command)) recent = CommandRecency.record(context, command)
    }

    PickerModal(
        onDismiss = onDismiss,
        // Arrows and Enter must reach us even though the text field has
        // focus, so they are intercepted before it.
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (isCommandPalette(event)) {
                // The chord that opened it closes it, as in Zed.
                onDismiss()
                return@onPreviewKeyEvent true
            }
            when {
                event.key == Key.DirectionDown -> { move(1); true }
                event.key == Key.DirectionUp -> { move(-1); true }
                // Zed's menu bindings: Ctrl+N/Ctrl+P and Tab move
                // the selection too, for hands that never leave
                // the home row.
                event.isCtrlPressed && event.key == Key.N -> { move(1); true }
                event.isCtrlPressed && event.key == Key.P -> { move(-1); true }
                event.key == Key.Tab -> {
                    move(if (event.isShiftPressed) -1 else 1)
                    true
                }
                event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                    results.getOrNull(selected)?.let(::run)
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
            // Zed's own placeholder (command_palette.rs:394-396).
            placeholder = "Execute a command...",
            focusRequester = focus,
        )

        if (results.isEmpty()) {
            PickerEmptyState("No matching commands")
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PickerListPadding,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            ) {
                itemsIndexed(
                    results,
                    key = { _, match -> match.entry.command.id },
                ) { index, match ->
                    CommandRow(
                        match = match,
                        isSelected = index == selected,
                        onClick = { run(match) },
                    )
                }
            }
        }
    }
}

/**
 * One command: the name on the left, its chord on the right, `justify_between`
 * with an extra `py_px` of breathing room inside the inset row
 * (command_palette.rs:635-654).
 */
@Composable
private fun CommandRow(
    match: CommandMatch,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val entry = match.entry

    PickerListItem(
        isSelected = isSelected,
        enabled = entry.isEnabled,
        onClick = onClick,
    ) {
        Text(
            text = highlighted(entry.name, match.positions, theme.color("text.accent")),
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry.isEnabled) {
                theme.color("text")
            } else {
                theme.color("text.disabled", MaterialTheme.colorScheme.onSurfaceVariant)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 1.dp),
        )
        // The alias sits between the name and the chord, in the chord's own
        // muted colour: it is another way of pressing the row, not a second
        // name for it.
        if (entry.alias != null) {
            Text(
                text = entry.alias,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text.muted"),
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (entry.shortcut != null) {
            KeyChips(entry.shortcut)
        }
    }
}

/**
 * A chord as Zed renders it: one chip per keystroke, 4px apart (`gap(Base04)`,
 * keybinding.rs:216), each chip `py_0p5` with `rounded_xs` corners — and no
 * fill, so the rounding is invisible — in `text.muted` (keybinding.rs:218-230).
 * Glyphs are the default 14px text size on a `relative(1.)` line; a
 * single-character key is boxed to a square of that size and centred, longer
 * ones get 2px of side padding (keybinding.rs:418-438). Our chord labels
 * separate keys with spaces, so that is the seam the split uses.
 */
@Composable
private fun KeyChips(shortcut: String) {
    val theme = LocalZedTheme.current
    val glyphStyle = MaterialTheme.typography.bodyMedium
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (key in shortcut.split(' ')) {
            if (key.isEmpty()) continue
            Box(
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .then(
                        if (key.length == 1) {
                            // `size × size`, glyph centred (keybinding.rs:427-429);
                            // the text size is `rems(0.875)` (typography.rs:139).
                            Modifier.width(rem(0.875f))
                        } else {
                            Modifier.padding(horizontal = 2.dp)
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = key,
                    style = glyphStyle.copy(lineHeight = glyphStyle.fontSize),
                    color = theme.color("text.muted"),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The name with matched characters recoloured to `text.accent` — a colour
 * change and nothing else, which is all Zed's `HighlightedLabel` does
 * (crates/ui/src/components/label/highlighted_label.rs:208-218).
 */
private fun highlighted(name: String, positions: List<Int>, color: Color): AnnotatedString {
    if (positions.isEmpty()) return AnnotatedString(name)
    val marked = positions.toHashSet()
    return buildAnnotatedString {
        name.forEachIndexed { index, character ->
            if (index in marked) {
                withStyle(SpanStyle(color = color)) { append(character) }
            } else {
                append(character)
            }
        }
    }
}
