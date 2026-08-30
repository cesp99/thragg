package to.eyed.seeker.code.terminal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import to.eyed.seeker.code.core.TerminalSettings
import to.eyed.seeker.code.core.TaskHide
import to.eyed.seeker.code.core.TaskReveal
import to.eyed.seeker.code.core.TaskSpec

/**
 * The terminal dock: a list of shell sessions and which one is showing.
 *
 * Sessions outlive the panel being hidden — a build keeps running while you
 * read code — but not a project switch, since a shell sitting in a directory
 * the workspace no longer has open is a trap.
 *
 * Held by [TerminalSessions] rather than by a composable, because a running
 * `apt install` must survive anything the UI does to itself, and because the
 * foreground service that keeps those processes alive is driven from the
 * session count here.
 */
class TerminalPanelState(context: Context) {

    /** Application context: these outlive any activity by design. */
    private val context = context.applicationContext

    private val entries = mutableStateListOf<TerminalSessionHost>()

    val sessions: List<TerminalSessionHost> get() = entries

    var activeIndex by mutableStateOf(-1)
        private set

    var isOpen by mutableStateOf(false)
        private set

    val active: TerminalSessionHost? get() = entries.getOrNull(activeIndex)

    /**
     * Whether the search bar is up over the active session — Zed's
     * `buffer_search::Deploy` in the terminal context. Held here rather than
     * in the dock's composition so the workspace's command runner and the
     * dock's own buttons open the same bar.
     */
    var searchOpen by mutableStateOf(false)

    /**
     * What the next session is born with — `terminal.env` and the scrollback
     * from settings.json. The workspace keeps this current; sessions already
     * running keep what they had, as in Zed.
     */
    var spawn: TerminalSettings = TerminalSettings()

    /**
     * The dock's answer to a `terminal::` keymap action (`terminal::Copy`,
     * `terminal::Clear`, …) by its name, or false for one it has not got.
     * Registered by the dock while it is on screen — only the composition
     * that holds the view can act on it — and null while it is not, so a key
     * bound to a dock that is not there is refused rather than dropped.
     */
    var dockAction: ((String) -> Boolean)? = null

    private var created = 0

    /** Show the dock, starting a first shell in [cwd] if there is none. */
    fun open(cwd: String) {
        if (entries.isEmpty()) newSession(cwd) else isOpen = true
    }

    fun hide() {
        isOpen = false
    }

    fun toggle(cwd: String) {
        if (isOpen) hide() else open(cwd)
    }

    /** Start another shell and show it. Must be called on the main thread. */
    fun newSession(cwd: String) {
        created += 1
        entries.add(
            TerminalSessionHost(
                context,
                cwd,
                "shell $created",
                extraEnvironment = spawn.env.map { (name, value) -> "$name=$value" },
                scrollbackRows = spawn.scrollbackLines,
            )
        )
        activeIndex = entries.lastIndex
        isOpen = true
        syncService()
    }

    /**
     * Open a session running one program rather than a shell, and show it.
     *
     * The agent panel's terminal sign-in: ACP lets an agent offer an auth
     * method whose meaning is "run me with these arguments and let the user
     * answer" (`AuthMethod::Terminal`), which needs a real pty and a keyboard
     * — this dock — and not the pipe-shaped terminals the agent itself
     * drives. Must be called on the main thread.
     */
    fun runSession(cwd: String, label: String, command: ShellCommand) {
        entries.add(
            TerminalSessionHost(context, cwd, label, command, scrollbackRows = spawn.scrollbackLines)
        )
        activeIndex = entries.lastIndex
        isOpen = true
        syncService()
    }

    /**
     * Run a task in the dock, the way Zed's terminal panel spawns one
     * (terminal_panel.rs:632-708): a task that allows concurrent runs *and*
     * wants a new terminal always gets a fresh tab; otherwise the tab that
     * last ran the same label is reused — replaced at once when concurrent
     * runs are allowed, after the current run ends when they are not — and
     * only a task with no tab of its own opens one. Then the `reveal`
     * strategy decides whether the dock shows and the tab takes the front.
     * Returns the tab the task runs in. Must be called on the main thread.
     */
    fun runTask(task: TaskSpec, command: ShellCommand, cwd: String): TerminalSessionHost {
        val existing = if (task.allowConcurrentRuns && task.useNewTerminal) {
            null
        } else {
            entries.lastOrNull { it.task?.fullLabel == task.fullLabel }
        }
        val host = when {
            existing == null -> TerminalSessionHost(
                context,
                cwd,
                task.label,
                command,
                extraEnvironment = spawn.env.map { (name, value) -> "$name=$value" },
                scrollbackRows = spawn.scrollbackLines,
                task = task,
            ).also { entries.add(it) }
            task.allowConcurrentRuns -> existing.also { it.rerun(command) }
            else -> existing.also { it.rerunWhenFinished(command) }
        }
        host.onFinished = { finished -> hideIfAsked(finished) }
        when (task.reveal) {
            TaskReveal.Always -> {
                activeIndex = entries.indexOf(host)
                isOpen = true
            }
            TaskReveal.NoFocus -> {
                if (activeIndex < 0) activeIndex = entries.indexOf(host)
                isOpen = true
            }
            TaskReveal.Never -> if (activeIndex < 0) activeIndex = entries.indexOf(host)
        }
        syncService()
        return host
    }

    /**
     * A task's `hide` strategy, applied when its run ends: `always` closes the
     * tab, `on_success` closes it only after a clean exit, and the dock goes
     * with the last tab as it does for any close (Zed's `HideStrategy`).
     */
    private fun hideIfAsked(host: TerminalSessionHost) {
        val hide = host.task?.hide ?: return
        val succeeded = host.exitStatus == 0
        val close = when (hide) {
            TaskHide.Never -> false
            TaskHide.Always -> true
            TaskHide.OnSuccess -> succeeded
        }
        val index = entries.indexOf(host)
        if (close && index >= 0) closeSession(index)
    }

    fun select(index: Int) {
        if (index !in entries.indices) return
        activeIndex = index
        // Looking at a session is hearing its bell, the same rule Zed uses.
        entries[index].clearBell()
    }

    /**
     * Open the dock on one session — what a notification's "Show output"
     * means. A session that has already been closed is a no-op rather than a
     * dock that opens onto whatever happens to be at that index.
     */
    fun reveal(host: TerminalSessionHost) {
        val index = entries.indexOf(host)
        if (index < 0) return
        select(index)
        isOpen = true
        syncService()
    }

    /** Give a session a name of its own; empty hands the chip back to the shell. */
    fun rename(index: Int, title: String) {
        entries.getOrNull(index)?.rename(title)
    }

    fun selectRelative(delta: Int) {
        if (entries.isEmpty()) return
        val size = entries.size
        select(((activeIndex + delta) % size + size) % size)
    }

    /** Kill a session and drop it. Hides the dock when the last one goes. */
    fun closeSession(index: Int) {
        val host = entries.getOrNull(index) ?: return
        host.finish()
        entries.removeAt(index)
        if (entries.isEmpty()) {
            activeIndex = -1
            isOpen = false
            searchOpen = false
        } else {
            activeIndex = activeIndex.coerceAtMost(entries.lastIndex)
        }
        syncService()
    }

    fun closeAll() {
        for (host in entries) host.finish()
        entries.clear()
        activeIndex = -1
        isOpen = false
        created = 0
        syncService()
    }

    /**
     * Keep the foreground service in step with reality: it exists exactly as
     * long as there is a session for it to protect.
     */
    private fun syncService() {
        TerminalService.sync(context, entries.size)
    }
}

/**
 * The one place terminal sessions live.
 *
 * Not a composable's `remember {}`: a session is a running process tree, and
 * it has to outlive the composition, the activity, and the user closing the
 * dock. Everything above it is still ordinary Compose state, so the UI
 * observes it exactly as before.
 */
object TerminalSessions {
    private var instance: TerminalPanelState? = null

    fun of(context: Context): TerminalPanelState =
        instance ?: TerminalPanelState(context).also { instance = it }
}
