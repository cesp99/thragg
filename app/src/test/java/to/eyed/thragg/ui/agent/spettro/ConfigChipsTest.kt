package to.eyed.thragg.ui.agent.spettro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.thragg.core.AgentSessionState
import to.eyed.thragg.core.SpettroToolbar
import to.eyed.thragg.core.UltraState

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
        val mode = chipIcon(
            option("""{"id":"stance","name":"Stance","type":"select","category":"mode"}""")
        )
        val model = chipIcon(
            option("""{"id":"model","name":"Model","type":"select","category":"model"}""")
        )
        val thinking = chipIcon(
            option("""{"id":"thinking","name":"T","type":"select","category":"thought_level"}""")
        )
        val permission = chipIcon(option("""{"id":"permission","name":"P","type":"select"}"""))
        val ultra = chipIcon(option("""{"id":"ultra","name":"U","type":"boolean"}"""))
        val fallback = chipIcon(option("""{"id":"sandbox","name":"S","type":"select"}"""))

        // Six distinct marks. Resource ids are generated numbers, so what is
        // pinned here is that the six cases stay *told apart* — the failure
        // this catches is a chip row where mode, model and thinking all draw
        // the same shape and the row stops saying anything.
        assertEquals(
            6,
            setOf(mode, model, thinking, permission, ultra, fallback).size,
        )
        // `category` wins over `id`: an agent that renames `mode` to `stance`
        // keeps the mode mark rather than falling through to the diamond.
        assertNotEquals(fallback, mode)
        // And a category nobody knows lands on the fallback rather than on
        // one of the five that mean something.
        assertEquals(
            fallback,
            chipIcon(option("""{"id":"x","name":"X","type":"select","category":"weather"}""")),
        )
    }

    // --- the summary line ----------------------------------------------------

    /**
     * The summary reads in the order the answer is wanted, which is NOT the
     * sheet's order: `chipOrder` puts Ultra first because it is the charged
     * control and must not need a scroll, and a sentence has neither a scroll
     * nor an Ultra — the amber dot carries that.
     */
    @Test
    fun theSummaryReadsModeModelPermissionThinking() {
        val toolbar = SpettroToolbar(
            options(
                """{"phase":"ready","configOptions":[
                    {"id":"ultra","name":"Ultra","type":"boolean","currentValue":true},
                    {"id":"thinking","name":"Thinking","type":"select",
                     "category":"thought_level","currentValue":"high"},
                    {"id":"permission","name":"Permission","type":"select",
                     "currentValue":"ask-first",
                     "options":[{"name":"Ask first","value":"ask-first"}]},
                    {"id":"model","name":"Model","type":"select","category":"model",
                     "currentValue":"sonnet-4-6"},
                    {"id":"mode","name":"Mode","type":"select","category":"mode",
                     "currentValue":"coding"}]}"""
            )
        )
        assertEquals("coding · sonnet-4-6 · Ask first · high", configSummary(toolbar))
    }

    /**
     * A selector the agent grows later appears at the end rather than
     * disappearing, and booleans stay out: "Ultra Off" in the middle of the
     * line spends three of its forty characters on the default state.
     */
    @Test
    fun anUnknownSelectFollowsAndBooleansAreLeftOut() {
        val toolbar = SpettroToolbar(
            options(
                """{"phase":"ready","configOptions":[
                    {"id":"sandbox","name":"Sandbox","type":"select","currentValue":"strict"},
                    {"id":"voice","name":"Voice","type":"boolean","currentValue":false},
                    {"id":"mode","name":"Mode","type":"select","category":"mode",
                     "currentValue":"plan"}]}"""
            )
        )
        assertEquals("plan · strict", configSummary(toolbar))
    }

    /** Nothing advertised is an empty line, and the row draws nothing at all. */
    @Test
    fun anAgentThatHasSaidNothingHasNoSummary() {
        assertEquals("", configSummary(SpettroToolbar(emptyList())))
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

    // --- the composer chip ---------------------------------------------------

    /**
     * The chip prints the model, because the model is the one selector whose
     * value is not recoverable from memory. The mode and the permission level
     * are still *reachable* — the sheet is a tap away — and they are still
     * *announced*, which is what [configChipState] is for.
     */
    @Test
    fun theChipPrintsTheModelAndTheThinkingLevelBesideIt() {
        val toolbar = SpettroToolbar(
            options(
                """{"phase":"ready","configOptions":[
                    {"id":"mode","name":"Mode","type":"select","category":"mode",
                     "currentValue":"coding"},
                    {"id":"model","name":"Model","type":"select","category":"model",
                     "currentValue":"sonnet-4-6"},
                    {"id":"thinking","name":"Thinking","type":"select",
                     "category":"thought_level","currentValue":"high"}]}"""
            )
        )
        assertEquals("sonnet-4-6", configChipLabel(toolbar))
        assertEquals("high", configChipEffort(toolbar))
    }

    /**
     * `off` earns no ink: it is the default, and the chip's whole budget is
     * the width the model name is not using.
     */
    @Test
    fun thinkingOffPrintsNothingBesideTheModel() {
        val toolbar = SpettroToolbar(
            options(
                """{"phase":"ready","configOptions":[
                    {"id":"model","name":"Model","type":"select","category":"model",
                     "currentValue":"sonnet-4-6"},
                    {"id":"thinking","name":"Thinking","type":"select",
                     "category":"thought_level","currentValue":"off",
                     "options":[{"name":"Off","value":"off"}]}]}"""
            )
        )
        assertNull(configChipEffort(toolbar))
    }

    /**
     * An agent with no `model` still gets a chip, because the chip is the only
     * route to the config sheet from the composer. A boolean is never it: "On"
     * on its own names nothing.
     */
    @Test
    fun anAgentWithoutAModelFallsBackToItsFirstSelector() {
        val toolbar = SpettroToolbar(
            options(
                """{"phase":"ready","configOptions":[
                    {"id":"verbose","name":"Verbose","type":"boolean","currentValue":true},
                    {"id":"sandbox","name":"Sandbox","type":"select","currentValue":"strict"}]}"""
            )
        )
        assertEquals("strict", configChipLabel(toolbar))
    }

    /** Nothing on the wire is no chip at all, rather than an empty pill. */
    @Test
    fun anAgentThatHasSaidNothingHasNoChip() {
        assertNull(configChipLabel(SpettroToolbar(emptyList())))
    }

    /**
     * THE CHIP ANNOUNCES EVERYTHING THE SUMMARY LINE DID. Sighted users traded
     * two readouts for a shorter row; a screen reader user would only have
     * lost the mode and the permission level, so the spoken state keeps the
     * whole sentence and appends Ultra's gating in words.
     */
    @Test
    fun theSpokenStateKeepsTheModeThePermissionAndUltra() {
        val toolbar = SpettroToolbar(
            options(
                """{"phase":"ready","configOptions":[
                    {"id":"mode","name":"Mode","type":"select","category":"mode",
                     "currentValue":"coding"},
                    {"id":"model","name":"Model","type":"select","category":"model",
                     "currentValue":"sonnet-4-6"},
                    {"id":"permission","name":"Permission","type":"select",
                     "currentValue":"ask-first",
                     "options":[{"name":"Ask first","value":"ask-first"}]},
                    {"id":"thinking","name":"Thinking","type":"select",
                     "category":"thought_level","currentValue":"high"},
                    {"id":"ultra","name":"Ultra","type":"boolean","currentValue":true}]}"""
            )
        )
        // Stored-on under ask-first is Suspended, which is exactly the state a
        // dot cannot express.
        assertEquals(
            "coding · sonnet-4-6 · Ask first · high. Ultra: " +
                "On but suspended — the permission level is Ask first",
            configChipState(toolbar),
        )
    }

    /** Ultra off says nothing about Ultra: the dot is not drawn either. */
    @Test
    fun theSpokenStateLeavesUltraOutWhenItIsOff() {
        val toolbar = SpettroToolbar(
            options(
                """{"phase":"ready","configOptions":[
                    {"id":"model","name":"Model","type":"select","category":"model",
                     "currentValue":"sonnet-4-6"},
                    {"id":"permission","name":"Permission","type":"select",
                     "currentValue":"yolo","options":[{"name":"YOLO","value":"yolo"}]},
                    {"id":"ultra","name":"Ultra","type":"boolean","currentValue":false}]}"""
            )
        )
        assertEquals("sonnet-4-6 · YOLO", configChipState(toolbar))
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
    /**
     * The amber dot means Ultra is *engaged*, not merely "not off".
     *
     * `Locked` is the resting state of any default `ask-first` session, so the
     * first cut lit the dot on more or less every screen at rest and it
     * stopped carrying information. Locked stays in the spoken state, where a
     * sentence can explain itself; it does not get a mark.
     */
    @Test
    fun theUltraDotSkipsTheStateEverySessionStartsIn() {
        assertTrue(ultraEngaged(UltraState.On))
        assertTrue(ultraEngaged(UltraState.Suspended))
        assertFalse(ultraEngaged(UltraState.Locked))
        assertFalse(ultraEngaged(UltraState.Off))
    }

}
