package to.eyed.thragg.solana.build

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import to.eyed.thragg.ui.editor.Diagnostic
import to.eyed.thragg.ui.editor.DiagnosticSeverity
import to.eyed.thragg.ui.editor.DiagnosticSummary
import to.eyed.thragg.ui.editor.FileDiagnosticRows
import to.eyed.thragg.ui.editor.FileDiagnostics
import to.eyed.thragg.ui.editor.ProjectDiagnosticRows
import java.io.File

/**
 * Where a build's diagnostics live, because the engine will not take them.
 *
 * This is the merge layer, and it exists for a fact that was verified in the
 * engine rather than assumed: **the diagnostics store is read-only across
 * JNI.** `CoreBridge.lspDiagnostics`, `lspDiagnosticRows` and
 * `bufferDiagnostics` are `external fun` *reads* with no ingest path, so the
 * appealing sentence in docs/SOLANA.md — "cargo's JSON diagnostics are parsed
 * and fed into the same diagnostics store the language server writes to" — is
 * not something Kotlin can do. What it can do, and what this file is, is hold
 * the build's diagnostics beside the engine's and merge the two at every
 * consumer: the editor's block rows and gutter marks, the Problems route, and
 * the header counts (docs/UI.md, P4).
 *
 * Three rules follow from that and are enforced here rather than at each
 * consumer:
 *
 *  1. **Every row is tagged with its producer.** `cargo · anchor build`
 *     against `rust-analyzer`, carried in [Diagnostic.source], so a build
 *     error from four minutes ago is distinguishable from what the language
 *     server thinks right now. Without the tag the two sets are
 *     indistinguishable and the older one is always the liar.
 *  2. **The build's set is cleared at the start of each run**, not at the end
 *     of it. A build that is running has not yet contradicted anything, but
 *     showing its predecessor's errors while it runs is exactly the "read the
 *     error you already fixed" failure the save-before-build rule exists for.
 *  3. **Consumers read the merge, never the engine directly.** That is a
 *     convention this file cannot enforce, and it is written down here so the
 *     next reader knows it is a rule and not an accident.
 *
 * Held outside the composition for the same reason [BuildRunner] is: the
 * result of a 71-second build must survive a rotation, a destination switch
 * and the activity being rebuilt.
 */
object BuildDiagnostics {

    /**
     * Bumped on every publish and every clear.
     *
     * The store itself is plain collections — merging is a pure function over
     * them and belongs in a host test — so composition watches this counter
     * instead. Same shape as [to.eyed.thragg.ui.tasks.TaskRuns.revision]
     * and for the same reason.
     */
    var version by mutableIntStateOf(0)
        private set

    /** Project-relative (or absolute, when outside) path → its rows, sorted. */
    private var byPath: Map<String, List<Diagnostic>> = emptyMap()

    /** The root the current set was published against; nothing matches without it. */
    private var root: String? = null

    /** `cargo · anchor build`, or null when nothing has been published. */
    var producer: String? = null
        private set

    var errors: Int = 0
        private set

    var warnings: Int = 0
        private set

    /** Every file the last run reported on, sorted by path. */
    val files: List<FileDiagnosticRows>
        get() = byPath.entries.sortedBy { it.key }
            .map { (path, rows) -> FileDiagnosticRows(path, rows) }

    /** Drop the last run's rows. Called at the *start* of a run — see rule 2. */
    fun clear() {
        if (byPath.isEmpty() && producer == null) return
        byPath = emptyMap()
        root = null
        producer = null
        errors = 0
        warnings = 0
        version++
    }

    /**
     * Replace the build's set with [issues], as reported by [producer] for the
     * project at [projectRoot].
     *
     * Replace rather than append: a run reports on the whole project every
     * time, and appending would keep a fixed error alive until a clean build
     * happened to touch the same file.
     */
    fun publish(projectRoot: String, producer: String, issues: List<BuildIssue>) {
        val grouped = LinkedHashMap<String, MutableList<Diagnostic>>()
        var errorCount = 0
        var warningCount = 0
        for (issue in issues) {
            when (issue.severity) {
                DiagnosticSeverity.Error -> errorCount++
                DiagnosticSeverity.Warning -> warningCount++
                else -> Unit
            }
            // A problem with no file — "Failed to execute rustup", a linker
            // error — is real and is counted, but it cannot be a row in a
            // file's list. The Build log is where those are read; Problems is
            // a per-file screen and inventing a file for them would be a lie
            // with a tappable row on it.
            val path = issue.path ?: continue
            grouped.getOrPut(normalizePath(projectRoot, path)) { mutableListOf() }
                .add(toDiagnostic(issue, producer))
        }
        byPath = grouped.mapValues { (_, rows) -> rows.sortedWith(ROW_ORDER) }
        root = projectRoot
        this.producer = producer
        errors = errorCount
        warnings = warningCount
        version++
    }

    /**
     * The build's rows for one file, given its absolute host path — what the
     * editor's gutter and inline blocks merge with the engine's.
     *
     * Empty for a file the last build said nothing about, which is the
     * overwhelmingly common case and is why this is a map lookup rather than
     * a scan.
     */
    fun rowsFor(absolutePath: String): List<Diagnostic> {
        val projectRoot = root ?: return emptyList()
        val relative = normalizePath(projectRoot, absolutePath)
        return byPath[relative].orEmpty()
    }

    /** The build's rows for a path already spelled the way the engine spells it. */
    fun rowsForProjectPath(path: String): List<Diagnostic> = byPath[path].orEmpty()

    /**
     * The engine's project rows with the build's merged in — what the Problems
     * route lists.
     *
     * Per file, the two producers' rows are concatenated and re-sorted by
     * position, so one file's errors read top to bottom regardless of which
     * tool found them. Duplicates are *not* removed: rust-analyzer and cargo
     * genuinely disagree about the same line often enough (a proc-macro that
     * one expands and the other does not) that silently dropping one of them
     * would hide the more accurate answer as often as the redundant one, and
     * the producer tag is on every row precisely so the reader can decide.
     */
    fun merge(lsp: ProjectDiagnosticRows): ProjectDiagnosticRows {
        if (byPath.isEmpty()) return lsp
        val merged = LinkedHashMap<String, MutableList<Diagnostic>>()
        for (file in lsp.files) merged.getOrPut(file.path) { mutableListOf() }.addAll(file.rows)
        for ((path, rows) in byPath) merged.getOrPut(path) { mutableListOf() }.addAll(rows)
        return ProjectDiagnosticRows(
            // The engine's counter plus ours, so a consumer keyed on the
            // version recomputes when either side moves.
            version = lsp.version + version,
            files = merged.entries.sortedBy { it.key }
                .map { (path, rows) -> FileDiagnosticRows(path, rows.sortedWith(ROW_ORDER)) },
        )
    }

    /** The engine's counts with the build's added — what a header badge shows. */
    fun merge(lsp: DiagnosticSummary): DiagnosticSummary {
        if (byPath.isEmpty()) return lsp
        val counts = LinkedHashMap<String, FileDiagnostics>()
        for (file in lsp.files) counts[file.path] = file
        for ((path, rows) in byPath) {
            val existing = counts[path]
            counts[path] = FileDiagnostics(
                path = path,
                errors = (existing?.errors ?: 0) + rows.count { it.severity == DiagnosticSeverity.Error },
                warnings = (existing?.warnings ?: 0) + rows.count { it.severity == DiagnosticSeverity.Warning },
                infos = (existing?.infos ?: 0) + rows.count { it.severity == DiagnosticSeverity.Info },
                hints = (existing?.hints ?: 0) + rows.count { it.severity == DiagnosticSeverity.Hint },
            )
        }
        val files = counts.values.sortedBy { it.path }
        return DiagnosticSummary(
            version = lsp.version + version,
            errors = files.sumOf { it.errors },
            warnings = files.sumOf { it.warnings },
            infos = files.sumOf { it.infos },
            hints = files.sumOf { it.hints },
            files = files,
        )
    }

    // --- pure helpers, so the interesting parts are host-testable -------------

    /** Document order, the same order the bridge guarantees for its own rows. */
    private val ROW_ORDER = compareBy<Diagnostic>({ it.row }, { it.colUtf16 })

    /**
     * One issue as the editor's row model sees it.
     *
     * The conversion that matters is the coordinates: compilers print 1-based
     * lines and columns and the renderer works in 0-based rows and UTF-16
     * columns. rustc's `column_start` counts *characters*, not UTF-16 code
     * units, so a line with an emoji in it before the error can be off by one
     * unit — the alternative is reading the file here to re-measure, which is
     * a disk read per diagnostic to fix a case that does not occur in Rust
     * source. The row is exact, and the row is what the gutter and the jump
     * use.
     */
    fun toDiagnostic(issue: BuildIssue, producer: String): Diagnostic {
        val row = (issue.line - 1).coerceAtLeast(0)
        val col = (issue.column - 1).coerceAtLeast(0)
        return Diagnostic(
            row = row,
            colUtf16 = col,
            endRow = row,
            endColUtf16 = col,
            severity = issue.severity,
            message = issue.message,
            source = producer,
            code = issue.code,
        )
    }

    /**
     * The path a compiler printed, spelled the way the engine spells its own:
     * project-relative and `/`-separated, or left absolute when it names a
     * file outside the project (a crate in the registry, a header in
     * `/usr/include`), which is exactly [FileDiagnostics]'s own rule.
     *
     * Three prefixes have to come off, and only one of them is obvious. cargo
     * usually prints relative to the directory it ran in, which is the project
     * root. When it prints an absolute path it is a **guest** absolute path,
     * because the compiler ran inside the proot where the projects directory
     * is bound at `/projects` — so `/projects/escrow/src/lib.rs` and the host's
     * `/data/user/0/…/projects/escrow/src/lib.rs` are the same file and both
     * have to reduce to `src/lib.rs`.
     */
    fun normalizePath(projectRoot: String, printed: String): String {
        val root = projectRoot.trimEnd('/')
        val guestRoot = GUEST_PROJECTS + "/" + File(root).name
        var path = printed.trim().replace('\\', '/')
        for (prefix in listOf(root, guestRoot)) {
            if (path == prefix) return ""
            if (path.startsWith("$prefix/")) {
                path = path.removePrefix("$prefix/")
                break
            }
        }
        // `./x` and `a/../b` come out of build scripts and out of Anchor's
        // generated manifests; the engine's paths never carry them, so a row
        // spelled that way would key a file the project panel cannot match.
        return File(path).normalize().path
    }

    /** Where the userland binds the projects directory; see DebianUserland. */
    private const val GUEST_PROJECTS = "/projects"

    /**
     * The whole failed build as text for the agent — message, code, location
     * and the compiler's own rendered snippet, in the order they were
     * reported.
     *
     * This is what `[ Fix with agent ]` sends, and it is deliberately the
     * *rendered* block rather than a tidy summary: rustc's snippet with the
     * carets under the offending token is the single most useful thing an
     * agent can be handed, and re-formatting it loses the one thing it is
     * good at.
     */
    fun agentPrompt(issues: List<BuildIssue>, command: String, limit: Int = 8): String {
        val errors = issues.filter { it.severity == DiagnosticSeverity.Error }
        val chosen = (if (errors.isNotEmpty()) errors else issues).take(limit)
        return buildString {
            append("`")
            append(command)
            append("` failed with ")
            append(errors.size)
            append(if (errors.size == 1) " error" else " errors")
            append(". Please fix them.\n\n")
            for (issue in chosen) {
                append(issue.severity.token)
                issue.code?.let { append('[').append(it).append(']') }
                append(": ")
                append(issue.message)
                append('\n')
                issue.location?.let { append("  --> ").append(it).append('\n') }
                issue.rendered
                    ?.takeIf { it.lineSequence().count() > 1 }
                    ?.let { append(it.trimEnd()).append('\n') }
                append('\n')
            }
            if (issues.size > chosen.size) {
                append("(")
                append(issues.size - chosen.size)
                append(" more not shown.)\n")
            }
        }
    }
}
