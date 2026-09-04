package to.eyed.thragg.ui.shell.code

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import to.eyed.thragg.R
import to.eyed.thragg.ui.shell.Route
import to.eyed.thragg.ui.shell.SheetScaffold
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.SeekerIcon
import to.eyed.thragg.ui.theme.accentIcon
import to.eyed.thragg.ui.theme.touchTarget
import to.eyed.thragg.ui.workspace.OpenFile

/**
 * The ⋮ sheet — everything Code can do that is not worth a permanent control.
 *
 * **Capped at seven rows** (docs/UI.md, "Code — the editor"), and the cap is
 * the design: this is the drain the old command palette emptied into, and a
 * drain with no cap is a palette with a different name. What replaced the
 * palette is not this sheet — it is the rule that every surviving capability
 * has a touch target *where it is used*: the file bar switches files, the
 * action row edits, the hover card navigates, the gutter's ✕ fixes. What is
 * left over is seven things you do a few times a session, and they are here.
 *
 * Three of the seven push a full-screen route rather than doing anything
 * themselves, so they close the sheet on the way out — a route drawn under a
 * sheet is a route nobody can read.
 *
 * EVERY ROW HAS A GLYPH, as the Projects sheet's tool rows do. Seven lines of
 * text at the same size and the same ink are read by reading them; seven
 * glyphs are found by looking, and a sheet you open a few times a session is
 * one you scan rather than read. The rows that push a route share their
 * glyph with the destination they push — Problems' warning, Changes' commit
 * — so the row and the screen say the same thing.
 */
@Composable
fun CodeOverflowSheet(
    shell: ShellState,
    file: OpenFile?,
    onSave: () -> Unit,
    onGoToSymbol: () -> Unit,
    onGoToLine: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val hasBuffer = file?.editor != null
    SheetScaffold(
        state = shell,
        onDismiss = onDismiss,
        title = file?.name ?: "Code",
    ) {
        OverflowRow("Save", R.drawable.ic_ui_save, enabled = hasBuffer && file?.isDirty == true) {
            onSave()
            onDismiss()
        }
        OverflowRow("Go to symbol", R.drawable.ic_ui_braces, enabled = hasBuffer) {
            // Dismissed first: both pickers are dialogs that preview into the
            // buffer as you browse, and a 65% sheet over it would cover the
            // very lines being previewed.
            onDismiss()
            onGoToSymbol()
        }
        OverflowRow("Go to line", R.drawable.ic_ui_hash, enabled = hasBuffer) {
            onDismiss()
            onGoToLine()
        }
        OverflowRow("Problems", R.drawable.ic_ui_warning) {
            onDismiss()
            shell.push(Route.Problems)
        }
        OverflowRow("Changes", R.drawable.ic_ui_git_commit) {
            onDismiss()
            shell.push(Route.Changes)
        }
        OverflowRow("Share file…", R.drawable.ic_ui_share, enabled = file?.absolutePath != null) {
            // The one caller of ShareOut on this side of the app: without it
            // there is no way to get a file *out* of a sandboxed IDE at all.
            file?.let { shareFile(context, it) }
            onDismiss()
        }
        OverflowRow("Settings", R.drawable.ic_file_settings) {
            onDismiss()
            shell.push(Route.Settings)
        }
    }
}

@Composable
private fun OverflowRow(
    label: String,
    @DrawableRes icon: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // 38% is Material's disabled-content alpha, and a disabled row drawn in
    // `onSurfaceVariant` — which is what `text.disabled` resolved to here —
    // is indistinguishable from an enabled secondary one. The glyph dims with
    // the label so the two read as one disabled thing, not a live icon beside
    // a dead word.
    val alpha = if (enabled) 1f else 0.38f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space3),
        modifier = Modifier
            .fillMaxWidth()
            .touchTarget()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = MD.space4, vertical = MD.space3),
    ) {
        SeekerIcon(
            icon = icon,
            // Decoration: the label beside it is the row's name.
            contentDescription = null,
            tint = accentIcon.copy(alpha = alpha),
            size = IconSize.Inline,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            // Every row in every sheet wraps rather than truncating at 1.3x
            // font scale; only paths and base58 addresses may be cut, and this
            // is neither (docs/UI.md, "Orientation" — text scale).
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
