package to.eyed.thragg.solana.build

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import to.eyed.thragg.core.SafeDelete
import to.eyed.thragg.solana.chain.BackgroundWork
import to.eyed.thragg.solana.templates.SolanaFramework
import to.eyed.thragg.solana.templates.SolanaProgram
import to.eyed.thragg.solana.templates.SolanaScaffold
import to.eyed.thragg.solana.templates.TemplateFile
import to.eyed.thragg.solana.toolchain.SolanaToolchain
import to.eyed.thragg.solana.toolchain.ToolchainInstaller
import to.eyed.thragg.solana.toolchain.ToolchainManifest
import to.eyed.thragg.terminal.GuestProcess
import to.eyed.thragg.terminal.Userland
import to.eyed.thragg.terminal.UserlandState
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Compiles the scaffolds' dependency graphs into the shared build cache once
 * per phone, in the background, so the first real build of a new project is
 * only the program crate.
 *
 * The numbers this exists for (docs/SOLANA.md, "Build cache", measured on the
 * Seeker 2026-09-19): the first Anchor project with an empty cache is
 * **4 min 54 s** (4 min 51 s on a second cold run), the second — same
 * scaffold, cache holding its dependencies — is **51 s** with rust-analyzer
 * holding the cache's `debug` lock part of the way, **16 s** with it idle;
 * `cargo build-sbf` itself is 3.41 s of that. Every dependency in that gap
 * is decided by the scaffold's manifests, which are this app's own strings;
 * nothing about them waits for a user. So the phone builds them at a moment
 * nobody is watching, and the first project's Build button gets the short
 * row.
 *
 * What it does: renders the Anchor and Native scaffolds
 * ([SolanaFramework.files], fixed dummy names, devnet) into two throwaway
 * directories *inside the rootfs* under a per-run directory ([runDir]),
 * runs the same lines the Build button runs — `anchor build` (its IDL host
 * build included, which is the compile the Test button needs) and `cargo
 * build-sbf` followed by `cargo test --no-run` (the host-side dev profile of
 * `solana-program`, which Native's Test button compiles) — through the same
 * guard and the same environment, under `nice -n 10` so a foreground build
 * or the editor wins the CPU, and deletes its run directory afterwards. The
 * cache keeps the artifacts; the projects' own `target/` is junk.
 *
 * What it must never do: run beside a user's build — the two would share the
 * cache directory and the loser waits on cargo's lock for minutes, and the
 * build the user is *watching* must be the winner ([onBuildStarted] kills
 * this the moment [BuildRunner] launches, and the build waits for the
 * corpse) — or beside the toolchain installer ([ToolchainInstaller] kills
 * it the same way before its first step), or without both drivers in. It
 * never gates on the network being *there*: the first fetch needs crates.io
 * and there is no honest way to know that before trying, so a refusal is a
 * line in the log, not a dialog. It does gate on the network being
 * *metered* for the automatic triggers — 582 MB of crates is not a
 * download to start on someone's data plan unasked — and the row's own
 * button ignores that gate, because pressing it is the asking.
 *
 * "Primed" is a key and a directory, not a flag: [key] is a sha256 over
 * everything that decides what the cache would hold — the rendered
 * manifests of both scaffolds, the arch, the tools version and the
 * installed revisions of the two drivers and platform-tools — recorded in
 * `<filesDir>/solana-build-cache.json` when a run lands, and [isPrimed]
 * believes the record only while the cache directory still holds compiled
 * artifacts ([cacheHasContent]). A scaffold bump, a driver update or a
 * platform-tools bump changes the key; "free the disk" or a cleanup that
 * took the cache empties the directory; either way the next trigger primes
 * again, and cargo reuses whatever still matches.
 *
 * Held outside composition like [BuildRunner] and [ToolchainInstaller]: the
 * Toolchain screen draws its state and leaves; the work carries on under the
 * foreground service and a wake lock ([BackgroundWork.hold]). Killed anyway,
 * the next run is idempotent — cargo resumes from whatever it had written.
 *
 * ### The lifecycle
 *
 * One monitor guards every transition — a launch, a cancel, a step
 * change, the process slot, the run's own ending — and the blocking parts
 * happen outside it: the evaluation (rendering the scaffolds for the key,
 * statting the rootfs) before, the kill and the wait for the corpse after.
 * What the monitor decides is re-validated inside it against what was read
 * outside, so a build that started during the evaluation is seen.
 *
 * A cancel does not return until the run it killed is *gone*: the guest
 * process has been through [GuestProcess.terminate]'s SIGQUIT → SIGKILL,
 * and the run's coroutine has finished its `finally` — bounded by
 * [CORPSE_WAIT_MS], after which the run is written off as abandoned and
 * logged. Until then [live] stays set and nothing else launches: the
 * alternative is two cargos on one lock, which is the minutes-long stall
 * this whole object exists to prevent. A run that a cancel found between
 * its check and its spawn is caught by the spawn itself: `onStart` reads
 * the flag under the monitor and kills the process it was just handed.
 */
object BuildCachePrimer {

    private const val TAG = "thragg-warm"

    /**
     * Where the warm projects are rendered, as a guest path. Inside the
     * rootfs on purpose: [BuildRunner]'s cwd mapping covers `/projects` only,
     * so a directory under `/opt/solana/build` is the one place the guest
     * and this side agree on without a bind mount. Under the cargo scratch
     * too, so Settings' "free the disk" takes it with the rest and the
     * installer's scratch cleanup ([ToolchainInstaller.cleanCargoScratch])
     * removes a half-rendered one left by a killed run.
     *
     * Each run renders into its own `<WARM_ROOT>/<runId>/` ([runDir]) and
     * deletes only that: a run that was killed with SIGKILL after its
     * successor had already started must not be able to take the
     * successor's files with it, and the successor's sweep of stale
     * siblings ([sweepStale]) is what removes the corpse's directory.
     */
    const val WARM_ROOT = "/opt/solana/build/warm"

    /** The record: `{key, primedAt, elapsedMs}` beside the toolchain's own. */
    private const val RECORD_FILE = "solana-build-cache.json"

    /**
     * `nice -n 10`: ten steps below a build the user pressed ▶ for and below
     * rust-analyzer's check, which share the same eight cores. Priming is
     * the work nobody is waiting for, and CFS gives a nice-10 group roughly a
     * tenth of the CPU a nice-0 one gets when both want it — enough that a
     * foreground build is not noticeably slower, and the whole CPU when
     * nothing else runs.
     */
    const val NICE = "nice -n 10"

    /**
     * How long a cancel waits for the killed run's coroutine to finish —
     * its `finally` deleting the run directory — before writing it off.
     * [GuestProcess.terminate] itself takes at most 6 s (two grace periods),
     * so 10 s is that plus the delete of a rendered scaffold with a partial
     * `target/`; a run still alive after it is a proot that ignored SIGKILL,
     * which nothing here can do more about than log.
     */
    const val CORPSE_WAIT_MS = 10_000L

    /**
     * The wake-lock ceiling for one run: twice [BackgroundWork.hold]'s
     * 30 min default. A run is two scaffolds at `nice -n 10` — the 4 min 54 s
     * was measured with the CPU to itself; under a foreground build it gets
     * a tenth of it — plus, on a phone that has never built, the first
     * fetch of every crate from crates.io, which on a slow link is minutes
     * on its own. 30 min was chosen for a deploy and would cut this one
     * short exactly on the phones that need it most.
     */
    const val WAKE_LOCK_MS = 60L * 60L * 1_000L

    /**
     * The two steps, in run order: Anchor first because it is the 4 min 54 s
     * one, and the one a new project is likeliest to need.
     */
    enum class Step(
        val label: String,
        val framework: SolanaFramework,
        /** The dummy program name — what `SolanaProgram.of` derives the crate from. */
        val programName: String,
        /** The directory under the run's [runDir]. */
        val dirName: String,
    ) {
        Anchor("Anchor", SolanaFramework.Anchor, "thragg-warm-anchor", "anchor"),
        Native("Native", SolanaFramework.Native, "thragg-warm-native", "native");

        /** Guest absolute path of the rendered project in run [runId]. */
        fun guestDir(runId: Long): String = "${runDir(runId)}/$dirName"
    }

    /**
     * The guest directory of one run: [WARM_ROOT] plus the run's id, which
     * is its start in epoch millis — monotonic within a process, unique
     * across process restarts (a counter would restart at 1 and land on a
     * killed predecessor's directory), and the same number the row's clock
     * counts from.
     */
    fun runDir(runId: Long): String = "$WARM_ROOT/$runId"

    /** What the Toolchain screen's row draws. */
    sealed interface State {
        /** Nothing running. The row says whether the cache is primed. */
        data object Idle : State

        /** A run is in flight, on [step]. [startedAt] is wall clock, for the ticking figure. */
        data class Running(val startedAt: Long, val step: Step) : State

        /** The last run landed at [at], taking [elapsedMs]. */
        data class Done(val at: Long, val elapsedMs: Long) : State

        /** The last run did not land, and [message] is its last word. */
        data class Failed(val message: String) : State
    }

    var state: State by mutableStateOf(State.Idle)
        private set

    val isRunning: Boolean get() = state is State.Running

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One run, from its launch to the moment its corpse is confirmed gone.
     * [id] doubles as the start stamp and the directory name ([runDir]).
     */
    private class Run(val id: Long) {
        lateinit var job: Job

        /** The guest process of the step in flight, for the kill. Guarded by [monitor]. */
        var process: Process? = null

        /** Set once, by a cancel, under [monitor]; read by the loop between and after its steps. */
        @Volatile
        var cancelled = false
    }

    /** The one lock — see the class comment, "The lifecycle". */
    private val monitor = Any()

    /**
     * The run that owns the cache right now: from its launch until its
     * coroutine has ended (a landing, or the corpse a cancel waited for).
     * Non-null means "nothing else may launch". Guarded by [monitor].
     */
    private var live: Run? = null

    /**
     * Runs a cancel gave up waiting for. Each removes itself when its
     * coroutine finally ends; while any is here, a successful run does not
     * sweep sibling directories, because one of them may still be written.
     * Guarded by [monitor].
     */
    private val abandoned = mutableSetOf<Run>()

    /** One evaluation at a time; the rest of a recomposition's calls collapse into it. */
    private val evaluating = AtomicBoolean(false)

    /**
     * A trigger that arrived while an evaluation was in flight, kept so it
     * runs afterwards instead of vanishing. One slot is enough: what matters
     * is that the inputs are read again once the in-flight pass — which may
     * be sitting in a ten-second corpse wait — is over, and a manual press
     * must not be swallowed by an automatic pass that then refuses on the
     * metered gate. A manual request wins the slot over an automatic one.
     * Guarded by [monitor].
     */
    private var pending: Pair<String, Boolean>? = null

    // --- the pure half ---------------------------------------------------------

    /** The files [step]'s scaffold writes, with the fixed dummy name and cluster. */
    fun scaffold(step: Step): List<TemplateFile> =
        step.framework.files(SolanaProgram.of(step.programName), SolanaProgram.DEFAULT_CLUSTER)

    /**
     * Every `*.toml` the two scaffolds emit, each path prefixed by its
     * step's directory so the two `Cargo.toml`s stay distinct in the key.
     *
     * TOML only, because that is where the dependency graph is decided:
     * `Cargo.toml` names the crates and versions, `Anchor.toml` names the
     * cluster and the program map. A change to `lib.rs` changes the program
     * crate, which is not what the cache holds.
     */
    fun manifests(): List<TemplateFile> = Step.entries.flatMap { step ->
        scaffold(step)
            .filter { it.path.endsWith(".toml") }
            .map { TemplateFile("${step.dirName}/${it.path}", it.contents) }
    }

    /**
     * The primed key: sha256, hex, over the manifests, the arch, the tools
     * version and the driver revisions, in that order, each field
     * NUL-terminated so a value cannot run into the next.
     *
     * [revisions] is the install record's `id → revision` for the three
     * components whose bytes decide what cargo produces: `cargo-build-sbf`
     * and `anchor` (the drivers, whose defaults and link flags shape the
     * artifacts) and `platform-tools` (the compiler itself — a new rustc is
     * a new cache). Keyed and sorted by id, so the record's map order is not
     * part of the answer; a missing one hashes as absent, which is a
     * different key from any installed revision, as it should be.
     */
    fun key(
        manifests: List<TemplateFile>,
        sbpfArch: String?,
        platformToolsVersion: String?,
        revisions: Map<String, String>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(text: String) {
            digest.update(text.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        for (file in manifests) {
            field(file.path)
            field(file.contents)
        }
        field("arch=${sbpfArch.orEmpty()}")
        field("tools=${platformToolsVersion.orEmpty()}")
        for (id in KEYED_COMPONENTS) field("$id=${revisions[id].orEmpty()}")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The components whose revision is part of the key — see [key]. */
    val KEYED_COMPONENTS: List<String> = listOf("cargo-build-sbf", "anchor", "platform-tools")

    /**
     * The one `sh -c` line a step runs: `cd` into the run's warm project —
     * or stop, so a missing directory can never run cargo in `/root` — then
     * the Build button's guard ([BuildTasks.toolchainGuard], the same
     * repairs a real build gets, silenced), then the Build button's own
     * command under [NICE].
     *
     * The command strings are [BuildTasks]' own, read from each command's
     * `display` so this line and the Build tab's first row cannot drift:
     * [BuildTasks.buildCommand] — `anchor build --arch v3 --tools-version
     * v1.57` for Anchor, `cargo build-sbf …` for Native — and for Native
     * then [BuildTasks.cargoTestNoRunCommand], the host-side compile of the
     * scaffold's dev-dependencies that the Test button's `cargo test` would
     * otherwise do on its first press. The Anchor step's `anchor build`
     * already runs the IDL's host build, so it needs no second command.
     */
    fun stepLine(
        step: Step,
        runId: Long,
        platformToolsVersion: String?,
        sbpfArch: String?,
        seeds: List<String> = emptyList(),
    ): String {
        val dir = step.guestDir(runId)
        val layout = ProjectLayout(
            root = dir,
            framework = when (step) {
                Step.Anchor -> ProjectFramework.Anchor
                Step.Native -> ProjectFramework.Native
            },
            programs = emptyList(),
        )
        val build = BuildTasks.buildCommand(
            layout,
            GuestTools(cargoBuildSbf = true, anchor = true),
            platformToolsVersion,
            sbpfArch,
            seeds,
        ) ?: error("no build command for ${step.label}")
        val commands = buildList {
            add("$NICE ${build.display}")
            if (step == Step.Native) add("$NICE ${BuildTasks.cargoTestNoRunCommand(platformToolsVersion, seeds).display}")
        }
        return "cd $dir || exit 1; " +
            BuildTasks.toolchainGuard(platformToolsVersion, seeds) +
            commands.joinToString(" && ")
    }

    /**
     * Why priming may not start, or null when it may. One place for the
     * rule, so the three triggers and the button cannot each ask half of it.
     *
     * The order is the order of how permanent the answer is: running is
     * "already doing it"; primed is the good reason and the usual one; the
     * next three are "not on this phone yet"; the last three are "not right
     * now", and are the ones a later trigger clears. [metered] is what the
     * automatic triggers pass; the row's button passes false, because a
     * press is the consent the gate exists to get.
     */
    fun startBlocker(
        running: Boolean,
        primed: Boolean,
        cargoBuildSbf: Boolean,
        anchor: Boolean,
        userlandReady: Boolean,
        buildRunning: Boolean,
        installerRunning: Boolean,
        metered: Boolean,
    ): String? = when {
        running -> "priming is already running"
        primed -> "the cache is already primed for this toolchain and these scaffolds"
        !userlandReady -> "the userland is not ready"
        !cargoBuildSbf -> "cargo-build-sbf is not installed"
        !anchor -> "Anchor is not installed"
        installerRunning -> "the toolchain installer is running"
        buildRunning -> "a build is running"
        metered -> "metered"
        else -> null
    }

    /**
     * Whether [cache] — the manifest's `buildCache`, seen from this side —
     * holds compiled artifacts, cheaply and honestly.
     *
     * What a landed run leaves there is cargo's build-dir layout: a profile
     * directory (`release`, `debug`) straight under the root for the host
     * builds — the IDL's, `cargo test --no-run`'s — and `<triple>/release`
     * for the SBF ones; each with a `.fingerprint/` directory, which is what
     * cargo consults to decide that a dependency need not be compiled
     * again. So: any first-level directory with a `.fingerprint` inside, or
     * any second-level one. Two directory listings, no walk, and it cannot
     * say yes over a cache that "free the disk" or the installer's cleanup
     * emptied while the record still names the right key — the case the
     * record alone got wrong.
     */
    fun cacheHasContent(cache: File): Boolean {
        val profiles = cache.listFiles()?.filter { it.isDirectory } ?: return false
        return profiles.any { profile ->
            File(profile, FINGERPRINT).isDirectory ||
                profile.listFiles().orEmpty().any { it.isDirectory && File(it, FINGERPRINT).isDirectory }
        }
    }

    private const val FINGERPRINT = ".fingerprint"

    // --- the record ---------------------------------------------------------------

    /** What the last successful run wrote. */
    data class Record(val key: String, val primedAt: Long, val elapsedMs: Long)

    private fun recordFile(context: Context): File =
        File(context.applicationContext.filesDir, RECORD_FILE)

    /** The record, or null when there is none or it is unreadable. Blocking. */
    fun record(context: Context): Record? = runCatching {
        val file = recordFile(context)
        if (!file.isFile) return@runCatching null
        val json = JSONObject(file.readText())
        Record(
            key = json.getString("key"),
            primedAt = json.getLong("primedAt"),
            elapsedMs = json.optLong("elapsedMs", 0L),
        )
    }.getOrElse { error ->
        Log.w(TAG, "unreadable build cache record; treating the cache as not primed", error)
        null
    }

    private fun writeRecord(context: Context, record: Record) {
        val json = JSONObject()
            .put("key", record.key)
            .put("primedAt", record.primedAt)
            .put("elapsedMs", record.elapsedMs)
        runCatching { recordFile(context).writeText(json.toString()) }
            .onFailure { Log.e(TAG, "could not write the build cache record", it) }
    }

    /**
     * Drop the record. [SolanaToolchain.remove] calls it beside its
     * component forgets: the cache went with `/opt/solana`, and a record
     * that outlived it would be one more thing [isPrimed] has to disbelieve.
     */
    fun forget(context: Context) {
        runCatching { recordFile(context).delete() }
            .onFailure { Log.w(TAG, "could not delete the build cache record", it) }
    }

    /**
     * The key the cache would need to hold today, or null when the manifest
     * cannot be read — in which case nothing can be primed against it.
     * Blocking: it renders both scaffolds and reads the install record.
     */
    fun currentKey(context: Context): String? {
        val app = context.applicationContext
        val manifest = runCatching { ToolchainManifest.load(app) }.getOrNull() ?: return null
        return currentKey(app, manifest)
    }

    private fun currentKey(app: Context, manifest: ToolchainManifest): String {
        val record = SolanaToolchain.record(app)
        return key(
            manifests(),
            manifest.sbpfArch,
            manifest.platformToolsVersion,
            KEYED_COMPONENTS.mapNotNull { id -> record[id]?.let { id to it } }.toMap(),
        )
    }

    /**
     * Whether the record's key is today's key *and* the cache directory
     * still holds what the record claims ([cacheHasContent]). Blocking.
     */
    fun isPrimed(context: Context): Boolean {
        val app = context.applicationContext
        val manifest = runCatching { ToolchainManifest.load(app) }.getOrNull() ?: return false
        if (record(app)?.key != currentKey(app, manifest)) return false
        return cacheHasContent(SolanaToolchain.hostPath(app, manifest.buildCache))
    }

    // --- the triggers -----------------------------------------------------------------

    /**
     * Start priming if it should run, and say why not otherwise. Cheap and
     * idempotent from any thread: the evaluation runs on the primer's own
     * scope, at most one at a time, and a run already in flight is left
     * alone. The shell calls this beside its userland sync on start and
     * [BuildRunner] after every run; [ToolchainInstaller] through
     * [onToolchainInstalled]. Skips a metered connection — see [startBlocker].
     */
    fun maybeStart(context: Context, trigger: String = "app start") {
        if (isRunning) return
        evaluateOnce(context, trigger, manual = false)
    }

    /**
     * The row's button. A refusal is shown on the row rather than logged
     * only, and the metered gate does not apply: the press is the consent.
     */
    fun start(context: Context) = evaluateOnce(context, "the Prime now button", manual = true)

    /** The one gate both entrances go through: one evaluation at a time. */
    private fun evaluateOnce(context: Context, trigger: String, manual: Boolean) {
        // The slot is taken and released under the monitor, together with
        // the pending slot, so a trigger can never land between "nothing
        // pending" and "slot free" and be orphaned there.
        val launch = synchronized(monitor) {
            if (evaluating.compareAndSet(false, true)) {
                true
            } else {
                val queued = pending
                if (queued == null || manual || !queued.second) pending = trigger to manual
                false
            }
        }
        if (!launch) return
        val app = context.applicationContext
        scope.launch {
            var next: Pair<String, Boolean>? = trigger to manual
            while (next != null) {
                val (why, byHand) = next
                try {
                    evaluate(app, why, byHand)
                } finally {
                    next = synchronized(monitor) {
                        val queued = pending
                        pending = null
                        if (queued == null) evaluating.set(false)
                        queued
                    }
                }
            }
        }
    }

    /** An install or update landed with the drivers in: the first trigger. */
    fun onToolchainInstalled(context: Context) = maybeStart(context, "toolchain install")

    /**
     * [BuildRunner] is launching a run: yield. The build then compiles what
     * is missing itself, and [onBuildFinished] tries again when it is done.
     * Returns the cancel, so the build can wait for the corpse before it
     * spawns its own cargo — the two on one lock is the stall this yields
     * to avoid. A build's coroutine joins it; the main thread does not.
     */
    fun onBuildStarted(): Job = scope.launch { cancel("the user's build") }

    /** [BuildRunner]'s run ended, however it ended. */
    fun onBuildFinished(context: Context) = maybeStart(context, "build finished")

    /** The row's Stop chip: a [cancel] nobody waits for. */
    fun stop() {
        scope.launch { cancel("the Stop chip") }
    }

    /**
     * Stop the run and wait for it to be gone: the flag under the monitor,
     * the row back to idle, then SIGQUIT to proot, a grace, then SIGKILL
     * ([GuestProcess.terminate]), then the run's coroutine — whose `finally`
     * deletes its run directory — joined for at most [CORPSE_WAIT_MS]. Not
     * cancellable itself: whoever asked, the guest process has to go, and
     * the caller's own cancellation is honoured at its next suspension.
     * Whatever cargo wrote into the cache before the kill stays and is
     * reused by the next run.
     *
     * A second cancel arriving while the first waits waits with it; a
     * cancel with nothing live returns at once.
     */
    suspend fun cancel(reason: String = "stopped") {
        val doomed = synchronized(monitor) {
            val run = live ?: return
            if (!run.cancelled) {
                run.cancelled = true
                state = State.Idle
                Log.i(TAG, "stopping the priming run ($reason)")
            }
            run
        }
        withContext(NonCancellable + Dispatchers.IO) {
            synchronized(monitor) { doomed.process }?.let { process ->
                runCatching { GuestProcess.terminate(process) }
                    .onFailure { Log.w(TAG, "could not terminate the priming process", it) }
            }
            val ended = withTimeoutOrNull(CORPSE_WAIT_MS) { doomed.job.join() } != null
            synchronized(monitor) {
                if (ended) {
                    reap()
                } else if (live === doomed) {
                    Log.w(TAG, "the priming run did not end within ${CORPSE_WAIT_MS / 1_000} s; abandoning it")
                    live = null
                    abandoned.add(doomed)
                }
            }
        }
    }

    /** Under [monitor]: a cancelled run whose coroutine has ended is gone. */
    private fun reap() {
        val run = live ?: return
        if (run.cancelled && run.job.isCompleted) live = null
    }

    /**
     * The blocking half of a decision, outside the monitor: the scaffolds
     * rendered for the key, the rootfs statted for the drivers and the
     * cache, the network asked. Then, inside it, the decision against the
     * *live* flags — a build or an install that started while this was
     * rendering is seen here, not missed — and the launch.
     */
    private suspend fun evaluate(app: Context, trigger: String, manual: Boolean) {
        // A cancel still waiting for its corpse: wait with it, so a Prime
        // now pressed right after Stop starts a run instead of being refused.
        synchronized(monitor) { live?.takeIf { it.cancelled } }?.let { stopping ->
            withTimeoutOrNull(CORPSE_WAIT_MS) { stopping.job.join() }
        }
        val manifest = runCatching { ToolchainManifest.load(app) }.getOrNull()
        val installed = { id: String ->
            manifest?.component(id)?.let { SolanaToolchain.isInstalled(app, it) } == true
        }
        val primed = isPrimed(app)
        val cargoBuildSbf = installed("cargo-build-sbf")
        val anchor = installed("anchor")
        val userlandReady = Userland.backend.isSupported &&
            Userland.backend.state(app) is UserlandState.Ready
        val metered = !manual && BackgroundWork.isMetered(app)
        val record = record(app)

        synchronized(monitor) {
            reap()
            val blocker = startBlocker(
                running = live != null,
                primed = primed,
                cargoBuildSbf = cargoBuildSbf,
                anchor = anchor,
                userlandReady = userlandReady,
                buildRunning = BuildRunner.isRunning,
                installerRunning = ToolchainInstaller.isRunning,
                metered = metered,
            )
            if (blocker != null) {
                Log.i(TAG, "not priming ($trigger): $blocker")
                if (live != null) return
                // The row says what the record says, so a primed phone reads
                // "primed · <date>" on its first visit and not "not primed".
                state = when {
                    primed && record != null -> State.Done(record.primedAt, record.elapsedMs)
                    manual -> State.Failed(blocker)
                    state is State.Done -> State.Idle
                    else -> state
                }
                return
            }
            launchLocked(app, manifest ?: return, trigger)
        }
    }

    // --- the run ------------------------------------------------------------------------

    /**
     * Under [monitor], with nothing live. The row is set to Running *before*
     * the two flags are read again, on purpose: [BuildRunner] sets its
     * `isRunning` before it calls [onBuildStarted], and the installer sets
     * its phase before it calls [cancel], so a build or an install starting
     * in this very instant is either seen here — and this launch gives up —
     * or sees Running and cancels it. Neither side can miss the other.
     */
    private fun launchLocked(app: Context, manifest: ToolchainManifest, trigger: String) {
        val run = Run(now())
        state = State.Running(run.id, Step.Anchor)
        if (BuildRunner.isRunning || ToolchainInstaller.isRunning) {
            state = State.Idle
            Log.i(TAG, "not priming ($trigger): a build or the installer started meanwhile")
            return
        }
        Log.i(TAG, "priming ($trigger), run ${run.id}")
        run.job = scope.launch { execute(app, manifest, run) }
        live = run
    }

    /** The run's coroutine: the work, then its ending under the monitor. */
    private suspend fun execute(app: Context, manifest: ToolchainManifest, run: Run) {
        val outcome = runCatching {
            BackgroundWork.hold(app, "warm", maxMs = WAKE_LOCK_MS) { perform(app, manifest, run) }
        }
        synchronized(monitor) {
            abandoned.remove(run)
            // A cancel already set the row and moved on; the corpse does not
            // get to report the failure its own kill produced. The cancel
            // that is waiting on this coroutine clears `live` when it sees
            // the join return.
            if (run.cancelled) return
            live = null
            state = outcome.fold(
                onSuccess = { record -> State.Done(record.primedAt, record.elapsedMs) },
                onFailure = { error ->
                    Log.e(TAG, "priming failed", error)
                    State.Failed(error.message ?: error.javaClass.simpleName)
                },
            )
        }
    }

    /**
     * The blocking half: render, run each step, delete the run's directory,
     * sweep, record. The `finally` deletes *this run's* directory only —
     * never [WARM_ROOT], whose other entries may belong to a run that is
     * still dying.
     */
    private fun perform(app: Context, manifest: ToolchainManifest, run: Run): Record {
        val key = currentKey(app, manifest)
        val root = SolanaToolchain.hostPath(app, runDir(run.id))
        try {
            for (step in Step.entries) {
                // The step change under the monitor, with the check: a cancel
                // that has set the row to idle is never overwritten by the
                // run it cancelled.
                synchronized(monitor) {
                    if (run.cancelled) error("cancelled")
                    state = State.Running(run.id, step)
                }
                val dir = File(root, step.dirName)
                SafeDelete.deleteTree(dir)
                dir.mkdirs()
                val written = SolanaScaffold.write(
                    dir,
                    step.framework,
                    SolanaProgram.of(step.programName),
                    SolanaProgram.DEFAULT_CLUSTER,
                )
                if (written is SolanaScaffold.Result.Failed) {
                    error("could not render the ${step.label} scaffold: ${written.reason}")
                }
                val line = stepLine(step, run.id, manifest.platformToolsVersion, manifest.sbpfArch, manifest.toolsCacheSeeds)
                Log.i(TAG, "${step.label}: $line")
                val stepStarted = now()
                val tail = ArrayDeque<String>()
                val exit = execute(app, run, line) { text ->
                    if (text.isNotBlank()) {
                        tail.addLast(text)
                        if (tail.size > TAIL_LINES) tail.removeFirst()
                    }
                }
                Log.i(TAG, "timing ${step.label} took ${now() - stepStarted} ms (exit $exit)")
                if (run.cancelled) error("cancelled")
                if (exit != 0) {
                    val detail = BuildRunner.failureDetail(tail.toList())
                    error("${step.label} exited $exit" + (detail?.let { ": $it" } ?: ""))
                }
            }
        } finally {
            SafeDelete.deleteTree(root)
        }
        sweepStale(app)
        val elapsed = now() - run.id
        Log.i(TAG, "timing run took $elapsed ms")
        val record = Record(key, now(), elapsed)
        writeRecord(app, record)
        return record
    }

    /**
     * After a landing, with this run's own directory already gone: the
     * leftovers of runs a process death took — a killed app, a proot that
     * outlived its cancel — go too. Not while an [abandoned] run may still
     * be writing into one of them; that one's sweep is the next landing's.
     */
    private fun sweepStale(app: Context) {
        val skip = synchronized(monitor) { abandoned.isNotEmpty() }
        if (skip) {
            Log.i(TAG, "not sweeping stale warm directories: an abandoned run may still be alive")
            return
        }
        val warm = SolanaToolchain.hostPath(app, WARM_ROOT)
        for (entry in warm.listFiles().orEmpty()) {
            Log.i(TAG, "sweeping stale warm directory ${entry.name}")
            SafeDelete.deleteTree(entry)
        }
    }

    /**
     * One `sh -c` line in the guest, its exit status returned. Blocking.
     *
     * Real links and a null working directory: the line does its own `cd`
     * into a rootfs path, which [BuildRunner]'s `/projects` mapping does not
     * cover, and `--link2symlink` would turn cargo's uplifted artifacts into
     * symlinks exactly as it does for a real build ([BuildRunner.execute]).
     * `/bin/sh -c`, not the login shell, for the same reason a build uses
     * it: Debian's profile would reorder the `PATH` [BuildTasks.guestEnvironment]
     * establishes.
     *
     * `onStart` is the last line of defence against an orphan: a cancel
     * that arrived after the loop's check and before the spawn found no
     * process to signal, so the process reads the flag the moment it
     * exists and, if it moved, is killed here before it can take cargo's
     * lock. Both sides go through the monitor, so there is no ordering in
     * which neither of them kills it.
     */
    private fun execute(app: Context, run: Run, line: String, onLine: (String) -> Unit): Int {
        val command = Userland.backend.execCommandRealLinks(
            app,
            null,
            listOf("/bin/sh", "-c", line),
            BuildTasks.guestEnvironment(),
        ) ?: error("the userland refused to start the command")
        return GuestProcess.run(
            command,
            onStart = { process ->
                val doomed = synchronized(monitor) {
                    run.process = process
                    run.cancelled
                }
                if (doomed) {
                    Log.i(TAG, "killing a step spawned after its run was cancelled")
                    runCatching { GuestProcess.terminate(process) }
                }
            },
            onRecord = onLine,
        ).also { synchronized(monitor) { run.process = null } }
    }

    /** How many output lines are kept for the failure message. */
    private const val TAIL_LINES = 40

    private fun now(): Long = System.currentTimeMillis()
}
