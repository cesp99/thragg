package to.eyed.seeker.code.ui.shell.build

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import to.eyed.seeker.code.terminal.TerminalSessions
import to.eyed.seeker.code.ui.shell.Destination
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.terminal.TerminalDock
import to.eyed.seeker.code.ui.theme.LocalAppSettings

/**
 * Shell is a **mode of Build**, not a fourth destination.
 *
 * The bar has three stops and that is the specification, not an accident
 * (docs/UI.md, "Navigation"): a terminal is the escape hatch for everything
 * the three buttons do not cover — `solana config`, `anchor keys list`,
 * `apt install`, `solana logs` — and it is reached by the `⌗ Shell` chip in
 * the Build header, in the same Debian proot and the same working directory
 * the build uses. Back from Shell goes to Code, not to Build, because the two
 * are one destination; the mode is remembered, so coming back to ▶ comes back
 * to the terminal you left mid-command.
 *
 * Two sessions exist at most and neither is a tab: the interactive shell here,
 * born in the project root and reused forever
 * ([to.eyed.seeker.code.terminal.TerminalPanelState.openShell]), and the
 * transient one a task opens. There is no new/close/next/previous, and the
 * build itself owns no session at all — it runs through a pipe, because
 * cargo's JSON diagnostics do not survive a pty's line wrapping (see
 * [to.eyed.seeker.code.solana.build.BuildRunner]).
 */
object ShellModes {

    /**
     * Which projects are showing the terminal rather than the build controls,
     * keyed by project root.
     *
     * Per project and outside composition: switching destination, rotating the
     * phone or pushing a route must not drop you back into the build screen
     * while a `cargo install` you started is still printing.
     */
    private val shellByProject = mutableStateMapOf<String, Boolean>()

    fun isShell(projectRoot: String?): Boolean =
        projectRoot != null && shellByProject[projectRoot] == true

    fun set(projectRoot: String?, shell: Boolean) {
        projectRoot ?: return
        shellByProject[projectRoot] = shell
    }

    fun toggle(projectRoot: String?) {
        projectRoot ?: return
        set(projectRoot, !isShell(projectRoot))
    }

    /** A closed project takes its mode with it. */
    fun forget(projectRoot: String) {
        shellByProject.remove(projectRoot)
    }
}

/**
 * The terminal itself, hosted rather than rebuilt.
 *
 * `TerminalDock` is the inherited Termux emulator host with everything a touch
 * user needs already in it: the extra-keys row (GBoard has no Esc, Tab, Ctrl
 * or arrows, so without it the terminal cannot be driven at all), the ⌨
 * show/hide key that is the only way to get the keyboard back after dismissing
 * it to read scrollback, pinch-to-zoom, selection handles, and clickable
 * `path:line:col` output. All of that is reused verbatim; what this file adds
 * is where the session comes from and where a tapped path goes.
 */
@Composable
fun ShellTerminal(state: ShellState, projectRoot: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val terminals = remember(context) { TerminalSessions.of(context) }
    val settings = LocalAppSettings.current

    // On the main thread by construction: TerminalSession binds a Handler to
    // the calling thread's looper, and building one off the main thread throws
    // (TerminalSessionHost's own warning).
    LaunchedEffect(projectRoot) { terminals.openShell(projectRoot) }

    Box(modifier = modifier.fillMaxSize()) {
        if (terminals.active != null) {
            TerminalDock(
                state = terminals,
                cwd = projectRoot,
                fontSizeSp = settings.bufferFontSize.coerceIn(6f, 48f),
                // There is no keymap in this app and no `Terminal` context to
                // resolve against: every key a hardware keyboard sends belongs
                // to the shell, which is the whole point of a terminal.
                onKey = { false },
                // The dock's collapse button and its ▶ both mean the same thing
                // here — the terminal is a mode, and leaving it lands on the
                // build controls rather than closing anything.
                onHide = { ShellModes.set(projectRoot, false) },
                onSpawnTask = { ShellModes.set(projectRoot, false) },
                onFocusChanged = {},
                onOpenPath = { target ->
                    CodeJump.to(state, target.absolutePath, target.row, target.column)
                },
            )
        }
    }
}

/**
 * Opening a file in Code at a position — from a build error row, from a
 * `path:line:col` the terminal printed, and (P7) from a row in Problems.
 *
 * [ShellState.openPath] takes a path and nothing else, and a build error
 * without its line is a jump to the top of a 400-line file. So this is the
 * richer seam beside it: P2 registers [openAt] from the Code destination at
 * the same moment it registers `openPath`, and until it does, the path-only
 * route is used and the caret simply lands where the file was last left. Both
 * paths switch destination, because a tap on an error row that leaves you
 * looking at the log has not done what it promised.
 */
object CodeJump {

    /** Registered by the Code destination (P2). Row and column are 1-based. */
    var openAt: ((String, Int, Int) -> Unit)? = null

    fun to(state: ShellState, absolutePath: String, row: Int?, column: Int?) {
        val at = openAt
        if (at != null) {
            at(absolutePath, row ?: 0, column ?: 0)
        } else {
            state.openPath?.invoke(absolutePath) ?: return
        }
        state.show(Destination.Code)
    }
}
