package to.eyed.seeker.code.ui.editor

import org.json.JSONObject

/**
 * A pair the editor closes for you, and wraps a selection in — Zed's
 * `BracketPair`, as the grammar's `config.toml` writes it.
 *
 * [autoClose] is its `close` flag: `<` opens a generic in Rust but is far too
 * often a comparison to close automatically, so it may still surround a
 * selection while never auto-closing on its own. [newline] is its `newline`:
 * whether Enter between the two halves gives the closer a line of its own.
 * Quotes are `newline = false` in every config we carry, which is why
 * `x = "hello"` + Enter does not indent.
 *
 * [notIn] is the list the old hardcoded table dropped entirely: the
 * tree-sitter scopes — "string", "comment" — where the pair is *not* live. It
 * cannot be answered from here; see [EditorState.enabledPairsAt].
 */
internal data class BracketPair(
    val open: String,
    val close: String,
    val autoClose: Boolean,
    val surround: Boolean,
    val newline: Boolean,
    val notIn: List<String>,
) {
    /** Quotes are their own closer, which changes how they may be typed. */
    val isQuote: Boolean get() = open == close

    /**
     * Whether typing [text] can open this pair — the pair's opener ends with
     * it, and it is not a word.
     *
     * The word test is why `do`/`done` and `then`/`fi` are in the shell
     * config without every `o` and `n` typed in a shell script taking the
     * pair path: those pairs exist for matching and for Enter, not for
     * typing, and Zed's own autoclose never fires on them either.
     */
    fun openedByTyping(text: String): Boolean =
        open.endsWith(text) && !open.last().isLetterOrDigit()
}

/** A language's block comment: `/*` … `*/`, `<!--` … `-->`. */
internal data class BlockComment(val start: String, val end: String)

/**
 * One language's editing rules, as the engine reads them out of the vendored
 * grammar's own `config.toml`.
 *
 * **Nothing in this file decides what a language's rules are.** It parses what
 * `CoreBridge.languageConfig` hands over and applies it. The table that used
 * to live here claimed to mirror those configs and did not: one
 * `autocloseBefore` string for every language where six of them differ, no
 * `not_in` at all, and a `'` pair for JSON that its grammar has never had.
 *
 * The one rule applied here rather than in the engine is
 * [increaseIndentPattern], and the reason is the keystroke path: the pattern
 * is data from the same config, but Enter is a keypress, and crossing JNI on
 * every one of them is exactly the chatter the bridge forbids. It is compiled
 * once per language, next to the config it came from.
 */
internal data class LanguageConfig(
    /** The language's display name ("Rust"), or "" when there is none. */
    val name: String,
    val lineComments: List<String>,
    val blockComment: BlockComment?,
    val brackets: List<BracketPair>,
    val autocloseBefore: String,
    val hardTabs: Boolean,
    val increaseIndentPattern: String?,
) {
    /**
     * The token `toggle comment` writes. Zed uses the first of
     * `line_comments` and keeps the rest for continuing a comment onto the
     * next line, which we do not do yet.
     */
    val lineComment: String? get() = lineComments.firstOrNull()

    private val increaseIndent: Regex? by lazy {
        increaseIndentPattern?.let { pattern ->
            runCatching { Regex(pattern) }.getOrNull()
        }
    }

    /**
     * Whether a line ending in [text] opens an indented block — Python's
     * trailing colon, a shell `do`/`then`, a YAML key with nothing after it.
     * Languages whose blocks are brackets have no pattern and answer false;
     * the bracket's own [BracketPair.newline] covers them.
     */
    fun opensBlock(text: String): Boolean = increaseIndent?.containsMatchIn(text) == true

    /** The pair typing [text] opens, longest opener first (`f"` beats `"`). */
    fun opener(text: String): BracketPair? =
        brackets.filter { it.openedByTyping(text) }.maxByOrNull { it.open.length }

    /** The pair [text] closes, if any. Quotes answer to both. */
    fun closer(text: String): BracketPair? = brackets.firstOrNull { it.close == text }

    /**
     * The longest pair whose opener [text] ends with — what Enter looks for
     * behind the caret, so `r#"` beats `"`.
     *
     * Word openers are left out for the same reason [BracketPair.openedByTyping]
     * leaves them out: a shell's `do`/`done` pair would make `echo begin`
     * open a block, and the shell's own `increase_indent_pattern` already
     * recognises a real `do` with the word boundaries this cannot see.
     */
    fun openerBefore(text: String): BracketPair? =
        brackets
            .filter { !it.open.last().isLetterOrDigit() && text.endsWith(it.open) }
            .maxByOrNull { it.open.length }

    /**
     * Every single-character bracket or quote the language pairs, which is
     * what a word deletion refuses to cross — Zed's `adjust_greedy_deletion`
     * stops at the brackets its grammar's `brackets.scm` names
     * (crates/editor/src/movement.rs:299-350), and those queries list the
     * quotes beside the brackets. Multi-character openers — Rust's raw
     * string prefix, the block comment's — are left out: their halves are
     * punctuation runs a boundary already stops at.
     */
    val bracketCharacters: Set<Char> by lazy {
        brackets.flatMap { listOf(it.open, it.close) }.filter { it.length == 1 }.map { it[0] }.toSet()
    }

    /** Whether [text] is either half of any pair, so typing it needs care. */
    fun isPairCharacter(text: String): Boolean =
        brackets.any { it.openedByTyping(text) || it.close == text }

    /** The pairs typing [text] could touch, by index into [brackets]. */
    fun pairsTriggeredBy(text: String): List<Int> =
        brackets.indices.filter { index ->
            val pair = brackets[index]
            pair.openedByTyping(text) || pair.close == text
        }
}

/**
 * The parsed configs, one per grammar, for the life of the process.
 *
 * Keys are grammar names as `BufferSession.language` reports them, which is
 * the engine's registry name: a `.js` file parses with the `tsx` grammar and
 * therefore answers "tsx", not "javascript". The two configs differ only in
 * their name and their Prettier parser.
 */
internal object EditorLanguage {

    /**
     * What a buffer with no grammar gets.
     *
     * No comment token and no indent rule — inventing those for a file we
     * cannot parse would be guessing — but the **brackets stay**. "No
     * grammar" is not a rare case here: `engine::language_for_path` answers
     * `None` for `.kt`, `.java`, `.kts`, `.toml`, `.xml`, `.html` and plain
     * text, which in an IDE for Android is most of what anyone opens. Taking
     * auto-close away from them to be principled about it would make the
     * editor worse at its main job, and a bracket pairs with its partner in
     * every language anyone writes.
     *
     * `autocloseBefore` is Zed's own default set (crates/language/src/
     * language_registry.rs), which is what its grammar-less buffers use too.
     */
    val None = LanguageConfig(
        name = "",
        lineComments = emptyList(),
        blockComment = null,
        brackets = listOf(
            BracketPair("(", ")", autoClose = true, surround = true, newline = true, notIn = emptyList()),
            BracketPair("[", "]", autoClose = true, surround = true, newline = true, notIn = emptyList()),
            BracketPair("{", "}", autoClose = true, surround = true, newline = true, notIn = emptyList()),
            BracketPair("\"", "\"", autoClose = true, surround = true, newline = false, notIn = emptyList()),
            BracketPair("'", "'", autoClose = true, surround = true, newline = false, notIn = emptyList()),
        ),
        autocloseBefore = ";:.,=}])>\n\t ",
        hardTabs = false,
        increaseIndentPattern = null,
    )

    private val cache = HashMap<String, LanguageConfig>()

    /**
     * The rules for [language], fetching them through [fetch] — one bridge
     * call per grammar, ever — and parsing them once.
     *
     * Synchronized because two editor panes can open files of the same
     * language on different threads, and the whole point of the cache is that
     * the second one does not cross the bridge.
     */
    @Synchronized
    fun configFor(language: String?, fetch: () -> String?): LanguageConfig {
        if (language == null) return None
        cache[language]?.let { return it }
        val config = parse(fetch())
        cache[language] = config
        return config
    }

    /** Visible for tests, which have no engine to fetch a config from. */
    internal fun parse(json: String?): LanguageConfig {
        if (json == null) return None
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return None
        val lineComments = root.optJSONArray("line_comments")
        val brackets = root.optJSONArray("brackets")
        return LanguageConfig(
            name = root.optString("name"),
            lineComments = List(lineComments?.length() ?: 0) { lineComments!!.getString(it) },
            blockComment = root.optJSONObject("block_comment")?.let {
                BlockComment(it.getString("start"), it.getString("end"))
            },
            brackets = List(brackets?.length() ?: 0) { index ->
                val pair = brackets!!.getJSONObject(index)
                val notIn = pair.optJSONArray("not_in")
                BracketPair(
                    open = pair.getString("start"),
                    close = pair.getString("end"),
                    autoClose = pair.optBoolean("close"),
                    surround = pair.optBoolean("surround", true),
                    newline = pair.optBoolean("newline"),
                    notIn = List(notIn?.length() ?: 0) { notIn!!.getString(it) },
                )
            },
            autocloseBefore = root.optString("autoclose_before"),
            hardTabs = root.optBoolean("hard_tabs"),
            increaseIndentPattern = root.optString("increase_indent_pattern").takeIf {
                it.isNotEmpty() && !root.isNull("increase_indent_pattern")
            },
        )
    }
}
