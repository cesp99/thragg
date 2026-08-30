package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.LaunchedEffect
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.ProjectSummary
import to.eyed.seeker.code.terminal.CloneState
import to.eyed.seeker.code.terminal.GitClone
import to.eyed.seeker.code.terminal.GitCloneUrl
import to.eyed.seeker.code.ui.theme.LocalZedTheme

/** What the picker is currently asking for. */
private enum class PickerMode { List, NewProject, Clone, ConfirmDelete }

/**
 * Switch, create, clone, import, export and delete projects.
 *
 * Projects are directories in app-private storage; importing copies a folder
 * in from the device rather than opening it in place, because the engine's
 * worktree needs a real path (see `ProjectsRoot`).
 *
 * Cloning is the one thing here that needs the Linux userland — git only
 * exists inside it — so the action is *absent*, not disabled, in a build that
 * has none. See [GitClone].
 */
@Composable
fun ProjectPicker(
    projects: List<ProjectSummary>,
    currentPath: String?,
    busyMessage: String?,
    errorMessage: String?,
    onOpen: (ProjectSummary) -> Unit,
    onCreate: (String) -> Unit,
    onImport: () -> Unit,
    onExport: (ProjectSummary) -> Unit,
    onDelete: (ProjectSummary) -> Unit,
    onDismiss: () -> Unit,
    nameError: (String) -> String?,
    /** Open straight into the clone form — what Ctrl+Shift+G does. */
    startInClone: Boolean = false,
    /** A clone finished: the new project's path, for the workspace to open. */
    onCloned: (String) -> Unit = {},
) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    var mode by remember {
        mutableStateOf(if (startInClone && GitClone.isSupported) PickerMode.Clone else PickerMode.List)
    }
    var pendingDelete by remember { mutableStateOf<ProjectSummary?>(null) }
    var newName by remember { mutableStateOf(TextFieldValue("")) }

    // Kept across mode changes so a failed clone comes back to what was typed
    // rather than to an empty form.
    var cloneUrl by remember { mutableStateOf(TextFieldValue("")) }
    var cloneName by remember { mutableStateOf(TextFieldValue("")) }
    var cloneNameEdited by remember { mutableStateOf(false) }
    val cloneState = GitClone.state

    // A clone that is still running keeps the picker honest about it, even if
    // the user closed the dialog and came back — the work belongs to
    // [GitClone], not to this dialog. Opening the project is [GitClone]'s
    // callback rather than this one, so it happens whether or not the picker
    // is still on screen; all that is left here is clearing the form.
    LaunchedEffect(cloneState) {
        if (GitClone.isBusy) mode = PickerMode.Clone
        if (cloneState !is CloneState.Finished) return@LaunchedEffect
        GitClone.reset()
        cloneUrl = TextFieldValue("")
        cloneName = TextFieldValue("")
        cloneNameEdited = false
        mode = PickerMode.List
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = theme.color("elevated_surface.background", MaterialTheme.colorScheme.surface),
            modifier = Modifier.widthIn(min = 320.dp, max = 520.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text(
                    text = if (mode == PickerMode.Clone) "CLONE A REPOSITORY" else "PROJECTS",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )

                when {
                    busyMessage != null -> Message(busyMessage)

                    mode == PickerMode.NewProject -> NewProjectForm(
                        value = newName,
                        onValueChange = { newName = it },
                        error = newName.text.takeIf { it.isNotEmpty() }?.let(nameError),
                        onCancel = { mode = PickerMode.List; newName = TextFieldValue("") },
                        onConfirm = {
                            onCreate(newName.text)
                            newName = TextFieldValue("")
                            mode = PickerMode.List
                        },
                    )

                    mode == PickerMode.Clone -> CloneForm(
                        state = cloneState,
                        url = cloneUrl,
                        name = cloneName,
                        onUrlChange = { value ->
                            cloneUrl = value
                            // The name follows the URL until the user says
                            // otherwise; after that it is theirs.
                            if (!cloneNameEdited) {
                                cloneName = TextFieldValue(
                                    GitCloneUrl.projectName(value.text).orEmpty()
                                )
                            }
                        },
                        onNameChange = { value ->
                            cloneName = value
                            cloneNameEdited = true
                        },
                        error = cloneName.text.takeIf { it.isNotEmpty() }?.let(nameError),
                        onCancel = {
                            GitClone.cancel()
                            GitClone.reset()
                            mode = PickerMode.List
                        },
                        onConfirm = {
                            GitClone.start(context, cloneUrl.text, cloneName.text, onCloned)
                        },
                        onInstallGit = {
                            GitClone.installGitAndClone(
                                context,
                                cloneUrl.text,
                                cloneName.text,
                                onCloned,
                            )
                        },
                        onRetry = { GitClone.reset() },
                    )

                    mode == PickerMode.ConfirmDelete && pendingDelete != null -> {
                        val target = pendingDelete!!
                        ConfirmDelete(
                            project = target,
                            onCancel = { mode = PickerMode.List; pendingDelete = null },
                            onConfirm = {
                                onDelete(target)
                                mode = PickerMode.List
                                pendingDelete = null
                            },
                        )
                    }

                    else -> {
                        if (errorMessage != null) Message(errorMessage, isError = true)
                        if (projects.isEmpty()) {
                            Message(stringResource(R.string.projects_no_projects_yet))
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 360.dp)
                                    .padding(top = 8.dp)
                            ) {
                                items(projects, key = { it.path }) { project ->
                                    ProjectRow(
                                        project = project,
                                        isCurrent = project.path == currentPath,
                                        onOpen = { onOpen(project) },
                                        onExport = { onExport(project) },
                                        onDelete = {
                                            pendingDelete = project
                                            mode = PickerMode.ConfirmDelete
                                        },
                                    )
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(horizontal = 20.dp),
                        ) {
                            DialogAction(stringResource(R.string.projects_new), onClick = { mode = PickerMode.NewProject })
                            // Absent rather than disabled where there is no
                            // userland: the `play` edition cannot run git at
                            // all, and advertising what it cannot do is worse
                            // than not mentioning it.
                            if (GitClone.isSupported) {
                                DialogAction(stringResource(R.string.projects_clone), onClick = { mode = PickerMode.Clone })
                            }
                            DialogAction(stringResource(R.string.projects_import_folder), onClick = onImport)
                            Box(modifier = Modifier.weight(1f))
                            DialogAction(stringResource(R.string.projects_close), onClick = onDismiss)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(
    project: ProjectSummary,
    isCurrent: Boolean,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isCurrent) {
                    theme.color("element.selected")
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                }
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = project.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${project.entryCount} " +
                    if (project.entryCount == 1) "entry" else "entries",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DialogAction(stringResource(R.string.projects_export), onClick = onExport)
        Box(modifier = Modifier.padding(start = 16.dp)) {
            DialogAction(stringResource(R.string.projects_delete), onClick = onDelete, isDestructive = true)
        }
    }
}

@Composable
private fun NewProjectForm(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    error: String?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val canConfirm = value.text.isNotBlank() && error == null

    Column(modifier = Modifier.padding(top = 16.dp)) {
        DialogTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = stringResource(R.string.projects_project_name),
            focusRequester = focus,
            onSubmit = { if (canConfirm) onConfirm() },
            onEscape = onCancel,
        )
        if (error != null) {
            Message(error, isError = true)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
        ) {
            Box(modifier = Modifier.weight(1f))
            DialogAction(stringResource(R.string.projects_cancel), onClick = onCancel)
            DialogAction(stringResource(R.string.projects_create), onClick = onConfirm, enabled = canConfirm)
        }
    }
}

/**
 * Clone a repository into a new project: the form, the progress, and the two
 * ways it can stop short.
 *
 * The name is derived from the URL and stays editable, because the repository
 * a URL names and the project you want to call it are not always the same
 * thing. Everything reachable by tapping is reachable from the keyboard:
 * `Enter` clones, `Esc` goes back, `Tab` moves between the fields.
 */
@Composable
private fun CloneForm(
    state: CloneState,
    url: TextFieldValue,
    name: TextFieldValue,
    onUrlChange: (TextFieldValue) -> Unit,
    onNameChange: (TextFieldValue) -> Unit,
    error: String?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onInstallGit: () -> Unit,
    onRetry: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val canConfirm = url.text.isNotBlank() && name.text.isNotBlank() && error == null

    Column(modifier = Modifier.padding(top = 16.dp)) {
        when (state) {
            is CloneState.Working -> {
                CloneProgressView(
                    phase = state.progress.phase,
                    fraction = state.progress.fraction,
                )
                CloneActions {
                    // Cancel is the only thing to do here, and it must also
                    // clear the half-cloned directory — GitClone does that.
                    DialogAction(stringResource(R.string.projects_cancel), onClick = onCancel, isDestructive = true)
                }
            }

            is CloneState.InstallingGit -> {
                CloneProgressView(phase = "Installing — ${state.step}", fraction = null)
                CloneActions {
                    DialogAction(stringResource(R.string.projects_cancel), onClick = onCancel, isDestructive = true)
                }
            }

            CloneState.NeedsGit -> {
                // Covers both halves of "can this userland clone": the git
                // binary and the CA bundle its https support needs. Naming
                // only git would be a lie on a rootfs that has git alone.
                Message(
                    stringResource(R.string.projects_the_userland_still_needs_git_and)
                )
                CloneActions {
                    DialogAction(stringResource(R.string.projects_back), onClick = onRetry)
                    DialogAction(stringResource(R.string.projects_install_and_clone), onClick = onInstallGit)
                }
            }

            is CloneState.Failed -> {
                Message(state.summary, isError = true)
                // git's own words, kept verbatim: they are often the only
                // thing that says *which* host or branch went wrong.
                state.detail?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                CloneActions {
                    DialogAction(stringResource(R.string.projects_close), onClick = onCancel)
                    DialogAction(stringResource(R.string.projects_edit_and_retry), onClick = onRetry)
                }
            }

            else -> {
                LaunchedEffect(Unit) { focus.requestFocus() }
                DialogTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    placeholder = stringResource(R.string.projects_https_github_com_owner_repo_git),
                    focusRequester = focus,
                    onSubmit = { if (canConfirm) onConfirm() },
                    onEscape = onCancel,
                )
                Box(modifier = Modifier.height(8.dp))
                DialogTextField(
                    value = name,
                    onValueChange = onNameChange,
                    placeholder = stringResource(R.string.projects_project_name),
                    onSubmit = { if (canConfirm) onConfirm() },
                    onEscape = onCancel,
                )
                if (error != null) Message(error, isError = true)
                CloneActions {
                    DialogAction(stringResource(R.string.projects_cancel), onClick = onCancel)
                    DialogAction(stringResource(R.string.projects_clone_2), onClick = onConfirm, enabled = canConfirm)
                }
            }
        }
    }
}

@Composable
private fun CloneActions(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
    ) {
        Box(modifier = Modifier.weight(1f))
        content()
    }
}

/** Phase text and a bar; the bar is indeterminate-looking when there is no percent. */
@Composable
private fun CloneProgressView(phase: String, fraction: Float?) {
    val theme = LocalZedTheme.current
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        if (fraction == null) {
                            theme.color("element.selected", MaterialTheme.colorScheme.primary)
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        RoundedCornerShape(2.dp),
                    )
            )
        }
    }
}

/**
 * One text field, with the keyboard and mouse behaviour every field in this
 * dialog owes the user: `Enter` submits, `Esc` backs out, `Tab` moves on, and
 * the pointer becomes a caret over the text rather than staying an arrow.
 */
@Composable
private fun DialogTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    onSubmit: () -> Unit,
    onEscape: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val theme = LocalZedTheme.current
    val focusManager = LocalFocusManager.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .background(theme.color("editor.background"), RoundedCornerShape(6.dp))
            .pointerHoverIcon(PointerIcon.Text)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(
                theme.color("editor.foreground")
            ),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> { onSubmit(); true }
                        Key.Escape -> { onEscape(); true }
                        Key.Tab -> {
                            focusManager.moveFocus(
                                if (event.isShiftPressed) {
                                    FocusDirection.Previous
                                } else {
                                    FocusDirection.Next
                                }
                            )
                            true
                        }
                        else -> false
                    }
                },
        )
        if (value.text.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConfirmDelete(
    project: ProjectSummary,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            text = "Delete “${project.name}” and everything in it? This cannot be undone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
        ) {
            Box(modifier = Modifier.weight(1f))
            DialogAction(stringResource(R.string.projects_cancel), onClick = onCancel)
            DialogAction(stringResource(R.string.projects_delete), onClick = onConfirm, isDestructive = true)
        }
    }
}

@Composable
private fun Message(text: String, isError: Boolean = false) {
    val theme = LocalZedTheme.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) {
            theme.color("error", MaterialTheme.colorScheme.error)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
private fun DialogAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isDestructive: Boolean = false,
) {
    val theme = LocalZedTheme.current
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            isDestructive -> theme.color("error", MaterialTheme.colorScheme.error)
            else -> MaterialTheme.colorScheme.primary
        },
        modifier = Modifier
            .then(
                if (enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(vertical = 4.dp),
    )
}
