package to.eyed.thragg.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What "RECENT" means in the Projects list.
 *
 * A folder's mtime moves when a direct child is created or removed — not when
 * a file three levels down is edited, and not when the project is opened — so
 * the project you had open all morning read "4 hours ago" and sat at the
 * bottom of a list headed RECENT (QA P-13). The list now ages a project by
 * the later of the two facts it has.
 */
class ProjectsRootTest {

    @Test
    fun `an open is more recent than a folder that has not changed`() {
        val morning = 1_000_000L
        val now = 9_000_000L
        assertEquals(now, ProjectsRoot.touchedAt(folderModified = morning, openedAt = now))
    }

    @Test
    fun `a project written to since it was last opened is aged by the write`() {
        val opened = 1_000_000L
        val written = 4_000_000L
        assertEquals(written, ProjectsRoot.touchedAt(folderModified = written, openedAt = opened))
    }

    @Test
    fun `a project this app has never opened still has its folder to go on`() {
        assertEquals(7L, ProjectsRoot.touchedAt(folderModified = 7L, openedAt = 0L))
    }
}
