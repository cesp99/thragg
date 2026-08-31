package to.eyed.seeker.code.ui.shell.changes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.AgentSessions
import to.eyed.seeker.code.core.GitSession
import to.eyed.seeker.code.ui.git.DiffBody
import to.eyed.seeker.code.ui.git.DiffPane
import to.eyed.seeker.code.ui.git.DiffTarget
import to.eyed.seeker.code.ui.git.GitOps
import to.eyed.seeker.code.ui.shell.Route
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.shell.build.CodeJump
import to.eyed.seeker.code.ui.shell.build.FlatButton
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.workspace.Notifications

/**
 * Diff — the **one** diff surface, shared by the agent's edits and by git.
 *
 * There is no second diff in this app: no split view, no diff-as-a-tab, no
 * editable multibuffer (docs/UI.md, "Diff"). What changes between the two
 * callers is not the renderer but the pair of buttons at the bottom, and which
 * bytes are being compared:
 *
 *  - **An edit the agent is waiting on** is its own pre-edit text against what
 *    the file holds now — [to.eyed.seeker.code.core.AgentReview] carries that
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
 */
@Composable
fun DiffScreen(state: ShellState, route: Route.Diff, modifier: Modifier = Modifier) {
    val theme = LocalZedTheme.current
    val project = state.project
    if (project == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No project is open.",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
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
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (pending != null) {
                // The agent's own diff, not git's: git would compare against
                // the last commit and fold every earlier edit of this thread
                // into the answer, which is not what Keep and Reject are about.
                DiffBody(files = listOf(pending.diff), onOpenFile = ::openInCode)
            } else {
                DiffPane(
                    project = project,
                    target = DiffTarget(path = route.path, staged = route.staged),
                    onOpenFile = ::openInCode,
                )
            }
        }
        HorizontalDivider(color = theme.color("border", MaterialTheme.colorScheme.outlineVariant))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (pending != null) {
                FlatButton(
                    label = "Reject",
                    icon = R.drawable.ic_ui_close,
                    modifier = Modifier.weight(1f),
                ) {
                    AgentSessions.rejectEdits(listOf(route.path))
                    // The bytes this screen is drawing are about to stop
                    // existing; staying here would show a diff of nothing.
                    state.pop()
                }
                FlatButton(
                    label = "Keep",
                    icon = R.drawable.ic_ui_check,
                    emphasis = true,
                    modifier = Modifier.weight(1f),
                ) {
                    AgentSessions.keepEdits(listOf(route.path))
                    state.pop()
                }
            } else {
                FlatButton(label = "Discard…", modifier = Modifier.weight(1f)) {
                    discardAsk = true
                }
                FlatButton(label = "Stage", emphasis = true, modifier = Modifier.weight(1f)) {
                    val started = GitOps.run(project.id, { session.stage(listOf(route.path)) })
                    if (!started) {
                        Notifications.info("Still running the last git command…", key = "git:busy")
                    }
                }
            }
        }
    }

    if (discardAsk) {
        // The file's status is what says whether discarding restores or
        // trashes, and this route does not poll status — so the question is
        // asked in the form that is true either way, and the row's own
        // long-press in Changes is where the exact sentence is (it has the
        // [to.eyed.seeker.code.core.GitChange] to read it from).
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
