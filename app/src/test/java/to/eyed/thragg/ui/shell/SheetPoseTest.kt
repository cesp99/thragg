package to.eyed.thragg.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that turns a released sheet handle into a pose: where it was, how
 * fast it was going, and the two poses it may rest at. Fractions are of the
 * window — 0.65 is the open pose, 1.0 is full — and velocities are windows
 * per second, up positive (the sibling of [NavBarSwipeTest]).
 */
class SheetPoseTest {

    @Test
    fun `a slow release settles to the nearer pose`() {
        // Below the midpoint between 0.65 and 1.0 is 0.65; above it is full.
        assertEquals(0.65f, settlePose(fraction = 0.80f, velocity = 0f), 0f)
        assertEquals(0.65f, settlePose(fraction = 0.50f, velocity = 0f), 0f)
        assertEquals(1f, settlePose(fraction = 0.85f, velocity = 0f), 0f)
        assertEquals(1f, settlePose(fraction = 0.99f, velocity = 0f), 0f)
    }

    @Test
    fun `a fast upward flick from the open pose goes full`() {
        // 1.5 windows/s is ~3600px/s on the phone; projected ~0.3 of a window.
        assertEquals(1f, settlePose(fraction = 0.66f, velocity = 1.5f), 0f)
    }

    @Test
    fun `a fast downward flick from near full comes back to the open pose`() {
        assertEquals(0.65f, settlePose(fraction = 0.90f, velocity = -1.5f), 0f)
    }

    @Test
    fun `a slow drift does not carry across the midpoint`() {
        // 0.1 windows/s projects ~0.02: a hand that stopped stays where it was.
        assertEquals(0.65f, settlePose(fraction = 0.80f, velocity = 0.1f), 0f)
        assertEquals(1f, settlePose(fraction = 0.85f, velocity = -0.1f), 0f)
    }

    @Test
    fun `a sheet that opened full settles home after a small drag`() {
        assertEquals(1f, settlePose(fraction = 0.95f, velocity = -0.2f), 0f)
    }

    @Test
    fun `a fast downward flick from the open pose is a dismissal`() {
        // The finger never reached the line; where it was going is below it.
        assertTrue(releaseDismisses(fraction = 0.65f, velocity = -1.5f))
        assertTrue(releaseDismisses(fraction = 0.60f, velocity = -1.0f))
    }

    @Test
    fun `a slow drift above the line is not a dismissal`() {
        assertFalse(releaseDismisses(fraction = 0.65f, velocity = -0.3f))
        assertFalse(releaseDismisses(fraction = 0.50f, velocity = 0f))
        assertFalse(releaseDismisses(fraction = 1f, velocity = -1.5f))
    }

    @Test
    fun `below the line is a dismissal whatever the throw`() {
        assertTrue(releaseDismisses(fraction = 0.44f, velocity = 0f))
        assertTrue(releaseDismisses(fraction = 0.40f, velocity = 3f))
    }

    @Test
    fun `never below the dismiss line, whatever the throw`() {
        for (fraction in listOf(0.46f, 0.5f, 0.65f, 0.8f, 1f)) {
            for (velocity in listOf(-20f, -5f, -1f, 0f, 1f, 5f, 20f)) {
                val pose = settlePose(fraction, velocity)
                assertTrue("$fraction @ $velocity -> $pose", pose >= DISMISS_FRACTION)
                assertTrue(pose == OPEN_FRACTION || pose == 1f)
            }
        }
    }
}
