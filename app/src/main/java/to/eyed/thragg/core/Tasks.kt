package to.eyed.thragg.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Tasks, as the engine resolves them and the terminal runs them.
 *
 * The engine owns the templates, the `$ZED_*` variables and their
 * substitution (core/crates/engine/src/tasks.rs); what crosses the bridge is
 * Zed's `SpawnInTerminal` — a label, a command line, a directory, an
 * environment and the three flags that decide how the terminal dock treats
 * the tab. This file is the Kotlin side of that contract: the parser, the
 * context the editor sends, the session's memory of what ran, and the shell
 * line a task becomes. All of it is plain logic, tested on the host.
 */

/** Zed's `RevealStrategy`: what the dock does with the tab once it is spawned. */
enum class TaskReveal {
    /** Show the dock and focus the tab (the default). */
    Always,

    /** Show the dock, add the tab, leave focus alone. */
    NoFocus,

    /** Add or reuse the tab without touching the dock or focus. */
    Never;

    companion object {
        fun parse(name: String?): TaskReveal = when (name) {
            "no_focus" -> NoFocus
            "never" -> Never
            else -> Always
        }
    }
}

/** Zed's `HideStrategy`: what happens to the tab when the command ends. */
enum class TaskHide {
    Never,
    Always,
    OnSuccess;

    companion object {
        fun parse(name: String?): TaskHide = when (name) {
            "always" -> Always
            "on_success" -> OnSuccess
            else -> Never
        }
    }
}

/**
 * Zed's `SaveStrategy`: which edited buffers the workspace writes to disk
 * before the run (workspace/src/tasks.rs `save_for_task`).
 */
enum class TaskSave {
    All,
    Current,
    None;

    companion object {
        fun parse(name: String?): TaskSave = when (name) {
            "all" -> All
            "current" -> Current
            else -> None
        }
    }
}

/** Where a task came from — the engine's `TaskSource`. */
enum class TaskSource(val wireName: String) {
    /** Typed into the picker: Zed's oneshot. */
    UserInput("user_input"),
    Project("project"),
    Global("global"),
    Language("language");

    companion object {
        fun parse(name: String?): TaskSource =
            entries.firstOrNull { it.wireName == name } ?: Language
    }
}

/** One resolved task — every field of the engine's `TaskSpec`. */
data class TaskSpec(
    val id: String,
    /** The label to display, long variables shortened. */
    val label: String,
    /** The label in full — what tab reuse is keyed on, as in Zed. */
    val fullLabel: String,
    val command: String,
    val args: List<String>,
    /** `command` and `args` joined: the line the shell runs. */
    val commandLabel: String,
    val cwd: String?,
    val env: Map<String, String>,
    val useNewTerminal: Boolean,
    val allowConcurrentRuns: Boolean,
    val reveal: TaskReveal,
    val hide: TaskHide,
    val save: TaskSave,
    val showCommand: Boolean,
    val showSummary: Boolean,
    val source: TaskSource,
    val tags: List<String>,
) {
    companion object {
        /** The engine's JSON array of tasks. Garbage is an empty list, never a crash. */
        fun parseList(json: String?): List<TaskSpec> {
            if (json.isNullOrEmpty()) return emptyList()
            return try {
                val array = JSONArray(json)
                buildList {
                    for (i in 0 until array.length()) {
                        parse(array.optJSONObject(i) ?: continue)?.let(::add)
                    }
                }
            } catch (_: JSONException) {
                emptyList()
            }
        }

        /** One task object, or null when it lacks the fields a run needs. */
        fun parse(json: String?): TaskSpec? {
            if (json.isNullOrEmpty()) return null
            return try {
                parse(JSONObject(json))
            } catch (_: JSONException) {
                null
            }
        }

        private fun parse(item: JSONObject): TaskSpec? {
            val label = item.optString("label")
            val command = item.optString("command")
            if (label.isEmpty() || command.isEmpty()) return null
            val args = item.optJSONArray("args")?.let { array ->
                List(array.length()) { array.optString(it) }
            }.orEmpty()
            val env = item.optJSONObject("env")?.let { obj ->
                buildMap {
                    for (key in obj.keys()) put(key, obj.optString(key))
                }
            }.orEmpty()
            val tags = item.optJSONArray("tags")?.let { array ->
                List(array.length()) { array.optString(it) }
            }.orEmpty()
            return TaskSpec(
                id = item.optString("id"),
                label = label,
                fullLabel = item.optString("full_label").ifEmpty { label },
                command = command,
                args = args,
                commandLabel = item.optString("command_label").ifEmpty {
                    (listOf(command) + args).joinToString(" ")
                },
                cwd = item.optString("cwd").takeIf { it.isNotEmpty() },
                env = env,
                useNewTerminal = item.optBoolean("use_new_terminal", false),
                allowConcurrentRuns = item.optBoolean("allow_concurrent_runs", false),
                reveal = TaskReveal.parse(item.optString("reveal")),
                hide = TaskHide.parse(item.optString("hide")),
                save = TaskSave.parse(item.optString("save")),
                showCommand = item.optBoolean("show_command", true),
                showSummary = item.optBoolean("show_summary", true),
                source = TaskSource.parse(item.optString("source")),
                tags = tags,
            )
        }
    }
}

/**
 * One row's runnable, as the engine found it: the tags to bind, the named
 * captures the language's templates read, and the `@run` node's text.
 */
data class Runnable(
    val row: Int,
    val col: Int,
    val endRow: Int,
    val tags: List<String>,
    val captures: Map<String, String>,
    val runText: String,
) {
    /** The `runnable` object of a task context. */
    fun toJson(): JSONObject = JSONObject()
        .put("tags", JSONArray(tags))
        .put("captures", JSONObject(captures))
        .put("run_text", runText)

    companion object {
        /** The engine's `bufferRunnables` array. Garbage is no runnables. */
        fun parseList(json: String?): List<Runnable> {
            if (json.isNullOrEmpty()) return emptyList()
            return try {
                val array = JSONArray(json)
                buildList {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val tags = item.optJSONArray("tags") ?: continue
                        val captures = item.optJSONObject("captures")?.let { obj ->
                            buildMap { for (key in obj.keys()) put(key, obj.optString(key)) }
                        }.orEmpty()
                        add(
                            Runnable(
                                row = item.optInt("row"),
                                col = item.optInt("col_utf16"),
                                endRow = item.optInt("end_row", item.optInt("row")),
                                tags = List(tags.length()) { tags.optString(it) },
                                captures = captures,
                                runText = item.optString("run_text"),
                            )
                        )
                    }
                }
            } catch (_: JSONException) {
                emptyList()
            }
        }
    }
}

/**
 * What the editor tells the engine when it asks for tasks — the caret's
 * buffer and place, the selection, and the runnable when a play button asked.
 * Everything optional: the picker opened from the terminal has no editor.
 */
data class TaskEditorContext(
    val bufferId: Long? = null,
    val row: Int? = null,
    val column: Int? = null,
    val selectedText: String? = null,
    val runnable: Runnable? = null,
) {
    fun toJson(): String = JSONObject().apply {
        bufferId?.let { put("buffer_id", it) }
        row?.let { put("row", it) }
        column?.let { put("column", it) }
        selectedText?.takeIf { it.isNotBlank() }?.let { put("selected_text", it) }
        runnable?.let { put("runnable", it.toJson()) }
    }.toString()

    companion object {
        val EMPTY = TaskEditorContext()
    }
}

/**
 * A oneshot's template — what the picker types becomes, in the format
 * `tasks.json` uses: label and command are both the prompt
 * (tasks_ui/src/modal.rs:89-93).
 */
fun oneshotTemplateJson(prompt: String): String =
    JSONObject().put("label", prompt).put("command", prompt).toString()

/**
 * What ran this session, most recent first — Zed's `last_scheduled_tasks`
 * (task_inventory.rs:677-693), which is what puts recently used tasks at
 * the top of the picker and what `task::Rerun` reruns. Session-only, like
 * Zed's: a oneshot typed today is not a task tomorrow.
 *
 * Keyed on the task id, which the engine hashes from the template and the
 * variables it resolved with (task_template.rs:236-240): the same test
 * spawned from the same file is one entry however often it ran; the same
 * template on another file is another.
 */
class TaskHistory {
    private val entries = ArrayDeque<TaskSpec>()

    /** Most recent first. */
    val recent: List<TaskSpec> get() = entries.toList()

    val last: TaskSpec? get() = entries.firstOrNull()

    fun record(task: TaskSpec) {
        entries.removeAll { it.id == task.id }
        entries.addFirst(task)
        while (entries.size > LIMIT) entries.removeLast()
    }

    /** Forget one — the picker's delete on a previously used row. */
    fun forget(task: TaskSpec) {
        entries.removeAll { it.id == task.id }
    }

    fun clear() = entries.clear()

    private companion object {
        /** Zed keeps 5 000 (task_inventory.rs:684); a phone session wants fewer. */
        const val LIMIT = 100
    }
}

/**
 * The picker's rows: what ran before, then what resolves now, one per
 * label, with the divider index between the two halves — Zed's
 * `used_and_current_resolved_tasks` and `TasksModal::tasks_loaded`
 * (modal.rs:171-200). A used task whose label the fresh list also carries
 * keeps its place at the top and is not listed twice.
 */
data class TaskCandidates(
    val tasks: List<TaskSpec>,
    /** Index of the last previously-used row, or null when none ran yet. */
    val lastUsedIndex: Int?,
)

fun taskCandidates(used: List<TaskSpec>, current: List<TaskSpec>): TaskCandidates {
    val seen = HashSet<String>()
    val rows = ArrayList<TaskSpec>(used.size + current.size)
    for (task in used) if (seen.add(task.fullLabel)) rows.add(task)
    val lastUsed = rows.lastIndex.takeIf { it >= 0 }
    for (task in current) if (seen.add(task.fullLabel)) rows.add(task)
    return TaskCandidates(rows, lastUsed)
}

/** Every query word must appear in the label or the command, case-insensitively. */
fun filterTasks(tasks: List<TaskSpec>, query: String): List<TaskSpec> {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return tasks
    return tasks.filter { task ->
        words.all { word ->
            task.label.contains(word, ignoreCase = true) ||
                task.commandLabel.contains(word, ignoreCase = true)
        }
    }
}

/**
 * The line the task's shell runs: the resolved command line, preceded when
 * `show_command` asks by an echo of it — Zed prints the command at the top
 * of the task's terminal (terminal.rs `TaskState`, "⏵ {command}") — and
 * followed when `show_summary` asks by the exit status, which is what Zed's
 * "Task `x` finished with exit code N" line carries. The task's own line is
 * passed to the shell as written: quoting is the template's business, as
 * in Zed's `ShellBuilder` (util/src/shell_builder.rs:77-99).
 */
fun taskShellLine(task: TaskSpec): String = buildString {
    if (task.showCommand) {
        append("printf '%s\\n' ")
        append(shellQuote("⏵ ${task.commandLabel}"))
        append("; ")
    }
    append(task.commandLabel)
    if (task.showSummary) {
        append("; __seeker_status=$?; printf '\\n%s\\n' ")
        append(shellQuote("Task `${task.label}` finished with exit code "))
        append("\"\$__seeker_status\"; exit \"\$__seeker_status\"")
    }
}

/** Single-quote for a POSIX shell, the one quoting that never expands. */
fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

/**
 * The task's environment as `NAME=value` rows, appended to the session's
 * own so the task's entries win — Zed's `env` "will be appended to the
 * terminal's environment from the settings" (docs/src/tasks.md).
 */
fun taskEnvironmentRows(task: TaskSpec): List<String> =
    task.env.entries
        .filter { (name, _) -> name.isNotEmpty() && '=' !in name }
        .map { (name, value) -> "$name=$value" }
