package to.eyed.thragg.ui.agent.spettro

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.thragg.core.PermissionOption
import to.eyed.thragg.core.QuestionDraft
import to.eyed.thragg.core.SpettroAnswer
import to.eyed.thragg.core.SpettroQuestion

/**
 * The question form's answering rules.
 *
 * Every case here is one the *model* can tell apart. An omitted question is
 * not the recommended option; a note typed beside a multi-select pick is not a
 * replacement for it; and tick order is not option order. Each is one line of
 * code away from telling the agent something the user did not say.
 */
class QuestionFormTest {

    private fun opt(id: String, recommended: Boolean = false, preview: String? = null) =
        SpettroQuestion.Opt(id, id.replaceFirstChar { it.uppercase() }, null, preview, recommended)

    private fun question(
        id: String,
        multi: Boolean = false,
        custom: Boolean = false,
        options: List<SpettroQuestion.Opt> = listOf(opt("postgres"), opt("sqlite", recommended = true)),
    ) = SpettroQuestion.Q(
        id = id,
        header = id,
        question = "Which database?",
        options = options,
        multiSelect = multi,
        allowCustomInput = custom || options.isEmpty(),
    )

    private fun form(vararg questions: SpettroQuestion.Q, version: Int = 2) = SpettroQuestion(
        id = "question-1",
        session = 7,
        version = version,
        sessionId = "sess",
        context = null,
        questions = questions.toList(),
        transport = SpettroQuestion.Transport.Ask,
    )

    // --- omission ------------------------------------------------------------

    /**
     * The whole point of the form: a question nobody touched is *missing* from
     * the answers, so Spettro tells the model nobody answered it.
     */
    @Test
    fun anUntouchedQuestionIsOmittedRatherThanDefaulted() {
        val request = form(question("q-0"), question("q-1"))
        val answers = QuestionForm.answers(
            request,
            mapOf("q-0" to QuestionDraft(selected = listOf("postgres"))),
        )
        assertEquals(1, answers.size)
        assertEquals("q-0", answers[0].questionId)
    }

    /** And in particular it is never the option the agent recommended. */
    @Test
    fun theRecommendationIsNeverSentForAnUntouchedQuestion() {
        val answers = QuestionForm.answers(form(question("q-0")), emptyMap())
        assertTrue(answers.isEmpty())
    }

    @Test
    fun answersComeOutInQuestionOrder() {
        val request = form(question("q-0"), question("q-1"), question("q-2"))
        val answers = QuestionForm.answers(
            request,
            mapOf(
                "q-2" to QuestionDraft(selected = listOf("sqlite")),
                "q-0" to QuestionDraft(selected = listOf("postgres")),
            ),
        )
        assertEquals(listOf("q-0", "q-2"), answers.map { it.questionId })
    }

    // --- option order --------------------------------------------------------

    /**
     * Two users who ticked the same two boxes in opposite orders must send the
     * same thing, or the agent sees a difference the user did not make.
     */
    @Test
    fun selectionsComeOutInOptionOrderNotTickOrder() {
        val q = question("q-0", multi = true, options = listOf(opt("a"), opt("b"), opt("c")))
        val answers = QuestionForm.answers(
            form(q),
            mapOf("q-0" to QuestionDraft(selected = listOf("c", "a"))),
        )
        val option = answers.single() as SpettroAnswer.Option
        assertEquals(listOf("a", "c"), option.optionIds)
    }

    // --- custom text ---------------------------------------------------------

    /** On a multi-select the words are extra, so they travel as `notes`. */
    @Test
    fun multiSelectCustomTextJoinsTheNote() {
        val q = question("q-0", multi = true, custom = true)
        val answers = QuestionForm.answers(
            form(q),
            mapOf(
                "q-0" to QuestionDraft(
                    selected = listOf("sqlite"),
                    custom = "or DuckDB",
                    note = "vet is slow",
                ),
            ),
        )
        val option = answers.single() as SpettroAnswer.Option
        assertEquals(listOf("sqlite"), option.optionIds)
        assertEquals("or DuckDB\nvet is slow", option.notes)
    }

    @Test
    fun multiSelectCustomTextWithNoNoteBecomesTheNote() {
        val q = question("q-0", multi = true, custom = true)
        val answers = QuestionForm.answers(
            form(q),
            mapOf("q-0" to QuestionDraft(selected = listOf("sqlite"), custom = "or DuckDB")),
        )
        assertEquals("or DuckDB", (answers.single() as SpettroAnswer.Option).notes)
    }

    /** On a single-select the words replace the pick — one answer, not two. */
    @Test
    fun singleSelectCustomTextClearsTheSelection() {
        val q = question("q-0", custom = true)
        val answers = QuestionForm.answers(
            form(q),
            mapOf("q-0" to QuestionDraft(selected = listOf("sqlite"), custom = "the MySQL box")),
        )
        val custom = answers.single() as SpettroAnswer.Custom
        assertEquals("the MySQL box", custom.text)
    }

    @Test
    fun aQuestionWithNoOptionsIsAnsweredByTyping() {
        val q = question("q-0", options = emptyList())
        assertTrue(q.allowCustomInput)
        val answers = QuestionForm.answers(form(q), mapOf("q-0" to QuestionDraft(custom = "  yes  ")))
        assertEquals("yes", (answers.single() as SpettroAnswer.Custom).text)
    }

    /**
     * A note on its own is still something a person typed. `Q.answer` calls it
     * nothing; the encoder has a shape for it, and that is the one that wins.
     */
    @Test
    fun aNoteOnItsOwnSurvivesAsANotesOnlyAnswer() {
        val answers = QuestionForm.answers(
            form(question("q-0")),
            mapOf("q-0" to QuestionDraft(note = "whichever is cheaper")),
        )
        val option = answers.single() as SpettroAnswer.Option
        assertTrue(option.optionIds.isEmpty())
        assertEquals("whichever is cheaper", option.notes)
    }

    @Test
    fun blankTextIsNotAnAnswer() {
        val answers = QuestionForm.answers(
            form(question("q-0", custom = true)),
            mapOf("q-0" to QuestionDraft(custom = "   ", note = "  ")),
        )
        assertTrue(answers.isEmpty())
    }

    // --- the dots and the review page ---------------------------------------

    @Test
    fun anEmptyDraftIsUnanswered() {
        val q = question("q-0")
        assertFalse(QuestionForm.isAnswered(q, QuestionDraft()))
        assertEquals(QuestionForm.NOT_ANSWERED, QuestionForm.summary(q, QuestionDraft()))
    }

    @Test
    fun theReviewRowNamesTheOptionsInOptionOrder() {
        val q = question("q-0", multi = true, options = listOf(opt("a"), opt("b"), opt("c")))
        val summary = QuestionForm.summary(q, QuestionDraft(selected = listOf("c", "a")))
        assertEquals("A, C", summary)
        assertTrue(QuestionForm.isAnswered(q, QuestionDraft(selected = listOf("c"))))
    }

    @Test
    fun theReviewRowQuotesTypedWords() {
        val q = question("q-0", custom = true)
        assertEquals("the MySQL box", QuestionForm.summary(q, QuestionDraft(custom = "the MySQL box")))
    }

    @Test
    fun theReviewRowShowsANoteOnlyAnswer() {
        val q = question("q-0")
        assertEquals("cheapest", QuestionForm.summary(q, QuestionDraft(note = "cheapest")))
        assertTrue(QuestionForm.isAnswered(q, QuestionDraft(note = "cheapest")))
    }

    /** The sentence is the spec's, word for word; it is not ours to improve. */
    @Test
    fun theWarningIsVerbatim() {
        assertEquals(
            "Questions you left alone are sent as unanswered — the agent is told " +
                "nobody answered them, not that you had no preference.",
            QuestionForm.UNANSWERED_WARNING,
        )
    }

    // --- submit on pick ------------------------------------------------------

    @Test
    fun oneSingleSelectQuestionSubmitsOnPick() {
        assertTrue(QuestionForm.submitsOnPick(form(question("q-0"))))
    }

    @Test
    fun aMultiSelectNeverSubmitsOnPick() {
        assertFalse(QuestionForm.submitsOnPick(form(question("q-0", multi = true))))
    }

    @Test
    fun aTwoQuestionFormNeverSubmitsOnPick() {
        assertFalse(QuestionForm.submitsOnPick(form(question("q-0"), question("q-1"))))
    }

    // --- the walked transport ------------------------------------------------

    /**
     * W-10: over the permission channel the answer is an option id plus the
     * decision tagged into `_meta`, because the outcome alone cannot say
     * whether an approval was a security decision or a product one.
     */
    @Test
    fun aWalkedPickRepliesWithTheTaggedOptionId() {
        val reply = QuestionForm.permissionReply(
            listOf(SpettroAnswer.Option("q-0", listOf("sqlite"))),
            listOf(PermissionOption("sqlite", "SQLite", "allow_once")),
        )!!
        assertEquals("sqlite", reply.optionId)
        val meta = JSONObject(reply.answerMetaJson).getJSONObject("spettro.app/questionAnswer")
        assertEquals("option", meta.getString("kind"))
        assertEquals("sqlite", meta.getString("optionId"))
    }

    @Test
    fun aWalkedCustomAnswerGoesToTheCustomInputOption() {
        val reply = QuestionForm.permissionReply(
            listOf(SpettroAnswer.Custom("q-0", "  the MySQL box  ")),
            listOf(
                PermissionOption("sqlite", "SQLite", "allow_once"),
                PermissionOption("custom", "Type an answer", "allow_once", isCustomInput = true),
            ),
        )!!
        assertEquals("custom", reply.optionId)
        val meta = JSONObject(reply.answerMetaJson).getJSONObject("spettro.app/questionAnswer")
        assertEquals("custom", meta.getString("kind"))
        assertEquals("the MySQL box", meta.getString("text"))
    }

    /** An older CLI that omits the flag still calls the option `custom`. */
    @Test
    fun aWalkedCustomAnswerFallsBackToTheConventionalId() {
        val reply = QuestionForm.permissionReply(
            listOf(SpettroAnswer.Custom("q-0", "something else")),
            listOf(PermissionOption("sqlite", "SQLite", "allow_once")),
        )!!
        assertEquals("custom", reply.optionId)
    }

    /** A walked form has no way to say "unanswered": there is nothing to send. */
    @Test
    fun aWalkedFormWithNoAnswerSendsNothing() {
        assertNull(QuestionForm.permissionReply(emptyList(), emptyList()))
    }
}
