package to.eyed.seeker.code.terminal

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import to.eyed.seeker.code.solana.toolchain.SolanaToolchain
import java.io.File

/**
 * Where a terminal session runs and what it can see.
 *
 * Android forbids executing anything from writable storage, so bundled
 * programs ride in the APK as `lib<name>_exec.so` and are executed from
 * `nativeLibraryDir`. Two consequences shape everything here, both measured on
 * device (agent-docs/archive/research/android-exec-policy.md):
 *
 * 1. That directory's path contains a per-install random hash and changes on
 *    every app update, so it can never be baked into a program or a script.
 *    [installBinDir] therefore rebuilds a stable `$PREFIX/bin` of symlinks on
 *    every launch — symlinks into `nativeLibraryDir` *are* executable, and a
 *    child sees the plain name as `argv[0]`, which is what `PATH` lookup,
 *    busybox-style dispatch and `GIT_EXEC_PATH` all need.
 * 2. Nothing the user or a package downloads can ever be executed. Bundled
 *    interpreters running data files are the whole story for extensibility.
 *
 * This describes the *host* side. When a Linux userland is installed the
 * terminal runs inside that instead (see [Userland]), and this is the
 * fallback: `/system/bin/sh` is mksh, and toybox puts ~210 commands on `PATH`
 * for free, so the terminal is useful with nothing installed at all.
 */
object ShellEnvironment {

    private const val TAG = "seeker-shell"

    /** Bundled executables are packaged under this pattern. */
    private val EXEC_LIB = Regex("""^lib(.+)_exec\.so$""")

    /**
     * Preferred host shell if some build ever ships one. Nothing bundles a
     * shell today — the userland brings its own — but the lookup costs
     * nothing and keeps the mechanism honest.
     */
    private const val BUNDLED_SHELL = "bash"

    /** Always present on Android, and a real interactive shell (mksh). */
    const val SYSTEM_SHELL = "/system/bin/sh"

    /** `$PREFIX` — the stable root the shell environment is described in. */
    fun prefix(context: Context): File = File(context.filesDir, "usr")

    fun binDir(context: Context): File = File(prefix(context), "bin")

    fun homeDir(context: Context): File = File(context.filesDir, "home")

    fun tmpDir(context: Context): File = File(prefix(context), "tmp")

    /**
     * Rebuild `$PREFIX/bin` so every bundled executable is reachable under its
     * real name, and return it.
     *
     * Rebuilt rather than patched because `nativeLibraryDir` moves on update,
     * which would leave dangling links. Cheap: a handful of `symlink(2)` calls.
     */
    fun installBinDir(context: Context): File {
        val bin = binDir(context)
        bin.mkdirs()
        bin.listFiles()?.forEach { it.delete() }

        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        for (file in nativeDir.listFiles().orEmpty()) {
            val name = EXEC_LIB.matchEntire(file.name)?.groupValues?.get(1) ?: continue
            link(File(bin, name), file.absolutePath)
        }
        // `sh` must resolve even with nothing bundled, so scripts and
        // `system()` calls inside the sandbox behave.
        if (!File(bin, "sh").exists()) link(File(bin, "sh"), SYSTEM_SHELL)
        return bin
    }

    private fun link(at: File, target: String) {
        try {
            Os.symlink(target, at.absolutePath)
        } catch (e: ErrnoException) {
            Log.w(TAG, "could not link ${at.name} -> $target", e)
        }
    }

    /**
     * The shell to run on the *host* side: the bundled one if this build has
     * it, else the device's. Returned as its `$PREFIX/bin` path so that what
     * the user sees in `$SHELL` is a path that still exists tomorrow.
     */
    fun shellPath(context: Context): String {
        val bundled = File(binDir(context), BUNDLED_SHELL)
        return if (bundled.exists()) bundled.absolutePath else SYSTEM_SHELL
    }

    /**
     * What a terminal session should actually run in [cwd].
     *
     * A Linux userland takes precedence when one is installed — that is the
     * whole point of it — and the host shell is the fallback, so the terminal
     * works on a fresh install, in the `play` flavour, and while Debian is
     * still downloading.
     */
    fun commandFor(context: Context, cwd: String): ShellCommand {
        Userland.backend.shellCommand(context, cwd)?.let { command ->
            // `shellCommand` takes no extra environment, so the toolchain's
            // entries are appended to the one it built. Later entries win —
            // that is the pty shim's rule and the backend's own — so this is
            // what leads PATH rather than replacing it.
            return command.copy(
                environment = command.environment + guestToolchainEnvironment(context, cwd)
            )
        }

        installBinDir(context)
        val shell = shellPath(context)
        return ShellCommand(
            executable = shell,
            argv = listOf(File(shell).name),
            environment = buildEnvironment(context, cwd).toList(),
        )
    }

    /**
     * What a *task* runs: [line] handed to a login shell's `-c` in [cwd],
     * with [extraEnvironment] appended to the session's own so the task's
     * entries win — Zed spawns a task the same way, a shell with the
     * command line as its argument (util/src/shell_builder.rs), so the
     * user's profile is sourced and `PATH` is what their terminal has.
     * Inside the userland when one is installed, else the host shell; the
     * session ends when the command does, which is the exit bar's cue.
     */
    fun taskCommand(
        context: Context,
        cwd: String,
        line: String,
        extraEnvironment: List<String>,
    ): ShellCommand {
        Userland.backend
            .execCommand(
                context,
                cwd,
                listOf("/bin/bash", "--login", "-c", line),
                // Ahead of the caller's, so a task that sets its own PATH still
                // wins: the backend appends both and the last entry stands.
                guestToolchainEnvironment(context, cwd) + extraEnvironment,
            )
            ?.let { return it }

        installBinDir(context)
        val shell = shellPath(context)
        return ShellCommand(
            executable = shell,
            argv = listOf(File(shell).name, "-c", line),
            environment = buildEnvironment(context, cwd).toList() + extraEnvironment,
        )
    }

    /**
     * What the active toolchain contributes to a *guest* process, as
     * `NAME=value` strings appended to the session's own.
     *
     * This is the Kotlin-side replacement for the engine's `ToolchainEnv`
     * (core/crates/engine/src/toolchain.rs), which is deleted with the rest of
     * the toolchain picker in P10. Two behaviours had to survive that deletion
     * and both are here, because losing either is a silent failure rather than
     * a visible one (docs/UI.md, P5 and "removed"):
     *
     *  * **`PATH` is led, not replaced.** rust-analyzer, `cargo`, `anchor` and
     *    every task resolve through it, and `cargo-build-sbf` shells out to
     *    `rustup` — which lives in `/root/.cargo/bin` and nowhere Debian would
     *    look. See [SolanaToolchain.GUEST_PATH_ENTRIES] for what each entry is
     *    for.
     *  * **`VIRTUAL_ENV` names a Python environment's root.** That variable is
     *    what every Python tool reads to know it is in a venv; exporting the
     *    interpreter on `PATH` without it gets half the behaviour. It is set
     *    only when the project actually has one — a Rust toolchain contributes
     *    a `PATH` entry and nothing else, exactly as toolchain.rs had it.
     *
     * The toolchain entries go out whether or not anything is installed: a
     * `PATH` entry that does not exist costs one failed `stat` per lookup, and
     * the alternative is a disk read on the path that starts every shell.
     */
    fun guestToolchainEnvironment(context: Context, cwd: String?): List<String> {
        val base = SolanaToolchain.guestEnvironment()
        val venv = guestVirtualEnv(context, cwd) ?: return base
        return base.map { entry ->
            if (entry.startsWith("PATH=")) {
                "PATH=$venv/bin:${entry.removePrefix("PATH=")}"
            } else {
                entry
            }
        } + "VIRTUAL_ENV=$venv"
    }

    /**
     * The guest path of the project's virtualenv, or null when it has none.
     *
     * `bin/activate` rather than `bin/python` is the test, because a venv made
     * by `python -m venv` links its interpreter under whichever name it was
     * created with (`python3`, `python3.13`) and the activate script is the one
     * file every implementation writes.
     */
    private fun guestVirtualEnv(context: Context, cwd: String?): String? {
        val root = cwd?.let(::File) ?: return null
        val venv = listOf(".venv", "venv")
            .map { File(root, it) }
            .firstOrNull { File(it, "bin/activate").isFile }
            ?: return null
        return guestPathOf(context, venv)
    }

    /**
     * Where an Android path shows up inside the guest.
     *
     * The same mapping DebianUserland does for a session's working directory,
     * restated here because that one is private to the flavour and this file
     * compiles into both. Only the projects directory is bound in, so anything
     * outside it — or anything that would have to climb out with `..` — has no
     * guest path at all and is answered with null rather than a path that does
     * not exist there.
     */
    private fun guestPathOf(context: Context, file: File): String? {
        val projects = File(context.filesDir, "projects")
        val relative = file.relativeToOrNull(projects)?.path ?: return null
        if (relative.isEmpty() || relative.startsWith("..")) return null
        return "/projects/$relative"
    }

    /**
     * Environment for a session, as `NAME=value` strings.
     *
     * The pty shim calls `clearenv()` before exec, so this is the *whole*
     * environment — nothing of the app's own leaks into the shell.
     */
    fun buildEnvironment(context: Context, cwd: String): Array<String> {
        val home = homeDir(context).also { it.mkdirs() }
        val tmp = tmpDir(context).also { it.mkdirs() }
        val bin = binDir(context)
        val prefix = prefix(context)

        return arrayOf(
            "HOME=${home.absolutePath}",
            "PREFIX=${prefix.absolutePath}",
            // Ours first, then Android's: /system/bin is toybox, awk and curl.
            "PATH=${bin.absolutePath}:/system/bin:/system/xbin",
            "TMPDIR=${tmp.absolutePath}",
            "PWD=$cwd",
            "SHELL=${shellPath(context)}",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=en_US.UTF-8",
            // Only matters once a bundled program links against a bundled
            // library, but harmless now and easy to forget later.
            "LD_LIBRARY_PATH=${context.applicationInfo.nativeLibraryDir}",
        )
    }
}
