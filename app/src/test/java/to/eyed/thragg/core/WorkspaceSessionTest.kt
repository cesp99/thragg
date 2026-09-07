package to.eyed.thragg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session document's JSON, which is the engine's format key for key
 * (`engine/src/session.rs`). If these two ever disagree, a relaunch silently
 * loses your place — so the round trip is pinned here as well as there.
 */
class WorkspaceSessionTest {

    @Test
    fun aDocumentSurvivesARoundTrip() {
        val session = WorkspaceSession(
            root = "/data/projects/vault-counter",
            files = listOf(
                SessionItem(path = "README.md"),
                SessionItem(path = "logo.png", kind = SessionItemKind.Media),
                SessionItem(
                    path = "programs/vault_counter/src/lib.rs",
                    scroll = 128.5f,
                    selections = listOf(SessionSelection(3, 0, 3, 7), SessionSelection(9, 2, 9, 2)),
                ),
            ),
            destination = "build",
            shellMode = true,
        )
        val json = session.toJson()
        assertTrue(json.contains("\"version\":${WorkspaceSession.VERSION}"))
        assertTrue(json.contains("\"shell_mode\":true"))
        assertTrue(json.contains("\"anchor_row\":3"))
        assertEquals(session, WorkspaceSession.parse(json))
    }

    @Test
    fun theDefaultsAreCodeWithNothingOpen() {
        val parsed = WorkspaceSession.parse("""{"version":${WorkspaceSession.VERSION},"root":"/p"}""")!!
        assertEquals("/p", parsed.root)
        assertEquals(emptyList<SessionItem>(), parsed.files)
        assertEquals("code", parsed.destination)
        assertEquals(false, parsed.shellMode)
    }

    /** The desktop shell's version-1 pane tree is not guessed at. */
    @Test
    fun aDocumentFromAnotherFormatVersionIsRefused() {
        assertNull(WorkspaceSession.parse("""{"version":1,"root":"/p","panes":{"kind":"leaf"}}"""))
        assertNull(WorkspaceSession.parse("""{"root":"/p"}"""))
    }

    @Test
    fun garbageIsNullRatherThanACrash() {
        assertNull(WorkspaceSession.parse(null))
        assertNull(WorkspaceSession.parse(""))
        assertNull(WorkspaceSession.parse("{ not json"))
        assertNull(WorkspaceSession.parse("[]"))
    }

    @Test
    fun anItemWithNoPathIsDroppedRatherThanOpenedAtNothing() {
        val parsed = WorkspaceSession.parse(
            """{"version":${WorkspaceSession.VERSION},"root":"/p","files":[{"kind":"text"},{"path":"a.rs","scroll":-1}]}""",
        )!!
        assertEquals(listOf("a.rs"), parsed.files.map { it.path })
    }

    @Test
    fun theRecentListReadsTheEnginesArrayAndToleratesRubbish() {
        val recent = RecentProject.parseList(
            """[{"path":"/p/one","name":"one","last_opened":5},{"name":"nopath"},{"path":"/p/two","last_opened":3}]""",
        )
        assertEquals(listOf("/p/one", "/p/two"), recent.map { it.path })
        assertEquals("two", recent[1].name)
        assertEquals(emptyList<RecentProject>(), RecentProject.parseList("not json"))
    }
}
