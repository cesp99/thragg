package to.eyed.thragg.ui.terminal

import java.io.File

/**
 * A path the terminal printed, resolved to something the workspace can act
 * on — Zed's `PathLikeTarget` after `resolve_open_target`
 * (terminal_view/src/terminal_path_like_target.rs): the path made absolute
 * against the shell's working directory, plus the position it carried.
 *
 * [absolutePath] is a *host* path. Inside the Debian userland the projects
 * directory is bound at `/projects`, so a guest path under it is mapped back
 * here; any other absolute guest path (`/usr/include/stdio.h`) stays as it is
 * and will be reported as outside the project, which it is.
 */
data class TerminalPathTarget(
    val absolutePath: String,
    /** 1-based, as printed; null when the text had no position. */
    val row: Int?,
    val column: Int?,
)

/** Where the userland's `proot -b <projects>:/projects` puts the projects. */
private const val GUEST_PROJECTS = "/projects"

/**
 * Make [link] absolute against [cwd] — the shell's working directory as read
 * from `/proc`, or the directory the session started in — and normalise
 * `..` and `.` away, so `../Cargo.toml` from `src/` names the file it means.
 *
 * [hostProjectsDir] is the Android directory the userland shows as
 * `/projects`; null when there is no userland.
 */
fun resolveTerminalPath(
    link: TerminalLink.PathLike,
    cwd: String,
    hostProjectsDir: String?,
): TerminalPathTarget {
    val raw = link.path
    val absolute = when {
        raw.startsWith("/") -> {
            if (hostProjectsDir != null && (raw == GUEST_PROJECTS || raw.startsWith("$GUEST_PROJECTS/"))) {
                hostProjectsDir + raw.removePrefix(GUEST_PROJECTS)
            } else {
                raw
            }
        }
        raw == "~" || raw.startsWith("~/") -> raw // no home to expand against; reported as outside
        else -> "$cwd/$raw"
    }
    return TerminalPathTarget(File(absolute).normalize().path, link.row, link.column)
}

/**
 * The project-relative name of [absolutePath], or null when it is not inside
 * [projectRoot] — the engine works on project paths, so a file elsewhere is
 * one the editor cannot open.
 */
fun projectRelativePath(absolutePath: String, projectRoot: String): String? {
    val root = projectRoot.trimEnd('/')
    return when {
        absolutePath == root -> null
        absolutePath.startsWith("$root/") -> absolutePath.removePrefix("$root/")
        else -> null
    }
}
