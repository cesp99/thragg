package to.eyed.seeker.code.ui.shell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The caption under the box, which is where [sendLabel]'s distinction went.
 *
 * The send control used to be the *word* Send/Steer/Queue; it is a 40dp filled
 * circle now (docs/VISUAL.md, "Agent — the composer, three states"), and a
 * circle cannot say which of the three it is about to do. [busyNote] is the
 * replacement, so these three assertions are the ones that keep a steer from
 * looking like an ordinary send — the same property AgentScreenTest pins for
 * [sendMode] itself.
 */
class ComposerNoteTest {

    /**
     * Nothing running, nothing said. The resting composer must not carry a
     * line of explanation: it would be on screen for the whole life of the app
     * and it would be true of nothing.
     */
    @Test
    fun `an idle composer says nothing under the box`() {
        assertNull(busyNote(SendMode.Send))
        assertNull(busyNote(sendMode(busy = false, steerable = true)))
    }

    /**
     * Steering is the claim that needs making: the transcript does not change
     * when a steer lands, so without the sentence the screen looks like it
     * dropped the message. The wording must stay an *offer* ("Send to…") —
     * this caption shows for the whole turn, empty box included, and a
     * past-tense "Steering —" read as the app steering something nobody sent
     * (reported on-device, 2026-09-01).
     */
    @Test
    fun `steering is offered, not claimed`() {
        val note = busyNote(sendMode(busy = true, steerable = true))
        assertEquals("Send to steer — the agent takes it at its next step.", note)
    }

    /** Queue is a wait, and a wait that is not announced reads as a hang. */
    @Test
    fun `queueing says when it goes`() {
        val note = busyNote(sendMode(busy = true, steerable = false))
        assertEquals("Send to queue — it goes when the turn settles.", note)
    }
}
