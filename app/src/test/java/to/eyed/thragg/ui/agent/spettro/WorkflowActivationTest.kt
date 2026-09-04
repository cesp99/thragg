package to.eyed.thragg.ui.agent.spettro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The activation matcher, held against the CLI it was ported from.
 *
 * These cases are `internal/agent/workflow_test.go`'s own, plus the ones the
 * phone adds. They are the contract that keeps the glow honest: if the two
 * ever disagree, the composer is either promising a mode the run will not
 * enter or spending the user's money without saying so, and both are worse
 * than no highlight.
 */
class WorkflowActivationTest {

    /** The lit text, for assertions that care what got lit rather than where. */
    private fun lit(text: String): List<String> =
        workflowActivationSpans(text).map { text.substring(it.first, it.last + 1) }

    @Test
    fun theKeywordLightsUpWhereverAndHoweverItIsCased() {
        assertEquals(
            listOf("ultracode", "ULTRACODE"),
            lit("héllo ultracode wörld ULTRACODE"),
        )
        assertEquals(listOf("ULTRAcode"), lit("ULTRAcode!"))
        // Punctuation on either side is not a word character, so the boundary
        // holds — including inside a path, which the CLI also activates on.
        assertEquals(listOf("ultracode"), lit("x ultracode."))
        assertEquals(listOf("ultracode"), lit("src/ultracode.go"))
    }

    @Test
    fun aWordThatMerelyContainsTheKeywordStaysPlain() {
        // The trailing boundary is the whole point: "ultracoded" is a word
        // somebody wrote, not a request.
        assertEquals(emptyList<String>(), lit("ultracoded"))
        assertEquals(emptyList<String>(), lit("preultracode"))
        assertEquals(emptyList<String>(), lit("ultracode_run"))
        assertEquals(emptyList<String>(), lit(""))
    }

    @Test
    fun theSevenPatternsEachHaveTheirOwnDoor() {
        // 1 · the keyword
        assertEquals(listOf("ultracode"), lit("ultracode the rest"))
        // 2 · "<verb> a workflow", with every shape of the verb list
        assertEquals(listOf("use a workflow"), lit("use a workflow to modernise these handlers"))
        assertEquals(listOf("Use a workflow"), lit("Use a workflow for this"))
        assertEquals(
            listOf("write a workflow"),
            lit("can you write a workflow that reviews the diff"),
        )
        assertEquals(
            listOf("run a multi-agent workflow"),
            lit("run a multi-agent workflow over the packages"),
        )
        assertEquals(
            listOf("set up a workflow"),
            lit("set up a workflow to migrate the call sites"),
        )
        assertEquals(listOf("setup a workflow"), lit("setup a workflow for the sweep"))
        assertEquals(listOf("do this as a workflow"), lit("do this as a workflow please"))
        assertEquals(listOf("kick off a workflow"), lit("kick off a workflow"))
        assertEquals(
            listOf("create another orchestration workflow"),
            lit("create another orchestration workflow"),
        )
        // 3 · "<preposition> workflows"
        assertEquals(listOf("with workflows"), lit("handle it with workflows"))
        assertEquals(listOf("via workflows"), lit("do it via workflows"))
        // 4 · the tool by name
        assertEquals(listOf("workflow tool"), lit("reach for the workflow tool here"))
        // 5 · fanning out
        assertEquals(
            listOf("fan this out across sub-agents"),
            lit("fan this out across sub-agents"),
        )
        assertEquals(listOf("fan it out over several agents"), lit("fan it out over several agents"))
        // 6 · orchestrating
        assertEquals(
            listOf("orchestrate this with subagents"),
            lit("orchestrate this with subagents"),
        )
        assertEquals(
            listOf("orchestrate the migration using sub-agents"),
            lit("orchestrate the migration using sub-agents"),
        )
        // 7 · the compound noun
        assertEquals(
            listOf("multi-agent orchestration"),
            lit("I want a multi-agent orchestration for this"),
        )
        assertEquals(listOf("multi agent pipeline"), lit("build a multi agent pipeline"))
    }

    @Test
    fun ordinaryTalkAboutWorkflowsStaysQuiet() {
        // Every conversation about CI would otherwise silently pay for the
        // workflow guidance in the system prompt.
        val off = listOf(
            "our deploy workflow is broken",
            "fix the GitHub workflow in .github/workflows/ci.yml",
            "what is the workflow for releasing?",
            "the workflow diagram needs updating",
            "add a step to the release workflow",
            // The definite article is the discriminator: "the workflow" is a
            // CI job or a saved script, "a workflow" is a request for one.
            "run the workflow again",
            "trigger the workflow on push",
            "agents.md documents the agent manifest",
            "the agent tool delegates one subtask",
            "this workflow of ours predates the rewrite",
        )
        for (s in off) assertEquals(s, emptyList<String>(), lit(s))
    }

    @Test
    fun overlappingMatchesAreMergedIntoOnePhrase() {
        // "use a workflow" and "workflow tool" both fire here; a renderer
        // handed both would run two gradients over the same letters.
        val text = "please use a workflow tool here, then ultracode the rest"
        assertEquals(listOf("use a workflow tool", "ultracode"), lit(text))
        val spans = workflowActivationSpans(text)
        assertTrue("spans overlap: $spans", spans[0].last < spans[1].first)
    }

    @Test
    fun spansComeBackSortedNonOverlappingAndInBounds() {
        val text = "ultracode, then fan this out across agents, with workflows, ultracode again"
        val spans = workflowActivationSpans(text)
        assertTrue(spans.size >= 3)
        var previousEnd = -1
        for (span in spans) {
            assertTrue("start before previous end: $spans", span.first > previousEnd)
            assertTrue("empty or inverted span: $span", span.last >= span.first)
            assertTrue("out of bounds: $span in ${text.length}", span.last < text.length)
            previousEnd = span.last
        }
    }

    @Test
    fun twoSeparatePhrasesStayTwoAndTheWordsBetweenStayDark() {
        // Merging is for overlap, not for proximity: the space between these
        // is ordinary prose and lighting it would claim it activated
        // something. The CLI's `sp[0] <= last[1]` says the same on half-open
        // ranges — a gap of one character is still a gap.
        val text = "ultracode use a workflow"
        assertEquals(listOf("ultracode", "use a workflow"), lit(text))
        val spans = workflowActivationSpans(text)
        assertEquals(2, spans.size)
        assertTrue("no dark gap between $spans", spans[1].first > spans[0].last + 1)
    }

    @Test
    fun theHighlightAndTheRuntimeNeverDisagree() {
        val corpus = listOf(
            "", "ultracode", "ultracoded", "x ultracode.", "ULTRAcode!",
            "src/ultracode.go", "use a workflow", "run the workflow again",
            "our deploy workflow is broken", "orchestrate this with subagents",
            "fan this out across sub-agents", "multi-agent run",
        )
        for (s in corpus) {
            assertTrue(s, workflowActivationSpans(s).isNotEmpty() == workflowRequested(s))
        }
    }

    @Test
    fun onlyTheKeywordIsAStandingYes() {
        // Typing the keyword is consent to spend; asking in English is a
        // request that Spettro still confirms before it spawns anything.
        for (s in listOf("ultracode", "ULTRACODE do the thing", "ok, ultracode.")) {
            assertTrue(s, workflowPreapproved(s))
        }
        for (s in listOf("use a workflow for this", "fan this out across agents", "")) {
            assertFalse(s, workflowPreapproved(s))
        }
        // Pre-approved implies requested, never the other way round.
        for (s in listOf("ultracode", "use a workflow", "nothing here")) {
            if (workflowPreapproved(s)) assertTrue(s, workflowRequested(s))
        }
    }

    @Test
    fun theWordBoundaryIsAsciiTheWayRe2sIs() {
        // Java's own `\b` is Unicode-aware even when `\w` is not, so it would
        // read "ö" as a word character and stay dark here where the CLI lights
        // up. The lookarounds in WorkflowActivation.kt exist for this case.
        assertEquals(listOf("ultracode"), lit("wöultracode"))
        assertEquals(listOf("ultracode"), lit("ultracodeö"))
    }
}
