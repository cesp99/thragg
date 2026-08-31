package to.eyed.seeker.code.ui.shell.setup

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.R
import to.eyed.seeker.code.solana.toolchain.ComponentRow
import to.eyed.seeker.code.solana.toolchain.ComponentState
import to.eyed.seeker.code.solana.toolchain.SolanaToolchain
import to.eyed.seeker.code.solana.toolchain.ToolchainInstaller
import to.eyed.seeker.code.solana.toolchain.ToolchainManifest
import to.eyed.seeker.code.solana.toolchain.ToolchainPhase
import to.eyed.seeker.code.solana.toolchain.formatBytes
import to.eyed.seeker.code.terminal.Userland
import to.eyed.seeker.code.ui.components.BottomActions
import to.eyed.seeker.code.ui.components.BottomActionsGap
import to.eyed.seeker.code.ui.components.HairlineDivider
import to.eyed.seeker.code.ui.components.NoticeCard
import to.eyed.seeker.code.ui.components.SeekerCard
import to.eyed.seeker.code.ui.components.SeekerChip
import to.eyed.seeker.code.ui.components.SeekerSpinner
import to.eyed.seeker.code.ui.components.Severity
import to.eyed.seeker.code.ui.components.fadeUnderBottomActions
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.IconSize
import to.eyed.seeker.code.ui.theme.LocalSeekerColors
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.SeekerIcon
import to.eyed.seeker.code.ui.theme.TabularNums
import to.eyed.seeker.code.ui.theme.touchTarget

/**
 * The one full-screen takeover: the honest cost of a phone that compiles
 * Solana programs, said plainly, once, with an honest Skip.
 *
 * THE SHAPE IS VISUAL.md'S SETUP WIREFRAME, and it is the same shape the
 * agent's own provider gate takes: a static 40dp mark, a `headlineSmall`, two
 * lines of `bodyMedium` at the muted ink, one **StepList** — a single
 * [SeekerCard] whose rows are divided by [HairlineDivider] rather than a
 * column of separate cards — and one filled action pinned at the bottom above
 * the nav bar. The mark is drawn STATIC: this is the slot spettro-chat-android
 * fills with a morphing blob, and the slot is worth having while the blob is
 * not (docs/VISUAL.md, "Every other screen" → Setup).
 *
 * It is a route like any other (Route.Setup) and it is reachable at any time
 * from Projects → Toolchain, where it doubles as the repair and free-the-disk
 * page. It is not, however, a gate: the toolchain download is deferred until
 * the first Build press rather than blocking first launch (docs/UI.md, "The
 * design chosen"), so nothing here runs until somebody presses a button.
 *
 * Three rules from docs/UI.md are enforced here rather than left to the
 * caller, because each one is a way a first-run screen can lie:
 *
 *  1. **Two numbers, not one.** Transfer and disk are different quantities and
 *     conflating them is the exact dishonesty this screen cannot afford. Both
 *     are summed from the manifest, so a toolchain bump moves them on its own.
 *  2. **Two kinds of row.** A sized download counts bytes and a rate; an
 *     on-device compile counts *seconds*. `cargo-build-sbf` and `anchor` have
 *     no arm64 binary anywhere upstream and are built here, and a MB bar on a
 *     four-minute compile would be an invention.
 *  3. **Skip is a text link, not a button.** It is the minority path and it is
 *     a real one: the editor, highlighting, the file tree, search, git and the
 *     agent all work with no toolchain at all. A `TextButton` under a filled
 *     one is Material's own way of saying exactly that.
 *
 * Leaving the screen does not stop the install — [ToolchainInstaller] lives
 * outside the composition and holds the terminal's foreground notification
 * while it runs — and coming back re-attaches to the same rows.
 */
@Composable
fun SetupScreen(state: ShellState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val supported = Userland.backend.isSupported

    LaunchedEffect(Unit) { ToolchainInstaller.refresh(context) }

    val manifest = remember(context) {
        runCatching { ToolchainManifest.load(context) }.getOrNull()
    }
    val rows = ToolchainInstaller.rows
    val phase = ToolchainInstaller.phase
    val complete = ToolchainInstaller.isComplete

    /**
     * One tick a second, and only while something is running — this is what
     * the elapsed timer on a compile row counts with. A clock in the state
     * holder would recompose every destination; a clock here stops when the
     * screen leaves, which is exactly when nobody is reading it.
     */
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(phase) {
        while (phase == ToolchainPhase.Running) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // The steps are CLIPPED where the action bar begins, and a
                // running install is exactly when the list is long enough to
                // reach it — the last component was drawn sliced in half with
                // nothing to say the list continued. The fade says it; the
                // 24dp at the foot of this column is what lets that last row
                // scroll clear of the fade ([BottomActions]). Before the
                // scroll, so it masks the VIEWPORT rather than the content.
                .fadeUnderBottomActions()
                .verticalScroll(rememberScrollState())
                // 16dp is the screen gutter, everywhere, on every screen.
                .padding(horizontal = MD.space4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(MD.space8))
            SeekerIcon(
                icon = R.drawable.ic_launcher_monochrome,
                contentDescription = null,
                tint = scheme.primary,
                size = IconSize.Hero,
            )
            Spacer(Modifier.height(MD.space4))
            Text(
                text = headline(supported, complete),
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(MD.space2))
            Text(
                text = costLine(supported, manifest),
                style = MaterialTheme.typography.bodyMedium,
                // 70%, centred: the sentence under a headline is context, and
                // a second line at full strength competes with the headline
                // for the same job.
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(MD.space6))

            if (!supported) {
                NoticeCard(
                    severity = Severity.Info,
                    title = null,
                    body = "Android will not execute a program that arrived after " +
                        "installation — which every part of a compiler toolchain is. " +
                        "The editor, the file tree, search and git are unaffected.",
                )
            } else {
                StepList(
                    rows = rows,
                    now = now,
                    onRetry = { id -> ToolchainInstaller.retry(context, id) },
                )
            }

            ToolchainInstaller.lastError?.let { message ->
                Spacer(Modifier.height(MD.space2))
                NoticeCard(
                    severity = Severity.Error,
                    title = "The install stopped",
                    body = message,
                )
            }

            Spacer(Modifier.height(BottomActionsGap))
        }

        // The actions sit at the bottom, in the thumb zone, which is the
        // reachability rule the whole shell is built on (docs/UI.md).
        Actions(
            state = state,
            context = context,
            supported = supported,
            manifest = manifest,
        )
    }
}

/** What the screen is for, in four words, and it changes with the state. */
private fun headline(supported: Boolean, complete: Boolean): String = when {
    !supported -> "No Linux userland"
    complete -> "The toolchain is installed"
    else -> "Set up the toolchain"
}

/**
 * The headline's second line, and the one place the two numbers appear
 * together.
 *
 * Summed from the manifest rather than written down, so the sentence cannot
 * drift from the component list underneath it. They are genuinely different
 * numbers — the compiles produce disk out of CPU and download nothing — and
 * saying so is the point.
 */
private fun costLine(supported: Boolean, manifest: ToolchainManifest?): String {
    // Defensive: the userland seam answers yes in every build that ships, so
    // this branch is a backstop rather than a state anyone reaches. It used to
    // name an edition that no longer exists.
    if (!supported) {
        return "The Linux guest is not available, so the Solana toolchain " +
            "cannot be installed. Everything else works."
    }
    if (manifest == null) return "The toolchain manifest could not be read."
    return "One setup, once. ${formatBytes(manifest.totalDownloadBytes)} down, " +
        "${formatBytes(manifest.totalInstallBytes)} on disk, then the phone builds offline."
}

/**
 * Every component in one card, divided by hairlines.
 *
 * ONE CARD RATHER THAN A COLUMN OF THEM, which is the whole difference between
 * this and what was here before. Six separate cards read as six unrelated
 * things; a list inside one edge reads as the steps of a single operation,
 * which is what an install IS. It is also the shape the wireframe draws and
 * the shape the agent's provider gate takes, so the two setup screens in this
 * app are recognisably the same screen.
 */
@Composable
private fun StepList(
    rows: List<ComponentRow>,
    now: Long,
    onRetry: (String) -> Unit,
) {
    SeekerCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            rows.forEachIndexed { index, row ->
                if (index > 0) HairlineDivider()
                ComponentRowView(
                    row = row,
                    now = now,
                    onRetry = { onRetry(row.component.id) },
                )
            }
        }
    }
}

/**
 * One component: a mark, a name, a right-hand figure, and — only while it is
 * the row that is running — a bar under it.
 *
 * The right-hand figure is where the two row kinds diverge and it is decided
 * by the component, not by the state: a compile row says "builds on device"
 * before it starts and an elapsed timer while it runs, and never a byte.
 */
@Composable
private fun ComponentRowView(row: ComponentRow, now: Long, onRetry: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val state = row.state

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MD.rowMin)
            .padding(horizontal = MD.space3, vertical = MD.rowPadY),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MD.space3),
        ) {
            // A fixed 20dp slot whether or not there is a mark in it, so the
            // names of the components stay in one column down the card.
            Box(
                modifier = Modifier.width(20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                StateMark(state)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.component.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )
                val detail = detail(row, now)
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = MD.space05),
                    )
                }
            }
            if (state is ComponentState.Failed || state is ComponentState.Cancelled) {
                SeekerChip(
                    label = "Retry",
                    onClick = onRetry,
                    // 28dp drawn, 48dp of target: the chip does not grow its
                    // own hit box because most chips sit in a scrolling row
                    // where that would change the layout.
                    modifier = Modifier.touchTarget(),
                    tint = scheme.primary,
                )
            } else {
                Text(
                    text = figure(row),
                    style = MaterialTheme.typography.labelSmall.copy(
                        // A figure that ticks — bytes, elapsed — must not
                        // shimmy as its digits change width.
                        fontFeatureSettings = TabularNums,
                    ),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state is ComponentState.Downloading) {
            val fraction = state.fraction
            if (fraction == null) {
                Bar()
            } else {
                Bar(fraction)
            }
        } else if (state is ComponentState.Working) {
            // No fraction exists for an unpack, an apt run or a compile, and
            // inventing one is exactly what the two-row-kinds rule forbids.
            Bar()
        }
    }
}

/**
 * The progress bar, at the app's colours rather than Material's defaults.
 *
 * `primary` on `surfaceVariant`, with the gap and stop indicator Material 1.4
 * draws by default — this is the one place a stock M3 indicator is used, and
 * it is used because a determinate download genuinely has a fraction. There is
 * no `WavyProgressIndicator` at this version and nothing here wants one.
 */
@Composable
private fun Bar(fraction: Float? = null) {
    val scheme = MaterialTheme.colorScheme
    val modifier = Modifier.fillMaxWidth().padding(top = MD.space2)
    if (fraction == null) {
        LinearProgressIndicator(
            modifier = modifier,
            color = scheme.primary,
            trackColor = scheme.surfaceVariant,
        )
    } else {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = modifier,
            color = scheme.primary,
            trackColor = scheme.surfaceVariant,
        )
    }
}

/**
 * The mark on the left of a component row.
 *
 * A running row draws the app's own [SeekerSpinner] rather than a static
 * glyph: it is the same braille cadence the agent's live-run strip and the
 * build strip use, it stands still under reduce-motion rather than vanishing,
 * and it is the only thing in the list that says "this one, right now".
 *
 * A pending row draws an empty circle at half strength — "not yet" has a
 * shape, and the middle dot that used to sit there was a glyph carrying no
 * meaning a screen reader could read.
 */
@Composable
private fun StateMark(state: ComponentState) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalSeekerColors.current
    when (state) {
        is ComponentState.Installed -> SeekerIcon(
            icon = R.drawable.ic_ui_check,
            contentDescription = "installed",
            tint = colors.addedMark,
            size = IconSize.Marker,
        )

        is ComponentState.Downloading, is ComponentState.Working ->
            SeekerSpinner(size = 14.dp, color = scheme.primary)

        is ComponentState.Failed -> SeekerIcon(
            icon = R.drawable.ic_ui_close,
            contentDescription = "failed",
            tint = colors.removedMark,
            size = IconSize.Marker,
        )

        is ComponentState.Pending, is ComponentState.Cancelled -> SeekerIcon(
            icon = R.drawable.ic_ui_circle,
            contentDescription = null,
            tint = scheme.onSurfaceVariant.copy(alpha = 0.5f),
            size = IconSize.Marker,
        )
    }
}

/** The right-hand figure: a size, a byte count, or the words for a compile. */
private fun figure(row: ComponentRow): String {
    val component = row.component
    return when (val state = row.state) {
        is ComponentState.Downloading ->
            "${formatBytes(state.received)} / ${formatBytes(state.total)}"
        is ComponentState.Installed -> "installed"
        is ComponentState.Working ->
            if (component.isCompiled) elapsed(state.startedAt) else "working"
        else ->
            if (component.isCompiled) "builds on device"
            else if (component.approximate) "≈ ${formatBytes(component.downloadBytes)}"
            else formatBytes(component.downloadBytes)
    }
}

/** The second line: a percentage and a rate, or what the step last said. */
private fun detail(row: ComponentRow, now: Long): String = when (val state = row.state) {
    is ComponentState.Downloading -> buildString {
        state.fraction?.let { append("${(it * 100).toInt()} %") }
        state.bytesPerSecond?.let {
            if (isNotEmpty()) append(" · ")
            append("${formatBytes(it)}/s")
        }
    }
    is ComponentState.Working ->
        if (row.component.isCompiled) {
            "compiling on the device · ${elapsedFrom(state.startedAt, now)}"
        } else {
            state.step
        }
    is ComponentState.Failed -> state.message
    is ComponentState.Cancelled -> "stopped — the bytes already fetched are kept"
    else -> row.component.summary
}

/** Elapsed as `m:ss`, recomputed against the screen's one-second tick. */
private fun elapsedFrom(startedAt: Long, now: Long): String {
    val seconds = ((now - startedAt).coerceAtLeast(0L)) / 1000L
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}

private fun elapsed(startedAt: Long): String = elapsedFrom(startedAt, System.currentTimeMillis())

/**
 * One primary action, and the text links under it.
 *
 * On a metered connection the button names the cost instead of saying Start,
 * because "Start" on mobile data is a question the user was never asked
 * (docs/UI.md, "Setup" — metered connections).
 *
 * A stock filled `Button`, and this is the first screen in the app to use one:
 * the bridge solves `onPrimary` against `primary`, which is what makes it
 * safe. Left as it was — `onPrimary = editor.background` (Theme.kt:139) — the
 * label on this button measured 2.84:1 on Ayu Light, and it is the single
 * most important label on the whole screen.
 */
@Composable
private fun Actions(
    state: ShellState,
    context: Context,
    supported: Boolean,
    manifest: ToolchainManifest?,
) {
    val scheme = MaterialTheme.colorScheme
    val phase = ToolchainInstaller.phase
    val complete = ToolchainInstaller.isComplete
    val metered = remember(context) { isMetered(context) }
    val remaining = ToolchainInstaller.remainingDownloadBytes
        .takeIf { it > 0L }
        ?: manifest?.totalDownloadBytes
        ?: 0L
    // Summed from the rows that are actually in, not from the manifest total:
    // "frees 2.1 GB" has to mean the disk this device is holding right now.
    val installedBytes = ToolchainInstaller.rows
        .filter { it.state is ComponentState.Installed }
        .sumOf { it.component.installBytes }

    val label = when {
        !supported -> "Continue without a toolchain"
        phase == ToolchainPhase.Running -> "Pause"
        complete -> "Done"
        phase == ToolchainPhase.Failed -> "Retry"
        metered -> "Download over mobile data (${formatBytes(remaining)})"
        else -> "Start"
    }

    // [BottomActions] rather than a bare Column, and this is the screen that
    // needed it most: Setup is the one route that hides the shell's nav bar
    // (Route.hidesNavBar), so nothing below this was clearing the gesture
    // handle — and with the whole step list scrolling into it, the bar had no
    // edge of its own either.
    BottomActions(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = {
                when {
                    !supported || complete -> {
                        // From the rows, not from disk: this runs on the main
                        // thread and the rows already are the answer.
                        state.toolchainReady = ToolchainInstaller.isComplete
                        state.pop()
                    }
                    phase == ToolchainPhase.Running -> ToolchainInstaller.cancel()
                    else -> ToolchainInstaller.start(context) { ready ->
                        state.toolchainReady = ready
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(MD.rowMin),
            // Elevation zero, in both halves, always: depth is a fill step and
            // one hairline, never a shadow (docs/VISUAL.md, ELEVATION).
            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        ) {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
        // A text link and not a button, deliberately: the minority path, and a
        // real one. Setup comes back from Projects → Toolchain at any time.
        TextButton(
            onClick = {
                state.toolchainReady = ToolchainInstaller.isComplete
                state.pop()
            },
        ) {
            Text(
                text = if (complete) "Close" else "Skip — I only want to edit code",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
            )
        }
        // The other half of this screen's job: once the toolchain is in, this
        // is also the page you come to to get the disk back (docs/UI.md —
        // "the repair / uninstall / free-1.4-GB page"). Only offered when
        // there is something to free, and it leaves the Debian userland
        // standing, because the terminal and git are useful without a compiler.
        if (complete) {
            val scope = rememberCoroutineScope()
            TextButton(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { SolanaToolchain.remove(context) }
                        state.toolchainReady = false
                        ToolchainInstaller.refresh(context)
                    }
                },
            ) {
                Text(
                    text = "Remove the toolchain — frees ${formatBytes(installedBytes)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Whether the active network bills by the byte.
 *
 * Read once when the screen composes rather than watched: a change of network
 * mid-install is not a reason to relabel a button under the user's thumb, and
 * the install itself survives the change either way.
 */
private fun isMetered(context: Context): Boolean = runCatching {
    context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true
}.getOrDefault(false)
