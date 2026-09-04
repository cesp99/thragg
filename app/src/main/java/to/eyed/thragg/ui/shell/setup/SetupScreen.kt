package to.eyed.thragg.ui.shell.setup

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
import to.eyed.thragg.R
import to.eyed.thragg.solana.toolchain.ComponentRow
import to.eyed.thragg.solana.toolchain.ComponentState
import to.eyed.thragg.solana.toolchain.SolanaToolchain
import to.eyed.thragg.solana.toolchain.ToolchainInstaller
import to.eyed.thragg.solana.toolchain.ToolchainManifest
import to.eyed.thragg.solana.toolchain.ToolchainPhase
import to.eyed.thragg.solana.toolchain.ToolchainUpdates
import to.eyed.thragg.solana.toolchain.UpdateStatus
import to.eyed.thragg.solana.toolchain.formatBytes
import to.eyed.thragg.terminal.Userland
import to.eyed.thragg.ui.components.BottomActions
import to.eyed.thragg.ui.components.BottomActionsGap
import to.eyed.thragg.ui.components.HairlineDivider
import to.eyed.thragg.ui.components.NoticeCard
import to.eyed.thragg.ui.components.ThraggCard
import to.eyed.thragg.ui.components.ThraggChip
import to.eyed.thragg.ui.components.ThraggIndeterminateBar
import to.eyed.thragg.ui.components.ThraggSpinner
import to.eyed.thragg.ui.components.ThraggTopBar
import to.eyed.thragg.ui.components.Severity
import to.eyed.thragg.ui.components.fadeUnderBottomActions
import to.eyed.thragg.ui.workspace.ConfirmDeleteDialog
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.LocalThraggColors
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.ThraggIcon
import to.eyed.thragg.ui.theme.TabularNums
import to.eyed.thragg.ui.theme.touchTarget

/**
 * The one full-screen takeover: the honest cost of a phone that compiles
 * Solana programs, said plainly, once — and, on a fresh install, the gate.
 *
 * THE SHAPE IS VISUAL.md'S SETUP WIREFRAME, and it is the same shape the
 * agent's own provider gate takes: a static 40dp mark, a `headlineSmall`, two
 * lines of `bodyMedium` at the muted ink, one **StepList** — a single
 * [ThraggCard] whose rows are divided by [HairlineDivider] rather than a
 * column of separate cards — and one filled action pinned at the bottom above
 * the nav bar. The mark is drawn STATIC: this is the slot spettro-chat-android
 * fills with a morphing blob, and the slot is worth having while the blob is
 * not (docs/VISUAL.md, "Every other screen" → Setup).
 *
 * It is a route like any other (Route.Setup) and it is reachable at any time
 * from Projects → Toolchain, where it doubles as the repair and free-the-disk
 * page. On a phone with no toolchain it is also **the gate**: the shell puts
 * it over Code at launch ([ShellState.gate]), back does not pop it
 * (ShellBackHandler, `gated`), and there is no Skip. Without the toolchain
 * there is no Build, no deploy and no program that compiles — an IDE that
 * can only edit is not the product, and a Skip that led there was an
 * invitation to a dead end (docs/UI.md, "First run").
 *
 * Under the gate, before anything has started, the screen is an
 * **onboarding pager** of three pages: why the phone needs this, what to
 * expect while it runs, and the parts with their sizes and the Start button.
 * The pages exist because the install is twenty-odd minutes of a phone's
 * time and the user is owed the reasons before the button, not after. Once a
 * run has started — or resumed from a previous session — the pager is gone
 * and the step list is the screen, exactly as before.
 *
 * Three rules from docs/UI.md are enforced here rather than left to the
 * caller, because each one is a way a first-run screen can lie:
 *
 *  1. **Two numbers, not one.** Transfer and disk are different quantities and
 *     conflating them is the exact dishonesty this screen cannot afford. Both
 *     are summed from the manifest, so a toolchain bump moves them on its own.
 *     The third number — minutes — is measured, on the reference phone or on
 *     this one, and is called an estimate.
 *  2. **Two kinds of row.** A sized download counts bytes and a rate; an
 *     on-device compile counts *seconds*. `cargo-build-sbf` and `anchor` have
 *     no arm64 binary anywhere upstream and are built here, and a MB bar on a
 *     four-minute compile would be an invention.
 *  3. **The gate opens on the required rows.** Anchor is optional in the
 *     manifest and is the longest compile; when everything Build needs is in
 *     and Anchor is still going, the button says so and lets the user through
 *     while it finishes in the background.
 *
 * Leaving the screen does not stop the install — [ToolchainInstaller] lives
 * outside the composition and holds the terminal's foreground notification
 * while it runs — and coming back re-attaches to the same rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(state: ShellState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val supported = Userland.backend.isSupported

    LaunchedEffect(Unit) { ToolchainInstaller.refresh(context) }
    // The last update check's line is this page's; it does not follow the
    // user to Settings.
    DisposableEffect(Unit) { onDispose { ToolchainUpdates.reset() } }

    val manifest = remember(context) {
        runCatching { ToolchainManifest.load(context) }.getOrNull()
    }
    val rows = ToolchainInstaller.rows
    val phase = ToolchainInstaller.phase
    val complete = ToolchainInstaller.isComplete
    val gated = state.isGated

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

    // The pager shows only for a first run that has not started: rows loaded,
    // none of them in, nothing running. A phone that already has half the
    // toolchain has read the pages and wants the list.
    val untouched = rows.isNotEmpty() && rows.all { it.state is ComponentState.Pending }
    val onboarding = gated && supported && untouched && phase == ToolchainPhase.Idle
    // Kept across the moment the first row leaves Pending, so a Start on the
    // last page does not rebuild the whole screen under the thumb.
    var pagerDismissed by remember { mutableStateOf(false) }
    val showPager = onboarding && !pagerDismissed
    val estimate = remember(manifest, rows) { estimateSeconds(context, manifest) }

    Column(modifier = modifier.fillMaxSize()) {
        // The gate has no way back and draws no bar; the toolchain page
        // reached from Settings is a drill page like any other and has one.
        if (!gated) {
            ThraggTopBar(title = "Toolchain", onBack = { state.pop() })
            HairlineDivider()
        }
        if (showPager) {
            OnboardingPager(
                manifest = manifest,
                rows = rows,
                estimateSeconds = estimate,
                metered = remember(context) { isMetered(context) },
                onStart = {
                    pagerDismissed = true
                    ToolchainInstaller.start(context) { ready -> state.toolchainReady = ready }
                },
            )
            return@Column
        }

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
            ThraggIcon(
                icon = R.drawable.ic_launcher_monochrome,
                contentDescription = null,
                tint = scheme.primary,
                size = IconSize.Hero,
            )
            Spacer(Modifier.height(MD.space4))
            Text(
                text = headline(supported, complete, phase),
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
            val timeLine = timeLine(phase, estimate, ToolchainInstaller.runStartedAt, now)
                ?: manifest?.takeIf { !gated }?.let { "Toolchain manifest of ${it.released}." }
            if (timeLine != null) {
                Spacer(Modifier.height(MD.space1))
                Text(
                    text = timeLine,
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TabularNums),
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }
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
                    onRetry = { id ->
                        ToolchainInstaller.retry(context, id) { ready -> state.toolchainReady = ready }
                    },
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

            UpdateNotice(ToolchainUpdates.status)

            if (gated && phase == ToolchainPhase.Running) {
                Spacer(Modifier.height(MD.space2))
                NoticeCard(
                    severity = Severity.Info,
                    title = null,
                    body = "You can lock the phone or switch apps — the install keeps " +
                        "going under its notification and this screen picks up where " +
                        "it was. Keep it on Wi-Fi: most of this is a download.",
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
            gated = gated,
        )
    }
}

// --- the onboarding pages ----------------------------------------------------

private const val PAGES = 3

/**
 * Three pages and one Start. Swipeable, with dots, and a Next that walks them
 * for a thumb that would rather tap. The third page IS the step list, so the
 * last thing read before Start is the list of what Start does and what each
 * part costs — the same list the screen then animates.
 */
@Composable
private fun ColumnScope.OnboardingPager(
    manifest: ToolchainManifest?,
    rows: List<ComponentRow>,
    estimateSeconds: Long?,
    metered: Boolean,
    onStart: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val pager = rememberPagerState(pageCount = { PAGES })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == PAGES - 1

    HorizontalPager(
        state = pager,
        modifier = Modifier.fillMaxWidth().weight(1f),
        // No fling past the edges into nothing; the pages have edges.
        beyondViewportPageCount = 1,
    ) { page ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .fadeUnderBottomActions()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MD.space4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(MD.space8))
            ThraggIcon(
                icon = R.drawable.ic_launcher_monochrome,
                contentDescription = null,
                tint = scheme.primary,
                size = IconSize.Hero,
            )
            Spacer(Modifier.height(MD.space4))
            when (page) {
                0 -> WhyPage()
                1 -> ExpectPage(manifest, estimateSeconds)
                else -> PartsPage(manifest, rows, estimateSeconds)
            }
            Spacer(Modifier.height(BottomActionsGap))
        }
    }

    BottomActions(horizontalAlignment = Alignment.CenterHorizontally) {
        Dots(count = PAGES, current = pager.currentPage)
        Spacer(Modifier.height(MD.space3))
        val remaining = manifest?.totalDownloadBytes ?: 0L
        Button(
            onClick = {
                if (last) onStart()
                else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            },
            modifier = Modifier.fillMaxWidth().height(MD.rowMin),
            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        ) {
            Text(
                text = when {
                    !last -> "Next"
                    metered -> "Download over mobile data (${formatBytes(remaining)})"
                    else -> "Start"
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }
        // A way to the button from page one for the reader who has done this
        // before — a text link, because it is the minority path.
        if (!last) {
            TextButton(onClick = { scope.launch { pager.animateScrollToPage(PAGES - 1) } }) {
                Text(
                    text = "Skip to the parts",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PageTitle(headline: String, body: String) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = headline,
        style = MaterialTheme.typography.headlineSmall,
        color = scheme.onSurface,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(MD.space2))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(MD.space6))
}

/** Page 1: why a phone needs a compiler at all. */
@Composable
private fun WhyPage() {
    PageTitle(
        headline = "Thragg builds Solana programs on the phone",
        body = "Not on a server. The compiler, the linker and the build driver run " +
            "here, inside a Linux userland, so a build works on a plane and your " +
            "code never leaves the device.",
    )
    PointList(
        listOf(
            R.drawable.ic_ui_hexagon to ("A real Debian, under proot" to
                "apt works. git, gcc and the SBF compiler are ordinary packages in it."),
            R.drawable.ic_ui_play to ("Build, test and deploy from the Build tab" to
                "cargo-build-sbf and Anchor produce the same target/deploy/ a laptop does."),
            R.drawable.ic_ui_sparkles to ("An agent that can run your build" to
                "Spettro reads the errors and edits the code. Without a toolchain it can only read."),
        )
    )
}

/** Page 2: what the next half hour looks like. */
@Composable
private fun ExpectPage(manifest: ToolchainManifest?, estimateSeconds: Long?) {
    val minutes = estimateSeconds?.let { minutesFor(it) }
    PageTitle(
        headline = "One setup, once",
        body = if (minutes != null) {
            "About $minutes minutes on a Seeker, then the phone builds offline. " +
                "Two of the parts have no arm64 build upstream; ours come prebuilt " +
                "from a public GitHub Actions workflow."
        } else {
            "Then the phone builds offline. Two of the parts have no arm64 build " +
                "upstream; ours come prebuilt from a public GitHub Actions workflow."
        },
    )
    PointList(
        listOf(
            R.drawable.ic_ui_download to ("Wi-Fi and a charger" to
                "${manifest?.let { formatBytes(it.totalDownloadBytes) } ?: "About 700 MB"} " +
                    "over the network and a few minutes of unpacking. Plug in. On mobile " +
                    "data the button says what it costs."),
            R.drawable.ic_ui_lock to ("You can leave" to
                "It runs under a notification. Lock the phone, switch apps, come back — " +
                    "the list is where you left it. Pause keeps every byte already fetched."),
            R.drawable.ic_ui_rotate_ccw to ("If a part fails" to
                "Its row gets a Retry. Nothing already installed is downloaded again, " +
                    "and a half-finished download resumes from where it stopped."),
            R.drawable.ic_ui_server to ("${manifest?.let { formatBytes(it.totalInstallBytes) } ?: "About 2 GB"} on disk" to
                "Settings, then Toolchain, shows what it holds and frees it in one tap."),
        )
    )
}

/** Page 3: the parts — the same list the install then animates. */
@Composable
private fun PartsPage(manifest: ToolchainManifest?, rows: List<ComponentRow>, estimateSeconds: Long?) {
    PageTitle(
        headline = "The parts",
        body = costLine(supported = true, manifest = manifest) +
            (estimateSeconds?.let { " About ${minutesFor(it)} minutes." } ?: ""),
    )
    StepList(rows = rows, now = System.currentTimeMillis(), onRetry = {})
}

/** A card of icon + title + one line, the shape every page's body takes. */
@Composable
private fun PointList(points: List<Pair<Int, Pair<String, String>>>) {
    val scheme = MaterialTheme.colorScheme
    ThraggCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            points.forEachIndexed { index, (icon, text) ->
                if (index > 0) HairlineDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = MD.rowMin)
                        .padding(horizontal = MD.space3, vertical = MD.space3),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(MD.space3),
                ) {
                    Box(modifier = Modifier.width(20.dp).padding(top = MD.space05)) {
                        ThraggIcon(
                            icon = icon,
                            contentDescription = null,
                            tint = scheme.primary,
                            size = IconSize.Marker,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = text.first,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurface,
                        )
                        Text(
                            text = text.second,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = MD.space05),
                        )
                    }
                }
            }
        }
    }
}

/** Page dots: the current one at full primary, the rest at the muted ink. */
@Composable
private fun Dots(count: Int, current: Int) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(MD.space2)) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        color = if (index == current) scheme.primary
                        else scheme.onSurfaceVariant.copy(alpha = 0.3f),
                        shape = CircleShape,
                    ),
            )
        }
    }
}

// --- the headline -------------------------------------------------------------

/** What the screen is for, in a few words, and it changes with the state. */
private fun headline(supported: Boolean, complete: Boolean, phase: ToolchainPhase): String = when {
    !supported -> "No Linux userland"
    phase == ToolchainPhase.Running -> "Setting up the toolchain"
    complete -> "The toolchain is installed"
    phase == ToolchainPhase.Failed -> "The install stopped"
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
 * The third line: minutes. Elapsed against the estimate while running, the
 * estimate alone before, nothing after — a finished install has no time left
 * to talk about.
 */
private fun timeLine(phase: ToolchainPhase, estimateSeconds: Long?, startedAt: Long?, now: Long): String? {
    val minutes = estimateSeconds?.let { minutesFor(it) }
    return when {
        phase == ToolchainPhase.Running && startedAt != null ->
            "${elapsedFrom(startedAt, now)} elapsed" +
                (minutes?.let { " · usually about $it min on a Seeker" } ?: "")
        phase == ToolchainPhase.Idle || phase == ToolchainPhase.Failed ->
            minutes?.let { "Usually about $it minutes on a Seeker." }
        else -> null
    }
}

/**
 * The wall-clock estimate, in seconds: this phone's own history when it has
 * a complete one, the manifest's reference measurement otherwise, null when
 * the manifest has none.
 *
 * "Own history" is the sum over the *guest lane* rows of what they took
 * here last time — the same critical path the manifest's number is — and it
 * needs every one of them, because a phone that has only ever installed
 * rustup knows nothing about how long its apt takes.
 */
private fun estimateSeconds(context: Context, manifest: ToolchainManifest?): Long? {
    if (manifest == null) return null
    val lane = manifest.components.filter { it.onGuestLane }
    val own = SolanaToolchain.timings(context)
    if (lane.all { it.id in own }) return lane.sumOf { own.getValue(it.id) } / 1000L
    return manifest.estimatedWallSeconds.takeIf { it > 0L }
}

/** Seconds to a whole number of minutes, rounded *up* — never promise less. */
private fun minutesFor(seconds: Long): Long = (seconds + 59L) / 60L

// --- the step list --------------------------------------------------------------

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
    ThraggCard(modifier = Modifier.fillMaxWidth()) {
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
                ThraggChip(
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
        // Not the stock indeterminate indicator: that one invalidates every
        // frame of a 120 Hz panel for the length of a compile, and the CPU it
        // held came straight out of rustc's share (see ThraggIndeterminateBar).
        ThraggIndeterminateBar(
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
 * A running row draws the app's own [ThraggSpinner] rather than a static
 * glyph: it is the same braille cadence the agent's live-run strip and the
 * build strip use, it stands still under reduce-motion rather than vanishing,
 * and it is the only thing in the list that says "this one, right now".
 *
 * A pending row draws an empty circle at half strength — "not yet" has a
 * shape, and the middle dot that used to sit there was a glyph carrying no
 * meaning a screen reader could read. A staged row — bytes in, waiting for
 * the guest lane — draws the dotted circle: further along than pending,
 * and standing still.
 */
@Composable
private fun StateMark(state: ComponentState) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalThraggColors.current
    when (state) {
        is ComponentState.Installed -> ThraggIcon(
            icon = R.drawable.ic_ui_check,
            contentDescription = "installed",
            tint = colors.addedMark,
            size = IconSize.Marker,
        )

        is ComponentState.Downloading, is ComponentState.Working ->
            ThraggSpinner(size = 14.dp, color = scheme.primary)

        is ComponentState.Staged -> ThraggIcon(
            icon = R.drawable.ic_ui_circle_dashed,
            contentDescription = "downloaded",
            tint = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            size = IconSize.Marker,
        )

        is ComponentState.Outdated -> ThraggIcon(
            icon = R.drawable.ic_ui_arrow_circle,
            contentDescription = "update available",
            tint = scheme.primary,
            size = IconSize.Marker,
        )

        is ComponentState.Failed -> ThraggIcon(
            icon = R.drawable.ic_ui_close,
            contentDescription = "failed",
            tint = colors.removedMark,
            size = IconSize.Marker,
        )

        is ComponentState.Pending, is ComponentState.Cancelled -> ThraggIcon(
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
            if (component.isCompiled) {
                component.estimatedSeconds.takeIf { it > 0L }
                    ?.let { "builds here · ≈ ${minutesFor(it)} min" }
                    ?: "builds on device"
            } else if (component.approximate) {
                "≈ ${formatBytes(component.downloadBytes)}"
            } else {
                formatBytes(component.downloadBytes)
            }
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
    is ComponentState.Staged -> "downloaded · waiting for its turn"
    is ComponentState.Outdated -> "installed · update available"
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
 * What the last update check said, under the list. Nothing while idle or
 * checking — the link under the button already says "Checking" — and one
 * card otherwise: an info card naming the rows that are behind, a plain one
 * for up to date, an error card for a check that could not reach GitHub.
 */
@Composable
private fun UpdateNotice(status: UpdateStatus) {
    val (severity, title, body) = when (status) {
        is UpdateStatus.Idle, is UpdateStatus.Checking -> return
        is UpdateStatus.UpToDate ->
            Triple(Severity.Info, "Up to date", "Everything is at the toolchain manifest of ${status.released}.")
        is UpdateStatus.Available -> Triple(
            Severity.Info,
            "Update available",
            "${status.names.joinToString(", ")} — ${formatBytes(status.downloadBytes)} " +
                "to download, from the manifest of ${status.released}. Update installs only those.",
        )
        is UpdateStatus.Failed -> Triple(Severity.Error, "Could not check for updates", status.message)
    }
    Spacer(Modifier.height(MD.space2))
    NoticeCard(severity = severity, title = title, body = body)
}

// --- the actions ----------------------------------------------------------------

/**
 * One primary action, and the text links under it.
 *
 * On a metered connection the button names the cost instead of saying Start,
 * because "Start" on mobile data is a question the user was never asked
 * (docs/UI.md, "Setup" — metered connections).
 *
 * Under the gate there is no Skip and no Close: the ways off this screen are
 * Continue, once the required rows are in, and the back gesture, which
 * leaves the app with the install still running. Reached from Settings with
 * the toolchain in, the same screen has Close and the remove link.
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
    gated: Boolean,
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

    // "Complete" means every *required* row; an optional row — Anchor — can
    // still be pending, and it must keep a way to start. Without this, an
    // install interrupted between cargo-build-sbf and Anchor came back to a
    // screen whose only button said Done, and the Anchor CLI was unreachable
    // until a build failed.
    val allInstalled = ToolchainInstaller.rows.isNotEmpty() &&
        ToolchainInstaller.rows.all { it.state is ComponentState.Installed }
    val running = phase == ToolchainPhase.Running
    val updates = ToolchainInstaller.hasUpdates

    val label = when {
        !supported -> "Continue without a toolchain"
        // The gate opens on the required rows. Anchor may still be compiling
        // — and it keeps compiling, outside the composition, under the
        // notification — but Build, the agent and the editor are all real now.
        gated && complete && running -> "Continue — Anchor keeps installing"
        gated && complete -> "Continue"
        running -> "Pause"
        // Behind the manifest: the primary action is the update, and it is
        // exactly a Start over the rows that are not at this revision.
        updates && phase != ToolchainPhase.Failed ->
            "Update (${formatBytes(ToolchainInstaller.updateDownloadBytes)})"
        complete && allInstalled -> "Done"
        phase == ToolchainPhase.Failed -> "Retry"
        metered -> "Download over mobile data (${formatBytes(remaining)})"
        complete -> "Install the rest"
        ToolchainInstaller.rows.any { it.state !is ComponentState.Pending } -> "Resume"
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
                    !supported || (!updates && complete && (allInstalled || gated)) -> {
                        // From the rows, not from disk: this runs on the main
                        // thread and the rows already are the answer.
                        state.toolchainReady = ToolchainInstaller.isUsable
                        state.pop()
                    }
                    running -> ToolchainInstaller.cancel()
                    else -> {
                        ToolchainUpdates.reset()
                        ToolchainInstaller.start(context) { ready ->
                            state.toolchainReady = ready
                        }
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
        // Under the gate the primary button is the only way on; Pause is a
        // text link beneath Continue so a user who wants Anchor to wait for
        // a charger still has it.
        if (gated && running && complete) {
            TextButton(onClick = { ToolchainInstaller.cancel() }) {
                Text(
                    text = "Pause the rest",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        if (!gated) {
            // The update check: fetch the published manifest, adopt it if it
            // is newer, and say which rows are behind. A text link, because
            // the primary button becomes the Update the moment there is one.
            if (!running) {
                TextButton(
                    onClick = { ToolchainUpdates.check(context) },
                    enabled = !ToolchainUpdates.isChecking,
                ) {
                    Text(
                        text = if (ToolchainUpdates.isChecking) "Checking for updates…" else "Check for updates",
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.primary,
                    )
                }
            }
            TextButton(
                onClick = {
                    state.toolchainReady = ToolchainInstaller.isUsable
                    state.pop()
                },
            ) {
                Text(
                    text = "Close",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        // The other half of this screen's job: once the toolchain is in, this
        // is also the page you come to to get the disk back (docs/UI.md —
        // "the repair / uninstall / free-1.4-GB page"). Only offered when
        // there is something to free and nothing is mid-install — a remove
        // under a running compile deletes the compiler out from under it —
        // and it leaves the Debian userland standing, because the terminal
        // and git are useful without a compiler.
        if (!gated && complete && !running && !updates) {
            val scope = rememberCoroutineScope()
            var confirmRemove by remember { mutableStateOf(false) }
            TextButton(onClick = { confirmRemove = true }) {
                Text(
                    text = "Remove the toolchain — frees ${formatBytes(installedBytes)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
            }
            // Confirmed, always. This is gigabytes deleted and hours of
            // download-and-compile to earn back, sitting one stray tap below
            // Close on a screen with no nav bar — it must never fire on a
            // touch nobody meant.
            if (confirmRemove) {
                ConfirmDeleteDialog(
                    paths = listOf("the Solana toolchain (${formatBytes(installedBytes)})"),
                    permanent = true,
                    onConfirm = {
                        confirmRemove = false
                        scope.launch {
                            withContext(Dispatchers.IO) { SolanaToolchain.remove(context) }
                            state.toolchainReady = false
                            ToolchainInstaller.refresh(context)
                        }
                    },
                    onDismiss = { confirmRemove = false },
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
