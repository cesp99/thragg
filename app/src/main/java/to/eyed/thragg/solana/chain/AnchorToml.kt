package to.eyed.thragg.solana.chain

/**
 * Reading and rewriting the two things the app cares about in an Anchor.toml:
 * `[provider] cluster`, and the `module = "id"` rows of `[programs.<cluster>]`.
 *
 * Text in, text out, and every line that is not the one being changed comes
 * back byte-for-byte — comments, blank lines, ordering, indentation, the
 * user's choice of `\r\n`. That is the whole design and the reason there is no
 * TOML library here: a parser that re-serialises would re-flow a file the user
 * also edits by hand and keeps in git, and the diff from a cluster switch must
 * be exactly one line. It is also why this is a pure object over strings —
 * the interesting cases (`[programs."Devnet"]`, `cluster = 'Localnet'`,
 * a `[provider]` table that is not there at all) are all host-testable.
 *
 * What it understands, and no more: `[a.b]` headers with quoted or unquoted
 * parts, `key = "value"` rows with quoted or unquoted keys and single-, double-
 * or unquoted values, and `#` comments. Table names are matched
 * case-insensitively because Anchor matches its cluster names that way; module
 * keys are matched exactly, because a crate name is.
 */
object AnchorToml {

    /** The raw `[provider] cluster` value as written (`Devnet`, `localnet`), or null. */
    fun providerCluster(text: String): String? {
        val doc = Document(text)
        val section = doc.section(listOf("provider")) ?: return null
        return doc.value(section, "cluster")
    }

    /**
     * [text] with `[provider] cluster` set to [anchorName]: the existing row
     * rewritten in place, a missing row added to the existing table, or a
     * whole `[provider]` table created at the end when there is none.
     */
    fun withProviderCluster(text: String, anchorName: String): String {
        val doc = Document(text)
        return doc.set(listOf("provider"), "cluster", anchorName)
    }

    /** The id `[programs.<cluster>]` lists for [module], or null. */
    fun programId(text: String, clusterAnchorName: String, module: String): String? {
        val doc = Document(text)
        val section = doc.section(listOf("programs", clusterAnchorName)) ?: return null
        return doc.value(section, module)
    }

    /**
     * [text] with `[programs.<cluster>]` saying `module = "id"`, creating the
     * table at the end when it is missing. An existing table keeps its own
     * spelling of the cluster name.
     */
    fun withProgramId(text: String, clusterAnchorName: String, module: String, id: String): String {
        val doc = Document(text)
        return doc.set(listOf("programs", clusterAnchorName), module, id)
    }

    /**
     * Every `[programs.<cluster>]` table, keyed by cluster name lowercased
     * (so `[programs.Devnet]` is found under `devnet`), then by module.
     */
    fun programTables(text: String): Map<String, Map<String, String>> {
        val doc = Document(text)
        val out = LinkedHashMap<String, LinkedHashMap<String, String>>()
        for (section in doc.sections) {
            val parts = section.parts
            if (parts.size != 2 || !parts[0].equals("programs", ignoreCase = true)) continue
            val table = out.getOrPut(parts[1].lowercase()) { LinkedHashMap() }
            for (i in section.firstBody until section.end) {
                val row = parseRow(doc.lines[i]) ?: continue
                table[row.key] = row.value
            }
        }
        return out
    }

    // --- the line model -------------------------------------------------------

    /** One `[a.b]` header: its parts and the half-open line range it owns. */
    private class Section(val parts: List<String>, val header: Int, val end: Int) {
        /** The first line after the header. */
        val firstBody: Int get() = header + 1
    }

    /** One `key = value` row, with where the value sits so it can be spliced. */
    private class Row(val key: String, val value: String, val valueStart: Int, val valueEnd: Int)

    /**
     * The file as lines, with the newline convention it arrived with. `split`
     * leaves a trailing empty element for a file that ends in a newline, and
     * `joinToString` puts that newline back — nothing here adds or removes
     * one except by design.
     */
    private class Document(text: String) {
        val eol: String = if ("\r\n" in text) "\r\n" else "\n"
        val lines: MutableList<String> = text.split(eol).toMutableList()
        val sections: List<Section> = scan()

        private fun scan(): List<Section> {
            val headers = ArrayList<Pair<Int, List<String>>>()
            lines.forEachIndexed { index, line ->
                parseHeader(line)?.let { headers.add(index to it) }
            }
            return headers.mapIndexed { i, (at, parts) ->
                val end = if (i + 1 < headers.size) headers[i + 1].first else lines.size
                Section(parts, at, end)
            }
        }

        fun section(parts: List<String>): Section? =
            sections.firstOrNull { s ->
                s.parts.size == parts.size &&
                    s.parts.zip(parts).all { (a, b) -> a.equals(b, ignoreCase = true) }
            }

        fun row(section: Section, key: String): Pair<Int, Row>? {
            for (i in section.firstBody until section.end) {
                val row = parseRow(lines[i]) ?: continue
                if (row.key == key) return i to row
            }
            return null
        }

        fun value(section: Section, key: String): String? = row(section, key)?.second?.value

        /** The rewritten text: replace the row, or add it, or add the table. */
        fun set(parts: List<String>, key: String, value: String): String {
            val quoted = "\"$value\""
            val section = section(parts)
            if (section == null) {
                appendTable(parts, key, quoted)
                return lines.joinToString(eol)
            }
            val existing = row(section, key)
            if (existing != null) {
                val (at, row) = existing
                val line = lines[at]
                lines[at] = line.substring(0, row.valueStart) + quoted + line.substring(row.valueEnd)
                return lines.joinToString(eol)
            }
            // Add after the last real line of the table so a trailing blank
            // line that separates it from the next table stays where it is.
            var insertAt = section.end
            while (insertAt > section.firstBody && lines[insertAt - 1].isBlank()) insertAt--
            lines.add(insertAt, indentOf(section) + "${quoteIfNeeded(key)} = $quoted")
            return lines.joinToString(eol)
        }

        /** The indentation of the table's first row, so a new one lines up. */
        private fun indentOf(section: Section): String {
            for (i in section.firstBody until section.end) {
                if (parseRow(lines[i]) != null) return lines[i].takeWhile { it == ' ' || it == '\t' }
            }
            return ""
        }

        private fun appendTable(parts: List<String>, key: String, quoted: String) {
            // Drop the trailing empty element a final newline produced, so the
            // new table lands after the last real line, then put it back.
            val endedWithNewline = lines.isNotEmpty() && lines.last().isEmpty()
            if (endedWithNewline) lines.removeAt(lines.lastIndex)
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add("[" + parts.joinToString(".") { quoteIfNeeded(it) } + "]")
            lines.add("${quoteIfNeeded(key)} = $quoted")
            lines.add("")
        }
    }

    // --- parsing one line -----------------------------------------------------

    /** `[programs.devnet]`, `[programs."devnet"]`, `[ provider ] # note` -> parts, else null. */
    private fun parseHeader(line: String): List<String>? {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith("[") || trimmed.startsWith("[[")) return null
        val close = closingBracket(trimmed) ?: return null
        val inner = trimmed.substring(1, close)
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (c in inner) {
            when {
                quote != null -> if (c == quote) quote = null else current.append(c)
                c == '"' || c == '\'' -> quote = c
                c == '.' -> {
                    parts.add(current.toString().trim())
                    current.setLength(0)
                }
                else -> current.append(c)
            }
        }
        if (quote != null) return null
        parts.add(current.toString().trim())
        if (parts.any { it.isEmpty() }) return null
        return parts
    }

    /** Index of the `]` that closes the header, skipping quoted ones. */
    private fun closingBracket(trimmed: String): Int? {
        var quote: Char? = null
        for (i in 1 until trimmed.length) {
            val c = trimmed[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == ']' -> return i
            }
        }
        return null
    }

    /** `key = "value" # note`, `"my-key" = 'v'`, `k = v` -> the row, else null. */
    private fun parseRow(line: String): Row? {
        val trimmed = line.trimStart()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("[")) return null
        val indent = line.length - trimmed.length
        // The key: a quoted string, or a bare run up to the `=`.
        var i = 0
        val key: String
        if (trimmed[0] == '"' || trimmed[0] == '\'') {
            val q = trimmed[0]
            val close = trimmed.indexOf(q, 1)
            if (close < 0) return null
            key = trimmed.substring(1, close)
            i = close + 1
        } else {
            val eq = trimmed.indexOf('=')
            if (eq < 0) return null
            key = trimmed.substring(0, eq).trim()
            if (key.isEmpty() || key.any { it == '#' || it.isWhitespace() }) return null
            i = eq
        }
        while (i < trimmed.length && trimmed[i].isWhitespace()) i++
        if (i >= trimmed.length || trimmed[i] != '=') return null
        i++
        while (i < trimmed.length && trimmed[i].isWhitespace()) i++
        if (i >= trimmed.length) return null
        // The value: quoted, or bare up to whitespace or a comment.
        val start = i
        val value: String
        val end: Int
        val first = trimmed[i]
        if (first == '"' || first == '\'') {
            val close = trimmed.indexOf(first, i + 1)
            if (close < 0) return null
            value = trimmed.substring(i + 1, close)
            end = close + 1
        } else {
            var j = i
            while (j < trimmed.length && !trimmed[j].isWhitespace() && trimmed[j] != '#') j++
            value = trimmed.substring(i, j)
            end = j
        }
        return Row(key, value, indent + start, indent + end)
    }

    /** Bare TOML keys are `A-Za-z0-9_-`; anything else has to be quoted. */
    private fun quoteIfNeeded(part: String): String =
        if (part.all { it.isLetterOrDigit() || it == '_' || it == '-' }) part else "\"$part\""
}
