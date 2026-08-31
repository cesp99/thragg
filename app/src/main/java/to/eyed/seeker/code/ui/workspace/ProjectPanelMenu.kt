package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import to.eyed.seeker.code.core.ProjectWorktree

/** One line of the project panel's context menu. */
sealed interface PanelMenuEntry {
    /** A rule between groups; two in a row, or one at either end, collapse. */
    data object Separator : PanelMenuEntry

    data class Action(
        val label: String,
        /** Shown greyed on the right, as in the title bar's menu. */
        val shortcut: String? = null,
        val enabled: Boolean = true,
        val onClick: () -> Unit,
    ) : PanelMenuEntry
}

/**
 * The context menu, in Zed's order: create, then move things about, then the
 * destructive pair, then whole-tree commands.
 *
 * One menu serves all three ways in: right-click and long-press both open it
 * at the pointer, the menu key and `Shift F10` open it on the selected row.
 * That is deliberate — a menu that exists only under a right-click is missing
 * on a phone, and one that exists only under a long-press is missing on DeX,
 * where there is no touchscreen at all.
 */
@Composable
fun ProjectContextMenu(
    entries: List<PanelMenuEntry>,
    offset: DpOffset,
    onDismiss: () -> Unit,
) {
    // The same dress as every other context menu — ContextMenu.kt's
    // elevation-2 container and dense ripple-free rows — rather than stock
    // DropdownMenuItems, which bring their own ripple (material3's Menu.kt
    // passes `indication = ripple()` explicitly, so the theme's NoIndication
    // never reaches them) and Material's menu surface tokens.
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        offset = offset,
        shape = RoundedCornerShape(8.dp),
        // The same two inks as before under their Material names — the bridge
        // maps `surfaceContainer` from `elevated_surface.background` and
        // `outlineVariant` from `border.variant`. It matters that they come
        // through the scheme rather than raw: this menu opens over the Files
        // sheet, which is `surfaceContainer` too, and the ladder de-dupe is
        // what stops the two collapsing into one edgeless slab on the nine
        // bundled themes whose rungs 2 and 3 are the same hex.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.onPreviewKeyEvent { event ->
            // Escape closes it. Arrows and Enter are Compose's own focus
            // traversal, which the items are already wired for.
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onDismiss()
                true
            } else {
                false
            }
        },
    ) {
        Column(modifier = Modifier.widthIn(min = 220.dp).padding(horizontal = 4.dp)) {
            for (entry in entries.withoutStraySeparators()) {
                when (entry) {
                    is PanelMenuEntry.Separator ->
                        // Zed's ListSeparator: 1px `border.variant`, 6px above
                        // and below (list_separator.rs:9-12).
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )

                    is PanelMenuEntry.Action -> PanelMenuRow(entry, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun PanelMenuRow(entry: PanelMenuEntry.Action, onDismiss: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            // Hover and press are the indication's job now, not a fill this
            // row paints: `ghost_element.hover` has no M3 answer because it is
            // a state layer. The clip is what keeps that layer in the corners.
            .clip(RoundedCornerShape(4.dp))
            .then(
                if (entry.enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        // The long-press menu of a file row, opened from the
                        // Files sheet: a Material surface, so it takes the
                        // Material ripple. `indication = null` here gave a
                        // phone menu no press feedback at all.
                        .clickable(
                            interactionSource = interaction,
                            indication = LocalIndication.current,
                        ) {
                            onDismiss()
                            entry.onClick()
                        }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = entry.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry.enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
            },
            modifier = Modifier.weight(1f),
        )
        if (entry.shortcut != null) {
            Text(
                text = entry.shortcut,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Ask for a name — new file, new folder, rename.
 *
 * [selectionEnd] is how much of the name starts out selected: renaming
 * `main.rs` selects `main`, because the extension is almost never the part
 * being changed. Zed's inline rename does the same.
 */
@Composable
fun EntryNameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    /** How much of [initial] starts out selected, counted from the front. */
    selectionEnd: Int,
    placeholder: String,
    errorFor: (String) -> String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember {
        mutableStateOf(
            TextFieldValue(initial, TextRange(0, selectionEnd))
        )
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val error = value.text.takeIf { it.isNotBlank() }?.let(errorFor)
    val canConfirm = value.text.isNotBlank() && error == null

    PanelDialog(title = title, onDismiss = onDismiss) {
        PanelTextField(
            value = value,
            onValueChange = { value = it },
            placeholder = placeholder,
            focusRequester = focus,
            onSubmit = { if (canConfirm) onConfirm(value.text) },
            onEscape = onDismiss,
        )
        if (error != null) PanelMessage(error, isError = true)
        PanelActions {
            PanelTextAction("Cancel", onClick = onDismiss)
            PanelTextAction(confirmLabel, enabled = canConfirm) { onConfirm(value.text) }
        }
    }
}

/**
 * Confirm a delete, naming what is about to go.
 *
 * Two questions, not one — Zed's own pair: `project_panel::Trash` is the
 * Delete key and is undoable, `project_panel::Delete` is Shift+Delete and is
 * not (default-linux.json:996-998). The trash is the app-private one in
 * `core/crates/trash-android`, the same one git discard has always used, so
 * "moved to the trash" here means a real directory a real Undo can reach into.
 */
@Composable
fun ConfirmDeleteDialog(
    paths: List<String>,
    permanent: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val subject = when {
        paths.size == 1 -> "“${paths.first()}”"
        else -> "${paths.size} items"
    }
    PanelDialog(title = if (permanent) "DELETE" else "TRASH", onDismiss = onDismiss) {
        Text(
            text = if (permanent) {
                "Permanently delete $subject? This cannot be undone."
            } else {
                "Move $subject to the app's trash? You can undo this."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        PanelActions {
            PanelTextAction("Cancel", onClick = onDismiss)
            PanelTextAction(
                if (permanent) "Delete" else "Move to trash",
                isDestructive = true,
                onClick = onConfirm,
            )
        }
    }
}

/**
 * A drop that would replace something already in the destination.
 *
 * Zed's own drag-and-drop overwrites where it can; Android has no system-wide
 * undo to catch that, so the question is asked. The paste path never reaches
 * here — it takes a free name ("main copy.rs") instead, which is what a paste
 * should do.
 */
@Composable
fun ConfirmOverwriteDialog(
    names: List<String>,
    destination: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val where = destination.ifEmpty { "the project root" }
    PanelDialog(title = "REPLACE", onDismiss = onDismiss) {
        Text(
            text = if (names.size == 1) {
                "“${names.first()}” already exists in $where. Replace it?"
            } else {
                "${names.size} of these already exist in $where. Replace them?"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        PanelActions {
            PanelTextAction("Cancel", onClick = onDismiss)
            PanelTextAction("Replace", isDestructive = true, onClick = onConfirm)
        }
    }
}

/**
 * "Stop showing this folder" — Zed's `workspace::RemoveWorktreeFromProject`.
 *
 * Zed asks nothing, because its worktrees are folders that were already on
 * disk. Here a folder had to be *imported* into the app's storage to be
 * opened at all (docs/ARCHITECTURE.md, "Where projects live"), so the copy
 * outlives the project unless it is deleted separately — and saying so is the
 * only reason this dialog exists.
 */
@Composable
fun ConfirmRemoveFolderDialog(
    folder: ProjectWorktree,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    PanelDialog(title = "REMOVE FOLDER", onDismiss = onDismiss) {
        Text(
            text = "Stop showing \u201c${folder.name}\u201d in this project? " +
                "Nothing is deleted \u2014 the folder stays where it is and can be " +
                "added again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        PanelActions {
            PanelTextAction("Cancel", onClick = onDismiss)
            PanelTextAction("Remove", onClick = onConfirm)
        }
    }
}

/** An operation that didn't happen, and why. */
@Composable
fun PanelErrorDialog(message: String, onDismiss: () -> Unit) {
    PanelDialog(title = "PROJECT", onDismiss = onDismiss) {
        PanelMessage(message, isError = true)
        PanelActions {
            PanelTextAction("Close", onClick = onDismiss)
        }
    }
}

@Composable
internal fun PanelDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            // `surfaceContainerHigh` rather than `surfaceContainer`: M3's own
            // dialog rung, and one step above the Files sheet this is raised
            // over, so a rename prompt has an edge without a shadow.
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(min = 320.dp, max = 520.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Box(modifier = Modifier.padding(top = 16.dp)) {
                    Column { content() }
                }
            }
        }
    }
}

/**
 * The dialog's text field: `Enter` confirms, `Esc` backs out, and the pointer
 * becomes a caret over it rather than staying an arrow.
 */
@Composable
internal fun PanelTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
    onSubmit: () -> Unit,
    onEscape: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            // A field on the dialog's own ladder, not a patch of buffer:
            // `editor.background` under a dialog is the one place in the app
            // where the editor's canvas appears with no editor in it, and on
            // the Ayu family it is a different hue from the dialog around it.
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(6.dp),
            )
            .pointerHoverIcon(PointerIcon.Text)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> { onSubmit(); true }
                        Key.Escape -> { onEscape(); true }
                        else -> false
                    }
                },
        )
        if (value.text.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun PanelActions(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
    ) {
        Box(modifier = Modifier.weight(1f))
        content()
    }
}

@Composable
internal fun PanelMessage(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
internal fun PanelTextAction(
    label: String,
    enabled: Boolean = true,
    isDestructive: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
            isDestructive -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        },
        modifier = Modifier
            .then(
                if (enabled) {
                    Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(vertical = 4.dp),
    )
}

/**
 * Drop the separators that would render as a rule against nothing: the menu is
 * built by appending groups that each may turn out to be empty.
 */
private fun List<PanelMenuEntry>.withoutStraySeparators(): List<PanelMenuEntry> {
    val kept = mutableListOf<PanelMenuEntry>()
    for (entry in this) {
        if (entry is PanelMenuEntry.Separator &&
            (kept.isEmpty() || kept.last() is PanelMenuEntry.Separator)
        ) {
            continue
        }
        kept += entry
    }
    if (kept.lastOrNull() is PanelMenuEntry.Separator) kept.removeAt(kept.lastIndex)
    return kept
}
