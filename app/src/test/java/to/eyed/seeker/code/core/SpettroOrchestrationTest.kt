package to.eyed.seeker.code.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The orchestration fold.
 *
 * Worth testing this hard because every failure mode here is silent: a member
 * attached to the wrong run, a phase that quietly disappears, a script row
 * absorbed twice — none of them throw, they just draw a run that is not the
 * one that happened. The four cases the wire actually produces and a reader
 * would never think to check are the ones with their own tests below:
 * out-of-order arrival, a declared phase nothing ever lands in, a sub-agent
 * that fails while its tool call succeeds, and the same tool call rewritten
 * repeatedly as the run progresses.
 */
class SpettroOrchestrationTest {

    // --- fixtures ------------------------------------------------------------

    private fun call(
        id: String,
        title: String,
        open: String? = null,
        latest: String? = open,
        status: ToolCallStatus = ToolCallStatus.Completed,
        markdown: String? = null,
        turn: Long = 1,
    ) = AgentEntry.ToolCall(
        id = id,
        title = title,
        kind = ToolKind.Other,
        status = status,
        options = emptyList(),
        content = markdown?.let { listOf(ToolContent.Markdown(it)) } ?: emptyList(),
        locations = emptyList(),
        rawInput = latest,
        rawInputOpen = open,
        turn = turn,
    )

    private val runOpen = """
        {"workflow":"review-changes","run_id":"run-7","origin":"saved",
         "description":"Review the diff, then refute each finding",
         "phases":[{"name":"Review","description":"one agent per dimension"},
                   {"name":"Verify","description":"refute each finding"},
                   {"name":"Report","description":"write it up"}]}
    """.trimIndent()

    /** What the finish update overwrites `rawInput` with (SPETTRO.md W-09). */
    private val runFinish =
        """{"run_id":"run-7","workflow":"review-changes","agents":3,"failed":1,"cached":1,
            "tokens":98219}"""

    private fun member(
        id: String,
        spec: String,
        index: Int,
        phase: String,
        status: ToolCallStatus = ToolCallStatus.Completed,
        cached: Boolean = false,
        markdown: String? = null,
        run: String? = "run-7",
    ) = call(
        id = id,
        title = "agent $spec#$index: $phase work",
        open = """{"agent":"$spec","index":$index,"phase":"$phase","cached":$cached""" +
            (run?.let { ""","run_id":"$it"""" } ?: "") +
            ""","task":"the $phase task"}""",
        status = status,
        markdown = markdown,
    )

    private fun child(id: String, instance: String, title: String) =
        call(id = id, title = "[$instance] $title", open = """{"command":"go vet ./..."}""")

    private fun runs(rows: List<TranscriptRow>) =
        rows.filterIsInstance<TranscriptRow.Run>().map { it.run }

    // --- a whole three-phase run --------------------------------------------

    @Test
    fun foldsAThreePhaseRunWithOneFailure() {
        val entries = listOf<AgentEntry>(
            AgentEntry.User("review the diff"),
            call("wf-1", "workflow review-changes", runOpen, status = ToolCallStatus.InProgress),
            member("call-1", "review", 1, "Review"),
            child("call-2", "review#1", "bash go vet ./..."),
            member(
                "call-3", "review", 2, "Review",
                status = ToolCallStatus.InProgress,
            ),
            child("call-4", "review#2", "read internal/acp/bridge.go"),
            member(
                "call-5", "review", 3, "Review",
                status = ToolCallStatus.Failed,
                markdown = """{"status":"error","summary":"exit 1: 2 tests failed"}""",
            ),
        )

        val rows = foldOrchestration(entries)
        // The user message, then the run. Members and their children are
        // absorbed — nothing is emitted twice and nothing is lost.
        assertEquals(2, rows.size)
        assertTrue(rows[0] is TranscriptRow.Item)
        val run = (rows[1] as TranscriptRow.Run).run as OrchRun.Workflow

        assertEquals("run-7", run.runId)
        assertEquals("review-changes", run.name)
        assertEquals("Review the diff, then refute each finding", run.description)
        assertEquals("saved", run.origin)
        assertEquals(listOf("Review", "Verify", "Report"), run.phases.map { it.title })
        assertEquals("one agent per dimension", run.phases[0].detail)
        assertEquals(OrchStatus.Running, run.status)

        val review = run.phases[0]
        assertEquals(3, review.members.size)
        assertEquals(OrchCounts(3, 1, 1, 1, 0), review.counts)
        // Running first, then failed, then done.
        assertEquals(
            listOf("review#2", "review#3", "review#1"),
            review.members.map { it.instance },
        )
        assertEquals(listOf("review"), review.members.map { it.specId }.distinct())

        // Its own child, related back to it, and used as the live detail.
        val running = review.members.first()
        assertEquals(1, running.children.size)
        assertEquals("read internal/acp/bridge.go", running.liveDetail)
        // A settled member falls back to its task: there is no current call.
        assertEquals("the Review task", review.members.last().liveDetail)

        val failed = review.members[1]
        assertEquals(OrchStatus.Failed, failed.status)
        assertEquals("exit 1: 2 tests failed", failed.failureReason)
        assertTrue(failed.resultIsJson)
    }

    @Test
    fun aDeclaredPhaseWithNoMembersStaysVisibleAndPending() {
        val rows = foldOrchestration(
            listOf(
                call("wf-1", "workflow review-changes", runOpen, status = ToolCallStatus.InProgress),
                member("call-1", "review", 1, "Review", status = ToolCallStatus.InProgress),
            )
        )
        val run = runs(rows).single() as OrchRun.Workflow
        assertEquals(3, run.phases.size)
        val verify = run.phases[1]
        assertTrue(verify.declared)
        assertTrue(verify.isPending)
        assertEquals(OrchCounts.NONE, verify.counts)
        // Knowing what is still coming is half the value of a declared plan,
        // so a pending phase is never dropped.
        assertEquals("refute each finding", verify.detail)
    }

    @Test
    fun aPhaseNobodyDeclaredIsKeptAfterTheDeclaredOnesAndTheUnnamedBucketLast() {
        val rows = foldOrchestration(
            listOf(
                call("wf-1", "workflow review-changes", runOpen, status = ToolCallStatus.InProgress),
                member("call-1", "review", 1, ""),
                member("call-2", "review", 2, "Cleanup"),
                member("call-3", "review", 3, "Review"),
            )
        )
        val run = runs(rows).single() as OrchRun.Workflow
        assertEquals(
            listOf("Review", "Verify", "Report", "Cleanup", ""),
            run.phases.map { it.title },
        )
        assertFalse(run.phases.last().declared)
        assertEquals(1, run.phases.last().members.size)
    }

    // --- the hazards ---------------------------------------------------------

    @Test
    fun arrivalOrderDoesNotMatter() {
        // The child before its member, the members before the run that owns
        // them: exactly what a stream of deltas produces when the engine
        // re-sends only the rows that moved.
        val scrambled = listOf<AgentEntry>(
            child("call-2", "review#1", "bash go vet ./..."),
            member("call-1", "review", 1, "Review", status = ToolCallStatus.InProgress),
            call("wf-1", "workflow review-changes", runOpen, status = ToolCallStatus.InProgress),
        )
        val run = runs(foldOrchestration(scrambled)).single() as OrchRun.Workflow
        val member = run.phases[0].members.single()
        assertEquals("review#1", member.instance)
        assertEquals(1, member.children.size)
        assertEquals("bash go vet ./...", member.liveDetail)
        // And the row lands where its own tool call was, not where its
        // members were.
        val rows = foldOrchestration(scrambled)
        assertEquals(1, rows.size)
        assertTrue(rows.single() is TranscriptRow.Run)
    }

    @Test
    fun aSubAgentThatReportsAnErrorIsFailedEvenWhenItsCallSucceeded() {
        val rows = foldOrchestration(
            listOf(
                call("wf-1", "workflow review-changes", runOpen),
                member(
                    "call-1", "review", 1, "Review",
                    status = ToolCallStatus.Completed,
                    markdown = """{"status":"error","error":"429 after 3 attempts"}""",
                ),
            )
        )
        val member = (runs(rows).single() as OrchRun.Workflow).phases[0].members.single()
        // The sub-agent saying "I could not" is a successful *call* and a
        // failed *member*; the card is about the member.
        assertEquals(OrchStatus.Failed, member.status)
        assertEquals("429 after 3 attempts", member.failureReason)
    }

    @Test
    fun aPlainTextFailureSurvivesAsItsOwnReason() {
        val rows = foldOrchestration(
            listOf(
                call("wf-1", "workflow review-changes", runOpen),
                member(
                    "call-1", "review", 1, "Review",
                    status = ToolCallStatus.Failed,
                    markdown = "429 after 3 attempts\n",
                ),
            )
        )
        val member = (runs(rows).single() as OrchRun.Workflow).phases[0].members.single()
        assertFalse(member.resultIsJson)
        assertEquals("429 after 3 attempts", member.failureReason)
    }

    @Test
    fun rewritingTheRunsToolCallNeverLosesTheDeclaredPlan() {
        // The mechanism: Spettro rewrites the workflow tool call as the run
        // progresses, and the finish update replaces `rawInput` wholesale
        // with a summary that has no `phases`, no `description` and no
        // `origin`. Only `rawInputOpen` still carries them (W-09).
        val members = listOf(
            member("call-1", "review", 1, "Review"),
            member("call-2", "verify", 1, "Verify", cached = true),
            member("call-3", "verify", 2, "Verify", status = ToolCallStatus.Failed),
        )
        var latest = runOpen
        var seen: OrchRun.Workflow? = null
        // Fold the same conversation again on every rewrite, exactly as the
        // 120 ms poll does.
        for (step in 0..3) {
            if (step == 3) latest = runFinish
            val rows = foldOrchestration(
                listOf(
                    call(
                        "wf-1", "workflow review-changes", runOpen, latest,
                        status = if (step == 3) ToolCallStatus.Completed else ToolCallStatus.InProgress,
                    )
                ) + members.take(step)
            )
            seen = runs(rows).single() as OrchRun.Workflow
            assertEquals(listOf("Review", "Verify", "Report"), seen.phases.map { it.title })
            assertEquals("review-changes", seen.name)
            assertEquals("Review the diff, then refute each finding", seen.description)
        }
        val finished = requireNotNull(seen)
        assertEquals(OrchStatus.Done, finished.status)
        // The finish summary is authoritative where it is bigger: it counts
        // agents this transcript never carried a tool call for.
        assertEquals(3, finished.counts.total)
        assertEquals(1, finished.counts.failed)
        assertEquals(1, finished.counts.cached)
        assertEquals("3 agents · 1 failed · 1 replayed", finished.summary)
    }

    @Test
    fun aRunReportingMoreAgentsThanTheTranscriptCarriesKeepsTheBiggerDenominator() {
        val rows = foldOrchestration(
            listOf(
                call("wf-1", "workflow review-changes", runOpen, runFinish),
            )
        )
        val run = runs(rows).single() as OrchRun.Workflow
        assertEquals(3, run.counts.total)
        assertEquals(0, run.counts.done)
    }

    // --- the swarm -----------------------------------------------------------

    private fun swarmOpen(items: Int) = buildString {
        append("""{"description":"Add doc comments to every exported symbol",""")
        append(""""subagent_type":"code","isolation":"worktree","items":[""")
        append((1..items).joinToString(",") { "\"internal/pkg/file$it.go\"" })
        append("]}")
    }

    @Test
    fun aSwarmMidRampKeepsThePromisedDenominatorAndGrowsGhostCells() {
        val entries = mutableListOf<AgentEntry>(
            call("ultra-1", "ultra code", swarmOpen(20), status = ToolCallStatus.InProgress),
        )
        // Ultra launches five immediately then one every 700 ms; seven are up.
        for (index in 1..7) {
            entries += call(
                id = "call-$index",
                title = "agent code#$index",
                open = """{"agent":"code","index":$index,"swarm":true,"task":"file$index"}""",
                status = when (index) {
                    4 -> ToolCallStatus.Failed
                    in 1..3 -> ToolCallStatus.Completed
                    else -> ToolCallStatus.InProgress
                },
            )
        }
        val swarm = runs(foldOrchestration(entries)).single() as OrchRun.Swarm

        assertEquals("code", swarm.subagentType)
        assertEquals("worktree", swarm.isolation)
        assertEquals(20, swarm.items.size)
        assertEquals(7, swarm.members.size)
        // The denominator is what the swarm was asked to cover, never what it
        // has launched — otherwise the meter runs backwards as it ramps.
        assertEquals(13, swarm.pending.size)
        assertEquals(20, swarm.counts.total)
        assertEquals(3, swarm.counts.running)
        assertEquals(1, swarm.counts.failed)
        assertEquals("internal/pkg/file8.go", swarm.pending.first())
        assertEquals(
            listOf("code#5", "code#6", "code#7", "code#4", "code#1", "code#2", "code#3"),
            swarm.members.map { it.instance },
        )
    }

    @Test
    fun aSwarmMemberJoinsTheRunningSwarmAboveIt() {
        val rows = foldOrchestration(
            listOf(
                call("ultra-1", "ultra code", swarmOpen(2), status = ToolCallStatus.Completed),
                call("ultra-2", "ultra review", swarmOpen(2), status = ToolCallStatus.InProgress),
                call(
                    "call-9", "agent code#1",
                    open = """{"agent":"code","index":1,"swarm":true,"task":"x"}""",
                ),
            )
        )
        val all = runs(rows).filterIsInstance<OrchRun.Swarm>()
        assertEquals(2, all.size)
        assertEquals(0, all[0].members.size)
        assertEquals(1, all[1].members.size)
    }

    // --- script calls --------------------------------------------------------

    private fun scriptCall(id: String, output: String?, save: String = "review-changes") = call(
        id = id,
        title = "workflow author",
        open = """{"script":"phase(\"Review\")","save_as":"$save"}""",
        markdown = output,
    )

    @Test
    fun aScriptCallIsClaimedByTheRunItsOwnOutputNames() {
        val rows = foldOrchestration(
            listOf(
                scriptCall(
                    "call-0",
                    """<workflow_result run_id="run-7"><returned>{"ok":true}</returned></workflow_result>""",
                ),
                call("wf-1", "workflow review-changes", runOpen),
            )
        )
        assertEquals(1, rows.size)
        val run = runs(rows).single() as OrchRun.Workflow
        val script = requireNotNull(run.script)
        assertEquals("run-7", script.runId)
        assertEquals("review-changes", script.savedAs)
        // The returned value is pretty-printed, because it is what the script
        // was written to produce and is shown first.
        assertTrue(script.returned.startsWith("{"))
        assertTrue(script.returned.contains("\"ok\""))
    }

    @Test
    fun anUnclaimedScriptCallSurvivesAsItsOwnRow() {
        // The only evidence a workflow was attempted and failed before any
        // run existed. Dropping it leaves the transcript saying nothing
        // happened at all.
        val rows = foldOrchestration(
            listOf(
                AgentEntry.User("write me a review workflow"),
                scriptCall("call-0", "phase name already taken", save = "review-changes"),
            )
        )
        assertEquals(2, rows.size)
        val script = (rows[1] as TranscriptRow.Script).script
        assertEquals("", script.runId)
        assertEquals("review-changes", script.savedAs)
        assertEquals("phase(\"Review\")", script.source)
    }

    @Test
    fun aScriptWithNoResultIdIsStillClaimedByTheRunBelowIt() {
        val rows = foldOrchestration(
            listOf(
                scriptCall("call-0", "saved"),
                call("wf-1", "workflow review-changes", runOpen),
            )
        )
        assertEquals(1, rows.size)
        assertNotNull((runs(rows).single() as OrchRun.Workflow).script)
    }

    // --- everything else stays where it was ---------------------------------

    @Test
    fun ordinaryToolCallsAndMessagesPassThroughUntouched() {
        val entries = listOf<AgentEntry>(
            AgentEntry.User("hello"),
            call("call-1", "read src/main.rs", """{"path":"src/main.rs"}"""),
            AgentEntry.Assistant(listOf(AssistantChunk(false, "done"))),
        )
        val rows = foldOrchestration(entries)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it is TranscriptRow.Item })
        assertEquals(entries, rows.map { (it as TranscriptRow.Item).entry })
    }

    @Test
    fun aSubAgentWithNoRunKeepsItsOwnRow() {
        val rows = foldOrchestration(
            listOf(
                call(
                    "call-1", "agent general-purpose#7",
                    open = """{"agent":"general-purpose","index":7,"task":"find the leak"}""",
                    status = ToolCallStatus.InProgress,
                ),
                child("call-2", "general-purpose#7", "search \"leak\""),
            )
        )
        assertEquals(1, rows.size)
        val member = (rows.single() as TranscriptRow.Agent).member
        assertEquals("general-purpose#7", member.instance)
        assertEquals("general-purpose", member.specId)
        assertEquals(1, member.children.size)
    }

    @Test
    fun rowIdsSurviveToolCallIdsRepeatingEveryTurn() {
        // Spettro builds a fresh tool-call table per prompt, so `wf-1` comes
        // round again in the next turn (W-17). Two runs, two cards.
        val rows = foldOrchestration(
            listOf(
                call("wf-1", "workflow review-changes", runOpen, turn = 1),
                call("wf-1", "workflow review-changes", runOpen, turn = 2),
            )
        )
        assertEquals(listOf("run-1:wf-1", "run-2:wf-1"), rows.map { it.id })
    }

    @Test
    fun phaseAndLogProgressCallsAreAbsorbedRatherThanDrawn() {
        val rows = foldOrchestration(
            listOf(
                call("wf-1", "workflow review-changes", runOpen, status = ToolCallStatus.InProgress),
                call(
                    "wf-2", "workflow review-changes",
                    """{"workflow":"review-changes","run_id":"run-7","kind":"phase","phase":"Verify"}""",
                ),
                call(
                    "wf-3", "workflow review-changes",
                    """{"workflow":"review-changes","run_id":"run-7","kind":"log","message":"hi"}""",
                ),
            )
        )
        assertEquals(1, rows.size)
    }

    @Test
    fun anEmptyTranscriptFoldsToNothing() {
        assertEquals(emptyList<TranscriptRow>(), foldOrchestration(emptyList()))
    }

    // --- text mining ---------------------------------------------------------

    @Test
    fun minesTheLogBlockAndFallsBackToTheRenderedPhaseTree() {
        val rendered = """
            review-changes — review the diff

            log:
              journal replayed 0 entries
              "phase Review entered"

            ▸ Review        one agent per dimension
              ✓ review#1  bash go vet ./...
              ▶ review#2  read internal/acp/bridge.go
            ○ Verify        refute each finding
        """.trimIndent()

        val mined = parseRenderedWorkflow(rendered)
        assertEquals(listOf("journal replayed 0 entries", "phase Review entered"), mined.logs)
        assertEquals(listOf("Review", "Verify"), mined.phases.map { it.first })
        assertEquals("one agent per dimension", mined.phases[0].second)
    }

    @Test
    fun proseIsNeverMistakenForAPhaseTree() {
        // A description in the same text must not fake a phase spine; the
        // block only counts when *every* line is a header or a member row.
        val mined = parseRenderedWorkflow(
            """
            ○ this is a sentence that begins with a bullet
            and keeps going in prose
            """.trimIndent()
        )
        assertEquals(emptyList<Pair<String, String>>(), mined.phases)
    }

    @Test
    fun aRunWithNoDeclaredPhasesFallsBackToTheRenderedTree() {
        val rows = foldOrchestration(
            listOf(
                call(
                    "wf-1", "workflow ad-hoc",
                    open = """{"workflow":"ad-hoc","run_id":"run-9"}""",
                    status = ToolCallStatus.InProgress,
                    markdown = "▸ Review        one agent per dimension\n○ Verify",
                ),
            )
        )
        val run = runs(rows).single() as OrchRun.Workflow
        assertEquals(listOf("Review", "Verify"), run.phases.map { it.title })
    }

    @Test
    fun textMiningNeverThrows() {
        assertEquals(RenderedWorkflow(emptyList(), emptyList()), parseRenderedWorkflow(""))
        assertEquals(emptyList<String>(), parseRenderedWorkflow("log:").logs)
        assertNull(parseRenderedWorkflow("▸").phases.firstOrNull())
    }
}
