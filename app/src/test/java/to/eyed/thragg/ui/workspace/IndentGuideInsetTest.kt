package to.eyed.thragg.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a guide run's 4px end insets go.
 *
 * Zed pulls a run in by `PADDING_Y` = `px(4.)` at each of its ends
 * (project_panel.rs:7215, applied at 7231-7248). We decide that per *end* from
 * the flattened tree — this row holds an end of level ℓ's run exactly when the
 * neighbouring row that way no longer draws ℓ — where Zed decides it per *run*
 * from the visible window; [guideRunEndsHere] carries the full reconciliation.
 *
 * The rule is one comparison, and it has been written the wrong way round
 * before (`<` instead of `<=` drops the inset on every run whose neighbour is
 * exactly one level out, which is the common case), so it is pinned here.
 */
class IndentGuideInsetTest {

    @Test
    fun aNeighbourThatStillDrawsTheLevelIsNotAnEnd() {
        // A row two levels deep next to one three levels deep: level 1's guide
        // runs straight through both.
        assertFalse(guideRunEndsHere(level = 1, neighbourRenderedDepth = 3))
        assertFalse(guideRunEndsHere(level = 1, neighbourRenderedDepth = 2))
    }

    @Test
    fun aNeighbourAtTheLevelItselfIsAnEnd() {
        // A row draws levels 0 until depth, so a neighbour of rendered depth 2
        // draws levels 0 and 1 — level 2's run stops here. The boundary case,
        // and the one a `<` would get wrong.
        assertTrue(guideRunEndsHere(level = 2, neighbourRenderedDepth = 2))
    }

    @Test
    fun aShallowerNeighbourIsAnEnd() {
        assertTrue(guideRunEndsHere(level = 3, neighbourRenderedDepth = 1))
        assertTrue(guideRunEndsHere(level = 0, neighbourRenderedDepth = 0))
    }

    @Test
    fun theListsOwnEdgesAreEnds() {
        // The panel passes 0 for the space above the first row and below the
        // last: the root row above the list draws no guides, and neither does
        // the empty space under the tree, so every run really does end there.
        for (level in 0..4) {
            assertTrue(guideRunEndsHere(level, neighbourRenderedDepth = 0))
        }
    }

    @Test
    fun aStickyRowNeverInsets() {
        // The default the pinned copies are drawn with. Zed's sticky guide
        // decoration has no insets at all (project_panel.rs:7280-7311), and a
        // pinned row is a slice out of the middle of its run by construction.
        for (level in 0..4) {
            assertFalse(guideRunEndsHere(level, neighbourRenderedDepth = Int.MAX_VALUE))
        }
    }
}
