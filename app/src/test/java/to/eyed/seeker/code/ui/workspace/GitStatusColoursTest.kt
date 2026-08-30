package to.eyed.seeker.code.ui.workspace

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The colour rule, which is pure and therefore cheap to pin down.
 *
 * Two behaviours matter beyond "each status gets its own colour": ignored
 * beats status (an ignored file that is also modified still reads as ignored,
 * as in Zed), and it only does so while the panel is set to dim ignored
 * entries at all.
 */
class GitStatusColoursTest {

    private val colours = GitStatusColours(
        default = Color(0xFF111111),
        modified = Color(0xFF222222),
        added = Color(0xFF333333),
        untracked = Color(0xFF444444),
        deleted = Color(0xFF555555),
        renamed = Color(0xFF666666),
        conflicted = Color(0xFF777777),
        ignored = Color(0xFF888888),
    )

    @Test
    fun eachStatusPaintsItsOwnColour() {
        assertEquals(colours.modified, colours.colorFor(GitFileStatus.Modified))
        assertEquals(colours.added, colours.colorFor(GitFileStatus.Added))
        assertEquals(colours.untracked, colours.colorFor(GitFileStatus.Untracked))
        assertEquals(colours.deleted, colours.colorFor(GitFileStatus.Deleted))
        assertEquals(colours.renamed, colours.colorFor(GitFileStatus.Renamed))
        assertEquals(colours.conflicted, colours.colorFor(GitFileStatus.Conflicted))
        assertEquals(colours.default, colours.colorFor(GitFileStatus.None))
    }

    @Test
    fun aRealChangeBeatsIgnored() {
        // Zed's `entry_git_aware_label_color` checks conflict, deleted,
        // modified and created before it ever looks at ignored-ness
        // (editor/src/items.rs:2205-2219): an ignored file that is also
        // modified reads as modified.
        assertEquals(
            colours.modified,
            colours.colorFor(GitFileStatus.Modified, isIgnored = true, dimIgnored = true),
        )
        // With no change to show, ignored-ness still dims.
        assertEquals(
            colours.ignored,
            colours.colorFor(GitFileStatus.Ignored, isIgnored = false, dimIgnored = true),
        )
        assertEquals(
            colours.ignored,
            colours.colorFor(GitFileStatus.None, isIgnored = true, dimIgnored = true),
        )
    }

    @Test
    fun ignoredIsNotDimmedWhenTheSettingSaysNotTo() {
        assertEquals(
            colours.modified,
            colours.colorFor(GitFileStatus.Modified, isIgnored = true, dimIgnored = false),
        )
        assertEquals(
            colours.default,
            colours.colorFor(GitFileStatus.Ignored, isIgnored = true, dimIgnored = false),
        )
    }

    /** A snapshot must not alias a caller's mutable map: @Immutable is a promise. */
    @Test
    fun snapshotDoesNotAliasTheCallersMap() {
        val live = HashMap<String, GitFileStatus>()
        live["src/main.rs"] = GitFileStatus.Modified
        val snapshot = GitStatusSnapshot.of(1L, live)

        live["src/main.rs"] = GitFileStatus.Deleted
        live["src/other.rs"] = GitFileStatus.Added

        assertEquals(GitFileStatus.Modified, snapshot.statusOf("src/main.rs"))
        assertEquals(GitFileStatus.None, snapshot.statusOf("src/other.rs"))
    }

    @Test
    fun anUnknownPathIsNotAStatus() {
        val snapshot = GitStatusSnapshot.of(3L, mapOf("a.txt" to GitFileStatus.Added))
        assertEquals(GitFileStatus.None, snapshot.statusOf("b.txt"))
        assertEquals(GitFileStatus.None, GitStatusSnapshot.Empty.statusOf("a.txt"))
    }
}
