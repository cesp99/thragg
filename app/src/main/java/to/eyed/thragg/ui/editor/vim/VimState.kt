package to.eyed.thragg.ui.editor.vim

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import to.eyed.thragg.ui.editor.Caret
import to.eyed.thragg.ui.editor.EditorState
import to.eyed.thragg.ui.editor.insertNewline

/**
 * The `:` / `/` / `?` line being typed at the bottom of the pane.
 */
data class CommandLine(val prefix: Char, val text: String)

/**
 * One editor's vim layer: its mode, what it is waiting for, and the key
 * handling that moves between them — Zed's `Vim` addon (crates/vim/src/vim.rs)
 * plus the parts of its `normal.rs`, `visual.rs`, `insert.rs` and
 * `replace.rs` that this port carries.
 *
 * **Everything here runs in Kotlin on the keystroke and touches the engine
 * only through the editor's edit primitives**, which is the rule for editor
 * commands: a `dd` is one `applyCaretEdits` batch, one JNI edit, one undo
 * step. The mode is snapshot state so the status bar, the caret shape and
 * the command line follow it without being told.
 *
 * Keys arrive as Zed keystroke names — `"a"`, `"shift-a"` is spelled as the
 * character it types (`"A"`), `"ctrl-d"`, `"escape"`, `"enter"`, `"left"` —
 * from a hardware key event ([vimKeystrokeOf]) or from a soft keyboard's
 * commit ([handleTyped]). The state machine itself does not know which.
 */
class VimState(
    internal val editor: EditorState,
    private val host: VimHost,
    private val globals: VimGlobals = VimGlobals.shared,
    startMode: VimMode = VimMode.Normal,
) {
    var mode: VimMode by mutableStateOf(VimMode.Normal)
        private set

    /**
     * What the mode indicator prints beside the mode — the pending count,
     * register and operator, as Zed's `Vim::status` renders them
     * (state.rs:1124): `2d`, `"a`, `g`.
     */
    var pendingKeys: String by mutableStateOf("")
        private set

    /** The `:` / `/` / `?` line while one is being typed. */
    var commandLine: CommandLine? by mutableStateOf(null)
        private set

    /** Vim's message line: `Pattern not found`, `E37`, `written`. Cleared by the next key. */
    var message: String? by mutableStateOf(null)
        private set

    // ---- The command being assembled --------------------------------------

    private var count: Int? = null
    private var operator: String? = null
    private var operatorCount: Int? = null
    private var prefix: String = ""
    private var register: Char? = null

    /** `j`/`k` aim here; -1 means the cursor's own column. */
    private var goalCol: Int = -1

    // ---- Visual mode -------------------------------------------------------

    private var visualAnchor: Pos by mutableStateOf(Pos(0, 0))
    private var visualCursor: Pos by mutableStateOf(Pos(0, 0))
    private var lastVisual: Triple<VimMode, Pos, Pos>? = null

    /** The caret set this layer last put on the editor, to notice a touch moving it. */
    private var lastSetCaret: Caret? = null

    // ---- Insert and replace modes -----------------------------------------

    private var insertStart: Pos? = null
    private var insertCount: Int = 1
    private var lastInsert: Pos? = null

    /** What `R` overwrote, oldest first, so Backspace can put it back. */
    private val replaced = ArrayList<String?>()

    // ---- Marks and jumps ----------------------------------------------------

    private val marks = HashMap<Char, Pos>()

    // ---- Dot repeat ----------------------------------------------------------

    private val recordKeys = ArrayList<String>()
    private var changed = false
    private var pendingInsertKeys: List<String>? = null

    /** The selection a visual `c` took, so `.` takes the same amount again. */
    private var insertVisualExtent: VisualExtent? = null
    private var replaying = false
    private var replayInsertText: String? = null

    /** A `/` or `?` typed after an operator, to finish when the line is entered. */
    private var searchOperator: String? = null
    private var searchOperatorCount: Int = 1

    init {
        when (startMode) {
            VimMode.Insert -> mode = VimMode.Insert
            VimMode.Replace -> mode = VimMode.Replace
            VimMode.Visual, VimMode.VisualLine, VimMode.VisualBlock -> enterVisual(startMode, cursorFromEditor())
            VimMode.Normal -> enterNormal()
        }
    }

    // ---- What the view asks ------------------------------------------------

    /**
     * Whether typed characters belong to this layer rather than the buffer:
     * every mode but insert, and the command line in any mode.
     */
    val wantsRawInput: Boolean
        get() = commandLine != null || mode != VimMode.Insert

    /** Zed's `Vim::cursor_shape` (vim.rs:1397-1435). */
    val cursorShape: VimCursorShape
        get() = when {
            mode == VimMode.Insert -> VimCursorShape.Bar
            mode == VimMode.Replace -> VimCursorShape.Underline
            prefix in WAITING_PREFIXES -> VimCursorShape.Underline
            else -> VimCursorShape.Block
        }

    /**
     * Where Vim's cursor is, which in a visual mode is the character the
     * selection's moving end covers rather than the caret past it.
     */
    fun cursor(): Pos = if (mode.isVisual) visualCursor else Pos(editor.cursorRow, editor.cursorCol)

    // ---- Keys ------------------------------------------------------------------

    /** A soft keyboard commit: each character is a keystroke. */
    fun handleTyped(text: String): Boolean {
        var handled = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val key = if (cp == '\n'.code) "enter" else String(Character.toChars(cp))
            handled = handleKey(key) || handled
            i += Character.charCount(cp)
        }
        return handled
    }

    /**
     * One keystroke. Returns whether the layer took it; false lets the
     * editor's own handler have it, which in insert mode is nearly always.
     */
    fun handleKey(key: String): Boolean {
        if (!replaying) message = null
        commandLine?.let { return handleCommandLineKey(it, key) }
        return when (mode) {
            VimMode.Insert -> handleInsertKey(key)
            VimMode.Replace -> handleReplaceKey(key)
            else -> {
                syncFromEditor()
                val idle = operator == null && prefix.isEmpty() && count == null && register == null
                if (idle && !replaying) {
                    recordKeys.clear()
                    insertVisualExtent = null
                }
                if (!replaying) recordKeys.add(key)
                val handled = handleNormalOrVisualKey(key)
                finishRecording()
                refreshPending()
                handled
            }
        }
    }

    /**
     * Zed's `workspace::ToggleVimMode` leaves the buffer as it is; this is
     * the tidy-up when the setting goes off under an open editor.
     */
    fun detach() {
        if (mode.isVisual) editor.clearSelection()
        editor.dropExtraCarets()
    }

    // ---- Recording -----------------------------------------------------------------

    private fun finishRecording() {
        if (replaying) return
        val idle = operator == null && prefix.isEmpty() && count == null && register == null && commandLine == null && searchOperator == null
        if (!idle) return
        when {
            mode == VimMode.Insert || mode == VimMode.Replace -> {
                pendingInsertKeys = ArrayList(recordKeys)
                changed = false
            }
            changed -> {
                globals.lastChange = DotRecord(ArrayList(recordKeys))
                changed = false
            }
        }
    }

    private fun recordVisualChange(keys: List<String>, extent: VisualExtent, insertText: String? = null) {
        if (replaying) return
        globals.lastChange = DotRecord(keys, insertText, extent)
        pendingInsertKeys = null
        changed = false
    }

    private fun refreshPending() {
        pendingKeys = buildString {
            register?.let { append('"').append(it) }
            operatorCount?.let { append(it) }
            operator?.let { append(it) }
            count?.let { append(it) }
            append(prefix)
        }
    }

    // ---- Keeping up with the editor ---------------------------------------------

    /**
     * A tap, a drag or a search hit moves the editor's caret without this
     * layer hearing about it. Before every key, read it back: a selection
     * made by touch in normal mode becomes a visual one, exactly as a mouse
     * drag does in Zed (vim.rs `local_selections_changed`), and a visual
     * selection collapsed by a tap is normal mode again.
     */
    private fun syncFromEditor() {
        val primary = editor.primaryCaret()
        if (primary == lastSetCaret) return
        if (mode.isVisual) {
            if (primary.isEmpty) {
                mode = VimMode.Normal
                setCursor(editor.clampToRow(Pos(primary.headRow, primary.headCol)))
            } else {
                adoptSelection(primary)
            }
        } else if (mode == VimMode.Normal) {
            if (primary.isEmpty) {
                val clamped = editor.clampToRow(Pos(primary.headRow, primary.headCol))
                if (clamped != Pos(primary.headRow, primary.headCol)) setCursor(clamped) else lastSetCaret = primary
            } else {
                mode = VimMode.Visual
                adoptSelection(primary)
            }
        }
    }

    private fun adoptSelection(primary: Caret) {
        val forward = primary.anchorRow < primary.headRow ||
            (primary.anchorRow == primary.headRow && primary.anchorCol <= primary.headCol)
        if (forward) {
            visualAnchor = Pos(primary.anchorRow, primary.anchorCol)
            visualCursor = editor.clampToRow(Pos(primary.headRow, (primary.headCol - 1).coerceAtLeast(0)))
        } else {
            visualAnchor = editor.clampToRow(Pos(primary.anchorRow, (primary.anchorCol - 1).coerceAtLeast(0)))
            visualCursor = Pos(primary.headRow, primary.headCol)
        }
        if (mode == VimMode.VisualLine || mode == VimMode.VisualBlock) mode = VimMode.Visual
        lastSetCaret = primary
    }

    private fun cursorFromEditor(): Pos = editor.clampToRow(Pos(editor.cursorRow, editor.cursorCol))

    /** Put the one normal-mode caret at [pos], clipped to its row. */
    private fun setCursor(pos: Pos) {
        val at = editor.clampToRow(pos)
        val caret = Caret(at.row, at.col)
        editor.setCarets(listOf(caret), caret)
        lastSetCaret = caret
    }

    /** As [setCursor], but allowed on the newline — where insert mode may sit. */
    private fun setInsertCursor(pos: Pos) {
        val at = editor.clampToLine(pos)
        val caret = Caret(at.row, at.col)
        editor.setCarets(listOf(caret), caret)
        lastSetCaret = caret
    }

    // ---- Modes ------------------------------------------------------------------------

    private fun enterNormal() {
        mode = VimMode.Normal
        prefix = ""
        operator = null
        operatorCount = null
        count = null
        register = null
        setCursor(cursorFromEditor())
        editor.onCursorChangedExternally?.invoke()
    }

    private fun enterInsert(at: Pos, times: Int = 1) {
        mode = VimMode.Insert
        setInsertCursor(at)
        insertStart = editor.clampToLine(at)
        insertCount = times
        lastInsert = insertStart
        editor.onCursorChangedExternally?.invoke()
        replayInsertText?.let { text ->
            // `.` replaying an insert: the text goes in as one edit and the
            // mode comes straight back, as Zed replays a recorded insert.
            replayInsertText = null
            if (text.isNotEmpty()) Operators.insert(editor, insertStart!!, text)
            leaveInsert()
        }
    }

    /**
     * Escape from insert: the caret steps back onto the last character typed
     * (Zed's `vim::NormalBefore`), a count repeats what was typed, and the
     * typed text is what `.` will replay.
     */
    private fun leaveInsert() {
        val start = insertStart
        val end = Pos(editor.cursorRow, editor.cursorCol)
        var typed = ""
        if (start != null && end >= start && start.row <= editor.lastRow) {
            typed = editor.textBetween(start, end)
        }
        if (insertCount > 1 && typed.isNotEmpty() && !replaying) {
            Operators.insert(editor, end, typed.repeat(insertCount - 1))
            typed = typed.repeat(insertCount)
        }
        if (!replaying) {
            pendingInsertKeys?.let { keys -> globals.lastChange = DotRecord(keys, typed, insertVisualExtent) }
        }
        pendingInsertKeys = null
        insertVisualExtent = null
        insertStart = null
        insertCount = 1
        replaced.clear()
        editor.dropExtraCarets()
        mode = VimMode.Normal
        val here = Pos(editor.cursorRow, editor.cursorCol)
        setCursor(Pos(here.row, (here.col - 1).coerceAtLeast(0)))
        editor.onCursorChangedExternally?.invoke()
    }

    private fun enterVisual(kind: VimMode, at: Pos) {
        visualAnchor = at
        visualCursor = at
        mode = kind
        syncVisualSelection()
    }

    private fun leaveVisual() {
        val (start, end) = visualBounds()
        marks['<'] = start
        marks['>'] = end
        lastVisual = Triple(mode, visualAnchor, visualCursor)
        mode = VimMode.Normal
        editor.dropExtraCarets()
        setCursor(visualCursor)
    }

    private fun visualBounds(): Pair<Pos, Pos> =
        if (visualAnchor <= visualCursor) visualAnchor to visualCursor else visualCursor to visualAnchor

    /**
     * Paint the visual selection on the editor. Vim's selection includes the
     * character under the cursor; the editor's is half-open, so the moving
     * end sits one past it. Visual block is one selection per row, which is
     * how Zed does it too (docs/src/vim.md "Visual block selections").
     */
    private fun syncVisualSelection() {
        val anchor = visualAnchor
        val cursor = visualCursor
        val forward = anchor <= cursor
        val primary: Caret
        val carets: List<Caret>
        when (mode) {
            VimMode.VisualLine -> {
                primary = if (forward) {
                    Caret(anchor.row, 0, cursor.row, editor.lineLength(cursor.row))
                } else {
                    Caret(anchor.row, editor.lineLength(anchor.row), cursor.row, 0)
                }
                carets = listOf(primary)
            }
            VimMode.VisualBlock -> {
                val left = minOf(anchor.col, cursor.col)
                val right = if (goalCol == GOAL_END_OF_LINE) Int.MAX_VALUE else maxOf(anchor.col, cursor.col)
                val first = minOf(anchor.row, cursor.row)
                val last = maxOf(anchor.row, cursor.row)
                val rows = ArrayList<Caret>()
                var head: Caret? = null
                for (row in first..last) {
                    val len = editor.lineLength(row)
                    if (len <= left && row != cursor.row) continue
                    val end = if (right == Int.MAX_VALUE) len else (right + 1).coerceAtMost(len)
                    val start = left.coerceAtMost(len)
                    val caret = if (anchor.col <= cursor.col) Caret(row, start, row, end) else Caret(row, end, row, start)
                    rows.add(caret)
                    if (row == cursor.row) head = caret
                }
                primary = head ?: rows.last()
                carets = rows
            }
            else -> {
                primary = if (forward) {
                    Caret(anchor.row, anchor.col, cursor.row, (cursor.col + 1).coerceAtMost(editor.lineLength(cursor.row)))
                } else {
                    Caret(anchor.row, (anchor.col + 1).coerceAtMost(editor.lineLength(anchor.row)), cursor.row, cursor.col)
                }
                carets = listOf(primary)
            }
        }
        editor.setCarets(carets, primary)
        lastSetCaret = editor.primaryCaret()
    }

    // ---- Insert and replace keys -----------------------------------------------------

    private fun handleInsertKey(key: String): Boolean {
        when (key) {
            "escape", "ctrl-[", "ctrl-c" -> {
                leaveInsert()
                return true
            }
        }
        return false
    }

    /**
     * `R`: each character typed overwrites the one under the caret, and
     * Backspace puts the overwritten one back (Zed's replace.rs `undo_replace`).
     */
    private fun handleReplaceKey(key: String): Boolean {
        when (key) {
            "escape", "ctrl-[", "ctrl-c" -> {
                leaveInsert()
                return true
            }
            "enter" -> {
                editor.insertNewline()
                replaced.add(null)
                return true
            }
            "backspace" -> {
                if (replaced.isEmpty()) return true
                val original = replaced.removeAt(replaced.lastIndex)
                val here = Pos(editor.cursorRow, editor.cursorCol)
                if (here.col == 0) {
                    setInsertCursor(if (here.row > 0) editor.endOfRow(here.row - 1) else here)
                    return true
                }
                val before = Pos(here.row, here.col - 1)
                Operators.replace(editor, OpRange(before, here, linewise = false), original ?: "")
                setInsertCursor(before)
                return true
            }
            "left", "right", "up", "down", "home", "end" -> return false
        }
        if (key.length > 1 && key != "tab") return false
        val text = if (key == "tab") editor.indentUnit() else key
        val here = Pos(editor.cursorRow, editor.cursorCol)
        val line = editor.line(here.row)
        if (here.col < line.length) {
            val next = line.offsetByCodePoints(here.col, 1)
            replaced.add(line.substring(here.col, next))
            Operators.replace(editor, OpRange(here, Pos(here.row, next), linewise = false), text)
        } else {
            replaced.add(null)
            Operators.insert(editor, here, text)
        }
        changed = true
        return true
    }

    // ---- Normal and visual keys ------------------------------------------------------

    private fun handleNormalOrVisualKey(key: String): Boolean {
        // A count: digits, except a leading 0, which is a motion.
        if (key.length == 1 && key[0].isDigit() && (key != "0" || count != null) && prefix.isEmpty()) {
            count = ((count ?: 0) * 10 + key.toInt()).coerceAtMost(99_999)
            return true
        }
        if (prefix.isNotEmpty()) {
            val full = "$prefix $key"
            prefix = ""
            return handleCombined(full, key)
        }
        return when (key) {
            "escape", "ctrl-[", "ctrl-c" -> {
                if (operator != null || count != null || register != null) {
                    cancelPending()
                } else if (mode.isVisual) {
                    leaveVisual()
                } else {
                    editor.dropExtraCarets()
                    setCursor(cursorFromEditor())
                }
                true
            }
            "\"", "f", "F", "t", "T", "g", "z", "'", "`", "m", "r", "[", "]", "Z", "ctrl-w" -> {
                if ((key == "m" || key == "r" || key == "Z") && operator != null) return cancelPending()
                prefix = key
                true
            }
            "i", "a" -> when {
                operator != null || mode.isVisual && mode != VimMode.VisualBlock -> {
                    prefix = key
                    true
                }
                mode == VimMode.VisualBlock -> true
                else -> {
                    val here = cursorFromEditor()
                    val at = if (key == "a" && editor.lineLength(here.row) > 0) Pos(here.row, here.col + 1) else here
                    enterInsert(at, takeCount())
                    true
                }
            }
            ":" -> {
                if (operator != null) return cancelPending()
                val range = when {
                    mode.isVisual -> {
                        // `:` ends visual mode and the line opens on the
                        // selection's marks (`:help v_:`), which are set as
                        // the mode is left.
                        leaveVisual()
                        "'<,'>"
                    }
                    count != null -> ".,.+${takeCount() - 1}"
                    else -> ""
                }
                commandLine = CommandLine(':', range)
                true
            }
            "/", "?" -> {
                if (operator != null) {
                    searchOperator = operator
                    searchOperatorCount = takeCount()
                    operator = null
                    operatorCount = null
                }
                commandLine = CommandLine(key[0], "")
                true
            }
            else -> {
                if (operator != null) return handleOperatorPendingKey(key)
                if (mode.isVisual) handleVisualKey(key) else handleNormalKey(key)
            }
        }
    }

    private fun cancelPending(): Boolean {
        operator = null
        operatorCount = null
        count = null
        register = null
        prefix = ""
        searchOperator = null
        return true
    }

    /** The count typed so far, spent; 1 when there was none. */
    private fun takeCount(): Int {
        val motion = count
        val op = operatorCount
        count = null
        operatorCount = null
        return when {
            motion != null && op != null -> motion * op
            motion != null -> motion
            op != null -> op
            else -> 1
        }
    }

    private fun hasCount(): Boolean = count != null || operatorCount != null

    /** A prefix key and the key after it. */
    private fun handleCombined(full: String, key: String): Boolean {
        val first = full.substringBefore(' ')
        when (first) {
            "\"" -> {
                if (key.length == 1 && (key[0].isLetterOrDigit() || key[0] in "\"+*-_")) register = key[0]
                return true
            }
            "f", "F", "t", "T" -> {
                if (key.length != 1) return cancelPending()
                val forward = first == "f" || first == "t"
                val till = first == "t" || first == "T"
                globals.lastFind = VimGlobals.Find(key[0], forward, till)
                return applyMotion(findMotion(key[0], forward, till, repeat = false))
            }
            "m" -> {
                if (key.length == 1 && key[0].isLetter()) marks[key[0]] = cursor()
                return true
            }
            "'", "`" -> {
                val target = markPosition(key) ?: return cancelPending()
                val kind = if (first == "'") MotionKind.Linewise else MotionKind.Exclusive
                val at = if (first == "'") Pos(target.row, editor.firstNonBlank(target.row)) else target
                return applyMotion(MotionTarget(editor.clampToRow(at), kind, jump = true))
            }
            "r" -> {
                if (key.length != 1 && key != "enter") return cancelPending()
                val ch = if (key == "enter") '\n' else key[0]
                return replaceUnderCursor(ch)
            }
            "Z" -> {
                when (key) {
                    "Z" -> host.saveAndClose()
                    "Q" -> host.closeTab(force = true)
                    else -> {}
                }
                return true
            }
            "z" -> {
                scrollCursorTo(key)
                return true
            }
            "ctrl-w" -> {
                message = "Split panes are not available in Thragg"
                return cancelPending()
            }
            "[", "]" -> {
                when (key) {
                    "d" -> editor.goToDiagnostic(forward = first == "]").also { setCursor(cursorFromEditor()) }
                    "(" , ")" -> return applyMotion(unmatched(first == "]", '(', ')'))
                    "{", "}" -> return applyMotion(unmatched(first == "]", '{', '}'))
                    else -> {}
                }
                return true
            }
            "i", "a" -> return applyTextObject(key, around = first == "a")
            "g" -> return handleG(key)
        }
        return cancelPending()
    }

    private fun handleG(key: String): Boolean {
        when (key) {
            "g" -> return applyMotion(lineMotion(if (hasCount()) takeCount() - 1 else 0, jump = true))
            "e", "E" -> {
                val target = Motions.previousWordEnd(editor, cursor(), takeCount(), big = key == "E")
                return applyMotion(MotionTarget(target, MotionKind.Inclusive))
            }
            "_" -> {
                val row = (cursor().row + takeCount() - 1).coerceAtMost(editor.lastRow)
                val text = editor.line(row).trimEnd()
                return applyMotion(MotionTarget(Pos(row, (text.length - 1).coerceAtLeast(0)), MotionKind.Inclusive))
            }
            "j", "k", "down", "up" -> return applyMotion(rowMotion(if (key == "j" || key == "down") takeCount() else -takeCount()))
            "d" -> {
                if (operator != null) return cancelPending()
                host.goToDefinition()
                return true
            }
            "v" -> {
                if (operator != null) return cancelPending()
                val last = lastVisual ?: return true
                if (mode.isVisual) leaveVisual()
                mode = last.first
                visualAnchor = editor.clampToRow(last.second)
                visualCursor = editor.clampToRow(last.third)
                syncVisualSelection()
                return true
            }
            "i" -> {
                if (operator != null) return cancelPending()
                enterInsert(lastInsert ?: cursor())
                return true
            }
            "J" -> {
                if (operator != null) return cancelPending()
                return joinLines(spaces = false)
            }
            "u", "U", "~", "c" -> {
                val op = "g$key"
                if (mode.isVisual) return runVisualOperator(op, listOf("g", key))
                if (operator == op) return runLinewiseOperator(op)
                if (operator != null) return cancelPending()
                operator = op
                operatorCount = count
                count = null
                return true
            }
            "I" -> {
                if (mode.isVisual) return visualInsert(atEnd = false, everyRow = true)
                enterInsert(Pos(cursor().row, 0))
                return true
            }
            "A" -> {
                if (mode.isVisual) return visualInsert(atEnd = true, everyRow = true)
                return cancelPending()
            }
            "*", "#" -> return searchWordUnderCursor(backwards = key == "#", wholeWord = false)
            "0", "home" -> return applyMotion(MotionTarget(Pos(cursor().row, 0), MotionKind.Exclusive, goalCol = 0))
            "$", "end" -> return applyMotion(MotionTarget(editor.clampToRow(editor.endOfRow(cursor().row)), MotionKind.Inclusive, goalCol = GOAL_END_OF_LINE))
            "^" -> return applyMotion(MotionTarget(Pos(cursor().row, editor.firstNonBlank(cursor().row)), MotionKind.Exclusive))
            else -> return cancelPending()
        }
    }

    // ---- Normal mode -----------------------------------------------------------------------

    private fun handleNormalKey(key: String): Boolean {
        motionFor(key)?.let { return applyMotion(it) }
        val here = cursorFromEditor()
        when (key) {
            "d", "c", "y", ">", "<", "=" -> {
                operator = key
                operatorCount = count
                count = null
                return true
            }
            "v" -> enterVisual(VimMode.Visual, here)
            "V" -> enterVisual(VimMode.VisualLine, here)
            "ctrl-v", "ctrl-q" -> enterVisual(VimMode.VisualBlock, here)
            "I" -> enterInsert(Pos(here.row, editor.firstNonBlank(here.row)), takeCount())
            "A" -> enterInsert(editor.endOfRow(here.row), takeCount())
            "insert" -> enterInsert(here, takeCount())
            "o", "O" -> {
                takeCount()
                changed = true
                if (key == "o") {
                    // Enter at the end of the line: the editor's own
                    // auto-indent, so `o` after `{` indents as Enter would.
                    setInsertCursor(editor.endOfRow(here.row))
                    editor.insertNewline()
                    enterInsert(Pos(editor.cursorRow, editor.cursorCol))
                } else {
                    enterInsert(Operators.openLine(editor, here.row, below = false, indent = editor.indentOf(here.row)))
                }
            }
            "x", "delete" -> deleteChars(here, takeCount(), forward = true)
            "X" -> deleteChars(here, takeCount(), forward = false)
            "s" -> {
                val n = takeCount()
                if (editor.lineLength(here.row) > 0) {
                    val end = Pos(here.row, (here.col + n).coerceAtMost(editor.lineLength(here.row)))
                    yankRange(OpRange(here, end, linewise = false), isYank = false)
                    Operators.delete(editor, OpRange(here, end, linewise = false))
                    changed = true
                }
                enterInsert(here)
            }
            "S" -> {
                val range = OpRange(Pos(here.row, 0), Pos((here.row + takeCount() - 1).coerceAtMost(editor.lastRow), 0), linewise = true)
                yankRange(range, isYank = false)
                changed = true
                enterInsert(Operators.changeLines(editor, range))
            }
            "C" -> {
                val end = editor.endOfRow((here.row + takeCount() - 1).coerceAtMost(editor.lastRow))
                val range = OpRange(here, end, linewise = false)
                yankRange(range, isYank = false)
                Operators.delete(editor, range)
                changed = true
                enterInsert(here)
            }
            "D" -> {
                val end = editor.endOfRow((here.row + takeCount() - 1).coerceAtMost(editor.lastRow))
                val range = OpRange(here, end, linewise = false)
                yankRange(range, isYank = false)
                setCursor(Operators.delete(editor, range))
                changed = true
            }
            "Y" -> {
                val range = OpRange(Pos(here.row, 0), Pos((here.row + takeCount() - 1).coerceAtMost(editor.lastRow), 0), linewise = true)
                yankRange(range, isYank = true)
            }
            "p", "P" -> put(here, after = key == "p", times = takeCount())
            "J" -> return joinLines(spaces = true)
            "R" -> {
                takeCount()
                mode = VimMode.Replace
                replaced.clear()
                insertStart = here
                setInsertCursor(here)
                editor.onCursorChangedExternally?.invoke()
            }
            "u" -> {
                repeat(takeCount()) { editor.undo() }
                setCursor(cursorFromEditor())
            }
            "ctrl-r" -> {
                repeat(takeCount()) { editor.redo() }
                setCursor(cursorFromEditor())
            }
            "." -> repeatLastChange()
            "~" -> {
                val n = takeCount()
                val len = editor.lineLength(here.row)
                if (len > 0) {
                    val end = Pos(here.row, (here.col + n).coerceAtMost(len))
                    Operators.changeCase(editor, OpRange(here, end, linewise = false), Operators.CaseKind.Toggle)
                    changed = true
                    setCursor(end)
                }
            }
            "ctrl-a", "ctrl-x" -> incrementNumber(here, if (key == "ctrl-a") takeCount() else -takeCount())
            "ctrl-o" -> host.navigateBack()
            "ctrl-i", "tab" -> host.navigateForward()
            "K" -> {}
            else -> {
                // A printable key with no meaning is swallowed: in normal mode
                // nothing types. Chords the layer does not know go back to the
                // editor, whose own `ctrl-z` and friends still work.
                return !key.startsWith("ctrl-")
            }
        }
        return true
    }

    /**
     * The motion [key] names in normal, visual and operator-pending mode,
     * with the pending count spent; null when the key is not a motion.
     */
    private fun motionFor(key: String): MotionTarget? {
        val here = cursor()
        return when (key) {
            "h", "left", "backspace" -> {
                val n = takeCount()
                MotionTarget(Pos(here.row, (here.col - n).coerceAtLeast(0)), MotionKind.Exclusive)
            }
            "l", "right", " " -> {
                val n = takeCount()
                val len = editor.lineLength(here.row)
                MotionTarget(Pos(here.row, (here.col + n).coerceAtMost(len)), MotionKind.Exclusive)
            }
            "j", "down", "ctrl-j", "ctrl-n" -> rowMotion(takeCount())
            "k", "up", "ctrl-p" -> rowMotion(-takeCount())
            "0", "home" -> MotionTarget(Pos(here.row, 0), MotionKind.Exclusive, goalCol = 0)
            "^" -> MotionTarget(Pos(here.row, editor.firstNonBlank(here.row)), MotionKind.Exclusive)
            "$", "end" -> {
                val row = (here.row + takeCount() - 1).coerceAtMost(editor.lastRow)
                MotionTarget(editor.endOfRow(row), MotionKind.Inclusive, goalCol = GOAL_END_OF_LINE)
            }
            "|" -> MotionTarget(Pos(here.row, takeCount() - 1), MotionKind.Exclusive)
            "w", "W" -> {
                val n = takeCount()
                val big = key == "W"
                if (operator != null) {
                    val text = editor.line(here.row)
                    // `cw` on a word is `ce` (`:help cw`).
                    if (operator == "c" && here.col < text.length && !text[here.col].isWhitespace()) {
                        MotionTarget(Motions.nextWordEnd(editor, here, n, big), MotionKind.Inclusive)
                    } else {
                        MotionTarget(Motions.nextWordStartForOperator(editor, here, n, big), MotionKind.Exclusive)
                    }
                } else {
                    MotionTarget(Motions.nextWordStart(editor, here, n, big), MotionKind.Exclusive)
                }
            }
            "b", "B" -> MotionTarget(Motions.previousWordStart(editor, here, takeCount(), key == "B"), MotionKind.Exclusive)
            "e", "E" -> MotionTarget(Motions.nextWordEnd(editor, here, takeCount(), key == "E"), MotionKind.Inclusive)
            "G" -> lineMotion(if (hasCount()) takeCount() - 1 else editor.lastRow, jump = true)
            "{", "}" -> MotionTarget(Motions.paragraph(editor, here, takeCount(), forward = key == "}"), MotionKind.Exclusive, jump = true)
            "%" -> {
                if (hasCount()) {
                    // `N%`: to N percent of the file (`:help N%`).
                    val n = takeCount().coerceIn(1, 100)
                    lineMotion((n * editor.lineCount + 99) / 100 - 1, jump = true)
                } else {
                    val target = Motions.matchingBracket(editor, here) ?: return MotionTarget(here, MotionKind.Inclusive)
                    MotionTarget(target, MotionKind.Inclusive, jump = true)
                }
            }
            ";", "," -> {
                val last = globals.lastFind ?: return MotionTarget(here, MotionKind.Exclusive)
                val forward = if (key == ";") last.forward else !last.forward
                findMotion(last.target, forward, last.till, repeat = true)
            }
            "H", "L", "M" -> lineMotion(Motions.windowRow(editor, key[0], takeCount()))
            "enter", "+" -> lineMotion((here.row + takeCount()).coerceAtMost(editor.lastRow))
            "-" -> lineMotion((here.row - takeCount()).coerceAtLeast(0))
            "_" -> lineMotion((here.row + takeCount() - 1).coerceAtMost(editor.lastRow))
            "ctrl-d", "ctrl-u" -> {
                val rows = (editor.viewportRows() / 2).coerceAtLeast(1)
                scrollBy(if (key == "ctrl-d") rows else -rows)
            }
            "ctrl-f", "pagedown", "ctrl-b", "pageup" -> {
                val rows = (editor.viewportRows() - 2).coerceAtLeast(1)
                scrollBy(if (key == "ctrl-f" || key == "pagedown") rows else -rows)
            }
            "ctrl-e", "ctrl-y" -> {
                // Scroll the view a line without moving the caret unless it
                // would leave the screen.
                val delta = if (key == "ctrl-e") takeCount() else -takeCount()
                editor.scrollToY(editor.scrollY + delta * editor.lineHeightPx)
                val first = editor.firstDisplayRow()
                val last = (editor.lastDisplayRow(first) - 2).coerceAtLeast(first)
                val row = here.row.coerceIn(editor.pointAtDisplayRow(first, 0).first, editor.pointAtDisplayRow(last, 0).first)
                if (row == here.row) MotionTarget(here, MotionKind.Exclusive) else lineMotion(row)
            }
            "n", "N" -> {
                val last = globals.lastSearch ?: return MotionTarget(here, MotionKind.Exclusive).also { message = "E35: No previous regular expression" }
                val backwards = if (key == "n") last.backwards else !last.backwards
                val target = searchFrom(here, last, backwards, takeCount()) ?: return MotionTarget(here, MotionKind.Exclusive)
                MotionTarget(target, MotionKind.Exclusive, jump = true)
            }
            "*", "#" -> {
                searchWordUnderCursor(backwards = key == "#", wholeWord = true)
                null
            }
            else -> null
        }
    }

    private fun rowMotion(delta: Int): MotionTarget {
        val here = cursor()
        val row = (here.row + delta).coerceIn(0, editor.lastRow)
        val goal = if (goalCol < 0) here.col else goalCol
        val col = if (goal == GOAL_END_OF_LINE) editor.lineLength(row) else goal
        return MotionTarget(Pos(row, col), MotionKind.Linewise, goalCol = goal)
    }

    /** A linewise motion to [row], landing on its first non-blank (`:help G`). */
    private fun lineMotion(row: Int, jump: Boolean = false): MotionTarget {
        val at = row.coerceIn(0, editor.lastRow)
        return MotionTarget(Pos(at, editor.firstNonBlank(at)), MotionKind.Linewise, jump = jump)
    }

    private fun findMotion(target: Char, forward: Boolean, till: Boolean, repeat: Boolean): MotionTarget? {
        val n = takeCount()
        val pos = Motions.findInLine(editor, cursor(), target, n, forward, till, repeat) ?: return null
        return MotionTarget(pos, if (forward) MotionKind.Inclusive else MotionKind.Exclusive)
    }

    private fun unmatched(forward: Boolean, open: Char, close: Char): MotionTarget? {
        val here = cursor()
        val pos = if (forward) {
            Motions.scanForward(editor, Pos(here.row, here.col + 1), open, close, startsInside = true)
        } else {
            Motions.scanBackward(editor, Pos(here.row, here.col - 1), open, close, startsInside = true)
        }
        return pos?.let { MotionTarget(it, if (forward) MotionKind.Inclusive else MotionKind.Exclusive) }
    }

    /** `ctrl-d` and friends: the caret and the view move together (`:help CTRL-D`). */
    private fun scrollBy(rows: Int): MotionTarget {
        takeCount()
        editor.scrollToY(editor.scrollY + rows * editor.lineHeightPx)
        val target = rowMotion(rows)
        return target
    }

    private fun scrollCursorTo(key: String) {
        val here = cursor()
        val display = editor.displayRowOf(here.row, here.col)
        val rows = editor.viewportRows()
        val top = when (key) {
            "t", "enter" -> display
            "b", "-" -> display - rows + 1
            "z", "." -> display - rows / 2
            else -> return
        }
        editor.scrollToY(top.coerceAtLeast(0) * editor.lineHeightPx)
        if (key == "enter" || key == "-" || key == ".") setCursor(Pos(here.row, editor.firstNonBlank(here.row)))
    }

    /**
     * A motion in normal mode moves the caret; in visual mode it moves the
     * selection's end; after an operator it is the operator's range.
     */
    private fun applyMotion(target: MotionTarget?): Boolean {
        if (target == null) return cancelPending()
        goalCol = target.goalCol ?: -1
        val op = operator
        if (op != null) {
            operator = null
            return runOperator(op, motionRange(cursor(), target))
        }
        // `''` goes back to where the jump left from (`:help ''`).
        if (target.jump) marks['\''] = cursor()
        if (mode.isVisual) {
            visualCursor = if (mode == VimMode.VisualBlock && target.goalCol == GOAL_END_OF_LINE) {
                target.pos
            } else {
                editor.clampToRow(target.pos)
            }
            syncVisualSelection()
        } else {
            setCursor(target.pos)
        }
        return true
    }

    /**
     * Vim's rules for what an operator takes from a motion (`:help exclusive`):
     * an inclusive motion takes its end character; an exclusive one whose end
     * is at the start of a row moves its end to the end of the row before
     * and, if it began at or before the first non-blank, becomes linewise.
     */
    private fun motionRange(from: Pos, target: MotionTarget): OpRange {
        var start = minOf(from, target.pos)
        var end = maxOf(from, target.pos)
        when (target.kind) {
            MotionKind.Linewise -> return OpRange(Pos(start.row, 0), Pos(end.row, 0), linewise = true)
            MotionKind.Inclusive -> {
                val len = editor.lineLength(end.row)
                end = Pos(end.row, (end.col + 1).coerceAtMost(len))
            }
            MotionKind.Exclusive -> {
                if (end.col == 0 && end.row > start.row) {
                    end = editor.endOfRow(end.row - 1)
                    if (start.col <= editor.firstNonBlank(start.row)) {
                        return OpRange(Pos(start.row, 0), Pos(end.row, 0), linewise = true)
                    }
                }
            }
        }
        start = editor.clampToLine(start)
        end = editor.clampToLine(end)
        return OpRange(start, end, linewise = false)
    }

    // ---- Operators -------------------------------------------------------------------------

    private fun handleOperatorPendingKey(key: String): Boolean {
        val op = operator!!
        // The doubled operator takes lines: `dd`, `cc`, `yy`, `>>`, `<<`,
        // `==`, and `gcc` / `guu` / `gUU` / `g~~` for the `g` family.
        if (key == op || (op.length == 2 && op[0] == 'g' && key == op.substring(1))) return runLinewiseOperator(op)
        motionFor(key)?.let { return applyMotion(it) }
        return cancelPending()
    }

    private fun runLinewiseOperator(op: String): Boolean {
        val n = takeCount()
        operator = null
        val here = cursor()
        val last = (here.row + n - 1).coerceAtMost(editor.lastRow)
        return runOperator(op, OpRange(Pos(here.row, 0), Pos(last, 0), linewise = true))
    }

    private fun applyTextObject(key: String, around: Boolean): Boolean {
        val here = cursor()
        val range = when (key) {
            "w" -> TextObjects.word(editor, here, around, big = false)
            "W" -> TextObjects.word(editor, here, around, big = true)
            "(", ")", "b" -> TextObjects.bracket(editor, here, '(', ')', around)
            "[", "]" -> TextObjects.bracket(editor, here, '[', ']', around)
            "{", "}", "B" -> TextObjects.bracket(editor, here, '{', '}', around)
            "<", ">" -> TextObjects.bracket(editor, here, '<', '>', around)
            "\"", "'", "`" -> TextObjects.quote(editor, here, key[0], around)
            "t" -> TextObjects.tag(editor, here, around)
            "p" -> TextObjects.paragraph(editor, here, around)
            else -> null
        } ?: return cancelPending()
        takeCount()
        val op = operator
        if (op != null) {
            operator = null
            return runOperator(op, OpRange(range.start, range.end, range.linewise))
        }
        // Visual mode: the selection becomes the object.
        if (range.linewise) {
            if (mode != VimMode.VisualLine) mode = VimMode.VisualLine
            visualAnchor = Pos(range.start.row, 0)
            visualCursor = Pos(range.end.row, 0)
        } else {
            visualAnchor = range.start
            visualCursor = editor.clampToRow(Pos(range.end.row, range.end.col - 1))
        }
        syncVisualSelection()
        return true
    }

    /** Run [op] over [range] from normal or operator-pending mode. */
    private fun runOperator(op: String, range: OpRange): Boolean {
        val times = takeCount()
        when (op) {
            "d" -> {
                yankRange(range, isYank = false)
                val at = Operators.delete(editor, range)
                changed = true
                setCursor(at)
            }
            "y" -> {
                yankRange(range, isYank = true)
                // `yiw` lands on the word; `yj` stays put and `yk` goes up
                // to the first yanked row (`:help y`).
                setCursor(if (range.linewise) Pos(minOf(cursor().row, range.firstRow), cursor().col) else range.start)
            }
            "c" -> {
                yankRange(range, isYank = false)
                changed = true
                if (range.linewise) {
                    enterInsert(Operators.changeLines(editor, range))
                } else {
                    Operators.delete(editor, range)
                    enterInsert(range.start)
                }
            }
            ">", "<" -> {
                Operators.shift(editor, range.firstRow, range.lastRow, times, indent = op == ">")
                changed = true
                setCursor(Pos(range.firstRow, editor.firstNonBlank(range.firstRow)))
            }
            "=" -> {
                Operators.autoIndent(editor, range.firstRow, range.lastRow)
                changed = true
                setCursor(Pos(range.firstRow, editor.firstNonBlank(range.firstRow)))
            }
            "gu", "gU", "g~" -> {
                val kind = when (op) {
                    "gu" -> Operators.CaseKind.Lower
                    "gU" -> Operators.CaseKind.Upper
                    else -> Operators.CaseKind.Toggle
                }
                // Read before the edit moves the editor's caret: `guu`
                // keeps the column (`:help gu`).
                val column = cursor().col
                Operators.changeCase(editor, range, kind)
                changed = true
                setCursor(if (range.linewise) Pos(range.firstRow, column) else range.start)
            }
            "gc" -> {
                val here = cursor()
                Operators.toggleComment(editor, range.firstRow, range.lastRow)
                changed = true
                setCursor(here)
            }
            else -> return cancelPending()
        }
        register = null
        return true
    }

    private fun yankRange(range: OpRange, isYank: Boolean) {
        globals.registers.write(register, Operators.textOf(editor, range), isYank, host)
        register = null
    }

    private fun deleteChars(here: Pos, n: Int, forward: Boolean) {
        val len = editor.lineLength(here.row)
        if (len == 0) return
        val range = if (forward) {
            OpRange(here, Pos(here.row, (here.col + n).coerceAtMost(len)), linewise = false)
        } else {
            if (here.col == 0) return
            OpRange(Pos(here.row, (here.col - n).coerceAtLeast(0)), here, linewise = false)
        }
        yankRange(range, isYank = false)
        setCursor(Operators.delete(editor, range))
        changed = true
        register = null
    }

    private fun put(here: Pos, after: Boolean, times: Int) {
        val value = globals.registers.read(register, host)
        register = null
        if (value == null) return
        setCursor(Operators.put(editor, here, value, after, times))
        changed = true
    }

    private fun joinLines(spaces: Boolean): Boolean {
        val n = if (mode.isVisual) {
            val (start, end) = visualBounds()
            leaveVisual()
            setCursor(start)
            (end.row - start.row + 1).coerceAtLeast(2)
        } else {
            takeCount().coerceAtLeast(2)
        }
        if (spaces) {
            Operators.join(editor, cursor().row, n)
        } else {
            val last = (cursor().row + n - 1).coerceAtMost(editor.lastRow)
            val text = (cursor().row..last).joinToString("") { editor.line(it) }
            Operators.replace(editor, OpRange(Pos(cursor().row, 0), Pos(last, 0), linewise = true), text)
        }
        changed = true
        setCursor(cursorFromEditor())
        return true
    }

    private fun replaceUnderCursor(ch: Char): Boolean {
        val n = takeCount()
        if (mode.isVisual) {
            val (start, end) = visualBounds()
            val linewise = mode == VimMode.VisualLine
            val range = if (linewise) OpRange(Pos(start.row, 0), Pos(end.row, 0), true) else OpRange(start, Pos(end.row, (end.col + 1).coerceAtMost(editor.lineLength(end.row))), false)
            val text = Operators.textOf(editor, range).text.let { if (linewise) it.dropLast(1) else it }
            val extent = visualExtent()
            leaveVisual()
            Operators.replace(editor, range, buildString { for (c in text) append(if (c == '\n') '\n' else ch) })
            setCursor(start)
            recordVisualChange(listOf("r", ch.toString()), extent)
            changed = false
            return true
        }
        val here = cursorFromEditor()
        if (ch == '\n') {
            Operators.replace(editor, OpRange(here, Pos(here.row, (here.col + n).coerceAtMost(editor.lineLength(here.row))), false), "\n")
            setCursor(Pos(here.row + 1, 0))
            changed = true
            return true
        }
        val at = Operators.replaceChars(editor, here, n, ch) ?: return true
        setCursor(at)
        changed = true
        return true
    }

    /** `ctrl-a` / `ctrl-x`: the number under or after the cursor, by [delta]. */
    private fun incrementNumber(here: Pos, delta: Int) {
        val text = editor.line(here.row)
        val match = NUMBER.findAll(text).firstOrNull { it.range.last >= here.col } ?: return
        val value = match.value.toLongOrNull() ?: return
        val replacement = (value + delta).toString()
        Operators.replace(editor, OpRange(Pos(here.row, match.range.first), Pos(here.row, match.range.last + 1), false), replacement)
        setCursor(Pos(here.row, match.range.first + replacement.length - 1))
        changed = true
    }

    // ---- Visual mode --------------------------------------------------------------------------

    private fun visualExtent(): VisualExtent {
        val (start, end) = visualBounds()
        return VisualExtent(mode, end.row - start.row, if (mode == VimMode.VisualLine) 0 else end.col - start.col)
    }

    private fun handleVisualKey(key: String): Boolean {
        motionFor(key)?.let { return applyMotion(it) }
        val here = visualCursor
        when (key) {
            "v", "V", "ctrl-v", "ctrl-q" -> {
                val kind = when (key) {
                    "v" -> VimMode.Visual
                    "V" -> VimMode.VisualLine
                    else -> VimMode.VisualBlock
                }
                if (mode == kind) leaveVisual() else {
                    mode = kind
                    syncVisualSelection()
                }
            }
            "o", "O" -> {
                val anchor = visualAnchor
                visualAnchor = visualCursor
                visualCursor = anchor
                syncVisualSelection()
            }
            "d", "x", "delete", "y", "c", "s", ">", "<", "=", "~", "u", "U", "J", "p", "P" -> {
                val op = when (key) {
                    "x", "delete" -> "d"
                    "s" -> "c"
                    "~" -> "g~"
                    "u" -> "gu"
                    "U" -> "gU"
                    else -> key
                }
                if (op == "J") return joinLines(spaces = true)
                if (op == "p" || op == "P") return visualPut(preserve = op == "P")
                return runVisualOperator(op, listOf(key))
            }
            "D", "X" -> {
                mode = VimMode.VisualLine
                return runVisualOperator("d", listOf(key))
            }
            "Y" -> {
                mode = VimMode.VisualLine
                return runVisualOperator("y", listOf(key))
            }
            "C", "S", "R" -> {
                mode = VimMode.VisualLine
                return runVisualOperator("c", listOf(key))
            }
            "I", "A" -> return visualInsert(atEnd = key == "A", everyRow = false)
            "*", "#" -> {}
            else -> return !key.startsWith("ctrl-")
        }
        return true
    }

    /** An operator over the visual selection, which then ends. */
    private fun runVisualOperator(op: String, keys: List<String>): Boolean {
        val (start, end) = visualBounds()
        val extent = visualExtent()
        val kind = mode
        val range = when (kind) {
            VimMode.VisualLine -> OpRange(Pos(start.row, 0), Pos(end.row, 0), linewise = true)
            VimMode.VisualBlock -> null
            else -> OpRange(start, Pos(end.row, (end.col + 1).coerceAtMost(editor.lineLength(end.row))), linewise = false)
        }
        if (range == null) return runBlockOperator(op, keys, extent)
        leaveVisual()
        val handled = runOperator(op, range)
        if (op != "y") recordVisualChange(keys, extent)
        if (op == "c") pendingInsertKeys = keys.also { insertVisualExtent = extent }
        return handled
    }

    /**
     * Visual block operators: one edit per row, in a single batch, so the
     * block deletes as one undo step. `c` leaves a caret on every row, which
     * is Zed's block change (docs/src/vim.md: "anything you insert after a
     * block selection updates on every line").
     */
    private fun runBlockOperator(op: String, keys: List<String>, extent: VisualExtent): Boolean {
        val (start, end) = visualBounds()
        val left = minOf(visualAnchor.col, visualCursor.col)
        val right = if (goalCol == GOAL_END_OF_LINE) Int.MAX_VALUE else maxOf(visualAnchor.col, visualCursor.col)
        val rows = start.row..end.row
        fun rowRange(row: Int): Pair<Int, Int>? {
            val len = editor.lineLength(row)
            if (len <= left) return null
            return left to (if (right == Int.MAX_VALUE) len else (right + 1).coerceAtMost(len))
        }
        val pieces = rows.mapNotNull { row -> rowRange(row)?.let { (a, b) -> editor.line(row).substring(a, b) } }
        when (op) {
            "y", "d", "c" -> {
                globals.registers.write(register, Register(pieces.joinToString("\n"), linewise = false), op == "y", host)
                register = null
                if (op == "y") {
                    leaveVisual()
                    setCursor(Pos(start.row, left))
                    return true
                }
                val edits = rows.mapNotNull { row ->
                    val (a, b) = rowRange(row) ?: return@mapNotNull null
                    EditorState.CaretEdit(editor.byteOffsetOf(row, a), editor.byteOffsetOf(row, b), "", head = 0, isPrimary = row == start.row)
                }
                mode = VimMode.Normal
                lastVisual = Triple(VimMode.VisualBlock, visualAnchor, visualCursor)
                if (edits.isNotEmpty()) editor.applyCaretEdits(edits)
                changed = true
                if (op == "c") {
                    mode = VimMode.Insert
                    insertStart = Pos(start.row, left)
                    insertCount = 1
                    editor.onCursorChangedExternally?.invoke()
                    pendingInsertKeys = keys
                    insertVisualExtent = extent
                } else {
                    editor.dropExtraCarets()
                    setCursor(Pos(start.row, left))
                    recordVisualChange(keys, extent)
                }
                return true
            }
            ">", "<", "=", "gc" -> {
                mode = VimMode.VisualLine
                return runVisualOperator(op, keys)
            }
            "gu", "gU", "g~" -> {
                val kind = when (op) {
                    "gu" -> Operators.CaseKind.Lower
                    "gU" -> Operators.CaseKind.Upper
                    else -> Operators.CaseKind.Toggle
                }
                val edits = rows.mapNotNull { row ->
                    val (a, b) = rowRange(row) ?: return@mapNotNull null
                    val text = editor.line(row).substring(a, b)
                    val changedText = when (kind) {
                        Operators.CaseKind.Lower -> text.lowercase()
                        Operators.CaseKind.Upper -> text.uppercase()
                        Operators.CaseKind.Toggle -> buildString { for (c in text) append(if (c.isUpperCase()) c.lowercaseChar() else c.uppercaseChar()) }
                    }
                    EditorState.CaretEdit(editor.byteOffsetOf(row, a), editor.byteOffsetOf(row, b), changedText, head = 0, isPrimary = row == start.row)
                }
                leaveVisual()
                if (edits.isNotEmpty()) editor.applyCaretEdits(edits)
                editor.dropExtraCarets()
                setCursor(Pos(start.row, left))
                changed = true
                recordVisualChange(keys, extent)
                return true
            }
            else -> return cancelPending()
        }
    }

    /**
     * `I` / `A` in visual block: a caret at the block's left or right edge on
     * every row, then insert mode — the multi-caret insert this editor
     * already has, which is exactly how Zed implements it. `g I` / `g A`
     * ([everyRow]) do the same on the first non-blank / the end of every
     * selected row (Zed's `VisualInsertFirstNonWhiteSpace` /
     * `VisualInsertEndOfLine`); a plain `I` / `A` in characterwise visual
     * mode is one caret at the selection's start or end.
     */
    private fun visualInsert(atEnd: Boolean, everyRow: Boolean): Boolean {
        val (start, end) = visualBounds()
        val block = mode == VimMode.VisualBlock
        val perRow = block || everyRow || mode == VimMode.VisualLine
        val left = minOf(visualAnchor.col, visualCursor.col)
        val right = if (goalCol == GOAL_END_OF_LINE) Int.MAX_VALUE else maxOf(visualAnchor.col, visualCursor.col)
        val carets = ArrayList<Caret>()
        if (!perRow) {
            carets.add(if (atEnd) Caret(end.row, (end.col + 1).coerceAtMost(editor.lineLength(end.row))) else Caret(start.row, start.col))
        }
        for (row in start.row..end.row) {
            if (!perRow) break
            val len = editor.lineLength(row)
            val col = when {
                !block && everyRow -> if (atEnd) len else editor.firstNonBlank(row)
                !block -> if (atEnd) len else 0
                atEnd -> if (right == Int.MAX_VALUE) len else (right + 1).coerceAtMost(len)
                else -> {
                    if (len < left) continue
                    left
                }
            }
            carets.add(Caret(row, col))
        }
        if (carets.isEmpty()) return leaveVisual().let { true }
        lastVisual = Triple(mode, visualAnchor, visualCursor)
        mode = VimMode.Insert
        val primary = carets.first()
        editor.setCarets(carets, primary)
        lastSetCaret = editor.primaryCaret()
        insertStart = Pos(primary.headRow, primary.headCol)
        insertCount = 1
        editor.onCursorChangedExternally?.invoke()
        return true
    }

    /** `p` in visual mode: the selection is replaced by the register (`:help v_p`). */
    private fun visualPut(preserve: Boolean): Boolean {
        val value = globals.registers.read(register, host) ?: return leaveVisual().let { true }
        val (start, end) = visualBounds()
        val linewise = mode == VimMode.VisualLine
        val range = if (linewise) OpRange(Pos(start.row, 0), Pos(end.row, 0), true) else OpRange(start, Pos(end.row, (end.col + 1).coerceAtMost(editor.lineLength(end.row))), false)
        val replaced = Operators.textOf(editor, range)
        leaveVisual()
        val body = if (value.linewise) value.text.removeSuffix("\n") else value.text
        val text = when {
            linewise && !value.linewise -> body
            !linewise && value.linewise -> "\n$body\n"
            else -> body
        }
        Operators.replace(editor, range, text)
        if (!preserve) globals.registers.write(register, replaced, isYank = false, host)
        register = null
        changed = true
        setCursor(if (value.linewise && !linewise) Pos(start.row + 1, editor.firstNonBlank(start.row + 1)) else Pos(start.row, editor.firstNonBlank(start.row).let { if (linewise) it else start.col }))
        return true
    }

    // ---- Search ------------------------------------------------------------------------------

    /** `*` / `#`: the word under the cursor, whole-word and case-sensitive as in Vim. */
    private fun searchWordUnderCursor(backwards: Boolean, wholeWord: Boolean): Boolean {
        val here = cursor()
        val obj = TextObjects.word(editor, here, around = false, big = false) ?: return true
        val text = editor.line(here.row)
        // Vim takes the keyword under or after the cursor, not a run of punctuation.
        var start = obj.start.col
        var end = obj.end.col
        if (!text[start].isLetterOrDigit() && text[start] != '_') {
            val next = text.indexOfFirst { it.isLetterOrDigit() || it == '_' }.takeIf { it > start } ?: return true
            val word = TextObjects.word(editor, Pos(here.row, next), around = false, big = false) ?: return true
            start = word.start.col
            end = word.end.col
        }
        val word = text.substring(start, end)
        val search = VimGlobals.Search(word, backwards, wholeWord, caseSensitive = true)
        globals.lastSearch = search
        val n = takeCount()
        val target = searchFrom(here, search, backwards, n) ?: return true
        if (operator != null) {
            val op = operator!!
            operator = null
            return runOperator(op, motionRange(here, MotionTarget(target, MotionKind.Exclusive)))
        }
        applyMotion(MotionTarget(target, MotionKind.Exclusive, jump = true))
        return true
    }

    /**
     * The [n]th match of [search] after (or before) [from], wrapping at the
     * ends with Vim's message, and every match highlighted as the find bar
     * would — `/` is the same search seen through a different key.
     */
    private fun searchFrom(from: Pos, search: VimGlobals.Search, backwards: Boolean, n: Int): Pos? {
        val matches = host.search(search.pattern, globals.useRegexSearch, search.caseSensitive, search.wholeWord)
        if (matches == null) {
            message = "E486: Pattern not found: ${search.pattern}"
            return null
        }
        val starts = matches.map { Pos(it.startRow, it.startCol) }
        if (starts.isEmpty()) {
            message = "E486: Pattern not found: ${search.pattern}"
            editor.clearSearchMatches()
            return null
        }
        var index = if (backwards) {
            starts.indexOfLast { it < from }.let { if (it < 0) starts.lastIndex.also { message = "search hit TOP, continuing at BOTTOM" } else it }
        } else {
            starts.indexOfFirst { it > from }.let { if (it < 0) 0.also { message = "search hit BOTTOM, continuing at TOP" } else it }
        }
        val step = if (backwards) -1 else 1
        repeat(n - 1) { index = ((index + step) % starts.size + starts.size) % starts.size }
        editor.showSearchMatches(matches, index)
        return starts[index]
    }

    // ---- The command line ------------------------------------------------------------------------

    private fun handleCommandLineKey(line: CommandLine, key: String): Boolean {
        when (key) {
            "escape", "ctrl-[", "ctrl-c" -> {
                commandLine = null
                searchOperator = null
                return true
            }
            "backspace" -> {
                commandLine = if (line.text.isEmpty()) null else line.copy(text = line.text.dropLast(1))
                if (commandLine == null) searchOperator = null
                return true
            }
            "ctrl-u" -> {
                commandLine = line.copy(text = "")
                return true
            }
            "enter" -> {
                commandLine = null
                if (line.prefix == ':') {
                    if (!replaying) recordKeys.clear()
                    ExCommands.run(this, line.text)
                } else {
                    runSearchLine(line)
                }
                return true
            }
            "tab" -> {
                commandLine = line.copy(text = line.text + "\t")
                return true
            }
            "ctrl-r" -> return true
        }
        if (key.length == 1 || key.codePointCount(0, key.length) == 1) {
            commandLine = line.copy(text = line.text + key)
            return true
        }
        return false
    }

    private fun runSearchLine(line: CommandLine) {
        val backwards = line.prefix == '?'
        val pattern = line.text.ifEmpty { globals.lastSearch?.pattern ?: return }
        val search = VimGlobals.Search(pattern, backwards, wholeWord = false, caseSensitive = pattern.any { it.isUpperCase() })
        globals.lastSearch = search
        val here = cursor()
        val target = searchFrom(here, search, backwards, 1)
        val op = searchOperator
        searchOperator = null
        if (target == null) return
        if (op != null) {
            runOperator(op, motionRange(here, MotionTarget(target, MotionKind.Exclusive)))
            finishRecording()
            return
        }
        applyMotion(MotionTarget(target, MotionKind.Exclusive, jump = true))
    }

    // ---- Dot repeat -------------------------------------------------------------------------------

    private fun repeatLastChange() {
        val record = globals.lastChange ?: return
        val override = if (hasCount()) takeCount() else null
        replaying = true
        try {
            val visual = record.visual
            if (visual != null) {
                val here = cursorFromEditor()
                mode = VimMode.Normal
                enterVisual(visual.mode, here)
                visualCursor = editor.clampToRow(Pos(here.row + visual.rows, if (visual.rows == 0) here.col + visual.cols else visual.cols))
                syncVisualSelection()
            }
            var keys = record.keys
            if (override != null) {
                keys = keys.dropWhile { it.length == 1 && it[0].isDigit() }
                keys = override.toString().map { it.toString() } + keys
            }
            replayInsertText = record.insertedText
            for (key in keys) handleKey(key)
            if (mode == VimMode.Insert || mode == VimMode.Replace) {
                val text = replayInsertText
                replayInsertText = null
                if (text != null && text.isNotEmpty()) Operators.insert(editor, Pos(editor.cursorRow, editor.cursorCol), text)
                leaveInsert()
            }
            replayInsertText = null
        } finally {
            replaying = false
            changed = false
        }
    }

    // ---- Ex support ----------------------------------------------------------------------------------

    internal fun say(text: String) {
        message = text
    }

    internal fun mark(name: Char): Pos? = marks[name]

    internal fun currentRow(): Int = cursor().row

    internal fun goToRow(row: Int) {
        val at = row.coerceIn(0, editor.lastRow)
        if (mode.isVisual) leaveVisual()
        marks['\''] = cursor()
        setCursor(Pos(at, editor.firstNonBlank(at)))
    }

    internal fun exHost(): VimHost = host
    internal fun exGlobals(): VimGlobals = globals
    internal fun exRegister(): Char? = register.also { register = null }
    /** An ex command edited the buffer. `.` does not repeat those (`&` would), so nothing is recorded. */
    internal fun noteChanged() {
        changed = false
    }

    internal fun exDelete(first: Int, last: Int) {
        val range = OpRange(Pos(first, 0), Pos(last, 0), linewise = true)
        yankRange(range, isYank = false)
        setCursor(Operators.delete(editor, range))
        noteChanged()
    }

    internal fun exYank(first: Int, last: Int) {
        yankRange(OpRange(Pos(first, 0), Pos(last, 0), linewise = true), isYank = true)
    }

    internal fun exJoin(first: Int, last: Int) {
        setCursor(Pos(first, 0))
        Operators.join(editor, first, (last - first + 1).coerceAtLeast(2))
        noteChanged()
    }

    internal fun exShift(first: Int, last: Int, indent: Boolean) {
        Operators.shift(editor, first, last, 1, indent)
        setCursor(Pos(last, editor.firstNonBlank(last)))
        noteChanged()
    }

    private fun markPosition(key: String): Pos? = when (key) {
        "'", "`" -> marks['\'']
        "<", ">" -> marks[key[0]]
        else -> if (key.length == 1) marks[key[0]] else null
    }

    private companion object {
        val WAITING_PREFIXES = setOf("f", "F", "t", "T", "r", "m", "'", "`", "\"")
        val NUMBER = Regex("-?\\d+")
    }
}
