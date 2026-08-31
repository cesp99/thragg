package to.eyed.seeker.code.ui.shell.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.eyed.seeker.code.core.ProjectsRoot
import to.eyed.seeker.code.terminal.CloneState
import to.eyed.seeker.code.terminal.GitClone
import to.eyed.seeker.code.terminal.GitCloneUrl
import to.eyed.seeker.code.ui.shell.Destination
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.LocalZedTheme

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
 * Projects sheet leaves the row out; this screen still says so rather than
 * showing a form that cannot submit, because a route can also be reached by a
 * restored back stack.
 */
@Composable
fun CloneScreen(state: ShellState, modifier: Modifier = Modifier) {
    val theme = LocalZedTheme.current
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
                .padding(horizontal = ScreenPadding),
        ) {
            if (!GitClone.isSupported) {
                Message(
                    "This build has no Linux userland, and git only exists inside it. " +
                        "Cloning needs the full edition.",
                    isError = true,
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

                CloneState.NeedsGit -> Message(
                    // Both halves of "can this userland clone": the binary and
                    // the CA bundle its https support needs. Naming only git
                    // would be a lie on a rootfs that has git alone.
                    "The Linux guest still needs git and the CA certificates its " +
                        "https support reads. Install them and carry on?"
                )

                is CloneState.Failed -> {
                    Message(current.summary, isError = true)
                    // git's own words, verbatim: they are often the only thing
                    // that says which host, branch or credential went wrong.
                    current.detail?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                else -> {
                    FormLabel("Repository")
                    SheetTextField(
                        value = url,
                        onValueChange = { value ->
                            url = value
                            if (!nameEdited) name = GitCloneUrl.projectName(value).orEmpty()
                        },
                        placeholder = "https://github.com/owner/repo.git",
                        autoFocus = true,
                    )
                    FormLabel("Project name")
                    SheetTextField(
                        value = name,
                        onValueChange = { value -> name = value; nameEdited = true },
                        placeholder = "repo",
                        error = error,
                    )
                    Message(
                        "A private repository asks for credentials in a dialog; " +
                            "the clone waits for it rather than hanging."
                    )
                }
            }
        }

        if (GitClone.isSupported) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = ScreenPadding, vertical = 8.dp),
            ) {
                when (cloneState) {
                    is CloneState.Working, is CloneState.InstallingGit -> SheetButtons(
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

                    CloneState.NeedsGit -> SheetButtons(
                        cancelLabel = "Back",
                        onCancel = { GitClone.reset() },
                        confirmLabel = "Install and clone",
                        confirmEnabled = true,
                        onConfirm = { GitClone.installGitAndClone(context, url, name, onCloned) },
                    )

                    is CloneState.Failed -> SheetButtons(
                        cancelLabel = "Close",
                        onCancel = { GitClone.reset(); state.pop() },
                        confirmLabel = "Edit and retry",
                        confirmEnabled = true,
                        onConfirm = { GitClone.reset() },
                    )

                    else -> SheetButtons(
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
 * Phase and a bar. With no percentage the bar is full and tinted rather than
 * animated: `git clone` spends whole phases with no number to report, and a
 * bar that crawls on nothing is a bar that lies about progress.
 */
@Composable
private fun CloneProgress(phase: String, fraction: Float?) {
    val theme = LocalZedTheme.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = phase,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (fraction != null) {
                Text(
                    text = "${(fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
        Box(
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .height(4.dp)
                .background(
                    theme.color("element.background", MaterialTheme.colorScheme.surfaceVariant),
                    RoundedCornerShape(2.dp),
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction?.coerceIn(0f, 1f) ?: 1f)
                    .fillMaxHeight()
                    .background(
                        theme.color("element.selected", MaterialTheme.colorScheme.primary),
                        RoundedCornerShape(2.dp),
                    )
            )
        }
    }
}

@Composable
private fun FormLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = LocalZedTheme.current.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

private val ScreenPadding = 16.dp
