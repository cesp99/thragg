package to.eyed.seeker.code.ui.preview

/**
 * A delimited file read into rows — Zed's `tabular_data_preview`, in Kotlin.
 *
 * The shape is Zed's: the first row is the header, every later row is data,
 * and the column count is the *widest* row rather than the header's, because
 * a hand-maintained CSV grows a column at the bottom first and dropping the
 * ragged cells would hide data (`parser.rs:170-178`,
 * `from_buffer_with_delimiter`).
 *
 * The quoting rules are RFC 4180's, which is what Zed's scanner implements
 * (`parse_delimited_text_with_positions`, parser.rs:194-330): a field may be
 * wrapped in `"`, a doubled `""` inside one is a literal quote, and a
 * delimiter or a newline inside quotes is content rather than structure. CRLF
 * counts as one line break and a lone CR ends a row.
 *
 * Pure Kotlin with no Android or Compose types, so all of the above is
 * checked on the host; [TablePreview] draws the result.
 */
class TableDocument(
    val header: List<String>,
    val rows: List<List<String>>,
    /** The widest row's width — see the class comment. */
    val columnCount: Int,
    /**
     * The 1-based source line each row started on, index-aligned with [rows] —
     * Zed's `LineNumber` (types.rs:11-17), narrowed to the start because that
     * is all a jump to the source needs.
     */
    val rowLines: List<Int>,
    /** True when the file was longer than [MAX_ROWS] and the tail was dropped. */
    val isTruncated: Boolean = false,
) {
    val isEmpty: Boolean get() = header.isEmpty() && rows.isEmpty()

    companion object {
        val EMPTY = TableDocument(emptyList(), emptyList(), 0, emptyList())

        /**
         * How many rows are read before the rest is left on disk.
         *
         * The preview holds every cell it parses in the Java heap as a
         * `String`, and a CSV is the one file type that is routinely tens of
         * megabytes: an export of a database table is a hundred thousand rows
         * without being unusual. The editor still has the whole file — this is
         * the same refusal the Markdown preview makes for the same reason (see
         * `MAX_PREVIEW_CHARS`), except that a table can say *how much* it is
         * showing, so it does.
         */
        const val MAX_ROWS = 20_000

        /** Cells past this in one row are dropped: a row is not a document. */
        const val MAX_COLUMNS = 512

        /** The delimiter [path]'s suffix implies, or `,` when it implies none. */
        fun delimiterFor(path: String): Char =
            TABULAR_SUFFIXES[path.substringAfterLast('.', "").lowercase()] ?: ','

        /**
         * Read [text] as rows of [delimiter]-separated fields.
         *
         * Blank rows are dropped rather than drawn, as Zed drops them
         * (`parser.rs:262-266`: a row whose every field is blank never
         * reaches the table) — a trailing newline is otherwise a phantom
         * final row in every file.
         */
        fun parse(text: String, delimiter: Char): TableDocument {
            if (text.isBlank()) return EMPTY
            val rows = mutableListOf<List<String>>()
            val lines = mutableListOf<Int>()
            var row = mutableListOf<String>()
            val field = StringBuilder()
            var quoted = false
            var line = 1
            var rowStart = 1
            var truncated = false
            var index = 0

            fun endField() {
                if (row.size < MAX_COLUMNS) row.add(field.toString())
                field.setLength(0)
            }

            fun endRow() {
                endField()
                // Zed's rule, and it is the reason a file ending in a newline
                // does not grow an empty last row.
                if (row.any { it.isNotBlank() }) {
                    rows.add(row)
                    lines.add(rowStart)
                }
                row = mutableListOf()
            }

            while (index < text.length) {
                if (rows.size >= MAX_ROWS) {
                    truncated = true
                    break
                }
                val char = text[index]
                when {
                    char == '"' -> {
                        if (quoted && index + 1 < text.length && text[index + 1] == '"') {
                            field.append('"')
                            index++
                        } else {
                            quoted = !quoted
                        }
                    }
                    char == delimiter && !quoted -> endField()
                    (char == '\n' || char == '\r') && !quoted -> {
                        // CRLF is one break, not two.
                        if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
                            index++
                        }
                        line++
                        endRow()
                        rowStart = line
                    }
                    else -> {
                        // A newline *inside* quotes is content: it is the
                        // reason this is a scanner and not `split('\n')`.
                        if (char == '\n' || char == '\r') line++
                        field.append(char)
                    }
                }
                index++
            }
            if (!truncated && (field.isNotEmpty() || row.isNotEmpty())) endRow()

            if (rows.isEmpty()) return EMPTY
            val header = rows.first()
            val body = rows.drop(1)
            return TableDocument(
                header = header,
                rows = body,
                columnCount = maxOf(header.size, body.maxOfOrNull { it.size } ?: 0),
                rowLines = lines.drop(1),
                isTruncated = truncated,
            )
        }

        /**
         * How wide column [column] wants to be, in characters.
         *
         * Measured from the content, as Zed sizes its columns from what is in
         * them, and clamped: a column of one-character flags should not be a
         * sliver, and a column holding a paragraph should not push every other
         * column off the screen. Only the first [WIDTH_SAMPLE] rows are
         * measured — the cost has to be bounded by the screen, not by the
         * file.
         */
        fun columnWidth(document: TableDocument, column: Int): Int {
            var widest = document.header.getOrNull(column)?.length ?: 0
            for (row in document.rows.take(WIDTH_SAMPLE)) {
                val cell = row.getOrNull(column) ?: continue
                // A cell with a newline in it is measured by its longest line:
                // the renderer clips to one line anyway.
                val length = if ('\n' in cell) {
                    cell.splitToSequence('\n').maxOf { it.length }
                } else {
                    cell.length
                }
                if (length > widest) widest = length
            }
            return widest.coerceIn(MIN_COLUMN_CHARS, MAX_COLUMN_CHARS)
        }

        /** Rows consulted when sizing a column. See [columnWidth]. */
        const val WIDTH_SAMPLE = 200
        const val MIN_COLUMN_CHARS = 6
        const val MAX_COLUMN_CHARS = 40
    }
}
