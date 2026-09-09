package to.eyed.thragg.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two rules about the tab list that were device findings in the 0.0.22 pass.
 *
 * **The snapshot (B-02).** Three cold starts out of eight died with a
 * `ConcurrentModificationException` inside the status poll: every loop over
 * the tabs suspends in its body — `reload()` and `refreshLanguageSettings()`
 * both hop to IO — and the session restore adds its second tab in exactly that
 * window. [OpenFilesState.tabs] hands out a copy now, so no caller can be
 * standing in the live list when it moves.
 *
 * **The prefix rules (G-07).** The project panel's rename, move, delete and
 * trash all reach the open tabs now, and a *folder* takes the tabs under it
 * with it. `src/lib.rs` must not follow a rename of `s`.
 */
class TabSnapshotTest {

    private fun file(path: String) = OpenFile(path, editor = null)

    @Test
    fun iteratingTheTabsSurvivesTheListMovingUnderTheLoop() {
        val files = OpenFilesState()
        files.open(file("a.rs"))
        files.open(file("b.rs"))
        var seen = 0
        // Precisely the shape of the poll: read, then mutate mid-loop.
        for (tab in files.tabs) {
            seen++
            if (seen == 1) {
                files.open(file("c.rs"))
                files.close(files.indexOfPath("b.rs"))
            }
        }
        assertEquals("the loop walks the list it started with", 2, seen)
        assertEquals(listOf("a.rs", "c.rs"), files.tabs.map { it.path })
    }

    @Test
    fun theSnapshotIsNotTheLiveList() {
        val files = OpenFilesState()
        files.open(file("a.rs"))
        val held = files.tabs
        files.open(file("b.rs"))
        assertEquals(1, held.size)
        assertEquals(2, files.tabs.size)
    }

    @Test
    fun aRenamedFileTakesItsTabWithIt() {
        assertEquals("src/main.rs", movedTabPath("src/lib.rs", "src/lib.rs", "src/main.rs"))
    }

    @Test
    fun aRenamedFolderTakesEveryTabUnderIt() {
        assertEquals("app/lib.rs", movedTabPath("src/lib.rs", "src", "app"))
        assertEquals("app/a/b.rs", movedTabPath("src/a/b.rs", "src", "app"))
    }

    @Test
    fun aPrefixThatIsNotAPathSegmentIsNotAMove() {
        assertNull(movedTabPath("src/lib.rs", "s", "t"))
        assertNull(movedTabPath("srcx/lib.rs", "src", "app"))
        assertNull(movedTabPath("src/lib.rs", "other.rs", "renamed.rs"))
    }

    @Test
    fun anEmptyEndOfTheMoveIsNoMove() {
        assertNull(movedTabPath("src/lib.rs", "", "app"))
        assertNull(movedTabPath("src/lib.rs", "src", ""))
    }

    @Test
    fun aDeletedFolderClosesTheTabsUnderIt() {
        assertTrue(isUnderRemoved("src/lib.rs", "src"))
        assertTrue(isUnderRemoved("src/lib.rs", "src/lib.rs"))
        assertFalse(isUnderRemoved("srcx/lib.rs", "src"))
        assertFalse(isUnderRemoved("src/lib.rs", ""))
    }
}
