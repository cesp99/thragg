package to.eyed.seeker.code.ui.shell.build

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.solana.build.BuildDiagnostics
import to.eyed.seeker.code.solana.build.BuildIssue
import to.eyed.seeker.code.solana.build.BuildLog
import to.eyed.seeker.code.solana.build.BuildLogRow
import to.eyed.seeker.code.ui.components.EmptyState
import to.eyed.seeker.code.ui.editor.DiagnosticSeverity
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.MonoBody
import to.eyed.seeker.code.ui.theme.MonoSmall
import to.eyed.seeker.code.ui.theme.touchTarget
import to.eyed.seeker.code.ui.workspace.ContextMenu
import to.eyed.seeker.code.ui.workspace.ContextMenuItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The streamed build log: what ran, what it printed, what went wrong, and how
 * long it took — drawn as a **Zed island** inside a Material screen.
 *
 * THE ISLAND IS THE POINT OF THE REDESIGN HERE. Compiler output in the user's
 * buffer face, on the editor's ground, in the theme's own terminal colours,
 * behind a 1dp `outlineVariant` hairline at [MD.radiusSm] that belongs to the
 * *sheet* rather than to the editor — the seam rule, and the border is exactly
 * what stops a dark block on a dark screen reading as a hole punched through
 * it (docs/VISUAL.md, "The hybrid" → THE SEAM). Six sites in this file used to
 * draw with `FontFamily.Monospace`, the SYSTEM mono rather than the buffer
 * face, over Material ink: the same file looked like two different files two
 * taps apart. They are gone; [MonoBody] and [MonoSmall] are the buffer's face
 * with its feature settings, and every colour below is a raw `theme.color(...)`
 * read because inks on the Zed side of the seam are drawn raw.
 *
 * WHY THIS IS NOT LITERALLY A `ZedCodeBlock`. It is that component's metrics,
 * ground, ink and border, reproduced around a `LazyColumn` — because the log
 * caps at `BuildLog.MAX_ROWS` = 8 000 rows and `ZedCodeBlock` renders one
 * `Text`, so a cold Anchor build would lay out a couple of megabytes of string
 * on every flush. Virtualisation is the only thing that differs, and where a
 * payload is bounded — a rendered `rustc` diagnostic — the island reproduces
 * the component's horizontal scroll per row as well.
 *
 * Three properties survive from the first version, each a decision rather than
 * a default:
 *
 *  1. **Nothing is ever dropped.** A line the parser did not recognise is
 *     still a row, still in the buffer face, still copyable and still routable
 *     to the agent. The parser is allowed to be imperfect; the log is not
 *     allowed to lose anything (see CargoDiagnostics).
 *  2. **It follows the tail, until you scroll.** A build prints for a minute
 *     and you watch the end of it; the moment you scroll back to read
 *     something, it stops yanking you away. Re-tapping ▶ on the bar brings you
 *     back to the end (docs/UI.md, "Navigation").
 *  3. **An error row goes somewhere.** Tapping one opens Code at the caret.
 *     The *wrapped, unclipped* presentation of a diagnostic has moved up to
 *     the [BuildIssueCard]s above the log, which is where the wireframe puts
 *     it; down here a problem is shown as `rustc` rendered it — with the
 *     colour, the arrow and the carets, which is a shape you read rather than
 *     a sentence you parse.
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
        // No island: an empty bordered box is a frame around nothing, and the
        // way out of this state is the run control in the app bar rather than
        // anything that could be an [EmptyState] action.
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                headline = "Nothing built yet",
                body = "Press Run and the compiler's output arrives here, " +
                    "line by line, with every problem tappable.",
            )
        }
        return
    }

    // The island's ink, read once: every row below is a raw Zed colour.
    val ink = theme.color("editor.foreground")
    val shape = RoundedCornerShape(MD.radiusSm)

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(theme.color("editor.background"))
            .border(MD.hairline, MaterialTheme.colorScheme.outlineVariant, shape),
        contentPadding = PaddingValues(vertical = MD.space2),
    ) {
        if (log.dropped > 0) {
            item(key = "dropped") {
                LogLine(
                    text = "… ${log.dropped} earlier lines dropped",
                    color = ink.copy(alpha = MUTED),
                )
            }
        }
        items(rows.size) { index ->
            when (val row = rows[index]) {
                is BuildLogRow.Command -> CommandRow(row, ink)
                is BuildLogRow.Note -> NoteRow(row.text)
                is BuildLogRow.Text -> SelectableLine(state, row.text) {
                    LogLine(text = row.text, color = ink.copy(alpha = 0.85f))
                }

                is BuildLogRow.Issue -> IssueRow(state, row.issue, projectRoot, ink)
                is BuildLogRow.Summary -> SummaryRow(row)
            }
        }
    }
}

/**
 * `14:22  anchor build` — the head of a run.
 *
 * The one row drawn at [MonoBody] rather than [MonoSmall]: it is the sentence
 * the rows under it are the answer to, and a build log with no visible run
 * boundaries is one long undifferentiated column.
 */
@Composable
private fun CommandRow(row: BuildLogRow.Command, ink: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MD.space2, vertical = MD.space1),
        horizontalArrangement = Arrangement.spacedBy(MD.space2),
    ) {
        Text(
            text = CLOCK.format(Date(row.at)),
            style = MonoSmall.copy(color = ink.copy(alpha = MUTED)),
        )
        Text(
            text = row.text,
            style = MonoBody.copy(color = ink, fontWeight = FontWeight.Medium),
        )
    }
}

/** Something the app is saying, distinguished from the compiler's own words. */
@Composable
private fun NoteRow(text: String) {
    val theme = LocalZedTheme.current
    Text(
        text = text,
        // Raw `text.accent`, like everything else on this side of the seam.
        style = MonoSmall.copy(color = theme.color("text.accent")),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MD.space2, vertical = 2.dp),
    )
}

/**
 * One line of output.
 *
 * Soft-wrapped, deliberately, and the opposite of what [IssueRow] does with a
 * rendered diagnostic: an ordinary log line is prose that `cargo` has already
 * broken where it wanted to, so wrapping the overflow loses nothing and a
 * horizontal scroll would cost a gesture on every line. A rendered diagnostic
 * is an *aligned block* whose carets stop meaning anything the moment it
 * re-wraps, so that one scrolls instead.
 */
@Composable
private fun LogLine(text: String, color: Color) {
    Text(
        text = text,
        style = MonoSmall.copy(color = color),
        modifier = Modifier.fillMaxWidth().padding(horizontal = MD.space2, vertical = 1.dp),
    )
}

/**
 * `failed · 1 error, 1 warning · 1m11s` — one per run, and the only row that
 * is ever coloured by outcome rather than by severity.
 *
 * The rule of dashes it used to be drawn between is gone: `──` in a `Text` is
 * a glyph doing an icon's job, it renders at the font's weight rather than a
 * stroke weight, and it draws tofu on a face that lacks U+2500
 * (NoEmojiInUiTest). The row is now separated by *space* — [MD.space2] above
 * and below, twice any other row's — which is what the dashes were for.
 */
@Composable
private fun SummaryRow(row: BuildLogRow.Summary) {
    val theme = LocalZedTheme.current
    Text(
        text = row.text,
        style = MonoBody.copy(
            color = if (row.failed) theme.color("error") else theme.color("created"),
            fontWeight = FontWeight.Medium,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MD.space2, vertical = MD.space2),
    )
}

/**
 * One problem, as `rustc` rendered it: colour, the arrow, the carets under the
 * span — and the whole block a target that opens Code at the caret.
 *
 * The rendered block is what the `--message-format=json-diagnostic-rendered-ansi`
 * flag in `BuildTasks` exists to produce, and until now the log threw it away
 * and re-typeset the message itself. It is drawn through [ansiAnnotate] with
 * the live theme's `terminal.ansi.*` keys, so an error is red in whatever red
 * the user's terminal is — not One Dark's, which is the bug `OrchBits.kt:150`
 * shipped by baking a fallback table.
 *
 * NO SOFT WRAP, and its own horizontal scroll: see [LogLine]. The payload is
 * bounded — a rendered diagnostic is a handful of lines — so a `ScrollState`
 * per row costs nothing, and a shared one across the whole log would snap back
 * every time a shorter line scrolled into view.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IssueRow(state: ShellState, issue: BuildIssue, projectRoot: String?, ink: Color) {
    val theme = LocalZedTheme.current
    var menuOpen by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val isError = issue.severity == DiagnosticSeverity.Error
    val rendered = issue.rendered
    val body = remember(rendered, ink, theme) {
        if (rendered != null) {
            ansiAnnotate(rendered.trimEnd(), ink) { name ->
                theme.color("terminal.ansi.$name", ink)
            }
        } else {
            null
        }
    }
    val label = issue.location?.let { "Open $it" } ?: issue.message

    Box {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = label,
                    onClick = { openIssue(state, issue, projectRoot) },
                    onLongClick = { menuOpen = true },
                )
                .touchTarget()
                .padding(horizontal = MD.space2, vertical = MD.space1),
        ) {
            if (body != null) {
                Text(
                    text = body,
                    style = MonoSmall.copy(color = ink),
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(scroll),
                )
            } else {
                // No rendered block — a linker error, `seahorse`, an IDL step.
                // Then the message IS the row, and it wraps, because there is
                // no alignment left to preserve.
                Column {
                    Text(
                        text = "${issue.severity.token}: ${issue.message}",
                        style = MonoSmall.copy(
                            color = if (isError) {
                                theme.color("error")
                            } else {
                                theme.color("warning")
                            },
                        ),
                    )
                    issue.location?.let { location ->
                        Text(
                            text = "  at $location",
                            style = MonoSmall.copy(color = ink.copy(alpha = MUTED)),
                        )
                    }
                }
            }
        }
        RowMenu(
            state = state,
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            text = stripAnsi(issue.rendered ?: issue.message),
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
        RowMenu(state, menuOpen, { menuOpen = false }, stripAnsi(text))
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
internal fun openIssue(state: ShellState, issue: BuildIssue, projectRoot: String?) {
    val path = issue.path ?: return
    val root = projectRoot ?: return
    val relative = BuildDiagnostics.normalizePath(root, path)
    val absolute = if (relative.startsWith("/")) relative else File(root, relative).path
    CodeJump.to(state, absolute, issue.line, issue.column)
}

/** `14:22`. Device locale, because it is a wall clock and not a duration. */
private val CLOCK = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * 0.6 — the island's own muted ink.
 *
 * An alpha over `editor.foreground` rather than a `text.muted` read, matching
 * `ZedCodeBlock`'s header: `text.muted` is solved against the *panel*, not
 * against the editor's ground, and on Ayu Light it measures 2.79:1 there.
 */
private const val MUTED = 0.6f
