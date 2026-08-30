package to.eyed.seeker.code.ui.preview

/**
 * What the toolbar's eye offers for the file that is open.
 *
 * Zed decides this the same way and in the same place: its quick action bar
 * asks the active item what it is and shows one Eye button for whichever
 * preview applies (`quick_action_bar/preview.rs:24-40`). A file with no
 * preview gets no button at all rather than a disabled one.
 */
enum class PreviewKind {
    Markdown,
    Svg,

    /**
     * Delimited data — Zed's `tabular_data_preview`, which registers the same
     * `OpenPreview` action for `.csv`, `.tsv`, `.psv` and `.ssv`
     * (`tabular_data_preview/src/parser.rs:29-33`) and draws the file as a
     * table beside the text.
     */
    Table;

    companion object {
        /** The preview [path] has, or null when it has none. */
        fun of(path: String): PreviewKind? {
            val name = path.substringAfterLast('/')
            val suffix = name.substringAfterLast('.', "").lowercase()
            return when {
                MARKDOWN_SUFFIXES.any { name.endsWith(it, ignoreCase = true) } -> Markdown
                name.endsWith(".svg", ignoreCase = true) -> Svg
                suffix in TABULAR_SUFFIXES -> Table
                else -> null
            }
        }
    }
}

/** Files the Markdown preview will render. */
internal val MARKDOWN_SUFFIXES = listOf(".md", ".markdown", ".mdown", ".mkd")

/**
 * Zed's `TABULAR_FORMATS` (tabular_data_preview/src/parser.rs:29-33), and the
 * delimiter each of them means. A suffix the table does not know never opens
 * a table preview — guessing a delimiter from the bytes would turn every
 * `.txt` with a comma in it into a one-column table.
 */
internal val TABULAR_SUFFIXES: Map<String, Char> = mapOf(
    "csv" to ',',
    "tsv" to '\t',
    "psv" to '|',
    "ssv" to ';',
)
