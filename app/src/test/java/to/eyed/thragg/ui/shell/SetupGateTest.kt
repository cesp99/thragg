package to.eyed.thragg.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one screen the app can be *trapped* on, and the rule that says when it
 * is a trap on purpose.
 *
 * Setup is the only route that hides the nav bar, and under the gate it also
 * draws no top bar, no Close and no back arrow — because on a fresh install
 * there is nothing behind it worth reaching. Every one of those decisions
 * reads [ShellState.isGated], so what that property answers is the whole
 * safety of the screen, and it was answered by inference: "a Setup route is a
 * gate whenever the toolchain is missing". Two device findings came out of
 * that one line.
 *
 *  - **B-09.** Settings → Toolchain → "Remove the toolchain" writes
 *    `toolchainReady = false` while its own Setup page is on top. The page
 *    became a gate in the same snapshot: bar, Close and remove link all
 *    vanished (they are drawn behind `!gated`), the nav bar was already gone,
 *    and back resolved to [BackStep.LeaveApp]. The only control left was the
 *    primary button, now reading Start, which re-downloads the 1.4 GB just
 *    deleted. Relaunching re-gated.
 *  - **P-02.** The other direction: the gate stopped being a gate the moment
 *    the install finished, so the first screen a new phone ever shows
 *    re-rendered as the toolchain drill page — Done, Check for updates, Close
 *    and **Remove the toolchain**, three of four being exits and the
 *    destructive one sitting one tap under Close.
 *
 * A gate is now a gate because the bootstrap put it up, and it stays one
 * until the user is let through it.
 */
class SetupGateTest {

    @Test
    fun `a fresh shell is not gated`() {
        val state = ShellState()
        assertFalse(state.isGated)
        assertFalse(state.gateActive)
    }

    @Test
    fun `the bootstrap gate is a gate`() {
        val state = ShellState()
        state.gate()
        assertTrue(state.isGated)
        assertEquals(Destination.Code, state.destination)
        assertEquals(1, state.currentStack.depth)
    }

    /** P-02: finishing the install does not hand the user the Remove link. */
    @Test
    fun `the gate survives the install finishing`() {
        val state = ShellState()
        state.gate()
        state.toolchainReady = true
        assertTrue(state.isGated)
    }

    /** Continue — the one way off the gate — is what opens it. */
    @Test
    fun `popping the gate is being let through it`() {
        val state = ShellState()
        state.gate()
        state.toolchainReady = true
        assertTrue(state.pop())
        assertFalse(state.isGated)
        assertFalse(state.gateActive)
    }

    /**
     * B-09 itself: Settings → Toolchain → Remove. The page keeps its bar, its
     * Close and its back arrow, and back pops it.
     */
    @Test
    fun `removing the toolchain from Settings does not trap the app`() {
        val state = ShellState()
        state.toolchainReady = true
        state.push(Route.Settings)
        state.push(Route.Setup)
        state.toolchainReady = false
        assertFalse(state.isGated)
        assertEquals(BackStep.PopRoute, backStep(state.backContext(imeVisible = false)))
        assertTrue(state.pop())
        assertEquals(Route.Settings, state.currentStack.top)
    }

    /** The quieter half of the same trap: Setup opened with no toolchain at all. */
    @Test
    fun `Setup opened by hand with no toolchain is still a drill page`() {
        val state = ShellState()
        state.show(Destination.Build)
        state.push(Route.Setup)
        assertFalse(state.toolchainReady)
        assertFalse(state.isGated)
        assertEquals(BackStep.PopRoute, backStep(state.backContext(imeVisible = false)))
    }

    /**
     * The belt to the braces: the gate is the bottom route of Code's own
     * stack, so anything pushed over it — Licences from a link, say — is not
     * the gate and back pops it normally.
     */
    @Test
    fun `a route pushed over the gate is not the gate`() {
        val state = ShellState()
        state.gate()
        state.push(Route.Licences)
        assertFalse(state.isGated)
        assertEquals(BackStep.PopRoute, backStep(state.backContext(imeVisible = false)))
        state.pop()
        assertTrue(state.isGated)
    }

    /** A rotation puts the gate up again; it must not stack two, or arm twice. */
    @Test
    fun `gating twice is one gate`() {
        val state = ShellState()
        state.gate()
        state.gate()
        assertEquals(1, state.currentStack.depth)
        assertTrue(state.isGated)
    }

    @Test
    fun `closing a project disarms the gate with the routes it lived on`() {
        val state = ShellState()
        state.gate()
        state.reset()
        assertFalse(state.gateActive)
        assertFalse(state.isGated)
    }
}
