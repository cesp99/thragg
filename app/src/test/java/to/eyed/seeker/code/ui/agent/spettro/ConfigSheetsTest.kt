package to.eyed.seeker.code.ui.agent.spettro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.seeker.code.core.AgentConfigOption
import to.eyed.seeker.code.core.AgentSessionState

/**
 * The selector sheet's list building and its two footer sentences.
 *
 * Both halves are places where a plausible shortcut is wrong. Flattening a
 * grouped model list loses the provider a model belongs to, which is the only
 * thing that distinguishes `gpt-5` on OpenAI from `gpt-5` on OpenRouter; and
 * omitting the permission footers hides the two consequences of a permission
 * change that are not visible anywhere in the permission list.
 */
class ConfigSheetsTest {

    private fun option(json: String) =
        AgentSessionState.parse("""{"phase":"ready","configOptions":[$json]}""")
            .configOptions
            .single()

    // --- sections ------------------------------------------------------------

    /** Groups survive as sections, in the agent's order, headers intact. */
    @Test
    fun aGroupedModelListBecomesOneSectionPerProvider() {
        val model = option(
            """{"id":"model","name":"Model","type":"select","category":"model",
                "currentValue":"anthropic:sonnet","options":[
                  {"group":"anthropic","name":"Anthropic","options":[
                    {"name":"Claude Sonnet 4.5","value":"anthropic:sonnet"},
                    {"name":"Claude Opus 4.1","value":"anthropic:opus"}]},
                  {"group":"lmstudio","name":"LM Studio (local)","options":[
                    {"name":"qwen3-coder-30b","value":"lmstudio:qwen3"}]}]}"""
        )
        val sections = sheetSections(model)
        assertEquals(listOf("Anthropic", "LM Studio (local)"), sections.map { it.header })
        assertEquals(2, sections[0].choices.size)
        assertEquals("lmstudio:qwen3", sections[1].choices.single().value)
    }

    /** A flat list is one headerless section, not a section per choice. */
    @Test
    fun aFlatListIsOneSectionWithNoHeader() {
        val permission = option(
            """{"id":"permission","name":"Permission","type":"select","currentValue":"restricted",
                "options":[{"name":"Ask first","value":"ask-first","description":"Prompt first"},
                           {"name":"Restricted","value":"restricted"},
                           {"name":"YOLO","value":"yolo"}]}"""
        )
        val section = sheetSections(permission).single()
        assertEquals(null, section.header)
        assertEquals(listOf("ask-first", "restricted", "yolo"), section.choices.map { it.value })
        assertEquals("Prompt first", section.choices.first().description)
    }

    /**
     * A connected provider that has published no models is a header over
     * nothing, which reads as a loading bug. Drop the band instead.
     */
    @Test
    fun anEmptyGroupIsDroppedRatherThanDrawnAsAnEmptyBand() {
        val model = option(
            """{"id":"model","name":"Model","type":"select","category":"model","options":[
                 {"group":"a","name":"Anthropic","options":[{"name":"S","value":"s"}]},
                 {"group":"b","name":"OpenAI","options":[]}]}"""
        )
        assertEquals(listOf("Anthropic"), sheetSections(model).map { it.header })
    }

    /** No choices at all is an empty list, and the sheet says so itself. */
    @Test
    fun anOptionWithNoChoicesHasNoSections() {
        assertEquals(
            emptyList<SheetSection>(),
            sheetSections(option("""{"id":"model","name":"Model","type":"select","category":"model"}""")),
        )
        // A boolean never opens a sheet; if one is passed anyway it lands on
        // the same empty state rather than drawing a two-row radio group.
        assertEquals(
            emptyList<SheetSection>(),
            sheetSections(option("""{"id":"ultra","name":"Ultra","type":"boolean","currentValue":true}""")),
        )
    }

    // --- internal roles ------------------------------------------------------

    /**
     * `worker` and `subagent` are what a fan-out puts its children into.
     * Offering them to a person makes a top-level conversation answer as
     * somebody's child.
     */
    @Test
    fun internalModeRolesAreNotOfferedAsModes() {
        val mode = option(
            """{"id":"mode","name":"Mode","type":"select","category":"mode","currentValue":"coding",
                "options":[{"name":"Plan","value":"plan"},{"name":"Coding","value":"coding"},
                           {"name":"Ask","value":"ask"},{"name":"Worker","value":"worker"},
                           {"name":"Subagent","value":"subagent"}]}"""
        )
        assertEquals(
            listOf("plan", "coding", "ask"),
            sheetSections(mode).single().choices.map { it.value },
        )
    }

    /**
     * Except the one you are in. A sheet with no selection at all reads as
     * broken, and a session really can be parked in a role by a slash command.
     */
    @Test
    fun anInternalRoleThatIsTheCurrentValueStaysVisible() {
        val mode = option(
            """{"id":"mode","name":"Mode","type":"select","category":"mode","currentValue":"worker",
                "options":[{"name":"Plan","value":"plan"},{"name":"Worker","value":"worker"},
                           {"name":"Subagent","value":"subagent"}]}"""
        )
        assertEquals(
            listOf("plan", "worker"),
            sheetSections(mode).single().choices.map { it.value },
        )
    }

    /** The filter is the mode list's alone — a model called `worker` is a model. */
    @Test
    fun theFilterDoesNotLeakIntoOtherOptions() {
        val model = option(
            """{"id":"model","name":"Model","type":"select","category":"model",
                "options":[{"name":"Worker","value":"worker"}]}"""
        )
        assertEquals(listOf("worker"), sheetSections(model).single().choices.map { it.value })
    }

    // --- opening position ----------------------------------------------------

    /**
     * The sheet opens on the current value. Item 0 is the subtitle and each
     * header takes one item, so this arithmetic is where the off-by-one would
     * live.
     */
    @Test
    fun theOpeningRowCountsHeadersAndTheSubtitle() {
        val sections = listOf(
            SheetSection("Anthropic", choices("a1", "a2")),
            SheetSection("OpenAI", choices("o1")),
        )
        assertEquals(2, currentRowIndex(sections, "a1"))
        assertEquals(3, currentRowIndex(sections, "a2"))
        assertEquals(5, currentRowIndex(sections, "o1"))
        // Unresolvable, or nothing set: stay at the top rather than guessing.
        assertEquals(0, currentRowIndex(sections, "gone"))
        assertEquals(0, currentRowIndex(sections, null))
    }

    @Test
    fun aFlatListHasNoHeaderToCount() {
        val sections = listOf(SheetSection(null, choices("x", "y")))
        assertEquals(1, currentRowIndex(sections, "x"))
        assertEquals(2, currentRowIndex(sections, "y"))
    }

    /**
     * An option the agent described has a subtitle row; one it did not has
     * none, and every row below moves up by one. Getting this wrong opens the
     * sheet on the model *above* the current one, which reads as correct.
     */
    @Test
    fun anOptionWithNoDescriptionHasNoSubtitleRowToSkip() {
        val sections = listOf(SheetSection("Anthropic", choices("a1", "a2")))
        assertEquals(2, currentRowIndex(sections, "a1", leading = 1))
        assertEquals(1, currentRowIndex(sections, "a1", leading = 0))
        assertEquals(2, currentRowIndex(sections, "a2", leading = 0))
    }

    // --- footers -------------------------------------------------------------

    /** At the bottom level, raising is what unlocks Ultra and workflows. */
    @Test
    fun askFirstIsToldWhatRaisingWouldUnlock() {
        assertEquals(
            listOf(RAISE_UNLOCKS),
            permissionFooters(permission("ask-first"), ultraStored = false),
        )
        assertEquals(
            listOf(RAISE_UNLOCKS),
            permissionFooters(permission("ask-first"), ultraStored = true),
        )
    }

    /**
     * Above the bottom level with Ultra stored on, the warning is the other
     * direction: lowering suspends it rather than clearing it, and the chip
     * will still read on afterwards.
     */
    @Test
    fun aStoredUltraIsWarnedAboutLoweringAndOnlyThen() {
        assertEquals(
            listOf(LOWER_SUSPENDS),
            permissionFooters(permission("restricted"), ultraStored = true),
        )
        assertEquals(
            emptyList<String>(),
            permissionFooters(permission("restricted"), ultraStored = false),
        )
    }

    /** They are mutually exclusive: never two sentences at once. */
    @Test
    fun theTwoFootersNeverAppearTogether() {
        for (current in listOf("ask-first", "restricted", "yolo")) {
            for (stored in listOf(true, false)) {
                assertTrue(permissionFooters(permission(current), stored).size <= 1)
            }
        }
    }

    /** No `ask-first` in the agent's own list means nothing to lower to. */
    @Test
    fun noAskFirstChoiceMeansNoLoweringWarning() {
        val permission = option(
            """{"id":"permission","name":"Permission","type":"select","currentValue":"yolo",
                "options":[{"name":"Restricted","value":"restricted"},{"name":"YOLO","value":"yolo"}]}"""
        )
        assertEquals(emptyList<String>(), permissionFooters(permission, ultraStored = true))
    }

    /** Only the Permission sheet carries them. */
    @Test
    fun otherOptionsGetNoFooters() {
        val model = option(
            """{"id":"model","name":"Model","type":"select","category":"model",
                "currentValue":"ask-first","options":[{"name":"A","value":"ask-first"}]}"""
        )
        assertEquals(emptyList<String>(), permissionFooters(model, ultraStored = true))
    }

    // --- helpers -------------------------------------------------------------

    private fun choices(vararg values: String) =
        values.map { AgentConfigOption.Choice(it, it) }

    private fun permission(current: String) = option(
        """{"id":"permission","name":"Permission","type":"select","currentValue":"$current",
            "options":[{"name":"Ask first","value":"ask-first"},
                       {"name":"Restricted","value":"restricted"},
                       {"name":"YOLO","value":"yolo"}]}"""
    )
}
