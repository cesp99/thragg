package to.eyed.seeker.code.ui.agent.spettro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import to.eyed.seeker.code.core.AgentEntry
import to.eyed.seeker.code.core.Member
import to.eyed.seeker.code.core.OrchCounts
import to.eyed.seeker.code.core.OrchStatus
import to.eyed.seeker.code.core.ToolCallStatus
import to.eyed.seeker.code.core.ToolKind
import org.junit.Test

/**
 * The arithmetic behind the run cards.
 *
 * Everything tested here decides what a reader is told about a run that is
 * still going, and every failure mode is silent: a meter that rounds a failure
 * away draws a struggling run as a clean one, a cap that re-sorts makes the
 * live list churn under the thumb, a truncation that eats `#7` turns twelve
 * members into one member twelve times. None of it throws.
 */
class OrchBitsTest {

    private fun tool(id: String, turn: Long = 1) = AgentEntry.ToolCall(
        id = id,
        title = "agent $id",
        kind = ToolKind.Other,
        status = ToolCallStatus.Completed,
        options = emptyList(),
        content = emptyList(),
        locations = emptyList(),
        rawInput = null,
        turn = turn,
    )

    private fun member(
        id: String,
        status: OrchStatus,
        spec: String = "review",
        index: Int = 1,
        cached: Boolean = false,
        result: String = "",
    ) = Member(
        tool = tool(id),
        instance = "$spec#$index",
        specId = spec,
        index = index,
        task = "the $spec task",
        phase = "Review",
        cached = cached,
        status = status,
        children = emptyList(),
        resultText = result,
        resultIsJson = false,
        rawResult = result,
    )

    // --- the meter -----------------------------------------------------------

    @Test
    fun anEmptyRunFillsNothing() {
        assertEquals(MeterFill(0f, 0f), meterFill(OrchCounts.NONE))
        assertEquals(MeterFill(0f, 0f), meterFill(OrchCounts(0, 0, 0, 0, 0)))
    }

    /**
     * The case the rule exists for: one failure in fifty is 2% of the bar,
     * which is a couple of pixels the corner radius eats. Forced to 6%, and
     * the successes give way rather than the sum spilling past the box.
     */
    @Test
    fun oneFailureInFiftyIsStillVisible() {
        val fill = meterFill(OrchCounts(total = 50, running = 0, done = 49, failed = 1, cached = 0))
        assertEquals(0.06f, fill.failed, 1e-6f)
        assertEquals(0.94f, fill.done, 1e-6f)
        assertEquals(0f, fill.track, 1e-6f)
    }

    @Test
    fun aFailureLargerThanTheFloorKeepsItsRealWidth() {
        val fill = meterFill(OrchCounts(total = 4, running = 0, done = 2, failed = 2, cached = 0))
        assertEquals(0.5f, fill.failed, 1e-6f)
        assertEquals(0.5f, fill.done, 1e-6f)
    }

    /** No failures means no red at all — the floor is not a minimum on zero. */
    @Test
    fun aCleanRunHasNoFailureSegment() {
        val fill = meterFill(OrchCounts(total = 8, running = 4, done = 4, failed = 0, cached = 0))
        assertEquals(0f, fill.failed, 1e-6f)
        assertEquals(0.5f, fill.done, 1e-6f)
        assertEquals(0.5f, fill.track, 1e-6f)
    }

    @Test
    fun everythingFailedLeavesNoRoomForDone() {
        val fill = meterFill(OrchCounts(total = 3, running = 0, done = 0, failed = 3, cached = 0))
        assertEquals(1f, fill.failed, 1e-6f)
        assertEquals(0f, fill.done, 1e-6f)
    }

    // --- the counts line -----------------------------------------------------

    /** Zero terms are dropped: `0 failed` reads as a number before it reads as a zero. */
    @Test
    fun zeroTermsAreDropped() {
        val terms = countsTerms(OrchCounts(total = 7, running = 2, done = 4, failed = 0, cached = 0))
        assertEquals(listOf("2 running", "4 done"), terms.map { it.text })
        assertTrue(terms.none { it.failure })
    }

    /** `cached` is always spelled *replayed*, and only the failure is coloured. */
    @Test
    fun cachedIsSpelledReplayedAndOnlyFailureIsFlagged() {
        val terms = countsTerms(OrchCounts(total = 12, running = 1, done = 8, failed = 1, cached = 3))
        assertEquals(listOf("1 running", "8 done", "1 failed", "3 replayed"), terms.map { it.text })
        assertEquals(listOf(false, false, true, false), terms.map { it.failure })
    }

    @Test
    fun anUntouchedRunHasNoCountsLine() {
        assertEquals(emptyList<CountTerm>(), countsTerms(OrchCounts(total = 5, 0, 0, 0, 0)))
    }

    // --- names ---------------------------------------------------------------

    /** The `#N` is the only part that distinguishes one member of a fan-out. */
    @Test
    fun truncationKeepsTheInstanceNumber() {
        assertEquals("general-p…#7", truncateInstance("general-purpose#7", 12))
        assertEquals("review#2", truncateInstance("review#2", 12))
        assertEquals("code#12", truncateInstance("code#12", 12))
    }

    @Test
    fun aNamelessSuffixTruncatesPlainly() {
        assertEquals("verylongsp…", truncateInstance("verylongspecname", 11))
        assertEquals("", truncateInstance("anything", 0))
    }

    /** Too narrow for head + ellipsis + suffix: keep the front, drop the rest. */
    @Test
    fun aSuffixThatCannotFitIsGivenUp() {
        assertEquals("gen…", truncateInstance("general-purpose#123", 4))
    }

    // --- tints ---------------------------------------------------------------

    @Test
    fun tintsAreStableAndInRange() {
        val first = memberTintIndex("review")
        assertEquals(first, memberTintIndex("review"))
        for (spec in listOf("review", "code", "general-purpose", "", "docs")) {
            assertTrue(memberTintIndex(spec) in 0..7)
        }
        assertEquals(0, memberTintIndex("review", count = 0))
    }

    /** Different specs must be able to disagree, or the tint says nothing. */
    @Test
    fun differentSpecsSpreadAcrossTints() {
        val spread = listOf("review", "code", "docs", "test", "plan", "audit")
            .map { memberTintIndex(it) }
            .toSet()
        assertTrue(spread.size > 1)
    }

    // --- elapsed -------------------------------------------------------------

    @Test
    fun elapsedNeverShowsMoreThanTwoUnits() {
        assertEquals("12s", elapsedLabel(12_400))
        assertEquals("1m 07s", elapsedLabel(67_000))
        assertEquals("1h 04m", elapsedLabel(3_840_000))
        assertEquals("0s", elapsedLabel(-5))
    }

    // --- which rows survive --------------------------------------------------

    private val fanOut = listOf(
        member("a", OrchStatus.Running, index = 1),
        member("b", OrchStatus.Running, index = 2),
        member("c", OrchStatus.Failed, index = 3),
        member("d", OrchStatus.Done, index = 4),
        member("e", OrchStatus.Done, index = 5),
    )

    /**
     * The cap picks survivors and never re-sorts: the fold already put the
     * actionable rows first, and a live list that reorders is unreadable.
     */
    @Test
    fun theLiveCapKeepsTheOrderItWasGiven() {
        val split = splitMembers(fanOut, live = true, cap = 3)
        assertEquals(listOf("a", "b", "c"), split.rows.map { it.tool.id })
        assertEquals(listOf("d", "e"), split.hidden.map { it.tool.id })
        assertEquals(emptyList<Member>(), split.folded)
    }

    /** Settled: every failure keeps its row, the successes fold away. */
    @Test
    fun aSettledRunFoldsSuccessesAndKeepsFailures() {
        val split = splitMembers(fanOut, live = false, cap = 6)
        assertEquals(listOf("a", "b", "c"), split.rows.map { it.tool.id })
        assertEquals(listOf("d", "e"), split.folded.map { it.tool.id })
        assertEquals(0, split.hiddenCount)
    }

    @Test
    fun showAllOverridesBothRules() {
        val split = splitMembers(fanOut, live = false, cap = 1, showAll = true)
        assertEquals(fanOut, split.rows)
        assertEquals(0, split.hiddenCount)
        assertEquals(emptyList<Member>(), split.folded)
    }

    /**
     * "Five more are working" is an answer; "five more exist" is a scrollbar.
     */
    @Test
    fun theOverflowLineSaysWhetherTheHiddenRowsAreMoving() {
        assertEquals(
            "… 2 more running",
            overflowLabel(listOf(member("x", OrchStatus.Running), member("y", OrchStatus.Running))),
        )
        assertEquals(
            "… 2 more",
            overflowLabel(listOf(member("x", OrchStatus.Running), member("y", OrchStatus.Done))),
        )
        assertEquals("", overflowLabel(emptyList()))
    }

    // --- swarm merge outcomes ------------------------------------------------

    /** Only the two outcomes that leave work behind get a pill. */
    @Test
    fun onlyUnmergedWorkGetsAPill() {
        assertEquals("conflict", mergeOutcome(member("a", OrchStatus.Done, result = """{"merge":"conflict"}""")))
        assertEquals("preserved", mergeOutcome(member("b", OrchStatus.Failed, result = """{"merge_status":"PRESERVED"}""")))
        assertEquals("", mergeOutcome(member("c", OrchStatus.Done, result = """{"merge":"merged"}""")))
        assertEquals("", mergeOutcome(member("d", OrchStatus.Done, result = "not json at all")))
        assertEquals("", mergeOutcome(member("e", OrchStatus.Done)))
    }

    /** A member still working has not merged anything yet. */
    @Test
    fun aRunningMemberIsNeverPilled() {
        assertEquals(
            "",
            mergeOutcome(member("a", OrchStatus.Running, result = """{"merge":"conflict"}""")),
        )
    }
}
