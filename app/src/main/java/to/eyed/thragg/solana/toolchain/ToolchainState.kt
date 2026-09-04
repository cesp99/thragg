package to.eyed.thragg.solana.toolchain

import android.content.Context
import android.util.Log
import org.json.JSONObject
import to.eyed.thragg.core.SafeDelete
import to.eyed.thragg.terminal.Userland
import to.eyed.thragg.terminal.UserlandState
import java.io.File

/**
 * What is installed, what a row is doing, and the two things the rest of the
 * app asks the toolchain.
 *
 * [SolanaToolchain] is the read side and it is deliberately tiny: P4 asks
 * `isReady`, the terminal asks for the guest `PATH`, Settings asks how much
 * disk it is holding. The write side — fetch, verify, unpack, compile — is all
 * in ToolchainInstaller.kt, so nothing that merely *reads* the toolchain has to
 * link against the installer.
 */

/**
 * One row's state. Two shapes and no more, matching the two kinds of row the
 * Setup screen draws (docs/UI.md, "Setup"): [Downloading] counts bytes,
 * [Working] counts seconds. A component that is compiling on the device is
 * never in [Downloading], which is what keeps a MB bar off a `cargo install`.
 */
sealed interface ComponentState {

    /** Not started. `·` in the wireframe. */
    data object Pending : ComponentState

    /**
     * Bytes are moving. [received] and [total] are the *file's* bytes, so a
     * resumed download starts at what is already on disk rather than at zero —
     * the number on screen is the number of bytes that exist, not the number
     * transferred this attempt.
     */
    data class Downloading(
        val received: Long,
        val total: Long,
        /** Bytes per second over the last window, or null before the first one. */
        val bytesPerSecond: Long?,
    ) : ComponentState {
        val fraction: Float? get() = if (total > 0L) (received.toFloat() / total) else null
    }

    /**
     * Something is happening that is not a transfer: an unpack, an apt run, a
     * four-minute compile. [step] is the last thing the step said — a line of
     * apt's output, a crate name — and [startedAt] is what the elapsed timer
     * on a compile row counts from.
     */
    data class Working(val step: String, val startedAt: Long) : ComponentState

    /**
     * Bytes down and unpacked, waiting for the guest lane to register and
     * verify it. Drawn as a pending row with a word under it, not as a
     * spinner: nothing is happening to this one right now, and a bar that
     * moved would say otherwise.
     */
    data object Staged : ComponentState

    /** Present, verified, recorded. `✓`. */
    data object Installed : ComponentState

    /**
     * It did not land. The row keeps its own Retry and the run stops here;
     * nothing already installed is touched, so retrying never restarts the
     * gigabyte (docs/UI.md, "First run", step 2).
     */
    data class Failed(val message: String) : ComponentState

    /** The user cancelled while this row was the one running. */
    data object Cancelled : ComponentState

    /**
     * Installed at an earlier revision than the manifest now names: the
     * binary is there and Build still works, and an Update fetches the new
     * one. The distinction from [Pending] is the whole update story — an
     * outdated required row must not put the gate back up.
     */
    data object Outdated : ComponentState
}

/** A component and what it is doing — the unit the Setup screen draws. */
data class ComponentRow(
    val component: ToolchainComponent,
    val state: ComponentState,
)

/** What the whole install is doing, which is what the primary button reads. */
enum class ToolchainPhase {
    /** Nothing running. The button is Start, or Done when everything is in. */
    Idle,

    /** A run is in flight. The button is Pause. */
    Running,

    /** Every component in this run landed. */
    Complete,

    /** A row failed and the run stopped there. The button is Retry. */
    Failed,
}

/**
 * The installed toolchain: what is there, and how a guest process reaches it.
 *
 * Two callers matter. P4 asks [isReady] before enabling the three Build
 * buttons — with it false, pressing ▶ pushes Setup instead of failing
 * (docs/UI.md, "First run", step 8). ShellEnvironment asks for
 * [guestEnvironment] so that every terminal session, task and language server
 * has `cargo`, `rustup` and LLVM's `ld.lld` on `PATH`.
 */
object SolanaToolchain {

    private const val TAG = "seeker-toolchain"

    /**
     * The guest `PATH` entries the toolchain contributes, ahead of Debian's own.
     *
     * Straight out of tools/device-toolchain.sh, where each one was needed:
     * `/root/.cargo/bin` is rustup's shim directory and therefore where `cargo`
     * and `rustc` resolve from; `/opt/solana/cli/bin` is where the two
     * on-device compiles install to; the LLVM directory carries `ld.lld`,
     * `llvm-readelf` and the rest of the SBF link step.
     *
     * Exported whether or not the toolchain is installed. A `PATH` entry that
     * does not exist costs a failed `stat` per lookup and nothing else, and the
     * alternative is a disk read on the path that starts every shell.
     */
    val GUEST_PATH_ENTRIES = listOf(
        "/root/.cargo/bin",
        "/opt/solana/cli/bin",
        "/opt/solana/platform-tools/llvm/bin",
    )

    /** As one `PATH` fragment, without a trailing separator. */
    val GUEST_PATH_PREFIX: String = GUEST_PATH_ENTRIES.joinToString(":")

    /**
     * Debian's own `PATH`, which the prefix goes in front of.
     *
     * A copy of the string DebianUserland builds for a session, because the
     * environment handed to `execCommand` replaces entries rather than
     * prepending to them: to *lead* `PATH` we have to restate the rest of it.
     * If that file's default ever changes, this is the line to change with it.
     */
    const val GUEST_BASE_PATH =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /**
     * Where the guest's filesystem is on this side.
     *
     * The same literal SeekerShell.kt hands the engine in
     * `syncUserlandWithEngine` — DebianUserland keeps its own copy private, and
     * this is the third caller that needs to look *at* the rootfs rather than
     * run something inside it.
     */
    fun rootfs(context: Context): File = File(context.applicationContext.filesDir, "debian")

    /** A guest absolute path, seen from this side. */
    fun hostPath(context: Context, guestPath: String): File =
        File(rootfs(context), guestPath.trimStart('/'))

    /**
     * Whether Build can run.
     *
     * Every *required* component has to be both recorded and still on disk. The
     * second half is not paranoia: a user who freed 1.4 GB from Settings, or an
     * install the system killed mid-unpack, would otherwise leave a record
     * saying yes over a rootfs that says no, and the failure would surface as
     * an unreadable linker error four minutes into a build.
     *
     * Cheap enough for the main thread — a handful of `stat` calls and one
     * small file, read once per process and cached.
     */
    fun isReady(context: Context): Boolean {
        val app = context.applicationContext
        if (!Userland.backend.isSupported) return false
        if (Userland.backend.state(app) !is UserlandState.Ready) return false
        val manifest = runCatching { ToolchainManifest.load(app) }.getOrNull() ?: return false
        // Present at *any* recorded revision: a manifest bump — from an app
        // update or from a remote manifest the Update button adopted — must
        // not turn a working phone into a gated one. The strict check,
        // [isInstalled], is what the rows and the Update button read.
        return manifest.components.filter { it.required }.all { isPresent(app, it) }
    }

    /** Whether one component is recorded at this manifest's revision and present. */
    fun isInstalled(context: Context, component: ToolchainComponent): Boolean {
        val app = context.applicationContext
        if (record(app)[component.id] != component.revision) return false
        // File.exists() follows symlinks, so this is also the dangling-symlink
        // check that `cargo install` under --link2symlink would otherwise slip
        // past — see ToolchainComponent.marker.
        return hostPath(app, component.marker).exists()
    }

    /** Whether one component is recorded at some revision and its marker exists. */
    fun isPresent(context: Context, component: ToolchainComponent): Boolean {
        val app = context.applicationContext
        if (component.id !in record(app)) return false
        return hostPath(app, component.marker).exists()
    }

    /** Present, but at a revision other than the manifest's: an Update fetches it. */
    fun isOutdated(context: Context, component: ToolchainComponent): Boolean =
        isPresent(context, component) && !isInstalled(context, component)

    /**
     * Whether another component unpacks into the same directory — the two
     * drivers both land in `/opt/solana/cli` — in which case an update must
     * overwrite rather than clear the directory first.
     */
    fun sharesInstallPath(context: Context, component: ToolchainComponent): Boolean {
        val manifest = runCatching { ToolchainManifest.load(context.applicationContext) }.getOrNull()
            ?: return true
        return manifest.components.any { it.id != component.id && it.installPath == component.installPath }
    }

    /**
     * Whether a fetched component's bytes are already unpacked in the rootfs
     * at this revision, with only its guest half — postInstall, verify —
     * still to run.
     *
     * Recorded separately from "installed" because the two lanes make it a
     * state a run can be interrupted in: platform-tools is staged while apt
     * is still running, and if apt fails, the next run must not fetch the
     * 505 MB again. The marker is checked as well as the record, for the
     * same reason [isInstalled] checks it.
     */
    fun isStaged(context: Context, component: ToolchainComponent): Boolean {
        val app = context.applicationContext
        if (component.url == null) return false
        if (stagedRecord(app)[component.id] != component.revision) return false
        return hostPath(app, component.marker).exists()
    }

    /** Bytes the toolchain is holding, for Settings → Toolchain. Blocking. */
    fun diskBytes(context: Context): Long {
        val app = context.applicationContext
        val manifest = runCatching { ToolchainManifest.load(app) }.getOrNull() ?: return 0L
        return manifest.components
            .filter { isInstalled(app, it) }
            .sumOf { it.installBytes }
    }

    /**
     * The environment a guest process needs to see the toolchain.
     *
     * Appended to a session's own, later entries winning, which is how `PATH`
     * is *led* rather than replaced. `CARGO_HOME` and `RUSTUP_HOME` are here
     * because `cargo-build-sbf` execs `rustup`, and a rustup that cannot find
     * its own home reports the toolchain as missing even though it is linked.
     */
    fun guestEnvironment(): List<String> = listOf(
        "PATH=$GUEST_PATH_PREFIX:$GUEST_BASE_PATH",
        "CARGO_HOME=/root/.cargo",
        "RUSTUP_HOME=/root/.rustup",
    )

    // --- the install record ---------------------------------------------------

    /**
     * Which components are in, keyed by id, valued by [ToolchainComponent.revision].
     *
     * A per-component record rather than one "the toolchain is installed" flag,
     * because that is what makes a resume and a per-row Retry possible at all:
     * an interrupted first run comes back with five rows already ✓ and starts
     * at the sixth (docs/UI.md, "Setup" — resume "across a dropped connection
     * AND across app restarts").
     */
    @Volatile
    private var cachedRecord: Map<String, String>? = null

    private fun recordFile(context: Context): File =
        File(context.applicationContext.filesDir, "solana-toolchain.json")

    fun record(context: Context): Map<String, String> {
        cachedRecord?.let { return it }
        val file = recordFile(context)
        val parsed = runCatching {
            if (!file.isFile) return@runCatching emptyMap<String, String>()
            val json = JSONObject(file.readText()).optJSONObject("components")
                ?: return@runCatching emptyMap<String, String>()
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrElse { error ->
            Log.w(TAG, "unreadable install record; treating the toolchain as absent", error)
            emptyMap()
        }
        cachedRecord = parsed
        return parsed
    }

    /**
     * Note that [component] landed at its current revision, and how long it
     * took. Blocking.
     *
     * [tookMillis] is kept beside the revision, in the same file, because the
     * install's own history is the only honest estimate of how long the next
     * one will take on *this* phone: a Seeker on a good Wi-Fi and the same
     * phone on a hotel network are the same manifest and very different
     * afternoons. Read back by [timings].
     */
    fun markInstalled(context: Context, component: ToolchainComponent, tookMillis: Long? = null) {
        val timings = if (tookMillis != null) timings(context) + (component.id to tookMillis) else timings(context)
        write(
            context,
            record(context) + (component.id to component.revision),
            timings,
            stagedRecord(context) - component.id,
        )
    }

    /** Note that [component]'s bytes are in the rootfs at its current revision. Blocking. */
    fun markStaged(context: Context, component: ToolchainComponent) {
        write(context, record(context), timings(context), stagedRecord(context) + (component.id to component.revision))
    }

    /** Forget one component — a failed verify, or a removal. Blocking. */
    fun forget(context: Context, id: String) {
        write(context, record(context) - id, timings(context) - id, stagedRecord(context) - id)
    }

    @Volatile
    private var cachedStaged: Map<String, String>? = null

    private fun stagedRecord(context: Context): Map<String, String> {
        cachedStaged?.let { return it }
        val parsed = readMap(context, "staged") { it }
        cachedStaged = parsed
        return parsed
    }

    // --- timings ---------------------------------------------------------------

    @Volatile
    private var cachedTimings: Map<String, Long>? = null

    /** How long each recorded component took to install, in milliseconds, by id. */
    fun timings(context: Context): Map<String, Long> {
        cachedTimings?.let { return it }
        val parsed = readMap(context, "timings") { it.toLongOrNull() ?: 0L }
        cachedTimings = parsed
        return parsed
    }

    /** One object of the record file as a map, or empty when absent or unreadable. */
    private fun <T> readMap(context: Context, key: String, value: (String) -> T): Map<String, T> {
        val file = recordFile(context)
        return runCatching {
            if (!file.isFile) return@runCatching emptyMap<String, T>()
            val json = JSONObject(file.readText()).optJSONObject(key)
                ?: return@runCatching emptyMap<String, T>()
            json.keys().asSequence().associateWith { value(json.get(it).toString()) }
        }.getOrDefault(emptyMap())
    }

    private fun write(
        context: Context,
        entries: Map<String, String>,
        timings: Map<String, Long>,
        staged: Map<String, String>,
    ) {
        val components = JSONObject()
        for ((id, revision) in entries) components.put(id, revision)
        val took = JSONObject()
        for ((id, millis) in timings) took.put(id, millis)
        val stagedJson = JSONObject()
        for ((id, revision) in staged) stagedJson.put(id, revision)
        val json = JSONObject()
            .put("components", components)
            .put("timings", took)
            .put("staged", stagedJson)
        runCatching { recordFile(context).writeText(json.toString()) }
            .onFailure { Log.e(TAG, "could not write the install record", it) }
        cachedRecord = entries
        cachedTimings = timings
        cachedStaged = staged
    }

    /**
     * Delete everything the toolchain owns inside the guest, and forget it.
     *
     * The rootfs itself stays: the terminal, git and apt are useful without a
     * Solana toolchain, and "free 1.4 GB" must not also take away the shell.
     * Blocking, and symlink-safe — a rootfs is full of links and one of them
     * could point at the user's projects (see [SafeDelete]).
     */
    fun remove(context: Context) {
        val app = context.applicationContext
        val manifest = runCatching { ToolchainManifest.load(app) }.getOrNull() ?: return
        val root = rootfs(app)
        val directories = buildSet {
            add(manifest.guestRoot)
            add(manifest.cargoScratch)
            add("/opt/ra")
            add("/root/.cargo")
            add("/root/.rustup")
        }
        for (guest in directories) {
            val target = File(root, guest.trimStart('/'))
            if (SafeDelete.isInside(root, target)) SafeDelete.deleteTree(target)
        }
        for (component in manifest.components) {
            if (component.method != InstallMethod.Userland) forget(app, component.id)
        }
    }
}
