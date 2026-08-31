package to.eyed.seeker.code.ui.agent.spettro

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.seeker.code.core.AgentEntry
import to.eyed.seeker.code.core.PermissionOption
import to.eyed.seeker.code.core.ToolCallStatus
import to.eyed.seeker.code.core.ToolKind

/**
 * What the approval sheet reads out of a stopped tool call.
 *
 * "Edit notes.md" does not say what the edit is and "Run shell command" does
 * not say which one, so the sheet's honesty lives entirely in these functions:
 * pick the right field out of the call's own arguments, group the buttons so a
 * rejection is never the one that scrolls off, and tag the reply as an
 * *answer* only when it really is one.
 */
class PermissionPromptTest {

    private fun call(
        id: String = "call-1",
        title: String = "Run shell command",
        kind: ToolKind = ToolKind.Execute,
        options: List<PermissionOption> = emptyList(),
        rawInput: String? = null,
        rawInputOpen: String? = null,
        permissionMeta: String? = null,
    ) = AgentEntry.ToolCall(
        id = id,
        title = title,
        kind = kind,
        status = ToolCallStatus.WaitingForConfirmation,
        options = options,
        content = emptyList(),
        locations = emptyList(),
        rawInput = rawInput,
        rawInputOpen = rawInputOpen,
        permissionMeta = permissionMeta,
        turn = 3,
    )

    private fun option(id: String, kind: String, recommended: Boolean = false, custom: Boolean = false) =
        PermissionOption(id, id, kind, isRecommended = recommended, isCustomInput = custom)

    // --- button order --------------------------------------------------------

    /**
     * Allows above rejects so a mis-tap in a column is never destructive, and
     * wire order kept *inside* each group because that ordering is the agent's
     * own judgement about which answer it expects.
     */
    @Test
    fun allowsGroupAboveRejectsAndKeepTheirWireOrder() {
        val ordered = PermissionPrompt.order(
            listOf(
                option("reject-once", "reject_once"),
                option("allow-once", "allow_once"),
                option("reject-always", "reject_always"),
                option("allow-always", "allow_always"),
            ),
        )
        assertEquals(
            listOf("allow-once", "allow-always", "reject-once", "reject-always"),
            ordered.map { it.id },
        )
    }

    @Test
    fun anAllOrNothingListIsLeftAlone() {
        val options = listOf(option("a", "allow_once"), option("b", "allow_always"))
        assertEquals(options, PermissionPrompt.order(options))
    }

    // --- the mono block ------------------------------------------------------

    @Test
    fun theCommandIsQuotedFromTheOpeningArguments() {
        val subject = PermissionPrompt.subject(
            call(
                rawInputOpen = """{"command":"cargo test --workspace --all-features"}""",
                // A finish update can already have rewritten this to a
                // summary (W-09); the opening args are the ones we stopped on.
                rawInput = """{"command":"<summarised>"}""",
            ),
        )
        assertEquals("cargo test --workspace --all-features", subject)
    }

    @Test
    fun argvArrivingAsAnArrayIsShownAsACommandLine() {
        val subject = PermissionPrompt.subject(
            call(rawInput = """{"command":["cargo","test","--workspace"]}"""),
        )
        assertEquals("cargo test --workspace", subject)
    }

    @Test
    fun aFetchQuotesItsUrl() {
        val subject = PermissionPrompt.subject(
            call(kind = ToolKind.Fetch, rawInput = """{"url":"https://example.test/x"}"""),
        )
        assertEquals("https://example.test/x", subject)
    }

    @Test
    fun anEditQuotesItsPath() {
        assertEquals(
            "src/main.rs",
            PermissionPrompt.subject(call(kind = ToolKind.Edit, rawInput = """{"path":"src/main.rs"}""")),
        )
    }

    /** No quotable field, no empty box: the title stands alone. */
    @Test
    fun argumentsWithNothingQuotableGiveNoBlock() {
        assertNull(PermissionPrompt.subject(call(rawInput = """{"thinking":true}""")))
        assertNull(PermissionPrompt.subject(call(rawInput = "not json at all")))
        assertNull(PermissionPrompt.subject(call()))
    }

    /** A nested object is a shape, not a command; showing it approves nothing. */
    @Test
    fun aStructuredCommandIsNotFlattenedIntoTheBlock() {
        assertNull(PermissionPrompt.subject(call(rawInput = """{"command":{"argv":["ls"]}}""")))
    }

    @Test
    fun theAgentsOwnSentenceIsTheExplanation() {
        assertEquals(
            "verify the steering refactor",
            PermissionPrompt.explanation(
                call(rawInput = """{"command":"cargo test","description":"verify the steering refactor"}"""),
            ),
        )
        assertNull(PermissionPrompt.explanation(call(rawInput = """{"command":"cargo test"}""")))
    }

    // --- icons and the durability note --------------------------------------

    @Test
    fun theCompactionPromptGetsItsOwnIcon() {
        val compaction = call(id = "compact-1", kind = ToolKind.Other, title = "Context nearly full")
        assertTrue(PermissionPrompt.isCompaction(compaction))
        assertEquals("◍", PermissionPrompt.glyph(compaction))
    }

    @Test
    fun kindsChooseTheIcon() {
        assertEquals("▸", PermissionPrompt.glyph(call(kind = ToolKind.Execute)))
        assertEquals("?", PermissionPrompt.glyph(call(kind = ToolKind.Think)))
        assertEquals("⛨", PermissionPrompt.glyph(call(kind = ToolKind.Edit)))
    }

    /**
     * The two grant files are different, and a note that names the wrong one
     * leaves somebody unable to find the grant they want to revoke.
     */
    @Test
    fun theDurabilityNoteNamesTheRightFile() {
        assertTrue(
            PermissionPrompt.durabilityNote(call(kind = ToolKind.Execute))
                .contains(".spettro/allowed_commands.json"),
        )
        assertTrue(
            PermissionPrompt.durabilityNote(call(kind = ToolKind.Fetch))
                .contains(".spettro/allowed_network.json"),
        )
    }

    // --- the reply -----------------------------------------------------------

    /** An ordinary approval is an approval: nothing extra goes back with it. */
    @Test
    fun anOrdinaryApprovalCarriesNoAnswerMeta() {
        assertEquals("", PermissionPrompt.answerMeta(call(), option("allow-once", "allow_once")))
    }

    /**
     * Spettro marks its preferred option on ordinary permission prompts too,
     * so `isRecommended` must not be read as "this is really a question" —
     * that would file a security approval as a product preference.
     */
    @Test
    fun aRecommendedOptionIsStillJustAnApproval() {
        assertEquals(
            "",
            PermissionPrompt.answerMeta(call(), option("allow-once", "allow_once", recommended = true)),
        )
    }

    @Test
    fun theCustomInputOptionCarriesTheTypedText() {
        val meta = PermissionPrompt.answerMeta(
            call(),
            option("custom", "allow_once", custom = true),
            "  use the existing MySQL box  ",
        )
        val answer = JSONObject(meta).getJSONObject("spettro.app/questionAnswer")
        assertEquals("custom", answer.getString("kind"))
        assertEquals("use the existing MySQL box", answer.getString("text"))
    }

    @Test
    fun aWalkedQuestionTagsTheChosenOption() {
        val walked = call(permissionMeta = """{"spettro.app/question":{"version":1,"question":"Which?"}}""")
        assertTrue(PermissionPrompt.isWalkedQuestion(walked))
        val answer = JSONObject(PermissionPrompt.answerMeta(walked, option("opt-1", "allow_once")))
            .getJSONObject("spettro.app/questionAnswer")
        assertEquals("option", answer.getString("kind"))
        assertEquals("opt-1", answer.getString("optionId"))
    }

    @Test
    fun unreadableMetaIsNotAWalkedQuestion() {
        assertFalse(PermissionPrompt.isWalkedQuestion(call(permissionMeta = "{oops")))
        assertFalse(PermissionPrompt.isWalkedQuestion(call(permissionMeta = """{"other":1}""")))
        assertFalse(PermissionPrompt.isWalkedQuestion(call()))
    }

    @Test
    fun theHeadlineIsVerbatim() {
        assertEquals("Spettro needs your approval", PermissionPrompt.HEADLINE)
    }
}
