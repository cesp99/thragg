@file:OptIn(ExperimentalMaterial3Api::class)

package to.eyed.thragg.ui.shell.build

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import to.eyed.thragg.R
import to.eyed.thragg.solana.chain.Cluster
import to.eyed.thragg.solana.chain.ClusterStore
import to.eyed.thragg.ui.shell.settings.TopUpSheet
import to.eyed.thragg.ui.shell.settings.WalletSheet
import to.eyed.thragg.solana.build.AgentFix
import to.eyed.thragg.solana.build.ArtifactFreshness
import to.eyed.thragg.solana.build.BuildAction
import to.eyed.thragg.solana.build.BuildDiagnostics
import to.eyed.thragg.solana.build.BuildIssue
import to.eyed.thragg.solana.build.BuildRunner
import to.eyed.thragg.solana.build.BuildTasks
import to.eyed.thragg.solana.build.FailureFacts
import to.eyed.thragg.solana.build.ProjectFramework
import to.eyed.thragg.solana.build.ProjectLayout
import to.eyed.thragg.terminal.Userland
import to.eyed.thragg.ui.components.EmptyState
import to.eyed.thragg.ui.components.HairlineDivider
import to.eyed.thragg.ui.components.NoticeCard
import to.eyed.thragg.ui.components.ThraggCard
import to.eyed.thragg.ui.components.ThraggChip
import to.eyed.thragg.ui.components.ThraggSpinner
import to.eyed.thragg.ui.components.ThraggTopBar
import to.eyed.thragg.ui.components.Severity
import to.eyed.thragg.ui.components.StatusDot
import to.eyed.thragg.ui.editor.DiagnosticSeverity
import to.eyed.thragg.ui.shell.BuildState
import to.eyed.thragg.ui.shell.Destination
import to.eyed.thragg.ui.shell.Route
import to.eyed.thragg.ui.shell.SheetScaffold
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.shell.code.CodeBuildSeam
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.LocalThraggColors
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.MonoSmall
import to.eyed.thragg.ui.theme.RowChevron
import to.eyed.thragg.ui.theme.ThraggIcon
import to.eyed.thragg.ui.theme.ThraggIconButton
import to.eyed.thragg.ui.theme.TabularNums
import to.eyed.thragg.ui.theme.Durations
import to.eyed.thragg.ui.theme.LocalReduceMotion
import to.eyed.thragg.ui.theme.animateSize
import to.eyed.thragg.ui.theme.effectSpec
import to.eyed.thragg.ui.theme.mutedIcon
import to.eyed.thragg.ui.components.outlinedButtonEdge
import to.eyed.thragg.ui.workspace.ContextMenu
import to.eyed.thragg.ui.workspace.ContextMenuItem
import to.eyed.thragg.ui.workspace.Notifications

/**
 * The Build destination — the screen the app exists for.
 *
 * FOUR BANDS, TOP TO BOTTOM (docs/VISUAL.md, "Every other screen" → Build): a
 * 56dp [ThraggTopBar] carrying the target as its subtitle and the run control
 * as a filled button; a 36dp [BuildStatusStrip] that *reports* and never acts;
 * the problems as [ThraggCard]s you can tap into Code; and the log as a Zed
 * island in the buffer's own face. Everything except that island is Material —
 * `MaterialTheme.colorScheme` and `LocalThraggColors`, no `theme.color(...)`
 * read anywhere in this file — because the log is the only part of this screen
 * that has to agree with tree-sitter, and the rest is an app.
 *
 * THE RUN CONTROL MOVED INTO THE BAR, and it swaps its GLYPH rather than its
 * label. A build is a cancel-not-steer situation: while it runs there is
 * exactly one thing to do to it, and a second control that says "Stop" beside
 * a first that says "Build" is two answers to a question with one. That is the
 * opposite of the agent composer, where the send button must not become a stop
 * button, and the difference is that an agent turn can be *redirected*.
 *
 * WHAT THIS COSTS, SAID PLAINLY: the three-button bottom row is gone, and with
 * it docs/UI.md's reachability inversion for this screen. Build is now one tap
 * in the bar; Test and Deploy are in the overflow, which for Deploy is a
 * feature — it spends SOL, and docs/UI.md's own argument was that it must not
 * sit under the thumb that presses Build forty times a session. VISUAL.md is
 * the authority here and its wireframe has no bottom row.
 *
 * THE TACTILE VARIANT PUTS A ROW BACK — the [RunDeck], four keys above the
 * capsule — and asks the owner to judge exactly the paragraph above on the
 * phone. Deploy's key opens the Deploy sheet and never spends; the bar keeps
 * only ⋮. See RunDeck.kt for the argument and the fallback.
 *
 * The whole destination still toggles in place to [ShellTerminal]; see
 * [ShellModes].
 */
@Composable
fun BuildScreen(state: ShellState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val root = state.project?.rootPath
    val layout = BuildRunner.layout
    val inShell = ShellModes.isShell(root)
    // Why Test cannot run as `anchor test` right now, or null. Non-null is
    // the sheet; it is a String rather than a Boolean because the sheet
    // says which of Node and yarn is missing.
    var testBlockedBy by remember { mutableStateOf<String?>(null) }
    // The Wallet sheet, from here. Build is where SOL is spent — Deploy and
    // Test both draw on the deploy key — and until 2026-09-08 the only ways
    // to the sheet, and to its "Mine 5 SOL", were Projects & tools and
    // Settings: five taps from this tab, measured by looking for it. The
    // cluster is Anchor.toml's, read off the main thread as ProjectsSheet
    // reads it.
    var walletOpen by remember { mutableStateOf(false) }
    // The deploy's shortfall, when Deploy handed it to the top-up picker.
    var topUpShortfall by remember { mutableStateOf<Long?>(null) }
    // A tap on a blocked deck key, counted so the status strip's readout —
    // where the key's reason is printed — can flash (RunDeck.kt).
    var blockedTaps by remember { mutableIntStateOf(0) }
    val walletCluster by produceState(Cluster.DEFAULT, root, ClusterStore.version) {
        value = withContext(Dispatchers.IO) { ClusterStore.of(context, root) }
    }

    // Detect, probe and stat — all three are blocking, and the probe starts a
    // proot. Keyed on the project and on the toolchain flag, so finishing
    // Setup re-answers "can this device compile" without a restart.
    LaunchedEffect(root, state.toolchainReady) {
        withContext(Dispatchers.IO) { BuildRunner.refresh(context, root) }
    }
    LaunchedEffect(state) { BuildBootstrap.install(state, context) }

    // A new run supersedes the old verdict. The failure toast is an Error, so
    // Notifications never expires it on its own — right, until a rebuild
    // starts, at which point a red "Build failed" floating over a log that is
    // busy succeeding is a lie (the rehearsal had it sitting over a green
    // "Built" row for a whole demo beat). Keyed on isRunning rather than on
    // the individual ▶ handlers so every path that starts work — Build, Test,
    // Deploy, the sheet's cargo-test — clears it, including the ones added
    // later.
    LaunchedEffect(BuildRunner.isRunning) {
        if (BuildRunner.isRunning) Notifications.dismissKey(BUILD_TOAST_KEY)
    }

    val onTest = {
        val blocked = layout?.let { BuildTasks.anchorTestBlockedBy(it, BuildRunner.tools) }
        if (blocked != null) {
            testBlockedBy = blocked
        } else {
            BuildRunner.start(context, state, BuildAction.Test)
        }
    }

    /**
     * Retry runs the verb that failed — the whole of QA G-02's second half.
     *
     * Deploy retries as the *sheet* rather than as a deploy: it spends SOL,
     * and the one control in the app that spends it is the sheet's own
     * confirm. A failed test goes back through [onTest] so its Node gate is
     * asked again rather than skipped.
     */
    val onRetry = { action: BuildAction? ->
        when (action) {
            BuildAction.Deploy -> DeployPrompt.open = true
            BuildAction.Test -> onTest()
            else -> BuildRunner.start(context, state, BuildAction.Build)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        BuildBar(
            state = state,
            context = context,
            layout = layout,
            inShell = inShell,
            onWallet = { walletOpen = true },
        )
        // The seam under a flat bar. Nothing tints on scroll anywhere in this
        // app, so a hairline is what separates a bar from its content.
        HairlineDivider()

        // No project means nothing to report, and a strip that says "Not
        // built" about a project that does not exist is noise with a border.
        if (!inShell && root != null) {
            BuildStatusStrip(state, layout, context, blockedTaps)
            HairlineDivider()
        }

        when {
            root == null -> Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    headline = "No project open",
                    body = "Open one from Code and its builds, its problems and " +
                        "its shell all land here.",
                )
            }

            inShell -> ShellTerminal(state, root, modifier = Modifier.weight(1f))

            else -> BuildBody(state, context, root, layout, onRetry, modifier = Modifier.weight(1f))
        }

        // The verbs, under the thumb, in Build and in Shell alike; the log
        // island above yields the 56dp band. Hides under the IME with the
        // capsule.
        if (root != null) {
            RunDeck(
                state = state,
                context = context,
                layout = layout,
                inShell = inShell,
                onTest = onTest,
                onWallet = { walletOpen = true },
                onBlockedTap = { blockedTaps++ },
            )
        }
    }

    testBlockedBy?.let { reason ->
        AnchorTestSheet(
            state = state,
            reason = reason,
            onDismiss = { testBlockedBy = null },
            onSetup = {
                testBlockedBy = null
                state.push(Route.Setup)
            },
            onCargoTest = {
                testBlockedBy = null
                BuildRunner.start(context, state, BuildAction.Test, BuildTasks.cargoTestCommand())
            },
        )
    }
    if (DeployPrompt.open) {
        DeploySheet(
            state = state,
            onDismiss = { DeployPrompt.open = false },
            onWallet = { walletOpen = true },
            onTopUp = { shortfall -> topUpShortfall = shortfall },
        )
    }
    if (walletOpen) {
        WalletSheet(state = state, cluster = walletCluster, onDismiss = { walletOpen = false }, deployKeyFirst = true)
    }
    // The Deploy sheet's own door to the top-up: it dismisses itself first,
    // so this is the picker standing where the deploy summary was, with the
    // shortfall it just showed already in it.
    topUpShortfall?.let { shortfall ->
        TopUpSheet(
            state = state,
            cluster = walletCluster,
            shortfall = shortfall,
            onConnect = { topUpShortfall = null; walletOpen = true },
            onDismiss = { topUpShortfall = null },
        )
    }
}

/**
 * Filling in the seams the other destinations left for the build layer.
 *
 * There is exactly one today: `▶ Build` in the editor's fixed action-row head,
 * which P2 routes through [CodeBuildSeam] rather than importing this package —
 * the two chunks landed in parallel and that seam is what let the editor's
 * button exist before its runner did (CodeScreen.kt, `CodeBuildSeam`).
 *
 * Installed once and never taken back: it is a process-wide holder, and a
 * `▶` that stops working because you navigated away from Build would be worse
 * than one that never worked. It is installed from the shell's own
 * composition as well as from here (`ThraggShell.kt`), because this call
 * happens only when the Build destination composes — and a cold start that
 * lands on Code used to leave the editor's ▶ dead until Build had been opened
 * once (QA G-01). Installing it twice with the same state is a no-op.
 *
 * The other half of that fix is not here: ▶ needs [BuildRunner] to be pointed
 * at the open project, which is now done where the project is opened
 * (`openProjectInShell`) rather than by this screen's own `LaunchedEffect`.
 */
object BuildBootstrap {
    fun install(state: ShellState, context: Context) {
        val app = context.applicationContext
        // P2's runBuild() has already pushed Setup if there is no toolchain
        // and saved every dirty buffer by the time this is called; what is
        // left is the second half, which is the run.
        //
        // The stale-verdict toast is dismissed here as well as in the
        // screen's isRunning effect, because this seam is the one start path
        // that runs while the user is on Code — where BuildScreen is not
        // composed and its LaunchedEffect cannot fire.
        CodeBuildSeam.run = { project ->
            Notifications.dismissKey(BUILD_TOAST_KEY)
            // The argument is not decoration: it is the project the editor
            // believes it is building, and a run that disagreed with it is
            // exactly the shape of QA B-10. `BuildRunner.start` makes the
            // same check against its own layout; this one catches the case
            // before a notification is dismissed for a project nobody is
            // looking at.
            if (project.rootPath == state.project?.rootPath) {
                BuildRunner.start(app, state, BuildAction.Build)
            }
        }
    }
}

/**
 * `Build / escrow · Anchor                          [⋮]`
 *
 * Only ⋮: the run control and the terminal mark went down to the [RunDeck],
 * where the thumb is. What is left in the menu is what is not a verb —
 * Wallet, Problems, the log as text, Setup.
 */
@Composable
private fun BuildBar(
    state: ShellState,
    context: Context,
    layout: ProjectLayout?,
    inShell: Boolean,
    onWallet: () -> Unit,
) {
    var overflow by remember { mutableStateOf(false) }
    val projectName = state.project?.rootName

    ThraggTopBar(
        title = if (inShell) "Shell" else "Build",
        subtitle = when {
            projectName == null -> "No project open"
            inShell -> "$projectName · terminal"
            layout != null -> "$projectName · ${layout.label}"
            else -> projectName
        },
        actions = {
            Box {
                ThraggIconButton(
                    icon = R.drawable.ic_ui_more_vertical,
                    description = "More",
                    onClick = { overflow = true },
                    tint = mutedIcon,
                )
                ContextMenu(
                    expanded = overflow,
                    onDismiss = { overflow = false },
                    items = listOf(
                        // Deploy and Test spend from the deploy key; this is
                        // where it is funded (Mine 5 SOL on devnet) and emptied.
                        ContextMenuItem("Wallet", onClick = onWallet),
                        ContextMenuItem("Problems") { state.push(Route.Problems) },
                        ContextMenuItem("Copy the log") {
                            copyToClipboard(context, logText())
                        },
                        ContextMenuItem("Set up the toolchain") { state.push(Route.Setup) },
                    ),
                )
            }
        },
    )
}

/**
 * `◐ 2m 08s   anchor build            3 warnings` — 36dp, and it reports.
 *
 * A bar that acts is 48dp and a bar that reports is [MD.stripHeight]; this one
 * has no target in it at all, which is why it can be that short. Running, it
 * is a [ThraggSpinner] and the progress word only — the elapsed clock lives
 * in the deck's Stop key ([RunDeck]), so there is one clock on the screen,
 * in the key that ends what it is timing.
 *
 * At rest it is a [StatusDot] and the artifact's freshness, which is the one
 * fact this screen exists to keep honest: deploying a `.so` from before the
 * edit you are trying to test is the failure the old ProgramRow was there to
 * prevent (docs/UI.md — `stale — edited since the last build`), and it now
 * lives here rather than in a band of its own. Its trailing slot is the
 * artifact path — or, while a deck verb is blocked, that verb's reason
 * ([deckReadout]: "Deploy · needs a build"), which flashes the warning ink
 * when the blocked key is tapped ([blockedTaps]). The deck's keys never
 * print a reason themselves; this is where it lives.
 */
@Composable
private fun BuildStatusStrip(state: ShellState, layout: ProjectLayout?, context: Context, blockedTaps: Int) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalThraggColors.current
    val running = BuildRunner.isRunning
    val issues = BuildRunner.lastIssues
    val errors = issues.count { it.severity == DiagnosticSeverity.Error }
    val warnings = issues.size - errors
    // A tap on a blocked key answers here: the readout goes warnInk for one
    // TINT and returns on effectSpec (a cut under reduce-motion).
    var flashing by remember { mutableStateOf(false) }
    LaunchedEffect(blockedTaps) {
        if (blockedTaps == 0) return@LaunchedEffect
        flashing = true
        delay(Durations.TINT.toLong())
        flashing = false
    }
    val readoutInk by animateColorAsState(
        targetValue = if (flashing) colors.warnInk else scheme.onSurfaceVariant,
        animationSpec = effectSpec(),
        label = "strip-readout-ink",
    )
    // The first blocked verb's reason, or null for the path. Quiet while
    // running (drawnReason): the spinner row is the whole strip then, and a
    // blocked tap flashes nothing rather than "· building…".
    val toolchainReady = unavailableReason(context, layout) == null
    val readout = drawnReason(
        deckReadout(
            listOf(BuildAction.Build, BuildAction.Test, BuildAction.Deploy).map { action ->
                action to verbReason(action, toolchainReady, layout, BuildRunner.freshness, running = false)
            },
        ),
        running,
    )

    // `transitionSpec` is not composable: the fade is resolved here, which
    // is also the one place reduce-motion is read for it (LevelSlider.kt).
    val fade = effectSpec<Float>()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(MD.stripHeight)
            .padding(horizontal = MD.space4),
        contentAlignment = Alignment.CenterStart,
    ) {
        // The strip's contents crossfade as a run starts and as it ends: dot
        // and "Built" to ticker and "Building", and back. The strip itself
        // never moves — it is 36dp whichever it is saying.
        AnimatedContent(
            targetState = running,
            transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
            contentAlignment = Alignment.CenterStart,
            label = "build-strip",
            modifier = Modifier.fillMaxWidth(),
        ) { shownRunning ->
            Row(
                modifier = Modifier.fillMaxWidth().height(MD.stripHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MD.space2),
            ) {
                if (shownRunning) {
                    // "Going", and no seconds: the clock is in the Stop key.
                    ThraggSpinner(size = 12.dp)
                    Text(
                        text = BuildRunner.runningAction?.progressLabel ?: "Working",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    val failed = state.build is BuildState.Failed
                    StatusDot(
                        color = when {
                            failed -> colors.removedMark
                            BuildRunner.freshness is ArtifactFreshness.Stale -> colors.warnMark
                            state.build is BuildState.Succeeded -> colors.addedMark
                            else -> scheme.onSurfaceVariant.copy(alpha = 0.4f)
                        },
                        size = 8.dp,
                    )
                    Text(
                        text = restLabel(state),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (readout != null) {
                        Text(
                            text = readout,
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TabularNums),
                            color = readoutInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Text(
                            text = stripPath(
                                layout?.primary?.artifactPath,
                                failed,
                                BuildRunner.freshness,
                            ),
                            // The buffer's face, because it is a path: the same
                            // figure in the same face as the editor's tab and the
                            // log's own rows.
                            style = MonoSmall.copy(color = scheme.onSurfaceVariant),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    IssueCounts(errors, warnings)
                }
            }
        }
    }
}

/**
 * The artifact path the strip prints in its trailing slot, or nothing.
 *
 * Nothing after a failed run, and nothing with no artifact on disk. The slot
 * used to print the path unconditionally, so a build that failed showed the
 * `.so` from *before* it beside the word "Failed" — which reads as "here is
 * what it produced" — and a project that had never built showed a path to a
 * file that was not there (QA P-09). Pure (FailureCardTest).
 */
internal fun stripPath(
    artifactPath: String?,
    failed: Boolean,
    freshness: ArtifactFreshness,
): String = when {
    artifactPath == null -> ""
    failed -> ""
    freshness is ArtifactFreshness.Missing -> ""
    else -> artifactPath
}

/** What the strip says when nothing is running. Never a duration: none is kept. */
private fun restLabel(state: ShellState): String = when {
    state.build is BuildState.Failed -> "Failed"
    BuildRunner.freshness is ArtifactFreshness.Missing -> "Not built"
    BuildRunner.freshness is ArtifactFreshness.Stale -> "Stale"
    state.build is BuildState.Succeeded -> "Built"
    else -> "Built"
}

/**
 * `2 errors · 3 warnings`, in the solved inks and in tabular figures.
 *
 * Not `DiffStatLabel`: that component's job is an added/removed PAIR and it
 * prints a signed `+24 −6`. This is two counts of two different things, and
 * either can be absent — a build with three warnings and no errors says one
 * word, not `0 errors`.
 */
@Composable
private fun IssueCounts(errors: Int, warnings: Int) {
    if (errors == 0 && warnings == 0) return
    val colors = LocalThraggColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(MD.space1)) {
        if (errors > 0) {
            Text(
                text = "$errors ${plural(errors, "error")}",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFeatureSettings = TabularNums,
                ),
                color = colors.removedInk,
            )
        }
        if (warnings > 0) {
            Text(
                text = "$warnings ${plural(warnings, "warning")}",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFeatureSettings = TabularNums,
                ),
                color = colors.warnInk,
            )
        }
    }
}

private fun plural(count: Int, word: String) = if (count == 1) word else "${word}s"

/**
 * The title on the failure notice: which verb failed.
 *
 * "The build failed" was hard-coded, so a failed deploy and a failed test both
 * claimed a build had failed — four independent QA reports (G-02). Null is a
 * failure this process did not run (a verdict restored with the shell), and
 * "The build failed" is the honest fallback there: Build is what the ▶ does.
 */
internal fun failureTitle(action: BuildAction?): String = when (action) {
    BuildAction.Test -> "The tests failed"
    BuildAction.Deploy -> "The deploy failed"
    else -> "The build failed"
}

/**
 * The sentence on the failure notice: what ran, and what it found.
 *
 * The counts come from the run's own [FailureFacts] rather than from
 * `BuildRunner.lastIssues`, because those two can disagree by design — a run
 * that died on a linker error the parser did not recognise has a failure with
 * no issues in it, and the notice has to say so rather than claim zero
 * problems above an empty card list.
 *
 * The order is the fix for G-02's first half. A run that produced no *errors*
 * did not fail because of its warnings, and saying "anchor test reported 8
 * warnings" about a test that died with "Attempt to load a program that does
 * not exist" is a false cause. Errors are quoted first, then the log's own
 * last word ([FailureFacts.detail]), and the warning count only when there is
 * nothing better to say.
 */
internal fun failureBody(failure: FailureFacts?, command: String): String {
    val head = command.ifBlank { "The last run" }
    val errors = failure?.errors ?: 0
    val warnings = failure?.warnings ?: 0
    val counts = buildList {
        if (errors > 0) add("$errors ${plural(errors, "error")}")
        if (warnings > 0) add("$warnings ${plural(warnings, "warning")}")
    }
    val detail = failure?.detail?.trim().orEmpty()
    return when {
        errors > 0 -> "$head reported ${counts.joinToString(" and ")}."
        detail.isNotEmpty() -> "$head stopped: $detail"
        counts.isNotEmpty() -> "$head reported ${counts.joinToString(" and ")}."
        else -> "$head stopped without finishing. The log has what it printed."
    }
}

/** The notices, the problems, and the log. */
@Composable
private fun BuildBody(
    state: ShellState,
    context: Context,
    root: String,
    layout: ProjectLayout?,
    onRetry: (BuildAction?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reason = unavailableReason(context, layout)
    val issues = BuildRunner.lastIssues
    val failed = !BuildRunner.isRunning && state.build is BuildState.Failed
    // Cards are for ERRORS. The first Anchor build of any project prints
    // eight `unexpected cfg` warnings from anchor-lang itself, and previewing
    // whatever came first buried a *successful* build under three full-width
    // warning cards — the loudest thing on a screen whose news was good. A
    // warning's whole representation here is the strip's count and one quiet
    // chip into Problems below.
    val preview =
        if (BuildRunner.isRunning) {
            emptyList()
        } else {
            issues.filter { it.severity == DiagnosticSeverity.Error }.take(PREVIEW_ISSUES)
        }
    // With no log yet the reason IS the page, so it is drawn once, by the
    // empty state, with the Setup button on it. Printing it as a NoticeCard as
    // well would put the same sentence twice on a screen that has nothing else
    // on it. Over a log the card is right — it is a warning about the output
    // below it — so the card comes back the moment there is output.
    val log = BuildRunner.log
    val notice = reason.takeIf { log.rows.isNotEmpty() }
    // Issues, not preview: a warnings-only run previews no cards but still
    // owns the one chip into Problems.
    val header = notice != null || failed || (!BuildRunner.isRunning && issues.isNotEmpty())
    // What the header said last, kept for its exit: a new run clears the
    // issues and the failure the frame it starts, and a header that shrank
    // away empty would be a bar collapsing over nothing. Plain fields, not
    // snapshot state — the composable re-runs on its inputs anyway.
    val hold = remember { HeaderHold() }
    if (header) {
        hold.notice = notice
        hold.failed = failed
        hold.failure = BuildRunner.lastFailure
        hold.command = BuildRunner.lastCommand
        hold.issues = issues
        hold.preview = preview
    }
    val reduce = LocalReduceMotion.current

    Column(modifier = modifier.fillMaxSize()) {
        // What the last deploy left: the id, the link, the sheet (DeployedCard.kt).
        DeployedCard(state = state, root = root, layout = layout)
        // The header expands in as a run ends badly and shrinks out as the
        // next one starts, on the band durations every strip uses; the log
        // island below takes the height back on the same motion.
        AnimatedVisibility(
            visible = header,
            enter = if (reduce) {
                fadeIn(snap())
            } else {
                expandVertically(tween(Durations.BAND_IN)) + fadeIn(tween(Durations.BAND_IN))
            },
            exit = if (reduce) {
                fadeOut(snap())
            } else {
                shrinkVertically(tween(Durations.BAND_OUT)) + fadeOut(tween(Durations.BAND_OUT))
            },
            label = "build-header",
        ) {
            val shownNotice = hold.notice
            val shownFailed = hold.failed
            val shownFailure = hold.failure
            val shownIssues = hold.issues
            val shownPreview = hold.preview
            Column {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateSize()
                    .padding(horizontal = MD.space4, vertical = MD.space3),
                verticalArrangement = Arrangement.spacedBy(MD.space2),
            ) {
                if (shownNotice != null) {
                    NoticeCard(
                        severity = Severity.Warn,
                        title = null,
                        body = shownNotice.message,
                        actions = {
                            if (shownNotice.setup) {
                                ThraggChip(
                                    label = "Set up the toolchain",
                                    onClick = { state.push(Route.Setup) },
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                    )
                } else if (shownFailed) {
                    // The third tier of the error model: not a toast, not a
                    // banner — a card that STAYS, in the place the thing went
                    // wrong, with the ways out on it (docs/VISUAL.md, "What we
                    // deliberately do not copy").
                    NoticeCard(
                        severity = Severity.Error,
                        title = failureTitle(shownFailure?.action),
                        body = failureBody(shownFailure, hold.command),
                        actions = {
                            ThraggChip(
                                label = "Retry",
                                onClick = { onRetry(shownFailure?.action) },
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            // Only for a failure a compiler produced. A deploy
                            // that ran out of SOL and a test that called a
                            // program nobody deployed are not code the agent
                            // can fix, and offering it there sent an empty
                            // prompt (QA G-02).
                            if (shownIssues.isNotEmpty()) {
                                ThraggChip(
                                    label = "Fix with agent",
                                    onClick = {
                                        askAgent(
                                            state,
                                            context,
                                            BuildDiagnostics.agentPrompt(
                                                shownIssues,
                                                hold.command,
                                            ),
                                        )
                                    },
                                )
                            }
                            // Two, not three: NoticeCard's action row does not
                            // wrap, and a third chip runs off a 400dp card.
                            // Problems is reachable from the overflow and from
                            // the "N more" chip under these cards.
                        },
                    )
                }
                for (issue in shownPreview) {
                    BuildIssueCard(
                        issue = issue,
                        onClick = { openIssue(state, issue, root) },
                    )
                }
                if (shownIssues.size > shownPreview.size) {
                    // With error cards above it this counts the rest; with
                    // none — a run that produced only warnings — it is the
                    // single, whole representation the warnings get here.
                    //
                    // IT NAMES ITS OWN SCOPE. "8 warnings in Problems"
                    // promised the number the Problems route shows, which is
                    // a different set entirely: this run's rows plus every
                    // one the language server has about the project, each
                    // said once ([mergedProblems]). The device read 8 here
                    // and 19 there and both were right about different
                    // questions (QA G-19). The count is this build's; the tap
                    // is still the way to the list that holds it.
                    val rest = shownIssues.size - shownPreview.size
                    ThraggChip(
                        label = if (shownPreview.isEmpty()) {
                            "$rest ${plural(rest, "warning")} from this build"
                        } else {
                            "$rest more from this build"
                        },
                        onClick = { state.push(Route.Problems) },
                    )
                }
            }
            HairlineDivider(modifier = Modifier.padding(horizontal = MD.space4))
            }
        }

        BuildLogView(
            state = state,
            log = log,
            projectRoot = root,
            // The empty state's whole job is to say what to do next, and
            // "Press Run" is the wrong answer while Run is greyed.
            unavailable = reason,
            // The log is per-process; the artifact is not. After an app
            // restart the strip says "Built" from disk while the log is
            // empty, and "Nothing built yet" under it called the strip a
            // liar. The empty state adapts instead of contradicting.
            artifactOnDisk = BuildRunner.freshness !is ArtifactFreshness.Missing,
            modifier = Modifier
                .weight(1f)
                .padding(
                    start = MD.space4,
                    end = MD.space4,
                    top = MD.space3,
                    // 24dp so the last line of a log clears the nav bar rather
                    // than dying against it — the rhythm rule for scrolling
                    // content (docs/VISUAL.md, "Foundations", RHYTHM).
                    bottom = MD.space6,
                ),
        )
    }
}

/**
 * One problem, as a card you can tap into the file.
 *
 * The card is the *wrapped, unclipped* presentation of a diagnostic that the
 * log used to carry: an E0609 clipped at 40 columns tells you nothing, so the
 * message takes as many as three lines here and the location sits under it in
 * the buffer's face. The glyph is `warnMark`/`removedMark` — SOLVED marks at
 * 3:1 against a card's real ground — rather than the raw `theme.color("warning")`
 * this screen used to draw, which measures 1.64:1 on Ayu Light.
 *
 * [MD.radiusSm] rather than a card's 12dp: a selectable option row is 8dp by
 * role, and a row that opens a file is that shape.
 */
@Composable
private fun BuildIssueCard(issue: BuildIssue, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalThraggColors.current
    val isError = issue.severity == DiagnosticSeverity.Error
    ThraggCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MD.radiusSm),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // A one-line message would otherwise draw a 40dp row, and this
                // whole card is the target.
                .defaultMinSize(minHeight = MD.rowMin)
                .padding(horizontal = MD.space3, vertical = MD.rowPadY),
            horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
        ) {
            ThraggIcon(
                icon = if (isError) R.drawable.ic_ui_close else R.drawable.ic_ui_warning,
                contentDescription = if (isError) "error" else "warning",
                tint = if (isError) colors.removedMark else colors.warnMark,
                size = IconSize.Inline,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = issue.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val location = issue.location
                val code = issue.code
                if (location != null || code != null) {
                    Text(
                        text = listOfNotNull(location, code).joinToString("  "),
                        style = MonoSmall.copy(color = scheme.onSurfaceVariant),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = MD.space05),
                    )
                }
            }
            RowChevron(modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * Anchor's tests need Node and yarn, and this guest has not got them yet.
 *
 * Said before the run rather than after it. Anchor's scaffolded
 * `[scripts] test` is `yarn run ts-mocha …`, so pressing Test on an Anchor
 * project with no Node fails with a shell error that explains nothing —
 * docs/UI.md calls this out by name ("Test honesty"). Since 2026-09-08 Node
 * is an optional Setup row (manifest.json, `node`), so the first way out is
 * Setup, one tap; the second is real too: `cargo test` runs the program's
 * own Rust tests and needs nothing but the toolchain that is already there.
 * [reason] is [BuildTasks.anchorTestBlockedBy]'s sentence — which of the two
 * is missing — because a Node whose yarn step failed is a different repair
 * from no Node at all.
 */
@Composable
private fun AnchorTestSheet(
    state: ShellState,
    reason: String,
    onDismiss: () -> Unit,
    onSetup: () -> Unit,
    onCargoTest: () -> Unit,
) {
    SheetScaffold(
        state = state,
        onDismiss = onDismiss,
        title = reason,
        actions = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MD.space2),
            ) {
                // One filled button, the way out; the rest outlined with
                // the house edge. Zero elevation in every slot, as always.
                Button(
                    onClick = onSetup,
                    modifier = Modifier.fillMaxWidth(),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                ) {
                    Text("Set up")
                }
                OutlinedButton(
                    onClick = onCargoTest,
                    modifier = Modifier.fillMaxWidth(),
                    border = outlinedButtonEdge(),
                ) {
                    Text("Run cargo test instead")
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    border = outlinedButtonEdge(),
                ) {
                    Text("Not now")
                }
            }
        },
    ) {
        Text(
            text = if (reason.startsWith("yarn")) {
                "Node is installed but its yarn step did not finish: the Node row in Setup " +
                    "activates yarn through corepack, over the network, after the download. " +
                    "Retry that row in Setup and come back, and Test will run `anchor test` " +
                    "against the program deployed under Anchor.toml's cluster.\n\n" +
                    "`cargo test` runs the program's own Rust tests and works today."
            } else {
                "Anchor's scaffolded test script is `yarn run ts-mocha`, which runs on " +
                    "Node. Node (with yarn) is an optional part of Setup, about 57 MB to " +
                    "download; install it and come back, and Test will run `anchor test` " +
                    "against the program deployed under Anchor.toml's cluster.\n\n" +
                    "`cargo test` runs the program's own Rust tests and works today."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = MD.space4, vertical = MD.space2),
        )
    }
}

/** Why the run control is dead, when it is. */
/**
 * Why Run is greyed, in the words the screen will use.
 *
 * [headline] exists because this is read in two places that need two lengths:
 * the [NoticeCard] over a log prints the sentence alone, and [BuildLogView]'s
 * empty state needs a title over it. Internal rather than private so the log
 * view can take one — the empty state that says "Press Run" has to be able to
 * know that Run cannot be pressed.
 */
internal data class Unavailable(
    val headline: String,
    val message: String,
    /** Whether Setup is the way out, and therefore whether to offer it. */
    val setup: Boolean,
)

/** The header's last contents, held through its exit — see [BuildBody]. */
private class HeaderHold {
    var notice: Unavailable? = null
    /** Whether the run failed, held so the card does not blank out mid-exit. */
    var failed: Boolean = false
    var failure: FailureFacts? = null
    /** What ran, held with the rest so the body does not change under its exit. */
    var command: String = ""
    var issues: List<BuildIssue> = emptyList()
    var preview: List<BuildIssue> = emptyList()
}

/**
 * Why a verb is greyed, as data — or null when it is not.
 *
 * The gate for Test and Deploy, in one pure function, so the overflow and
 * whatever control a screen draws beside the log agree about it. The words
 * are the short ones a control can print under itself: `no toolchain`,
 * `open a project`, `needs a build`, `building…`. A STALE artifact is not a
 * blocker — deploying a `.so` from before the last edit is allowed and the
 * status strip says so — and Deploy asks nothing of the guest: the chain
 * layer signs and sends from Kotlin (solana/chain/ProgramDeploy.kt), so a
 * phone with no toolchain can still ship the artifact it has.
 *
 * [toolchainReady] is `unavailableReason(context, layout) == null`, passed
 * rather than computed so this needs no Context and can be tested as a
 * table (VerbReasonTest).
 */
internal fun verbReason(
    action: BuildAction,
    toolchainReady: Boolean,
    layout: ProjectLayout?,
    freshness: ArtifactFreshness,
    running: Boolean,
): String? = when {
    running -> "building…"
    layout == null || !layout.isBuildable -> "open a project"
    action != BuildAction.Deploy && !toolchainReady -> "no toolchain"
    action == BuildAction.Deploy && freshness is ArtifactFreshness.Missing -> "needs a build"
    else -> null
}

/**
 * What the strip DRAWS of a reason, as against what gates the key. While a
 * run is going the screen already says so twice — the red Stop and the
 * strip's spinner — so a third "building…" was repetition (the owner's
 * note). The keys stay disabled, the reason still gates and is still spoken
 * as `stateDescription`, but no readout is drawn until the run ends; at rest
 * the reason is drawn as given. Pure, so it is a table (DeckReadoutTest).
 */
internal fun drawnReason(reason: String?, running: Boolean): String? = reason.takeUnless { running }

/**
 * The status strip's readout for the deck: the first blocked verb in deck
 * order, said as `Verb · reason`, or null when nothing is blocked and the
 * slot is the artifact path. Callers pass the reasons already resolved so
 * this needs no Context (DeckReadoutTest).
 */
internal fun deckReadout(reasons: List<Pair<BuildAction, String?>>): String? =
    reasons.firstNotNullOfOrNull { (action, reason) -> reason?.let { "${action.label} · $it" } }

internal fun unavailableReason(context: Context, layout: ProjectLayout?): Unavailable? = when {
    !Userland.backend.isSupported -> Unavailable(
        "No Linux guest on this device",
        // No userland means no compiler: Android will not execute a program
        // that arrived after installation. Said plainly rather than shown as a
        // disabled button with no explanation beside it.
        "The Linux guest is not available, so it cannot compile a Solana program. " +
            "Everything else — the editor, git, the agent — works.",
        setup = false,
    )

    layout == null || !BuildRunner.probed -> null

    layout.framework == ProjectFramework.Unknown -> Unavailable(
        "Nothing here to build",
        "No Anchor.toml and no Solana crate here, so there is nothing to build.",
        setup = false,
    )

    !BuildRunner.tools.canCompile -> Unavailable(
        "The toolchain is not installed",
        "The Solana toolchain is not installed yet. It is about 600 MB to download " +
            "and 1.4 GB on disk, and it is what compiles a program to SBF.",
        setup = true,
    )

    // The two optional rows, asked for only by the project that needs them.
    // Said here, before the run, rather than as `anchor: command not found`
    // three lines into a log: both are one tap away in Setup, and a Seahorse
    // project is the one case where "the toolchain is installed" and "this
    // project can build" differ.
    layout.framework == ProjectFramework.Anchor && !BuildRunner.tools.anchor -> Unavailable(
        "Anchor is not installed",
        "This is an Anchor project, and `anchor build` is what compiles it. Anchor is " +
            "an optional part of Setup — install the rest of the toolchain and come back.",
        setup = true,
    )

    layout.framework == ProjectFramework.Seahorse && !BuildRunner.tools.seahorse -> Unavailable(
        "Seahorse is not installed",
        "Seahorse turns this project's Python into an Anchor program before Anchor " +
            "builds it. It is an optional part of Setup and compiles on this phone in " +
            "about two minutes — install the rest of the toolchain and come back.",
        setup = true,
    )

    else -> null
}

// --- the two things the log rows also need ------------------------------------

internal fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("", text))
    Notifications.info("Copied", key = "build:copy")
}

/**
 * Hand [text] to the agent, or to the clipboard when there is no agent to hand
 * it to.
 *
 * The fallback is not an apology. The Agent destination registers
 * [AgentFix.seed] when it composes; before that — and on a device where the
 * user skipped the agent install entirely — the text of a build failure is
 * still the most useful thing on the screen, and putting it on the clipboard
 * with a word about why beats a button that does nothing.
 */
internal fun askAgent(state: ShellState, context: Context, text: String) {
    val seed = AgentFix.seed
    if (seed != null) {
        seed(text)
        state.show(Destination.Agent)
        return
    }
    copyToClipboard(context, text)
    Notifications.info(
        "No agent is set up yet — the build errors are on the clipboard",
        key = "build:agent",
    )
}

/**
 * The whole log as text, for ⋮ → Copy the log.
 *
 * ANSI-stripped: `rustc`'s rendered blocks arrive with SGR escapes in them
 * (`BuildTasks` asks for `json-diagnostic-rendered-ansi`), and a clipboard
 * full of raw control bytes is not a paste anybody wants — see AnsiText.kt.
 */
private fun logText(): String = BuildRunner.log.rows.joinToString("\n") { row ->
    when (row) {
        is to.eyed.thragg.solana.build.BuildLogRow.Command -> "$ ${row.text}"
        is to.eyed.thragg.solana.build.BuildLogRow.Text -> stripAnsi(row.text)
        is to.eyed.thragg.solana.build.BuildLogRow.Progress -> stripAnsi(row.text)
        is to.eyed.thragg.solana.build.BuildLogRow.Note -> "# ${row.text}"
        is to.eyed.thragg.solana.build.BuildLogRow.Summary -> "-- ${row.text}"
        is to.eyed.thragg.solana.build.BuildLogRow.Issue ->
            row.issue.rendered?.let(::stripAnsi) ?: buildString {
                append(row.issue.severity.token)
                append(": ")
                append(row.issue.message)
                row.issue.location?.let { append("\n  --> ").append(it) }
            }
    }
}

/**
 * Three problems on the screen, and the rest behind one chip.
 *
 * A failed build reports dozens; a column of dozens of cards above the log
 * would push the compiler's own output — which is what a developer actually
 * reads — off the bottom of a 890dp screen. Three is what the wireframe shows
 * plus one, and Problems is one tap away and is the screen built for the list.
 */
private const val PREVIEW_ISSUES = 3

/**
 * The toast key every build-lifecycle notification is raised under —
 * `BuildRunner.NOTIFICATION_KEY`, restated because that constant is private
 * to a package this screen only observes. The literal is the contract: it is
 * what lets the ▶ handlers *take back* a "Build failed" that a new run has
 * made stale, and if BuildRunner ever changes its key this must move with it
 * (the symptom would be exactly rehearsal BUG 4 returning: a red failure
 * toast outliving the next successful build).
 */
private const val BUILD_TOAST_KEY = "solana:build"
