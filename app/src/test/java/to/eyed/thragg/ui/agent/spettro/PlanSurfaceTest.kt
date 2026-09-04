package to.eyed.thragg.ui.agent.spettro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.thragg.core.AgentPlanEntry

/**
 * What the 32 dp strip picks out of a plan.
 *
 * The plan is republished *whole* on every task mutation, so this function
 * runs on every update of a list that is never patched in place. The cases
 * that matter are the ones where the obvious `first { in_progress }` returns
 * nothing: between two tasks, before the first one starts, and after the last
 * one finishes. In every one of them the strip must still say something.
 */
class PlanSurfaceTest {

    private fun entry(
        content: String,
        status: String,
        blocked: Boolean = false,
        priority: String = "medium",
    ) = AgentPlanEntry(content = content, priority = priority, status = status, blocked = blocked)

    @Test
    fun anEmptyPlanHasNoStrip() {
        // Not an absence: Spettro publishes `entries: []` on purpose when the
        // last task is deleted, and the strip's answer is to disappear.
        assertNull(planSummary(emptyList()))
    }

    @Test
    fun theStripShowsTheRunningTask() {
        val summary = planSummary(
            listOf(
                entry("Read the doc", "completed"),
                entry("Wire the sheet", "in_progress"),
                entry("Run the tests", "pending"),
            ),
        )!!
        assertEquals("Wire the sheet", summary.headline?.content)
        assertEquals("1/3", summary.counts)
        assertFalse(summary.isComplete)
    }

    @Test
    fun betweenTasksItFallsBackToTheFirstPendingOne() {
        // No `in_progress` entry exists for the moment between one task
        // completing and the next starting, and a strip that blanked there
        // would flicker several times a turn.
        val summary = planSummary(
            listOf(
                entry("Read the doc", "completed"),
                entry("Wire the sheet", "pending"),
                entry("Run the tests", "pending"),
            ),
        )!!
        assertEquals("Wire the sheet", summary.headline?.content)
        assertEquals("1/3", summary.counts)
    }

    @Test
    fun aRunningTaskOutranksAnEarlierPendingOne() {
        // Dependency order is not execution order: a blocked task can sit
        // above the one actually running.
        val summary = planSummary(
            listOf(
                entry("Ship it", "pending", blocked = true),
                entry("Run the tests", "in_progress"),
            ),
        )!!
        assertEquals("Run the tests", summary.headline?.content)
    }

    @Test
    fun aFinishedPlanKeepsItsLastTaskOnTheStrip() {
        val plan = listOf(entry("Read the doc", "completed"), entry("Ship it", "completed"))
        val summary = planSummary(plan)!!
        assertEquals("Ship it", summary.headline?.content)
        assertEquals("2/2", summary.counts)
        assertTrue(summary.isComplete)
    }

    @Test
    fun aPlanPublishedButNotStartedShowsItsFirstTask() {
        val summary = planSummary(listOf(entry("Read the doc", "pending"), entry("Ship it", "pending")))!!
        assertEquals("Read the doc", summary.headline?.content)
        assertEquals("0/2", summary.counts)
        assertFalse(summary.isComplete)
    }

    @Test
    fun blockedOnlySaysSomethingAboutWorkThatHasNotStarted() {
        // W-12 lifts the literal " (blocked)" suffix into a flag. A completed
        // task that still carries the flag is stale data, not a blocked task.
        assertTrue(entry("Run the tests", "pending", blocked = true).isBlocked)
        assertFalse(entry("Run the tests", "completed", blocked = true).isBlocked)
        assertFalse(entry("Run the tests", "in_progress", blocked = true).isBlocked)
        assertFalse(entry("Run the tests", "pending").isBlocked)
    }

    @Test
    fun theSuffixIsNotInTheTaskText() {
        // The pill exists so the word never lands inside the sentence.
        val blocked = entry("Run the test suite", "pending", blocked = true)
        assertFalse(blocked.content.contains("blocked"))
    }

    /**
     * One drawable and one spoken word per status, and no two the same.
     *
     * The identity of the drawables is not asserted — a resource id is a
     * generated number and pinning it would fail on every unrelated resource
     * change. What matters is that the three statuses stay *distinguishable*:
     * the bug this guards against is a copy-paste that gives "in progress" and
     * "pending" the same mark, which on screen is a plan that never moves.
     */
    @Test
    fun theMarksAreOnePerStatusAndAllDifferent() {
        val statuses = AgentPlanEntry.Status.entries
        val icons = statuses.map { statusIcon(it).first }
        val words = statuses.map { statusIcon(it).second }
        assertEquals(statuses.size, icons.toSet().size)
        assertEquals(statuses.size, words.toSet().size)
        // Every mark carries words: this is the one icon in the app that is
        // the sole bearer of its row's state, so it may not be decoration.
        assertTrue(words.none { it.isBlank() })
    }

    @Test
    fun anUnknownStatusCountsAsPendingRatherThanVanishing() {
        // Forward compatibility: a status ACP adds later must still draw.
        val summary = planSummary(listOf(entry("Something new", "deferred")))!!
        assertEquals("Something new", summary.headline?.content)
        assertEquals("0/1", summary.counts)
    }
}
