@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package to.eyed.thragg.ui.shell.changes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import to.eyed.thragg.R
import to.eyed.thragg.core.AgentSessions
import to.eyed.thragg.core.GitSession
import to.eyed.thragg.ui.components.outlinedButtonEdge
import to.eyed.thragg.ui.git.DiffBody
import to.eyed.thragg.ui.git.DiffPane
import to.eyed.thragg.ui.git.DiffTarget
import to.eyed.thragg.ui.git.GitOps
import to.eyed.thragg.ui.shell.Route
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.components.HairlineDivider
import to.eyed.thragg.ui.components.SeekerTopBar
import to.eyed.thragg.ui.shell.build.CodeJump
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.SeekerIcon
import to.eyed.thragg.ui.theme.ZedSurface
import to.eyed.thragg.ui.workspace.Notifications

/**
 * Diff — the **one** diff surface, shared by the agent's edits and by git.
 *
 * There is no second diff in this app: no split view, no diff-as-a-tab, no
 * editable multibuffer (docs/UI.md, "Diff"). What changes between the two
 * callers is not the renderer but the pair of buttons at the bottom, and which
 * bytes are being compared:
 *
 *  - **An edit the agent is waiting on** is its own pre-edit text against what
 *    the file holds now — [to.eyed.thragg.core.AgentReview] carries that
 *    diff already — and the answer is ✕ Reject / ✓ Keep, per file, because
 *    [AgentSessions] offers nothing finer.
 *  - **Anything else** is the working tree against the last commit, drawn by
 *    [DiffPane] with its per-hunk Stage / Unstage / Restore on each `@@`
 *    header, and the answer is Discard / Stage.
 *
 * Which of the two it is, is read off the file rather than off the route.
 * A route carrying the answer would have to be right at every push site — the
 * transcript's file chips, the review bar, both blocks of Changes — and the
 * file itself already knows: a path the agent is still waiting on is an agent
 * diff, and the moment it is kept it becomes an ordinary change. That also
 * makes Keep and Reject impossible to reach for a file that has already had
 * its answer, which is the mistake a stale route would cause.
 *
 * The buttons are at the BOTTOM, not beside the ←. Accepting an agent's edit
 * is a high-frequency, consequential, one-handed action on this device, and
 * the top-right corner of a 890dp phone is the furthest point from a thumb.
 *
 * Read-only. Editing happens in Code.
 *
 * **NO VISUAL CHANGE below the bar, and that is the decision** (docs/VISUAL.md,
 * "Diff"). The panes keep every one of their Zed colour reads, their rem
 * metrics, their no-ripple rule and their LTR pin, and this file wraps them in
 * [ZedSurface] to say so out loud: the gutters, the hunk fills, the blame rows
 * (`theme.playerColor(index)`) and the syntax spans have to agree with the same
 * file open in the editor two taps away, and a Material-styled diff would put
 * two different renderings of one hunk on two screens of one app. What is
 * Material here is the chrome around it — the bar above, the hairline, and the
 * two buttons at the bottom.
 */
@Composable
fun DiffScreen(state: ShellState, route: Route.Diff, modifier: Modifier = Modifier) {
    val project = state.project
    if (project == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No project is open.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val session = remember(project) { GitSession(project) }
    val review = rememberAgentReview()
    val pending = review.pending.firstOrNull { it.path == route.path }
    var discardAsk by remember { mutableStateOf(false) }

    /** Leave the diff for the file itself — the header's "open". */
    fun openInCode(path: String) {
        // Popped first, while this route is still the top of the stack it was
        // pushed onto: [ShellState.pop] works on the *current* destination, and
        // switching to Code first would pop Code's own stack instead.
        state.pop()
        CodeJump.to(state, absoluteIn(project.rootPath, path), null, null)
    }

    Column(modifier = modifier.fillMaxSize()) {
        // OUTSIDE the wrapper, above it: the bar belongs to the app half even
        // on the one screen whose body does not (docs/VISUAL.md, "Diff").
        SeekerTopBar(
            title = route.path.substringAfterLast('/'),
            subtitle = route.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() },
            onBack = { state.pop() },
        )
        HairlineDivider()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            ZedSurface {
                if (pending != null) {
                    // The agent's own diff, not git's: git would compare
                    // against the last commit and fold every earlier edit of
                    // this thread into the answer, which is not what Keep and
                    // Reject are about.
                    DiffBody(files = listOf(pending.diff), onOpenFile = ::openInCode)
                } else {
                    DiffPane(
                        project = project,
                        target = DiffTarget(path = route.path, staged = route.staged),
                        onOpenFile = ::openInCode,
                    )
                }
            }
        }
        HairlineDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = MD.space4, vertical = MD.space2),
            horizontalArrangement = Arrangement.spacedBy(MD.space3),
        ) {
            if (pending != null) {
                OutlinedButton(
                    onClick = {
                        AgentSessions.rejectEdits(listOf(route.path))
                        // The bytes this screen is drawing are about to stop
                        // existing; staying here would show a diff of nothing.
                        state.pop()
                    },
                    modifier = Modifier.weight(1f),
                    border = outlinedButtonEdge(),
                ) {
                    ButtonMark(R.drawable.ic_ui_close)
                    Text("Reject")
                }
                Button(
                    onClick = {
                        AgentSessions.keepEdits(listOf(route.path))
                        state.pop()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    ButtonMark(R.drawable.ic_ui_check)
                    Text("Keep")
                }
            } else {
                OutlinedButton(
                    onClick = { discardAsk = true },
                    border = outlinedButtonEdge(),
                    modifier = Modifier.weight(1f),
                ) { Text("Discard…") }
                Button(
                    onClick = {
                        val started = GitOps.run(project.id, { session.stage(listOf(route.path)) })
                        if (!started) {
                            Notifications.info(
                                "Still running the last git command…",
                                key = "git:busy",
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Stage") }
            }
        }
    }

    if (discardAsk) {
        // The file's status is what says whether discarding restores or
        // trashes, and this route does not poll status — so the question is
        // asked in the form that is true either way, and the row's own
        // long-press in Changes is where the exact sentence is (it has the
        // [to.eyed.thragg.core.GitChange] to read it from).
        AlertDialog(
            onDismissRequest = { discardAsk = false },
            title = { Text("Discard ${route.path.substringAfterLast('/')}?") },
            text = {
                Text(
                    "Every uncommitted change to this file goes — back to the last " +
                        "commit if it has one, to the app's trash if it does not. " +
                        "This cannot be undone from here."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    discardAsk = false
                    val started = GitOps.run(project.id, { session.discard(listOf(route.path)) })
                    if (started) {
                        state.pop()
                    } else {
                        Notifications.info("Still running the last git command…", key = "git:busy")
                    }
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { discardAsk = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The glyph before a button's label, at Material's own icon-to-label gap.
 *
 * Decoration: `Keep` and `Reject` are words, and the button already reads them
 * out. `ButtonDefaults.IconSpacing` rather than [MD.iconGap] because this one
 * belongs to the stock component's metrics, not to the app's rhythm.
 */
@Composable
private fun ButtonMark(icon: Int) {
    SeekerIcon(
        icon = icon,
        contentDescription = null,
        tint = LocalContentColor.current,
        size = IconSize.Inline,
        modifier = Modifier.padding(end = ButtonDefaults.IconSpacing),
    )
}
