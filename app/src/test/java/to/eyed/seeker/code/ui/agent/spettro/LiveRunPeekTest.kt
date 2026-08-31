package to.eyed.seeker.code.ui.agent.spettro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.seeker.code.core.AgentEntry
import to.eyed.seeker.code.core.Member
import to.eyed.seeker.code.core.OrchCounts
import to.eyed.seeker.code.core.OrchRun
import to.eyed.seeker.code.core.OrchStatus
import to.eyed.seeker.code.core.ToolCallStatus
import to.eyed.seeker.code.core.ToolKind

/**
 * The peek's settle choreography.
 *
 * It is a state machine driven by a clock, and every bug in one is a bug you
 * only see at the moment a run ends — which is the moment the user is looking
 * hardest. The three that matter: a run that ended must not vanish mid-glance,
 * a run that ended must not linger for ever, and a run that merely stopped
 * being mentioned must never be reported as a success.
 */
class LiveRunPeekTest {

    private fun tool(id: String, turn: Long = 1) = AgentEntry.ToolCall(
        id = id,
        title = "workflow $id",
        kind = ToolKind.Other,
        status = ToolCallStatus.InProgress,
        options = emptyList(),
        content = emptyList(),
        locations = emptyList(),
        rawInput = null,
        turn = turn,
    )

    private fun member(id: String, status: OrchStatus, spec: String = "review", index: Int = 1) =
        Member(
            tool = tool(id),
            instance = "$spec#$index",
            specId = spec,
            index = index,
            task = "the $spec task",
            phase = "Review",
            cached = false,
            status = status,
            children = emptyList(),
            resultText = "",
            resultIsJson = false,
        )

    private fun workflow(
        id: String,
        status: OrchStatus,
        counts: OrchCounts = OrchCounts(7, 2, 4, 1, 0),
        summary: String = "",
        name: String = "review-changes",
    ) = OrchRun.Workflow(
        tool = tool(id),
        runId = "run-$id",
        name = name,
        description = "Review the diff",
        origin = "saved",
        phases = emptyList(),
        logs = emptyList(),
        summary = summary,
        rendered = "",
        script = null,
        status = status,
        counts = counts,
    )

    private fun swarm(id: String, status: OrchStatus, counts: OrchCounts = OrchCounts(20, 5, 7, 0, 0)) =
        OrchRun.Swarm(
            tool = tool(id),
            description = "Add doc comments",
            subagentType = "code",
            isolation = "worktree",
            items = emptyList(),
            members = emptyList(),
            pending = emptyList(),
            status = status,
            counts = counts,
        )

    // --- admission and slots -------------------------------------------------

    /** Slots are handed out in arrival order and never recomputed. */
    @Test
    fun slotsAreAssignedOnceAndKept() {
        val first = peekSlots(emptyList(), listOf(workflow("a", OrchStatus.Running)), 1_000)
        assertEquals(listOf(0), first.map { it.slot })

        val second = peekSlots(
            first,
            listOf(swarm("b", OrchStatus.Running), workflow("a", OrchStatus.Running)),
            2_000,
        )
        // Sorted by slot, not by the order the transcript happened to fold in.
        assertEquals(listOf("1:a", "1:b"), second.map { it.key })
        assertEquals(listOf(0, 1), second.map { it.slot })
    }

    /**
     * A finished run scrolling into view must not pop the peek open for
     * something that ended ten minutes ago.
     */
    @Test
    fun aRunThatWasNeverLiveIsNeverAdmitted() {
        val slots = peekSlots(emptyList(), listOf(workflow("a", OrchStatus.Done)), 1_000)
        assertTrue(slots.isEmpty())
    }

    // --- hold, collapse, release ---------------------------------------------

    /** The first poll that sees a run stop stamps the time; later polls do not. */
    @Test
    fun settlingIsStickyAndSurvivesRepeatedPolls() {
        val live = peekSlots(emptyList(), listOf(workflow("a", OrchStatus.Running)), 1_000)
        val settled = peekSlots(live, listOf(workflow("a", OrchStatus.Done)), 5_000)
        assertEquals(5_000L, settled.single().settledAt)
        val later = peekSlots(settled, listOf(workflow("a", OrchStatus.Done)), 6_200)
        assertEquals(5_000L, later.single().settledAt)
    }

    /**
     * 4000 ms in full, then the one-line summary, then gone at 7000 ms.
     *
     * The hold is `Durations.RUN_HOLD` now rather than a constant of its own —
     * it was 1600 ms, which is the copy-confirmation's duration, and four
     * figures of final counts are not readable in it (docs/VISUAL.md,
     * "Foundations", MOTION).
     */
    @Test
    fun theSnapshotIsHeldThenCollapsedThenReleased() {
        val live = peekSlots(emptyList(), listOf(workflow("a", OrchStatus.Running)), 0)
        val settled = peekSlots(live, listOf(workflow("a", OrchStatus.Done)), 1_000).single()

        assertTrue(settled.isHeld(1_000 + 3_999))
        assertFalse(settled.isHeld(1_000 + 4_000))
        assertFalse(settled.isReleased(1_000 + 6_999))
        assertTrue(settled.isReleased(1_000 + 7_000))

        assertTrue(peekSlots(listOf(settled), emptyList(), 1_000 + 7_000).isEmpty())
    }

    /** A live run is held for ever — the hold only starts when it stops. */
    @Test
    fun aLiveRunIsAlwaysHeld() {
        val live = peekSlots(emptyList(), listOf(workflow("a", OrchStatus.Running)), 0).single()
        assertEquals(0L, live.settledAt)
        assertTrue(live.isHeld(9_999_999))
        assertFalse(live.isReleased(9_999_999))
    }

    // --- runs that merely disappear ------------------------------------------

    @Test
    fun aVanishedRunKeepsItsSlotAndIsMarked() {
        val live = peekSlots(
            emptyList(),
            listOf(workflow("a", OrchStatus.Running), swarm("b", OrchStatus.Running)),
            0,
        )
        val after = peekSlots(live, listOf(swarm("b", OrchStatus.Running)), 500)
        val gone = after.single { it.key == "1:a" }
        assertTrue(gone.vanished)
        assertEquals(0, gone.slot)
        assertEquals(500L, gone.settledAt)
    }

    /** Never claim a success you cannot vouch for. */
    @Test
    fun aVanishedRunWithFailuresReportsFailed() {
        val failing = workflow("a", OrchStatus.Running, OrchCounts(7, 2, 4, 1, 0))
        assertEquals(OrchStatus.Failed, peekStatus(failing, vanished = true))
        assertEquals("4 done · 1 failed", peekNote(failing, vanished = true))
    }

    /** And one that produced nothing at all says only that it stopped. */
    @Test
    fun aVanishedRunWithNothingToShowSaysEnded() {
        val empty = workflow("a", OrchStatus.Running, OrchCounts(3, 3, 0, 0, 0))
        assertEquals("ended", peekNote(empty, vanished = true))
        assertEquals("finished", peekNote(empty, vanished = false))
    }

    /** The run's own summary wins over anything this file could compose. */
    @Test
    fun theRunsOwnSummaryIsPreferred() {
        val run = workflow("a", OrchStatus.Done, OrchCounts(12, 0, 11, 1, 0), summary = "12 agents · 1 failed")
        assertEquals("12 agents · 1 failed", peekNote(run, vanished = false))
    }

    @Test
    fun aLiveRunKeepsItsOwnStatus() {
        assertEquals(OrchStatus.Running, peekStatus(workflow("a", OrchStatus.Running), vanished = false))
        assertEquals(OrchStatus.Done, peekStatus(workflow("a", OrchStatus.Done), vanished = false))
    }

    // --- what the column lists -----------------------------------------------

    /** Only running members, capped — the settled ones are on the card. */
    @Test
    fun onlyRunningMembersAreListed() {
        val members = listOf(
            member("m1", OrchStatus.Running, index = 1),
            member("m2", OrchStatus.Running, index = 2),
            member("m3", OrchStatus.Failed, index = 3),
            member("m4", OrchStatus.Running, index = 4),
            member("m5", OrchStatus.Done, index = 5),
        )
        assertEquals(
            listOf("review#1", "review#2"),
            peekMembers(members, PeekPhaseCap - 1).map { it.instance },
        )
        assertEquals(1, peekHidden(members, PeekPhaseCap - 1))
        assertEquals(0, peekHidden(members, PeekSwarmCap))
    }

    @Test
    fun theCollapsedLineNamesTheRun() {
        assertEquals("review-changes", peekName(workflow("a", OrchStatus.Running)))
        assertEquals("workflow", peekName(workflow("a", OrchStatus.Running, name = "")))
        assertEquals("ultra swarm · code", peekName(swarm("b", OrchStatus.Running)))
    }
}
