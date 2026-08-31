package to.eyed.seeker.code.solana.build

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.solana.templates.SolanaProgram
import to.eyed.seeker.code.terminal.GuestProcess
import to.eyed.seeker.code.terminal.ShellCommand
import to.eyed.seeker.code.terminal.TerminalSessions
import to.eyed.seeker.code.terminal.Userland
import to.eyed.seeker.code.terminal.UserlandState
import to.eyed.seeker.code.ui.shell.BuildState
import to.eyed.seeker.code.ui.shell.Destination
import to.eyed.seeker.code.ui.editor.DiagnosticSeverity
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.workspace.NotificationAction
import to.eyed.seeker.code.ui.workspace.Notifications
import java.io.File

/**
 * The one entry point for Build, Test and Deploy.
 *
 * Its ordering is the design and it is not negotiable (docs/UI.md, P4):
 *
 *  1. **save every dirty buffer and wait for the writes.** Not a setting, not
 *     a preference. "Edit, tap Build, read the error you already fixed" is the
 *     failure this whole file was reorganised around, and the only way to
 *     design it out is to make save-then-build a single action nobody can get
 *     between.
 *  2. **reconcile the program id** if `declare_id!` still holds the scaffold's
 *     placeholder while a program keypair exists — `anchor keys sync` — because
 *     `DeclaredProgramIdMismatch` is the number-one first-deploy failure and
 *     the scaffold ships the placeholder on purpose.
 *  3. **spawn**, inside the guest, through the existing machinery.
 *  4. **stream**, 5. **parse**, 6. **publish**.
 *
 * On the spawn: this drives the guest through
 * [to.eyed.seeker.code.terminal.UserlandBackend.execCommand] and
 * [GuestProcess], exactly as `GitClone` does, and *not* through a terminal
 * session. That is not a preference either. A pty re-wraps its output at the
 * terminal's width, and cargo's `--message-format=json` writes one JSON object
 * per line that can be a thousand characters long: through a pty the parser
 * would be handed the same object cut into 80-column pieces. A pipe keeps the
 * lines, and cancellation is the identical problem `GitClone` already solved —
 * proot ignores SIGTERM, so [GuestProcess.terminate] sends SIGQUIT, waits, and
 * only then reaches for SIGKILL.
 *
 * What a build *does* borrow from the terminal is the foreground service: a
 * 71-second build has to survive the screen going off, and
 * [to.eyed.seeker.code.terminal.TerminalService] is what keeps Android's
 * phantom-process reaper off the proot. See
 * [to.eyed.seeker.code.terminal.TerminalPanelState.holdForBackgroundWork].
 *
 * Held outside composition, like `UserlandInstaller` and `GitClone`, because a
 * rotation or a destination switch must not abandon a running build.
 */
object BuildRunner {

    private const val TAG = "seeker-build"

    /** The log the Build destination draws. Cleared at the start of each run. */
    val log = BuildLog()

    /** What the project is, as last detected. Null before the first refresh. */
    var layout: ProjectLayout? by mutableStateOf(null)
        private set

    /** Which build programs the guest has, as last probed. */
    var tools: GuestTools by mutableStateOf(GuestTools.NONE)
        private set

    /** Whether [refresh] has answered once for the current project. */
    var probed: Boolean by mutableStateOf(false)
        private set

    /** Whether `target/deploy/<name>.so` is there, and whether it is current. */
    var freshness: ArtifactFreshness by mutableStateOf(ArtifactFreshness.Missing)
        private set

    /** The problems the last finished run reported, in the order it found them. */
    var lastIssues: List<BuildIssue> by mutableStateOf(emptyList())
        private set

    /** What the last run ran — the head of the "Fix with agent" prompt. */
    var lastCommand: String by mutableStateOf("")
        private set

    /** True between [start] and the run ending, however it ends. */
    var isRunning: Boolean by mutableStateOf(false)
        private set

    /** Which of the three buttons is running, for the Stop row's label. */
    var runningAction: BuildAction? by mutableStateOf(null)
        private set

    // --- the seams other chunks fill -----------------------------------------

    /**
     * Save every dirty buffer in the project and return how many were written.
     * Registered by the Code destination (P2), which is the only code that
     * knows what "dirty" means; called on the **main thread**, because writing
     * a buffer goes through the editor's own state.
     *
     * Null is read as "nothing is open, so nothing is dirty", which is exactly
     * true before Code has ever composed — and is why a build works today,
     * before P2 has landed, rather than waiting for it.
     */
    var saveAll: (suspend () -> Int)? = null

    /**
     * Whether `declare_id!`, the program keypair and Anchor.toml disagree —
     * the full three-way comparison, which needs base58 and lives in P6's
     * `solana/chain/ProgramIds.kt`. Until it is registered, [placeholderId]
     * below answers the one case that does not need any of that and that
     * covers the scaffold's own happy path.
     */
    var idsDisagree: ((ProjectLayout) -> Boolean)? = null

    // --- state -----------------------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * Bumped by every start and every stop, so a run whose kill produced a
     * non-zero status cannot report a failure over whatever the user is doing
     * now. Exactly `GitClone.generation`, for exactly that reason.
     */
    @Volatile
    private var generation = 0

    /** The guest process to signal on [stop]. */
    @Volatile
    private var current: Process? = null

    // --- what the screen asks before it draws ---------------------------------

    /**
     * Re-detect the project, probe the guest and re-stat the artifact.
     * Blocking on all three counts: call it off the main thread.
     *
     * Called when the Build destination is entered and after a run, which is
     * often enough for a value that only changes when the user edits a
     * manifest or finishes the toolchain setup, and rare enough that the proot
     * start-up the probe costs is not on any hot path.
     */
    fun refresh(context: Context, projectRoot: String?) {
        if (projectRoot == null) {
            layout = null
            freshness = ArtifactFreshness.Missing
            probed = false
            return
        }
        val root = File(projectRoot)
        val detected = BuildTasks.detect(root)
        layout = detected
        freshness = BuildTasks.freshness(root, detected.primary)
        // The probe is the expensive half and it says nothing about a project
        // that has nothing to build.
        if (detected.isBuildable) tools = BuildTasks.probe(context)
        probed = true
    }

    /** Re-stat the artifact only — what a finished run needs. */
    fun refreshFreshness() {
        val current = layout ?: return
        freshness = BuildTasks.freshness(File(current.root), current.primary)
    }

    // --- running ----------------------------------------------------------------

    /**
     * Run [action] on the open project. A no-op while something is already
     * running: a second press of ▶ is [stop], and the screen presents it that
     * way rather than queueing a second build.
     *
     * [command] overrides the table — the "run `cargo test` instead" answer to
     * the Anchor-tests-need-Node question, and nothing else.
     */
    fun start(
        context: Context,
        shell: ShellState,
        action: BuildAction,
        command: BuildCommand? = null,
    ) {
        if (isRunning) return
        val current = layout ?: return
        val chosen = command ?: when (action) {
            BuildAction.Build -> BuildTasks.buildCommand(current, tools)
            BuildAction.Test -> BuildTasks.testCommand(current)
            BuildAction.Deploy -> null
        }
        if (action == BuildAction.Deploy) {
            startDeploy(context, shell, current)
            return
        }
        if (chosen == null) {
            Notifications.error(
                "There is nothing here to ${action.label.lowercase()}: " +
                    "no Anchor.toml and no Solana crate",
                key = NOTIFICATION_KEY,
            )
            return
        }
        launch(context, shell, action, current, chosen)
    }

    /**
     * Stop the run: SIGQUIT to proot, a three-second grace, then SIGKILL —
     * [GuestProcess.terminate]. The signalling runs in its own coroutine, a
     * sibling of the run's, so cancelling the run cannot cancel the kill and
     * the main thread never blocks on the grace period.
     */
    fun stop() {
        val running = job?.takeIf { it.isActive } ?: return
        val doomed = current
        current = null
        job = null
        generation++
        scope.launch {
            doomed?.let { GuestProcess.terminate(it) }
            running.cancel()
        }
    }

    private fun launch(
        context: Context,
        shell: ShellState,
        action: BuildAction,
        project: ProjectLayout,
        command: BuildCommand,
    ) {
        val app = context.applicationContext
        val mine = ++generation
        val startedAt = System.currentTimeMillis()

        log.clear()
        // Rule 2 of BuildDiagnostics: the previous run's rows go at the
        // *start* of this one, not when it finishes.
        BuildDiagnostics.clear()
        lastIssues = emptyList()
        lastCommand = command.display
        isRunning = true
        runningAction = action
        shell.build = BuildState.Running(action.progressLabel, startedAt)
        holdService(app, true)
        log.append(BuildLogRow.Command(command.display, startedAt))
        command.note?.let { log.append(BuildLogRow.Note(it)) }

        job = scope.launch {
            // The log coalesces its writes at 10 Hz; without this the last
            // burst of a build that has gone quiet would sit invisible until
            // the next line, which for a link step is a whole minute.
            val ticker = launch {
                while (isActive) {
                    log.flush()
                    delay(FLUSH_TICK_MS)
                }
            }
            // The `finally` is not tidiness. A stop cancels this coroutine, and
            // the cancellation can land while `run` is suspended on the main
            // thread waiting for the saves rather than inside the blocking
            // read — in which case nothing below would run and the screen
            // would sit on "■ Stop" for the rest of the session with no
            // process behind it.
            try {
                val result = runCatching {
                    run(app, shell, action, project, command, startedAt, mine)
                }.getOrElse { error ->
                    Log.e(TAG, "build failed", error)
                    RunResult(exit = -1, issues = emptyList(), message = error.message)
                }
                // A stop already moved on; do not report the failure its own
                // kill produced over whatever the user is looking at now.
                if (generation == mine) {
                    finish(app, shell, action, project, command, result, startedAt)
                }
            } finally {
                ticker.cancel()
                log.flush()
                current = null
                if (generation != mine) finishCancelled(app, shell, startedAt)
            }
        }
    }

    /** The blocking half: save, reconcile, spawn, stream, parse. */
    private suspend fun run(
        context: Context,
        shell: ShellState,
        action: BuildAction,
        project: ProjectLayout,
        command: BuildCommand,
        startedAt: Long,
        generationAtStart: Int,
    ): RunResult {
        // 1. Save every dirty buffer, and *wait*.
        val saved = withContext(Dispatchers.Main) { saveAll?.invoke() ?: 0 }
        if (saved > 0) {
            log.append(
                BuildLogRow.Note(if (saved == 1) "Saved 1 file" else "Saved $saved files")
            )
        }
        if (generation != generationAtStart) return RunResult(-1, emptyList(), null)

        // 2. Reconcile the program id before an Anchor build, never after.
        if (action == BuildAction.Build && needsKeysSync(project)) {
            log.append(
                BuildLogRow.Note(
                    "declare_id! still holds the scaffold's placeholder — running anchor keys sync"
                )
            )
            execute(context, project, "anchor keys sync") { line ->
                log.append(BuildLogRow.Text(line))
            }
            if (generation != generationAtStart) return RunResult(-1, emptyList(), null)
        }

        // 3-5. Spawn, stream, parse.
        val parser = CargoDiagnostics(command.jsonDiagnostics)
        val issues = ArrayList<BuildIssue>()
        fun consume(events: List<BuildLogEvent>) {
            for (event in events) {
                when (event) {
                    is BuildLogEvent.Issue -> {
                        issues.add(event.issue)
                        log.append(BuildLogRow.Issue(event.issue))
                    }
                    is BuildLogEvent.Text -> log.append(BuildLogRow.Text(event.line))
                }
            }
        }
        val exit = execute(context, project, command.line) { line -> consume(parser.feed(line)) }
        consume(parser.flush())
        return RunResult(exit, issues, null)
    }

    /**
     * One command inside the guest, every line handed to [onLine], its exit
     * status returned. Blocking.
     *
     * `/bin/sh -c`, deliberately not the login shell
     * `ShellEnvironment.taskCommand` uses: Debian's `/etc/profile` sets `PATH`
     * unconditionally for root, which would throw away the ordering
     * [BuildTasks.guestEnvironment] exists to establish and take
     * `$CARGO_HOME/bin` — and therefore `cargo-build-sbf` and `anchor` — off
     * the path entirely. A build is not an interactive session and has no
     * business sourcing anybody's profile.
     */
    private fun execute(
        context: Context,
        project: ProjectLayout,
        line: String,
        onLine: (String) -> Unit,
    ): Int {
        val command: ShellCommand = Userland.backend.execCommand(
            context,
            project.root,
            listOf("/bin/sh", "-c", line),
            BuildTasks.guestEnvironment(),
        ) ?: run {
            log.append(BuildLogRow.Note(noUserland(context)))
            return NO_USERLAND
        }
        return GuestProcess.run(
            command,
            onStart = { process -> current = process },
            onRecord = onLine,
        )
    }

    // --- finishing ---------------------------------------------------------------

    private data class RunResult(val exit: Int, val issues: List<BuildIssue>, val message: String?)

    private fun finish(
        context: Context,
        shell: ShellState,
        action: BuildAction,
        project: ProjectLayout,
        command: BuildCommand,
        result: RunResult,
        startedAt: Long,
    ) {
        val elapsed = System.currentTimeMillis() - startedAt
        val errors = result.issues.count { it.severity == DiagnosticSeverity.Error }
        val warnings = result.issues.count { it.severity == DiagnosticSeverity.Warning }
        val failed = result.exit != 0

        lastIssues = result.issues
        BuildDiagnostics.publish(project.root, producerTag(command), result.issues)
        refreshFreshness()

        log.append(
            BuildLogRow.Summary(
                summaryLine(action, failed, errors, warnings, elapsed),
                failed,
            )
        )
        log.flush()

        shell.build = if (failed) {
            BuildState.Failed(errors, warnings)
        } else {
            BuildState.Succeeded(System.currentTimeMillis())
        }
        isRunning = false
        runningAction = null
        holdService(context, false)

        if (failed) {
            // The one thing worth interrupting for: a build you walked away
            // from and that failed. The action goes where the output is.
            Notifications.error(
                message = "${action.label} failed" +
                    if (errors > 0) " · $errors ${plural(errors, "error")}" else "",
                action = NotificationAction("Show output") { shell.show(Destination.Build) },
                key = NOTIFICATION_KEY,
            )
        }
    }

    /** A stopped run says so and leaves no failure behind. */
    private fun finishCancelled(context: Context, shell: ShellState, startedAt: Long) {
        log.append(
            BuildLogRow.Summary(
                "stopped · ${duration(System.currentTimeMillis() - startedAt)}",
                failed = false,
            )
        )
        log.flush()
        isRunning = false
        runningAction = null
        shell.build = BuildState.Idle
        holdService(context, false)
    }

    // --- deploy -------------------------------------------------------------------

    /**
     * Deploy is P6's. This is the seam and the honest degradation: with no
     * [Deployer] registered there is no wallet, no cluster and no signer, and
     * the button says that instead of running a CLI that would fail on a
     * keypair file nobody has created.
     */
    private fun startDeploy(context: Context, shell: ShellState, project: ProjectLayout) {
        val deployer = Deployers.current
        val program = project.primary
        if (deployer == null || program == null) {
            Notifications.error(
                "Deploying needs a cluster and a wallet, which are not set up yet",
                key = NOTIFICATION_KEY,
            )
            return
        }
        val app = context.applicationContext
        val mine = ++generation
        val startedAt = System.currentTimeMillis()
        log.clear()
        lastCommand = "deploy ${program.artifactPath}"
        isRunning = true
        runningAction = BuildAction.Deploy
        shell.build = BuildState.Running(BuildAction.Deploy.progressLabel, startedAt)
        holdService(app, true)
        log.append(BuildLogRow.Command("deploy ${program.artifactPath}", startedAt))
        job = scope.launch {
            // Same `finally` as a build's, for the same reason: a cancelled
            // deploy must not leave the screen showing Stop forever.
            try {
                val outcome = runCatching {
                    deployer.deploy(app, project, program) { line ->
                        log.append(BuildLogRow.Text(line))
                    }
                }.getOrElse { Result.failure(it) }
                if (generation != mine) return@launch
                val elapsed = System.currentTimeMillis() - startedAt
                val failed = outcome.isFailure
                outcome.exceptionOrNull()?.message?.let { log.append(BuildLogRow.Text(it)) }
                outcome.getOrNull()?.let { log.append(BuildLogRow.Note(it)) }
                log.append(
                    BuildLogRow.Summary(
                        summaryLine(BuildAction.Deploy, failed, 0, 0, elapsed),
                        failed,
                    )
                )
                shell.build = if (failed) {
                    BuildState.Failed(1, 0)
                } else {
                    BuildState.Succeeded(System.currentTimeMillis())
                }
                isRunning = false
                runningAction = null
                holdService(app, false)
            } finally {
                log.flush()
                if (generation != mine) finishCancelled(app, shell, startedAt)
            }
        }
    }

    // --- small pure things --------------------------------------------------------

    /**
     * Whether an Anchor build should run `anchor keys sync` first.
     *
     * The cheap half of the three-way reconciliation, and the only half that
     * needs no base58: if `declare_id!` still holds the scaffold's placeholder
     * *and* a program keypair exists, they certainly disagree. The full
     * comparison — declare_id! against the keypair's real address against
     * Anchor.toml — is [idsDisagree], which P6 registers.
     */
    private fun needsKeysSync(project: ProjectLayout): Boolean {
        if (project.framework == ProjectFramework.Native) return false
        idsDisagree?.let { return it(project) }
        val root = File(project.root)
        val hasKeypair = project.programs.any {
            File(root, "target/deploy/${it.moduleName}-keypair.json").isFile
        }
        if (!hasKeypair) return false
        return project.programs.any { program ->
            val lib = File(root, "programs/${program.crateName}/src/lib.rs")
            runCatching { lib.readText() }.getOrNull()
                ?.contains(SolanaProgram.PLACEHOLDER_ID) == true
        }
    }

    /** `cargo · anchor build` — the tag every published row carries. */
    private fun producerTag(command: BuildCommand): String = "cargo · ${command.display}"

    /**
     * Keep the terminal's foreground service up while a build runs, even
     * though a build owns no terminal session. Android kills the child
     * processes of a cached app, and a proot compiling for 71 seconds is
     * exactly the shape of thing the phantom-process reaper takes.
     */
    private fun holdService(context: Context, held: Boolean) {
        runCatching { TerminalSessions.of(context).holdForBackgroundWork(held) }
    }

    private fun noUserland(context: Context): String =
        if (!Userland.backend.isSupported) {
            "This edition has no Linux userland, so there is nothing here that can compile " +
                "a Solana program."
        } else if (Userland.backend.state(context) !is UserlandState.Ready) {
            "${Userland.backend.displayName} is not installed yet — open Shell to install it, " +
                "then build again."
        } else {
            "The userland refused to start the command."
        }

    private const val NOTIFICATION_KEY = "solana:build"
    private const val FLUSH_TICK_MS = 150L

    /** What [execute] returns when there is no guest to run anything in. */
    const val NO_USERLAND = -2

    /**
     * `failed · 1 error, 1 warning · 1m11s` — docs/UI.md's own words, and a
     * pure function so the phrasing is a test rather than a screenshot.
     */
    fun summaryLine(
        action: BuildAction,
        failed: Boolean,
        errors: Int,
        warnings: Int,
        elapsedMs: Long,
    ): String {
        val head = if (failed) "failed" else when (action) {
            BuildAction.Build -> "built"
            BuildAction.Test -> "tested"
            BuildAction.Deploy -> "deployed"
        }
        val counts = buildList {
            if (errors > 0) add("$errors ${plural(errors, "error")}")
            if (warnings > 0) add("$warnings ${plural(warnings, "warning")}")
        }
        return buildString {
            append(head)
            if (counts.isNotEmpty()) {
                append(" · ")
                append(counts.joinToString(", "))
            }
            append(" · ")
            append(duration(elapsedMs))
        }
    }

    /** `1m11s`, `34s`, `1h02m` — what a summary line prints. */
    fun duration(elapsedMs: Long): String {
        val seconds = (elapsedMs / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m${(seconds % 60).toString().padStart(2, '0')}s"
            else -> "${seconds / 3600}h${((seconds % 3600) / 60).toString().padStart(2, '0')}m"
        }
    }

    /** `0:38` — what the Stop row's elapsed counter prints. */
    fun clock(elapsedMs: Long): String {
        val seconds = (elapsedMs / 1000).coerceAtLeast(0)
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    private fun plural(count: Int, word: String) = if (count == 1) word else "${word}s"
}

/**
 * How a program gets on chain. Implemented by P6 (`solana/chain/ProgramDeploy.kt`)
 * and called only from here, so that the two halves of the deploy story —
 * the keypair-signer CLI path that works on day one and the Kotlin-side
 * Seed Vault path that comes after it — are one seam to the build layer.
 */
interface Deployer {

    /** What the Deploy button and the sheet name as the signer. */
    val label: String

    /**
     * Deploy [program]'s artifact, reporting progress line by line. The result
     * is the transaction signature on success; a failure carries the message
     * the sheet shows. Called off the main thread; may take minutes.
     */
    suspend fun deploy(
        context: Context,
        project: ProjectLayout,
        program: ProgramTarget,
        onLine: (String) -> Unit,
    ): Result<String>
}

/** The registry P6 writes to and [BuildRunner] reads. */
object Deployers {
    var current: Deployer? by mutableStateOf(null)
}

/**
 * `[ Fix with agent ]`.
 *
 * The Agent destination (P3) registers [seed], which puts text in the composer
 * and gives it focus; the Build screen calls it and then switches destination.
 * Null until P3 has composed once — and the button degrades rather than
 * disappearing, because the text it would have sent is worth having on the
 * clipboard even with no agent installed at all (docs/UI.md, "First run":
 * "the other two destinations are complete without it").
 */
object AgentFix {
    var seed: ((String) -> Unit)? by mutableStateOf(null)
}
