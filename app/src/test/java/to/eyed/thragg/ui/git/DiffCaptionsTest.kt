package to.eyed.thragg.ui.git

import org.junit.Assert.assertEquals
import org.junit.Test
import to.eyed.thragg.core.FileDiff

/**
 * What a hunkless file section is captioned. git prints a bare header for
 * four different reasons; the device test caught the diff view calling a
 * commit's empty new `.gitignore` "Only the file's mode changed." — a new
 * file has no old mode.
 */
class DiffCaptionsTest {

    private fun diff(
        original: String? = null,
        created: Boolean = false,
        deleted: Boolean = false,
    ) = FileDiff(
        path = "a.txt",
        original = original,
        isBinary = false,
        created = created,
        deleted = deleted,
        hunks = emptyList(),
    )

    @Test
    fun anEmptyNewFileSaysAddedNotModeChanged() {
        assertEquals("Empty file added.", hunklessCaption(diff(created = true)))
    }

    @Test
    fun anEmptyDeletedFileSaysDeleted() {
        assertEquals("Empty file deleted.", hunklessCaption(diff(deleted = true)))
    }

    @Test
    fun aPureRenameSaysRenamed() {
        assertEquals(
            "Renamed — the contents are unchanged.",
            hunklessCaption(diff(original = "old.txt")),
        )
    }

    @Test
    fun onlyARealModeChangeBlamesTheMode() {
        assertEquals("Only the file's mode changed.", hunklessCaption(diff()))
    }
}
