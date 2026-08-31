package to.eyed.seeker.code.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire shape of a question answer.
 *
 * Every rule below is one the model can tell the difference between. An
 * omitted question is *not* the recommended option; `optionId` alongside
 * `optionIds` is not redundant to a CLI that reads only one of them; and a
 * decline is all-or-nothing at the top level rather than a form of empty
 * answers.
 */
class SpettroAnswersTest {

    @Test
    fun aSinglePickCarriesBothOptionKeys() {
        val json = JSONObject(
            SpettroAnswers.encode(
                listOf(SpettroAnswer.Option("q-0", listOf("opt-1"))),
            )
        )
        val answer = json.getJSONArray("answers").getJSONObject(0)
        assertEquals("q-0", answer.getString("questionId"))
        assertEquals("option", answer.getString("kind"))
        assertEquals("opt-1", answer.getString("optionId"))
        assertEquals(1, answer.getJSONArray("optionIds").length())
        assertEquals("opt-1", answer.getJSONArray("optionIds").getString(0))
    }

    /**
     * A multi-select has no single `optionId` to name, and inventing one —
     * "the first" — would answer a different question from the one asked.
     */
    @Test
    fun aMultiPickCarriesNoSingleOptionId() {
        val json = JSONObject(
            SpettroAnswers.encode(
                listOf(SpettroAnswer.Option("q-1", listOf("opt-0", "opt-1"), notes = "vet is slow")),
            )
        )
        val answer = json.getJSONArray("answers").getJSONObject(0)
        assertFalse(answer.has("optionId"))
        assertEquals(2, answer.getJSONArray("optionIds").length())
        assertEquals("vet is slow", answer.getString("notes"))
    }

    /**
     * The rule that makes "skip" possible: an untouched question is left out,
     * so the model is told nobody answered it. Defaulting it to the badged
     * recommendation would be the app answering on the user's behalf.
     */
    @Test
    fun anUntouchedQuestionIsOmittedRatherThanDefaulted() {
        val json = JSONObject(
            SpettroAnswers.encode(
                listOf(
                    SpettroAnswer.Option("q-0", emptyList()),
                    SpettroAnswer.Custom("q-1", "   "),
                    SpettroAnswer.Option("q-2", listOf("opt-3")),
                ),
            )
        )
        val answers = json.getJSONArray("answers")
        assertEquals(1, answers.length())
        assertEquals("q-2", answers.getJSONObject(0).getString("questionId"))
    }

    /** A note alone is an answer: the user typed something, so it travels. */
    @Test
    fun aNoteWithNoSelectionStillTravels() {
        val json = JSONObject(
            SpettroAnswers.encode(
                listOf(SpettroAnswer.Option("q-0", emptyList(), notes = "none of these")),
            )
        )
        assertEquals(1, json.getJSONArray("answers").length())
    }

    @Test
    fun customTextIsTrimmedAndTagged() {
        val json = JSONObject(
            SpettroAnswers.encode(
                listOf(SpettroAnswer.Custom("q-2", "  use the existing MySQL box  ")),
            )
        )
        val answer = json.getJSONArray("answers").getJSONObject(0)
        assertEquals("custom", answer.getString("kind"))
        assertEquals("use the existing MySQL box", answer.getString("text"))
    }

    /** Version 1 has no `questions[]`, so it has no envelope either. */
    @Test
    fun versionOneTakesTheBareShapeOfTheFirstAnswer() {
        val json = JSONObject(
            SpettroAnswers.encode(
                listOf(SpettroAnswer.Option("q-0", listOf("opt-1"))),
                version = 1,
            )
        )
        assertFalse(json.has("answers"))
        assertEquals("option", json.getString("kind"))
        assertEquals("opt-1", json.getString("optionId"))
    }

    /**
     * Version 1 with nothing answered can only be declined — it has no way to
     * say "skipped", and sending an empty envelope it does not understand
     * would leave the agent waiting.
     */
    @Test
    fun versionOneWithNothingAnsweredDeclines() {
        val json = JSONObject(SpettroAnswers.encode(emptyList(), version = 1))
        assertEquals("declined", json.getString("kind"))
    }

    @Test
    fun decliningIsAllOrNothingAtTheTopLevel() {
        val json = JSONObject(SpettroAnswers.encode(null))
        assertEquals("declined", json.getString("kind"))
        assertFalse(json.has("answers"))
    }

    /**
     * The other transport: a form walked through the permission channel
     * answers by selecting an option *and* attaching the same decision as
     * `_meta`, because the outcome alone cannot carry free text.
     */
    @Test
    fun theWalkedFormCarriesItsAnswerInMeta() {
        val meta = JSONObject(SpettroAnswers.optionMeta("opt-1"))
            .getJSONObject("spettro.app/questionAnswer")
        assertEquals("option", meta.getString("kind"))
        assertEquals("opt-1", meta.getString("optionId"))

        val declined = JSONObject(SpettroAnswers.DECLINED_META)
            .getJSONObject("spettro.app/questionAnswer")
        assertEquals("declined", declined.getString("kind"))

        val custom = JSONObject(SpettroAnswers.customMeta("something else"))
            .getJSONObject("spettro.app/questionAnswer")
        assertEquals("custom", custom.getString("kind"))
        assertEquals("something else", custom.getString("text"))
    }

    /** Order is the caller's — option order — and the encoder preserves it. */
    @Test
    fun answersKeepTheOrderTheyWereGivenIn() {
        val json = JSONObject(
            SpettroAnswers.encode(
                listOf(
                    SpettroAnswer.Option("q-0", listOf("a")),
                    SpettroAnswer.Option("q-1", listOf("b")),
                    SpettroAnswer.Option("q-2", listOf("c")),
                ),
            )
        )
        val answers = json.getJSONArray("answers")
        assertEquals("q-0", answers.getJSONObject(0).getString("questionId"))
        assertEquals("q-1", answers.getJSONObject(1).getString("questionId"))
        assertEquals("q-2", answers.getJSONObject(2).getString("questionId"))
    }

    /** Everything the encoder produces has to be readable JSON, always. */
    @Test
    fun everyShapeIsValidJson() {
        assertTrue(JSONObject(SpettroAnswers.encode(emptyList())).has("answers"))
        assertNull(
            runCatching { JSONObject(SpettroAnswers.declined()) }.exceptionOrNull()
        )
    }
}
