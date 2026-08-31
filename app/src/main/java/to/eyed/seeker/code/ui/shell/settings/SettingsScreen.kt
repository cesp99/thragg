package to.eyed.seeker.code.ui.shell.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import to.eyed.seeker.code.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.core.AppSettings
import to.eyed.seeker.code.solana.toolchain.SolanaToolchain
import to.eyed.seeker.code.solana.toolchain.formatBytes
import to.eyed.seeker.code.core.Autosave
import to.eyed.seeker.code.core.FormatOnSave
import to.eyed.seeker.code.ui.editor.SoftWrapMode
import to.eyed.seeker.code.ui.shell.Destination
import to.eyed.seeker.code.ui.shell.Route
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.RowChevron
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.workspace.AboutDialog
import to.eyed.seeker.code.ui.workspace.Notifications

/**
 * Settings — one scrolling list, four sections, and one door to the JSON.
 *
 * This replaces ui/workspace/SettingsScreen.kt (1842 lines, a filter box, a
 * per-language matrix, an agent form, an MCP form and rows for six things
 * that no longer exist) with the rows docs/UI.md keeps and nothing else. The
 * deletions are not an omission and must not be re-added here: vim, the base
 * keymap, dock sides, chrome visibility, the minimap, inlay hints, the
 * project-panel sort/fold/spacing block, preview tabs and icon themes all
 * configure subsystems that P10 deletes. A row for a setting that changes
 * nothing is worse than no row.
 *
 * Every key that *is* still in the engine stays reachable, through the one
 * "Edit settings.json" row: the file is JSONC, the engine preserves its
 * comments through the writes this screen makes, and the person who wants
 * `lsp.rust-analyzer.initialization_options` opens it in Code and types it
 * (docs/UI.md, "Settings").
 *
 * Writes go one key at a time through [AppSettings.set], which is **blocking**
 * — it is a JNI hop that rewrites a file — so every one of them is on IO and
 * the resolved settings come back to [onSettingsChanged], which is what
 * repaints the theme.
 */
@Composable
fun SettingsScreen(
    state: ShellState,
    settings: AppSettings,
    /** The real settings.json, which the ADVANCED row opens in Code. */
    settingsPath: String?,
    onSettingsChanged: (AppSettings) -> Unit,
    modifier: Modifier = Modifier,
    /** Open the Cluster sheet — P6's, null until it lands. */
    onOpenCluster: (() -> Unit)? = null,
    /** Open the Wallet sheet — P6's, null until it lands. */
    onOpenWallet: (() -> Unit)? = null,
    /** Open the agent picker / install sheet — P3's, null until it lands. */
    onOpenAgentPicker: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var themeListOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }

    // What the toolchain is actually holding, rather than the doc's round
    // number: [SolanaToolchain.diskBytes] sums the *installed* components'
    // declared sizes, so a partial install reads as what it is. Blocking (one
    // small file plus a stat per component), re-asked whenever the flag moves,
    // and null until it answers so the row never invents a figure.
    var toolchainBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.toolchainReady) {
        toolchainBytes = withContext(Dispatchers.IO) {
            runCatching { SolanaToolchain.diskBytes(context) }.getOrNull()
        }?.takeIf { it > 0L }
    }

    /** One key, written off the main thread, with the refusal made visible. */
    fun write(key: String, valueJson: String) {
        scope.launch {
            val updated = withContext(Dispatchers.IO) { AppSettings.set(key, valueJson) }
            if (updated == null) {
                // A value the engine refused never reached the file and the
                // rest of the settings are untouched — but silence here is
                // what lets a toggle look like it worked.
                Notifications.error("The engine refused that setting", key = "settings")
            } else {
                onSettingsChanged(updated)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        SectionHeader("SOLANA")
        LinkRow(
            label = "Toolchain",
            detail = when {
                !state.toolchainReady -> "not installed"
                toolchainBytes != null -> "installed · ${formatBytes(toolchainBytes!!)}"
                else -> "installed"
            },
            onClick = { state.push(Route.Setup) },
        )
        LinkRow(
            label = "Cluster",
            detail = if (onOpenCluster == null) "not set up yet" else "devnet",
            enabled = onOpenCluster != null,
            onClick = { onOpenCluster?.invoke() },
        )
        LinkRow(
            label = "Wallet",
            detail = if (onOpenWallet == null) "not set up yet" else "Seed Vault",
            enabled = onOpenWallet != null,
            onClick = { onOpenWallet?.invoke() },
        )

        SectionHeader("AGENT")
        LinkRow(
            label = "Coding agent",
            // The agents in settings.json are the only agents there are — the
            // panel names none of its own (core/AppSettings.kt, `agents`).
            detail = settings.agents.firstOrNull()?.name ?: "none installed",
            enabled = onOpenAgentPicker != null,
            onClick = { onOpenAgentPicker?.invoke() },
        )
        LinkRow(
            label = "Install an agent",
            enabled = onOpenAgentPicker != null,
            onClick = { onOpenAgentPicker?.invoke() },
        )

        SectionHeader("EDITOR")
        LinkRow(
            label = "Theme",
            detail = settings.themeSelection.let { selection ->
                if (selection.isStatic) selection.light else "${selection.dark} / ${selection.light}"
            },
            onClick = { themeListOpen = true },
        )
        SliderRow(
            label = "Font size",
            value = settings.bufferFontSize,
            range = MIN_FONT_SIZE..MAX_FONT_SIZE,
            onValue = { size ->
                write(AppSettings.KEY_FONT_SIZE, size.toInt().toString())
            },
        )
        ToggleRow(
            label = "Wrap long lines",
            checked = settings.softWrap.wraps,
            onToggle = { on ->
                // `editor_width` and not `bounded`: bounded also wraps at
                // preferred_line_length, and an 80-column wrap on a 400dp
                // screen would leave a strip of empty gutter down the right.
                val mode = if (on) SoftWrapMode.EditorWidth else SoftWrapMode.None
                write(AppSettings.KEY_SOFT_WRAP, "\"${mode.key}\"")
            },
        )
        ToggleRow(
            label = "Format on save",
            checked = settings.formatOnSave != FormatOnSave.Off,
            onToggle = { on ->
                val value = if (on) FormatOnSave.On else FormatOnSave.Off
                write(AppSettings.KEY_FORMAT_ON_SAVE, "\"${value.key}\"")
            },
        )
        ToggleRow(
            label = "Autosave on leaving a file",
            detail = "A build reads the file on disk. 71 seconds is a long time to spend on a stale one.",
            checked = settings.autosave != Autosave.Off,
            onToggle = { on ->
                val value = if (on) Autosave.OnFocusChange else Autosave.Off
                write(AppSettings.KEY_AUTOSAVE, value.toJson())
            },
        )

        SectionHeader("ADVANCED")
        LinkRow(
            label = "Edit settings.json",
            detail = "every key, including the ones with no row",
            enabled = settingsPath != null && state.openPath != null,
            onClick = {
                val path = settingsPath ?: return@LinkRow
                val open = state.openPath ?: return@LinkRow
                state.show(Destination.Code)
                open(path)
            },
        )
        // Between the JSON door and About, which is where docs/LICENSING.md §5
        // puts it. Two taps from anywhere in the app to every notice in the
        // package — that reachability is the compliance requirement, not the
        // existence of the files.
        LinkRow(
            label = stringResource(R.string.licences_settings_row),
            detail = stringResource(R.string.licences_settings_detail),
            onClick = { state.push(Route.Licences) },
        )
        LinkRow(
            label = "About this device",
            detail = "engine version, ABI, page size",
            onClick = { aboutOpen = true },
        )
    }

    if (themeListOpen) {
        ThemeList(
            state = state,
            settings = settings,
            onDismiss = { themeListOpen = false },
            onSet = { key, json -> write(key, json) },
        )
    }
    if (aboutOpen) {
        // Kept whole from the inherited workspace: 226 lines that produce a
        // copyable bug report with the engine version, the ABI and the
        // kernel's page size. For a product whose premise rests on the page
        // size being 4 KB, that is cheap insurance (docs/UI.md, "Settings").
        AboutDialog(
            onDismiss = { aboutOpen = false },
            onOpenLicences = {
                // Close the dialog first: a route pushed under an open dialog
                // leaves the dialog on top of it, and the back gesture would
                // then close the dialog before popping the route.
                aboutOpen = false
                state.push(Route.Licences)
            },
        )
    }
}

/**
 * The engine's clamp on `buffer_font_size` is 6..48; these are the sizes a
 * 400dp column can actually hold a line of Rust at. Below 10 the gutter
 * numbers stop being legible, above 24 an `#[account(...)]` attribute does
 * not fit on two lines.
 */
private const val MIN_FONT_SIZE = 10f
private const val MAX_FONT_SIZE = 24f

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = LocalZedTheme.current.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.padding(start = RowPadding, end = RowPadding, top = 20.dp, bottom = 4.dp),
    )
}

/** A row that goes somewhere: a label, an optional readout, and a chevron. */
@Composable
private fun LinkRow(
    label: String,
    detail: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = RowHeight)
            .padding(horizontal = RowPadding),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                theme.color("text", MaterialTheme.colorScheme.onSurface)
            } else {
                theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
            },
            modifier = Modifier.weight(1f),
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.padding(end = 10.dp),
            )
        }
        RowChevron()
    }
}

/**
 * A row with a switch.
 *
 * Drawn rather than Material's `Switch` for the reason every control in this
 * app is drawn: Material's takes its colours from the M3 scheme, and this
 * app's colours come from a Zed theme file. Two boxes and a circle is the
 * whole of it, and the whole row is the target.
 */
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    detail: String? = null,
) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .heightIn(min = RowHeight)
            .padding(horizontal = RowPadding, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text", MaterialTheme.colorScheme.onSurface),
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
        Box(
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
            modifier = Modifier
                .size(width = SwitchWidth, height = SwitchHeight)
                .clip(RoundedCornerShape(SwitchHeight / 2))
                .background(
                    if (checked) {
                        theme.color("element.selected", MaterialTheme.colorScheme.primary)
                    } else {
                        theme.color("element.background", MaterialTheme.colorScheme.surfaceVariant)
                    }
                )
                .padding(3.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(SwitchHeight - 6.dp)
                    .clip(CircleShape)
                    .background(theme.color("text", MaterialTheme.colorScheme.onSurface)),
            )
        }
    }
}

/**
 * A row with a slider, for the one setting that is a number worth dragging.
 *
 * Material's `Slider` with the Zed theme's colours poured into it, rather
 * than a hand-drawn track: a slider is one of the few controls where the
 * platform's own touch handling — the press-anywhere-on-the-track jump, the
 * drag that keeps following a finger that has left the track vertically — is
 * worth more than matching Zed's chrome exactly.
 *
 * The *write* is on release, through `onValueChangeFinished`, and this is not
 * a nicety: [AppSettings.set] rewrites settings.json through the engine, and
 * writing the file on every frame of a drag would be sixty file rewrites a
 * second. What moves live is the local value, which is what the number on the
 * right reads.
 */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
) {
    val theme = LocalZedTheme.current
    // Null while the setting is what is showing; a number while a finger is
    // on it. Keyed on [value] so a change from anywhere else — a hand edit of
    // settings.json — is picked up rather than pinned by a stale drag.
    var dragging by remember(value) { mutableStateOf<Float?>(null) }
    val shown = dragging ?: value

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = RowPadding, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = shown.toInt().toString(),
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            valueRange = range,
            // Whole points only: `buffer_font_size` is written into a JSON
            // file that a person reads, and 14.372 is not a font size anyone
            // chose. The step count is the number of gaps, not of values.
            steps = (range.endInclusive - range.start).toInt() - 1,
            onValueChangeFinished = { dragging?.let { onValue(it) } },
            colors = SliderDefaults.colors(
                thumbColor = theme.color("text", MaterialTheme.colorScheme.onSurface),
                activeTrackColor = theme.color("element.selected", MaterialTheme.colorScheme.primary),
                inactiveTrackColor = theme.color(
                    "element.background",
                    MaterialTheme.colorScheme.surfaceVariant,
                ),
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = RowHeight),
        )
    }
}

private val RowHeight = 44.dp
private val RowPadding = 16.dp
private val SwitchWidth = 44.dp
private val SwitchHeight = 26.dp
