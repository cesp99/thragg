package to.eyed.seeker.code.solana.toolchain

import android.content.Context
import android.system.ErrnoException
import android.system.Os
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
import to.eyed.seeker.code.terminal.GuestProcess
import to.eyed.seeker.code.terminal.InstallCancelledMarker
import to.eyed.seeker.code.terminal.ShellCommand
import to.eyed.seeker.code.terminal.TerminalService
import to.eyed.seeker.code.terminal.TerminalSessions
import to.eyed.seeker.code.terminal.Userland
import to.eyed.seeker.code.terminal.UserlandState
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
 * Modelled on [to.eyed.seeker.code.terminal.UserlandInstaller] and for the
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
 *     [Userland.backend] install and it goes through proot as it always did.
 *  3. **`cargo-build-sbf` execs `rustup`.** rustup is installed with
 *     `--default-toolchain none`, so it downloads no compiler, and
 *     platform-tools' own `postInstall` then links itself in as the `solana`
 *     toolchain and makes it the default. Both halves are in the manifest.
 *
 * Resume is per component and it survives more than a dropped connection:
 * partial bytes live in the cache as `<id>.part` and are re-requested with an
 * HTTP `Range`, while *finished* components are recorded in
 * [SolanaToolchain.markInstalled] and skipped on the next run. A failed row's
 * Retry starts at that row. Nothing ever restarts the gigabyte.
 */
object ToolchainInstaller {

    private const val TAG = "seeker-toolchain"

    /** Progress is reported at most this often; a phone need not see more. */
    private const val PROGRESS_INTERVAL_MS = 250L

    /** Read and write in chunks this size — one page-aligned buffer. */
    private const val BUFFER = 64 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * The guest process the current step is waiting on, so [cancel] can stop
     * it. proot ignores SIGTERM and never sees SIGKILL, which is why this goes
     * through [GuestProcess.terminate] rather than `Process.destroy`.
     */
    @Volatile
    private var guest: Process? = null

    /** One row per component, in manifest order. Empty until [refresh]. */
    var rows: List<ComponentRow> by mutableStateOf(emptyList())
        private set

    var phase: ToolchainPhase by mutableStateOf(ToolchainPhase.Idle)
        private set

    /** Why the run stopped, for the line under the button. */
    var lastError: String? by mutableStateOf(null)
        private set

    val isRunning: Boolean get() = phase == ToolchainPhase.Running

    /** Whether every *required* row is in — the same question P4 asks. */
    val isComplete: Boolean
        get() = rows.isNotEmpty() &&
            rows.filter { it.component.required }.all { it.state is ComponentState.Installed }

    /** Bytes still to fetch, for the headline while a run is part-way through. */
    val remainingDownloadBytes: Long
        get() = rows.filter { it.state !is ComponentState.Installed }
            .sumOf { it.component.downloadBytes }

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
                    state = if (SolanaToolchain.isInstalled(app, component)) {
                        ComponentState.Installed
                    } else {
                        ComponentState.Pending
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
     * [to.eyed.seeker.code.ui.shell.ShellState.toolchainReady], which the nav
     * bar and the Build buttons read from the composition.
     */
    fun start(context: Context, onFinished: (Boolean) -> Unit = {}) {
        launchRun(context, from = null, onFinished = onFinished)
    }

    /**
     * Retry at [componentId] and carry on from there.
     *
     * Rows above it are already recorded and are skipped in a stat, not a
     * download; rows below it have not started. This is the "Retry on its own
     * row" of docs/UI.md, and the reason it can resume rather than restart is
     * that "installed" is recorded per component.
     */
    fun retry(context: Context, componentId: String, onFinished: (Boolean) -> Unit = {}) {
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
        guest?.let { process -> runCatching { GuestProcess.terminate(process) } }
        guest = null
        rows = rows.map { row ->
            if (row.state is ComponentState.Installed) row
            else if (row.state is ComponentState.Pending) row
            else row.copy(state = ComponentState.Cancelled)
        }
        phase = if (isComplete) ToolchainPhase.Complete else ToolchainPhase.Idle
    }

    // --- the run --------------------------------------------------------------

    private fun launchRun(context: Context, from: String?, onFinished: (Boolean) -> Unit) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        lastError = null
        phase = ToolchainPhase.Running
        job = scope.launch {
            val self = coroutineContext[Job]
            val ok = runCatching { run(app, from) }.getOrElse { error ->
                if (error is InstallCancelledMarker) {
                    Log.i(TAG, "toolchain install cancelled")
                } else {
                    Log.e(TAG, "toolchain install failed", error)
                    lastError = error.message ?: error.javaClass.simpleName
                }
                false
            }
            // Only if this run is still the current one: a cancel followed
            // immediately by a Start hands `job` to the new run, and clearing
            // it from here would leave that one uncancellable.
            if (job === self) job = null
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
                onFinished(isComplete)
            }
        }
        // The notification that keeps Android from reaping proot while the
        // screen is off. Started on the main thread because startForegroundService
        // is an activity-manager call and the rest of the app does it there.
        scope.launch(Dispatchers.Main) { holdForegroundService(app) }
    }

    private fun run(app: Context, from: String?): Boolean {
        val manifest = ToolchainManifest.load(app)
        if (!Userland.backend.isSupported) {
            error("the Linux guest is not available, so it cannot install a Solana toolchain")
        }

        val startIndex = from
            ?.let { id -> manifest.components.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: 0
        val queue = manifest.components.drop(startIndex)
            .filterNot { SolanaToolchain.isInstalled(app, it) }
        checkSpace(app, queue)

        for (component in queue) {
            ensureActive()
            setState(component.id, ComponentState.Working("Starting", now()))
            try {
                install(app, manifest, component)
            } catch (cancelled: InstallCancelledMarker) {
                setState(component.id, ComponentState.Cancelled)
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "${component.id} failed", error)
                val message = error.message ?: error.javaClass.simpleName
                setState(component.id, ComponentState.Failed(message))
                lastError = "${component.name}: $message"
                return false
            }
            SolanaToolchain.markInstalled(app, component)
            setState(component.id, ComponentState.Installed)
        }

        cleanCargoScratch(app, manifest)
        return true
    }

    /**
     * Refuse before starting rather than fail halfway through an unpack.
     *
     * The peak is what is left to install *plus* the largest single download,
     * because a tarball sits in the cache while it is being unpacked into the
     * rootfs beside it. Failing with two numbers is the only failure here a
     * user can act on.
     */
    private fun checkSpace(app: Context, queue: List<ToolchainComponent>) {
        if (queue.isEmpty()) return
        val needed = queue.sumOf { it.installBytes } + queue.maxOf { it.downloadBytes }
        val free = app.filesDir.usableSpace
        if (free < needed) {
            error(
                "not enough space: ${formatBytes(needed)} needed, ${formatBytes(free)} free"
            )
        }
    }

    private fun install(app: Context, manifest: ToolchainManifest, component: ToolchainComponent) {
        when (component.method) {
            InstallMethod.Userland -> installUserland(app, component)
            InstallMethod.Apt -> installApt(app, component)
            InstallMethod.Binary -> installBinary(app, component)
            InstallMethod.GzSingleBinary -> installGzBinary(app, component)
            InstallMethod.Tarball -> installTarball(app, component)
            InstallMethod.CargoInstall -> installCrate(app, manifest, component)
        }
        for (line in component.postInstall) {
            ensureActive()
            setState(component.id, ComponentState.Working(line.first().substringAfterLast('/'), now()))
            val exit = runInGuest(app, line, apt = false) { }
            if (exit != 0) error("`${line.joinToString(" ")}` exited $exit")
        }
        verify(app, component)
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
            isActive = { job?.isActive != false },
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
        val script = buildString {
            append("export DEBIAN_FRONTEND=noninteractive; ")
            append("apt-get update -qq && ")
            append("apt-get install -y --no-install-recommends ")
            append(component.packages.joinToString(" "))
        }
        val exit = runInGuest(app, listOf("/bin/bash", "-c", script), apt = true) { line ->
            setState(component.id, ComponentState.Working(line.take(80), started))
        }
        if (exit != 0) error("apt-get exited $exit")
    }

    /** One downloaded file, copied in as it came and made executable. */
    private fun installBinary(app: Context, component: ToolchainComponent) {
        val file = download(app, component)
        val target = SolanaToolchain.hostPath(app, component.installPath)
        target.parentFile?.mkdirs()
        setState(component.id, ComponentState.Working("Installing", now()))
        file.copyTo(target, overwrite = true)
        chmodExecutable(target)
        file.delete()
    }

    /** A gzipped ELF — how rust-analyzer ships its server. */
    private fun installGzBinary(app: Context, component: ToolchainComponent) {
        val file = download(app, component)
        val target = SolanaToolchain.hostPath(app, component.installPath)
        target.parentFile?.mkdirs()
        setState(component.id, ComponentState.Working("Unpacking", now()))
        GZIPInputStream(file.inputStream().buffered()).use { source ->
            target.outputStream().use { sink -> source.copyTo(sink, BUFFER) }
        }
        chmodExecutable(target)
        file.delete()
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
    private fun installTarball(app: Context, component: ToolchainComponent) {
        val file = download(app, component)
        val target = SolanaToolchain.hostPath(app, component.installPath)
        target.mkdirs()
        setState(component.id, ComponentState.Working("Unpacking", now()))
        val log = File(app.cacheDir, "toolchain-unpack.log")
        val process = ProcessBuilder("/system/bin/tar", "xf", file.absolutePath)
            .directory(target)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        guest = process
        val exit = process.waitFor()
        guest = null
        // The bytes are worth more than the disk: keep the archive only long
        // enough to unpack it, then give 505 MB back before the next component.
        file.delete()
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
        val exit = runInGuest(app, listOf("/bin/bash", "-c", argv.joinToString(" ")), apt = false) { line ->
            setState(component.id, ComponentState.Working(line.take(80), started))
        }
        if (exit != 0) error("cargo install $crate exited $exit")
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
        val partial = File(app.cacheDir, "solana-toolchain").apply { mkdirs() }
            .let { File(it, "${component.id}.part") }

        var attempt = 0
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
            fetch(component, url, partial)
            val actual = sha256Of(partial)
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
        return GuestProcess.run(
            command,
            onStart = { process -> guest = process },
            onRecord = onLine,
        ).also { guest = null }
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
        runCatching { to.eyed.seeker.code.core.SafeDelete.deleteTree(scratch) }
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

    private fun setState(id: String, state: ComponentState) {
        rows = rows.map { row -> if (row.component.id == id) row.copy(state = state) else row }
    }

    private fun ensureActive() {
        if (job?.isActive == false) throw ToolchainCancelled()
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
