package to.eyed.seeker.code.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.seeker.code.core.ProjectEntry

/**
 * The sticky-header derivation, which is pure: given the flattened rows and
 * what the viewport shows, which ancestors pin, and when the last one is being
 * pushed off. Mirrors Zed's `find_sticky_anchor`
 * (ui/src/components/sticky_items.rs:285-316) and `sticky_parents`
 * (project_panel.rs:6824-6846), so each case below states which behaviour of
 * the original it pins down.
 */
class ProjectPanelStickyTest {

    private fun row(path: String, depth: Int, isDir: Boolean = false) = ProjectTreeRow(
        entry = ProjectEntry(
            path = path,
            name = path.substringAfterLast('/'),
            isDir = isDir,
            isIgnored = false,
            isHidden = false,
            isUnloaded = false,
            size = 0L,
        ),
        depth = depth,
    )

    /**
     * A row's identity is its folder and its path — see [ProjectTreeRow.key].
     * These rows are all in the project's own folder, id 0.
     */
    private fun key(path: String) = rowKey(0L, path)

    // A deep subtree, flattened the way ProjectTreeState does it:
    //  0 a/           (0)
    //  1 a/b/         (1)
    //  2 a/b/f1       (2)
    //  3 a/b/f2       (2)
    //  4 a/b/f3       (2)
    //  5 a/b/f4       (2)
    //  6 a/g          (1)
    //  7 c/           (0)
    //  8 c/d/         (1)
    //  9 c/d/x        (2)
    private val rows = listOf(
        row("a", 0, isDir = true),
        row("a/b", 1, isDir = true),
        row("a/b/f1", 2),
        row("a/b/f2", 2),
        row("a/b/f3", 2),
        row("a/b/f4", 2),
        row("a/g", 1),
        row("c", 0, isDir = true),
        row("c/d", 1, isDir = true),
        row("c/d/x", 2),
    )

    // --- findStickyAnchor: which visible row the stack is computed for ---

    @Test
    fun aFlatListAnchorsARowWithNoAncestorsSoNothingPins() {
        // All top-level: the anchor is the first row whose depth is smaller
        // than its position, and a depth-0 row has no ancestors to pin.
        val anchor = findStickyAnchor(listOf(0, 0, 0, 0))
        assertEquals(1, anchor!!.localIndex)
        assertFalse(anchor.drifting)
        assertTrue(stickyAncestorsOf(rows, 7).isEmpty())
    }

    @Test
    fun scrolledIntoADeepRunTheFirstUncoveredRowAnchors() {
        // Viewport starts mid-run at depth 2: rows at positions 0..2 sit under
        // where a 2-row stack plus the boundary would be; position 3 is the
        // first with depth < position (sticky_items.rs:292-298).
        val anchor = findStickyAnchor(listOf(2, 2, 2, 2))
        assertEquals(3, anchor!!.localIndex)
        assertFalse(anchor.drifting)
    }

    @Test
    fun theLastChildAnchorsAtItsOwnSlotWithoutDrifting() {
        // f4 (depth 2) at position 2, with a/g (depth 1) next: the next row
        // outdents by one and depth == position — the stack is fully formed
        // and not yet being pushed (sticky_items.rs:300-312).
        val anchor = findStickyAnchor(listOf(2, 2, 2, 1, 0))
        assertEquals(2, anchor!!.localIndex)
        assertFalse(anchor.drifting)
    }

    @Test
    fun theLastChildOneSlotEarlyIsTheDrift() {
        // One row further up: f4 at position 1, still depth 2 — depth ==
        // position + 1, so the deepest pinned row is mid push-off
        // (sticky_items.rs:307-311, `drifting: depth_greater_than_index`).
        val anchor = findStickyAnchor(listOf(2, 2, 1, 0))
        assertEquals(1, anchor!!.localIndex)
        assertTrue(anchor.drifting)
    }

    @Test
    fun tooShortAViewportPinsNothing() {
        // Three visible rows all at depth 2: no row is provably below a
        // formed stack, and no outdent is in sight — Zed shows no stickies.
        assertNull(findStickyAnchor(listOf(2, 2, 2)))
    }

    @Test
    fun anEmptyViewportPinsNothing() {
        assertNull(findStickyAnchor(emptyList()))
    }

    // --- stickyAncestorsOf: which rows the anchor pins ---

    @Test
    fun ancestorsComeBackOutermostFirst() {
        // f3's ancestors are a then a/b — the stack renders top-down in that
        // order, like Zed's reversed sticky_parents (project_panel.rs:6846).
        assertEquals(listOf(0, 1), stickyAncestorsOf(rows, 4))
    }

    @Test
    fun ancestorsAreTakenFromTheRightSubtree() {
        // c/d/x's ancestors are c and c/d, not the earlier a/b at the same
        // depth: the walk takes the *nearest* shallower row each time.
        assertEquals(listOf(7, 8), stickyAncestorsOf(rows, 9))
    }

    @Test
    fun aTopLevelAnchorHasNoAncestors() {
        assertTrue(stickyAncestorsOf(rows, 0).isEmpty())
        assertTrue(stickyAncestorsOf(rows, 7).isEmpty())
    }

    @Test
    fun anOutOfRangeAnchorPinsNothing() {
        // The rows and the scroll position can be a frame apart while the
        // tree reshapes; the derivation must shrug, not throw.
        assertTrue(stickyAncestorsOf(rows, 99).isEmpty())
        assertTrue(stickyAncestorsOf(emptyList(), 0).isEmpty())
    }

    // The fold boundary the basis note on findStickyAnchor describes: two
    // directories side by side under `a`.
    //  0 a/           (0)
    //  1 a/b/         (1)
    //  2 a/b/f1       (2)
    //  3 a/b/f2       (2)
    //  4 a/c/         (1)
    //  5 a/c/f3       (2)
    //  6 a/c/f4       (2)
    //  7 a/c/f5       (2)
    private val fold = listOf(
        row("a", 0, isDir = true),
        row("a/b", 1, isDir = true),
        row("a/b/f1", 2),
        row("a/b/f2", 2),
        row("a/c", 1, isDir = true),
        row("a/c/f3", 2),
        row("a/c/f4", 2),
        row("a/c/f5", 2),
    )

    @Test
    fun atAFoldBoundaryTheLastChildAnchorsAndItsOwnChainPins() {
        // Viewport from a/b/f1, so depths 2, 2, 1, 2, 2, 2. f2 is the last
        // child of a/b and sits in the stack's last slot, so it anchors and
        // the stack — a, a/b — is one scroll row from being pushed off.
        //
        // Zed, whose depths are one higher because its list begins with the
        // worktree root, anchors a/c/f4 here and pins root, a, a/c. That is
        // the divergence [findStickyAnchor]'s doc comment records: its stack
        // is one row longer than any we can draw, so borrowing its basis
        // would land our deepest pin a slot above the row it must cover.
        val firstVisible = 2
        val depths = (firstVisible..7).map { fold[it].depth }
        val anchor = findStickyAnchor(depths)!!
        assertEquals(1, anchor.localIndex)
        assertTrue(anchor.drifting)
        assertEquals(listOf(0, 1), stickyAncestorsOf(fold, firstVisible + anchor.localIndex))
    }

    // --- stickyDriftPx: how far the deepest pinned row has been pushed ---

    @Test
    fun aStackThatIsNotDriftingSitsInItsSlots() {
        // No push-off in sight: every pinned row is at its own slot, whatever
        // the anchor's offset happens to be (sticky_items.rs:179-186).
        assertEquals(0, stickyDriftPx(13, 26, 2, drifting = false))
    }

    @Test
    fun thePushOffTracksTheAnchorsBottomEdge() {
        // Anchor in the stack's last slot, aligned: its bottom edge is the
        // stack's bottom edge, so nothing has been pushed yet.
        assertEquals(0, stickyDriftPx(26, 26, 2, drifting = true))
        // Half a row of scroll takes half a row off the bottom of the stack —
        // it slides, it doesn't swap (sticky_items.rs:179-186).
        assertEquals(-13, stickyDriftPx(13, 26, 2, drifting = true))
        // A full row, and the deepest pinned row has been pushed out of its
        // slot entirely, ready for the next directory to take it.
        assertEquals(-26, stickyDriftPx(0, 26, 2, drifting = true))
    }

    @Test
    fun aSingleRowStackDriftsFromItsOwnSlot() {
        // One pinned ancestor: the anchor's slot is the top of the viewport,
        // so the drift is the anchor's offset once it goes negative.
        assertEquals(0, stickyDriftPx(0, 26, 1, drifting = true))
        assertEquals(-10, stickyDriftPx(-10, 26, 1, drifting = true))
    }

    @Test
    fun theStackNeverDriftsDownwards() {
        // `min(Pixels::ZERO)`: rows and layout can be a frame apart, and an
        // anchor measured below its slot must not push the stack *into* the
        // list (sticky_items.rs:183).
        assertEquals(0, stickyDriftPx(60, 26, 2, drifting = true))
    }

    // --- the two halves together, as the panel drives them ---

    @Test
    fun theStackForAViewportIsTheTopRowsAncestryDeepInATree() {
        // Viewport shows rows 2..5 (f1..f4): anchor is f4 at local position 3,
        // and the pinned rows are a and a/b, in that order.
        val firstVisible = 2
        val depths = (firstVisible..5).map { rows[it].depth }
        val anchor = findStickyAnchor(depths)!!
        val ancestors = stickyAncestorsOf(rows, firstVisible + anchor.localIndex)
        assertEquals(listOf(0, 1), ancestors)
    }

    // --- activeGuideRun: the guide lit for the selection ---

    @Test
    fun aSelectedFileLightsItsParentsRun() {
        // f2 selected: the run under a/b — level 2 in rendered coordinates
        // (children draw one level in from the root row), spanning f1..f4
        // (project_panel.rs:6724-6790).
        val run = activeGuideRun(rows, key("a/b/f2")) { true }
        assertEquals(ActiveGuideRun(level = 2, first = 2, last = 5), run)
    }

    @Test
    fun aSelectedExpandedDirectoryLightsItsOwnRun() {
        // a/b selected while expanded: Zed stops the upward walk at the first
        // expanded directory — the selection itself.
        val run = activeGuideRun(rows, key("a/b")) { true }
        assertEquals(ActiveGuideRun(level = 2, first = 2, last = 5), run)
    }

    @Test
    fun aSelectedCollapsedDirectoryLightsItsParentsRun() {
        // a/b selected but collapsed: the nearest expanded ancestor is a, so
        // a's run lights up — every row under a, a/b included.
        val run = activeGuideRun(rows, key("a/b")) { it != key("a/b") }
        assertEquals(ActiveGuideRun(level = 1, first = 1, last = 6), run)
    }

    @Test
    fun aTopLevelSelectionLightsTheRootRun() {
        // A top-level file has the root row for a parent, and the root's run
        // is the whole list — Zed's worktree root is the expanded ancestor of
        // a top-level selection.
        val flat = listOf(row("x", 0), row("y", 0), row("z", 0))
        val run = activeGuideRun(flat, key("y")) { true }
        assertEquals(ActiveGuideRun(level = 0, first = 0, last = 2), run)
    }

    @Test
    fun noSelectionNoActiveGuide() {
        assertNull(activeGuideRun(rows, null) { true })
        assertNull(activeGuideRun(rows, key("not/here")) { true })
    }

    @Test
    fun anEmptyExpandedDirectoryHangsNoGuide() {
        val sparse = listOf(row("a", 0, isDir = true), row("b", 0, isDir = true))
        assertNull(activeGuideRun(sparse, key("a")) { true })
    }
}
