package to.eyed.seeker.code.ui.editor.vim

import to.eyed.seeker.code.core.VimClipboard

/** What a register holds: text, and whether it was taken by lines. */
data class Register(val text: String, val linewise: Boolean)

/**
 * Vim's registers, with Zed's clipboard rule on top.
 *
 * The unnamed register `"` is what every yank and delete writes and every
 * put reads. `0` keeps the last yank, `1`–`9` the last deletes (shifting),
 * `-` the last delete inside a line, `a`–`z` are the user's (`A`–`Z` append),
 * `_` is the black hole, and `+`/`*` are the system clipboard.
 *
 * `use_system_clipboard` is Zed's (vim/src/state.rs `write_registers` /
 * `read_register`): with `always` the unnamed register *is* the clipboard —
 * writes go out and a paste reads back whatever is there, so text copied in
 * another app pastes with `p`; with `on_yank` only yanks go out; with
 * `never` only `"+` and `"*` touch it.
 */
class VimRegisters(private val clipboard: () -> VimClipboard) {
    private val named = HashMap<Char, Register>()
    private var unnamed: Register? = null

    /** What Vim last put on the clipboard, so a copy made elsewhere can be told apart. */
    private var lastClipboardWrite: String? = null

    /**
     * Read [name] (null is the unnamed register). Empty registers read as
     * null, which makes a put with nothing in it a no-op rather than an
     * insertion of "".
     */
    fun read(name: Char?, host: VimHost): Register? = when (name) {
        null, '"' -> {
            // Zed: the clipboard wins when it holds something Vim did not put
            // there (state.rs `read_register`, the newer-clipboard check).
            val system = if (clipboard() == VimClipboard.Always) host.readClipboard() else null
            if (!system.isNullOrEmpty() && system != lastClipboardWrite) {
                Register(system, linewise = false)
            } else {
                unnamed
            }
        }
        '+', '*' -> host.readClipboard()?.takeIf { it.isNotEmpty() }?.let { Register(it, linewise = false) }
        '_' -> null
        else -> named[name.lowercaseChar()]
    }

    /**
     * Write a yank or delete. [name] is the register the user chose, if any;
     * an uppercase name appends. Deletes shift `1`–`9` and fill `-`, yanks
     * fill `0`, exactly as Vim documents (`:help registers`).
     */
    fun write(name: Char?, value: Register, isYank: Boolean, host: VimHost) {
        if (name == '_') return
        when {
            name == null || name == '"' -> {
                unnamed = value
                if (isYank) {
                    named['0'] = value
                } else if (value.linewise || value.text.contains('\n')) {
                    shiftDeletes(value)
                } else {
                    named['-'] = value
                }
                val policy = clipboard()
                if (policy == VimClipboard.Always || (policy == VimClipboard.OnYank && isYank)) {
                    lastClipboardWrite = value.text
                    host.writeClipboard(value.text)
                }
            }
            name == '+' || name == '*' -> {
                unnamed = value
                lastClipboardWrite = value.text
                host.writeClipboard(value.text)
            }
            name.isUpperCase() -> {
                val key = name.lowercaseChar()
                val old = named[key]
                val joined = if (old == null) {
                    value
                } else {
                    Register(
                        if (old.linewise && !old.text.endsWith("\n")) old.text + "\n" + value.text else old.text + value.text,
                        old.linewise || value.linewise,
                    )
                }
                named[key] = joined
                unnamed = joined
            }
            else -> {
                named[name] = value
                unnamed = value
            }
        }
    }

    private fun shiftDeletes(value: Register) {
        for (i in 9 downTo 2) {
            named['0' + (i - 1)]?.let { named['0' + i] = it }
        }
        named['1'] = value
    }
}

/**
 * The state Vim shares across buffers: registers, the last search, the last
 * `f`/`t`, the last change for `.` and the last `:` command. One per
 * process in the app ([shared]); one per test on the host.
 */
class VimGlobals(clipboard: () -> VimClipboard = { VimClipboard.Always }) {
    val registers = VimRegisters(clipboard)
    var lastSearch: Search? = null
    var lastFind: Find? = null
    var lastChange: DotRecord? = null
    var lastExCommand: String? = null

    /** Zed's `vim.use_regex_search`, whose default is on. */
    var useRegexSearch: Boolean = true

    /** A `/`, `?`, `*` or `#`, as `n` and `N` need to repeat it. */
    class Search(val pattern: String, val backwards: Boolean, val wholeWord: Boolean, val caseSensitive: Boolean)

    /** An `f`/`F`/`t`/`T`, as `;` and `,` need to repeat it. */
    class Find(val target: Char, val forward: Boolean, val till: Boolean)

    companion object {
        /** The app's own. The clipboard policy follows the settings through [policy]. */
        val shared = VimGlobals { policy }

        @Volatile
        var policy: VimClipboard = VimClipboard.Always
    }
}

/**
 * What `.` replays: the keys of the last change, the count it ran with,
 * the text an insert typed before Escape, and — for a change made from
 * visual mode — the shape of the selection, so the same amount of text is
 * taken from the new cursor (`:help .`: "the same text is operated on").
 */
class DotRecord(
    val keys: List<String>,
    val insertedText: String? = null,
    val visual: VisualExtent? = null,
)

/** A visual selection relative to its start, for [DotRecord]. */
class VisualExtent(val mode: VimMode, val rows: Int, val cols: Int)
