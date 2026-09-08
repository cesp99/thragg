package to.eyed.thragg.ui.shell.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The follow rule, pinned on host: follow the tail until the user scrolls
 * up; a user scroll back to the bottom re-engages. The log that stopped
 * following on the Seeker 2026-09-08 (Test on the Anchor scaffold, stuck on
 * the first `yarn` lines for 5 min 52 s with nobody touching the screen) had
 * derived this from the layout instead (`TailFollow`).
 */
class TailFollowTest {

    @Test
    fun `follows from the start`() {
        assertTrue(TailFollow().following)
    }

    @Test
    fun `a user scroll up that leaves the end stops following`() {
        val follow = TailFollow()
        follow.onUserScroll(towardStart = true, endInView = false)
        assertFalse(follow.following)
    }

    @Test
    fun `a nudge up that keeps the end on screen does not`() {
        val follow = TailFollow()
        follow.onUserScroll(towardStart = true, endInView = true)
        assertTrue(follow.following)
    }

    @Test
    fun `scrolling back down re-engages only once the end is in view`() {
        val follow = TailFollow()
        follow.onUserScroll(towardStart = true, endInView = false)
        follow.onUserScroll(towardStart = false, endInView = false)
        assertFalse(follow.following)
        follow.onUserScroll(towardStart = false, endInView = true)
        assertTrue(follow.following)
    }

    /**
     * The burst-while-dragging case: rows arrive under a finger that is
     * moving toward the tail, so the end is momentarily out of view. That
     * is the log outrunning the user, not the user leaving the log.
     */
    @Test
    fun `a scroll toward the end never disengages`() {
        val follow = TailFollow()
        follow.onUserScroll(towardStart = false, endInView = false)
        assertTrue(follow.following)
    }

    @Test
    fun `a re-tap on the tab rejoins`() {
        val follow = TailFollow()
        follow.onUserScroll(towardStart = true, endInView = false)
        follow.rejoin()
        assertTrue(follow.following)
    }

    // --- the layout predicates ----------------------------------------------------------

    /** Ten rows, the tenth visible, a viewport ending at 808 (800 plus 8 of after-padding). */
    private fun endInView(bottom: Int, lastVisible: Int = 9, total: Int = 10) =
        TailFollow.endInView(lastVisible, total, lastVisibleBottom = bottom, viewportEnd = 808)

    private fun overflow(bottom: Int, lastVisible: Int = 9, total: Int = 10) =
        TailFollow.overflow(lastVisible, total, lastVisibleBottom = bottom, contentEnd = 800)

    @Test
    fun `the end is in view when the last row's bottom is inside the viewport`() {
        assertTrue(endInView(bottom = 800))
        // Flush with the end, and the after-padding counts as slack.
        assertTrue(endInView(bottom = 808))
        assertFalse(endInView(bottom = 809))
    }

    /** The 2026-09-08 shape: forty rows appended, the last visible one dozens short. */
    @Test
    fun `the end is not in view when the newest row is not the visible one`() {
        assertFalse(endInView(bottom = 400, lastVisible = 9, total = 50))
    }

    @Test
    fun `an empty log is at its end`() {
        assertTrue(endInView(bottom = 0, lastVisible = -1, total = 0))
    }

    @Test
    fun `overflow is what hangs below the content end, and only for the newest row`() {
        assertEquals(0, overflow(bottom = 700))
        assertEquals(0, overflow(bottom = 800))
        // A wrapped yarn error taller than the island, top-aligned by scrollToItem.
        assertEquals(1200, overflow(bottom = 2000))
        assertEquals(0, overflow(bottom = 2000, lastVisible = 3))
        assertEquals(0, overflow(bottom = 0, lastVisible = -1, total = 0))
    }
}
