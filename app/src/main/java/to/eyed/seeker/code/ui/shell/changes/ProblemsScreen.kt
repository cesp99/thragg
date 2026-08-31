@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package to.eyed.seeker.code.ui.shell.changes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import java.io.File
import to.eyed.seeker.code.R
import to.eyed.seeker.code.solana.build.BuildDiagnostics
import to.eyed.seeker.code.ui.components.BottomActions
import to.eyed.seeker.code.ui.components.BottomActionsGap
import to.eyed.seeker.code.ui.components.EmptyState
import to.eyed.seeker.code.ui.components.HairlineDivider
import to.eyed.seeker.code.ui.components.SeekerCard
import to.eyed.seeker.code.ui.components.SeekerTopBar
import to.eyed.seeker.code.ui.components.SectionHeader
import to.eyed.seeker.code.ui.components.fadeUnderBottomActions
import to.eyed.seeker.code.ui.editor.Diagnostic
import to.eyed.seeker.code.ui.editor.DiagnosticSeverity
import to.eyed.seeker.code.ui.editor.FileDiagnosticRows
import to.eyed.seeker.code.ui.editor.ProjectDiagnosticRows
import to.eyed.seeker.code.ui.editor.rememberProjectDiagnostics
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.shell.build.CodeJump
import to.eyed.seeker.code.ui.shell.build.askAgent
import to.eyed.seeker.code.ui.theme.IconSize
import to.eyed.seeker.code.ui.theme.LocalSeekerColors
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.MonoSmall
import to.eyed.seeker.code.ui.theme.RowChevron
import to.eyed.seeker.code.ui.theme.SeekerIcon
import to.eyed.seeker.code.ui.theme.TabularNums

/**
 * Problems — every diagnostic in the project, from **both** producers.
 *
 * "Read errors" is a named step in the loop this product is about, and it gets
 * a screen rather than a dock panel (docs/UI.md, "Problems"). What this route
 * adds over the raw diagnostics is the three things a pane cannot know about:
 *
 *  1. **The merge.** The engine's diagnostics store is read-only across JNI,
 *     so a build's diagnostics live beside it in
 *     [BuildDiagnostics] and only a consumer can put the two together
 *     (BuildDiagnostics.kt). This is that consumer: [BuildDiagnostics.merge]
 *     over what the language server published, with every row still carrying
 *     the producer that found it — `cargo · anchor build` or `rust-analyzer`
 *     — under its message, so a build error from four minutes ago is
 *     distinguishable from what rust-analyzer thinks right now.
 *  2. **The filter**, in the header where the wireframe puts it, as three
 *     stock `FilterChip`s that carry their own counts.
 *  3. **The two exits.** A tap on a row opens Code at that row and column and
 *     pops this route, so build → tap → fix → build is two taps back; and
 *     `Fix with agent` sends the whole *filtered* set into the composer.
 *
 * THE LIST IS DRAWN HERE now, rather than by re-hosting `DiagnosticsPane`
 * (docs/VISUAL.md, "Problems"). That pane belongs to the Zed half — it is
 * built out of `theme.color(…)` and Zed's rem metrics, as the editor's own
 * inline diagnostics are — and hosting it under a Material bar put a Zed
 * island in the middle of an app screen, which is the exact seam this pass
 * exists to stop. What it drew is fourteen lines of `SectionHeader` and
 * `SeekerCard` here, against the same [ProjectDiagnosticRows] the pane took;
 * its collapse-a-file gesture is the one thing not carried across, and on a
 * list already cut down by a filter it was buying very little.
 */
@Composable
fun ProblemsScreen(state: ShellState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val project = state.project
    var filter by remember { mutableStateOf(ProblemFilter.All) }

    if (project == null) {
        Column(modifier = modifier.fillMaxSize()) {
            SeekerTopBar(title = "Problems", onBack = { state.pop() })
            HairlineDivider()
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No project is open.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    // The chips count the WHOLE set, not the filtered one: a chip that said
    // "Errors 0" because the warnings filter was on would be a control lying
    // about what pressing it does. The subtitle counts the same set for the
    // same reason.
    val errors = remember(merged) { countBy(merged, DiagnosticSeverity.Error) }
    val warnings = remember(merged) { countBy(merged, DiagnosticSeverity.Warning) }
    val total = remember(merged) { merged.files.sumOf { it.rows.size } }

    Column(modifier = modifier.fillMaxSize()) {
        SeekerTopBar(
            title = "Problems",
            subtitle = problemsSummary(errors, warnings, total),
            onBack = { state.pop() },
        )
        HairlineDivider()
        // The filter row scrolls rather than wrapping: three chips fit at
        // 400dp and a fourth one, or a large font scale, must push sideways
        // instead of doubling the strip's height.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MD.space4, vertical = MD.space2),
            horizontalArrangement = Arrangement.spacedBy(MD.space2),
        ) {
            for (entry in ProblemFilter.entries) {
                ProblemChip(
                    entry = entry,
                    selected = entry == filter,
                    count = when (entry) {
                        ProblemFilter.All -> total
                        ProblemFilter.Errors -> errors
                        ProblemFilter.Warnings -> total - errors
                    },
                    onClick = { filter = entry },
                )
            }
        }
        HairlineDivider()

        if (shown.files.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(
                    headline = if (total == 0) "Nothing is broken" else "Nothing under this filter",
                    body = if (total == 0) {
                        "The language server and the last build both have nothing to say " +
                            "about this project."
                    } else {
                        "There are $total in the project — the filter above is hiding them."
                    },
                )
            }
        } else {
            LazyColumn(
                // Fades into the action bar rather than being cut by it —
                // the sixth and last of the pinned bars to adopt the seam.
                modifier = Modifier.weight(1f).fillMaxWidth().fadeUnderBottomActions(),
                contentPadding = PaddingValues(
                    start = MD.space4,
                    end = MD.space4,
                    top = MD.space2,
                    bottom = BottomActionsGap,
                ),
                verticalArrangement = Arrangement.spacedBy(MD.space2),
            ) {
                for (file in shown.files) {
                    item(key = "h/${file.path}") {
                        SectionHeader(
                            text = file.path,
                            modifier = Modifier.padding(top = MD.space2),
                        )
                    }
                    item(key = "f/${file.path}") {
                        // One card per file, its rows separated by hairlines —
                        // the same shape Changes gives a block, so a list of
                        // things about one file reads as one object in both
                        // places (docs/VISUAL.md, "Problems").
                        SeekerCard {
                            file.rows.forEachIndexed { index, diagnostic ->
                                if (index > 0) HairlineDivider()
                                ProblemRow(diagnostic) {
                                    openProblem(state, project.rootPath, file.path, diagnostic)
                                }
                            }
                        }
                    }
                }
            }
        }

        // The one action, at the bottom, in the thumb's third of the screen —
        // as every consequential action in this app is (docs/UI.md). The bar
        // brings its own hairline and its own inset, so this screen no longer
        // spells either.
        BottomActions {
            Button(
                onClick = { askAgent(state, context, problemsPrompt(shown)) },
                enabled = shown.files.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Fix with agent") }
        }
    }
}

/**
 * One filter, as a stock `FilterChip` carrying its own count.
 *
 * This screen's hand-rolled `all ⌄` chip-plus-menu is what it replaces, and it
 * was the one place in the app that had already tried to be a Material chip
 * and had to draw it itself. Three chips beat a menu here for the same reason
 * a segmented control beats a picker: the whole answer set is three items
 * long, and a menu makes you open it to find that out.
 *
 * The count is part of the label rather than a second `Text` so the chip has
 * one semantics node — "Errors 2" is the sentence, and a screen reader that
 * read the two halves separately would give the number no subject.
 */
@Composable
private fun ProblemChip(
    entry: ProblemFilter,
    selected: Boolean,
    count: Int,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = "${entry.label} $count",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFeatureSettings = TabularNums,
                ),
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.40f),
            selectedBorderWidth = MD.hairline,
        ),
    )
}

/**
 * One diagnostic: its severity mark, the message, and `104:31` at the end.
 *
 * The position is [MonoSmall] and tabular, because it is a coordinate and it
 * columns down the card; the message wraps to two lines rather than
 * ellipsising at one, because a compiler message's *end* is usually the part
 * that names the type. The producer — `rust-analyzer`, `cargo · anchor build`
 * — goes under it, since two sources disagreeing about one line is a fact the
 * reader needs and cannot recover from the message.
 */
@Composable
private fun ProblemRow(diagnostic: Diagnostic, onOpen: () -> Unit) {
    val colours = LocalSeekerColors.current
    val (icon, tint) = when (diagnostic.severity) {
        DiagnosticSeverity.Error -> R.drawable.ic_ui_close to colours.removedInk
        DiagnosticSeverity.Warning -> R.drawable.ic_ui_warning to colours.warnInk
        else -> R.drawable.ic_file_info to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MD.rowMin)
            .clickable(onClick = onOpen)
            .padding(horizontal = MD.space3, vertical = MD.rowPadY),
        horizontalArrangement = Arrangement.spacedBy(MD.space2),
    ) {
        SeekerIcon(
            icon = icon,
            // Decoration: the severity is spoken by the row's own message and
            // the position beside it, and "close icon" is not information.
            contentDescription = null,
            tint = tint,
            size = IconSize.Marker,
            modifier = Modifier.padding(top = MD.space05),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = diagnostic.message.trim(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val source = diagnostic.source
            if (source != null) {
                Text(
                    text = source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(top = MD.space05),
                )
            }
        }
        Text(
            text = "${diagnostic.row + 1}:${diagnostic.colUtf16 + 1}",
            style = MonoSmall.copy(fontFeatureSettings = TabularNums),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(top = MD.space05),
        )
        RowChevron(modifier = Modifier.padding(top = MD.space05))
    }
}

/**
 * `2 errors · 5 warnings` — the bar's subtitle.
 *
 * Pure and internal, because it is a sentence the product prints and every
 * sentence this screen prints is checkable on the host (ProblemsScreenTest).
 * Infos and hints are counted in neither half, so a project with only those
 * says how many rows there are rather than claiming zero of everything.
 */
internal fun problemsSummary(errors: Int, warnings: Int, total: Int): String? = when {
    total == 0 -> null
    errors == 0 && warnings == 0 -> if (total == 1) "1 problem" else "$total problems"
    else -> listOf(
        "$errors " + if (errors == 1) "error" else "errors",
        "$warnings " + if (warnings == 1) "warning" else "warnings",
    ).joinToString(" · ")
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

/** The three answers the filter row offers. */
enum class ProblemFilter(val label: String) {
    All("All"),
    Errors("Errors"),
    Warnings("Warnings"),
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

/** How many rows of one severity are listed — what the bar's subtitle counts. */
internal fun countBy(rows: ProjectDiagnosticRows, severity: DiagnosticSeverity): Int =
    rows.files.sumOf { file -> file.rows.count { it.severity == severity } }

/**
 * The filtered list as the sentence `Fix with agent` seeds the composer with.
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
