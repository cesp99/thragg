package to.eyed.seeker.code.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Spettro half of the session snapshot.
 *
 * These are the fields that gate whole surfaces — the extension
 * advertisement, the four-state Ultra rule, the opening arguments of a tool
 * call — and every one of them fails silently when it is read wrong: a
 * missing gate is a generic agent panel, a flattened model list is an
 * unnavigable column, and a lost `rawInputOpen` is a workflow card with no
 * plan in it.
 */
class SpettroSessionStateTest {

    private fun state(body: String) = AgentSessionState.parse(body)

    private companion object {
        const val ULTRA_ON = "\"currentValue\":true}]}"
        const val ULTRA_OFF = "\"currentValue\":false}]}"
        const val ASK_FIRST = "\"currentValue\":\"ask-first\""
        const val RESTRICTED = "\"currentValue\":\"restricted\""
    }

    // --- the gate ------------------------------------------------------------

    @Test
    fun readsTheExtensionAdvertisement() {
        val parsed = state(
            """
            {"version":3,"phase":"ready","agent":{"name":"Spettro","agent_name":"spettro",
             "spettroExtensions":{"version":4,
               "methods":["_spettro/models/list","_spettro/account/status"],
               "clientMethods":["_spettro/question/ask"]}}}
            """.trimIndent()
        )
        val spettro = requireNotNull(parsed.spettro)
        assertEquals(4, spettro.version)
        assertTrue(spettro.hasWorkflowAuthoring)
        assertTrue(spettro.serves("_spettro/models/list"))
        assertFalse(spettro.serves("_spettro/workflow/list"))
        assertEquals(setOf("_spettro/question/ask"), spettro.clientMethods)
    }

    @Test
    fun aGenericAgentHasNoSpettroSurfaceAndThatIsNotAnError() {
        val parsed = state("""{"version":1,"phase":"ready","agent":{"name":"Some Agent"}}""")
        assertNull(parsed.spettro)
        assertEquals(0, parsed.needsUser)
    }

    @Test
    fun anOlderCliAdvertisesAnOlderVersion() {
        val parsed = state(
            """{"phase":"ready","agent":{"spettroExtensions":{"version":3,"methods":[]}}}"""
        )
        assertFalse(requireNotNull(parsed.spettro).hasWorkflowAuthoring)
    }

    // --- config options ------------------------------------------------------

    private val fiveOptions = """
        {"phase":"ready","configOptions":[
          {"id":"mode","name":"Mode","type":"select","category":"mode","currentValue":"plan",
           "options":[{"name":"Plan","value":"plan"},{"name":"Build","value":"build"}]},
          {"id":"model","name":"Model","type":"select","category":"model",
           "currentValue":"anthropic:claude-sonnet-4-5","options":[
             {"group":"anthropic","name":"Anthropic","options":[
               {"name":"Claude Sonnet 4.5","value":"anthropic:claude-sonnet-4-5"},
               {"name":"Claude Opus 4.1","value":"anthropic:claude-opus-4-1"}]},
             {"group":"openai","name":"OpenAI","options":[
               {"name":"GPT-5","value":"openai:gpt-5"}]}]},
          {"id":"permission","name":"Permission","type":"select","currentValue":"ask-first",
           "options":[{"name":"Ask first","value":"ask-first"},
                      {"name":"Restricted","value":"restricted"}]},
          {"id":"thinking","name":"Thinking","type":"select","category":"thought_level",
           "currentValue":"medium","options":[{"name":"Medium","value":"medium"}]},
          {"id":"ultra","name":"Ultra","type":"boolean","currentValue":true}]}
    """.trimIndent()

    @Test
    fun keepsGroupsAndCategoriesInsteadOfFlatteningThem() {
        val toolbar = state(fiveOptions).toolbar
        val model = requireNotNull(toolbar.model)
        assertTrue(model.isGrouped)
        assertEquals(listOf("Anthropic", "OpenAI"), model.groups.map { it.name })
        assertEquals(listOf("anthropic", "openai"), model.groups.map { it.id })
        assertEquals(3, model.choices.size)
        assertEquals("Claude Sonnet 4.5", model.currentLabel)
        assertEquals("Anthropic", requireNotNull(model.currentGroup).name)
        assertEquals("model", model.category)
        // `permission` and `ultra` deliberately carry no category.
        assertNull(requireNotNull(toolbar.permission).category)
        assertEquals("mode", requireNotNull(toolbar.mode).category)
        assertEquals("thought_level", requireNotNull(toolbar.thinking).category)
    }

    @Test
    fun aFlatSelectStaysFlat() {
        val mode = requireNotNull(state(fiveOptions).toolbar.mode)
        assertFalse(mode.isGrouped)
        assertEquals(listOf("plan", "build"), mode.flat.map { it.value })
        assertEquals("Plan", mode.currentLabel)
    }

    @Test
    fun theOldFlatViewStillAnswersForCallersThatWantIt() {
        val model = requireNotNull(state(fiveOptions).toolbar.model)
        assertEquals("anthropic:claude-sonnet-4-5", model.currentValueId)
        assertEquals(
            listOf("anthropic:claude-sonnet-4-5", "anthropic:claude-opus-4-1", "openai:gpt-5"),
            model.values.map { it.id },
        )
    }

    @Test
    fun aValueTheAgentNoLongerListsStillPrintsAsItself() {
        val option = requireNotNull(
            state(
                """{"phase":"ready","configOptions":[{"id":"model","name":"Model","type":"select",
                   "currentValue":"local:qwen","options":[{"name":"GPT-5","value":"openai:gpt-5"}]}]}"""
            ).toolbar.model
        )
        // Printing the option's own name over an unknown value hides the
        // state the user is actually in.
        assertEquals("local:qwen", option.currentLabel)
        assertNull(option.current)
    }

    // --- the three-state Ultra rule -----------------------------------------

    @Test
    fun ultraStoredOnUnderAskFirstIsSuspendedNotOn() {
        val toolbar = state(fiveOptions).toolbar
        assertTrue(toolbar.ultraOn)
        assertTrue(toolbar.askFirst)
        // The agent publishes the stored flag, not `Ultra && !askFirst` — a
        // plain switch would read ON while the swarm is suspended.
        assertEquals(UltraState.Suspended, toolbar.ultraState)
        assertTrue(toolbar.canToggleUltra)
    }

    @Test
    fun ultraOffUnderAskFirstIsLockedAndCannotBeTurnedOn() {
        val toolbar = state(fiveOptions.replace(ULTRA_ON, ULTRA_OFF)).toolbar
        assertFalse(toolbar.ultraOn)
        assertEquals(UltraState.Locked, toolbar.ultraState)
        assertFalse(toolbar.canToggleUltra)
    }

    @Test
    fun ultraUnderARestrictedPermissionIsPlainlyOnOrOff() {
        val restricted = fiveOptions.replace(ASK_FIRST, RESTRICTED)
        assertEquals(UltraState.On, state(restricted).toolbar.ultraState)
        assertEquals(
            UltraState.Off,
            state(restricted.replace(ULTRA_ON, ULTRA_OFF)).toolbar.ultraState,
        )
    }

    // --- usage ---------------------------------------------------------------

    @Test
    fun readsBothUsageNumbersAndKeepsThemApart() {
        val parsed = state(
            """{"phase":"ready","usage":{"used":96000,"size":128000,"tokensUsed":412000},
                "turnUsage":{"inputTokens":40120,"outputTokens":3311,"totalTokens":98219,
                             "cachedReadTokens":54200,"cachedWriteTokens":588,"tokensUsed":91772}}"""
        )
        val usage = requireNotNull(parsed.usage)
        assertEquals(0.75f, usage.fraction, 0.0001f)
        assertTrue(usage.isWarm)
        assertFalse(usage.isNearlyFull)
        // Occupancy and spend are different numbers and only one of them may
        // be added up.
        assertEquals(412000L, usage.tokensUsed)

        val turn = requireNotNull(parsed.turnUsage)
        assertEquals(98219L, turn.totalTokens)
        assertEquals(91772L, turn.tokensUsed)
        // 54200 / (40120 + 54200 + 588)
        assertEquals(0.5712f, requireNotNull(turn.cacheHitRate), 0.001f)
    }

    @Test
    fun aZeroWindowReadsAsEmptyRatherThanDividingByZero() {
        val usage = requireNotNull(state("""{"phase":"ready","usage":{"used":900,"size":0}}""").usage)
        assertEquals(0f, usage.fraction, 0f)
        assertFalse(usage.isWarm)
        assertNull(usage.tokensUsed)
    }

    // --- the plan ------------------------------------------------------------

    @Test
    fun aBlockedTaskCarriesAFlagRatherThanASuffixInItsSentence() {
        val plan = state(
            """{"phase":"ready","plan":[
                {"content":"Run the test suite","priority":"medium","status":"pending","blocked":true},
                {"content":"Read the file","priority":"high","status":"completed"}]}"""
        ).plan
        assertEquals("Run the test suite", plan[0].content)
        assertTrue(plan[0].isBlocked)
        assertEquals(AgentPlanEntry.Priority.Medium, plan[0].priorityOf)
        assertEquals(AgentPlanEntry.Status.Pending, plan[0].statusOf)
        assertFalse(plan[1].isBlocked)
        assertTrue(plan[1].isDone)
        assertEquals(AgentPlanEntry.Priority.High, plan[1].priorityOf)
    }

    @Test
    fun aBlockedTaskThatStartedIsNoLongerBlocked() {
        val entry = AgentPlanEntry("x", "low", "in_progress", blocked = true)
        // "Blocked" only says anything about work that has not started; a
        // running task drawn as blocked is a contradiction on screen.
        assertFalse(entry.isBlocked)
        assertTrue(entry.blocked)
    }

    // --- tool calls ----------------------------------------------------------

    @Test
    fun aToolCallKeepsItsOpeningArgumentsItsPermissionMetaAndItsTurn() {
        val conversation = AgentConversation().apply(
            """
            {"revision":4,"total":1,"entries":[
              {"index":0,"rev":4,"kind":"tool_call","id":"wf-1","turn":3,
               "title":"[review#2] workflow review-changes","tool_kind":"other","status":"completed",
               "rawInput":"{\"run_id\":\"run-7\",\"agents\":12}",
               "rawInputOpen":"{\"workflow\":\"review-changes\",\"phases\":[\"Review\"]}",
               "permissionMeta":{"spettro.app/question":{"version":2}},
               "options":[{"optionId":"opt-1","name":"Postgres","kind":"allow_once",
                           "_meta":{"spettro.app/isRecommended":true}},
                          {"optionId":"custom","name":"Something else","kind":"allow_once",
                           "_meta":{"spettro.app/isCustomInput":true}}]}]}
            """.trimIndent()
        )
        val call = conversation.entries.single() as AgentEntry.ToolCall
        assertEquals(3L, call.turn)
        assertEquals("3:wf-1", call.key)
        assertEquals("review#2", call.agentPrefix)
        assertEquals("workflow", call.toolName)
        // The finish update overwrote `rawInput`; the plan survives only in
        // the opening arguments.
        assertEquals("run-7", requireNotNull(call.args).optString("run_id"))
        assertEquals("review-changes", requireNotNull(call.openArgs).optString("workflow"))
        assertEquals(1, requireNotNull(call.openArgs).optJSONArray("phases")?.length())

        assertTrue(call.options[0].isRecommended)
        assertFalse(call.options[0].isCustomInput)
        assertTrue(call.options[1].isCustomInput)

        // A permission request carrying a question is not a permission prompt.
        val question = requireNotNull(
            SpettroQuestion.fromPermissionMeta(call.key, JSONObject(requireNotNull(call.permissionMeta)))
        )
        assertEquals(SpettroQuestion.Transport.Permission, question.transport)
        assertEquals(2, question.version)
    }

    @Test
    fun aToolCallWithNoOpeningArgumentsFallsBackToItsLatestOnes() {
        val call = AgentEntry.ToolCall(
            id = "call-1",
            title = "read src/main.rs",
            kind = ToolKind.Read,
            status = ToolCallStatus.Completed,
            options = emptyList(),
            content = emptyList(),
            locations = emptyList(),
            rawInput = """{"path":"src/main.rs"}""",
        )
        assertEquals("src/main.rs", requireNotNull(call.openArgs).optString("path"))
        assertNull(call.agentPrefix)
        assertEquals("read", call.toolName)
    }

    @Test
    fun malformedArgumentsAreNullRatherThanAThrow() {
        val call = AgentEntry.ToolCall(
            id = "call-1",
            title = "edit {\"path\":\"/very/long/pa",
            kind = ToolKind.Edit,
            status = ToolCallStatus.Completed,
            options = emptyList(),
            content = emptyList(),
            locations = emptyList(),
            rawInput = "{not json",
        )
        assertNull(call.args)
        assertNull(call.openArgs)
        assertEquals("edit", call.toolName)
    }

    // --- questions -----------------------------------------------------------

    private val askPayload = """
        [{"id":"question-1","session":7,"payload":{
          "version":2,"sessionId":"sess-a","context":"Choosing a datastore",
          "questions":[
            {"id":"q-0","header":"Datastore","question":"Which database?",
             "options":[{"id":"opt-0","label":"Postgres","description":"boring and good"},
                        {"id":"opt-1","label":"SQLite","isRecommended":true}]},
            {"id":"q-1","header":"Checks","question":"Which checks?","multiSelect":true,
             "options":[{"id":"a","label":"go vet"},{"id":"b","label":"staticcheck"}]},
            {"id":"q-2","header":"Anything else","question":"Notes?","allowCustomInput":true,
             "options":[]}]}}]
    """.trimIndent()

    @Test
    fun readsAWholeAskUserForm() {
        val question = SpettroQuestion.parseAll(JSONArray(askPayload)).single()
        assertEquals("question-1", question.id)
        assertEquals(7L, question.session)
        assertEquals(2, question.version)
        assertEquals("Choosing a datastore", question.context)
        assertEquals(SpettroQuestion.Transport.Ask, question.transport)
        assertEquals(3, question.questions.size)
        assertFalse(question.isSingle)

        val first = question.questions[0]
        assertEquals("Datastore", first.header)
        assertFalse(first.multiSelect)
        assertFalse(first.allowCustomInput)
        assertTrue(first.options[1].isRecommended)
        assertEquals("boring and good", first.options[0].description)

        // No options at all can only be typed into, whatever the flag said.
        assertTrue(question.questions[2].allowCustomInput)
        assertTrue(question.questions[2].options.isEmpty())
    }

    @Test
    fun aVersionOneQuestionIsOneFlatQuestion() {
        val question = SpettroQuestion.parseAll(
            JSONArray(
                """[{"id":"question-2","session":null,"payload":{
                   "question":"Proceed?","options":[{"id":"yes","label":"Yes"}]}}]"""
            )
        ).single()
        assertEquals(1, question.version)
        assertNull(question.session)
        assertTrue(question.isSingle)
        assertEquals("Proceed?", question.questions.single().question)
        assertEquals("q-0", question.questions.single().id)
    }

    @Test
    fun anUnansweredQuestionIsOmittedRatherThanDefaultedToTheRecommendation() {
        val q = SpettroQuestion.parseAll(JSONArray(askPayload)).single().questions[0]
        assertNull(q.answer(QuestionDraft()))
    }

    @Test
    fun selectionsComeOutInOptionOrderNotTickOrder() {
        val q = SpettroQuestion.parseAll(JSONArray(askPayload)).single().questions[1]
        val draft = QuestionDraft()
            .toggle("b", multi = true)
            .toggle("a", multi = true)
        assertEquals(listOf("b", "a"), draft.selected)
        val answer = q.answer(draft.copy(note = "vet is slow")) as QuestionAnswer.Option
        assertEquals(listOf("a", "b"), answer.optionIds)
        assertEquals("vet is slow", answer.notes)
        assertEquals("q-1", answer.questionId)
    }

    @Test
    fun aSinglePickReplacesRatherThanAccumulates() {
        val draft = QuestionDraft().toggle("opt-0", multi = false).toggle("opt-1", multi = false)
        assertEquals(listOf("opt-1"), draft.selected)
        // Tapping the chosen one again clears it: nothing is preselected and
        // nothing is unclearable.
        assertTrue(draft.toggle("opt-1", multi = false).selected.isEmpty())
    }

    @Test
    fun typedTextBecomesACustomAnswer() {
        val q = SpettroQuestion.parseAll(JSONArray(askPayload)).single().questions[2]
        val answer = q.answer(QuestionDraft(custom = "  use the existing MySQL box  "))
        assertEquals(
            QuestionAnswer.Custom("q-2", "use the existing MySQL box", null),
            answer,
        )
        assertTrue(QuestionDraft(custom = "   ").isEmpty)
    }

    @Test
    fun questionsRideTheOrdinarySessionPollAndCountAsWorkForTheUser() {
        val parsed = state(
            """{"phase":"running","waitingCount":1,"questions":$askPayload}"""
        )
        assertEquals(1, parsed.questions.size)
        assertEquals(2, parsed.needsUser)
    }

    @Test
    fun aQuestionListThatIsMissingEntirelyIsEmptyRatherThanNull() {
        assertEquals(emptyList<SpettroQuestion>(), state("""{"phase":"ready"}""").questions)
        assertEquals(emptyList<SpettroQuestion>(), SpettroQuestion.parseAll(null))
    }
}
