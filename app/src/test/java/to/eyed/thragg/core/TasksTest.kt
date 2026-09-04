package to.eyed.thragg.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin half of the task contract: what the engine's JSON becomes, what
 * the picker shows, what the shell is handed.
 */
class TasksTest {

    private fun task(
        label: String,
        command: String = "echo",
        id: String = label,
        fullLabel: String = label,
        showCommand: Boolean = true,
        showSummary: Boolean = true,
    ) = TaskSpec(
        id = id,
        label = label,
        fullLabel = fullLabel,
        command = command,
        args = emptyList(),
        commandLabel = command,
        cwd = null,
        env = emptyMap(),
        useNewTerminal = false,
        allowConcurrentRuns = false,
        reveal = TaskReveal.Always,
        hide = TaskHide.Never,
        save = TaskSave.None,
        showCommand = showCommand,
        showSummary = showSummary,
        source = TaskSource.Language,
        tags = emptyList(),
    )

    @Test
    fun theEnginesTaskArrayParsesWithItsFlags() {
        val json = """
            [{"id": "language_ab_cd", "label": "Test 'adds'", "full_label": "Test 'adds' (package: welcome)",
              "command": "cargo", "args": ["test", "--", "adds"], "command_label": "cargo test -- adds",
              "cwd": "/p", "env": {"ZED_FILE": "/p/src/main.rs"}, "use_new_terminal": true,
              "allow_concurrent_runs": false, "reveal": "no_focus", "hide": "on_success", "save": "current",
              "show_command": false, "show_summary": true, "source": "project", "tags": ["rust-test"]},
             {"label": "no command"},
             "garbage"]
        """.trimIndent()
        val tasks = TaskSpec.parseList(json)
        assertEquals(1, tasks.size)
        val task = tasks[0]
        assertEquals("Test 'adds'", task.label)
        assertEquals("Test 'adds' (package: welcome)", task.fullLabel)
        assertEquals(listOf("test", "--", "adds"), task.args)
        assertEquals("/p", task.cwd)
        assertEquals("/p/src/main.rs", task.env["ZED_FILE"])
        assertTrue(task.useNewTerminal)
        assertFalse(task.allowConcurrentRuns)
        assertEquals(TaskReveal.NoFocus, task.reveal)
        assertEquals(TaskHide.OnSuccess, task.hide)
        assertFalse(task.showCommand)
        assertEquals(TaskSource.Project, task.source)
        assertEquals(listOf("rust-test"), task.tags)
        // Garbage is nothing, never a crash.
        assertTrue(TaskSpec.parseList("not json").isEmpty())
        assertTrue(TaskSpec.parseList(null).isEmpty())
        assertNull(TaskSpec.parse("{}"))
    }

    @Test
    fun runnablesParseAndBecomeContext() {
        val rows = Runnable.parseList(
            """[{"row": 7, "col_utf16": 7, "end_row": 9, "tags": ["rust-test"],
                 "captures": {"_test_name": "adds"}, "run_text": "adds"}]""",
        )
        assertEquals(1, rows.size)
        assertEquals(7, rows[0].row)
        assertEquals(9, rows[0].endRow)
        assertEquals("adds", rows[0].captures["_test_name"])

        val context = TaskEditorContext(
            bufferId = 3,
            row = 7,
            column = 0,
            selectedText = "  ",
            runnable = rows[0],
        )
        val json = JSONObject(context.toJson())
        assertEquals(3L, json.getLong("buffer_id"))
        assertEquals(7, json.getInt("row"))
        // A blank selection is no selection, as Zed treats it.
        assertFalse(json.has("selected_text"))
        assertEquals("rust-test", json.getJSONObject("runnable").getJSONArray("tags").getString(0))
        assertEquals("{}", TaskEditorContext.EMPTY.toJson())
    }

    @Test
    fun historyIsMostRecentFirstAndOnePerId() {
        val history = TaskHistory()
        val a = task("a")
        val b = task("b")
        history.record(a)
        history.record(b)
        history.record(a)
        assertEquals(listOf("a", "b"), history.recent.map { it.label })
        assertEquals("a", history.last?.label)
        history.forget(a)
        assertEquals(listOf("b"), history.recent.map { it.label })
        history.clear()
        assertNull(history.last)
    }

    @Test
    fun candidatesPutUsedTasksFirstWithoutRepeatingThem() {
        val used = listOf(task("Test", id = "used-test"), task("Run", id = "used-run"))
        val current = listOf(task("Check"), task("Test"), task("Clean"))
        val candidates = taskCandidates(used, current)
        assertEquals(listOf("Test", "Run", "Check", "Clean"), candidates.tasks.map { it.label })
        assertEquals(1, candidates.lastUsedIndex)
        // The used copy wins over the fresh one with the same label.
        assertEquals("used-test", candidates.tasks[0].id)
        assertNull(taskCandidates(emptyList(), current).lastUsedIndex)
    }

    @Test
    fun filteringMatchesEveryWordInLabelOrCommand() {
        val tasks = listOf(task("Check", "cargo check"), task("Test 'adds'", "cargo test -- adds"))
        assertEquals(listOf("Test 'adds'"), filterTasks(tasks, "cargo adds").map { it.label })
        assertEquals(2, filterTasks(tasks, "CARGO").size)
        assertEquals(2, filterTasks(tasks, "  ").size)
        assertTrue(filterTasks(tasks, "npm").isEmpty())
    }

    @Test
    fun theShellLineEchoesTheCommandAndReportsTheStatus() {
        val line = taskShellLine(task("Test 'it'", command = "cargo test -- it"))
        assertTrue(line.startsWith("printf '%s\\n' '⏵ cargo test -- it'; cargo test -- it; "))
        assertTrue(line.contains("finished with exit code"))
        assertTrue(line.endsWith("exit \"\$__thragg_status\""))
        // Without either flag the line is the command and nothing else.
        assertEquals(
            "cargo test -- it",
            taskShellLine(task("t", "cargo test -- it", showCommand = false, showSummary = false)),
        )
        // A quote in the label cannot break out of the echo.
        val quoted = taskShellLine(task("it's", "true", showSummary = false))
        assertEquals("printf '%s\\n' '⏵ true'; true", quoted)
        assertEquals("'it'\\''s'", shellQuote("it's"))
    }

    @Test
    fun environmentRowsSkipNamesAShellWouldReject() {
        val task = task("t").copy(env = mapOf("ZED_ROW" to "3", "" to "x", "A=B" to "y"))
        assertEquals(listOf("ZED_ROW=3"), taskEnvironmentRows(task))
    }

    @Test
    fun aOneshotTemplateIsItsPromptTwice() {
        val json = JSONObject(oneshotTemplateJson("ls -la"))
        assertEquals("ls -la", json.getString("label"))
        assertEquals("ls -la", json.getString("command"))
    }
}
