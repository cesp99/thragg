package to.eyed.seeker.code.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that turns a drag across the bottom bar into a destination change.
 *
 * `+1` is toward Build: the finger travelled LEFT, so the next tab arrives
 * from the right. A tap that wanders is `0`, and so is a drag that is neither
 * far nor fast.
 */
class NavBarSwipeTest {

    private fun direction(travel: Float, velocity: Float) =
        swipeDirection(travel, velocity, minDistance = 144f, minVelocity = 600f)

    @Test
    fun `a long slow drag left goes to the next tab`() {
        assertEquals(1, direction(travel = -200f, velocity = 0f))
    }

    @Test
    fun `a short fast flick right goes to the previous tab`() {
        assertEquals(-1, direction(travel = 30f, velocity = 900f))
    }

    @Test
    fun `a tap that wandered a few pixels goes nowhere`() {
        assertEquals(0, direction(travel = 12f, velocity = 40f))
        assertEquals(0, direction(travel = -12f, velocity = -40f))
    }

    @Test
    fun `just under both thresholds goes nowhere`() {
        assertEquals(0, direction(travel = -143f, velocity = -599f))
    }

    @Test
    fun `on the threshold counts`() {
        assertEquals(1, direction(travel = -144f, velocity = 0f))
        assertEquals(-1, direction(travel = 0f, velocity = 600f))
    }
}
