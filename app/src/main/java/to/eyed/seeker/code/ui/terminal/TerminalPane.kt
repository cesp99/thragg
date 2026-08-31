package to.eyed.seeker.code.ui.terminal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.terminal.TerminalPanelState
import to.eyed.seeker.code.terminal.TerminalSessionHost
import to.eyed.seeker.code.terminal.Userland
import to.eyed.seeker.code.terminal.UserlandInstaller
import to.eyed.seeker.code.terminal.UserlandState
import to.eyed.seeker.code.ui.theme.ZedTheme
import to.eyed.seeker.code.ui.theme.LocalReduceMotion
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.touchTarget
import to.eyed.seeker.code.ui.theme.LocalAppSettings
import to.eyed.seeker.code.ui.theme.FontCatalog
import to.eyed.seeker.code.ui.workspace.ContextMenu
import to.eyed.seeker.code.ui.workspace.ContextMenuItem
import to.eyed.seeker.code.ui.workspace.onSecondaryClick

/** How long the dock stays lit after a bell. Long enough to catch, short enough to ignore. */
private const val BELL_FLASH_MS = 220L

/** Modifier keys held by the extra-key row, since a soft keyboard has none. */
private class StickyModifiers {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var shift by mutableStateOf(false)

    fun clear() {
        ctrl = false
        alt = false
        shift = false
    }

    /** Modifier bits in the form [TerminalView.handleKeyCode] wants. */
    fun keyMod(): Int {
        var mod = 0
        if (ctrl) mod = mod or KEYMOD_CTRL
        if (alt) mod = mod or KEYMOD_ALT
        if (shift) mod = mod or KEYMOD_SHIFT
        return mod
    }
}

/**
 * Everything the dock can do to the session in front of it.
 *
 * One table behind the overflow menu and the long-press menu, so the two
 * can't drift. Names are Zed's `terminal::` actions (terminal_view.rs,
 * `actions!(terminal, …)`) and stay Zed's even though nothing binds them to a
 * chord any more: [byId] dispatches by id, and an id that means the same
 * thing in the upstream editor is worth more than a private one.
 *
 * There is no `shortcut` column now. The keymap subsystem it read — the
 * WorkspaceCommand table, DefaultKeymap and shortcutLabel in
 * ui/workspace/Keybindings.kt — is gone (docs/UI.md, "What is removed"), and
 * a menu on a phone that prints "Ctrl+Shift+F" beside a row you reached with
 * your thumb was chrome for a keyboard nobody attached.
 */
internal enum class TerminalAction(val id: String, val label: String) {
    Copy("terminal::Copy", "Copy"),
    Paste("terminal::Paste", "Paste"),
    /**
     * Zed's `buffer_search::Deploy` in the Terminal context
     * (default-linux.json:1281-1282: `find` and `ctrl-shift-f`). The id is
     * spelled out here rather than read from the command table, which no
     * longer exists; it is the same string that table carried.
     */
    Search("buffer_search::Deploy", "Find…"),
    Clear("terminal::Clear", "Clear"),
    ScrollToTop("terminal::ScrollToTop", "Scroll to top"),
    ScrollToBottom("terminal::ScrollToBottom", "Scroll to bottom"),
    Rename("terminal::RenameTerminal", "Rename…"),
    Restart("terminal::Restart", "Restart"),
    Close("terminal::Close", "Close");

    companion object {
        fun byId(id: String): TerminalAction? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Frame around the terminal view that sees hardware keys first.
 *
 * The vendored view drops the text selection at the top of its own `onKeyDown`,
 * *before* the client callback runs, so a `Ctrl+Shift+C` arriving that way
 * would always find nothing selected. Every ancestor `ViewGroup` is on the
 * dispatch path ahead of the focused child, which is early enough.
 */
private class TerminalKeyFrame(context: Context) : FrameLayout(context) {
    val terminal = TerminalView(context, null)

    /** Returns true when the chord belongs to the dock rather than the shell. */
    var onKey: ((AndroidKeyEvent) -> Boolean)? = null

    /**
     * What the last `update` pass pushed into the view, so a recomposition
     * that changed none of it — a bell, a title, a latched modifier — does
     * not call through again: `setTextSize` allocates a whole new renderer
     * per call, and `applyPalette` invalidates the screen. The palette is
     * written into the emulator, so a fresh emulator — session switch,
     * restart, first layout — needs it applied again even under the same
     * theme; hence the emulator is tracked next to the theme. Neither
     * reference catches a program resetting the colours *in place* (RIS,
     * OSC 104), so the guard also probes the emulator's actual colour state
     * via [paletteSentinelsMatch] before trusting these.
     */
    var lastTextSizePx = 0
    var lastTypeface: Typeface? = null
    var lastTheme: ZedTheme? = null
    var lastEmulator: TerminalEmulator? = null

    init {
        addView(
            terminal,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    override fun dispatchKeyEvent(event: AndroidKeyEvent): Boolean {
        if (event.action == AndroidKeyEvent.ACTION_DOWN && onKey?.invoke(event) == true) return true
        return super.dispatchKeyEvent(event)
    }
}

/**
 * The terminal dock — Zed's bottom panel, with the shell running in the
 * project directory.
 *
 * The screen itself is Termux's `TerminalView` (a classic Android `View`)
 * embedded with `AndroidView`; everything around it is ours. The parts worth
 * knowing:
 *
 * - **Keyboard.** The shell gets every plain `Ctrl+<letter>`, Escape, `Alt`
 *   chords and the function keys. Only the keymap's `Terminal` context is
 *   matched — the short default list in `DefaultKeymap`, plus whatever the
 *   user bound under `"context": "Terminal"` — and it is matched by the
 *   workspace's one key pass, which the dock's [onKey] backstops for a key
 *   that reaches the view first. The dock's own actions ([TerminalAction])
 *   answer through [TerminalPanelState.dockAction].
 * - **Touch.** Tap focuses and raises the keyboard; long-press on a path or
 *   a URL follows it, long-press elsewhere selects, with the vendored handles
 *   and copy/paste toolbar; pinch resizes the font. The
 *   extra-key row exists because GBoard has no Esc, Tab, Ctrl, arrows or page
 *   keys — without it the terminal cannot be driven by touch at all, and the
 *   scrollback could not be reached.
 * - **Mouse.** The vendored view already handles wheel scrolling and mouse
 *   reporting; right-click opens the same action menu the `⋮` button does, and
 *   the chrome takes hover cursors like the rest of the app. Ctrl+click on a
 *   path or a URL follows it, as Zed's cmd-click does. Zed also underlines
 *   the target while the modifier is held; the vendored renderer has no
 *   per-cell decoration hook short of rewriting its draw loop, so that is
 *   left out rather than done expensively.
 */
@Composable
fun TerminalDock(
    state: TerminalPanelState,
    cwd: String?,
    fontSizeSp: Float,
    /**
     * A hardware key arriving while the shell has focus, ahead of the shell.
     * The workspace resolves it against the keymap's `Terminal` context and
     * runs what it finds — a dock action through [TerminalPanelState.dockAction],
     * a workspace command directly — and returns whether it did, so the
     * shell sees only the keys nothing claimed.
     */
    onKey: (AndroidKeyEvent) -> Boolean,
    /** The header's collapse button: the workspace's `terminal_panel::Toggle`. */
    onHide: () -> Unit,
    /** The header's play button: the workspace's `task::Spawn` picker. */
    onSpawnTask: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    /**
     * A path the user Ctrl-clicked or long-pressed, resolved against the
     * shell's directory. The workspace decides what to do with it — open it
     * in the editor if it is in the project, say so if it is not.
     */
    onOpenPath: (TerminalPathTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val host = state.active ?: return
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Pinch-to-zoom is terminal-local: the setting drives the editor, and a
    // pinch here should not rewrite the user's settings file.
    var fontScale by remember { mutableFloatStateOf(1f) }
    val textSizePx = remember(fontSizeSp, fontScale, density) {
        with(density) { (fontSizeSp * fontScale).sp.toPx() }.toInt().coerceIn(8, 96)
    }

    // `buffer_font_family`, resolved off the main thread — the terminal draws
    // in the editor's face, as Zed's does. A terminal in a different monospace
    // from the editor two panes away is one font too many.
    val settings = LocalAppSettings.current
    val typeface by produceState(Typeface.MONOSPACE, settings.fonts.bufferFamily) {
        value = withContext(Dispatchers.IO) {
            FontCatalog.typeface(context, settings.fonts.bufferFamily, Typeface.MONOSPACE)
        }
    }

    val sticky = remember { StickyModifiers() }
    var view by remember { mutableStateOf<TerminalView?>(null) }
    var renaming by remember { mutableStateOf<TerminalSessionHost?>(null) }
    /** Where the right-click landed, and therefore whether the menu is up. */
    var surfaceMenuAt by remember { mutableStateOf<DpOffset?>(null) }

    fun runAction(action: TerminalAction) {
        val terminal = view
        // Read the active session *now*, never the `host` this composition
        // captured. `AndroidView`'s factory runs once and keeps the first
        // `onKey` it is given, so a closure over `host` is pinned to whichever
        // session existed when the dock's view was created: open a second
        // shell, press Ctrl+Shift+V, and the clipboard goes into the first
        // one's pty — invisibly, and executed if it ends in a newline.
        val active = state.active
        when (action) {
            TerminalAction.Copy -> terminal?.let { active?.copy(terminalSelection(it)) }
            TerminalAction.Paste -> active?.paste()
            TerminalAction.Search -> state.searchOpen = true
            TerminalAction.Clear -> terminal?.let(::clearTerminal)
            TerminalAction.ScrollToTop -> terminal?.let(::scrollTerminalToTop)
            TerminalAction.ScrollToBottom -> terminal?.let(::scrollTerminalToBottom)
            TerminalAction.Rename -> renaming = active
            TerminalAction.Restart -> active?.restart()
            TerminalAction.Close -> state.closeSession(state.activeIndex)
        }
    }

    /**
     * A link under a click, followed. URLs go to the browser here; a path is
     * made absolute against the shell's *current* directory — a `/proc` read,
     * kept off the main thread on principle — and handed to the workspace.
     */
    fun openLink(link: TerminalLink) {
        when (link) {
            is TerminalLink.Url -> openUrl(context, link.url)
            is TerminalLink.PathLike -> {
                val active = state.active ?: return
                scope.launch {
                    val directory = withContext(Dispatchers.IO) { active.currentDirectory() }
                    val projects = File(context.filesDir, "projects").absolutePath
                    onOpenPath(resolveTerminalPath(link, directory, projects))
                }
            }
        }
    }

    // The keymap's `terminal::` actions, answered here because only this
    // composition holds the view they act on. Registered on the shared state
    // so the workspace's one key pass can reach them, and taken back when
    // the dock leaves — a closed dock must not answer for a key.
    DisposableEffect(state) {
        state.dockAction = { id ->
            val action = TerminalAction.byId(id)
            if (action != null) runAction(action)
            action != null
        }
        onDispose { state.dockAction = null }
    }

    val client = remember {
        SeekerTerminalViewClient(
            context = context,
            sticky = sticky,
            currentHost = { state.active },
            currentView = { view },
            onFontScale = { scale -> fontScale = (fontScale * scale).coerceIn(0.5f, 3f) },
            onEmulatorReady = { view?.let { applyPalette(it, theme) } },
            onOpenLink = ::openLink,
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.color("terminal.background", theme.color("editor.background")))
    ) {
        TerminalHeader(
            state = state,
            onNew = { cwd?.let { state.newSession(it) } },
            onSelect = state::select,
            onClose = state::closeSession,
            onRename = { session -> renaming = session },
            onAction = ::runAction,
            onHide = onHide,
            onSpawnTask = onSpawnTask,
        )
        HorizontalDivider()

        // Zed deploys the buffer search bar in the terminal's toolbar, above
        // the screen (terminal_view.rs: `SearchableItem`); the same place here.
        if (state.searchOpen) {
            TerminalSearchBar(
                host = host,
                view = view,
                onDismiss = {
                    state.searchOpen = false
                    // Escape and the close button hand the keyboard back to
                    // the shell, which is where it came from.
                    view?.requestFocus()
                },
            )
            HorizontalDivider()
        }

        // The userland offer. Absent in builds without one, and once Debian is
        // installed there is nothing to say. The work itself belongs to
        // UserlandInstaller rather than to this composable — hiding the dock
        // must not cancel a 30 MB download.
        LaunchedEffect(Unit) { UserlandInstaller.refresh(context) }
        val userland = UserlandInstaller.state
        if (userland != null &&
            userland !is UserlandState.Ready &&
            userland !is UserlandState.Unsupported
        ) {
            UserlandBanner(
                state = userland,
                onInstall = {
                    // Re-enter the shell on success so this session lands in
                    // Debian rather than the fallback it started in.
                    UserlandInstaller.install(context) { host.restart() }
                },
                onCancel = { UserlandInstaller.cancel() },
            )
            HorizontalDivider()
        }

        // The renderer draws from x=0, so the padding has to come from here or
        // the first column sits against the window edge.
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .onSecondaryClick { position -> surfaceMenuAt = position }
        ) {
            AndroidView(
                factory = { ctx ->
                    TerminalKeyFrame(ctx).apply {
                        terminal.setTerminalViewClient(client)
                        terminal.isFocusableInTouchMode = true
                        // Order matters: setTextSize builds the renderer that
                        // setTypeface then reads its size from.
                        terminal.setTextSize(textSizePx)
                        lastTextSizePx = textSizePx
                        terminal.setTypeface(typeface)
                        lastTypeface = typeface
                        terminal.setOnFocusChangeListener { _, hasFocus ->
                            onFocusChanged(hasFocus)
                        }
                        // The workspace's own key pass has already seen the
                        // event on its way down through Compose; this is
                        // the backstop for a key that reached the view
                        // first, and gives the same answer.
                        this.onKey = { event -> onKey(event) }
                        view = terminal
                    }
                },
                update = { frame ->
                    // Runs on every recomposition — a bell, a title, a sticky
                    // modifier — so the expensive calls only go through when
                    // what they would push has actually changed.
                    if (frame.lastTextSizePx != textSizePx) {
                        frame.terminal.setTextSize(textSizePx)
                        frame.lastTextSizePx = textSizePx
                    }
                    // Same order as the factory: the renderer is built by
                    // setTextSize and read by setTypeface, so a face change
                    // re-pushes the size first.
                    if (frame.lastTypeface !== typeface) {
                        frame.terminal.setTextSize(frame.lastTextSizePx)
                        frame.terminal.setTypeface(typeface)
                        frame.lastTypeface = typeface
                    }
                    if (frame.terminal.currentSession !== host.session) {
                        host.attach(frame.terminal)
                    }
                    // The emulator only exists once the view has a size, so
                    // keep retrying until the first application lands. The
                    // sentinel probe is there because identity alone lies: a
                    // program can reset the palette in place (see
                    // [paletteSentinelsMatch]) without either tracked
                    // reference changing.
                    val emulator = frame.terminal.mEmulator
                    if (emulator != null &&
                        (frame.lastTheme !== theme ||
                            frame.lastEmulator !== emulator ||
                            !paletteSentinelsMatch(emulator, theme))
                    ) {
                        applyPalette(frame.terminal, theme)
                        frame.lastTheme = theme
                        frame.lastEmulator = emulator
                    }
                },
                onRelease = { frame -> host.detach(frame.terminal) },
                modifier = Modifier.fillMaxSize(),
            )

            BellFlash(host)

            val menuAt = surfaceMenuAt
            if (menuAt != null) {
                ContextMenu(
                    expanded = true,
                    onDismiss = { surfaceMenuAt = null },
                    items = menuItems(surfaceActions, ::runAction),
                    offset = menuAt,
                )
            }
        }

        // A session that exits stays where it is and says so. Zed does the
        // same: the scrollback is usually the reason you are looking, and a
        // pane that vanishes takes the error message with it.
        if (host.exitStatus != null) {
            HorizontalDivider()
            SessionExitedBar(
                host = host,
                onRestart = { runAction(TerminalAction.Restart) },
                onClose = { runAction(TerminalAction.Close) },
            )
        }

        HorizontalDivider()
        ExtraKeysRow(
            sticky = sticky,
            onKey = { keyCode ->
                view?.handleKeyCode(keyCode, sticky.keyMod())
                sticky.clear()
            },
            onPage = { up -> view?.let { scrollTerminalPage(it, up) } },
            onToggleKeyboard = { view?.let { toggleSoftKeyboard(context, it) } },
        )
    }

    val session = renaming
    if (session != null) {
        RenameSessionDialog(
            host = session,
            onRename = { name ->
                session.rename(name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    // Attach on entry, and re-attach whenever the visible session changes, so
    // the session that is on screen is the one receiving screen updates.
    val attached = view
    if (attached != null) {
        DisposableEffect(host, attached) {
            host.attach(attached)
            attached.requestFocus()
            applyPalette(attached, theme)
            onDispose { host.detach(attached) }
        }
    }
}

/** What the right-click menu offers: the actions that act on the screen itself. */
private val surfaceActions = listOf(
    TerminalAction.Copy,
    TerminalAction.Paste,
    TerminalAction.Search,
    TerminalAction.Clear,
    TerminalAction.ScrollToTop,
    TerminalAction.ScrollToBottom,
)

/** Everything, for the `⋮` button — the touch user's way to all of it. */
private val overflowActions = TerminalAction.entries

/**
 * The bell, made visible.
 *
 * A short wash of the terminal's foreground colour over the screen, and
 * nothing else: a sound or a vibration needs a setting to turn it off, and
 * the `terminal` section has no `bell` key yet. The session chip keeps a dot
 * until you type, so a bell you missed is still there when you look.
 */
@Composable
private fun BoxScope.BellFlash(host: TerminalSessionHost) {
    val theme = LocalZedTheme.current
    var lit by remember(host) { mutableStateOf(false) }
    LaunchedEffect(host, host.bells) {
        if (host.bells == 0) {
            lit = false
            return@LaunchedEffect
        }
        lit = true
        delay(BELL_FLASH_MS)
        lit = false
    }
    // The flash *is* the message here — it is the visual bell — so reducing
    // motion shortens it to an on/off rather than removing it.
    val still = LocalReduceMotion.current
    val alpha by animateFloatAsState(
        targetValue = if (lit) 0.16f else 0f,
        animationSpec = tween(durationMillis = if (still) 0 else BELL_FLASH_MS.toInt()),
        label = "bell",
    )
    if (alpha > 0f) {
        // No pointer modifiers, so it is not a hit target: the terminal keeps
        // every tap while the flash is on screen.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    theme
                        .color("terminal.foreground", theme.color("editor.foreground"))
                        .copy(alpha = alpha)
                )
        )
    }
}

@Composable
private fun TerminalHeader(
    state: TerminalPanelState,
    onNew: () -> Unit,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onRename: (TerminalSessionHost) -> Unit,
    onAction: (TerminalAction) -> Unit,
    onHide: () -> Unit,
    onSpawnTask: () -> Unit,
) {
    val theme = LocalZedTheme.current
    var overflowOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(theme.color("tab_bar.background"))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.sessions.forEachIndexed { index, session ->
                SessionChip(
                    session = session,
                    selected = index == state.activeIndex,
                    onSelect = { onSelect(index) },
                    onRename = { onRename(session) },
                    onRestart = { session.restart() },
                    onClose = { onClose(index) },
                )
            }
        }

        Box {
            HeaderAction(label = "⋮", onClick = { overflowOpen = true })
            ContextMenu(
                expanded = overflowOpen,
                onDismiss = { overflowOpen = false },
                items = menuItems(overflowActions, onAction),
            )
        }
        // The finger's way to the search bar; the chord is Ctrl+Shift+F.
        HeaderAction(label = "⌕", onClick = { onAction(TerminalAction.Search) })
        // The task picker for a finger — Zed's `task::Spawn`, which its
        // terminal panel offers from the same tab bar (terminal_panel.rs
        // `render_tab_bar_buttons`, the spawn-task button).
        HeaderAction(label = "▶", onClick = onSpawnTask)
        HeaderAction(label = "+", onClick = onNew)
        HeaderAction(label = "⌄", onClick = onHide)
    }
}

/**
 * One session in the header strip.
 *
 * The label is whatever the session is calling itself — the name you gave it,
 * else the title the running program set with an OSC sequence, else "shell 2".
 * A program's title can be a whole path, so it is clipped rather than allowed
 * to push the other sessions off the strip.
 */
@Composable
private fun SessionChip(
    session: TerminalSessionHost,
    selected: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit,
) {
    val theme = LocalZedTheme.current
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (selected) {
                        theme.color("tab.active_background", Color.Transparent)
                    } else {
                        Color.Transparent
                    }
                )
                .pointerHoverIcon(PointerIcon.Hand)
                .onSecondaryClick { menuOpen = true }
                .combinedClickable(
                    onClick = onSelect,
                    onLongClick = { menuOpen = true },
                    onDoubleClick = onRename,
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            if (session.bells > 0) {
                // Left over from a bell nobody was watching for.
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(theme.color("warning", MaterialTheme.colorScheme.primary))
                )
            }
            Text(
                text = session.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = when {
                    session.exitStatus != null -> MaterialTheme.colorScheme.onSurfaceVariant
                    selected -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.widthIn(max = 160.dp),
            )
            Text(
                text = "✕",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .semantics { contentDescription = "Close ${session.title}" }
                    .touchTarget()
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onClose),
            )
        }
        ContextMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            items = listOf(
                ContextMenuItem(TerminalAction.Rename.label) {
                    onRename()
                },
                ContextMenuItem(TerminalAction.Restart.label) { onRestart() },
                ContextMenuItem(TerminalAction.Close.label) {
                    onClose()
                },
            ),
        )
    }
}

/** The dock's actions as menu rows, so the menus and the key table agree. */
private fun menuItems(
    actions: List<TerminalAction>,
    onPick: (TerminalAction) -> Unit,
): List<ContextMenuItem> =
    actions.map { action -> ContextMenuItem(action.label) { onPick(action) } }

/** Says what happened to the shell, and offers the two things worth doing next. */
@Composable
private fun SessionExitedBar(
    host: TerminalSessionHost,
    onRestart: () -> Unit,
    onClose: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.color("status_bar.background"))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = "${host.title} ${host.exitDescription}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        HeaderAction(label = "↻ restart", onClick = onRestart)
        HeaderAction(label = "close", onClick = onClose)
    }
}

/** Ask for a session's name. Enter accepts, Esc and the scrim cancel. */
@Composable
private fun RenameSessionDialog(
    host: TerminalSessionHost,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(host) { mutableStateOf(host.customTitle ?: host.title) }
    val field = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename ${host.label}") },
        text = {
            // Inside the slot, not beside the dialog: the field only exists
            // once the dialog's own composition has run.
            LaunchedEffect(host) { field.requestFocus() }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onRename(name) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(field)
                    .pointerHoverIcon(PointerIcon.Text),
            )
        },
        confirmButton = { TextButton(onClick = { onRename(name) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Offers the Linux userland, and reports on it while it installs.
 *
 * Deliberately not a modal: the terminal below is a working shell already, and
 * a 30 MB download is not worth blocking on.
 */
@Composable
private fun UserlandBanner(
    state: UserlandState,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val backend = Userland.backend
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.color("status_bar.background"))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val message = when (state) {
                is UserlandState.NotInstalled ->
                    "Install ${backend.displayName} for apt and a full Linux toolchain " +
                        "(${backend.downloadDescription})"
                is UserlandState.Installing -> state.step + "…"
                is UserlandState.Failed -> "Install failed: ${state.message}"
                else -> ""
            }
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (state is UserlandState.Installing) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            } else {
                Text(
                    text = if (state is UserlandState.Failed) "Retry" else "Install",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = onInstall)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        if (state is UserlandState.Installing) {
            val fraction = state.fraction
            // The track is named because material3's default for it is
            // `secondaryContainer`, which this app's bridge maps to Zed's
            // `element.selected` — a fill a step BEYOND the top of the surface
            // ladder (docs/VISUAL.md, "Foundations", the secondaryContainer
            // trap). Left alone, the unfilled part of an install bar drew
            // brighter than the status bar it sits on, which reads as a panel
            // rather than as the part of the download that has not happened.
            // Zed's own `element.background` is a rung below this row instead.
            val trackColor = theme.color("element.background")
            if (fraction == null) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    trackColor = trackColor,
                )
            } else {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    trackColor = trackColor,
                )
            }
        }
    }
}

@Composable
private fun HeaderAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * The keys a soft keyboard doesn't have. Ctrl and Alt latch: press Ctrl, then
 * `c`, and the shell sees `^C` — the same one-shot behaviour Termux uses,
 * cleared as soon as a key is consumed. `pgup` and `pgdn` walk the scrollback
 * rather than the shell's history, which is what a touch user has instead of
 * `Shift+PageUp`.
 */
@Composable
private fun ExtraKeysRow(
    sticky: StickyModifiers,
    onKey: (Int) -> Unit,
    onPage: (Boolean) -> Unit,
    onToggleKeyboard: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(theme.color("status_bar.background"))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ExtraKey("esc", "Escape") { onKey(AndroidKeyEvent.KEYCODE_ESCAPE) }
        ExtraKey("tab", "Tab") { onKey(AndroidKeyEvent.KEYCODE_TAB) }
        ExtraKey("ctrl", "Control", latched = sticky.ctrl) { sticky.ctrl = !sticky.ctrl }
        ExtraKey("alt", "Alt", latched = sticky.alt) { sticky.alt = !sticky.alt }
        ExtraKey("←", "Left arrow") { onKey(AndroidKeyEvent.KEYCODE_DPAD_LEFT) }
        ExtraKey("↓", "Down arrow") { onKey(AndroidKeyEvent.KEYCODE_DPAD_DOWN) }
        ExtraKey("↑", "Up arrow") { onKey(AndroidKeyEvent.KEYCODE_DPAD_UP) }
        ExtraKey("→", "Right arrow") { onKey(AndroidKeyEvent.KEYCODE_DPAD_RIGHT) }
        ExtraKey("home", "Home") { onKey(AndroidKeyEvent.KEYCODE_MOVE_HOME) }
        ExtraKey("end", "End") { onKey(AndroidKeyEvent.KEYCODE_MOVE_END) }
        ExtraKey("pgup", "Page up the scrollback") { onPage(true) }
        ExtraKey("pgdn", "Page down the scrollback") { onPage(false) }
        ExtraKey("⌨", "Show or hide the keyboard", onClick = onToggleKeyboard)
    }
}

/**
 * One key of that row.
 *
 * [name] is what it is *called* — "Escape", "Left arrow" — because the caps
 * are abbreviations and glyphs, and "esc" and "←" read aloud as noise. The
 * latched state is part of the description too: a Ctrl that is held is a
 * different key from one that is not, and that is exactly what a
 * screen-reader user cannot see.
 */
@Composable
private fun ExtraKey(
    label: String,
    name: String,
    latched: Boolean = false,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (latched) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            // The row is 38dp tall, so this widens each key to 48dp and
            // leaves the height; a key row is the one place on this screen
            // where a mis-tap costs a keystroke into a running program.
            .touchTarget()
            .semantics { contentDescription = if (latched) "$name, held" else name }
            .background(
                if (latched) {
                    theme.color("element.selected", Color.Transparent)
                } else {
                    Color.Transparent
                }
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** The theme's terminal foreground, exactly as [applyPalette] writes it. */
private fun terminalForeground(theme: ZedTheme): Color =
    theme.color("terminal.foreground", theme.color("editor.foreground"))

/** The theme's terminal background, exactly as [applyPalette] writes it. */
private fun terminalBackground(theme: ZedTheme): Color =
    theme.color("terminal.background", theme.color("editor.background"))

/**
 * Whether the emulator still holds the palette [applyPalette] wrote for this
 * theme, probed through the foreground and background slots — the two entries
 * the theme alone determines (the sixteen ANSI lookups fall back to whatever
 * the slot already holds, so their target values cannot be predicted without
 * doing the work this probe exists to skip).
 *
 * The probe exists because a running program can restore the vendored Termux
 * defaults *in place* — `reset`/`tput reset` (RIS, `ESC c`) and a bare OSC 104
 * both call `TerminalColors.reset()` on the same emulator instance — which no
 * identity comparison in the update pass can see. When the slots diverge the
 * whole palette is rewritten, stomping deliberate OSC 4/10/11 recolouring just
 * as the old apply-every-recomposition code did; only ANSI-slot-only tweaks
 * outlive this probe, and merely until the next full reapply.
 *
 * Both sides are ARGB ints: the vendored parser packs `0xFF << 24 | r g b`
 * and [toArgb] packs the same, so plain equality is exact.
 */
private fun paletteSentinelsMatch(emulator: TerminalEmulator, theme: ZedTheme): Boolean {
    val colors = emulator.mColors.mCurrentColors
    return colors[TextStyle.COLOR_INDEX_FOREGROUND] == terminalForeground(theme).toArgb() &&
        colors[TextStyle.COLOR_INDEX_BACKGROUND] == terminalBackground(theme).toArgb()
}

/**
 * Paint the emulator with the Zed theme's terminal palette, which the theme
 * JSON already carries: 16 ANSI colours plus foreground, background and
 * cursor. Without this the terminal would be the only surface in the app not
 * following the theme.
 *
 * The foreground and background written here double as the sentinels
 * [paletteSentinelsMatch] probes, which is why their derivation lives in
 * [terminalForeground] and [terminalBackground] rather than inline.
 */
private fun applyPalette(view: TerminalView, theme: ZedTheme) {
    val emulator = view.mEmulator ?: return
    val colors = emulator.mColors.mCurrentColors
    val names = listOf("black", "red", "green", "yellow", "blue", "magenta", "cyan", "white")
    for ((index, name) in names.withIndex()) {
        colors[index] = theme.color("terminal.ansi.$name", Color(colors[index])).toArgb()
        colors[index + 8] =
            theme.color("terminal.ansi.bright_$name", Color(colors[index + 8])).toArgb()
    }
    val background = terminalBackground(theme)
    colors[TextStyle.COLOR_INDEX_FOREGROUND] = terminalForeground(theme).toArgb()
    colors[TextStyle.COLOR_INDEX_BACKGROUND] = background.toArgb()
    colors[TextStyle.COLOR_INDEX_CURSOR] = theme.cursor.toArgb()
    view.setBackgroundColor(background.toArgb())
    view.invalidate()
}

/**
 * The link under a pointer or a finger, if the cell there is part of one.
 *
 * The event's pixel position becomes a column and a transcript-relative row
 * through the view's own arithmetic; the row's text — trailing blanks
 * trimmed, wide characters two cells — is then read once and the column
 * turned into a character index the detector understands. Zed does the same
 * dance from a grid point (hyperlinks.rs `find_from_grid_point`); the
 * detector itself is the pure part and has the tests.
 */
private fun linkAt(view: TerminalView, event: MotionEvent): TerminalLink? {
    val emulator = view.mEmulator ?: return null
    val (column, row) = view.getColumnAndRow(event, true)
    val screen = emulator.screen
    if (row < -screen.activeTranscriptRows || row >= emulator.mRows) return null
    val text = screen.rowText(row, emulator.mColumns)
    val index = indexOfColumn(text, column) ?: return null
    return findTerminalLink(text, index)
}

/**
 * Hand a URL to whatever the device opens them with — Zed's `cx.open_url`
 * (terminal_view.rs:1221). No browser is a state a phone can be in, and it
 * is said rather than thrown.
 */
private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Nothing on this device opens $url", Toast.LENGTH_SHORT).show()
    }
}

private fun toggleSoftKeyboard(context: Context, view: TerminalView) {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
    view.requestFocus()
    imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
}

private fun showSoftKeyboard(context: Context, view: TerminalView) {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
    imm.showSoftInput(view, 0)
}

/**
 * The view's side of the contract: what it may ask us, and the one place
 * workspace chords are stolen from the shell.
 */
private class SeekerTerminalViewClient(
    private val context: Context,
    private val sticky: StickyModifiers,
    private val currentHost: () -> TerminalSessionHost?,
    private val currentView: () -> TerminalView?,
    private val onFontScale: (Float) -> Unit,
    private val onEmulatorReady: () -> Unit,
    private val onOpenLink: (TerminalLink) -> Unit,
) : TerminalViewClient {

    override fun onScale(scale: Float): Float {
        // Called with the accumulated factor; apply it and reset, so the next
        // pinch starts from the new size rather than compounding.
        if (scale < 0.9f || scale > 1.1f) onFontScale(scale)
        return 1f
    }

    /**
     * The view has already taken focus by the time this runs; raise the IME.
     *
     * Unless it was Ctrl+click — Zed's `cmd-click` on a path or URL
     * (terminal_element.rs, `Event::Open` from `handle_mouse_up`), here with
     * the modifier every Linux keymap uses. A finger has no Ctrl key, so the
     * extra-key row's latched `ctrl` counts too: tap `ctrl`, then the path.
     */
    override fun onSingleTapUp(e: MotionEvent) {
        currentHost()?.clearBell()
        val ctrl = (e.metaState and AndroidKeyEvent.META_CTRL_ON) != 0 || sticky.ctrl
        if (ctrl) {
            sticky.clear()
            currentView()?.let { view -> linkAt(view, e)?.let(onOpenLink) }
            return
        }
        currentView()?.let { showSoftKeyboard(context, it) }
    }

    /**
     * Long-press on a path or a URL follows it; on anything else the vendored
     * view starts its text selection as before. Returning true is what keeps
     * the selection handles away from a link that has just been opened.
     */
    override fun onLongPress(event: MotionEvent): Boolean {
        val view = currentView() ?: return false
        val link = linkAt(view, event) ?: return false
        onOpenLink(link)
        return true
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    /**
     * Samsung's keyboard does not reset its state on `TYPE_NULL`, which is the
     * bug Termux works around with character-based input — and the owner's
     * device is a Samsung.
     */
    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: AndroidKeyEvent, session: TerminalSession): Boolean {
        // Typing is how you say you heard the bell. The keymap has had its
        // say by now — the workspace's key pass and the frame above this
        // view both run first — so whatever reaches here is the shell's.
        currentHost()?.clearBell()
        return false
    }

    override fun onKeyUp(keyCode: Int, e: AndroidKeyEvent): Boolean {
        // The latched modifiers are one-shot: the view read them while
        // handling the key-down, so this is the first safe moment to clear.
        sticky.clear()
        return false
    }

    override fun readControlKey(): Boolean = sticky.ctrl

    override fun readAltKey(): Boolean = sticky.alt

    override fun readShiftKey(): Boolean = sticky.shift

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        // The view has already folded the latched modifiers into ctrlDown, so
        // clearing here makes them one-shot for soft-keyboard input too.
        sticky.clear()
        currentHost()?.clearBell()
        return false
    }

    override fun onEmulatorSet() {
        onEmulatorReady()
    }

    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit
}
