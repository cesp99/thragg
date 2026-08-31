package to.eyed.seeker.code.terminal

import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import android.system.OsConstants
import android.util.Log
import to.eyed.seeker.code.core.SafeDelete
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * A real Debian, running under proot.
 *
 * The rootfs is Debian's own container base image, pulled from the registry on
 * first use and unpacked into app storage. proot then fakes a filesystem root
 * for the shell, so `/usr/bin`, `/etc/apt` and the rest mean what Debian
 * expects them to mean, and `apt install` works against Debian's servers. We
 * maintain no packages and host no repository — that was the whole point of
 * the decision (agent-docs/DECISIONS.md, 2026-08-16).
 *
 * The whole app targets SDK 28 for this: Android refuses to execute downloaded
 * programs at any newer target, and every binary `apt` installs is a
 * downloaded program. That constraint used to be one product flavour's, with a
 * Play-compatible edition beside it that had no userland; app/build.gradle.kts
 * now carries it unconditionally in `defaultConfig`.
 */
internal fun createUserlandBackend(): UserlandBackend = DebianUserland

/**
 * Where proot builds its scaffolding for the *terminal's* instances.
 *
 * Its own directory, not the cache root, because the engine spawns short-lived
 * proots of its own and the two sharing one directory made each other's binds
 * disappear: with a shell open, a `git diff` from the panel came back "cannot
 * change to <the project>: No such file or directory".
 */
internal fun prootScratch(context: android.content.Context): java.io.File =
    java.io.File(context.cacheDir, "proot-terminal").apply { mkdirs() }

private const val TAG = "seeker-userland"

/** Debian's official container base image. `slim` is the smallest with apt. */
private const val IMAGE = "library/debian"
private const val TAG_NAME = "stable-slim"
private const val REGISTRY = "https://registry-1.docker.io/v2"
private const val AUTH = "https://auth.docker.io/token?service=registry.docker.io&scope=repository:$IMAGE:pull"

private val MANIFEST_TYPES = listOf(
    "application/vnd.oci.image.index.v1+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.v2+json",
).joinToString(", ")

/** Thrown when the user cancels; the failure path cleans up as usual. */
internal class InstallCancelled : InstallCancelledMarker("Install cancelled")

private object DebianUserland : UserlandBackend {

    override val isSupported = true
    override val displayName = "Debian"
    override val downloadDescription = "about 30 MB"

    private fun rootfs(context: Context) = File(context.filesDir, "debian")

    /** Written last, so its presence means a complete install. */
    private fun marker(context: Context) = File(rootfs(context), ".seeker-userland")

    /**
     * The image's hard-link entries, one `path<TAB>target` line each, written
     * at install time so [healHardLinks] can re-check them cheaply on every
     * session without re-reading a 30 MB archive that is long deleted.
     */
    private fun hardLinkIndex(root: File) = File(root, ".seeker-hardlinks")

    private fun proot(context: Context) =
        File(context.applicationInfo.nativeLibraryDir, "libproot_exec.so")

    override fun state(context: Context): UserlandState =
        if (marker(context).isFile) UserlandState.Ready else UserlandState.NotInstalled

    // --- running -----------------------------------------------------------

    override fun shellCommand(context: Context, projectDir: String): ShellCommand? =
        inside(context, projectDir, listOf("/bin/bash", "--login"))

    override fun execCommand(
        context: Context,
        hostWorkingDir: String?,
        argv: List<String>,
        extraEnvironment: List<String>,
    ): ShellCommand? =
        inside(context, hostWorkingDir, argv, extraEnvironment)

    /**
     * The same guest with `--link2symlink` dropped — see the interface, and
     * docs/SOLANA.md. The toolchain installer runs every step but apt through
     * this one, because under the rewrite `cargo install` reports success and
     * leaves a dangling symlink.
     */
    override fun execCommandRealLinks(
        context: Context,
        hostWorkingDir: String?,
        argv: List<String>,
        extraEnvironment: List<String>,
    ): ShellCommand? =
        inside(context, hostWorkingDir, argv, extraEnvironment, rewriteHardLinks = false)

    /**
     * Run [program] under proot with [hostWorkingDir] as the cwd, or null when
     * nothing is installed to run it in.
     *
     * One builder for both callers on purpose: an interactive shell and a
     * `git clone` must see the same fake root, the same bind mounts and the
     * same `/projects`, or the terminal and the rest of the app would disagree
     * about where a project is.
     */
    private fun inside(
        context: Context,
        hostWorkingDir: String?,
        program: List<String>,
        extraEnvironment: List<String> = emptyList(),
        rewriteHardLinks: Boolean = true,
    ): ShellCommand? {
        if (state(context) !is UserlandState.Ready) return null
        val root = rootfs(context)
        // The network may be a different one than at install time, and stale
        // resolvers are a maddening failure — apt hangs rather than saying
        // why. Cheap enough to redo per session: one IPC and a short write,
        // and only when the answer actually changed.
        refreshResolvConf(context, root)
        // Same rationale, worse failure: a guest whose hard links were
        // dropped (see [unpack]) fails as `apt` exiting 100 minutes later.
        // When healthy this is one small read and an lstat per link.
        runCatching { healHardLinks(root) }
        val projects = File(context.filesDir, "projects")

        val argv = listOf(
            "proot",
            // Be root inside: apt cannot chown its files otherwise.
            "-0",
        ) + (
            // dpkg unpacks hardlinks; app storage handles them, but proot's
            // translation of them is the well-trodden path. Dropped for
            // anything that hard-links a file it means to keep — `cargo
            // install` is the one that taught us, see [execCommandRealLinks].
            if (rewriteHardLinks) listOf("--link2symlink") else emptyList()
        ) + listOf(
            // Take the whole guest down with the session rather than leaving
            // processes behind for Android's phantom-process killer to reap.
            // This is also what makes cancelling a clone work: SIGQUIT to
            // proot takes git with it.
            "--kill-on-exit",
            // Some Debian packages refuse to install on kernels they think are
            // ancient; report something modern.
            "-k", "6.2.1",
            "-r", root.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${projects.absolutePath}:/projects",
            "-w", guestPath(projects, hostWorkingDir),
        ) + program

        return ShellCommand(
            executable = proot(context).absolutePath,
            argv = argv,
            environment = listOf(
                "PROOT_TMP_DIR=${prootScratch(context).absolutePath}",
                "HOME=/root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM=xterm-256color",
                "COLORTERM=truecolor",
                "LANG=C.UTF-8",
                "PS1=\\w \\$ ",
            ) + extraEnvironment,
        )
    }

    /**
     * Where an Android path shows up inside the guest.
     *
     * Only the projects directory is bound in, so anything else — and anything
     * that would have to climb out with `..` — lands in the guest's home
     * instead of naming a path that does not exist there.
     */
    private fun guestPath(projects: File, hostPath: String?): String {
        if (hostPath == null) return "/root"
        val relative = File(hostPath).relativeToOrNull(projects)?.path ?: return "/root"
        return when {
            relative.isEmpty() || relative == "." -> "/projects"
            relative.startsWith("..") -> "/root"
            else -> "/projects/$relative"
        }
    }

    // --- installing --------------------------------------------------------

    override fun install(
        context: Context,
        isActive: () -> Boolean,
        onProgress: (String, Float?) -> Unit,
    ): Result<Unit> =
        runCatching {
            val root = rootfs(context)
            // A half-finished rootfs is worse than none: start clean. Symlink
            // safe, because a rootfs is full of links and one of them could
            // point at the user's projects — see SafeDelete.
            SafeDelete.deleteTree(root)
            root.mkdirs()

            onProgress("Contacting the Debian image registry", null)
            val token = fetchToken()
            val layer = resolveLayer(token)
            Log.i(TAG, "layer ${layer.digest} (${layer.size} bytes)")

            // Unpacked, the base image is roughly three times its download, and
            // apt will want room on top. Failing here with a number beats
            // failing halfway through an unpack with ENOSPC.
            if (layer.size > 0) {
                val needed = layer.size * 4
                val free = context.filesDir.usableSpace
                if (free < needed) {
                    error(
                        "not enough space: ${needed / 1_000_000} MB needed, " +
                            "${free / 1_000_000} MB free"
                    )
                }
            }

            val blob = File(context.cacheDir, "debian-rootfs.tar.gz")
            downloadLayer(token, layer, blob, isActive) { fraction ->
                onProgress("Downloading Debian", fraction)
            }

            // Past this point the install is short and not interruptible; a
            // half-unpacked rootfs is deleted by the failure path either way.
            onProgress("Unpacking", null)
            unpack(context, blob, root)
            // The unpack above cannot deliver the archive's hard-link entries
            // (see [unpack] for what actually happens to them on hardware).
            // Walk the archive's own index and materialise each one as a
            // relative symlink, and keep the index so every later session can
            // re-check for pennies.
            val hardLinks = GZIPInputStream(blob.inputStream().buffered())
                .use { TarHardLinks.index(it) }
            hardLinkIndex(root).writeText(TarHardLinks.format(hardLinks))
            for (link in hardLinks) heal(root, link)
            blob.delete()

            onProgress("Configuring", null)
            configure(context, root)

            marker(context).writeText("${layer.digest}\n")
            Unit
        }.onFailure { error ->
            Log.e(TAG, "install failed", error)
            SafeDelete.deleteTree(rootfs(context))
            File(context.cacheDir, "debian-rootfs.tar.gz").delete()
        }

    override fun remove(context: Context) {
        SafeDelete.deleteTree(rootfs(context))
    }

    private data class Layer(val digest: String, val size: Long)

    private fun fetchToken(): String =
        JSONObject(URL(AUTH).readText()).getString("token")

    private fun open(url: String, token: String, accept: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer $token")
            if (accept != null) setRequestProperty("Accept", accept)
            connectTimeout = 30_000
            readTimeout = 30_000
        }

    /** Walk index → manifest → layer, picking the manifest for this ABI. */
    private fun resolveLayer(token: String): Layer {
        val wanted = when (val abi = android.os.Build.SUPPORTED_ABIS.first()) {
            "arm64-v8a" -> "arm64"
            "x86_64" -> "amd64"
            else -> error("no Debian image for $abi")
        }

        val top = open("$REGISTRY/$IMAGE/manifests/$TAG_NAME", token, MANIFEST_TYPES)
            .inputStream.bufferedReader().use { JSONObject(it.readText()) }

        val manifestDigest = if (top.has("manifests")) {
            val list = top.getJSONArray("manifests")
            (0 until list.length())
                .map { list.getJSONObject(it) }
                .firstOrNull { entry ->
                    val platform = entry.optJSONObject("platform")
                    platform?.optString("architecture") == wanted &&
                        platform.optString("os") == "linux"
                }
                ?.getString("digest")
                ?: error("the registry has no $wanted image")
        } else {
            null
        }

        val manifest = if (manifestDigest == null) {
            top
        } else {
            open("$REGISTRY/$IMAGE/manifests/$manifestDigest", token, MANIFEST_TYPES)
                .inputStream.bufferedReader().use { JSONObject(it.readText()) }
        }

        // A slim base image is one layer; if that ever changes, take the last,
        // which is the one carrying the filesystem.
        val layers = manifest.getJSONArray("layers")
        val layer = layers.getJSONObject(layers.length() - 1)
        return Layer(layer.getString("digest"), layer.optLong("size", -1))
    }

    /** Download to [into], verifying the registry's own digest as we go. */
    private fun downloadLayer(
        token: String,
        layer: Layer,
        into: File,
        isActive: () -> Boolean,
        onProgress: (Float?) -> Unit,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = open("$REGISTRY/$IMAGE/blobs/${layer.digest}", token)
        connection.instanceFollowRedirects = true
        connection.inputStream.use { source ->
            into.outputStream().use { sink ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                var lastReported = 0L
                while (true) {
                    if (!isActive()) {
                        into.delete()
                        throw InstallCancelled()
                    }
                    val read = source.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    sink.write(buffer, 0, read)
                    total += read
                    if (total - lastReported > 512 * 1024) {
                        lastReported = total
                        onProgress(if (layer.size > 0) total.toFloat() / layer.size else null)
                    }
                }
            }
        }
        val actual = "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != layer.digest) {
            into.delete()
            error("downloaded image does not match its digest")
        }
    }

    /**
     * Unpack under proot, so that the ownership and permissions inside the
     * rootfs come out right — we are not root, but the guest believes it is.
     * The tar is Android's own; only its view of the filesystem is faked.
     *
     * What this does NOT deliver, learned on a Seeker (2026-08): the image's
     * hard-link entries. The tar here is toybox, whose hard-link case is a
     * bare `link(2)`, and Android SELinux denies `link(2)`/`linkat(2)` to app
     * processes. `--link2symlink` is on this invocation and still cannot
     * save it: the rewrite runs on already-translated paths, and with no
     * `-r` here the symlinks it fabricates carry absolute *host* text that
     * dangles under the session proot's `-r`. On the device the entry for
     * `/usr/bin/perl` simply never appeared while tar exited 0, and the
     * failure surfaced days later as debconf's perl frontend exec-127,
     * ca-certificates' postinst dying, and apt exiting 100. The caller's
     * [heal] pass over [TarHardLinks.index] is what actually makes those
     * entries exist; this unpack is trusted for everything else.
     */
    private fun unpack(context: Context, blob: File, root: File) {
        val log = File(context.cacheDir, "unpack.log")
        val process = ProcessBuilder(
            proot(context).absolutePath, "-0", "--link2symlink",
            "/system/bin/tar", "-x", "-f", "-", "-C", root.absolutePath,
        )
            .redirectErrorStream(true)
            .redirectOutput(log)
            .also { it.environment()["PROOT_TMP_DIR"] = prootScratch(context).absolutePath }
            .start()

        GZIPInputStream(blob.inputStream().buffered()).use { source ->
            process.outputStream.use { source.copyTo(it) }
        }
        val exit = process.waitFor()
        if (exit != 0) error("unpacking failed (exit $exit): ${log.readText().take(400)}")
    }

    // --- hard links, made real -------------------------------------------------

    /**
     * Make one of the image's hard links exist as a relative symlink.
     *
     * Idempotent and cheap when the guest is healthy (two lstat calls), so it
     * doubles as the per-session self-heal. Three states it repairs, all seen
     * or derived from the Seeker incident (see [unpack]):
     *  - the path is simply absent (SELinux ate the `link(2)`; the device);
     *  - the path is a dangling or host-absolute symlink (`--link2symlink`
     *    debris, meaningless once the rootfs is mounted under `-r`);
     *  - the *target* itself became `--link2symlink` debris, in which case
     *    the real bytes are moved back under their Debian name first.
     */
    private fun heal(root: File, link: TarHardLink) {
        val target = File(root, link.target)
        restoreLinkedOriginal(root, target)
        val file = File(root, link.path)
        if (isGuestResolvable(file)) return
        if (!isGuestResolvable(target)) return // nothing to point at; leave the evidence
        file.delete()
        file.parentFile?.mkdirs()
        Os.symlink(TarHardLinks.relativeTarget(link.path, link.target), file.absolutePath)
        Log.i(TAG, "healed hard link ${link.path} -> ${link.target}")
    }

    /**
     * Whether [file] resolves *inside the guest*: a real file, or a symlink
     * whose text is relative and lands on something that exists. A symlink
     * with absolute text fails on purpose — at a hard-link path it can only
     * be `--link2symlink` debris naming this app's host data directory, and
     * `File.exists()` alone would bless it because the host can follow it.
     */
    private fun isGuestResolvable(file: File): Boolean {
        val stat = runCatching { Os.lstat(file.absolutePath) }.getOrNull() ?: return false
        if (!OsConstants.S_ISLNK(stat.st_mode)) return true
        val text = runCatching { Os.readlink(file.absolutePath) }.getOrNull() ?: return false
        return !text.startsWith("/") && File(file.parentFile, text).exists()
    }

    /**
     * Undo `--link2symlink` damage to a file the image meant to *keep*: the
     * rewrite renames the original to a hidden `.l2s.*` name and leaves the
     * Debian name as a symlink with absolute host text. Host-side that chain
     * still resolves, so the real bytes can be moved back where dpkg expects
     * them; the `.l2s.*` stepping-stone is deleted as the debris it is.
     */
    private fun restoreLinkedOriginal(root: File, file: File) {
        val stat = runCatching { Os.lstat(file.absolutePath) }.getOrNull() ?: return
        if (!OsConstants.S_ISLNK(stat.st_mode)) return
        val text = runCatching { Os.readlink(file.absolutePath) }.getOrNull() ?: return
        if (!text.startsWith("/")) return
        val rootPrefix = root.absolutePath + "/"
        val real = runCatching { file.canonicalFile }.getOrNull() ?: return
        if (!real.isFile || real.absolutePath == file.absolutePath) return
        if (!real.absolutePath.startsWith(rootPrefix)) return
        file.delete()
        if (real.renameTo(file)) Log.i(TAG, "restored ${file.name} from ${real.name}")
        val debris = File(text)
        if (debris.name.startsWith(".l2s.") && debris.absolutePath.startsWith(rootPrefix)) {
            debris.delete()
        }
    }

    /**
     * The per-session self-heal: every hard link the image declared, checked
     * and recreated when absent.
     *
     * Runs from [inside] rather than a one-time migration because the failure
     * it repairs is not one-time: any future image pull, backup restore or
     * partial delete can drop a link again, and the cost when healthy is a
     * small file read plus one lstat per entry (Debian slim has exactly one).
     * Installs made before the index existed — including the Seeker that was
     * hand-patched on-device — get the known-critical fallback: `perl`, the
     * link whose absence took apt down with it.
     */
    private fun healHardLinks(root: File) {
        val index = hardLinkIndex(root)
        if (index.isFile) {
            val links = runCatching { TarHardLinks.parse(index.readText()) }.getOrNull() ?: return
            for (link in links) runCatching { heal(root, link) }
            return
        }
        val perl = File(root, "usr/bin/perl")
        if (isGuestResolvable(perl)) return
        // debconf runs /usr/bin/perl; perl-base ships the real interpreter
        // beside it as perl5.<version>. Point one at the other, exactly the
        // repair that was applied by hand on the first bitten device.
        val real = File(root, "usr/bin").listFiles()
            ?.filter { it.name.startsWith("perl5.") && isGuestResolvable(it) }
            ?.minByOrNull { it.name } ?: return
        heal(root, TarHardLink("usr/bin/perl", "usr/bin/${real.name}"))
    }

    /** Rewrite the guest's resolvers if the device's have changed. */
    private fun refreshResolvConf(context: Context, root: File) {
        runCatching {
            val file = File(root, "etc/resolv.conf")
            val wanted = resolvConf(context)
            if (!file.isFile || file.readText() != wanted) file.writeText(wanted)
        }
    }

    /** The few things a container image leaves to whoever starts it. */
    private fun configure(context: Context, root: File) {
        File(root, "etc/resolv.conf").writeText(resolvConf(context))
        File(root, "etc/hostname").writeText("seeker\n")
        // apt in a proot has no reason to fsync every file, and it is slow on
        // a phone; this is the same tuning proot-distro applies.
        File(root, "etc/apt/apt.conf.d").mkdirs()
        File(root, "etc/apt/apt.conf.d/99seeker").writeText(
            """
            Acquire::Retries "3";
            DPkg::Use-Pty "0";
            Dir::Log::Terminal "";
            """.trimIndent() + "\n"
        )
        File(root, "root").mkdirs()
        File(root, "root/.bashrc").writeText(
            """
            # Seeker IDE — Debian userland. Edit freely; this file is yours.
            export PS1='\[\e[32m\]\w\[\e[0m\] \$ '
            alias ll='ls -alF'
            """.trimIndent() + "\n"
        )
    }

    /** The device's own DNS servers, with public resolvers appended after them. */
    private fun resolvConf(context: Context): String {
        val servers = runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val network = manager?.activeNetwork
            manager?.getLinkProperties(network)?.dnsServers
                ?.mapNotNull { it.hostAddress }
                .orEmpty()
        }.getOrDefault(emptyList())

        return GuestResolvers.conf(servers)
    }
}

/**
 * What goes in the guest's `resolv.conf`, from what the device reported.
 *
 * The publics used to be a fallback for an *empty* device list only, and the
 * rehearsal showed why that is not enough: the device's resolver refused the
 * guest's queries (Spettro's auth poll died on its DNS lookup) while the same
 * names resolved fine through a public server. Both resolvers the guest has —
 * glibc's and Go's — walk the `nameserver` list on failure, so appending
 * publics AFTER the device's own costs nothing on a healthy network (the
 * first entry answers, the rest are never tried) and turns "the carrier's
 * resolver dislikes proot" from a dead login into one slow lookup. Order
 * matters: device first, so names only the LAN's resolver knows still
 * resolve.
 *
 * A top-level object rather than a member of [DebianUserland], because that
 * backend is file-private and this rule is the one piece of it that is pure
 * enough to test on the host — see `GuestResolversTest`.
 */
internal object GuestResolvers {

    /**
     * How many device resolvers are kept: two, so at least one public entry
     * stays inside glibc's window — glibc honours only the first three
     * `nameserver` lines (MAXNS). A device that reports three or more
     * resolvers, all misbehaving the same way, would otherwise push every
     * public entry past line three and the fallback would exist only on
     * paper. Go's resolver reads the whole list either way.
     */
    const val DEVICE_KEPT = 2

    /**
     * Cloudflare's and Google's, in that order — the same pair the empty-list
     * fallback always wrote, now written unconditionally after the device's.
     */
    val PUBLIC = listOf("1.1.1.1", "8.8.8.8")

    /** The `nameserver` lines to write, in order. */
    fun list(device: List<String>): List<String> {
        val kept = device.distinct().take(DEVICE_KEPT)
        return kept + PUBLIC.filterNot { it in kept }
    }

    /**
     * The whole file: the nameserver lines, then `options use-vc`.
     *
     * use-vc forces DNS over TCP, and it is here because nothing gentler
     * worked. On the Seeker, the engine-spawned Spettro process hit
     * `write udp ...->...:53: operation not permitted` from Go's resolver in
     * multi-minute windows — same binary, same resolv.conf, same UID as a
     * terminal-spawned instance that resolved fine, and Python UDP probes to
     * every resolver passed from every spawn context while it was failing.
     * Not zygote seccomp (the terminal shares the filters), not the resolver
     * list (the block was UDP-wide). With use-vc the same open login went
     * 17/17 then 12/12 clean where UDP polls were dying, and removing the
     * line re-broke the same session twice — an A/B/A flip minutes apart.
     * Both resolvers honour it: Go switches to TCP, glibc still answers
     * `getent` (slower per lookup, which a phone's guest can afford; apt and
     * cargo do a handful of lookups per run, not thousands).
     */
    fun conf(device: List<String>): String =
        list(device).joinToString("\n", postfix = "\n") { "nameserver $it" } +
            "options use-vc\n"
}
