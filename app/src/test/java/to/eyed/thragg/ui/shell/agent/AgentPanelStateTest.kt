package to.eyed.thragg.ui.shell.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A dismissed permission sheet stays dismissed — G-26.
 *
 * Dismissing a parked request is not answering it: the turn stays blocked and
 * the request stays in the agent's list, which is why the destination raises
 * its sheet by itself in the first place. The record of "the user has put this
 * one away" therefore has to outlive the screen, and it did not: `sheet` and
 * `dismissed` were plain `remember`s inside `AgentScreen`, which the shell's
 * `AnimatedContent` removes from the composition on a tab switch and which a
 * rotation drops with the whole activity. Every return to the Agent tab found
 * an empty map and shoved the same modal straight back up — for ever, since
 * dismissing never answers.
 *
 * These are assertions about the retained holder, which is where the fix put
 * both values. What cannot be asserted here is the composition: that
 * `AgentScreen` reads *this* holder rather than a `remember` is a one-line
 * read at AgentScreen.kt, and the behaviour is the device check in
 * /tmp/qa/fix-batch3.md.
 */
class AgentPanelStateTest {

    @Test
    fun `there is one panel, process-wide`() {
        assertSame(AgentPanelState.current, AgentPanelState.current)
    }

    @Test
    fun `a dismissal survives the panel leaving the composition`() {
        val panel = AgentPanelState()
        panel.follow(7L)
        panel.dismissed["call-1"] = true
        // Leaving the tab and coming back: same session, same holder.
        panel.follow(7L)
        assertTrue(panel.dismissed["call-1"] == true)
    }

    @Test
    fun `the sheet that is open survives it too`() {
        val panel = AgentPanelState()
        panel.follow(7L)
        panel.sheet = AgentSheet.Approval("call-1")
        panel.follow(7L)
        assertTrue(panel.sheet is AgentSheet.Approval)
    }

    /**
     * A different thread has never answered anything: its questions carry
     * their own keys and none of them has been put away.
     */
    @Test
    fun `a thread switch forgets the previous thread's answers`() {
        val panel = AgentPanelState()
        panel.follow(7L)
        panel.dismissed["call-1"] = true
        panel.follow(8L)
        assertFalse(panel.dismissed["call-1"] == true)
    }

    @Test
    fun `no session yet is not a session change`() {
        val panel = AgentPanelState()
        panel.follow(null)
        panel.dismissed["form-1"] = true
        panel.follow(null)
        assertTrue(panel.dismissed["form-1"] == true)
    }

    @Test
    fun `a fresh panel has nothing up`() {
        val panel = AgentPanelState()
        assertNull(panel.sheet)
        assertTrue(panel.dismissed.isEmpty())
    }
}
