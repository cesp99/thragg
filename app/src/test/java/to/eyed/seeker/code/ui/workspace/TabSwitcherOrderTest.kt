package to.eyed.seeker.code.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order Ctrl+Tab walks, and the ring it walks it on.
 *
 * Zed's tab switcher reads the pane's activation history backwards
 * (tab_switcher/src/tab_switcher.rs) and its Ctrl+Tab is a picker selection
 * that wraps. The whole value of the feature is in that order: one press has
 * to mean "the file I was just in" however many times it is pressed, which
 * positional cycling cannot do, so the order is the thing worth pinning.
 */
class TabSwitcherOrderTest {

    @Test
    fun `most recently activated comes first`() {
        val order = mruOrder(
            paths = listOf("a.rs", "b.rs", "c.rs"),
            history = listOf("c.rs", "a.rs", "b.rs"),
        )
        assertEquals(listOf("b.rs", "a.rs", "c.rs"), order)
    }

    @Test
    fun `the second entry is the file you were just in`() {
        // Standing on b.rs, having come from a.rs: one Ctrl+Tab means a.rs.
        val order = mruOrder(
            paths = listOf("a.rs", "b.rs", "c.rs"),
            history = listOf("c.rs", "a.rs", "b.rs"),
        )
        assertEquals("a.rs", order[1])
    }

    @Test
    fun `re-activating a tab moves it to the front rather than duplicating it`() {
        // The state keeps one entry per path; the order model must agree, or a
        // tab would appear twice in the overlay.
        val order = mruOrder(
            paths = listOf("a.rs", "b.rs"),
            history = listOf("a.rs", "b.rs", "a.rs"),
        )
        assertEquals(listOf("a.rs", "b.rs"), order)
    }

    @Test
    fun `tabs the history never heard of come last, in strip order`() {
        // Restored from a session, or opened before the workspace started
        // tracking. Dropping them would hide a tab from the switcher, which is
        // worse than an imperfect order.
        val order = mruOrder(
            paths = listOf("a.rs", "b.rs", "c.rs", "d.rs"),
            history = listOf("c.rs"),
        )
        assertEquals(listOf("c.rs", "a.rs", "b.rs", "d.rs"), order)
    }

    @Test
    fun `history entries for closed tabs are ignored`() {
        val order = mruOrder(
            paths = listOf("a.rs"),
            history = listOf("gone.rs", "a.rs", "also-gone.rs"),
        )
        assertEquals(listOf("a.rs"), order)
    }

    @Test
    fun `an empty pane has an empty order`() {
        assertEquals(emptyList<String>(), mruOrder(emptyList(), listOf("a.rs")))
    }

    @Test
    fun `stepping forward wraps at the end`() {
        assertEquals(1, mruStep(size = 3, index = 0, delta = 1))
        assertEquals(2, mruStep(size = 3, index = 1, delta = 1))
        assertEquals(0, mruStep(size = 3, index = 2, delta = 1))
    }

    @Test
    fun `stepping backward wraps at the start`() {
        // Ctrl+Shift+Tab, which Zed spells `{ select_last: true }`.
        assertEquals(2, mruStep(size = 3, index = 0, delta = -1))
        assertEquals(0, mruStep(size = 3, index = 1, delta = -1))
    }

    @Test
    fun `stepping an empty ring stays put rather than dividing by zero`() {
        assertEquals(0, mruStep(size = 0, index = 0, delta = 1))
    }
}
