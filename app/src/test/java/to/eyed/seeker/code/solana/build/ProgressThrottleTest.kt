package to.eyed.seeker.code.solana.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The valve between a `\r`-redrawn download counter and the Build log
 * (BUG 3 of the 2026-08 rehearsal: 27 minutes of progress that never reached
 * the log). Time is a parameter, so the 500 ms window is asserted rather than
 * slept through.
 */
class ProgressThrottleTest {

    @Test
    fun `the first redraw shows immediately, the flood does not`() {
        val throttle = ProgressThrottle()
        // A downloader redrawing many times a second: the first one is the
        // "something is happening" signal and must not wait half a second.
        assertEquals("1.0 MB", throttle.progress("1.0 MB", nowMs = 1_000))
        assertNull(throttle.progress("1.1 MB", nowMs = 1_050))
        assertNull(throttle.progress("1.2 MB", nowMs = 1_100))
        // The interval elapses: the *current* redraw goes out, not the queue
        // of stale ones — there is no queue, a redraw supersedes its past.
        assertEquals("5.0 MB", throttle.progress("5.0 MB", nowMs = 1_500))
        assertNull(throttle.progress("5.1 MB", nowMs = 1_600))
    }

    @Test
    fun `drain hands over the redraw the window was holding, exactly once`() {
        val throttle = ProgressThrottle()
        assertEquals("10 %", throttle.progress("10 %", nowMs = 1_000))
        assertNull(throttle.progress("11 %", nowMs = 1_100))
        assertNull(throttle.progress("12 %", nowMs = 1_200))
        // A real \n line arrives, or the stream ends: the last state seen
        // must land (in order, before that line), and only once.
        assertEquals("12 %", throttle.drain())
        assertNull(throttle.drain())
    }

    @Test
    fun `an emitted redraw is not also drained later`() {
        val throttle = ProgressThrottle()
        assertEquals("done", throttle.progress("done", nowMs = 5_000))
        // Emitting cleared any held line; the end-of-stream drain finds
        // nothing and the log does not say "done" twice.
        assertNull(throttle.drain())
    }
}
