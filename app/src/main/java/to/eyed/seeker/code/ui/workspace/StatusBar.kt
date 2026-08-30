package to.eyed.seeker.code.ui.workspace

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.R
import to.eyed.seeker.code.ui.editor.Diagnostic
import to.eyed.seeker.code.ui.editor.DiagnosticSummary
import to.eyed.seeker.code.ui.editor.LspServer
import to.eyed.seeker.code.ui.editor.LspServerState
import to.eyed.seeker.code.ui.theme.LocalUiFontSize
import to.eyed.seeker.code.ui.theme.LocalReduceMotion
import to.eyed.seeker.code.ui.theme.LocalAppSettings
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.ZedRadius
import to.eyed.seeker.code.ui.theme.glyphHeight
import to.eyed.seeker.code.ui.theme.rem
import to.eyed.seeker.code.ui.theme.touchTarget
import to.eyed.seeker.code.ui.theme.remsAt

/** One panel's button: which panel, whether its dock is showing it, and the tap. */
data class PanelButton(
    val panel: WorkspacePanel,
    val isOpen: Boolean,
    val onClick: () -> Unit,
)

/**
 * The status bar's metrics as rem multiples — `ui_font_size` is the rem
 * (theme_settings/src/settings.rs:619) — held as bare numbers so the table is
 * checkable on the host (`ChromeMetricsTest`).
 */
internal object StatusBarMetrics {

    /**
     * Every item is Zed's IconButton at `ButtonSize::Default`:
     * `rems_from_px(22)` (button_like.rs:465-473) with `rounded_sm` corners
     * (button_like.rs:527) around an `IconSize::Small` glyph, itself
     * `rems_from_px(14)` (icon.rs:74; status_bar.rs:187; dock.rs:1398-1400).
     */
    const val ITEM_BOX = 1.375f
    const val ITEM_ICON = 0.875f

    /** The bar's own `p(DynamicSpacing::Base04.rems(cx))` (status_bar.rs:153). */
    const val BAR_PADDING = 0.25f

    /** `gap_1` within a group (status_bar.rs:196, 215). */
    const val ITEM_GAP = 0.25f

    /**
     * `Divider::vertical()` between the middle and a dock's button group:
     * `h_4` = `rems(1)` tall (divider.rs:29, 147-149; dock.rs:1433-1446). Its
     * 1px width is `px(1.)` and stays in [StatusBarPixels].
     */
    const val DIVIDER_HEIGHT = 1f

    /** `Indicator::dot()` at the size the LSP note uses (indicator.rs). */
    const val NOTE_DOT = 0.25f

    /**
     * The bar has no declared height in Zed: it is one default button plus the
     * frame's padding top and bottom, which comes to 30px at the default rem
     * (status_bar.rs:153). Written as that sum so it stays true at any font
     * size instead of drifting from the buttons it is supposed to contain.
     */
    fun barHeight(uiFontSize: Float): Dp =
        remsAt(uiFontSize, ITEM_BOX) + remsAt(uiFontSize, BAR_PADDING) * 2f
}

/** The one status-bar dimension Zed writes in pixels: the divider's rule. */
internal object StatusBarPixels {
    val DividerWidth = 1.dp
}

/**
 * The bar and its item boxes, each with the accessibility floor on top of Zed's
 * metric: `max(Zed's number, the label's ink)`. At every ordinary font scale
 * these are exactly [StatusBarMetrics.barHeight] and `rem(1.375f)`. See
 * [glyphHeight].
 */
private val StatusBarHeight: Dp
    @Composable @ReadOnlyComposable get() = maxOf(
        StatusBarMetrics.barHeight(LocalUiFontSize.current),
        glyphHeight(MaterialTheme.typography.labelMedium) +
            rem(StatusBarMetrics.BAR_PADDING) * 2f,
    )

private val ItemBox: Dp
    @Composable @ReadOnlyComposable get() = maxOf(
        rem(StatusBarMetrics.ITEM_BOX),
        glyphHeight(MaterialTheme.typography.labelMedium),
    )

private val ItemIconSize: Dp
    @Composable @ReadOnlyComposable get() = rem(StatusBarMetrics.ITEM_ICON)

private val BarPadding: Dp
    @Composable @ReadOnlyComposable get() = rem(StatusBarMetrics.BAR_PADDING)

private val ItemGap: Dp
    @Composable @ReadOnlyComposable get() = rem(StatusBarMetrics.ITEM_GAP)

/**
 * Zed-style status bar: **state, not actions**.
 *
 * Zed splits these deliberately — the title bar holds commands, the status bar
 * reports where you are and which panels are up. Everything that *does*
 * something to a project or a file lives in the title-bar menu, which also
 * keeps it reachable when the soft keyboard covers the bottom of the screen.
 *
 * The panel buttons follow their docks, exactly as Zed's do: the left dock's
 * buttons at the left end of the bar, the right dock's at the right end, and
 * the bottom dock's — the terminal — at the right after them
 * (`workspace.rs:1757-1759`). Move a panel across in settings and its button
 * moves with it, which is the only arrangement in which the button says where
 * the panel will appear.
 */
@Composable
fun StatusBar(
    cursorRow: Int,
    cursorCol: Int,
    modifier: Modifier = Modifier,
    language: String? = null,
    /**
     * Open the language selector — Zed's `ActiveBufferLanguage` item, whose
     * whole job is to dispatch `language_selector::Toggle`
     * (active_buffer_language.rs:75-84). Null leaves the language as a label.
     */
    onSelectLanguage: (() -> Unit)? = null,
    /**
     * The project's active toolchain, as Zed's `ActiveToolchain` item prints
     * it — the interpreter's own name, "Python (.venv)"; see
     * `statusBarToolchain` for which one that is when several languages have
     * a choice. Null when nothing is chosen, and then the item is absent
     * rather than empty, which is what Zed's item does
     * (`active_toolchain.rs:234-238` renders nothing without one). Tapping it
     * opens the toolchain selector.
     */
    toolchain: String? = null,
    onSelectToolchain: (() -> Unit)? = null,
    /**
     * Open the go-to-line picker — Zed's `CursorPosition` item, which
     * dispatches `go_to_line::Toggle` when clicked
     * (cursor_position.rs:216-222). Null leaves the position as a label.
     */
    onSelectCursorPosition: (() -> Unit)? = null,
    hasFile: Boolean = false,
    /**
     * The active file's line ending and encoding, as the status bar writes
     * them ("CRLF", "UTF-8 BOM"). Null with no text file open — a picture
     * has neither. Tapping either opens its picker, exactly as Zed's
     * `LineEndingIndicator` and `ActiveBufferEncoding` items do.
     */
    lineEnding: String? = null,
    onSelectLineEnding: (() -> Unit)? = null,
    encoding: String? = null,
    onSelectEncoding: (() -> Unit)? = null,
    /**
     * The strokes of a chord typed so far — `Ctrl K` while the keymap waits
     * for the `Ctrl 0` — or null. Zed shows the same in its status bar
     * (vim/src/mode_indicator.rs:55-58); without it a two-stroke binding
     * looks like a key that did nothing.
     */
    pendingKeys: String? = null,
    /** Panels docked left, in the order they appear in the enum. */
    leftPanels: List<PanelButton> = emptyList(),
    rightPanels: List<PanelButton> = emptyList(),
    isTerminalOpen: Boolean = false,
    onToggleTerminal: (() -> Unit)? = null,
    /**
     * The project's diagnostics, from `lspDiagnostics`. Null with no project
     * open, which is when Zed's indicator has nothing to summarise either.
     */
    diagnostics: DiagnosticSummary? = null,
    /** The project's language servers, from `lspServers`. */
    servers: List<LspServer> = emptyList(),
    /** The diagnostic under the caret in the active editor, if any. */
    cursorDiagnostic: Diagnostic? = null,
    /** Go to the next diagnostic in the active editor — Zed's button action. */
    onGoToDiagnostic: (() -> Unit)? = null,
    /**
     * Open the diagnostics panel — what tapping the summary does, exactly as
     * Zed's indicator deploys its project-diagnostics editor (items.rs:53-56).
     */
    onOpenDiagnostics: (() -> Unit)? = null,
    /**
     * Install the server that could not start. Null where there is no
     * userland to install into, which leaves the note as plain text rather
     * than a button that cannot work.
     */
    onInstallServer: ((LspServer) -> Unit)? = null,
    /**
     * Background work — Zed's `activity_indicator` (activity_indicator.rs).
     * Language-server progress is folded in by [activityLine]; everything
     * else — the worktree scan, a project search, a git fetch, a running
     * task — comes from [Activities].
     */
    activities: List<Activity> = emptyList(),
    /**
     * Reveal whatever the running job is happening in. Null leaves the
     * sentence as text rather than a button that goes nowhere.
     */
    onActivity: ((ActivityTarget?) -> Unit)? = null,
    /**
     * The language-server menu's rows — Zed's LspButton popover
     * (language_tools/src/lsp_button.rs: "Restart Server", "Stop Server",
     * "View Logs" per server). Null leaves the servers as text alone.
     */
    onRestartServer: ((LspServer) -> Unit)? = null,
    onStopServer: ((LspServer) -> Unit)? = null,
    onShowServerLogs: ((LspServer) -> Unit)? = null,
    /**
     * Vim's mode, as `-- NORMAL --`, with the pending keys before it — Zed's
     * `ModeIndicator`, a left item beside the language-server button
     * (vim.rs `Vim::init` adds it to the status bar; mode_indicator.rs:143
     * for the dashes). Null with the layer off, when Zed hides it too.
     */
    vimMode: String? = null,
    vimPending: String = "",
    /**
     * A tap on the mode is Escape — the way back to normal mode for a finger
     * on a keyboard that has no Escape key.
     */
    onVimEscape: (() -> Unit)? = null,
) {
    val theme = LocalZedTheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(StatusBarHeight)
            .background(theme.color("status_bar.background"))
            .padding(BarPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ItemGap),
    ) {
        for (button in leftPanels) {
            PanelStatusButton(button)
        }

        if (vimMode != null) {
            // Zed's indicator: the pending keys in medium weight, then the
            // mode as `-- NORMAL --` (mode_indicator.rs:130-172), a `Label`
            // at `LabelSize::Small` in the default text colour.
            if (vimPending.isNotEmpty()) {
                Text(
                    text = vimPending,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            Text(
                text = "-- $vimMode --",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier
                    .semanticsLabel("Vim mode: $vimMode. Tap for Escape")
                    .then(if (onVimEscape != null) Modifier.clickable(onClick = onVimEscape) else Modifier)
                    .padding(horizontal = ItemGap),
            )
        }

        // Zed registers both of these as *left* items, the language-server
        // button first and the diagnostic summary next to it
        // (crates/zed/src/zed.rs:640-641).
        if (servers.isNotEmpty() && (onRestartServer != null || onStopServer != null || onShowServerLogs != null)) {
            LanguageServerMenu(
                servers = servers,
                onRestart = onRestartServer,
                onStop = onStopServer,
                onShowLogs = onShowServerLogs,
            )
        }
        val blocked = servers.firstOrNull { it.note != null }
        if (blocked != null) {
            LanguageServerNote(
                note = blocked.note!!,
                others = servers.count { it.note != null } - 1,
                onClick = onInstallServer
                    ?.takeIf { blocked.installable }
                    ?.let { install -> { install(blocked) } },
            )
        } else {
            // What the workspace is busy with, in the note's slot — never
            // beside the note, because a server that could not start reports
            // no progress and the broken one is the more urgent sentence.
            // This is Zed's activity indicator, which owns that sentence; see
            // [activityLine] for what outranks what.
            val line = activityLine(activities, servers)
            if (line != null) {
                ActivityIndicatorItem(
                    line = line,
                    onClick = onActivity?.let { reveal -> { reveal(line.target) } },
                )
            }
        }
        if (diagnostics != null) {
            DiagnosticIndicator(summary = diagnostics, onClick = onOpenDiagnostics)
        }

        // The message of the diagnostic under the caret, taking whatever room
        // is left before the cursor position — Zed's `Button` at
        // `LabelSize::Small` with `.truncate(true)`, whose click is
        // `go_to_next_diagnostic` (crates/diagnostics/src/items.rs:60-85).
        // A Box rather than a Spacer so the message has somewhere to be
        // clipped: without one it would push the right-hand group off the
        // edge of a phone the first time a server said anything long.
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (cursorDiagnostic != null) {
                CursorDiagnosticMessage(cursorDiagnostic, onGoToDiagnostic)
            }
        }

        if (pendingKeys != null) {
            // Muted, like a server's progress: it is a state, not a result.
            Text(
                text = pendingKeys,
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                maxLines = 1,
                modifier = Modifier.padding(horizontal = ItemGap),
            )
        }

        if (hasFile) {
            // Zed writes the caret as line:column — both it and the language
            // are `Label`s at the default colour, `text`, not muted
            // (cursor_position.rs:210-247) — and both are buttons: the
            // position opens go-to-line and the language opens the selector.
            // Zed's `status_bar` block switches either off
            // (default.json:1904-1913); the commands behind them stay in the
            // palette, so hiding the button hides the *readout*, not the way in.
            val items = LocalAppSettings.current.statusBar
            if (items.cursorPositionButton) StatusTextAction(
                text = "${cursorRow + 1}:${cursorCol + 1}",
                label = "Go to Line/Column",
                onClick = onSelectCursorPosition,
            )
            // Zed registers its right-hand items encoding, language, line
            // ending, cursor (zed.rs:645-650) and draws that list *reversed*
            // (status_bar.rs:218-221), so what one reads left to right is
            // cursor, line ending, language, encoding. Both new items are
            // Zed's `Button`s at `LabelSize::Small` (line_ending_indicator.rs:
            // 40-42; active_buffer_encoding.rs:90-92): ghost buttons whose
            // label is the state.
            if (lineEnding != null) {
                StatusTextAction(
                    text = lineEnding,
                    label = "Select Line Ending",
                    onClick = onSelectLineEnding,
                )
            }
            if (language != null && items.activeLanguageButton) {
                StatusTextAction(
                    text = language,
                    label = "Select Language",
                    onClick = onSelectLanguage,
                )
            }
            if (encoding != null) {
                StatusTextAction(
                    text = encoding,
                    label = "Reopen with Encoding",
                    onClick = onSelectEncoding,
                )
            }
        }

        // Zed's `ActiveToolchain` sits with the other right-hand items and is
        // a project fact, not a file one, so it shows whether or not a text
        // file is open — the tab could be an image and the venv is still the
        // one `python -m pytest` would run in.
        if (toolchain != null) {
            StatusTextAction(
                text = toolchain,
                label = "Select Toolchain",
                onClick = onSelectToolchain,
            )
        }

        // A dock group is fenced with a 1px × 16px divider on the side facing
        // the middle (dock.rs:1433-1446, divider.rs:29, 147-149).
        if (rightPanels.isNotEmpty() || onToggleTerminal != null) {
            GroupDivider(theme.color("border"))
        }
        for (button in rightPanels) {
            PanelStatusButton(button)
        }
        if (onToggleTerminal != null) {
            // The touch twin of Ctrl+`, and the only way to reach a terminal
            // on a device with no keyboard attached. At the right end, where
            // Zed puts its bottom dock's buttons.
            StatusIconAction(
                icon = R.drawable.ic_ui_terminal,
                label = "Toggle the terminal",
                emphasised = isTerminalOpen,
                onClick = onToggleTerminal,
            )
        }
    }
}

/**
 * Zed's LspButton (language_tools/src/lsp_button.rs): an icon whose menu
 * lists every server of the project with its state, and under each the
 * three things one can do to it — restart, stop, read its log. Zed nests
 * them as submenus; a phone gets them flat, one group per server.
 */
@Composable
private fun LanguageServerMenu(
    servers: List<LspServer>,
    onRestart: ((LspServer) -> Unit)?,
    onStop: ((LspServer) -> Unit)?,
    onShowLogs: ((LspServer) -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    val anyBroken = servers.any { it.state == LspServerState.Unavailable && !it.stopped }
    Box {
        StatusIconAction(
            icon = R.drawable.ic_ui_server,
            label = "Language servers",
            emphasised = anyBroken,
            onClick = { open = true },
        )
        val items = buildList {
            for ((index, server) in servers.withIndex()) {
                val state = when {
                    server.stopped -> "stopped"
                    server.state == LspServerState.Running -> server.progress ?: "running"
                    server.state == LspServerState.Starting -> "starting"
                    else -> "not running"
                }
                add(
                    ContextMenuItem(
                        label = server.name,
                        aside = state,
                        enabled = false,
                        separatorAbove = index > 0,
                        onClick = {},
                    )
                )
                if (onRestart != null) {
                    add(ContextMenuItem(label = if (server.stopped) "Start" else "Restart", onClick = { onRestart(server) }))
                }
                if (onStop != null && !server.stopped) {
                    add(ContextMenuItem(label = "Stop", onClick = { onStop(server) }))
                }
                if (onShowLogs != null) {
                    add(ContextMenuItem(label = "Show logs", onClick = { onShowLogs(server) }))
                }
            }
        }
        ContextMenu(expanded = open, onDismiss = { open = false }, items = items)
    }
}

@Composable
private fun PanelStatusButton(button: PanelButton) {
    StatusIconAction(
        icon = button.panel.icon,
        label = if (button.isOpen) "Close the ${button.panel.title}" else button.panel.title,
        emphasised = button.isOpen,
        onClick = button.onClick,
    )
}

@Composable
private fun StatusIconAction(
    icon: Int,
    label: String,
    emphasised: Boolean = false,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(ItemBox)
            .clip(RoundedCornerShape(rem(ZedRadius.SM)))
            // A finger-sized area around Zed's small glyph; the bar's own
            // height caps it, so the bar does not grow.
            .touchTarget()
            // `Subtle`, a ghost button: transparent at rest,
            // `ghost_element.hover` under the pointer, `ghost_element.active`
            // while pressed, swapped instantly — no ripple
            // (button_like.rs:298-303, 324-329).
            .background(
                when {
                    pressed -> theme.color("ghost_element.active", Color.Transparent)
                    hovered -> theme.color("ghost_element.hover", Color.Transparent)
                    else -> Color.Transparent
                }
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = label,
            // An open panel's button is `toggle_state(true)` (dock.rs:1400):
            // the box stays ghost and the glyph swaps to `Color::Selected` =
            // `text.accent` (icon_button.rs:246-248, color.rs:108). At rest
            // the glyph is `Color::Default` = `text` (color.rs:92).
            colorFilter = ColorFilter.tint(
                if (emphasised) {
                    theme.color("text.accent", MaterialTheme.colorScheme.onSurface)
                } else {
                    theme.color("text", MaterialTheme.colorScheme.onSurface)
                }
            ),
            modifier = Modifier.size(ItemIconSize),
        )
    }
}

/**
 * A status item whose label is its state and whose tap opens a picker — the
 * line ending's `LF`, the encoding's `UTF-8`. Zed's `Button` at
 * `LabelSize::Small` in its `Subtle` dress: the same 22px ghost box every
 * icon item here wears, with the text where the glyph would be and 4px of
 * side padding (button_like.rs:464-473, 798-803). Plain text when there is
 * nowhere for a tap to go.
 */
@Composable
private fun StatusTextAction(
    text: String,
    label: String,
    onClick: (() -> Unit)?,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .height(ItemBox)
            .clip(RoundedCornerShape(rem(ZedRadius.SM)))
            .touchTarget()
            // The readout is the name — "3:14" — and the label says what
            // tapping it does, which is a different sentence.
            .semantics { contentDescription = "$label, $text" }
            .background(
                when {
                    onClick == null -> Color.Transparent
                    pressed -> theme.color("ghost_element.active", Color.Transparent)
                    hovered -> theme.color("ghost_element.hover", Color.Transparent)
                    else -> Color.Transparent
                }
            )
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = label,
                            onClick = onClick,
                        )
                }
            )
            .padding(horizontal = ItemGap),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * Zed's diagnostic summary: a check when the project is clean, otherwise an
 * error icon and a count and a warning icon and a count
 * (crates/diagnostics/src/items.rs:35-58).
 *
 * Two details are Zed's and easy to get wrong. The clean case is `(0, 0)` on
 * *errors and warnings* — a project with nothing but hints still shows the
 * check. And each half appears only when its own count is above zero, so a
 * file with warnings and no errors shows one icon, not a zero.
 *
 * Zed's click deploys its project-diagnostics editor (items.rs:53-56), and
 * ours opens the diagnostics panel, which is that editor in dock-panel form.
 */
@Composable
private fun DiagnosticIndicator(summary: DiagnosticSummary, onClick: (() -> Unit)?) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .height(ItemBox)
            .clip(RoundedCornerShape(rem(ZedRadius.SM)))
            .background(
                when {
                    onClick == null -> Color.Transparent
                    pressed -> theme.color("ghost_element.active", Color.Transparent)
                    hovered -> theme.color("ghost_element.hover", Color.Transparent)
                    else -> Color.Transparent
                }
            )
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = summary.label,
                            onClick = onClick,
                        )
                }
            )
            .padding(horizontal = ItemGap),
        verticalAlignment = Alignment.CenterVertically,
        // Zed's `gap_1` inside the indicator (items.rs:42).
        horizontalArrangement = Arrangement.spacedBy(ItemGap),
    ) {
        if (summary.isClean) {
            CheckIcon(theme.color("text", MaterialTheme.colorScheme.onSurface), summary.label)
        } else {
            if (summary.errors > 0) {
                XCircleIcon(theme.color("error"), summary.label)
                CountLabel(summary.errors)
            }
            if (summary.warnings > 0) {
                WarningIcon(theme.color("warning"), summary.label)
                CountLabel(summary.warnings)
            }
        }
    }
}

/** `Label::new(count.to_string()).size(LabelSize::Small)` (items.rs:50). */
@Composable
private fun CountLabel(count: Int) {
    Text(
        text = count.toString(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * The first line of the diagnostic under the caret, which is what Zed puts
 * beside the counts (`current_diagnostic`, items.rs:60-85). Clicking it goes
 * to the next diagnostic, exactly as Zed's does.
 */
@Composable
private fun CursorDiagnosticMessage(diagnostic: Diagnostic, onClick: (() -> Unit)?) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        text = diagnostic.label,
        style = MaterialTheme.typography.labelMedium,
        // Zed colours the message by its severity nowhere — it is a plain
        // `Button` label — but the icon beside it is already the severity, so
        // the sentence stays in `text` and the colour stays meaningful.
        color = if (hovered && onClick != null) {
            theme.color("text", MaterialTheme.colorScheme.onSurface)
        } else {
            theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = "Go to the next problem",
                            onClick = onClick,
                        )
                }
            )
            .padding(horizontal = ItemGap),
    )
}

/**
 * Zed's activity indicator: a turning circle and one sentence about the work
 * going on behind the editor (activity_indicator.rs:397-500, 760-820).
 *
 * The spinner is the same `load_circle` glyph the git panel's remote button
 * turns, at the same two-second period (git_ui.rs:1110-1123) — one animation
 * in the app rather than two that disagree. A tap reveals wherever the job
 * lives, which is Zed's `on_click` per branch.
 */
@Composable
private fun ActivityIndicatorItem(line: ActivityLine, onClick: (() -> Unit)?) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    // Zed's own answer to `reduce_motion` is to render a spinner "in a static
    // state" (default.json:281-282), so the circle stops rather than
    // disappearing: the indicator still says work is happening.
    val still = LocalReduceMotion.current
    val spin by rememberInfiniteTransition(label = "activity").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "activity-turn",
    )
    val turn = if (still) 0f else spin
    val label = if (line.others > 0) "${line.message}  +${line.others}" else line.message
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .height(ItemBox)
            .clip(RoundedCornerShape(rem(ZedRadius.SM)))
            .background(
                when {
                    onClick == null -> Color.Transparent
                    pressed -> theme.color("ghost_element.active", Color.Transparent)
                    hovered -> theme.color("ghost_element.hover", Color.Transparent)
                    else -> Color.Transparent
                }
            )
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = "Show what is running",
                            onClick = onClick,
                        )
                }
            )
            .padding(horizontal = ItemGap),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_ui_load_circle),
            contentDescription = null,
            colorFilter = ColorFilter.tint(
                theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
            ),
            modifier = Modifier.size(ItemIconSize).rotate(turn),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A language server that could not start, said in words.
 *
 * Zed shows this as a coloured dot on its `BoltOutlined` button, with the
 * detail behind a popover (language_tools/src/lsp_button.rs:1367-1379). A
 * popover is a poor fit for a 30px bar on a phone, and the detail is the
 * whole point: a server that could not start is exactly the cue to install
 * it, so the dot keeps Zed's colour and the sentence says what to do.
 */
@Composable
private fun LanguageServerNote(note: String, others: Int, onClick: (() -> Unit)? = null) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val text = if (others > 0) "$note (and $others more)" else note
    Row(
        modifier = Modifier
            .height(ItemBox)
            .clip(RoundedCornerShape(rem(ZedRadius.SM)))
            // The same ghost ramp every other item in this bar wears, and
            // only when there is somewhere for the tap to go.
            .background(
                when {
                    onClick == null -> Color.Transparent
                    pressed -> theme.color("ghost_element.active", Color.Transparent)
                    hovered -> theme.color("ghost_element.hover", Color.Transparent)
                    else -> Color.Transparent
                }
            )
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = "Install it",
                            onClick = onClick,
                        )
                }
            )
            .padding(horizontal = ItemGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ItemGap),
    ) {
        // `Indicator::dot().color(Color::Error)` — 4px, which is Zed's
        // `Indicator` dot at its default size (ui/src/components/indicator.rs).
        Canvas(modifier = Modifier.size(rem(StatusBarMetrics.NOTE_DOT))) {
            drawCircle(color = theme.color("error"), radius = size.minDimension / 2f)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Zed's `IconName::Check` at `IconSize::Small`, drawn rather than shipped.
 *
 * The three glyphs below are strokes on a canvas for the same reason the
 * editor's fold chevrons are: they are three shapes, and three SVGs to
 * maintain, license and keep in step with the icon set is a worse trade than
 * nine lines of geometry. The proportions are the icon set's 16px grid
 * scaled to the 14px `IconSize::Small` box.
 */
@Composable
private fun CheckIcon(color: Color, label: String) {
    Canvas(modifier = Modifier.size(ItemIconSize).semanticsLabel(label)) {
        val stroke = size.minDimension * 0.11f
        drawLine(
            color = color,
            start = Offset(size.width * 0.22f, size.height * 0.53f),
            end = Offset(size.width * 0.42f, size.height * 0.73f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.42f, size.height * 0.73f),
            end = Offset(size.width * 0.79f, size.height * 0.29f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** Zed's `IconName::XCircle`: a ring with a cross in it. */
@Composable
private fun XCircleIcon(color: Color, label: String) {
    Canvas(modifier = Modifier.size(ItemIconSize).semanticsLabel(label)) {
        val stroke = size.minDimension * 0.11f
        drawCircle(
            color = color,
            radius = (size.minDimension - stroke) / 2f,
            style = Stroke(width = stroke),
        )
        val inset = size.minDimension * 0.33f
        drawLine(
            color = color,
            start = Offset(inset, inset),
            end = Offset(size.width - inset, size.height - inset),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width - inset, inset),
            end = Offset(inset, size.height - inset),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** Zed's `IconName::Warning`: a triangle with a bang in it. */
@Composable
private fun WarningIcon(color: Color, label: String) {
    Canvas(modifier = Modifier.size(ItemIconSize).semanticsLabel(label)) {
        val stroke = size.minDimension * 0.11f
        val top = size.height * 0.14f
        val bottom = size.height * 0.84f
        val path = Path().apply {
            moveTo(size.width / 2f, top)
            lineTo(size.width - stroke, bottom)
            lineTo(stroke, bottom)
            close()
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawLine(
            color = color,
            start = Offset(size.width / 2f, size.height * 0.42f),
            end = Offset(size.width / 2f, size.height * 0.62f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = color,
            radius = stroke * 0.55f,
            center = Offset(size.width / 2f, size.height * 0.73f),
        )
    }
}

/**
 * A canvas has no text to read out, so the icons carry the summary Zed puts
 * in `aria_label` (items.rs:117).
 */
private fun Modifier.semanticsLabel(label: String): Modifier =
    this.semantics { contentDescription = label }

/**
 * Zed's `Divider::vertical()` between the middle and a dock's button group:
 * 1px wide, `h_4` (16px) tall, in `border` (divider.rs:29, 147-149;
 * dock.rs:1433-1446).
 */
@Composable
private fun GroupDivider(color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .width(StatusBarPixels.DividerWidth)
            .height(rem(StatusBarMetrics.DIVIDER_HEIGHT))
            .background(color)
    )
}
