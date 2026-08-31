package to.eyed.seeker.code.solana.build

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import to.eyed.seeker.code.ui.editor.DiagnosticSeverity

/**
 * Turning what a build printed into rows the app can act on.
 *
 * Two parsers in one file, because a build on this device produces two kinds
 * of output and only one of them is structured:
 *
 *  1. **cargo's JSON.** `--message-format=json-diagnostic-rendered-ansi` gives
 *     one self-describing object per line, with the file, the 1-based line and
 *     column, the level, the code and rustc's own rendered snippet. This is
 *     the good path and it is used wherever *cargo* is the process being
 *     driven ([BuildTasks.buildCommand] asks for it).
 *  2. **Everything else.** `anchor build` drives cargo itself and hands us
 *     rendered text; `seahorse build` is Python; the linker, the IDL
 *     generator, `cargo-build-sbf`'s own "Failed to execute rustup" and
 *     mocha are none of them cargo. For those there is a line parser.
 *
 * The design does not depend on the second parser being perfect, and that is
 * the contract this file is written to: **an unparsed line is never lost.**
 * Every line either becomes a [BuildLogEvent.Issue] or comes back as a
 * [BuildLogEvent.Text] that the log renders monospaced, can copy, and can hand
 * to the agent. There is no third outcome and no silent drop, which is why the
 * fallback path is tested rather than assumed (CargoDiagnosticsTest).
 *
 * The parser is a *stream*: [feed] takes one line and returns what that line
 * completed, because rustc writes a diagnostic's message and its location on
 * two consecutive lines and the log has to show a build as it happens rather
 * than when it ends. [flush] closes whatever the last line left open.
 */

/**
 * One problem a build reported, in the coordinates the compiler printed them:
 * **1-based** line and column, as every compiler on earth prints them and as
 * the `path:line:col` rows in the log show them.
 *
 * [BuildDiagnostics] is where these become the editor's 0-based
 * [to.eyed.seeker.code.ui.editor.Diagnostic]; keeping the conversion in one
 * place means an off-by-one is a single test rather than a hunt.
 */
data class BuildIssue(
    /**
     * The file, exactly as the compiler spelled it — usually relative to the
     * directory cargo ran in, sometimes absolute (a dependency in the registry
     * has an absolute guest path). Null for a problem with no file at all:
     * "Failed to execute rustup", a linker error, an IDL failure.
     */
    val path: String?,
    /** 1-based; 0 when the producer gave no position. */
    val line: Int = 0,
    /** 1-based; 0 when the producer gave no position. */
    val column: Int = 0,
    val severity: DiagnosticSeverity = DiagnosticSeverity.Error,
    val message: String,
    /** `E0609`, `ts6133`, or null. */
    val code: String? = null,
    /**
     * The producer's own rendered block, kept whole for "Fix with agent" —
     * an E0609 without the snippet under it is half a bug report.
     */
    val rendered: String? = null,
) {
    /** `programs/escrow/src/lib.rs:17:26`, or just the path, or null. */
    val location: String?
        get() {
            val file = path ?: return null
            if (line <= 0) return file
            return if (column > 0) "$file:$line:$column" else "$file:$line"
        }

    /** The first line of the message, which is all a one-line row can show. */
    val firstLine: String get() = message.substringBefore('\n')
}

/** What one line of build output turned into. */
sealed interface BuildLogEvent {
    /** Print it as it was written. The fallback, and never a failure. */
    data class Text(val line: String) : BuildLogEvent

    /** A problem, tappable and countable. */
    data class Issue(val issue: BuildIssue) : BuildLogEvent
}

/**
 * The streaming parser. One instance per run; not thread-safe, and it does not
 * need to be — [BuildRunner] feeds it from the one reader thread.
 *
 * [jsonDiagnostics] only says whether JSON was *asked* for. The JSON branch is
 * tried on any line that looks like a cargo message either way, because
 * `anchor build` forwards whatever cargo printed and a future Anchor may well
 * pass the flag through.
 */
class CargoDiagnostics(private val jsonDiagnostics: Boolean = true) {

    /**
     * A rustc diagnostic whose header has been read and whose `-->` line has
     * not arrived yet. Held for exactly one line: the location always follows
     * the message immediately, and if it does not, the diagnostic simply has
     * no location.
     */
    private var pending: BuildIssue? = null

    /** A mocha failure being collected across lines; see [feedMocha]. */
    private var mocha: MochaFailure? = null

    fun feed(line: String): List<BuildLogEvent> {
        val events = ArrayList<BuildLogEvent>(2)

        // A location for the header we are holding: attach and emit, and do
        // not print the line — the row the issue becomes already shows it.
        val held = pending
        if (held != null) {
            val where = LOCATION.find(line)
            pending = null
            if (where != null) {
                events += BuildLogEvent.Issue(
                    held.copy(
                        path = where.groupValues[1].trim(),
                        line = where.groupValues[2].toIntOrNull() ?: 0,
                        column = where.groupValues[3].toIntOrNull() ?: 0,
                    )
                )
                return events
            }
            // No location followed, so the diagnostic has none. Emit it and
            // carry on reading this line as an ordinary one.
            events += BuildLogEvent.Issue(held)
        }

        // cargo's JSON. Cheap guard first: this runs on every line of a build
        // that prints tens of thousands of them.
        if (line.startsWith("{") && REASON in line) {
            val parsed = parseCargoMessage(line)
            if (parsed != null) {
                // Recognised and structural: the artifact and build-finished
                // records carry nothing a person reads, so they are swallowed
                // rather than printed as JSON.
                parsed.forEach { events += BuildLogEvent.Issue(it) }
                return events
            }
            if (jsonDiagnostics && isCargoEnvelope(line)) return events
        }

        // A rustc/cargo header: `error[E0609]: no field …`.
        val header = HEADER.find(line)
        if (header != null) {
            val level = header.groupValues[1]
            val code = header.groupValues[2].takeIf { it.isNotEmpty() }
            val message = header.groupValues[3].trim()
            val severity = severityOf(level)
            if (severity != null && !isEpilogue(message)) {
                pending = BuildIssue(
                    path = null,
                    severity = severity,
                    message = message,
                    code = code,
                    rendered = line,
                )
                return events
            }
            // A note, a help, or cargo's "could not compile … due to 1
            // previous error": real output, but not a problem of its own.
            events += BuildLogEvent.Text(line)
            return events
        }

        // The producers that do not speak rustc.
        val other = parseForeign(line)
        if (other != null) {
            events += BuildLogEvent.Issue(other)
            events += BuildLogEvent.Text(line)
            return events
        }

        feedMocha(line)?.let { events += BuildLogEvent.Issue(it) }
        events += BuildLogEvent.Text(line)
        return events
    }

    /** Whatever the last line left open. Always called; never throws. */
    fun flush(): List<BuildLogEvent> {
        val events = ArrayList<BuildLogEvent>(2)
        pending?.let { events += BuildLogEvent.Issue(it) }
        pending = null
        mocha?.finish()?.let { events += BuildLogEvent.Issue(it) }
        mocha = null
        return events
    }

    // --- mocha ---------------------------------------------------------------

    /**
     * Mocha's failure block, which is four lines of one report:
     *
     * ```
     *   1) escrow
     *        is initialized!:
     *      Error: failed to send transaction
     *       at Context.<anonymous> (tests/escrow.ts:15:5)
     * ```
     *
     * Unlike a rustc diagnostic the lines are *not* swallowed — the block is
     * the readable part and the stack under it is often the answer — so this
     * only adds a row that points at the file, and the log keeps mocha's own
     * words above it.
     */
    private class MochaFailure(val title: String) {
        var lines = 0
        var message: String? = null
        var path: String? = null
        var line = 0
        var column = 0

        fun finish(): BuildIssue = BuildIssue(
            path = path,
            line = line,
            column = column,
            severity = DiagnosticSeverity.Error,
            message = listOfNotNull(title.takeIf { it.isNotEmpty() }, message)
                .joinToString(" — ")
                .ifEmpty { "test failed" },
            code = "test",
        )
    }

    private fun feedMocha(line: String): BuildIssue? {
        val started = MOCHA_FAILURE.find(line)
        if (started != null) {
            val finished = mocha?.finish()
            mocha = MochaFailure(started.groupValues[2].trim().trimEnd(':'))
            return finished
        }
        val current = mocha ?: return null
        current.lines++
        if (current.message == null) {
            MOCHA_MESSAGE.find(line)?.let { match ->
                current.message = match.value.trim()
            }
        }
        MOCHA_FRAME.find(line)?.let { match ->
            current.path = match.groupValues[1]
            current.line = match.groupValues[2].toIntOrNull() ?: 0
            current.column = match.groupValues[3].toIntOrNull() ?: 0
            mocha = null
            return current.finish()
        }
        if (current.lines > MOCHA_WINDOW) {
            mocha = null
            return current.finish()
        }
        return null
    }

    companion object {

        /**
         * `error[E0609]: no field …`, `warning: unused import: …`,
         * `error: proc-macro derive panicked`.
         *
         * Anchored at the start of the line on purpose: rustc's *rendered*
         * output indents everything under a header, and matching an indented
         * `error:` would turn the body of one diagnostic into several.
         */
        private val HEADER =
            Regex("""^(error|warning|note|help)(?:\[([A-Za-z0-9]+)])?:\s*(.*)$""")

        /** `  --> programs/escrow/src/lib.rs:17:26` */
        private val LOCATION = Regex("""^\s*-->\s+(\S.*?):(\d+):(\d+)\s*$""")

        /** Cheap pre-test before the JSON parser is asked for an opinion. */
        private const val REASON = "\"reason\""

        /**
         * `Error: Unable to read keypair file`, `Error: failed to generate IDL`
         * — the Agave CLI's and Anchor's own shape, capital E and no code.
         *
         * Anchored hard at column zero, unlike the others. mocha indents its
         * `Error:` inside a failure block by five spaces, and a lenient anchor
         * here reported every failing test twice: once as a CLI-shaped error
         * and once as the mocha failure it is actually part of.
         */
        private val CAPITAL_ERROR = Regex("""^Error:\s*(.+)$""")

        /** `rust-lld: error: …`, `ld.lld: error: …`, `clang: error: …`. */
        private val LINKER_ERROR = Regex("""^\s*(?:[\w.\-]*ld[\w.\-]*|clang|cc):\s*error:\s*(.+)$""")

        /**
         * `Failed to execute rustup: …` — `cargo-build-sbf`'s own death when
         * rustup is missing, and the reason the installer puts rustup in the
         * guest without letting it own a compiler (docs/SOLANA.md).
         */
        private val FAILED_TO_EXECUTE = Regex("""^\s*(Failed to execute\s+(\S+).*)$""")

        /** `  1) escrow` — the head of a mocha failure block. */
        private val MOCHA_FAILURE = Regex("""^\s{1,6}(\d+)\)\s+(.*)$""")

        /** `     Error: failed to send transaction`, `AssertionError: …`. */
        private val MOCHA_MESSAGE = Regex("""\b[A-Za-z]*Error\b.*$""")

        /** `      at Context.<anonymous> (tests/escrow.ts:15:5)` */
        private val MOCHA_FRAME = Regex("""^\s*at\s.*\(([^()]+?):(\d+):(\d+)\)\s*$""")

        /** How many lines a mocha failure block may run to before it is closed. */
        private const val MOCHA_WINDOW = 12

        /**
         * The lines that are a *consequence* of diagnostics rather than
         * diagnostics themselves. Counting cargo's "could not compile … due to
         * 1 previous error" as a second error would make every failed build
         * report one more error than the compiler found.
         */
        private val EPILOGUE = listOf(
            "could not compile",
            "aborting due to",
            "build failed",
            "failed to run custom build command",
            "process didn't exit successfully",
        )

        private fun isEpilogue(message: String): Boolean {
            val text = message.lowercase()
            return EPILOGUE.any { text.startsWith(it) }
        }

        /**
         * rustc's four levels, of which two are problems. `note` and `help`
         * are the *body* of somebody else's diagnostic; promoting them would
         * put three rows in Problems for one mistake.
         */
        private fun severityOf(level: String): DiagnosticSeverity? = when (level) {
            "error" -> DiagnosticSeverity.Error
            "warning" -> DiagnosticSeverity.Warning
            else -> null
        }

        /**
         * A cargo record that carries no diagnostic — `compiler-artifact`,
         * `build-script-executed`, `build-finished`. Swallowed rather than
         * printed, because one of them is emitted per crate and they are
         * hundreds of characters of machine JSON each.
         */
        private fun isCargoEnvelope(line: String): Boolean = runCatching {
            JSONObject(line).optString("reason").isNotEmpty()
        }.getOrDefault(false)

        /**
         * One `"reason": "compiler-message"` line, or null when the line is
         * not one (including when it is not JSON at all — the caller then
         * treats it as text, which is the whole fallback contract).
         *
         * Only the **primary** span is used for the position. rustc lists
         * every span it touched, and the secondary ones are the "borrow starts
         * here" annotations: navigating to one of those instead of to the
         * error is worse than not navigating at all.
         */
        fun parseCargoMessage(line: String): List<BuildIssue>? {
            val root = try {
                JSONObject(line)
            } catch (_: JSONException) {
                return null
            }
            if (root.optString("reason") != "compiler-message") return null
            val message = root.optJSONObject("message") ?: return null
            return listOfNotNull(issueOf(message))
        }

        /** One rustc diagnostic object → an issue, or null when it is a note. */
        private fun issueOf(message: JSONObject): BuildIssue? {
            val level = message.optString("level")
            val severity = when {
                level.startsWith("error") -> DiagnosticSeverity.Error
                level == "warning" -> DiagnosticSeverity.Warning
                else -> null
            } ?: return null
            val text = message.optString("message").takeIf { it.isNotEmpty() } ?: return null
            if (isEpilogue(text)) return null
            val code = message.optJSONObject("code")?.optString("code")?.takeIf { it.isNotEmpty() }
            val span = primarySpan(message.optJSONArray("spans"))
            val rendered = message.optString("rendered").takeIf { it.isNotEmpty() }
            return BuildIssue(
                path = span?.optString("file_name")?.takeIf { it.isNotEmpty() },
                line = span?.optInt("line_start", 0) ?: 0,
                column = span?.optInt("column_start", 0) ?: 0,
                severity = severity,
                message = text,
                code = code,
                rendered = rendered,
            )
        }

        /**
         * The primary span, else the first — a macro-expanded diagnostic
         * sometimes marks none of its spans primary, and a location in the
         * right file beats no location.
         */
        private fun primarySpan(spans: JSONArray?): JSONObject? {
            spans ?: return null
            var first: JSONObject? = null
            for (i in 0 until spans.length()) {
                val span = spans.optJSONObject(i) ?: continue
                if (first == null) first = span
                if (span.optBoolean("is_primary", false)) return span
            }
            return first
        }

        /**
         * The producers that are not rustc: the Agave CLI, Anchor's IDL
         * generation, the linker, and `cargo-build-sbf` failing to find
         * rustup. Each of these is a dead end for a user who is only shown
         * text, and each has a recognisable first line.
         */
        private fun parseForeign(line: String): BuildIssue? {
            FAILED_TO_EXECUTE.find(line)?.let { match ->
                val program = match.groupValues[2].trim(':', '"')
                return BuildIssue(
                    path = null,
                    message = match.groupValues[1].trim() +
                        if (program.startsWith("rustup")) {
                            " — cargo-build-sbf drives platform-tools through rustup, " +
                                "so rustup must be installed in the userland even though " +
                                "it owns no compiler."
                        } else {
                            ""
                        },
                    code = "toolchain",
                    rendered = line,
                )
            }
            LINKER_ERROR.find(line)?.let { match ->
                return BuildIssue(
                    path = null,
                    message = match.groupValues[1].trim(),
                    code = "link",
                    rendered = line,
                )
            }
            CAPITAL_ERROR.find(line)?.let { match ->
                return BuildIssue(
                    path = null,
                    message = match.groupValues[1].trim(),
                    rendered = line,
                )
            }
            return null
        }

        /**
         * The whole of a build's output at once — what the tests use, and what
         * a paste into the parser would do. Streaming order is preserved.
         */
        fun parseAll(output: String, jsonDiagnostics: Boolean = true): List<BuildLogEvent> {
            val parser = CargoDiagnostics(jsonDiagnostics)
            val events = ArrayList<BuildLogEvent>()
            for (line in output.lineSequence()) events += parser.feed(line)
            events += parser.flush()
            return events
        }

        /** Just the problems, in order. */
        fun issues(output: String, jsonDiagnostics: Boolean = true): List<BuildIssue> =
            parseAll(output, jsonDiagnostics).filterIsInstance<BuildLogEvent.Issue>()
                .map { it.issue }
    }
}
