package to.eyed.seeker.code.ui.shell.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.AgentSessions
import to.eyed.seeker.code.core.AppSettings
import to.eyed.seeker.code.core.Autosave
import to.eyed.seeker.code.core.FormatOnSave
import to.eyed.seeker.code.core.SpettroSetup
import to.eyed.seeker.code.solana.toolchain.SolanaToolchain
import to.eyed.seeker.code.solana.toolchain.formatBytes
import to.eyed.seeker.code.ui.components.HairlineDivider
import to.eyed.seeker.code.ui.components.SectionHeader
import to.eyed.seeker.code.ui.components.SeekerCard
import to.eyed.seeker.code.ui.editor.SoftWrapMode
import to.eyed.seeker.code.ui.shell.Destination
import to.eyed.seeker.code.ui.shell.Route
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.RowChevron
import to.eyed.seeker.code.ui.theme.TabularNums
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
 *
 * THE MATERIAL PASS (docs/VISUAL.md, "Settings") changed three things and no
 * behaviour. Each section is a [SeekerCard] group with a [HairlineDivider]
 * between rows, under the shared [SectionHeader] — this file's own private
 * copy of that header, one of three in the app, is gone. The booleans are a
 * real `Switch`: the note that used to be here said Material's takes its
 * colours from the M3 scheme "and this app's colours come from a Zed theme
 * file", which was true until the bridge made the M3 scheme *be* the Zed
 * theme, and two boxes and a circle never had the drag gesture, the state
 * description or the disabled treatment. [SliderRow] keeps its write-on-
 * release rule exactly and simply loses its three colour overrides.
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

    // The account row below reads the cache the agent pushes; re-ask once
    // when the screen opens so a sign-in that happened in the terminal is not
    // shown stale. Only with an agent process up to answer — the same
    // `projectId < 0` line callExtension itself refuses on — because with no
    // agent the call can only come back Offline and the cached value, null
    // included, is already the truth this device has.
    LaunchedEffect(Unit) {
        if (AgentSessions.projectId >= 0) SpettroSetup.refreshAccount()
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
            .padding(horizontal = MD.space4)
            // 24dp so the last row clears the nav bar rather than sitting
            // under it (docs/VISUAL.md, "Foundations", RHYTHM).
            .padding(bottom = MD.space6),
        verticalArrangement = Arrangement.spacedBy(MD.space2),
    ) {
        SectionHeader("Solana", modifier = Modifier.padding(top = MD.space4))
        SeekerCard(modifier = Modifier.fillMaxWidth()) {
            LinkRow(
                label = "Toolchain",
                detail = when {
                    !state.toolchainReady -> "not installed"
                    toolchainBytes != null -> "installed · ${formatBytes(toolchainBytes!!)}"
                    else -> "installed"
                },
                onClick = { state.push(Route.Setup) },
            )
            HairlineDivider()
            // "not set up yet" was the wrong sentence under a dead chevron:
            // it names a job the user could do and then will not let them do
            // it. Nothing on this device is unset — the sheet these rows open
            // is not in the build. The row says that and drops the chevron.
            LinkRow(
                label = "Cluster",
                detail = if (onOpenCluster == null) "not in this build yet" else "devnet",
                onClick = onOpenCluster,
            )
            HairlineDivider()
            LinkRow(
                label = "Wallet",
                detail = if (onOpenWallet == null) "not in this build yet" else "Seed Vault",
                onClick = onOpenWallet,
            )
        }

        SectionHeader("Agent", modifier = Modifier.padding(top = MD.space4))
        SeekerCard(modifier = Modifier.fillMaxWidth()) {
            // ONE ROW, NOT TWO. "Install an agent" used to sit under this
            // one carrying the SAME onClick — two rows, one action, and while
            // the picker is null neither could perform it. A permanently grey
            // row whose whole text is a verb is the worst case of the dead
            // affordance: it has no readout to fall back on, so with the
            // chevron gone there would be nothing left of it but a promise.
            // This row already says what is installed and is the route to the
            // picker the day it lands, so the second row was only ever a
            // second door into the same room.
            LinkRow(
                label = "Coding agent",
                // The agents in settings.json are the only agents there are —
                // the panel names none of its own (core/AppSettings.kt,
                // `agents`).
                detail = if (settings.agents.isNotEmpty()) {
                    settings.agents.first().name
                } else {
                    "none installed"
                },
                // The last row that ran a readout and a sentence together in
                // the trailing column, and it clipped for exactly the reason
                // the Advanced three did: `none installed · no installer in
                // this build yet` is 46 characters against [DetailMax]'s
                // 168dp, so what reached the screen was `none installed · no
                // in…` — the readout survived and the half that explained
                // the missing chevron did not. The readout stays right, the
                // precondition goes under the label where a sentence has
                // room to be read.
                description = "no installer in this build yet"
                    .takeIf { settings.agents.isEmpty() && onOpenAgentPicker == null },
                onClick = onOpenAgentPicker,
            )
            HairlineDivider()
            // Named for the STATE, not the destination: the email is proof of
            // which account this phone is on, and "Sign in to Spettro" is the
            // verb while there is no account to name. Both readings come from
            // the pure choosers below so a JVM test can pin them
            // (SpettroAccountRowTest). The plan is a sentence-slot description
            // rather than a trailing readout for the reason on [LinkRow]:
            // "Pro · active" beside an email would fight it for the row.
            val account = SpettroSetup.account
            LinkRow(
                label = spettroAccountLabel(account?.signedIn == true, account?.email),
                description = spettroAccountDescription(account?.signedIn == true, account?.plan),
                onClick = { state.push(Route.SpettroSettings) },
            )
        }

        SectionHeader("Editor", modifier = Modifier.padding(top = MD.space4))
        SeekerCard(modifier = Modifier.fillMaxWidth()) {
            LinkRow(
                label = "Theme",
                detail = settings.themeSelection.let { selection ->
                    if (selection.isStatic) selection.light else "${selection.dark} / ${selection.light}"
                },
                onClick = { themeListOpen = true },
            )
            HairlineDivider()
            SliderRow(
                label = "Font size",
                value = settings.bufferFontSize,
                range = MIN_FONT_SIZE..MAX_FONT_SIZE,
                onValue = { size ->
                    write(AppSettings.KEY_FONT_SIZE, size.toInt().toString())
                },
            )
            HairlineDivider()
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
            HairlineDivider()
            ToggleRow(
                label = "Format on save",
                checked = settings.formatOnSave != FormatOnSave.Off,
                onToggle = { on ->
                    val value = if (on) FormatOnSave.On else FormatOnSave.Off
                    write(AppSettings.KEY_FORMAT_ON_SAVE, "\"${value.key}\"")
                },
            )
            HairlineDivider()
            ToggleRow(
                label = "Autosave on leaving a file",
                detail = "A build reads the file on disk. 71 seconds is a long time to spend on a stale one.",
                checked = settings.autosave != Autosave.Off,
                onToggle = { on ->
                    val value = if (on) Autosave.OnFocusChange else Autosave.Off
                    write(AppSettings.KEY_AUTOSAVE, value.toJson())
                },
            )
        }

        SectionHeader("Advanced", modifier = Modifier.padding(top = MD.space4))
        SeekerCard(modifier = Modifier.fillMaxWidth()) {
            // Null rather than disabled, for the reason on [LinkRow]: with no
            // settings.json on disk and no editor registered to open it there
            // is nothing behind the chevron, so there is no chevron.
            val openInEditor = state.openPath
            LinkRow(
                label = "Edit settings.json",
                description = "every key, including the ones with no row",
                onClick = if (settingsPath != null && openInEditor != null) {
                    {
                        state.show(Destination.Code)
                        openInEditor(settingsPath)
                    }
                } else {
                    null
                },
            )
            HairlineDivider()
            // Between the JSON door and About, which is where
            // docs/LICENSING.md §5 puts it. Two taps from anywhere in the app
            // to every notice in the package — that reachability is the
            // compliance requirement, not the existence of the files.
            LinkRow(
                label = stringResource(R.string.licences_settings_row),
                description = stringResource(R.string.licences_settings_detail),
                onClick = { state.push(Route.Licences) },
            )
            HairlineDivider()
            LinkRow(
                label = "About this device",
                description = "engine version, ABI, page size",
                onClick = { aboutOpen = true },
            )
        }
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

/**
 * What the Spettro account row prints as its label. Pure and separate from
 * the composable so a JVM test can hold all three readings still — an email
 * only proves which account when there is one, and a signed-in answer with no
 * email (the backend omits it while the profile is still syncing) must not
 * print a blank row.
 */
internal fun spettroAccountLabel(signedIn: Boolean, email: String?): String = when {
    !signedIn -> "Sign in to Spettro"
    email != null -> email
    else -> "Signed in"
}

/**
 * The sentence under the label: the plan, and only for a signed-in account —
 * a leftover plan string from before a sign-out is not a fact about this row.
 */
internal fun spettroAccountDescription(signedIn: Boolean, plan: String?): String? =
    plan?.takeIf { signedIn }

/**
 * A row that goes somewhere: a label, an optional readout or description, and
 * a chevron.
 *
 * TWO SLOTS, BECAUSE THEY ARE TWO DIFFERENT THINGS, and conflating them is
 * what put "every key, including the ones w…" on screen. [detail] is a
 * READOUT — `devnet`, `installed · 41 MB`, the current theme — a value the
 * row's own control decides, which belongs on the right where the eye scans a
 * column of values and which is capped at [DetailMax] so it cannot squeeze the
 * label. [description] is a SENTENCE about where the row goes, and a sentence
 * has no business in a 168 dp trailing column: cut there it loses the half
 * that carried the meaning, so all three Advanced rows were paying a line's
 * ink to say nothing. It goes under the label instead, at 2 dp, exactly as
 * [ToggleRow] has always drawn its own (docs/VISUAL.md, "Foundations",
 * RHYTHM: 2dp between a label and its description).
 *
 * NO SUCH THING AS A DISABLED LINK ROW. [onClick] is nullable and null draws a
 * STATEMENT — the label, the readout, no chevron and no click target — because
 * the alternative, which this row used to draw, is a lie with an arrow on it.
 * A greyed "Wallet ›" names a destination, promises it is one tap away, and
 * then refuses the tap with no reason given; the user's only reading is that
 * they have done something wrong. A row with no chevron is not a control that
 * failed, it is a line of information, so it keeps its ink at full strength
 * and lets [detail] carry the precondition ("not in this build yet"). The
 * 38 % disabled alpha that used to be here is Material's answer for a control
 * that will become live when the FORM around it is valid — a Create button
 * beside an empty name field — and none of these rows is that.
 */
@Composable
private fun LinkRow(
    label: String,
    detail: String? = null,
    description: String? = null,
    /** Where the row goes; null when there is nowhere for it to go. */
    onClick: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space3),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = MD.rowMin)
            .padding(horizontal = MD.space3, vertical = MD.space2),
    ) {
        // The label is the only WEIGHTED child, so it absorbs the slack and
        // pushes the readout and the chevron to the row's edge. It used to
        // share a weight with the readout, and two weights split the row in
        // half — which parked the chevron in the middle of the card on
        // every row whose readout was short, while an icon-bearing sibling
        // put its own on the edge.
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    // Two lines because the widest of these needs one and a
                    // half at 12sp, and a description that ellipsises is the
                    // thing this slot exists to stop.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = MD.space05),
                )
            }
        }
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Unweighted, so it measures first — capped, so that measuring
                // first cannot squeeze the label. An uncapped readout like
                // "installed · 412 MB / One Dark / Solarized Light" takes its
                // whole intrinsic width and leaves the part that says what the
                // row *is* with nothing; [DetailMax] is the share of a 400dp
                // row a readout may have before it is the one that ellipsises.
                // A readout is short by nature, which is why the cap is a
                // safety net here and was a gag on a sentence.
                modifier = Modifier.widthIn(max = DetailMax),
            )
        }
        // The chevron IS the affordance. Drawn only when there is something
        // behind it, so its absence is the row saying so.
        if (onClick != null) RowChevron()
    }
}

/**
 * A row with a switch.
 *
 * A real `Switch` now. The row carries the `toggleable` semantics and the
 * switch is handed `onCheckedChange = null`, which is Material's own idiom for
 * "the control is the mark, the row is the target": one node, announced once,
 * with the whole 400dp width to hit.
 */
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    detail: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space3),
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onToggle,
            )
            .heightIn(min = MD.rowMin)
            .padding(horizontal = MD.space3, vertical = MD.space2),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MD.space05),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * A row with a slider, for the one setting that is a number worth dragging.
 *
 * Material's `Slider`, now with Material's own colours — the three overrides
 * this row used to pour into it were Zed reads whose M3 fallback never fired,
 * and `SliderDefaults.colors()` gives it the accent for free because the
 * accent *is* the theme's (docs/VISUAL.md, "Settings"). A slider is one of the
 * few controls where the platform's own touch handling — the
 * press-anywhere-on-the-track jump, the drag that keeps following a finger
 * that has left the track vertically — is worth more than matching Zed's
 * chrome exactly.
 *
 * The *write* is on release, through `onValueChangeFinished`, and this is not
 * a nicety: [AppSettings.set] rewrites settings.json through the engine, and
 * writing the file on every frame of a drag would be sixty file rewrites a
 * second. What moves live is the local value, which is what the number on the
 * right reads — tabular, because it ticks under a finger.
 */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
) {
    // Null while the setting is what is showing; a number while a finger is
    // on it. Keyed on [value] so a change from anywhere else — a hand edit of
    // settings.json — is picked up rather than pinned by a stale drag.
    var dragging by remember(value) { mutableStateOf<Float?>(null) }
    val shown = dragging ?: value

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MD.space3, vertical = MD.space2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = shown.toInt().toString(),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFeatureSettings = TabularNums,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            modifier = Modifier.fillMaxWidth(),
            // THREE ROLES SPELLED OUT, and all three for one reason:
            // material3 1.4.0's slider defaults are written against a stock M3
            // palette, where `secondaryContainer` is a pale lavender well away
            // from `primary`. Under this scheme it is Zed's `element.selected`
            // (MaterialBridge.kt, band A) — a fill a step BEYOND the top of the
            // surface ladder, further from the canvas than
            // `surfaceContainerHighest` on all eleven bundled themes
            // (MaterialBridgeTest, "secondaryContainer is a sixth fill rung").
            // So:
            //
            //  - `inactiveTrackColor` is `SliderTokens.InactiveTrackColor` =
            //    secondaryContainer, which drew the unspent half of the track
            //    as the brightest panel on a Settings page — a raised surface
            //    where the design wanted the quietest rung;
            //  - `defaultSliderColors` (Slider.kt:1169-1171) then sets
            //    `activeTickColor = InactiveTrackColor` and
            //    `inactiveTickColor = ActiveTrackColor`, i.e. each half's ticks
            //    in the OTHER half's colour — so full-strength `primary` dots
            //    ran along the UNSELECTED half and the slider read as if all of
            //    it were chosen.
            //
            // Same trap ShellNavBar.kt avoids by refusing secondaryContainer
            // for its indicator, and the same answer LevelSlider.kt gives.
            colors = SliderDefaults.colors(
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                activeTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f),
                inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            ),
        )
    }
}

/**
 * The share of a 400dp row a READOUT may take before it is the one that
 * ellipsises. A description does not go here at all — see [LinkRow].
 */
private val DetailMax = 168.dp
