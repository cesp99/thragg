package to.eyed.seeker.code.ui.workspace

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * The keymap as the app matches it: Zed's model, on Android's key events.
 *
 * The engine owns the *files* — the defaults it is handed, the base keymap
 * `settings.json` names, and the user's `keymap.json` — and hands back one
 * flat list in precedence order (engine/src/keymap.rs). This file is the
 * other half: what a keystroke's name means on this platform, which binding
 * wins when several match, and the chord state a two-stroke binding needs.
 * None of it touches a file, which is why it can be tested on the host.
 *
 * Precedence is Zed's (docs/src/key-bindings.md:174-178): a binding in a
 * deeper context beats one in a shallower — `Editor` over `Pane` over
 * `Workspace` over a section with no context — and at the same depth the one
 * defined later wins, which is how a user binding beats a default. A `null`
 * binding follows the same rule and shadows everything shallower.
 */

/** One key with its modifiers — gpui's `Keystroke`, less the platform key. */
data class Keystroke(
    val ctrl: Boolean,
    val alt: Boolean,
    val shift: Boolean,
    /** The key by Zed's name, lower case: `a`, `f1`, `pagedown`, `[`. */
    val key: String,
) {
    /** What the menus, the palette and docs/SHORTCUTS.md print: `Ctrl Shift S`. */
    val label: String
        get() = buildString {
            if (ctrl) append("Ctrl ")
            if (alt) append("Alt ")
            if (shift) append("Shift ")
            append(KEY_LABELS[key] ?: if (key.length == 1) key.uppercase() else key)
        }

    /**
     * Whether the keymap must hear this stroke *before* the soft keyboard.
     *
     * A hardware key reaches the IME first on Android, and Gboard has ideas
     * of its own about the modifier chords: it answers `ctrl-backspace` by
     * deleting a "word" through its `InputConnection` with its own idea of
     * a word (and an Undo chip), and never lets the editor see the key. Zed
     * has no such layer — its keymap is the first thing a chord meets — so
     * every chord with Ctrl or Alt down is dispatched from the view's
     * pre-IME pass ([android.view.View.onKeyPreIme], which Compose exposes
     * as `onPreInterceptKeyBeforeSoftKeyboard`), and only what no binding
     * claims goes on to the keyboard. Bare keys and shifted ones stay on
     * the ordinary path: typing, Enter, Tab and Backspace are the IME's to
     * see first, and it forwards them.
     */
    val beforeIme: Boolean get() = ctrl || alt

    /** Zed's spelling, `ctrl-alt-shift-key` — what keymap.json takes. */
    override fun toString(): String = buildString {
        if (ctrl) append("ctrl-")
        if (alt) append("alt-")
        if (shift) append("shift-")
        append(key)
    }

    companion object {
        /**
         * The keystroke a key event is, or null for one no binding can name
         * — a modifier on its own, or a key this table has no name for. A
         * modifier's own down-event must be null rather than a stroke, or
         * pressing Ctrl on the way to Ctrl+0 would end a pending `ctrl-k`.
         */
        fun of(event: AndroidKeyEvent): Keystroke? =
            of(event.keyCode, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed)

        /** [of], on the four facts a key event carries — the testable half. */
        fun of(keyCode: Int, ctrl: Boolean, alt: Boolean, shift: Boolean): Keystroke? {
            val name = KEY_NAMES[keyCode] ?: return null
            return Keystroke(ctrl = ctrl, alt = alt, shift = shift, key = name)
        }

        /**
         * Zed's `ctrl-alt-shift-key`, as the engine writes it back after
         * validating — so this reads the normalised form and does not
         * second-guess it. A stroke the engine would have refused is null.
         */
        fun parse(text: String): Keystroke? {
            // The key is what follows the last dash — except that the dash
            // itself is a key, spelled `ctrl--` (keystroke.rs:154-156), and
            // `ctrl-` with nothing after it is not one.
            val (modifiers, key) = when {
                text == "-" -> "" to "-"
                text.endsWith("--") -> text.dropLast(2) to "-"
                text.endsWith("-") -> return null
                else -> {
                    val dash = text.lastIndexOf('-')
                    if (dash < 0) "" to text else text.substring(0, dash) to text.substring(dash + 1)
                }
            }
            if (key !in KEY_CODES) return null
            var ctrl = false
            var alt = false
            var shift = false
            if (modifiers.isNotEmpty()) {
                for (part in modifiers.split('-')) {
                    when (part) {
                        "ctrl" -> ctrl = true
                        "alt" -> alt = true
                        "shift" -> shift = true
                        else -> return null
                    }
                }
            }
            return Keystroke(ctrl, alt, shift, key)
        }

        /** A chord — strokes separated by spaces — or null if any stroke is not one. */
        fun parseSequence(text: String): List<Keystroke>? {
            val strokes = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (strokes.isEmpty()) return null
            return strokes.map { parse(it) ?: return null }
        }

        /**
         * Zed's key names against Android's key codes. Enter has two codes
         * because a keyboard with a numeric pad sends the other one, and
         * nobody who binds `enter` means only one of them.
         */
        private val KEY_CODES: Map<String, List<Int>> = buildMap {
            for (letter in 'a'..'z') {
                put(letter.toString(), listOf(AndroidKeyEvent.KEYCODE_A + (letter - 'a')))
            }
            for (digit in 0..9) put(digit.toString(), listOf(AndroidKeyEvent.KEYCODE_0 + digit))
            for (n in 1..12) put("f$n", listOf(AndroidKeyEvent.KEYCODE_F1 + (n - 1)))
            put("enter", listOf(AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER))
            put("escape", listOf(AndroidKeyEvent.KEYCODE_ESCAPE))
            put("tab", listOf(AndroidKeyEvent.KEYCODE_TAB))
            put("backspace", listOf(AndroidKeyEvent.KEYCODE_DEL))
            put("delete", listOf(AndroidKeyEvent.KEYCODE_FORWARD_DEL))
            put("space", listOf(AndroidKeyEvent.KEYCODE_SPACE))
            put("up", listOf(AndroidKeyEvent.KEYCODE_DPAD_UP))
            put("down", listOf(AndroidKeyEvent.KEYCODE_DPAD_DOWN))
            put("left", listOf(AndroidKeyEvent.KEYCODE_DPAD_LEFT))
            put("right", listOf(AndroidKeyEvent.KEYCODE_DPAD_RIGHT))
            put("home", listOf(AndroidKeyEvent.KEYCODE_MOVE_HOME))
            put("end", listOf(AndroidKeyEvent.KEYCODE_MOVE_END))
            put("pageup", listOf(AndroidKeyEvent.KEYCODE_PAGE_UP))
            put("pagedown", listOf(AndroidKeyEvent.KEYCODE_PAGE_DOWN))
            put("insert", listOf(AndroidKeyEvent.KEYCODE_INSERT))
            put("-", listOf(AndroidKeyEvent.KEYCODE_MINUS))
            put("=", listOf(AndroidKeyEvent.KEYCODE_EQUALS))
            put("[", listOf(AndroidKeyEvent.KEYCODE_LEFT_BRACKET))
            put("]", listOf(AndroidKeyEvent.KEYCODE_RIGHT_BRACKET))
            put(";", listOf(AndroidKeyEvent.KEYCODE_SEMICOLON))
            put("'", listOf(AndroidKeyEvent.KEYCODE_APOSTROPHE))
            put(",", listOf(AndroidKeyEvent.KEYCODE_COMMA))
            put(".", listOf(AndroidKeyEvent.KEYCODE_PERIOD))
            put("/", listOf(AndroidKeyEvent.KEYCODE_SLASH))
            put("\\", listOf(AndroidKeyEvent.KEYCODE_BACKSLASH))
            put("`", listOf(AndroidKeyEvent.KEYCODE_GRAVE))
        }

        private val KEY_NAMES: Map<Int, String> = buildMap {
            for ((name, codes) in KEY_CODES) for (code in codes) put(code, name)
        }

        /** How a named key prints — the spellings docs/SHORTCUTS.md already uses. */
        private val KEY_LABELS = mapOf(
            "enter" to "Enter",
            "escape" to "Esc",
            "tab" to "Tab",
            "backspace" to "Backspace",
            "delete" to "Delete",
            "space" to "Space",
            "up" to "↑",
            "down" to "↓",
            "left" to "←",
            "right" to "→",
            "home" to "Home",
            "end" to "End",
            "pageup" to "PageUp",
            "pagedown" to "PageDown",
            "insert" to "Insert",
        ) + (1..12).associate { "f$it" to "F$it" }
    }
}

/**
 * Where a binding is listened for: the part of Zed's context tree this app
 * has, deepest last. [Global] is a section with no `"context"`.
 */
enum class KeymapContext(val zedName: String, val depth: Int) {
    Global("", 0),
    Workspace("Workspace", 1),
    Pane("Pane", 2),
    Editor("Editor", 3),
    Terminal("Terminal", 3),
    /**
     * The agent panel's composer — Zed's `AgentPanel` (and its `AcpThread`,
     * which the engine folds into this one name). Active only while the
     * composer holds the keyboard, which is how `ctrl-n` is a new thread
     * there and a new file everywhere else (default-linux.json:220 vs :654).
     */
    AgentPanel("AgentPanel", 3),
    /**
     * A picture as the open tab — Zed's `ImageViewer`
     * (default-linux.json:1566-1574), a pane item like the editor and at
     * its depth, so its zoom chords win over the workspace's UI font size
     * and first-tab chords on the same keys, and only while a picture is up.
     */
    ImageViewer("ImageViewer", 3);

    companion object {
        fun fromZedName(name: String): KeymapContext? = entries.firstOrNull { it.zedName == name }

        /**
         * The contexts active while [focus] has the keyboard, shallowest
         * first.
         *
         * A terminal hears **only** `Terminal` bindings. Zed's terminal sits
         * under its workspace and inherits the workspace's chords; here every
         * plain `Ctrl+<letter>` belongs to the shell — C interrupts, R
         * searches history — so nothing a user wrote for the workspace, or
         * for everywhere, can reach a shell unless they said `Terminal`.
         *
         * The agent composer sits under the workspace as Zed's does
         * (Workspace > Dock > AgentPanel): every workspace chord still
         * reaches it, and its own section wins over the workspace's on a
         * shared key. It is never the editor, so [editorFocused] and
         * [agentPanelFocused] never both hold.
         *
         * A picture sits where the editor would (Workspace > Pane >
         * ImageViewer): the pane's chords still reach it, and its own
         * section wins over the workspace's on a shared key. [imageFocused]
         * is the picture being the open tab with nothing else — no editor,
         * no composer — holding the keyboard.
         */
        fun chainFor(
            focus: Focus,
            editorFocused: Boolean,
            agentPanelFocused: Boolean = false,
            imageFocused: Boolean = false,
        ): List<KeymapContext> = when {
            focus == Focus.Terminal -> listOf(Terminal)
            editorFocused -> listOf(Global, Workspace, Pane, Editor)
            agentPanelFocused -> listOf(Global, Workspace, AgentPanel)
            imageFocused -> listOf(Global, Workspace, Pane, ImageViewer)
            else -> listOf(Global, Workspace, Pane)
        }

        /**
         * The chain in which [context] is the deepest member — the contexts
         * that are active when a binding written for it can fire.
         *
         * [chainFor] answers the same question from the *keyboard's* side;
         * this one answers it from a binding's, which is what a label lookup
         * needs: to print the chord of an `AgentPanel` binding you have to
         * ask what is active while the composer holds the keyboard, not what
         * is active where the palette was opened.
         */
        fun chainTo(context: KeymapContext): List<KeymapContext> = when (context) {
            Terminal -> chainFor(Focus.Terminal, editorFocused = false)
            Editor -> chainFor(Focus.Workspace, editorFocused = true)
            AgentPanel -> chainFor(Focus.Workspace, editorFocused = false, agentPanelFocused = true)
            ImageViewer -> chainFor(Focus.Workspace, editorFocused = false, imageFocused = true)
            // The three shallow ones are all reachable from the editor, and
            // the editor's chain is where their chords have to survive
            // shadowing to be worth printing.
            Pane, Workspace, Global -> chainFor(Focus.Workspace, editorFocused = true)
        }
    }
}

/** Who wrote a binding. Later sources outrank earlier at the same depth. */
enum class KeybindSource { Default, Base, User }

/** One resolved binding, as the engine listed it. */
data class KeyBinding(
    val context: KeymapContext,
    val keystrokes: List<Keystroke>,
    /** The action, or null for an unbinding — which still shadows shallower ones. */
    val action: String?,
    /** The action's argument as JSON text (`["pane::ActivateItem", 3]` → `3`), or null. */
    val args: String?,
    val source: KeybindSource,
    /** Position in the engine's list: the tie-breaker at equal depth. */
    val index: Int,
) {
    /** `Ctrl K, Ctrl 0` — each stroke's label, the strokes joined by commas. */
    val label: String get() = keystrokes.joinToString(", ") { it.label }

    /** The argument as an integer, where the action takes one. */
    val intArg: Int? get() = args?.trim()?.toIntOrNull()
}

/**
 * One way a pending chord could be finished — a which-key row.
 *
 * [keys] is what is left to press, in the label form the menus and the
 * palette print (`Ctrl 0`); [action] is the raw action id, humanised by the
 * view rather than here, so this file stays free of the palette's vocabulary.
 */
data class ChordCompletion(val keys: String, val action: String)

/** What a keystroke sequence resolves to against a set of active contexts. */
sealed interface Resolution {
    /**
     * A binding ends here. [hasLongerMatches] is Zed's "wait a second" case
     * (docs/src/key-bindings.md:180-182): another binding continues with
     * more strokes, so this one fires only once the pause says nothing more
     * is coming.
     */
    data class Matched(val binding: KeyBinding, val hasLongerMatches: Boolean) : Resolution

    /** Nothing ends here, but a longer binding starts this way. */
    data object Pending : Resolution

    /** No binding starts this way. */
    data object None : Resolution
}

/** The keymap: bindings in the engine's order, and the questions asked of them. */
class Keymap(val bindings: List<KeyBinding>) {

    /** Resolve [sequence] where [contexts] are active — see [Resolution]. */
    fun resolve(sequence: List<Keystroke>, contexts: Collection<KeymapContext>): Resolution {
        var best: KeyBinding? = null
        var longer = false
        for (binding in bindings) {
            if (binding.context !in contexts) continue
            if (binding.keystrokes.size < sequence.size) continue
            if (binding.keystrokes.subList(0, sequence.size) != sequence) continue
            if (binding.keystrokes.size > sequence.size) {
                longer = true
            } else if (best == null || binding.outranks(best)) {
                best = binding
            }
        }
        return when {
            best != null -> Resolution.Matched(best, longer)
            longer -> Resolution.Pending
            else -> Resolution.None
        }
    }

    /**
     * Every live binding that runs [action] while [contexts] are active,
     * strongest first — so the first is the one to print and the rest are
     * the alternatives. A binding another one shadows (same strokes, deeper
     * or later, whatever its action) is left out: the palette must never
     * print a chord that does something else.
     */
    fun bindingsFor(action: String, contexts: Collection<KeymapContext>): List<KeyBinding> =
        bindings
            .filter { it.action == action && it.context in contexts }
            .filter { candidate ->
                val winner = resolve(candidate.keystrokes, contexts)
                winner is Resolution.Matched && winner.binding == candidate
            }
            .sortedWith(compareByDescending<KeyBinding> { it.context.depth }.thenByDescending { it.index })

    /** The chord to print beside [action], or null when it has none. */
    fun labelFor(action: String, contexts: Collection<KeymapContext>): String? =
        bindingsFor(action, contexts).firstOrNull()?.label

    /**
     * Every context [action] is bound in at all, whether or not anything
     * shadows it there.
     *
     * This is what stops the palette printing nothing beside a command that
     * *has* a chord: a label is read against a chain of active contexts, and
     * a command bound only in `AgentPanel` is invisible to the editor's
     * chain. Asking the keymap where a command lives, and then reading its
     * chord there, is the fix — see `chainsFor` in Keybindings.kt.
     */
    fun contextsFor(action: String): Set<KeymapContext> =
        bindings.filterTo(mutableListOf()) { it.action == action }
            .mapTo(LinkedHashSet()) { it.context }

    /**
     * The ways a pending chord could be finished, best first.
     *
     * This is what a which-key overlay is made of (Zed's `which_key`, and
     * Emacs' before it): the user has pressed `ctrl-k`, several bindings
     * start that way, and the overlay lists the strokes that would finish
     * each and what it would do. Only live bindings count — one another
     * binding shadows on the same keys is not a completion, because pressing
     * those keys would run the other one — and an unbinding is not a
     * completion either, since finishing it does nothing.
     *
     * Deduplicated by the remaining strokes: two bindings that answer to the
     * same keys are one row, and [bindings] is in precedence order, so the
     * first one seen is the one that would win.
     */
    fun completions(
        prefix: List<Keystroke>,
        contexts: Collection<KeymapContext>,
    ): List<ChordCompletion> {
        if (prefix.isEmpty()) return emptyList()
        val rows = LinkedHashMap<String, ChordCompletion>()
        for (binding in bindings) {
            if (binding.action == null) continue
            if (binding.context !in contexts) continue
            if (binding.keystrokes.size <= prefix.size) continue
            if (binding.keystrokes.subList(0, prefix.size) != prefix) continue
            val winner = resolve(binding.keystrokes, contexts)
            if (winner !is Resolution.Matched || winner.binding != binding) continue
            val rest = binding.keystrokes.drop(prefix.size)
            val keys = rest.joinToString(", ") { it.label }
            rows.putIfAbsent(keys, ChordCompletion(keys, binding.action))
        }
        return rows.values.sortedBy { it.keys }
    }

    /**
     * Every chord that runs [action], read against each of [chains] in turn
     * and deduplicated by label — strongest first, so the head is the one to
     * print and the tail are the alternatives.
     */
    fun labelsAcross(action: String, chains: List<Collection<KeymapContext>>): List<String> {
        val labels = LinkedHashSet<String>()
        for (chain in chains) {
            for (binding in bindingsFor(action, chain)) labels += binding.label
        }
        return labels.toList()
    }

    private fun KeyBinding.outranks(other: KeyBinding): Boolean =
        context.depth > other.context.depth ||
            (context.depth == other.context.depth && index > other.index)

    companion object {
        val EMPTY = Keymap(emptyList())

        /**
         * The engine's `loadKeymap` answer: the bindings, and the sentences
         * about what it could not use. A binding whose keystrokes this side
         * cannot name — none, if the two tables agree — is dropped here with
         * a sentence of its own rather than silently.
         */
        fun parse(json: String): Pair<Keymap, List<String>> {
            val root = runCatching { JSONObject(json) }.getOrNull()
                ?: return EMPTY to listOf("The keymap could not be read.")
            val errors = mutableListOf<String>()
            val list = root.optJSONArray("errors") ?: JSONArray()
            for (i in 0 until list.length()) errors += list.optString(i)
            val bindings = mutableListOf<KeyBinding>()
            val entries = root.optJSONArray("bindings") ?: JSONArray()
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val context = KeymapContext.fromZedName(entry.optString("context")) ?: continue
                val strokes = entry.optString("keystrokes")
                val sequence = Keystroke.parseSequence(strokes)
                if (sequence == null) {
                    errors += "\"$strokes\" names a key this device cannot press."
                    continue
                }
                bindings += KeyBinding(
                    context = context,
                    keystrokes = sequence,
                    action = if (entry.isNull("action")) null else entry.optString("action"),
                    args = if (entry.isNull("args")) null else entry.opt("args")?.toString(),
                    source = when (entry.optString("source")) {
                        "user" -> KeybindSource.User
                        "base" -> KeybindSource.Base
                        else -> KeybindSource.Default
                    },
                    index = bindings.size,
                )
            }
            return Keymap(bindings) to errors
        }
    }
}

/**
 * The keymap in force, for every reader: the root key pass, the terminal,
 * the palette and the menus that print a chord beside a command. Compose
 * state, so a saved keymap.json re-labels every menu on the next frame.
 *
 * Starts as the defaults resolved on this side alone, so the keyboard works
 * from the first frame; the engine's answer — with the base keymap and the
 * user's file layered on — replaces it as soon as it is read.
 */
object KeymapStore {
    var keymap: Keymap by mutableStateOf(DefaultKeymap.keymap())
        private set

    /** What the last load could not use — for the toast and the settings screen. */
    var errors: List<String> by mutableStateOf(emptyList())
        private set

    fun install(keymap: Keymap, errors: List<String>) {
        this.keymap = keymap
        this.errors = errors
    }
}

/** What pressing a key came to — see [ChordDispatcher.press]. */
data class KeyPress(
    /** The binding to run now, if one resolved. */
    val binding: KeyBinding?,
    /** Whether the event is spoken for, run or not — a pending chord swallows its strokes. */
    val consumed: Boolean,
)

/**
 * The pending-chord state a two-stroke binding needs, shared by every
 * surface that dispatches keys so `ctrl-k` pressed in the editor and
 * `ctrl-0` pressed a moment later are one chord and not two keys.
 *
 * Zed keeps the same state on its window (`pending_input`,
 * gpui/src/window.rs:5398-5440) and shows the pending strokes in the status
 * bar (vim/src/mode_indicator.rs:55-58); [pending] is that text's source.
 * One rule of Zed's is copied deliberately: when a stroke both ends a
 * binding and begins a longer one, the short one waits for the pause
 * ([timeout]) rather than firing at once — so `ctrl-k` alone can mean
 * something without stealing `ctrl-k ctrl-0`.
 */
class ChordDispatcher {
    /** The strokes typed so far of a chord not yet complete. */
    var pending: List<Keystroke> by mutableStateOf(emptyList())
        private set

    /** The binding the pending strokes already complete, to fire if nothing follows. */
    private var fallback: KeyBinding? = null

    /** A stroke arrived while [contexts] are active. */
    fun press(stroke: Keystroke, keymap: Keymap, contexts: Collection<KeymapContext>): KeyPress {
        val sequence = pending + stroke
        return when (val resolution = keymap.resolve(sequence, contexts)) {
            is Resolution.Matched -> if (resolution.hasLongerMatches) {
                pending = sequence
                fallback = resolution.binding
                KeyPress(binding = null, consumed = true)
            } else {
                clear()
                // An unbinding is a key that means nothing here: let it
                // through to whatever would have typed it.
                KeyPress(resolution.binding, consumed = resolution.binding.action != null)
            }
            Resolution.Pending -> {
                pending = sequence
                fallback = null
                KeyPress(binding = null, consumed = true)
            }
            Resolution.None -> if (pending.isEmpty()) {
                KeyPress(binding = null, consumed = false)
            } else {
                // A second stroke that continues no chord ends the pending
                // one and then means what it always meant on its own.
                clear()
                press(stroke, keymap, contexts)
            }
        }
    }

    /**
     * The pause ran out. Returns the binding the pending strokes completed
     * on their own, if any, and forgets them either way.
     */
    fun timeout(): KeyBinding? {
        val due = fallback
        clear()
        return due
    }

    fun clear() {
        pending = emptyList()
        fallback = null
    }
}

/** How long a chord waits for its next stroke before giving up. */
const val CHORD_TIMEOUT_MS = 1500L
