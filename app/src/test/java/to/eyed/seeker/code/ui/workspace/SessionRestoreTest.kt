package to.eyed.seeker.code.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.seeker.code.core.RecentProject

/**
 * The two halves of a restore that are pure enough to test on the host: the
 * pane tree being rebuilt from a saved layout, and the recent-projects
 * picker's ranking.
 */
class SessionRestoreTest {

    private fun file(path: String) = OpenFile(path, editor = null)

    private fun leaf(vararg paths: String, active: Boolean = false) =
        PaneLayout.Leaf(paths.toList(), activeIndex = 0, isActive = active)

    @Test
    fun restoreRebuildsTheTreeAndHandsBackItsPanesInTreeOrder() {
        val saved = PaneLayout.Split(
            axis = PaneAxis.Horizontal,
            children = listOf(
                leaf("a1", "a2"),
                PaneLayout.Split(
                    axis = PaneAxis.Vertical,
                    children = listOf(leaf("b1"), leaf("c1", active = true)),
                    flexes = listOf(1.5f, 0.5f),
                ),
            ),
            flexes = listOf(1.2f, 0.8f),
        )

        val panes = PaneGroupState()
        val built = panes.restore(saved)

        assertEquals(3, built.size)
        // Tree order, so the caller can zip its own leaves against them.
        assertEquals(built, panes.panes)
        val axis = panes.root as PaneAxisNode
        assertEquals(PaneAxis.Horizontal, axis.axis)
        assertEquals(listOf(1.2f, 0.8f), axis.flexList)
        val column = axis.memberList[1] as PaneAxisNode
        assertEquals(PaneAxis.Vertical, column.axis)
        assertEquals(listOf(1.5f, 0.5f), column.flexList)
        // The panes come back empty: a tab is a buffer the caller opens.
        assertTrue(built.all { it.files.tabs.isEmpty() })
        // The leaf that said it was active is the active pane.
        assertSame(built[2], panes.active)
    }

    @Test
    fun restoreThrowsAwayWhatWasOpenBeforeIt() {
        val panes = PaneGroupState()
        val first = panes.active
        first.files.open(file("stale.rs"))
        panes.split(first, SplitDirection.Right)

        val built = panes.restore(leaf("fresh.rs", active = true))
        assertEquals(1, built.size)
        assertEquals(1, panes.panes.size)
        assertTrue(panes.allTabs.isEmpty())
        assertSame(built.first(), panes.active)
    }

    @Test
    fun restoreOfASingleLeafReusesThePaneAndLeavesNoOrphan() {
        val panes = PaneGroupState()
        val built = panes.restore(leaf("a.rs"))
        assertEquals(1, built.size)
        assertSame(built.first(), panes.root)
        // With no leaf claiming it, the first in tree order is active.
        assertSame(built.first(), panes.active)
    }

    @Test
    fun aSavedLayoutRoundTripsThroughLayoutAndRestore() {
        val panes = PaneGroupState()
        val a = panes.active
        val b = panes.split(a, SplitDirection.Right)
        panes.split(b, SplitDirection.Down)
        panes.activate(b)
        val saved = panes.layout()

        val rebuilt = PaneGroupState()
        rebuilt.restore(saved)
        // The shape and the active pane's position survive; the paths do not,
        // because the panes come back empty by design.
        assertEquals(shapeOf(saved), shapeOf(rebuilt.layout()))
        assertEquals(activeIndexOf(saved), activeIndexOf(rebuilt.layout()))
    }

    /** The tree without its contents: axes, child counts and flexes. */
    private fun shapeOf(layout: PaneLayout): String = when (layout) {
        is PaneLayout.Leaf -> "leaf"
        is PaneLayout.Split ->
            "${layout.axis}(${layout.children.joinToString(",", transform = ::shapeOf)})"
    }

    private fun activeIndexOf(layout: PaneLayout): Int =
        leavesOf(layout).indexOfFirst { it.isActive }

    private fun leavesOf(layout: PaneLayout): List<PaneLayout.Leaf> = when (layout) {
        is PaneLayout.Leaf -> listOf(layout)
        is PaneLayout.Split -> layout.children.flatMap(::leavesOf)
    }

    // ---- the recent-projects picker ------------------------------------------

    private fun recent(name: String, at: Long, under: String = "/data/projects") =
        RecentProject(path = "$under/$name", name = name, lastOpened = at)

    @Test
    fun anEmptyQueryKeepsTheEnginesNewestFirstOrder() {
        val all = listOf(recent("three", 30), recent("two", 20), recent("one", 10))
        assertEquals(all, rankRecentProjects(all, "  "))
    }

    @Test
    fun aNameMatchOutranksAPathMatch() {
        val named = recent("editor", 1)
        val underneath = recent("notes", 99, under = "/data/editor")
        val ranked = rankRecentProjects(listOf(underneath, named), "editor")
        assertEquals(listOf(named, underneath), ranked)
    }

    @Test
    fun aProjectTheQueryDoesNotMatchAtAllDropsOut() {
        val all = listOf(recent("welcome", 10), recent("scratch", 20))
        assertEquals(listOf("welcome"), rankRecentProjects(all, "wlc").map { it.name })
        assertTrue(rankRecentProjects(all, "zzzz").isEmpty())
    }
}
