package to.eyed.thragg.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import java.io.File
import to.eyed.thragg.core.TaskSpec

/**
 * One shell session, and the bridge between the vendored terminal and Compose.
 *
 * The vendored `TerminalSession` reports everything through a callback
 * interface on the main thread; this turns the parts the UI cares about into
 * Compose state and forwards screen updates to the attached view.
 *
 * **Must be constructed on the main thread.** `TerminalSession`'s constructor
 * binds a `Handler` to the calling thread's looper — found the hard way in
 * P4-1 — so building one on a background thread throws.
 */
class TerminalSessionHost(
    private val context: Context,
    /** Working directory: the project root, so a terminal opens where the code is. */
    val cwd: String,
    /** Stable label for the session chip, e.g. "shell 1". */
    val label: String,
    /**
     * What to run instead of a shell.
     *
     * Null is the ordinary case: a login shell in [cwd]. Non-null is a
     * session opened *for* one program — an agent's own `login` command,
     * which ACP's terminal auth method asks the client to run so the user can
     * sign in through its TUI. It still gets a pty and a keyboard, because
     * that is the entire point; what it does not get is a shell around it, so
     * the session ends when the program does.
     */
    private var command: ShellCommand? = null,
    /**
     * `NAME=value` pairs from `terminal.env`, appended after the app's own so
     * the user's win — the pty shim hands the list to `execve` as it is, and
     * a shell exports the *last* definition of a repeated name, but a program
     * started directly would read the first, so the app's copy of a name the
     * user sets is dropped rather than shadowed.
     */
    private val extraEnvironment: List<String> = emptyList(),
    /** Rows of scrollback, already within the emulator's own bounds. */
    private val scrollbackRows: Int = DEFAULT_TRANSCRIPT_ROWS,
    /**
     * The task this session was opened for, when it was — the label the
     * terminal dock keys tab reuse on, as Zed keys its `terminals_for_task`
     * on the task's full label (terminal_panel.rs:780-800). Null for a
     * shell and for the agent's sign-in session.
     */
    val task: TaskSpec? = null,
) : TerminalSessionClient {

    /**
     * Told when the process ends, after [exitStatus] is set — how the dock
     * applies a task's `hide` strategy without watching every session.
     */
    var onFinished: ((TerminalSessionHost) -> Unit)? = null

    /**
     * A rerun asked for while the last run was still going, honoured when
     * it ends — Zed's `deferred_tasks`, which wait for the running terminal
     * before spawning again (terminal_panel.rs:686-708).
     */
    private var pendingRerun: ShellCommand? = null

    /** The title the shell set with an OSC sequence, if any. */
    var shellTitle by mutableStateOf<String?>(null)
        private set

    /**
     * A name the user gave this session. It outranks the shell's own title and
     * survives a restart — the point of naming a session is that it keeps the
     * name while the program inside it comes and goes.
     */
    var customTitle by mutableStateOf<String?>(null)
        private set

    /** What the session chip shows, most specific first. */
    val title: String get() = customTitle ?: shellTitle ?: label

    /**
     * Bells rung since the session was last typed in.
     *
     * A count rather than a flag so the UI can flash again on the second bell;
     * Zed marks the terminal's tab the same way and clears it on input.
     */
    var bells by mutableIntStateOf(0)
        private set

    /** Non-null once the process has exited: >= 0 exit code, < 0 negated signal. */
    var exitStatus by mutableStateOf<Int?>(null)
        private set

    /** How the exit reads to a person, or null while the shell is running. */
    val exitDescription: String?
        get() = exitStatus?.let { status ->
            if (status < 0) "killed by signal ${-status}" else "exited with status $status"
        }

    var session: TerminalSession by mutableStateOf(startSession())
        private set

    /** The view currently showing this session, if it is the visible one. */
    private var view: TerminalView? = null

    private fun startSession(): TerminalSession {
        // Either a shell inside the Linux userland or the host's own — see
        // ShellEnvironment.commandFor — unless the caller named a program.
        val command = command ?: ShellEnvironment.commandFor(context, cwd)
        return TerminalSession(
            command.executable,
            cwd,
            command.argv.toTypedArray(),
            mergeEnvironment(command.environment, extraEnvironment).toTypedArray(),
            scrollbackRows,
            this,
        )
    }

    /**
     * The shell's working directory *now*, or [cwd] when the kernel will not
     * say. `/proc/<pid>/cwd` is readable for the app's own processes; the
     * userland's shell is one hop down from the pid the session knows
     * (proot), so that hop is followed. A path the terminal prints is
     * relative to this, not to where the session started — the user has
     * usually `cd`ed.
     */
    fun currentDirectory(): String = shellCurrentDirectory(session.pid) ?: cwd

    fun attach(view: TerminalView) {
        this.view = view
        view.attachSession(session)
    }

    fun detach(view: TerminalView) {
        if (this.view === view) this.view = null
    }

    /** Whether the process is still going. */
    val isRunning: Boolean get() = exitStatus == null

    /** Run the shell again in the same directory, reusing this session slot. */
    fun restart() {
        pendingRerun = null
        stopSessionTree()
        exitStatus = null
        shellTitle = null
        bells = 0
        session = startSession()
        view?.attachSession(session)
    }

    /**
     * Run [newCommand] in this slot, killing whatever is running — a task
     * rerun with `allow_concurrent_runs`, which Zed answers by replacing the
     * terminal (terminal_panel.rs:674-682).
     */
    fun rerun(newCommand: ShellCommand) {
        command = newCommand
        restart()
    }

    /**
     * Run [newCommand] here once the current run ends — now, if it already
     * has. The default for a task that is still going: Zed waits rather than
     * running two of the same at once (terminal_panel.rs:686-708).
     */
    fun rerunWhenFinished(newCommand: ShellCommand) {
        if (isRunning) pendingRerun = newCommand else rerun(newCommand)
    }

    /** Name this session; an empty name hands the chip back to the shell. */
    fun rename(title: String) {
        customTitle = title.trim().takeIf { it.isNotEmpty() }
    }

    /** Called when the session is looked at or typed in: the bell has been heard. */
    fun clearBell() {
        if (bells != 0) bells = 0
    }

    fun finish() {
        stopSessionTree()
    }

    /**
     * End this session and everything it started.
     *
     * `TerminalSession.finishIfRunning` sends **SIGKILL** to the one pid it
     * knows, which is proot's. proot is started with `--kill-on-exit`, and
     * that path is code proot has to *run*: SIGKILL is the one signal it can
     * never run anything for. The kernel detaches its tracees, and the guest
     * `bash` and whatever it was running are reparented to init and keep
     * going — on the device, deleting the open project left `bash --login`
     * and its `sleep` alive with `cwd -> …/projects/qa_git2 (deleted)` a
     * full minute later (QA 0.0.23, G-18).
     *
     * So the session's whole process group goes, the way
     * `GitClone.GuestProcess.terminate` already does it: the polite signal
     * first, so a `git` or a `dpkg` mid-write is given its own chance to
     * stop, then the sure one for whatever is left.
     */
    private fun stopSessionTree() {
        if (!GuestSessionReaper.terminate(session.pid)) session.finishIfRunning()
    }

    /** Type text into the shell, as the paste action and the extra keys do. */
    fun write(text: String) {
        if (exitStatus == null) session.write(text)
    }

    /** Put terminal text on the clipboard, as the copy action and the toolbar do. */
    fun copy(text: String?) {
        if (text.isNullOrEmpty()) return
        clipboard()?.setPrimaryClip(ClipData.newPlainText("", text))
    }

    /** Paste the clipboard into the shell. Null session: the caller is our UI. */
    fun paste() = onPasteTextFromClipboard(null)

    // --- TerminalSessionClient -------------------------------------------

    override fun onTextChanged(changedSession: TerminalSession) {
        if (changedSession === session) {
            view?.onScreenUpdated()
            screenRevision += 1
        }
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        if (changedSession === session) shellTitle = changedSession.title
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        if (finishedSession !== session) return
        exitStatus = finishedSession.exitStatus
        val queued = pendingRerun
        if (queued != null) {
            rerun(queued)
        } else {
            onFinished?.invoke(this)
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        copy(text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val text = clipboard()?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (!text.isNullOrEmpty()) write(text)
    }

    override fun onBell(session: TerminalSession) {
        // Visual only, and deliberately: a sound or a buzz needs a setting to
        // turn it off, and the `terminal` section has no `bell` key yet. A
        // flash of the dock costs nothing and can't wake anybody up.
        if (session === this.session) bells += 1
    }

    override fun onColorsChanged(session: TerminalSession) {
        if (session === this.session) view?.onScreenUpdated()
    }

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit

    /** Null means "the emulator's default block cursor". */
    override fun getTerminalCursorStyle(): Int? = null

    private fun clipboard(): ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    override fun logError(tag: String?, message: String?) {
        Log.e(tag ?: TAG, message.orEmpty())
    }

    override fun logWarn(tag: String?, message: String?) {
        Log.w(tag ?: TAG, message.orEmpty())
    }

    override fun logInfo(tag: String?, message: String?) {
        Log.i(tag ?: TAG, message.orEmpty())
    }

    override fun logDebug(tag: String?, message: String?) {
        Log.d(tag ?: TAG, message.orEmpty())
    }

    override fun logVerbose(tag: String?, message: String?) {
        Log.v(tag ?: TAG, message.orEmpty())
    }

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: TAG, message.orEmpty(), e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.e(tag ?: TAG, "terminal error", e)
    }

    /**
     * Screen revisions: bumped on every chunk of output the emulator took.
     * The search bar re-runs its query on it, so a match count stays honest
     * while a build prints; nothing else observes it.
     */
    var screenRevision by mutableIntStateOf(0)
        private set

    companion object {
        private const val TAG = "thragg-term"

        /**
         * Scrollback when settings say nothing — Zed's default. Build output
         * is the reason a terminal in an IDE needs more than a prompt's worth;
         * each row costs roughly its width in chars, so this is a few MB.
         */
        const val DEFAULT_TRANSCRIPT_ROWS = 10_000

        /**
         * [base] with [extra] appended, minus any base entry [extra] renames —
         * see the constructor's `extraEnvironment`. Pure, for the test.
         */
        fun mergeEnvironment(base: List<String>, extra: List<String>): List<String> {
            if (extra.isEmpty()) return base
            val overridden = extra.map { it.substringBefore('=') }.toSet()
            return base.filter { it.substringBefore('=') !in overridden } + extra
        }
    }
}

/**
 * Everything one terminal session started, stopped — not the one pid the pty
 * happens to hold.
 *
 * The vendored emulator forks with `setsid()`, so the process it starts —
 * proot — is a **session leader**, and every guest process under it inherits
 * that session id however many process groups job control makes on the way
 * down. `/proc/<pid>/stat` names it, and Android mounts `/proc` with
 * `hidepid=2`, so the scan sees this app's own processes and nothing else:
 * it is a handful of entries, not a machine's worth.
 *
 * The two signals are `GitClone.GuestProcess.terminate`'s, for its reasons.
 * SIGQUIT first, to everything at once, because proot can only run its
 * `--kill-on-exit` cleanup for a signal it is allowed to handle and a `git`
 * or a `dpkg` in the middle of a write deserves the same courtesy. SIGKILL
 * after the grace, to whatever answered the first with nothing — an
 * interactive `bash` ignores SIGQUIT by design, and it is reached by pid
 * here rather than through the tracer that is already gone.
 *
 * The second pass **re-reads** the session rather than reusing the first
 * list: by then the leader may have exited, and a pid the kernel has since
 * handed to somebody else is not in this session any more, so it cannot be
 * signalled by mistake.
 */
internal object GuestSessionReaper {

    private const val TAG = "thragg-term"

    /** How long the polite signal is given before the sure one. */
    const val QUIT_GRACE_MS = 400L

    /**
     * Stop the session led by [leader]. False when this is not a session
     * leader at all — an emulator that did not `setsid`, or a pid that has
     * already gone — in which case the caller falls back to what it did
     * before rather than this scanning for a session that is not there.
     */
    fun terminate(leader: Int, proc: File = File("/proc")): Boolean {
        if (leader <= 0) return false
        if (sessionIdOf(leader, proc) != leader) return false
        val doomed = sessionPids(leader, proc)
        if (doomed.isEmpty()) return false
        for (pid in doomed) signal(pid, OsConstants.SIGQUIT)
        // The sweep waits, so it cannot be done on the thread that asked:
        // closing a project is a main-thread call and 400 ms of sleep on it
        // is a frame budget spent 24 times over. A daemon thread, because
        // nothing should keep the process alive for it.
        val sweep = Thread({
            runCatching { Thread.sleep(QUIT_GRACE_MS) }
            for (pid in sessionPids(leader, proc)) signal(pid, OsConstants.SIGKILL)
        }, "thragg-session-reaper")
        sweep.isDaemon = true
        sweep.start()
        return true
    }

    private fun signal(pid: Int, sig: Int) {
        if (pid <= 0 || pid == Os.getpid()) return
        runCatching { Os.kill(pid, sig) }
            .onFailure { Log.w(TAG, "signal $sig to $pid failed: ${it.message}") }
    }

    /**
     * Every live pid in the session led by [leader], the leader last.
     *
     * Leader last so the polite pass reaches the tracees while their tracer
     * is still there to let the signal through.
     */
    fun sessionPids(leader: Int, proc: File = File("/proc")): List<Int> {
        val names = proc.list() ?: return emptyList()
        val found = ArrayList<Int>()
        for (name in names) {
            val pid = name.toIntOrNull() ?: continue
            if (pid == leader) continue
            if (sessionIdOf(pid, proc) == leader) found.add(pid)
        }
        found.sortDescending()
        if (sessionIdOf(leader, proc) == leader) found.add(leader)
        return found
    }

    private fun sessionIdOf(pid: Int, proc: File): Int? =
        runCatching { File(proc, "$pid/stat").readText() }.getOrNull()?.let(::sessionIdIn)

    /**
     * The session id in one `/proc/<pid>/stat` line.
     *
     * Read from the LAST `)` forward, never by splitting the whole line: the
     * second field is the executable's name in parentheses and it may hold
     * spaces and parentheses of its own — `(anchor build)` would move every
     * field after it. After that comes state, ppid, pgrp and then session.
     * Pure, for the test.
     */
    fun sessionIdIn(stat: String): Int? {
        val afterComm = stat.lastIndexOf(')').takeIf { it >= 0 } ?: return null
        val fields = stat.substring(afterComm + 1).trim().split(' ')
        // state(0) ppid(1) pgrp(2) session(3)
        return fields.getOrNull(3)?.toIntOrNull()
    }
}
