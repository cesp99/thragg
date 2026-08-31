package to.eyed.seeker.code.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two pure functions inside [RunTicker], pinned.
 *
 * They are worth a host test out of all proportion to their size, because
 * every live readout in the app now prints through them — the agent status
 * strip, the build strip, the live-run strip and the workflow card each had
 * their own copy before, and "1m 7s" against "1m 07s" is exactly the kind of
 * difference nobody notices in review and everybody notices on a phone, where
 * the second one is the reason the number beside it stops moving.
 */
class RunTickerFormatTest {

    @Test
    fun `elapsed prints at most two units`() {
        assertEquals("0s", elapsedLabel(0))
        assertEquals("12s", elapsedLabel(12_400))
        // Seconds are zero-padded inside a minute and minutes inside an hour:
        // the pad is what makes the label a fixed width, which with tabular
        // figures is what stops the row re-laying out once a second.
        assertEquals("1m 07s", elapsedLabel(67_000))
        assertEquals("1h 04m", elapsedLabel(3_840_000))
    }

    @Test
    fun `elapsed never goes backwards past zero`() {
        // A clock that has not ticked yet, or a startedAt from a host whose
        // clock is a little ahead of ours, must not print "-1s".
        assertEquals("0s", elapsedLabel(-5_000))
    }

    @Test
    fun `tokens carry one decimal and promote at the rounding edge`() {
        assertEquals("412", formatTokens(412))
        assertEquals("3.4k", formatTokens(3_400))
        assertEquals("12.3k", formatTokens(12_345))
        // 999,950 rounds to "1000.0k", which is wider than the label it
        // replaced; it promotes instead.
        assertEquals("1.0M", formatTokens(999_950))
        assertEquals("1.2M", formatTokens(1_234_567))
        assertEquals("0", formatTokens(-1))
    }
}
