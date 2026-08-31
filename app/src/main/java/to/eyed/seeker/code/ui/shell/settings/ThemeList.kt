package to.eyed.seeker.code.ui.shell.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.AppSettings
import to.eyed.seeker.code.core.ThemeMode
import to.eyed.seeker.code.core.ThemeSelection
import to.eyed.seeker.code.ui.components.Choice
import to.eyed.seeker.code.ui.components.HairlineDivider
import to.eyed.seeker.code.ui.components.SectionHeader
import to.eyed.seeker.code.ui.components.SeekerCard
import to.eyed.seeker.code.ui.components.SeekerSearchField
import to.eyed.seeker.code.ui.components.SegmentedSelect
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.IconSize
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.SeekerIcon
import to.eyed.seeker.code.ui.theme.ZedTheme
import to.eyed.seeker.code.ui.theme.ZedThemes

/**
 * Theme — the themes that are installed, each showing what it actually looks
 * like, and the three words that decide which half of the list is in use.
 *
 * Deliberately *not* ui/workspace/ThemeSelector.kt, which is deleted in P10:
 * that one previews each theme on the real window as the cursor walks the
 * list (Zed's `theme_selector.rs:227-256`), which is the right design for a
 * keyboard and a 27-inch display and the wrong one for a list you scroll with
 * a thumb — there is no "cursor" on a touch list, so a preview would fire on
 * every fling. Here a tap applies, and the sheet stays open so the next tap
 * corrects it (docs/UI.md, "Settings": no live-preview carousel, no user
 * themes folder, no Import theme, no icon themes, no font family picker).
 *
 * THE SWATCH STRIP IS THE POINT OF THE MATERIAL PASS ON THIS SCREEN
 * (docs/VISUAL.md, "Settings"). Every row draws three discs — that theme's own
 * accent, canvas and raised surface — read from the theme file itself. It is a
 * few lines and it is the best possible advert for the bridge: those three
 * colours are exactly what `ZedTheme.palette()` turns into `primary`,
 * `background` and `surfaceContainer`, so the strip is a true preview of what
 * the whole app will be a tap later, not an illustration of one.
 *
 * The mode row above the list is what makes the two names mean anything:
 * Zed's `theme` is an object of `{ mode, light, dark }`, and picking a dark
 * theme fills the dark slot while leaving the light one alone
 * (`ThemeSelection.with`), so "follow the system" keeps working once both
 * halves have been chosen. It is a [SegmentedSelect] because three flat
 * choices side by side is precisely what that component is for.
 */
@Composable
fun ThemeList(
    state: ShellState,
    settings: AppSettings,
    onDismiss: () -> Unit,
    /** Write one key — the settings screen's own [AppSettings.set] wrapper. */
    onSet: (keyPath: String, valueJson: String) -> Unit,
) {
    val context = LocalContext.current
    val selection = settings.themeSelection

    // Listing the installed themes reads the APK's asset directory and the
    // user's themes folder, and indexes each file's family. Parsing each of
    // them for its swatch is the same order of work again — both are blocking,
    // both happen here, off the main thread, with the sheet drawn before they
    // land. Eleven themes parse in the time the sheet's own open animation
    // takes, and `ZedThemes` caches them for the walk the user is about to do.
    val installed by produceState(emptyList<ThemeEntry>()) {
        value = withContext(Dispatchers.IO) {
            ZedThemes.installed(context).map { meta ->
                val theme = runCatching { ZedThemes.get(context, meta.name, meta.isDark) }
                    .getOrNull()
                ThemeEntry(
                    meta = meta,
                    accent = theme?.color("text.accent", Color.Transparent)
                        ?: Color.Transparent,
                    canvas = theme?.color("editor.background", Color.Transparent)
                        ?: Color.Transparent,
                    raised = theme?.color("elevated_surface.background", Color.Transparent)
                        ?: Color.Transparent,
                )
            }
        }
    }

    var query by remember { mutableStateOf("") }
    val matches = remember(installed, query) {
        val needle = query.trim().lowercase(Locale.getDefault())
        if (needle.isEmpty()) {
            installed
        } else {
            installed.filter { entry ->
                entry.meta.name.lowercase(Locale.getDefault()).contains(needle) ||
                    entry.meta.family.lowercase(Locale.getDefault()).contains(needle)
            }
        }
    }
    val dark = matches.filter { it.meta.isDark }
    val light = matches.filterNot { it.meta.isDark }

    /** Which name is in force for [meta]'s appearance right now. */
    fun isSelected(meta: ZedTheme.Meta): Boolean =
        if (meta.isDark) selection.dark == meta.name else selection.light == meta.name

    val modes = ThemeMode.entries.map { Choice(value = it.name, name = it.label) }

    SheetScaffold(state = state, onDismiss = onDismiss, title = "Theme") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MD.space4),
            verticalArrangement = Arrangement.spacedBy(MD.space2),
        ) {
            SegmentedSelect(
                options = modes,
                // Null while the selection is Zed's bare-name form: no
                // segment is active, because the theme file's own appearance
                // is what decides, and lighting one of the three would claim a
                // mode that is not in settings.json.
                selectedValue = selection.mode?.name,
                onSelect = { value ->
                    val mode = ThemeMode.entries.first { it.name == value }
                    onSet(AppSettings.KEY_THEME, selection.withMode(mode).toJson())
                },
                showActiveDescription = false,
            )
            Text(
                text = "Dark themes fill the dark slot and light ones the light slot, " +
                    "so both are ready when the system switches.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SeekerSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Filter themes…",
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(
                start = MD.space4,
                end = MD.space4,
                top = MD.space3,
                bottom = MD.space6,
            ),
            verticalArrangement = Arrangement.spacedBy(MD.space2),
        ) {
            if (matches.isEmpty()) {
                item(key = "no-matches") {
                    Text(
                        text = "Nothing matches “$query”.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = MD.space6),
                    )
                }
            }
            // Grouped by appearance rather than labelled per row: the split is
            // what the two slots above are about, and eleven themes across
            // four families is a list where "One Dark" and "One Light" are the
            // same decision twice.
            themeGroup(
                key = "dark",
                title = "Dark",
                entries = dark,
                isSelected = ::isSelected,
                onSet = onSet,
                selection = selection,
            )
            themeGroup(
                key = "light",
                title = "Light",
                entries = light,
                isSelected = ::isSelected,
                onSet = onSet,
                selection = selection,
            )
        }
    }
}

/** One appearance's themes: a header and one card of rows. */
private fun LazyListScope.themeGroup(
    key: String,
    title: String,
    entries: List<ThemeEntry>,
    isSelected: (ZedTheme.Meta) -> Boolean,
    onSet: (String, String) -> Unit,
    selection: ThemeSelection,
) {
    if (entries.isEmpty()) return
    item(key = "$key-header") { SectionHeader(title) }
    item(key = key) {
        SeekerCard(modifier = Modifier.fillMaxWidth()) {
            entries.forEachIndexed { index, entry ->
                if (index > 0) HairlineDivider()
                ThemeRow(
                    entry = entry,
                    isSelected = isSelected(entry.meta),
                    onClick = {
                        onSet(
                            AppSettings.KEY_THEME,
                            selection.with(entry.meta.name, entry.meta.isDark).toJson(),
                        )
                    },
                )
            }
        }
    }
}

/** A theme's identity plus the three colours a row shows it by. */
@Immutable
private data class ThemeEntry(
    val meta: ZedTheme.Meta,
    val accent: Color,
    val canvas: Color,
    val raised: Color,
)

/**
 * One theme: its swatch, its name, its family, and a check when it is the one
 * in force for its appearance.
 *
 * A check on the selected row rather than a radio on every one, which is the
 * rule [to.eyed.seeker.code.ui.components.DrillRow] sets for long lists: a
 * column of empty circles is one bit of information spread over eleven marks,
 * and the row's `selectable` semantics still announce "selected" for a screen
 * reader. This is not `DrillRow` itself only because of the swatch, which has
 * to sit inside the row's own layout.
 */
@Composable
private fun ThemeRow(entry: ThemeEntry, isSelected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space3),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = MD.rowMin)
            .padding(horizontal = MD.space3, vertical = MD.space2),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(MD.space1)) {
            Swatch(entry.canvas)
            Swatch(entry.raised)
            Swatch(entry.accent)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.meta.name,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.meta.family,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(top = MD.space05),
            )
        }
        if (isSelected) {
            SeekerIcon(
                icon = R.drawable.ic_ui_check,
                contentDescription = null,
                tint = scheme.primary,
                size = IconSize.Inline,
            )
        }
    }
}

/**
 * One disc of a theme's own colour.
 *
 * The hairline round it is not decoration: a light theme's canvas is very
 * nearly this sheet's own ground, and without an edge that disc would simply
 * not exist. The border is the Material half's `outlineVariant` rather than
 * the theme's, because the edge belongs to the row it is drawn on — the same
 * rule the Zed island's border follows (docs/VISUAL.md, THE SEAM).
 */
@Composable
private fun Swatch(color: Color) {
    Box(
        modifier = Modifier
            .size(IconSize.Marker)
            .background(color, CircleShape)
            .border(MD.hairline, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

/** Zed's three words for `theme.mode`, as a settings row says them. */
private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.System -> "System"
        ThemeMode.Light -> "Light"
        ThemeMode.Dark -> "Dark"
    }
