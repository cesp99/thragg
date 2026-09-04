package to.eyed.thragg.ui.terminal

/**
 * What a click on terminal text can point at — Zed's `MaybeNavigationTarget`
 * (terminal/src/terminal.rs:690-698): a URL for the browser, or something
 * path-shaped for the editor, possibly with a line and column attached.
 *
 * Pure functions over a row's text and a character index, so the detector
 * can be unit-tested without an emulator; the view layer maps a touch to a
 * column and a column to an index.
 */
sealed class TerminalLink {
    /** Where in the row the link sits, as character indices `[start, end)`. */
    abstract val start: Int
    abstract val end: Int

    /** An `http://`, `mailto:` … string, ready for `ACTION_VIEW`. */
    data class Url(val url: String, override val start: Int, override val end: Int) : TerminalLink()

    /**
     * A path — absolute or relative, existing or not — and the position the
     * text attached to it. [row] and [column] are 1-based as printed;
     * [column] is absent unless [row] is.
     */
    data class PathLike(
        val path: String,
        val row: Int?,
        val column: Int?,
        override val start: Int,
        override val end: Int,
    ) : TerminalLink()
}

/**
 * Zed's `URL_REGEX` (terminal/src/alacritty/hyperlinks.rs:22), in Java's
 * dialect: the scheme list is Zed's; the excluded characters are the C0 and
 * C1 controls, whitespace, the angle and curly brackets, quotes and the
 * mathematical angle brackets — `{-}` in Zed's class is the range `{`..`}`,
 * which takes `|` with it.
 */
private val URL_REGEX = Regex(
    "(ipfs:|ipns:|magnet:|mailto:|gemini://|gopher://|https://|http://|news:|file://|git://|ssh:|ftp://|zed://)" +
        "[^\\u0000-\\u001F\\u007F-\\u009F<>\"\\s\\x7B-\\x7D\\^⟨⟩`']+"
)

/**
 * Zed's default `terminal.path_hyperlink_regexes` (assets/settings/default.json:2136-2159),
 * in order: Python's `File "x", line N`, then the common path syntax — a run
 * of non-space characters that does not open with a bracket or a quote, does
 * not close with one or with punctuation, and may carry `:line:col` or
 * `(line,col)`. The colon rule in the middle (`[:(][^0-9()\ ]`) is what stops
 * `src/main.rs:12:5` from swallowing its own position into the path.
 */
private val PYTHON_DIAGNOSTIC_REGEX = Regex("File \"(?<path>[^\"]+)\", line (?<line>[0-9]+)")

private val COMMON_PATH_REGEX = Regex(
    "(?<path>" +
        "(" +
        // multi-char path: first char (not opening delimiter, space, or box drawing char)
        "[^({\\[<\"'` \\u2500-\\u257F]" +
        // middle chars: non-space, and colon/paren only if not followed by digit/paren/space
        "([^ :(]|[:(][^0-9() ])*" +
        // last char: not closing delimiter or colon
        "[^()}\\]>\"'`.,;: ]" +
        "|" +
        // single-char path: not delimiter, punctuation, space, or box drawing char
        "[^(){}\\[\\]<>\"'`.,;: \\u2500-\\u257F]" +
        ")" +
        // optional line/column suffix
        "(:+[0-9]+(:[0-9]+)?|:?\\([0-9]+([,:]?[0-9]+)?\\))?" +
        ")"
)

/**
 * The link under [index] in [line], or null when the character there is not
 * part of one. URLs win over paths, as in Zed's `find_from_grid_point`
 * (hyperlinks.rs:120-146); a `file://` URL comes back as a path, the way
 * Zed's `normalize_hyperlink_match` turns it into one (hyperlinks.rs:174-198),
 * so line numbers on the end of it are read.
 */
fun findTerminalLink(line: String, index: Int): TerminalLink? {
    if (index < 0 || index >= line.length) return null
    findUrl(line, index)?.let { return it }
    return findPath(line, index)
}

private fun findUrl(line: String, index: Int): TerminalLink? {
    for (match in URL_REGEX.findAll(line)) {
        if (index !in match.range) continue
        val (url, end) = sanitizeUrlPunctuation(match.value, match.range.last + 1)
        if (index >= end) return null
        if (url.startsWith("file://")) {
            // OSC 8 spells these `file://{host}{path}`; the host, if any, is
            // whatever sits before the first slash and is dropped, as Zed's
            // `try_osc8_url_to_path` does (hyperlinks.rs:223-235).
            val decoded = percentDecode("/" + url.removePrefix("file://").substringAfter('/', ""))
            val position = parsePathWithPosition(decoded)
            return TerminalLink.PathLike(position.path, position.row, position.column, match.range.first, end)
        }
        return TerminalLink.Url(url, match.range.first, end)
    }
    return null
}

/**
 * Zed's `sanitize_url_punctuation` (hyperlinks.rs:237-289): a URL does not
 * end in `.`, `,`, `:`, `;` or `(`, and a `)` on the end is only part of it
 * if an earlier `(` opened it — `(see https://example.com)` is plain text
 * around a link.
 */
private fun sanitizeUrlPunctuation(url: String, end: Int): Pair<String, Int> {
    var trimmed = url
    var closeParens = url.count { it == ')' }
    val openParens = url.count { it == '(' }
    while (trimmed.isNotEmpty()) {
        val last = trimmed.last()
        val drop = when (last) {
            '.', ',', ':', ';', '(' -> true
            ')' -> if (closeParens > openParens) {
                closeParens--
                true
            } else {
                false
            }
            else -> false
        }
        if (!drop) break
        trimmed = trimmed.dropLast(1)
    }
    return trimmed to end - (url.length - trimmed.length)
}

private fun findPath(line: String, index: Int): TerminalLink? {
    // Zed tries each regex in turn and stops at the first that matches the
    // line at all (hyperlinks.rs:419-477): the Python form claims the whole
    // "File …, line N" phrase, and only where it is absent does the common
    // syntax get a go.
    for (regex in listOf(PYTHON_DIAGNOSTIC_REGEX, COMMON_PATH_REGEX)) {
        var any = false
        for (match in regex.findAll(line)) {
            any = true
            val pathGroup = match.groups["path"] ?: continue
            var start = pathGroup.range.first
            val end = pathGroup.range.last + 1
            var text = pathGroup.value
            // Zed strips up to the first unbalanced `(` (hyperlinks.rs:293-317):
            // `Update(.claude/SKILL.md)` is a delimiter, `file(copy).txt` is a
            // name.
            firstUnbalancedOpenParen(text)?.let { trim ->
                start += trim
                text = text.substring(trim)
            }
            if (index < start || index >= end) continue
            // Only the Python form names a `line` group, and Java's matcher
            // throws for a name the pattern lacks rather than answering null.
            val lineNumber = if (regex === PYTHON_DIAGNOSTIC_REGEX) {
                match.groups["line"]?.value?.toIntOrNull()
            } else {
                null
            }
            val position = if (lineNumber != null) {
                PathWithPosition(text, lineNumber, null)
            } else {
                parsePathWithPosition(text)
            }
            if (position.path.isEmpty()) return null
            return TerminalLink.PathLike(position.path, position.row, position.column, start, end)
        }
        if (any) return null
    }
    return null
}

/** Zed's `first_unbalanced_open_paren` (hyperlinks.rs:293-317). */
private fun firstUnbalancedOpenParen(s: String): Int? {
    var balance = 0
    var firstUnmatched: Int? = null
    for ((i, c) in s.withIndex()) {
        when (c) {
            '(' -> {
                if (balance == 0) firstUnmatched = i + 1
                balance++
            }
            ')' -> {
                balance--
                if (balance <= 0) {
                    balance = 0
                    firstUnmatched = null
                }
            }
        }
    }
    return firstUnmatched?.takeIf { balance > 0 }
}

/** A path and the `row[:column]` its text carried. Rows and columns 1-based. */
data class PathWithPosition(val path: String, val row: Int?, val column: Int?)

private val ROW_COL_PAREN_SUFFIX = Regex(""":?\((\d+)(?:[,:](\d+))?\):*$""")
private val ROW_COL_COLON_SUFFIX = Regex(""":+(\d+)(?::(\d+))?:*$""")

/**
 * Zed's `PathWithPosition::parse_str` (util/src/paths.rs:626-720), for the
 * forms a compiler prints: `file.rs:12:5`, `file.rs:12`, `file.rs:12:`,
 * `file.c(12,5)`, `file.c(12)`, `file.cs:(12,5)`. Trailing colons are
 * ignored; a suffix that is not numbers is part of the name — `test:10:1:`
 * is a valid POSIX file name, and only the *trailing* numbers are read as a
 * position.
 */
fun parsePathWithPosition(text: String): PathWithPosition {
    val trimmed = text.trim()
    ROW_COL_PAREN_SUFFIX.find(trimmed)?.let { match ->
        return PathWithPosition(
            path = trimmed.substring(0, match.range.first),
            row = match.groupValues[1].toIntOrNull(),
            column = match.groupValues[2].toIntOrNull(),
        )
    }
    ROW_COL_COLON_SUFFIX.find(trimmed)?.let { match ->
        val row = match.groupValues[1].toIntOrNull()
        return PathWithPosition(
            path = trimmed.substring(0, match.range.first),
            row = row,
            column = row?.let { match.groupValues[2].toIntOrNull() },
        )
    }
    return PathWithPosition(trimmed.trimEnd(':'), null, null)
}

/** `%20` and friends back to characters; a stray `%` is left as it is. */
internal fun percentDecode(text: String): String {
    if (!text.contains('%')) return text
    val bytes = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%' && i + 2 < text.length) {
            val hex = text.substring(i + 1, i + 3).toIntOrNull(16)
            if (hex != null) {
                bytes.write(hex)
                i += 3
                continue
            }
        }
        val encoded = c.toString().toByteArray(Charsets.UTF_8)
        bytes.write(encoded, 0, encoded.size)
        i++
    }
    return bytes.toString(Charsets.UTF_8.name())
}
