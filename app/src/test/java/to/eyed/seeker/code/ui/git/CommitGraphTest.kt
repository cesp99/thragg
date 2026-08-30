package to.eyed.seeker.code.ui.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.seeker.code.core.Commit

/**
 * Laying commits into lanes.
 *
 * The graph is the one part of a git UI where being *slightly* wrong is worse
 * than not drawing it: a line to the wrong parent asserts a history that did
 * not happen. So the shapes are pinned here — a straight line, a branch and a
 * merge, an octopus, and the two orders a walk can meet them in.
 */
class CommitGraphTest {

    private fun commit(sha: String, vararg parents: String) = Commit(
        sha = sha,
        parents = parents.toList(),
        author = "A",
        authorEmail = "a@b",
        authorTime = 0,
        subject = sha,
        refs = emptyList(),
    )

    /** A history with no branches is one lane, top to bottom. */
    @Test
    fun aStraightHistoryIsOneLane() {
        val rows = layoutGraph(
            listOf(commit("c", "b"), commit("b", "a"), commit("a"))
        )
        assertEquals(listOf(0, 0, 0), rows.map { it.lane })
        assertTrue(rows.all { it.laneCount == 1 })
        assertTrue(rows.all { it.through.isEmpty() })
        // The root has no parent and frees its lane.
        assertTrue(rows.last().parentLanes.isEmpty())
    }

    /**
     * A merge: the newest commit has two parents, so a second lane opens and
     * closes again when the branch's commits run out.
     *
     *     m      lane 0, parents f (lane 0) and s (lane 1)
     *     |\
     *     f |    lane 0
     *     | s    lane 1
     *     |/
     *     a      both lanes want it; it takes one and the other merges in
     */
    @Test
    fun aMergeOpensASecondLaneAndClosesIt() {
        val rows = layoutGraph(
            listOf(
                commit("m", "f", "s"),
                commit("f", "a"),
                commit("s", "a"),
                commit("a"),
            )
        )
        assertEquals(0, rows[0].lane)
        assertEquals(listOf(0, 1), rows[0].parentLanes)
        assertEquals(2, rows[0].laneCount)

        assertEquals(0, rows[1].lane)
        // The side branch is still open and passes this row.
        assertEquals(listOf(1), rows[1].through)

        assertEquals(1, rows[2].lane)

        // Both branches lead to `a`, which is drawn once.
        assertEquals(1, rows.count { it.commit.sha == "a" })
        assertEquals(1, rows[3].laneCount)
    }

    /** An octopus merge — three parents — opens two extra lanes at once. */
    @Test
    fun anOctopusMergeOpensALanePerExtraParent() {
        val rows = layoutGraph(listOf(commit("m", "a", "b", "c")))
        assertEquals(listOf(0, 1, 2), rows[0].parentLanes)
        assertEquals(3, rows[0].laneCount)
    }

    /**
     * Two branches whose next commit is the same one: the second lane does not
     * carry on past it. Getting this wrong leaves a line running off the
     * bottom of the graph forever.
     */
    @Test
    fun twoLanesWaitingForTheSameCommitBecomeOne() {
        val rows = layoutGraph(
            listOf(
                commit("m", "x", "y"),
                commit("x", "base"),
                commit("y", "base"),
                commit("base"),
            )
        )
        val base = rows.last()
        assertEquals(1, base.laneCount)
        assertTrue("no lane should pass the root", base.through.isEmpty())
    }

    /** Nothing in, nothing out — and no crash on an empty repository. */
    @Test
    fun anEmptyHistoryHasNoRows() {
        assertTrue(layoutGraph(emptyList()).isEmpty())
    }

    /**
     * A page of history is a *window*: its oldest commits name parents that
     * were not loaded. Those lanes stay open, which is right — the graph is
     * cut off, not finished — and nothing may index past the lane list.
     */
    @Test
    fun parentsOutsideTheLoadedPageDoNotBreakTheLayout() {
        val rows = layoutGraph(listOf(commit("c", "not-loaded"), commit("d", "also-not-loaded")))
        assertEquals(0, rows[0].lane)
        // `d` is not `c`'s parent, so it takes a lane of its own.
        assertEquals(1, rows[1].lane)
        assertTrue(rows.all { it.lane < it.laneCount })
        assertTrue(rows.all { row -> row.parentLanes.all { it < row.laneCount } })
        assertTrue(rows.all { row -> row.through.all { it < row.laneCount } })
    }

    /**
     * The shape a skeptic found: a side branch whose commit is *newer* than
     * the fork point, so the lane waiting for the shared parent is the later
     * one. The lane must still close — before this it drew a line running off
     * the bottom of the graph, past the root commit.
     *
     *     M(A,B)   A(X)   B(P)   X(P)   P(R)   R
     */
    @Test
    fun aLaneClosesEvenWhenTheOtherLaneClaimedTheParentFirst() {
        val rows = layoutGraph(
            listOf(
                commit("M", "A", "B"),
                commit("A", "X"),
                commit("B", "P"),
                commit("X", "P"),
                commit("P", "R"),
                commit("R"),
            )
        )
        val root = rows.last()
        assertEquals("R", root.commit.sha)
        // Nothing passes the root any more. The merged lane keeps its column
        // — a line that jumped sideways would be worse than one that is a
        // column further right — but it no longer *draws*, which is the bug.
        assertTrue(
            "a lane was still open at the root: ${root.through}",
            root.through.isEmpty(),
        )
        val shared = rows.first { it.commit.sha == "P" }
        assertTrue("a lane was still open at the shared parent", shared.through.isEmpty())
        assertEquals(1, rows.count { it.commit.sha == "P" })
    }

    /** Every row's indices stay inside the width it reports. */
    @Test
    fun noRowPointsPastItsOwnLaneCount() {
        val rows = layoutGraph(
            listOf(
                commit("M", "A", "B", "C"),
                commit("A", "P"),
                commit("B", "P"),
                commit("C", "P"),
                commit("P"),
            )
        )
        for (row in rows) {
            assertTrue(row.lane < row.laneCount)
            assertTrue(row.through.all { it < row.laneCount })
            assertTrue(row.parentLanes.all { it < row.laneCount })
        }
    }
}
