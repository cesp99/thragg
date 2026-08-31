package to.eyed.seeker.code.ui.common

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import to.eyed.seeker.code.ui.theme.BufferFontFamily
import to.eyed.seeker.code.ui.theme.LocalAppSettings
import to.eyed.seeker.code.ui.theme.LocalUiFontSize
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.UiFontFamily
import to.eyed.seeker.code.ui.theme.ZedTheme

/**
 * Markdown as an *agent* writes it: prose, code, links and lists.
 *
 * This is the inline half of `ui/preview/MarkdownPreview.kt` + the reader in
 * `ui/preview/MarkdownDocument.kt`, carried here so the agent panel no longer
 * depends on a file-preview subsystem it never wanted. The preview is
 * buffer-bound — it follows an `EditorState`, polls the engine's version,
 * syncs its scroll to the source, highlights each fence through the engine and
 * draws mermaid, SVG and remote images. An agent's reply is none of that: it
 * is a string that grows, and what it contains is a paragraph, a fence, a
 * bullet and a link. So what came across is the reader and the renderer for
 * exactly those, and what stayed behind is the machinery that only a *file*
 * needs. Zed makes the same split — an agent's reply is a `Markdown` element
 * with the editor's own style (agent_ui renders `Entity<Markdown>` per chunk),
 * not a preview view.
 *
 * Two deliberate differences from the preview it came from, both noted where
 * they bite: a fence is not syntax-highlighted (the highlighter is an engine
 * parse per fence and it goes with `ui/preview`), and a table is read as the
 * prose it is written from rather than laid out — a three-column table has
 * nowhere to go on a 400dp portrait column anyway.
 *
 * The reader is pure Kotlin with no Android or Compose types in it, which is
 * what lets all of it be tested on the host. It is *not* a conforming
 * CommonMark parser, and the places it knowingly differs are marked at the
 * code that makes each choice.
 */

// ---- The model -------------------------------------------------------------

/** What an inline run is wearing. A run may wear several at once. */
internal enum class MarkdownStyle {
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
     * and an underscore — and that the renderer can set it apart from prose.
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
internal data class MarkdownSpan(
    val text: String,
    val styles: Set<MarkdownStyle> = emptySet(),
    /** Destination of the link this run belongs to, or null. */
    val link: String? = null,
    /**
     * True when the run stands in for an image, and [text] is its alt text.
     * Nothing here fetches anything: a picture in an agent's reply is *named*,
     * not drawn — see [buildInline]. The preview's `imageSource` is gone with
     * it, because drawing is what that field was for.
     */
    val isImage: Boolean = false,
)

/** One entry of a bullet or ordered list. */
internal data class MarkdownItem(
    /** What the renderer draws in the margin: `•`, `1.`, `2.`… */
    val marker: String,
    /** Checked state for a GitHub task item, or null when it is not one. */
    val checked: Boolean?,
    val blocks: List<MarkdownNode>,
)

/** A block of an agent's message. Deliberately fewer kinds than the preview's. */
internal sealed interface MarkdownNode {
    data class Heading(val level: Int, val content: List<MarkdownSpan>) : MarkdownNode

    data class Paragraph(val content: List<MarkdownSpan>) : MarkdownNode

    /** A fenced or indented code block. [language] is the fence's info word. */
    data class Code(val language: String?, val code: String) : MarkdownNode

    /**
     * A block quote. [kind] is GitHub's alert marker — `NOTE`, `TIP`,
     * `IMPORTANT`, `WARNING`, `CAUTION` — when the quote opens with one.
     */
    data class Quote(val kind: String?, val blocks: List<MarkdownNode>) : MarkdownNode

    /**
     * A list. [tight] is CommonMark's looseness: a list whose items are
     * separated by blank lines gets paragraph spacing, a tight one does not.
     */
    data class Bullets(
        val ordered: Boolean,
        val tight: Boolean,
        val items: List<MarkdownItem>,
    ) : MarkdownNode

    data object Rule : MarkdownNode
}

/** GitHub's alert kinds, as they may appear after `>` on a quote's first line. */
private val ALERT_KINDS = setOf("NOTE", "TIP", "IMPORTANT", "WARNING", "CAUTION")

/** CommonMark's limit: four spaces of indent is a code block, not a marker. */
private const val CODE_INDENT = 4

/*
 * The bounds below all exist for one reason, and it is worth stating once:
 * this reader's input is a string an agent produced, so every loop over it is
 * a loop over something no human checked — a tool result pasted whole, a
 * 200 KB log line, a file the model echoed back. Nothing here may recurse as
 * deep as the text says, and nothing here may spend time quadratic in a length
 * the text picks: a StackOverflowError or a half-minute stall on the parse
 * thread is process death, and process death takes every unsaved buffer with
 * it.
 */

/**
 * How deep block nesting is followed before the rest is shown as plain text.
 *
 * A quote and a list item are both read by recursing on their stripped body,
 * so nesting depth *is* stack depth: a line of 1500 `>` characters overflows a
 * worker thread's stack. Nothing legible nests thirty-two deep.
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
 * closer — at 3.7 seconds for 209 KB.
 */
private const val INLINE_SCAN_WINDOW = 1024

/**
 * Past this, a run of text is drawn as it stands with no markup read out of
 * it. [readParagraph] joins every consecutive non-blank line, so a "paragraph"
 * is as long as the text's longest unbroken run of them — prose does not do
 * this, and generated output does.
 */
private const val MAX_INLINE_CHARS = 32 * 1024

// ---- Block level -----------------------------------------------------------

/**
 * Read [source] into blocks.
 *
 * The link reference definitions are collected first, over the whole string,
 * because `[text][ref]` may name a `[ref]:` line further down.
 *
 * No source ranges: the preview records a line range per block because it
 * scroll-syncs to a buffer, and an agent's message has no buffer behind it.
 */
internal fun parseMarkdownText(source: String): List<MarkdownNode> {
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    return parseBlocks(lines, collectLinkDefinitions(lines), depth = 0)
}

private fun parseBlocks(
    lines: List<String>,
    links: Map<String, String>,
    depth: Int,
): List<MarkdownNode> {
    if (depth >= MAX_BLOCK_DEPTH) {
        // Shown rather than descended into: the text is still all there, it
        // simply stops being given structure. See MAX_BLOCK_DEPTH.
        val rest = lines.joinToString("\n").trim()
        return if (rest.isEmpty()) {
            emptyList()
        } else {
            listOf(MarkdownNode.Paragraph(listOf(MarkdownSpan(rest))))
        }
    }
    val blocks = mutableListOf<MarkdownNode>()
    var index = 0
    while (index < lines.size) {
        index = readBlock(lines, index, links, blocks, depth)
    }
    return blocks
}

/**
 * One block, from [index], appended to [blocks]; returns the line the reader
 * stopped at.
 */
private fun readBlock(
    lines: List<String>,
    index: Int,
    links: Map<String, String>,
    blocks: MutableList<MarkdownNode>,
    depth: Int,
): Int {
    val line = lines[index]
    if (line.isBlank()) return index + 1
    val indent = line.indentWidth()

    val fence = fenceAt(line)
    if (fence != null) return readFencedCode(lines, index, fence, blocks)
    // Indented code, but only where a paragraph cannot be continuing: a
    // wrapped paragraph line indented by four spaces is still that paragraph,
    // and treating it as code is the classic misread.
    if (indent >= CODE_INDENT && blocks.lastOrNull() !is MarkdownNode.Paragraph) {
        return readIndentedCode(lines, index, blocks)
    }
    if (linkDefinitionOf(line) != null) return index + 1
    val heading = atxHeadingAt(line)
    if (heading != null) {
        blocks.add(MarkdownNode.Heading(heading.first, parseInlineText(heading.second, links)))
        return index + 1
    }
    if (isThematicBreak(line)) {
        blocks.add(MarkdownNode.Rule)
        return index + 1
    }
    if (indent < CODE_INDENT && line.trimStart().startsWith('>')) {
        return readQuote(lines, index, links, blocks, depth)
    }
    if (markerAt(line) != null) return readList(lines, index, links, blocks, depth)
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
    blocks: MutableList<MarkdownNode>,
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
    blocks.add(MarkdownNode.Code(info, body.joinToString("\n")))
    return index
}

private fun readIndentedCode(
    lines: List<String>,
    start: Int,
    blocks: MutableList<MarkdownNode>,
): Int {
    val body = mutableListOf<String>()
    var index = start
    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) {
            // A blank line only stays in the block if code follows it. Scanned
            // by index rather than with `drop`, which would copy the tail of
            // the message once per blank line.
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
    blocks.add(MarkdownNode.Code(null, body.joinToString("\n")))
    return index
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
    blocks: MutableList<MarkdownNode>,
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
        // under a quote is the message's own code block, and swallowing it
        // draws it inside the quote's accent bar.
        if (line.isNotBlank() && body.lastOrNull()?.isNotBlank() == true &&
            !isThematicBreak(line) && atxHeadingAt(line) == null && markerAt(line) == null &&
            fenceAt(line) == null
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
    blocks.add(MarkdownNode.Quote(kind, parseBlocks(body, links, depth + 1)))
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
    blocks: MutableList<MarkdownNode>,
    depth: Int,
): Int {
    val first = markerAt(lines[start])!!
    val items = mutableListOf<MarkdownItem>()
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
        // Blanks after the last item belong to the message, not to the item.
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
        items.add(MarkdownItem(label, checked, parseBlocks(body, links, depth + 1)))
    }
    blocks.add(MarkdownNode.Bullets(first.ordered, tight = !loose, items = items))
    return index
}

private fun readParagraph(
    lines: List<String>,
    start: Int,
    links: Map<String, String>,
    blocks: MutableList<MarkdownNode>,
): Int {
    val text = StringBuilder()
    var index = start
    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) break
        if (index > start) {
            // A setext underline turns everything gathered so far into a
            // heading. It is checked before the block starters below because
            // `---` is also a thematic break, and CommonMark gives the heading
            // precedence while a paragraph is open.
            val underline = setextLevel(line)
            if (underline != null) {
                blocks.add(MarkdownNode.Heading(underline, parseInlineText(text.toString(), links)))
                return index + 1
            }
            if (fenceAt(line) != null || atxHeadingAt(line) != null || isThematicBreak(line) ||
                markerAt(line) != null || line.trimStart().startsWith('>')
            ) {
                break
            }
        }
        if (text.isNotEmpty()) text.append(if (endsWithHardBreak(lines[index - 1])) '\n' else ' ')
        text.append(line.trim().let { if (endsWithHardBreak(line)) it.trimEnd('\\') else it })
        index++
    }
    blocks.add(MarkdownNode.Paragraph(parseInlineText(text.toString(), links)))
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

// ---- Link reference definitions --------------------------------------------

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

// ---- Inline level ----------------------------------------------------------

/**
 * Read one paragraph's worth of text into styled runs.
 *
 * Written as a left-to-right scan with a recursive call per delimiter rather
 * than CommonMark's delimiter stack: the stack exists to resolve pathological
 * nesting (`*foo**bar*`), which no agent writes, and the scan is a tenth of
 * the code. Where a delimiter has no partner the characters stay literal,
 * which is the same answer the stack would give for the cases that matter.
 */
internal fun parseInlineText(text: String, links: Map<String, String>): List<MarkdownSpan> {
    if (text.length > MAX_INLINE_CHARS) return listOf(MarkdownSpan(text))
    val out = mutableListOf<MarkdownSpan>()
    scanInline(text, emptySet(), null, links, out, depth = 0)
    return out
}

private fun scanInline(
    text: String,
    styles: Set<MarkdownStyle>,
    link: String?,
    links: Map<String, String>,
    out: MutableList<MarkdownSpan>,
    depth: Int,
) {
    if (depth >= MAX_INLINE_DEPTH) {
        // As at block level: the text survives, it just stops being read for
        // markup. See MAX_INLINE_DEPTH.
        if (text.isNotEmpty()) out.add(MarkdownSpan(text, styles, link))
        return
    }
    val plain = StringBuilder()
    fun flush() {
        if (plain.isEmpty()) return
        out.add(MarkdownSpan(plain.toString(), styles, link))
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
                        MarkdownSpan(
                            if (code.startsWith(' ') && code.endsWith(' ') && code.isNotBlank()) {
                                code.substring(1, code.length - 1)
                            } else {
                                code
                            },
                            styles + MarkdownStyle.Code,
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
                    // An enclosing link wins over the image's own src: a badge
                    // is written `[![alt](badge.svg)](the-job)` and it is the
                    // job a tap means.
                    out.add(
                        MarkdownSpan(
                            image.label,
                            styles,
                            link ?: image.destination.ifEmpty { null },
                            isImage = true,
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
                        out.add(MarkdownSpan(inner.removePrefix("mailto:"), styles, inner))
                        index = close + 1
                    }
                    // Raw HTML. Markup we cannot draw, and printing the tag is
                    // worse than dropping it; `<br>` is the one that carries
                    // meaning.
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
                    out.add(MarkdownSpan(math.first, styles + MarkdownStyle.Math, link))
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
                        styles + MarkdownStyle.Strikethrough,
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
                    val style = if (run == 2) MarkdownStyle.Bold else MarkdownStyle.Italic
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
                out.add(MarkdownSpan(url, styles, url))
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
        // `(url "title")` — the title is not something we draw. An empty
        // destination is still a link's *shape*, and it is the caller that
        // decides what to do with one.
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

// ---- String helpers --------------------------------------------------------

/** Leading whitespace in columns, counting a tab as four. */
private fun String.indentWidth(): Int {
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
 * Two rules, and both earn their place. A closer may not follow whitespace, so
 * `2 * 3 * 4` is arithmetic rather than emphasis. And a run *longer* than the
 * one being closed closes from its end, so the `***` that ends
 * `**bold *and italic***` gives its first star to the italic and the other two
 * to the bold — matching from the front instead leaves a stray asterisk and
 * un-italicises the middle.
 *
 * Code spans are stepped over: a `*` inside backticks closes nothing.
 *
 * The search stops after [INLINE_SCAN_WINDOW] characters, which is what keeps
 * a paragraph full of openers with no partners from costing O(k·n).
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

// ---- Styling ---------------------------------------------------------------

/**
 * Everything the renderer draws with, resolved once per composition.
 *
 * The numbers are Zed's `MarkdownStyle::themed(MarkdownFont::Preview, …)`
 * (crates/markdown/src/markdown.rs:174-296): the body is the UI font at 1rem
 * with a 1.75 line height, code is the buffer font at the buffer font size,
 * inline code sits on `editor.foreground` at 8% and a link is `text.accent`
 * underlined at half strength.
 */
internal class MarkdownTextStyle(
    val theme: ZedTheme,
    val body: TextStyle,
    val code: TextStyle,
    val rem: Float,
    val linkStyles: TextLinkStyles,
    val inlineCodeBackground: Color,
)

@Composable
private fun markdownTextStyle(): MarkdownTextStyle {
    val theme = LocalZedTheme.current
    val rem = LocalUiFontSize.current
    val settings = LocalAppSettings.current
    val accent = theme.color("text.accent")
    val uiFont = UiFontFamily
    val bufferFont = BufferFontFamily
    return remember(theme, rem, settings.bufferFontSize, uiFont, bufferFont) {
        MarkdownTextStyle(
            theme = theme,
            body = TextStyle(
                fontFamily = uiFont,
                fontSize = rem.sp,
                lineHeight = (rem * 1.75f).sp,
                color = theme.color("text"),
            ),
            code = TextStyle(
                fontFamily = bufferFont,
                fontSize = settings.bufferFontSize.sp,
                lineHeight = (settings.bufferFontSize * 1.4f).sp,
                color = theme.color("editor.foreground"),
            ),
            rem = rem,
            linkStyles = TextLinkStyles(
                style = SpanStyle(
                    color = accent,
                    textDecoration = TextDecoration.Underline,
                ),
                hoveredStyle = SpanStyle(
                    color = accent,
                    background = accent.copy(alpha = 0.12f),
                    textDecoration = TextDecoration.Underline,
                ),
                pressedStyle = SpanStyle(
                    color = accent,
                    background = accent.copy(alpha = 0.24f),
                    textDecoration = TextDecoration.Underline,
                ),
            ),
            inlineCodeBackground = theme.color("editor.foreground").copy(alpha = 0.08f),
        )
    }
}

// ---- The composable --------------------------------------------------------

/**
 * How long a reply may go unrendered while it is still arriving.
 *
 * The preview's own constant, carried across with the throttle it belongs to.
 */
internal const val MARKDOWN_REPARSE_MS = 180L

/**
 * A markdown *string*, rendered.
 *
 * [source] may change on every frame of a streaming reply, and parsing it is
 * not free, so the work is throttled and done off the main thread, and the
 * last good render stays on screen meanwhile.
 */
@Composable
internal fun MarkdownText(
    source: String,
    modifier: Modifier = Modifier,
    onLink: (String) -> Unit = {},
) {
    val style = markdownTextStyle()
    var blocks by remember { mutableStateOf(emptyList<MarkdownNode>()) }
    var lastParsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(source) {
        // Throttled, **not** debounced, and the difference is the whole
        // feature. A plain `delay(…)` at the top of an effect keyed on the
        // text never fires at all while the text keeps changing faster than
        // the delay — and a streaming reply changes every 120 ms against a
        // 180 ms debounce, so the panel would have shown an empty bubble until
        // the agent stopped talking. Waiting only for the *remainder* since
        // the last parse bounds how stale the view can be instead of starving
        // it.
        val wait = markdownParseDelay(SystemClock.uptimeMillis() - lastParsed)
        if (wait > 0) delay(wait)
        blocks = withContext(Dispatchers.Default) {
            // The one place cancellation can land: `parseMarkdownText` has no
            // suspension point of its own, so a parse that has started runs to
            // the end whatever happens to the effect. Yielding first means a
            // reply that changed again while the throttle was waiting does not
            // *begin* a parse of the text before it.
            yield()
            parseMarkdownText(source)
        }
        lastParsed = SystemClock.uptimeMillis()
    }
    Column(modifier = modifier) {
        for (block in blocks) NodeView(block, style, onLink)
    }
}

/**
 * How long to wait before re-parsing, given how long ago the last parse was.
 *
 * Pure so the policy can be tested: the property that matters is that it is
 * bounded above by the interval — text arriving forever cannot postpone a
 * parse forever — and that a first parse, or one after a quiet spell, is
 * immediate.
 */
internal fun markdownParseDelay(
    sinceLastParse: Long,
    interval: Long = MARKDOWN_REPARSE_MS,
): Long = when {
    // A clock that went backwards (or a first parse) is not a reason to
    // stall; parse now.
    sinceLastParse < 0 -> 0
    sinceLastParse >= interval -> 0
    else -> interval - sinceLastParse
}

/**
 * Tailwind's scale, which is what Zed's `text_3xl`…`text_sm` resolve to
 * (crates/markdown/src/markdown.rs:3240-3247). Multiples of the body size, so
 * the whole message follows `ui_font_size` the way Zed's does.
 */
private fun headingScale(level: Int): Float = when (level) {
    1 -> 1.875f
    2 -> 1.5f
    3 -> 1.25f
    4 -> 1.125f
    5 -> 1f
    else -> 0.875f
}

@Composable
private fun NodeView(
    block: MarkdownNode,
    style: MarkdownTextStyle,
    onLink: (String) -> Unit,
) {
    when (block) {
        is MarkdownNode.Heading -> HeadingView(block, style, onLink)
        is MarkdownNode.Paragraph -> Text(
            text = buildInline(block.content, style, onLink),
            style = style.body,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        is MarkdownNode.Code -> CodeView(block, style)
        is MarkdownNode.Quote -> QuoteView(block, style, onLink)
        is MarkdownNode.Bullets -> BulletsView(block, style, onLink)
        MarkdownNode.Rule -> HorizontalDivider(
            color = style.theme.color("border"),
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun HeadingView(
    heading: MarkdownNode.Heading,
    style: MarkdownTextStyle,
    onLink: (String) -> Unit,
) {
    // Zed: everything but H1 gets `mt_6`, and H1–H3 get `pb_1` and a rule
    // under them (markdown.rs:3249-3262).
    val underlined = heading.level <= 3
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (heading.level == 1) 0.dp else 24.dp, bottom = 8.dp)
    ) {
        Text(
            text = buildInline(heading.content, style, onLink),
            style = style.body.copy(
                fontSize = (style.rem * headingScale(heading.level)).sp,
                lineHeight = 1.3.em,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.padding(bottom = if (underlined) 4.dp else 0.dp),
        )
        if (underlined) HorizontalDivider(color = style.theme.color("border.variant"))
    }
}

/**
 * A fence, in the buffer font, on the editor's background.
 *
 * **Not syntax-highlighted, and that is the extraction's one real loss.** The
 * preview colours a fence through `CodeFenceHighlighter`, which is a
 * tree-sitter parse per fence behind the engine's buffer mutex — engine work,
 * done off the main thread, for a *file*. It stays in `ui/preview` with the
 * rest of that machinery and goes when that package goes. A reply's fences are
 * short, and monospace on a panel of its own is what makes them read as code.
 */
@Composable
private fun CodeView(block: MarkdownNode.Code, style: MarkdownTextStyle) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(style.theme.color("editor.background"))
            .border(1.dp, style.theme.color("border.variant"), RoundedCornerShape(4.dp))
            // Zed sets `code_block_overflow_x_scroll`: code is not prose and
            // wrapping it silently changes what it says.
            .horizontalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        Text(text = AnnotatedString(block.code), style = style.code, softWrap = false)
    }
}

@Composable
private fun QuoteView(
    quote: MarkdownNode.Quote,
    style: MarkdownTextStyle,
    onLink: (String) -> Unit,
) {
    val fallback = style.theme.color("border")
    val accent = when (quote.kind) {
        "NOTE", "IMPORTANT" -> style.theme.color("info", fallback)
        "TIP" -> style.theme.color("success", fallback)
        "WARNING" -> style.theme.color("warning", fallback)
        "CAUTION" -> style.theme.color("error", fallback)
        else -> fallback
    }
    // The bar is painted behind the column rather than laid out beside it: a
    // sibling would need `fillMaxHeight` inside a wrap-content Row, and the
    // intrinsic measurement that makes that legal is not something every child
    // here supports — a fenced code block inside a quote is a horizontal
    // scroller, and asking it for an intrinsic height is asking for a crash.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
            .padding(start = 15.dp)
    ) {
        if (quote.kind != null) {
            Text(
                text = quote.kind.lowercase().replaceFirstChar { it.uppercase() },
                style = style.body.copy(fontWeight = FontWeight.SemiBold),
                color = accent,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        for (child in quote.blocks) NodeView(child, style, onLink)
    }
}

@Composable
private fun BulletsView(
    list: MarkdownNode.Bullets,
    style: MarkdownTextStyle,
    onLink: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (list.tight) 0.dp else 6.dp),
    ) {
        for (item in list.items) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = when (item.checked) {
                        true -> "☑"
                        false -> "☐"
                        null -> item.marker
                    },
                    style = style.body,
                    color = style.theme.color("text.muted"),
                    textAlign = if (list.ordered) TextAlign.End else TextAlign.Start,
                    // One line: a marker that wrapped would push its own item's
                    // first line down.
                    //
                    // **The width is a minimum, not a width**, and only an
                    // ordered list gets one. A fixed column so that `100.`
                    // lines up applies to `•` as well, and a bullet followed by
                    // nearly forty points of nothing is what the agent panel's
                    // lists used to look like: an empty corridor down the left
                    // of every reply. Zed sizes the marker to itself and leaves
                    // one gap (markdown.rs:2005-2017, `h_flex().items_start()`
                    // with `gap_1`).
                    maxLines = 1,
                    modifier = if (list.ordered) Modifier.widthIn(min = 24.dp) else Modifier,
                )
                Column(modifier = Modifier.padding(start = 6.dp).weight(1f)) {
                    for (child in item.blocks) NodeView(child, style, onLink)
                }
            }
        }
    }
}

/** One block's inline runs as a styled, clickable string. */
private fun buildInline(
    spans: List<MarkdownSpan>,
    style: MarkdownTextStyle,
    onLink: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        val text = if (span.isImage) {
            // No fetch, and no pretending: an image is named, not drawn. A
            // network request made on the user's behalf because a model wrote
            // a URL is not something this panel does.
            if (span.text.isBlank()) "[image]" else "[image: ${span.text}]"
        } else {
            span.text
        }
        if (text.isEmpty()) continue
        val appearance = SpanStyle(
            fontWeight = if (MarkdownStyle.Bold in span.styles) FontWeight.SemiBold else null,
            fontStyle = if (
                MarkdownStyle.Italic in span.styles || MarkdownStyle.Math in span.styles
            ) {
                FontStyle.Italic
            } else {
                null
            },
            textDecoration = if (MarkdownStyle.Strikethrough in span.styles) {
                TextDecoration.LineThrough
            } else {
                null
            },
            // The buffer font is a setting, so code *and* math take it from the
            // resolved style rather than the shipped family.
            fontFamily = if (
                MarkdownStyle.Code in span.styles || MarkdownStyle.Math in span.styles
            ) {
                style.code.fontFamily
            } else {
                null
            },
            fontSize = if (
                MarkdownStyle.Code in span.styles || MarkdownStyle.Math in span.styles
            ) {
                style.code.fontSize
            } else {
                TextUnit.Unspecified
            },
            // Math is set apart by its face, not by a chip: `$a$` inside a
            // sentence reads as a symbol, and a code-span background around
            // every one of them makes a paragraph of physics unreadable.
            background = when {
                MarkdownStyle.Code in span.styles -> style.inlineCodeBackground
                else -> Color.Unspecified
            },
            color = if (span.isImage) style.theme.color("text.muted") else Color.Unspecified,
        )
        val destination = span.link
        if (destination == null) {
            withStyle(appearance) { append(text) }
        } else {
            withLink(
                LinkAnnotation.Clickable(
                    tag = destination,
                    styles = style.linkStyles,
                    linkInteractionListener = LinkInteractionListener { onLink(destination) },
                )
            ) {
                withStyle(appearance) { append(text) }
            }
        }
    }
}
