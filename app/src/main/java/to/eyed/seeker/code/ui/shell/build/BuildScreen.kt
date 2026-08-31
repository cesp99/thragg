@file:OptIn(ExperimentalMaterial3Api::class)

package to.eyed.seeker.code.ui.shell.build

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.R
import to.eyed.seeker.code.solana.build.AgentFix
import to.eyed.seeker.code.solana.build.ArtifactFreshness
import to.eyed.seeker.code.solana.build.BuildAction
import to.eyed.seeker.code.solana.build.BuildDiagnostics
import to.eyed.seeker.code.solana.build.BuildIssue
import to.eyed.seeker.code.solana.build.BuildRunner
import to.eyed.seeker.code.solana.build.BuildTasks
import to.eyed.seeker.code.solana.build.ProjectFramework
import to.eyed.seeker.code.solana.build.ProjectLayout
import to.eyed.seeker.code.terminal.Userland
import to.eyed.seeker.code.ui.components.EmptyState
import to.eyed.seeker.code.ui.components.HairlineDivider
import to.eyed.seeker.code.ui.components.NoticeCard
import to.eyed.seeker.code.ui.components.RunTicker
import to.eyed.seeker.code.ui.components.SeekerCard
import to.eyed.seeker.code.ui.components.SeekerChip
import to.eyed.seeker.code.ui.components.SeekerSpinner
import to.eyed.seeker.code.ui.components.SeekerTopBar
import to.eyed.seeker.code.ui.components.Severity
import to.eyed.seeker.code.ui.components.StatusDot
import to.eyed.seeker.code.ui.editor.DiagnosticSeverity
import to.eyed.seeker.code.ui.shell.BuildState
import to.eyed.seeker.code.ui.shell.Destination
import to.eyed.seeker.code.ui.shell.Route
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.shell.code.CodeBuildSeam
import to.eyed.seeker.code.ui.theme.IconSize
import to.eyed.seeker.code.ui.theme.LocalSeekerColors
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.MonoSmall
import to.eyed.seeker.code.ui.theme.RowChevron
import to.eyed.seeker.code.ui.theme.SeekerIcon
import to.eyed.seeker.code.ui.theme.SeekerIconButton
import to.eyed.seeker.code.ui.theme.TabularNums
import to.eyed.seeker.code.ui.theme.accentIcon
import to.eyed.seeker.code.ui.theme.effectSpec
import to.eyed.seeker.code.ui.theme.mutedIcon
import to.eyed.seeker.code.ui.theme.touchTarget
import to.eyed.seeker.code.ui.workspace.ContextMenu
import to.eyed.seeker.code.ui.workspace.ContextMenuItem
import to.eyed.seeker.code.ui.workspace.Notifications

/**
 * The Build destination — the screen the app exists for.
 *
 * FOUR BANDS, TOP TO BOTTOM (docs/VISUAL.md, "Every other screen" → Build): a
 * 56dp [SeekerTopBar] carrying the target as its subtitle and the run control
 * as a filled button; a 36dp [BuildStatusStrip] that *reports* and never acts;
 * the problems as [SeekerCard]s you can tap into Code; and the log as a Zed
 * island in the buffer's own face. Everything except that island is Material —
 * `MaterialTheme.colorScheme` and `LocalSeekerColors`, no `theme.color(...)`
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
 * The whole destination still toggles in place to [ShellTerminal]; see
 * [ShellModes].
 */
@Composable
fun BuildScreen(state: ShellState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val root = state.project?.rootPath
    val layout = BuildRunner.layout
    val inShell = ShellModes.isShell(root)
    var testSheet by remember { mutableStateOf(false) }

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

    Column(modifier = modifier.fillMaxSize()) {
        BuildBar(
            state = state,
            context = context,
            root = root,
            layout = layout,
            inShell = inShell,
            onTest = {
                if (layout != null && BuildTasks.anchorTestNeedsNode(layout)) {
                    testSheet = true
                } else {
                    BuildRunner.start(context, state, BuildAction.Test)
                }
            },
        )
        // The seam under a flat bar. Nothing tints on scroll anywhere in this
        // app, so a hairline is what separates a bar from its content.
        HairlineDivider()

        // No project means nothing to report, and a strip that says "Not
        // built" about a project that does not exist is noise with a border.
        if (!inShell && root != null) {
            BuildStatusStrip(state, layout)
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

            else -> BuildBody(state, context, root, layout, modifier = Modifier.weight(1f))
        }
    }

    if (testSheet) {
        AnchorTestSheet(
            state = state,
            onDismiss = { testSheet = false },
            onCargoTest = {
                testSheet = false
                BuildRunner.start(context, state, BuildAction.Test, BuildTasks.cargoTestCommand())
            },
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
 * than one that never worked. **This is called from the Build destination's
 * composition, so the editor's ▶ works from the moment Build has been opened
 * once.** Making it work on the very first frame of a cold start needs one
 * line in `SeekerShell.kt`, which wave 1 owns — see this chunk's handoffs.
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
        CodeBuildSeam.run = { _ ->
            Notifications.dismissKey(BUILD_TOAST_KEY)
            BuildRunner.start(app, state, BuildAction.Build)
        }
    }
}

/** `Build / escrow · Anchor        [terminal] [▶] [⋮]` */
@Composable
private fun BuildBar(
    state: ShellState,
    context: Context,
    root: String?,
    layout: ProjectLayout?,
    inShell: Boolean,
    onTest: () -> Unit,
) {
    var overflow by remember { mutableStateOf(false) }
    val projectName = state.project?.rootName
    val runnable = layout != null && unavailableReason(context, layout) == null

    SeekerTopBar(
        title = if (inShell) "Shell" else "Build",
        subtitle = when {
            projectName == null -> "No project open"
            inShell -> "$projectName · terminal"
            layout != null -> "$projectName · ${layout.label}"
            else -> projectName
        },
        actions = {
            // The mode switch, and the app's only route to a terminal. The
            // label always names where the tap GOES rather than where you are,
            // which is the rule the old `⌗ Shell` chip already followed.
            SeekerIconButton(
                icon = R.drawable.ic_ui_terminal,
                description = if (inShell) "Leave the shell" else "Open the shell",
                onClick = { ShellModes.toggle(root) },
                tint = if (inShell) accentIcon else mutedIcon,
                enabled = root != null,
            )
            if (!inShell) {
                RunControl(
                    running = BuildRunner.isRunning,
                    enabled = runnable || BuildRunner.isRunning,
                    onClick = {
                        if (BuildRunner.isRunning) {
                            BuildRunner.stop()
                        } else {
                            BuildRunner.start(context, state, BuildAction.Build)
                        }
                    },
                )
            }
            Box {
                SeekerIconButton(
                    icon = R.drawable.ic_ui_more_vertical,
                    description = "More",
                    onClick = { overflow = true },
                    tint = mutedIcon,
                )
                ContextMenu(
                    expanded = overflow,
                    onDismiss = { overflow = false },
                    items = listOf(
                        ContextMenuItem("Test", enabled = runnable, onClick = onTest),
                        ContextMenuItem("Deploy", enabled = runnable) {
                            BuildRunner.start(context, state, BuildAction.Deploy)
                        },
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
 * One filled 40dp control in 48dp of target, and the only thing on this screen
 * that starts or stops work.
 *
 * Hand-rolled rather than a `FilledIconButton` for two small reasons that add
 * up. The fill has to CROSS between three states — disabled, armed, running —
 * and `IconButtonColors` is a static triple that swaps on recomposition, where
 * this tweens on [effectSpec] so the change of meaning is a 200ms colour move
 * rather than a frame swap. And the shape is [MD.radiusMd]: a 12dp square, not
 * the circle 1.4.0's filled icon button draws, because every other filled
 * thing on this screen is a rounded rectangle. [touchTarget] is applied
 * explicitly so the 48dp hit box around the 40dp drawn square is readable at
 * this call site rather than inherited from a default.
 */
@Composable
private fun RunControl(running: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        targetValue = when {
            !enabled -> scheme.surfaceContainerHigh
            running -> scheme.errorContainer
            else -> scheme.primary
        },
        animationSpec = effectSpec(),
        label = "build-run-fill",
    )
    val ink = when {
        !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.38f)
        running -> scheme.onErrorContainer
        else -> scheme.onPrimary
    }
    val label = if (running) "Stop the build" else "Build"
    Box(
        modifier = Modifier
            .touchTarget()
            .size(40.dp)
            .clip(RoundedCornerShape(MD.radiusMd))
            .background(fill)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SeekerIcon(
            icon = if (running) R.drawable.ic_ui_stop else R.drawable.ic_ui_play,
            contentDescription = label,
            tint = ink,
            size = IconSize.Action,
        )
    }
}

/**
 * `◐ 2m 08s   anchor build            3 warnings` — 36dp, and it reports.
 *
 * A bar that acts is 48dp and a bar that reports is [MD.stripHeight]; this one
 * has no target in it at all, which is why it can be that short. Running, it
 * is the app's shared [RunTicker] — the same spinner cadence, the same
 * `TabularNums` clock and the same single spoken semantics node the Agent's
 * status strip uses, so "how long has this been going" looks and sounds
 * identical whichever thing is going.
 *
 * At rest it is a [StatusDot] and the artifact's freshness, which is the one
 * fact this screen exists to keep honest: deploying a `.so` from before the
 * edit you are trying to test is the failure the old ProgramRow was there to
 * prevent (docs/UI.md — `stale — edited since the last build`), and it now
 * lives here rather than in a band of its own.
 */
@Composable
private fun BuildStatusStrip(state: ShellState, layout: ProjectLayout?) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalSeekerColors.current
    val running = BuildRunner.isRunning
    val issues = BuildRunner.lastIssues
    val errors = issues.count { it.severity == DiagnosticSeverity.Error }
    val warnings = issues.size - errors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MD.stripHeight)
            .padding(horizontal = MD.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space2),
    ) {
        if (running) {
            val startedAt = (state.build as? BuildState.Running)?.startedAt
            if (startedAt != null) {
                RunTicker(startedAt = startedAt, tokens = null, tint = scheme.primary)
            } else {
                // A run the shell state has not caught up with yet: the
                // spinner still says "going", which is the only claim the
                // strip can honestly make without a start time.
                SeekerSpinner(size = 12.dp)
            }
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
            Text(
                text = layout?.primary?.artifactPath.orEmpty(),
                // The buffer's face, because it is a path: the same figure in
                // the same face as the editor's tab and the log's own rows.
                style = MonoSmall.copy(color = scheme.onSurfaceVariant),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            IssueCounts(errors, warnings)
        }
    }
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
    val colors = LocalSeekerColors.current
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
 * The sentence on the failure notice: what ran, and what it found.
 *
 * The counts come from [BuildState.Failed] rather than from
 * `BuildRunner.lastIssues`, because those two can disagree by design — a run
 * that died on a linker error the parser did not recognise has a failure with
 * no issues in it, and the notice has to say so rather than claim zero
 * problems above an empty card list.
 */
private fun failureBody(failed: BuildState.Failed?): String {
    val command = BuildRunner.lastCommand.ifBlank { "The last run" }
    val counts = buildList {
        val errors = failed?.errors ?: 0
        val warnings = failed?.warnings ?: 0
        if (errors > 0) add("$errors ${plural(errors, "error")}")
        if (warnings > 0) add("$warnings ${plural(warnings, "warning")}")
    }
    return if (counts.isEmpty()) {
        "$command stopped without finishing. The log has what it printed."
    } else {
        "$command reported ${counts.joinToString(" and ")}."
    }
}

/** The notices, the problems, and the log. */
@Composable
private fun BuildBody(
    state: ShellState,
    context: Context,
    root: String,
    layout: ProjectLayout?,
    modifier: Modifier = Modifier,
) {
    val reason = unavailableReason(context, layout)
    val issues = BuildRunner.lastIssues
    val failed = !BuildRunner.isRunning && state.build is BuildState.Failed
    val preview = if (BuildRunner.isRunning) emptyList() else issues.take(PREVIEW_ISSUES)
    // With no log yet the reason IS the page, so it is drawn once, by the
    // empty state, with the Setup button on it. Printing it as a NoticeCard as
    // well would put the same sentence twice on a screen that has nothing else
    // on it. Over a log the card is right — it is a warning about the output
    // below it — so the card comes back the moment there is output.
    val log = BuildRunner.log
    val notice = reason.takeIf { log.rows.isNotEmpty() }
    val header = notice != null || failed || preview.isNotEmpty()

    Column(modifier = modifier.fillMaxSize()) {
        if (header) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MD.space4, vertical = MD.space3),
                verticalArrangement = Arrangement.spacedBy(MD.space2),
            ) {
                if (notice != null) {
                    NoticeCard(
                        severity = Severity.Warn,
                        title = null,
                        body = notice.message,
                        actions = {
                            if (notice.setup) {
                                SeekerChip(
                                    label = "Set up the toolchain",
                                    onClick = { state.push(Route.Setup) },
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                    )
                } else if (failed) {
                    // The third tier of the error model: not a toast, not a
                    // banner — a card that STAYS, in the place the thing went
                    // wrong, with the ways out on it (docs/VISUAL.md, "What we
                    // deliberately do not copy").
                    NoticeCard(
                        severity = Severity.Error,
                        title = "The build failed",
                        body = failureBody(state.build as? BuildState.Failed),
                        actions = {
                            SeekerChip(
                                label = "Retry",
                                onClick = {
                                    BuildRunner.start(context, state, BuildAction.Build)
                                },
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            SeekerChip(
                                label = "Fix with agent",
                                onClick = {
                                    askAgent(
                                        state,
                                        context,
                                        BuildDiagnostics.agentPrompt(
                                            issues,
                                            BuildRunner.lastCommand,
                                        ),
                                    )
                                },
                            )
                            // Two, not three: NoticeCard's action row does not
                            // wrap, and a third chip runs off a 400dp card.
                            // Problems is reachable from the overflow and from
                            // the "N more" chip under these cards.
                        },
                    )
                }
                for (issue in preview) {
                    BuildIssueCard(
                        issue = issue,
                        onClick = { openIssue(state, issue, root) },
                    )
                }
                if (issues.size > preview.size && preview.isNotEmpty()) {
                    SeekerChip(
                        label = "${issues.size - preview.size} more in Problems",
                        onClick = { state.push(Route.Problems) },
                    )
                }
            }
            HairlineDivider(modifier = Modifier.padding(horizontal = MD.space4))
        }

        BuildLogView(
            state = state,
            log = log,
            projectRoot = root,
            // The empty state's whole job is to say what to do next, and
            // "Press Run" is the wrong answer while Run is greyed.
            unavailable = reason,
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
    val colors = LocalSeekerColors.current
    val isError = issue.severity == DiagnosticSeverity.Error
    SeekerCard(
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
            SeekerIcon(
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
 * Anchor's tests need Node, and the manifest ships none.
 *
 * Said before the run rather than after it. Anchor's scaffolded
 * `[scripts] test` is `yarn run ts-mocha …`, so pressing Test on an Anchor
 * project with no Node fails with a shell error that explains nothing —
 * docs/UI.md calls this out by name ("Test honesty"). The alternative offered
 * is real: `cargo test` runs the program's own Rust tests and needs nothing
 * but the toolchain that is already there.
 */
@Composable
private fun AnchorTestSheet(state: ShellState, onDismiss: () -> Unit, onCargoTest: () -> Unit) {
    SheetScaffold(
        state = state,
        onDismiss = onDismiss,
        title = "Anchor tests need Node",
        actions = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MD.space2),
            ) {
                FlatButton(
                    label = "Run cargo test instead",
                    emphasis = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCargoTest,
                )
                FlatButton(
                    label = "Not now",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDismiss,
                )
            }
        },
    ) {
        Text(
            text = "Anchor's scaffolded test script is `yarn run ts-mocha`, and this device " +
                "has no Node — it is not part of the toolchain, and installing it is about " +
                "90 MB in the Shell (`apt install nodejs npm`).\n\n" +
                "`cargo test` runs the program's own Rust tests and works today.",
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

    else -> null
}

/**
 * The flat rectangular button the pre-Material screens were built from.
 *
 * KEPT ON PURPOSE, AND IT IS NOT USED BY THIS SCREEN ANY MORE. Four files in
 * `ui/shell/changes/` import it — ChangesScreen, CommitSheet, DiffScreen and
 * ProblemsScreen — and those are another chunk's to convert; deleting it here
 * would break their build for a cosmetic gain. Its colours are Material now,
 * so the sites that still call it stop being the only raised-looking things
 * left in the app while they wait.
 */
@Composable
internal fun FlatButton(
    label: String,
    modifier: Modifier = Modifier,
    emphasis: Boolean = false,
    /** Drawn before the label — play, stop. Decoration: the label names it. */
    @DrawableRes icon: Int? = null,
    /** Drawn after it, for a button that goes somewhere rather than doing something. */
    @DrawableRes trailingIcon: Int? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val background: Color = if (emphasis) scheme.primary else scheme.surfaceContainerHigh
    val ink: Color = if (emphasis) scheme.onPrimary else scheme.onSurface
    Box(
        modifier = modifier
            .height(MD.rowMin)
            .clip(RoundedCornerShape(MD.radiusMd))
            .background(background)
            .clickable(onClickLabel = label, onClick = onClick)
            .touchTarget(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
            modifier = Modifier.padding(horizontal = MD.space2),
        ) {
            if (icon != null) {
                SeekerIcon(
                    icon = icon,
                    contentDescription = null,
                    tint = ink,
                    size = IconSize.Inline,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = ink,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (trailingIcon != null) {
                SeekerIcon(
                    icon = trailingIcon,
                    contentDescription = null,
                    tint = ink,
                    size = IconSize.Inline,
                )
            }
        }
    }
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
        is to.eyed.seeker.code.solana.build.BuildLogRow.Command -> "$ ${row.text}"
        is to.eyed.seeker.code.solana.build.BuildLogRow.Text -> row.text
        is to.eyed.seeker.code.solana.build.BuildLogRow.Note -> "# ${row.text}"
        is to.eyed.seeker.code.solana.build.BuildLogRow.Summary -> "-- ${row.text}"
        is to.eyed.seeker.code.solana.build.BuildLogRow.Issue ->
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
