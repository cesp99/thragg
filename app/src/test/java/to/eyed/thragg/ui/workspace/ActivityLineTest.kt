package to.eyed.thragg.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import to.eyed.thragg.ui.editor.LspServer
import to.eyed.thragg.ui.editor.LspServerState

/**
 * What the status bar's one activity slot says when several things are
 * running — Zed's activity indicator, which also picks one branch and prints
 * it (activity_indicator.rs:397-500).
 */
class ActivityLineTest {

    private fun server(
        name: String,
        state: LspServerState = LspServerState.Running,
        progress: String? = null,
    ) = LspServer(name = name, state = state, error = null, languages = emptyList(), progress = progress)

    @Test
    fun nothingRunningIsNoLineAtAll() {
        assertNull(activityLine(emptyList(), emptyList()))
        assertNull(activityLine(emptyList(), listOf(server("rust-analyzer"))))
    }

    @Test
    fun aServersProgressOutranksItsStart() {
        val line = activityLine(
            emptyList(),
            listOf(server("clangd", state = LspServerState.Starting), server("rust-analyzer", progress = "indexing (45%)")),
        )
        assertEquals("rust-analyzer: indexing (45%)", line!!.message)
        assertEquals(ActivityTarget.LanguageServerLogs, line.target)
        assertEquals(0, line.others)
    }

    @Test
    fun aStartingServerSpeaksWhenNoneIsBusy() {
        val line = activityLine(emptyList(), listOf(server("gopls", state = LspServerState.Starting)))
        assertEquals("gopls is starting…", line!!.message)
    }

    /**
     * The thing the user just pressed wins: an indexer that has been running
     * for a minute is not news, and the search they started a second ago is.
     */
    @Test
    fun anExplicitJobOutranksTheLanguageServers() {
        val line = activityLine(
            listOf(Activity("project-search:1", "Searching welcome…", ActivityTarget.ProjectSearch)),
            listOf(server("rust-analyzer", progress = "indexing (45%)")),
        )
        assertEquals("Searching welcome…", line!!.message)
        assertEquals(ActivityTarget.ProjectSearch, line.target)
        assertEquals(1, line.others)
    }

    @Test
    fun theRestAreCountedRatherThanPrinted() {
        val line = activityLine(
            listOf(
                Activity("a", "Fetching…", ActivityTarget.GitPanel),
                Activity("b", "Scanning…", ActivityTarget.ProjectPanel),
                Activity("c", "Running tests", ActivityTarget.Terminal),
            ),
            emptyList(),
        )
        assertEquals("Fetching…", line!!.message)
        assertEquals(2, line.others)
    }

    // ---- the log itself ------------------------------------------------------

    @Test
    fun beginningTheSameKeyAgainRewordsItInPlace() {
        val log = ActivityLog()
        log.begin("scan", "Scanning welcome…", ActivityTarget.ProjectPanel)
        log.begin("search", "Searching…", ActivityTarget.ProjectSearch)
        // A progress tick must not shuffle the scan back to the front.
        log.begin("scan", "Scanning welcome (2000 files)…", ActivityTarget.ProjectPanel)
        assertEquals(
            listOf("Searching…", "Scanning welcome (2000 files)…"),
            log.all.map { it.message },
        )
    }

    @Test
    fun endingAJobThatNeverStartedIsHarmless() {
        val log = ActivityLog()
        log.begin("scan", "Scanning…")
        log.end("search")
        log.end("scan")
        log.end("scan")
        assertEquals(emptyList<Activity>(), log.all)
    }
}
