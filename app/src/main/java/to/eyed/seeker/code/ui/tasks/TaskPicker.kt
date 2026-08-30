package to.eyed.seeker.code.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.eyed.seeker.code.core.TaskEditorContext
import to.eyed.seeker.code.core.TaskSource
import to.eyed.seeker.code.core.TaskSpec
import to.eyed.seeker.code.core.filterTasks
import to.eyed.seeker.code.core.taskCandidates
import to.eyed.seeker.code.ui.theme.touchTarget
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.revealItem
import to.eyed.seeker.code.ui.workspace.PickerEmptyState
import to.eyed.seeker.code.ui.workspace.PickerListItem
import to.eyed.seeker.code.ui.workspace.PickerListPadding
import to.eyed.seeker.code.ui.workspace.PickerModal
import to.eyed.seeker.code.ui.workspace.PickerQueryField

/**
 * What the picker was opened for: the context to resolve against and,
 * from a play button, the row's tasks already resolved so the list opens
 * narrowed to them.
 */
data class TaskPickerRequest(
    val context: TaskEditorContext,
    val preloaded: List<TaskSpec>? = null,
)

/**
 * Zed's task modal — `task::Spawn` (crates/tasks_ui/src/modal.rs).
 *
 * The rows are what ran before, most recent first, then a divider, then
 * everything that resolves now, one per label (`tasks_loaded`,
 * modal.rs:171-200). Typing narrows the list and, as in Zed, offers what
 * was typed as a *oneshot* — a task whose label and command are the prompt
 * — listed first so `Enter` runs it when nothing else matches
 * (modal.rs:77-103; the tests there insist "New oneshot task should be
 * listed first"). `Tab` puts the selected task's command into the field to
 * edit and run as a oneshot, Zed's "adjust the currently selected task"
 * (docs/src/tasks.md § Oneshot tasks). A previously used row carries a `✕`
 * that forgets it (`delete_previously_used`).
 *
 * Every row is a button, so the whole thing works by touch: the terminal
 * tab bar's `▶` opens it and a tap runs a row.
 */
@Composable
fun TaskPicker(
    projectId: Long,
    request: TaskPickerRequest,
    onRun: (TaskSpec) -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var current by remember { mutableStateOf(request.preloaded) }
    var selected by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    // Read once per opening: the revision only moves when a run is recorded,
    // and a run closes the picker.
    val historyRevision = TaskRuns.revision
    val used = remember(historyRevision) { TaskRuns.history.recent }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(request) {
        if (current == null) current = loadTasks(projectId, request.context)
    }

    val candidates = remember(used, current) {
        taskCandidates(used, current.orEmpty())
    }
    val prompt = query.text.trim()
    val shown = remember(candidates, prompt) { filterTasks(candidates.tasks, prompt) }
    // The oneshot row: only for a prompt, and not when a listed task already
    // has exactly that label — pressing Enter on "cargo test" should run the
    // task called that, not a second copy of it.
    val offersOneshot = prompt.isNotEmpty() && shown.none { it.label == prompt }
    val rowCount = shown.size + if (offersOneshot) 1 else 0

    LaunchedEffect(prompt) { selected = 0 }
    LaunchedEffect(selected, rowCount) {
        if (selected >= rowCount) selected = 0
        if (selected in 0 until rowCount) listState.revealItem(selected)
    }

    fun move(delta: Int) {
        if (rowCount == 0) return
        selected = ((selected + delta) % rowCount + rowCount) % rowCount
    }

    /** The task at a row index, the oneshot row being index 0 when offered. */
    fun taskAt(index: Int): TaskSpec? {
        val shift = if (offersOneshot) 1 else 0
        return shown.getOrNull(index - shift)
    }

    fun runOneshot() {
        val text = prompt
        if (text.isEmpty()) return
        scope.launch {
            val task = resolveOneshot(projectId, request.context, text) ?: return@launch
            onRun(task)
        }
    }

    fun confirm() {
        if (offersOneshot && selected == 0) return runOneshot()
        val task = taskAt(selected) ?: return
        onRun(task)
    }

    fun adjustSelected() {
        val task = taskAt(selected) ?: return
        val line = task.commandLabel
        query = TextFieldValue(line, TextRange(line.length))
    }

    PickerModal(
        onDismiss = onDismiss,
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                event.key == Key.DirectionDown -> { move(1); true }
                event.key == Key.DirectionUp -> { move(-1); true }
                event.isCtrlPressed && event.key == Key.N -> { move(1); true }
                event.isCtrlPressed && event.key == Key.P -> { move(-1); true }
                event.key == Key.Tab -> { adjustSelected(); true }
                event.key == Key.Enter || event.key == Key.NumPadEnter -> { confirm(); true }
                event.key == Key.Escape -> { onDismiss(); true }
                else -> false
            }
        },
    ) {
        PickerQueryField(
            query = query,
            onQueryChange = { query = it },
            // Zed's own placeholder (modal.rs:60).
            placeholder = "Find a task, or run a command",
            focusRequester = focus,
        )

        if (rowCount == 0) {
            PickerEmptyState(
                when {
                    current == null -> "Reading tasks…"
                    else -> "No tasks for this file — type a command to run it"
                }
            )
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PickerListPadding,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            ) {
                if (offersOneshot) {
                    itemsIndexed(listOf(prompt), key = { _, _ -> "oneshot" }) { _, text ->
                        TaskRow(
                            label = text,
                            command = "Run as oneshot",
                            isUsed = false,
                            isSelected = selected == 0,
                            onClick = { selected = 0; runOneshot() },
                            onForget = null,
                        )
                    }
                }
                itemsIndexed(shown, key = { _, task -> task.id }) { index, task ->
                    val row = index + if (offersOneshot) 1 else 0
                    val isUsed = candidates.lastUsedIndex?.let { last ->
                        candidates.tasks.indexOf(task) <= last
                    } ?: false
                    TaskRow(
                        label = task.label,
                        command = task.commandLabel.takeIf { it != task.label },
                        isUsed = isUsed,
                        isSelected = row == selected,
                        onClick = { selected = row; onRun(task) },
                        onForget = if (isUsed) {
                            { TaskRuns.forget(task) }
                        } else {
                            null
                        },
                        source = task.source,
                    )
                    // Zed's divider between the used and the current tasks
                    // (modal.rs `divider_index`).
                    if (candidates.lastUsedIndex != null &&
                        candidates.tasks.indexOf(task) == candidates.lastUsedIndex &&
                        prompt.isEmpty()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(theme.color("border.variant")),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One task: the label, with the command underneath in `text.muted` when it
 * says something the label does not; a history mark on a previously used
 * row (Zed's `IconName::HistoryRerun`, modal.rs:511) and a `✕` to forget it.
 */
@Composable
private fun TaskRow(
    label: String,
    command: String?,
    isUsed: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onForget: (() -> Unit)?,
    source: TaskSource? = null,
) {
    val theme = LocalZedTheme.current
    PickerListItem(isSelected = isSelected, onClick = onClick) {
        Text(
            text = if (isUsed) "↺" else "▶",
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted"),
            modifier = Modifier.width(16.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) theme.color("text") else theme.color("text.muted"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (command != null) {
                Text(
                    text = command,
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (source != null && source != TaskSource.Language) {
            Text(
                text = when (source) {
                    TaskSource.Project -> ".zed"
                    TaskSource.Global -> "global"
                    TaskSource.UserInput -> "oneshot"
                    TaskSource.Language -> ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted"),
            )
        }
        if (onForget != null) {
            Text(
                text = "✕",
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
                modifier = Modifier
                    .semantics { contentDescription = "Forget this task" }
                    .touchTarget()
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onForget)
                    .padding(horizontal = 4.dp),
            )
        }
    }
}
