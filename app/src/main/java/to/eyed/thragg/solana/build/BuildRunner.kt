package to.eyed.thragg.solana.build

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
import to.eyed.thragg.solana.templates.SolanaProgram
import to.eyed.thragg.solana.toolchain.ToolchainManifest
import to.eyed.thragg.terminal.GuestProcess
import to.eyed.thragg.terminal.ShellCommand
import to.eyed.thragg.terminal.TerminalSessions
import to.eyed.thragg.terminal.Userland
import to.eyed.thragg.terminal.UserlandState
import to.eyed.thragg.ui.shell.BuildState
import to.eyed.thragg.ui.shell.Destination
import to.eyed.thragg.ui.editor.DiagnosticSeverity
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.shell.build.ShellModes
import to.eyed.thragg.ui.workspace.NotificationAction
import to.eyed.thragg.ui.workspace.Notifications
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
 *     placeholder while a program keypair exists — `anchor keys sync`, then
 *     the Anchor.toml table and a Seahorse program's Python, which keys sync
 *     leaves alone ([programIdsSync]) — because `DeclaredProgramIdMismatch`
 *     is the number-one first-deploy failure and the scaffold ships the
 *     placeholder on purpose.
 *  3. **spawn**, inside the guest, through the existing machinery — for an
 *     Anchor `Test`, after `yarn install` and the wallet file it needs
 *     ([prepareAnchorTest]).
 *  4. **stream**, 5. **parse**, 6. **publish**.
 *
 * On the spawn: this drives the guest through
 * [to.eyed.thragg.terminal.UserlandBackend.execCommand] and
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
 * [to.eyed.thragg.terminal.TerminalService] is what keeps Android's
 * phantom-process reaper off the proot. See
 * [to.eyed.thragg.terminal.TerminalPanelState.holdForBackgroundWork].
 *
 * Held outside composition, like `UserlandInstaller` and `GitClone`, because a
 * rotation or a destination switch must not abandon a running build.
 */
object BuildRunner {

    private const val TAG = "thragg-build"

    /** The log the Build destination draws. Cleared at the start of each run. */
    val log = BuildLog()

    /** What the project is, as last detected. Null before the first refresh. */
    var layout: ProjectLayout? by mutableStateOf(null)
        private set

    /** Which build programs the guest has, as last probed. */
    var tools: GuestTools by mutableStateOf(GuestTools.NONE)
        private set

    /**
     * The manifest's platform-tools release tag, read once per [refresh] so
     * [BuildTasks.buildCommand] can pass `--tools-version` — the flag whose
     * absence sent the rehearsal's first build into a 27-minute download of a
     * toolchain the phone already had. Null only when the manifest asset
     * itself cannot be read, in which case the command degrades to the
     * cache-seeding guard alone rather than refusing to build.
     */
    private var platformToolsVersion: String? = null

    /** The manifest's `toolsCacheSeeds`, for the guard — see [BuildTasks.toolchainGuard]. */
    private var toolsCacheSeeds: List<String> = emptyList()

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

    /**
     * What the last finished run was and how it ended — the failure card's
     * whole subject.
     *
     * It lives here rather than on `BuildState.Failed` because the verdict on
     * [ShellState] is read by the nav bar's badge and says only "something
     * failed"; the card has to say *which verb* failed and offer to run that
     * one again. Before this, a failed deploy and a failed test were both
     * titled "The build failed" and both retried a build (QA G-02).
     */
    var lastFailure: FailureFacts? by mutableStateOf(null)
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

    /**
     * Point the files `anchor keys sync` leaves behind at the program
     * keypair, returning the project-relative files rewritten. Registered
     * with [idsDisagree] by the chain layer, which owns the keypair reading.
     * It exists because `keys sync` stops at `lib.rs`: it rewrites the
     * Anchor.toml table for the provider's cluster only when that table is
     * already there (the template writes `[programs.localnet]` alone, so a
     * devnet project's file kept the placeholder — seen on the Seeker
     * 2026-09-08), and a Seahorse `lib.rs` is regenerated from the Python
     * by the very build that follows the sync (see
     * `ProgramIds.syncProgramIds`). Anchor and Seahorse builds both run it.
     */
    var programIdsSync: ((ProjectLayout) -> List<String>)? = null

    /**
     * Put the wallet Anchor.toml names where `anchor test` will look for it,
     * returning true when the file is there afterwards. Registered by the
     * chain layer (`ChainSeams`), which owns the key: the scaffold's
     * `[provider] wallet` is `~/.config/solana/id.json`, so the guest file
     * `/root/.config/solana/id.json` must hold a Solana keypair (the 64-byte
     * JSON array) or every test's first transaction fails to sign. What the
     * chain layer writes there is the app's own deploy key — the devnet
     * throwaway that pays for deploys (docs/CHAIN.md, "Two keys, one
     * prompt") — because it is the key Deploy pays with, so it is the one
     * that holds SOL when anything on this phone does. (It exists whether or
     * not a deploy has happened; a fresh one holds nothing, and the log says
     * where it gets funded.) Null before the
     * chain layer has registered, and read as "no wallet": the run still
     * goes ahead and the log says what will happen.
     */
    var testWallet: ((Context) -> Boolean)? = null

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
    fun refresh(context: Context, projectRoot: String?, probeTools: Boolean = true) {
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
        // The probe is the expensive half — it starts a proot — and it says
        // nothing about a project that has nothing to build. It is also not a
        // fact about the *project*: `tools` is what the guest has, one answer
        // for the whole device, which is why a project switch can ask for it
        // to be skipped (`probeTools = false`) and still get a correct build
        // command. It is taken anyway the first time in a process, because
        // "no tools" and "not asked yet" are the same value.
        if (detected.isBuildable && (probeTools || tools == GuestTools.NONE)) {
            tools = BuildTasks.probe(context)
        }
        val manifest = runCatching { ToolchainManifest.load(context) }.getOrNull()
        platformToolsVersion = manifest?.platformToolsVersion
        toolsCacheSeeds = manifest?.toolsCacheSeeds.orEmpty()
        probed = true
    }

    /** Re-stat the artifact only — what a finished run needs. */
    fun refreshFreshness() {
        val current = layout ?: return
        freshness = BuildTasks.freshness(File(current.root), current.primary)
    }

    /**
     * Let go of the open project: stop whatever is running and drop every
     * fact that was about it.
     *
     * Called when the shell stops pointing at a project — a switch, a delete
     * — and it is the whole of QA B-10. Everything below was per-project
     * state that nothing cleared: the log island, the "N warnings" figure,
     * the Problems rows, the last command the Fix-with-agent prompt quotes,
     * and — worst — [layout], which is the tree ▶ builds in. A switch that
     * left it alone built the *previous* project from the new project's
     * editor and published its diagnostics over the new project's file.
     *
     * The guest tools survive: they are a fact about the device, not about
     * the project ([refresh]).
     *
     * Main thread — it only writes state and signals a process.
     */
    fun forget() {
        stop()
        log.clear()
        log.flush()
        BuildDiagnostics.clear()
        lastIssues = emptyList()
        lastCommand = ""
        lastFailure = null
        layout = null
        freshness = ArtifactFreshness.Missing
        probed = false
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
        val root = shell.project?.rootPath
        val current = layout
        // The one rule that makes ▶ trustworthy: a run happens in the project
        // the shell is showing, or it does not happen and says why. `layout`
        // is re-pointed where a project is opened (`openProjectInShell`), so
        // the only way here is the moment between the two — and the editor's
        // ▶ used to answer that moment by silently building the *previous*
        // project (QA B-10, G-01).
        if (root == null) {
            Notifications.error(
                "No project is open, so there is nothing to ${action.label.lowercase()}",
                key = NOTIFICATION_KEY,
            )
            return
        }
        if (current == null || current.root != root) {
            Notifications.info(
                "Still opening ${File(root).name} — ${action.label} again in a moment",
                key = NOTIFICATION_KEY,
            )
            return
        }
        val chosen = command ?: when (action) {
            BuildAction.Build -> BuildTasks.buildCommand(current, tools, platformToolsVersion, toolsCacheSeeds)
            BuildAction.Test -> BuildTasks.testCommand(current, platformToolsVersion, toolsCacheSeeds)
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
        lastFailure = null
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
                // `mine + 1` is "the stop that killed me, and nothing since".
                // The kill's grace period is three seconds, and a run started
                // inside it — switch project, build the new one — would
                // otherwise have its "running" state wiped by the corpse of
                // the run before it, leaving a build going with no Stop on
                // screen (QA B-10c).
                if (generation == mine + 1) finishCancelled(app, shell, startedAt)
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

        // 2a. The app's own id sync, BEFORE `anchor keys sync`, because it
        // is the one that can run when there is no keypair yet: it makes the
        // keypair (the same file the build would make) and points lib.rs,
        // Anchor.toml and a Seahorse program's Python at it. Measured on the
        // Seeker 2026-09-08: a fresh Seahorse scaffold's first build failed
        // with anchor's "Program ID mismatch" because nothing had synced
        // before the build generated the key — and it could not have.
        // Every framework we can recognise, not only the two with an
        // Anchor.toml: a Native program that carries a `declare_id!` — cloned,
        // hand-written, or made by an older Thragg — disagrees with the
        // keypair this build generates, and the Deploy sheet's "rebuild syncs
        // them" was a lie until the sync was allowed to run for it (QA B-05).
        // `ProgramIds.syncProgramIds` answers `emptyList()` for `Unknown`, so
        // naming it here is belt and braces.
        val ourIds = idsAreOurs(project)
        if (action == BuildAction.Build && !ourIds && project.framework != ProjectFramework.Unknown) {
            // Somebody else's repository, with somebody else's id committed in
            // it. Rewriting that id is a change to *their* source that nobody
            // asked for and that `git status` reports as your work — measured
            // on a public Anchor clone, whose first build silently rewrote
            // `declare_id!` and added a `[programs.devnet]` table disagreeing
            // with the committed `[programs.localnet]` (QA P-12).
            log.append(
                BuildLogRow.Note(
                    "This is a clone with a program id committed in it, so the id is left " +
                        "exactly as the repository has it. Deploy generates a keypair and " +
                        "syncs the id when you ask it to."
                )
            )
        }
        if (action == BuildAction.Build && ourIds && project.framework != ProjectFramework.Unknown) {
            val synced = programIdsSync?.invoke(project).orEmpty()
            val (keypairs, named) = synced.partition { it.endsWith("-keypair.json") }
            if (keypairs.isNotEmpty()) {
                log.append(BuildLogRow.Note("Generated ${keypairs.joinToString(" and ")} — the program's id"))
            }
            if (named.isNotEmpty()) {
                val verb = if (named.size == 1) "names" else "name"
                log.append(
                    BuildLogRow.Note("${named.joinToString(" and ")} now $verb the program keypair")
                )
            }
        }
        // 2. Reconcile the program id before an Anchor build, never after.
        if (action == BuildAction.Build && ourIds && needsKeysSync(project)) {
            log.append(
                BuildLogRow.Note(
                    "declare_id! still holds the scaffold's placeholder — running anchor keys sync"
                )
            )
            // Behind the same guard the build itself gets: `keys sync` runs
            // `cargo metadata` through rustup's shim, and the first build's
            // cargo-build-sbf has just *uninstalled* the linked `solana`
            // toolchain — exactly the wound the guard repairs. Unguarded,
            // the second build of every fresh Anchor project died here with
            // "override toolchain 'solana' is not installed" (seen live,
            // 2026-09-01) before the guarded build command could heal it.
            execute(
                context,
                project,
                BuildTasks.toolchainGuard(platformToolsVersion, toolsCacheSeeds) + "anchor keys sync",
            ) { line ->
                log.append(BuildLogRow.Text(line))
            }
            if (generation != generationAtStart) return RunResult(-1, emptyList(), null)
        }

        // 2c. An Anchor test run has two things a build does not: a Node
        // dependency tree and a wallet file. Both are settled here, before
        // the command, so a failure is a sentence in the log rather than a
        // stack trace from ts-mocha.
        if (action == BuildAction.Test && command.display == BuildTasks.ANCHOR_TEST) {
            val prepared = prepareAnchorTest(context, project, generationAtStart)
            if (prepared != null) return prepared
        }

        // 3-5. Spawn, stream, parse.
        val parser = CargoDiagnostics(command.jsonDiagnostics)
        val issues = ArrayList<BuildIssue>()
        // [redraw] is true for a record the program ended with `\r` — a line it
        // is drawing in place — which the log shows as one live row rather
        // than as a row per frame. A diagnostic found in one is still a
        // diagnostic.
        fun consume(events: List<BuildLogEvent>, redraw: Boolean = false) {
            for (event in events) {
                when (event) {
                    is BuildLogEvent.Issue -> {
                        issues.add(event.issue)
                        log.append(BuildLogRow.Issue(event.issue))
                    }
                    is BuildLogEvent.Text ->
                        if (redraw) log.progress(BuildLogRow.Progress(event.line))
                        else log.append(BuildLogRow.Text(event.line))
                }
            }
        }
        // Carriage-return redraws — cargo-build-sbf's tools download counting
        // bytes for minutes — go through the throttle: without it the 27-min
        // rehearsal download showed a log with nothing on it but the command,
        // and *with* it but unthrottled it would be a thousand rows of the
        // same byte counter. Redraws still go through the parser, because the
        // no-line-is-ever-lost contract (CargoDiagnostics) has no exception
        // for lines that happened to end in \r.
        val throttle = ProgressThrottle()
        val exit = execute(
            context,
            project,
            command.line,
            onProgress = { line ->
                throttle.progress(line, System.currentTimeMillis())
                    ?.let { consume(parser.feed(it), redraw = true) }
            },
        ) { line ->
            // Order matters: the redraw the throttle is holding happened
            // before this line did.
            throttle.drain()?.let { consume(parser.feed(it), redraw = true) }
            consume(parser.feed(line))
        }
        throttle.drain()?.let { consume(parser.feed(it), redraw = true) }
        consume(parser.flush())
        return RunResult(exit, issues, null)
    }

    /**
     * What `anchor test` needs that `anchor build` does not, done before the
     * run. Null when the test can go ahead; a [RunResult] when it cannot.
     *
     *  1. **`yarn install`** when `node_modules/.bin/ts-mocha` is missing. The scaffold's
     *     `[scripts] test` is `yarn run ts-mocha …`, and a fresh clone or a
     *     fresh scaffold has no `node_modules` — yarn would fail with
     *     "Couldn't find the binary ts-mocha", which explains nothing. Run
     *     through the same [execute] path as the test, so it streams into the
     *     log and Stop kills it. Network, and a minute or two the first time;
     *     never again for this project unless `node_modules` is deleted or
     *     was left half-written by a stopped install.
     *  2. **The wallet.** [testWallet] writes the deploy key to the path
     *     Anchor.toml names; without one the tests run unsigned and fail on
     *     their first transaction, and the log says so before they do.
     *  3. **A reminder of what is on chain.** There is no local validator and
     *     no deploy in this run (`--skip-deploy`): the tests call the program
     *     Anchor.toml's `[provider] cluster` already has under the id in
     *     `[programs.<cluster>]`, which Deploy put there. A test against a
     *     program that was edited but not redeployed tests the old program,
     *     and an id nothing was deployed to fails as "program not found".
     */
    private fun prepareAnchorTest(
        context: Context,
        project: ProjectLayout,
        generationAtStart: Int,
    ): RunResult? {
        val root = File(project.root)
        // The binary the script runs, not the directory: a `yarn install`
        // stopped or cut off by the network leaves `node_modules/` half made,
        // and a directory test would call that done.
        if (!File(root, "node_modules/.bin/ts-mocha").exists()) {
            log.append(
                BuildLogRow.Note(
                    "First test run: installing the project's JavaScript dependencies with " +
                        "yarn install. This needs the network and takes a minute or two; " +
                        "it happens once per project."
                )
            )
            log.append(BuildLogRow.Command("yarn install", System.currentTimeMillis()))
            val exit = execute(context, project, "yarn install") { line ->
                log.append(BuildLogRow.Text(line))
            }
            if (generation != generationAtStart) return RunResult(-1, emptyList(), null)
            if (exit != 0) {
                log.append(
                    BuildLogRow.Note(
                        "yarn install exited $exit, so the tests cannot run: ts-mocha and " +
                            "@coral-xyz/anchor are not installed. Check the network, or run " +
                            "`yarn install` in the Shell to see the full output, then Test again."
                    )
                )
                return RunResult(exit, emptyList(), null)
            }
        }

        val wallet = testWallet?.invoke(context) == true
        log.append(
            BuildLogRow.Note(
                if (wallet) {
                    "The wallet Anchor.toml names (~/.config/solana/id.json) is this app's " +
                        "deploy key: the tests pay with the same key Deploy pays with, so it " +
                        "needs SOL on this cluster — a Deploy tops it up by itself, and Wallet " +
                        "(in this tab's menu) has Mine 5 SOL on devnet."
                } else {
                    "No wallet for the tests — nothing could be written to " +
                        "~/.config/solana/id.json, so the tests will run unsigned and fail on " +
                        "the first transaction."
                }
            )
        )

        log.append(
            BuildLogRow.Note(
                "There is no local validator and no deploy in this run: the tests call the " +
                    "program already deployed under Anchor.toml's [provider] cluster, so " +
                    "Deploy must have run first — and again after any change to the program."
            )
        )
        return null
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
        onProgress: ((String) -> Unit)? = null,
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
            onCarriage = onProgress,
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
        // Seahorse's exit code lies on a first build (BuildTasks.seahorseExitIsFalseFailure).
        val artifactModifiedAt = project.primary
            ?.let { File(project.root, it.artifactPath).lastModified() } ?: 0L
        val falseFailure = action == BuildAction.Build &&
            BuildTasks.seahorseExitIsFalseFailure(
                project.framework, result.exit, errors, artifactModifiedAt, startedAt,
            )
        if (falseFailure) {
            log.append(
                BuildLogRow.Note(
                    "seahorse build exited ${result.exit}, but anchor build wrote the program: " +
                        "Seahorse reads any \"error\" in cargo's output — a crate named " +
                        "solana-program-error is enough — as a failure. Trusting the artifact."
                )
            )
        }
        val failed = result.exit != 0 && !falseFailure

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
        // Which verb failed, and — when the compiler had nothing to say — the
        // line that did. A test that died with "Attempt to load a program
        // that does not exist" used to be reported as "anchor test reported 8
        // warnings" (QA G-02).
        lastFailure = if (failed) {
            FailureFacts(
                action = action,
                errors = errors,
                warnings = warnings,
                detail = if (errors == 0) failureDetail(textLines()) else null,
            )
        } else {
            null
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
                // The Build destination has two modes and the mode is
                // remembered per project: a toast that only names the
                // destination lands on the *terminal* whenever a `cargo
                // install` or a `git` run left the user in Shell mode, which
                // is not where the failing build's output is and on a fresh
                // terminal is nothing at all (QA P-21). Name the mode too.
                action = NotificationAction("Show output") {
                    ShellModes.set(shell.project?.rootPath, false)
                    shell.show(Destination.Build)
                },
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
        lastFailure = null
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
                // A deploy never reaches a compiler: its failure is a chain
                // failure, and what it says is the exception's own sentence.
                lastFailure = if (failed) {
                    FailureFacts(
                        action = BuildAction.Deploy,
                        errors = 0,
                        warnings = 0,
                        detail = outcome.exceptionOrNull()?.message?.trim()?.take(DETAIL_MAX)
                            ?: failureDetail(textLines()),
                    )
                } else {
                    null
                }
                isRunning = false
                runningAction = null
                holdService(app, false)
            } finally {
                log.flush()
                if (generation == mine + 1) finishCancelled(app, shell, startedAt)
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
    /**
     * Whether this project's program id is *ours to write*.
     *
     * Three states, and only the first is somebody else's: a git worktree we
     * did not scaffold, whose `declare_id!` holds a real committed id and
     * which has no program keypair here. Ours are the rest — a scaffold (no
     * repository yet, or one this app made and whose id it wrote), a project
     * whose keypair is already in `target/deploy` (that id came from this
     * phone), and one still holding the template's placeholder, which is
     * nobody's id.
     *
     * The keypair is the decisive fact: it is what a deploy signs with, so a
     * project that has one has already had its id decided here.
     */
    private fun idsAreOurs(project: ProjectLayout): Boolean {
        val root = File(project.root)
        if (project.programs.isEmpty()) return true
        if (!File(root, ".git").exists()) return true
        val hasKeypair = project.programs.any {
            File(root, "target/deploy/${it.moduleName}-keypair.json").isFile
        }
        if (hasKeypair) return true
        // A placeholder is not an id anybody committed on purpose.
        return project.programs.any { program ->
            val lib = File(root, "programs/${program.crateName}/src/lib.rs")
            val python = File(root, "programs_py/${program.moduleName}.py")
            listOf(lib, python).any { file ->
                runCatching { file.readText() }.getOrNull()
                    ?.contains(SolanaProgram.PLACEHOLDER_ID) == true
            }
        }
    }

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

    /** The log's plain output lines, newest last — what [failureDetail] reads. */
    private fun textLines(): List<String> = log.rows.mapNotNull { row ->
        when (row) {
            is BuildLogRow.Text -> row.text
            is BuildLogRow.Progress -> row.text
            else -> null
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
            "The Linux guest is not available, so there is nothing here that can compile " +
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
     * The one line of a log worth putting on the failure card, when the
     * compiler produced no diagnostics at all.
     *
     * The last line git, anchor or the chain marked — `error`, `failed`,
     * `fatal`, `panicked` — and the last line said at all when nothing is
     * marked. Lines still carrying rustc's SGR escapes are skipped: the card
     * is Material prose and a paste of control bytes is not a sentence
     * (AnsiText.kt renders those, in the log, where they belong).
     *
     * Pure, because it is a sentence the product prints (BuildFailureTest).
     */
    fun failureDetail(lines: List<String>): String? {
        val clean = lines
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains('\u001B') }
        if (clean.isEmpty()) return null
        val marked = clean.lastOrNull { line ->
            DETAIL_MARKERS.any { line.contains(it, ignoreCase = true) }
        }
        val chosen = marked ?: clean.last()
        return if (chosen.length <= DETAIL_MAX) chosen else chosen.take(DETAIL_MAX).trimEnd() + "…"
    }

    /** What marks a line as the reason a run ended — see [failureDetail]. */
    private val DETAIL_MARKERS = listOf("error", "failed", "fatal", "panicked", "not exist")

    /** How much of a log line the failure card will carry. */
    private const val DETAIL_MAX = 160

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
 * How the last run ended, when it ended badly.
 *
 * The card over the log is built from exactly this and nothing else, which is
 * what keeps its title, its body and its Retry talking about the same run: the
 * verb that failed, the compiler's counts, and — for a failure the compiler
 * never saw — the line that explains it.
 */
data class FailureFacts(
    val action: BuildAction,
    val errors: Int,
    val warnings: Int,
    /** The log's own last word, when there are no diagnostics to quote. */
    val detail: String?,
)

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
