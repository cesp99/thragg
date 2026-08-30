package to.eyed.seeker.code.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * The line break a file uses — Zed's `text::LineEnding`, whose labels these
 * are (`text/src/text.rs:3604-3609`). The engine's buffers hold `\n` whatever
 * the file does; this is what the save writes back.
 */
enum class LineEnding(
    /** What travels over JNI. */
    val key: String,
    /** What the status bar and the picker show. */
    val label: String,
) {
    Unix("lf", "LF"),
    Windows("crlf", "CRLF");

    companion object {
        fun fromKey(key: String): LineEnding? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The encoding a file is read and written in, by its WHATWG name
 * (`"UTF-8"`, `"UTF-16LE"`, `"windows-1252"` — `encoding_rs::Encoding::name`),
 * and whether it opens with a byte-order mark.
 */
data class BufferEncoding(val name: String, val hasBom: Boolean) {

    /**
     * The status-bar label: the name with its byte-order mark noted, as Zed's
     * indicator writes it (`active_buffer_encoding.rs:72-75`, where the suffix
     * is " (BOM)"), spelled the way people write these — `UTF-16 LE` rather
     * than the WHATWG `UTF-16LE`, `Windows-1252` capitalised — so the bar
     * reads as a word and not as a label.
     */
    val label: String get() = encodingLabel(name, hasBom)

    companion object {
        /** The engine's `{"name": "UTF-8", "bom": true}`. */
        fun fromJson(json: String): BufferEncoding {
            val obj = JSONObject(json)
            return BufferEncoding(obj.getString("name"), obj.optBoolean("bom", false))
        }
    }
}

/** The names in an engine `availableEncodings` answer, in its order. */
fun parseEncodingNames(json: String): List<String> {
    val array = JSONArray(json)
    return List(array.length()) { array.getString(it) }
}

/** The label for one encoding name, with or without its byte-order mark. */
fun encodingLabel(name: String, hasBom: Boolean): String {
    val spelled = when {
        name.equals("UTF-16LE", ignoreCase = true) -> "UTF-16 LE"
        name.equals("UTF-16BE", ignoreCase = true) -> "UTF-16 BE"
        name.startsWith("windows-") -> "Windows-" + name.removePrefix("windows-")
        name.startsWith("x-") -> name
        // Every other label is an acronym or a registry name that is already
        // written in capitals where it should be ("Big5", "EUC-JP", "KOI8-R").
        else -> name
    }
    return if (hasBom) "$spelled BOM" else spelled
}

/**
 * Filter encoding names by a query, keeping the engine's order: the list is
 * short enough to read whole, and reshuffling it as one types costs the place
 * one had found — the theme selector's reasoning (`ThemeSelector.kt`). A
 * match is a case-insensitive subsequence, and the positions it lands on are
 * returned for highlighting, as Zed's `HighlightedLabel` does with the
 * picker's `StringMatch` (`encoding_selector.rs:325-336`).
 */
fun matchEncodings(names: List<String>, query: String): List<EncodingMatch> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return names.map { EncodingMatch(it, emptyList()) }
    return names.mapNotNull { name ->
        subsequencePositions(encodingLabel(name, false), trimmed)?.let { EncodingMatch(name, it) }
    }
}

/** An encoding that matched, and where in its label it did. */
data class EncodingMatch(val name: String, val positions: List<Int>)

private fun subsequencePositions(text: String, query: String): List<Int>? {
    val positions = ArrayList<Int>(query.length)
    var at = 0
    for (character in query) {
        // Separators are not looked for: the labels put a space or a hyphen
        // where the names do not ("UTF-16 LE", "windows-1252"), and a query
        // typed by ear — "utf 16le", "utf16-le" — should find them all the same.
        if (character == ' ' || character == '-') continue
        val found = text.indexOf(character, at, ignoreCase = true)
        if (found < 0) return null
        positions += found
        at = found + 1
    }
    return positions
}
