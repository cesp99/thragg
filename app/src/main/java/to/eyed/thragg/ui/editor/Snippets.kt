package to.eyed.thragg.ui.editor

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * LSP snippet bodies, parsed into text plus tabstops — and the user's own
 * snippet files.
 *
 * Zed's snippets are the LSP syntax (`$1`, `${2:placeholder}`,
 * `${1|a,b,c|}`, `$0`, `\$`) expanded by `snippet::Snippet::parse`
 * (crates/snippet/src/snippet.rs), and the user's live in
 * `~/.config/zed/snippets/<language>.json` in VS Code's format:
 * `{"name": {"prefix": …, "body": […], "description": …}}`
 * (docs/src/snippets.md). Both are here; the driving of the tabstops is
 * [SnippetSession] and [EditorState.snippet].
 */

/**
 * One tabstop: every place the same number appears, in source order. More
 * than one range is a *mirror* — `${1:name}` twice means both, and jumping to
 * the stop puts a caret on each, so typing replaces them together.
 *
 * Offsets are indices into [ParsedSnippet.text].
 */
data class SnippetStop(
    /** The stop's number; `$0` is [FINAL] and always goes last. */
    val index: Int,
    val ranges: List<IntRange>,
    /** The choices `${1|a,b|}` offered, in order; empty for an ordinary stop. */
    val choices: List<String> = emptyList(),
) {
    companion object {
        /** `$0` — Zed's final caret, which ends the session rather than being one. */
        const val FINAL = 0
    }
}

/** A snippet body, expanded. */
data class ParsedSnippet(
    /** The text to insert, with every placeholder resolved to its default. */
    val text: String,
    /** The stops in visiting order: 1, 2, … and `$0` last. */
    val stops: List<SnippetStop>,
) {
    /** True when there is somewhere to put the caret other than the end. */
    val hasStops: Boolean get() = stops.isNotEmpty()

    /**
     * Where a caret goes when the session is not driven — the first stop's
     * start, or the end of the text. What the old flattening path produced.
     */
    val caret: Int get() = stops.firstOrNull()?.ranges?.firstOrNull()?.first ?: text.length
}

/**
 * Zed's snippet grammar (snippet.rs `parse_snippet`), in one pass:
 *
 * - `$N` — an empty stop at that point.
 * - `${N}` — the same, spelled with braces.
 * - `${N:default}` — a stop whose range covers `default`, which is inserted.
 *   Nested `$M` inside a default is parsed too, as Zed parses it.
 * - `${N|a,b,c|}` — a stop offering choices; the first is inserted, as Zed
 *   inserts it.
 * - `$0` — the final caret. Visited last, and leaving it ends the session.
 * - `\$`, `\}` and `\\` — the literal character.
 *
 * Anything that does not parse is left as written, which is what a `$` in a
 * shell snippet needs.
 */
fun parseSnippet(body: String): ParsedSnippet {
    val out = StringBuilder(body.length)
    val ranges = HashMap<Int, MutableList<IntRange>>()
    val choicesByStop = HashMap<Int, List<String>>()
    val order = ArrayList<Int>()

    fun note(index: Int, range: IntRange, choices: List<String> = emptyList()) {
        if (ranges.putIfAbsent(index, mutableListOf(range)) != null) {
            ranges.getValue(index).add(range)
        } else {
            order.add(index)
        }
        if (choices.isNotEmpty()) choicesByStop.putIfAbsent(index, choices)
    }

    // A recursive descent, because `${1:a ${2:b} c}` nests. `from` is where in
    // `body` to start; it returns where it stopped, and appends to `out`.
    fun scan(from: Int, until: Int): Int {
        var i = from
        while (i < until) {
            val char = body[i]
            if (char == '\\' && i + 1 < until && body[i + 1] in "$}\\") {
                out.append(body[i + 1])
                i += 2
                continue
            }
            if (char == '}' && from != 0) return i
            if (char != '$') {
                out.append(char)
                i++
                continue
            }
            // `$N`
            if (i + 1 < until && body[i + 1].isDigit()) {
                var end = i + 1
                while (end < until && body[end].isDigit()) end++
                val number = body.substring(i + 1, end).toIntOrNull()
                if (number == null) {
                    out.append(char)
                    i++
                    continue
                }
                note(number, out.length until out.length)
                i = end
                continue
            }
            // `${…}`
            if (i + 1 < until && body[i + 1] == '{') {
                var end = i + 2
                while (end < until && body[end].isDigit()) end++
                val number = body.substring(i + 2, end).toIntOrNull()
                if (number == null || end >= until) {
                    out.append(char)
                    i++
                    continue
                }
                when (body[end]) {
                    '}' -> {
                        note(number, out.length until out.length)
                        i = end + 1
                    }
                    ':' -> {
                        val start = out.length
                        val stopped = scan(end + 1, until)
                        note(number, start until out.length)
                        i = if (stopped < until && body[stopped] == '}') stopped + 1 else stopped
                    }
                    '|' -> {
                        val close = body.indexOf("|}", end + 1)
                        if (close < 0) {
                            out.append(char)
                            i++
                            continue
                        }
                        // Verbatim: an empty choice is a real one — Zed's
                        // `${1|mut ,|}` offers "mut " and "".
                        val choices = body.substring(end + 1, close).split(',')
                        val start = out.length
                        out.append(choices.firstOrNull().orEmpty())
                        note(number, start until out.length, choices)
                        i = close + 2
                    }
                    else -> {
                        out.append(char)
                        i++
                    }
                }
                continue
            }
            out.append(char)
            i++
        }
        return until
    }

    scan(0, body.length)

    // Visiting order: 1, 2, 3 … and `$0` last, which is Zed's order and the
    // LSP's — `$0` is where the caret rests when the snippet is done.
    val sorted = order.sortedBy { if (it == SnippetStop.FINAL) Int.MAX_VALUE else it }
    val stops = sorted.map { index ->
        SnippetStop(
            index = index,
            ranges = ranges.getValue(index).sortedBy { it.first },
            choices = choicesByStop[index].orEmpty(),
        )
    }
    return ParsedSnippet(out.toString(), stops)
}

/**
 * One snippet the user wrote — a row of `<filesDir>/snippets/<language>.json`
 * in Zed's format (docs/src/snippets.md):
 *
 * ```json
 * {"Log": {"prefix": "log", "body": ["console.log($1);", "$0"],
 *          "description": "Log to the console"}}
 * ```
 */
data class UserSnippet(
    /** The object's key — what the completion row is labelled with. */
    val name: String,
    /** What the user types to reach it. */
    val prefix: String,
    /** The body, lines already joined with `\n` as Zed joins them. */
    val body: String,
    val description: String?,
)

/**
 * Read one language's snippet file. Never throws: a file that will not parse
 * is no snippets, which is a state the menu already draws, rather than a
 * crash on the completion path.
 *
 * `prefix` may be a string or an array of strings in Zed's format; an entry
 * with neither a prefix nor a body is dropped, as an entry that can only fail.
 */
fun parseUserSnippets(json: String?): List<UserSnippet> {
    if (json.isNullOrBlank()) return emptyList()
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    val snippets = ArrayList<UserSnippet>()
    for (name in root.keys()) {
        val entry = root.optJSONObject(name) ?: continue
        val body = when (val raw = entry.opt("body")) {
            is JSONArray -> (0 until raw.length()).joinToString("\n") { raw.optString(it) }
            is String -> raw
            else -> null
        }?.takeIf { it.isNotEmpty() } ?: continue
        val prefixes = when (val raw = entry.opt("prefix")) {
            is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
            is String -> listOf(raw)
            else -> listOf(name)
        }.filter { it.isNotBlank() }
        val description = entry.optString("description").takeIf { it.isNotBlank() }
        for (prefix in prefixes) {
            snippets.add(UserSnippet(name, prefix, body, description))
        }
    }
    return snippets.sortedBy { it.prefix }
}

/**
 * The user's snippets for [language] plus the ones for every language
 * (`snippets/snippets.json`, Zed's global file). **Blocking** — call it off
 * the main thread.
 */
fun loadUserSnippets(filesDir: File, language: String?): List<UserSnippet> {
    val directory = File(filesDir, "snippets")
    if (!directory.isDirectory) return emptyList()
    val names = listOfNotNull(language?.lowercase(), "snippets").distinct()
    return names.flatMap { name ->
        val file = File(directory, "$name.json")
        if (file.isFile) parseUserSnippets(runCatching { file.readText() }.getOrNull()) else emptyList()
    }
}


// ---- driving a session ---------------------------------------------------

/**
 * A snippet being filled in: which stop the carets are on, and where every
 * stop is in the buffer right now.
 *
 * Offsets are absolute UTF-8 byte offsets, as everywhere the engine is
 * involved. They are kept current by [shiftedOffsets], which is the honest
 * thing to do without anchors on the bridge: an edit inside the stop being
 * filled in lengthens that stop and pushes everything after it along, and an
 * edit anywhere else ends the session ([EditorState.snippetTab] checks).
 */
data class SnippetSession(
    val stops: List<SnippetStop>,
    /** Which of [stops] the carets are on. */
    val at: Int,
    /** Every stop's ranges, in stop order — [at] indexes into this too. */
    val offsets: List<List<LongRange>>,
    /** The buffer's byte length when [offsets] were last true. */
    val bufferLength: Long,
) {
    /** Where the carets are now. */
    val ranges: List<LongRange> get() = offsets[at]
    val hasNext: Boolean get() = at + 1 < stops.size
    val hasPrevious: Boolean get() = at > 0
}

/**
 * Every stop's offsets after an edit of [delta] bytes made simultaneously by
 * [caretCount] carets, all of them on [current] — which is what typing into a
 * mirrored tabstop is.
 *
 * Each caret contributes `delta / caretCount`, so an offset moves by the
 * contributions of every caret in front of it: a stop before the current one
 * does not move, the current one's *j*th range moves by *j* contributions and
 * grows by one more, and everything after moves by all of them. Pure, so the
 * arithmetic can be pinned by a test.
 */
fun shiftedOffsets(
    offsets: List<List<LongRange>>,
    current: List<LongRange>,
    delta: Long,
    caretCount: Int,
): List<List<LongRange>> {
    if (delta == 0L || caretCount <= 0) return offsets
    val perCaret = delta / caretCount
    return offsets.map { ranges ->
        ranges.map { range ->
            val startShift = perCaret * current.count { it.first < range.first }
            val endShift = perCaret * current.count { it.first <= range.last }
            (range.first + startShift)..(range.last + endShift)
        }
    }
}

/**
 * Begin filling in a snippet whose text has just been inserted starting at
 * [insertedAt] — the carets go onto its first stop, every mirror of it at
 * once, so typing replaces them together.
 *
 * Returns false where there is nothing to drive: a snippet with no stops, or
 * one whose only stop is `$0`, which is where the caret already is.
 */
internal fun EditorState.startSnippet(parsed: ParsedSnippet, insertedAt: Long): Boolean {
    if (!parsed.hasStops) return false
    if (parsed.stops.size == 1 && parsed.stops.first().index == SnippetStop.FINAL) return false
    // The parser counts UTF-16 indices into its own text; the buffer counts
    // bytes, so each offset is measured against the text that was inserted.
    fun offsetOf(index: Int): Long = insertedAt + utf8Length(parsed.text, index)
    val offsets = parsed.stops.map { stop ->
        stop.ranges.map { offsetOf(it.first)..offsetOf(it.last + 1) }
    }
    val session = SnippetSession(
        stops = parsed.stops,
        at = 0,
        offsets = offsets,
        bufferLength = bufferByteLength(),
    )
    snippet = session
    placeSnippetCarets(session.ranges)
    return true
}

/**
 * Tab and Shift+Tab inside a snippet — Zed asks
 * `move_to_next_snippet_tabstop` before `editor::Tab` indents anything
 * (editor.rs `tab`), and this is that question.
 *
 * Returns false when there is no session, or when the buffer changed
 * *outside* the stop being filled in: the offsets would then describe text
 * that has moved, and guessing new ones would be a lie. The session ends and
 * Tab means what it always meant.
 */
internal fun EditorState.snippetTab(forward: Boolean): Boolean {
    val session = snippet ?: return false
    if (forward && !session.hasNext) {
        endSnippet()
        return false
    }
    if (!forward && !session.hasPrevious) return false
    val length = bufferByteLength()
    val delta = length - session.bufferLength
    val caretCount = session.ranges.size.coerceAtLeast(1)
    // A delta that is not a whole number of carets came from somewhere other
    // than typing into this stop — a paste elsewhere, an applied workspace
    // edit — and the offsets can no longer be trusted.
    if (delta % caretCount != 0L) {
        endSnippet()
        return false
    }
    val offsets = shiftedOffsets(session.offsets, session.ranges, delta, caretCount)
    val next = if (forward) session.at + 1 else session.at - 1
    val moved = session.copy(at = next, offsets = offsets, bufferLength = length)
    snippet = moved
    placeSnippetCarets(moved.ranges)
    // `$0` is where the snippet ends: land on it and the session is over, so
    // the next Tab indents as it always did.
    if (moved.stops[next].index == SnippetStop.FINAL) snippet = null
    return true
}

/** Escape while a snippet is being filled in — Zed's Cancel ends the session. */
internal fun EditorState.endSnippet(): Boolean {
    if (snippet == null) return false
    snippet = null
    return true
}

/** Put one caret on each range, the first of them primary. */
private fun EditorState.placeSnippetCarets(ranges: List<LongRange>) {
    val carets = ranges.map { range ->
        val (startRow, startCol) = pointAt(range.first)
        val (endRow, endCol) = pointAt(range.last)
        Caret(startRow, startCol, endRow, endCol)
    }
    if (carets.isEmpty()) return
    setCarets(carets, carets.first())
}

/** The buffer's length in bytes — what a session measures its drift against. */
private fun EditorState.bufferByteLength(): Long {
    val last = (lineCount - 1).coerceAtLeast(0)
    return byteOffsetOf(last, line(last).length)
}
