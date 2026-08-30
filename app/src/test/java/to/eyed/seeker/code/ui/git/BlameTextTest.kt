package to.eyed.seeker.code.ui.git

import org.junit.Assert.assertEquals
import to.eyed.seeker.code.core.BlameLine
import org.junit.Test

/**
 * The words at the end of the caret's line.
 *
 * Zed's own wording, from `time_format.rs:259-305`, which follows git's
 * `show_date_relative` — so "3 days ago" here means what it means in
 * `git log`, and the boundaries are where git puts them rather than where
 * rounding happens to land.
 */
class BlameTextTest {

    private val now = 1_700_000_000L

    private fun ago(seconds: Long) = relativeTime(now - seconds, now)

    @Test
    fun minutesAndHoursAreExact() {
        assertEquals("Just now", ago(0))
        assertEquals("Just now", ago(59))
        assertEquals("1 minute ago", ago(60))
        assertEquals("59 minutes ago", ago(59 * 60))
        assertEquals("1 hour ago", ago(3600))
        assertEquals("23 hours ago", ago(23 * 3600))
    }

    @Test
    fun daysWeeksMonthsAndYears() {
        assertEquals("Yesterday", ago(24 * 3600))
        assertEquals("6 days ago", ago(6 * 24 * 3600))
        assertEquals("1 week ago", ago(7 * 24 * 3600))
        assertEquals("4 weeks ago", ago(28 * 24 * 3600))
        assertEquals("1 month ago", ago(35L * 24 * 3600))
        assertEquals("6 months ago", ago(180L * 24 * 3600))
        assertEquals("1 year ago", ago(400L * 24 * 3600))
        assertEquals("3 years ago", ago(3L * 365 * 24 * 3600))
    }

    /** A commit from the future is a clock that disagrees, not an error. */
    @Test
    fun aTimestampInTheFutureDoesNotReadAsNegative() {
        assertEquals("Just now", relativeTime(now + 10_000, now))
    }

    @Test
    fun theLineNamesWhoTouchedItAndWhatTheySaid() {
        val line = BlameLine(
            sha = "a".repeat(40),
            startRow = 0,
            rowCount = 1,
            author = "Carlo Esposito",
            authorTime = now - 3600,
            summary = "Draw the git gutter",
        )
        assertEquals("Carlo Esposito, 1 hour ago - Draw the git gutter", blameText(line, now))
    }

    /**
     * git reports a line that is not committed as all-zeroes; naming that
     * commit would be naming nothing.
     */
    @Test
    fun anUncommittedLineSaysSo() {
        val line = BlameLine(
            sha = "0".repeat(40),
            startRow = 0,
            rowCount = 1,
            author = "Not Committed Yet",
            authorTime = 0,
            summary = "",
        )
        assertEquals("Uncommitted", blameText(line, now))
    }
}
