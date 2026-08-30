package to.eyed.seeker.code.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a filled-in form question back into the protocol's answer.
 *
 * The types are the whole point. The engine maps each value's JSON type
 * straight onto the protocol's `ElicitationContentValue` variant, so an
 * integer field answered with the string `"7"` reaches the agent as a
 * string — indistinguishable in a transcript, and wrong.
 */
class ElicitationAnswerTest {

    private fun field(
        key: String,
        type: String,
        required: Boolean = false,
        options: List<ElicitationOption> = emptyList(),
        defaultString: String? = null,
        defaultNumber: Double? = null,
        defaultBoolean: Boolean? = null,
        defaultList: List<String> = emptyList(),
        pattern: String? = null,
        minimum: Double? = null,
        maximum: Double? = null,
        minLength: Int? = null,
        maxLength: Int? = null,
        minItems: Int? = null,
        maxItems: Int? = null,
    ) = ElicitationField(
        key = key,
        type = type,
        title = null,
        description = null,
        required = required,
        options = options,
        format = null,
        pattern = pattern,
        defaultString = defaultString,
        defaultNumber = defaultNumber,
        defaultBoolean = defaultBoolean,
        defaultList = defaultList,
        minimum = minimum,
        maximum = maximum,
        minLength = minLength,
        maxLength = maxLength,
        minItems = minItems,
        maxItems = maxItems,
    )

    @Test
    fun everyFieldGoesBackAsItsOwnJsonType() {
        val fields = listOf(
            field("note", "string"),
            field("depth", "integer"),
            field("ratio", "number"),
            field("dry", "boolean"),
            field("tags", "array"),
        )
        val json = ElicitationAnswer.accept(
            fields,
            mapOf(
                "note" to "hello",
                "depth" to "7",
                "ratio" to "1.5",
                "dry" to true,
                "tags" to listOf("a", "c"),
            ),
        )
        val content = JSONObject(json).getJSONObject("content")
        assertEquals("accept", JSONObject(json).getString("action"))
        assertEquals("hello", content.get("note"))
        assertEquals(2, content.getJSONArray("tags").length())
        // The *text* is the contract: the engine reads this JSON and maps
        // each value's JSON type onto the protocol's own variant, so an
        // unquoted 7 and a quoted "7" are two different answers. Asserting on
        // the parsed object would let a string through — `getLong` parses one
        // happily, which is exactly the bug this guards.
        val emitted = content.toString()
        assertTrue("an integer, not text: $emitted", emitted.contains("\"depth\":7"))
        assertTrue("a real number: $emitted", emitted.contains("\"ratio\":1.5"))
        assertTrue("a boolean, not text: $emitted", emitted.contains("\"dry\":true"))
        assertFalse("nothing quoted that should not be: $emitted", emitted.contains("\"7\""))
    }

    /** A number field with junk in it must not be sent as junk-the-string. */
    @Test
    fun aNumberFieldThatIsNotANumberIsLeftOutRatherThanSentAsText() {
        val fields = listOf(field("depth", "integer"))
        val content = JSONObject(ElicitationAnswer.accept(fields, mapOf("depth" to "later")))
            .getJSONObject("content")
        assertFalse(content.has("depth"))
    }

    @Test
    fun requiredFieldsGateTheAnswer() {
        val fields = listOf(
            field("note", "string", required = true),
            field("depth", "integer", required = true),
            field("tags", "array", required = true),
            field("dry", "boolean", required = true),
        )
        val empty = ElicitationAnswer.missing(
            fields,
            mapOf("note" to "", "depth" to "", "tags" to emptyList<String>(), "dry" to false),
        )
        // A switch always has an answer; false is one.
        assertEquals(listOf("note", "depth", "tags"), empty)

        val filled = ElicitationAnswer.missing(
            fields,
            mapOf("note" to "x", "depth" to "2", "tags" to listOf("a"), "dry" to false),
        )
        assertTrue(filled.isEmpty())
    }

    /** An optional multi-select with nothing ticked says so, rather than going silent. */
    @Test
    fun anEmptyOptionalMultiSelectIsSentAsAnEmptyList() {
        val fields = listOf(field("tags", "array"))
        val content = JSONObject(ElicitationAnswer.accept(fields, mapOf("tags" to emptyList<String>())))
            .getJSONObject("content")
        assertEquals(0, content.getJSONArray("tags").length())
    }

    @Test
    fun defaultsSeedTheFieldsInTheirOwnShape() {
        assertEquals("", ElicitationAnswer.initialValue(field("a", "string")))
        assertEquals("x", ElicitationAnswer.initialValue(field("a", "string", defaultString = "x")))
        assertEquals(true, ElicitationAnswer.initialValue(field("a", "boolean", defaultBoolean = true)))
        // A JSON number arrives as a double; `3.0` in a form box is wrong.
        assertEquals("3", ElicitationAnswer.initialValue(field("a", "integer", defaultNumber = 3.0)))
        assertEquals("1.5", ElicitationAnswer.initialValue(field("a", "number", defaultNumber = 1.5)))
        assertEquals(listOf("a"), ElicitationAnswer.initialValue(field("a", "array", defaultList = listOf("a"))))
    }

    /** A field kind this build cannot draw is never invented an answer for. */
    @Test
    fun anUnsupportedFieldIsNeitherSentNorDemanded() {
        val fields = listOf(field("mystery", "unsupported", required = true))
        assertTrue(ElicitationAnswer.missing(fields, emptyMap()).isEmpty())
        val content = JSONObject(ElicitationAnswer.accept(fields, mapOf("mystery" to "x")))
            .getJSONObject("content")
        assertEquals(0, content.length())
    }

    /**
     * Every constraint the agent sends used to be discarded, so a form the
     * agent would certainly reject was one the user could happily submit —
     * and the rejection arrived as a turn error rather than beside the field.
     */
    @Test
    fun everyConstraintTheAgentSendsIsChecked() {
        val fields = listOf(
            field("depth", "integer", minimum = 1.0, maximum = 9.0),
            field("ratio", "number"),
            field("note", "string", minLength = 3, maxLength = 5),
            field("ref", "string", pattern = "^[a-f0-9]{7}$"),
            field("tags", "array", minItems = 2),
        )
        val errors = ElicitationAnswer.validate(
            fields,
            mapOf(
                "depth" to "12",
                "ratio" to "not a number",
                "note" to "ab",
                "ref" to "zzz",
                "tags" to listOf("a"),
            ),
        )
        assertEquals("Must be at most 9.", errors["depth"])
        assertEquals("Must be a number.", errors["ratio"])
        assertEquals("At least 3 characters.", errors["note"])
        assertEquals("Not in the expected format.", errors["ref"])
        assertEquals("Choose at least 2.", errors["tags"])
    }

    /** An integer field is not a number field: 1.5 is not a whole number. */
    @Test
    fun anIntegerFieldRefusesAFraction() {
        val errors = ElicitationAnswer.validate(
            listOf(field("depth", "integer")),
            mapOf("depth" to "1.5"),
        )
        assertEquals("Must be a whole number.", errors["depth"])
    }

    /** A valid form has nothing to say. */
    @Test
    fun aFilledInFormValidates() {
        val fields = listOf(
            field("depth", "integer", minimum = 1.0, maximum = 9.0),
            field("note", "string", required = true, minLength = 3),
            field("tags", "array", minItems = 1),
        )
        val errors = ElicitationAnswer.validate(
            fields,
            mapOf("depth" to "4", "note" to "fine", "tags" to listOf("a")),
        )
        assertTrue(errors.toString(), errors.isEmpty())
    }

    /**
     * A pattern this platform's regex engine cannot compile is the agent's
     * problem, not the user's — it must not lock them out of the form.
     */
    @Test
    fun anUncompilablePatternDoesNotBlockTheForm() {
        val errors = ElicitationAnswer.validate(
            listOf(field("ref", "string", pattern = "(?<incomplete")),
            mapOf("ref" to "anything"),
        )
        assertTrue(errors.isEmpty())
    }

    /** Only what is required is required; an empty optional field is fine. */
    @Test
    fun anEmptyOptionalFieldIsNotAnError() {
        val errors = ElicitationAnswer.validate(
            listOf(field("note", "string", minLength = 3), field("depth", "integer")),
            mapOf("note" to "", "depth" to ""),
        )
        assertTrue(errors.isEmpty())
    }
}
