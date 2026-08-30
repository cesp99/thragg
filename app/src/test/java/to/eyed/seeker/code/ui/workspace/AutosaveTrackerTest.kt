package to.eyed.seeker.code.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `"autosave": {"after_delay": …}` as a debounce read off the buffers'
 * version counters — Zed's `pending_autosave.fire_new` restarting on every
 * edit (workspace/src/item.rs:936-956), without an edit event to hook.
 */
class AutosaveTrackerTest {

    @Test
    fun aTabSavesOnceItHasSatStillForTheDelay() {
        val tracker = AutosaveTracker()
        tracker.observe("a.rs", version = 1, now = 0)
        // Not yet: 999 ms of silence against a 1000 ms delay.
        assertEquals(emptyList<String>(), tracker.due(listOf("a.rs"), 1000, now = 999))
        assertEquals(listOf("a.rs"), tracker.due(listOf("a.rs"), 1000, now = 1000))
    }

    @Test
    fun anEditRestartsTheClock() {
        val tracker = AutosaveTracker()
        tracker.observe("a.rs", version = 1, now = 0)
        tracker.observe("a.rs", version = 2, now = 800)
        assertEquals(emptyList<String>(), tracker.due(listOf("a.rs"), 1000, now = 1000))
        assertEquals(listOf("a.rs"), tracker.due(listOf("a.rs"), 1000, now = 1800))
    }

    /** A clean tab is never due, however long it sits. */
    @Test
    fun onlyDirtyTabsAreDue() {
        val tracker = AutosaveTracker()
        tracker.observe("a.rs", version = 1, now = 0)
        tracker.observe("b.rs", version = 1, now = 0)
        assertEquals(listOf("b.rs"), tracker.due(listOf("b.rs"), 1000, now = 5000))
    }

    /**
     * A save that went out is not repeated on every poll — a refused save
     * (a deleted directory, say) would otherwise be retried four times a
     * second — but the next edit makes the tab due again.
     */
    @Test
    fun aSavedTabWaitsForTheNextEdit() {
        val tracker = AutosaveTracker()
        tracker.observe("a.rs", version = 1, now = 0)
        assertEquals(listOf("a.rs"), tracker.due(listOf("a.rs"), 1000, now = 1000))
        tracker.saved("a.rs")
        assertEquals(emptyList<String>(), tracker.due(listOf("a.rs"), 1000, now = 5000))
        tracker.observe("a.rs", version = 2, now = 5000)
        assertEquals(listOf("a.rs"), tracker.due(listOf("a.rs"), 1000, now = 6000))
    }

    @Test
    fun closedTabsAreForgotten() {
        val tracker = AutosaveTracker()
        tracker.observe("a.rs", version = 1, now = 0)
        tracker.observe("b.rs", version = 1, now = 0)
        tracker.retain(listOf("b.rs"))
        // A forgotten tab reappearing starts a fresh clock rather than
        // saving at once.
        tracker.observe("a.rs", version = 1, now = 5000)
        assertEquals(listOf("b.rs"), tracker.due(listOf("a.rs", "b.rs"), 1000, now = 5000))
        assertTrue("a.rs" in tracker.due(listOf("a.rs", "b.rs"), 1000, now = 6000))
    }
}
