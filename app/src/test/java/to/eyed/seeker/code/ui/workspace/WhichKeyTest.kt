package to.eyed.seeker.code.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The which-key overlay's rows: what a half-pressed chord could become. */
class WhichKeyTest {

    private fun binding(
        context: KeymapContext,
        keystrokes: String,
        action: String?,
        index: Int,
    ) = KeyBinding(
        context = context,
        keystrokes = Keystroke.parseSequence(keystrokes)!!,
        action = action,
        args = null,
        source = KeybindSource.Default,
        index = index,
    )

    private val chain = KeymapContext.chainTo(KeymapContext.Editor)

    @Test
    fun theCompletionsAreTheStrokesLeftAndWhatTheyWouldDo() {
        val keymap = Keymap(
            listOf(
                binding(KeymapContext.Workspace, "ctrl-k ctrl-0", "zed::ResetBufferFontSize", 0),
                binding(KeymapContext.Workspace, "ctrl-k ctrl-s", "zed::OpenKeymap", 1),
                binding(KeymapContext.Workspace, "ctrl-s", "workspace::Save", 2),
            )
        )
        val rows = keymap.completions(Keystroke.parseSequence("ctrl-k")!!, chain)
        assertEquals(
            listOf(
                ChordCompletion("Ctrl 0", "zed::ResetBufferFontSize"),
                ChordCompletion("Ctrl S", "zed::OpenKeymap"),
            ),
            rows,
        )
        // A chord that no binding starts has nothing to offer, and neither
        // does an empty prefix — nothing is pending.
        assertTrue(keymap.completions(Keystroke.parseSequence("ctrl-j")!!, chain).isEmpty())
        assertTrue(keymap.completions(emptyList(), chain).isEmpty())
    }

    @Test
    fun anUnbindingIsNotAWayOut() {
        val keymap = Keymap(
            listOf(
                binding(KeymapContext.Workspace, "ctrl-k ctrl-0", "zed::ResetBufferFontSize", 0),
                binding(KeymapContext.Editor, "ctrl-k ctrl-s", null, 1),
            )
        )
        assertEquals(
            listOf(ChordCompletion("Ctrl 0", "zed::ResetBufferFontSize")),
            keymap.completions(Keystroke.parseSequence("ctrl-k")!!, chain),
        )
    }

    @Test
    fun aShadowedBindingIsNotOfferedEither() {
        val keymap = Keymap(
            listOf(
                // The editor's binding wins on these keys, so listing the
                // workspace's would promise something that will not happen.
                binding(KeymapContext.Workspace, "ctrl-k ctrl-w", "workspace::CloseWindow", 0),
                binding(KeymapContext.Editor, "ctrl-k ctrl-w", "editor::DeleteLine", 1),
            )
        )
        assertEquals(
            listOf(ChordCompletion("Ctrl W", "editor::DeleteLine")),
            keymap.completions(Keystroke.parseSequence("ctrl-k")!!, chain),
        )
    }

    @Test
    fun onlyTheContextsInForceAreOffered() {
        val keymap = Keymap(
            listOf(
                binding(KeymapContext.Workspace, "ctrl-k ctrl-0", "zed::ResetBufferFontSize", 0),
                binding(KeymapContext.Terminal, "ctrl-k ctrl-c", "terminal::Copy", 1),
            )
        )
        assertEquals(
            listOf(ChordCompletion("Ctrl 0", "zed::ResetBufferFontSize")),
            keymap.completions(Keystroke.parseSequence("ctrl-k")!!, chain),
        )
        assertEquals(
            listOf(ChordCompletion("Ctrl C", "terminal::Copy")),
            keymap.completions(
                Keystroke.parseSequence("ctrl-k")!!,
                KeymapContext.chainTo(KeymapContext.Terminal),
            ),
        )
    }

    @Test
    fun theShippedKeymapsChordsAllHaveSomethingToOffer() {
        val keymap = DefaultKeymap.keymap()
        val pending = Keystroke.parseSequence("ctrl-k")!!
        val rows = keymap.completions(pending, chain)
        // Ctrl+K is the app's one real chord prefix; if it ever offered
        // nothing, the overlay would never appear at all.
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.keys.isNotEmpty() && it.action.isNotEmpty() })
    }

    @Test
    fun theAnnouncementCountsTheWaysOut() {
        assertEquals("Ctrl K pressed. One way to finish it.", whichKeyAnnouncement("Ctrl K", 1))
        assertEquals("Ctrl K pressed. 4 ways to finish it.", whichKeyAnnouncement("Ctrl K", 4))
    }
}
