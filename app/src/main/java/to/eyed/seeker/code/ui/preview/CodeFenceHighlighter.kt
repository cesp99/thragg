package to.eyed.seeker.code.ui.preview

import to.eyed.seeker.code.core.BufferSession
import to.eyed.seeker.code.core.CoreBridge
import to.eyed.seeker.code.ui.editor.HighlightSpan

/**
 * Syntax colouring for the preview's fenced code blocks, through the engine we
 * already have rather than a second highlighter written in Kotlin.
 *
 * The bridge has no "highlight this string as Rust" call and this wave does not
 * hold the JNI pair, so the fence is highlighted the way any other text is: a
 * scratch engine buffer is created, given the fence's language, asked for its
 * spans, and closed. That is three existing calls and no new surface. It costs
 * one tree-sitter parse per distinct fence, which is why the answers are cached
 * and why [MAX_HIGHLIGHTED_BYTES] exists — a 200 KB code block in a README is
 * not worth a parse, and it is not worth the memory of its spans either.
 *
 * **Everything here blocks and takes the engine's buffer mutex. Call it off the
 * main thread**, which is what [MarkdownPreview]'s parse coroutine does.
 */
internal object CodeFenceHighlighter {

    /** Past this a fence is treated as plain text; see the class comment. */
    private const val MAX_HIGHLIGHTED_BYTES = 64 * 1024

    /** Distinct fences remembered. A README has a handful; this is generous. */
    private const val CACHE_LIMIT = 64

    private val cache = FenceCache(CACHE_LIMIT)

    /**
     * Per-line spans for [code] as [language], or null when we cannot colour
     * it — no info string, a language we carry no grammar for, or a fence too
     * large to be worth parsing. Null means "draw it plain", never an error.
     */
    fun highlight(code: String, language: String?): List<List<HighlightSpan>>? {
        val grammar = grammarFor(language) ?: return null
        if (code.length > MAX_HIGHLIGHTED_BYTES) return null
        cache.answerFor(grammar, code)?.let { return it.ifEmpty { null } }

        val session = BufferSession(code)
        // A grammar we do not carry answers `false` and there is nothing to
        // colour — but that is an *answer*, and remembering it is what stops a
        // README with a ```zigzag fence creating, parsing and closing an
        // engine buffer on every single reparse. See FenceCache.
        var spans: List<List<HighlightSpan>> = emptyList()
        try {
            // `setLanguage` parses on the calling thread, so the spans below
            // are the real ones rather than the empty set a background reparse
            // would still be on its way to producing.
            if (session.setLanguage(grammar)) {
                val rows = code.count { it == '\n' } + 1
                spans = groupSpans(CoreBridge.bufferHighlights(session.id, 0, rows.toLong()), rows)
            }
        } finally {
            // The engine holds every buffer until it is told not to; a preview
            // that reparses on every keystroke would leak one per fence per
            // keystroke without this.
            session.close()
        }
        cache.remember(grammar, code, spans)
        return spans.ifEmpty { null }
    }

    /**
     * Forget every fence. Called when the preview leaves the composition: the
     * entries are keyed by whole fence texts and hold a span object per token,
     * and a panel that has been closed has no claim on any of it.
     */
    fun clear() = cache.clear()

    /** Flat [row, start, end, style] groups → one list per row. */
    private fun groupSpans(flat: IntArray?, rows: Int): List<List<HighlightSpan>> {
        val grouped = List(rows) { mutableListOf<HighlightSpan>() }
        if (flat == null) return grouped
        var index = 0
        while (index + 3 < flat.size) {
            val row = flat[index]
            if (row in 0 until rows) {
                grouped[row].add(HighlightSpan(flat[index + 1], flat[index + 2], flat[index + 3]))
            }
            index += 4
        }
        return grouped
    }
}

/**
 * What [CodeFenceHighlighter] remembers, and the only part of it that can be
 * exercised without an engine.
 *
 * Bounded and least-recently-used, because the key is a whole fence text and
 * the value is a span per token — this is not a cache that may be allowed to
 * grow. A *refusal* is remembered as an empty list rather than as nothing at
 * all: "we cannot colour this" costs an engine buffer to find out, and a fence
 * in a language we do not carry is otherwise re-asked on every reparse.
 */
internal class FenceCache(private val limit: Int) {

    private data class Key(val grammar: String, val code: String)

    private val entries = object : LinkedHashMap<Key, List<List<HighlightSpan>>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Key, List<List<HighlightSpan>>>,
        ): Boolean = size > limit
    }

    /**
     * What is remembered for this fence: its spans, an empty list for one we
     * already know cannot be coloured, or null when we have not seen it.
     */
    fun answerFor(grammar: String, code: String): List<List<HighlightSpan>>? =
        synchronized(entries) { entries[Key(grammar, code)] }

    fun remember(grammar: String, code: String, spans: List<List<HighlightSpan>>) {
        synchronized(entries) { entries[Key(grammar, code)] = spans }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    val size: Int
        get() = synchronized(entries) { entries.size }
}

/**
 * The grammar name behind a fence's info string.
 *
 * The engine names its grammars after the directories they are vendored in
 * (`bash`, `tsx`, `python`); a fence is written the way people write them.
 * Anything not in this table is passed through unchanged and simply refused by
 * `setLanguage` if we do not carry it, so a new grammar needs no entry here to
 * work under its own name.
 */
internal fun grammarFor(language: String?): String? {
    // An info string is not always only a language: `rust,ignore` and
    // `js{1,3}` are both ordinary in a README, and the language is the run of
    // name characters at the front of it.
    val name = language?.trim()?.lowercase()
        ?.takeWhile { it.isLetterOrDigit() || it == '+' || it == '#' || it == '-' }
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return when (name) {
        "sh", "shell", "zsh", "console", "bash" -> "bash"
        "js", "jsx", "javascript", "mjs", "cjs", "tsx" -> "tsx"
        "ts", "typescript" -> "typescript"
        "py", "python", "python3" -> "python"
        "rs", "rust" -> "rust"
        "yml", "yaml" -> "yaml"
        "md", "markdown" -> "markdown"
        "c++", "cc", "cxx", "hpp", "cpp" -> "cpp"
        "h", "c" -> "c"
        "golang", "go" -> "go"
        "patch", "diff" -> "diff"
        "json5", "jsonc" -> "jsonc"
        else -> name
    }
}
