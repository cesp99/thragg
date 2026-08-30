package to.eyed.seeker.code.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Zed's two query rewrites in front of the command palette:
 * `command_aliases` (command_palette.rs:471-474) and
 * `normalize_action_query` (:48-67).
 */
class CommandAliasesTest {

    private val aliases = mapOf(
        "W" to "workspace::Save",
        "term" to "terminal_panel::Toggle",
    )

    @Test
    fun theWholeQueryHasToMatchAnAlias() {
        assertEquals("workspace::Save", resolveCommandAlias("W", aliases))
        assertEquals("terminal_panel::Toggle", resolveCommandAlias("term", aliases))
        // A prefix is not a match: a one-letter alias that swallowed every
        // query starting with it would be unusable.
        assertEquals("Wr", resolveCommandAlias("Wr", aliases))
        assertEquals("terminal", resolveCommandAlias("terminal", aliases))
        // Zed's lookup is on a `HashMap<String, _>`, so case counts.
        assertEquals("w", resolveCommandAlias("w", aliases))
        // Surrounding space is the soft keyboard's, not the user's.
        assertEquals("workspace::Save", resolveCommandAlias("  W ", aliases))
        assertEquals("save", resolveCommandAlias("save", emptyMap()))
    }

    @Test
    fun anActionNameIsNormalisedIntoAHumanisedOne() {
        // The exact rewrite Zed documents: underscores become spaces and the
        // second colon goes, so `terminal_panel::Toggle` can match
        // "terminal panel: toggle".
        assertEquals("terminal panel:Toggle", normalizeActionQuery("terminal_panel::Toggle"))
        assertEquals("workspace:Save", normalizeActionQuery("workspace::Save"))
        // Runs of whitespace collapse; the trim is Zed's too.
        assertEquals("go to line", normalizeActionQuery("  go   to  line  "))
        // Case is left alone — the matcher decides about case for itself.
        assertEquals("Save", normalizeActionQuery("Save"))
        assertEquals("", normalizeActionQuery("   "))
    }

    @Test
    fun anAliasedActionNameFindsTheCommand() {
        // The whole pipeline as the palette runs it: alias, then normalise,
        // then match. "W" has to end up on `workspace: save` and nothing else.
        val entries = paletteEntries(context(), aliases = aliases)
        val query = normalizeActionQuery(resolveCommandAlias("W", aliases))
        val best = matchCommands(entries, query).firstOrNull()
        assertEquals("workspace::Save", best?.entry?.command?.id)
    }

    @Test
    fun anAliasIsPrintedBesideTheCommandItReaches() {
        val entries = paletteEntries(context(), aliases = aliases)
        val save = entries.first { it.command == WorkspaceCommand.Save }
        assertEquals("W", save.alias)
        // Every other row is unmarked: an alias belongs to one action.
        assertNull(entries.first { it.command == WorkspaceCommand.CloseTab }.alias)
        // And with no aliases configured, nothing is marked at all.
        assertNull(
            paletteEntries(context()).first { it.command == WorkspaceCommand.Save }.alias
        )
    }

    private fun context() = CommandContext(
        hasProject = true,
        hasActiveFile = true,
        hasActiveBuffer = true,
        tabCount = 1,
        terminalCount = 0,
        canClone = true,
    )
}
