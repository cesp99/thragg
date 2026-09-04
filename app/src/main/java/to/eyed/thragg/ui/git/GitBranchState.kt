package to.eyed.thragg.ui.git

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.json.JSONObject
import to.eyed.thragg.core.CoreBridge
import to.eyed.thragg.core.GitBranch
import to.eyed.thragg.core.ProjectSession
import to.eyed.thragg.core.ResumedEffect
import to.eyed.thragg.core.pollVersion

/** How often the shared status counter is re-read. Cheap; see [GitBranch]. */
private const val POLL_MS = 500L

/**
 * The branch the project is on, for the title bar.
 *
 * It reads the *same* status run the project panel's colours and the git panel
 * both use — one `git status` per project, one counter to poll — through
 * [CoreBridge.gitBranchInfo], which hands back the cached branch record alone:
 * name, ahead/behind drift, upstream. The full
 * [to.eyed.thragg.core.GitSession.state] read serializes and parses
 * every changed file, which a title bar has no use for.
 *
 * Null when nothing is known — no repository, or no status run yet — and the
 * title bar shows nothing rather than guessing. A detached HEAD arrives as a
 * branch whose [GitBranch.name] is null, so the title bar still draws its
 * clickable "no branch" chip rather than losing the way into the git panel.
 */
@Composable
fun rememberGitBranch(project: ProjectSession?): GitBranch? {
    var branch by remember(project) { mutableStateOf<GitBranch?>(null) }
    ResumedEffect(project) {
        if (project == null) return@ResumedEffect
        pollVersion(
            intervalMs = POLL_MS,
            version = { project.gitStatusVersion },
            read = { _ ->
                CoreBridge.gitBranchInfo(project.id)?.let { GitBranch.parse(JSONObject(it)) }
            },
            apply = { branch = it },
        )
    }
    return branch
}
