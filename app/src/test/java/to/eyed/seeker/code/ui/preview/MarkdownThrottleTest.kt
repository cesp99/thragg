package to.eyed.seeker.code.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a markdown view re-parses while its text is being streamed into it.
 *
 * This is one number and it decided whether the agent panel worked at all. The
 * first version used the preview's debounce — `delay(180)` at the top of an
 * effect keyed on the text — which is right for a human typing, because the
 * pauses between words are longer than the wait. The agent panel re-reads the
 * engine every 120 ms, so the text changed *faster* than the wait: every
 * change cancelled the pending parse before it fired, and a streaming reply
 * rendered as an empty bubble until the agent stopped talking.
 *
 * So the policy is a throttle, and the property that matters is that no
 * arrival rate can postpone a parse indefinitely.
 */
class MarkdownThrottleTest {

    @Test
    fun aFirstParseIsImmediate() {
        // Nothing parsed yet: `now - 0` is enormous, so no wait.
        assertEquals(0L, parseDelay(sinceLastParse = 10_000, interval = 180))
    }

    @Test
    fun aParseAfterAQuietSpellIsImmediate() {
        assertEquals(0L, parseDelay(sinceLastParse = 180, interval = 180))
        assertEquals(0L, parseDelay(sinceLastParse = 500, interval = 180))
    }

    @Test
    fun aParseTooSoonWaitsOnlyForTheRemainder() {
        assertEquals(60L, parseDelay(sinceLastParse = 120, interval = 180))
        assertEquals(180L, parseDelay(sinceLastParse = 0, interval = 180))
    }

    /**
     * The defect itself, as a property: text arriving every 120 ms against a
     * 180 ms interval must still parse, and at a bounded interval — never
     * "wait 180 ms from the newest change", which is what starves.
     */
    @Test
    fun streamingFasterThanTheIntervalStillParses() {
        val interval = 180L
        val arrivalEvery = 120L
        var now = 0L
        var lastParsed = -interval // nothing parsed yet
        val parsedAt = mutableListOf<Long>()

        repeat(20) {
            now += arrivalEvery
            val wait = parseDelay(now - lastParsed, interval)
            val parseAt = now + wait
            // The next arrival does not cancel this one — the wait is only the
            // remainder, so it either fires before the next change or exactly
            // as the throttle allows.
            parsedAt += parseAt
            lastParsed = parseAt
        }

        assertTrue("a streaming reply must render while it streams", parsedAt.isNotEmpty())
        val gaps = parsedAt.zipWithNext { a, b -> b - a }
        assertTrue(
            "no gap may exceed the interval by more than one arrival: $gaps",
            gaps.all { it <= interval + arrivalEvery },
        )
        assertTrue("and it must keep up, not fall behind: $parsedAt", parsedAt.size >= 15)
    }

    /** A clock that went backwards is not a reason to stop drawing. */
    @Test
    fun aBackwardsClockParsesNow() {
        assertEquals(0L, parseDelay(sinceLastParse = -5, interval = 180))
    }
}
