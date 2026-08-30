package to.eyed.seeker.code.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * One piece of context attached to a prompt — Zed's `MentionUri`
 * (acp_thread/src/mention.rs:20-77), in the kinds the composer's `@` picker
 * offers (agent_ui's context picker: Files, Directories, Symbols, Threads,
 * Fetch, Rules, and the editor's selection).
 *
 * Data only. What each becomes on the wire is the engine's business
 * (`acp_thread::Mention`, `acp::prompt_blocks`); this side sends the
 * *reference* — a path, a session id, a URL — and the engine reads what it
 * points at, except for the two whose text lives here: the selection, which
 * is the editor's, and a fetched page, which is the network's.
 */
sealed class AgentMention {
    /** The one word that identifies the kind, for the chip and the wire. */
    abstract val kind: String

    /** What the chip prints. */
    abstract val label: String

    /**
     * The `@token` that stands in the composer text for this mention, when
     * one does — a file or directory typed as `@src/main.rs` keeps living in
     * the text, and deleting the token is deleting the mention (the prefix
     * bug `mentionTokensIn` exists for). Null for the kinds that live only as
     * a chip: a selection or a fetched page has no path to type.
     */
    open val textToken: String? get() = null

    /**
     * The engine's JSON for this mention — `{"kind": …, …}` in the tagged
     * shape `acp_thread::Mention` deserializes.
     */
    abstract fun toJson(): JSONObject

    data class File(val path: String) : AgentMention() {
        override val kind: String get() = "file"
        override val label: String get() = path.substringAfterLast('/')
        override val textToken: String get() = path
        override fun toJson(): JSONObject = JSONObject().put("kind", kind).put("path", path)
    }

    data class Directory(val path: String) : AgentMention() {
        override val kind: String get() = "directory"
        override val label: String get() = path.trimEnd('/').substringAfterLast('/') + "/"
        override val textToken: String get() = path.trimEnd('/') + "/"
        override fun toJson(): JSONObject = JSONObject().put("kind", kind).put("path", path)
    }

    /** A symbol from a buffer's outline; rows are 0-based and inclusive. */
    data class Symbol(
        val path: String,
        val name: String,
        val startRow: Int,
        val endRow: Int,
    ) : AgentMention() {
        override val kind: String get() = "symbol"
        override val label: String get() = name
        override fun toJson(): JSONObject = JSONObject()
            .put("kind", kind)
            .put("path", path)
            .put("name", name)
            .put("start_row", startRow)
            .put("end_row", endRow)
    }

    /** The editor's selection; rows 0-based and inclusive, text as selected. */
    data class Selection(
        val path: String,
        val startRow: Int,
        val endRow: Int,
        val text: String,
    ) : AgentMention() {
        override val kind: String get() = "selection"
        override val label: String
            get() = "${path.substringAfterLast('/')} L${startRow + 1}–${endRow + 1}"
        override fun toJson(): JSONObject = JSONObject()
            .put("kind", kind)
            .put("path", path)
            .put("start_row", startRow)
            .put("end_row", endRow)
            .put("text", text)
    }

    /** Another thread, by its engine session id; the engine writes the summary. */
    data class Thread(val sessionId: Long, val title: String) : AgentMention() {
        override val kind: String get() = "thread"
        override val label: String get() = title
        override fun toJson(): JSONObject = JSONObject().put("kind", kind).put("session", sessionId)
    }

    /** A web page, fetched by [FetchMention] and reduced to text. */
    data class Fetch(val url: String, val text: String) : AgentMention() {
        override val kind: String get() = "fetch"
        override val label: String get() = url.removePrefix("https://").removePrefix("http://")
        override fun toJson(): JSONObject =
            JSONObject().put("kind", kind).put("url", url).put("text", text)
    }

    /** A rules file at the project root — see [RulesFiles]. */
    data class Rules(val path: String) : AgentMention() {
        override val kind: String get() = "rules"
        override val label: String get() = path
        override val textToken: String get() = path
        override fun toJson(): JSONObject = JSONObject().put("kind", kind).put("path", path)
    }

    /** The project's diagnostics; the engine writes the text. */
    data object Diagnostics : AgentMention() {
        override val kind: String get() = "diagnostics"
        override val label: String get() = "Diagnostics"
        override fun toJson(): JSONObject = JSONObject().put("kind", kind)
    }

    companion object {
        /** The whole list as the JSON array [CoreBridge.acpPrompt] takes. */
        fun toJson(mentions: List<AgentMention>): String =
            JSONArray().apply { mentions.forEach { put(it.toJson()) } }.toString()
    }
}

/**
 * The rules files an agent should read — Zed's `RULES_FILE_NAMES`
 * (prompt_store/src/prompts.rs:22-32), looked for at the project root.
 *
 * Attached to a thread's *first* prompt by the panel, once, so the agent
 * starts with the project's own instructions; and offered under Rules in the
 * `@` picker for any prompt after that.
 */
object RulesFiles {
    val NAMES: List<String> = listOf(
        ".rules",
        ".cursorrules",
        ".windsurfrules",
        ".clinerules",
        ".github/copilot-instructions.md",
        "AGENT.md",
        "AGENTS.md",
        "CLAUDE.md",
        "GEMINI.md",
    )

    /** The rules files that exist under [rootPath], as project-relative paths. */
    fun present(rootPath: String): List<String> =
        NAMES.filter { File(rootPath, it).isFile }

    /** [present] as mentions, minus any already attached. */
    fun mentions(rootPath: String, already: List<AgentMention>): List<AgentMention> {
        val attached = already.mapNotNull { it.textToken }.toSet()
        return present(rootPath)
            .filter { it !in attached }
            .map { AgentMention.Rules(it) }
    }
}

/**
 * Fetching a page for a `@fetch` mention — Zed's Fetch context, which pulls
 * the URL and hands the agent its text (agent_ui's `FetchContextPicker`).
 *
 * **Only when the user picks it.** Nothing here runs until the Fetch row is
 * tapped, and the row appears only for something that looks like an https
 * URL: the editor makes no request the user did not ask for.
 */
object FetchMention {
    /** Zed limits what it pulls; a page bigger than this is cut, not refused. */
    private const val MAX_BYTES = 1024 * 1024
    private const val TIMEOUT_MS = 10_000

    /** Whether [text] is something the Fetch row should offer. */
    fun looksLikeUrl(text: String): Boolean =
        text.startsWith("https://") && text.length > "https://".length && ' ' !in text

    /**
     * GET [url] and reduce it to plain text. **Blocking** — call it off the
     * main thread. Null when the request failed, with the reason logged by
     * the caller.
     */
    fun fetch(url: String): Result<AgentMention.Fetch> = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "text/html, text/plain;q=0.9, */*;q=0.5")
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            val bytes = connection.inputStream.use { it.readNBytesCompat(MAX_BYTES) }
            val body = String(bytes, Charsets.UTF_8)
            val type = connection.contentType.orEmpty()
            val text = if ("html" in type || body.trimStart().startsWith("<")) {
                htmlToText(body)
            } else {
                body
            }
            AgentMention.Fetch(url, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * HTML as readable text: scripts and styles gone, block tags as line
     * breaks, the rest of the tags dropped, the common entities decoded and
     * runs of blank lines folded. A markdown converter is what Zed uses
     * (`html_to_markdown`); this is the small honest subset a phone can
     * carry, and enough for an agent to read a page.
     */
    fun htmlToText(html: String): String {
        var text = html
        text = text.replace(Regex("(?is)<(script|style|noscript|template)[^>]*>.*?</\\1>"), " ")
        text = text.replace(Regex("(?is)<!--.*?-->"), " ")
        text = text.replace(Regex("(?i)<br\\s*/?>"), "\n")
        text = text.replace(
            Regex("(?i)</?(p|div|h[1-6]|li|ul|ol|tr|table|section|article|header|footer|pre|blockquote|nav|main|aside|dd|dt|dl|hr|form)\\b[^>]*>"),
            "\n",
        )
        text = text.replace(Regex("(?s)<[^>]+>"), "")
        text = decodeEntities(text)
        text = text.replace(Regex("[ \\t\\u00A0]+"), " ")
        text = text.replace(Regex(" ?\\n ?"), "\n")
        text = text.replace(Regex("\\n{3,}"), "\n\n")
        return text.trim()
    }

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "copy" to "©", "laquo" to "«", "raquo" to "»", "lsquo" to "‘", "rsquo" to "’",
        "ldquo" to "“", "rdquo" to "”",
    )

    private fun decodeEntities(text: String): String =
        Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x") -> body.drop(2).toIntOrNull(16)?.let(::codePoint) ?: match.value
                body.startsWith("#") -> body.drop(1).toIntOrNull()?.let(::codePoint) ?: match.value
                else -> NAMED_ENTITIES[body] ?: match.value
            }
        }

    private fun codePoint(value: Int): String =
        if (Character.isValidCodePoint(value)) String(Character.toChars(value)) else ""
}
