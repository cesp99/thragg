package to.eyed.thragg.ui.shell.build

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the build log should keep its newest row on screen — the user's
 * *intent*, held apart from the list's layout.
 *
 * The first version derived "am I at the end" from the `LazyColumn`'s layout
 * at the moment a burst of rows arrived, and scrolled only if so. Seen on the
 * Seeker 2026-09-08, pressing Test on the Anchor scaffold: the log followed
 * the first `yarn` lines (8 s in) and then never moved again; when the run
 * finished 5 min 52 s later the "tested" line was thousands of pixels below
 * the fold and nobody had touched the screen. The same for a Seahorse build.
 * Three things break a layout-derived answer, and any one of them is enough
 * because a false answer is permanent — nothing ever set it true again:
 *
 *  - a 100 ms flush (`BuildLog.FLUSH_INTERVAL_NS`) appends dozens of rows at
 *    once, and if the list has already been measured with them when the
 *    effect asks, the last *visible* row is dozens short of the new end;
 *  - the island shrinks when the Problems chip or the "Deployed on devnet"
 *    card appears above it (`BuildScreen`), which pushes the end below the
 *    fold with no rows added and no scroll made;
 *  - a `BuildLogRow.Progress` redraw replaces the last row without changing
 *    `rows.size`, so a line that wraps to more pixels grows past the bottom.
 *
 * So the rule here is stated the way docs/UI.md states it — *follow the tail
 * until the user scrolls up; a user scroll back to the bottom re-engages* —
 * and only a **user** scroll changes [following]. A programmatic scroll never
 * does, and neither does a layout change. The composable owns the two halves
 * this class is blind to: telling a user scroll from its own (a nested-scroll
 * connection on the list, which programmatic `scrollToItem` calls bypass) and
 * bringing the end into view whenever [following] and the rows or the
 * viewport change (`BuildLogView`).
 */
internal class TailFollow(following: Boolean = true) {

    /** True while the newest row is to be kept on screen. Snapshot state. */
    var following: Boolean by mutableStateOf(following)
        private set

    /**
     * A user-driven scroll delta has just been applied; [endInView] is the
     * layout *after* it.
     *
     * A delta toward the start is the gesture the rule names: following is
     * then exactly whether the end is still on screen, so a nudge shorter
     * than the after-padding does not disengage but a real scroll up does.
     * A delta toward the end can only *engage* — it re-engages when the end
     * comes back into view, and it never disengages, because a user who is
     * dragging toward the tail while the log outruns them has not left it.
     */
    fun onUserScroll(towardStart: Boolean, endInView: Boolean) {
        following = if (towardStart) endInView else following || endInView
    }

    /** The bar's current tab was re-tapped: back to the end, whatever came before. */
    fun rejoin() {
        following = true
    }

    companion object {
        /**
         * Is the tail on screen? Pure, over the four numbers a
         * `LazyListLayoutInfo` gives: the newest visible row's index and
         * bottom edge, the row count, and the viewport's end offset (which
         * includes the after-padding, so that padding is the slack).
         */
        fun endInView(
            lastVisibleIndex: Int,
            totalItems: Int,
            lastVisibleBottom: Int,
            viewportEnd: Int,
        ): Boolean = totalItems == 0 ||
            (lastVisibleIndex == totalItems - 1 && lastVisibleBottom <= viewportEnd)

        /**
         * How many pixels of the newest row hang below the content's end
         * after `scrollToItem(lastIndex)`. That call puts the row's *top* at
         * the top of the viewport and lets the measure pull the content down
         * to fill — right for a one-line row, wrong for a row taller than
         * the island (a wrapped yarn error, a rendered diagnostic), whose
         * tail it leaves below the fold. Zero when the newest row is not the
         * one visible, or fits.
         */
        fun overflow(
            lastVisibleIndex: Int,
            totalItems: Int,
            lastVisibleBottom: Int,
            contentEnd: Int,
        ): Int = if (totalItems > 0 && lastVisibleIndex == totalItems - 1) {
            (lastVisibleBottom - contentEnd).coerceAtLeast(0)
        } else {
            0
        }
    }
}
