package to.eyed.thragg.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.thragg.opensAgentPanel

/**
 * Code is the start destination, and the two rules that stop anything dragging
 * the user off it.
 *
 * This exists because both rules were broken at once and the failure was only
 * visible on hardware: tap the agent's notification, walk back to Code, let
 * Android kill the process — which on a phone that unpacks a 505 MB toolchain
 * is a normal Tuesday — relaunch from the launcher, and the app came back on
 * Agent. docs/UI.md makes Code the start destination for a concrete reason:
 * it is the only destination that degrades honestly with no agent, no network
 * and no toolchain, so it is the only one that is *always* a working screen.
 *
 * Neither rule can be tested where it lives — one is inside `onCreate` over an
 * `android.content.Intent` that cannot be built on the host, the other inside a
 * `LaunchedEffect` — so both are value functions, exactly as
 * [takesBeforeIme] is and for exactly the same reason.
 */
class StartDestinationTest {

    @Test
    fun `a fresh shell is on Code`() {
        assertEquals(Destination.Code, ShellState().destination)
    }

    @Test
    fun `closing a project comes back to Code`() {
        val state = ShellState()
        state.show(Destination.Build)
        state.push(Route.Setup)
        state.reset()
        assertEquals(Destination.Code, state.destination)
        assertTrue(state.currentStack.isEmpty)
    }

    // ---- The notification's extra ------------------------------------------

    @Test
    fun `a notification tap on a fresh start opens Agent`() {
        assertTrue(opensAgentPanel(freshStart = true, extra = true))
    }

    @Test
    fun `an ordinary launch does not`() {
        assertFalse(opensAgentPanel(freshStart = true, extra = false))
    }

    /**
     * The bug itself. The activity's launching intent is sticky and
     * `singleTask` lets `onNewIntent` replace it, so the extra is still on the
     * intent the system re-delivers when it recreates the activity — hours
     * later, and after the user has moved on.
     */
    @Test
    fun `the same extra re-delivered by a recreation does not`() {
        assertFalse(opensAgentPanel(freshStart = false, extra = true))
    }

    // ---- The request counter ------------------------------------------------

    @Test
    fun `nobody has asked`() {
        assertFalse(answersPanelRequest(request = 0, answered = 0))
    }

    @Test
    fun `a first request is answered`() {
        assertTrue(answersPanelRequest(request = 1, answered = 0))
    }

    /**
     * `AgentSessions.openPanelRequest` is process-wide and is never reset, so a
     * new composition — which is what an activity recreation builds — reads a
     * counter that was already acted on. Answering it twice is the second half
     * of the same defect.
     */
    @Test
    fun `a request already answered is not answered again`() {
        assertFalse(answersPanelRequest(request = 3, answered = 3))
    }

    /** Two taps on two notifications are two navigations, as before. */
    @Test
    fun `a later request is a second navigation`() {
        assertTrue(answersPanelRequest(request = 4, answered = 3))
    }
}
