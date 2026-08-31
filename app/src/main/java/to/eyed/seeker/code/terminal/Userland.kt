package to.eyed.seeker.code.terminal

import android.content.Context

/**
 * The Linux userland a terminal session can run inside.
 *
 * Android will not execute a program that arrived after installation, which is
 * why a package manager is impossible on a modern target SDK. This app targets
 * SDK 28, where that restriction does not apply, and runs a real Debian under
 * proot: `apt install` works, and we maintain none of it. See
 * docs/BUILDING.md, and agent-docs/DECISIONS.md for why.
 *
 * This interface used to be a build-flavour seam — a Play-compatible edition
 * compiled a `NoUserland` in place of [DebianUserland] and fell back to
 * Android's own shell. That edition is gone, and the seam is kept anyway,
 * because it is what keeps everything above it honest: the terminal asks for a
 * command to run and gets one, whether that is `proot … /bin/bash` or plain
 * `/system/bin/sh`, and the not-installed and unsupported states below are
 * still reachable — a rootfs is a download, and a download can be absent.
 */
sealed interface UserlandState {
    /**
     * This build has no userland support. Unreachable from the one backend
     * that ships today; kept because [UserlandBackend.isSupported] is part of
     * the seam and every caller already branches on it.
     */
    data object Unsupported : UserlandState

    /** Supported, but the rootfs has not been downloaded yet. */
    data object NotInstalled : UserlandState

    /** [fraction] is null while the step's size is unknown. */
    data class Installing(val step: String, val fraction: Float?) : UserlandState

    data object Ready : UserlandState

    data class Failed(val message: String) : UserlandState
}

/** What the terminal needs to start a session. */
data class ShellCommand(
    /** Absolute path of the executable to run. */
    val executable: String,
    /** Full argv, including argv[0]. */
    val argv: List<String>,
    /** Complete environment as `NAME=value`; the pty shim clears everything else. */
    val environment: List<String>,
)

interface UserlandBackend {
    /** False in builds that cannot run a userland, so the UI can stay quiet. */
    val isSupported: Boolean

    /** What to call it in the UI — "Debian", say. */
    val displayName: String

    /** Rough download size, for the install prompt. Null when unsupported. */
    val downloadDescription: String?

    fun state(context: Context): UserlandState

    /**
     * A session running inside the userland, with [projectDir] visible, or
     * null when there is nothing installed and the host shell should be used.
     */
    fun shellCommand(context: Context, projectDir: String): ShellCommand?

    /**
     * A one-shot, non-interactive command *inside* the userland — `git clone`,
     * `apt-get install`, a formatter — or null when there is no userland to run
     * it in, exactly as with [shellCommand].
     *
     * This is not [shellCommand] with a different program: no login shell, no
     * pty, and [argv] is the program itself, so a caller can pipe its output
     * and read its exit status. [hostWorkingDir] is an *Android* path; the
     * backend maps it to wherever it appears inside the guest, and falls back
     * to the guest's home when it maps to nothing (or is null).
     * [extraEnvironment] is appended to the session environment, later entries
     * winning, which is how `GIT_TERMINAL_PROMPT=0` gets in.
     *
     * Callers must treat null as "this build cannot do it" and keep the
     * feature out of the UI rather than showing it disabled.
     */
    fun execCommand(
        context: Context,
        hostWorkingDir: String?,
        argv: List<String>,
        extraEnvironment: List<String> = emptyList(),
    ): ShellCommand?

    /**
     * [execCommand], through a sandbox that leaves `hard_link(2)` alone.
     *
     * proot's `--link2symlink` rewrites a hard link into a symlink, which is
     * the only reason `dpkg` — and therefore `apt` — can unpack at all, so it
     * is on by default for every command. For `cargo install` it is a trap
     * that looks like a success: cargo builds into a scratch directory, hard-
     * links the finished binary into place, then deletes the scratch, and
     * under the rewrite what is left is a symlink to a directory that no
     * longer exists. cargo prints "Installed package"; the binary is gone, and
     * the *next* one fails with `Operation not permitted (os error 1)`
     * (docs/SOLANA.md, "Living with proot" — measured, twice).
     *
     * So the rule is a rule and not a preference: `--link2symlink` belongs to
     * apt and to nothing else. This is the invocation everything else uses.
     * The default implementation is the ordinary one, because a backend with
     * no proot has no flag to drop.
     */
    fun execCommandRealLinks(
        context: Context,
        hostWorkingDir: String?,
        argv: List<String>,
        extraEnvironment: List<String> = emptyList(),
    ): ShellCommand? = execCommand(context, hostWorkingDir, argv, extraEnvironment)

    /**
     * Download and unpack the rootfs. Blocking; call it off the main thread.
     *
     * [isActive] is polled during the long phases so a cancelled install stops
     * promptly instead of finishing in the background — coroutine cancellation
     * alone would not interrupt a blocking socket read. Progress arrives as a
     * human-readable step and an optional fraction.
     */
    fun install(
        context: Context,
        isActive: () -> Boolean,
        onProgress: (String, Float?) -> Unit,
    ): Result<Unit>

    /** Delete the rootfs, freeing its disk. */
    fun remove(context: Context)
}

/** The one backend: DebianUserland.kt, beside this file. */
object Userland {
    val backend: UserlandBackend by lazy { createUserlandBackend() }
}
