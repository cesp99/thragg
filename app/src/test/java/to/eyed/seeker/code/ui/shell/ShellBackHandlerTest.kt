package to.eyed.seeker.code.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The eight-step back order, asserted step by step.
 *
 * Every case below turns on **one** fact while leaving every lower-priority
 * fact true as well: the point of the order is what happens when several
 * things could take the press, and a test that only ever set one flag would
 * pass against a `when` in any order at all. The steps are docs/UI.md,
 * "Navigation" — BACK GESTURE, and the numbering in the names is that list's.
 */
class ShellBackHandlerTest {

    /** Everything a back press could possibly take, all true at once. */
    private val everything = BackContext(
        overlayShowing = true,
        sheetOpen = true,
        imeVisible = true,
        findBarOpen = true,
        routePushed = true,
        jumpAvailable = true,
        destination = Destination.Build,
    )

    @Test
    fun `step 1 - an editor overlay beats everything else`() {
        assertEquals(BackStep.DismissOverlay, backStep(everything))
    }

    @Test
    fun `step 2 - a sheet beats the IME it opened`() {
        assertEquals(
            BackStep.CloseSheet,
            backStep(everything.copy(overlayShowing = false)),
        )
    }

    @Test
    fun `step 3 - the IME beats the find bar that raised it`() {
        assertEquals(
            BackStep.HideIme,
            backStep(everything.copy(overlayShowing = false, sheetOpen = false)),
        )
    }

    @Test
    fun `step 4 - the find bar beats the route it is deployed over`() {
        assertEquals(
            BackStep.CloseFindBar,
            backStep(
                everything.copy(overlayShowing = false, sheetOpen = false, imeVisible = false)
            ),
        )
    }

    @Test
    fun `step 5 - a pushed route beats the jump stack under it`() {
        assertEquals(
            BackStep.PopRoute,
            backStep(
                everything.copy(
                    overlayShowing = false,
                    sheetOpen = false,
                    imeVisible = false,
                    findBarOpen = false,
                    destination = Destination.Code,
                )
            ),
        )
    }

    @Test
    fun `step 6 - the jump stack, in Code, beats going to Code`() {
        assertEquals(
            BackStep.PopJump,
            backStep(
                BackContext(
                    jumpAvailable = true,
                    destination = Destination.Code,
                )
            ),
        )
    }

    @Test
    fun `step 7 - anywhere but Code goes to Code`() {
        assertEquals(BackStep.GoToCode, backStep(BackContext(destination = Destination.Agent)))
        assertEquals(BackStep.GoToCode, backStep(BackContext(destination = Destination.Build)))
    }

    @Test
    fun `step 8 - Code at the root leaves the app`() {
        assertEquals(BackStep.LeaveApp, backStep(BackContext()))
    }

    /**
     * The jump stack is Code's alone. Elsewhere a non-empty one — Code's,
     * left over from earlier — must not swallow the press that was meant to
     * bring the user home.
     */
    @Test
    fun `the jump stack is not consulted outside Code`() {
        assertEquals(
            BackStep.GoToCode,
            backStep(BackContext(jumpAvailable = true, destination = Destination.Build)),
        )
    }

    /** Back from Shell goes to Code, not to Build: Shell is a mode of Build. */
    @Test
    fun `back from the Build destination goes to Code, whatever mode it is in`() {
        assertEquals(BackStep.GoToCode, backStep(BackContext(destination = Destination.Build)))
    }

    // ---- The same order, run against the real state --------------------------

    @Test
    fun `dispatch pops the current destination's route stack`() {
        val state = ShellState()
        state.show(Destination.Agent)
        state.push(Route.Diff("src/state.rs"))
        assertEquals(BackStep.PopRoute, state.handleBack(imeVisible = false) {})
        assertTrue(state.currentStack.isEmpty)
        assertEquals(Destination.Agent, state.destination)
    }

    @Test
    fun `dispatch goes to Code from a destination with nothing pushed`() {
        val state = ShellState()
        state.show(Destination.Build)
        assertEquals(BackStep.GoToCode, state.handleBack(imeVisible = false) {})
        assertEquals(Destination.Code, state.destination)
    }

    @Test
    fun `dispatch at the root does nothing and reports LeaveApp`() {
        val state = ShellState()
        assertEquals(BackStep.LeaveApp, state.handleBack(imeVisible = false) {})
        assertEquals(Destination.Code, state.destination)
    }

    @Test
    fun `dispatch hides the IME without touching the route stack`() {
        val state = ShellState()
        state.push(Route.Problems)
        var hidden = false
        assertEquals(BackStep.HideIme, state.handleBack(imeVisible = true) { hidden = true })
        assertTrue(hidden)
        assertEquals(Route.Problems, state.currentStack.top)
    }

    @Test
    fun `dispatch closes the topmost sheet and nothing else`() {
        val state = ShellState()
        state.push(Route.Changes)
        var closedFirst = false
        var closedSecond = false
        state.sheetOpened(SheetHandle { closedFirst = true })
        state.sheetOpened(SheetHandle { closedSecond = true })
        assertEquals(BackStep.CloseSheet, state.handleBack(imeVisible = false) {})
        assertFalse(closedFirst)
        assertTrue(closedSecond)
        assertEquals(Route.Changes, state.currentStack.top)
    }

    @Test
    fun `a seam that says it has nothing is not asked to consume`() {
        val state = ShellState()
        var consumed = false
        state.overlaySeam = BackSeam(isActive = { false }, consume = { consumed = true })
        state.findBarSeam = BackSeam(isActive = { true }, consume = {})
        assertEquals(BackStep.CloseFindBar, state.handleBack(imeVisible = false) {})
        assertFalse(consumed)
    }

    @Test
    fun `the overlay seam is consumed before the find bar under it`() {
        val state = ShellState()
        var overlay = 0
        var findBar = 0
        state.overlaySeam = BackSeam(isActive = { true }, consume = { overlay++ })
        state.findBarSeam = BackSeam(isActive = { true }, consume = { findBar++ })
        assertEquals(BackStep.DismissOverlay, state.handleBack(imeVisible = false) {})
        assertEquals(1, overlay)
        assertEquals(0, findBar)
    }

    @Test
    fun `the jump seam is consumed in Code and skipped elsewhere`() {
        val state = ShellState()
        var jumps = 0
        state.jumpSeam = BackSeam(isActive = { true }, consume = { jumps++ })
        assertEquals(BackStep.PopJump, state.handleBack(imeVisible = false) {})
        assertEquals(1, jumps)
        state.show(Destination.Agent)
        assertEquals(BackStep.GoToCode, state.handleBack(imeVisible = false) {})
        assertEquals(1, jumps)
    }

    /** Re-selecting the destination you are on is a scroll-to-top, not a switch. */
    @Test
    fun `re-tapping the current destination counts rather than navigating`() {
        val state = ShellState()
        state.push(Route.Problems)
        state.show(Destination.Code)
        assertEquals(1, state.retapCount)
        assertEquals(Route.Problems, state.currentStack.top)
    }

    /** Arriving at Agent answers its badge, however you arrived. */
    @Test
    fun `showing Agent clears its badge`() {
        val state = ShellState()
        state.agentAttention = true
        state.show(Destination.Agent)
        assertFalse(state.agentAttention)
    }

    // ---- The hardware-key pass ----------------------------------------------
    //
    // Here rather than in a file of its own because the shell's host tests are
    // these two, and the pass has exactly one decision worth pinning.

    @Test
    fun `only a modifier chord going down is taken before the keyboard`() {
        assertTrue(takesBeforeIme(isKeyDown = true, ctrl = true, alt = false))
        assertTrue(takesBeforeIme(isKeyDown = true, ctrl = false, alt = true))
        // Bare and shifted keys are the IME's to see first — it forwards them.
        assertFalse(takesBeforeIme(isKeyDown = true, ctrl = false, alt = false))
        // Only the down: a chord's key-up must not be claimed twice.
        assertFalse(takesBeforeIme(isKeyDown = false, ctrl = true, alt = true))
    }

    /** The ten-second tick, as a property rather than a delay. */
    @Test
    fun `the build badge shows a tick for ten seconds and then nothing`() {
        val at = 1_000_000L
        assertEquals(BuildBadge.Tick, buildBadge(BuildState.Succeeded(at), at))
        assertEquals(
            BuildBadge.Tick,
            buildBadge(BuildState.Succeeded(at), at + BuildState.SUCCESS_TICK_MS - 1),
        )
        assertEquals(
            BuildBadge.None,
            buildBadge(BuildState.Succeeded(at), at + BuildState.SUCCESS_TICK_MS),
        )
        assertEquals(BuildBadge.Ring, buildBadge(BuildState.Running("Building", at), at + 500))
        assertEquals(BuildBadge.Failed, buildBadge(BuildState.Failed(1, 0), at))
        assertEquals(BuildBadge.None, buildBadge(BuildState.Idle, at))
    }
}
