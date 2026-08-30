package to.eyed.seeker.code.core

import org.json.JSONArray

/**
 * One language the engine can parse: the grammar name it is addressed by and
 * the display name Zed shows.
 */
data class LanguageChoice(
    /** What [BufferSession.setLanguage] takes — "rust", "tsx". */
    val grammar: String,
    /** From the language's `config.toml` — "Rust", "TSX". */
    val name: String,
)

/**
 * The bundled languages, read from the engine once.
 *
 * Zed's language selector lists what its `LanguageRegistry` holds
 * (language_selector/src/language_selector.rs); ours are the grammars
 * compiled into the binary, which is the same list for our purposes — a
 * language we cannot parse is not one a buffer can be switched to.
 *
 * The list never changes for the life of the process, so it is read on the
 * first ask and kept. [all] is **blocking** on that first call (one JNI hop
 * and a JSON parse of a few dozen rows); call it off the main thread.
 */
object Languages {
    @Volatile
    private var cached: List<LanguageChoice>? = null

    fun all(): List<LanguageChoice> {
        cached?.let { return it }
        val json = JSONArray(CoreBridge.availableLanguages())
        val languages = List(json.length()) { index ->
            val entry = json.getJSONObject(index)
            LanguageChoice(
                grammar = entry.getString("grammar"),
                name = entry.getString("name"),
            )
        }
        cached = languages
        return languages
    }

    /**
     * The display name for a grammar, or the grammar itself when it is not
     * one we know — which is what the engine falls back to as well.
     *
     * Reads whatever [all] has already cached and never asks the engine,
     * so the status bar can call it while drawing.
     */
    fun displayName(grammar: String): String =
        cached?.firstOrNull { it.grammar == grammar }?.name ?: grammar
}
