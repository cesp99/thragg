package to.eyed.thragg.ui.editor

import kotlin.random.Random

/**
 * The pure half of Zed's line and text manipulations — `editor::SortLines*`,
 * `ReverseLines`, `ShuffleLines`, `UniqueLines*`, the `ConvertTo*Case`
 * family and `Rewrap`.
 *
 * Zed splits each of these the same way: a small function over the text
 * (`manipulate_immutable_lines`' callback, `manipulate_text`'s callback,
 * editor.rs:5857-5910, 6688-6694, 7123-7264) and the machinery that decides
 * *which* text it runs over. The machinery is in [EditorCommands]; the
 * functions are here, where they can be tested without a buffer.
 */
object LineTransforms {

    /** Zed's `SortLinesCaseSensitive` (editor.rs:5857-5864): a plain sort. */
    fun sort(lines: List<String>): List<String> = lines.sorted()

    /**
     * Zed's `SortLinesCaseInsensitive` (editor.rs:5877-5886), which sorts by
     * the lowercased line — so equal-but-for-case lines keep the order they
     * were written in, Kotlin's sort being stable as Rust's is.
     */
    fun sortCaseInsensitive(lines: List<String>): List<String> =
        lines.sortedBy { it.lowercase() }

    /** Zed's `ReverseLines` (editor.rs:6688-6690). */
    fun reverse(lines: List<String>): List<String> = lines.reversed()

    /**
     * Zed's `ShuffleLines` (editor.rs:6692-6694). [random] is a parameter so
     * the test can pin the deal; the command passes the default source.
     */
    fun shuffle(lines: List<String>, random: Random = Random.Default): List<String> =
        lines.shuffled(random)

    /**
     * Zed's `UniqueLinesCaseSensitive` (editor.rs:5900-5910): the first of
     * each repeated line stays, later ones go. Order is otherwise untouched
     * — this is not a sort.
     */
    fun unique(lines: List<String>): List<String> {
        val seen = HashSet<String>()
        return lines.filter { seen.add(it) }
    }

    /** Zed's `UniqueLinesCaseInsensitive` (editor.rs:5888-5898). */
    fun uniqueCaseInsensitive(lines: List<String>): List<String> {
        val seen = HashSet<String>()
        return lines.filter { seen.add(it.lowercase()) }
    }

    /**
     * Zed's `ConvertToOppositeCase` (editor.rs:7196-7211): every uppercase
     * character goes down and every other character goes up, character by
     * character, so `fooBAR` becomes `FOObar`.
     */
    fun oppositeCase(text: String): String = buildString(text.length) {
        for (char in text) {
            append(if (char.isUpperCase()) char.lowercaseChar() else char.uppercaseChar())
        }
    }

    /**
     * The identifier cases Zed hands to the `convert_case` crate.
     *
     * Zed applies them per line and *keeps the surrounding whitespace*
     * (`convert_text_case`, editor.rs:7254-7264): the indent and any trailing
     * spaces are sliced off, the middle is converted, and the three are put
     * back together. That is what makes `ConvertToSnakeCase` over a selected
     * block leave the block's indentation alone.
     */
    enum class Case { Title, Snake, Kebab, UpperCamel, LowerCamel }

    /** [Case], applied line by line the way Zed applies it. */
    fun convertCase(text: String, case: Case): String =
        text.split("\n").joinToString("\n") { line ->
            val trimmedStart = line.trimStart()
            val leading = line.substring(0, line.length - trimmedStart.length)
            val trimmed = trimmedStart.trimEnd()
            val trailing = trimmedStart.substring(trimmed.length)
            leading + convertWords(trimmed, case) + trailing
        }

    private fun convertWords(text: String, case: Case): String {
        val words = identifierWords(text)
        if (words.isEmpty()) return text
        return when (case) {
            Case.Title -> words.joinToString(" ") { capitalize(it) }
            Case.Snake -> words.joinToString("_") { it.lowercase() }
            Case.Kebab -> words.joinToString("-") { it.lowercase() }
            Case.UpperCamel -> words.joinToString("") { capitalize(it) }
            Case.LowerCamel -> words.mapIndexed { index, word ->
                if (index == 0) word.lowercase() else capitalize(word)
            }.joinToString("")
        }
    }

    private fun capitalize(word: String): String =
        if (word.isEmpty()) word else word[0].uppercaseChar() + word.substring(1).lowercase()

    /**
     * The words inside an identifier, by the boundaries `convert_case`'s
     * defaults use: `_`, `-` and whitespace, a lowercase-to-uppercase step
     * (`fooBar`), the acronym break (`HTTPResponse` → `HTTP`, `Response`) and
     * the letter-to-digit steps either way (`utf8Text` → `utf`, `8`, `Text`).
     *
     * Anything that is not a letter or a digit is a separator and is dropped,
     * which is what makes `ConvertToSnakeCase` over `foo.bar` yield `foo_bar`.
     */
    fun identifierWords(text: String): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        fun flush() {
            if (word.isNotEmpty()) {
                words.add(word.toString())
                word.clear()
            }
        }
        for ((index, char) in text.withIndex()) {
            if (!char.isLetterOrDigit()) {
                flush()
                continue
            }
            val previous = text.getOrNull(index - 1)
            val next = text.getOrNull(index + 1)
            val boundary = when {
                previous == null || !previous.isLetterOrDigit() -> false
                previous.isLowerCase() && char.isUpperCase() -> true
                previous.isDigit() != char.isDigit() -> true
                // The acronym break: this character opens a new word only if
                // it is the last capital of a run and a lowercase follows.
                previous.isUpperCase() && char.isUpperCase() && next?.isLowerCase() == true -> true
                else -> false
            }
            if (boundary) flush()
            word.append(char)
        }
        flush()
        return words
    }

    /**
     * Zed's `editor::Rewrap` over one paragraph: [lines] all carry the same
     * indent and the same comment prefix, and come back reflowed so that no
     * line is longer than [maxColumns] unless a single word is.
     *
     * The prefix — the indent plus whatever comment marker the first line
     * opens with — is measured once and put back on every line, which is what
     * keeps a `//` block a `//` block (rewrap.rs:104-124, where Zed builds
     * exactly this `line_prefix` and reuses it for every wrapped row).
     * [prefix] is that string; the caller works it out from the language,
     * because only the caller knows the language.
     *
     * A paragraph whose words all fit on one line comes back as one line.
     */
    fun rewrap(lines: List<String>, prefix: String, maxColumns: Int): List<String> {
        val words = lines
            .map { line -> line.removePrefix(prefix).trim() }
            .flatMap { it.split(Regex("\\s+")) }
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return lines
        val width = maxColumns.coerceAtLeast(prefix.length + 1)
        val wrapped = mutableListOf<String>()
        val current = StringBuilder(prefix)
        var hasWord = false
        for (word in words) {
            val candidate = if (hasWord) current.length + 1 + word.length else current.length + word.length
            if (hasWord && candidate > width) {
                wrapped.add(current.toString())
                current.setLength(0)
                current.append(prefix)
                hasWord = false
            }
            if (hasWord) current.append(' ')
            current.append(word)
            hasWord = true
        }
        if (hasWord) wrapped.add(current.toString())
        return wrapped
    }

    /**
     * The indent-and-comment-marker prefix [rewrap] reflows under, taken from
     * [line]: its leading whitespace, plus the first of [commentPrefixes]
     * the text after that indent starts with — and the space after the
     * marker, because `// text` wraps to `// text`, not to `//text`.
     */
    fun rewrapPrefix(line: String, commentPrefixes: List<String>): String {
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val rest = line.substring(indent.length)
        val marker = commentPrefixes
            .filter { it.isNotEmpty() }
            .sortedByDescending { it.length }
            .firstOrNull { rest.startsWith(it) }
            ?: return indent
        // Zed's prefixes carry their own trailing space ("// "); one that does
        // not gets one here, so the wrapped rows read as the first one does.
        return if (marker.endsWith(" ")) indent + marker else "$indent$marker "
    }
}
