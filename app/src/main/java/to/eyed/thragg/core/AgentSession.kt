package to.eyed.thragg.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The agent panel's side of [CoreBridge]'s ACP contract.
 *
 * Everything here is parsing and merging — no drawing, no engine calls beyond
 * the bridge — so the shapes that actually go wrong can be tested on the host.
 * The engine owns the conversation; this owns the *reading* of it, which on a
 * long transcript is the part that has to stay cheap: the engine hands back
 * only the rows whose revision moved, and [AgentConversation.apply] merges
 * them in place.
 *
 * `org.json` trap, and the reason every optional string here goes through
 * [stringOrNull]: on Android `optString(name, null)` returns the **string**
 * `"null"` for a JSON null (agent-docs/CONVENTIONS.md § Traps). Every nullable
 * field below is a real null in the engine's JSON — an agent that has not
 * named itself, a session with no error — so getting this wrong would put the
 * word "null" on screen.
 */
private fun JSONObject.stringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

/** Where a session is in its life; `phase` in the engine's state JSON. */
enum class AgentPhase {
    /** Spawning, initializing, or asking for a session. Cannot be prompted. */
    Starting,

    /** Waiting for a prompt. */
    Ready,

    /** A turn is in flight. */
    Running,

    /** Over — the agent exited, refused, or wants signing in to. */
    Unavailable;

    internal companion object {
        fun parse(text: String?): AgentPhase = when (text) {
            "ready" -> Ready
            "running" -> Running
            "unavailable" -> Unavailable
            else -> Starting
        }
    }
}

/** A tool call's state; `status` on a `tool_call` entry. */
enum class ToolCallStatus {
    Pending,
    WaitingForConfirmation,
    InProgress,
    Completed,
    Failed,
    Rejected,
    Canceled;

    /** Whether it is still going somewhere, for a spinner. */
    val isMoving: Boolean get() = this == Pending || this == InProgress

    internal companion object {
        fun parse(text: String?): ToolCallStatus = when (text) {
            "waiting_for_confirmation" -> WaitingForConfirmation
            "in_progress" -> InProgress
            "completed" -> Completed
            "failed" -> Failed
            "rejected" -> Rejected
            "canceled" -> Canceled
            else -> Pending
        }
    }
}

/**
 * What kind of thing a tool call is doing — an icon, not the row's kind.
 *
 * ACP's own list (`ToolKind`), which is what Zed picks its icons from too.
 * Anything unrecognised is [Other], because the enum is open on the wire.
 */
enum class ToolKind {
    Read, Edit, Delete, Move, Search, Execute, Think, Fetch, SwitchMode, Other;

    internal companion object {
        fun parse(text: String?): ToolKind = when (text) {
            "read" -> Read
            "edit" -> Edit
            "delete" -> Delete
            "move" -> Move
            "search" -> Search
            "execute" -> Execute
            "think" -> Think
            "fetch" -> Fetch
            "switch_mode" -> SwitchMode
            else -> Other
        }
    }
}

/** One choice at a permission prompt. */
data class PermissionOption(
    val id: String,
    val name: String,
    /** `allow_once`, `allow_always`, `reject_once`, `reject_always`. */
    val kind: String,
    /**
     * The agent's own pick — `_meta["spettro.app/isRecommended"]`.
     *
     * Badged, never preselected. Spettro walks an ask-user form through the
     * permission channel when the client did not advertise the question
     * extension, and its recommendation is editorial: choosing it for the
     * user would put words in their mouth that the model then treats as
     * theirs.
     */
    val isRecommended: Boolean = false,
    /**
     * `_meta["spettro.app/isCustomInput"]` — the "let me type my own answer"
     * option, whose id is `"custom"`. Picking it means the reply carries the
     * user's text in `_meta["spettro.app/questionAnswer"]`, not just an id.
     */
    val isCustomInput: Boolean = false,
) {
    val isAllow: Boolean get() = kind.startsWith("allow")

    internal companion object {
        fun parse(json: JSONObject) = PermissionOption(
            // ACP's own wire shape is camelCase, unlike everything else the
            // engine sends: these objects are the protocol's, passed through.
            id = json.optString("optionId"),
            name = json.optString("name"),
            kind = json.optString("kind"),
            // `_meta` was being dropped wholesale, which is what reduced a
            // Spettro question walked over permissions to five identical
            // grey buttons (SPETTRO.md W-10).
            isRecommended = json.optJSONObject("_meta")
                ?.optBoolean("spettro.app/isRecommended") == true,
            isCustomInput = json.optJSONObject("_meta")
                ?.optBoolean("spettro.app/isCustomInput") == true,
        )
    }
}

/** A file the agent says it is working in. */
data class AgentLocation(val path: String, val line: Int?)

/** One piece of a tool call's output. */
sealed interface ToolContent {
    data class Markdown(val markdown: String) : ToolContent

    /**
     * A file edit, already in the shape the git diff view draws — so the
     * panel's expandable diff is the same renderer a commit gets, rather than
     * a second one that drifts from it.
     */
    data class Diff(val file: FileDiff) : ToolContent

    /**
     * A command the agent asked the editor to run, through `terminal/create`.
     *
     * Only the id is here, because only the id travels in the protocol: the
     * command line, the output and the exit status are read live from the
     * engine by [CoreBridge.acpTerminalOutput]. Keeping them out of the entry
     * is what stops a growing build log re-sending the whole card on every
     * chunk of output.
     */
    data class Terminal(
        val terminalId: String,
        /**
         * What the command printed, sealed onto the entry when the agent
         * released its terminal.
         *
         * The engine keeps only the last few released terminals resident, so
         * the transcript cannot depend on the registry: this is the card's
         * copy, and the live poll is only an optimisation while the command
         * is still running. Null means it is still live.
         */
        val sealed: AgentTerminalState? = null,
    ) : ToolContent
}

/** A live agent terminal, as [CoreBridge.acpTerminalOutput] reports it. */
/** What a turn has cost, when the agent says. Currency is the agent's string. */
data class AgentCost(val amount: Double, val currency: String)

/**
 * What kind of failure the session's `error` is.
 *
 * The panel needs it to say the right sentence and offer the right way out: a
 * rate limit is worth retrying, a context overflow needs a new thread, and a
 * dead transport means the agent is gone and nothing will help but restarting
 * it. One red line for all of them is what we had.
 */
enum class AgentErrorKind {
    RateLimit, Auth, ContextWindow, Refusal, Transport, Api, Other;

    /** The heading to put over the error, in the user's words. */
    val heading: String
        get() = when (this) {
            RateLimit -> "The provider is rate-limiting"
            Auth -> "The agent needs signing in to"
            ContextWindow -> "This conversation is too long to continue"
            Refusal -> "The agent declined"
            Transport -> "The agent is gone"
            Api -> "The provider returned an error"
            Other -> "Something went wrong"
        }

    /** What to do about it, when there is something to say. */
    val advice: String?
        get() = when (this) {
            RateLimit -> "Wait a moment and try again."
            ContextWindow -> "Start a new thread; this one cannot take any more."
            Transport -> "Start a new thread to bring it back."
            else -> null
        }

    internal companion object {
        fun parse(text: String?): AgentErrorKind? = when (text) {
            "rate_limit" -> RateLimit
            "auth" -> Auth
            "context_window" -> ContextWindow
            "refusal" -> Refusal
            "transport" -> Transport
            "api" -> Api
            "other" -> Other
            else -> null
        }
    }
}

data class AgentTerminalState(
    val revision: Long,
    val label: String,
    /** Where it ran. A command's meaning depends on its directory. */
    val cwd: String,
    val output: String,
    val truncated: Boolean,
    val running: Boolean,
    /** Null while it is still running, or when the wait itself failed. */
    val exitCode: Int?,
    /** The signal that ended it, when one did. */
    val signal: String?,
    /** How long it has run, or ran — frozen at the exit. */
    val elapsedMs: Long = 0,
    /** How much was dropped off the front to stay under the cap. */
    val droppedBytes: Long = 0,
    val droppedLines: Long = 0,
) {
    /**
     * "2.4 MB of earlier output dropped from the start", or null.
     *
     * The amount is the point: "some output was dropped" tells the reader
     * nothing about whether to go looking elsewhere for it.
     */
    val droppedSentence: String?
        get() = when {
            !truncated -> null
            droppedBytes <= 0 -> "Earlier output dropped from the start."
            else -> {
                val size = when {
                    droppedBytes >= 1024L * 1024 -> "%.1f MB".format(droppedBytes / 1048576.0)
                    droppedBytes >= 1024 -> "${droppedBytes / 1024} kB"
                    else -> "$droppedBytes bytes"
                }
                val lines = if (droppedLines > 0) " ($droppedLines lines)" else ""
                "$size$lines of earlier output dropped from the start."
            }
        }

    /** "4m 12s", or null when it is not worth saying. */
    val elapsedLabel: String?
        get() {
            val seconds = elapsedMs / 1000
            return when {
                seconds < 2 -> null
                seconds < 60 -> "${seconds}s"
                seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
                else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            }
        }

    companion object {
        /** What a terminal the engine no longer has looks like. */
        val Gone = AgentTerminalState(0, "", "", "", false, false, null, null)

        /**
         * Parse one poll. Returns [previous] unchanged when the revision has
         * not moved — the engine sends no payload in that case, and the whole
         * point of the revision is that this is the common answer.
         */
        fun parse(json: String, previous: AgentTerminalState?): AgentTerminalState {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return Gone
            val revision = root.optLong("revision")
            if (revision == 0L) return Gone
            if (previous != null && previous.revision == revision) return previous
            val exit = root.optJSONObject("exitStatus")
            return AgentTerminalState(
                revision = revision,
                label = root.optString("label"),
                cwd = root.optString("cwd"),
                output = root.optString("output"),
                truncated = root.optBoolean("truncated"),
                running = root.optBoolean("running"),
                exitCode = exit?.takeIf { !it.isNull("exitCode") }?.optInt("exitCode"),
                signal = exit?.takeIf { !it.isNull("signal") }?.optString("signal"),
                elapsedMs = root.optLong("elapsedMs"),
                droppedBytes = root.optLong("droppedBytes"),
                droppedLines = root.optLong("droppedLines"),
            )
        }
    }
}

/**
 * Terminal output with its escape sequences taken out.
 *
 * Agents run `cargo test`, jest and eslint, and those tools colour their
 * output whenever they think a terminal is watching — which lands in the card
 * as escape-code noise wrapped around every word. This is the *display* path
 * only: what the agent reads back over `terminal/output` stays byte-faithful,
 * because the agent is parsing it.
 *
 * Handles the two sequence families that actually appear: CSI (ESC `[` … a
 * final byte — colour, cursor movement, erase) and OSC (ESC `]` … BEL or
 * ESC `\` — window titles, hyperlinks). Any other escape is dropped with the
 * byte after it, which covers the short two-character ones.
 */
fun stripAnsi(text: String): String {
    // A carriage return counts too: a progress bar rewrites its line without
    // ever emitting an escape, and leaving the CR in makes the whole line
    // vanish in a Compose Text.
    if (!text.contains(ESC) && !text.contains('\r')) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (ch != ESC) {
            // A bare carriage return is a progress bar rewriting its line.
            // Keeping it makes the whole line vanish in a Compose Text, so
            // restart the line: the last state written is the one to show.
            if (ch == '\r' && i + 1 < text.length && text[i + 1] != '\n') {
                out.setLength(out.lastIndexOf("\n") + 1)
            } else if (ch != '\r') {
                out.append(ch)
            }
            i++
            continue
        }
        if (i + 1 >= text.length) break
        when (text[i + 1]) {
            '[' -> {
                // CSI: parameters and intermediates, then one final byte in
                // @-~. One cut off by a chunk boundary is dropped, not
                // printed.
                var j = i + 2
                while (j < text.length && text[j] !in '\u0040'..'\u007E') j++
                i = if (j < text.length) j + 1 else text.length
            }
            ']' -> {
                // OSC: runs to BEL, or to ESC-backslash.
                var j = i + 2
                while (j < text.length && text[j] != BEL) {
                    if (text[j] == ESC && j + 1 < text.length && text[j + 1] == '\\') {
                        j++
                        break
                    }
                    j++
                }
                i = if (j < text.length) j + 1 else text.length
            }
            else -> i += 2
        }
    }
    return out.toString()
}

private const val ESC = '\u001B'
private const val BEL = '\u0007'

/** One choice in a select or multi-select elicitation field. */
data class ElicitationOption(val value: String, val title: String, val description: String?)

/**
 * One field of a form elicitation, already flattened by the engine.
 *
 * The engine reads the schema and hands over exactly what a form needs, so
 * these rules are implemented once rather than once per side of JNI —
 * `type` is `string`/`number`/`integer`/`boolean`/`array`, and `options`
 * turns a string field into a picker and an array field into a checklist.
 */
data class ElicitationField(
    val key: String,
    val type: String,
    val title: String?,
    val description: String?,
    val required: Boolean,
    val options: List<ElicitationOption>,
    /** `email`, `uri`, `date` or `date-time`, when the schema said so. */
    val format: String?,
    /** A regex the answer must match, when the agent set one. */
    val pattern: String?,
    val defaultString: String?,
    val defaultNumber: Double?,
    val defaultBoolean: Boolean?,
    val defaultList: List<String>,
    val minimum: Double?,
    val maximum: Double?,
    val minLength: Int?,
    val maxLength: Int?,
    val minItems: Int?,
    val maxItems: Int?,
) {
    /** The label to show; the schema's title, else the property name. */
    val label: String get() = title?.takeIf { it.isNotBlank() } ?: key

    /** A field this build has no control for — shown, never silently dropped. */
    val isUnsupported: Boolean get() = type == "unsupported"
}

/**
 * A question the agent is waiting on.
 *
 * Two modes, and they behave differently on purpose. A **form** is over the
 * moment it is answered. A **url** stays on screen after Accept, because the
 * agent is watching for the sign-in and takes the card away itself with
 * `elicitation/complete` — [accepted] is true in that gap, which is what the
 * card shows as "waiting for the agent".
 */
data class AgentElicitation(
    val id: String,
    val mode: String,
    val message: String,
    val title: String?,
    val description: String?,
    val url: String?,
    /** The tool call it belongs to, when the agent said. */
    val toolCallId: String?,
    val fields: List<ElicitationField>,
    val accepted: Boolean,
) {
    val isForm: Boolean get() = mode == "form"
    val isUrl: Boolean get() = mode == "url"
}

internal fun parseElicitations(array: JSONArray?): List<AgentElicitation> {
    val items = array ?: return emptyList()
    return (0 until items.length()).mapNotNull { index ->
        val json = items.optJSONObject(index) ?: return@mapNotNull null
        AgentElicitation(
            id = json.optString("id"),
            mode = json.optString("mode"),
            message = json.optString("message"),
            title = json.stringOrNull("title"),
            description = json.stringOrNull("description"),
            url = json.stringOrNull("url"),
            toolCallId = json.stringOrNull("toolCallId"),
            fields = parseElicitationFields(json.optJSONArray("fields")),
            accepted = json.optBoolean("accepted"),
        ).takeIf { it.id.isNotEmpty() }
    }
}

private fun parseElicitationFields(array: JSONArray?): List<ElicitationField> {
    val items = array ?: return emptyList()
    return (0 until items.length()).mapNotNull { index ->
        val json = items.optJSONObject(index) ?: return@mapNotNull null
        val options = json.optJSONArray("options") ?: JSONArray()
        val defaults = json.optJSONArray("default")
        ElicitationField(
            key = json.optString("key"),
            type = json.optString("type"),
            title = json.stringOrNull("title"),
            description = json.stringOrNull("description"),
            required = json.optBoolean("required"),
            options = (0 until options.length()).mapNotNull { at ->
                val option = options.optJSONObject(at) ?: return@mapNotNull null
                ElicitationOption(
                    value = option.optString("value"),
                    title = option.optString("title").ifEmpty { option.optString("value") },
                    description = option.stringOrNull("description"),
                )
            },
            format = json.stringOrNull("format"),
            pattern = json.stringOrNull("pattern"),
            // One `default` key carrying whatever the field's type is, so it
            // is read by the field's type rather than guessed at.
            defaultString = if (json.opt("default") is String) json.optString("default") else null,
            defaultNumber = if (json.opt("default") is Number) json.optDouble("default") else null,
            defaultBoolean = if (json.opt("default") is Boolean) {
                json.optBoolean("default")
            } else {
                null
            },
            defaultList = defaults?.let { list ->
                (0 until list.length()).mapNotNull { at -> list.optString(at).takeIf(String::isNotEmpty) }
            } ?: emptyList(),
            minimum = json.numberOrNull("minimum"),
            maximum = json.numberOrNull("maximum"),
            minLength = json.numberOrNull("minLength")?.toInt(),
            maxLength = json.numberOrNull("maxLength")?.toInt(),
            minItems = json.numberOrNull("minItems")?.toInt(),
            maxItems = json.numberOrNull("maxItems")?.toInt(),
        ).takeIf { it.key.isNotEmpty() }
    }
}

/** `optDouble` returns NaN for a missing key, which is not the same as zero. */
private fun JSONObject.numberOrNull(name: String): Double? =
    if (isNull(name) || !has(name)) null else optDouble(name).takeIf { !it.isNaN() }

/**
 * Turning a filled-in form into the JSON [CoreBridge.acpRespondElicitation]
 * takes, and deciding whether it is filled in at all.
 *
 * Here rather than in the panel because the *types* are the load-bearing
 * part: an integer field must go back as a JSON number, not as the text the
 * keyboard produced — the engine passes each value's JSON type straight
 * through to the protocol's own variant, so a string there tells the agent it
 * asked for an integer and got text. That is worth testing on a host, which
 * a composable is not.
 *
 * The panel's values are: [String] for text and numbers, [Boolean] for
 * switches, `List<String>` for multi-selects.
 */
object ElicitationAnswer {

    /** `{"action":"accept","content":{…}}`. */
    fun accept(fields: List<ElicitationField>, values: Map<String, Any?>): String {
        val content = JSONObject()
        for (field in fields) {
            if (field.isUnsupported) continue
            val value = values[field.key]
            when (field.type) {
                "boolean" -> content.put(field.key, value as? Boolean ?: false)

                "array" -> {
                    val chosen = (value as? List<*>)?.filterIsInstance<String>().orEmpty()
                    // An empty optional multi-select is "nothing chosen", and
                    // sending `[]` says that plainly; omitting it would say
                    // "not answered", which is a different claim.
                    content.put(field.key, JSONArray().apply { chosen.forEach { put(it) } })
                }

                "integer" -> (value as? String)?.trim()?.toLongOrNull()
                    ?.let { content.put(field.key, it) }

                "number" -> (value as? String)?.trim()?.toDoubleOrNull()
                    ?.let { content.put(field.key, it) }

                else -> (value as? String)?.takeIf { it.isNotEmpty() }
                    ?.let { content.put(field.key, it) }
            }
        }
        return JSONObject().put("action", "accept").put("content", content).toString()
    }

    fun decline(): String = JSONObject().put("action", "decline").toString()

    fun cancel(): String = JSONObject().put("action", "cancel").toString()

    /**
     * What is wrong with the form, per field key.
     *
     * Every constraint the agent sends was being discarded, so a `number`
     * field with a fractional answer, or a string past its `maxLength`, was a
     * form the user could submit and the agent would reject — with the
     * failure arriving as a turn error rather than beside the field. Zed
     * validates the same rules in the card (elicitation.rs:1103-1266).
     *
     * The messages are deliberately about the *value*, not the schema: "must
     * be a whole number" beats "integer expected".
     */
    fun validate(
        fields: List<ElicitationField>,
        values: Map<String, Any?>,
    ): Map<String, String> {
        val errors = mutableMapOf<String, String>()
        for (field in fields) {
            if (field.isUnsupported) continue
            when (field.type) {
                // A switch always has an answer; false is one.
                "boolean" -> {}

                "array" -> {
                    val chosen = (values[field.key] as? List<*>)?.filterIsInstance<String>().orEmpty()
                    val min = field.minItems
                    val max = field.maxItems
                    when {
                        field.required && chosen.isEmpty() -> errors[field.key] = "Choose at least one."
                        min != null && chosen.size < min ->
                            errors[field.key] = "Choose at least $min."
                        max != null && chosen.size > max ->
                            errors[field.key] = "Choose no more than $max."
                    }
                }

                "integer", "number" -> {
                    val text = (values[field.key] as? String)?.trim().orEmpty()
                    if (text.isEmpty()) {
                        if (field.required) errors[field.key] = "Required."
                        continue
                    }
                    val number = if (field.type == "integer") {
                        text.toLongOrNull()?.toDouble()
                    } else {
                        text.toDoubleOrNull()
                    }
                    val min = field.minimum
                    val max = field.maximum
                    when {
                        number == null -> errors[field.key] = if (field.type == "integer") {
                            "Must be a whole number."
                        } else {
                            "Must be a number."
                        }
                        min != null && number < min -> errors[field.key] = "Must be at least ${trim(min)}."
                        max != null && number > max -> errors[field.key] = "Must be at most ${trim(max)}."
                    }
                }

                else -> {
                    val text = (values[field.key] as? String).orEmpty()
                    if (text.isBlank()) {
                        if (field.required) errors[field.key] = "Required."
                        continue
                    }
                    val min = field.minLength
                    val max = field.maxLength
                    val pattern = field.pattern
                    when {
                        min != null && text.length < min ->
                            errors[field.key] = "At least $min characters."
                        max != null && text.length > max ->
                            errors[field.key] = "At most $max characters."
                        field.options.isNotEmpty() && field.options.none { it.value == text } ->
                            errors[field.key] = "Choose one of the offered values."
                        // A pattern the agent sent that this platform's regex
                        // engine cannot compile is the agent's problem, not
                        // the user's: do not block the form on it.
                        pattern != null &&
                            runCatching { !Regex(pattern).containsMatchIn(text) }.getOrDefault(false) ->
                            errors[field.key] = "Not in the expected format."
                    }
                }
            }
        }
        return errors
    }

    private fun trim(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    /**
     * The required fields that are not answered yet, by label.
     *
     * Kept beside [validate] because it answers a different question: what is
     * *still to do*, which is worth saying before the user has touched
     * anything, rather than what is *wrong*, which is not.
     */
    fun missing(fields: List<ElicitationField>, values: Map<String, Any?>): List<String> =
        fields.filter { field ->
            if (!field.required || field.isUnsupported) {
                false
            } else {
                when (field.type) {
                    "boolean" -> false
                    "array" -> (values[field.key] as? List<*>).isNullOrEmpty()
                    "integer" -> (values[field.key] as? String)?.trim()?.toLongOrNull() == null
                    "number" -> (values[field.key] as? String)?.trim()?.toDoubleOrNull() == null
                    else -> (values[field.key] as? String).isNullOrBlank()
                }
            }
        }.map { it.label }

    /** What a field starts at, from the schema's default. */
    fun initialValue(field: ElicitationField): Any = when (field.type) {
        "boolean" -> field.defaultBoolean ?: false
        "array" -> field.defaultList
        "integer" -> field.defaultNumber?.toLong()?.toString() ?: ""
        "number" -> field.defaultNumber?.let { trimNumber(it) } ?: ""
        else -> field.defaultString ?: ""
    }

    /** `3.0` from a JSON number is the integer 3 to everyone but a parser. */
    private fun trimNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}

/** One chunk of the agent's reply; thoughts fold away. */
data class AssistantChunk(val thought: Boolean, val markdown: String)

/** One row of the conversation. */
sealed interface AgentEntry {
    /**
     * Set on every row after a restored checkpoint: the files those turns
     * edited are back as they were, and the rows are drawn as history the
     * project no longer reflects.
     */
    val reverted: Boolean

    data class User(
        val markdown: String,
        /**
         * Whether "Restore checkpoint" applies here: some file was edited
         * in this message's turn or a later one. Zed's `checkpoint` on a
         * user message (thread_view.rs).
         */
        val checkpoint: Boolean = false,
        override val reverted: Boolean = false,
    ) : AgentEntry

    data class Assistant(
        val chunks: List<AssistantChunk>,
        override val reverted: Boolean = false,
    ) : AgentEntry {
        /** The reply proper, without the reasoning. */
        val spoken: String get() = chunks.filter { !it.thought }.joinToString("") { it.markdown }

        val thoughts: String get() = chunks.filter { it.thought }.joinToString("") { it.markdown }
    }

    data class ToolCall(
        val id: String,
        val title: String,
        val kind: ToolKind,
        val status: ToolCallStatus,
        /** Non-empty only while [status] is [ToolCallStatus.WaitingForConfirmation]. */
        val options: List<PermissionOption>,
        val content: List<ToolContent>,
        val locations: List<AgentLocation>,
        /**
         * The arguments the agent wants to run with, pretty-printed JSON,
         * when it sent them.
         *
         * A permission prompt asks the user to approve a *call*, and "Edit
         * notes.md" does not say what the edit is. Folded away by default —
         * it is JSON, and most calls do not need reading.
         *
         * Last-write-wins: an update that carries `rawInput` replaces this.
         * For a long-running call that is what you want (the newest arguments
         * are the live ones) and for a workflow run it is exactly what you do
         * not — see [rawInputOpen].
         */
        val rawInput: String?,
        /**
         * The **opening** arguments, set once and never overwritten
         * (SPETTRO.md W-09).
         *
         * Spettro rewrites a workflow run's `rawInput` on its finish update,
         * replacing the declared `phases`, `description` and `origin` with a
         * summary — so by the time a run is worth reading, the plan it
         * announced at t=0 is gone from `rawInput` and survives only here.
         * Every classification in [SpettroOrchestration] reads this, never
         * the title and never the latest args.
         */
        val rawInputOpen: String? = null,
        /**
         * The raw `_meta` of the `session/request_permission` that stopped
         * this call, as JSON text (SPETTRO.md W-10).
         *
         * A call whose meta carries `spettro.app/question` is not a
         * permission prompt at all — it is one page of an ask-user form being
         * walked through the permission channel — and "Allow / Deny" is the
         * wrong thing to draw over it.
         */
        val permissionMeta: String? = null,
        /**
         * Which turn raised it (SPETTRO.md W-17).
         *
         * Spettro builds a fresh tool-call table per `session/prompt`, so
         * `call-1`, `wf-1` and `ask-1` recur in *every* turn. The ordinal is
         * what keeps a second turn's `call-1` from being drawn as an update
         * to the first turn's card; [key] is the identity to key state on.
         */
        val turn: Long = 0,
        override val reverted: Boolean = false,
    ) : AgentEntry {
        val diffs: List<FileDiff> get() = content.filterIsInstance<ToolContent.Diff>().map { it.file }

        /** The identity that is actually unique: `"3:call-1"`. */
        val key: String get() = "$turn:$id"

        /**
         * The latest arguments, parsed. Null when the agent sent none, and
         * null when what it sent will not parse — a tool call whose args are
         * malformed is still a tool call worth drawing.
         */
        val args: JSONObject? by lazy {
            rawInput?.let { runCatching { JSONObject(it) }.getOrNull() }
        }

        /** The opening arguments, parsed; falls back to [args]. */
        val openArgs: JSONObject? by lazy {
            rawInputOpen?.let { runCatching { JSONObject(it) }.getOrNull() } ?: args
        }

        /**
         * The tool's own name: the first token of the title, with any
         * `[review#2] ` orchestration prefix taken off first.
         *
         * The title is the only place the tool name travels — ACP has no
         * field for it — but it is *not* a place to read arguments from:
         * Spettro truncates the inline JSON at 120 characters, so a title's
         * `{"path":"/very/long/…` is routinely invalid JSON.
         */
        val toolName: String
            get() = title.removePrefix(agentPrefix?.let { "[$it] " } ?: "")
                .trimStart()
                .substringBefore(' ')

        /**
         * The `review#2` out of a `[review#2] bash go vet ./...` title, when
         * the call was made by a sub-agent rather than by the model itself.
         */
        val agentPrefix: String?
            get() {
                if (!title.startsWith("[")) return null
                val close = title.indexOf(']')
                if (close <= 1) return null
                return title.substring(1, close).takeIf { it.isNotBlank() }
            }
    }

    /**
     * A row this build does not know how to draw — a kind added to the engine
     * after this app was built.
     *
     * It is a *row*, not a gap, and that is the point. Skipping it left a hole
     * in the merge, a hole means "we are out of step with the engine", and
     * being out of step asks the poller to re-read from the start — which
     * returns the same unknown row, holes again, and re-reads for ever with an
     * empty transcript and no error anywhere. Keeping the row makes an unknown
     * kind exactly what it should be: one line nobody can render, and the rest
     * of the conversation intact.
     */
    /**
     * A plan the agent finished, filed where the turn it belonged to is.
     *
     * Distinct from the live plan beside the composer on purpose: a plan
     * completed three turns ago is history, and showing it as though the
     * agent were still working through it is the bug this exists to fix.
     */
    data class CompletedPlan(
        val entries: List<AgentPlanEntry>,
        override val reverted: Boolean = false,
    ) : AgentEntry

    data object Unsupported : AgentEntry {
        override val reverted: Boolean get() = false
    }

    companion object {
        /** Never null: an unknown row is [Unsupported], never a hole. */
        internal fun parse(json: JSONObject): AgentEntry = when (json.optString("kind")) {
            "user" -> User(
                markdown = json.optString("markdown"),
                checkpoint = json.optBoolean("checkpoint"),
                reverted = json.optBoolean("reverted"),
            )

            "completed_plan" -> {
                val entries = json.optJSONArray("entries") ?: JSONArray()
                CompletedPlan(
                    List(entries.length()) { index ->
                        AgentPlanEntry.parse(entries.getJSONObject(index))
                    },
                    reverted = json.optBoolean("reverted"),
                )
            }

            "assistant" -> {
                val chunks = json.optJSONArray("chunks") ?: JSONArray()
                Assistant(
                    List(chunks.length()) { index ->
                        val chunk = chunks.getJSONObject(index)
                        AssistantChunk(
                            thought = chunk.optBoolean("thought"),
                            markdown = chunk.optString("markdown"),
                        )
                    },
                    reverted = json.optBoolean("reverted"),
                )
            }

            "tool_call" -> {
                val options = json.optJSONArray("options") ?: JSONArray()
                val content = json.optJSONArray("content") ?: JSONArray()
                val locations = json.optJSONArray("locations") ?: JSONArray()
                ToolCall(
                    id = json.optString("id"),
                    title = json.optString("title"),
                    // `tool_kind`, not `kind` — `kind` is the row's own tag,
                    // and the two collided once already.
                    kind = ToolKind.parse(json.optString("tool_kind")),
                    status = ToolCallStatus.parse(json.optString("status")),
                    options = List(options.length()) {
                        PermissionOption.parse(options.getJSONObject(it))
                    },
                    content = (0 until content.length()).mapNotNull { index ->
                        val item = content.getJSONObject(index)
                        when (item.optString("type")) {
                            "markdown" -> ToolContent.Markdown(item.optString("markdown"))
                            "diff" -> item.optJSONObject("diff")
                                ?.let { ToolContent.Diff(FileDiff.parse(it)) }

                            "terminal" -> item.optString("terminalId")
                                .takeIf { it.isNotEmpty() }
                                ?.let { id ->
                                    val exit = item.optJSONObject("exitStatus")
                                    ToolContent.Terminal(
                                        terminalId = id,
                                        // Present only once the agent has
                                        // released it; until then the card
                                        // polls the live terminal.
                                        sealed = if (item.has("output")) {
                                            AgentTerminalState(
                                                revision = 1,
                                                label = item.optString("label"),
                                                cwd = "",
                                                output = item.optString("output"),
                                                truncated = item.optBoolean("truncated"),
                                                running = false,
                                                exitCode = exit
                                                    ?.takeIf { !it.isNull("exitCode") }
                                                    ?.optInt("exitCode"),
                                                signal = exit
                                                    ?.takeIf { !it.isNull("signal") }
                                                    ?.optString("signal"),
                                            )
                                        } else {
                                            null
                                        },
                                    )
                                }

                            else -> null
                        }
                    },
                    rawInput = json.stringOrNull("rawInput"),
                    // W-09/W-10/W-17. All three are absent on an engine that
                    // predates them, and all three degrade to the old
                    // behaviour rather than to a crash: no opening args means
                    // `openArgs` falls back to `rawInput`, no permission meta
                    // means an ordinary permission prompt, turn 0 means every
                    // call keys as it always did.
                    rawInputOpen = json.stringOrNull("rawInputOpen"),
                    permissionMeta = json.optJSONObject("permissionMeta")?.toString(),
                    turn = json.optLong("turn"),
                    locations = List(locations.length()) { index ->
                        val location = locations.getJSONObject(index)
                        AgentLocation(
                            path = location.optString("path"),
                            line = if (location.isNull("line")) null else location.optInt("line"),
                        )
                    },
                    reverted = json.optBoolean("reverted"),
                )
            }

            else -> Unsupported
        }
    }
}

/**
 * Context-window usage, when the agent reports it.
 *
 * Two numbers that look alike and are not. [used] is **occupancy** — the
 * largest single request so far — and it *falls* when the agent compacts, so
 * it is a gauge and never a counter. [tokensUsed] is the monotonic spend, and
 * it is the only one of the two that may be added up.
 */
data class AgentUsage(
    val used: Long,
    /** The window. The agent falls back to 128000 when it does not know. */
    val size: Long,
    /** `_meta["spettro.app/tokensUsed"]` (SPETTRO.md W-08); null elsewhere. */
    val tokensUsed: Long? = null,
    /** Spettro never sends this; other agents do. */
    val cost: AgentCost? = null,
) {
    /** 0..1. A nonsensical window reads as empty rather than dividing by zero. */
    val fraction: Float get() = if (size > 0) (used.toFloat() / size).coerceIn(0f, 1f) else 0f

    /** Worth colouring: the gauge turns amber here. */
    val isWarm: Boolean get() = fraction >= 0.75f

    /**
     * Whether the window is close enough to full to be worth saying so.
     *
     * Zed warns rather than only drawing a bar, because a thread that hits
     * the limit mid-turn loses the reply and the user had no notice.
     */
    val isNearlyFull: Boolean get() = fraction >= 0.90f
}

/**
 * What the turn that just ended actually cost — `prompt`'s own `usage`
 * (SPETTRO.md W-08b), cleared at the start of every turn.
 *
 * Separate from [AgentUsage] because it answers a different question: not
 * "how full is the context" but "what did that one answer spend", which is
 * the number a cache-miss regression shows up in first.
 */
data class AgentTurnUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
    val cachedReadTokens: Long,
    val cachedWriteTokens: Long,
    /** `_meta["spettro.app/tokensUsed"]` for this turn alone. */
    val tokensUsed: Long? = null,
) {
    /**
     * How much of the prompt came out of the cache, 0..1, or null when there
     * was no prompt to speak of.
     *
     * The denominator is everything that had to be *presented* to the model
     * — fresh input plus both halves of the cache — because a rate that
     * ignored the cache writes would read as 100 % on the very turn that paid
     * to fill it.
     */
    val cacheHitRate: Float?
        get() {
            val presented = inputTokens + cachedReadTokens + cachedWriteTokens
            return if (presented > 0) cachedReadTokens.toFloat() / presented else null
        }

    internal companion object {
        fun parse(json: JSONObject?): AgentTurnUsage? {
            val root = json ?: return null
            return AgentTurnUsage(
                inputTokens = root.optLong("inputTokens"),
                outputTokens = root.optLong("outputTokens"),
                totalTokens = root.optLong("totalTokens"),
                cachedReadTokens = root.optLong("cachedReadTokens"),
                cachedWriteTokens = root.optLong("cachedWriteTokens"),
                tokensUsed = root.longOrNull("tokensUsed"),
            )
        }
    }
}

/** `optLong` cannot tell a missing key from a zero, and here they differ. */
private fun JSONObject.longOrNull(name: String): Long? =
    if (!has(name) || isNull(name)) null else optLong(name)

/**
 * A prompt typed while the agent was busy, waiting its turn.
 *
 * Deliberately not a transcript entry: it has not been sent, and a
 * transcript that shows messages the agent never received is one that lies.
 */
data class AgentQueuedPrompt(val id: Long, val text: String)

/** One entry of the agent's plan. */
data class AgentPlanEntry(
    val content: String,
    /** `high`, `medium`, `low`. */
    val priority: String,
    /** `pending`, `in_progress`, `completed`. */
    val status: String,
    /**
     * Its dependencies are not met yet (SPETTRO.md W-12).
     *
     * ACP has no blocked status, so Spettro appends a literal `" (blocked)"`
     * to the task's text; the engine lifts the suffix into this flag so the
     * strip can draw a task that *cannot* start differently from one that
     * merely has not, without the word turning up inside the sentence.
     */
    val blocked: Boolean = false,
) {
    /** Ranked so a plan sheet can order by urgency without string compares. */
    enum class Priority { High, Medium, Low }

    enum class Status { Pending, InProgress, Completed }

    val priorityOf: Priority
        get() = when (priority) {
            "high" -> Priority.High
            "low" -> Priority.Low
            else -> Priority.Medium
        }

    val statusOf: Status
        get() = when (status) {
            "in_progress" -> Status.InProgress
            "completed" -> Status.Completed
            else -> Status.Pending
        }

    val isDone: Boolean get() = statusOf == Status.Completed

    /** Blocked only says anything about work that has not started. */
    val isBlocked: Boolean get() = blocked && statusOf == Status.Pending

    internal companion object {
        fun parse(json: JSONObject) = AgentPlanEntry(
            content = json.optString("content"),
            priority = json.optString("priority"),
            status = json.optString("status"),
            blocked = json.optBoolean("blocked"),
        )
    }
}

/** A mode the agent can work in — Claude Code's "Always Ask", "Accept Edits". */
data class AgentMode(val id: String, val name: String, val description: String?)

/** The modes, and which one is current. */
data class AgentModes(val currentId: String, val available: List<AgentMode>) {
    val current: AgentMode? get() = available.firstOrNull { it.id == currentId }
}

/** A slash command the agent advertised — `/plan`, `/init`, whatever it has. */
data class AgentCommand(val name: String, val description: String, val inputHint: String?)

/**
 * One value a select config option can take.
 *
 * The legacy flat shape, kept because the old panel's chip menu is built from
 * it. New code wants [AgentConfigOption.Choice], which carries the group it
 * came from rather than pretending the list was never grouped.
 */
data class AgentConfigValue(val id: String, val name: String, val description: String?)

/**
 * One of the agent's session configuration options — for Spettro exactly
 * five: `mode`, `model`, `permission`, `thinking` and `ultra`.
 *
 * ACP's `SessionConfigOption`, read whole rather than flattened (SPETTRO.md
 * W-13). Two things used to be thrown away here and both are load-bearing on
 * a phone:
 *
 *  - **the groups.** A model list arrives as `Anthropic` / `OpenAI` /
 *    `Local`, thirty-odd entries deep. Spliced into one flat list it is an
 *    unnavigable column; a full-height sheet has all the room the old popup
 *    did not.
 *  - **`category`.** `mode`, `model` and `thought_level` are what tell a chip
 *    which icon to wear and which tint the composer takes; `permission` and
 *    `ultra` deliberately carry none.
 *
 * The list is grouped exactly when its FIRST element itself carries an
 * `options` array — the same test Spettro's own desktop client makes — and
 * never per-element: a half-grouped list is not a shape the agent produces,
 * and guessing per element would silently reorder the other half.
 */
data class AgentConfigOption(
    val id: String,
    val name: String,
    val description: String?,
    /** `mode`, `model`, `thought_level`, or null. Never invented here. */
    val category: String?,
    /**
     * ACP's `type` discriminator: `"select"` or `"boolean"`.
     *
     * Deliberately still a string rather than a sealed type: this property is
     * switched on by name in the shipping panel, and the payload is reachable
     * through [currentValue] / [currentBool] / [groups] / [flat] without
     * making every existing caller a compile error.
     */
    val kind: String,
    /** Select only: the value the agent says is current. */
    val currentValue: String?,
    /** Boolean only: the flag the agent says is current. */
    val currentBool: Boolean?,
    /** Grouped children in wire order; empty when the list is flat. */
    val groups: List<Group>,
    /** Flat children in wire order; empty when the list is grouped. */
    val flat: List<Choice>,
) {
    /** One selectable value. `value` is what goes back on the wire. */
    data class Choice(val value: String, val name: String, val description: String? = null)

    /** A named run of [Choice]s — one provider, one family. */
    data class Group(val id: String, val name: String, val options: List<Choice>)

    val isSelect: Boolean get() = kind == "select"

    val isBool: Boolean get() = kind == "boolean"

    val isGrouped: Boolean get() = groups.isNotEmpty()

    /** Every choice in display order, groups spliced. */
    val choices: List<Choice>
        get() = if (groups.isEmpty()) flat else groups.flatMap { it.options }

    val current: Choice? get() = choices.firstOrNull { it.value == currentValue }

    /** The group the current value sits in, for a sheet that opens on it. */
    val currentGroup: Group?
        get() = groups.firstOrNull { group -> group.options.any { it.value == currentValue } }

    /**
     * What the chip prints.
     *
     * Falls back to the raw value rather than to the option's own name: a
     * value the agent offered and then did not list is a real state the user
     * is in, and printing "Model" over it hides that.
     */
    val currentLabel: String
        get() = current?.name
            ?: currentValue
            ?: when (currentBool) {
                true -> "On"
                false -> "Off"
                null -> "—"
            }

    // --- the legacy flat view, for callers written before groups existed ---

    /** [currentValue] under its old name. */
    val currentValueId: String? get() = currentValue

    val values: List<AgentConfigValue>
        get() = choices.map { AgentConfigValue(it.value, it.name, it.description) }
}

/**
 * The five chips, derived fresh from `configOptions` on every poll.
 *
 * Nothing is cached: `config_option_update` is a **full replacement** and
 * Spettro pushes one after any handled slash command, so a toolbar that
 * remembered what it last set would be wrong every time the user typed
 * `/model` instead of tapping.
 */
data class SpettroToolbar(val options: List<AgentConfigOption>) {
    val mode: AgentConfigOption? get() = options.firstOrNull { it.id == "mode" }
    val model: AgentConfigOption? get() = options.firstOrNull { it.id == "model" }
    val permission: AgentConfigOption? get() = options.firstOrNull { it.id == "permission" }
    val thinking: AgentConfigOption? get() = options.firstOrNull { it.id == "thinking" }
    val ultra: AgentConfigOption? get() = options.firstOrNull { it.id == "ultra" }

    val permissionValue: String? get() = permission?.currentValue

    /** What the agent stores, which is not the same as what it *does*. */
    val ultraOn: Boolean get() = ultra?.currentBool == true

    val askFirst: Boolean get() = permissionValue == "ask-first"

    /**
     * THE THREE-STATE RULE, and the reason a plain switch is wrong here.
     *
     * The agent publishes `cfg.Ultra` — the stored flag — not
     * `UltraActive() = Ultra && Permission != ask-first`. Under `ask-first`
     * with ultra stored true, a switch reads ON while the swarm is suspended
     * and nothing fans out; and turning it on *from* ask-first is refused by
     * the agent, which a switch renders as a toggle that flips back by itself
     * with no explanation.
     */
    val ultraState: UltraState
        get() = when {
            ultraOn && askFirst -> UltraState.Suspended
            ultraOn -> UltraState.On
            askFirst -> UltraState.Locked
            else -> UltraState.Off
        }

    /** Turning Ultra **off** is never locked; only turning it on is. */
    val canToggleUltra: Boolean get() = ultraState != UltraState.Locked
}

/** Ultra's four states — see [SpettroToolbar.ultraState]. */
enum class UltraState { Off, On, Suspended, Locked }

const val ULTRA_LOCK_REASON =
    "Ultra requires the Restricted or YOLO permission level — change Permission first"

/**
 * What the agent advertised in `initialize`'s `_meta` under
 * `spettro.app/extensions` (SPETTRO.md W-02).
 *
 * Its **presence** is the gate: everything Spettro-specific in the UI asks
 * whether this is non-null, never whether the agent is called "spettro". An
 * agent that does not answer the handshake gets the generic ACP panel, which
 * is correct and is also what a future Spettro that dropped the extension
 * would deserve.
 */
data class SpettroSurface(
    /** 4 on the shipping CLI. Trust the wire, not the docs. */
    val version: Int,
    /** The `_spettro` methods the agent serves, in full. */
    val methods: Set<String>,
    /** The ones it expects *us* to serve — `_spettro/question/ask`. */
    val clientMethods: Set<String>,
) {
    /** Workflow authoring — the `_spettro/workflow` calls — landed in 4. */
    val hasWorkflowAuthoring: Boolean get() = version >= 4

    fun serves(method: String): Boolean = method in methods

    internal companion object {
        fun parse(json: JSONObject?): SpettroSurface? {
            val root = json ?: return null
            return SpettroSurface(
                version = root.optInt("version"),
                methods = stringSet(root.optJSONArray("methods")),
                clientMethods = stringSet(root.optJSONArray("clientMethods")),
            )
        }

        private fun stringSet(array: JSONArray?): Set<String> {
            val items = array ?: return emptySet()
            return (0 until items.length())
                .mapNotNull { items.optString(it).takeIf(String::isNotEmpty) }
                .toSet()
        }
    }
}

/** A way to sign in, as the agent advertised it. */
/**
 * One way in, from the agent's `authMethods`.
 *
 * The `type` discriminator matters, and its absence means `agent`. A plain
 * `agent` method is answered with [CoreBridge.acpAuthenticate] — the agent
 * does the signing in. A **terminal** method is not: it means "run me with
 * these extra arguments in a terminal and let the user answer", so the client
 * opens a real pty session on the agent's own command instead of sending
 * anything. The engine advertises `auth.terminal`, so agents are entitled to
 * offer it; answering one with `authenticate` would sign nobody in and say
 * nothing about why.
 */
data class AgentAuthMethod(
    val id: String,
    val name: String,
    val description: String?,
    /** `agent` (the default), `terminal`, or `env_var`. */
    val type: String,
    /** Extra arguments for the agent's own command; `terminal` only. */
    val args: List<String>,
    /** Extra environment for it, as `NAME` to value; `terminal` only. */
    val env: Map<String, String>,
) {
    /** Whether signing in means opening a terminal on the agent's command. */
    val isTerminal: Boolean get() = type == "terminal"
}

/** What the agent said about itself when it initialized. */
data class AgentInfo(
    /** The name we launched it under. */
    val name: String?,
    /** What it calls itself, when it says. */
    val agentName: String?,
    val agentVersion: String?,
    val authMethods: List<AgentAuthMethod>,
    /** It has not answered `initialize` yet. */
    val starting: Boolean,
    /** It will not start, and this is why. */
    val error: String?,
    /**
     * What it said it can do with sessions, at `initialize`.
     *
     * Every one of these is a method it is an error to call unasked, so they
     * gate buttons rather than decorate them: an agent that keeps no history
     * must not be offered a history view.
     */
    val capabilities: AgentCapabilities,
)

/** The session-lifecycle methods an agent has; all false until it says. */
data class AgentCapabilities(
    /** `session/load` — reopen a past conversation *with* its history. */
    val loadSession: Boolean = false,
    /** `session/resume` — reopen one without its history. */
    val resume: Boolean = false,
    /** `session/list` — what past conversations it has kept. */
    val list: Boolean = false,
    /** `session/delete` — forget one. */
    val delete: Boolean = false,
    /** `session/close` — the polite end, rather than a bare cancel. */
    val close: Boolean = false,
    /** `logout` — sign out of what `authenticate` signed into. */
    val logout: Boolean = false,
    /**
     * `promptCapabilities.image` — whether a prompt may carry a picture.
     * Gates the composer's attach button: an agent that never claimed it is
     * not offered the choice, and the engine drops the block besides.
     */
    val images: Boolean = false,
) {
    /** Whether a past conversation can be reopened at all, either way round. */
    val canOpenHistory: Boolean get() = loadSession || resume

    /** Whether the history view is worth offering: it can be listed and opened. */
    val hasHistory: Boolean get() = list && canOpenHistory

    internal companion object {
        /**
         * [json] is the agent's `capabilities` object — the session methods.
         * [images] comes from beside it on the agent, because what a *prompt*
         * may carry is a different question from what a session supports.
         */
        fun parse(json: JSONObject?, images: Boolean = false): AgentCapabilities {
            val caps = json ?: return AgentCapabilities(images = images)
            return AgentCapabilities(
                loadSession = caps.optBoolean("load_session"),
                resume = caps.optBoolean("resume"),
                list = caps.optBoolean("list"),
                delete = caps.optBoolean("delete"),
                close = caps.optBoolean("close"),
                logout = caps.optBoolean("logout"),
                images = images,
            )
        }
    }
}

/** One of the agent's own past conversations, from `session/list`. */
data class AgentPastSession(
    val sessionId: String,
    val cwd: String,
    val title: String?,
    /** An ISO-8601 timestamp, as the agent wrote it. */
    val updatedAt: String?,
) {
    /** What to put in a row: the agent's title, else the directory's name. */
    val label: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: cwd.trimEnd('/').substringAfterLast('/').ifEmpty { sessionId }
}

/** The cached `session/list`, as [CoreBridge.acpSessionList] reports it. */
data class AgentSessionList(
    val version: Long = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val sessions: List<AgentPastSession> = emptyList(),
) {
    companion object {
        val NONE = AgentSessionList()

        fun parse(text: String): AgentSessionList = runCatching {
            val root = JSONObject(text)
            val items = root.optJSONArray("sessions") ?: JSONArray()
            AgentSessionList(
                version = root.optLong("version"),
                loading = root.optBoolean("loading"),
                error = root.stringOrNull("error"),
                sessions = (0 until items.length()).mapNotNull { index ->
                    val item = items.optJSONObject(index) ?: return@mapNotNull null
                    AgentPastSession(
                        sessionId = item.optString("sessionId"),
                        cwd = item.optString("cwd"),
                        title = item.stringOrNull("title"),
                        updatedAt = item.stringOrNull("updatedAt"),
                    ).takeIf { it.sessionId.isNotEmpty() }
                },
            )
        }.getOrDefault(NONE)
    }
}

/**
 * A whole ask-user form, parked by the engine until it is answered
 * (SPETTRO.md W-04/§5).
 *
 * This is Spettro's `_spettro/question/ask`, and it exists because the
 * standard has no shape for "ask the user several related things at once".
 * Without the extension the same form is *walked* through the permission
 * channel one question at a time, which on a phone is five sheets deep and
 * loses the relationship between the answers — so the transport this arrived
 * on is kept on the value: it decides how the answer goes back, and only
 * that.
 *
 * The payload is forwarded verbatim by the engine and parsed only here, so a
 * field the CLI adds next release needs no engine change to reach the UI.
 */
data class SpettroQuestion(
    /** The engine's own id — `question-1` — and what [CoreBridge.acpRespondQuestion] answers. */
    val id: String,
    /** The seeker session it belongs to, when the engine could resolve one. */
    val session: Long?,
    /** 1 = the flat single question, 2 = `questions[]`. */
    val version: Int,
    /** The agent's own session id, as it sent it. */
    val sessionId: String?,
    /** Why it is asking — shown once, above the questions. */
    val context: String?,
    val questions: List<Q>,
    val transport: Transport,
) {
    /**
     * How it reached us, which is how the answer must go back.
     *
     * [Ask] answers the parked request with `{"answers":[…]}`; [Permission]
     * selects an option id on the stopped tool call and tags the reply's
     * `_meta`; [Elicitation] is the pre-extension fallback and answers the
     * elicitation. Nothing else about the form differs.
     */
    enum class Transport { Ask, Permission, Elicitation }

    /** One question of the form. */
    data class Q(
        /** `q-0`. Stable within this form; the answer quotes it back. */
        val id: String,
        /** The model's own heading. A label to read, never an identifier. */
        val header: String,
        val question: String,
        val options: List<Opt>,
        val multiSelect: Boolean,
        /**
         * Whether a typed answer is allowed. A question with no options at
         * all is custom input by definition, whatever the flag says — the
         * alternative is a form with nothing to answer it with.
         */
        val allowCustomInput: Boolean,
    ) {
        /**
         * What [draft] amounts to, or null when it amounts to nothing.
         *
         * Null is a real answer and is *not* the recommended option: a
         * question left alone is omitted from the reply, and the model is
         * then told plainly that nobody answered it. Defaulting to the
         * recommendation would put a decision in the user's mouth.
         *
         * Selections come out in OPTION order rather than tick order, so two
         * users who chose the same two boxes send the same thing.
         */
        fun answer(draft: QuestionDraft): QuestionAnswer? {
            val notes = draft.note.trim().takeIf { it.isNotEmpty() }
            val custom = draft.custom.trim()
            if (allowCustomInput && custom.isNotEmpty() && draft.selected.isEmpty()) {
                return QuestionAnswer.Custom(id, custom, notes)
            }
            val picked = options.map { it.id }.filter { it in draft.selected }
            if (picked.isEmpty()) {
                // Custom text alongside a selection is a note about the
                // choice, not a replacement for it.
                return if (custom.isNotEmpty()) QuestionAnswer.Custom(id, custom, notes) else null
            }
            return QuestionAnswer.Option(id, picked, notes)
        }
    }

    /** One offered answer. */
    data class Opt(
        val id: String,
        val label: String,
        val description: String?,
        /** A snippet the option would produce — a diff, a command, a name. */
        val preview: String?,
        /** Badged, never preselected. */
        val isRecommended: Boolean,
    )

    /** A v1 payload is exactly one question; v2 may be several. */
    val isSingle: Boolean get() = questions.size <= 1

    companion object {
        /** Parses `[{"id","session","payload"}]` — the engine's question view. */
        fun parseAll(array: JSONArray?): List<SpettroQuestion> {
            val items = array ?: return emptyList()
            return (0 until items.length()).mapNotNull { index ->
                val row = items.optJSONObject(index) ?: return@mapNotNull null
                val id = row.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                parsePayload(
                    id = id,
                    session = if (row.isNull("session")) null else row.optLong("session"),
                    payload = row.optJSONObject("payload") ?: JSONObject(),
                    transport = Transport.Ask,
                )
            }
        }

        /**
         * The same form when it arrived as a permission request's `_meta`
         * (SPETTRO.md W-10) — `spettro.app/question`.
         *
         * [id] is the tool call's [AgentEntry.ToolCall.key], because that is
         * what the answer is addressed to in this transport.
         */
        fun fromPermissionMeta(id: String, meta: JSONObject?): SpettroQuestion? {
            val payload = meta?.optJSONObject("spettro.app/question") ?: return null
            return parsePayload(id, session = null, payload = payload, transport = Transport.Permission)
        }

        private fun parsePayload(
            id: String,
            session: Long?,
            payload: JSONObject,
            transport: Transport,
        ): SpettroQuestion {
            // Absent means 1: the extension shipped before `questions[]` did,
            // and a payload with neither field is a single flat question.
            val version = payload.optInt("version", 1)
            val nested = payload.optJSONArray("questions")
            val questions = if (nested != null && nested.length() > 0) {
                (0 until nested.length()).mapNotNull { index ->
                    nested.optJSONObject(index)?.let { parseQuestion(it, index) }
                }
            } else {
                listOf(parseQuestion(payload, 0))
            }
            return SpettroQuestion(
                id = id,
                session = session,
                version = version,
                sessionId = payload.stringOrNull("sessionId"),
                context = payload.stringOrNull("context"),
                questions = questions,
                transport = transport,
            )
        }

        private fun parseQuestion(json: JSONObject, index: Int): SpettroQuestion.Q {
            val options = parseOptions(json.optJSONArray("options"))
            return SpettroQuestion.Q(
                id = json.stringOrNull("id") ?: "q-$index",
                header = json.stringOrNull("header").orEmpty(),
                question = json.stringOrNull("question")
                    ?: json.stringOrNull("text").orEmpty(),
                options = options,
                multiSelect = json.optBoolean("multiSelect"),
                // A question with nothing to pick can only be typed into,
                // whatever the flag says.
                allowCustomInput = json.optBoolean("allowCustomInput") || options.isEmpty(),
            )
        }

        private fun parseOptions(array: JSONArray?): List<SpettroQuestion.Opt> {
            val items = array ?: return emptyList()
            return (0 until items.length()).mapNotNull { index ->
                val row = items.optJSONObject(index)
                    // A bare string list is legal and is its own label.
                    ?: return@mapNotNull items.optString(index)
                        .takeIf { it.isNotEmpty() }
                        ?.let { SpettroQuestion.Opt(it, it, null, null, false) }
                val id = row.stringOrNull("id")
                    ?: row.stringOrNull("optionId")
                    ?: row.stringOrNull("value")
                    ?: "opt-$index"
                SpettroQuestion.Opt(
                    id = id,
                    label = row.stringOrNull("label")
                        ?: row.stringOrNull("name")
                        ?: id,
                    description = row.stringOrNull("description"),
                    preview = row.stringOrNull("preview"),
                    isRecommended = row.optBoolean("isRecommended"),
                )
            }
        }
    }
}

/**
 * One question's working state, held by the sheet.
 *
 * Nothing starts selected. [note] is the optional "why", which the model gets
 * alongside the choice and which is the difference between "Postgres" and
 * "Postgres, because the ops team already runs one".
 */
data class QuestionDraft(
    val selected: List<String> = emptyList(),
    val custom: String = "",
    val note: String = "",
) {
    /** Tick, or untick; [multi] false makes it a radio group. */
    fun toggle(optionId: String, multi: Boolean): QuestionDraft = when {
        !multi -> copy(selected = if (selected == listOf(optionId)) emptyList() else listOf(optionId))
        optionId in selected -> copy(selected = selected - optionId)
        else -> copy(selected = selected + optionId)
    }

    val isEmpty: Boolean get() = selected.isEmpty() && custom.isBlank()
}

/** One answered question. A question with no answer is omitted, never faked. */
sealed interface QuestionAnswer {
    val questionId: String

    data class Option(
        override val questionId: String,
        /** In OPTION order, never tick order. */
        val optionIds: List<String>,
        val notes: String?,
    ) : QuestionAnswer

    data class Custom(
        override val questionId: String,
        val text: String,
        val notes: String?,
    ) : QuestionAnswer
}

/** Everything about a session except its rows. */
data class AgentSessionState(
    val version: Long,
    val phase: AgentPhase,
    /** The sentence to show when something went wrong. */
    val error: String?,
    /** [CoreBridge.acpAuthenticate] with one of [AgentInfo.authMethods] is the way on. */
    val needsAuth: Boolean,
    val title: String?,
    /** How the last turn ended: `end_turn`, `cancelled`, `refusal`, … */
    val stopReason: String?,
    val entryCount: Int,
    val plan: List<AgentPlanEntry>,
    val usage: AgentUsage?,
    val modes: AgentModes?,
    /** Slash commands, for the composer's `/` popup. */
    val commands: List<AgentCommand>,
    /** Config options, for the selector chips under the composer. */
    val configOptions: List<AgentConfigOption>,
    /**
     * Questions the agent is waiting on — `elicitation/create`, which is how
     * ACP carries every ask that is not a permission. Nothing else can move
     * until one is answered, so the panel puts them at the end of the
     * transcript where the next thing to do is.
     */
    val elicitations: List<AgentElicitation>,
    /** What kind of failure [error] is, when the engine could tell. */
    val errorKind: AgentErrorKind?,
    /** Whether trying the same thing again could plausibly work. */
    val canRetry: Boolean,
    /**
     * Why the last thing the user asked for did not happen — a mode the agent
     * refused, a config option it rejected. These used to be computed and
     * shown to nobody.
     */
    val notice: String?,
    /** The agent's own id for this session, for reconciling its history list. */
    val acpSessionId: String?,
    /** The agent's own timestamp for the conversation, as it wrote it. */
    val updatedAt: String?,
    /** Prompts waiting for the running turn to end, oldest first. */
    val queue: List<AgentQueuedPrompt>,
    val agent: AgentInfo?,
    /** How many edited files still want a Keep or Reject — the review badge. */
    val editedFiles: Int = 0,
    /** How many tool calls are stopped on a permission prompt. */
    val waitingCount: Int = 0,
    /** What the last finished turn spent (SPETTRO.md W-08b). */
    val turnUsage: AgentTurnUsage? = null,
    /**
     * Ask-user forms parked on this session — `_spettro/question/ask`.
     *
     * Folded into the session state by the engine so the panel's ordinary
     * poll finds them; [rememberSpettroQuestions] exists only for one raised
     * before any session did.
     */
    val questions: List<SpettroQuestion> = emptyList(),
    /**
     * Present exactly when the agent is Spettro (SPETTRO.md W-02).
     *
     * Every superset surface — workflows, Ultra, the four selectors, the
     * question sheet, steering — gates on this being non-null. `null` is not
     * an error: it is a generic ACP agent, and it gets the generic panel.
     */
    val spettro: SpettroSurface? = null,
) {
    val isBusy: Boolean get() = phase == AgentPhase.Running

    /** The five chips, derived. Cheap enough to build on every read. */
    val toolbar: SpettroToolbar get() = SpettroToolbar(configOptions)

    /**
     * Everything the agent is blocked on: permission prompts, unanswered
     * questions and parked ask-user forms. What the background watcher
     * notifies about.
     *
     * A walked question is already counted in [waitingCount] — it *is* a
     * stopped tool call — so only the extension's own forms are added here,
     * or a single ask would be announced twice.
     */
    val needsUser: Int
        get() = waitingCount + elicitations.count { !it.accepted } + questions.size

    /** Whether a prompt would be accepted at all. */
    val canPrompt: Boolean get() = phase != AgentPhase.Unavailable

    companion object {
        /** Before the first read, and for a session the engine has forgotten. */
        val NONE = AgentSessionState(
            version = 0,
            phase = AgentPhase.Starting,
            error = null,
            needsAuth = false,
            title = null,
            stopReason = null,
            entryCount = 0,
            plan = emptyList(),
            usage = null,
            modes = null,
            commands = emptyList(),
            configOptions = emptyList(),
            elicitations = emptyList(),
            errorKind = null,
            canRetry = false,
            notice = null,
            acpSessionId = null,
            updatedAt = null,
            queue = emptyList(),
            agent = null,
            turnUsage = null,
            questions = emptyList(),
            spettro = null,
        )

        /** Parses [CoreBridge.acpSessionState]; [NONE] for `"null"` or rubbish. */
        fun parse(text: String): AgentSessionState = runCatching {
            val root = JSONObject(text)
            val plan = root.optJSONArray("plan") ?: JSONArray()
            val usage = root.optJSONObject("usage")
            val modes = root.optJSONObject("modes")
            val agent = root.optJSONObject("agent")
            AgentSessionState(
                version = root.optLong("version"),
                phase = AgentPhase.parse(root.optString("phase")),
                error = root.stringOrNull("error"),
                needsAuth = root.optBoolean("needs_auth"),
                title = root.stringOrNull("title"),
                stopReason = root.stringOrNull("stop_reason"),
                entryCount = root.optInt("entry_count"),
                plan = List(plan.length()) { index ->
                    AgentPlanEntry.parse(plan.getJSONObject(index))
                },
                // A window of zero divides every gauge by zero, and the
                // engine drops such an update — but a stale one can still be
                // on the entry, so the read is defensive as well.
                usage = usage?.let {
                    val cost = it.optJSONObject("cost")
                    AgentUsage(
                        used = it.optLong("used"),
                        size = it.optLong("size"),
                        tokensUsed = it.longOrNull("tokensUsed"),
                        cost = cost?.let { money ->
                            AgentCost(money.optDouble("amount"), money.optString("currency"))
                        },
                    )
                },
                turnUsage = AgentTurnUsage.parse(root.optJSONObject("turnUsage")),
                modes = modes?.let {
                    val available = it.optJSONArray("availableModes") ?: JSONArray()
                    AgentModes(
                        currentId = it.optString("currentModeId"),
                        available = List(available.length()) { index ->
                            val mode = available.getJSONObject(index)
                            AgentMode(
                                id = mode.optString("id"),
                                name = mode.optString("name"),
                                description = mode.stringOrNull("description"),
                            )
                        },
                    )
                },
                commands = parseCommands(root.optJSONArray("commands")),
                configOptions = parseConfigOptions(root.optJSONArray("configOptions")),
                elicitations = parseElicitations(root.optJSONArray("elicitations")),
                errorKind = AgentErrorKind.parse(root.stringOrNull("errorKind")),
                canRetry = root.optBoolean("canRetry"),
                notice = root.stringOrNull("notice"),
                acpSessionId = root.stringOrNull("acpSessionId"),
                updatedAt = root.stringOrNull("updatedAt"),
                queue = (root.optJSONArray("queue") ?: JSONArray()).let { queued ->
                    (0 until queued.length()).mapNotNull { index ->
                        val row = queued.optJSONObject(index) ?: return@mapNotNull null
                        AgentQueuedPrompt(row.optLong("id"), row.optString("text"))
                    }
                },
                agent = agent?.let {
                    val methods = it.optJSONArray("auth_methods") ?: JSONArray()
                    AgentInfo(
                        name = it.stringOrNull("name"),
                        agentName = it.stringOrNull("agent_name"),
                        agentVersion = it.stringOrNull("agent_version"),
                        authMethods = List(methods.length()) { index ->
                            val method = methods.getJSONObject(index)
                            val args = method.optJSONArray("args") ?: JSONArray()
                            val env = method.optJSONObject("env")
                            AgentAuthMethod(
                                // ACP's shape again: camelCase, and the id may
                                // sit under either name depending on variant.
                                id = method.stringOrNull("methodId")
                                    ?: method.optString("id"),
                                name = method.optString("name"),
                                description = method.stringOrNull("description"),
                                // Absent means `agent`, which the schema says
                                // outright — an untagged variant, not a
                                // missing field.
                                type = method.stringOrNull("type") ?: "agent",
                                args = (0 until args.length()).map { at -> args.optString(at) },
                                env = env?.keys()?.asSequence()
                                    ?.associateWith { key -> env.optString(key) }
                                    .orEmpty(),
                            )
                        },
                        starting = it.optBoolean("starting"),
                        error = it.stringOrNull("error"),
                        capabilities = AgentCapabilities.parse(
                            it.optJSONObject("capabilities"),
                            images = it.optBoolean("images"),
                        ),
                    )
                },
                editedFiles = root.optInt("editedFiles"),
                waitingCount = root.optInt("waitingCount"),
                questions = SpettroQuestion.parseAll(root.optJSONArray("questions")),
                // The gate, and it lives on the *agent* rather than on the
                // session because it is a property of the handshake: it is
                // there from `initialize` and survives every new thread.
                spettro = SpettroSurface.parse(
                    agent?.optJSONObject("spettroExtensions"),
                ),
            )
        }.getOrDefault(NONE)

        private fun parseCommands(json: JSONArray?): List<AgentCommand> {
            if (json == null) return emptyList()
            return List(json.length()) { index ->
                val command = json.optJSONObject(index) ?: return@List null
                val name = command.optString("name").takeIf { it.isNotEmpty() }
                    ?: return@List null
                AgentCommand(
                    name = name,
                    description = command.optString("description"),
                    // ACP's `input` is `{ "hint": … }` for unstructured input.
                    inputHint = command.optJSONObject("input")?.stringOrNull("hint"),
                )
            }.filterNotNull()
        }

        /**
         * The five options, groups intact (SPETTRO.md W-13).
         *
         * A `select` whose FIRST element carries its own `options` array is a
         * grouped list, and every element is then read as a group; anything
         * else is flat. Testing only the first element is the same rule
         * Spettro's desktop client applies, and it is what stops a flat list
         * with one oddly-shaped entry from being read as half a tree.
         */
        private fun parseConfigOptions(json: JSONArray?): List<AgentConfigOption> {
            if (json == null) return emptyList()
            return List(json.length()) { index ->
                val option = json.optJSONObject(index) ?: return@List null
                val id = option.optString("id").takeIf { it.isNotEmpty() } ?: return@List null
                val name = option.optString("name")
                val description = option.stringOrNull("description")
                val category = option.stringOrNull("category")
                when (option.optString("type")) {
                    "select" -> {
                        val raw = option.optJSONArray("options") ?: JSONArray()
                        val grouped = raw.optJSONObject(0)?.optJSONArray("options") != null
                        AgentConfigOption(
                            id = id,
                            name = name,
                            description = description,
                            category = category,
                            kind = "select",
                            currentValue = option.stringOrNull("currentValue"),
                            currentBool = null,
                            groups = if (grouped) parseConfigGroups(raw) else emptyList(),
                            flat = if (grouped) emptyList() else parseConfigChoices(raw),
                        )
                    }

                    "boolean" -> AgentConfigOption(
                        id = id,
                        name = name,
                        description = description,
                        category = category,
                        kind = "boolean",
                        currentValue = null,
                        currentBool = option.optBoolean("currentValue"),
                        groups = emptyList(),
                        flat = emptyList(),
                    )

                    // A kind this build cannot render is left out rather than
                    // drawn as a chip that cannot work.
                    else -> null
                }
            }.filterNotNull()
        }

        private fun parseConfigGroups(array: JSONArray): List<AgentConfigOption.Group> =
            (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val children = parseConfigChoices(entry.optJSONArray("options") ?: JSONArray())
                // A group with nothing in it is a header with no rows under
                // it — a provider that is configured but has no models yet.
                if (children.isEmpty()) return@mapNotNull null
                val name = entry.optString("name")
                    .ifEmpty { entry.optString("group") }
                AgentConfigOption.Group(
                    // `group` is the stable key; `name` is what is drawn, and
                    // the two are the same string more often than not.
                    id = entry.optString("group").ifEmpty { name },
                    name = name.ifEmpty { entry.optString("group") },
                    options = children,
                )
            }

        private fun parseConfigChoices(array: JSONArray): List<AgentConfigOption.Choice> =
            (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val value = entry.optString("value").takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                AgentConfigOption.Choice(
                    value = value,
                    name = entry.optString("name").ifEmpty { value },
                    description = entry.stringOrNull("description"),
                )
            }
    }
}

/**
 * The transcript, merged from deltas.
 *
 * The engine stamps every row with the revision that last touched it and hands
 * back only the ones newer than what the caller quotes, so a conversation that
 * is thousands of rows long costs one row per change rather than the whole
 * thing per poll. Immutable and copied on apply, so Compose sees a new value.
 */
data class AgentConversation(
    val entries: List<AgentEntry> = emptyList(),
    /** The revision to quote on the next read. */
    val revision: Long = 0,
) {
    /**
     * Merge one [CoreBridge.acpEntriesSince] payload.
     *
     * Rows arrive with the index they sit at. A `total` smaller than what we
     * hold means rows were *removed* — a refusal truncates the transcript back
     * past the prompt it refused — and the only honest answer is to drop
     * everything and re-read from zero, which is what returning a conversation
     * at revision 0 asks for.
     */
    fun apply(text: String): AgentConversation = runCatching {
        val root = JSONObject(text)
        val total = root.optInt("total")
        if (total < entries.size) return AgentConversation()

        val incoming = root.optJSONArray("entries") ?: JSONArray()
        // Grown to `total` with holes first: only *changed* rows come back, so
        // the untouched ones in between are already ours and a delta may name
        // an index past the end.
        val merged: MutableList<AgentEntry?> = entries.toMutableList()
        while (merged.size < total) merged.add(null)
        for (index in 0 until incoming.length()) {
            val json = incoming.getJSONObject(index)
            val at = json.optInt("index", -1)
            if (at in merged.indices) merged[at] = AgentEntry.parse(json)
        }
        // A hole left over means we are out of step with the engine — a row
        // exists that no delta has ever described. Start again rather than
        // draw a blank message where a real one belongs.
        if (merged.any { it == null }) return AgentConversation()
        AgentConversation(merged.filterNotNull(), root.optLong("revision", revision))
    }.getOrDefault(this)
}

/** One file of the review tab, as [CoreBridge.acpEditedFiles] reports it. */
data class AgentEditedFile(
    val path: String,
    /** `pending` wants a decision; `kept` has had one. */
    val status: String,
    val created: Boolean,
    val deleted: Boolean,
    val diff: FileDiff,
) {
    val isPending: Boolean get() = status == "pending"
}

/** The review tab's rows, and the revision they were read at. */
data class AgentReview(
    val version: Long = 0,
    val files: List<AgentEditedFile> = emptyList(),
) {
    val pending: List<AgentEditedFile> get() = files.filter { it.isPending }

    companion object {
        val NONE = AgentReview()

        fun parse(text: String): AgentReview = runCatching {
            val root = JSONObject(text)
            val items = root.optJSONArray("files") ?: JSONArray()
            AgentReview(
                version = root.optLong("version"),
                files = (0 until items.length()).mapNotNull { index ->
                    val item = items.optJSONObject(index) ?: return@mapNotNull null
                    val diff = item.optJSONObject("diff") ?: return@mapNotNull null
                    AgentEditedFile(
                        path = item.optString("path"),
                        status = item.optString("status", "pending"),
                        created = item.optBoolean("created"),
                        deleted = item.optBoolean("deleted"),
                        diff = FileDiff.parse(diff),
                    )
                },
            )
        }.getOrDefault(NONE)
    }
}

/** Both halves of what the panel draws, polled together. */
data class AgentSessionSnapshot(
    val state: AgentSessionState = AgentSessionState.NONE,
    val conversation: AgentConversation = AgentConversation(),
)

/** How often the panel looks for news while a session is open. */
private const val POLL_MS = 120L

/**
 * Poll one session, reading only when its counter moves.
 *
 * The house pattern, and deliberately not `produceState`: the "seen" value
 * lives beside the loop rather than in an effect's keys, because a counter
 * that starts at zero and is corrected a frame later makes a keyed effect run
 * twice — which for a guest command means two processes
 * (agent-docs/CONVENTIONS.md § Traps, item 3).
 *
 * 120 ms rather than the 250 the other panels use: this is streaming text, and
 * a reply that arrives in quarter-second steps reads as stuttering rather than
 * as typing.
 */
@Composable
fun rememberAgentSession(sessionId: Long?): AgentSessionSnapshot {
    var snapshot by remember(sessionId) { mutableStateOf(AgentSessionSnapshot()) }
    ResumedEffect(sessionId) {
        if (sessionId == null || sessionId < 0) return@ResumedEffect
        withContext(Dispatchers.Default) {
            var seen = -1L
            // Seeded from the snapshot, not fresh: coming back to the
            // foreground restarts this block, and starting the merge from
            // what is already on screen re-reads only the rows that moved
            // while the app was away instead of the whole transcript.
            var conversation = snapshot.conversation
            while (true) {
                val version = CoreBridge.acpSessionVersion(sessionId)
                if (version != seen) {
                    val state = AgentSessionState.parse(CoreBridge.acpSessionState(sessionId))
                    val merged = conversation.apply(
                        CoreBridge.acpEntriesSince(sessionId, conversation.revision)
                    )
                    val next = AgentSessionSnapshot(state, merged)
                    conversation = merged
                    withContext(Dispatchers.Main) { snapshot = next }
                    // A truncation puts us back to nothing and asks to be
                    // re-read from zero. Recording the version we just saw
                    // would mean waiting for the *next* change to do it,
                    // leaving the panel blank in between — so leave `seen`
                    // behind instead.
                    seen = if (merged.revision == 0L && state.entryCount > 0) {
                        -1L
                    } else {
                        version
                    }
                }
                delay(POLL_MS)
            }
        }
    }
    return snapshot
}

/**
 * Poll the agent's questions that belong to no session.
 *
 * Separate from the session poll because there may *be* no session: an agent
 * can ask for a token while authenticating, before any conversation exists,
 * and one of those left unanswered blocks it for ever. The panel shows these
 * wherever it is — over the agent picker, over the threads list, over
 * "starting the agent".
 */
@Composable
fun rememberPendingElicitations(enabled: Boolean): List<AgentElicitation> {
    var questions by remember { mutableStateOf(emptyList<AgentElicitation>()) }
    ResumedEffect(enabled) {
        if (!enabled) {
            questions = emptyList()
            return@ResumedEffect
        }
        // The counter covers session-scoped questions too, so a move can find
        // this list unchanged — one spare read per question raised anywhere,
        // instead of a serialize-and-parse per tick to find out nothing
        // happened.
        pollVersion(
            intervalMs = POLL_MS,
            version = { CoreBridge.acpElicitationsVersion() },
            read = {
                parseElicitations(
                    runCatching { JSONArray(CoreBridge.acpPendingElicitations()) }.getOrNull(),
                )
            },
            apply = { questions = it },
        )
    }
    return questions
}

/**
 * Poll the Spettro questions that belong to no session.
 *
 * The twin of [rememberPendingElicitations] and for the same reason: an
 * ask-user form can be raised while the agent is starting or authenticating,
 * before any conversation exists, and one left unanswered blocks the agent
 * for ever. A question that *does* name a session is already in
 * [AgentSessionState.questions] and needs no second loop — this list may
 * therefore repeat one the panel is already showing, and the caller filters
 * by [SpettroQuestion.session].
 *
 * Costs one JNI counter read per tick on every agent, Spettro or not: a
 * non-Spettro agent never serves the extension, so the counter never moves.
 */
@Composable
fun rememberSpettroQuestions(enabled: Boolean): List<SpettroQuestion> {
    var questions by remember { mutableStateOf(emptyList<SpettroQuestion>()) }
    ResumedEffect(enabled) {
        if (!enabled) {
            questions = emptyList()
            return@ResumedEffect
        }
        pollVersion(
            intervalMs = POLL_MS,
            version = { CoreBridge.acpQuestionsVersion() },
            read = {
                SpettroQuestion.parseAll(
                    runCatching { JSONArray(CoreBridge.acpPendingQuestions()) }.getOrNull(),
                )
            },
            apply = { questions = it },
        )
    }
    return questions
}

/**
 * Poll the agent's own past conversations while the threads view is open.
 *
 * [refreshToken] is what asks for a *fresh* list: change it (opening the
 * view, finishing a delete) and the next tick makes the round trip to the
 * agent. Every other tick reads the engine's cache, so an open view costs a
 * version compare rather than a request.
 */
@Composable
fun rememberAgentSessionList(enabled: Boolean, refreshToken: Int): AgentSessionList {
    var list by remember { mutableStateOf(AgentSessionList.NONE) }
    // Outside the lifecycle block: the round trip is owed to the *ask*, not
    // to the block — coming back to the foreground must re-read the cache,
    // never re-ask the agent.
    var refreshOwed by remember(enabled, refreshToken) { mutableStateOf(true) }
    ResumedEffect(enabled, refreshToken) {
        if (!enabled) {
            list = AgentSessionList.NONE
            return@ResumedEffect
        }
        withContext(Dispatchers.Default) {
            var seen = -1L
            while (true) {
                // The counter first, and the full serialize-and-parse only
                // when it moved: the refresh bumps it at both ends (loading,
                // then the answer), so both of those still arrive.
                val version = CoreBridge.acpSessionListVersion()
                val refresh = refreshOwed
                if (refresh || version != seen) {
                    val fresh = AgentSessionList.parse(CoreBridge.acpSessionList(refresh))
                    seen = version
                    withContext(Dispatchers.Main) {
                        refreshOwed = false
                        list = fresh
                    }
                }
                delay(POLL_MS)
            }
        }
    }
    return list
}

/**
 * Poll one agent terminal while its card is on screen.
 *
 * Separate from [rememberAgentSession] on purpose. A terminal's output is the
 * one thing in a session that grows without bound, and putting it in the
 * entry delta would re-send the whole tool-call card on every chunk; keeping
 * it here means the transcript's poll stays the size of the transcript, and
 * the terminal's poll costs a revision compare until something is actually
 * printed.
 *
 * The loop stops on its own once the command has ended — a finished terminal
 * never changes again, and nothing that never changes is worth waking for.
 */
@Composable
fun rememberAgentTerminal(terminalId: String, enabled: Boolean = true): AgentTerminalState {
    var state by remember(terminalId) { mutableStateOf(AgentTerminalState.Gone) }
    // Outside the lifecycle block, because finality must survive its
    // restarts: coming back to the foreground re-runs the block, and asking
    // the engine about a terminal it has since evicted would overwrite the
    // kept record with Gone.
    var over by remember(terminalId) { mutableStateOf(false) }
    ResumedEffect(terminalId, enabled, over) {
        // A terminal whose output is already sealed onto the entry needs no
        // poll at all: the record is on the card, and the engine may well
        // have evicted the live one.
        if (!enabled || over) return@ResumedEffect
        withContext(Dispatchers.Default) {
            var seen = 0L
            while (true) {
                val previous = state
                val fresh =
                    AgentTerminalState.parse(CoreBridge.acpTerminalOutput(terminalId, seen), previous)
                seen = fresh.revision
                // Gone (released, or its session closed) and finished are both
                // final; the card keeps what it last read.
                val finished = fresh.revision == 0L || !fresh.running
                // `parse` answers with `previous` itself when nothing moved,
                // so identity is the no-change test.
                if (fresh !== previous || finished) {
                    withContext(Dispatchers.Main) {
                        state = fresh
                        if (finished) over = true
                    }
                }
                if (finished) return@withContext
                delay(POLL_MS)
            }
        }
    }
    return state
}
