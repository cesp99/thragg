package to.eyed.thragg.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that turns a released pill into a tab: where it was, how fast it
 * was going, and the ends of the capsule. Positions are in slots — 0 is
 * Code, 2 is Build — and velocities in slots per second.
 */
class NavBarSwipeTest {

    private fun settle(position: Float, velocity: Float) = settleSlot(position, velocity, last = 2)

    @Test
    fun `a pill placed past halfway lands on the next tab`() {
        assertEquals(1, settle(position = 0.6f, velocity = 0f))
        assertEquals(0, settle(position = 0.4f, velocity = 0f))
    }

    @Test
    fun `a flick that let go early is thrown to the next tab`() {
        // 2 slots/s is a 600px/s flick on the phone; projected ~0.4 slot.
        assertEquals(1, settle(position = 0.15f, velocity = 2f))
        assertEquals(1, settle(position = 1.85f, velocity = -2f))
    }

    @Test
    fun `a slow steady drag lands where it stopped`() {
        // 0.8 slots/s is an adb swipe's uniform speed; it must not carry.
        assertEquals(1, settle(position = 0.76f, velocity = -0.8f))
    }

    @Test
    fun `a tap that wandered goes nowhere`() {
        assertEquals(0, settle(position = 0.05f, velocity = 0.1f))
        assertEquals(1, settle(position = 0.95f, velocity = -0.1f))
    }

    @Test
    fun `no flick jumps two tabs, but a drag that went there lands there`() {
        assertEquals(1, settle(position = 0f, velocity = 40f))
        assertEquals(2, settle(position = 1.7f, velocity = 0f))
        assertEquals(2, settle(position = 1.2f, velocity = 2f))
    }

    @Test
    fun `the ends hold`() {
        assertEquals(0, settle(position = -0.3f, velocity = -5f))
        assertEquals(2, settle(position = 2.3f, velocity = 5f))
    }

    @Test
    fun `rubber band gives less the further out and is identity inside`() {
        assertEquals(1.3f, rubberBand(1.3f, last = 2), 0f)
        val near = -rubberBand(-0.5f, last = 2)
        val far = -rubberBand(-2f, last = 2)
        assertTrue(near in 0.01f..0.5f)
        assertTrue(far > near && far < 1f)
        assertTrue(rubberBand(3f, last = 2) in 2f..3f)
    }
}
