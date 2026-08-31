package to.eyed.seeker.code.ui.agent.spettro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.seeker.code.core.AgentPastSession
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The picker's three rules: which call opens a row, which rows a search
 * keeps, and which day a row lands under.
 *
 * The first is the one that can corrupt a screen rather than merely annoy —
 * `session/load` replays the whole transcript and `session/resume` does not,
 * so choosing wrong either doubles a conversation or leaves a live session
 * over an empty scrollback.
 */
class SessionPickerTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    private fun session(
        id: String,
        cwd: String = "/home/user/projects/seeker-ide",
        title: String? = null,
        updatedAt: String? = null,
    ) = AgentPastSession(sessionId = id, cwd = cwd, title = title, updatedAt = updatedAt)

    // -- load versus resume -------------------------------------------------

    @Test
    fun aSessionThePhoneDoesNotHoldIsLoadedSoTheTranscriptArrives() {
        assertEquals(SessionOpen.LOAD, sessionOpenMode(alreadyOpen = false, canReplay = true))
    }

    @Test
    fun aSessionAlreadyOnScreenIsResumedSoItIsNotPrintedTwice() {
        assertEquals(SessionOpen.RESUME, sessionOpenMode(alreadyOpen = true, canReplay = true))
    }

    @Test
    fun anAgentWithoutLoadStillOpensTheRow() {
        // Falling back to resume gives a working conversation with no
        // scrollback, which the replay notice explains. Doing nothing would
        // be a row that looks broken.
        assertEquals(SessionOpen.RESUME, sessionOpenMode(alreadyOpen = false, canReplay = false))
        assertEquals(SessionOpen.RESUME, sessionOpenMode(alreadyOpen = true, canReplay = false))
    }

    @Test
    fun theReplayNoticeSaysWhatIsMissingRatherThanApologising() {
        assertTrue(REPLAYED_SESSION_NOTICE.contains("tool activity"))
        assertTrue(REPLAYED_SESSION_NOTICE.contains("conversation only"))
    }

    // -- search -------------------------------------------------------------

    @Test
    fun aBlankQueryKeepsEverything() {
        val row = session("s1", title = "add a jupiter swap route")
        assertTrue(sessionMatches(row, ""))
        assertTrue(sessionMatches(row, "   "))
    }

    @Test
    fun searchCoversTheTitleAndTheProjectName() {
        val row = session("s1", cwd = "/home/user/projects/solana-pay-demo", title = "fix the IDL")
        assertTrue(sessionMatches(row, "idl"))
        assertTrue(sessionMatches(row, "PAY"))
        assertFalse(sessionMatches(row, "anchor"))
    }

    @Test
    fun anUntitledSessionIsStillFoundByItsFolder() {
        // The title falls back to the cwd basename, and the folder is what
        // the user types when there is no title to remember.
        val row = session("s1", cwd = "/home/user/projects/escrow")
        assertEquals("escrow", row.label)
        assertTrue(sessionMatches(row, "escr"))
    }

    @Test
    fun theProjectIsTheLastSegmentEvenWithATrailingSlash() {
        assertEquals("escrow", sessionProject(session("s1", cwd = "/home/user/escrow/")))
        assertEquals("", sessionProject(session("s1", cwd = "")))
    }

    // -- timestamps ---------------------------------------------------------

    @Test
    fun anIsoInstantIsRead() {
        assertEquals(
            1_756_562_520_000L,
            sessionTimeMillis("2025-08-30T14:02:00Z", utc),
        )
    }

    @Test
    fun anOffsetTimestampIsRead() {
        // Same instant, written by a Go formatter that kept the offset.
        assertEquals(
            sessionTimeMillis("2025-08-30T14:02:00Z", utc),
            sessionTimeMillis("2025-08-30T16:02:00+02:00", utc),
        )
    }

    @Test
    fun aTimestampWithNoOffsetIsReadAsLocalTime() {
        assertEquals(
            sessionTimeMillis("2025-08-30T14:02:00Z", utc),
            sessionTimeMillis("2025-08-30T14:02:00", utc),
        )
    }

    @Test
    fun aBareDateLandsAtTheStartOfItsDay() {
        assertEquals(1_756_512_000_000L, sessionTimeMillis("2025-08-30", utc))
    }

    @Test
    fun bareEpochsAreAcceptedInBothUnits() {
        assertEquals(1_756_562_520_000L, sessionTimeMillis("1756562520", utc))
        assertEquals(1_756_562_520_000L, sessionTimeMillis("1756562520000", utc))
    }

    @Test
    fun anUnreadableTimestampIsNullRatherThanNow() {
        assertNull(sessionTimeMillis(null, utc))
        assertNull(sessionTimeMillis("", utc))
        assertNull(sessionTimeMillis("last tuesday", utc))
    }

    // -- grouping -----------------------------------------------------------

    /** 2025-08-30 14:02 UTC — the day every case below is measured from. */
    private val now = 1_756_562_520_000L

    @Test
    fun rowsAreNewestFirstWithinAndAcrossDays() {
        val days = sessionDays(
            listOf(
                session("old", updatedAt = "2025-08-29T18:20:00Z"),
                session("newest", updatedAt = "2025-08-30T14:02:00Z"),
                session("mid", updatedAt = "2025-08-30T09:41:00Z"),
            ),
            now = now,
            zone = utc,
            locale = Locale.US,
        )
        assertEquals(listOf("TODAY", "YESTERDAY"), days.map { it.label })
        assertEquals(listOf("newest", "mid"), days[0].sessions.map { it.sessionId })
        assertEquals(listOf("old"), days[1].sessions.map { it.sessionId })
    }

    @Test
    fun undatedRowsGoLastInTheAgentsOwnOrderRatherThanBeingDropped() {
        val days = sessionDays(
            listOf(
                session("nodate-a"),
                session("today", updatedAt = "2025-08-30T09:41:00Z"),
                session("nodate-b"),
            ),
            now = now,
            zone = utc,
            locale = Locale.US,
        )
        assertEquals(listOf("TODAY", "UNDATED"), days.map { it.label })
        assertEquals(listOf("nodate-a", "nodate-b"), days[1].sessions.map { it.sessionId })
    }

    @Test
    fun anEmptyListHasNoDays() {
        assertTrue(sessionDays(emptyList(), now = now, zone = utc, locale = Locale.US).isEmpty())
    }

    @Test
    fun dayLabelsAreWordsNearbyAndDatesFurtherOut() {
        val today = LocalDate.of(2025, 8, 30)
        assertEquals("TODAY", dayLabel(today, today, Locale.US))
        assertEquals("YESTERDAY", dayLabel(today.minusDays(1), today, Locale.US))
        // Inside the week the weekday is what gets remembered.
        assertEquals("MON 25 AUG", dayLabel(today.minusDays(5), today, Locale.US))
        // Beyond it the weekday is noise.
        assertEquals("10 AUG", dayLabel(LocalDate.of(2025, 8, 10), today, Locale.US))
        assertEquals("30 DEC 2024", dayLabel(LocalDate.of(2024, 12, 30), today, Locale.US))
    }

    @Test
    fun theClockIsTheRowsOwnTimeInTheRowsOwnDay() {
        assertEquals("14:02", sessionClock(now, utc, Locale.US))
        assertEquals("09:41", sessionClock(1_756_546_860_000L, utc, Locale.US))
    }
}
