package to.eyed.seeker.code.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session document's JSON, which is the engine's format key for key
 * (`engine/src/session.rs`). If these two ever disagree, a relaunch silently
 * loses the workspace — so the round trip is pinned here as well as there.
 */
class WorkspaceSessionTest {

    private fun leaf(vararg paths: String) = SessionPane.Leaf(
        items = paths.map { SessionItem(path = it) },
        activeIndex = 0,
        active = true,
    )

    @Test
    fun aDocumentSurvivesARoundTrip() {
        val session = WorkspaceSession(
            root = "/data/projects/welcome",
            panes = SessionPane.Split(
                axis = SessionAxis.Vertical,
                children = listOf(
                    SessionPane.Leaf(
                        items = listOf(
                            SessionItem(
                                path = "src/main.rs",
                                kind = SessionItemKind.Text,
                                pinned = true,
                                scroll = 128.5f,
                                selections = listOf(SessionSelection(3, 0, 3, 7)),
                            ),
                            SessionItem(path = "logo.png", kind = SessionItemKind.Media),
                            // The pane's provisional slot: it has to survive
                            // the round trip, or a browsed project comes back
                            // as a strip of permanent tabs.
                            SessionItem(path = "src/lib.rs", preview = true),
                        ),
                        activeIndex = 1,
                        active = false,
                        history = SessionNavHistory(
                            back = listOf(SessionNavEntry("README.md", row = 2, col = 1, scroll = 8f)),
                            forward = emptyList(),
                        ),
                    ),
                    leaf("README.md"),
                ),
                flexes = listOf(1.25f, 0.75f),
            ),
            docks = SessionDocks(
                left = SessionDock("project_panel", 240f),
                right = SessionDock("git_panel", 360f),
                lastOpenedRight = true,
                terminal = SessionTerminalDock(open = true, height = 260f),
            ),
            terminals = listOf(SessionTerminal("shell 1", "/data/projects/welcome/src")),
            zoomed = true,
        )

        assertEquals(session, WorkspaceSession.parse(session.toJson()))
    }

    /**
     * A document written before preview tabs existed has no `preview` key.
     * It must read as "not a preview" rather than refusing the whole item:
     * the field was added after the format version was fixed, and Zed's own
     * reader defaults a missing column the same way.
     */
    @Test
    fun anItemWithNoPreviewKeyIsAPermanentTab() {
        val json = """
            {"version":1,"root":"/p","panes":{"kind":"leaf",
             "items":[{"path":"a.rs"}],"activeIndex":0,"active":true}}
        """.trimIndent()
        val leaf = WorkspaceSession.parse(json)?.panes as SessionPane.Leaf
        assertEquals(false, leaf.items.single().preview)
    }

    @Test
    fun aDocumentFromAnotherFormatVersionIsRefused() {
        val json = WorkspaceSession("/p", leaf("a.rs")).toJson()
        assertNull(WorkspaceSession.parse(json.replace("\"version\":1", "\"version\":99")))
    }

    @Test
    fun garbageIsNullRatherThanACrash() {
        assertNull(WorkspaceSession.parse(null))
        assertNull(WorkspaceSession.parse(""))
        assertNull(WorkspaceSession.parse("{ this is not json"))
        assertNull(WorkspaceSession.parse("[]"))
    }

    @Test
    fun aSplitWithOneUsableChildCollapsesIntoIt() {
        // The engine prunes these before they arrive; parsing must not
        // reintroduce a one-member axis, which the pane tree has no shape for.
        val json = """
            {"version":1,"root":"/p","panes":{"kind":"split","axis":"vertical",
             "children":[{"kind":"leaf","items":[{"path":"a.rs"}],"active_index":0,"active":true}],
             "flexes":[1.0]}}
        """.trimIndent()
        val parsed = WorkspaceSession.parse(json)
        assertTrue(parsed?.panes is SessionPane.Leaf)
    }

    @Test
    fun anItemWithNoPathIsDroppedRatherThanOpenedAtNothing() {
        val json = """
            {"version":1,"root":"/p","panes":{"kind":"leaf",
             "items":[{"path":""},{"path":"a.rs"}],"active_index":1,"active":true}}
        """.trimIndent()
        val leaf = WorkspaceSession.parse(json)?.panes as SessionPane.Leaf
        assertEquals(listOf("a.rs"), leaf.items.map { it.path })
    }

    @Test
    fun theRecentListReadsTheEnginesArrayAndToleratesRubbish() {
        val recents = RecentProject.parseList(
            """
            [{"path":"/p/two","name":"two","last_opened":20},
             {"path":"/p/one","name":"one","last_opened":10},
             {"name":"nameless"},
             7]
            """.trimIndent()
        )
        assertEquals(listOf("/p/two", "/p/one"), recents.map { it.path })
        assertEquals(20L, recents.first().lastOpened)
        assertEquals(emptyList<RecentProject>(), RecentProject.parseList("not json"))
    }
}
