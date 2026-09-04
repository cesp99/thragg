package to.eyed.thragg.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.thragg.ui.git.DiffTarget

/**
 * The jump list's rules, pinned as data — Zed's `NavHistory`
 * (workspace/src/pane.rs:4707-4860), whose semantics these tests restate:
 * pushes clear the forward stack, going back feeds it, same-place entries
 * dedup, dead entries are skipped, and the stacks cap at 1024.
 *
 * Two layers on purpose: [NavHistory] alone for the stack arithmetic, and
 * [OpenFilesState] for the recording — which tab switches push, which
 * selections are navigation and must not push.
 */
class NavHistoryTest {

    private fun entry(path: String, row: Int = 0) = NavEntry(path, row = row)

    private val anywhere: (NavEntry) -> Boolean = { true }

    // ---- NavHistory: the stacks ------------------------------------------

    @Test
    fun backReturnsNewestFirst() {
        val nav = NavHistory()
        nav.push(entry("a"))
        nav.push(entry("b"))
        assertEquals("b", nav.back(null, anywhere)?.path)
        assertEquals("a", nav.back(null, anywhere)?.path)
        assertNull(nav.back(null, anywhere))
    }

    @Test
    fun goingBackFeedsForward() {
        val nav = NavHistory()
        nav.push(entry("a"))
        assertFalse(nav.canGoForward)
        assertEquals("a", nav.back(entry("b"), anywhere)?.path)
        assertTrue(nav.canGoForward)
        assertEquals("b", nav.forward(entry("a"), anywhere)?.path)
        // And the round trip put "a" back where GoBack finds it.
        assertTrue(nav.canGoBack)
        assertFalse(nav.canGoForward)
    }

    @Test
    fun newTravelTruncatesForward() {
        val nav = NavHistory()
        nav.push(entry("a"))
        nav.back(entry("b"), anywhere)
        assertTrue(nav.canGoForward)
        // Navigating somewhere new after a GoBack throws the branch away
        // (pane.rs:4815).
        nav.push(entry("c"))
        assertFalse(nav.canGoForward)
        assertNull(nav.forward(entry("c"), anywhere))
    }

    @Test
    fun samePlaceEntriesDedup() {
        val nav = NavHistory()
        nav.push(entry("a", row = 3))
        nav.push(entry("b"))
        nav.push(entry("a", row = 3))
        assertEquals("a", nav.back(null, anywhere)?.path)
        assertEquals("b", nav.back(null, anywhere)?.path)
        // The older ("a", 3) was removed when its twin was pushed
        // (Zed's `is_same_location`, pane.rs:4795-4797).
        assertNull(nav.back(null, anywhere))
    }

    @Test
    fun samePathDifferentRowIsADifferentPlace() {
        val nav = NavHistory()
        nav.push(entry("a", row = 1))
        nav.push(entry("a", row = 40))
        assertEquals(40, nav.back(null, anywhere)?.row)
        assertEquals(1, nav.back(null, anywhere)?.row)
    }

    @Test
    fun backSkipsUnusableEntries() {
        val nav = NavHistory()
        nav.push(entry("dead"))
        nav.push(entry("alive"))
        nav.push(entry("dead"))
        val usable = { e: NavEntry -> e.path == "alive" }
        assertEquals("alive", nav.back(entry("now"), usable)?.path)
        // The dead entries were discarded on the way past, not kept.
        assertNull(nav.back(entry("alive"), usable))
    }

    @Test
    fun backSkipsThePlaceTheUserIsStandingOn() {
        val nav = NavHistory()
        nav.push(entry("a"))
        nav.push(entry("b", row = 5))
        // Standing at ("b", 5): its own entry navigates nowhere, so the one
        // under it is the answer (workspace.rs:2837-2843's loop).
        assertEquals("a", nav.back(entry("b", row = 5), anywhere)?.path)
    }

    @Test
    fun nothingGoesForwardWhenThereWasNowhereToGoBackTo() {
        val nav = NavHistory()
        nav.push(entry("dead"))
        assertNull(nav.back(entry("now")) { false })
        assertFalse(nav.canGoForward)
    }

    @Test
    fun stacksCapAtZedsLimit() {
        val nav = NavHistory()
        for (i in 0 until 1100) nav.push(entry("f$i"))
        var popped = 0
        var last: NavEntry? = null
        while (true) {
            val e = nav.back(null, anywhere) ?: break
            last = e
            popped++
        }
        // MAX_NAVIGATION_HISTORY_LEN = 1024 (pane.rs:322), oldest dropped.
        assertEquals(1024, popped)
        assertEquals("f76", last?.path)
    }

    // ---- OpenFilesState: what records, what doesn't ----------------------

    private fun file(path: String) = OpenFile(path, editor = null)

    private fun stateWith(vararg paths: String): OpenFilesState {
        val files = OpenFilesState()
        for (path in paths) files.open(file(path))
        return files
    }

    @Test
    fun openingFilesRecordsTheDepartures() {
        val files = stateWith("a", "b", "c")
        assertTrue(files.canGoBack)
        assertEquals("b", files.goBack()?.path)
        assertEquals(1, files.activeIndex)
        assertEquals("a", files.goBack()?.path)
        assertEquals(0, files.activeIndex)
        assertFalse(files.canGoBack)
    }

    @Test
    fun forwardReplaysWhatBackLeft() {
        val files = stateWith("a", "b", "c")
        files.goBack()
        files.goBack()
        assertEquals("b", files.goForward()?.path)
        assertEquals("c", files.goForward()?.path)
        assertEquals(2, files.activeIndex)
        assertFalse(files.canGoForward)
    }

    @Test
    fun selectingATabRecordsAndTruncatesForward() {
        val files = stateWith("a", "b", "c")
        files.goBack() // at b, forward holds c
        assertTrue(files.canGoForward)
        files.select(0) // new travel: to a by hand
        assertFalse(files.canGoForward)
        // …and the departure from b was recorded as backward travel.
        assertEquals("b", files.goBack()?.path)
    }

    @Test
    fun reselectingTheActiveTabRecordsNothing() {
        val files = stateWith("a", "b")
        files.goBack() // at a, forward holds b
        files.select(files.activeIndex) // a no-op click on the active tab
        // Were that click travel, it would have truncated the forward stack.
        assertTrue(files.canGoForward)
    }

    @Test
    fun backToAClosedFileAsksForItsPathAndKeepsForward() {
        val files = stateWith("a", "b")
        files.close(files.indexOfPath("a"))
        val entry = files.goBack()
        assertEquals("a", entry?.path)
        // Nothing to select yet — the workspace reopens it…
        assertEquals("b", files.active?.path)
        assertTrue(files.canGoForward)
        // …and that reopen is the navigation landing, not new travel, so the
        // forward stack survives it.
        files.open(file("a"))
        assertEquals("a", files.active?.path)
        assertTrue(files.canGoForward)
        assertEquals("b", files.goForward()?.path)
    }

    @Test
    fun aNavigationThatCannotLandIsPutBack() {
        val files = stateWith("a", "b")
        files.close(files.indexOfPath("a"))
        val entry = files.goBack()
        assertEquals("a", entry?.path)
        assertTrue(files.canGoForward)

        // The workspace could not reopen it — no session, or the path is
        // gone. That is not a move: the entry goes back where it came from,
        // the arrow it lit goes out, and the landing bracket disarms so a
        // later manual open of the same path counts as ordinary travel.
        files.navigationFailed(entry!!, wasBack = true)
        assertTrue(files.canGoBack)
        assertFalse(files.canGoForward)
        assertEquals("b", files.active?.path)

        files.open(file("a"))
        assertEquals("b", files.goBack()?.path)
    }

    @Test
    fun anUnrelatedOpenCancelsThePendingNavigation() {
        val files = stateWith("a", "b")
        files.close(files.indexOfPath("a"))
        files.goBack() // asks for "a" to be reopened
        files.open(file("c")) // but the user opened something else first
        // "c" was ordinary travel: it recorded the departure and cut forward.
        assertFalse(files.canGoForward)
        // And a later open of "a" is ordinary too, not the stale landing.
        files.open(file("a"))
        assertEquals("c", files.goBack()?.path)
    }

    @Test
    fun closedDiffEntriesAreSkippedNotReopened() {
        val files = OpenFilesState()
        files.open(file("a"))
        files.open(OpenFile("git-diff:f", editor = null, diff = DiffTarget("f")))
        files.open(file("b"))
        files.close(files.indexOfPath("git-diff:f"))
        // The diff can't be reopened by path; GoBack lands on "a" instead.
        assertEquals("a", files.goBack()?.path)
        assertEquals("a", files.active?.path)
    }

    @Test
    fun ctrlTabTravelIsRecorded() {
        val files = stateWith("a", "b")
        files.selectRelative(1) // wraps b -> a
        assertEquals("a", files.active?.path)
        assertEquals("b", files.goBack()?.path)
    }

    @Test
    fun leavingTheProjectClearsTheJumpList() {
        val files = stateWith("a", "b")
        files.goBack()
        files.clearClosedHistory()
        assertFalse(files.canGoBack)
        assertFalse(files.canGoForward)
        assertNull(files.goBack())
    }
}
