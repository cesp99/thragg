package to.eyed.thragg.ui.tasks

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.thragg.core.CoreBridge
import to.eyed.thragg.core.TaskEditorContext
import to.eyed.thragg.core.TaskHistory
import to.eyed.thragg.core.TaskSpec
import to.eyed.thragg.core.oneshotTemplateJson
import to.eyed.thragg.core.taskEnvironmentRows
import to.eyed.thragg.core.taskShellLine
import to.eyed.thragg.terminal.ShellEnvironment
import to.eyed.thragg.terminal.TerminalPanelState
import to.eyed.thragg.ui.editor.EditorState
import to.eyed.thragg.ui.workspace.Activities
import to.eyed.thragg.ui.workspace.ActivityTarget
import to.eyed.thragg.ui.workspace.NotificationAction
import to.eyed.thragg.ui.workspace.Notifications

/**
 * Where a task goes to run: the engine resolves it, this turns it into a
 * shell command and hands it to the terminal dock, and remembers it for
 * `task::Rerun` and the top of the picker.
 *
 * The history is process-wide like [to.eyed.thragg.terminal.TerminalSessions],
 * and for the same reason: the tabs it describes outlive any composable.
 * It is cleared with them when the project changes.
 */
object TaskRuns {
    val history = TaskHistory()

    /**
     * Bumped on every record, so composition can watch a plain class —
     * [TaskHistory] is host-testable logic and has no snapshot state.
     */
    var revision by mutableIntStateOf(0)
        private set

    /**
     * Whether anything ran this session — what `task::Rerun` needs. Reads
     * [revision] first so a composable asking recomposes when a task runs.
     */
    val hasHistory: Boolean
        get() {
            revision
            return history.last != null
        }

    fun record(task: TaskSpec) {
        history.record(task)
        revision++
    }

    fun forget(task: TaskSpec) {
        history.forget(task)
        revision++
    }

    fun clear() {
        history.clear()
        revision++
    }
}

/** The tasks that resolve for [context], off the main thread. */
suspend fun loadTasks(projectId: Long, context: TaskEditorContext): List<TaskSpec> =
    withContext(Dispatchers.IO) {
        TaskSpec.parseList(CoreBridge.tasksList(projectId, context.toJson()))
    }

/** The oneshot for [prompt], resolved against [context]; null when it cannot be. */
suspend fun resolveOneshot(
    projectId: Long,
    context: TaskEditorContext,
    prompt: String,
): TaskSpec? = withContext(Dispatchers.IO) {
    TaskSpec.parse(CoreBridge.taskResolve(projectId, context.toJson(), oneshotTemplateJson(prompt)))
}

/**
 * The caret's context, as Zed's `task_context_for_location` sees it: the
 * buffer, the primary caret and the selection. Null-free for an editor,
 * empty when there is none — the picker still lists the file-free tasks.
 */
fun editorTaskContext(editor: EditorState?): TaskEditorContext {
    editor ?: return TaskEditorContext.EMPTY
    val session = editor.sessionOrNull ?: return TaskEditorContext.EMPTY
    return TaskEditorContext(
        bufferId = session.id,
        row = editor.cursorRow,
        column = editor.cursorCol,
        selectedText = editor.selectionText().takeIf { it.isNotBlank() },
    )
}

/**
 * Run [task] in the terminal dock and remember it. The working directory is
 * the task's own, else the project root — Zed's `cwd` "defaults to current
 * project root" (docs/src/tasks.md). Must be called on the main thread: the
 * session it opens binds to the caller's looper.
 */
fun runTask(
    context: Context,
    terminals: TerminalPanelState,
    projectRoot: String,
    task: TaskSpec,
) {
    val cwd = task.cwd ?: projectRoot
    val command = ShellEnvironment.taskCommand(
        context,
        cwd,
        taskShellLine(task),
        taskEnvironmentRows(task),
    )
    val host = terminals.runTask(task, command, cwd)
    TaskRuns.record(task)

    // While it runs, in the status bar — Zed's activity indicator reports a
    // running task and its click reveals the terminal it is running in
    // (activity_indicator.rs:455-465). Keyed by the task's full label, so a
    // rerun replaces the entry rather than counting the task twice.
    val activity = "task:${task.fullLabel}"
    Activities.begin(activity, "Running ${task.label}", ActivityTarget.Terminal)
    // The dock set this to apply the task's `hide` strategy; chained rather
    // than replaced, or a task with `"hide": "on_success"` would stop hiding.
    val hideStrategy = host.onFinished
    host.onFinished = { finished ->
        hideStrategy?.invoke(finished)
        Activities.end(activity)
        val status = finished.exitStatus
        // A task that failed is the thing the user wants to know about, and
        // with `reveal: never` or the dock closed there was nothing at all to
        // see. The button goes to the tab holding the output.
        if (status != null && status != 0) {
            Notifications.error(
                message = "${task.label}: ${finished.exitDescription ?: "failed"}",
                action = NotificationAction("Show output") {
                    terminals.reveal(finished)
                },
                key = activity,
            )
        }
    }
}
