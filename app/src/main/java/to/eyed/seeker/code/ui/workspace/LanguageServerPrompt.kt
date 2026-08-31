package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.core.LanguageServerInstaller
import to.eyed.seeker.code.core.LanguageServers
import to.eyed.seeker.code.core.AptInstallState
import to.eyed.seeker.code.core.targetOrNull
import to.eyed.seeker.code.terminal.Userland
import to.eyed.seeker.code.ui.theme.LocalZedTheme

/**
 * Zed's `Button` at `ButtonSize::Medium`: a 28px box with 8px of horizontal
 * padding and `rounded_sm` corners (ui/src/components/button/button_like.rs —
 * heights 32/28/22/18/16, px 8/8/4/4/1). Medium rather than Default because
 * these two are the decision the modal exists to take.
 */
private val ButtonHeight = 28.dp
private val ButtonPadding = 8.dp

/**
 * "Python needs a language server — install python3-pylsp and
 * python3-pyflakes (~12.4 MB)?"
 *
 * Zed asks before it installs anything for a language: it offers the
 * recommended extension for the file you just opened and waits for an answer,
 * with "No, don't install it" beside it
 * (crates/extensions_ui/src/extension_suggest.rs:170-205), and its
 * server-initiated prompts are the same shape — a message and a row of
 * options, never an action taken on your behalf
 * (crates/workspace/src/notifications.rs:248-330). This dialog is that, with
 * the price attached, because here the download is Debian's and the connection
 * is a phone's.
 *
 * [grammar] is the language to offer — the engine's grammar name, as
 * `BufferSession.language` reports it. Null opens the list instead, which is
 * what the command palette does when there is no file open to infer one from.
 *
 * The work belongs to [LanguageServerInstaller], not to this dialog: closing
 * the prompt while apt is unpacking leaves apt unpacking, and reopening it
 * shows where it got to. Only [cancel][LanguageServerInstaller.cancel] stops
 * it, exactly as with a clone.
 *
 * Touch, keyboard and mouse in the same change, as the conventions require:
 * every row and button is a tap target with a hand cursor and an instant
 * hover swap, `Enter` takes the primary action, `Esc` closes, and `↑`/`↓`
 * (with `Tab`/`Shift+Tab`) move through the language list.
 */
@Composable
fun LanguageServerPrompt(
    grammar: String?,
    onDismiss: () -> Unit,
) {
    // A build with no guest has nothing to install into. Every build that
    // ships has one, so this is a backstop rather than a path: draw nothing
    // rather than a dialog whose every button fails. (The command that used to
    // grey itself out here went with ui/workspace/Commands.kt.)
    if (!LanguageServerInstaller.isSupported) return

    val context = LocalContext.current
    val theme = LocalZedTheme.current
    val state = LanguageServerInstaller.state
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var selected by remember { mutableIntStateOf(0) }

    // What we were opened for, when it is something we can install. A prompt
    // that is already busy with another language wins: the state is the work,
    // and the work is what the user needs to see.
    val requested = remember(grammar) { LanguageServers.forGrammar(grammar) }
    val unpackaged = remember(grammar) { LanguageServers.unpackagedMessage(grammar) }

    LaunchedEffect(requested) {
        // Belt as well as braces: whatever route closed the prompt last time,
        // a state left over for a *different* language is this one's to
        // replace. `dismiss()` is a no-op while apt is actually running, so a
        // live install still wins — which is the rule the doc comment above
        // states.
        val stale = LanguageServerInstaller.state.targetOrNull
        if (requested != null && stale != null && stale != requested) {
            LanguageServerInstaller.dismiss()
        }
        if (requested != null && LanguageServerInstaller.state is AptInstallState.Idle) {
            // Asking what it costs is not installing: the estimate answers
            // apt's own confirmation prompt with "no" and fetches nothing.
            LanguageServerInstaller.offer(context, requested)
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(selected) {
        if (selected in LanguageServers.ALL.indices) listState.scrollToItem(selected)
    }

    /**
     * Leaving. The installer's state outlives this composable on purpose —
     * closing must not stop an apt that is already running — but a state
     * that is *not* running is this prompt's, and leaving it behind means the
     * next opening shows the last language's question and installs the last
     * language's packages. `dismiss()` clears exactly the not-running case.
     */
    fun close() {
        LanguageServerInstaller.dismiss()
        onDismiss()
    }

    /** What Enter does, which is also what the rightmost button does. */
    fun primary() {
        when (val current = state) {
            is AptInstallState.Offered ->
                LanguageServerInstaller.install(context, current.target)

            is AptInstallState.Failed -> {
                val target = current.target
                if (target != null) {
                    LanguageServerInstaller.offer(context, target)
                } else {
                    LanguageServerInstaller.dismiss()
                    onDismiss()
                }
            }

            is AptInstallState.Checking, is AptInstallState.Installing ->
                LanguageServerInstaller.cancel()

            is AptInstallState.AlreadyInstalled, is AptInstallState.Finished -> {
                LanguageServerInstaller.dismiss()
                onDismiss()
            }

            AptInstallState.Idle ->
                if (unpackaged != null) {
                    close()
                } else {
                    LanguageServers.ALL.getOrNull(selected)?.let {
                        LanguageServerInstaller.offer(context, it)
                    }
                }
        }
    }

    fun move(delta: Int) {
        val size = LanguageServers.ALL.size
        if (size == 0) return
        selected = ((selected + delta) % size + size) % size
    }

    PickerModal(
        onDismiss = onDismiss,
        modifier = Modifier
            .focusRequester(focus)
            // Nothing here is a text field, so the modal itself has to hold
            // focus or the keyboard would talk to the workspace underneath.
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.Escape -> { close(); true }
                    event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                        primary()
                        true
                    }
                    // The list is only on screen while nothing is running.
                    state !is AptInstallState.Idle -> false
                    event.key == Key.DirectionDown -> { move(1); true }
                    event.key == Key.DirectionUp -> { move(-1); true }
                    event.key == Key.Tab -> {
                        move(if (event.isShiftPressed) -1 else 1)
                        true
                    }
                    else -> false
                }
            },
    ) {
        Column(
            // Zed's notification body is `p_3` = 12px
            // (workspace/src/notifications.rs:329).
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "LANGUAGE SERVER",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = theme.color("text.muted"),
            )

            when (val current = state) {
                AptInstallState.Idle -> if (unpackaged != null) {
                    Body(unpackaged)
                    Actions {
                        PromptButton("Close", isPrimary = true, onClick = onDismiss)
                    }
                } else {
                    Body("Install a language server into ${Userland.backend.displayName}.")
                    LanguageList(
                        selected = selected,
                        onSelect = { index ->
                            selected = index
                            LanguageServers.ALL.getOrNull(index)?.let {
                                LanguageServerInstaller.offer(context, it)
                            }
                        },
                        listState = listState,
                    )
                    Actions {
                        PromptButton("Close", onClick = onDismiss)
                        PromptButton("Install…", isPrimary = true, onClick = { primary() })
                    }
                }

                is AptInstallState.Checking -> {
                    Body("Asking apt what ${current.target.packageList} would cost…")
                    Bar()
                    Actions {
                        PromptButton("Cancel", isPrimary = true) {
                            LanguageServerInstaller.cancel()
                        }
                    }
                }

                is AptInstallState.Offered -> {
                    Text(
                        text = current.target.question(current.plan),
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.color("text"),
                    )
                    Body(current.target.detail(current.plan, Userland.backend.displayName))
                    Actions {
                        // Zed's own second option, in Zed's own words
                        // (extension_suggest.rs:193).
                        PromptButton("No, don't install it") {
                            LanguageServerInstaller.dismiss()
                            onDismiss()
                        }
                        PromptButton("Install", isPrimary = true, onClick = { primary() })
                    }
                }

                is AptInstallState.AlreadyInstalled -> {
                    Body(current.target.alreadyInstalledMessage())
                    Actions {
                        PromptButton("Close", isPrimary = true, onClick = { primary() })
                    }
                }

                is AptInstallState.Installing -> {
                    Text(
                        text = "Installing ${current.target.packageList}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.color("text"),
                    )
                    // apt's last line, verbatim: it names the mirror, the
                    // package and the phase, which is more than any summary of
                    // ours would.
                    Body(current.step, maxLines = 2)
                    Bar()
                    Actions {
                        PromptButton("Cancel", isPrimary = true) {
                            LanguageServerInstaller.cancel()
                        }
                    }
                }

                is AptInstallState.Failed -> {
                    Text(
                        text = current.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.color("error", MaterialTheme.colorScheme.error),
                    )
                    // apt's own words, kept verbatim: they are usually the only
                    // thing that names the mirror or the broken dependency.
                    current.detail?.let { Body(it, maxLines = 6) }
                    Actions {
                        PromptButton("Close") {
                            LanguageServerInstaller.dismiss()
                            onDismiss()
                        }
                        if (current.target != null) {
                            PromptButton("Try again", isPrimary = true, onClick = { primary() })
                        }
                    }
                }

                is AptInstallState.Finished -> {
                    Body(current.target.installedMessage())
                    Actions {
                        PromptButton("Close", isPrimary = true, onClick = { primary() })
                    }
                }
            }
        }
    }
}

/** One line of explanation, `Color::Muted` as Zed's notification bodies are. */
@Composable
private fun Body(text: String, maxLines: Int = Int.MAX_VALUE) {
    val theme = LocalZedTheme.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = theme.color("text.muted"),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The action row: `justify_end` with `gap_1` between buttons. */
@Composable
private fun Actions(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/**
 * What apt is doing, drawn the way the clone dialog draws a phase with no
 * percentage: a full bar in the selected fill. apt reports lines, not
 * fractions — "Get:14 http://deb.debian.org … 4,096 kB" — and a bar that
 * invented a percentage from them would be a bar that lies.
 */
@Composable
private fun Bar() {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(theme.color("element.background"))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(theme.color("element.selected"))
        )
    }
}

/** Every server we can install: the palette's route in when no file says which. */
@Composable
private fun LanguageList(
    selected: Int,
    onSelect: (Int) -> Unit,
    listState: LazyListState,
) {
    val theme = LocalZedTheme.current
    LazyColumn(
        state = listState,
        contentPadding = PickerListPadding,
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(LanguageServers.ALL, key = { _, item -> item.server }) { index, item ->
            PickerListItem(
                isSelected = index == selected,
                onClick = { onSelect(index) },
            ) {
                Text(
                    text = item.language,
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.color("text"),
                    maxLines = 1,
                )
                Box(modifier = Modifier.weight(1f))
                Text(
                    text = item.packages.joinToString(" "),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A modal button: filled for the primary answer, ghost for the way out.
 *
 * The ghost ramp is Zed's — transparent, `ghost_element.hover`,
 * `ghost_element.active` — and the filled one is `element.background` /
 * `.hover` / `.active`, swapped instantly with no ripple
 * (button_like.rs:298-329).
 */
@Composable
private fun PromptButton(
    label: String,
    isPrimary: Boolean = false,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val fill = when {
        pressed -> theme.color(
            if (isPrimary) "element.active" else "ghost_element.active",
            Color.Transparent,
        )
        hovered -> theme.color(
            if (isPrimary) "element.hover" else "ghost_element.hover",
            Color.Transparent,
        )
        isPrimary -> theme.color("element.background", Color.Transparent)
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .height(ButtonHeight)
            .clip(RoundedCornerShape(4.dp))
            .background(fill)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(horizontal = ButtonPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isPrimary) {
                theme.color("text.accent", MaterialTheme.colorScheme.primary)
            } else {
                theme.color("text.muted")
            },
            maxLines = 1,
        )
    }
}

/**
 * The grammar to offer a server for, from what the status bar knows.
 *
 * `lspServers` reports the *binary* — `{"name":"clangd","state":"unavailable"}`
 * — while this dialog and the engine's table are keyed by grammar, so the two
 * are joined here rather than in the status bar. Falls back to the server's
 * own first grammar, which is what "install clangd" means when a C++ file put
 * it on screen.
 */
fun grammarForServer(server: String?): String? =
    LanguageServers.forServer(server)?.grammars?.firstOrNull()
