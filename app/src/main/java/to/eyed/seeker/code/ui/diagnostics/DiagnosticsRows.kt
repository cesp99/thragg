package to.eyed.seeker.code.ui.diagnostics

import to.eyed.seeker.code.ui.editor.Diagnostic
import to.eyed.seeker.code.ui.editor.DiagnosticSeverity
import to.eyed.seeker.code.ui.editor.FileDiagnosticRows

/**
 * One drawn line of the diagnostics list.
 *
 * The engine hands back files with their rows nested; a `LazyColumn` wants one
 * flat list, and so does keyboard navigation — the arrows walk what is on
 * screen, not a tree. Flattening is pure, which is why it lives here and is
 * tested rather than being written inline in the panel — the same split the
 * search results make in `ProjectSearchRows`.
 */
sealed interface DiagnosticsRow {
    /** Project-relative path of the file this row belongs to. */
    val path: String

    /** List key, unique within one flattening. */
    val key: String

    data class FileRow(
        override val path: String,
        val name: String,
        /** Everything before the name, "" at the project root. */
        val directory: String,
        /** Counts over the rows *shown*, so the header agrees with the fold. */
        val errors: Int,
        val warnings: Int,
        val isCollapsed: Boolean,
    ) : DiagnosticsRow {
        override val key: String get() = "f/$path"
    }

    data class IssueRow(
        override val path: String,
        /** Position within the file's own shown rows, and half of the key. */
        val index: Int,
        val diagnostic: Diagnostic,
    ) : DiagnosticsRow {
        override val key: String get() = "i/$path/$index"
    }
}

/**
 * The files, flattened to rows, with [collapsed] files showing their header
 * only.
 *
 * [includeWarnings] is Zed's `ToggleWarnings` on its project diagnostics
 * (crates/diagnostics/src/diagnostics.rs): off, only errors are listed —
 * warnings, infos and hints all wait behind the toggle, because "show me what
 * is broken" is the question the off state asks. A file whose every row is
 * filtered out disappears entirely, header and all.
 */
fun diagnosticsRows(
    files: List<FileDiagnosticRows>,
    collapsed: Set<String>,
    includeWarnings: Boolean,
): List<DiagnosticsRow> {
    val rows = ArrayList<DiagnosticsRow>(files.size)
    for (file in files) {
        val shown = if (includeWarnings) {
            file.rows
        } else {
            file.rows.filter { it.severity == DiagnosticSeverity.Error }
        }
        if (shown.isEmpty()) continue
        val isCollapsed = file.path in collapsed
        val cut = file.path.lastIndexOf('/')
        rows.add(
            DiagnosticsRow.FileRow(
                path = file.path,
                name = file.path.substring(cut + 1),
                directory = if (cut < 0) "" else file.path.substring(0, cut),
                errors = shown.count { it.severity == DiagnosticSeverity.Error },
                warnings = shown.count { it.severity == DiagnosticSeverity.Warning },
                isCollapsed = isCollapsed,
            )
        )
        if (isCollapsed) continue
        shown.forEachIndexed { index, diagnostic ->
            rows.add(DiagnosticsRow.IssueRow(file.path, index, diagnostic))
        }
    }
    return rows
}
