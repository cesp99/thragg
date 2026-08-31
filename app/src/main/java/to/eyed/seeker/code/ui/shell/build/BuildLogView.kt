package to.eyed.seeker.code.ui.shell.build

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.solana.build.BuildDiagnostics
import to.eyed.seeker.code.solana.build.BuildIssue
import to.eyed.seeker.code.solana.build.BuildLog
import to.eyed.seeker.code.solana.build.BuildLogRow
import to.eyed.seeker.code.ui.editor.DiagnosticSeverity
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.touchTarget
import to.eyed.seeker.code.ui.workspace.ContextMenu
import to.eyed.seeker.code.ui.workspace.ContextMenuItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The streamed build log: what ran, what it printed, what went wrong, and how
 * long it took.
 *
 * Three properties, each of which is a decision rather than a default:
 *
 *  1. **Error rows are the point.** A `✕` row shows the message wrapped over
 *     as many lines as it needs — an E0609 clipped at 40 columns tells you
 *     nothing — with its code and a `path:line:col →` under it, and the whole
 *     row is a 48dp target that opens Code at the caret. That is the two-tap
 *     loop the whole destination exists for: build, tap the error, fix it.
 *  2. **Nothing is ever dropped.** A line the parser did not recognise is
 *     still a row, still monospaced, still copyable and still routable to the
 *     agent. The parser is allowed to be imperfect; the log is not allowed to
 *     lose anything (see CargoDiagnostics).
 *  3. **It follows the tail, until you scroll.** A build prints for a minute
 *     and you watch the end of it; the moment you scroll back to read
 *     something, it stops yanking you away. Re-tapping ▶ on the bar brings
 *     you back to the end (docs/UI.md, "Navigation": re-tapping the current
 *     destination scrolls it to the end of the log).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BuildLogView(
    state: ShellState,
    log: BuildLog,
    projectRoot: String?,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val listState = rememberLazyListState()
    val rows = log.rows

    /**
     * Whether the tail is on screen. Derived rather than remembered: a
     * `LaunchedEffect` that scrolled unconditionally would fight the user's
     * own scroll on every one of the hundreds of lines a build prints.
     */
    val atEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf true
            last >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(rows.size) {
        if (atEnd && rows.isNotEmpty()) listState.scrollToItem(rows.lastIndex)
    }
    LaunchedEffect(state.retapCount) {
        if (rows.isNotEmpty()) listState.scrollToItem(rows.lastIndex)
    }

    if (rows.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No build yet.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        if (log.dropped > 0) {
            item(key = "dropped") {
                LogText(
                    text = "… ${log.dropped} earlier lines dropped",
                    muted = true,
                )
            }
        }
        items(rows.size) { index ->
            when (val row = rows[index]) {
                is BuildLogRow.Command -> CommandRow(row)
                is BuildLogRow.Note -> NoteRow(row.text)
                is BuildLogRow.Text -> SelectableLine(state, row.text) {
                    LogText(text = row.text, muted = true)
                }
                is BuildLogRow.Issue -> IssueRow(state, row.issue, projectRoot)
                is BuildLogRow.Summary -> SummaryRow(row)
            }
        }
    }
}

/** `14:22  anchor build` — the head of a run. */
@Composable
private fun CommandRow(row: BuildLogRow.Command) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = CLOCK.format(Date(row.at)),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Text(
            text = row.text,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = theme.color("text", MaterialTheme.colorScheme.onSurface),
        )
    }
}

/** Something the app is saying, distinguished from the compiler's own words. */
@Composable
private fun NoteRow(text: String) {
    val theme = LocalZedTheme.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
    )
}

@Composable
private fun LogText(text: String, muted: Boolean) {
    val theme = LocalZedTheme.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = if (muted) {
            theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            theme.color("text", MaterialTheme.colorScheme.onSurface)
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp),
    )
}

/**
 * `── failed · 1 error, 1 warning · 1m11s ─` — one per run, and the only row
 * that is ever coloured by outcome rather than by severity.
 */
@Composable
private fun SummaryRow(row: BuildLogRow.Summary) {
    val theme = LocalZedTheme.current
    Text(
        text = "── ${row.text} " + "─".repeat(4),
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Start,
        color = if (row.failed) {
            theme.color("error", MaterialTheme.colorScheme.error)
        } else {
            theme.color("created", MaterialTheme.colorScheme.primary)
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * One problem: severity glyph, wrapped message, code, and the location as its
 * own tappable line with a `→`.
 *
 * The whole block is the target rather than just the location line — a 40dp
 * strip of text under a two-line message is not a thing a thumb hits, and the
 * only reason to look at an error row is to go to it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IssueRow(state: ShellState, issue: BuildIssue, projectRoot: String?) {
    val theme = LocalZedTheme.current
    var menuOpen by remember { mutableStateOf(false) }
    val isError = issue.severity == DiagnosticSeverity.Error
    val tint = if (isError) {
        theme.color("error", MaterialTheme.colorScheme.error)
    } else {
        theme.color("warning", MaterialTheme.colorScheme.tertiary)
    }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { openIssue(state, issue, projectRoot) },
                    onLongClick = { menuOpen = true },
                )
                .touchTarget()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (isError) "✕" else "!",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = tint,
                modifier = Modifier.width(12.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // Wrapped, never truncated: docs/UI.md is explicit that a
                    // clipped diagnostic tells you nothing.
                    text = issue.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                )
                val location = issue.location
                if (location != null || issue.code != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (location != null) {
                            Text(
                                text = location,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = theme.color(
                                    "text.accent",
                                    MaterialTheme.colorScheme.primary,
                                ),
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Text(
                                text = "→",
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.color(
                                    "text.accent",
                                    MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                        issue.code?.let { code ->
                            Text(
                                text = code,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = theme.color(
                                    "text.muted",
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            }
        }
        RowMenu(
            state = state,
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            text = issue.rendered ?: issue.message,
        )
    }
}

/**
 * Any log line, long-pressable. The two answers to "what is this line" that
 * do not need the parser to have understood it: put it on the clipboard, or
 * hand it to the agent.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectableLine(state: ShellState, text: String, content: @Composable () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier.combinedClickable(
                onClick = {},
                onLongClick = { menuOpen = true },
            )
        ) {
            content()
        }
        RowMenu(state, menuOpen, { menuOpen = false }, text)
    }
}

@Composable
private fun RowMenu(
    state: ShellState,
    expanded: Boolean,
    onDismiss: () -> Unit,
    text: String,
) {
    val context = LocalContext.current
    ContextMenu(
        expanded = expanded,
        onDismiss = onDismiss,
        items = listOf(
            ContextMenuItem("Copy") { copyToClipboard(context, text) },
            ContextMenuItem("Ask the agent about this") {
                askAgent(state, context, "What does this mean, and how do I fix it?\n\n```\n$text\n```")
            },
        ),
    )
}

/**
 * Open the file an issue names, at its line and column.
 *
 * The path is whatever the compiler printed — relative to the project root,
 * usually — so it is resolved against the root here rather than being handed
 * to Code as-is; a guest absolute path is folded back to the host by
 * [BuildDiagnostics.normalizePath], which is the same reduction the Problems
 * list keys its rows on.
 */
private fun openIssue(state: ShellState, issue: BuildIssue, projectRoot: String?) {
    val path = issue.path ?: return
    val root = projectRoot ?: return
    val relative = BuildDiagnostics.normalizePath(root, path)
    val absolute = if (relative.startsWith("/")) relative else File(root, relative).path
    CodeJump.to(state, absolute, issue.line, issue.column)
}

/** `14:22`. Device locale, because it is a wall clock and not a duration. */
private val CLOCK = SimpleDateFormat("HH:mm", Locale.getDefault())
