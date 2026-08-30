package to.eyed.seeker.code.ui.workspace

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import to.eyed.seeker.code.core.DockSide

/**
 * How much room each dock gets.
 *
 * This arithmetic has been wrong twice — once leaving the editor one character
 * wide between two panels, once giving a dock the whole screen on a foldable
 * that had room to split — so it lives in a function with no Compose in it and
 * the cases are pinned here. The numbers are the app's own: a 240dp tree, a
 * 360dp dock, a 360dp floor under the editor.
 */
class DockPlanTest {

    private val minEditor = 360.dp
    private val minDock = 200.dp

    private fun plan(
        window: Int,
        left: Int? = null,
        right: Int? = null,
        lastOpened: DockSide = DockSide.Left,
        canSplit: Boolean = true,
    ) = planDocks(
        window = window.dp,
        leftWanted = left?.dp,
        rightWanted = right?.dp,
        lastOpened = lastOpened,
        minEditor = minEditor,
        minDock = minDock,
        canSplit = canSplit,
    )

    @Test
    fun nothingOpenDrawsNothing() {
        val plan = plan(window = 1000)
        assertEquals(0.dp, plan.left)
        assertEquals(0.dp, plan.right)
        assertNull(plan.fullScreen)
    }

    /** A tablet: the tree, the editor and a dock all at once. */
    @Test
    fun aWideScreenHoldsBothDocksAndTheEditor() {
        val plan = plan(window = 1200, left = 240, right = 360)
        assertEquals(240.dp, plan.left)
        assertEquals(360.dp, plan.right)
        assertNull(plan.fullScreen)
    }

    /**
     * The Fold's inner screen, 841dp: 841 − 240 − 360 = 241, less than a
     * phone's width of editor. Two docks on opposite sides never close each
     * other — that is the whole point of having two — so they shrink to share
     * the 481dp there is, and the editor keeps its floor.
     */
    @Test
    fun twoDocksThatDoNotFitShrinkRatherThanCloseEachOther() {
        val plan = plan(window = 841, left = 240, right = 360)
        assert(plan.left >= minDock) { "left ${plan.left} fell below its floor" }
        assert(plan.right >= minDock) { "right ${plan.right} fell below its floor" }
        assertEquals(841.dp - minEditor, plan.left + plan.right)
        // Still in proportion: the one that asked for more still has more.
        assert(plan.right > plan.left)
        assertNull(plan.fullScreen)
    }

    /**
     * A screen too small for two floors *and* an editor — 740dp, where two
     * 200dp docks would leave 340 — so now the most recent one wins and the
     * other waits rather than being closed.
     */
    @Test
    fun whenEvenTwoFloorsWillNotFitTheMostRecentWins() {
        val right = plan(window = 740, left = 240, right = 360, lastOpened = DockSide.Right)
        assertEquals(0.dp, right.left)
        assertEquals(360.dp, right.right)

        val left = plan(window = 740, left = 240, right = 360, lastOpened = DockSide.Left)
        assertEquals(240.dp, left.left)
        assertEquals(0.dp, left.right)
    }

    /** A phone: one dock, and it takes the work area. */
    @Test
    fun aDockThatLeavesNoEditorTakesTheWholeArea() {
        val plan = plan(window = 411, right = 360)
        assertEquals(DockSide.Right, plan.fullScreen)
        assertEquals(0.dp, plan.right)
        assertEquals(0.dp, plan.left)
    }

    /** Compact layouts never split, however wide the numbers happen to be. */
    @Test
    fun aCompactScreenAlwaysGivesTheWorkAreaToOneDock() {
        val plan = plan(window = 900, left = 240, canSplit = false)
        assertEquals(DockSide.Left, plan.fullScreen)
    }

    /** A width smaller than the floor is raised to it, not honoured. */
    @Test
    fun aDockIsNeverNarrowerThanItsFloor() {
        val plan = plan(window = 1200, left = 40)
        assertEquals(minDock, plan.left)
    }

    /** `draws` is what the layout asks; it has to agree with both fields. */
    @Test
    fun drawsAgreesWithTheWidths() {
        val split = plan(window = 1200, left = 240, right = 360)
        assert(split.draws(DockSide.Left) && split.draws(DockSide.Right))
        val full = plan(window = 411, right = 360)
        assert(full.draws(DockSide.Right) && !full.draws(DockSide.Left))
    }

    // ---- what the layout state does, as opposed to what it measures -------

    private val settings = to.eyed.seeker.code.core.AppSettings()

    /**
     * Moving *both* panels across at once — one hand-edit of settings.json
     * does it — used to drop whichever moved first, because the second
     * assignment overwrote the first.
     */
    @Test
    fun swappingBothPanelsAtOnceKeepsBoth() {
        val docks = DockLayout()
        docks.open(WorkspacePanel.Project, settings)
        docks.open(WorkspacePanel.Git, settings)
        assertEquals(WorkspacePanel.Project, docks.left)
        assertEquals(WorkspacePanel.Git, docks.right)

        val swapped = settings.copy(
            panels = mapOf(
                "project_panel" to to.eyed.seeker.code.core.PanelPlacement(DockSide.Right, 240f),
                "git_panel" to to.eyed.seeker.code.core.PanelPlacement(DockSide.Left, 360f),
            )
        )
        docks.reconcile(swapped)
        assertEquals(WorkspacePanel.Git, docks.left)
        assertEquals(WorkspacePanel.Project, docks.right)
    }

    /**
     * A dock that is open but not drawn — the loser of a tight screen — is
     * *raised* by its button, not closed. Toggling it was a silent no-op
     * followed by a second press that finally worked.
     */
    @Test
    fun raisingAWaitingDockDoesNotCloseIt() {
        val docks = DockLayout()
        docks.open(WorkspacePanel.Project, settings)
        docks.open(WorkspacePanel.Git, settings)
        assertEquals(DockSide.Right, docks.lastOpened)

        docks.raise(WorkspacePanel.Project, settings)
        assertEquals(DockSide.Left, docks.lastOpened)
        // Still open, both of them.
        assertEquals(WorkspacePanel.Project, docks.left)
        assertEquals(WorkspacePanel.Git, docks.right)
    }

    /** One panel per dock: opening a second on the same side replaces it. */
    @Test
    fun aSecondPanelOnTheSameSideTakesTheDock() {
        val docks = DockLayout()
        docks.open(WorkspacePanel.Git, settings)
        docks.open(WorkspacePanel.Search, settings)
        assertEquals(WorkspacePanel.Search, docks.right)
        // And the other side is untouched by any of it.
        assertNull(docks.left)
    }
}
