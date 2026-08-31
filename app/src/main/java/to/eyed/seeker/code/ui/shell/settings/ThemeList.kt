package to.eyed.seeker.code.ui.shell.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.core.AppSettings
import to.eyed.seeker.code.core.ThemeMode
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.ZedTheme
import to.eyed.seeker.code.ui.theme.ZedThemes

/**
 * Theme — a plain list of the themes that are installed, and the three words
 * that decide which half of it is in use.
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
 * The mode row above the list is what makes the two names mean anything:
 * Zed's `theme` is an object of `{ mode, light, dark }`, and picking a dark
 * theme fills the dark slot while leaving the light one alone
 * (`ThemeSelection.with`), so "follow the system" keeps working once both
 * halves have been chosen.
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
    val theme = LocalZedTheme.current
    val selection = settings.themeSelection

    // Listing the installed themes reads the APK's asset directory and the
    // user's themes folder, and indexes each file's family — blocking, and
    // therefore off the main thread with the sheet drawn before it lands.
    val installed by produceState(emptyList<ZedTheme.Meta>()) {
        value = withContext(Dispatchers.IO) { ZedThemes.installed(context) }
    }

    /** Which name is in force for [meta]'s appearance right now. */
    fun isSelected(meta: ZedTheme.Meta): Boolean =
        if (meta.isDark) selection.dark == meta.name else selection.light == meta.name

    SheetScaffold(state = state, onDismiss = onDismiss, title = "THEME") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = SheetPadding, vertical = 4.dp),
        ) {
            for (mode in ThemeMode.entries) {
                ModeChip(
                    label = mode.label,
                    isSelected = !selection.isStatic && selection.mode == mode,
                    onClick = {
                        onSet(AppSettings.KEY_THEME, selection.withMode(mode).toJson())
                    },
                )
            }
        }
        Text(
            text = "Dark themes fill the dark slot and light ones the light slot, " +
                "so both are ready when the system switches.",
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.padding(horizontal = SheetPadding, vertical = 6.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(installed, key = { it.name }) { meta ->
                ThemeRow(
                    meta = meta,
                    isSelected = isSelected(meta),
                    onClick = {
                        onSet(
                            AppSettings.KEY_THEME,
                            selection.with(meta.name, meta.isDark).toJson(),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (isSelected) {
            theme.color("text", MaterialTheme.colorScheme.onSurface)
        } else {
            theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
        },
        modifier = Modifier
            .background(
                if (isSelected) {
                    theme.color("element.selected", MaterialTheme.colorScheme.surfaceVariant)
                } else {
                    theme.color("element.background", MaterialTheme.colorScheme.surface)
                },
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun ThemeRow(meta: ZedTheme.Meta, isSelected: Boolean, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = RowHeight)
            .padding(horizontal = SheetPadding, vertical = 4.dp),
    ) {
        Text(
            text = if (isSelected) "●" else " ",
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
            modifier = Modifier.padding(end = 10.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = meta.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // The family, because eleven themes across four families is a
                // list where "One Dark" and "One Light" are the same decision
                // twice and the family is what tells them apart.
                text = "${meta.family} · ${if (meta.isDark) "dark" else "light"}",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                maxLines = 1,
            )
        }
        Box(modifier = Modifier.padding(start = 8.dp))
    }
}

/** Zed's three words for `theme.mode`, as a settings row says them. */
private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.System -> "Follow system"
        ThemeMode.Light -> "Light"
        ThemeMode.Dark -> "Dark"
    }

private val RowHeight = 44.dp
private val SheetPadding = 16.dp
