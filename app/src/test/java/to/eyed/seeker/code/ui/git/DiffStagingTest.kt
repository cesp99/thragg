package to.eyed.seeker.code.ui.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.seeker.code.core.GitHunk
import to.eyed.seeker.code.core.GitHunkKind
import to.eyed.seeker.code.core.PatchHunk
import to.eyed.seeker.code.core.PatchLine

/**
 * The project diff's translation from git's `@@` blocks — context lines and
 * all — to the buffer rows the engine stages by, and the staged bit read
 * across them. The deletion case is the one worth a test: a run of removed
 * lines has no rows on the new side, and its row is the boundary it sits on.
 */
class DiffStagingTest {

    private fun context(old: Int, new: Int) = PatchLine(' ', "ctx", old, new)
    private fun added(new: Int) = PatchLine('+', "new", 0, new)
    private fun removed(old: Int) = PatchLine('-', "old", old, 0)

    private fun hunk(oldStart: Int, newStart: Int, vararg lines: PatchLine) =
        PatchHunk(oldStart = oldStart, newStart = newStart, heading = "", lines = lines.toList())

    @Test
    fun aModificationCoversItsAddedRows() {
        // Rows 4..5 (1-based) replaced by rows 4..6 of the new file.
        val hunk = hunk(
            1, 1,
            context(1, 1), context(2, 2), context(3, 3),
            removed(4), removed(5),
            added(4), added(5), added(6),
            context(6, 7), context(7, 8), context(8, 9),
        )
        assertEquals(3..5, changedRows(hunk))
    }

    @Test
    fun aDeletionSitsOnTheBoundaryRow() {
        // Old rows 4..5 removed; the new file's row 4 (1-based) is the next
        // kept line, so the deletion is on 0-based row 3 — where the gutter
        // draws its pill and where the engine's hunk lies.
        val hunk = hunk(
            1, 1,
            context(1, 1), context(2, 2), context(3, 3),
            removed(4), removed(5),
            context(6, 4), context(7, 5), context(8, 6),
        )
        assertEquals(3..3, changedRows(hunk))
    }

    @Test
    fun aDeletionAtTheVeryEndSitsPastTheLastRow() {
        // No context after the deletion: the boundary is the row after the
        // last kept line.
        val hunk = hunk(8, 8, context(8, 8), context(9, 9), context(10, 10), removed(11), removed(12))
        assertEquals(10..10, changedRows(hunk))
    }

    @Test
    fun aWholeFileDeletionSitsOnRowZero() {
        val hunk = hunk(1, 0, removed(1), removed(2))
        assertEquals(0..0, changedRows(hunk))
    }

    @Test
    fun twoChangesInOneBlockSpanBoth() {
        // git merges changes fewer than six rows apart into one `@@`; the
        // rows asked of the engine cover both, and the engine stages every
        // hunk on them.
        val hunk = hunk(
            1, 1,
            context(1, 1),
            added(2),
            context(2, 3), context(3, 4),
            removed(4),
            context(5, 5),
        )
        assertEquals(1..4, changedRows(hunk))
    }

    @Test
    fun aBlockOfNothingButContextHasNoRows() {
        assertNull(changedRows(hunk(1, 1, context(1, 1), context(2, 2))))
    }

    @Test
    fun theStagedBitIsReadAcrossEveryHunkOnTheRows() {
        val states = listOf(
            GitHunk(GitHunkKind.Modified, 1, 2, 1, staged = true),
            GitHunk(GitHunkKind.Deleted, 4, 4, 1, staged = false),
            GitHunk(GitHunkKind.Added, 9, 10, 0, staged = true),
        )
        assertTrue(hunkStagedState(states, 1..1)!!)
        // The deletion on its boundary row.
        assertFalse(hunkStagedState(states, 4..4)!!)
        // A block spanning a staged and an unstaged hunk reads Stage.
        assertFalse(hunkStagedState(states, 1..4)!!)
        // Nothing there: not known.
        assertNull(hunkStagedState(states, 6..7))
        assertFalse(fileStagedState(states)!!)
        assertTrue(fileStagedState(states.filter { it.staged == true })!!)
        assertNull(fileStagedState(emptyList()))
    }
}
