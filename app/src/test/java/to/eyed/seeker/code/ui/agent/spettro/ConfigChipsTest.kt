package to.eyed.seeker.code.ui.agent.spettro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import to.eyed.seeker.code.core.AgentSessionState
import to.eyed.seeker.code.core.UltraState

/**
 * The chip row's decisions, taken away from Compose.
 *
 * Everything here is a rule the user would only discover was wrong by being
 * lied to: a chip in the wrong place is one they have to scroll for, a model
 * truncated from the wrong end makes every model look the same, and an Ultra
 * tap that goes on the wire under `ask-first` is a toggle that flips back by
 * itself.
 *
 * Options are built from wire JSON through the real parser rather than by
 * constructor, so a change to W-13's grouping rule breaks these too.
 */
class ConfigChipsTest {

    private fun options(body: String) = AgentSessionState.parse(body).configOptions

    private fun option(json: String) =
        options("""{"phase":"ready","configOptions":[$json]}""").single()

    // --- order ---------------------------------------------------------------

    /**
     * Ultra first even though the agent sends it last: it is the charged one
     * and has to be reachable without scrolling a 400 dp row.
     */
    @Test
    fun ultraLeadsAndTheRestFollowThePhoneOrder() {
        val wire = options(
            """
            {"phase":"ready","configOptions":[
              {"id":"mode","name":"Mode","type":"select","currentValue":"plan"},
              {"id":"model","name":"Model","type":"select","currentValue":"x"},
              {"id":"permission","name":"Permission","type":"select","currentValue":"yolo"},
              {"id":"thinking","name":"Thinking","type":"select","currentValue":"off"},
              {"id":"ultra","name":"Ultra","type":"boolean","currentValue":false}]}
            """.trimIndent()
        )
        assertEquals(
            listOf("ultra", "mode", "model", "thinking", "permission"),
            chipOrder(wire).map { it.id },
        )
    }

    /** A missing option is a missing chip, not a hole in the order. */
    @Test
    fun anAgentThatSendsOnlySomeOptionsGetsOnlyThoseChips() {
        val wire = options(
            """
            {"phase":"ready","configOptions":[
              {"id":"permission","name":"Permission","type":"select","currentValue":"yolo"},
              {"id":"ultra","name":"Ultra","type":"boolean","currentValue":true}]}
            """.trimIndent()
        )
        assertEquals(listOf("ultra", "permission"), chipOrder(wire).map { it.id })
    }

    /**
     * The list is the agent's, not this build's. An option nobody here has
     * heard of renders after the five, in the order it arrived — the failure
     * we are guarding against is a future Spettro adding a chip and the phone
     * silently dropping it.
     */
    @Test
    fun unknownOptionsKeepTheirWireOrderAfterTheKnownOnes() {
        val wire = options(
            """
            {"phase":"ready","configOptions":[
              {"id":"sandbox","name":"Sandbox","type":"select","currentValue":"on"},
              {"id":"mode","name":"Mode","type":"select","currentValue":"plan"},
              {"id":"voice","name":"Voice","type":"boolean","currentValue":false}]}
            """.trimIndent()
        )
        assertEquals(listOf("mode", "sandbox", "voice"), chipOrder(wire).map { it.id })
    }

    @Test
    fun noOptionsIsAnEmptyRowRatherThanAnException() {
        assertEquals(emptyList<String>(), chipOrder(emptyList()).map { it.id })
    }

    // --- icons ---------------------------------------------------------------

    /**
     * `category` decides, so an agent that renames `mode` to `stance` keeps
     * the sliders. `permission` and `ultra` carry no category on the wire and
     * are the only two matched by id.
     */
    @Test
    fun iconsComeFromCategoryAndFallBackToTheTwoIdsWithoutOne() {
        assertEquals(
            "⌁",
            chipGlyph(option("""{"id":"stance","name":"Stance","type":"select","category":"mode"}""")),
        )
        assertEquals(
            "⬢",
            chipGlyph(option("""{"id":"model","name":"Model","type":"select","category":"model"}""")),
        )
        assertEquals(
            "✻",
            chipGlyph(
                option("""{"id":"thinking","name":"T","type":"select","category":"thought_level"}""")
            ),
        )
        assertEquals("⛨", chipGlyph(option("""{"id":"permission","name":"P","type":"select"}""")))
        assertEquals("⚡", chipGlyph(option("""{"id":"ultra","name":"U","type":"boolean"}""")))
        assertEquals("◇", chipGlyph(option("""{"id":"sandbox","name":"S","type":"select"}""")))
    }

    // --- tint ----------------------------------------------------------------

    @Test
    fun theThreeModeColoursAreExactAndNothingElseIsTinted() {
        fun mode(value: String) = option(
            """{"id":"mode","name":"Mode","type":"select","category":"mode",
                "currentValue":"$value"}"""
        )
        assertEquals(0xFFBD93F9, modeTintArgb(mode("plan")))
        assertEquals(0xFF34D399, modeTintArgb(mode("coding")))
        assertEquals(0xFF60A5FA, modeTintArgb(mode("ask")))
        // A repo whose spettro.agents.toml names its own mode gets no tint
        // rather than a colour this build invented for it.
        assertNull(modeTintArgb(mode("triage")))
        assertNull(
            modeTintArgb(
                option("""{"id":"model","name":"Model","type":"select","category":"model",
                          "currentValue":"plan"}""")
            )
        )
    }

    // --- labels --------------------------------------------------------------

    /**
     * The model chip keeps its end. `anthropic:claude-sonnet-4-5` truncated
     * from the head is `anthropic:claude…`, which is the same string for
     * every Anthropic model on the list.
     */
    @Test
    fun theModelChipTruncatesFromTheEndAndEveryoneElseFromTheHead() {
        val model = option(
            """{"id":"model","name":"Model","type":"select","category":"model",
                "currentValue":"anthropic:claude-sonnet-4-5"}"""
        )
        assertEquals("…claude-sonnet-4-5", chipLabel(model))

        val mode = option(
            """{"id":"mode","name":"Mode","type":"select","category":"mode",
                "currentValue":"plan-and-review-carefully"}"""
        )
        assertEquals("plan-and-review-c…", chipLabel(mode))
    }

    /** A label that fits is printed whole, ellipsis and all decisions skipped. */
    @Test
    fun aShortLabelIsUntouched() {
        val permission = option(
            """{"id":"permission","name":"Permission","type":"select",
                "currentValue":"ask-first","options":[{"name":"Ask first","value":"ask-first"}]}"""
        )
        assertEquals("Ask first", chipLabel(permission))
    }

    /**
     * Boolean chips print the state, not the name — the name is already the
     * word beside the glyph.
     */
    @Test
    fun aBooleanWithNoResolvedChoicePrintsOnOrOff() {
        assertEquals(
            "On",
            chipLabel(option("""{"id":"ultra","name":"Ultra","type":"boolean","currentValue":true}""")),
        )
        assertEquals(
            "Off",
            chipLabel(option("""{"id":"voice","name":"Voice","type":"boolean","currentValue":false}""")),
        )
    }

    // --- the Ultra gate ------------------------------------------------------

    /**
     * The whole point of the chip. Only the *on* direction under `ask-first`
     * refuses; the other three taps go on the wire, including turning a
     * suspended Ultra off.
     */
    @Test
    fun onlyTurningUltraOnUnderAskFirstIsWithheld() {
        assertEquals(UltraTap.Set(true), ultraTap(UltraState.Off))
        assertEquals(UltraTap.Set(false), ultraTap(UltraState.On))
        assertEquals(UltraTap.Set(false), ultraTap(UltraState.Suspended))
        assertEquals(UltraTap.Locked, ultraTap(UltraState.Locked))
    }

    /**
     * Suspended and locked have to be distinguishable to a screen reader,
     * because on screen they differ by a glyph and an opacity.
     */
    @Test
    fun everyUltraStateSaysSomethingDifferentOutLoud() {
        val spoken = UltraState.entries.map(::ultraStateText)
        assertEquals(spoken.size, spoken.toSet().size)
        assertEquals("On but suspended — the permission level is Ask first", ultraStateText(UltraState.Suspended))
    }
}
