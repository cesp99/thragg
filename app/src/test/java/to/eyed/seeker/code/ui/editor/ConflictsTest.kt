package to.eyed.seeker.code.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor's side of a merge conflict: reading the engine's regions, which
 * row belongs to which, and where "next conflict" goes.
 *
 * The parsing of the markers themselves is the engine's and tested there
 * (git_conflict.rs); what is tested here is the arithmetic the draw pass and
 * the motions do over the rows it hands back — the one binary search that is
 * asked once per drawn row, and the wrap the motions share with the
 * diagnostic ones.
 */
class ConflictsTest {

    private fun region(start: Int, oursEnd: Int, theirsStart: Int, end: Int, base: IntRange? = null) =
        ConflictRegion(
            oursBranchName = "HEAD",
            theirsBranchName = "feature",
            startRow = start,
            endRow = end,
            oursStartRow = start + 1,
            oursEndRow = oursEnd,
            baseStartRow = base?.first ?: -1,
            baseEndRow = base?.let { it.last + 1 } ?: -1,
            theirsStartRow = theirsStart,
            theirsEndRow = end - 1,
        )

    // <<<<<<< at 2, ours 3..4, ======= at 5, theirs 6..7, >>>>>>> at 8.
    private val first = region(start = 2, oursEnd = 5, theirsStart = 6, end = 9)

    // <<<<<<< at 20, ours 21, ||||||| at 22, base 23, ======= at 24, theirs 25, >>>>>>> at 26.
    private val second = region(start = 20, oursEnd = 22, theirsStart = 25, end = 27, base = 23..23)

    private val conflicts = listOf(first, second)

    @Test
    fun theEngineJsonIsReadRowForRow() {
        val json = """
            [{"ours_branch_name":"HEAD","theirs_branch_name":"feature",
              "range":{"start":0,"end":50},"ours":{"start":13,"end":18},"theirs":{"start":27,"end":34},
              "base":null,"start_row":1,"end_row":6,
              "ours_rows":{"start":2,"end":3},"base_rows":null,"theirs_rows":{"start":4,"end":5}}]
        """.trimIndent()
        val parsed = ConflictRegion.parseAll(json)
        assertEquals(1, parsed.size)
        val region = parsed[0]
        assertEquals("HEAD", region.oursBranchName)
        assertEquals("feature", region.theirsBranchName)
        assertEquals(1, region.startRow)
        assertEquals(6, region.endRow)
        assertEquals(2..3, region.oursStartRow..region.oursEndRow)
        assertEquals(4..5, region.theirsStartRow..region.theirsEndRow)
        assertFalse(region.hasBase)
        assertTrue(second.hasBase)
    }

    @Test
    fun anEmptyOrBrokenAnswerIsNoConflicts() {
        assertTrue(ConflictRegion.parseAll(null).isEmpty())
        assertTrue(ConflictRegion.parseAll("").isEmpty())
        assertTrue(ConflictRegion.parseAll("[]").isEmpty())
        assertTrue(ConflictRegion.parseAll("not json").isEmpty())
    }

    @Test
    fun aRowInsideAConflictFindsIt() {
        assertEquals(first, conflictAt(conflicts, 2))
        assertEquals(first, conflictAt(conflicts, 8))
        assertEquals(second, conflictAt(conflicts, 23))
    }

    @Test
    fun aRowOutsideEveryConflictFindsNone() {
        assertNull(conflictAt(conflicts, 1))
        assertNull(conflictAt(conflicts, 9))
        assertNull(conflictAt(conflicts, 19))
        assertNull(conflictAt(conflicts, 27))
        assertNull(conflictAt(emptyList(), 0))
    }

    @Test
    fun theOpeningMarkerAndOursAreOursTheRestIsTheirs() {
        // Zed paints the region in theirs' colour and then the `<<<<<<<`
        // line and ours over it (conflict_view.rs:307-326): the separator,
        // and a diff3 base, come out as theirs.
        assertEquals(ConflictSide.Ours, first.sideOf(2))
        assertEquals(ConflictSide.Ours, first.sideOf(4))
        assertEquals(ConflictSide.Theirs, first.sideOf(5))
        assertEquals(ConflictSide.Theirs, first.sideOf(8))
        assertNull(first.sideOf(9))
        assertEquals(ConflictSide.Theirs, second.sideOf(22))
        assertEquals(ConflictSide.Theirs, second.sideOf(23))
    }

    @Test
    fun nextGoesToTheFirstConflictStartingAfterTheCaretAndWraps() {
        assertEquals(first, nextConflict(conflicts, 0, forward = true))
        // From inside the first, next is the second — not the one we are in.
        assertEquals(second, nextConflict(conflicts, 2, forward = true))
        assertEquals(second, nextConflict(conflicts, 8, forward = true))
        assertEquals(first, nextConflict(conflicts, 20, forward = true))
        assertEquals(first, nextConflict(conflicts, 100, forward = true))
    }

    @Test
    fun previousGoesToTheLastConflictStartingBeforeTheCaretAndWraps() {
        assertEquals(second, nextConflict(conflicts, 100, forward = false))
        assertEquals(first, nextConflict(conflicts, 20, forward = false))
        assertEquals(first, nextConflict(conflicts, 3, forward = false))
        assertEquals(second, nextConflict(conflicts, 2, forward = false))
        assertEquals(second, nextConflict(conflicts, 0, forward = false))
    }

    @Test
    fun noConflictsMeansNowhereToGo() {
        assertNull(nextConflict(emptyList(), 0, forward = true))
        assertNull(nextConflict(emptyList(), 0, forward = false))
    }
}
