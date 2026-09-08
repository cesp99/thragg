package to.eyed.thragg.ui.shell.build

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import to.eyed.thragg.solana.build.BuildDiagnostics
import to.eyed.thragg.solana.build.BuildIssue
import to.eyed.thragg.solana.build.BuildLog
import to.eyed.thragg.solana.build.BuildLogRow
import to.eyed.thragg.ui.components.EmptyState
import to.eyed.thragg.ui.editor.DiagnosticSeverity
import to.eyed.thragg.ui.shell.Route
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.theme.LocalZedTheme
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.MonoBody
import to.eyed.thragg.ui.theme.MonoSmall
import to.eyed.thragg.ui.theme.longPressDoor
import to.eyed.thragg.ui.theme.revealItem
import to.eyed.thragg.ui.theme.touchTarget
import to.eyed.thragg.ui.workspace.ContextMenu
import to.eyed.thragg.ui.workspace.ContextMenuItem
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
 *     something, it stops yanking you away, and scrolling back down to the
 *     end picks it up again. Re-tapping ▶ on the bar brings you back to the
 *     end (docs/UI.md, "Navigation"). Whether to follow is the user's
 *     *intent* ([TailFollow]), changed only by a user scroll — never derived
 *     from the layout at the moment rows arrive, which is what silently
 *     stopped the log 8 s into a 6-minute `anchor test` on the Seeker
 *     2026-09-08 (see that class).
 *  3. **An error row goes somewhere.** Tapping one opens Code at the caret.
 *     The *wrapped, unclipped* presentation of a diagnostic has moved up to
 *     the [BuildIssueCard]s above the log, which is where the wireframe puts
 *     it; down here a problem is shown as `rustc` rendered it — with the
 *     colour, the arrow and the carets, which is a shape you read rather than
 *     a sentence you parse.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BuildLogView(
    state: ShellState,
    log: BuildLog,
    projectRoot: String?,
    modifier: Modifier = Modifier,
    /**
     * Why Run is greyed, or null when it is armed.
     *
     * The empty state is the only thing on this screen when nothing has been
     * built, so it is the only thing that can tell the user what to do — and
     * it told them to press a control that was disabled, on a device with no
     * toolchain, which is every first run. [BuildScreen] already computes the
     * answer for the run button and the overflow; handing it here is what
     * stops the two disagreeing.
     */
    unavailable: Unavailable? = null,
    /**
     * Whether `target/deploy` already holds an artifact. The log lives and
     * dies with the process; the artifact survives it, so a fresh launch can
     * be "Built" in the strip and empty here — and the empty state must say
     * "no output this session", not deny a build the strip is reporting.
     */
    artifactOnDisk: Boolean = false,
) {
    val theme = LocalZedTheme.current
    val listState = rememberLazyListState()
    val rows = log.rows
    val follow = remember { TailFollow() }

    /**
     * The user's scrolls, and only theirs. A nested-scroll connection above
     * the list's own `scrollable` sees every drag, fling and wheel delta the
     * list consumed — and nothing [LazyListState.scrollToItem] does, which
     * bypasses nested scrolling — so this is where intent is read. The delta
     * is in pointer space: a positive `y` is the finger moving down, the
     * content moving toward its start. `layoutInfo` is already the layout
     * after the delta here (`LazyListState.onScroll` remeasures
     * synchronously), so "is the end still on screen" is exact.
     */
    val userScroll = remember(listState, follow) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Nothing moved — a drag past the top or bottom edge — so
                // nothing about where the end is has changed.
                if (consumed.y == 0f) return Offset.Zero
                follow.onUserScroll(
                    towardStart = consumed.y > 0f,
                    endInView = listState.layoutInfo.endInView(),
                )
                return Offset.Zero
            }
        }
    }

    /**
     * The island's height, and only that: the "Deployed on devnet" card and
     * the Problems chip above it appear mid-run and shrink it, pushing the
     * end below the fold with no rows added. Derived, because `layoutInfo`
     * itself changes on every measure and this must recompose only when the
     * number does.
     */
    val viewportHeight by remember(listState) {
        derivedStateOf { listState.layoutInfo.viewportSize.height }
    }

    // Catch up whenever anything that can move the end has moved, while the
    // user wants the end: more rows, a different last row (a Progress redraw
    // replaces it without changing the count, and a longer text wraps to
    // more pixels), a shorter island — and the end of a user gesture, because
    // a `scrollToItem` under a finger is cancelled by the drag's priority and
    // the rows that arrived meanwhile would otherwise wait for the next one.
    val scrolling = listState.isScrollInProgress
    // The list index of the newest row: the "dropped" banner, when there is
    // one, sits in front of the rows and shifts every index by one.
    val lastItem = rows.lastIndex + if (log.dropped > 0) 1 else 0
    LaunchedEffect(rows.size, rows.lastOrNull(), viewportHeight, scrolling) {
        if (follow.following && !scrolling && rows.isNotEmpty()) {
            listState.scrollToTail(lastItem)
        }
    }
    // The bar's re-tap: back to the tail, gliding, because the user asked
    // for it — arrival (the first run of this effect, whatever the count is
    // when the log is composed) still snaps, as everything arriving does.
    // The glide lands on the newest row; the tail alignment after it is
    // the same instant one the follow uses, and is a few pixels at most.
    val arrivedAt = remember { state.retapCount }
    LaunchedEffect(state.retapCount) {
        follow.rejoin()
        if (rows.isEmpty()) return@LaunchedEffect
        if (state.retapCount != arrivedAt) listState.revealItem(lastItem)
        listState.scrollToTail(lastItem)
    }
    // A new run empties the log (BuildRunner.start clears it): a scroll-up
    // from the last run's failure must not leave the next run unfollowed —
    // that was the sticky-false the intent model exists to remove, back
    // through another door. The empty log is the run boundary.
    LaunchedEffect(rows.isEmpty()) {
        if (rows.isEmpty()) follow.rejoin()
    }

    if (rows.isEmpty()) {
        // No island: an empty bordered box is a frame around nothing. When Run
        // is armed the way out of this state is the run control in the app bar
        // and there is nothing to put in the action slot; when it is NOT armed
        // the way out is Setup, and this is the only place on the screen with
        // room to offer it.
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (unavailable == null) {
                if (artifactOnDisk) {
                    EmptyState(
                        headline = "No output this session",
                        body = "The last build's artifact is on disk — the strip above " +
                            "has it. Press Run to build again and the compiler's " +
                            "output streams here.",
                    )
                } else {
                    EmptyState(
                        headline = "Nothing built yet",
                        body = "Press Run and the compiler's output arrives here, " +
                            "line by line, with every problem tappable.",
                    )
                }
            } else {
                EmptyState(
                    headline = unavailable.headline,
                    body = unavailable.message,
                    action = if (unavailable.setup) {
                        {
                            Button(
                                onClick = { state.push(Route.Setup) },
                                // Zero in all five slots: depth here is a fill
                                // step and a hairline, never a shadow
                                // (docs/VISUAL.md, ELEVATION).
                                elevation = ButtonDefaults.buttonElevation(
                                    0.dp, 0.dp, 0.dp, 0.dp, 0.dp,
                                ),
                            ) {
                                Text(
                                    text = "Set up the toolchain",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    } else {
                        // No userland, or no Solana crate in this folder:
                        // there is no button that would help, and inventing
                        // one is the defect this branch exists to avoid.
                        null
                    },
                )
            }
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
            .border(MD.hairline, MaterialTheme.colorScheme.outlineVariant, shape)
            .nestedScroll(userScroll),
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

                // The line being redrawn in place — one row that keeps
                // changing, not a row per frame (BuildLog.progress). Muted:
                // it is a spinner or a counter, not something to read.
                is BuildLogRow.Progress -> SelectableLine(state, row.text) {
                    LogLine(text = row.text, color = ink.copy(alpha = 0.6f))
                }

                is BuildLogRow.Issue -> IssueRow(state, row.issue, projectRoot, ink)
                is BuildLogRow.Summary -> SummaryRow(row)
            }
        }
    }
}

/**
 * Bring the newest row's *bottom* to the bottom of the island.
 *
 * `scrollToItem(lastIndex)` alone puts that row's top at the top of the
 * viewport and lets the measure pull the content down to fill the rest, which
 * is the end of the log for any row shorter than the island — and the *head*
 * of the row for one taller than it (a wrapped yarn error, a rendered
 * diagnostic with a long note), leaving its tail below the fold. So: snap,
 * read what hangs over, and scroll by that. The second step is clamped by the
 * list itself, which never scrolls past its content.
 */
private suspend fun LazyListState.scrollToTail(lastIndex: Int) {
    scrollToItem(lastIndex)
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return
    val overflow = TailFollow.overflow(
        lastVisibleIndex = last.index,
        totalItems = info.totalItemsCount,
        lastVisibleBottom = last.offset + last.size,
        contentEnd = info.viewportEndOffset - info.afterContentPadding,
    )
    if (overflow > 0) scrollBy(overflow.toFloat())
}

/**
 * Is the newest row on screen? `viewportEndOffset` includes the
 * after-padding, so a nudge shorter than [MD.space2] does not count as
 * leaving the end.
 */
private fun LazyListLayoutInfo.endInView(): Boolean {
    val last = visibleItemsInfo.lastOrNull()
    return TailFollow.endInView(
        lastVisibleIndex = last?.index ?: -1,
        totalItems = totalItemsCount,
        lastVisibleBottom = last?.let { it.offset + it.size } ?: 0,
        viewportEnd = viewportEndOffset,
    )
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
    // Through the same ANSI pass a rendered diagnostic gets: `anchor build`
    // and Seahorse print bold and colour into an ordinary line too, and an
    // escape drawn as text is a `[1m` in front of every crate name.
    val theme = LocalZedTheme.current
    val body = remember(text, color, theme) {
        ansiAnnotate(text, color) { name -> theme.color("terminal.ansi.$name", color) }
    }
    Text(
        text = body,
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
    // Its own combinedClickable rather than [longPressDoor], for the
    // click label a screen reader speaks; the door's haptic is the same.
    val haptics = LocalHapticFeedback.current

    Box {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = label,
                    onClick = { openIssue(state, issue, projectRoot) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
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
            modifier = Modifier.longPressDoor(onLongClick = { menuOpen = true })
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
