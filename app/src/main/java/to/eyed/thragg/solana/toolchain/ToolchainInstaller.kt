package to.eyed.thragg.solana.toolchain

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import to.eyed.thragg.terminal.GuestProcess
import to.eyed.thragg.terminal.InstallCancelledMarker
import to.eyed.thragg.terminal.ShellCommand
import to.eyed.thragg.terminal.TerminalService
import to.eyed.thragg.terminal.TerminalSessions
import to.eyed.thragg.terminal.Userland
import to.eyed.thragg.terminal.UserlandState
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Fetches, verifies, unpacks and builds the Solana toolchain — outside the
 * composition, under the foreground service, one component at a time.
 *
 * Modelled on [to.eyed.thragg.terminal.UserlandInstaller] and for the
 * same reason, only more so: leaving the Setup screen removes the composable,
 * and a `rememberCoroutineScope` would cancel a 505 MB download because the
 * user went to look at a file. An install is work the *app* is doing.
 * The state is ordinary Compose state, so the screen observes it directly.
 *
 * Three things here are correctness requirements rather than choices, and each
 * one cost real time to find on the device (docs/SOLANA.md):
 *
 *  1. **`--link2symlink` is apt's and nothing else's.** Every guest step but
 *     the apt one runs through [Userland.backend]'s `execCommandRealLinks`.
 *     Under the rewrite, `cargo install` hard-links its finished binary out of
 *     a scratch directory it then deletes: cargo prints success and leaves a
 *     dangling symlink. That is why [verify] stats the marker from *this* side
 *     — `File.exists()` follows symlinks — before believing an exit code of 0.
 *  2. **Large archives are unpacked beside proot, not through it.** proot
 *     traces every syscall, and platform-tools is 1.4 GB of small files. The
 *     one archive that cannot go that way is Debian's own rootfs, which
 *     contains a hard link (`perl`); that one is the existing
 *     [Userland.backend] install and it goes through proot as it always did —
 *     and even there the hard link is not tar's to deliver (SELinux denies
 *     `link(2)` to app processes; on a Seeker the entry vanished with exit 0
 *     and apt died at 100 days later): DebianUserland materialises it as a
 *     relative symlink from the tar's own index, and this component's
 *     manifest verify (`perl -e 1`) proves it, not just `/bin/sh`.
 *  3. **`cargo-build-sbf` execs `rustup`.** rustup is installed with
 *     `--default-toolchain none`, so it downloads no compiler, and
 *     platform-tools' own `postInstall` then links itself in as the `solana`
 *     toolchain and makes it the default. Both halves are in the manifest.
 *
 * The install runs as **two lanes**, not one queue. The *fetch lane* pulls
 * every download in turn (smallest first) and unpacks each one into the rootfs
 * with the host's tar; the *guest lane* does everything that happens inside
 * proot — the userland, apt, every postInstall, and the two compiles. A row
 * starts on the guest lane the moment every id in its manifest `needs` is
 * installed and its own bytes are staged, so apt runs while platform-tools is
 * still downloading and unpacking, and the 505 MB is never on the critical
 * path. Measured on the Seeker (docs/SOLANA.md, "How long it takes"): the
 * serial run spent ~3 min on downloads and unpacks the guest lane sat through;
 * the two-lane run hides all of it behind the apt step. The compiles stay in
 * series with each other on purpose — two `cargo install`s at once would
 * fight for eight cores and a phone's worth of RAM.
 *
 * Resume is per component and it survives more than a dropped connection:
 * partial bytes live in the cache as `<id>.part` and are re-requested with an
 * HTTP `Range`, while *finished* components are recorded in
 * [SolanaToolchain.markInstalled] and skipped on the next run. A failed row's
 * Retry starts at that row. Nothing ever restarts the gigabyte.
 */
object ToolchainInstaller {

    private const val TAG = "thragg-toolchain"

    /** Progress is reported at most this often; a phone need not see more. */
    private const val PROGRESS_INTERVAL_MS = 250L

    /** Read and write in chunks this size — one page-aligned buffer. */
    private const val BUFFER = 64 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * The processes the two lanes are waiting on — a proot on one, the host's
     * tar on the other — so [cancel] and a failure on the other lane can stop
     * them. proot ignores SIGTERM and never sees SIGKILL, which is why this
     * goes through [GuestProcess.terminate] rather than `Process.destroy`.
     */
    private val processes = java.util.Collections.synchronizedSet(mutableSetOf<Process>())

    /**
     * Set by the first lane to fail, read by the other in [ensureActive]. A
     * failure on one lane must stop the other — a 505 MB download has no
     * business finishing after apt has died — without the run reading as
     * *cancelled*, which is what the user does and what keeps its own rows.
     */
    @Volatile
    private var aborting = false

    /** One row per component, in manifest order. Empty until [refresh]. */
    var rows: List<ComponentRow> by mutableStateOf(emptyList())
        private set

    var phase: ToolchainPhase by mutableStateOf(ToolchainPhase.Idle)
        private set

    /** Why the run stopped, for the line under the button. */
    var lastError: String? by mutableStateOf(null)
        private set

    /**
     * When the current run started, for the screen's elapsed figure; null
     * between runs. Wall clock, because it is compared against the screen's
     * own one-second tick.
     */
    var runStartedAt: Long? by mutableStateOf(null)
        private set

    val isRunning: Boolean get() = phase == ToolchainPhase.Running

    /** Whether every *required* row is in at the manifest's revision. */
    val isComplete: Boolean
        get() = rows.isNotEmpty() &&
            rows.filter { it.component.required }.all { it.state is ComponentState.Installed }

    /**
     * Whether Build can run: every required row present, at this revision or
     * an earlier one. This is what [to.eyed.thragg.ui.shell.ShellState.toolchainReady]
     * follows — the same lenient question [SolanaToolchain.isReady] answers
     * from disk — so an available update never disables Build.
     */
    val isUsable: Boolean
        get() = rows.isNotEmpty() &&
            rows.filter { it.component.required }
                .all { it.state is ComponentState.Installed || it.state is ComponentState.Outdated }

    /** Whether any row is behind the manifest — the Update button's condition. */
    val hasUpdates: Boolean
        get() = rows.any { it.state is ComponentState.Outdated }

    /** Bytes still to fetch, for the headline while a run is part-way through. */
    val remainingDownloadBytes: Long
        get() = rows.filter { it.state !is ComponentState.Installed && it.state !is ComponentState.Staged }
            .sumOf { it.component.downloadBytes }

    /** Bytes an Update would fetch: the outdated rows only. */
    val updateDownloadBytes: Long
        get() = rows.filter { it.state is ComponentState.Outdated }.sumOf { it.component.downloadBytes }

    /**
     * Rebuild the rows from the manifest and the install record.
     *
     * Does nothing while a run is in flight: the rows *are* the run's state,
     * and re-deriving them from disk mid-install would replace a downloading
     * row with a pending one. Blocking work is on the installer's own scope,
     * so this is safe to call from a `LaunchedEffect`.
     */
    fun refresh(context: Context) {
        if (isRunning) return
        val app = context.applicationContext
        scope.launch {
            val manifest = runCatching { ToolchainManifest.load(app) }.getOrElse { error ->
                Log.e(TAG, "the toolchain manifest could not be read", error)
                lastError = error.message ?: "the toolchain manifest could not be read"
                return@launch
            }
            rows = manifest.components.map { component ->
                ComponentRow(
                    component = component,
                    state = when {
                        SolanaToolchain.isInstalled(app, component) -> ComponentState.Installed
                        SolanaToolchain.isStaged(app, component) -> ComponentState.Staged
                        SolanaToolchain.isOutdated(app, component) -> ComponentState.Outdated
                        else -> ComponentState.Pending
                    },
                )
            }
            phase = if (isComplete) ToolchainPhase.Complete else ToolchainPhase.Idle
        }
    }

    /**
     * Install everything that is not already in, in manifest order.
     *
     * [onFinished] lands **on the main thread**, with true when every required
     * component is present — the same guarantee UserlandInstaller makes, and
     * for the same class of reason: the caller flips
     * [to.eyed.thragg.ui.shell.ShellState.toolchainReady], which the nav
     * bar and the Build buttons read from the composition.
     */
    fun start(context: Context, onFinished: (Boolean) -> Unit = {}) {
        launchRun(context, from = null, onFinished = onFinished)
    }

    /**
     * Retry from [componentId]'s row.
     *
     * With the install a graph rather than a list there is nothing "below" a
     * row to skip: a run always installs exactly the components that are not
     * recorded, in dependency order, so a Retry is a Start. Rows already in
     * are skipped in a stat, not a download, and a partial download is
     * resumed from its `.part` — this is the "Retry on its own row" of
     * docs/UI.md, and the reason it never restarts the gigabyte.
     */
    fun retry(context: Context, componentId: String, onFinished: (Boolean) -> Unit = {}) {
        Log.i(TAG, "retry requested from $componentId")
        launchRun(context, from = componentId, onFinished = onFinished)
    }

    /**
     * Stop the run. The download loop notices within a chunk, and a guest
     * process is taken down with SIGQUIT so proot's tracees go with it.
     *
     * Partial bytes are deliberately **kept**: they are the resume, and
     * throwing away 300 MB because someone pressed Pause is the failure this
     * whole design exists to avoid.
     */
    fun cancel() {
        job?.cancel()
        job = null
        terminateProcesses()
        updateRows { row ->
            when (row.state) {
                is ComponentState.Installed, is ComponentState.Pending, is ComponentState.Staged,
                is ComponentState.Outdated -> row
                else -> row.copy(state = ComponentState.Cancelled)
            }
        }
        phase = if (isComplete) ToolchainPhase.Complete else ToolchainPhase.Idle
    }

    private fun terminateProcesses() {
        val doomed = synchronized(processes) { processes.toList().also { processes.clear() } }
        for (process in doomed) runCatching { GuestProcess.terminate(process) }
    }

    // --- the run --------------------------------------------------------------

    private fun launchRun(context: Context, from: String?, onFinished: (Boolean) -> Unit) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        lastError = null
        aborting = false
        runStartedAt = now()
        phase = ToolchainPhase.Running
        job = scope.launch {
            val self = coroutineContext[Job]
            val ok = runCatching { run(app) }.getOrElse { error ->
                when (error) {
                    is InstallCancelledMarker -> Log.i(TAG, "toolchain install cancelled")
                    is ComponentFailed -> {
                        // The row already says what went wrong; the line under
                        // the button names it. Whatever the other lane had in
                        // flight goes back to Pending — it did nothing wrong
                        // and its bytes are kept.
                        Log.e(TAG, "toolchain install failed at ${error.id}", error)
                        lastError = "${error.name}: ${error.message}"
                    }
                    else -> {
                        Log.e(TAG, "toolchain install failed", error)
                        lastError = error.message ?: error.javaClass.simpleName
                    }
                }
                updateRows { row ->
                    when (row.state) {
                        is ComponentState.Installed, is ComponentState.Failed, is ComponentState.Staged,
                        is ComponentState.Pending, is ComponentState.Cancelled, is ComponentState.Outdated -> row
                        else -> row.copy(state = ComponentState.Pending)
                    }
                }
                false
            }
            // Only if this run is still the current one: a cancel followed
            // immediately by a Start hands `job` to the new run, and clearing
            // it from here would leave that one uncancellable.
            if (job === self) {
                job = null
                runStartedAt = null
            }
            phase = when {
                isComplete || ok -> ToolchainPhase.Complete
                lastError != null -> ToolchainPhase.Failed
                else -> ToolchainPhase.Idle
            }
            // NonCancellable, because the whole point of this block is to give
            // the foreground notification back — and a cancel is exactly when
            // it must be given back. Without it, pressing Pause leaves the
            // service holding a notification for work that has stopped.
            withContext(NonCancellable + Dispatchers.Main) {
                syncForegroundService(app)
                onFinished(isUsable)
            }
        }
        // The notification that keeps Android from reaping proot while the
        // screen is off. Started on the main thread because startForegroundService
        // is an activity-manager call and the rest of the app does it there.
        scope.launch(Dispatchers.Main) { holdForegroundService(app) }
    }

    /**
     * The two lanes. See the class comment for why there are two and the
     * manifest's own note for what depends on what.
     *
     * The *guest lane* is this coroutine: it loops over what is left, takes
     * the first row (list order) whose `needs` are all installed and whose
     * bytes are staged, and runs its guest half. When nothing is ready it
     * waits on the staging of whatever is still being fetched — never busy,
     * never asleep for a fixed interval. The *fetch lane* is the child
     * coroutine: downloads smallest-first, so the three 15 MB binaries are in
     * before the 505 MB one starts and the guest lane has work while it waits.
     */
    private suspend fun run(app: Context): Boolean {
        val manifest = ToolchainManifest.load(app)
        if (!Userland.backend.isSupported) {
            error("the Linux guest is not available, so it cannot install a Solana toolchain")
        }
        val queue = manifest.components.filterNot { SolanaToolchain.isInstalled(app, it) }
        checkSpace(app, queue)
        // A staging directory left by a run that died mid-unpack is worth
        // nothing: the archive it came from is still the resume.
        to.eyed.thragg.core.SafeDelete.deleteTree(File(app.filesDir, "toolchain-stage"))
        val runStarted = now()

        // Completed when a fetched component's bytes are unpacked in place.
        val staged = queue.filter { it.url != null }.associate { it.id to CompletableDeferred<Unit>() }
        // Completed when a component is recorded — what `needs` waits on.
        val landed = queue.associate { it.id to CompletableDeferred<Unit>() }
        fun isLanded(id: String) = landed[id]?.isCompleted ?: true

        coroutineScope {
            launch { fetchLane(app, queue, staged, landed) }

            val pending = queue.toMutableList()
            while (pending.isNotEmpty()) {
                ensureActive()
                val ready = pending.firstOrNull { component ->
                    component.needs.all(::isLanded) && (staged[component.id]?.isCompleted ?: true)
                }
                if (ready == null) {
                    val waiting = pending.mapNotNull { staged[it.id] }.filterNot { it.isCompleted }
                    check(waiting.isNotEmpty()) {
                        "no component can start: ${pending.map { it.id }} — the manifest's needs are wrong"
                    }
                    select<Unit> { for (deferred in waiting) deferred.onAwait { } }
                    continue
                }
                pending.remove(ready)
                val started = now()
                guard(ready) { installGuestSide(app, manifest, ready) }
                val took = now() - started
                Log.i(TAG, "timing ${ready.id} took $took ms")
                SolanaToolchain.markInstalled(app, ready, took)
                setState(ready.id, ComponentState.Installed)
                landed.getValue(ready.id).complete(Unit)
            }
        }

        Log.i(TAG, "timing run took ${now() - runStarted} ms for ${queue.map { it.id }}")
        cleanCargoScratch(app, manifest)
        return true
    }

    /**
     * Everything that comes over the network, in turn, and into the rootfs.
     *
     * Largest first, deliberately: platform-tools is the one download whose
     * unpack can outlast the guest lane's userland-plus-apt, and nothing on
     * that lane needs any of the small ones before apt is done. Unpacking
     * does not wait for the rootfs either: the userland install starts by
     * wiping its directory, so every archive is unpacked into a *staging*
     * directory beside it and moved in with a rename — O(1) for a directory
     * on the same filesystem — the moment the userland has landed. Measured
     * on the Seeker: with the wait, the 2 min 09 s bzip2 unpack could only
     * start after Debian's 81 s and finished a minute after apt; without it,
     * it starts at t≈0 and is in before apt is.
     */
    private suspend fun fetchLane(
        app: Context,
        queue: List<ToolchainComponent>,
        staged: Map<String, CompletableDeferred<Unit>>,
        landed: Map<String, CompletableDeferred<Unit>>,
    ) {
        val userland = queue.firstOrNull { it.method == InstallMethod.Userland }
        for (component in queue.filter { it.url != null }.sortedByDescending { it.downloadBytes }) {
            ensureActive()
            // Already in the rootfs from a run that was interrupted after this
            // unpack: nothing to fetch, and above all nothing to fetch *again*.
            if (SolanaToolchain.isStaged(app, component)) {
                setState(component.id, ComponentState.Staged)
                staged.getValue(component.id).complete(Unit)
                continue
            }
            guard(component) {
                val stagingRoot = stagingRoot(app, component)
                val file = if (canStream(app, component)) {
                    streamTarball(app, component, File(stagingRoot, component.installPath.trimStart('/')))
                } else {
                    download(app, component).also { stage(app, component, it, stagingRoot) }
                }
                userland?.let { landed.getValue(it.id).await() }
                ensureActive()
                setState(component.id, ComponentState.Working("Moving into place", now()))
                moveIntoRootfs(app, component, stagingRoot)
                // Only now: the bytes are in the rootfs, and the archive was
                // the resume until they were.
                file.delete()
                SolanaToolchain.markStaged(app, component)
            }
            setState(component.id, ComponentState.Staged)
            staged.getValue(component.id).complete(Unit)
        }
    }

    /**
     * Whether a tarball can be unpacked *as it downloads*.
     *
     * Only from byte zero: a partial file on disk is a resume, and a resume
     * is served by the plain path — finish the download with a `Range`, hash
     * the file, unpack it. Only for a compression tar can be told about on
     * a pipe (no seeking back to sniff the magic), and only when nothing has
     * yet been unpacked for it.
     */
    private fun canStream(app: Context, component: ToolchainComponent): Boolean {
        if (component.method != InstallMethod.Tarball) return false
        if (compressionFlag(component.url.orEmpty()) == null) return false
        val partial = partFile(app, component)
        return !partial.isFile || partial.length() == 0L
    }

    private fun compressionFlag(url: String): String? = when {
        url.endsWith(".tar.bz2") || url.endsWith(".tbz2") -> "-j"
        url.endsWith(".tar.gz") || url.endsWith(".tgz") -> "-z"
        url.endsWith(".tar.xz") || url.endsWith(".txz") -> "-J"
        else -> null
    }

    private fun partFile(app: Context, component: ToolchainComponent): File =
        File(app.cacheDir, "solana-toolchain").apply { mkdirs() }
            .let { File(it, "${component.id}.part") }

    /**
     * Download a tarball and unpack it in the same pass: every chunk goes to
     * the `.part` file, to the digest, and down a pipe into the host's tar.
     *
     * Measured on the Seeker (2026-09-02) for platform-tools: 60 s of
     * download and 2 min 09 s of bzip2 in series were the whole critical path
     * of the install once everything else overlapped; streamed, the unpack
     * runs *during* the download and the pair takes about as long as the
     * slower of the two. The archive is still written to disk, so a drop
     * mid-stream costs nothing the plain path would not have paid: tar is
     * killed, the half-unpacked staging directory is thrown away, and the
     * next run resumes the download with a `Range` and unpacks the file —
     * "nothing ever restarts the gigabyte" still holds. The hash is checked
     * at the end exactly as the plain path checks it; a mismatch deletes
     * both the bytes and what they unpacked to.
     */
    private fun streamTarball(app: Context, component: ToolchainComponent, target: File): File {
        val url = component.url ?: error("${component.id} has no url")
        val sha256 = component.sha256 ?: error("${component.id} has no sha256")
        val flag = compressionFlag(url) ?: error("${component.id} is not a streamable tarball")
        val partial = partFile(app, component)
        partial.delete()
        target.mkdirs()
        val started = now()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) error("$url answered HTTP $code")
        val total = connection.contentLengthLong.takeIf { it > 0L } ?: component.downloadBytes

        val log = File(app.cacheDir, "toolchain-unpack.log")
        val process = ProcessBuilder("/system/bin/tar", "-x", flag, "-f", "-")
            .directory(target)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        processes.add(process)
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        var lastReport = 0L
        var windowBytes = 0L
        var windowStart = now()
        try {
            connection.inputStream.use { source ->
                FileOutputStream(partial).use { sink ->
                    process.outputStream.buffered(BUFFER).use { pipe ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            ensureActive()
                            val read = source.read(buffer)
                            if (read < 0) break
                            sink.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            pipe.write(buffer, 0, read)
                            received += read
                            windowBytes += read
                            val stamp = now()
                            if (stamp - lastReport >= PROGRESS_INTERVAL_MS) {
                                val elapsed = stamp - windowStart
                                setState(
                                    component.id,
                                    ComponentState.Downloading(
                                        received = received,
                                        total = total,
                                        bytesPerSecond = if (elapsed > 0L) windowBytes * 1000L / elapsed else null,
                                    ),
                                )
                                lastReport = stamp
                                windowBytes = 0L
                                windowStart = stamp
                            }
                        }
                    }
                }
            }
            Log.i(TAG, "timing ${component.id} download took ${now() - started} ms (streamed)")
            // The pipe is closed; tar has whatever the network was ahead of
            // it still to unpack — on the Seeker about a minute of bzip2.
            setState(component.id, ComponentState.Working("Unpacking", now()))
            val exit = process.waitFor()
            processes.remove(process)
            Log.i(TAG, "timing ${component.id} download+unpack took ${now() - started} ms (streamed)")
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(sha256, ignoreCase = true)) {
                to.eyed.thragg.core.SafeDelete.deleteTree(target)
                partial.delete()
                error("the download does not match its pinned sha256 — refusing to install it")
            }
            if (exit != 0) {
                to.eyed.thragg.core.SafeDelete.deleteTree(target)
                error("unpacking failed (exit $exit): ${log.takeIf { it.isFile }?.readText()?.take(300)}")
            }
            return partial
        } catch (error: Throwable) {
            // A dropped connection, a cancel, a failure on the other lane:
            // tar dies with its pipe, the staging tree is worthless, and the
            // .part keeps every byte received for the resume.
            processes.remove(process)
            runCatching { GuestProcess.terminate(process) }
            to.eyed.thragg.core.SafeDelete.deleteTree(target)
            throw error
        }
    }

    /** Where a component is unpacked before the rootfs is ready for it. */
    private fun stagingRoot(app: Context, component: ToolchainComponent): File =
        File(app.filesDir, "toolchain-stage/${component.id}").also {
            to.eyed.thragg.core.SafeDelete.deleteTree(it)
            it.mkdirs()
        }

    /**
     * Move a staged tree into the rootfs.
     *
     * A directory that does not exist yet in the rootfs is one `rename(2)`.
     * One that does — `/opt/solana/cli`, shared by the two drivers — is
     * merged a level down, so the second driver's `bin/` lands beside the
     * first's rather than replacing it. A component whose *previous*
     * revision is still on disk under a directory nothing else shares is
     * cleared first, so an update never leaves a stale file from the old
     * release beside the new ones.
     */
    private fun moveIntoRootfs(app: Context, component: ToolchainComponent, stagingRoot: File) {
        val rootfs = SolanaToolchain.rootfs(app)
        if (SolanaToolchain.isOutdated(app, component) && !SolanaToolchain.sharesInstallPath(app, component)) {
            val old = SolanaToolchain.hostPath(app, component.installPath)
            if (old.isDirectory) to.eyed.thragg.core.SafeDelete.deleteTree(old)
        }
        moveTree(stagingRoot, rootfs)
        to.eyed.thragg.core.SafeDelete.deleteTree(stagingRoot)
    }

    private fun moveTree(source: File, target: File) {
        val children = source.listFiles() ?: return
        for (child in children) {
            val destination = File(target, child.name)
            val lstat = runCatching { Os.lstat(destination.absolutePath) }.getOrNull()
            when {
                lstat == null -> renameOrCopy(child, destination)
                child.isDirectory && destination.isDirectory && !isSymlink(destination) -> moveTree(child, destination)
                else -> {
                    if (destination.isDirectory && !isSymlink(destination)) {
                        to.eyed.thragg.core.SafeDelete.deleteTree(destination)
                    } else {
                        destination.delete()
                    }
                    renameOrCopy(child, destination)
                }
            }
        }
    }

    private fun renameOrCopy(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        if (source.renameTo(destination)) return
        // Different filesystem — not the case on any device this ships to,
        // but a rename that fails must not lose the bytes.
        if (source.isDirectory && !isSymlink(source)) {
            destination.mkdirs()
            moveTree(source, destination)
            source.delete()
        } else {
            source.copyTo(destination, overwrite = true)
            source.delete()
        }
    }

    private fun isSymlink(file: File): Boolean =
        runCatching { android.system.OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode) }.getOrDefault(false)

    /**
     * Run one lane's step for [component], turning whatever it throws into
     * the row's own state.
     *
     * A cancel is the user's and passes through with the row marked
     * Cancelled. A failure marks the row, stops the *other* lane through
     * [aborting] and [terminateProcesses], and surfaces as [ComponentFailed]
     * so the run reports the right name. A step that dies *because* the
     * other lane already failed — its process was just killed — is not a
     * second failure: its row goes back to Pending and the run keeps the
     * first error.
     */
    private inline fun guard(component: ToolchainComponent, step: () -> Unit) {
        try {
            step()
        } catch (cancelled: InstallCancelledMarker) {
            setState(component.id, ComponentState.Cancelled)
            throw cancelled
        } catch (error: Throwable) {
            if (aborting || job?.isActive == false) {
                setState(component.id, ComponentState.Pending)
                throw ToolchainCancelled()
            }
            aborting = true
            Log.e(TAG, "${component.id} failed", error)
            val message = error.message ?: error.javaClass.simpleName
            setState(component.id, ComponentState.Failed(message))
            terminateProcesses()
            throw ComponentFailed(component.id, component.name, message, error)
        }
    }

    /**
     * Refuse before starting rather than fail halfway through an unpack.
     *
     * The peak is what is left to install *plus* every download that can be
     * sitting in the cache at once — with two lanes that is all of them,
     * because the fetch lane may have the gigabyte on disk while the guest
     * lane is still on apt. Failing with two numbers is the only failure here
     * a user can act on.
     */
    private fun checkSpace(app: Context, queue: List<ToolchainComponent>) {
        if (queue.isEmpty()) return
        val needed = queue.sumOf { it.installBytes } + queue.sumOf { it.downloadBytes }
        val free = app.filesDir.usableSpace
        if (free < needed) {
            error(
                "not enough space: ${formatBytes(needed)} needed, ${formatBytes(free)} free"
            )
        }
    }

    /**
     * The guest half of a component: the work itself for the three kinds
     * that *are* guest work, then the postInstall lines and the verify for
     * all of them.
     */
    private fun installGuestSide(app: Context, manifest: ToolchainManifest, component: ToolchainComponent) {
        setState(component.id, ComponentState.Working("Starting", now()))
        when (component.method) {
            InstallMethod.Userland -> installUserland(app, component)
            InstallMethod.Apt -> installApt(app, component)
            InstallMethod.CargoInstall -> installCrate(app, manifest, component)
            InstallMethod.RustupToolchain -> installRustupToolchain(app, component)
            // Already staged by the fetch lane; only the guest half is left.
            InstallMethod.Binary, InstallMethod.GzSingleBinary, InstallMethod.Tarball -> Unit
        }
        for (line in component.postInstall) {
            ensureActive()
            setState(component.id, ComponentState.Working(line.first().substringAfterLast('/'), now()))
            val exit = runInGuest(app, line, apt = false) { }
            if (exit != 0) error("`${line.joinToString(" ")}` exited $exit")
        }
        verify(app, component)
    }

    /**
     * The host half of a fetched component: the downloaded [file], unpacked
     * under [root] at the component's install path. [root] is a staging
     * directory, never the rootfs — see [fetchLane].
     */
    private fun stage(app: Context, component: ToolchainComponent, file: File, root: File) {
        val target = File(root, component.installPath.trimStart('/'))
        when (component.method) {
            InstallMethod.Binary -> installBinary(app, component, file, target)
            InstallMethod.GzSingleBinary -> installGzBinary(app, component, file, target)
            InstallMethod.Tarball -> installTarball(app, component, file, target)
            else -> error("${component.id} has a url but is not a fetched component")
        }
    }

    // --- the six install methods ---------------------------------------------

    /**
     * The Debian rootfs, through the installer that already owns it.
     *
     * Not re-implemented here: [Userland.backend] resolves Debian's own
     * container image, verifies the registry's digest as it streams, and
     * unpacks *through* proot — which this one component needs, because
     * Debian's image contains a hard link that a host tar cannot reproduce
     * into app storage. Its `(step, fraction)` progress is mapped onto this
     * row's declared size so the row still counts bytes like its neighbours.
     */
    private fun installUserland(app: Context, component: ToolchainComponent) {
        if (Userland.backend.state(app) is UserlandState.Ready) return
        val total = component.downloadBytes
        val result = Userland.backend.install(
            app,
            isActive = { job?.isActive != false && !aborting },
            onProgress = { step, fraction ->
                setState(
                    component.id,
                    if (fraction == null) {
                        ComponentState.Working(step, now())
                    } else {
                        ComponentState.Downloading(
                            received = (fraction.toDouble() * total).toLong(),
                            total = total,
                            bytesPerSecond = null,
                        )
                    },
                )
            },
        )
        result.getOrElse { error -> throw error }
    }

    /**
     * apt, and the **only** step that runs under `--link2symlink`.
     *
     * `execCommand` is the flagged invocation; everything else in this file
     * calls `execCommandRealLinks`. dpkg unpacks hard links and cannot work
     * without the rewrite; `cargo install` cannot work with it.
     */
    private fun installApt(app: Context, component: ToolchainComponent) {
        val started = now()
        // --force-unsafe-io: dpkg fsyncs every file it unpacks, and on a
        // phone's flash under ptrace that was a large share of the step. An
        // interrupted apt is re-run from the top by the row's Retry, so the
        // durability it buys protects nothing here — the same reasoning as
        // every container build that sets it.
        val script = buildString {
            append("export DEBIAN_FRONTEND=noninteractive; ")
            append("apt-get update -qq && ")
            append("apt-get -o Dpkg::Options::=--force-unsafe-io ")
            append("install -y --no-install-recommends ")
            append(component.packages.joinToString(" "))
        }
        val exit = runInGuest(app, listOf("/bin/bash", "-c", script), apt = true, onLine = workingStep(component.id, started))
        if (exit != 0) error("apt-get exited $exit")
    }

    /** One downloaded file, copied in as it came and made executable. */
    private fun installBinary(app: Context, component: ToolchainComponent, file: File, target: File) {
        target.parentFile?.mkdirs()
        setState(component.id, ComponentState.Working("Installing", now()))
        file.copyTo(target, overwrite = true)
        chmodExecutable(target)
    }

    /** A gzipped ELF — how rust-analyzer ships its server. */
    private fun installGzBinary(app: Context, component: ToolchainComponent, file: File, target: File) {
        target.parentFile?.mkdirs()
        setState(component.id, ComponentState.Working("Unpacking", now()))
        GZIPInputStream(file.inputStream().buffered()).use { source ->
            target.outputStream().use { sink -> source.copyTo(sink, BUFFER) }
        }
        chmodExecutable(target)
    }

    /**
     * A tar archive, unpacked with the **device's own tar**, beside proot.
     *
     * `tar` auto-detects the compression, and the flags mirror
     * tools/device-toolchain.sh exactly — including running with the
     * destination as the working directory rather than passing `-C`, because
     * that is the invocation that was measured on the device. Through proot
     * this same unpack takes many times longer: ptrace pays per syscall and
     * platform-tools is 1.4 GB of small files.
     */
    private fun installTarball(app: Context, component: ToolchainComponent, file: File, target: File) {
        target.mkdirs()
        setState(component.id, ComponentState.Working("Unpacking", now()))
        val log = File(app.cacheDir, "toolchain-unpack.log")
        val process = ProcessBuilder("/system/bin/tar", "xf", file.absolutePath)
            .directory(target)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        processes.add(process)
        val unpackStarted = now()
        val exit = process.waitFor()
        processes.remove(process)
        Log.i(TAG, "timing ${component.id} unpack took ${now() - unpackStarted} ms")
        if (exit != 0) {
            error("unpacking failed (exit $exit): ${log.takeIf { it.isFile }?.readText()?.take(300)}")
        }
    }

    /**
     * `cargo install`, on the phone, through a proot **without**
     * `--link2symlink`.
     *
     * Neither of these crates has an arm64 Linux binary anywhere upstream, and
     * building them here is what removes the need for us to host one
     * (docs/SOLANA.md). Measured: 3 min 45 s wall for `cargo-build-sbf`. The
     * row shows an elapsed timer and never a byte count, because there is no
     * transfer here to measure and a bar that guessed would be a lie.
     */
    private fun installCrate(
        app: Context,
        manifest: ToolchainManifest,
        component: ToolchainComponent,
    ) {
        val crate = component.crate ?: error("${component.id} names no crate")
        val started = now()
        val argv = buildList {
            add("cargo")
            add("install")
            add(crate)
            component.version?.let { add("--version"); add(it) }
            if (component.locked) add("--locked")
            add("--root")
            add(component.cargoRoot ?: "${manifest.guestRoot}/cli")
            add("--target-dir")
            add(component.targetDir ?: manifest.cargoScratch)
        }
        // Both of these crates are *drivers*: they spawn the platform-tools
        // compiler and shuffle files, and their own runtime is process-spawn
        // noise. cargo's release profile — opt 3, one codegen unit's worth of
        // waiting per big crate — buys nothing here and costs real minutes.
        // Measured on the Seeker, 2026-09-02, cargo-build-sbf with a warm
        // crate cache (docs/SOLANA.md, "How long it takes"):
        //   opt 1, cgu 256, lto off              2 min 49 s   16 min CPU
        //   + lld as the linker                  2 min 45 s
        //   + -Zthreads=4 (parallel frontend)    3 min 02 s   — slower: eight
        //                                        rustcs times four threads
        //                                        oversubscribes eight cores
        //   opt 0 + lld                          2 min 05 s    9 min CPU
        // Opt 0 is the one that moves: LLVM does the least it can and the
        // frontend is the whole cost. A driver at opt 0 is still a driver —
        // its slowest path is waiting on the compiler it spawned. lld is kept
        // because a 500-rlib link on an A76 is where bfd shows, and it costs
        // nothing (platform-tools ships it on the guest PATH). The retry bump
        // is for the same phone: cargo gives up a crate download after three
        // tries, and a Wi-Fi that starves to bytes-per-second mid-install
        // (seen live: "Less than 10 bytes/sec ... the last 30 seconds")
        // deserves ten. Single-quoted for the shell: RUSTFLAGS has a space.
        val tuning = "export CARGO_PROFILE_RELEASE_OPT_LEVEL=0 " +
            "CARGO_PROFILE_RELEASE_LTO=off " +
            "CARGO_PROFILE_RELEASE_CODEGEN_UNITS=256 " +
            "RUSTFLAGS='-C link-arg=-fuse-ld=lld' " +
            "CARGO_NET_RETRY=10; "
        val exit = runInGuest(
            app,
            listOf("/bin/bash", "-c", tuning + argv.joinToString(" ")),
            apt = false,
            onLine = workingStep(component.id, started),
        )
        if (exit != 0) error("cargo install $crate exited $exit")
    }

    /**
     * `rustup toolchain install`, in the guest: the editor's own Rust.
     *
     * rust-analyzer expands a crate's proc macros through a *server* that
     * must have been built by the very compiler that built the macros —
     * the sysroot's `libexec/rust-analyzer-proc-macro-srv`, which rustup's
     * `rust-analyzer` component carries and platform-tools does not. With
     * no server, every Anchor attribute — `#[program]`, `#[account]`,
     * `#[derive(Accounts)]` — is left unexpanded and the scaffold opens
     * with thirteen false errors (measured on the Seeker, 2026-09-04; zero
     * with this toolchain). So the editor reads code with the toolchain
     * named in `version`, and the phone builds it with platform-tools as
     * before: the engine sets `RUSTUP_TOOLCHAIN` for the language server
     * alone (lsp.rs, `EDITOR_TOOLCHAIN`), and nothing else sees this one.
     *
     * `--profile minimal` is rustc, rust-std and cargo — the least a
     * `cargo check` can run on; `packages` adds rust-analyzer and rust-src,
     * the standard library's source, without which the server loads the
     * workspace with "can't load standard library from sysroot" and
     * resolves nothing from std. rustup verifies its own downloads, which
     * is why this row pins a version and no hash. Measured over home Wi-Fi:
     * 18 s for the four binaries and 2 min 39 s for rust-src's thousands
     * of small files through proot; 123 MB down, 613 MB on disk.
     */
    private fun installRustupToolchain(app: Context, component: ToolchainComponent) {
        val toolchain = component.version ?: error("${component.id} names no toolchain version")
        val started = now()
        val argv = buildList {
            add("/root/.cargo/bin/rustup")
            add("toolchain")
            add("install")
            add(toolchain)
            add("--profile")
            add("minimal")
            for (extra in component.packages) {
                add("--component")
                add(extra)
            }
            add("--no-self-update")
        }
        val exit = runInGuest(app, argv, apt = false, onLine = workingStep(component.id, started))
        if (exit != 0) error("rustup toolchain install $toolchain exited $exit")
    }

    /**
     * A row updater that keeps up with a chatty process without redrawing the
     * screen per line.
     *
     * apt and cargo print hundreds of progress lines a second in their busy
     * stretches, and each [setState] call replaces the row list Compose is
     * observing — measured on the Seeker as the Setup screen holding a whole
     * core while cargo downloaded crates, CPU the compile itself wants.
     * A phone need not see more than [PROGRESS_INTERVAL_MS] either way; the
     * step after the last line is [ComponentState.Installed], so nothing a
     * user must read is dropped.
     */
    private fun workingStep(id: String, started: Long): (String) -> Unit {
        var lastReport = 0L
        return { line ->
            val stamp = now()
            if (stamp - lastReport >= PROGRESS_INTERVAL_MS) {
                lastReport = stamp
                setState(id, ComponentState.Working(line.take(80), started))
            }
        }
    }

    // --- verification ---------------------------------------------------------

    /**
     * Prove the component is actually there, from both sides.
     *
     * The host-side stat is the one that matters. `cargo install` under
     * `--link2symlink` exits 0 and leaves a symlink into a scratch directory
     * it has already deleted; `File.exists()` follows symlinks, so a dangling
     * one reads as absent here and is named as what it is. The guest command
     * then runs the thing, which is the only way to catch a binary that is
     * present and cannot start.
     */
    private fun verify(app: Context, component: ToolchainComponent) {
        val marker = SolanaToolchain.hostPath(app, component.marker)
        if (!marker.exists()) {
            val dangling = runCatching { Os.lstat(marker.absolutePath) }.isSuccess
            error(
                if (dangling) {
                    "${component.marker} is a dangling symlink — this is what proot's " +
                        "--link2symlink does to `cargo install`; the step must not use it"
                } else {
                    "${component.marker} is missing after the install"
                }
            )
        }
        val verify = component.verify ?: return
        ensureActive()
        var last = ""
        val exit = runInGuest(app, verify, apt = false) { line -> if (line.isNotBlank()) last = line }
        if (exit != 0) {
            error("`${verify.joinToString(" ")}` exited $exit${if (last.isEmpty()) "" else ": $last"}")
        }
    }

    // --- downloading ----------------------------------------------------------

    /**
     * Fetch [component] into the cache, resuming a partial file, and verify
     * its pinned SHA-256 before returning it.
     *
     * Resumable because the first run pulls the better part of a gigabyte over
     * a phone's Wi-Fi and *will* be interrupted (docs/SOLANA.md). The partial
     * file is the resume: a `Range` request asks for the rest, a server that
     * answers 200 instead of 206 is taken at its word and the file starts
     * again, and a 416 means what is on disk is already the whole thing — in
     * which case the hash decides whether it is the *right* whole thing.
     *
     * The digest is computed over the finished file rather than as the bytes
     * arrive, because a resume across an app restart has no running digest to
     * continue: hashing 505 MB off local storage is seconds, and being able to
     * resume days later is worth them.
     */
    private fun download(app: Context, component: ToolchainComponent): File {
        val url = component.url ?: error("${component.id} has no url")
        val sha256 = component.sha256 ?: error("${component.id} has no sha256")
        val partial = partFile(app, component)

        var attempt = 0
        val downloadStarted = now()
        while (true) {
            ensureActive()
            attempt++
            // A file that is already the declared length is probably the whole
            // thing from a previous run; hash it before spending a request on
            // it. A partial one skips straight to the Range request, so the
            // 505 MB is not read twice for nothing.
            val complete = partial.isFile &&
                (component.downloadBytes <= 0L || partial.length() == component.downloadBytes)
            if (complete && sha256Of(partial).equals(sha256, ignoreCase = true)) {
                return partial
            }
            fetchWithRetries(component, url, partial)
            val actual = sha256Of(partial)
            Log.i(TAG, "timing ${component.id} download took ${now() - downloadStarted} ms")
            if (actual.equals(sha256, ignoreCase = true)) return partial
            // One retry: a truncated resume is far more likely than a bad
            // upstream, and starting clean fixes it. Twice in a row is a real
            // mismatch and the user has to hear about it rather than watch the
            // phone download 505 MB in a loop.
            Log.w(TAG, "${component.id} hash mismatch ($actual); restarting the download")
            partial.delete()
            if (attempt >= 2) {
                error("the download does not match its pinned sha256 — refusing to install it")
            }
        }
    }

    /**
     * [fetch], surviving the network a phone actually has.
     *
     * The design brief for this installer says the gigabyte "*will* be
     * interrupted" — and then a single 30-second connect timeout to GitHub
     * failed the whole run and sat waiting for a human to press Retry
     * (seen live on the Seeker mid-reinstall). A transient `IOException`
     * gets three more attempts with a short growing pause; the partial file
     * is the resume, so a retry re-requests only the missing bytes. Anything
     * that is not an I/O error — a bad HTTP status, cancellation — still
     * fails straight through to the row's Retry.
     */
    private fun fetchWithRetries(component: ToolchainComponent, url: String, into: File) {
        var failures = 0
        while (true) {
            ensureActive()
            try {
                return fetch(component, url, into)
            } catch (error: java.io.IOException) {
                failures++
                if (failures > 3) throw error
                Log.w(TAG, "${component.id} download interrupted (attempt $failures); retrying", error)
                setState(component.id, ComponentState.Working("Reconnecting", now()))
                // A sleep in slices, so Pause still lands within a beat.
                repeat(failures * 4) {
                    ensureActive()
                    Thread.sleep(500L)
                }
            }
        }
    }

    private fun fetch(component: ToolchainComponent, url: String, into: File) {
        val already = if (into.isFile) into.length() else 0L
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            if (already > 0L) setRequestProperty("Range", "bytes=$already-")
        }
        val code = connection.responseCode
        // 416 is "your Range is past the end", which for a completed file is
        // the truth: fall through to the hash check with what is on disk.
        if (code == 416) return
        val resuming = code == HttpURLConnection.HTTP_PARTIAL
        if (code != HttpURLConnection.HTTP_OK && !resuming) {
            error("$url answered HTTP $code")
        }
        val offset = if (resuming) already else 0L
        val remaining = connection.contentLengthLong.takeIf { it > 0L } ?: 0L
        val total = if (remaining > 0L) offset + remaining else component.downloadBytes

        var received = offset
        var lastReport = 0L
        var windowBytes = 0L
        var windowStart = now()
        connection.inputStream.use { source ->
            FileOutputStream(into, resuming).use { sink ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    received += read
                    windowBytes += read
                    val stamp = now()
                    if (stamp - lastReport >= PROGRESS_INTERVAL_MS) {
                        val elapsed = stamp - windowStart
                        setState(
                            component.id,
                            ComponentState.Downloading(
                                received = received,
                                total = total,
                                bytesPerSecond =
                                    if (elapsed > 0L) windowBytes * 1000L / elapsed else null,
                            ),
                        )
                        lastReport = stamp
                        windowBytes = 0L
                        windowStart = stamp
                    }
                }
            }
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { source ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                ensureActive()
                val read = source.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // --- running things in the guest ------------------------------------------

    /**
     * Run [argv] inside the userland and return its exit status, feeding every
     * completed line to [onLine].
     *
     * [apt] picks the sandbox, and it is the whole rule in one parameter: true
     * takes proot's `--link2symlink`, which dpkg needs; false takes the plain
     * one, which is what everything else must have. There is no third caller
     * and there should never be — see [Userland] and docs/SOLANA.md.
     */
    private fun runInGuest(
        app: Context,
        argv: List<String>,
        apt: Boolean,
        onLine: (String) -> Unit,
    ): Int {
        val extras = SolanaToolchain.guestEnvironment()
        val command: ShellCommand = (
            if (apt) Userland.backend.execCommand(app, null, argv, extras)
            else Userland.backend.execCommandRealLinks(app, null, argv, extras)
            ) ?: error("there is no userland to run `${argv.firstOrNull()}` in")
        var started: Process? = null
        return GuestProcess.run(
            command,
            onStart = { process -> started = process; processes.add(process) },
            onRecord = onLine,
        ).also { started?.let(processes::remove) }
    }

    /**
     * The cargo scratch, once nothing needs it.
     *
     * `cargo install --target-dir` keeps every intermediate artifact of two
     * large crates — several GB of `.rlib` that will never be read again. Kept
     * while the compiles run so the second one reuses the first one's
     * dependencies, deleted the moment they are both in.
     */
    private fun cleanCargoScratch(app: Context, manifest: ToolchainManifest) {
        val compiles = manifest.components.filter { it.isCompiled }
        if (compiles.isEmpty()) return
        if (!compiles.all { SolanaToolchain.isInstalled(app, it) }) return
        val scratch = SolanaToolchain.hostPath(app, manifest.cargoScratch)
        runCatching { to.eyed.thragg.core.SafeDelete.deleteTree(scratch) }
            .onFailure { Log.w(TAG, "could not clear the cargo scratch", it) }
    }

    // --- the foreground service ------------------------------------------------

    /**
     * Keep the process out of Android's cached bucket for the length of the
     * install.
     *
     * The same notification the terminal uses, because it is the same problem:
     * proot plus a four-minute compile is exactly the process tree the phantom
     * process reaper takes when you switch away. Counted as one more "session"
     * than the terminal has open, so ending the install hands the service back
     * to the terminal rather than stopping it out from under a running shell.
     */
    private fun holdForegroundService(app: Context) {
        TerminalService.sync(app, TerminalSessions.of(app).sessions.size + 1)
    }

    private fun syncForegroundService(app: Context) {
        TerminalService.sync(app, TerminalSessions.of(app).sessions.size)
    }

    // --- plumbing --------------------------------------------------------------

    /**
     * Synchronized, because two lanes now write the rows from two threads and
     * `rows = rows.map { … }` is a read-modify-write: unguarded, a download
     * tick and an apt line landing together lost one of them.
     */
    @Synchronized
    private fun setState(id: String, state: ComponentState) {
        rows = rows.map { row -> if (row.component.id == id) row.copy(state = state) else row }
    }

    @Synchronized
    private fun updateRows(transform: (ComponentRow) -> ComponentRow) {
        rows = rows.map(transform)
    }

    private fun ensureActive() {
        if (job?.isActive == false || aborting) throw ToolchainCancelled()
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun chmodExecutable(file: File) {
        try {
            Os.chmod(file.absolutePath, 493) // 0755
        } catch (error: ErrnoException) {
            Log.w(TAG, "could not chmod ${file.name}", error)
        }
    }
}

/**
 * A cancel, not a failure — so the screen offers Start again rather than
 * reporting an error the user caused on purpose. Shares the marker type with
 * the userland installer so both paths read the same on the way out.
 */
class ToolchainCancelled : InstallCancelledMarker("Toolchain install cancelled")

/**
 * One component did not land. Carries the row's id and name so the run can
 * report *which* one, after the other lane has been stopped and its rows put
 * back to Pending.
 */
class ComponentFailed(
    val id: String,
    val name: String,
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)
