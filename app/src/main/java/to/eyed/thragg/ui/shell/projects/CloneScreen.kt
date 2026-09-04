package to.eyed.thragg.ui.shell.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.eyed.thragg.core.ProjectsRoot
import to.eyed.thragg.terminal.CloneState
import to.eyed.thragg.terminal.GitClone
import to.eyed.thragg.terminal.GitCloneUrl
import to.eyed.thragg.ui.components.NoticeCard
import to.eyed.thragg.ui.components.SectionHeader
import to.eyed.thragg.ui.components.Severity
import to.eyed.thragg.ui.components.ZedCodeBlock
import to.eyed.thragg.ui.shell.Destination
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.TabularNums

/**
 * Clone from GitHub — the route, over [GitClone].
 *
 * Nothing about cloning is reimplemented here. [GitClone] owns the whole of
 * it and owns it *outside the composition*, for a reason this screen depends
 * on: a clone is minutes of network on a phone, and leaving the route (or
 * being rotated out of it) must not abandon a half-cloned directory. So this
 * file is a form and a progress readout over `GitClone.state`, and every
 * ending — success, failure, "the guest has no git yet", cancel — is a state
 * of that object rather than a local variable.
 *
 * git lives inside the Linux userland and nowhere else, so a build without
 * one cannot clone at all. `GitClone.isSupported` is false there and the
 * Projects sheet leaves the button out; this screen still says so rather than
 * showing a form that cannot submit, because a route can also be reached by a
 * restored back stack.
 *
 * A FAILURE IS A [NoticeCard], not a red paragraph (docs/VISUAL.md, "New
 * program / Clone"). It has a title, a hue at 10% behind it, and the ways out
 * on the card itself — and git's own words underneath go in a [ZedCodeBlock],
 * because "fatal: could not read Username for 'https://github.com'" is
 * terminal output and reading it in the buffer face beside a red sentence is
 * the difference between a message and a diagnosis.
 */
@Composable
fun CloneScreen(state: ShellState, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    // The name follows the URL until the user edits it, and then it is
    // theirs — the repository a URL names and the project you want to call it
    // are not always the same thing (ProjectPicker.kt had this rule and it is
    // the one piece of that dialog worth carrying across).
    var nameEdited by remember { mutableStateOf(false) }

    val cloneState = GitClone.state
    val error = remember(name, cloneState) {
        name.takeIf { it.isNotBlank() }?.let { ProjectsRoot.nameError(context, it) }
    }

    // Opening the cloned project is GitClone's callback, so it fires whether
    // or not this route is still on screen. `rememberUpdatedState` keeps the
    // lambda handed over pointing at the current composition.
    val shell by rememberUpdatedState(state)
    val onCloned: (String) -> Unit = { path ->
        // [ProjectWork] rather than this route's scope: a clone is minutes of
        // network and this callback routinely arrives after the route has
        // popped — which is exactly when opening the result matters most.
        ProjectWork.launch {
            openProjectInShell(context, shell, path)
            shell.show(Destination.Code)
        }
    }

    LaunchedEffect(cloneState) {
        if (cloneState is CloneState.Finished) {
            GitClone.reset()
            state.pop()
        }
    }
    // A form left behind is a form that opens pre-filled with someone else's
    // failure. The *clone* is not cancelled here — it belongs to GitClone and
    // outlives this route deliberately — only the finished states are cleared.
    DisposableEffect(Unit) {
        onDispose { if (!GitClone.isBusy) GitClone.reset() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MD.space4),
        ) {
            if (!GitClone.isSupported) {
                NoticeCard(
                    severity = Severity.Error,
                    title = "No Linux userland",
                    body = "git only exists inside it, so this build cannot clone.",
                    modifier = Modifier.padding(top = MD.space4),
                )
                return@Column
            }

            when (val current = cloneState) {
                is CloneState.Working -> CloneProgress(
                    phase = current.progress.phase,
                    fraction = current.progress.fraction,
                )

                is CloneState.InstallingGit -> CloneProgress(
                    phase = "Installing git — ${current.step}",
                    fraction = null,
                )

                CloneState.NeedsGit -> NoticeCard(
                    severity = Severity.Info,
                    title = "The guest needs git",
                    // Both halves of "can this userland clone": the binary and
                    // the CA bundle its https support needs. Naming only git
                    // would be a lie on a rootfs that has git alone.
                    body = "The Linux guest still needs git and the CA certificates its " +
                        "https support reads. Install them and carry on?",
                    modifier = Modifier.padding(top = MD.space4),
                )

                is CloneState.Failed -> {
                    NoticeCard(
                        severity = Severity.Error,
                        title = "The clone did not finish",
                        body = current.summary,
                        modifier = Modifier.padding(top = MD.space4),
                    )
                    // git's own words, verbatim: they are often the only thing
                    // that says which host, branch or credential went wrong,
                    // and they are terminal output, so they are drawn as the
                    // island they are.
                    current.detail?.let { detail ->
                        ZedCodeBlock(
                            text = detail,
                            maxLines = 8,
                            modifier = Modifier.padding(top = MD.space2),
                        )
                    }
                }

                else -> {
                    SectionHeader("Repository", modifier = Modifier.padding(top = MD.space4))
                    SheetTextField(
                        value = url,
                        onValueChange = { value ->
                            url = value
                            if (!nameEdited) name = GitCloneUrl.projectName(value).orEmpty()
                        },
                        placeholder = "https://github.com/owner/repo.git",
                        autoFocus = true,
                    )
                    SectionHeader("Project name", modifier = Modifier.padding(top = MD.space6))
                    SheetTextField(
                        value = name,
                        onValueChange = { value -> name = value; nameEdited = true },
                        placeholder = "repo",
                        error = error,
                    )
                    Text(
                        text = "A private repository asks for credentials in a dialog; " +
                            "the clone waits for it rather than hanging.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = MD.space3),
                    )
                }
            }
        }

        if (GitClone.isSupported) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MD.space3, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = MD.space4, vertical = MD.space2),
            ) {
                when (cloneState) {
                    is CloneState.Working, is CloneState.InstallingGit -> CloneActions(
                        cancelLabel = "Leave it running",
                        onCancel = { state.pop() },
                        confirmLabel = "Cancel the clone",
                        confirmEnabled = true,
                        isDestructive = true,
                        // Cancelling also deletes the half-cloned directory,
                        // and killing proot's tracees is its own small mess —
                        // both are GitClone.cancel()'s, not this screen's.
                        onConfirm = { GitClone.cancel() },
                    )

                    CloneState.NeedsGit -> CloneActions(
                        cancelLabel = "Back",
                        onCancel = { GitClone.reset() },
                        confirmLabel = "Install and clone",
                        confirmEnabled = true,
                        onConfirm = { GitClone.installGitAndClone(context, url, name, onCloned) },
                    )

                    is CloneState.Failed -> CloneActions(
                        cancelLabel = "Close",
                        onCancel = { GitClone.reset(); state.pop() },
                        confirmLabel = "Edit and retry",
                        confirmEnabled = true,
                        onConfirm = { GitClone.reset() },
                    )

                    else -> CloneActions(
                        cancelLabel = "Cancel",
                        onCancel = { state.pop() },
                        confirmLabel = "Clone",
                        confirmEnabled = url.isNotBlank() && name.isNotBlank() && error == null,
                        onConfirm = { GitClone.start(context, url, name, onCloned) },
                    )
                }
            }
        }
    }
}

/**
 * The pinned pair, in this screen's four flavours.
 *
 * The same shape as [SheetButtons] and deliberately not a call to it: these
 * live in a `Row` the caller already laid out (one row, four `when` branches),
 * and nesting a second full-width row inside it to get the same two buttons
 * would put the gap in twice.
 */
@Composable
private fun CloneActions(
    cancelLabel: String,
    onCancel: () -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    isDestructive: Boolean = false,
) {
    TextButton(onClick = onCancel) {
        Text(cancelLabel, style = MaterialTheme.typography.labelLarge)
    }
    Button(
        onClick = onConfirm,
        enabled = confirmEnabled,
        colors = if (isDestructive) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(confirmLabel, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Phase and a bar.
 *
 * With no percentage the bar is full and tinted rather than animated: `git
 * clone` spends whole phases with no number to report, and a bar that crawls
 * on nothing is a bar that lies about progress. Material's own indeterminate
 * indicator is exactly that lie, which is why the unknown case passes 1f to
 * the determinate one instead — the phase line above it is what is carrying
 * "still working", and it changes.
 *
 * The percentage is tabular, because it ticks.
 */
@Composable
private fun CloneProgress(phase: String, fraction: Float?) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = MD.space6)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = phase,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (fraction != null) {
                Text(
                    text = "${(fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFeatureSettings = TabularNums,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LinearProgressIndicator(
            progress = { fraction?.coerceIn(0f, 1f) ?: 1f },
            modifier = Modifier.fillMaxWidth().padding(top = MD.rowPadY),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}
