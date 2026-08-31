package to.eyed.seeker.code.core

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import to.eyed.seeker.code.terminal.GuestProcess
import to.eyed.seeker.code.terminal.Userland

/**
 * Installing Debian packages into the guest: the parts that are the same
 * whatever is being installed.
 *
 * P5-2 worked all of this out for language servers — how to ask apt what
 * something costs without installing it, how to read the answer, and how to
 * turn apt's failures into a sentence. Phase 6 needs the same thing for Node,
 * and a second copy would be a second behaviour, so the general half lives
 * here and the two callers ([LanguageServers], the language-server table) supply only
 * what differs: which packages, and what to say about them.
 *
 * **Nothing here installs anything on its own.** Every path starts from
 * something the user did, and [AptInstaller.offer] only ever *asks* — the
 * rule Zed follows for extensions (extensions_ui/src/extension_suggest.rs:176)
 * and the one the clone dialog and the userland banner already follow.
 */
object Apt {

    /**
     * A Debian binary-package name, as policy defines it: lower case, and only
     * `+ - .` besides letters and digits. Checked rather than assumed because
     * [installArgv] hands the names to `/bin/sh -c` — the two commands apt
     * needs cannot be one argv — and a name with a space or a `;` in it would
     * be a command, not a package.
     */
    val PACKAGE_NAME = Regex("[a-z0-9][a-z0-9+.-]+")

    /**
     * What an install would cost, without installing anything.
     *
     * **Not `apt-get install -s`, and this was checked in apt's own source
     * rather than assumed.** The simulator returns from `InstallPackages`
     * *before* the block that prints the sizes — apt 3.0.3 (trixie, our
     * stable) `apt-private/private-install.cc:364-376` returns at the end of
     * "Run the simulator ..", and the `Need to get %sB of archives` /
     * `After this operation, %sB` lines are at 393-411, below it. apt 2.6.1
     * (bookworm) has the same ordering at 232-233 and 264-269. So `-s` can say
     * *what* would be installed and never *what it costs*, and a prompt built
     * on it would have quoted a price it never had.
     *
     * `--assume-no` goes down the real path — statistics, then the "Do you
     * want to continue?" prompt — and answers N itself without reading stdin
     * (`apt-private/private-output.cc:975-1030`), so nothing is fetched,
     * nothing is unpacked, and apt exits 1 having printed the number we came
     * for. It is the dry run apt actually has.
     *
     * A plain argv rather than a shell line: one command, so nothing needs
     * quoting, and the package names never reach a shell at all.
     */
    fun estimateArgv(packages: List<String>): List<String> =
        listOf("apt-get", "install", "--assume-no", "--no-install-recommends", "--") + packages

    /**
     * The install itself: refresh the lists, then install exactly the named
     * packages and nothing they merely recommend.
     *
     * `apt-get update` first for the same reason the clone does it: a rootfs
     * whose lists were fetched at image time cannot resolve today's versions,
     * and apt fails with "404 Not Found" rather than saying so.
     */
    fun installArgv(packages: List<String>): List<String> {
        check(packages.isNotEmpty()) { "an install needs at least one package" }
        check(packages.all { PACKAGE_NAME.matches(it) }) {
            "not a Debian package name: ${packages.firstOrNull { !PACKAGE_NAME.matches(it) }}"
        }
        return listOf(
            "/bin/sh", "-c",
            "apt-get update && apt-get install -y --no-install-recommends -- " +
                packages.joinToString(" "),
        )
    }

    /**
     * The environment both commands run in.
     *
     * `DEBIAN_FRONTEND=noninteractive` is what stops dpkg opening a dialog on
     * a terminal that is not there — the same trap `GIT_TERMINAL_PROMPT=0`
     * closes for git. `LC_ALL=C` is not cosmetic: [parsePlan] reads apt's own
     * words, and a translated "Need to get" would silently become "size
     * unknown".
     */
    val ENVIRONMENT: List<String> = listOf(
        "DEBIAN_FRONTEND=noninteractive",
        "LC_ALL=C",
        "LANG=C",
    )

    // --- reading apt ---------------------------------------------------------

    /**
     * `Need to get 4,096 kB of archives.`, and its partly-cached form
     * `Need to get 0 B/12.4 MB of archives.` — the second number is the total,
     * which is why the first group is optional and discarded.
     *
     * apt 3.0 rewrote this line as `  Download size: 12.4 MB` when
     * `APT::Output-Version` is 30 or above (private-install.cc:396-401), which
     * `apt` sets and `apt-get` does not — so both spellings are read, because
     * which one arrives is a property of the guest's apt rather than of us.
     */
    private val NEED_TO_GET =
        Regex("""(?:Need to get|Download size:) (?:[\d.,]+ ?[kMGT]?B ?/ ?)?([\d.,]+) ?([kMGT]?B)""")

    /**
     * `After this operation, 44.0 MB of additional disk space will be used.`,
     * and apt 3.0's `  Space needed: 44.0 MB / 3,600 MB available`
     * (private-install.cc:405-467).
     */
    private val AFTER_OPERATION = Regex(
        """(?:After this operation, ([\d.,]+) ?([kMGT]?B) of additional disk space""" +
            """|Space needed: ([\d.,]+) ?([kMGT]?B))"""
    )

    /** `0 upgraded, 2 newly installed, 0 to remove and 0 not upgraded.` */
    private val NEWLY_INSTALLED = Regex("""(\d+) newly installed""")

    /** `E: Unable to locate package python3-pylsp` */
    private val UNABLE_TO_LOCATE = Regex("""Unable to locate package (\S+)""")

    /**
     * Read [output] — everything the dry run printed — into a plan.
     *
     * Every field is allowed to be missing, because every one of them really
     * is on some path: a package already installed prints no size at all, and
     * a rootfs that has never run `apt-get update` prints nothing but
     * "Unable to locate package".
     */
    fun parsePlan(output: String): AptPlan = AptPlan(
        downloadBytes = NEED_TO_GET.find(output)
            ?.let { bytesOf(it.groupValues[1], it.groupValues[2]) },
        diskBytes = AFTER_OPERATION.find(output)?.let { match ->
            // One alternative or the other matched; the empty pair is the one
            // that did not.
            bytesOf(match.groupValues[1], match.groupValues[2])
                ?: bytesOf(match.groupValues[3], match.groupValues[4])
        },
        newPackages = NEWLY_INSTALLED.find(output)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
        missing = UNABLE_TO_LOCATE.findAll(output).map { it.groupValues[1] }.distinct().toList(),
        hasSummary = NEWLY_INSTALLED.containsMatchIn(output),
    )

    /**
     * apt's units are SI — it prints 1,000 bytes as `1,000 B` and 1,000,000 as
     * `1,000 kB` — so kB is a thousand here, not 1024. Sizes are reported the
     * way apt reported them or not at all.
     */
    private fun bytesOf(number: String, unit: String): Long? {
        val value = number.replace(",", "").toDoubleOrNull() ?: return null
        val scale = when (unit) {
            "B" -> 1.0
            "kB" -> 1_000.0
            "MB" -> 1_000_000.0
            "GB" -> 1_000_000_000.0
            "TB" -> 1_000_000_000_000.0
            else -> return null
        }
        return (value * scale).toLong()
    }

    /** apt's own spelling of a size: `857 kB`, `12.4 MB`, `1.2 GB`. */
    fun formatBytes(bytes: Long?): String? {
        if (bytes == null || bytes < 0) return null
        if (bytes < 1_000) return "$bytes B"
        val units = listOf("kB", "MB", "GB", "TB")
        var value = bytes / 1_000.0
        var unit = 0
        while (value >= 1_000 && unit < units.lastIndex) {
            value /= 1_000
            unit++
        }
        // One decimal below a hundred, which is apt's own rule — its
        // `SizeToStr` (apt-pkg/contrib/strutl.cc) prints `%.1f` while the
        // value is under 100 and `%.0f` above it, so "12.4 MB" keeps its
        // tenth and "115 MB" does not. The number beside apt's own transcript
        // has to be spelled the way apt spells it.
        val text = if (value < 100) {
            // Locale.US, or a device set to Italian prints "12,4 MB" beside
            // apt's own "12.4 MB" in the transcript underneath.
            String.format(java.util.Locale.US, "%.1f", value)
        } else {
            value.toInt().toString()
        }
        return "$text ${units[unit]}"
    }

    /**
     * A sentence the user can act on, from whatever apt printed. [what] names
     * the thing that could not be installed, as a noun phrase.
     *
     * Same shape and same intent as `GitClone.explain`: apt has already said
     * it better than we can, so its words are kept verbatim underneath and
     * this goes in front of them.
     */
    fun explain(output: String, what: String): String {
        val text = output.lowercase()
        return when {
            "could not resolve" in text ||
                "temporary failure resolving" in text ||
                "network is unreachable" in text ||
                "connection timed out" in text ||
                "connection failed" in text ->
                "Could not reach the Debian archive"

            "unable to locate package" in text ->
                "Debian could not find $what"

            "no space left on device" in text || "not enough free space" in text ->
                "There is not enough room left on the device"

            "could not get lock" in text || "unable to lock" in text ->
                "Another apt is already running in the userland"

            "is not signed" in text || "no_pubkey" in text || "not trusted" in text ->
                "The archive's signatures could not be checked"

            "not found" in text && "http" in text ->
                "The archive has moved on; apt-get update in the terminal will resync it"

            else -> "Could not install $what"
        }
    }
}

/**
 * What apt says an install would cost, read out of the dry run
 * ([Apt.estimateArgv]).
 *
 * Nothing here is guessed. A number apt did not print is null and the prompt
 * says so, because a made-up download size is exactly the kind of lie that
 * turns into "it said 12 MB and used 300".
 */
data class AptPlan(
    /** Bytes to download, or null when apt did not say. */
    val downloadBytes: Long?,
    /** Bytes the install will occupy, or null. */
    val diskBytes: Long?,
    /** How many packages apt would newly install. */
    val newPackages: Int,
    /** Packages apt could not find — usually because its lists are empty. */
    val missing: List<String>,
    /**
     * Whether apt printed its `N upgraded, N newly installed …` summary at
     * all.
     *
     * Without this, "apt said nothing" and "apt said there is nothing to do"
     * are the same plan — zero packages, no size — and a guest where `apt-get`
     * itself failed to start would be read as "already installed", which is
     * the one answer that leaves the user nowhere to go.
     */
    val hasSummary: Boolean,
)

/**
 * Something [AptInstaller] can install, and the sentences said about it.
 *
 * The sentences live on the target rather than in the installer because they
 * are the only part that is not general: "Python needs a language server" and
 * "The agent panel needs Node" are the same dialog saying different things.
 */
interface AptTarget {
    /** Debian packages, in the order apt should be given them. */
    val packages: List<String>

    /** "python3-pylsp and python3-pyflakes" — the packages, said out loud. */
    val packageList: String
        get() = when (packages.size) {
            0 -> ""
            1 -> packages[0]
            2 -> "${packages[0]} and ${packages[1]}"
            else -> packages.dropLast(1).joinToString(", ") + " and " + packages.last()
        }

    /** "is" for one package, "are" for several — [packageList]'s verb. */
    val packagesAre: String
        get() = if (packages.size == 1) "is" else "are"

    /** The question itself, with apt's price in it when apt gave one. */
    fun question(plan: AptPlan?): String

    /** The line under the question: what apt will do, and what it could not say. */
    fun detail(plan: AptPlan?, userland: String): String

    /** What to say once it is installed. */
    fun installedMessage(): String

    /** What to say when apt has everything already and it still is not working. */
    fun alreadyInstalledMessage(): String
}

/** What the prompt draws. Mirrors `CloneState`, for the same reasons. */
sealed interface AptInstallState {
    /** Nothing running: the prompt shows the list, or nothing at all. */
    data object Idle : AptInstallState

    /** Asking apt what it would cost. Short, but not instant on a phone. */
    data class Checking(val target: AptTarget) : AptInstallState

    /** The question. [plan] is null when apt could not be asked at all. */
    data class Offered(val target: AptTarget, val plan: AptPlan?) : AptInstallState

    /**
     * apt has every package already and the thing still is not working.
     * Offering the install again would be a loop with nothing on screen to
     * explain it — the lesson `GitClone` learned when apt succeeded and the
     * clone still failed.
     */
    data class AlreadyInstalled(val target: AptTarget) : AptInstallState

    /** [step] is apt's last line, throttled to ~10 Hz. */
    data class Installing(val target: AptTarget, val step: String) : AptInstallState

    /** [detail] is apt's own words, kept verbatim. */
    data class Failed(
        val target: AptTarget?,
        val summary: String,
        val detail: String?,
    ) : AptInstallState

    data class Finished(val target: AptTarget) : AptInstallState
}

/**
 * Which target a state is about, or null for [AptInstallState.Idle] and for a
 * failure with nothing to retry.
 *
 * An extension rather than an interface member so the data classes keep their
 * own plain `target`: a prompt asks this one so that a state left over from
 * one target cannot answer for another.
 */
val AptInstallState.targetOrNull: AptTarget?
    get() = when (this) {
        is AptInstallState.Checking -> target
        is AptInstallState.Offered -> target
        is AptInstallState.Installing -> target
        is AptInstallState.AlreadyInstalled -> target
        is AptInstallState.Finished -> target
        is AptInstallState.Failed -> target
        AptInstallState.Idle -> null
    }

/**
 * Installing a set of Debian packages from apt, asked first and cancellable
 * throughout.
 *
 * The state lives in the instance rather than in the composition for the
 * reason [to.eyed.seeker.code.terminal.GitClone]'s does: the prompt is a
 * dialog, and dismissing it must not abandon a running `apt-get` half way
 * through unpacking. Reopening the prompt shows the install still going.
 *
 * One instance per kind of thing installed, so a language-server install and a
 * Node install cannot overwrite each other's state — they can still collide on
 * apt's own lock inside the guest, which apt reports and [Apt.explain] turns
 * into "Another apt is already running in the userland".
 *
 * Nothing starts on its own. [offer] is called by something the user did and
 * only ever *asks*; [install] is the answer.
 */
class AptInstaller(private val tag: String) {

    /** Keep the tail of apt's own words for the error message. */
    private val transcriptLines = 12

    var state by mutableStateOf<AptInstallState>(AptInstallState.Idle)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * Bumped by every start and every cancel, so a job finishing after it was
     * cancelled — the kill leaves apt with a non-zero status, which looks like
     * a failure — cannot report over whatever the user is doing now.
     */
    @Volatile
    private var generation = 0

    @Volatile
    private var running: Process? = null

    /**
     * False in builds with no userland: the UI must not offer this at all.
     *
     * True in every build that ships today. It is still asked rather than
     * assumed, because it is the seam's own answer (`UserlandBackend`) and the
     * rule it encodes outlives the edition that motivated it: no guest means
     * no apt, `execCommand` returns null, and installing is *absent* — exactly
     * as cloning is (`GitClone.isSupported`) — rather than shown greyed out.
     */
    val isSupported: Boolean get() = Userland.backend.isSupported

    val isBusy: Boolean
        get() = state is AptInstallState.Checking || state is AptInstallState.Installing

    /** Back to nothing. Ignored while apt is running. */
    fun dismiss() {
        if (!isBusy) state = AptInstallState.Idle
    }

    /**
     * Ask apt what installing [target] would cost, and then ask the user.
     *
     * Never installs. What this runs is [Apt.estimateArgv], which answers
     * apt's own confirmation prompt with "no" — nothing is fetched and nothing
     * is unpacked.
     */
    fun offer(context: Context, target: AptTarget) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        val mine = ++generation
        state = AptInstallState.Checking(target)
        job = scope.launch {
            val result = runCatching { estimate(app, target) }.getOrElse { error ->
                Log.w(tag, "apt estimate failed", error)
                // A simulation we could not run is not a reason to refuse:
                // ask the question without the price rather than dead-ending.
                AptInstallState.Offered(target, null)
            }
            running = null
            if (generation != mine) return@launch
            state = result
        }
    }

    /** Say yes to what [offer] asked. */
    fun install(context: Context, target: AptTarget) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        val mine = ++generation
        state = AptInstallState.Installing(target, "Starting")
        job = scope.launch {
            val result = runCatching { apt(app, target, mine) }.getOrElse { error ->
                Log.e(tag, "install failed", error)
                AptInstallState.Failed(
                    target,
                    "Could not install ${target.packageList}",
                    error.message,
                )
            }
            running = null
            if (generation != mine) return@launch
            state = result
        }
    }

    /**
     * Stop apt and forget it.
     *
     * The signalling runs in its own coroutine, a sibling of the install's, so
     * cancelling cannot cancel the cleanup — and so the main thread never
     * blocks on SIGQUIT's grace period. Nothing is deleted: a half-unpacked
     * package is dpkg's to sort out on the next run, and deleting a rootfs
     * directory behind its back would be worse than leaving it.
     */
    fun cancel() {
        val active = job?.takeIf { it.isActive } ?: run {
            dismiss()
            return
        }
        // Captured now, on the main thread: by the time terminate() runs the
        // user may have started something else, and this cancellation must not
        // kill that instead.
        val doomed = running
        running = null
        job = null
        generation++
        state = AptInstallState.Idle
        scope.launch {
            doomed?.let { GuestProcess.terminate(it) }
            active.cancel()
        }
    }

    // --- the work ------------------------------------------------------------

    private fun estimate(context: Context, target: AptTarget): AptInstallState {
        val command = Userland.backend.execCommand(
            context,
            ProjectsRoot.directory(context).absolutePath,
            Apt.estimateArgv(target.packages),
            Apt.ENVIRONMENT,
        ) ?: return AptInstallState.Failed(target, noUserland(), null)

        val transcript = StringBuilder()
        // The exit status is deliberately ignored. A dry run that found work
        // to do exits 1 ("Abort."), one that found none exits 0, and one that
        // could not find a package exits 100 — all three have something worth
        // reading in them, and none of them is a failure to report.
        GuestProcess.run(command, onStart = { running = it }) { record ->
            transcript.appendLine(record)
        }
        val plan = Apt.parsePlan(transcript.toString())
        // apt never got as far as its own summary — no apt in the rootfs, a
        // broken sources.list, proot refusing to start. Ask the question
        // without a price rather than claiming to know something.
        // A plan that names packages apt could not find is worth showing even
        // though apt never reached its summary — that list is *why* there is
        // no price, and the sentence built for it was otherwise unreachable.
        if (!plan.hasSummary) {
            return AptInstallState.Offered(target, plan.takeIf { it.missing.isNotEmpty() })
        }
        // Everything present and nothing to install: the packages are there
        // and it still would not work, which the user needs told rather than
        // offered a no-op download.
        if (plan.newPackages == 0 && plan.missing.isEmpty() && plan.downloadBytes == null) {
            return AptInstallState.AlreadyInstalled(target)
        }
        return AptInstallState.Offered(target, plan)
    }

    private fun apt(
        context: Context,
        target: AptTarget,
        /**
         * The install this reader belongs to. A cancelled apt keeps producing
         * records through proot's SIGQUIT grace period, and a later install
         * has already taken the state by then — guarding on "still
         * Installing" is not enough, because the later one *is* Installing.
         */
        mine: Int,
    ): AptInstallState {
        val command = Userland.backend.execCommand(
            context,
            ProjectsRoot.directory(context).absolutePath,
            Apt.installArgv(target.packages),
            Apt.ENVIRONMENT,
        ) ?: return AptInstallState.Failed(target, noUserland(), null)

        val transcript = ArrayDeque<String>()
        var lastStep = 0L
        val exit = GuestProcess.run(command, onStart = { running = it }) { record ->
            if (transcript.size >= transcriptLines) transcript.removeFirst()
            transcript.addLast(record)
            // apt is chatty — every "Get:12 http://…" is a record — and a
            // phone need not repaint for each. The same 10 Hz ceiling the
            // clone's progress uses.
            val now = System.nanoTime()
            // Never resurrect a state a cancel has already moved past.
            if (now - lastStep >= GuestProcess.PROGRESS_INTERVAL_NS &&
                generation == mine &&
                state is AptInstallState.Installing
            ) {
                lastStep = now
                state = AptInstallState.Installing(target, record.take(120))
            }
        }
        if (exit == 0) return AptInstallState.Finished(target)
        val output = transcript.joinToString("\n")
        return AptInstallState.Failed(
            target,
            Apt.explain(output, target.packageList),
            output.ifBlank { null },
        )
    }

    /**
     * Why there is nowhere to run apt. Reached before Debian is installed;
     * the unsupported branch is unreachable today, because the UI leaves the
     * action out entirely when [isSupported] is false.
     */
    private fun noUserland(): String =
        if (Userland.backend.isSupported) {
            "${Userland.backend.displayName} is not installed yet — open the terminal to " +
                "install it, then try again"
        } else {
            "The Linux guest is not available, so there is nothing to install into"
        }
}
