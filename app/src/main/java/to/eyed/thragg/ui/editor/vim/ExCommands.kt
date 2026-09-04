package to.eyed.thragg.ui.editor.vim

import to.eyed.thragg.ui.editor.EditorState

/**
 * The `:` commands — Zed's `crates/vim/src/command.rs`, the subset the
 * docs list under "Command palette" that this workspace can honour
 * (docs/src/vim.md). Zed opens its command palette on `:` with the vim
 * aliases mixed in; here the line is Vim's own, at the bottom of the pane,
 * because the palette's rows are workspace commands and `:s/a/b/` is not
 * one.
 *
 * A range is Vim's: `%`, `N`, `N,M`, `.`, `$`, `'<,'>`, `'a`, with `+N`/`-N`
 * offsets. The default range is the cursor's row.
 */
internal object ExCommands {

    /** A parsed range as first and last row, inclusive. */
    private class Range(val first: Int, val last: Int, val given: Boolean)

    fun run(vim: VimState, line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        vim.exGlobals().lastExCommand = trimmed
        val editor = vim.editor
        val (range, rest) = parseRange(vim, trimmed) ?: run {
            vim.say("E14: Invalid address")
            return
        }
        val command = rest.trim()

        // A bare address is a jump: `:42`, `:$`, `:'a`.
        if (command.isEmpty()) {
            if (range.given) vim.goToRow(range.last)
            return
        }

        val name = command.takeWhile { it.isLetter() }
        val bang = command.drop(name.length).startsWith("!")
        val argument = command.drop(name.length + if (bang) 1 else 0).trim()
        val host = vim.exHost()

        when {
            name.isEmpty() && command.startsWith("s/") || name == "s" || name == "substitute" ->
                substitute(vim, range, command.drop(if (name.isEmpty()) 1 else name.length))
            name.isEmpty() && command.startsWith("<") -> vim.exShift(range.first, range.last, indent = false)
            name.isEmpty() && command.startsWith(">") -> vim.exShift(range.first, range.last, indent = true)
            name in setOf("w", "write", "up", "update", "wa", "wall") -> {
                if (!host.save()) vim.say("E32: No file name")
            }
            name in setOf("q", "quit", "qa", "qall", "clo", "close", "bd", "bdelete") -> {
                if (!host.closeTab(force = bang)) vim.say("E492: Not an editor command: $command")
            }
            name in setOf("wq", "x", "xit", "exit", "wqa", "wqall", "xa", "xall") -> {
                if (!host.saveAndClose()) vim.say("E32: No file name")
            }
            name == "e" || name == "edit" -> {
                if (argument.isEmpty()) {
                    vim.say("E32: No file name")
                } else if (!host.openPath(argument)) {
                    vim.say("E484: Can't open file $argument")
                }
            }
            name in setOf("noh", "nohlsearch") -> editor.clearSearchMatches()
            name in setOf("sp", "split", "vs", "vsp", "vsplit", "new", "vne", "vnew", "tabe", "tabedit", "tabnew") ->
                vim.say("E492: Split panes are not available in Thragg")
            name == "j" || name == "join" -> {
                val last = if (range.given && range.last > range.first) range.last else range.first + (argument.toIntOrNull() ?: 2) - 1
                vim.exJoin(range.first, last.coerceAtMost(editor.lastRow))
            }
            name == "d" || name == "delete" -> vim.exDelete(range.first, range.last)
            name == "y" || name == "yank" -> vim.exYank(range.first, range.last)
            name == "se" || name == "set" -> vim.say("E518: Unknown option: $argument")
            name == "cn" || name == "cnext" || name == "ln" || name == "lnext" -> editor.goToDiagnostic(forward = true)
            name == "cp" || name == "cprev" || name == "lp" || name == "lprev" -> editor.goToDiagnostic(forward = false)
            else -> vim.say("E492: Not an editor command: $command")
        }
    }

    /** The range at the head of [text], and what follows it. Null on a bad address. */
    private fun parseRange(vim: VimState, text: String): Pair<Range, String>? {
        val editor = vim.editor
        var i = 0
        if (text.startsWith("%")) {
            return Range(0, editor.lastRow, given = true) to text.substring(1)
        }
        val addresses = ArrayList<Int>()
        while (true) {
            val (row, next) = parseAddress(vim, text, i) ?: break
            addresses.add(row)
            i = next
            if (i < text.length && (text[i] == ',' || text[i] == ';')) {
                i++
                continue
            }
            break
        }
        if (addresses.isEmpty()) {
            return Range(vim.currentRow(), vim.currentRow(), given = false) to text.substring(i)
        }
        val first = addresses.first().coerceIn(0, editor.lastRow)
        val last = addresses.last().coerceIn(0, editor.lastRow)
        return Range(minOf(first, last), maxOf(first, last), given = true) to text.substring(i)
    }

    /** One address at [start]: a row, `.`, `$`, `'x`, with `+N` / `-N` after it. */
    private fun parseAddress(vim: VimState, text: String, start: Int): Pair<Int, Int>? {
        val editor = vim.editor
        var i = start
        var row: Int? = null
        when {
            i < text.length && text[i].isDigit() -> {
                val digits = text.substring(i).takeWhile { it.isDigit() }
                row = (digits.toIntOrNull() ?: return null) - 1
                i += digits.length
            }
            i < text.length && text[i] == '.' -> {
                row = vim.currentRow()
                i++
            }
            i < text.length && text[i] == '$' -> {
                row = editor.lastRow
                i++
            }
            i + 1 < text.length && text[i] == '\'' -> {
                row = vim.mark(text[i + 1])?.row ?: return null
                i += 2
            }
        }
        // `+3` / `-2`, and a bare `+` / `-` which means one.
        while (i < text.length && (text[i] == '+' || text[i] == '-')) {
            val sign = if (text[i] == '+') 1 else -1
            i++
            val digits = text.substring(i).takeWhile { it.isDigit() }
            i += digits.length
            val amount = if (digits.isEmpty()) 1 else digits.toInt()
            row = (row ?: vim.currentRow()) + sign * amount
        }
        return row?.let { it to i }
    }

    /**
     * `:[range]s/pattern/replacement/[flags]`. The pattern is a regex in
     * Zed's syntax unless `use_regex_search` is off, and the replacement
     * uses `$1` for groups (docs/src/vim.md "Regex differences"). `g` takes
     * every match on a row, `i` ignores case. Every changed row goes in one
     * batch, so `u` puts the whole substitution back.
     */
    private fun substitute(vim: VimState, range: Range, spec: String) {
        val editor = vim.editor
        val delimiter = spec.firstOrNull() ?: run {
            vim.say("E35: No previous regular expression")
            return
        }
        val parts = splitOn(spec.drop(1), delimiter)
        val pattern = parts.getOrNull(0).orEmpty()
        val replacement = parts.getOrNull(1).orEmpty()
        val flags = parts.getOrNull(2).orEmpty()
        if (pattern.isEmpty()) {
            vim.say("E35: No previous regular expression")
            return
        }
        val options = HashSet<RegexOption>()
        if ('i' in flags) options.add(RegexOption.IGNORE_CASE)
        val regex = try {
            if (vim.exGlobals().useRegexSearch) Regex(pattern, options) else Regex(Regex.escape(pattern), options)
        } catch (e: IllegalArgumentException) {
            vim.say("E486: Pattern not found: $pattern")
            return
        }
        val all = 'g' in flags
        val edits = ArrayList<EditorState.CaretEdit>()
        var lastChanged = -1
        for (row in range.first..range.last) {
            val text = editor.line(row)
            val changed = if (all) regex.replace(text, replacement) else regex.replaceFirst(text, replacement)
            if (changed == text) continue
            val at = editor.lineStartOffset(row)
            edits.add(EditorState.CaretEdit(at, at + editor.utf8Length(text), changed, head = 0, isPrimary = lastChanged < 0))
            lastChanged = row
        }
        if (edits.isEmpty()) {
            vim.say("E486: Pattern not found: $pattern")
            return
        }
        editor.applyCaretEdits(edits)
        vim.goToRow(lastChanged)
        vim.noteChanged()
    }

    /** Split on an unescaped [delimiter], unescaping `\/` on the way. */
    private fun splitOn(text: String, delimiter: Char): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length && text[i + 1] == delimiter) {
                current.append(delimiter)
                i += 2
                continue
            }
            if (c == delimiter) {
                parts.add(current.toString())
                current.setLength(0)
                i++
                continue
            }
            current.append(c)
            i++
        }
        parts.add(current.toString())
        return parts
    }
}
