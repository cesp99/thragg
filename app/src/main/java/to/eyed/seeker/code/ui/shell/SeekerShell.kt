@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package to.eyed.seeker.code.ui.shell

import android.content.Context
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import to.eyed.seeker.code.core.AgentSessions
import to.eyed.seeker.code.core.AppSettings
import to.eyed.seeker.code.core.CoreBridge
import to.eyed.seeker.code.core.ImportRequest
import to.eyed.seeker.code.core.IncomingFiles
import to.eyed.seeker.code.core.ProjectsRoot
import to.eyed.seeker.code.terminal.Userland
import to.eyed.seeker.code.terminal.UserlandInstaller
import to.eyed.seeker.code.terminal.UserlandState
import to.eyed.seeker.code.ui.git.AskpassDialog
import to.eyed.seeker.code.solana.toolchain.SolanaToolchain
import to.eyed.seeker.code.ui.shell.build.BuildBootstrap
import to.eyed.seeker.code.ui.shell.build.BuildScreen
import to.eyed.seeker.code.ui.shell.changes.ChangesScreen
import to.eyed.seeker.code.ui.shell.changes.DiffScreen
import to.eyed.seeker.code.ui.shell.changes.ProblemsScreen
import to.eyed.seeker.code.ui.shell.licences.LicenceDetailScreen
import to.eyed.seeker.code.ui.shell.licences.LicencesScreen
import to.eyed.seeker.code.ui.shell.projects.CloneScreen
import to.eyed.seeker.code.ui.shell.projects.NewProgramScreen
import to.eyed.seeker.code.ui.shell.projects.openProjectInShell
import to.eyed.seeker.code.ui.shell.settings.SettingsScreen
import to.eyed.seeker.code.ui.shell.setup.SetupScreen
import to.eyed.seeker.code.ui.agent.spettro.SpettroSettingsScreen
import to.eyed.seeker.code.ui.components.HairlineDivider
import to.eyed.seeker.code.ui.components.SeekerTopBar
import to.eyed.seeker.code.ui.theme.Durations
import to.eyed.seeker.code.ui.theme.LocalReduceMotion
import to.eyed.seeker.code.ui.workspace.NotificationHost
import to.eyed.seeker.code.ui.shell.agent.AgentScreen
import to.eyed.seeker.code.ui.shell.code.CodeScreen
import to.eyed.seeker.code.ui.workspace.Notifications

/**
 * The root of the portrait shell — the file that replaces WorkspaceScreen.kt.
 *
 * The whole structure is `Column { destination(weight = 1f); ShellNavBar }`
 * (docs/UI.md, "Navigation"). There is no title bar, no status bar, no tab
 * strip, no dock and no pane tree, and — deliberately, checkably — no `isWide`,
 * no `WideLayoutMinWidth`, no `DockMinWidth`, no `MinEditorWidth` and no
 * window-size-class branch anywhere in `ui/shell/`. This is a 400 x 890dp
 * phone; the answer to a wider window is the same single column.
 *
 * What this file owns is only what all three destinations share, which after
 * the demolition is a short list: the root focus and the hardware-key pass
 * over it, the ordered back handler, the toast host, the credential dialog,
 * files arriving from other apps, and the bar. Everything else belongs to a
 * destination, and P2, P3 and P4 land by replacing one call site each below.
 */
@Composable
fun SeekerShell(
    /**
     * settings.json, read once by the activity and re-read on every change.
     * The shell itself uses none of it — it is the editor's and the settings
     * screen's, and it is threaded through here so P2 and P8 do not have to
     * re-plumb MainActivity to reach it.
     */
    settings: AppSettings,
    /** The real file behind those settings, which Settings → "Edit settings.json" opens in Code. */
    settingsPath: String?,
    onSettingsChanged: (AppSettings) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * A file or text another app handed the activity — see [IncomingFiles].
     * Cleared through [onIncomingHandled] once the bytes are staged, so a
     * rotation does not import it twice.
     */
    incoming: ImportRequest? = null,
    onIncomingHandled: () -> Unit = {},
    /**
     * The retained state. Defaulted to the process-wide one; a parameter so a
     * preview or a test can host the shell on its own.
     */
    state: ShellState = ShellState.current,
) {
    val context = LocalContext.current
    /**
     * The shell's own focusable. Two jobs, both carried across from
     * WorkspaceScreen.kt: it is where the hardware-key pass hangs, and it is
     * where focus goes when a surface that had it leaves the composition.
     * Compose hands focus *nowhere* on its own in that case — measured on the
     * Fold, closing the terminal from the notification shade left every key
     * binding dead until something was tapped (WorkspaceScreen.kt:3588-3594).
     */
    val rootFocus = remember { FocusRequester() }
    val keySeenBeforeIme = remember { PreImeKey() }

    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    ShellBackHandler(state, rootFocus)

    /**
     * A tap on the agent's notification, from anywhere: go to Agent, which is
     * what the tap promised. The request is a counter, so a second tap on a
     * later notification is a second navigation (WorkspaceScreen.kt:3652-3657).
     *
     * The counter is never reset — [AgentSessions] is process-wide and a
     * `requestPanel` from an hour ago still reads as `3`. So what is compared
     * is the value this process has already *answered*, held outside the
     * composition for exactly the same reason [ShellState] is: an activity
     * recreation builds a new composition over the old counter, and a
     * `LaunchedEffect` keyed on a stale `3` would drag the user off Code and
     * onto Agent every single time. Code is the start destination and it stays
     * the start destination (docs/UI.md, "The design chosen").
     */
    LaunchedEffect(AgentSessions.openPanelRequest) {
        val request = AgentSessions.openPanelRequest
        if (!answersPanelRequest(request, agentPanelRequestAnswered)) return@LaunchedEffect
        agentPanelRequestAnswered = request
        state.show(Destination.Agent)
    }

    ShellBootstrap(state)

    // The engine runs Debian's git through proot for status and cannot guess
    // where either lives. Keyed on the installer's state, not on `Unit`: the
    // userland can be installed and removed while the app runs, and told once
    // at startup the engine keeps pointing at whatever was true then. The
    // engine's `set_userland` is idempotent on purpose — do not "optimise"
    // that away, an earlier version restarted the askpass server on every
    // repeat and left GIT_ASKPASS pointing at nothing, which is what makes
    // [AskpassDialog] below reachable at all (WorkspaceScreen.kt:1352-1367).
    LaunchedEffect(UserlandInstaller.state) {
        withContext(Dispatchers.IO) { syncUserlandWithEngine(context) }
    }

    ImportIncoming(state, incoming, onIncomingHandled)

    Box(
        modifier = modifier
            .fillMaxSize()
            // Bare, with no Zed read in front of it: the shell is the
            // Material half, and `background` *is* `editor.background` — the
            // bridge maps it there (MaterialBridge.kt, BAND A). A
            // `theme.color("editor.background", …)` here was a Zed read with
            // an M3 fallback that could never fire.
            .background(MaterialTheme.colorScheme.background)
            .focusRequester(rootFocus)
            .focusable()
            // A modifier chord is taken here, before the soft keyboard sees the
            // key at all. Gboard otherwise answers Ctrl+Backspace — and, with a
            // selection on screen, Ctrl+Z — through the editor's
            // InputConnection with an idea of its own, and the editor never
            // hears the chord (Keymap.kt:45-59, `Keystroke.beforeIme`, which
            // this carries across without the keymap that file was built for:
            // there is no keymap in this app any more, only the nine bindings
            // hard-coded in EditorInput.kt).
            .onPreInterceptKeyBeforeSoftKeyboard { event ->
                val takes = takesBeforeIme(
                    isKeyDown = event.type == KeyEventType.KeyDown,
                    ctrl = event.isCtrlPressed,
                    alt = event.isAltPressed,
                )
                if (!takes) return@onPreInterceptKeyBeforeSoftKeyboard false
                keySeenBeforeIme.event = event.nativeKeyEvent
                state.onHardwareKey?.invoke(event) == true
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Refused above and passed on to the keyboard: skip it rather
                // than resolve the same physical key twice.
                if (event.nativeKeyEvent === keySeenBeforeIme.event) {
                    keySeenBeforeIme.event = null
                    return@onPreviewKeyEvent false
                }
                state.onHardwareKey?.invoke(event) == true
            },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // The status bar and any cutout, once, above whatever the
                    // destination draws as its 56dp top bar. The bottom is the
                    // bar's (ShellNavBar), and the IME is the editor's — it
                    // lifts its own action row onto the keyboard and must not
                    // be padded off it from up here (EditorPane.kt:2709).
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                        )
                    ),
            ) {
                val stack = state.currentStack
                val reduceMotion = LocalReduceMotion.current
                AnimatedContent(
                    targetState = ShellSurface(state.destination, stack.top, stack.depth),
                    // The depth is in the state so the transition can read the
                    // direction off it, but it is not in the key: two surfaces
                    // showing the same screen are the same surface, and keying
                    // on depth would replay an animation over identical pixels.
                    contentKey = { surface -> surface.destination to surface.route },
                    transitionSpec = { surfaceTransition(reduceMotion) },
                    // Clipped, because a slide is by definition drawn partly
                    // outside its slot, and the row above this box is the
                    // status-bar inset a travelling screen must not cross.
                    modifier = Modifier.clipToBounds(),
                    label = "shell-surface",
                ) { surface ->
                    // Every surface is opaque over the shell background. The
                    // screens themselves don't paint one — composed directly
                    // into the shell they never needed to — but two of them
                    // overlap mid-slide, and a transparent screen sliding over
                    // another is two screens interleaved, not a transition.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    ) {
                        val route = surface.route
                        if (route == null) {
                            // The three destinations, all real: P2's editor,
                            // P3's conversation and P4's build runner. There
                            // is no placeholder left in this file.
                            when (surface.destination) {
                                Destination.Code -> CodeScreen(state, settings, settingsPath, onSettingsChanged)
                                Destination.Agent -> AgentScreen(state)
                                Destination.Build -> BuildScreen(state)
                            }
                        } else {
                            RouteHost(route, state, settings, settingsPath, onSettingsChanged)
                        }
                    }
                }
                // The toast stack, over everything in the destination and under
                // nothing: a failure has to be readable with a sheet or a route
                // on screen. At the bottom, above the nav bar, within a thumb's
                // reach — dismissing one is a tap (NotificationHost.kt). There
                // is no wide layout to place it for, so the wide branch is
                // answered once, here, with false.
                NotificationHost(
                    stack = Notifications,
                    isWide = false,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            ShellNavBar(state)
        }
    }

    // A credential question from any git command that can ask one — a fetch,
    // a pull, a push, a clone. At the root so it sits above whichever surface
    // is on screen; it shows itself only while one is running and has asked
    // (WorkspaceScreen.kt:5043).
    AskpassDialog()
}

/**
 * What the shell is showing: the destination, whatever route is over it, and
 * how deep that route sits.
 *
 * One value class where [SeekerShell] used to read `state` twice, because
 * [AnimatedContent] holds the *previous* one of these while the next animates
 * in — and the previous destination, route and depth are exactly the three
 * facts the transition needs and the live `state` no longer has.
 */
private data class ShellSurface(
    val destination: Destination,
    val route: Route?,
    val depth: Int,
)

/**
 * How one [ShellSurface] becomes another — the answer to "where did this
 * screen come from", which is the question an instant swap leaves the user to
 * reconstruct from memory.
 *
 * Three motions, one per kind of navigation, so the motion *is* the map:
 *
 *  - **Push** (deeper on the same tab — a diagnostics chip, a changed file, a
 *    settings row): the new screen slides in from the right and the old one
 *    gives way leftward at a third of the speed, which is the depth cue every
 *    stacked navigation on the platform uses. Back will reverse it, and the
 *    user has been shown which edge it will come from.
 *  - **Pop** (the ← or the gesture): the same motion backwards — the top
 *    screen leaves to the right, the parent settles back from the left.
 *  - **Tab switch**: a fade-through, deliberately without direction. The
 *    three destinations are siblings, not a stack (docs/UI.md,
 *    "Navigation"); a slide here would promise a spatial order the back
 *    gesture does not honour.
 *
 * Reduce-motion swaps instantly: the destination is the information, the
 * travel is the decoration (see [LocalReduceMotion]) — and this is the one
 * animation in the app that moves the *entire screen*, so it is the first
 * one the setting exists for.
 */
private fun AnimatedContentTransitionScope<ShellSurface>.surfaceTransition(
    reduceMotion: Boolean,
): ContentTransform {
    val transition = if (reduceMotion) {
        fadeIn(snap()) togetherWith fadeOut(snap())
    } else if (targetState.destination != initialState.destination) {
        fadeIn(tween(Durations.BAND_IN)) togetherWith fadeOut(tween(Durations.BLOCK_FADE))
    } else if (targetState.depth > initialState.depth) {
        (slideInHorizontally(tween(Durations.ROUTE)) { width -> width })
            .togetherWith(
                slideOutHorizontally(tween(Durations.ROUTE)) { width -> -width / PARALLAX } +
                    fadeOut(tween(Durations.ROUTE)),
            )
    } else {
        (slideInHorizontally(tween(Durations.ROUTE)) { width -> -width / PARALLAX } +
            fadeIn(tween(Durations.ROUTE)))
            .togetherWith(slideOutHorizontally(tween(Durations.ROUTE)) { width -> width })
    }
    // Deeper draws on top, always. Each surface keeps the z it was given on
    // entry, so this one line covers both directions: a pushed route slides
    // in *over* its parent, and on pop that same route — still carrying the
    // higher z — slides away over the parent being revealed, rather than the
    // parent smearing across the top of it.
    transition.targetContentZIndex = targetState.depth.toFloat()
    return transition
}

/**
 * The give-way fraction: the covered screen travels a third of the width the
 * covering one does. Enough that the parent visibly *moves aside* — a parent
 * that stood still would read as the new screen merely lying on top, and back
 * would be a guess again — but not so much that it appears to leave, because
 * it hasn't: it is one ← away.
 */
private const val PARALLAX = 3

/**
 * The three things that have to be true before the first frame is *useful*,
 * none of which any single chunk could do from inside its own destination.
 *
 * Every one of them is a handoff the parallel chunks wrote down and could not
 * apply themselves, because the file they needed edited is this one:
 *
 *  1. **The build seam.** `BuildBootstrap.install` is what fills P2's
 *     `CodeBuildSeam`, and P4 could only call it from the Build destination's
 *     own composition — so the editor's `▶` did nothing until Build had been
 *     visited once, which is precisely the order nobody uses.
 *  2. **`toolchainReady`.** Four screens read it (Code's `▶`, Build's three
 *     buttons, Projects → Toolchain, Settings → Toolchain) and only
 *     SetupScreen ever wrote it, so a cold start with a fully installed
 *     toolchain said "not installed" and `▶` pushed Setup over a toolchain
 *     that was already there. [SolanaToolchain.isReady] stats the rootfs, so
 *     it goes off the main thread. And when it answers *no*, the same effect
 *     puts the gate up ([ShellState.gate]): Setup over Code, with no Skip,
 *     until the required components are in.
 *  3. **The last project.** `ProjectsRoot.lastProject` returns null on a fresh
 *     install — the honest first-run state the shell draws — but on the second
 *     launch it names the project you were in, and nothing was reading it.
 *     Without this, every launch is "No project is open" no matter how much
 *     work is on disk. Opening one is blocking (it starts the engine's scan),
 *     and it is skipped entirely once anything has set [ShellState.project],
 *     so a share or a notification tap that arrives first still wins.
 *
 * This is a smaller thing than P9's SessionRestore, which owns the caret, the
 * scroll and the open buffers across process death; what is here is only the
 * root, which is what the difference between an empty app and a working one
 * turns on.
 */
@Composable
private fun ShellBootstrap(state: ShellState) {
    val context = LocalContext.current
    LaunchedEffect(state) {
        // Synchronous and first: `▶` is reachable on the very first frame and
        // installing a lambda costs nothing.
        BuildBootstrap.install(state, context)
        val ready = withContext(Dispatchers.IO) {
            runCatching { SolanaToolchain.isReady(context) }.getOrDefault(false)
        }
        state.toolchainReady = ready
        // The gate. Everything past this line is the app; with no toolchain
        // there is no Build, no program that compiles, no deploy, and an
        // agent that can only read — which is not the product. Setup goes up
        // over Code and stays there until the required rows are in
        // (docs/UI.md, "First run"). The last project is still restored
        // underneath it, so Continue lands on real work.
        if (!ready) state.gate()
        if (state.project != null) return@LaunchedEffect
        val path = withContext(Dispatchers.IO) { ProjectsRoot.lastProject(context) }
            ?: return@LaunchedEffect
        // Between the two suspension points a share, a notification or the
        // Projects sheet may have opened one; the last writer must not be this.
        if (state.project != null) return@LaunchedEffect
        // `switching = false`: reopening the last project is not a switch.
        // There is nothing open to tear down, and `ShellState.reset()` would
        // force the destination back to Code — which sounds harmless right up
        // until you notice this is a *suspending* call that lands a second or
        // two into the session, long after the agent's notification has asked
        // for Agent and long after a thumb can have reached the bar.
        openProjectInShell(context, state, path, switching = false)
    }
}

/**
 * The `openPanelRequest` this process has already acted on.
 *
 * A plain top-level var, outside the composition and outside [ShellState]: it
 * is not drawn, nothing observes it, and its whole job is to be *older* than
 * the composition that reads it. See the effect in [SeekerShell].
 */
private var agentPanelRequestAnswered = 0

/**
 * Whether an `openPanelRequest` of [request] is one this process still owes an
 * answer to, given it has already answered [answered].
 *
 * A value function for the same reason [takesBeforeIme] is one: the rule is
 * three tokens long and the bug it fixes cost a device round trip to see. Zero
 * is "nobody has asked", and equality is "asked, and already taken there" — a
 * new composition over an old counter, which is what an activity recreation
 * is, must not move the user off the start destination.
 */
internal fun answersPanelRequest(request: Int, answered: Int): Boolean =
    request != 0 && request != answered

/**
 * Files and text another app shared into Seeker.
 *
 * Carried across from WorkspaceScreen.kt:1323-1345 with its two orderings
 * intact, because both were paid for: the bytes are **staged first**, before
 * any question is asked, since the read grant on a `content://` URI is the
 * sender's to revoke and a dialog would spend it; and [onIncomingHandled] is
 * called **last**, because it nulls the key this effect is launched on and
 * cancelling the effect before the copy would lose the share.
 */
@Composable
private fun ImportIncoming(
    state: ShellState,
    incoming: ImportRequest?,
    onIncomingHandled: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(incoming) {
        val request = incoming ?: return@LaunchedEffect
        val staged = withContext(Dispatchers.IO) { IncomingFiles.stage(context, request) }
        val files = staged.getOrNull()
        if (files == null) {
            Notifications.error(
                staged.exceptionOrNull()?.message ?: "The shared file could not be read",
                key = "import",
            )
            onIncomingHandled()
            return@LaunchedEffect
        }
        // A cold start through a share is still opening its project; wait for
        // it rather than racing it. Nothing to wait for means nothing is open,
        // and Scratch is where the file goes.
        val open = withTimeoutOrNull(5_000L) {
            snapshotFlow { state.project }.filterNotNull().first()
        }
        val root = withContext(Dispatchers.IO) {
            open?.rootPath?.let(::File) ?: ProjectsRoot.scratch(context)
        }
        val landed = withContext(Dispatchers.IO) {
            files.map { file -> IncomingFiles.place(root, file.name, file) }
        }
        val failed = landed.firstOrNull { it.isFailure }
        if (failed != null) {
            // A toast rather than a dialog: some of the files may have landed,
            // and a modal over the ones that did would be a demand for
            // acknowledgement of a result the user can already see.
            Notifications.error(
                failed.exceptionOrNull()?.message ?: "The file could not be added",
                key = "import",
            )
        }
        val paths = landed.mapNotNull { it.getOrNull() }
        val openPath = state.openPath
        if (paths.isNotEmpty() && open != null && openPath != null) {
            state.show(Destination.Code)
            paths.forEach { relative -> openPath(File(root, relative).absolutePath) }
        } else if (paths.isNotEmpty()) {
            // Nowhere to open it *into* yet: no project, or Code has not
            // composed once. Say where the bytes went rather than swallow them.
            Notifications.info("Added to ${root.name}: ${paths.joinToString()}", key = "import")
        }
        onIncomingHandled()
    }
}

/**
 * Tell the engine where the userland is, or that there is none.
 *
 * A deliberate copy of `WorkspaceScreen.kt:6022`, not a call into it: that
 * file is deleted whole in P10 and the shell would go with it. Twelve lines
 * and no state, so the duplicate costs nothing while both hosts exist.
 *
 * The engine runs Debian's git through proot for status and cannot guess where
 * either lives; `set_userland` is idempotent on the engine side on purpose
 * (guest.rs), because this runs more than once per process. Do not "optimise"
 * that away — an earlier version restarted the askpass server on every repeat,
 * and the old server's Drop took the new one's helper script with it, leaving
 * GIT_ASKPASS pointing at nothing and credential prompts unable to reach the
 * dialog.
 */
private fun syncUserlandWithEngine(context: Context) {
    if (Userland.backend.state(context) !is UserlandState.Ready) {
        CoreBridge.clearUserland()
        return
    }
    CoreBridge.setUserland(
        File(context.applicationInfo.nativeLibraryDir, "libproot_exec.so").absolutePath,
        File(context.filesDir, "debian").absolutePath,
        context.cacheDir.absolutePath,
        File(context.filesDir, "projects").absolutePath,
    )
}

/**
 * Whether the pre-IME pass takes this key, as a value function so the rule is
 * checkable on the host — `android.view.KeyEvent` is a stub in a JVM test and
 * cannot be built, so the *decision* is separated from the event.
 *
 * The rule is `Keystroke.beforeIme` (Keymap.kt:45-59) carried across without
 * the keymap it was written for. A hardware key reaches the IME first on
 * Android, and Gboard has ideas of its own about the modifier chords: it
 * answers Ctrl+Backspace by deleting a "word" through its own
 * `InputConnection`, and never lets the editor see the key. So every chord
 * with Ctrl or Alt down is claimed here first, and only what no one claims
 * goes on to the keyboard. Bare keys and shifted ones stay on the ordinary
 * path — typing, Enter, Tab and Backspace are the IME's to see first, and it
 * forwards them.
 */
internal fun takesBeforeIme(isKeyDown: Boolean, ctrl: Boolean, alt: Boolean): Boolean =
    isKeyDown && (ctrl || alt)

/**
 * The key event the shell's pre-IME pass last took up.
 *
 * A plain holder rather than snapshot state: it lives for one event and
 * nothing draws it (WorkspaceScreen.kt:6035-6042). Identity comparison is the
 * point — the same `android.view.KeyEvent` instance comes back through the
 * ordinary pass when no one claimed it before the keyboard.
 */
internal class PreImeKey {
    var event: AndroidKeyEvent? = null
}

/**
 * A full-screen route. All seven are real; there is no placeholder body left.
 *
 * What this frame gives every one of them is the part they share: a ← in the
 * route's own top row (the bar below stays, so ← is *not* the only way back —
 * the gesture pops the same stack), the route's title beside it, and the route
 * drawn over the destination it was pushed from rather than replacing it.
 * Anything a route needs *in* that row — Changes' branch chip, Problems'
 * filter — it draws as its own strip under this one, so this file stays a
 * frame and does not grow a widget per route.
 */
@Composable
private fun RouteHost(
    route: Route,
    state: ShellState,
    settings: AppSettings,
    settingsPath: String?,
    onSettingsChanged: (AppSettings) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (!route.ownsItsBar) {
            // The shared bar, and it is a **transitional** one. A route's top
            // bar is the route's own — Changes needs a branch chip in it,
            // Problems a pair of counts under the title, Licences a subtitle —
            // so the end state is that every screen draws its own
            // [SeekerTopBar] and this frame draws none. Until the screens that
            // are still being converted have theirs, they get this: the same
            // component, with the title the route already carries and a back
            // arrow, so no route is ever *unbarred* and no route is ever
            // barred twice. A chunk lands its own bar and adds its route to
            // [ownsItsBar]; when the list holds all of them, this branch and
            // `Route.title` go with it.
            SeekerTopBar(title = route.title, onBack = { state.pop() })
            HairlineDivider()
        }
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            // One branch per route, and every one of them is a real screen.
            when (route) {
                is Route.Changes -> ChangesScreen(state)

                is Route.Diff -> DiffScreen(state, route)

                is Route.Problems -> ProblemsScreen(state)

                is Route.Settings -> SettingsScreen(
                    state = state,
                    settings = settings,
                    settingsPath = settingsPath,
                    onSettingsChanged = onSettingsChanged,
                )

                is Route.NewProgram -> NewProgramScreen(state)

                is Route.Clone -> CloneScreen(state)

                is Route.Setup -> SetupScreen(state)

                // The screen itself lives with the Spettro sheets it grew out
                // of; the shell only frames it, exactly as it frames Settings.
                is Route.SpettroSettings -> SpettroSettingsScreen(state)

                is Route.Licences -> LicencesScreen(state)

                // The detail carries its own id; it does not need the shell,
                // because a licence text is the same text whatever else is
                // going on and nothing on it can navigate anywhere but back.
                is Route.LicenceDetail -> LicenceDetailScreen(route.id)
            }
        }
    }
}

/**
 * Whether this route draws its own [SeekerTopBar] and must not be given the
 * shared one.
 *
 * Three so far — the three this pass converted. Each of them needs something
 * in the bar that a frame cannot know: Changes puts the branch chip and the
 * remote counts there, Diff titles itself with the file and subtitles itself
 * with the directory, Problems carries `2 errors · 5 warnings` under its
 * title. Add a route here in the same commit that gives it a bar, never
 * before: this list is the only thing keeping the two halves of the migration
 * from drawing two bars or none.
 */
private val Route.ownsItsBar: Boolean
    get() = this is Route.Changes || this is Route.Diff || this is Route.Problems ||
        this is Route.Setup

/** What a route's own top row prints, for the ones still on the shared bar. */
private val Route.title: String
    get() = when (this) {
        is Route.Changes -> "Changes"
        is Route.Diff -> path.substringAfterLast('/')
        is Route.Problems -> "Problems"
        is Route.Settings -> "Settings"
        is Route.Licences -> "Licences"
        // Carried on the route rather than looked up: a title that had to read
        // a 260 KB asset to print itself would arrive a frame late.
        is Route.LicenceDetail -> name
        is Route.NewProgram -> "New program"
        is Route.Clone -> "Clone"
        is Route.Setup -> "Set up the toolchain"
        is Route.SpettroSettings -> "Account & models"
    }
