package to.eyed.seeker.code.ui.preview

/**
 * A hand-written Markdown reader, deliberately.
 *
 * Zed renders its preview with `pulldown-cmark` behind
 * `crates/markdown/src/parser.rs`; the Rust equivalent is not reachable from
 * here without a new JNI surface, and the Java/Kotlin markdown libraries all
 * cost more than they are worth for this: commonmark-java is ~330 KB of dex
 * before extensions, flexmark closer to 1.5 MB, and neither of them renders
 * anything — they hand back an AST that still has to be walked into Compose
 * exactly as [MarkdownBlock] is. What a README actually uses is the CommonMark
 * core plus GitHub's tables, task lists and alerts, and that is what this file
 * reads.
 *
 * It is *not* a conforming CommonMark parser, and the places it knowingly
 * differs are marked at the code that makes each choice. It is pure Kotlin
 * with no Android or Compose types in it, which is what lets the whole of it
 * be tested on the host.
 */

/** What an inline run is wearing. A run may wear several at once. */
enum class InlineStyle {
    Bold,
    Italic,
    Code,
    Strikethrough,

    /**
     * A `$…$` (or `$$…$$`) run of TeX.
     *
     * Nothing here *typesets* it: there is no TeX engine on the device and
     * shipping one is a megabyte of dex for a `\frac`. What the style buys is
     * that the source survives — `$a_i * b_j$` is no longer read as emphasis
     * and an underscore, which is the state this file was in — and that the
     * renderer can set it apart from prose. See `docs/SHORTCUTS.md` for what
     * is and is not supported.
     */
    Math,
}

/**
 * One run of inline text: the smallest piece with a single appearance.
 *
 * A link's label is one or more runs all carrying the same [link], so
 * `**[bold link](url)**` stays bold *and* clickable rather than having to
 * choose.
 */
data class InlineSpan(
    val text: String,
    val styles: Set<InlineStyle> = emptySet(),
    /** Destination of the link this run belongs to, or null. */
    val link: String? = null,
    /**
     * True when the run stands in for an image, and [text] is its alt text.
     * Nothing here fetches anything — see the placeholder in the renderer.
     */
    val isImage: Boolean = false,
    /**
     * An image run's own `src`, kept beside [link].
     *
     * The two differ for the shape every badge is written in —
     * `[![alt](badge.svg)](the-job)` — where [link] is the job a tap opens and
     * this is the picture to draw. Before this field the src was thrown away
     * whenever a link enclosed the image, so a badge could only ever be alt
     * text.
     */
    val imageSource: String? = null,
)

/** How a table column is aligned, from its delimiter row's colons. */
enum class ColumnAlignment { Start, Center, End }

/** One entry of a bullet or ordered list. */
data class ListItem(
    /** What the renderer draws in the margin: `•`, `1.`, `2.`… */
    val marker: String,
    /** Checked state for a GitHub task item, or null when it is not one. */
    val checked: Boolean?,
    val blocks: List<MarkdownBlock>,
)

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val content: List<InlineSpan>) : MarkdownBlock

    data class Paragraph(val content: List<InlineSpan>) : MarkdownBlock

    /** A fenced or indented code block. [language] is the fence's info word. */
    data class Code(val language: String?, val code: String) : MarkdownBlock

    /**
     * A block quote. [kind] is GitHub's alert marker — `NOTE`, `TIP`,
     * `IMPORTANT`, `WARNING`, `CAUTION` — when the quote opens with one, which
     * is how a modern README writes a callout.
     */
    data class Quote(val kind: String?, val blocks: List<MarkdownBlock>) : MarkdownBlock

    /**
     * A list. [tight] is CommonMark's looseness: a list whose items are
     * separated by blank lines gets paragraph spacing, a tight one does not.
     */
    data class Bullets(
        val ordered: Boolean,
        val tight: Boolean,
        val items: List<ListItem>,
    ) : MarkdownBlock

    data object Rule : MarkdownBlock

    data class Table(
        val header: List<List<InlineSpan>>,
        val alignments: List<ColumnAlignment>,
        val rows: List<List<List<InlineSpan>>>,
    ) : MarkdownBlock

    /**
     * A `$$…$$` display-math block, its TeX kept verbatim.
     *
     * Not typeset — see [InlineStyle.Math]. Held as its own block rather than
     * as a paragraph so the renderer can centre it and set it in the buffer
     * font, and so a multi-line `\begin{aligned}` keeps its line breaks
     * instead of being reflowed into a sentence.
     */
    data class Math(val source: String) : MarkdownBlock
}

/** GitHub's alert kinds, as they may appear after `>` on a quote's first line. */
private val ALERT_KINDS = setOf("NOTE", "TIP", "IMPORTANT", "WARNING", "CAUTION")

/** CommonMark's limit: four spaces of indent is a code block, not a marker. */
private const val CODE_INDENT = 4

/*
 * The bounds below all exist for one reason, and it is worth stating once:
 * this reader's input is a file out of a repository the user has just cloned,
 * so every loop over it is a loop over something an adversary chose. Nothing
 * here may recurse as deep as the document says, and nothing here may spend
 * time quadratic in a length the document picks — a `StackOverflowError` or a
 * half-minute stall on the parse thread is process death, and process death
 * takes every unsaved buffer in every tab with it.
 */

/**
 * How deep block nesting is followed before the rest is shown as plain text.
 *
 * A quote and a list item are both read by recursing on their stripped body,
 * so nesting depth *is* stack depth: a line of 1500 `>` characters overflows a
 * worker thread's stack, and `- - - - …` copies its own body once per level on
 * the way down. Nothing legible nests thirty-two deep.
 */
private const val MAX_BLOCK_DEPTH = 32

/** The same, for a link inside emphasis inside strikethrough inside a link. */
private const val MAX_INLINE_DEPTH = 24

/**
 * How far a closing delimiter is looked for.
 *
 * An opener with no partner otherwise scans to the end of its paragraph and
 * *then* stays literal, so k of them in a paragraph of n characters cost
 * O(k·n): 240 KB of `*a ` measured at 23 seconds, and a paragraph of glob
 * patterns — `*.rs *.toml …`, every one of them an opener and none of them a
 * closer — at 3.7 seconds for 209 KB. A README's emphasis, code span, link
 * label and autolink are all far shorter than this window.
 */
private const val INLINE_SCAN_WINDOW = 1024

/**
 * Past this, a run of text is drawn as it stands with no markup read out of
 * it. [readParagraph] joins every consecutive non-blank line, so a "paragraph"
 * is as long as the file's longest unbroken run of them — prose does not do
 * this, and generated output does.
 */
private const val MAX_INLINE_CHARS = 32 * 1024

/**
 * A parsed document, and where in the source each of its top-level blocks
 * came from.
 *
 * Zed's markdown parser hangs a source range off every parsed block
 * (`crates/markdown/src/parser.rs`, `ParsedMarkdownElement::source_range`) and
 * the preview uses it for both directions of scroll sync —
 * `sync_preview_to_source_index` picks the *root* block that owns the editor's
 * offset (markdown_preview_view.rs:628-647). Roots are all this needs too, so
 * only the top level is measured: a range per nested list item would cost a
 * parallel tree and buy nothing the reader can see.
 *
 * [lineRanges] is index-aligned with [blocks]: `lineRanges[i]` is the
 * half-open run of 0-based source lines block `i` was read from, and the
 * ranges are in increasing order because the reader only ever moves forward.
 */
class ParsedMarkdown(
    val blocks: List<MarkdownBlock>,
    val lineRanges: List<IntRange>,
) {
    companion object {
        val EMPTY = ParsedMarkdown(emptyList(), emptyList())
    }
}

/**
 * Read [source] into blocks, with the source lines each top-level block came
 * from.
 *
 * The link reference definitions are collected first, over the whole document,
 * because `[text][ref]` may name a `[ref]:` line further down — which is how
 * every badge-heavy README is written.
 */
fun parseMarkdownDocument(source: String): ParsedMarkdown {
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val ranges = mutableListOf<IntRange>()
    val blocks = parseBlocks(lines, collectLinkDefinitions(lines), depth = 0, ranges = ranges)
    return ParsedMarkdown(blocks, ranges)
}

/** [parseMarkdownDocument] for a caller with no source to point back at. */
fun parseMarkdown(source: String): List<MarkdownBlock> = parseMarkdownDocument(source).blocks

// ---- Block level ---------------------------------------------------------

private fun parseBlocks(
    lines: List<String>,
    links: Map<String, String>,
    depth: Int,
    ranges: MutableList<IntRange>? = null,
): List<MarkdownBlock> {
    if (depth >= MAX_BLOCK_DEPTH) {
        // Shown rather than descended into: the text is still all there, it
        // simply stops being given structure. See MAX_BLOCK_DEPTH.
        val rest = lines.joinToString("\n").trim()
        return if (rest.isEmpty()) {
            emptyList()
        } else {
            listOf(MarkdownBlock.Paragraph(listOf(InlineSpan(rest))))
        }
    }
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0
    while (index < lines.size) {
        val start = index
        index = readBlock(lines, index, links, blocks, depth)
        // One reader may only ever add one block, but the bookkeeping is
        // written as a loop so that stays true rather than merely being true
        // today. Readers that consume a line without producing a block — a
        // blank, a `[ref]:` definition — leave the list where it was.
        while (ranges != null && ranges.size < blocks.size) {
            ranges.add(start until maxOf(index, start + 1))
        }
    }
    return blocks
}

/**
 * One block, from [index], appended to [blocks]; returns the line the reader
 * stopped at.
 *
 * Split out of [parseBlocks] so the loop above can bracket each block with the
 * lines it came from — the source range Zed's parser records per block.
 */
private fun readBlock(
    lines: List<String>,
    index: Int,
    links: Map<String, String>,
    blocks: MutableList<MarkdownBlock>,
    depth: Int,
): Int {
    val line = lines[index]
    if (line.isBlank()) return index + 1
    val indent = line.indentWidth()

    val fence = fenceAt(line)
    if (fence != null) return readFencedCode(lines, index, fence, blocks)
    // Indented code, but only where a paragraph cannot be continuing: a
    // wrapped paragraph line indented by four spaces is still that
    // paragraph, and treating it as code is the classic misread.
    if (indent >= CODE_INDENT && blocks.lastOrNull() !is MarkdownBlock.Paragraph) {
        return readIndentedCode(lines, index, blocks)
    }
    if (linkDefinitionOf(line) != null) return index + 1
    val heading = atxHeadingAt(line)
    if (heading != null) {
        blocks.add(MarkdownBlock.Heading(heading.first, parseInline(heading.second, links)))
        return index + 1
    }
    if (isThematicBreak(line)) {
        blocks.add(MarkdownBlock.Rule)
        return index + 1
    }
    val mathEnd = displayMathEnd(lines, index)
    if (mathEnd != null) return readDisplayMath(lines, index, mathEnd, blocks)
    if (indent < CODE_INDENT && line.trimStart().startsWith('>')) {
        return readQuote(lines, index, links, blocks, depth)
    }
    if (markerAt(line) != null) return readList(lines, index, links, blocks, depth)
    if (index + 1 < lines.size && '|' in line && tableAlignments(lines[index + 1]) != null) {
        return readTable(lines, index, links, blocks)
    }
    return readParagraph(lines, index, links, blocks)
}

/** The fence's char and length, or null when [line] does not open one. */
private fun fenceAt(line: String): Pair<Char, Int>? {
    if (line.indentWidth() >= CODE_INDENT) return null
    val body = line.trimStart()
    val char = body.firstOrNull() ?: return null
    if (char != '`' && char != '~') return null
    val run = body.takeWhile { it == char }.length
    if (run < 3) return null
    // A backtick fence's info string may not contain a backtick, which is what
    // keeps `` `a` `` from being read as a fence.
    if (char == '`' && '`' in body.drop(run)) return null
    return char to run
}

private fun readFencedCode(
    lines: List<String>,
    start: Int,
    fence: Pair<Char, Int>,
    blocks: MutableList<MarkdownBlock>,
): Int {
    val (char, length) = fence
    val opener = lines[start]
    val indent = opener.indentWidth()
    val info = opener.trimStart().drop(length).trim()
        .substringBefore(' ')
        .takeIf { it.isNotEmpty() }
    val body = mutableListOf<String>()
    var index = start + 1
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        if (line.indentWidth() < CODE_INDENT &&
            trimmed.isNotEmpty() &&
            trimmed.all { it == char } &&
            trimmed.length >= length
        ) {
            index++
            break
        }
        body.add(line.dropIndent(indent))
        index++
    }
    blocks.add(MarkdownBlock.Code(info, body.joinToString("\n")))
    return index
}

private fun readIndentedCode(
    lines: List<String>,
    start: Int,
    blocks: MutableList<MarkdownBlock>,
): Int {
    val body = mutableListOf<String>()
    var index = start
    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) {
            // A blank line only stays in the block if code follows it. Scanned
            // by index rather than with `drop`, which would copy the tail of
            // the document once per blank line.
            var next = index + 1
            while (next < lines.size && lines[next].isBlank()) next++
            if (next >= lines.size || lines[next].indentWidth() < CODE_INDENT) break
            body.add("")
            index++
            continue
        }
        if (line.indentWidth() < CODE_INDENT) break
        body.add(line.dropIndent(CODE_INDENT))
        index++
    }
    blocks.add(MarkdownBlock.Code(null, body.joinToString("\n")))
    return index
}

/**
 * How many lines a `$$` block may run to before it is read as prose instead.
 *
 * An opening `$$` with no partner would otherwise swallow the rest of the
 * file into one grey box — the same failure an unclosed fence has, except a
 * lone `$$` is a far likelier typo than a lone triple backtick. Past this the
 * opener is just a line of a paragraph.
 */
private const val MAX_MATH_LINES = 200

/**
 * Where the `$$` block opening at [start] closes, or null when [start] does
 * not open one (or nothing closes it within [MAX_MATH_LINES]).
 *
 * GitHub's display-math extension, which is the only `$$` a README has: the
 * opener is `$$` at the start of its own line, the closer is `$$` at the end
 * of a line, and `$$x$$` on one line is both.
 */
private fun displayMathEnd(lines: List<String>, start: Int): Int? {
    if (lines[start].indentWidth() >= CODE_INDENT) return null
    val first = lines[start].trim()
    if (!first.startsWith("$$")) return null
    if (first.length >= 4 && first.endsWith("$$")) return start + 1
    val stop = minOf(lines.size, start + 1 + MAX_MATH_LINES)
    for (index in start + 1 until stop) {
        if (lines[index].trim().endsWith("$$")) return index + 1
    }
    return null
}

/** The TeX between the `$$`s, verbatim — see [MarkdownBlock.Math]. */
private fun readDisplayMath(
    lines: List<String>,
    start: Int,
    end: Int,
    blocks: MutableList<MarkdownBlock>,
): Int {
    val body = mutableListOf<String>()
    for (index in start until end) {
        var text = lines[index]
        if (index == start) text = text.trim().removePrefix("$$")
        if (index == end - 1) text = text.trimEnd().removeSuffix("$$")
        body.add(text)
    }
    blocks.add(MarkdownBlock.Math(body.joinToString("\n").trim('\n').trimEnd()))
    return end
}

/** `#` … `######`, with its text. */
private fun atxHeadingAt(line: String): Pair<Int, String>? {
    if (line.indentWidth() >= CODE_INDENT) return null
    val body = line.trimStart()
    val hashes = body.takeWhile { it == '#' }.length
    if (hashes !in 1..6) return null
    val rest = body.drop(hashes)
    if (rest.isNotEmpty() && !rest[0].isWhitespace()) return null
    // A closing run of hashes is decoration, not text.
    return hashes to rest.trim().trimEnd('#').trim()
}

private fun isThematicBreak(line: String): Boolean {
    if (line.indentWidth() >= CODE_INDENT) return false
    val body = line.trim().filter { !it.isWhitespace() }
    if (body.length < 3) return false
    val char = body[0]
    return (char == '-' || char == '*' || char == '_') && body.all { it == char }
}

private fun readQuote(
    lines: List<String>,
    start: Int,
    links: Map<String, String>,
    blocks: MutableList<MarkdownBlock>,
    depth: Int,
): Int {
    val body = mutableListOf<String>()
    var index = start
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trimStart()
        if (line.indentWidth() < CODE_INDENT && trimmed.startsWith('>')) {
            body.add(trimmed.drop(1).removePrefix(" "))
            index++
            continue
        }
        // Lazy continuation: an unprefixed line still belongs to the quote's
        // open paragraph, which is how most people actually wrap a quote. A
        // fence opener is not a continuation of anything — a ``` immediately
        // under a quote is the document's own code block, and swallowing it
        // draws it inside the quote's accent bar.
        if (line.isNotBlank() && body.lastOrNull()?.isNotBlank() == true &&
            !isThematicBreak(line) && atxHeadingAt(line) == null && markerAt(line) == null &&
            fenceAt(line) == null && displayMathEnd(lines, index) == null
        ) {
            body.add(line)
            index++
            continue
        }
        break
    }
    var kind: String? = null
    val first = body.firstOrNull()?.trim().orEmpty()
    if (first.startsWith("[!") && first.endsWith("]")) {
        val name = first.removePrefix("[!").removeSuffix("]").uppercase()
        if (name in ALERT_KINDS) {
            kind = name
            body.removeAt(0)
        }
    }
    blocks.add(MarkdownBlock.Quote(kind, parseBlocks(body, links, depth + 1)))
    return index
}

/**
 * A list marker on one line.
 *
 * [contentIndent] is the *column* an item's continuation lines have to reach
 * to belong to it; [contentOffset] is the character index its own first line's
 * content starts at. The two differ as soon as a tab is involved, and using
 * one for the other eats a character of the item's text.
 */
private class Marker(
    val indent: Int,
    val text: String,
    val contentIndent: Int,
    val contentOffset: Int,
    val ordered: Boolean,
)

private fun markerAt(line: String): Marker? {
    val indent = line.indentWidth()
    if (indent >= CODE_INDENT) return null
    val body = line.trimStart()
    val char = body.firstOrNull() ?: return null
    val text: String
    val ordered: Boolean
    if (char == '-' || char == '*' || char == '+') {
        // `---` is a rule, and `- - -` is one too; neither opens a list.
        if (isThematicBreak(line)) return null
        text = char.toString()
        ordered = false
    } else if (char.isDigit()) {
        val digits = body.takeWhile { it.isDigit() }
        if (digits.length > 9) return null
        val after = body.getOrNull(digits.length) ?: return null
        if (after != '.' && after != ')') return null
        text = digits + after
        ordered = true
    } else {
        return null
    }
    val rest = body.drop(text.length)
    if (rest.isNotEmpty() && !rest[0].isWhitespace()) return null
    val spaces = rest.takeWhile { it == ' ' }.length
    // An empty item, or one whose content starts a code block, indents by one.
    val gap = if (rest.isBlank() || spaces == 0 || spaces > CODE_INDENT) 1 else spaces
    val leading = line.length - body.length
    return Marker(
        indent = indent,
        text = text,
        contentIndent = indent + text.length + gap,
        contentOffset = leading + text.length + gap,
        ordered = ordered,
    )
}

private fun readList(
    lines: List<String>,
    start: Int,
    links: Map<String, String>,
    blocks: MutableList<MarkdownBlock>,
    depth: Int,
): Int {
    val first = markerAt(lines[start])!!
    val items = mutableListOf<ListItem>()
    var loose = false
    var index = start
    var counter = 0
    while (index < lines.size) {
        // A marker indented as far as the first item's content is a *nested*
        // list, and the inner loop below has already claimed it; only one
        // shallower than that is a sibling.
        val marker = markerAt(lines[index])?.takeIf { it.indent < first.contentIndent } ?: break
        // A different kind of marker starts a different list, as in CommonMark.
        if (marker.ordered != first.ordered) break
        val body = mutableListOf(
            lines[index].substring(marker.contentOffset.coerceAtMost(lines[index].length))
        )
        index++
        var pendingBlanks = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                pendingBlanks++
                index++
                continue
            }
            val continues = line.indentWidth() >= marker.contentIndent
            val nextMarker = markerAt(line)?.takeIf { it.indent < first.contentIndent }
            if (!continues && nextMarker == null) break
            if (pendingBlanks > 0) {
                // A blank anywhere inside a list makes the whole list loose,
                // which is the only thing looseness is used for here.
                loose = true
                if (!continues) break
                repeat(pendingBlanks) { body.add("") }
                pendingBlanks = 0
            }
            if (!continues) break
            body.add(line.dropIndent(marker.contentIndent))
            index++
        }
        // Blanks after the last item belong to the document, not to the item.
        counter++
        var checked: Boolean? = null
        val head = body.firstOrNull().orEmpty()
        if (head.length >= 3 && head[0] == '[' && head[2] == ']' &&
            (head[1] == ' ' || head[1].lowercaseChar() == 'x')
        ) {
            checked = head[1].lowercaseChar() == 'x'
            body[0] = head.drop(3).removePrefix(" ")
        }
        val label = if (first.ordered) {
            "${first.text.dropLast(1).toIntOrNull()?.plus(counter - 1) ?: counter}."
        } else {
            "•"
        }
        items.add(ListItem(label, checked, parseBlocks(body, links, depth + 1)))
    }
    blocks.add(MarkdownBlock.Bullets(first.ordered, tight = !loose, items = items))
    return index
}

/**
 * How many columns a table has to draw.
 *
 * The rows are consulted, not just the header: a hand-maintained table grows a
 * column at the bottom first, and a row wider than its header should come out
 * ragged rather than have its last cells silently dropped.
 */
internal fun MarkdownBlock.Table.columnCount(): Int =
    maxOf(alignments.size, header.size, rows.maxOfOrNull { it.size } ?: 0)

/** The alignments a delimiter row declares, or null if it is not one. */
internal fun tableAlignments(line: String): List<ColumnAlignment>? {
    // The pipe is required: without it a plain `---` would read as a
    // one-column delimiter row and swallow every thematic break in the file.
    if ('|' !in line) return null
    val cells = splitTableRow(line)
    if (cells.isEmpty()) return null
    val alignments = cells.map { cell ->
        val text = cell.trim()
        if (text.isEmpty() || !text.all { it == '-' || it == ':' }) return null
        if (text.count { it == '-' } == 0) return null
        when {
            text.startsWith(':') && text.endsWith(':') -> ColumnAlignment.Center
            text.endsWith(':') -> ColumnAlignment.End
            else -> ColumnAlignment.Start
        }
    }
    return alignments
}

/** Split a table row on unescaped pipes, dropping the outer ones. */
internal fun splitTableRow(line: String): List<String> {
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var index = 0
    val body = line.trim()
    while (index < body.length) {
        val char = body[index]
        if (char == '\\' && index + 1 < body.length) {
            current.append(char).append(body[index + 1])
            index += 2
            continue
        }
        if (char == '|') {
            cells.add(current.toString())
            current.clear()
            index++
            continue
        }
        current.append(char)
        index++
    }
    cells.add(current.toString())
    if (cells.isNotEmpty() && cells.first().isBlank()) cells.removeAt(0)
    if (cells.isNotEmpty() && cells.last().isBlank()) cells.removeAt(cells.size - 1)
    return cells
}

private fun readTable(
    lines: List<String>,
    start: Int,
    links: Map<String, String>,
    blocks: MutableList<MarkdownBlock>,
): Int {
    val alignments = tableAlignments(lines[start + 1])!!
    val header = splitTableRow(lines[start]).map { parseInline(it.trim(), links) }
    val rows = mutableListOf<List<List<InlineSpan>>>()
    var index = start + 2
    while (index < lines.size && lines[index].isNotBlank() && '|' in lines[index]) {
        rows.add(splitTableRow(lines[index]).map { parseInline(it.trim(), links) })
        index++
    }
    blocks.add(MarkdownBlock.Table(header, alignments, rows))
    return index
}

private fun readParagraph(
    lines: List<String>,
    start: Int,
    links: Map<String, String>,
    blocks: MutableList<MarkdownBlock>,
): Int {
    val text = StringBuilder()
    var index = start
    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) break
        if (index > start) {
            // A setext underline turns everything gathered so far into a
            // heading. It is checked before the block starters below because
            // `---` is also a thematic break, and CommonMark gives the
            // heading precedence while a paragraph is open.
            val underline = setextLevel(line)
            if (underline != null) {
                blocks.add(MarkdownBlock.Heading(underline, parseInline(text.toString(), links)))
                return index + 1
            }
            if (fenceAt(line) != null || atxHeadingAt(line) != null || isThematicBreak(line) ||
                markerAt(line) != null || line.trimStart().startsWith('>') ||
                displayMathEnd(lines, index) != null
            ) {
                break
            }
            // GitHub lets a table's header row be the line that would otherwise
            // have continued the paragraph, so the delimiter row underneath it
            // ends the paragraph here.
            if (index + 1 < lines.size && '|' in line && tableAlignments(lines[index + 1]) != null) {
                break
            }
        }
        if (text.isNotEmpty()) text.append(if (endsWithHardBreak(lines[index - 1])) '\n' else ' ')
        text.append(line.trim().let { if (endsWithHardBreak(line)) it.trimEnd('\\') else it })
        index++
    }
    blocks.add(MarkdownBlock.Paragraph(parseInline(text.toString(), links)))
    return index
}

private fun setextLevel(line: String): Int? {
    if (line.indentWidth() >= CODE_INDENT) return null
    val body = line.trim()
    if (body.isEmpty()) return null
    return when {
        body.all { it == '=' } -> 1
        body.all { it == '-' } -> 2
        else -> null
    }
}

/** Markdown's two hard breaks: two trailing spaces, or a trailing backslash. */
private fun endsWithHardBreak(line: String): Boolean =
    line.endsWith("  ") || (line.endsWith("\\") && !line.endsWith("\\\\"))

// ---- Link reference definitions ------------------------------------------

/** `[label]: destination "title"` → label (folded to lower case) → destination. */
private fun linkDefinitionOf(line: String): Pair<String, String>? {
    if (line.indentWidth() >= CODE_INDENT) return null
    val body = line.trimStart()
    if (!body.startsWith('[')) return null
    val close = body.indexOf("]:")
    if (close <= 1) return null
    val label = body.substring(1, close)
    if ('[' in label) return null
    val rest = body.substring(close + 2).trim()
    if (rest.isEmpty()) return null
    val destination = rest.substringBefore(' ').trim('<', '>')
    if (destination.isEmpty()) return null
    return label.lowercase() to destination
}

private fun collectLinkDefinitions(lines: List<String>): Map<String, String> {
    val definitions = mutableMapOf<String, String>()
    var fence: Pair<Char, Int>? = null
    for (line in lines) {
        val open = fence
        if (open != null) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && trimmed.all { it == open.first } &&
                trimmed.length >= open.second
            ) {
                fence = null
            }
            continue
        }
        val opened = fenceAt(line)
        if (opened != null) {
            fence = opened
            continue
        }
        linkDefinitionOf(line)?.let { (label, destination) ->
            definitions.putIfAbsent(label, destination)
        }
    }
    return definitions
}

// ---- Inline level --------------------------------------------------------

/**
 * Read one paragraph's worth of text into styled runs.
 *
 * Written as a left-to-right scan with a recursive call per delimiter rather
 * than CommonMark's delimiter stack: the stack exists to resolve pathological
 * nesting (`*foo**bar*`), which no README contains, and the scan is a tenth of
 * the code. Where a delimiter has no partner the characters stay literal,
 * which is the same answer the stack would give for the cases that matter.
 */
internal fun parseInline(text: String, links: Map<String, String>): List<InlineSpan> {
    if (text.length > MAX_INLINE_CHARS) return listOf(InlineSpan(text))
    val out = mutableListOf<InlineSpan>()
    scanInline(text, emptySet(), null, links, out, depth = 0)
    return out
}

private fun scanInline(
    text: String,
    styles: Set<InlineStyle>,
    link: String?,
    links: Map<String, String>,
    out: MutableList<InlineSpan>,
    depth: Int,
) {
    if (depth >= MAX_INLINE_DEPTH) {
        // As at block level: the text survives, it just stops being read for
        // markup. See MAX_INLINE_DEPTH.
        if (text.isNotEmpty()) out.add(InlineSpan(text, styles, link))
        return
    }
    val plain = StringBuilder()
    fun flush() {
        if (plain.isEmpty()) return
        out.add(InlineSpan(plain.toString(), styles, link))
        plain.clear()
    }

    var index = 0
    while (index < text.length) {
        val char = text[index]
        when {
            char == '\\' && index + 1 < text.length && !text[index + 1].isLetterOrDigit() -> {
                plain.append(text[index + 1])
                index += 2
            }
            char == '`' -> {
                val run = text.countRun('`', index)
                val close = text.indexOfRun('`', run, index + run)
                if (close < 0) {
                    plain.append(text, index, index + run)
                    index += run
                } else {
                    flush()
                    // One space either side is the fence that lets a code span
                    // hold a backtick; it is not content.
                    val code = text.substring(index + run, close)
                    out.add(
                        InlineSpan(
                            if (code.startsWith(' ') && code.endsWith(' ') && code.isNotBlank()) {
                                code.substring(1, code.length - 1)
                            } else {
                                code
                            },
                            styles + InlineStyle.Code,
                            link,
                        )
                    )
                    index = close + run
                }
            }
            char == '!' && index + 1 < text.length && text[index + 1] == '[' -> {
                val image = readLink(text, index + 1, links)
                if (image == null) {
                    plain.append(char)
                    index++
                } else {
                    flush()
                    // An enclosing link wins over the image's own src. A badge
                    // is written `[![alt](badge.svg)](the-job)` and it is the
                    // job a tap means; taking the src regardless sent every
                    // shields.io badge to its own SVG, and a repo-relative src
                    // opened a PNG as a text buffer.
                    out.add(
                        InlineSpan(
                            image.label,
                            styles,
                            link ?: image.destination.ifEmpty { null },
                            isImage = true,
                            imageSource = image.destination.ifEmpty { null },
                        )
                    )
                    index = image.end
                }
            }
            char == '[' -> {
                val found = readLink(text, index, links)
                if (found == null) {
                    plain.append(char)
                    index++
                } else {
                    flush()
                    // Both halves of a link may be empty, and neither is worth
                    // showing as syntax: `[text]()` is its text, and `[](url)`
                    // has nowhere to put its label, so the destination stands
                    // in for it rather than the link vanishing without trace.
                    val destination = found.destination.ifEmpty { null }
                    val label = found.label.ifBlank { found.destination }
                    scanInline(label, styles, destination ?: link, links, out, depth + 1)
                    index = found.end
                }
            }
            char == '<' -> {
                val close = text.indexOf('>', index).takeIf { it <= index + INLINE_SCAN_WINDOW }
                    ?: -1
                val inner = if (close < 0) "" else text.substring(index + 1, close)
                when {
                    close < 0 -> {
                        plain.append(char)
                        index++
                    }
                    inner.startsWith("http://") || inner.startsWith("https://") ||
                        inner.startsWith("mailto:") -> {
                        flush()
                        out.add(InlineSpan(inner.removePrefix("mailto:"), styles, inner))
                        index = close + 1
                    }
                    // Raw HTML. A README's `<p align="center">` and `<img>` are
                    // markup we cannot draw, and printing the tag is worse than
                    // dropping it; `<br>` is the one that carries meaning.
                    inner.isHtmlTag() -> {
                        if (inner.trim().trimEnd('/').equals("br", ignoreCase = true)) {
                            plain.append('\n')
                        }
                        index = close + 1
                    }
                    else -> {
                        plain.append(char)
                        index++
                    }
                }
            }
            char == '$' -> {
                val math = inlineMathAt(text, index)
                if (math == null) {
                    plain.append(char)
                    index++
                } else {
                    flush()
                    out.add(InlineSpan(math.first, styles + InlineStyle.Math, link))
                    index = math.second
                }
            }
            text.startsWith("~~", index) -> {
                val close = text.indexOfDelimiter("~~", index + 2)
                if (close < 0) {
                    plain.append("~~")
                    index += 2
                } else {
                    flush()
                    scanInline(
                        text.substring(index + 2, close),
                        styles + InlineStyle.Strikethrough,
                        link,
                        links,
                        out,
                        depth + 1,
                    )
                    index = close + 2
                }
            }
            char == '*' || char == '_' -> {
                val run = text.countRun(char, index).coerceAtMost(2)
                val close = if (text.canOpenEmphasis(index, run, char)) {
                    text.indexOfCloser(run, index + run, char)
                } else {
                    -1
                }
                if (close < 0) {
                    plain.append(text, index, index + run)
                    index += run
                } else {
                    flush()
                    val style = if (run == 2) InlineStyle.Bold else InlineStyle.Italic
                    scanInline(
                        text.substring(index + run, close),
                        styles + style,
                        link,
                        links,
                        out,
                        depth + 1,
                    )
                    index = close + run
                }
            }
            // GitHub's bare autolink. Only at a word boundary, or the `http`
            // inside a longer word would start one.
            (char == 'h') && (index == 0 || !text[index - 1].isLetterOrDigit()) &&
                (text.startsWith("http://", index) || text.startsWith("https://", index)) -> {
                var end = index
                while (end < text.length && !text[end].isWhitespace() && text[end] != '<') end++
                // Trailing punctuation belongs to the sentence, not the URL.
                while (end > index && text[end - 1] in ".,;:!?)]") end--
                flush()
                val url = text.substring(index, end)
                out.add(InlineSpan(url, styles, url))
                index = end
            }
            else -> {
                plain.append(char)
                index++
            }
        }
    }
    flush()
}

/**
 * The TeX of the `$…$` (or `$$…$$`) run starting at [at], and the index just
 * past its closing delimiter — or null when this `$` opens nothing.
 *
 * The rules are the ones GitHub's renderer uses, and each of them exists to
 * keep a price out of a formula: the opener may not be followed by a space,
 * the closer may not be preceded by one, and a single-`$` closer may not be
 * followed by a digit. So `$5 and $6 each` stays money, `$x + y$` becomes
 * math, and neither of them is read as emphasis on the way through.
 */
private fun inlineMathAt(text: String, at: Int): Pair<String, Int>? {
    val run = text.countRun('$', at).coerceAtMost(2)
    val open = at + run
    val first = text.getOrNull(open) ?: return null
    if (first.isWhitespace() || first == '$') return null
    val stop = minOf(text.length, open + INLINE_SCAN_WINDOW)
    var index = open
    while (index < stop) {
        if (text[index] == '\\') {
            index += 2
            continue
        }
        if (text[index] != '$') {
            index++
            continue
        }
        val closing = text.countRun('$', index)
        if (closing < run) {
            index += closing
            continue
        }
        val before = text[index - 1]
        val after = text.getOrNull(index + run)
        if (!before.isWhitespace() && (run == 2 || after == null || !after.isDigit())) {
            return text.substring(open, index) to index + run
        }
        index += closing
    }
    return null
}

/** A resolved `[label](dest)`, `[label][ref]` or `[ref]`. */
private class FoundLink(val label: String, val destination: String, val end: Int)

private fun readLink(text: String, start: Int, links: Map<String, String>): FoundLink? {
    val labelEnd = text.matchingBracket(start) ?: return null
    val label = text.substring(start + 1, labelEnd)
    var index = labelEnd + 1
    if (index < text.length && text[index] == '(') {
        val close = text.matchingParen(index) ?: return null
        val inside = text.substring(index + 1, close).trim()
        // `(url "title")` — the title is not something we draw.
        // An empty destination is still a link's *shape*, and it is the caller
        // that decides what to do with one — refusing here printed `[text]()`
        // back at the reader verbatim, syntax and all.
        val destination = inside.substringBefore(' ').trim('<', '>')
        return FoundLink(label, destination, close + 1)
    }
    if (index < text.length && text[index] == '[') {
        val close = text.matchingBracket(index) ?: return null
        val reference = text.substring(index + 1, close).ifBlank { label }
        val destination = links[reference.lowercase()] ?: return null
        return FoundLink(label, destination, close + 1)
    }
    // Shortcut reference: `[ref]` on its own.
    val destination = links[label.lowercase()] ?: return null
    return FoundLink(label, destination, index)
}

/**
 * A run of inline content that is drawn as one thing.
 *
 * A paragraph is one styled string right up until it holds a picture, which
 * Compose cannot put inside a `Text` without knowing its size first. So the
 * runs are cut into stretches of text and stretches of image, and the renderer
 * stacks them: a badge row stays a row, a screenshot on its own gets the
 * paragraph's whole width, and the prose either side of it is still one
 * wrapping string.
 */
sealed interface InlineSegment {
    class Prose(val spans: List<InlineSpan>) : InlineSegment

    class Images(val spans: List<InlineSpan>) : InlineSegment
}

/**
 * Cut [spans] into text and image stretches. [isDrawable] decides which image
 * runs are worth their own element — one whose source cannot be resolved
 * stays in the prose as its alt text, exactly as it did before pictures were
 * drawn at all.
 *
 * Whitespace *between* two drawable images is dropped rather than opening a
 * paragraph of its own: `![a](1.png) ![b](2.png)` is a badge row, and the
 * space between the badges is not a line of prose.
 */
internal fun inlineSegments(
    spans: List<InlineSpan>,
    isDrawable: (InlineSpan) -> Boolean,
): List<InlineSegment> {
    val out = mutableListOf<InlineSegment>()
    val prose = mutableListOf<InlineSpan>()
    val images = mutableListOf<InlineSpan>()
    fun flushProse() {
        if (prose.isEmpty()) return
        out.add(InlineSegment.Prose(prose.toList()))
        prose.clear()
    }
    fun flushImages() {
        if (images.isEmpty()) return
        out.add(InlineSegment.Images(images.toList()))
        images.clear()
    }
    for (span in spans) {
        when {
            span.isImage && isDrawable(span) -> {
                flushProse()
                images.add(span)
            }
            span.text.isBlank() && images.isNotEmpty() -> Unit
            else -> {
                flushImages()
                prose.add(span)
            }
        }
    }
    flushImages()
    flushProse()
    return out
}

// ---- Source ranges and anchors ---------------------------------------------

/**
 * The index of the top-level block that owns source line [line].
 *
 * "Owns" the way Zed's `sync_preview_to_source_index` means it: the block
 * whose range covers the line, and otherwise the last one that starts before
 * it — the blank lines between two blocks belong to the one above, so a
 * reader scrolling through whitespace does not see the preview snap forward.
 * A binary search, because this runs on every scroll frame of a document that
 * may have thousands of blocks.
 */
internal fun blockAtSourceLine(ranges: List<IntRange>, line: Int): Int {
    if (ranges.isEmpty()) return 0
    var low = 0
    var high = ranges.size - 1
    var best = 0
    while (low <= high) {
        val mid = (low + high) / 2
        if (ranges[mid].first <= line) {
            best = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best
}

/** The first source line of block [index], or 0 when there is no such block. */
internal fun sourceLineOfBlock(ranges: List<IntRange>, index: Int): Int =
    ranges.getOrNull(index)?.first ?: 0

/** An inline run's text with its markup gone — what a heading's slug is made of. */
internal fun List<InlineSpan>.plainText(): String = joinToString("") { it.text }

/**
 * GitHub's heading anchor for [text].
 *
 * Their algorithm, as `github-slugger` implements it: fold to lower case,
 * drop everything that is not a letter, a digit, a space, a hyphen or an
 * underscore, then turn the spaces into hyphens. It is not a spec, it is what
 * every `[jump](#the-heading)` in every README was written against.
 */
internal fun headingSlug(text: String): String =
    text.trim()
        .lowercase()
        .filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
        .replace(' ', '-')

/**
 * The block a `#anchor` link points at, or null when no heading answers to it.
 *
 * Left null rather than guessed: a `#` link that names nothing is a broken
 * link in the document, and scrolling somewhere arbitrary would hide that.
 */
internal fun anchorBlockIndex(blocks: List<MarkdownBlock>, anchor: String): Int? {
    val slug = headingSlug(anchor.removePrefix("#").replace("%20", " "))
    if (slug.isEmpty()) return null
    val index = blocks.indexOfFirst {
        it is MarkdownBlock.Heading && headingSlug(it.content.plainText()) == slug
    }
    return index.takeIf { it >= 0 }
}

// ---- Relative links -------------------------------------------------------

/**
 * A link's target as a project-relative path, resolved against the previewed
 * file's own directory.
 *
 * `..` is followed, and a walk that would climb above the project root is
 * clamped rather than allowed to escape: whatever comes back is opened inside
 * the project, and `../../../etc/passwd` in a README somebody cloned must not
 * be a way out of it.
 */
internal fun resolveRelativePath(from: String, target: String): String {
    val cleaned = target.substringBefore('#').substringBefore('?')
    val base = if (cleaned.startsWith('/')) emptyList() else from.split('/').dropLast(1)
    val parts = ArrayList(base)
    for (segment in cleaned.trimStart('/').split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts.add(segment)
        }
    }
    return parts.joinToString("/")
}

/**
 * The file a relative link should open, or null when it does not name one.
 *
 * `[the docs](docs/)` and `[up](../)` are ordinary in a README and neither is
 * a file: handing either to `openFile` opens a *directory* as a text buffer.
 * A link that names nothing openable is better left inert than followed into
 * something the editor cannot show.
 */
internal fun relativeLinkTarget(from: String, target: String): String? {
    val cleaned = target.substringBefore('#').substringBefore('?')
    if (cleaned.isEmpty() || cleaned.endsWith('/')) return null
    val last = cleaned.substringAfterLast('/')
    if (last == "." || last == "..") return null
    return resolveRelativePath(from, target).ifEmpty { null }
}

// ---- Small string helpers -------------------------------------------------

/** Leading whitespace in columns, counting a tab as four. */
internal fun String.indentWidth(): Int {
    var width = 0
    for (char in this) {
        when (char) {
            ' ' -> width++
            '\t' -> width += CODE_INDENT
            else -> return width
        }
    }
    return width
}

/** Drop up to [columns] of leading indent, keeping anything past it. */
private fun String.dropIndent(columns: Int): String {
    var width = 0
    var index = 0
    while (index < length && width < columns) {
        when (this[index]) {
            ' ' -> width++
            '\t' -> width += CODE_INDENT
            else -> break
        }
        index++
    }
    return substring(index)
}

private fun String.countRun(char: Char, from: Int): Int {
    var index = from
    while (index < length && this[index] == char) index++
    return index - from
}

/**
 * The next run of exactly [run] [char]s at or after [from], within
 * [INLINE_SCAN_WINDOW] characters of it.
 */
private fun String.indexOfRun(char: Char, run: Int, from: Int): Int {
    val stop = minOf(length, from + INLINE_SCAN_WINDOW)
    var index = from
    while (index < stop) {
        if (this[index] != char) {
            index++
            continue
        }
        val size = countRun(char, index)
        if (size == run) return index
        index += size
    }
    return -1
}

/**
 * The next [delimiter] not inside a code span and not escaped, within
 * [INLINE_SCAN_WINDOW] characters of [from].
 */
private fun String.indexOfDelimiter(delimiter: String, from: Int): Int {
    val stop = minOf(length, from + INLINE_SCAN_WINDOW)
    var index = from
    while (index < stop) {
        when {
            this[index] == '\\' -> index += 2
            this[index] == '`' -> {
                val run = countRun('`', index)
                val close = indexOfRun('`', run, index + run)
                index = if (close < 0) index + run else close + run
            }
            startsWith(delimiter, index) -> return index
            else -> index++
        }
    }
    return -1
}

/**
 * Where an emphasis run of [length] [char]s opened just before [from] closes.
 *
 * Two rules, and both earn their place in a README. A closer may not follow
 * whitespace, so `2 * 3 * 4` is arithmetic rather than emphasis. And a run
 * *longer* than the one being closed closes from its end, so the `***` that
 * ends `**bold *and italic***` gives its first star to the italic and the
 * other two to the bold — matching from the front instead leaves a stray
 * asterisk and un-italicises the middle.
 *
 * Code spans are stepped over: a `*` inside backticks closes nothing.
 *
 * The search stops after [INLINE_SCAN_WINDOW] characters, which is what keeps
 * a paragraph full of openers with no partners from costing O(k·n) — see that
 * constant.
 */
private fun String.indexOfCloser(length: Int, from: Int, char: Char): Int {
    val stop = minOf(this.length, from + INLINE_SCAN_WINDOW)
    var index = from
    while (index < stop) {
        if (this[index] == '\\') {
            index += 2
            continue
        }
        if (this[index] == '`') {
            val ticks = countRun('`', index)
            val close = indexOfRun('`', ticks, index + ticks)
            index = if (close < 0) index + ticks else close + ticks
            continue
        }
        if (this[index] != char) {
            index++
            continue
        }
        val run = countRun(char, index)
        if (run >= length) {
            val at = index + run - length
            val before = getOrNull(index - 1)
            val after = getOrNull(index + run)
            val wordSafe = char != '_' || after == null || !after.isLetterOrDigit()
            if (at > from && before != null && !before.isWhitespace() && wordSafe) return at
        }
        index += run
    }
    return -1
}

/** Whether an emphasis run at [at] can open: it must not be followed by space. */
private fun String.canOpenEmphasis(at: Int, run: Int, char: Char): Boolean {
    val after = getOrNull(at + run) ?: return false
    if (after.isWhitespace()) return false
    if (char != '_') return true
    val before = getOrNull(at - 1)
    return before == null || !before.isLetterOrDigit()
}

/** Bounded like the delimiter searches above: a link label is not a chapter. */
private fun String.matchingBracket(open: Int): Int? {
    val stop = minOf(length, open + INLINE_SCAN_WINDOW)
    var depth = 0
    var index = open
    while (index < stop) {
        when {
            this[index] == '\\' -> index++
            this[index] == '[' -> depth++
            this[index] == ']' -> {
                depth--
                if (depth == 0) return index
            }
        }
        index++
    }
    return null
}

private fun String.matchingParen(open: Int): Int? {
    val stop = minOf(length, open + INLINE_SCAN_WINDOW)
    var depth = 0
    var index = open
    while (index < stop) {
        when {
            this[index] == '\\' -> index++
            this[index] == '(' -> depth++
            this[index] == ')' -> {
                depth--
                if (depth == 0) return index
            }
        }
        index++
    }
    return null
}

/** Whether `<…>` holds something that is plausibly an HTML tag. */
private fun String.isHtmlTag(): Boolean {
    val head = firstOrNull() ?: return false
    if (!head.isLetter() && head != '/' && head != '!') return false
    return trimStart('/', '!').takeWhile { it.isLetterOrDigit() }.isNotEmpty()
}
