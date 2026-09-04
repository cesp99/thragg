package to.eyed.thragg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the engine's agent contract.
 *
 * The delta merge is the part worth testing hardest: the engine hands back
 * only the rows whose revision moved, so getting the merge wrong shows up as a
 * conversation that silently stops updating, or as rows in the wrong places —
 * neither of which looks like a crash.
 */
class AgentSessionTest {

    private fun entriesJson(revision: Long, total: Int, vararg rows: String) =
        """{"revision":$revision,"total":$total,"entries":[${rows.joinToString(",")}]}"""

    private fun user(index: Int, rev: Long, text: String) =
        """{"index":$index,"rev":$rev,"kind":"user","markdown":${quote(text)}}"""

    private fun assistant(index: Int, rev: Long, text: String, thought: Boolean = false) =
        """{"index":$index,"rev":$rev,"kind":"assistant","chunks":[
           {"thought":$thought,"markdown":${quote(text)}}]}"""

    private fun quote(text: String) = org.json.JSONObject.quote(text)

    // --- the session state ---------------------------------------------------

    @Test
    fun readsAWholeSessionState() {
        val state = AgentSessionState.parse(
            """
            {"version":12,"project":1,"phase":"running","error":null,"needs_auth":false,
             "title":"Fixing the parser","stop_reason":null,"entry_count":9,
             "plan":[{"content":"Read the file","priority":"high","status":"completed"},
                     {"content":"Fix it","priority":"medium","status":"in_progress"}],
             "usage":{"used":1200,"size":200000},
             "modes":{"currentModeId":"default","availableModes":[
                {"id":"default","name":"Always Ask","description":"Ask before editing"},
                {"id":"acceptEdits","name":"Accept Edits"}]},
             "commands":[],
             "agent":{"name":"Claude Code","agent_name":"claude-code","agent_version":"0.16.2",
                      "auth_methods":[{"methodId":"login","name":"Log in"}]}}
            """.trimIndent()
        )

        assertEquals(12L, state.version)
        assertEquals(AgentPhase.Running, state.phase)
        assertTrue(state.isBusy)
        assertTrue(state.canPrompt)
        assertEquals("Fixing the parser", state.title)
        assertEquals(9, state.entryCount)
        assertEquals(2, state.plan.size)
        assertEquals("completed", state.plan[0].status)
        assertEquals(1200L, state.usage?.used)
        assertEquals(0.006f, state.usage?.fraction!!, 0.0001f)
        assertEquals("default", state.modes?.currentId)
        assertEquals("Always Ask", state.modes?.current?.name)
        assertEquals(2, state.modes?.available?.size)
        assertEquals("Claude Code", state.agent?.name)
        assertEquals("0.16.2", state.agent?.agentVersion)
        assertEquals("login", state.agent?.authMethods?.single()?.id)
    }

    /**
     * `optString` on a JSON null hands back the **string** "null" on Android
     * (CONVENTIONS § Traps). Every nullable field here is a real null in the
     * engine's JSON, so this is the difference between an empty title and the
     * word "null" on screen.
     */
    /**
     * The composer's `/` popup and the selector chips are drawn from these —
     * ACP's own wire shapes, as the engine relays them: `commands` from
     * `available_commands_update`, `configOptions` from `session/new` and
     * `config_option_update` (`type` tags the kind, a select's `options`
     * may arrive flat or grouped).
     */
    @Test
    fun readsCommandsAndConfigOptions() {
        val state = AgentSessionState.parse(
            """
            {
              "version": 3, "phase": "ready",
              "commands": [
                {"name": "plan", "description": "Show a plan"},
                {"name": "echo", "description": "Repeat", "input": {"hint": "text"}},
                {"description": "nameless is dropped"}
              ],
              "configOptions": [
                {"id": "model", "name": "Model", "type": "select",
                 "currentValue": "two",
                 "options": [
                   {"value": "one", "name": "One"},
                   {"name": "Group", "options": [{"value": "two", "name": "Two"}]}
                 ]},
                {"id": "verbose", "name": "Verbose", "type": "boolean", "currentValue": true},
                {"id": "odd", "name": "Odd", "type": "slider", "currentValue": 3}
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("plan", "echo"), state.commands.map { it.name })
        assertEquals("text", state.commands[1].inputHint)
        assertEquals(null, state.commands[0].inputHint)

        // The slider this build cannot render is left out, not drawn broken.
        assertEquals(listOf("model", "verbose"), state.configOptions.map { it.id })
        val model = state.configOptions[0]
        assertEquals("select", model.kind)
        // Grouped-ness is decided by the FIRST element alone (SPETTRO.md
        // W-13), which this list's is not — so the list is flat, the
        // group-shaped element carries no `value` and is dropped, and the
        // current value prints as itself rather than as a name we cannot
        // resolve. Splicing a half-grouped list would silently reorder it.
        assertFalse(model.isGrouped)
        assertEquals(listOf("one"), model.values.map { it.id })
        assertEquals("two", model.currentLabel)
        val verbose = state.configOptions[1]
        assertEquals("boolean", verbose.kind)
        assertEquals(true, verbose.currentBool)
        assertEquals("On", verbose.currentLabel)
    }

    @Test
    fun aJsonNullIsANullAndNotTheWordNull() {
        val state = AgentSessionState.parse(
            """
            {"version":3,"phase":"ready","error":null,"needs_auth":false,"title":null,
             "stop_reason":null,"entry_count":0,"plan":[],"usage":null,"modes":null,
             "agent":{"name":"Gemini","agent_name":null,"agent_version":null,
                      "auth_methods":[],"starting":false,"error":null}}
            """.trimIndent()
        )
        assertNull(state.title)
        assertNull(state.error)
        assertNull(state.stopReason)
        assertNull(state.usage)
        assertNull(state.modes)
        assertNull(state.agent?.agentName)
        assertNull(state.agent?.agentVersion)
        assertNull(state.agent?.error)
    }

    /** A forgotten session, and anything unparseable, read as "nothing yet". */
    @Test
    fun aForgottenSessionReadsAsNothing() {
        assertEquals(AgentSessionState.NONE, AgentSessionState.parse("null"))
        assertEquals(AgentSessionState.NONE, AgentSessionState.parse(""))
        assertEquals(AgentSessionState.NONE, AgentSessionState.parse("not json at all"))
    }

    /** An unavailable session refuses prompts and carries the reason. */
    @Test
    fun anUnavailableSessionSaysWhy() {
        val state = AgentSessionState.parse(
            """{"version":2,"phase":"unavailable","needs_auth":false,
                "error":"sh: 1: claude-code-acp: not found","entry_count":0,
                "plan":[],"agent":null}"""
        )
        assertEquals(AgentPhase.Unavailable, state.phase)
        assertFalse(state.canPrompt)
        assertTrue(Agents.looksLikeMissingProgram(state.error))
    }

    // --- the entries ---------------------------------------------------------

    @Test
    fun readsEachKindOfRow() {
        val conversation = AgentConversation().apply(
            entriesJson(
                4, 3,
                user(0, 2, "fix the parser"),
                assistant(1, 3, "Looking now"),
                """{"index":2,"rev":4,"kind":"tool_call","id":"t1","title":"Edit main.rs",
                    "tool_kind":"edit","status":"waiting_for_confirmation",
                    "options":[{"optionId":"yes","name":"Allow","kind":"allow_once"},
                               {"optionId":"no","name":"Deny","kind":"reject_once"}],
                    "content":[{"type":"markdown","markdown":"about to edit"},
                               {"type":"diff","diff":{"path":"src/main.rs","original":null,
                                "is_binary":false,"hunks":[{"old_start":1,"new_start":1,
                                "heading":"","lines":[
                                  {"kind":"-","text":"old","old_line":1,"new_line":0},
                                  {"kind":"+","text":"new","old_line":0,"new_line":1}]}]}}],
                    "locations":[{"path":"src/main.rs","line":4}]}""",
            )
        )

        assertEquals(3, conversation.entries.size)
        assertEquals(4L, conversation.revision)
        assertEquals(AgentEntry.User("fix the parser"), conversation.entries[0])

        val reply = conversation.entries[1] as AgentEntry.Assistant
        assertEquals("Looking now", reply.spoken)
        assertEquals("", reply.thoughts)

        val call = conversation.entries[2] as AgentEntry.ToolCall
        assertEquals("t1", call.id)
        assertEquals("Edit main.rs", call.title)
        // `tool_kind`, not `kind` — the row's own tag says `tool_call`.
        assertEquals(ToolKind.Edit, call.kind)
        assertEquals(ToolCallStatus.WaitingForConfirmation, call.status)
        assertEquals(listOf("yes", "no"), call.options.map { it.id })
        assertTrue(call.options[0].isAllow)
        assertFalse(call.options[1].isAllow)
        assertEquals(2, call.content.size)
        assertEquals("src/main.rs", call.diffs.single().path)
        assertEquals(1, call.diffs.single().added)
        assertEquals(1, call.diffs.single().removed)
        assertEquals(AgentLocation("src/main.rs", 4), call.locations.single())
    }

    /** Thoughts are kept apart so the panel can fold them away. */
    @Test
    fun thoughtsStayApartFromTheReply() {
        val conversation = AgentConversation().apply(
            entriesJson(
                2, 1,
                """{"index":0,"rev":2,"kind":"assistant","chunks":[
                   {"thought":true,"markdown":"hmm, the parser"},
                   {"thought":false,"markdown":"I will fix it"}]}""",
            )
        )
        val reply = conversation.entries.single() as AgentEntry.Assistant
        assertEquals("I will fix it", reply.spoken)
        assertEquals("hmm, the parser", reply.thoughts)
    }

    /**
     * A row shape this build does not know becomes a row, not a gap.
     *
     * Skipping it left a hole; a hole means "out of step with the engine",
     * which asks the poller to re-read from the start; the re-read returns the
     * same unknown row. That is an empty transcript and eight re-reads a
     * second, for ever, with no error anywhere.
     */
    @Test
    fun anUnknownRowKindBecomesAPlaceholderRatherThanAHole() {
        val conversation = AgentConversation().apply(
            entriesJson(
                3, 2,
                user(0, 2, "hello"),
                """{"index":1,"rev":3,"kind":"from_the_future"}""",
            )
        )
        assertEquals(2, conversation.entries.size)
        assertEquals(AgentEntry.User("hello"), conversation.entries[0])
        assertEquals(AgentEntry.Unsupported, conversation.entries[1])
        assertEquals("the transcript is not reset", 3L, conversation.revision)
    }

    /** And re-reading it converges instead of looping. */
    @Test
    fun anUnknownRowDoesNotWedgeTheTranscript() {
        val payload = entriesJson(3, 1, """{"index":0,"rev":3,"kind":"from_the_future"}""")
        var conversation = AgentConversation()
        repeat(3) { conversation = conversation.apply(payload) }
        assertEquals(1, conversation.entries.size)
        assertEquals(
            "a revision of 0 would ask the poller to start again, for ever",
            3L,
            conversation.revision,
        )
    }

    // --- the merge -----------------------------------------------------------

    /**
     * The whole point of the delta: a second poll carries only what moved, and
     * the rows it does not mention keep what they had.
     */
    @Test
    fun aDeltaUpdatesOneRowAndLeavesTheRestAlone() {
        val first = AgentConversation().apply(
            entriesJson(3, 2, user(0, 2, "go"), assistant(1, 3, "work"))
        )
        assertEquals(2, first.entries.size)

        val second = first.apply(entriesJson(4, 2, assistant(1, 4, "working harder")))
        assertEquals(2, second.entries.size)
        assertEquals(AgentEntry.User("go"), second.entries[0])
        assertEquals("working harder", (second.entries[1] as AgentEntry.Assistant).spoken)
        assertEquals(4L, second.revision)
    }

    /** A row appended past the end is grown into rather than dropped. */
    @Test
    fun aNewRowExtendsTheTranscript() {
        val conversation = AgentConversation()
            .apply(entriesJson(2, 1, user(0, 2, "one")))
            .apply(entriesJson(3, 2, assistant(1, 3, "two")))
        assertEquals(2, conversation.entries.size)
        assertEquals("two", (conversation.entries[1] as AgentEntry.Assistant).spoken)
    }

    /**
     * A refusal truncates the transcript back past the prompt it refused, so
     * `total` shrinks. Everything we hold is then suspect and the only honest
     * answer is to start again — which a revision of 0 asks the poller to do.
     */
    @Test
    fun aShrinkingTranscriptResetsRatherThanLeavingStaleRows() {
        val full = AgentConversation().apply(
            entriesJson(3, 2, user(0, 2, "refused"), assistant(1, 3, "no"))
        )
        val after = full.apply(entriesJson(5, 0))
        assertTrue(after.entries.isEmpty())
        assertEquals("revision 0 means: read me again from the start", 0L, after.revision)
    }

    /**
     * A row that exists but no delta has ever described means we are out of
     * step; drawing a blank message where a real one belongs would be worse
     * than starting again.
     */
    @Test
    fun aRowNoDeltaEverDescribedResetsTheTranscript() {
        // total says three rows, but only the first is described and we hold
        // nothing — so rows 1 and 2 are holes.
        val conversation = AgentConversation().apply(
            entriesJson(9, 3, user(0, 9, "only me"))
        )
        assertTrue(conversation.entries.isEmpty())
        assertEquals(0L, conversation.revision)
    }

    /** Rubbish on the wire leaves what we already had. */
    @Test
    fun anUnreadablePayloadChangesNothing() {
        val held = AgentConversation().apply(entriesJson(2, 1, user(0, 2, "kept")))
        assertEquals(held, held.apply("null"))
        assertEquals(held, held.apply("<html>"))
    }
}
