package to.eyed.seeker.code.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import to.eyed.seeker.code.core.GitHunk
import to.eyed.seeker.code.core.GitHunkKind
import org.junit.Test

/**
 * Which hunk a drawn row belongs to.
 *
 * A binary search rather than a scan because it is asked once per drawn row,
 * on every frame, and a file under review can have hundreds of hunks — but the
 * reason it is worth a test is the deletion case, which covers *no* rows and
 * would swallow the row after it if `endRow` were treated as inclusive.
 */
class GitGutterTest {

    private fun hunk(kind: GitHunkKind, start: Int, end: Int, old: Int = 0) =
        GitHunk(kind, start, end, old)

    private val hunks = listOf(
        hunk(GitHunkKind.Added, 2, 5),
        hunk(GitHunkKind.Deleted, 9, 9, old = 3),
        hunk(GitHunkKind.Modified, 20, 21),
    )

    @Test
    fun aRowInsideAHunkFindsIt() {
        assertEquals(GitHunkKind.Added, hunkAt(hunks, 2)?.kind)
        assertEquals(GitHunkKind.Added, hunkAt(hunks, 4)?.kind)
        assertEquals(GitHunkKind.Modified, hunkAt(hunks, 20)?.kind)
    }

    @Test
    fun theRowAfterAHunkIsNotInIt() {
        assertNull(hunkAt(hunks, 5))
        assertNull(hunkAt(hunks, 21))
        assertNull(hunkAt(hunks, 0))
    }

    /**
     * A deletion is a boundary, not a run: `startRow == endRow`. If it
     * answered for row 9, the line that replaced the deleted ones would be
     * painted as deleted.
     */
    @Test
    fun aDeletionCoversNoRows() {
        assertNull(hunkAt(hunks, 9))
        assertNull(hunkAt(hunks, 8))
    }

    @Test
    fun noHunksMeansNoAnswers() {
        assertNull(hunkAt(emptyList(), 0))
    }
}
