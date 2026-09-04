package to.eyed.thragg.ui.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.eyed.thragg.ui.theme.LocalIconTheme
import to.eyed.thragg.ui.theme.rem

/** `IconSize::Medium` = `rems_from_px(16)` (icon.rs:75) — a rem, so it scales. */
internal const val ENTRY_ICON = 1f

/**
 * The panel's file icon. `Icon::from_path` asks for no size, so it gets the
 * 16px default (project_panel.rs:6247, icon.rs:61-63, 75) — and that default
 * is `rems_from_px(16)`, a multiple of `ui_font_size` rather than a constant,
 * so the icons grow with the rest of the chrome.
 */
private val EntryIconSize: Dp
    @Composable @ReadOnlyComposable get() = rem(ENTRY_ICON)

/**
 * The slot the icon sits in. The same rem: Zed's alignment spacer for a row
 * with no icon is `IconSize::default().rems()` (project_panel.rs:6253-6259),
 * so every row's name starts at the same column whatever its icon is.
 */
val EntryIconWidth: Dp
    @Composable @ReadOnlyComposable get() = rem(ENTRY_ICON)

/**
 * The icon in front of a row: Zed's own, for the language the file is in.
 *
 * These were hand-drawn marks for a while — a filled folder and an outlined
 * page, with the *type* carried in colour — on the reasoning that Zed's icon
 * theme is a couple of hundred SVGs and Android cannot render an SVG anyway.
 * Both halves of that turned out to be wrong in the way that matters: the set
 * a file tree actually reaches is 79 icons, they convert to VectorDrawables
 * cleanly (`tools/import-zed-icons.py`), and they cost 348 KB — which buys
 * the thing the panel is *for*, telling files apart at a glance.
 *
 * Monochrome, as Zed draws them: the icon says what kind of file it is and
 * the row's colour says what git thinks of it. Two colour languages in one
 * row would be one too many.
 */
@Composable
fun EntryIconMark(
    name: String,
    isDir: Boolean,
    isExpanded: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val iconTheme = LocalIconTheme.current
    val reference = FileIcons.referenceFor(iconTheme, name, isDir, isExpanded)
    Image(
        painter = FileIcons.painterFor(reference),
        // Named for the reader, not for a screen reader: the row's own text is
        // the file's name, and an icon that repeated it would be read twice.
        contentDescription = null,
        // A user icon theme's own art keeps its colours; the bundled set is
        // monochrome and takes the row's, as Zed draws it.
        colorFilter = if (FileIcons.tintable(reference)) ColorFilter.tint(color) else null,
        modifier = modifier.size(EntryIconSize),
    )
}
