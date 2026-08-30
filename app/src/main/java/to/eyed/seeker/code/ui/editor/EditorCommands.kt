package to.eyed.seeker.code.ui.editor

/**
 * The editing commands that go beyond moving one cursor around: multiple
 * cursors, the line operations, comment toggling, auto-closing pairs and
 * auto-indent.
 *
 * They live outside [EditorState] because they are *commands* — each one
 * reads the caret set, works out one edit per caret, and hands the batch to
 * [EditorState.applyCaretEdits], which owns the ordering rules that make a
 * multi-caret edit safe. Keeping them here leaves EditorState as what it has
 * always been: the pane's view state plus the primitives.
 *
 * Every one of them is multi-caret by construction. Where Zed's own
 * behaviour is not obvious the comment says which of its actions was
 * followed; the bindings are in `EditorPane.handleEditorKey` and the
 * user-facing list is `docs/SHORTCUTS.md`.
 */

/** Rows read per bridge call while searching for occurrences. */
private const val SEARCH_CHUNK_ROWS = 256

/** Ceiling on Ctrl+Shift+L, so one press on a common word can't hang a frame. */
internal const val SELECT_ALL_MATCHES_LIMIT = 1024

// ---- Multiple cursors ----------------------------------------------------

/**
 * Add a caret one row above or below the outermost one, or — if the last
 * press went the other way — take the outermost one back off again, which is
 * how Zed's AddSelectionAbove/Below pair behaves when you overshoot.
 *
 * The column is a goal rather than a position: a short line in the middle of
 * the run clamps its caret without dragging the rest of the column in with
 * it, the same as Zed's columnar selections.
 */
internal fun EditorState.addCaretVertically(delta: Int) {
    val direction = if (delta < 0) -1 else 1
    val carets = caretsInOrder()
    val grew = addCaretDirection
    if (grew != 0 && grew != direction && carets.size > 1) {
        val goal = addCaretGoalCol
        val kept = if (grew < 0) carets.drop(1) else carets.dropLast(1)
        val edge = if (grew < 0) kept.first() else kept.last()
        setCarets(kept, edge)
        // The run is still growing the way it was; it has just been asked to
        // give a row back.
        addCaretDirection = grew
        addCaretGoalCol = goal
        return
    }
    val edge = if (direction < 0) carets.first() else carets.last()
    var row = edge.headRow + direction
    if (row !in 0 until lineCount) return
    // The neighbouring *visible* row: growing the column over a fold steps
    // past it, the same as the arrows do.
    row = if (direction < 0) displayMap.prevVisibleRow(row) else displayMap.nextVisibleRow(row)
    if (row !in 0 until lineCount) return
    val goal = if (addCaretDirection == 0) edge.headCol else addCaretGoalCol
    val added = Caret(row, goal.coerceAtMost(line(row).length))
    setCarets(carets + added, added)
    addCaretDirection = direction
    addCaretGoalCol = goal
}

/**
 * Zed's `editor::SelectNext`: with bare carets, select the word each one
 * sits in; with something already selected, add a caret over the next
 * occurrence of it, wrapping at the end of the buffer.
 *
 * A selection that spans rows is left alone. Zed searches across newlines;
 * doing that here would mean stitching the search across the chunks the
 * bridge hands back for the sake of a case nobody reaches with Ctrl+D.
 */
internal fun EditorState.selectNextOccurrence(): Boolean {
    val carets = caretsInOrder()
    val primary = primaryCaret()
    if (carets.all { it.isEmpty }) {
        var newPrimary: Caret? = null
        val words = carets.map { caret ->
            val text = line(caret.headRow)
            val (start, end) = wordAround(text, caret.headCol)
            Caret(caret.headRow, start, caret.headRow, end)
                .also { if (caret == primary) newPrimary = it }
        }
        if (words.all { it.isEmpty }) return false
        setCarets(words, newPrimary ?: words.last())
        // The query came from a word, so from here on only whole words match.
        selectNextWordwise = true
        return true
    }
    val newest = carets.last { !it.isEmpty }
    if (newest.startRow != newest.endRow) return false
    val query = textIn(newest)
    if (query.isEmpty()) return false
    val match = nextOccurrence(query, newest.endRow, newest.endCol, selectNextWordwise, carets)
        ?: return false
    val wordwise = selectNextWordwise
    setCarets(carets + match, match)
    selectNextWordwise = wordwise
    return true
}

/** Zed's `editor::SelectAllMatches`: a caret on every occurrence at once. */
internal fun EditorState.selectAllOccurrences(): Boolean {
    val carets = caretsInOrder()
    val seed = carets.lastOrNull { !it.isEmpty }
        ?: run {
            if (!selectNextOccurrence()) return false
            caretsInOrder().lastOrNull { !it.isEmpty } ?: return false
        }
    if (seed.startRow != seed.endRow) return false
    val query = textIn(seed)
    if (query.isEmpty()) return false
    val matches = ArrayList<Caret>()
    forEachOccurrence(query, selectNextWordwise, 0, revisitFirstRow = false) { row, col, _ ->
        matches.add(Caret(row, col, row, col + query.length))
        matches.size < SELECT_ALL_MATCHES_LIMIT
    }
    if (matches.isEmpty()) return false
    val nearest = matches.firstOrNull { it.startRow >= seed.startRow } ?: matches.last()
    setCarets(matches, nearest)
    return true
}

/** The first occurrence at or after (row, col) that no caret already holds. */
private fun EditorState.nextOccurrence(
    query: String,
    fromRow: Int,
    fromCol: Int,
    wordwise: Boolean,
    taken: List<Caret>,
): Caret? {
    var found: Caret? = null
    forEachOccurrence(query, wordwise, fromRow, revisitFirstRow = true) { row, col, wrapped ->
        // The starting row is walked twice — once for what follows the
        // caret, and again at the end of the wrap for what precedes it.
        if (!wrapped && row == fromRow && col < fromCol) return@forEachOccurrence true
        val candidate = Caret(row, col, row, col + query.length)
        if (taken.any { it.startRow == row && it.startCol < candidate.endCol && col < it.endCol }) {
            return@forEachOccurrence true
        }
        found = candidate
        false
    }
    return found
}

/**
 * Walk the occurrences of [query] from [fromRow] forward and round the end
 * of the buffer, stopping when [action] answers false. Its third argument is
 * true once the walk has wrapped.
 *
 * [revisitFirstRow] makes the walk end on [fromRow] a second time, which is
 * what "the next occurrence after the cursor" needs — the matches earlier on
 * the starting row come last. A walk that wants every match once, and
 * starts at row 0, must not: it would count that row's matches twice.
 *
 * Reads the buffer in chunks straight from the bridge rather than through
 * the pane's line window, so searching never evicts the lines being drawn.
 * The right home for this is a search API on the engine — Zed runs an
 * Aho-Corasick automaton over the rope — but that is a bridge call we don't
 * have, and Ctrl+D is a keypress somebody made, not a keystroke on the
 * typing path.
 */
internal fun EditorState.forEachOccurrence(
    query: String,
    wordwise: Boolean,
    fromRow: Int,
    revisitFirstRow: Boolean,
    action: (row: Int, col: Int, wrapped: Boolean) -> Boolean,
) {
    var row = fromRow.coerceIn(0, lineCount - 1)
    val rowsToVisit = if (revisitFirstRow) lineCount + 1 else lineCount
    var visited = 0
    var wrapped = false
    while (visited < rowsToVisit) {
        val end = (row + SEARCH_CHUNK_ROWS).coerceAtMost(lineCount)
        val chunk = linesOf(row, end).split('\n')
        for ((index, text) in chunk.withIndex()) {
            if (visited >= rowsToVisit) return
            val at = row + index
            var found = text.indexOf(query)
            while (found >= 0) {
                if (!wordwise || isWholeWord(text, found, found + query.length)) {
                    if (!action(at, found, wrapped)) return
                }
                found = text.indexOf(query, found + 1)
            }
            visited++
        }
        if (end >= lineCount) {
            row = 0
            wrapped = true
        } else {
            row = end
        }
    }
}

private fun isWholeWord(text: String, start: Int, end: Int): Boolean {
    fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'
    if (start > 0 && isWordChar(text[start - 1])) return false
    if (end < text.length && isWordChar(text[end])) return false
    return true
}

// ---- Folding ---------------------------------------------------------------
//
// Zed's indent-based code folding, exactly the actions its Linux keymap binds:
// editor::Fold (ctrl-shift-[), editor::UnfoldLines (ctrl-shift-]),
// editor::FoldAll (ctrl-k ctrl-0) and editor::UnfoldAll (ctrl-k ctrl-j)
// (assets/keymaps/default-linux.json:575-576,589-590). The range arithmetic is
// [IndentFolds]; the fold set itself lives on [EditorState] with the rest of
// the pane's view state.

/**
 * Buffer rows served in aligned chunks, so a fold scan — which walks rows the
 * pane's line window has never held — costs a bridge call per couple of
 * hundred rows instead of one per row. Chunks are *aligned* because the fold
 * commands walk both ways: [EditorState.foldAtCarets] climbs towards row 0
 * while every range it tries scans forward, and unaligned chunks would
 * re-fetch on every step of that. Two slots, for exactly that pincer.
 */
private class ChunkedRows(private val state: EditorState) {
    private val firsts = intArrayOf(-1, -1)
    private val chunks = arrayOf<List<String>>(emptyList(), emptyList())
    private var next = 0

    fun line(row: Int): String {
        val base = (row / SEARCH_CHUNK_ROWS) * SEARCH_CHUNK_ROWS
        for (i in firsts.indices) {
            if (firsts[i] == base) return chunks[i].getOrElse(row - base) { "" }
        }
        val last = (base + SEARCH_CHUNK_ROWS).coerceAtMost(state.lineCount)
        val slot = next
        next = (next + 1) % firsts.size
        firsts[slot] = base
        chunks[slot] = state.linesOf(base, last).split('\n')
        return chunks[slot].getOrElse(row - base) { "" }
    }
}

/**
 * Whether [content] — a line with its indent already stripped — opens with
 * one of the language's closing brackets: Zed's `closing_bracket_indent_len`
 * test (display_map.rs:2294-2314), which decides that the blank rows in
 * front of a `}` belong inside the fold.
 */
private fun EditorState.closesBlock(content: String): Boolean =
    languageConfig.brackets.any { pair -> content.startsWith(pair.close) }

/**
 * The fold hanging off [row], or null — the gutter chevron's click target.
 * From what the tree or the server knows where they know the language
 * ([EditorState.knownFolds]); from the indentation otherwise.
 */
internal fun EditorState.foldableRangeAt(row: Int): FoldRange? {
    knownFolds?.let { return foldStartingAt(it, row) }
    val reader = ChunkedRows(this)
    return IndentFolds.rangeAt(lineCount, row, reader::line) { content -> closesBlock(content) }
}

/**
 * Whether [row] would show a fold chevron — [IndentFolds.startsIndent] with
 * the scan bounded, because the gutter asks per visible row per frame and the
 * rows it asks about sit in the pane's line window. A row whose deeper block
 * only appears after sixty-odd blank lines misses its chevron until the
 * chord or a click finds the fold anyway; that trade is recorded in the
 * feature's deviations.
 */
internal fun EditorState.rowIsFoldable(row: Int): Boolean {
    knownFolds?.let { return foldStartingAt(it, row) != null }
    return IndentFolds.startsIndent(lineCount, row, { r -> line(r) }, scanLimit = 64)
}

/**
 * Zed's `editor::Fold` (crates/editor/src/fold.rs:167-206): a selection that
 * spans rows folds every foldable block inside it; a caret folds the
 * innermost block that contains it, found exactly as Zed finds it — walk up
 * from the caret's row and take the first fold that reaches back down to it.
 */
internal fun EditorState.foldAtCarets() {
    val known = knownFolds
    if (known != null) {
        // The tree's folds are a list, so the walk Zed does over its creases
        // is two lookups — see `SyntaxFolds.kt`.
        val toFold = ArrayList<FoldRange>()
        for (caret in caretsInOrder()) {
            if (caret.startRow != caret.endRow) {
                val inside = foldsWithin(known, caret.startRow..caret.endRow)
                if (inside.isNotEmpty()) {
                    toFold.addAll(inside)
                    continue
                }
            }
            foldContaining(known, caret.startRow)?.let(toFold::add)
        }
        foldRanges(toFold)
        return
    }
    val reader = ChunkedRows(this)
    fun rangeAt(row: Int): FoldRange? =
        IndentFolds.rangeAt(lineCount, row, reader::line) { content -> closesBlock(content) }

    val toFold = ArrayList<FoldRange>()
    for (caret in caretsInOrder()) {
        if (caret.startRow != caret.endRow) {
            var row = caret.startRow
            var found = false
            while (row <= caret.endRow) {
                val range = rangeAt(row)
                if (range != null) {
                    toFold.add(range)
                    row = range.endRow + 1
                    found = true
                } else {
                    row++
                }
            }
            if (found) continue
        }
        for (row in caret.startRow downTo 0) {
            val range = rangeAt(row)
            if (range != null && range.endRow >= caret.startRow) {
                toFold.add(range)
                break
            }
            // Stop at the first top-level row instead of walking to row 0.
            // A block that reaches down to the caret has to start on a row
            // indented less than the rows inside it, and nothing is indented
            // less than a non-blank row at column zero — so once one of those
            // has been tried and did not enclose the caret, nothing above it
            // can. Blank rows are no evidence either way (their indent is the
            // whole line) and are walked through. Zed's own walk has no such
            // stop (fold.rs:195-204) because its creases come from a snapshot
            // it already holds; ours reads the file to find them, and without
            // this a press with the caret below the last block of a 20k-line
            // file read every row above it.
            val text = reader.line(row)
            if (!IndentFolds.isBlank(text) && IndentFolds.indentOf(text) == 0) break
        }
    }
    foldRanges(toFold)
}

/**
 * Zed's `editor::UnfoldLines` (fold.rs:443-460): open every fold the carets'
 * rows touch — and a caret on a chip row touches that chip's fold, which is
 * how the same chord that folded a block opens it again.
 */
internal fun EditorState.unfoldAtCarets(): Boolean {
    var changed = false
    for (caret in caretsInOrder()) {
        if (unfoldRowsTouching(caret.startRow..caret.endRow)) changed = true
    }
    return changed
}

/**
 * Zed's `editor::FoldAll` (fold.rs:330-344): every foldable row in the file,
 * nested blocks included — Zed pushes a crease per row without skipping the
 * rows an earlier crease swallowed, which is what leaves the inner blocks
 * folded when an outer one is opened by hand.
 */
internal fun EditorState.foldAllRows() {
    knownFolds?.let { known ->
        foldRanges(known)
        return
    }
    val reader = ChunkedRows(this)
    val ranges = ArrayList<FoldRange>()
    for (row in 0 until lineCount - 1) {
        IndentFolds.rangeAt(lineCount, row, reader::line) { content -> closesBlock(content) }
            ?.let(ranges::add)
    }
    foldRanges(ranges)
}

/**
 * The gutter chevron's click — Zed's toggle in `render_crease_toggle`
 * (fold.rs:60-68): a folded row unfolds (`unfold_at`, fold.rs:498-518), a
 * foldable one folds (`fold_at`, fold.rs:424-441).
 */
internal fun EditorState.toggleFoldAt(row: Int): Boolean {
    if (foldStartingAt(row) != null) return unfoldRowsTouching(row..row)
    val range = foldableRangeAt(row) ?: return false
    foldRanges(listOf(range))
    return true
}

// ---- Line operations -----------------------------------------------------

/**
 * The contiguous row ranges the carets cover, with the carets that produced
 * each. A caret whose selection stops at column 0 doesn't claim that row,
 * and ranges that touch are merged — both of them Zed's rules, from
 * `consume_contiguous_rows`.
 */
private fun EditorState.rowGroups(): List<Pair<IntRange, MutableList<Caret>>> {
    val groups = ArrayList<Pair<IntRange, MutableList<Caret>>>()
    for (caret in caretsInOrder()) {
        val end = if (caret.endRow > caret.startRow && caret.endCol == 0) {
            caret.endRow - 1
        } else {
            caret.endRow
        }
        val last = groups.lastOrNull()
        if (last != null && caret.startRow <= last.first.last + 1) {
            groups[groups.size - 1] =
                (last.first.first..maxOf(last.first.last, end)) to last.second
            last.second.add(caret)
        } else {
            groups.add((caret.startRow..end) to mutableListOf(caret))
        }
    }
    return groups
}

/** Byte offset just past the text of [row], before its newline. */
private fun EditorState.lineEndOffset(row: Int): Long =
    lineStartOffset(row) + utf8Length(line(row))

private fun EditorState.linesOf(rows: IntRange): String = linesOf(rows.first, rows.last + 1)

/**
 * Zed's `MoveLineUp` / `MoveLineDown`: swap each group of rows with the row
 * on the far side of it. Groups that would run off the ends of the buffer
 * stay where they are, and their carets with them.
 */
internal fun EditorState.moveLines(delta: Int) {
    val edits = ArrayList<EditorState.CaretEdit>()
    val carets = ArrayList<Caret>()
    val primary = primaryCaret()
    var newPrimary: Caret? = null
    for ((rows, group) in rowGroups()) {
        val blocked = if (delta < 0) rows.first == 0 else rows.last >= lineCount - 1
        for (caret in group) {
            val moved = if (blocked) {
                caret
            } else {
                Caret(
                    caret.anchorRow + delta,
                    caret.anchorCol,
                    caret.headRow + delta,
                    caret.headCol,
                )
            }
            carets.add(moved)
            if (caret == primary) newPrimary = moved
        }
        if (blocked) continue
        // Zed unfolds what it is about to move (editor.rs:7417-7499,
        // `unfold_ranges.push(range_to_move)`); its anchors would carry the
        // neighbour's fold across the swap, ours cannot, so the neighbour
        // row on either side is unfolded too. Without this a
        // row-count-preserving swap would slide text out from under a
        // fold's row numbers.
        unfoldRowsTouching((rows.first - 1).coerceAtLeast(0)..rows.last + 1)
        val block = linesOf(rows)
        if (delta < 0) {
            val neighbour = line(rows.first - 1)
            edits.add(
                EditorState.CaretEdit(
                    start = lineStartOffset(rows.first - 1),
                    end = lineEndOffset(rows.last),
                    replacement = "$block\n$neighbour",
                )
            )
        } else {
            val neighbour = line(rows.last + 1)
            edits.add(
                EditorState.CaretEdit(
                    start = lineStartOffset(rows.first),
                    end = lineEndOffset(rows.last + 1),
                    replacement = "$neighbour\n$block",
                )
            )
        }
    }
    if (edits.isEmpty()) return
    applyEdits(edits, carets, newPrimary ?: carets.last())
}

/**
 * Zed's `DuplicateLineUp` / `DuplicateLineDown`. Either way the carets stay
 * on the row they were on, which for the upward copy means they end up on
 * the duplicate — what Zed's own selection fix-up arrives at.
 */
internal fun EditorState.duplicateLines(above: Boolean) {
    val edits = ArrayList<EditorState.CaretEdit>()
    val carets = ArrayList<Caret>()
    val primary = primaryCaret()
    var newPrimary: Caret? = null
    var shift = 0
    for ((rows, group) in rowGroups()) {
        for (caret in group) {
            val moved = Caret(
                caret.anchorRow + shift,
                caret.anchorCol,
                caret.headRow + shift,
                caret.headCol,
            )
            carets.add(moved)
            if (caret == primary) newPrimary = moved
        }
        val block = linesOf(rows)
        val at = if (above) lineStartOffset(rows.first) else lineEndOffset(rows.last)
        edits.add(
            EditorState.CaretEdit(
                start = at,
                end = at,
                replacement = if (above) "$block\n" else "\n$block",
            )
        )
        // Everything below this group has just been pushed down by the copy.
        shift += rows.last - rows.first + 1
    }
    if (edits.isEmpty()) return
    applyEdits(edits, carets, newPrimary ?: carets.last())
}

/**
 * Zed's `DeleteLine`: take out every row the carets touch, leaving one caret
 * per group on the row that closes the gap, at the column it had.
 */
internal fun EditorState.deleteLines() {
    val groups = rowGroups()
    val primary = primaryCaret()
    val edits = groups.map { (rows, group) ->
        val isPrimary = group.any { it == primary }
        val goal = group.first().headCol
        if (rows.last < lineCount - 1) {
            EditorState.CaretEdit(
                start = lineStartOffset(rows.first),
                end = lineStartOffset(rows.last + 1),
                replacement = "",
                head = 0,
                columnGoal = goal,
                isPrimary = isPrimary,
            )
        } else {
            // Nothing follows, so the newline that has to go is the one
            // *before* the range — which also puts the caret a row higher.
            EditorState.CaretEdit(
                start = (lineStartOffset(rows.first) - 1).coerceAtLeast(0),
                end = lineEndOffset(rows.last),
                replacement = "",
                head = 0,
                columnGoal = goal,
                isPrimary = isPrimary,
            )
        }
    }
    applyCaretEdits(edits)
}

/**
 * Zed's `JoinLines`: pull the following row onto this one, dropping its
 * indent and putting a single space at the seam unless one side is already
 * blank or already ends in whitespace. A bare caret joins its row with the
 * next; a selection joins everything it spans.
 */
internal fun EditorState.joinLines() {
    val primary = primaryCaret()
    val edits = rowGroups().map { (rows, group) ->
        // A group that covers a single row still means "and the next one",
        // or the command would do nothing for a bare caret.
        val last = if (rows.last == rows.first) rows.first + 1 else rows.last
        if (last > lineCount - 1) {
            // Nothing follows the last row of the buffer, so this group has
            // nothing to pull up. Its caret still travels with the batch:
            // the new caret set is built from these edits, and dropping the
            // edit would drop the caret with it.
            val at = lineEndOffset(rows.first)
            return@map EditorState.CaretEdit(
                start = at,
                end = at,
                replacement = "",
                head = 0,
                columnGoal = group.first().headCol,
                isPrimary = group.any { it == primary },
            )
        }
        val joined = StringBuilder()
        var tail = line(rows.first)
        var seam = 0
        for (row in rows.first + 1..last) {
            val text = line(row).trimStart(' ', '\t')
            val separator =
                if (text.isEmpty() || tail.isEmpty() || tail.last().isWhitespace()) "" else " "
            joined.append(separator)
            seam = utf8Length(joined.toString())
            joined.append(text)
            if (text.isNotEmpty()) tail = text
        }
        EditorState.CaretEdit(
            start = lineEndOffset(rows.first),
            end = lineEndOffset(last),
            replacement = joined.toString(),
            head = seam,
            isPrimary = group.any { it == primary },
        )
    }
    if (edits.isEmpty()) return
    applyCaretEdits(edits)
}

// ---- Syntax-aware selection and brackets ---------------------------------

/**
 * Zed's `editor::SelectLargerSyntaxNode` (selection.rs:670-780, bound to
 * `alt-shift-right` in default-linux.json:547): grow every selection to the
 * smallest syntax node that strictly contains it.
 *
 * The engine walks the tree ([EditorBuffer.syntaxNodeRange]); what lives here
 * is the *stack*, which is what makes shrinking retrace. Zed keeps the same
 * thing (`select_syntax_node_history`, editor.rs) for the same reason: the
 * tree can only say "wider", so "narrower" has to be remembered.
 *
 * A press that grows nothing — the selection already covers the file, or the
 * buffer has no grammar — leaves the selection and the stack alone, and the
 * chord goes unhandled.
 */
internal fun EditorState.selectLargerSyntaxNode(): Boolean {
    val before = caretsInOrder()
    val primary = primaryCaret()
    var newPrimary: Caret? = null
    var grewAny = false
    val after = before.map { caret ->
        val grown = buffer.syntaxNodeRange(
            caret.startRow,
            caret.startCol,
            caret.endRow,
            caret.endCol,
        )
        val next = if (grown == null) {
            caret
        } else {
            grewAny = true
            // The selection keeps which end is moving, as Zed keeps
            // `reversed`: a selection made backwards grows backwards.
            if (caret.anchorFirst || caret.isEmpty) {
                grown
            } else {
                Caret(grown.headRow, grown.headCol, grown.anchorRow, grown.anchorCol)
            }
        }
        if (caret == primary) newPrimary = next
        next
    }
    if (!grewAny) return false
    pushSyntaxSelection(before)
    setCarets(after, newPrimary ?: after.last())
    return true
}

/**
 * Zed's `editor::SelectSmallerSyntaxNode` (`alt-shift-left`,
 * default-linux.json:548): step back down the stack the growing built. With
 * nothing on the stack there is nothing to shrink to and the chord is
 * unhandled — Zed does nothing there either.
 */
internal fun EditorState.selectSmallerSyntaxNode(): Boolean {
    val previous = popSyntaxSelection() ?: return false
    setCarets(previous, previous.last())
    return true
}

/**
 * Zed's `editor::MoveToEnclosingBracket` (selection.rs:1051-1100, `ctrl-m`
 * in default-linux.json:573): jump to the other delimiter of the pair around
 * the caret — to the closer when the caret is inside the pair or on the
 * opener, and back to the opener when it is already on the closer.
 */
internal fun EditorState.moveToEnclosingBracket(): Boolean {
    val primary = primaryCaret()
    val (open, close) = matchingBrackets() ?: return false
    val head = primary.headRow to primary.headCol
    val openStart = open.anchorRow to open.anchorCol
    val openEnd = open.headRow to open.headCol
    val closeStart = close.anchorRow to close.anchorCol
    val closeEnd = close.headRow to close.headCol
    fun atLeast(a: Pair<Int, Int>, b: Pair<Int, Int>) =
        a.first > b.first || (a.first == b.first && a.second >= b.second)
    // Zed's own three cases (selection.rs:1089-1097): from inside the pair
    // the caret goes to the closer; from *on* the closer it goes back past
    // the opener; and from on the opener it goes to the closer's far side.
    val inside = atLeast(head, openEnd) && atLeast(closeStart, head)
    val onClose = atLeast(head, closeStart) && atLeast(closeEnd, head)
    val target = when {
        onClose -> if (inside) openEnd else openStart
        inside -> closeStart
        else -> closeEnd
    }
    val moved = Caret(target.first, target.second)
    setCarets(listOf(moved), moved)
    ensureCursorVisible()
    return true
}

/**
 * The bracket pair around the primary caret, as `(open, close)` one-row
 * [Caret] ranges — what the pane paints in the document-highlight colour, the
 * way Zed marks the matching pair. Null when the caret is inside no pair.
 */
internal fun EditorState.matchingBrackets(): Pair<Caret, Caret>? {
    val caret = primaryCaret()
    return buffer.enclosingBrackets(
        caret.startRow,
        caret.startCol,
        caret.endRow,
        caret.endCol,
    )
}

// ---- Whole-line manipulations --------------------------------------------

/**
 * Zed's `manipulate_immutable_lines` (editor.rs:6860-6930), which is what
 * every `SortLines*` / `ReverseLines` / `ShuffleLines` / `UniqueLines*`
 * command is built on: each contiguous run of caret rows is taken as a block
 * of lines, [transform] rewrites the block, and the selection comes back
 * covering the block it produced.
 *
 * A bare caret is a one-line block, which does nothing for a sort and drops
 * nothing from a unique — Zed behaves the same, and the alternative (guessing
 * a paragraph) is [rewrap]'s job, not this one.
 */
internal fun EditorState.manipulateLines(transform: (List<String>) -> List<String>): Boolean {
    val edits = ArrayList<EditorState.CaretEdit>()
    val primary = primaryCaret()
    for ((rows, group) in rowGroups()) {
        val before = (rows.first..rows.last).map { line(it) }
        val after = transform(before)
        if (after == before) continue
        val text = after.joinToString("\n")
        edits.add(
            EditorState.CaretEdit(
                start = lineStartOffset(rows.first),
                end = lineEndOffset(rows.last),
                replacement = text,
                // The block comes back selected, as Zed leaves it: the rows
                // moved, and a caret sitting on one of them would say nothing
                // about what happened.
                anchor = 0,
                head = utf8Length(text),
                isPrimary = group.any { it == primary },
            )
        )
    }
    if (edits.isEmpty()) return false
    applyCaretEdits(edits)
    return true
}

/**
 * Zed's `manipulate_text` (editor.rs:7314-7370), the text half of the same
 * idea: [transform] runs over each caret's selection, or — for a bare caret —
 * over the word it sits in, and the result comes back selected.
 */
internal fun EditorState.manipulateText(transform: (String) -> String): Boolean {
    val edits = ArrayList<EditorState.CaretEdit>()
    val primary = primaryCaret()
    for (caret in caretsInOrder()) {
        val range = if (caret.isEmpty) wordRangeAt(caret.headRow, caret.headCol) else caret
        if (range.startRow == range.endRow && range.startCol == range.endCol) continue
        val old = textIn(range)
        val new = transform(old)
        if (new == old) continue
        edits.add(
            EditorState.CaretEdit(
                start = byteOffsetOf(range.startRow, range.startCol),
                end = byteOffsetOf(range.endRow, range.endCol),
                replacement = new,
                anchor = 0,
                head = utf8Length(new),
                isPrimary = caret == primary,
            )
        )
    }
    if (edits.isEmpty()) return false
    applyCaretEdits(edits)
    return true
}

/**
 * The word around a position, as a one-row [Caret] — Zed's
 * `surrounding_word`, which is what a bare caret means to the `ConvertTo*`
 * commands. An empty caret comes back where the position is not in a word.
 */
private fun EditorState.wordRangeAt(row: Int, col: Int): Caret {
    val text = line(row)
    val at = col.coerceIn(0, text.length)
    fun isWord(index: Int): Boolean =
        index in text.indices && (text[index].isLetterOrDigit() || text[index] == '_')
    var start = at
    while (start > 0 && isWord(start - 1)) start--
    var end = at
    while (isWord(end)) end++
    return Caret(row, start, row, end)
}

/**
 * Zed's `editor::SelectLine` (default-linux.json:111): every row the carets
 * touch, selected whole, newline included where there is one below.
 */
internal fun EditorState.selectLines(): Boolean {
    val primary = primaryCaret()
    var newPrimary: Caret? = null
    val carets = rowGroups().map { (rows, group) ->
        val endRow = if (rows.last < lineCount - 1) rows.last + 1 else rows.last
        val endCol = if (rows.last < lineCount - 1) 0 else line(rows.last).length
        val selected = Caret(rows.first, 0, endRow, endCol)
        if (group.any { it == primary }) newPrimary = selected
        selected
    }
    if (carets.isEmpty()) return false
    setCarets(carets, newPrimary ?: carets.last())
    return true
}

/**
 * Zed's `editor::Transpose` (editor.rs:7772-7830): swap the character before
 * the caret with the one after it and step the caret past the pair. At the
 * end of a line the pair is the last two characters of that line, and at the
 * very start of the buffer there is nothing to swap.
 *
 * Bare carets only, as in Zed — a selection has no "the character before it"
 * to mean.
 */
internal fun EditorState.transpose(): Boolean {
    val edits = ArrayList<EditorState.CaretEdit>()
    val primary = primaryCaret()
    for (caret in caretsInOrder()) {
        if (!caret.isEmpty) continue
        val text = line(caret.headRow)
        // Zed steps back off the end of the line, so `ab|` swaps `a` and `b`.
        val at = if (caret.headCol >= text.length) text.length - 1 else caret.headCol
        if (at <= 0) continue
        val before = text.substring(at - 1, at)
        val after = text.substring(at, at + 1)
        edits.add(
            EditorState.CaretEdit(
                start = byteOffsetOf(caret.headRow, at - 1),
                end = byteOffsetOf(caret.headRow, at + 1),
                replacement = after + before,
                isPrimary = caret == primary,
            )
        )
    }
    if (edits.isEmpty()) return false
    applyCaretEdits(edits)
    return true
}

/**
 * Zed's `editor::Rewrap` (rewrap.rs): reflow the paragraph or comment block
 * the caret sits in to `preferred_line_length`, keeping the indent and the
 * comment marker on every line it produces.
 *
 * With a selection the rows are the selected ones. With a bare caret the
 * paragraph is found the way Zed finds it (rewrap.rs:126-148): outwards from
 * the caret's row while the neighbour is non-blank, carries the same prefix
 * and has something after it.
 */
internal fun EditorState.rewrap(preferredLineLength: Int): Boolean {
    val edits = ArrayList<EditorState.CaretEdit>()
    val primary = primaryCaret()
    val prefixes = languageConfig.lineComments
    for ((rows, group) in rowGroups()) {
        val prefix = LineTransforms.rewrapPrefix(line(rows.first), prefixes)
        var first = rows.first
        var last = rows.last
        if (rows.first == rows.last) {
            while (first > 0 && line(first - 1).startsWith(prefix) &&
                line(first - 1).length > prefix.length
            ) {
                first--
            }
            while (last < lineCount - 1 && line(last + 1).startsWith(prefix) &&
                line(last + 1).length > prefix.length
            ) {
                last++
            }
        }
        val before = (first..last).map { line(it) }
        if (before.all { it.isBlank() }) continue
        val after = LineTransforms.rewrap(before, prefix, preferredLineLength)
        if (after == before) continue
        val text = after.joinToString("\n")
        edits.add(
            EditorState.CaretEdit(
                start = lineStartOffset(first),
                end = lineEndOffset(last),
                replacement = text,
                head = utf8Length(text),
                isPrimary = group.any { it == primary },
            )
        )
    }
    if (edits.isEmpty()) return false
    applyCaretEdits(edits)
    return true
}

// ---- Comments ------------------------------------------------------------

/** How one row's columns move when its comment prefix goes in or comes out. */
private class ColumnShift(val at: Int, val removed: Int, val inserted: Int) {
    fun apply(col: Int): Int = when {
        col <= at -> col
        col <= at + removed -> at + inserted
        else -> col - removed + inserted
    }
}

/**
 * Zed's `editor::ToggleComments`. The tokens are the grammar's own, so this
 * writes `#` in Python, `//` in Rust and `<!--` … `-->` in Markdown, and
 * writes nothing at all in a language that has neither.
 *
 * Zed's order of preference, and ours: line comments where the language has
 * them, the block comment where it does not. Markdown and CSS are the two of
 * ours with only a block comment; a diff has neither, and toggling in one
 * does nothing rather than corrupting the patch.
 */
internal fun EditorState.toggleComment(): Boolean {
    val token = languageConfig.lineComment
        ?: return languageConfig.blockComment?.let { toggleBlockComment(it) } ?: false
    val prefix = token.trimEnd(' ')
    val padding = token.substring(prefix.length)

    val edits = ArrayList<EditorState.CaretEdit>()
    val shifts = HashMap<Int, ColumnShift>()
    for ((rows, _) in rowGroups()) {
        val affected = ArrayList<Int>()
        var allCommented = true
        for (row in rows) {
            val text = line(row)
            // A blank row inside a multi-row range is passed over rather
            // than counted against the "everything is commented" test.
            if (rows.first != rows.last && text.isBlank()) continue
            affected.add(row)
            if (!text.trimStart(' ', '\t').startsWith(prefix)) allCommented = false
        }
        if (affected.isEmpty()) continue

        if (allCommented) {
            for (row in affected) {
                val text = line(row)
                val indent = text.indexOfFirst { it != ' ' && it != '\t' }.coerceAtLeast(0)
                var end = indent + prefix.length
                // Take the padding with it only where it matches, so an
                // aligned `//␠` round-trips and a bare `//` stays bare.
                var i = 0
                while (i < padding.length && end < text.length && text[end] == padding[i]) {
                    end++
                    i++
                }
                edits.add(
                    EditorState.CaretEdit(
                        start = byteOffsetOf(row, indent),
                        end = byteOffsetOf(row, end),
                        replacement = "",
                    )
                )
                shifts[row] = ColumnShift(indent, end - indent, 0)
            }
        } else {
            val column = affected.minOf { row ->
                val text = line(row)
                text.indexOfFirst { it != ' ' && it != '\t' }.let {
                    if (it < 0) text.length else it
                }
            }
            for (row in affected) {
                val at = byteOffsetOf(row, column)
                edits.add(EditorState.CaretEdit(start = at, end = at, replacement = token))
                shifts[row] = ColumnShift(column, 0, token.length)
            }
        }
    }
    if (edits.isEmpty()) return true

    // The carets keep their rows; only their columns move, and only by the
    // width of what went in or came out of the row they sit on.
    val primary = primaryCaret()
    var newPrimary: Caret? = null
    val carets = caretsInOrder().map { caret ->
        val moved = Caret(
            caret.anchorRow,
            shifts[caret.anchorRow]?.apply(caret.anchorCol) ?: caret.anchorCol,
            caret.headRow,
            shifts[caret.headRow]?.apply(caret.headCol) ?: caret.headCol,
        )
        if (caret == primary) newPrimary = moved
        moved
    }
    applyEdits(edits, carets, newPrimary ?: carets.last())
    return true
}

/**
 * The block-comment half, for the languages that have no line comment:
 * Markdown's `<!--` … `-->` and CSS's C-style pair. The delimiters wrap the
 * range the carets cover — the opener at the shallowest indent of the first
 * row, the closer past the last row's content — and come off again when they
 * are already there.
 *
 * The space either side goes in with them and comes off tolerantly, so
 * `<!-- note -->` round-trips and a hand-written `<!--note-->` still
 * uncomments.
 */
private fun EditorState.toggleBlockComment(comment: BlockComment): Boolean {
    val edits = ArrayList<EditorState.CaretEdit>()
    val shifts = HashMap<Int, MutableList<ColumnShift>>()
    fun note(row: Int, shift: ColumnShift) {
        shifts.getOrPut(row) { mutableListOf() }.add(shift)
    }

    for ((rows, _) in rowGroups()) {
        // A blank row inside a multi-row range is passed over, as it is for
        // line comments; a range that is only a blank row still comments.
        val affected = rows.filter { rows.first == rows.last || line(it).isNotBlank() }
        if (affected.isEmpty()) continue
        val firstRow = affected.first()
        val lastRow = affected.last()
        val head = line(firstRow)
        val tail = line(lastRow)
        val open = head.indexOfFirst { it != ' ' && it != '\t' }
            .let { if (it < 0) head.length else it }
        val close = tail.trimEnd().length
        // On one row the two delimiters have to fit side by side before the
        // text can be said to be commented at all.
        val roomForBoth = firstRow != lastRow ||
            close - open >= comment.start.length + comment.end.length
        val commented = roomForBoth &&
            head.startsWith(comment.start, open) &&
            tail.trimEnd().endsWith(comment.end)

        if (commented) {
            var from = open + comment.start.length
            if (head.getOrNull(from) == ' ') from++
            var to = close - comment.end.length
            if (tail.getOrNull(to - 1) == ' ' && (firstRow != lastRow || to - 1 >= from)) to--
            edits.add(
                EditorState.CaretEdit(
                    start = byteOffsetOf(firstRow, open),
                    end = byteOffsetOf(firstRow, from),
                    replacement = "",
                )
            )
            edits.add(
                EditorState.CaretEdit(
                    start = byteOffsetOf(lastRow, to),
                    end = byteOffsetOf(lastRow, close),
                    replacement = "",
                )
            )
            note(firstRow, ColumnShift(open, from - open, 0))
            note(lastRow, ColumnShift(to, close - to, 0))
        } else if (open == close) {
            // Nothing to wrap: an empty pair with a space either side, and
            // the caret between them ready to type into — so that what is
            // typed there uncomments again cleanly. The shift is counted from
            // one column earlier than the insertion because a caret standing
            // exactly at an insertion point does not move.
            val at = byteOffsetOf(firstRow, open)
            edits.add(
                EditorState.CaretEdit(
                    start = at,
                    end = at,
                    replacement = "${comment.start}  ${comment.end}",
                )
            )
            note(firstRow, ColumnShift(open - 1, 0, comment.start.length + 1))
        } else {
            val opener = byteOffsetOf(firstRow, open)
            val closer = byteOffsetOf(lastRow, close)
            edits.add(
                EditorState.CaretEdit(start = opener, end = opener, replacement = "${comment.start} ")
            )
            edits.add(
                EditorState.CaretEdit(start = closer, end = closer, replacement = " ${comment.end}")
            )
            note(firstRow, ColumnShift(open, 0, comment.start.length + 1))
            note(lastRow, ColumnShift(close, 0, comment.end.length + 1))
        }
    }
    if (edits.isEmpty()) return true

    // Two shifts can land on one row, and each is measured against the row as
    // it stands now — so they are applied from the right, where an earlier
    // one cannot have moved the column a later one is counted from.
    fun moved(row: Int, col: Int): Int =
        shifts[row]?.sortedByDescending { it.at }?.fold(col) { at, shift -> shift.apply(at) } ?: col

    val primary = primaryCaret()
    var newPrimary: Caret? = null
    val carets = caretsInOrder().map { caret ->
        val moved = Caret(
            caret.anchorRow,
            moved(caret.anchorRow, caret.anchorCol),
            caret.headRow,
            moved(caret.headRow, caret.headCol),
        )
        if (caret == primary) newPrimary = moved
        moved
    }
    applyEdits(edits, carets, newPrimary ?: carets.last())
    return true
}

// ---- Typing: auto-close, surround, auto-indent ---------------------------

/**
 * Type [text] at every caret, honouring the bracket and quote pairs.
 *
 * Four behaviours, in the order they are tested, because the order is what
 * makes them bearable:
 *
 * 1. A pair the language disables here does not apply at all: `not_in =
 *    ["string", "comment"]` is on every quote pair Zed ships, and it is why a
 *    `"` typed inside a comment stays a lone `"`. That question needs the
 *    syntax tree, so it is the engine's — see [EditorState.enabledPairsAt] —
 *    and it is asked once for every caret, only when a pair character was
 *    typed, and only when some candidate pair actually carries a `not_in`.
 * 2. With something selected, an opener wraps the selection instead of
 *    replacing it, and the selection survives (Zed's `auto_surround`).
 * 3. Typing a closer that is already sitting in front of the caret steps
 *    over it rather than doubling it.
 * 4. An opener brings its closer with it, but only where the closer would
 *    land somewhere sensible: at the end of the line, before whitespace, or
 *    before one of the language's `autoclose_before` characters. A quote
 *    additionally refuses to open right after a word character, so the
 *    apostrophe in `don't` stays an apostrophe.
 *
 * Openers can be more than one character — Python's `f"`, Rust's `r#"`, the
 * two that open a C-style block comment — so what counts as an opener is
 * "the longest pair whose start ends with what was typed, and whose earlier
 * characters are already behind the caret".
 *
 * Anything else is a plain insert, and single-caret plain inserts go back
 * through [EditorState.insertAtCursor] so typing keeps costing exactly what
 * it did before.
 */
internal fun EditorState.typeCharacter(text: String) {
    val config = languageConfig
    val candidates = config.pairsTriggeredBy(text)
    if (candidates.isEmpty()) {
        insertAtCursor(text)
        return
    }
    val carets = caretsInOrder()

    /** Whether the rest of [pair]'s opener is already on the line at [col]. */
    fun opensHere(pair: BracketPair, lineText: String, col: Int): Boolean {
        val alreadyTyped = pair.open.length - text.length
        return pair.openedByTyping(text) &&
            col >= alreadyTyped &&
            lineText.startsWith(pair.open.dropLast(text.length), col - alreadyTyped)
    }

    // The scope is only worth a bridge call for a pair that could actually
    // open here *and* carries a `not_in`. That is a narrow set: no plain
    // bracket has one, and Rust's block-comment pair — the reason typing `*`
    // reaches this at all — needs the `/` in front of it before it counts.
    val needsScope = carets.any { caret ->
        val lineText = line(caret.headRow)
        candidates.any { index ->
            val pair = config.brackets[index]
            pair.notIn.isNotEmpty() && opensHere(pair, lineText, caret.startCol)
        }
    }
    val masks = if (needsScope) {
        enabledPairsAt(
            LongArray(carets.size) { byteOffsetOf(carets[it].startRow, carets[it].startCol) }
        )
    } else {
        null
    }

    val allowedBefore = config.autocloseBefore
    val primary = primaryCaret()
    val edits = carets.mapIndexed { index, caret ->
        val isPrimary = caret == primary
        val start = byteOffsetOf(caret.startRow, caret.startCol)
        val end = byteOffsetOf(caret.endRow, caret.endCol)
        val lineText = line(caret.headRow)
        val live = { pair: Int ->
            val mask = masks?.getOrNull(index)
            mask == null || (mask ushr pair) and 1L == 1L
        }
        // The longest opener whose earlier characters are already typed:
        // `f"` in Python only opens when the `f` is right there.
        // Against the *start* row's line, not the head's: an opener goes in
        // front of the selection's start, and for a selection spanning rows
        // those are different lines. Asking the head's line about the start's
        // column found no opener whenever the head row was the shorter of the
        // two — and the selection was then replaced by the typed character
        // instead of being wrapped in it.
        val startLine = if (caret.startRow == caret.headRow) lineText else line(caret.startRow)
        val opener = candidates
            .filter { live(it) }
            .map { config.brackets[it] }
            .filter { opensHere(it, startLine, caret.startCol) }
            .maxByOrNull { it.open.length }
        // Deliberately *not* filtered by the scope: `not_in` says where a pair
        // may be opened, and Zed applies it to the opening half alone. A `"`
        // typed in front of the one that ends the string you are inside must
        // still step over it rather than doubling it.
        val closer = candidates.map { config.brackets[it] }.firstOrNull { it.close == text }

        if (!caret.isEmpty && opener != null && opener.surround) {
            val inner = textIn(caret)
            // Only what was typed goes in front: the earlier characters of a
            // multi-character opener are already on the line, which is the
            // precondition for it having matched at all.
            return@mapIndexed EditorState.CaretEdit(
                start = start,
                end = end,
                replacement = text + inner + opener.close,
                anchor = utf8Length(text),
                head = utf8Length(text) + utf8Length(inner),
                isPrimary = isPrimary,
            )
        }
        if (caret.isEmpty && closer != null &&
            lineText.startsWith(closer.close, caret.headCol)
        ) {
            // Step over: no edit at all, just a caret that moved.
            return@mapIndexed EditorState.CaretEdit(
                start = start,
                end = start,
                replacement = "",
                head = utf8Length(closer.close),
                isPrimary = isPrimary,
            )
        }
        if (caret.isEmpty && opener != null && opener.autoClose &&
            closesWellHere(lineText, caret.headCol, opener, allowedBefore)
        ) {
            return@mapIndexed EditorState.CaretEdit(
                start = start,
                end = end,
                // Only the character just typed goes in; the rest of a
                // multi-character opener is already on the line.
                replacement = text + opener.close,
                head = utf8Length(text),
                isPrimary = isPrimary,
            )
        }
        EditorState.CaretEdit(start = start, end = end, replacement = text, isPrimary = isPrimary)
    }
    applyCaretEdits(edits)
}

private fun closesWellHere(
    text: String,
    col: Int,
    pair: BracketPair,
    allowedBefore: String,
): Boolean {
    val following = text.getOrNull(col)
    if (following != null && !following.isWhitespace() && following !in allowedBefore) return false
    // Zed's rule, and only for a pair that is its own closer: a quote right
    // after a word is an apostrophe or a suffix, not an opening quote, and one
    // right after its own kind is closing a string. `f"` is exempt by
    // construction — its halves differ — which is what lets the `f` in front
    // of it be a word character.
    if (!pair.isQuote) return true
    val preceding = text.getOrNull(col - 1) ?: return true
    return !preceding.isLetterOrDigit() && preceding != '_' && !text.startsWith(pair.open, col - 1)
}

/**
 * Enter, with the indent carried over: the new row starts at the current
 * row's indent, one level deeper after a pair or a pattern that opens a
 * block, and an opener whose closer is waiting on the far side of the caret
 * gets a row of its own with the closer pushed down below it.
 *
 * Both rules are the language's own. `newline` on a bracket pair is exactly
 * "an extra newline belongs between these two", which is `true` for every
 * bracket and `false` for every quote in Zed's configs — that flag is why
 * `x = "hello"` + Enter does not indent. `increase_indent_pattern` is the
 * rest: Python's trailing colon, a shell `do`/`then`, a YAML key with nothing
 * after it. Neither is spelled out here.
 *
 * The indent width is the `tab_size` setting; whether it is tabs or spaces
 * is [EditorState.indentUnit]'s question, because the settings file has no
 * hard-tabs key yet and the file in front of you is better evidence than the
 * language's `hard_tabs` would be.
 */
internal fun EditorState.insertNewline() {
    val config = languageConfig
    val primary = primaryCaret()
    val edits = caretsInOrder().map { caret ->
        val text = line(caret.startRow)
        val indentEnd = text.indexOfFirst { it != ' ' && it != '\t' }
            .let { if (it < 0) text.length else it }
        val indent = text.take(minOf(indentEnd, caret.startCol))
        val unit = indentUnit(indent)

        val before = text.take(caret.startCol).trimEnd()
        val opener = config.openerBefore(before)?.takeIf { it.newline }
        val after = line(caret.endRow).drop(caret.endCol).trimStart()
        val opensBlock = opener != null || config.opensBlock(before)
        // An opener whose closer is waiting on the other side of the caret
        // gets a line of its own, with the closer pushed down below it.
        val splitsPair = opener != null && after.startsWith(opener.close.trimStart())

        val replacement = when {
            splitsPair -> "\n$indent$unit\n$indent"
            opensBlock -> "\n$indent$unit"
            else -> "\n$indent"
        }
        val head = if (splitsPair) utf8Length("\n$indent$unit") else null
        EditorState.CaretEdit(
            start = byteOffsetOf(caret.startRow, caret.startCol),
            end = byteOffsetOf(caret.endRow, caret.endCol),
            replacement = replacement,
            head = head,
            isPrimary = caret == primary,
        )
    }
    applyCaretEdits(edits)
}

// ---- Motion the arrow keys cannot express ---------------------------------
//
// Zed binds all of these (assets/keymaps/default-linux.json), and without them
// a paired keyboard — which is how this app is used on DeX and on a foldable
// with a case — can only walk a file one character at a time. Each is
// multi-caret aware for the same reason the arrows are: a column of cursors
// that moves as one is the point of having it.

/** Where a caret lands, given a new row and column. */
private fun EditorState.moveCarets(extend: Boolean, place: (Caret) -> Pair<Int, Int>) {
    val primary = primaryCaret()
    fun moved(caret: Caret): Caret {
        val (row, col) = place(caret)
        return if (extend) {
            // Keep the anchor where it was, or plant one if there was none.
            val anchorRow = if (caret.isEmpty) caret.headRow else caret.anchorRow
            val anchorCol = if (caret.isEmpty) caret.headCol else caret.anchorCol
            Caret(anchorRow, anchorCol, row, col)
        } else {
            Caret(row, col)
        }
    }
    setCarets(caretsInOrder().map(::moved), moved(primary))
}

/**
 * Home, and Zed's "smart" version of it: the first press goes to the first
 * character that is not whitespace, the second to column zero. Landing on the
 * indent is what you want nine times in ten, and the plain column is one more
 * press away rather than gone.
 */
fun EditorState.moveToLineStart(extend: Boolean = false) = moveCarets(extend) { caret ->
    val text = line(caret.headRow)
    val firstText = text.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
    val col = if (caret.headCol == firstText) 0 else firstText
    caret.headRow to col
}

fun EditorState.moveToLineEnd(extend: Boolean = false) = moveCarets(extend) { caret ->
    caret.headRow to line(caret.headRow).length
}

fun EditorState.moveToDocumentStart(extend: Boolean = false) = moveCarets(extend) { 0 to 0 }

fun EditorState.moveToDocumentEnd(extend: Boolean = false) = moveCarets(extend) {
    // The last *visible* row: a fold that runs to the end of the file would
    // otherwise swallow the caret.
    val last = displayMap.prevVisibleRow((lineCount - 1).coerceAtLeast(0))
    last to line(last).length
}

/**
 * A screenful, measured from the viewport rather than a constant: the whole
 * point of Page Down is that it moves by what you can see, and on a foldable
 * that is a different number folded and unfolded.
 */
fun EditorState.movePage(down: Boolean, extend: Boolean = false) {
    val rows = (viewportRows() - 1).coerceAtLeast(1)
    val delta = if (down) rows else -rows
    moveCarets(extend) { caret ->
        // A screenful of *display* rows, which with soft wrap on is far fewer
        // file rows: paging by file rows would jump clean past everything the
        // page just showed.
        pointAtDisplayRow(
            displayRowOf(caret.headRow, caret.headCol) + delta,
            columnWithinSegment(caret.headRow, caret.headCol),
        )
    }
}

/**
 * Ctrl+arrow: to the far end of the run the caret is in — a word, a run of
 * punctuation, or a run of spaces — which is the rule every editor uses and
 * the one that makes the key predictable. Crossing a line boundary lands at
 * the neighbouring line's near end rather than skipping a whole word of it.
 */
fun EditorState.moveByWord(forward: Boolean, extend: Boolean = false) = moveCarets(extend) { caret ->
    val text = line(caret.headRow)
    var col = caret.headCol.coerceIn(0, text.length)
    if (forward) {
        if (col >= text.length) {
            // The next visible row, so the motion never enters a fold.
            val next = displayMap.nextVisibleRow(caret.headRow + 1)
            val row = if (next <= (lineCount - 1).coerceAtLeast(0)) next else caret.headRow
            return@moveCarets if (row == caret.headRow) caret.headRow to col else row to 0
        }
        val kind = characterClass(text[col])
        while (col < text.length && characterClass(text[col]) == kind) col++
        while (col < text.length && text[col].isWhitespace()) col++
    } else {
        if (col <= 0) {
            val row = displayMap.prevVisibleRow((caret.headRow - 1).coerceAtLeast(0))
            return@moveCarets if (row == caret.headRow) caret.headRow to 0 else row to line(row).length
        }
        while (col > 0 && text[col - 1].isWhitespace()) col--
        if (col > 0) {
            val kind = characterClass(text[col - 1])
            while (col > 0 && characterClass(text[col - 1]) == kind) col--
        }
    }
    caret.headRow to col
}

/** Word / punctuation / whitespace — the three runs a word motion stops at. */
private fun characterClass(char: Char): Int = when {
    char.isLetterOrDigit() || char == '_' -> 0
    char.isWhitespace() -> 1
    else -> 2
}

// ---- Indent and outdent ---------------------------------------------------
//
// Zed's `editor::Tab`, `Backtab`, `Indent` and `Outdent`
// (crates/editor/src/editor.rs:5234-5300 and 5424-5590). Every one of them
// is one batch through `applyEdits`, so a whole selection's worth of rows
// costs one bridge call and undoes as one step.

/**
 * A row's leading whitespace as Zed's `IndentSize` sees it
 * (crates/language/src/buffer.rs:5404-5417): the kind is that of the first
 * character, the length counts spaces and tabs alike. A row that starts
 * with a tab and goes on with spaces is a tab-kind indent three long, and
 * outdenting it takes one character.
 */
private class IndentSize(val len: Int, val hardTab: Boolean)

private fun indentSizeOf(text: String): IndentSize {
    var len = 0
    while (len < text.length && (text[len] == ' ' || text[len] == '\t')) len++
    return IndentSize(len, len > 0 && text[0] == '\t')
}

/**
 * The rows a caret indents or outdents — Zed's `spanned_rows(false, ..)`:
 * a selection that stops at column 0 of a later row has not claimed that
 * row. A bare caret claims the row it is on.
 */
private fun Caret.spannedRows(): IntRange =
    if (endRow > startRow && endCol == 0) startRow..endRow - 1 else startRow..endRow

/**
 * How one row's columns move once [inserted] characters went in at [at]
 * and [removed] came out. Unlike the comment toggle's shift, a caret that
 * stands exactly at an insertion goes *after* the text — Zed adds the
 * indent's width to the selection's own columns (`indent_selection`,
 * editor.rs:5514-5520), which is also what keeps a caret on the text it
 * was on.
 */
private class IndentShift(val at: Int, val removed: Int, val inserted: Int) {
    fun apply(col: Int): Int = when {
        col < at -> col
        col < at + removed -> at
        else -> col - removed + inserted
    }
}

/**
 * Zed's `editor::Tab` (editor.rs:5280-5422), without the suggested-indent
 * half: with nothing selected the key inserts one indent level at every
 * caret, as it always has here; with a selection it indents every row the
 * selection spans, and a batch that has both kinds of caret does both at
 * once, which is what Zed's loop does too.
 */
internal fun EditorState.tab() {
    if (caretsInOrder().all { it.isEmpty }) {
        insertAtCursor(indentUnit())
        return
    }
    shiftIndent(indent = true, insertAtBareCarets = true)
}

/** Zed's `editor::Indent` (`ctrl-]`): every caret's rows, one level deeper. */
internal fun EditorState.indent() = shiftIndent(indent = true, insertAtBareCarets = false)

/**
 * Zed's `editor::Outdent` (`ctrl-[`), which is also what `editor::Backtab`
 * comes to once there is no snippet tabstop to go back to (editor.rs:5234):
 * up to one level of leading whitespace off every row the carets touch,
 * and nothing off a row that has none — this never deletes text.
 */
internal fun EditorState.outdent() = shiftIndent(indent = false, insertAtBareCarets = false)

private fun EditorState.shiftIndent(indent: Boolean, insertAtBareCarets: Boolean) {
    val hardTabs = indentUnit() == "\t"
    val edits = ArrayList<EditorState.CaretEdit>()
    val shifts = HashMap<Int, MutableList<IndentShift>>()
    fun note(row: Int, shift: IndentShift) {
        shifts.getOrPut(row) { mutableListOf() }.add(shift)
    }
    // Two carets can share a row; the row is indented once, for the first
    // of them (Zed's `prev_edited_row` / `last_outdent`).
    var lastRow = -1
    for (caret in caretsInOrder()) {
        if (caret.isEmpty && insertAtBareCarets) {
            val unit = indentUnit(line(caret.headRow).takeWhile { it == ' ' || it == '\t' })
            val at = byteOffsetOf(caret.headRow, caret.headCol)
            edits.add(EditorState.CaretEdit(start = at, end = at, replacement = unit))
            note(caret.headRow, IndentShift(caret.headCol, 0, unit.length))
            continue
        }
        val rows = caret.spannedRows()
        val multiple = rows.first != rows.last
        for (row in rows) {
            if (row <= lastRow) continue
            lastRow = row
            val current = indentSizeOf(line(row))
            if (indent) {
                // To the next tab stop, as Zed does (editor.rs:5488-5497): a
                // row indented three spaces in a four-space file goes to
                // four, not seven.
                val delta = when {
                    hardTabs -> "\t"
                    current.hardTab -> " ".repeat(tabSize)
                    else -> " ".repeat(tabSize - current.len % tabSize)
                }
                // A caret standing inside the indent keeps what is behind
                // it: the level goes in at the caret. Anywhere else, and for
                // any multi-row range, it goes in at the margin.
                val at = if (multiple || current.len < caret.startCol) 0 else caret.startCol
                val offset = byteOffsetOf(row, at)
                edits.add(EditorState.CaretEdit(start = offset, end = offset, replacement = delta))
                note(row, IndentShift(at, 0, delta.length))
            } else {
                if (current.len == 0) continue
                // Back to the previous tab stop (`IndentSize::outdent_len`,
                // language/src/buffer.rs:5790-5806): a five-space indent
                // loses one, a four-space one loses four, a tab loses itself.
                val len = if (current.hardTab) {
                    1
                } else {
                    (current.len % tabSize).let { if (it == 0) tabSize else it }
                }
                val at = if (multiple || len > caret.startCol || current.len < caret.startCol) {
                    0
                } else {
                    caret.startCol - len
                }
                edits.add(
                    EditorState.CaretEdit(
                        start = byteOffsetOf(row, at),
                        end = byteOffsetOf(row, at + len),
                        replacement = "",
                    )
                )
                note(row, IndentShift(at, len, 0))
            }
        }
    }
    if (edits.isEmpty()) return

    // Every shift on a row is measured against the row as it stands now, so
    // they are applied from the right, where an earlier one cannot have
    // moved the column a later one is counted from.
    fun moved(row: Int, col: Int): Int =
        shifts[row]?.sortedByDescending { it.at }?.fold(col) { at, shift -> shift.apply(at) } ?: col

    val primary = primaryCaret()
    var newPrimary: Caret? = null
    val carets = caretsInOrder().map { caret ->
        val moved = Caret(
            caret.anchorRow,
            moved(caret.anchorRow, caret.anchorCol),
            caret.headRow,
            moved(caret.headRow, caret.headCol),
        )
        if (caret == primary) newPrimary = moved
        moved
    }
    applyEdits(edits, carets, newPrimary ?: carets.last())
}

// ---- Newline above and below ---------------------------------------------

/**
 * Zed's `editor::NewlineAbove` (crates/editor/src/input.rs:787-854): a
 * fresh row in front of each caret's row, with the caret on it at the
 * indent the row should have. Zed asks the syntax tree's `suggested_indents`
 * for that indent; ours is the nearest non-blank row above the new one,
 * one level deeper where the language says it opens a block — the same two
 * rules Enter uses.
 */
internal fun EditorState.newlineAbove() = newlineBeside(above = true)

/** Zed's `editor::NewlineBelow` (input.rs:856-935): the row after, same rules. */
internal fun EditorState.newlineBelow() = newlineBeside(above = false)

private fun EditorState.newlineBeside(above: Boolean) {
    val config = languageConfig
    val primary = primaryCaret()
    val edits = caretsInOrder().map { caret ->
        val row = caret.headRow
        // The row the new one's indent is read from: the last one with
        // anything on it, at or above where the new row goes.
        var reference = if (above) row - 1 else row
        while (reference >= 0 && line(reference).isBlank()) reference--
        val indent = if (reference < 0) {
            ""
        } else {
            val text = line(reference)
            val lead = text.takeWhile { it == ' ' || it == '\t' }
            val trimmed = text.trimEnd()
            val opener = config.openerBefore(trimmed)?.takeIf { it.newline }
            if (opener != null || config.opensBlock(trimmed)) lead + indentUnit(lead) else lead
        }
        if (above) {
            EditorState.CaretEdit(
                start = lineStartOffset(row),
                end = lineStartOffset(row),
                replacement = "$indent\n",
                head = utf8Length(indent),
                isPrimary = caret == primary,
            )
        } else {
            // A new row under a fold's chip row would land inside the fold;
            // it opens first, as it does for a Backspace at the seam.
            unfoldRowsTouching(row + 1..row + 1, hiddenOnly = true)
            val at = lineEndOffset(row)
            EditorState.CaretEdit(
                start = at,
                end = at,
                replacement = "\n$indent",
                isPrimary = caret == primary,
            )
        }
    }
    applyCaretEdits(edits)
}

// ---- Deleting forwards and by word ----------------------------------------

/**
 * Zed's `editor::Delete` (editor.rs:5205-5232): each caret's selection, or
 * the character in front of it — the newline, at the end of a row, which
 * pulls the next row up.
 */
internal fun EditorState.delete() {
    val primary = primaryCaret()
    val edits = caretsInOrder().map { caret ->
        val start = byteOffsetOf(caret.startRow, caret.startCol)
        val end = if (!caret.isEmpty) {
            byteOffsetOf(caret.endRow, caret.endCol)
        } else {
            val text = line(caret.headRow)
            val col = caret.headCol.coerceAtMost(text.length)
            when {
                col < text.length -> byteOffsetOf(caret.headRow, text.offsetByCodePoints(col, 1))
                caret.headRow < lineCount - 1 -> {
                    // Taking the newline under a chip row would pull the
                    // fold's first hidden row up onto it; open it first.
                    unfoldRowsTouching(caret.headRow + 1..caret.headRow + 1, hiddenOnly = true)
                    start + 1
                }
                // The end of the buffer: nothing in front, the caret rides
                // along so it survives the batch.
                else -> start
            }
        }
        EditorState.CaretEdit(start, end, "", isPrimary = caret == primary)
    }
    applyCaretEdits(edits)
}

/**
 * Zed's `editor::DeleteToPreviousWordStart` as its Linux keymap binds it,
 * `ignore_newlines: false, ignore_brackets: false` (input.rs:975-1005): a
 * selection goes as a whole; a bare caret at column 0 takes the newline
 * behind it and stops there; anywhere else it deletes back to
 * [wordDeletionStart].
 */
internal fun EditorState.deleteToPreviousWordStart() {
    val brackets = languageConfig.bracketCharacters
    val primary = primaryCaret()
    val edits = caretsInOrder().map { caret ->
        if (!caret.isEmpty) {
            return@map EditorState.CaretEdit(
                start = byteOffsetOf(caret.startRow, caret.startCol),
                end = byteOffsetOf(caret.endRow, caret.endCol),
                replacement = "",
                isPrimary = caret == primary,
            )
        }
        val row = caret.headRow
        val text = line(row)
        val col = caret.headCol.coerceAtMost(text.length)
        val start: Long
        val end: Long
        if (col > 0) {
            start = byteOffsetOf(row, wordDeletionStart(text, col, brackets))
            end = byteOffsetOf(row, col)
        } else if (row > 0) {
            unfoldRowsTouching(row - 1..row, hiddenOnly = true)
            start = lineStartOffset(row) - 1
            end = start + 1
        } else {
            start = 0
            end = 0
        }
        EditorState.CaretEdit(start, end, "", isPrimary = caret == primary)
    }
    applyCaretEdits(edits)
}

/**
 * Zed's `editor::DeleteToNextWordEnd`, again with `ignore_newlines: false`
 * (input.rs:1041-1071): the mirror image — at the end of a row the newline
 * goes and nothing more, elsewhere the text up to [wordDeletionEnd].
 */
internal fun EditorState.deleteToNextWordEnd() {
    val brackets = languageConfig.bracketCharacters
    val primary = primaryCaret()
    val edits = caretsInOrder().map { caret ->
        if (!caret.isEmpty) {
            return@map EditorState.CaretEdit(
                start = byteOffsetOf(caret.startRow, caret.startCol),
                end = byteOffsetOf(caret.endRow, caret.endCol),
                replacement = "",
                isPrimary = caret == primary,
            )
        }
        val row = caret.headRow
        val text = line(row)
        val col = caret.headCol.coerceAtMost(text.length)
        val start = byteOffsetOf(row, col)
        val end = when {
            col < text.length -> byteOffsetOf(row, wordDeletionEnd(text, col, brackets))
            row < lineCount - 1 -> {
                unfoldRowsTouching(row + 1..row + 1, hiddenOnly = true)
                start + 1
            }
            else -> start
        }
        EditorState.CaretEdit(start, end, "", isPrimary = caret == primary)
    }
    applyCaretEdits(edits)
}

/** Zed's `CharKind` (crates/language/src/buffer.rs:582-589). */
internal enum class CharKind { Whitespace, Punctuation, Word }

internal fun charKind(c: Char): CharKind = when {
    c.isWhitespace() -> CharKind.Whitespace
    c.isLetterOrDigit() || c == '_' -> CharKind.Word
    else -> CharKind.Punctuation
}

/**
 * Where `ctrl-backspace` deletes back to from [col] on [line], with
 * [col] > 0. Two of Zed's rules, one after the other:
 *
 * 1. `previous_word_start_or_newline` (movement.rs:282-292): walk left
 *    and stop where the character class changes and the one on the right
 *    is not whitespace — so trailing spaces are taken with the word before
 *    them — or at the start of the row.
 * 2. `adjust_greedy_deletion` (movement.rs:299-350): the motion is greedier
 *    than a deletion should be, so the range is cut back to the nearest
 *    bracket or quote it crossed, and then to the nearest run of two or
 *    more whitespace characters — deleting `foo   |` takes the spaces and
 *    leaves `foo` for a second press, and deleting `f(x);|` takes only
 *    the `;`.
 *
 * The bracket rule reads [brackets] textually where Zed reads the syntax
 * tree; the difference is a bracket inside a string, which Zed's grammar
 * queries mostly stop at as well.
 */
internal fun wordDeletionStart(line: String, col: Int, brackets: Set<Char> = emptySet()): Int {
    var start = col
    var right: Char? = null
    while (start > 0) {
        val left = line[start - 1]
        if (right != null &&
            charKind(left) != charKind(right) &&
            charKind(right) != CharKind.Whitespace
        ) {
            break
        }
        start--
        right = left
    }
    // The nearest bracket edge strictly inside the range: either side of a
    // bracket character counts, the range's own ends do not.
    for (i in col - 1 downTo start) {
        if (line[i] !in brackets) continue
        if (i + 1 < col) {
            start = i + 1
            break
        }
        if (i > start) {
            start = i
            break
        }
    }
    // The last run of two or more whitespace characters in what is left.
    var runStart = -1
    var i = start
    while (i < col) {
        if (line[i].isWhitespace()) {
            val from = i
            while (i < col && line[i].isWhitespace()) i++
            if (i - from >= 2) runStart = from
        } else {
            i++
        }
    }
    return if (runStart >= 0) runStart else start
}

/**
 * Where `ctrl-delete` deletes forward to from [col] on [line], with [col]
 * short of the row's end. The mirror of [wordDeletionStart]:
 * `next_word_end_or_newline` (movement.rs:462-479) stops where the class
 * changes and the character on the *left* is not whitespace, so leading
 * spaces go with the word after them; then the same greedy cut, to the
 * nearest bracket and the first long run of whitespace.
 */
internal fun wordDeletionEnd(line: String, col: Int, brackets: Set<Char> = emptySet()): Int {
    var end = col
    var left: Char? = null
    while (end < line.length) {
        val right = line[end]
        if (left != null &&
            charKind(left) != charKind(right) &&
            charKind(left) != CharKind.Whitespace
        ) {
            break
        }
        end++
        left = right
    }
    for (i in col until end) {
        if (line[i] !in brackets) continue
        if (i > col) {
            end = i
            break
        }
        if (i + 1 < end) {
            end = i + 1
            break
        }
    }
    var i = col
    while (i < end) {
        if (line[i].isWhitespace()) {
            val from = i
            while (i < end && line[i].isWhitespace()) i++
            if (i - from >= 2) return i
        } else {
            i++
        }
    }
    return end
}
