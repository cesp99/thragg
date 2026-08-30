package to.eyed.seeker.code.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which chord the palette and the menus print beside a command.
 *
 * The bug this pins: a label is resolved against a *chain* of active
 * contexts, and reading every command against the editor's chain printed
 * nothing at all for the ones bound where the editor never is. `AgentPanel`
 * sits beside `Pane`, not under it, so every agent-panel command came out
 * blank.
 */
class ChordLabelTest {

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

    @Test
    fun aCommandBoundOnlyInTheAgentPanelStillPrintsItsChord() {
        val keymap = Keymap(
            listOf(
                binding(KeymapContext.Workspace, "ctrl-s", "workspace::Save", 0),
                binding(KeymapContext.AgentPanel, "ctrl-n", "agent::NewThread", 1),
            )
        )
        // The editor's chain alone — what this used to do — cannot see it.
        assertEquals(
            null,
            keymap.labelFor("agent::NewThread", KeymapContext.chainTo(KeymapContext.Editor)),
        )
        // Asking the keymap where the command lives, and reading there, can.
        assertEquals(
            listOf("Ctrl N"),
            keymap.labelsAcross(
                "agent::NewThread",
                chainsFor(keymap.contextsFor("agent::NewThread")),
            ),
        )
        // And the ordinary case is unchanged.
        assertEquals(
            listOf("Ctrl S"),
            keymap.labelsAcross("workspace::Save", chainsFor(keymap.contextsFor("workspace::Save"))),
        )
    }

    @Test
    fun aChordAnotherBindingShadowsIsNotPrinted() {
        val keymap = Keymap(
            listOf(
                // A workspace binding, and a deeper editor binding on the same
                // keys. Pressing Ctrl+N in the editor runs the editor's, so
                // printing "Ctrl N" beside the workspace's would be a lie.
                binding(KeymapContext.Workspace, "ctrl-n", "workspace::NewFile", 0),
                binding(KeymapContext.Editor, "ctrl-n", "editor::MoveDown", 1),
            )
        )
        assertEquals(
            emptyList<String>(),
            keymap.labelsAcross("workspace::NewFile", chainsFor(keymap.contextsFor("workspace::NewFile"))),
        )
        assertEquals(
            listOf("Ctrl N"),
            keymap.labelsAcross("editor::MoveDown", chainsFor(keymap.contextsFor("editor::MoveDown"))),
        )
    }

    @Test
    fun aTerminalPaletteOnlyPrintsWhatAShellCanHear() {
        val keymap = Keymap(
            listOf(
                binding(KeymapContext.Workspace, "ctrl-s", "workspace::Save", 0),
                binding(KeymapContext.Terminal, "ctrl-shift-s", "workspace::Save", 1),
            )
        )
        // In the workspace, both chords reach Save — the workspace's first,
        // because that is the one to learn there.
        assertEquals(
            listOf("Ctrl S", "Ctrl Shift S"),
            keymap.labelsAcross("workspace::Save", chainsFor(keymap.contextsFor("workspace::Save"))),
        )
        // While a shell has the keyboard, only the Terminal binding exists:
        // printing Ctrl+S there would be teaching the user to send XOFF.
        assertEquals(
            listOf("Ctrl Shift S"),
            keymap.labelsAcross(
                "workspace::Save",
                chainsFor(keymap.contextsFor("workspace::Save"), Focus.Terminal),
            ),
        )
    }

    @Test
    fun aPicturesChordsAreReadWithAPictureUp() {
        val keymap = Keymap(
            listOf(
                binding(KeymapContext.Workspace, "ctrl-0", "zed::ResetBufferFontSize", 0),
                binding(KeymapContext.ImageViewer, "ctrl-0", "image_viewer::ResetZoom", 1),
            )
        )
        assertEquals(
            listOf("Ctrl 0"),
            keymap.labelsAcross(
                "image_viewer::ResetZoom",
                chainsFor(keymap.contextsFor("image_viewer::ResetZoom")),
            ),
        )
    }

    @Test
    fun anUnboundCommandHasNoChordAndAsksNothingOfTheKeymap() {
        val keymap = Keymap(emptyList())
        assertTrue(keymap.contextsFor("workspace::Save").isEmpty())
        assertEquals(
            emptyList<String>(),
            keymap.labelsAcross("workspace::Save", chainsFor(emptySet())),
        )
    }

    @Test
    fun theShippedKeymapPrintsAChordForEveryContextItBindsIn() {
        // The regression in the field: with the real default keymap, no
        // command that has a binding may come out blank.
        val keymap = DefaultKeymap.keymap()
        val blank = keymap.bindings
            .mapNotNull { it.action }
            .distinct()
            .filter { action ->
                keymap.labelsAcross(action, chainsFor(keymap.contextsFor(action))).isEmpty()
            }
            // A shadowed binding legitimately prints nothing; only actions
            // that win *somewhere* are the ones under test.
            .filter { action ->
                keymap.bindings.any { binding ->
                    binding.action == action &&
                        keymap.resolve(
                            binding.keystrokes,
                            KeymapContext.chainTo(binding.context),
                        ).let { it is Resolution.Matched && it.binding == binding }
                }
            }
        assertEquals(emptyList<String>(), blank)
    }
}
