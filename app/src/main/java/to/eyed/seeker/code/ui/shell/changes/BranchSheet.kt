package to.eyed.seeker.code.ui.shell.changes

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.GitBranchList
import to.eyed.seeker.code.core.GitPanelState
import to.eyed.seeker.code.core.GitSession
import to.eyed.seeker.code.core.ProjectSession
import to.eyed.seeker.code.ui.git.BranchPickerRow
import to.eyed.seeker.code.ui.git.GitOps
import to.eyed.seeker.code.ui.git.branchPickerRows
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.shell.projects.SheetTextField
import to.eyed.seeker.code.ui.theme.IconSize
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.SeekerIcon
import to.eyed.seeker.code.ui.workspace.Notifications

/**
 * The `main ▾` chip's sheet: the branches, and the query as a new one.
 *
 * Forty lines over [branchPickerRows], which is Zed's `process_branches` with
 * its collapse rule (a remote branch some local branch already tracks is not
 * offered — checking it out would detach HEAD for nothing), its ordering
 * (current first, then most recent) and its create entry. Deliberately **not**
 * BranchPicker.kt: that file is 889 lines of create/checkout/track/remote
 * matrix with a force-delete confirmation, and docs/UI.md keeps the row model
 * and drops the matrix.
 *
 * Checkout is refused by git itself when the worktree would lose changes, and
 * git's sentence is what the toast shows — this sheet does not second-guess it
 * with a rule of its own.
 */
@Composable
fun BranchSheet(
    state: ShellState,
    project: ProjectSession,
    session: GitSession,
    status: GitPanelState,
    onDismiss: () -> Unit,
) {
    val theme = LocalZedTheme.current
    var listing by remember(project) { mutableStateOf(GitBranchList()) }
    var query by remember { mutableStateOf("") }

    // `git branch` is a command inside the guest: read once, off the main
    // thread, when the sheet opens. It is a picker, not a live view — a branch
    // appearing while it is open is not a case worth a poll.
    LaunchedEffect(session) {
        listing = withContext(Dispatchers.IO) { session.branches() }
    }

    val rows = remember(listing, query) { branchPickerRows(listing.branches, query) }
    val current = status.branch?.name

    fun run(action: suspend () -> String?) {
        onDismiss()
        val started = GitOps.run(project.id, action)
        if (!started) Notifications.info("Still running the last git command…", key = "git:busy")
    }

    SheetScaffold(
        state = state,
        onDismiss = onDismiss,
        title = "Branches",
        field = {
            SheetTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "filter, or a new branch's name",
            )
        },
    ) {
        listing.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("error", MaterialTheme.colorScheme.error),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is BranchPickerRow.Branch -> BranchRow(
                        name = row.entry.name,
                        leading = null,
                        subject = row.entry.subject,
                        isCurrent = row.entry.name == current,
                        isRemote = row.entry.isRemote,
                        // Checking out a remote branch by its own name is how
                        // a detached HEAD happens; git creates the tracking
                        // branch when the name matches, which is what
                        // `git checkout origin/main` cannot do and
                        // `git checkout main` can.
                        onClick = { run { session.checkoutBranch(row.entry.name.substringAfter('/')) } },
                    )

                    is BranchPickerRow.Create -> BranchRow(
                        name = "New branch “${row.name}”",
                        // Was a *fullwidth* ＋ (U+FF0B) in the label. A phone's
                        // UI face is not obliged to carry it, so the one row
                        // that creates something was the likeliest in the
                        // sheet to draw as tofu.
                        leading = R.drawable.ic_ui_plus,
                        subject = "from ${current ?: "HEAD"}",
                        isCurrent = false,
                        isRemote = false,
                        onClick = { run { session.createBranch(row.name) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun BranchRow(
    name: String,
    subject: String,
    isCurrent: Boolean,
    isRemote: Boolean,
    /** Drawn in the tick's slot instead of it — the create row's plus. */
    @DrawableRes leading: Int?,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // A fixed slot, so every name in the list starts at the same x
        // whether or not its row has a mark. The old blank-string spacer held
        // the column only by accident of the face's space width.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(IconSize.Marker),
        ) {
            val mark = leading ?: R.drawable.ic_ui_check.takeIf { isCurrent }
            if (mark != null) {
                SeekerIcon(
                    icon = mark,
                    // Decoration for the create row (its label says "New
                    // branch"); for the current branch the row below carries
                    // the word, so the tick is not the only thing saying it.
                    contentDescription = null,
                    tint = theme.color("text.accent", MaterialTheme.colorScheme.primary),
                    size = IconSize.Marker,
                )
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
            color = theme.color("text", MaterialTheme.colorScheme.onSurface),
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = if (isRemote) "remote" else subject,
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
