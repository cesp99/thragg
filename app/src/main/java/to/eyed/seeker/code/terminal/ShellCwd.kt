package to.eyed.seeker.code.terminal

import java.io.File

/**
 * Where a shell is *now*, from `/proc` — what Zed reads through its pty's
 * `foreground_process_info` (terminal/src/pty_info.rs) so a relative path in
 * the output resolves against the directory the user has `cd`ed to.
 *
 * Android's `/proc` is mounted `hidepid=2`, but the app's own processes are
 * visible to it, and `/proc/<pid>/cwd` is a symlink the kernel resolves on
 * read. Two things can still make it fail — a pid that has already exited,
 * and a proot session whose *shell* is a child of the pid the session holds
 * — and both come back as null (or the child's answer), never a throw.
 */
fun shellCurrentDirectory(pid: Int): String? {
    if (pid <= 0) return null
    // The userland's session pid is proot's; the shell it traces is its
    // child, and it is the shell's directory the user means. proot keeps the
    // host path as the traced process's real cwd, so the kernel's answer is
    // an Android path — exactly what the editor needs.
    val shell = childOf(pid) ?: pid
    return cwdOf(shell) ?: cwdOf(pid)
}

private fun cwdOf(pid: Int): String? =
    runCatching { File("/proc/$pid/cwd").canonicalPath }
        .getOrNull()
        // A dead pid resolves to the link's own name rather than failing.
        ?.takeIf { it != "/proc/$pid/cwd" && !it.startsWith("/proc/") }

/**
 * The first live child of [pid], from `/proc/<pid>/task/<pid>/children`
 * (needs `CONFIG_PROC_CHILDREN`, which Android kernels set) — one read, no
 * directory scan. Null when there is none, or the kernel does not offer the
 * file.
 */
private fun childOf(pid: Int): Int? =
    runCatching { File("/proc/$pid/task/$pid/children").readText() }
        .getOrNull()
        ?.trim()
        ?.split(' ')
        ?.firstOrNull()
        ?.toIntOrNull()
