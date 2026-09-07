package to.eyed.thragg.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import to.eyed.thragg.core.SessionItem
import to.eyed.thragg.core.SessionSelection
import to.eyed.thragg.core.WorkspaceSession

/**
 * The pure half of the restore: the order the files are asked for, and the
 * spelling of the destination. The rest — the engine's validation, the
 * caret going back into a buffer — is tested on the engine's side and on the
 * device respectively.
 */
class SessionRestoreTest {

    @Test
    fun filesAreOpenedOldestFirstSoTheActiveOneEndsUpActive() {
        val session = WorkspaceSession(
            root = "/p",
            files = listOf(
                SessionItem("README.md"),
                SessionItem("src/lib.rs", scroll = 40f, selections = listOf(SessionSelection(2, 1, 2, 1))),
            ),
        )
        val plan = SessionRestore.plan(session)
        assertEquals(listOf("README.md", "src/lib.rs"), plan.map { it.path })
        // The saved place travels whole; the line/column jump is not used.
        assertEquals(session.files[1], plan[1].restore)
        assertEquals(0, plan[1].row)
    }

    @Test
    fun theDestinationIsSpelledLowerCaseInTheDocument() {
        for (destination in Destination.entries) {
            assertEquals(destination, SessionRestore.destinationOf(destination.name.lowercase()))
        }
        assertNull(SessionRestore.destinationOf("settings"))
        assertNull(SessionRestore.destinationOf(""))
    }
}
