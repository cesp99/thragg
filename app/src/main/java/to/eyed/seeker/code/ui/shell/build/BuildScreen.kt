package to.eyed.seeker.code.ui.shell.build

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.R
import to.eyed.seeker.code.solana.build.ArtifactFreshness
import to.eyed.seeker.code.solana.build.AgentFix
import to.eyed.seeker.code.solana.build.BuildAction
import to.eyed.seeker.code.solana.build.BuildDiagnostics
import to.eyed.seeker.code.solana.build.BuildRunner
import to.eyed.seeker.code.solana.build.BuildTasks
import to.eyed.seeker.code.solana.build.ProjectFramework
import to.eyed.seeker.code.solana.build.ProjectLayout
import to.eyed.seeker.code.terminal.Userland
import to.eyed.seeker.code.ui.editor.DiagnosticSeverity
import to.eyed.seeker.code.ui.shell.Destination
import to.eyed.seeker.code.ui.shell.code.CodeBuildSeam
import to.eyed.seeker.code.ui.shell.Route
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.IconSize
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.SeekerIcon
import to.eyed.seeker.code.ui.theme.SeekerIconButton
import to.eyed.seeker.code.ui.theme.touchTarget
import to.eyed.seeker.code.ui.workspace.ContextMenu
import to.eyed.seeker.code.ui.workspace.ContextMenuItem
import to.eyed.seeker.code.ui.workspace.Notifications

/**
 * The Build destination — the screen the app exists for.
 *
 * Which program, what the compiler said, and three buttons, on one column,
 * with the primary action at the bottom right where the thumb rests. The
 * layout is upside down compared to every desktop IDE and that is the point:
 * "reachability inversion" was one of the three judged defects of the base
 * design (docs/UI.md, "Why"), and the fix is that every high-frequency control
 * lives in the bottom third. The 44dp header carries identity and the rare
 * exits; Test/Deploy/Build sit directly above the nav bar.
 *
 * Two orderings inside the button row are deliberate. Build is the widest and
 * the rightmost because it is the one that gets hammered, and Deploy is
 * separated from it by Test because Deploy spends SOL — a mis-tap on the
 * button next to the one you press forty times a session should not be a
 * transaction.
 *
 * The whole destination toggles in place to [ShellTerminal] via the `⌗ Shell`
 * header chip. Shell is not a fourth stop on the bar; see [ShellModes].
 */
@Composable
fun BuildScreen(state: ShellState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val theme = LocalZedTheme.current
    val root = state.project?.rootPath
    val layout = BuildRunner.layout
    val inShell = ShellModes.isShell(root)

    // Detect, probe and stat — all three are blocking, and the probe starts a
    // proot. Keyed on the project and on the toolchain flag, so finishing
    // Setup re-answers "can this device compile" without a restart.
    LaunchedEffect(root, state.toolchainReady) {
        withContext(Dispatchers.IO) { BuildRunner.refresh(context, root) }
    }
    LaunchedEffect(state) { BuildBootstrap.install(state, context) }

    Column(modifier = modifier.fillMaxSize()) {
        BuildHeader(
            state = state,
            projectName = state.project?.rootName ?: "No project",
            subtitle = if (inShell) "Shell" else layout?.label.orEmpty(),
            inShell = inShell,
            onToggleShell = { ShellModes.toggle(root) },
        )
        HorizontalDivider(color = theme.color("border", MaterialTheme.colorScheme.outlineVariant))

        when {
            root == null -> EmptyState(
                "No project is open. Open one from Code to build it."
            )

            inShell -> ShellTerminal(state, root, modifier = Modifier.weight(1f))

            else -> BuildBody(state, context, root, layout, modifier = Modifier.weight(1f))
        }
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
        CodeBuildSeam.run = { _ -> BuildRunner.start(app, state, BuildAction.Build) }
    }
}

/** `escrow ▾   Anchor          ⌗ Shell   ⋮` */
@Composable
private fun BuildHeader(
    state: ShellState,
    projectName: String,
    subtitle: String,
    inShell: Boolean,
    onToggleShell: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    var overflow by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = projectName,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = theme.color("text", MaterialTheme.colorScheme.onSurface),
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.weight(1f),
        )
        // The one control that switches modes. The terminal mark is this app's
        // own glyph for a shell, and the label always names where the tap goes
        // rather than where you are.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .clickable(
                    onClickLabel = if (inShell) "Build" else "Shell",
                    onClick = onToggleShell,
                )
                .touchTarget()
                .padding(horizontal = 4.dp),
        ) {
            SeekerIcon(
                icon = R.drawable.ic_ui_terminal,
                contentDescription = null,
                tint = theme.color("text.accent", MaterialTheme.colorScheme.primary),
                size = IconSize.Marker,
            )
            Text(
                text = if (inShell) "Build" else "Shell",
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
            )
        }
        Box {
            SeekerIconButton(
                icon = R.drawable.ic_ui_more_vertical,
                description = "More",
                onClick = { overflow = true },
                tint = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            )
            ContextMenu(
                expanded = overflow,
                onDismiss = { overflow = false },
                items = listOf(
                    ContextMenuItem("Problems") { state.push(Route.Problems) },
                    ContextMenuItem("Copy the log") {
                        copyToClipboard(context, logText())
                    },
                    ContextMenuItem("Set up the toolchain") { state.push(Route.Setup) },
                ),
            )
        }
    }
}

/** The program row, the log, the result card and the three buttons. */
@Composable
private fun BuildBody(
    state: ShellState,
    context: Context,
    root: String,
    layout: ProjectLayout?,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    Column(modifier = modifier.fillMaxSize()) {
        ProgramRow(layout)
        HorizontalDivider(color = theme.color("border", MaterialTheme.colorScheme.outlineVariant))
        BuildLogView(
            state = state,
            log = BuildRunner.log,
            projectRoot = root,
            modifier = Modifier.weight(1f),
        )
        ResultCard(state, context)
        HorizontalDivider(color = theme.color("border", MaterialTheme.colorScheme.outlineVariant))
        Actions(state, context, layout)
    }
}

/**
 * `escrow  ·  target/deploy/escrow.so  ·  not deployed`.
 *
 * Freshness rather than mere existence: deploying a `.so` from before the edit
 * you are trying to test is the failure this row is here to prevent
 * (docs/UI.md — `stale — edited since the last build`). The program *id* and
 * the deployed/not-deployed half belong to the wallet and cluster layer (P6)
 * and are not invented here.
 */
@Composable
private fun ProgramRow(layout: ProjectLayout?) {
    val theme = LocalZedTheme.current
    val program = layout?.primary
    val freshness = BuildRunner.freshness
    val text = when {
        layout == null -> "Looking at the project…"
        program == null -> layout.label
        else -> program.artifactPath
    }
    val detail = when {
        program == null -> null
        freshness is ArtifactFreshness.Missing -> "not built"
        freshness is ArtifactFreshness.Stale -> "stale — edited since the last build"
        else -> "built"
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = theme.color("text", MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.weight(1f),
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = if (freshness is ArtifactFreshness.Stale) {
                    theme.color("warning", MaterialTheme.colorScheme.tertiary)
                } else {
                    theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
                },
            )
        }
    }
}

/**
 * `[ Fix with agent ]      [ Problems 2 → ]`, on a failed run and only then.
 *
 * "Fix with agent" is the design's central claim made concrete: every error
 * carries a one-tap route to the thing that can fix it. It pushes the failing
 * diagnostics — message, code, `path:line:col` and rustc's own rendered
 * snippet — into the Agent composer and switches destination.
 */
@Composable
private fun ResultCard(state: ShellState, context: Context) {
    val issues = BuildRunner.lastIssues
    if (BuildRunner.isRunning || issues.isEmpty()) return
    val theme = LocalZedTheme.current
    val errors = issues.count { it.severity == DiagnosticSeverity.Error }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlatButton(
            label = "Fix with agent",
            modifier = Modifier.weight(1f),
        ) {
            askAgent(
                state,
                context,
                BuildDiagnostics.agentPrompt(issues, BuildRunner.lastCommand),
            )
        }
        FlatButton(
            label = "Problems ${errors.coerceAtLeast(issues.size)}",
            trailingIcon = R.drawable.ic_ui_arrow_right,
            modifier = Modifier.weight(1f),
        ) {
            state.push(Route.Problems)
        }
    }
    HorizontalDivider(color = theme.color("border", MaterialTheme.colorScheme.outlineVariant))
}

/**
 * Test, Deploy, Build — or one Stop row while something runs, or one card
 * when there is nothing here that can build.
 */
@Composable
private fun Actions(state: ShellState, context: Context, layout: ProjectLayout?) {
    val theme = LocalZedTheme.current
    var testSheet by remember { mutableStateOf(false) }

    // The elapsed counter, ticking only while a run is going. A second is the
    // right resolution for a build measured in minutes and costs one
    // recomposition of one row.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(BuildRunner.isRunning) {
        while (BuildRunner.isRunning) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    if (BuildRunner.isRunning) {
        val started = (state.build as? to.eyed.seeker.code.ui.shell.BuildState.Running)?.startedAt
        val label = BuildRunner.runningAction?.progressLabel ?: "Working"
        Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            FlatButton(
                label = "Stop · $label ${BuildRunner.clock(now - (started ?: now))}",
                icon = R.drawable.ic_ui_stop,
                emphasis = true,
                modifier = Modifier.fillMaxWidth(),
            ) { BuildRunner.stop() }
        }
        return
    }

    val reason = unavailableReason(context, layout)
    if (reason != null) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = reason.message,
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (reason.setup) {
                FlatButton(
                    label = "Set up the toolchain",
                    emphasis = true,
                    modifier = Modifier.fillMaxWidth(),
                ) { state.push(Route.Setup) }
            }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlatButton(label = "Test", modifier = Modifier.weight(1f)) {
            if (layout != null && BuildTasks.anchorTestNeedsNode(layout)) {
                testSheet = true
            } else {
                BuildRunner.start(context, state, BuildAction.Test)
            }
        }
        FlatButton(label = "Deploy", modifier = Modifier.weight(1f)) {
            BuildRunner.start(context, state, BuildAction.Deploy)
        }
        FlatButton(
            label = "Build",
            icon = R.drawable.ic_ui_play,
            emphasis = true,
            modifier = Modifier.weight(1.6f),
        ) {
            BuildRunner.start(context, state, BuildAction.Build)
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
    val theme = LocalZedTheme.current
    SheetScaffold(
        state = state,
        onDismiss = onDismiss,
        title = "Anchor tests need Node",
        actions = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            style = MaterialTheme.typography.bodySmall,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** Why the three buttons are not there, when they are not. */
private data class Unavailable(val message: String, val setup: Boolean)

private fun unavailableReason(context: Context, layout: ProjectLayout?): Unavailable? = when {
    !Userland.backend.isSupported -> Unavailable(
        // The play flavour has no userland at all, and no userland means no
        // compiler: Android will not execute a program that arrived after
        // installation. Said plainly rather than shown as a disabled button.
        "This edition has no Linux userland, so it cannot compile a Solana program. " +
            "Everything else — the editor, git, the agent — works.",
        setup = false,
    )

    layout == null || !BuildRunner.probed -> null

    layout.framework == ProjectFramework.Unknown -> Unavailable(
        "No Anchor.toml and no Solana crate here, so there is nothing to build.",
        setup = false,
    )

    !BuildRunner.tools.canCompile -> Unavailable(
        "The Solana toolchain is not installed yet. It is about 600 MB to download " +
            "and 1.4 GB on disk, and it is what compiles a program to SBF.",
        setup = true,
    )

    else -> null
}

@Composable
private fun EmptyState(message: String) {
    val theme = LocalZedTheme.current
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}

/**
 * The one button shape this screen uses: a filled rectangle with a label, at
 * least 48dp of target, and no icon. [emphasis] is the accent fill the primary
 * action takes.
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
    val theme = LocalZedTheme.current
    val background = if (emphasis) {
        theme.color("element.selected", MaterialTheme.colorScheme.primaryContainer)
    } else {
        theme.color("element.background", MaterialTheme.colorScheme.surfaceVariant)
    }
    val ink = theme.color("text", MaterialTheme.colorScheme.onSurface)
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .clickable(onClickLabel = label, onClick = onClick)
            .touchTarget(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 8.dp),
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
 * [AgentFix.seed] when it composes (P3); before that — and on a device where
 * the user skipped the agent install entirely — the text of a build failure is
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

/** The whole log as text, for ⋮ → Copy the log. */
private fun logText(): String = BuildRunner.log.rows.joinToString("\n") { row ->
    when (row) {
        is to.eyed.seeker.code.solana.build.BuildLogRow.Command -> "$ ${row.text}"
        is to.eyed.seeker.code.solana.build.BuildLogRow.Text -> row.text
        is to.eyed.seeker.code.solana.build.BuildLogRow.Note -> "# ${row.text}"
        is to.eyed.seeker.code.solana.build.BuildLogRow.Summary -> "-- ${row.text}"
        is to.eyed.seeker.code.solana.build.BuildLogRow.Issue ->
            row.issue.rendered ?: buildString {
                append(row.issue.severity.token)
                append(": ")
                append(row.issue.message)
                row.issue.location?.let { append("\n  --> ").append(it) }
            }
    }
}
