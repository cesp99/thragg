package to.eyed.seeker.code.ui.git

import org.junit.Assert.assertEquals
import org.junit.Test
import to.eyed.seeker.code.core.BlameLine
import to.eyed.seeker.code.core.StashEntry

/**
 * The stash picker's rows and titles, and the blame column's text rules —
 * the pure halves of the two pickers the editor's git commands open.
 */
class StashPickerRowsTest {

    private fun entry(index: Int, message: String, branch: String? = "main") =
        StashEntry(index = index, sha = "sha$index", message = message, branch = branch, timestamp = 0)

    @Test
    fun rowsAreNewestFirstAndTitledAsZedTitlesThem() {
        val rows = stashPickerRows(listOf(entry(2, "old"), entry(0, "newest"), entry(1, "middle")), "")
        assertEquals(listOf(0, 1, 2), rows.map { it.index })
        assertEquals("#0: newest", stashTitle(rows.first()))
    }

    @Test
    fun theQueryMatchesMessageBranchOrIndexCaseBlind() {
        val entries = listOf(entry(0, "WIP thing", branch = "feature"), entry(1, "other", branch = "main"))
        assertEquals(listOf(0), stashPickerRows(entries, "wip").map { it.index })
        assertEquals(listOf(0), stashPickerRows(entries, "FEAT").map { it.index })
        assertEquals(listOf(1), stashPickerRows(entries, "#1").map { it.index })
        assertEquals(emptyList<Int>(), stashPickerRows(entries, "nothing").map { it.index })
    }

    @Test
    fun theBlameAuthorIsCappedAtTwentyCharacters() {
        val long = BlameLine("abc1234", 0, 1, "Bartholomew Fitzgerald-Smythe", 1, "s")
        assertEquals(20, blameAuthor(long).length)
        assertEquals("Bartholomew Fitzger…", blameAuthor(long))
        val short = BlameLine("abc1234", 0, 1, "Ada", 1, "s")
        assertEquals("Ada", blameAuthor(short))
        val uncommitted = BlameLine("0000000000", 0, 1, "", 0, "")
        assertEquals("Uncommitted", blameAuthor(uncommitted))
    }

    @Test
    fun theShaIndexIsTheFirstFourBytes() {
        assertEquals(0x0000ff00, shaIndex("0000ff00abcdef"))
        // Two shas alike in their first four bytes share a colour.
        assertEquals(shaIndex("deadbeef11"), shaIndex("deadbeef22"))
    }
}
