package to.eyed.seeker.code.ui.shell.changes

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.io.File
import to.eyed.seeker.code.R
import to.eyed.seeker.code.solana.build.BuildDiagnostics
import to.eyed.seeker.code.ui.editor.Diagnostic
import to.eyed.seeker.code.ui.editor.DiagnosticSeverity
import to.eyed.seeker.code.ui.editor.FileDiagnosticRows
import to.eyed.seeker.code.ui.editor.ProjectDiagnosticRows
import to.eyed.seeker.code.ui.editor.rememberProjectDiagnostics
import to.eyed.seeker.code.ui.diagnostics.DiagnosticsPane
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.shell.build.CodeJump
import to.eyed.seeker.code.ui.shell.build.FlatButton
import to.eyed.seeker.code.ui.shell.build.askAgent
import to.eyed.seeker.code.ui.theme.ChipCaret
import to.eyed.seeker.code.ui.theme.IconSize
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.SeekerIcon
import to.eyed.seeker.code.ui.theme.touchTarget
import to.eyed.seeker.code.ui.workspace.ContextMenu
import to.eyed.seeker.code.ui.workspace.ContextMenuItem

/**
 * Problems — every diagnostic in the project, from **both** producers.
 *
 * "Read errors" is a named step in the loop this product is about, and it gets
 * a screen rather than a dock panel (docs/UI.md, "Problems"). The list itself
 * is not new: [DiagnosticsPane] has been a grouped, per-file, tappable list
 * with sticky headers all along, and re-hosting it is cheaper and better than
 * the rebuild every competing design proposed. What this route adds is the
 * three things the pane cannot know about:
 *
 *  1. **The merge.** The engine's diagnostics store is read-only across JNI,
 *     so a build's diagnostics live beside it in
 *     [BuildDiagnostics] and only a consumer can put the two together
 *     (BuildDiagnostics.kt). This is that consumer: [BuildDiagnostics.merge]
 *     over what the language server published, with every row still carrying
 *     the producer that found it — `cargo · anchor build` or `rust-analyzer`
 *     — under its message, so a build error from four minutes ago is
 *     distinguishable from what rust-analyzer thinks right now.
 *  2. **The filter**, which is finer than the pane's own warnings toggle:
 *     all / errors / warnings, in the header where the wireframe puts it.
 *  3. **The two exits.** A tap on a row opens Code at that row and column and
 *     pops this route, so build → tap → fix → build is two taps back; and
 *     `[ Fix with agent ]` sends the whole *filtered* set into the composer.
 */
@Composable
fun ProblemsScreen(state: ShellState, modifier: Modifier = Modifier) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    val project = state.project
    var filter by remember { mutableStateOf(ProblemFilter.All) }

    if (project == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No project is open.",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        return
    }

    // The language server's set, polled while this route is on screen — the
    // payload is every message in the project, which is why nothing else in
    // the shell reads it.
    val lsp = rememberProjectDiagnostics(project.id)
    // `BuildDiagnostics.version` is read *here*, in composition, and not
    // inside the merge: it is the snapshot-state counter that makes a build
    // publishing its errors repaint this list. Keyed on it rather than on the
    // store, which is plain collections and cannot be observed.
    val merged = remember(lsp, BuildDiagnostics.version) { BuildDiagnostics.merge(lsp) }
    val shown = remember(merged, filter) { filterProblems(merged, filter) }
    val errors = remember(shown) { countBy(shown, DiagnosticSeverity.Error) }
    val warnings = remember(shown) { countBy(shown, DiagnosticSeverity.Warning) }

    Column(modifier = modifier.fillMaxSize()) {
        // The route's own strip, under the shell's ← row: the filter on the
        // left where the wireframe has it, the counts on the right.
        Row(
            modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(filter) { filter = it }
            Box(modifier = Modifier.weight(1f))
            if (errors > 0) {
                CountMark(
                    icon = R.drawable.ic_ui_close,
                    count = errors,
                    spoken = "$errors errors",
                    tint = theme.color("error", MaterialTheme.colorScheme.error),
                )
            }
            if (warnings > 0) {
                CountMark(
                    icon = R.drawable.ic_ui_warning,
                    count = warnings,
                    spoken = "$warnings warnings",
                    tint = theme.color("warning", MaterialTheme.colorScheme.tertiary),
                )
            }
            if (errors == 0 && warnings == 0) {
                Text(
                    text = "clean",
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
        HorizontalDivider(color = theme.color("border", MaterialTheme.colorScheme.outlineVariant))

        DiagnosticsPane(
            project = project,
            // Nothing here bumps it: the pane's focus token exists for a
            // keyboard the shell does not have, and the route is opened by a
            // tap that has already put focus where it belongs.
            focusToken = 0,
            onOpenDiagnostic = { path, diagnostic ->
                openProblem(state, project.rootPath, path, diagnostic)
            },
            // Null, deliberately and finally: there is no multibuffer on this
            // device, and passing null here is what lets P10 delete
            // core/MultiBufferSession.kt without touching this pane
            // (docs/UI.md, P7).
            onOpenMultibuffer = null,
            onDismiss = { state.pop() },
            rows = shown,
            showToolbar = false,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        HorizontalDivider(color = theme.color("border", MaterialTheme.colorScheme.outlineVariant))
        // The one action, at the bottom, in the thumb's third of the screen —
        // as every consequential action in this app is (docs/UI.md).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            FlatButton(
                label = "Fix with agent",
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (shown.files.isEmpty()) return@FlatButton
                askAgent(state, context, problemsPrompt(shown))
            }
        }
    }
}

/**
 * The two counts in the strip: the mark that says what is being counted, then
 * the number.
 *
 * The mark is decoration and the *row* carries the words, because "close
 * icon, 3" is not what a screen reader should say about three compiler
 * errors. Same pairing as Code's header count (CodeScreen.ProblemsAction) and
 * the same two drawables the log rows use (BuildLogView), so one project has
 * one error mark rather than three.
 */
@Composable
private fun CountMark(
    @DrawableRes icon: Int,
    count: Int,
    spoken: String,
    tint: androidx.compose.ui.graphics.Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = spoken },
    ) {
        SeekerIcon(
            icon = icon,
            contentDescription = null,
            tint = tint,
            size = IconSize.Marker,
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

/** `all ⌄` — the filter, as a chip that opens a three-row menu. */
@Composable
private fun FilterChip(filter: ProblemFilter, onPick: (ProblemFilter) -> Unit) {
    val theme = LocalZedTheme.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClickLabel = "Filter problems") { open = true }
                .touchTarget()
                .padding(horizontal = 4.dp),
        ) {
            Text(
                text = filter.label,
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text", MaterialTheme.colorScheme.onSurface),
            )
            // The caret is the affordance, not punctuation: it is what says
            // the word beside it opens a menu, so it is drawn at an icon
            // metric rather than at labelMedium's.
            ChipCaret(modifier = Modifier.padding(start = 2.dp))
        }
        ContextMenu(
            expanded = open,
            onDismiss = { open = false },
            items = ProblemFilter.entries.map { entry ->
                ContextMenuItem(
                    label = entry.label,
                    checked = entry == filter,
                ) { onPick(entry) }
            },
        )
    }
}

/**
 * Open a diagnostic in Code, at its row and column, and leave this route
 * behind.
 *
 * The order matters and is the one thing here that is easy to get wrong:
 * [ShellState.pop] pops the *current* destination's stack, so the pop has to
 * happen while Problems is still the current destination's top. Switching to
 * Code first would pop whatever Code had pushed instead and leave Problems on
 * the stack it came from.
 */
private fun openProblem(
    state: ShellState,
    projectRoot: String,
    path: String,
    diagnostic: Diagnostic,
) {
    state.pop()
    CodeJump.to(
        state,
        absoluteIn(projectRoot, path),
        // 1-based, as the compiler, the terminal and every LSP client spell a
        // position; the engine's rows and columns are 0-based.
        diagnostic.row + 1,
        diagnostic.colUtf16 + 1,
    )
}

/**
 * A diagnostic's path as the file system spells it.
 *
 * Rows are project-relative, except for the ones that name a file outside the
 * project — a crate in the registry, a header in `/usr/include` — which the
 * engine and [BuildDiagnostics.normalizePath] both leave absolute. Both come
 * through here and only the first gets a root.
 */
internal fun absoluteIn(projectRoot: String, path: String): String =
    if (path.startsWith('/')) path else File(projectRoot, path).path

/** The three answers the header's `all ▾` offers. */
enum class ProblemFilter(val label: String) {
    All("all"),
    Errors("errors"),
    Warnings("warnings"),
    ;

    /** Whether a row of this severity is listed under this filter. */
    fun keeps(severity: DiagnosticSeverity): Boolean = when (this) {
        All -> true
        Errors -> severity == DiagnosticSeverity.Error
        // Warnings *and* the two below them: the question the filter asks is
        // "what is not an error", and infos and hints have nowhere else to be
        // listed. An empty screen under a filter with rows behind it would be
        // a list that lies.
        Warnings -> severity != DiagnosticSeverity.Error
    }
}

/**
 * [rows] with everything [filter] excludes taken out, files that end up empty
 * dropped with it.
 *
 * A file header whose every row was filtered away is a header for nothing, and
 * the pane's own flattening ([to.eyed.seeker.code.ui.diagnostics.diagnosticsRows])
 * makes the same choice for the same reason. The version is carried through
 * untouched so a consumer keyed on it still recomputes when either producer
 * moves.
 */
internal fun filterProblems(
    rows: ProjectDiagnosticRows,
    filter: ProblemFilter,
): ProjectDiagnosticRows {
    if (filter == ProblemFilter.All) return rows
    val files = rows.files.mapNotNull { file ->
        val kept = file.rows.filter { filter.keeps(it.severity) }
        if (kept.isEmpty()) null else FileDiagnosticRows(file.path, kept)
    }
    return ProjectDiagnosticRows(version = rows.version, files = files)
}

/** How many rows of one severity are listed — what the header's `✕1 !3` counts. */
internal fun countBy(rows: ProjectDiagnosticRows, severity: DiagnosticSeverity): Int =
    rows.files.sumOf { file -> file.rows.count { it.severity == severity } }

/**
 * The filtered list as the sentence `[ Fix with agent ]` seeds the composer
 * with.
 *
 * Errors first and then everything else, because a warning is rarely why the
 * build failed, and capped: an agent handed ninety rows spends its context on
 * the list rather than on the fix, and the eightieth `unused import` is not
 * what the user meant. Each row is `path:line:col`, the severity, the code and
 * the message as the compiler wrote it — the same shape
 * [to.eyed.seeker.code.solana.build.BuildDiagnostics.agentPrompt] uses for a
 * failed run, minus rustc's rendered snippet, which the diagnostics store does
 * not carry.
 *
 * Pure, and the reason it is: the wording is the product, and it is checkable
 * on the host (ProblemsPromptTest).
 */
internal fun problemsPrompt(rows: ProjectDiagnosticRows, limit: Int = 12): String {
    val all = rows.files.flatMap { file -> file.rows.map { file.path to it } }
    val errors = all.filter { it.second.severity == DiagnosticSeverity.Error }
    val ordered = errors + all.filterNot { it.second.severity == DiagnosticSeverity.Error }
    val chosen = ordered.take(limit)
    if (chosen.isEmpty()) return "There are no problems in this project."
    return buildString {
        append("There ")
        append(if (all.size == 1) "is 1 problem" else "are ${all.size} problems")
        append(" in this project. Please fix ")
        append(if (chosen.size == 1) "it" else "them")
        append(".\n\n")
        for ((path, diagnostic) in chosen) {
            append(path)
            append(':')
            append(diagnostic.row + 1)
            append(':')
            append(diagnostic.colUtf16 + 1)
            append(' ')
            append(diagnostic.severity.token)
            diagnostic.code?.let { append('[').append(it).append(']') }
            append(": ")
            append(diagnostic.message.trim())
            // The producer, because "cargo · anchor build" and
            // "rust-analyzer" disagreeing about the same line is a fact the
            // agent needs and cannot recover from the message.
            diagnostic.source?.let { append("\n  — ").append(it) }
            append('\n')
        }
        if (all.size > chosen.size) {
            append('\n')
            append(all.size - chosen.size)
            append(" more are not listed here.\n")
        }
    }
}
