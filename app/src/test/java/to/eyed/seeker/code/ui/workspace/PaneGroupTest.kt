package to.eyed.seeker.code.ui.workspace

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pane tree's rules, pinned as data — Zed's `PaneGroup` and `PaneAxis`
 * (workspace/src/pane_group.rs), whose semantics these restate: a split
 * along an axis inserts a sibling and across it nests, a removal collapses
 * a lone survivor, flexes always sum to the member count, and the direction
 * commands are answered from geometry.
 */
class PaneGroupTest {

    private fun file(path: String) = OpenFile(path, editor = null)

    private fun Pane.open(vararg paths: String) {
        for (path in paths) files.open(file(path))
    }

    private fun PaneGroupState.axis(): PaneAxisNode = root as PaneAxisNode

    private fun PaneGroupState.paths(pane: Pane) = pane.files.tabs.map { it.path }

    // ---- split ---------------------------------------------------------------

    @Test
    fun splitRightOfTheRootMakesARowWithTheNewPaneSecond() {
        val panes = PaneGroupState()
        val first = panes.active
        val fresh = panes.split(first, SplitDirection.Right)
        val axis = panes.axis()
        assertEquals(PaneAxis.Horizontal, axis.axis)
        assertEquals(listOf(first, fresh), axis.memberList)
        assertEquals(listOf(1f, 1f), axis.flexList)
        // The split does not activate by itself; the caller decides.
        assertSame(first, panes.active)
    }

    @Test
    fun splitLeftAndUpPutTheNewPaneFirst() {
        val panes = PaneGroupState()
        val first = panes.active
        val left = panes.split(first, SplitDirection.Left)
        assertEquals(listOf(left, first), panes.axis().memberList)

        val column = PaneGroupState()
        val top = column.split(column.active, SplitDirection.Up)
        assertEquals(PaneAxis.Vertical, column.axis().axis)
        assertSame(top, column.axis().memberList.first())
    }

    @Test
    fun splittingAlongTheAxisInsertsASiblingAndResetsFlexes() {
        val panes = PaneGroupState()
        val a = panes.active
        val b = panes.split(a, SplitDirection.Right)
        // Unequal flexes, to prove the insert resets them (pane_group.rs:717-720).
        panes.resize(panes.axis(), 0, 100f, 1000f)
        assertTrue(panes.axis().flexList[0] > 1f)

        val c = panes.split(a, SplitDirection.Right)
        assertEquals(listOf(a, c, b), panes.axis().memberList)
        assertEquals(listOf(1f, 1f, 1f), panes.axis().flexList)
        assertEquals(3, panes.panes.size)
    }

    @Test
    fun splittingAcrossTheAxisNestsAnAxisInPlace() {
        val panes = PaneGroupState()
        val a = panes.active
        val b = panes.split(a, SplitDirection.Right)
        val below = panes.split(b, SplitDirection.Down)
        val row = panes.axis()
        assertSame(a, row.memberList[0])
        val nested = row.memberList[1] as PaneAxisNode
        assertEquals(PaneAxis.Vertical, nested.axis)
        assertEquals(listOf(b, below), nested.memberList)
        // Tree order is left to right, top to bottom.
        assertEquals(listOf(a, b, below), panes.panes)
    }

    // ---- remove --------------------------------------------------------------

    @Test
    fun removingTheLastPaneIsRefused() {
        val panes = PaneGroupState()
        assertFalse(panes.remove(panes.active))
        assertEquals(1, panes.panes.size)
    }

    @Test
    fun removingCollapsesAnAxisLeftWithOneMember() {
        val panes = PaneGroupState()
        val a = panes.active
        val b = panes.split(a, SplitDirection.Right)
        val c = panes.split(b, SplitDirection.Down)
        assertTrue(panes.remove(c))
        // The nested column collapsed into b; the row still stands.
        assertEquals(listOf(a, b), panes.axis().memberList)
        assertTrue(panes.remove(b))
        assertSame(a, panes.root)
    }

    @Test
    fun removingTheActivePaneActivatesTheMostRecentlyMadeSurvivor() {
        val panes = PaneGroupState()
        val a = panes.active
        val b = panes.split(a, SplitDirection.Right)
        val c = panes.split(a, SplitDirection.Down)
        panes.activate(b)
        assertTrue(panes.remove(b))
        assertSame(c, panes.active)
    }

    @Test
    fun closingAPanesLastTabRemovesThePaneUnlessItIsTheLast() {
        val panes = PaneGroupState()
        val a = panes.active
        val b = panes.split(a, SplitDirection.Right)
        b.open("x")
        b.files.close(0)
        assertEquals(listOf(a), panes.panes)
        // The last pane stays, empty.
        a.open("y")
        a.files.close(0)
        assertEquals(listOf(a), panes.panes)
        assertTrue(a.files.tabs.isEmpty())
    }

    // ---- swap ----------------------------------------------------------------

    @Test
    fun swapExchangesTwoPanesPlacesAndKeepsTheFlexesWithTheSlots() {
        val panes = PaneGroupState()
        val a = panes.active
        val b = panes.split(a, SplitDirection.Right)
        panes.resize(panes.axis(), 0, 200f, 1000f)
        val flexes = panes.axis().flexList.toList()
        panes.swap(a, b)
        assertEquals(listOf(b, a), panes.axis().memberList)
        assertEquals(flexes, panes.axis().flexList)
    }

    // ---- flexes ----------------------------------------------------------------

    @Test
    fun flexesAlwaysSumToTheMemberCount() {
        val panes = PaneGroupState()
        val a = panes.active
        panes.split(a, SplitDirection.Right)
        panes.split(a, SplitDirection.Right)
        val axis = panes.axis()
        panes.resize(axis, 0, 150f, 900f)
        panes.resize(axis, 1, -60f, 900f)
        assertEquals(3f, axis.flexList.sum(), 0.0001f)
        // The dragged handle moved the two members it sits between only.
        assertEquals(1f + 150f / 900f * 3f, axis.flexList[0], 0.0001f)
    }

    @Test
    fun resizeNeverTakesAPaneUnderTheMinimum() {
        val panes = PaneGroupState()
        val a = panes.active
        panes.split(a, SplitDirection.Right)
        val axis = panes.axis()
        // 1000px across two panes: dragging 900px right can only take the
        // neighbour down to HORIZONTAL_MIN_SIZE.
        panes.resize(axis, 0, 900f, 1000f)
        val neighbourPx = 1000f * axis.flexList[1] / 2f
        assertEquals(PANE_MIN_WIDTH_PX, neighbourPx, 0.001f)
        assertEquals(2f, axis.flexList.sum(), 0.0001f)
    }

    @Test
    fun loadedFlexesAreKeptOnlyWhenTheyFitTheMembers() {
        val a = Pane(1)
        val b = Pane(2)
        val kept = PaneAxisNode(PaneAxis.Horizontal, listOf(a, b), flexes = listOf(0.5f, 1.5f))
        assertEquals(listOf(0.5f, 1.5f), kept.flexList)
        val wrongCount = PaneAxisNode(PaneAxis.Horizontal, listOf(a, b), flexes = listOf(1f, 1f, 1f))
        assertEquals(listOf(1f, 1f), wrongCount.flexList)
        val wrongSum = PaneAxisNode(PaneAxis.Horizontal, listOf(a, b), flexes = listOf(1f, 2f))
        assertEquals(listOf(1f, 1f), wrongSum.flexList)
    }

    // ---- directions, from geometry -------------------------------------------

    private fun sideBySide(): Triple<PaneGroupState, Pane, Pane> {
        val panes = PaneGroupState()
        val a = panes.active
        val b = panes.split(a, SplitDirection.Right)
        // Two columns with a 6px handle between, as the renderer reports them.
        panes.recordBounds(a, Rect(0f, 0f, 500f, 400f), tabBarBottom = 32f)
        panes.recordBounds(b, Rect(506f, 0f, 1000f, 400f), tabBarBottom = 32f)
        return Triple(panes, a, b)
    }

    @Test
    fun findPaneInDirectionProbesJustPastTheEdge() {
        val (panes, a, b) = sideBySide()
        assertSame(b, panes.findPaneInDirection(a, SplitDirection.Right))
        assertSame(a, panes.findPaneInDirection(b, SplitDirection.Left))
        assertNull(panes.findPaneInDirection(a, SplitDirection.Left))
        assertNull(panes.findPaneInDirection(a, SplitDirection.Up))
        assertNull(panes.findPaneInDirection(b, SplitDirection.Down))
    }

    @Test
    fun activateInDirectionMovesTheActivePaneOrRefuses() {
        val (panes, a, b) = sideBySide()
        assertTrue(panes.activateInDirection(SplitDirection.Right))
        assertSame(b, panes.active)
        assertFalse(panes.activateInDirection(SplitDirection.Right))
        assertSame(b, panes.active)
        assertTrue(panes.activateInDirection(SplitDirection.Left))
        assertSame(a, panes.active)
    }

    @Test
    fun swapInDirectionSwapsWithTheNeighbourThatWay() {
        val (panes, a, b) = sideBySide()
        assertTrue(panes.swapInDirection(SplitDirection.Right))
        assertEquals(listOf(b, a), panes.axis().memberList)
        assertFalse(panes.swapInDirection(SplitDirection.Up))
    }

    @Test
    fun nextAndPreviousWrapInTreeOrder() {
        val panes = PaneGroupState()
        val a = panes.active
        val b = panes.split(a, SplitDirection.Right)
        val c = panes.split(b, SplitDirection.Down)
        assertTrue(panes.activateNext())
        assertSame(b, panes.active)
        assertTrue(panes.activateNext())
        assertSame(c, panes.active)
        assertTrue(panes.activateNext())
        assertSame(a, panes.active)
        assertTrue(panes.activatePrevious())
        assertSame(c, panes.active)
        assertTrue(panes.activateAt(1))
        assertSame(b, panes.active)
        assertFalse(panes.activateAt(3))
    }

    @Test
    fun aLonePaneHasNoNextPane() {
        val panes = PaneGroupState()
        assertFalse(panes.activateNext())
        assertFalse(panes.activatePrevious())
    }

    // ---- moving tabs -------------------------------------------------------------

    @Test
    fun joinIntoNextEmptiesTheActivePaneIntoItsNeighbourAndRemovesIt() {
        val (panes, a, b) = sideBySide()
        a.open("one", "two")
        b.open("three")
        assertTrue(panes.joinIntoNext())
        assertEquals(listOf(b), panes.panes)
        assertEquals(listOf("three", "one", "two"), panes.paths(b))
        assertSame(b, panes.active)
    }

    @Test
    fun joinAllGathersEveryTabIntoTheActivePaneAndKeepsItsActiveTab() {
        val panes = PaneGroupState()
        val a = panes.active
        val b = panes.split(a, SplitDirection.Right)
        val c = panes.split(b, SplitDirection.Down)
        a.open("a1", "a2")
        a.files.select(0)
        b.open("b1")
        c.open("c1")
        assertTrue(panes.joinAll())
        assertEquals(listOf(a), panes.panes)
        assertEquals(listOf("a1", "a2", "b1", "c1"), panes.paths(a))
        assertEquals("a1", a.files.active?.path)
    }

    @Test
    fun joinAllWithOnePaneIsNothing() {
        val panes = PaneGroupState()
        assertFalse(panes.joinAll())
    }

    @Test
    fun movingATabToAnotherPaneKeepsTheObjectAndDedupesByPath() {
        val (panes, a, b) = sideBySide()
        a.open("shared", "other")
        b.open("shared")
        val moved = a.files.tabs[1]
        panes.moveItem(moved, a, b, direction = null)
        assertEquals(listOf("shared"), panes.paths(a))
        assertEquals(listOf("shared", "other"), panes.paths(b))
        assertSame(moved, b.files.tabs[1])
        assertSame(b, panes.active)
        // The twin of a path already there is dropped and the existing one selected.
        panes.moveItem(a.files.tabs[0], a, b, direction = null)
        assertEquals(listOf("shared", "other"), panes.paths(b))
        assertEquals("shared", b.files.active?.path)
        // Its pane emptied and went.
        assertEquals(listOf(b), panes.panes)
    }

    @Test
    fun movingATabWithADirectionSplitsTheTargetFirst() {
        val (panes, a, b) = sideBySide()
        a.open("one", "two")
        panes.moveItem(a.files.tabs[0], a, b, SplitDirection.Down)
        val column = panes.axis().memberList[1] as PaneAxisNode
        val fresh = column.memberList[1] as Pane
        assertEquals(listOf("one"), panes.paths(fresh))
        assertEquals(listOf("two"), panes.paths(a))
        assertSame(fresh, panes.active)
    }

    // ---- drops ----------------------------------------------------------------------

    @Test
    fun dropTargetIsTheEdgeBandOrTheMiddleOrTheTabBar() {
        val (panes, a, b) = sideBySide()
        a.open("one", "two")
        val tab = a.files.tabs[0]
        // b is 494 x 400; the band is 20% of the shorter side = 80px.
        panes.startDrag(tab, a, Offset(750f, 200f))
        assertEquals(DropTarget(b.id, null), panes.dropTarget)
        panes.updateDrag(Offset(960f, 200f))
        assertEquals(DropTarget(b.id, SplitDirection.Right), panes.dropTarget)
        panes.updateDrag(Offset(750f, 390f))
        assertEquals(DropTarget(b.id, SplitDirection.Down), panes.dropTarget)
        panes.updateDrag(Offset(520f, 200f))
        assertEquals(DropTarget(b.id, SplitDirection.Left), panes.dropTarget)
        // Over the tab bar it is always a join, even in a corner.
        panes.updateDrag(Offset(990f, 10f))
        assertEquals(DropTarget(b.id, null), panes.dropTarget)
        // Off every pane: nowhere.
        panes.updateDrag(Offset(503f, 200f))
        assertNull(panes.dropTarget)
        panes.cancelDrag()
        assertNull(panes.drag)
    }

    @Test
    fun aPanesOnlyTabCannotSplitItsOwnPane() {
        val (panes, a, _) = sideBySide()
        a.open("one")
        panes.startDrag(a.files.tabs[0], a, Offset(490f, 200f))
        assertEquals(DropTarget(a.id, null), panes.dropTarget)
        // Dropped on itself: nothing moves, nothing splits.
        panes.finishDrag()
        assertEquals(2, panes.panes.size)
        assertEquals(listOf("one"), panes.paths(a))
    }

    @Test
    fun finishingADragOnAnEdgePerformsTheSplit() {
        val (panes, a, b) = sideBySide()
        a.open("one", "two")
        panes.startDrag(a.files.tabs[1], a, Offset(960f, 200f))
        val target = panes.finishDrag()
        assertEquals(DropTarget(b.id, SplitDirection.Right), target)
        assertEquals(3, panes.panes.size)
        assertEquals(listOf("two"), panes.paths(panes.panes.last()))
        assertNull(panes.drag)
    }

    // ---- zoom -----------------------------------------------------------------------

    @Test
    fun zoomNeedsATabAndFollowsActivation() {
        val panes = PaneGroupState()
        val a = panes.active
        val b = panes.split(a, SplitDirection.Right)
        assertFalse(panes.toggleZoom())
        a.open("one")
        assertTrue(panes.toggleZoom())
        assertEquals(a.id, panes.zoomedPaneId)
        panes.activate(b)
        assertNull(panes.zoomedPaneId)
        panes.activate(a)
        assertTrue(panes.toggleZoom())
        assertTrue(panes.toggleZoom())
        assertFalse(panes.isZoomed)
    }

    // ---- the layout as data ----------------------------------------------------

    @Test
    fun layoutDescribesTheTreeWithPathsActiveTabsAndFlexes() {
        val panes = PaneGroupState()
        val a = panes.active
        val b = panes.split(a, SplitDirection.Right)
        val c = panes.split(b, SplitDirection.Down)
        a.open("a1", "a2")
        a.files.select(0)
        c.open("c1")
        panes.resize(panes.axis(), 0, 100f, 1000f)
        panes.activate(c)
        val layout = panes.layout() as PaneLayout.Split
        assertEquals(PaneAxis.Horizontal, layout.axis)
        assertEquals(panes.axis().flexList.toList(), layout.flexes)
        assertEquals(PaneLayout.Leaf(listOf("a1", "a2"), activeIndex = 0, isActive = false), layout.children[0])
        val column = layout.children[1] as PaneLayout.Split
        assertEquals(PaneAxis.Vertical, column.axis)
        assertEquals(PaneLayout.Leaf(emptyList(), activeIndex = -1, isActive = false), column.children[0])
        assertEquals(PaneLayout.Leaf(listOf("c1"), activeIndex = 0, isActive = true), column.children[1])
    }

    @Test
    fun resetGoesBackToOneEmptyPane() {
        val panes = PaneGroupState()
        val a = panes.active
        val b = panes.split(a, SplitDirection.Right)
        a.open("one")
        b.open("two")
        panes.reset()
        assertEquals(1, panes.panes.size)
        assertTrue(panes.root is Pane)
        assertTrue(panes.active.files.tabs.isEmpty())
        assertFalse(panes.active.files.hasClosedTabs)
    }
}
