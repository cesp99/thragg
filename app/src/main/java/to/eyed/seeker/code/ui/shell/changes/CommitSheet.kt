package to.eyed.seeker.code.ui.shell.changes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.core.GitSession
import to.eyed.seeker.code.core.ProjectSession
import to.eyed.seeker.code.ui.git.GitOps
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.shell.build.FlatButton
import to.eyed.seeker.code.ui.shell.build.askAgent
import to.eyed.seeker.code.ui.shell.projects.SheetTextField
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.workspace.Notifications

/**
 * The commit message, where the keyboard can be under it.
 *
 * Changes keeps the message on a *row* at the bottom of its list and this
 * sheet is what that row opens, for the reason every sheet in this app pins
 * its field at the bottom: an editor 44dp above the navigation bar is an
 * editor the IME covers the instant it is tapped (SheetScaffold.kt, rule 1).
 * The staged files scroll above it, so what is about to be committed stays
 * readable while the message is being written.
 *
 * "Ask the agent for a message" is the one thing here that is not git. It is
 * seeded, never sent: the file list and a request go into the composer and the
 * user finishes the sentence, exactly as `[ Fix with agent ]` does with a
 * build failure (AgentSeams.kt).
 */
@Composable
fun CommitSheet(
    state: ShellState,
    project: ProjectSession,
    session: GitSession,
    model: ChangesModel,
    message: String,
    onMessageChange: (String) -> Unit,
    onCommitted: () -> Unit,
    onPush: () -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    val ops = GitOps.of(project.id)
    val staged = model.stagedPaths
    val canCommit = message.isNotBlank() && model.stagedCount > 0 && !ops.busy

    fun commit(andPush: Boolean) {
        if (!canCommit) return
        onDismiss()
        val started = GitOps.run(
            project.id,
            action = { session.commit(message) },
            onDone = { failure ->
                if (failure != null) return@run
                onCommitted()
                Notifications.info("Committed", key = "git:commit")
                if (andPush) onPush()
            },
        )
        if (!started) Notifications.info("Still running the last git command…", key = "git:busy")
    }

    SheetScaffold(
        state = state,
        onDismiss = onDismiss,
        title = when (model.stagedCount) {
            0 -> "Nothing is staged"
            1 -> "Committing 1 file"
            else -> "Committing ${model.stagedCount} files"
        },
        field = {
            SheetTextField(
                value = message,
                onValueChange = onMessageChange,
                placeholder = "what changed, and why",
                autoFocus = true,
                // A commit message is a subject line and a body; forcing it to
                // one line is how a phone ends up with a repository of
                // one-sentence history.
                singleLine = false,
            )
        },
        actions = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FlatButton(label = "Commit", modifier = Modifier.weight(1f)) { commit(false) }
                FlatButton(
                    label = "Commit & Push",
                    emphasis = true,
                    modifier = Modifier.weight(1f),
                ) { commit(true) }
            }
        },
    ) {
        Text(
            text = "Ask the agent for a message",
            style = MaterialTheme.typography.labelLarge,
            color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = model.stagedCount > 0) {
                    onDismiss()
                    askAgent(state, context, commitMessagePrompt(staged))
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
        if (model.stagedCount == 0) {
            Text(
                text = "Stage something first — the checkbox on a row, or Stage all.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(staged, key = { it }) { path ->
                Column {
                    Text(
                        text = path,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * What "Ask the agent for a message" says.
 *
 * The staged paths and nothing else: the agent has the repository and can read
 * the diff itself, and pasting the patch into a prompt spends the context
 * window on bytes it can fetch. The instruction names the shape a commit
 * message takes so the answer is a message rather than a paragraph about one.
 *
 * Pure, because the wording is the product (CommitPromptTest).
 */
internal fun commitMessagePrompt(staged: List<String>): String = buildString {
    append("Write a commit message for the staged changes")
    if (staged.isNotEmpty()) {
        append(" in ")
        append(staged.take(PROMPT_PATHS).joinToString(", "))
        if (staged.size > PROMPT_PATHS) append(" and ${staged.size - PROMPT_PATHS} more")
    }
    append(".\n\n")
    append("One short subject line in the imperative, then a blank line, then ")
    append("the why if it is not obvious. Answer with the message and nothing else.")
}

/** How many paths a prompt names before it stops listing them. */
private const val PROMPT_PATHS = 8
