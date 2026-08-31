package to.eyed.seeker.code.terminal

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import to.eyed.seeker.code.core.AskpassSetup
import to.eyed.seeker.code.core.GitAskpass
import to.eyed.seeker.code.core.ProjectsRoot
import to.eyed.seeker.code.core.SafeDelete
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Clone a git repository into a new project.
 *
 * git runs *inside the userland*, through the same seam the terminal uses
 * ([UserlandBackend.execCommand]), for the reason the userland exists at all:
 * the only git on this device is the one apt installed, and Android will
 * execute it for nobody but proot. Where there is no guest `execCommand`
 * returns null, and the UI leaves the action out rather than showing it
 * greyed — see [isSupported].
 *
 * Three things about driving git from a program rather than a terminal, all of
 * which have bitten this code:
 *
 * 1. `--progress` writes its phases separated by **carriage returns**, because
 *    it is redrawing one line. `readLine()` blocks until the phase is over and
 *    then hands you the whole thing at once, so the UI would sit at 0% and
 *    jump to 100%. [GitProgressReader] splits on `\r` as well as `\n`.
 * 2. Without `GIT_TERMINAL_PROMPT=0`, a private repository does not fail — it
 *    *hangs*, waiting for a username on a terminal that is not there. With
 *    it, and nothing else, it fails; the askpass environment the engine
 *    hands out ([AskpassSetup]) is what turns that into a question the
 *    user can answer, in the same dialog the panel's push uses.
 * 3. Cancelling is not `destroy()`. proot ignores SIGTERM, and
 *    `destroyForcibly()` sends SIGKILL, which proot never sees: its tracees
 *    (git, and whatever git forked) are orphaned and keep downloading into a
 *    directory we are about to delete. proot *does* handle SIGQUIT — it kills
 *    its tracees and exits — so [GuestProcess.terminate] sends that first and
 *    keeps SIGKILL as the last resort. Getting the pid is its own small mess:
 *    `Process.pid()` is API 33 and minSdk is 31.
 *
 * Lessons 1 and 3 are not git's alone — `apt-get` is driven from a program the
 * same way, and cancelling it has the same proot problem — so they live in
 * [GuestProcess], which this object and `LanguageServerInstaller` share.
 */
object GitClone {

    private const val TAG = "seeker-clone"

    /** Keep the tail of git's own words for the error message. */
    private const val TRANSCRIPT_LINES = 12

    /** Where ca-certificates puts the bundle git's TLS reads. Guest path. */
    private const val CA_BUNDLE = "/etc/ssl/certs/ca-certificates.crt"

    // --- state ---------------------------------------------------------------

    /**
     * Owned here rather than in the composition: the project picker is a
     * dialog, and dismissing it must not abandon a half-finished clone with a
     * partial directory on disk. Same reasoning as [UserlandInstaller].
     */
    var state by mutableStateOf<CloneState>(CloneState.Idle)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * Bumped by every start and every cancel. A job that finishes after it was
     * cancelled — the kill leaves git with a non-zero status, which looks like
     * a failure — checks this before writing [state], so a cancelled clone
     * cannot report an error over whatever the user is doing now.
     */
    @Volatile
    private var generation = 0

    /** The process to signal, and what to delete if it is cancelled. */
    @Volatile
    private var attempt: Attempt? = null

    private class Attempt(val process: Process, val destination: File?)

    /** False in builds with no userland: the UI must not offer cloning at all. */
    val isSupported: Boolean get() = Userland.backend.isSupported

    val isBusy: Boolean
        get() = state is CloneState.Working || state is CloneState.InstallingGit

    /** Back to a blank form. Ignored while something is running. */
    fun reset() {
        if (!isBusy) state = CloneState.Idle
    }

    // --- running -------------------------------------------------------------

    /**
     * Clone [url] into a new project called [name], reporting through [state].
     *
     * [onCloned] fires on the caller's thread only on success, with the new
     * project's path, so the workspace can open it.
     */
    fun start(context: Context, url: String, name: String, onCloned: (String) -> Unit) =
        launch(context, url, name, installFirst = false, onCloned = onCloned)

    /**
     * `apt-get install -y git ca-certificates` in the guest, then carry on with
     * the clone the user asked for. Offered instead of failing with "command
     * not found" or an SSL trust error, both of which are dead ends the user
     * has no way out of.
     */
    fun installGitAndClone(context: Context, url: String, name: String, onCloned: (String) -> Unit) =
        launch(context, url, name, installFirst = true, onCloned = onCloned)

    private fun launch(
        context: Context,
        url: String,
        name: String,
        installFirst: Boolean,
        onCloned: (String) -> Unit,
    ) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        val mine = ++generation
        state = if (installFirst) {
            CloneState.InstallingGit("Starting")
        } else {
            CloneState.Working(CloneProgress("Preparing", null))
        }
        job = scope.launch {
            val result = runCatching {
                if (installFirst) {
                    installGit(app)?.let { return@runCatching it }
                    if (generation != mine) return@launch
                    state = CloneState.Working(CloneProgress("Preparing", null))
                }
                val outcome = clone(app, url.trim(), name.trim())
                // apt said it succeeded and the guest still cannot clone.
                // Offering the same install again would be a loop with no way
                // out and nothing on screen to explain it, so say what
                // happened instead.
                if (installFirst && outcome == CloneState.NeedsGit) {
                    CloneState.Failed(
                        "The install finished but the userland still cannot clone",
                        "apt-get reported success, yet git or $CA_BUNDLE is " +
                            "still missing inside the userland. Installing them " +
                            "from the terminal will show why.",
                    )
                } else {
                    outcome
                }
            }.getOrElse { error ->
                Log.e(TAG, "clone failed", error)
                CloneState.Failed("Could not clone", error.message)
            }
            attempt = null
            // A cancel already moved on; do not report the failure its own
            // kill produced over whatever the user is looking at now.
            if (generation != mine) return@launch
            state = result
            if (result is CloneState.Finished) onCloned(result.path)
        }
    }

    /**
     * Stop a clone and leave nothing behind.
     *
     * The signalling runs in its own coroutine, a sibling of the clone's, so
     * cancelling the clone cannot cancel the cleanup — and so the main thread
     * never blocks on the grace period.
     */
    fun cancel() {
        val running = job?.takeIf { it.isActive } ?: return
        // Capture the doomed attempt *now*, on the main thread, rather than
        // letting terminate() read the field later: by then the user may have
        // started a second clone, and this cancellation would kill that one
        // and delete its destination.
        val doomed = attempt
        attempt = null
        job = null
        generation++
        state = CloneState.Idle
        scope.launch {
            terminate(doomed)
            running.cancel()
        }
    }

    /** SIGQUIT, then SIGKILL, then delete whatever git had written. */
    private fun terminate(doomed: Attempt? = null) {
        val current = doomed ?: attempt ?: return
        if (doomed == null) attempt = null
        GuestProcess.terminate(current.process)
        // Only after the writer is gone, or we would race it and leave files.
        current.destination?.let { SafeDelete.deleteTree(it) }
    }

    // --- the clone itself ----------------------------------------------------

    private suspend fun clone(context: Context, url: String, name: String): CloneState {
        if (url.isEmpty()) return CloneState.Failed("Enter a repository URL", null)
        ProjectsRoot.nameError(context, name)?.let { return CloneState.Failed(it, null) }

        // Cloning into the projects directory with a *relative* destination:
        // the guest sees it as /projects, and we never have to know what the
        // backend called it inside the fake root.
        val projects = ProjectsRoot.directory(context)
        val destination = ProjectsRoot.projectDir(context, name)
        if (destination.exists()) {
            return CloneState.Failed("A project called “$name” already exists", null)
        }

        // The engine's askpass helper and credential-cache option, the same
        // ones its own fetch and push run with. Empty without a userland —
        // in which case execCommand below answers null anyway.
        val askpass = AskpassSetup.current()
        val command = Userland.backend.execCommand(
            context,
            projects.absolutePath,
            listOf("git") + askpass.gitArgs + listOf("clone", "--progress", "--", url, name),
            gitEnvironment(askpass),
        ) ?: return CloneState.Failed(noUserland(), null)

        if (!canClone(context, projects, url)) return CloneState.NeedsGit

        val transcript = ArrayDeque<String>()
        // Under the watch that puts a credential question on screen, under
        // the command's own name — the way Zed's modals are titled "git
        // fetch", "git push origin" (git_panel.rs:3899, 4164).
        val exit = GitAskpass.during("git clone") {
            run(command, destination) { record ->
                // Keep what git *said*, not the progress bar it drew: a
                // hundred "Receiving objects" redraws would push the actual
                // error out of the tail we show.
                if (GitProgress.parse(record) == null) {
                    if (transcript.size >= TRANSCRIPT_LINES) transcript.removeFirst()
                    transcript.addLast(record)
                }
            }
        }
        if (exit == 0) return CloneState.Finished(destination.absolutePath, name)

        // git already said it better than we can; keep its words and put a
        // sentence the user can act on in front of them.
        val output = transcript.joinToString("\n")
        return CloneState.Failed(explain(output, url), output.ifBlank { null })
            .also { SafeDelete.deleteTree(destination) }
    }

    /** Null when git is now installed; a failure state otherwise. */
    private fun installGit(context: Context): CloneState? {
        val projects = ProjectsRoot.directory(context)
        val command = Userland.backend.execCommand(
            context,
            projects.absolutePath,
            listOf(
                "/bin/sh", "-c",
                // ca-certificates is only a *recommendation* of git, so
                // --no-install-recommends alone produces a git that cannot
                // reach any https:// remote: no /etc/ssl/certs, and every
                // clone dies with "Problem with the SSL CA cert". Name it.
                "apt-get update && apt-get install -y --no-install-recommends " +
                    "git ca-certificates",
            ),
            listOf("DEBIAN_FRONTEND=noninteractive"),
        ) ?: return CloneState.Failed(noUserland(), null)

        val transcript = ArrayDeque<String>()
        var lastStep = 0L
        val exit = run(command, destination = null) { record ->
            if (transcript.size >= TRANSCRIPT_LINES) transcript.removeFirst()
            transcript.addLast(record)
            // apt is chatty; the same 10 Hz ceiling as the clone's progress.
            val now = System.nanoTime()
            // Never resurrect a state a cancel has already moved past.
            if (now - lastStep >= GuestProcess.PROGRESS_INTERVAL_NS &&
                state is CloneState.InstallingGit
            ) {
                lastStep = now
                state = CloneState.InstallingGit(record.take(120))
            }
        }
        if (exit == 0) return null
        val output = transcript.joinToString("\n")
        return CloneState.Failed("Could not install git", output.ifBlank { null })
    }

    /**
     * Why there is nowhere to run git. Reached before Debian is installed;
     * the unsupported branch is unreachable today, because the UI leaves the
     * action out entirely when [isSupported] is false.
     */
    private fun noUserland(): String =
        if (Userland.backend.isSupported) {
            "${Userland.backend.displayName} is not installed yet — open the terminal to " +
                "install it, then try again"
        } else {
            "The Linux guest is not available, so there is nowhere to run git"
        }

    /**
     * Whether the guest can clone *this* URL.
     *
     * More than the git binary, for an `https://` remote: a rootfs with git but
     * no CA bundle fails every one of those with "Problem with the SSL CA cert",
     * and offering the install is the only way out, so for them the two are one
     * question.
     *
     * Only for those, though. ssh remotes go through `GIT_SSH_COMMAND` and a
     * local path touches no network at all; demanding a trust store they never
     * read would refuse a clone that works, and send the user to an `apt-get
     * update` that cannot even run offline.
     */
    private fun canClone(context: Context, projects: File, url: String): Boolean {
        val test = StringBuilder("command -v git >/dev/null")
        if (url.trim().startsWith("https://", ignoreCase = true)) {
            test.append(" && [ -s $CA_BUNDLE ]")
        }
        val command = Userland.backend.execCommand(
            context,
            projects.absolutePath,
            listOf("/bin/sh", "-c", test.toString()),
        ) ?: return false
        return runCatching { run(command, destination = null) { } == 0 }.getOrDefault(false)
    }

    /**
     * [GuestProcess.run] with the clone's own reporting on top: the process is
     * remembered as the cancellable [Attempt], every completed record is
     * handed to [onRecord] so callers can keep a transcript, and the [state]
     * updates are throttled to [GuestProcess.PROGRESS_INTERVAL_NS] because git
     * redraws far faster than a screen.
     */
    private fun run(
        command: ShellCommand,
        destination: File?,
        onRecord: (String) -> Unit,
    ): Int {
        var lastEmit = 0L
        var lastPhase: String? = null
        return GuestProcess.run(
            command,
            onStart = { process -> attempt = Attempt(process, destination) },
        ) { record ->
            onRecord(record)
            val progress = GitProgress.parse(record)
            if (progress != null) {
                val now = System.nanoTime()
                // Always show a new phase at once — that is the part the user
                // reads — and rate-limit only the percentages.
                if (progress.phase != lastPhase ||
                    now - lastEmit >= GuestProcess.PROGRESS_INTERVAL_NS
                ) {
                    lastPhase = progress.phase
                    lastEmit = now
                    if (state is CloneState.Working) state = CloneState.Working(progress)
                }
            }
        }
    }

    /**
     * The environment that makes git ask instead of hang.
     *
     * `GIT_TERMINAL_PROMPT=0` is the one that matters: a private HTTPS
     * repository otherwise blocks forever on "Username for …", with no
     * terminal to type it into. What it asks instead goes through the
     * engine's helper — `GIT_ASKPASS`, `SSH_ASKPASS` and
     * `SSH_ASKPASS_REQUIRE=force`, the three Zed sets around a remote
     * command (repository.rs:4033-4036) — so a private clone, a key with a
     * passphrase and an unknown host key are all questions the dialog can
     * answer. With no helper (no userland) the variables are simply absent
     * and git fails as it always did. No `BatchMode`: it would silence the
     * very passphrase prompt the helper exists to carry.
     */
    internal fun gitEnvironment(askpass: AskpassSetup): List<String> = listOf(
        "GIT_TERMINAL_PROMPT=0",
        // Progress is a terminal affordance; git suppresses it when stdout is
        // a pipe unless asked, which --progress does. Keep the columns sane so
        // the records are the shape the parser expects.
        "COLUMNS=80",
    ) + askpass.environment

    /** A sentence the user can act on, from whatever git printed. */
    private fun explain(output: String, url: String): String {
        val text = output.lowercase()
        return when {
            // The dialog was cancelled, or what it was given was refused:
            // git's own transcript underneath says which.
            "could not read username" in text ||
                "could not read password" in text ||
                "terminal prompts disabled" in text ||
                "authentication failed" in text ||
                "permission denied (publickey)" in text ||
                "host key verification failed" in text ||
                "invalid username or password" in text ->
                "That repository needs credentials git was not given"

            "repository not found" in text ||
                "not found" in text && "remote" in text ||
                "does not appear to be a git repository" in text ||
                "could not find remote repository" in text ->
                "No repository at $url"

            "could not resolve host" in text ||
                "network is unreachable" in text ||
                "connection timed out" in text ||
                "connection refused" in text ||
                "temporary failure in name resolution" in text ->
                "Could not reach the network"

            "already exists and is not an empty directory" in text ->
                "The destination already exists"

            // Deliberately narrow: git's own "could not create work tree dir
            // …: No such file or directory" must not be read as a missing git.
            "command not found" in text || "exec: git" in text || "git: not found" in text ->
                "git is not installed in the userland"

            else -> "git could not clone that repository"
        }
    }
}

/**
 * Running one program inside the userland, reading everything it says, and
 * stopping it when the user changes their mind.
 *
 * Extracted from [GitClone] when installing a language server needed the same
 * three things (see the doc comment there for what each one cost to learn):
 * the environment has to be built by hand because the pty shim clears it, the
 * output has to be split on `\r` as well as `\n` because a program that thinks
 * it is on a terminal redraws one line, and cancelling has to go through
 * SIGQUIT because proot ignores SIGTERM and never sees SIGKILL.
 *
 * Nothing here touches state or the UI: callers own their own reporting, so
 * git can throttle a percentage and apt can show its last line.
 */
internal object GuestProcess {

    private const val TAG = "seeker-guest"

    /** How long to give proot to take its tracees down after SIGQUIT. */
    private const val QUIT_GRACE_MS = 3_000L

    /** ~10 Hz. Progress arrives far faster than that and a phone need not care. */
    const val PROGRESS_INTERVAL_NS = 100_000_000L

    /**
     * Start [command], hand the process to [onStart] so a caller can cancel
     * it, feed every completed record to [onRecord], and return its exit
     * status. Blocking: call it off the main thread.
     *
     * [onStart] runs before the first byte is read, which is the only ordering
     * that matters — a cancel arriving in that window must find the process.
     */
    fun run(
        command: ShellCommand,
        onStart: (Process) -> Unit = {},
        onRecord: (String) -> Unit,
    ): Int {
        val process = start(command)
        onStart(process)
        val reader = GitProgressReader()
        process.inputStream.reader(Charsets.UTF_8).use { source ->
            val buffer = CharArray(4096)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                for (record in reader.feed(String(buffer, 0, read))) onRecord(record)
            }
        }
        for (record in reader.flush()) onRecord(record)
        return process.waitFor()
    }

    /** The process, started with a clean environment and one merged stream. */
    private fun start(command: ShellCommand): Process {
        // ProcessBuilder cannot set argv[0] apart from the executable, and
        // proot does not care what it is called, so the .so path stands in.
        val builder = ProcessBuilder(listOf(command.executable) + command.argv.drop(1))
            // git writes its progress to stderr and almost nothing to stdout,
            // and apt splits itself the same way; merging keeps the ordering
            // and gives one stream to read.
            .redirectErrorStream(true)
        builder.environment().apply {
            clear()
            for (entry in command.environment) {
                val split = entry.indexOf('=')
                if (split > 0) put(entry.substring(0, split), entry.substring(split + 1))
            }
        }
        return builder.start()
    }

    /**
     * Stop [process] and its tracees: SIGQUIT, a grace period, then SIGKILL.
     *
     * SIGKILL alone would orphan whatever proot was supervising — git still
     * downloading, dpkg still unpacking — which is why the polite signal goes
     * first even though it costs three seconds in the worst case.
     */
    fun terminate(process: Process) {
        if (!process.isAlive) return
        val pid = pidOf(process)
        if (pid != null) {
            runCatching { Os.kill(pid, OsConstants.SIGQUIT) }
                .onFailure { Log.w(TAG, "SIGQUIT to $pid failed", it) }
            runCatching { process.waitFor(QUIT_GRACE_MS, TimeUnit.MILLISECONDS) }
        } else {
            Log.w(TAG, "no pid for the guest process; going straight to SIGKILL")
        }
        if (process.isAlive) {
            // proot did not go, so its tracees are about to be orphaned.
            // Nothing better is left than killing what we can reach.
            process.destroyForcibly()
            runCatching { process.waitFor(QUIT_GRACE_MS, TimeUnit.MILLISECONDS) }
        }
    }

    /**
     * The child's pid, defensively.
     *
     * `Process.pid()` is API 33 and this app runs from 31 — and it is not even
     * in the SDK stubs this module compiles against, so it has to be reached
     * by name. Where it is missing, the platform's own `ProcessImpl` has
     * always carried the pid in a field. Both paths can fail, and the caller
     * degrades to `destroyForcibly()` when they do.
     */
    private fun pidOf(process: Process): Int? {
        runCatching {
            val method = process.javaClass.getMethod("pid")
            method.isAccessible = true
            return (method.invoke(process) as Long).toInt()
        }.onFailure { Log.w(TAG, "Process.pid() unavailable, trying the field", it) }

        var type: Class<*>? = process.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField("pid") }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.getInt(process)
                }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }
}

/** Where a clone has got to. [fraction] is null while a phase has no percent. */
data class CloneProgress(val phase: String, val fraction: Float?)

/** What the picker draws. */
sealed interface CloneState {
    /** Nothing running: show the form. */
    data object Idle : CloneState

    data class Working(val progress: CloneProgress) : CloneState

    /**
     * The guest cannot clone this URL yet — no git, or no CA bundle for an
     * `https://` one. The user is offered the install rather than an error.
     */
    data object NeedsGit : CloneState

    data class InstallingGit(val step: String) : CloneState

    /** [detail] is git's own message, kept verbatim. */
    data class Failed(val summary: String, val detail: String?) : CloneState

    data class Finished(val path: String, val name: String) : CloneState
}

/**
 * Split git's output into records.
 *
 * `git clone --progress` is drawing on a terminal: it ends a progress update
 * with `\r` and returns to the start of the line to overwrite it. Only the
 * *last* update of a phase is followed by `\n`. Reading by line therefore
 * shows nothing for the length of a download and then everything at once,
 * which is exactly the 0→100% jump this class exists to prevent.
 *
 * Chunk boundaries fall wherever the pipe felt like it, so a record is
 * routinely split across two reads; the tail is held until it is complete.
 */
class GitProgressReader {
    private val pending = StringBuilder()

    /** The records completed by [text]. Empty separators (`\r\n`) are dropped. */
    fun feed(text: String): List<String> {
        val records = mutableListOf<String>()
        for (char in text) {
            if (char == '\r' || char == '\n') {
                val record = pending.toString().trim()
                pending.setLength(0)
                if (record.isNotEmpty()) records += record
            } else {
                pending.append(char)
            }
        }
        return records
    }

    /** Whatever was left unterminated when the stream closed. */
    fun flush(): List<String> {
        val record = pending.toString().trim()
        pending.setLength(0)
        return if (record.isEmpty()) emptyList() else listOf(record)
    }
}

/** Reading a phase and a percentage out of one of git's records. */
object GitProgress {

    /** `Receiving objects:  43% (531/1234), 1.20 MiB | 600.00 KiB/s` */
    private val PERCENT = Regex("""^(?:remote:\s*)?([A-Za-z][A-Za-z ]*[a-z]):\s+(\d{1,3})%""")

    /** `remote: Enumerating objects: 1234, done.` — a count, with no total. */
    private val COUNT = Regex("""^(?:remote:\s*)?([A-Za-z][A-Za-z ]*[a-z]):\s+\d+(?:,|$)""")

    private val CLONING = Regex("""^Cloning into '.*'\.\.\.$""")

    /** Null when the record is not progress — an error, a hint, a warning. */
    fun parse(record: String): CloneProgress? {
        PERCENT.find(record)?.let { match ->
            val percent = match.groupValues[2].toInt().coerceIn(0, 100)
            return CloneProgress(match.groupValues[1], percent / 100f)
        }
        COUNT.find(record)?.let { return CloneProgress(it.groupValues[1], null) }
        if (CLONING.matches(record)) return CloneProgress("Cloning", null)
        return null
    }
}

/** Turning a repository URL into a project name. */
object GitCloneUrl {

    /** Suffixes git itself strips when it picks a directory name. */
    private const val GIT_SUFFIX = ".git"

    /**
     * The directory name git would use for [url], or null if it cannot be one.
     *
     * Follows git's own rule — the last non-empty path component, minus a
     * `.git` suffix — across the URL shapes people actually paste:
     * `https://host/owner/repo.git`, `git@host:owner/repo.git`,
     * `ssh://git@host:22/owner/repo/`, and a bare local path. Null for
     * anything with no component left to use, so the picker can say so rather
     * than creating a project called `.git` or an empty string.
     */
    fun projectName(url: String): String? {
        var text = url.trim()
        if (text.isEmpty()) return null

        // Strip a fragment or query first: they are not part of the path.
        text = text.substringBefore('#').substringBefore('?')

        // scp-style `git@host:owner/repo` has no scheme and a colon where a
        // slash belongs; everything after the colon is the path.
        text = when {
            "://" in text -> text.substringAfter("://").substringAfter('/', "")
            ':' in text && !text.startsWith("/") -> text.substringAfter(':')
            else -> text
        }

        val last = text.split('/', '\\')
            .lastOrNull { it.isNotBlank() && it != "." && it != ".." }
            ?: return null

        val trimmed = last.removeSuffix(GIT_SUFFIX).trim().trimEnd('.')
        if (trimmed.isEmpty() || trimmed.startsWith(".")) return null
        // A name that cannot be a directory is worse than no suggestion.
        if (trimmed.any { it.code < 0x20 }) return null
        return trimmed
    }
}
