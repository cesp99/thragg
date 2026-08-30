package to.eyed.seeker.code.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outline panel's tree, built from the flat `depth` column the engine
 * sends and folded the way Zed's outline panel folds it — plus
 * `auto_reveal_entries`, which is the rule that decides what the panel is
 * showing while somebody scrolls through a file.
 *
 * The fixture is the shape a Rust file actually has: a module with two
 * functions, one of which has a nested closure, and a struct with a field
 * after it.
 */
class OutlineTreeTest {

    private fun entry(label: String, depth: Int, row: Int, endRow: Int) =
        OutlineEntry(label = label, depth = depth, row = row, col = 0, endRow = endRow)

    /**
     * ```
     * 0  mod parser            (0..40)
     * 1    fn parse            (2..20)
     * 2      let scan = |c|    (5..9)
     * 3    fn finish           (22..38)
     * 4  struct Token          (42..50)
     * 5    kind                (44..44)
     * ```
     */
    private val outline = listOf(
        entry("mod parser", 0, 0, 40),
        entry("fn parse", 1, 2, 20),
        entry("scan", 2, 5, 9),
        entry("fn finish", 1, 22, 38),
        entry("struct Token", 0, 42, 50),
        entry("kind", 1, 44, 44),
    )

    // ---- building ------------------------------------------------------------

    @Test
    fun everyEntrysParentIsTheNearestShallowerOneBeforeIt() {
        assertEquals(
            listOf(-1, 0, 1, 0, -1, 4),
            outlineParents(outline).toList(),
        )
    }

    @Test
    fun anOutlineThatStartsDeepStillHasRoots() {
        // A file whose first symbol is nested — a method with no enclosing
        // impl in the query — must not look for a parent that is not there.
        val deep = listOf(entry("a", 2, 0, 4), entry("b", 3, 1, 2))
        assertEquals(listOf(-1, 0), outlineParents(deep).toList())
    }

    @Test
    fun nothingCollapsedDrawsEveryRowInSourceOrder() {
        val rows = flattenOutline(outline, collapsed = emptySet())
        assertEquals(outline.map { it.label }, rows.map { it.entry.label })
        assertEquals(outline.indices.toList(), rows.map { it.index })
    }

    @Test
    fun onlyEntriesWithChildrenOfferATriangle() {
        val rows = flattenOutline(outline, collapsed = emptySet())
        assertEquals(
            listOf(true, true, false, false, true, false),
            rows.map { it.hasChildren },
        )
    }

    @Test
    fun collapsingAParentHidesEveryDescendantNotJustItsChildren() {
        // Folding `mod parser` must take the closure inside `fn parse` with
        // it: a grandchild whose own parent is expanded is still under a
        // fold, and a one-level check would have left it on screen.
        val rows = flattenOutline(outline, collapsed = setOf(0))
        assertEquals(listOf("mod parser", "struct Token", "kind"), rows.map { it.entry.label })
        assertFalse(rows.first().isExpanded)
    }

    @Test
    fun collapsingAMiddleEntryLeavesItsSiblingsAlone() {
        val rows = flattenOutline(outline, collapsed = setOf(1))
        assertEquals(
            listOf("mod parser", "fn parse", "fn finish", "struct Token", "kind"),
            rows.map { it.entry.label },
        )
    }

    @Test
    fun anEmptyOutlineDrawsNothing() {
        assertTrue(flattenOutline(emptyList(), collapsed = setOf(0)).isEmpty())
    }

    // ---- following the caret -------------------------------------------------

    @Test
    fun theCaretPicksTheDeepestSymbolContainingIt() {
        assertEquals(2, outlineIndexAt(outline, caretRow = 6))
        assertEquals(1, outlineIndexAt(outline, caretRow = 15))
        assertEquals(0, outlineIndexAt(outline, caretRow = 21))
        assertEquals(5, outlineIndexAt(outline, caretRow = 44))
    }

    /**
     * A caret between two top-level items belongs to nothing, and the panel
     * leaves its selection where it was rather than snapping to row 0.
     */
    @Test
    fun aCaretInNoSymbolSelectsNothing() {
        assertNull(outlineIndexAt(outline, caretRow = 41))
        assertNull(outlineReveal(outline, caretRow = 41, collapsed = setOf(0)))
    }

    @Test
    fun revealingOpensEveryAncestorOfTheCaretsSymbol() {
        // Everything folded, caret inside the closure: both `mod parser` and
        // `fn parse` have to open, or the row is selected under a fold.
        val reveal = outlineReveal(outline, caretRow = 6, collapsed = setOf(0, 1, 4))
        assertEquals(2, reveal!!.index)
        assertEquals(setOf(4), reveal.collapsed)
    }

    /**
     * The symbol the caret is *in* keeps its own fold: standing on the `fn`
     * line of a function you have just collapsed must not re-open it under
     * you.
     */
    @Test
    fun revealingDoesNotOpenTheSelectedSymbolItself() {
        val reveal = outlineReveal(outline, caretRow = 22, collapsed = setOf(3))
        assertEquals(3, reveal!!.index)
        assertEquals(setOf(3), reveal.collapsed)
    }

    // ---- the filter ----------------------------------------------------------

    @Test
    fun theFilterIsThePickersAndMatchesEveryWordSomewhere() {
        assertEquals(
            listOf("fn parse", "mod parser"),
            filterOutline(outline, "pars").map { it.label }.sorted(),
        )
        assertEquals(emptyList<String>(), filterOutline(outline, "parse token").map { it.label })
        assertEquals(outline.size, filterOutline(outline, "   ").size)
    }
}
