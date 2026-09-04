package to.eyed.thragg.ui.shell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import to.eyed.thragg.core.AgentEntry
import to.eyed.thragg.core.AgentMention
import to.eyed.thragg.core.PermissionOption
import to.eyed.thragg.core.SpettroQuestion
import to.eyed.thragg.core.ToolCallStatus
import to.eyed.thragg.core.ToolKind
import to.eyed.thragg.ui.editor.Diagnostic
import to.eyed.thragg.ui.editor.DiagnosticSeverity

/**
 * The Agent destination's decisions, taken away from Compose.
 *
 * Everything here is a rule whose failure the user meets as a lie rather than
 * as a crash: a Send button that says the wrong word about a running turn, a
 * permission sheet drawn over a product question, a seed that overwrites the
 * sentence the last one left in the box.
 */
class AgentScreenTest {

    private fun toolCall(
        id: String = "call-1",
        title: String = "bash go vet ./...",
        kind: ToolKind = ToolKind.Execute,
        status: ToolCallStatus = ToolCallStatus.Completed,
        options: List<PermissionOption> = emptyList(),
        rawInput: String? = null,
        rawInputOpen: String? = null,
        permissionMeta: String? = null,
        turn: Long = 0,
    ) = AgentEntry.ToolCall(
        id = id,
        title = title,
        kind = kind,
        status = status,
        options = options,
        content = emptyList(),
        locations = emptyList(),
        rawInput = rawInput,
        rawInputOpen = rawInputOpen,
        permissionMeta = permissionMeta,
        turn = turn,
    )

    private fun allow(id: String = "allow") = PermissionOption(id, "Allow", "allow_once")

    // --- the send button -----------------------------------------------------

    /**
     * The three states, and the one that is *not* here: there is no mode in
     * which the send button becomes a cancel. Stop is its own control, because
     * a Send that turns into a Stop mid-turn is what makes steering look like
     * an interruption.
     */
    @Test
    fun theButtonSaysWhatItWillDo() {
        assertEquals(SendMode.Send, sendMode(busy = false, steerable = true))
        assertEquals(SendMode.Send, sendMode(busy = false, steerable = false))
        assertEquals(SendMode.Steer, sendMode(busy = true, steerable = true))
        assertEquals("Steer", sendLabel(sendMode(busy = true, steerable = true)))
    }

    /**
     * Against a generic ACP agent the same press is a *queue*. A second
     * concurrent `session/prompt` there is two turns at once, which the
     * protocol says nothing about — so the word changes with the transport.
     */
    @Test
    fun aGenericAgentQueuesRatherThanSteers() {
        assertEquals(SendMode.Queue, sendMode(busy = true, steerable = false))
        assertEquals("Queue", sendLabel(SendMode.Queue))
    }

    /**
     * The wording moved to strings.xml so the agent's own name can sit in the
     * middle of it; the branch is what is still decidable off a device.
     *
     * It names the agent and NOT the project. The project used to be in there
     * too, and on the phone the line came out as "Message conformance-agent —
     * working in swa…" — a one-line placeholder cannot hold both, and the app
     * bar subtitle and the empty state say the project name already.
     */
    @Test
    fun thePlaceholderNamesTheAgentAndNotTheProject() {
        assertEquals(ComposerHint.Ready, composerHint(enabled = true))
        assertEquals(ComposerHint.Stopped, composerHint(enabled = false))
    }

    // --- whose name is on the screen ----------------------------------------

    /**
     * The name the program gives itself in `initialize` beats the
     * `agent_servers` key it was launched under, because the key is only what
     * the user called the entry.
     */
    @Test
    fun theConnectedAgentNamesItself() {
        assertEquals("Spettro", agentDisplayName("Spettro", "spettro-dev"))
        assertEquals("spettro-dev", agentDisplayName(null, "spettro-dev"))
        assertEquals("Agent", agentDisplayName(null, null))
    }

    /** A blank name is no name: it would print an empty gap mid-sentence. */
    @Test
    fun aBlankNameFallsThroughRatherThanBeingShown() {
        assertEquals("my-agent", agentDisplayName("  ", "my-agent"))
        assertEquals("Agent", agentDisplayName("", " "))
    }

    // --- following the tail --------------------------------------------------

    /**
     * The tail item is the last one in the list, so seeing it is seeing the
     * newest words. A list that has laid nothing out yet counts as at the tail:
     * answering otherwise would leave the very first reply un-followed.
     */
    @Test
    fun theTailIsWhereTheLastItemIsVisible() {
        assertTrue(transcriptAtTail(lastVisibleIndex = 7, totalItems = 8))
        assertFalse(transcriptAtTail(lastVisibleIndex = 3, totalItems = 8))
        assertTrue(transcriptAtTail(lastVisibleIndex = null, totalItems = 8))
        assertTrue(transcriptAtTail(lastVisibleIndex = null, totalItems = 0))
    }

    /**
     * The rule the whole auto-scroll fix rests on: a reply *growing* under the
     * fold pushes the tail item out of view with nobody touching the screen,
     * and that must not read as "the user scrolled up". Only a drag decides.
     */
    @Test
    fun growingTextDoesNotStopTheFollowButAFingerDoes() {
        // Streaming pushed the tail off screen; no finger down.
        assertTrue(followsTail(previous = true, dragging = false, atTail = false))
        // The reader drags away from the tail: stop chasing them.
        assertFalse(followsTail(previous = true, dragging = true, atTail = false))
        // …and stays stopped once the finger is lifted.
        assertFalse(followsTail(previous = false, dragging = false, atTail = false))
        // Back at the tail, by hand or by a re-tap: follow again.
        assertTrue(followsTail(previous = false, dragging = true, atTail = true))
        assertTrue(followsTail(previous = false, dragging = false, atTail = true))
    }

    // --- the seams -----------------------------------------------------------

    @Before
    fun clearSeed() {
        AgentSeams.clear()
    }

    /**
     * Two Fix presses before looking at the screen are one prompt about two
     * errors. Replacing rather than appending would silently throw the first
     * one away — and the user pressed the button, so they were told it was
     * taken.
     */
    @Test
    fun seedsAccumulateRatherThanOverwrite() {
        AgentSeams.offer("first", listOf(AgentMention.File("a.rs")))
        AgentSeams.offer("second", listOf(AgentMention.File("b.rs")))
        val seed = AgentSeams.take()
        assertEquals("first\n\nsecond", seed?.text)
        assertEquals(2, seed?.mentions?.size)
        // Taken means taken: a second drain must not paste it again.
        assertNull(AgentSeams.take())
    }

    /** Two errors in one file name it once — the agent must not read it twice. */
    @Test
    fun repeatedMentionsAreNotSentTwice() {
        AgentSeams.offer("first", listOf(AgentMention.File("a.rs")))
        AgentSeams.offer("second", listOf(AgentMention.File("a.rs")))
        assertEquals(listOf(AgentMention.File("a.rs")), AgentSeams.take()?.mentions)
    }

    @Test
    fun anEmptySeedIsNotASeed() {
        AgentSeams.offer("   ")
        assertNull(AgentSeams.take())
    }

    /**
     * The compiler's whole message, positions spelled the way the compiler
     * spells them — 1-based — and the `rustc E0308` tag the user can search
     * for. `firstLine` alone would drop rustc's "expected …, found …", which
     * is the half that says what to do.
     */
    @Test
    fun theFixPromptQuotesTheCompiler() {
        val prompt = agentFixPrompt(
            "programs/escrow/src/state.rs",
            Diagnostic(
                row = 11,
                colUtf16 = 4,
                endRow = 11,
                endColUtf16 = 9,
                severity = DiagnosticSeverity.Error,
                message = "mismatched types\nexpected `&str`, found `String`",
                source = "rustc",
                code = "E0308",
            ),
        )
        assertEquals(
            "Fix this error in programs/escrow/src/state.rs:12:5 (rustc E0308)\n\n" +
                "mismatched types\nexpected `&str`, found `String`",
            prompt,
        )
    }

    // --- conversation rows ---------------------------------------------------

    /**
     * The steering strings are matched by PREFIX, because the CLI composes a
     * tail onto each of them. Rendered as pills, they are the difference
     * between "the agent got your correction" and the agent apparently
     * muttering to itself.
     */
    @Test
    fun steeringLinesAreSystemPillsAndTheRestIsProse() {
        assertEquals("→ steering queued", systemPill("→ steering queued"))
        assertEquals(
            "✔ steering delivered (step 3)",
            systemPill("✔ steering delivered (step 3)"),
        )
        assertEquals("↻ goal iteration 3 of 10", systemPill("↻ goal iteration 3 of 10"))
        assertNull(systemPill("I read the diff across four files."))
        assertNull(systemPill(""))
    }

    /**
     * Prose stays in whole blocks so markdown still sees fenced code and
     * lists; only the pills are split out.
     */
    @Test
    fun anAnswerSplitsIntoProseAndPillsInOrder() {
        val blocks = splitSpoken(
            "I will start.\n→ steering queued\nPicking it up now.\n✔ steering delivered",
        )
        assertEquals(4, blocks.size)
        assertNull(blocks[0].pill)
        assertEquals("I will start.", blocks[0].text)
        assertEquals("→ steering queued", blocks[1].pill)
        assertEquals("Picking it up now.", blocks[2].text)
        assertEquals("✔ steering delivered", blocks[3].pill)
    }

    /** An answer with no pill in it is one block, untouched. */
    @Test
    fun ordinaryProseIsNotWalkedLineByLine() {
        val text = "one\n\n```\ntwo\n```\n"
        assertEquals(listOf(SpokenBlock(null, text)), splitSpoken(text))
    }

    /** `end_turn` is silent: a turn that ended normally has said its piece. */
    @Test
    fun onlyTheStopReasonsWorthSayingAreSaid() {
        assertNull(stopReasonNotice("end_turn"))
        assertNull(stopReasonNotice(null))
        assertEquals(
            "The agent declined to continue.",
            stopReasonNotice("refusal")?.text,
        )
        assertTrue(stopReasonNotice("refusal")!!.isError)
        assertEquals(
            "The turn hit a limit before finishing.",
            stopReasonNotice("max_turn_requests")?.text,
        )
        assertFalse(stopReasonNotice("cancelled")!!.isError)
    }

    // --- tool-call rows ------------------------------------------------------

    @Test
    fun verbsComeFromTheKindWithTwoNameExceptions() {
        assertEquals("Terminal", toolVerb(toolCall(kind = ToolKind.Execute)))
        assertEquals("Search", toolVerb(toolCall(title = "grep foo", kind = ToolKind.Search)))
        assertEquals("List", toolVerb(toolCall(title = "ls src", kind = ToolKind.Search)))
        assertEquals("Plan", toolVerb(toolCall(title = "think", kind = ToolKind.Think)))
        assertEquals(
            "Agent",
            toolVerb(toolCall(title = "[review#2] agent_run x", kind = ToolKind.Think)),
        )
        assertEquals("Frobnicate", toolVerb(toolCall(title = "frobnicate", kind = ToolKind.Other)))
    }

    /**
     * The detail is read from the ARGUMENTS, never from the title: Spettro
     * truncates the title's inline JSON at 120 characters, so a title is
     * routinely invalid JSON and lying about what a call will do.
     */
    @Test
    fun theDetailIsReadFromTheArguments() {
        assertEquals(
            "cargo test --workspace",
            toolDetail(
                toolCall(
                    title = "bash {\"command\":\"cargo test --wor",
                    rawInput = """{"command":"cargo test --workspace"}""",
                )
            ),
        )
    }

    /** An argv array is a command line, not a shape to approve. */
    @Test
    fun anArgvArrayReadsAsACommand() {
        assertEquals(
            "cargo test",
            toolDetail(toolCall(rawInput = """{"command":["cargo","test"]}""")),
        )
    }

    /**
     * A path keeps its filename. Compose's middle ellipsis eats the ends,
     * which on `programs/escrow/src/state.rs` loses the only identifying part.
     */
    @Test
    fun pathsAreShortenedFromTheFront() {
        assertEquals("…/escrow/src/state.rs", shortenPath("programs/escrow/src/state.rs"))
        assertEquals("src/state.rs", shortenPath("src/state.rs"))
        assertEquals(
            "\"bump\" in …/escrow/src/state.rs",
            toolDetail(
                toolCall(
                    kind = ToolKind.Search,
                    title = "grep",
                    rawInput = """{"path":"programs/escrow/src/state.rs","pattern":"bump"}""",
                )
            ),
        )
    }

    /** Newlines collapse, because a row is one line and a wrap makes it shiver. */
    @Test
    fun newlinesCollapseToOneGlyph() {
        assertEquals("a⏎b", oneLine("a\nb"))
        assertEquals("a⏎b", oneLine("a\r\nb\n"))
    }

    /** Nothing named in the arguments still beats printing raw JSON. */
    @Test
    fun unnamedArgumentsBecomeCompactPairs() {
        assertEquals(
            "depth: 2",
            toolDetail(toolCall(title = "weird", rawInput = """{"depth":2}""")),
        )
    }

    // --- approvals and forms -------------------------------------------------

    /**
     * Only a call that is *stopped* and has options is an approval. A pending
     * call with no options is work in flight, and raising a sheet over it
     * would ask the user to answer a question nobody asked.
     */
    @Test
    fun onlyStoppedCallsWithOptionsAreApprovals() {
        val entries = listOf(
            toolCall(id = "a", status = ToolCallStatus.InProgress),
            toolCall(
                id = "b",
                status = ToolCallStatus.WaitingForConfirmation,
                options = listOf(allow()),
            ),
            toolCall(id = "c", status = ToolCallStatus.WaitingForConfirmation),
            AgentEntry.User("hello"),
        )
        assertEquals(listOf("b"), pendingApprovals(entries).map { it.id })
    }

    /**
     * W-10, and the routing rule the whole permission sheet depends on: a
     * prompt carrying `spettro.app/question` is a *form*, and Allow/Deny over
     * "Which database?" turns a product question into a security decision.
     */
    @Test
    fun aWalkedFormIsSniffedOutOfThePermissionMeta() {
        val call = toolCall(
            id = "ask-1",
            turn = 3,
            status = ToolCallStatus.WaitingForConfirmation,
            options = listOf(allow()),
            permissionMeta = """
                {"spettro.app/question":{"version":2,"questions":[
                  {"id":"db","question":"Which database?","options":[
                    {"id":"pg","label":"Postgres"}]}]}}
            """.trimIndent(),
        )
        val form = walkedQuestion(call)
        assertEquals("3:ask-1", form?.id)
        assertEquals(SpettroQuestion.Transport.Permission, form?.transport)
        assertEquals("Which database?", form?.questions?.single()?.question)
    }

    /** An ordinary approval has no form hiding in it, and must not grow one. */
    @Test
    fun anOrdinaryApprovalHasNoForm() {
        assertNull(walkedQuestion(toolCall(permissionMeta = """{"spettro.app/other":1}""")))
        assertNull(walkedQuestion(toolCall(permissionMeta = "not json at all")))
        assertNull(walkedQuestion(toolCall()))
    }

    /**
     * The session-less poll repeats forms the session already reports; drawing
     * the same form twice would ask the user to answer it twice, and the
     * second answer is refused.
     */
    @Test
    fun aQuestionIsNeverListedTwice() {
        val ask = SpettroQuestion.parseAll(
            org.json.JSONArray(
                """[{"id":"question-1","session":7,"payload":{"version":1,"question":"a?"}}]"""
            )
        )
        val loose = SpettroQuestion.parseAll(
            org.json.JSONArray(
                """
                [{"id":"question-1","session":null,"payload":{"version":1,"question":"a?"}},
                 {"id":"question-2","session":null,"payload":{"version":1,"question":"b?"}}]
                """.trimIndent()
            )
        )
        assertEquals(listOf("question-1", "question-2"), mergedQuestions(ask, loose).map { it.id })
    }

    // --- the small print -----------------------------------------------------

    @Test
    fun theReviewBarCountsFilesAndDisappearsAtZero() {
        assertNull(reviewLabel(0))
        assertNull(reviewLabel(-1))
        assertEquals("1 file changed", reviewLabel(1))
        assertEquals("2 files changed", reviewLabel(2))
    }

    /**
     * The bar names the CONVERSATION and falls back to the project only when
     * the thread has no name yet — a title of `thragg-ide` with one project
     * open says nothing the Projects control beside it does not already say.
     *
     * (`tickerLabel` used to be tested here. It is gone: the elapsed/token
     * readout is `ui/components/RunTicker.kt` now, pinned to the status strip
     * instead of scrolling away at the transcript's tail, and its two halves
     * are pinned by `RunTickerFormatTest`.)
     */
    @Test
    fun theTitleNamesTheThreadAndFallsBackToTheProject() {
        assertEquals("Fix the resume crash", barTitle("Fix the resume crash", "thragg-ide"))
        assertEquals("thragg-ide", barTitle(null, "thragg-ide"))
        assertEquals("thragg-ide", barTitle("  ", "thragg-ide"))
        assertEquals("No project", barTitle(null, null))
    }

    /**
     * The second line is identity plus the context the title gave up, and it
     * carries NO mode: the mode was on the bar, on the status strip and on the
     * composer's summary row at the same time, and this is the copy that had
     * nothing attached to it. It also refuses to print the project twice —
     * with an unnamed thread the title is already the project.
     */
    @Test
    fun theSubtitleNamesTheAgentAndTheProjectTheTitleGaveUp() {
        assertEquals("Spettro · thragg-ide", barSubtitle("Spettro", "thragg-ide", "Fix the crash"))
        assertEquals("Spettro", barSubtitle("Spettro", "thragg-ide", null))
        assertEquals("Spettro", barSubtitle("Spettro", "thragg-ide", " "))
        assertEquals("Agent", barSubtitle("Agent", null, "Fix the crash"))
    }

    /**
     * The status strip reports run state, and the mode is not run state: a
     * band that stays up for a pill the composer already shows is 37 dp of an
     * 890 dp column spent on nothing, for the whole of every fresh thread.
     */
    @Test
    fun theStatusStripStandsDownForTheModeAlone() {
        assertFalse(stripReports(busy = false, hasPlan = false, hasUsage = false))
        assertTrue(stripReports(busy = true, hasPlan = false, hasUsage = false))
        assertTrue(stripReports(busy = false, hasPlan = true, hasUsage = false))
        assertTrue(stripReports(busy = false, hasPlan = false, hasUsage = true))
    }

    /**
     * The attention bar counts what it can actually open, and says nothing at
     * all when there is nothing parked — it is a band that exists only while
     * the turn is stopped.
     */
    @Test
    fun theAttentionBarNamesTheAgentAndCountsWhatIsParked() {
        assertNull(attentionLabel("Spettro", 0))
        assertNull(attentionLabel("Spettro", -1))
        assertEquals("Spettro is waiting on you — 1 request", attentionLabel("Spettro", 1))
        assertEquals("Spettro is waiting on you — 3 requests", attentionLabel("Spettro", 3))
    }

    /**
     * The plan unfold and the run strip are *hidden* with the keyboard up,
     * never shrunk: this layout animates opacity and transform only, and these
     * two repaint several times a second during a fan-out. The 36 dp status
     * strip is not one of them — it is one of the three bands that always
     * survive.
     */
    @Test
    fun theSecondaryBandsCollapseForTheKeyboard() {
        assertTrue(showsSecondaryBands(imeVisible = false))
        assertFalse(showsSecondaryBands(imeVisible = true))
    }

    /**
     * A model id may hold a quote; a hand-built `"$value"` would send
     * malformed JSON and the agent's parse error names nothing the user did.
     */
    @Test
    fun configValuesAreQuotedAsJson() {
        assertEquals("\"plan\"", configValueJson("plan"))
        assertEquals("\"a\\\"b\"", configValueJson("a\"b"))
        assertEquals("true", configValueJson(true))
        assertEquals("false", configValueJson(false))
    }

    /**
     * Every empty section names the thing that would fill it. "Nothing
     * matches" over a section that *cannot* match — Symbols with no file open
     * — is what makes a working feature look broken.
     */
    @Test
    fun anEmptyPickerSectionSaysWhatWouldFillIt() {
        assertEquals(
            "Open a project first.",
            emptySectionLine(
                to.eyed.thragg.ui.agent.MentionSection.Files,
                hasProject = false,
                searching = false,
            ),
        )
        assertEquals(
            "Select something in the editor first.",
            emptySectionLine(
                to.eyed.thragg.ui.agent.MentionSection.Selection,
                hasProject = true,
                searching = false,
            ),
        )
        assertEquals(
            "Nothing matches.",
            emptySectionLine(
                to.eyed.thragg.ui.agent.MentionSection.Files,
                hasProject = true,
                searching = false,
            ),
        )
    }

    @Test
    fun theSlashPaletteMatchesOnThePrefix() {
        val commands = listOf(
            to.eyed.thragg.core.AgentCommand("goal", "work autonomously", "<objective>"),
            to.eyed.thragg.core.AgentCommand("compact", "summarize history", null),
            to.eyed.thragg.core.AgentCommand("gopher", "", null),
        )
        assertEquals(listOf("goal", "gopher"), commandMatches(commands, "go").map { it.name })
        assertEquals(3, commandMatches(commands, "").size)
        assertEquals(2, commandMatches(commands, "", limit = 2).size)
    }

    // --- against the real fake agent ----------------------------------------

    /**
     * The payload below is **captured verbatim** from a live handshake with
     * `tools/conformance-agent.py` (initialize with the top-level `_meta`
     * mirror, then `/question`). It is here because the shapes this screen
     * routes on are the ones an agent actually sends, not the ones the spec
     * describes: note `version: 4` rather than the `2` the prose implies, a
     * top-level `question` *beside* the `questions[]` array, and a question
     * whose empty `options` make it typed-answer by definition.
     */
    @Test
    fun theConformanceAgentsFormRoutesThroughTheQuestionSheet() {
        val payload = """
            {"version":4,"sessionId":"conf-1",
             "question":"How should the conformance agent proceed?",
             "context":"Asked as one form rather than as a walk.",
             "allowCustomInput":true,
             "questions":[
               {"id":"branch","question":"Which branch?","options":[
                 {"id":"main","label":"main"},{"id":"dev","label":"development"}]},
               {"id":"note","question":"Anything to add?","options":[]}]}
        """.trimIndent()
        val form = SpettroQuestion.parseAll(
            org.json.JSONArray("""[{"id":"question-1","session":7,"payload":$payload}]""")
        ).single()

        assertEquals(4, form.version)
        assertEquals(SpettroQuestion.Transport.Ask, form.transport)
        assertEquals(2, form.questions.size)
        // Nothing to pick means there is only one way to answer it.
        assertTrue(form.questions[1].allowCustomInput)

        // One answered, one left alone: the untouched question is OMITTED, so
        // the model is told nobody answered it rather than being handed the
        // recommendation as though it were the user's decision.
        val answers = to.eyed.thragg.ui.agent.spettro.QuestionForm.answers(
            form,
            mapOf("branch" to to.eyed.thragg.core.QuestionDraft(selected = listOf("main"))),
        )
        assertEquals(1, answers.size)
        val wire = to.eyed.thragg.core.SpettroAnswers.encode(answers, form.version)
        assertTrue(wire, wire.contains("\"answers\""))
        assertTrue(wire, wire.contains("\"optionId\":\"main\""))
    }

    /**
     * …and the same agent's `session/request_permission` — captured from the
     * same run — carries no `_meta` at all, so it must stay an approval. This
     * is the negative half of the W-10 sniff: routing every prompt through the
     * question sheet would turn "Allow this edit?" into a product survey.
     */
    @Test
    fun theConformanceAgentsPermissionStaysAnApproval() {
        val call = toolCall(
            id = "t-1",
            title = "Edit AGENT_NOTE.md",
            kind = ToolKind.Edit,
            status = ToolCallStatus.WaitingForConfirmation,
            options = listOf(
                PermissionOption("allow", "Allow", "allow_once"),
                PermissionOption("reject", "Reject", "reject_once"),
            ),
        )
        assertNull(walkedQuestion(call))
        assertEquals(listOf("t-1"), pendingApprovals(listOf(call)).map { it.id })
    }

    /**
     * The failed-turn card sheds Spettro's transport wrapper and keeps the
     * provider's words — and passes through anything it cannot parse, because
     * an error we fail to prettify must still be an error we show.
     */
    @Test
    fun `turn errors are unwrapped for people`() {
        val wire = "Internal error: {\n \"error\": \"plan agent: agent call " +
            "failed: unauthorized: Not Enough Credits\"\n}"
        assertEquals(
            "plan agent: agent call failed: unauthorized: Not Enough Credits",
            humanTurnError(wire),
        )
        assertEquals("The socket closed", humanTurnError("The socket closed"))
        assertEquals("Internal error: { not json", humanTurnError("Internal error: { not json"))
        assertEquals("""{"code": 500}""", humanTurnError("""{"code": 500}"""))
    }
}
