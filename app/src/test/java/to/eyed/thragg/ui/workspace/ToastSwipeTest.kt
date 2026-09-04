package to.eyed.thragg.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The swipe-to-dismiss rule for a toast, checked on the host.
 *
 * The rule is a projection, not a threshold: a release is judged by where the
 * card would coast to, so a fast flick that lets go early still dismisses and
 * a slow drag that stops short still comes home. Each case here is one of the
 * gestures a thumb actually makes.
 */
class ToastSwipeTest {

    private val width = 1000

    @Test
    fun `a slow drag past the middle dismisses`() {
        assertTrue(dismissesToast(offset = 520f, velocity = 0f, width = width))
    }

    @Test
    fun `a slow drag that stops short comes home`() {
        assertFalse(dismissesToast(offset = 300f, velocity = 0f, width = width))
    }

    @Test
    fun `a quick flick dismisses from almost nowhere`() {
        // 40px in, but moving at 1500px/s: the projection carries it well past
        // the middle, which is what a flick means.
        assertTrue(dismissesToast(offset = 40f, velocity = 1500f, width = width))
    }

    @Test
    fun `a flick back toward home cancels a long drag`() {
        // Dragged most of the way out, then thrown back: the velocity's sign
        // decides, not the position.
        assertFalse(dismissesToast(offset = 450f, velocity = -2000f, width = width))
    }

    @Test
    fun `it works in both directions`() {
        assertTrue(dismissesToast(offset = -40f, velocity = -1500f, width = width))
        assertTrue(dismissesToast(offset = -600f, velocity = 0f, width = width))
    }

    @Test
    fun `an unmeasured card never dismisses`() {
        assertFalse(dismissesToast(offset = 900f, velocity = 5000f, width = 0))
    }

    @Test
    fun `projection is the exponential-decay form at UIScrollView's rate`() {
        // (v / 1000) · d / (1 − d) with d = 0.998 is 499·v/1000: a 1000px/s
        // release coasts 499px.
        assertEquals(499f, projectedTravel(1000f), 0.5f)
        assertEquals(0f, projectedTravel(0f), 0f)
    }
}
