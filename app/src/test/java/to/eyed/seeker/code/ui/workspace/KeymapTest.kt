package to.eyed.seeker.code.ui.workspace

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keymap's platform half: a keystroke's Zed spelling against the four
 * facts an Android key event carries, and the precedence and chord rules
 * the engine's list is read with. The engine tests cover parsing the file;
 * these cover what the app does with the result. Only `KeyEvent`'s
 * constants are touched — they are compile-time — so no Android runtime is
 * needed.
 */
class KeymapTest {

    private fun press(keyCode: Int, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) =
        Keystroke.of(keyCode, ctrl, alt, shift)

    @Test
    fun aKeyEventMatchesItsZedSpelling() {
        assertEquals(Keystroke.parse("ctrl-shift-s"), press(KeyEvent.KEYCODE_S, ctrl = true, shift = true))
        assertEquals(Keystroke.parse("alt-up"), press(KeyEvent.KEYCODE_DPAD_UP, alt = true))
        assertEquals(Keystroke.parse("f12"), press(KeyEvent.KEYCODE_F12))
        assertEquals(Keystroke.parse("ctrl-`"), press(KeyEvent.KEYCODE_GRAVE, ctrl = true))
        assertEquals(Keystroke.parse("ctrl-alt-shift--"), press(KeyEvent.KEYCODE_MINUS, ctrl = true, alt = true, shift = true))
        assertEquals(Keystroke.parse("pagedown"), press(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(Keystroke.parse("backspace"), press(KeyEvent.KEYCODE_DEL))
        assertEquals(Keystroke.parse("delete"), press(KeyEvent.KEYCODE_FORWARD_DEL))
        // A shifted stroke is not the unshifted one.
        assertFalse(Keystroke.parse("ctrl-s") == press(KeyEvent.KEYCODE_S, ctrl = true, shift = true))
    }

    @Test
    fun bothEnterKeysAreEnter() {
        assertEquals(Keystroke.parse("enter"), press(KeyEvent.KEYCODE_ENTER))
        assertEquals(Keystroke.parse("enter"), press(KeyEvent.KEYCODE_NUMPAD_ENTER))
    }

    @Test
    fun aModifierOnItsOwnIsNoKeystroke() {
        assertNull(press(KeyEvent.KEYCODE_CTRL_LEFT, ctrl = true))
        assertNull(press(KeyEvent.KEYCODE_SHIFT_RIGHT, shift = true))
        assertNull(press(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun theSpellingRoundTripsAndPrintsAsTheMenusDo() {
        val stroke = Keystroke.parse("ctrl-alt-shift-[")!!
        assertEquals("ctrl-alt-shift-[", stroke.toString())
        assertEquals("Ctrl Alt Shift [", stroke.label)
        assertEquals("Ctrl K, Ctrl 0", Keystroke.parseSequence("ctrl-k ctrl-0")!!.joinToString(", ") { it.label })
        assertEquals("Shift F8", Keystroke.parse("shift-f8")!!.label)
        assertEquals("↑", Keystroke.parse("up")!!.label)
        assertEquals("Esc", Keystroke.parse("escape")!!.label)
    }

    @Test
    fun whatTheEngineWouldRefuseIsNullHere() {
        assertNull(Keystroke.parse("cmd-s"))
        assertNull(Keystroke.parse("ctrl-bogus"))
        assertNull(Keystroke.parse("ctrl-"))
        assertNull(Keystroke.parse(""))
        assertNull(Keystroke.parseSequence("   "))
        assertNull(Keystroke.parseSequence("ctrl-k nope"))
        // `ctrl--` is Ctrl and the minus key.
        assertEquals("-", Keystroke.parse("ctrl--")!!.key)
    }

    @Test
    fun theDefaultKeymapIsWhollyExpressibleOnThisPlatform() {
        // Every default binding names keys this side can hear, and the
        // rendered file has a section per context with every stroke in it.
        val keymap = DefaultKeymap.keymap()
        assertTrue(keymap.bindings.isNotEmpty())
        val text = DefaultKeymap.text()
        assertTrue(text.contains("\"context\": \"Editor\""))
        assertTrue(text.contains("\"ctrl-k ctrl-s\": \"zed::OpenKeymap\""))
        assertTrue(text.contains("[\"pane::ActivateItem\", 0]"))
    }

    /**
     * The one rule about the default table a compiler cannot check
     * (agent-docs/CONVENTIONS.md): the clipboard and selection chords belong
     * to whatever has focus — the editor, the project panel, a text field —
     * and a Workspace binding on one of them would not lose an argument, it
     * would win one, silently. The editor's own copies live in the deeper
     * `Editor` context, which is the point of contexts.
     */
    @Test
    fun noWorkspaceBindingTakesAClipboardChord() {
        val clipboardAndSelection = setOf("a", "c", "v", "x", "z")
        for (section in DefaultKeymap.sections) {
            if (section.context == KeymapContext.Editor || section.context == KeymapContext.Terminal) continue
            for (binding in section.bindings) {
                val first = Keystroke.parseSequence(binding.keystrokes)!!.first()
                assertFalse(
                    "${binding.action} is on ${binding.keystrokes}, which is an editor-local chord",
                    first.ctrl && !first.alt && first.key in clipboardAndSelection,
                )
            }
        }
    }

    @Test
    fun theEngineListIsReadInOrderWithNullsKept() {
        val (keymap, errors) = Keymap.parse(
            """
            {"bindings":[
              {"context":"Workspace","keystrokes":"ctrl-s","action":"workspace::Save","args":null,"source":"default"},
              {"context":"Editor","keystrokes":"ctrl-k ctrl-0","action":"editor::FoldAll","args":null,"source":"default"},
              {"context":"Workspace","keystrokes":"ctrl-1","action":"pane::ActivateItem","args":0,"source":"default"},
              {"context":"Workspace","keystrokes":"ctrl-s","action":null,"args":null,"source":"user"},
              {"context":"Editor","keystrokes":"ctrl-nope","action":"editor::Undo","args":null,"source":"user"}
            ],"errors":["keymap.json: \"ctrl-q\": \"zed::Nope\" is not an action this app has"]}
            """
        )
        assertEquals(4, keymap.bindings.size)
        assertEquals(KeybindSource.User, keymap.bindings[3].source)
        assertNull(keymap.bindings[3].action)
        assertEquals(0, keymap.bindings[2].intArg)
        assertEquals(2, errors.size)
        assertTrue(errors[1].contains("ctrl-nope"))
    }

    private fun binding(context: KeymapContext, strokes: String, action: String?, index: Int, source: KeybindSource = KeybindSource.Default) =
        KeyBinding(context, Keystroke.parseSequence(strokes)!!, action, null, source, index)

    @Test
    fun deeperContextsAndLaterBindingsWin() {
        val keymap = Keymap(
            listOf(
                binding(KeymapContext.Workspace, "ctrl-shift-enter", "workspace::OpenWithSystem", 0),
                binding(KeymapContext.Editor, "ctrl-shift-enter", "editor::NewlineAbove", 1),
                binding(KeymapContext.Workspace, "ctrl-p", "file_finder::Toggle", 2),
                binding(KeymapContext.Workspace, "ctrl-p", null, 3, KeybindSource.User),
            )
        )
        val editing = listOf(KeymapContext.Global, KeymapContext.Workspace, KeymapContext.Pane, KeymapContext.Editor)
        val notEditing = listOf(KeymapContext.Global, KeymapContext.Workspace, KeymapContext.Pane)
        val enter = listOf(Keystroke.parse("ctrl-shift-enter")!!)
        assertEquals("editor::NewlineAbove", (keymap.resolve(enter, editing) as Resolution.Matched).binding.action)
        assertEquals("workspace::OpenWithSystem", (keymap.resolve(enter, notEditing) as Resolution.Matched).binding.action)
        // The user's null outranks the default and prints nothing.
        val p = keymap.resolve(listOf(Keystroke.parse("ctrl-p")!!), editing) as Resolution.Matched
        assertNull(p.binding.action)
        assertNull(keymap.labelFor("file_finder::Toggle", editing))
        // A shadowed binding is not printed as the chord for its action.
        assertEquals("Ctrl Shift Enter", keymap.labelFor("editor::NewlineAbove", editing))
        assertNull(keymap.labelFor("workspace::OpenWithSystem", editing))
        assertEquals("Ctrl Shift Enter", keymap.labelFor("workspace::OpenWithSystem", notEditing))
    }

    /**
     * Zed's `ImageViewer` context (default-linux.json:1566-1574): a picture
     * as the open tab takes `ctrl-=` and `ctrl-1` from the UI font size and
     * the first tab, and gives them back the moment it is not.
     */
    @Test
    fun aPictureWinsTheZoomChordsOnlyWhileItIsUp() {
        val keymap = DefaultKeymap.keymap()
        val picture = KeymapContext.chainFor(Focus.Workspace, editorFocused = false, imageFocused = true)
        val editing = KeymapContext.chainFor(Focus.Workspace, editorFocused = true)
        assertEquals(
            listOf(KeymapContext.Global, KeymapContext.Workspace, KeymapContext.Pane, KeymapContext.ImageViewer),
            picture,
        )
        fun action(chord: String, contexts: List<KeymapContext>) =
            (keymap.resolve(listOf(Keystroke.parse(chord)!!), contexts) as Resolution.Matched).binding.action
        assertEquals("image_viewer::ZoomIn", action("ctrl-=", picture))
        assertEquals("image_viewer::ZoomIn", action("ctrl-shift-=", picture))
        assertEquals("image_viewer::ZoomOut", action("ctrl--", picture))
        assertEquals("image_viewer::ResetZoom", action("ctrl-0", picture))
        assertEquals("image_viewer::ZoomToActualSize", action("ctrl-1", picture))
        assertEquals("image_viewer::FitToView", action("ctrl-shift-0", picture))
        // Away from a picture the same keys are the *buffer* font's, as they
        // are in Zed's global section (default-linux.json:30-33).
        assertEquals("zed::IncreaseBufferFontSize", action("ctrl-=", editing))
        assertEquals("pane::ActivateItem", action("ctrl-1", editing))
        // The palette prints the picture's chord for the picture's action
        // and the font size's for the font size's, never a shadowed one.
        assertEquals("Ctrl =", keymap.labelFor("image_viewer::ZoomIn", picture))
        assertNull(keymap.labelFor("zed::IncreaseBufferFontSize", picture))
        assertEquals("Ctrl =", keymap.labelFor("zed::IncreaseBufferFontSize", editing))
        // The interface size has no chord at all, anywhere: those keys are
        // the editor's, and Zed spends them the same way.
        assertNull(keymap.labelFor("zed::IncreaseUiFontSize", editing))
    }

    @Test
    fun aTerminalHearsOnlyTerminalBindings() {
        assertEquals(listOf(KeymapContext.Terminal), KeymapContext.chainFor(Focus.Terminal, editorFocused = true))
        val keymap = Keymap(listOf(binding(KeymapContext.Workspace, "ctrl-s", "workspace::Save", 0)))
        assertEquals(Resolution.None, keymap.resolve(listOf(Keystroke.parse("ctrl-s")!!), KeymapContext.chainFor(Focus.Terminal, false)))
    }

    @Test
    fun aChordWaitsForItsSecondStrokeAndATimeoutFallsBack() {
        val keymap = Keymap(
            listOf(
                binding(KeymapContext.Workspace, "ctrl-k", "seeker::Alone", 0),
                binding(KeymapContext.Workspace, "ctrl-k ctrl-s", "zed::OpenKeymap", 1),
                binding(KeymapContext.Workspace, "ctrl-p", "file_finder::Toggle", 2),
            )
        )
        val contexts = listOf(KeymapContext.Workspace)
        val chords = ChordDispatcher()
        val k = Keystroke.parse("ctrl-k")!!

        // `ctrl-k` completes a binding but also starts a longer one: wait.
        var press = chords.press(k, keymap, contexts)
        assertNull(press.binding)
        assertTrue(press.consumed)
        assertEquals(listOf(k), chords.pending)
        // The second stroke completes the chord.
        press = chords.press(Keystroke.parse("ctrl-s")!!, keymap, contexts)
        assertEquals("zed::OpenKeymap", press.binding?.action)
        assertTrue(chords.pending.isEmpty())

        // The pause runs out: the one-stroke binding fires after all.
        chords.press(k, keymap, contexts)
        assertEquals("seeker::Alone", chords.timeout()?.action)
        assertTrue(chords.pending.isEmpty())

        // A stroke that continues no chord ends the wait and then means
        // what it always meant on its own.
        chords.press(k, keymap, contexts)
        press = chords.press(Keystroke.parse("ctrl-p")!!, keymap, contexts)
        assertEquals("file_finder::Toggle", press.binding?.action)
        assertTrue(chords.pending.isEmpty())

        // A key no binding starts with is left alone.
        press = chords.press(Keystroke.parse("ctrl-x")!!, keymap, contexts)
        assertNull(press.binding)
        assertFalse(press.consumed)
    }

    /**
     * The shipped table, not a synthetic one: `ctrl-shift-enter` is
     * `workspace::OpenWithSystem` in the Workspace section and
     * `editor::NewlineAbove` in the Editor's, and with the editor focused the
     * deeper one has to win — the key opened the "Add to project" dialog on
     * a device build whose bindings had no contexts to rank by.
     */
    @Test
    fun withTheEditorFocusedTheEditorsCtrlShiftEnterOutranksTheWorkspaces() {
        val keymap = DefaultKeymap.keymap()
        val enter = listOf(press(KeyEvent.KEYCODE_ENTER, ctrl = true, shift = true)!!)
        val editing = KeymapContext.chainFor(Focus.Workspace, editorFocused = true)
        val notEditing = KeymapContext.chainFor(Focus.Workspace, editorFocused = false)
        assertEquals("editor::NewlineAbove", (keymap.resolve(enter, editing) as Resolution.Matched).binding.action)
        assertEquals("workspace::OpenWithSystem", (keymap.resolve(enter, notEditing) as Resolution.Matched).binding.action)
        // And the unshifted twin is the editor's alone.
        val plain = listOf(press(KeyEvent.KEYCODE_ENTER, ctrl = true)!!)
        assertEquals("editor::NewlineBelow", (keymap.resolve(plain, editing) as Resolution.Matched).binding.action)
    }

    /**
     * Which strokes the workspace takes before the soft keyboard sees them:
     * every chord with Ctrl or Alt down, and nothing else. Gboard answers
     * hardware Ctrl+Backspace itself — deleting its own idea of a word and
     * showing an Undo chip — unless the app claims the key first; a bare
     * Backspace, Enter or Tab and their shifted forms must still reach the
     * IME, which is how it types.
     */
    @Test
    fun modifierChordsAreTakenBeforeTheIme() {
        assertTrue(press(KeyEvent.KEYCODE_DEL, ctrl = true)!!.beforeIme)
        assertTrue(press(KeyEvent.KEYCODE_DEL, alt = true)!!.beforeIme)
        assertTrue(press(KeyEvent.KEYCODE_FORWARD_DEL, ctrl = true)!!.beforeIme)
        assertTrue(press(KeyEvent.KEYCODE_Z, ctrl = true)!!.beforeIme)
        assertTrue(press(KeyEvent.KEYCODE_ENTER, ctrl = true, shift = true)!!.beforeIme)
        assertFalse(press(KeyEvent.KEYCODE_DEL)!!.beforeIme)
        assertFalse(press(KeyEvent.KEYCODE_TAB, shift = true)!!.beforeIme)
        assertFalse(press(KeyEvent.KEYCODE_ENTER)!!.beforeIme)
        assertFalse(press(KeyEvent.KEYCODE_ESCAPE)!!.beforeIme)
        // The bindings the device test found broken all sit on chords the
        // pre-IME pass takes.
        val keymap = DefaultKeymap.keymap()
        val editing = KeymapContext.chainFor(Focus.Workspace, editorFocused = true)
        for ((strokes, action) in listOf(
            "ctrl-backspace" to "editor::DeleteToPreviousWordStart",
            "ctrl-delete" to "editor::DeleteToNextWordEnd",
            "ctrl-z" to "editor::Undo",
            "ctrl-shift-enter" to "editor::NewlineAbove",
        )) {
            val stroke = Keystroke.parse(strokes)!!
            assertTrue(strokes, stroke.beforeIme)
            assertEquals(action, (keymap.resolve(listOf(stroke), editing) as Resolution.Matched).binding.action)
        }
    }
}
