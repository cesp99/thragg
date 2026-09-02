package to.eyed.seeker.code.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEvent
import to.eyed.seeker.code.core.ProjectSession

/**
 * Everything the shell knows, held **outside the composition**.
 *
 * Same reason UserlandInstaller.kt lives outside it, and the same shape: a
 * rotation, a split-screen resize or a theme change tears the composition down
 * and builds it again, and anything kept in a `remember` goes with it. What
 * would be lost here is not a cached value — it is which destination you were
 * on, what you had pushed over it, and the build that is still running. The
 * spec makes that a requirement in as many words: "Rotation must not lose the
 * caret, the scroll, the log, the terminal session or the sheet that is open"
 * (docs/UI.md, "Orientation").
 *
 * It is a class with one process-wide instance rather than an `object` so that
 * a test can build a fresh one; [current] is the instance the app runs on, and
 * it is created on first touch and never replaced. `rememberSaveable` is not
 * the answer to any of this — it survives process death, which is P9's job
 * (WorkspaceSession.kt), and it does not survive being *removed from the
 * composition*, which is what a destination switch does.
 *
 * The state is ordinary Compose state, so the UI observes it directly, exactly
 * as UserlandInstaller's is.
 *
 * The seams marked "registered by" are how a destination that this file must
 * not know about takes part in the ordered back handler. The shell owns the
 * *order* (ShellBackHandler.kt); the editor owns what "dismiss the completion
 * menu" means. A destination sets its lambda when it enters the composition
 * and clears it when it leaves; a null one is read as "nothing to dismiss",
 * which is exactly right for a destination that is not on screen.
 */
class ShellState {

    // ---- Where you are ------------------------------------------------------

    /**
     * The destination the bar has selected. Code is the start destination
     * because it is the only one that degrades honestly with no agent, no
     * network and no toolchain (docs/UI.md, "The design chosen").
     */
    var destination: Destination by mutableStateOf(Destination.Code)
        private set

    /** The three independent stacks — see [RouteStacks]. */
    var routes: RouteStacks by mutableStateOf(RouteStacks())
        private set

    /**
     * Bumped when the bar's *current* destination is tapped again, which the
     * destination reads as "scroll to top / to the newest message / to the end
     * of the log" (docs/UI.md, "Navigation"). A counter rather than a flag: two
     * taps mean two scrolls, and a flag would have to be cleared by whoever
     * consumed it.
     */
    var retapCount: Int by mutableStateOf(0)
        private set

    /** The stack the back handler and the route host are looking at. */
    val currentStack: RouteStack get() = routes[destination]

    /**
     * Select a destination, or re-select the one already showing.
     *
     * Switching never touches the stacks: each destination keeps whatever it
     * had pushed over it, which is what makes leaving Agent to look something
     * up in Code and coming back a free action.
     */
    fun show(destination: Destination) {
        if (this.destination == destination) {
            retapCount++
            return
        }
        this.destination = destination
        // The badge answered its question by being followed. Cleared here
        // rather than in the Agent screen so that arriving from the bar, from
        // a notification tap and from a "Fix with agent" all clear it.
        if (destination == Destination.Agent) agentAttention = false
    }

    /** Push a full-screen route onto a destination's stack — the current one by default. */
    fun push(route: Route, on: Destination = destination) {
        routes = routes.push(on, route)
    }

    /**
     * Pop the current destination's stack. Returns false when it was already
     * empty, which is how the back handler knows to fall through to its next
     * step rather than swallowing the gesture.
     */
    fun pop(): Boolean {
        val stack = currentStack
        if (stack.isEmpty) return false
        routes = routes.with(destination, stack.pop())
        return true
    }

    // ---- What the badges say ------------------------------------------------

    /**
     * The state of the last or current build, test or deploy — the single
     * source both the ▶ nav badge and the editor action row's ▶ key read
     * (docs/UI.md, P4). Written by solana/build/BuildRunner.kt; the shell only
     * paints it.
     */
    var build: BuildState by mutableStateOf(BuildState.Idle)

    /**
     * Whether the agent finished, or is blocked on a permission, while you
     * were somewhere else. Cleared by [show] when Agent is reached.
     */
    var agentAttention: Boolean by mutableStateOf(false)

    // ---- What the three destinations share ----------------------------------

    /**
     * The open project. One at a time, for the whole app: the editor, the
     * conversation and the build all speak about the same root, and there is
     * no multi-root worktree on this device.
     *
     * P1 never sets this — restoring the last project is P9's SessionRestore
     * and opening one is P8's Projects sheet. It lives here because all three
     * destinations read it, and a null one is the honest first-run state:
     * empty Code with the Projects sheet over it (docs/UI.md, "First run").
     */
    var project: ProjectSession? by mutableStateOf(null)

    /**
     * Whether the Solana toolchain is installed. The one boolean where P4 and
     * P5 meet: with it false, Build's three buttons are replaced by a single
     * "Set up the toolchain" card and pressing ▶ pushes [Route.Setup] instead
     * of failing (docs/UI.md, "First run", step 8).
     */
    var toolchainReady: Boolean by mutableStateOf(false)

    /**
     * Whether the Setup takeover on top of the current stack is the *gate* —
     * the mandatory first-run one — rather than the toolchain page reached
     * from Settings after the install. It is the same route; what makes it a
     * gate is only that the toolchain is not in. The back handler and the
     * screen's own actions both read this, so a Setup reached from Settings
     * with everything installed still has a Close and a back arrow.
     */
    val isGated: Boolean get() = currentStack.top is Route.Setup && !toolchainReady

    /**
     * Put the gate up: Setup on top of Code, and Code selected. Called by the
     * shell's bootstrap when the toolchain is missing, and a no-op when the
     * gate is already showing — a rotation must not stack two.
     */
    fun gate() {
        if (destination != Destination.Code) show(Destination.Code)
        if (currentStack.top !is Route.Setup) push(Route.Setup, on = Destination.Code)
    }

    /**
     * Open a file in Code — registered by the Code destination (P2).
     *
     * Everything that navigates to a line of source goes through here: a build
     * error row, a diagnostic in Problems, a `path:line:col` in the terminal,
     * a file the user shared into the app. Null before Code has composed once,
     * and the callers all treat that as "not yet".
     */
    var openPath: ((String) -> Unit)? by mutableStateOf(null)

    // ---- The seams the back handler reads ------------------------------------

    /**
     * Step 1: a completion menu, hover card, selection toolbar or code-action
     * popup is showing → dismiss it. Registered by the editor host (P2), which
     * is the only code that knows those four exist.
     */
    var overlaySeam: BackSeam? by mutableStateOf(null)

    /**
     * Step 4: the find bar is deployed → close it *and clear the match
     * highlights*. The second half is not optional — WorkspaceScreen.kt:3634
     * calls `clearSearchMatches()` before dismissing, and leaving it out is how
     * a closed find bar leaves a buffer painted yellow.
     */
    var findBarSeam: BackSeam? by mutableStateOf(null)

    /**
     * Step 6: pop one entry off the jump stack, restoring file, caret and
     * scroll. This is OpenFiles.kt's `NavHistory` (OpenFiles.kt:99), kept —
     * following a go-to-definition and pressing back is the gesture this whole
     * step exists for.
     */
    var jumpSeam: BackSeam? by mutableStateOf(null)

    /**
     * A hardware key that reached the shell — see the pre-IME pass in
     * SeekerShell.kt. There is no keymap behind this: the nine bindings a
     * paired Bluetooth keyboard gets are hard-coded in EditorInput.kt
     * (docs/UI.md, "Navigation"), and this seam exists so a chord Gboard would
     * otherwise eat can be offered to whoever is on screen. Returns true when
     * the event was consumed.
     */
    var onHardwareKey: ((KeyEvent) -> Boolean)? by mutableStateOf(null)

    // ---- Sheets --------------------------------------------------------------

    /**
     * The modal bottom sheets that are open, oldest first.
     *
     * A sheet registers itself from [SheetScaffold] rather than being named
     * here, so the shell can order back around sheets it knows nothing about —
     * Files & Find, Cluster, Commit, the ⋮ overflow — without a `when` over an
     * enum that every later chunk would have to edit.
     */
    private val openSheets = mutableStateListOf<SheetHandle>()

    val sheetCount: Int get() = openSheets.size

    fun sheetOpened(handle: SheetHandle) {
        if (openSheets.none { it === handle }) openSheets.add(handle)
    }

    fun sheetClosed(handle: SheetHandle) {
        openSheets.removeAll { it === handle }
    }

    /**
     * Step 2: ask the topmost sheet to close. It calls the sheet's own dismiss
     * rather than removing the entry, so the sheet animates out through the
     * same path a tap on the scrim takes; the entry leaves when the sheet does.
     */
    fun dismissTopSheet(): Boolean {
        val top = openSheets.lastOrNull() ?: return false
        top.dismiss()
        return true
    }

    /** Everything a closed project takes with it. */
    fun reset() {
        routes = routes.clear()
        destination = Destination.Code
        build = BuildState.Idle
        agentAttention = false
        project = null
    }

    companion object {
        /**
         * The instance the app runs on. A `val` on the companion rather than an
         * `object` so tests build their own; created once per process, which is
         * exactly the lifetime a rotation does not touch.
         */
        val current: ShellState = ShellState()
    }
}

/** One open sheet: an identity to stack on, and the way to close it. */
class SheetHandle(val dismiss: () -> Unit)

/**
 * What the ▶ nav item is saying, and what the editor's ▶ key does when
 * pressed (docs/UI.md, "Navigation" — badges).
 *
 * This is the app's most important cross-destination signal: a 71-second build
 * is a build you walk away from, so its outcome has to be legible from Code
 * and from Agent, not only from the screen that started it.
 */
sealed interface BuildState {

    /** Nothing has run this session, or the last result has been read and aged out. */
    data object Idle : BuildState

    /**
     * A build, test or deploy is running: an animated ring on the badge, and a
     * second press of ▶ is Stop rather than a second build (docs/UI.md, P4).
     * [label] is what the elapsed row prints ("Building", "Testing").
     */
    data class Running(val label: String, val startedAt: Long) : BuildState

    /** The last run failed: a red dot, until the next run starts. */
    data class Failed(val errors: Int, val warnings: Int) : BuildState

    /**
     * The last run succeeded at [at] (`System.currentTimeMillis`). A green tick
     * for [SUCCESS_TICK_MS] and then nothing — a permanent tick is decoration,
     * and the ten seconds are there for the walk back to the phone.
     */
    data class Succeeded(val at: Long) : BuildState

    companion object {
        /** "a green tick for 10 s after a success" — docs/UI.md, "Navigation". */
        const val SUCCESS_TICK_MS = 10_000L
    }
}

/** What the badge slot on the ▶ item draws. */
enum class BuildBadge { None, Ring, Failed, Tick }

/**
 * The badge for [state] at time [now], as a pure function so the ten-second
 * tick is a property a test can pin rather than a `delay` in a composable.
 */
fun buildBadge(state: BuildState, now: Long): BuildBadge = when (state) {
    is BuildState.Idle -> BuildBadge.None
    is BuildState.Running -> BuildBadge.Ring
    is BuildState.Failed -> BuildBadge.Failed
    is BuildState.Succeeded ->
        if (now - state.at < BuildState.SUCCESS_TICK_MS) BuildBadge.Tick else BuildBadge.None
}
