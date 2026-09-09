package to.eyed.thragg.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the action row docks: the keyboard's overlap with the pane, from the
 * pane's *current* bottom edge. The case that mattered is the last one — the
 * pane's bottom moving after the bars under it hide, which the row has to
 * follow rather than keep the number it was composed with.
 */
class ImeDockTest {

    @Test
    fun aPaneReachingTheWindowBottomIsCoveredByTheWholeKeyboard() {
        assertEquals(800f, imeOverlap(imeBottomPx = 800f, windowHeightPx = 2400f, paneBottomPx = 2400f))
    }

    @Test
    fun whatSitsUnderThePaneComesOffTheOverlap() {
        // 100px of bars between the pane and the window's bottom edge.
        assertEquals(700f, imeOverlap(imeBottomPx = 800f, windowHeightPx = 2400f, paneBottomPx = 2300f))
    }

    @Test
    fun aKeyboardEndingBelowThePaneCoversNoneOfIt() {
        assertEquals(0f, imeOverlap(imeBottomPx = 800f, windowHeightPx = 2400f, paneBottomPx = 1500f))
    }

    @Test
    fun aHiddenKeyboardCoversNothing() {
        assertEquals(0f, imeOverlap(imeBottomPx = 0f, windowHeightPx = 2400f, paneBottomPx = 2400f))
    }

    @Test
    fun anUnplacedPaneIsTakenToReachTheWindowBottom() {
        assertEquals(800f, imeOverlap(imeBottomPx = 800f, windowHeightPx = 2400f, paneBottomPx = -1f))
    }

    /**
     * The keyboard shows, the nav bar (56dp), file bar (44dp) and status line
     * (28dp) hide, and the pane's bottom drops by their 128dp. The overlap
     * measured before that relayout is 128dp short — a row lifted by it sits
     * 128dp under the keyboard. Recomputed from the new bottom it is right.
     */
    @Test
    fun theOverlapFollowsThePaneBottomAcrossTheBarsHiding() {
        val density = 2.625f
        val bars = 128f * density
        val ime = 900f
        val window = 2400f
        val before = imeOverlap(ime, window, paneBottomPx = window - bars)
        val after = imeOverlap(ime, window, paneBottomPx = window)
        assertEquals(ime - bars, before)
        assertEquals(ime, after)
    }
}
