package to.eyed.seeker.code.ui.shell

import android.content.Context
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.workspace.NotificationHost
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
    val theme = LocalZedTheme.current
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
     */
    LaunchedEffect(AgentSessions.openPanelRequest) {
        if (AgentSessions.openPanelRequest == 0) return@LaunchedEffect
        state.show(Destination.Agent)
    }

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
            .background(theme.color("editor.background", MaterialTheme.colorScheme.background))
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
                    // destination draws as its 44dp header. The bottom is the
                    // bar's (ShellNavBar), and the IME is the editor's — it
                    // lifts its own action row onto the keyboard and must not
                    // be padded off it from up here (EditorPane.kt:2709).
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                        )
                    ),
            ) {
                val route = state.currentStack.top
                if (route == null) {
                    // The three destinations. P2, P3 and P4 land by replacing
                    // exactly one of these lines each.
                    when (state.destination) {
                        Destination.Code -> CodeDestination(state)
                        Destination.Agent -> AgentDestination(state)
                        Destination.Build -> BuildDestination(state)
                    }
                } else {
                    RouteHost(route, state)
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

// ---- Placeholder destinations -------------------------------------------------
//
// P1 ships the host, not the screens. These three exist so that P2, P3 and P4
// can land in parallel against a shell that already navigates, already handles
// back, already hides its bar for the keyboard and already has somewhere to
// put a toast — each of them replaces one line in the `when` above and deletes
// one function here.

@Composable
private fun CodeDestination(state: ShellState) {
    Placeholder(
        title = "Code",
        detail = "The editor lands here (P2): header, buffer, file bar, find bar.",
        state = state,
    )
}

@Composable
private fun AgentDestination(state: ShellState) {
    Placeholder(
        title = "Agent",
        detail = "The ACP conversation lands here (P3).",
        state = state,
    )
}

@Composable
private fun BuildDestination(state: ShellState) {
    Placeholder(
        title = "Build",
        detail = "Cluster, wallet, program id, log and the three buttons land here (P4).",
        state = state,
    )
}

/**
 * What a destination that does not exist yet says, and the one thing it does:
 * pushing a route, so that the back handler's step 5 is reachable by hand on a
 * device before any real route exists.
 */
@Composable
private fun Placeholder(title: String, detail: String, state: ShellState) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = theme.color("text", MaterialTheme.colorScheme.onSurface),
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "Open Settings",
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .padding(top = 16.dp)
                .clickable { state.push(Route.Settings) },
        )
    }
}

/**
 * A full-screen route, until the chunks that own them land: Changes, Diff and
 * Problems are P7's, Settings, New program and Clone are P8's, Setup is P5's.
 *
 * What is real here is the frame every one of them keeps: a ← in the route's
 * own top row (the bar below stays, so ← is *not* the only way back — the
 * gesture pops the same stack), and the route drawn over the destination it
 * was pushed from rather than replacing it.
 */
@Composable
private fun RouteHost(route: Route, state: ShellState) {
    val theme = LocalZedTheme.current
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "←",
                style = MaterialTheme.typography.titleMedium,
                color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                modifier = Modifier
                    .clickable { state.pop() }
                    .padding(horizontal = 16.dp),
            )
            Text(
                text = route.title,
                style = MaterialTheme.typography.labelLarge,
                color = theme.color("text", MaterialTheme.colorScheme.onSurface),
            )
        }
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${route.title} lands here.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}

/** What a route's own top row prints. */
private val Route.title: String
    get() = when (this) {
        is Route.Changes -> "Changes"
        is Route.Diff -> path.substringAfterLast('/')
        is Route.Problems -> "Problems"
        is Route.Settings -> "Settings"
        is Route.NewProgram -> "New program"
        is Route.Clone -> "Clone"
        is Route.Setup -> "Set up the toolchain"
    }
