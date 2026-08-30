package to.eyed.seeker.code.ui.workspace

import androidx.compose.runtime.mutableStateListOf
import to.eyed.seeker.code.ui.editor.LspServer
import to.eyed.seeker.code.ui.editor.LspServerState

/**
 * Where a running job lives, so tapping the status bar can go there — Zed's
 * activity indicator dispatches an action per branch (activity_indicator.rs:
 * 410-415, 558-563, 620-626), which is the same idea with a mouse.
 */
enum class ActivityTarget {
    /** The worktree scan: the tree is what is filling up. */
    ProjectPanel,
    ProjectSearch,
    GitPanel,

    /** A task, which runs in the terminal dock. */
    Terminal,

    /** A language server's log, which is where its progress is explained. */
    LanguageServerLogs,
}

/**
 * One piece of background work, while it lasts.
 *
 * [key] identifies the job: beginning the same key twice replaces the message
 * rather than counting the job twice, which is what a poll that re-reports
 * "scanning" every 200ms would otherwise do.
 */
data class Activity(
    val key: String,
    val message: String,
    val target: ActivityTarget? = null,
)

/**
 * What the workspace is doing in the background — Zed's `activity_indicator`
 * crate, whose whole job is to put one sentence about long-running work in the
 * status bar (activity_indicator.rs:397-740).
 *
 * Newest first, and the newest is the one the bar prints: it is the one the
 * user just asked for. The rest are a count beside it, so a fetch started
 * during a worktree scan does not hide the scan, it just waits its turn.
 *
 * Global for the reason [Notifications] is: the jobs start in panels, in
 * pollers and in the terminal, none of which can see the status bar.
 */
open class ActivityLog {
    private val items = mutableStateListOf<Activity>()

    val all: List<Activity> get() = items

    /** Start (or re-word) a job. Idempotent per [key]. */
    fun begin(key: String, message: String, target: ActivityTarget? = null) {
        val existing = items.indexOfFirst { it.key == key }
        val activity = Activity(key, message, target)
        if (existing >= 0) {
            // Re-worded in place: a progress percentage that moved must not
            // shuffle the job to the front of the queue on every tick.
            items[existing] = activity
        } else {
            items.add(0, activity)
        }
    }

    fun end(key: String) {
        items.removeAll { it.key == key }
    }

    /** Everything this project had running — what closing a project drops. */
    fun clear() {
        items.clear()
    }

    /**
     * Run [key] for as long as [body] does, however it ends. The only safe way
     * to start one from a coroutine that a recomposition or a cancelled
     * search can kill halfway.
     */
    suspend fun <T> during(
        key: String,
        message: String,
        target: ActivityTarget? = null,
        body: suspend () -> T,
    ): T {
        begin(key, message, target)
        return try {
            body()
        } finally {
            end(key)
        }
    }
}

/** The one log the status bar draws. */
object Activities : ActivityLog()

/** One line of the status bar: the sentence, what else is waiting, where to go. */
data class ActivityLine(
    val message: String,
    /** How many other jobs are running — printed as "+2". */
    val others: Int,
    val target: ActivityTarget?,
)

/**
 * What the indicator says right now, or null when nothing is running.
 *
 * Language-server work is folded in here rather than kept as a second sentence
 * beside it, because in Zed it *is* the activity indicator: LSP progress,
 * downloads and formatter failures are branches of the same element
 * (activity_indicator.rs:440-500). Explicit jobs outrank it — a search or a
 * fetch is something the user just pressed, and an indexer that has been
 * running for a minute is not news.
 *
 * Pure, so the priority is checkable on the host.
 */
fun activityLine(activities: List<Activity>, servers: List<LspServer>): ActivityLine? {
    val lsp = lspActivity(servers)
    val ordered = activities + listOfNotNull(lsp)
    val first = ordered.firstOrNull() ?: return null
    return ActivityLine(
        message = first.message,
        others = ordered.size - 1,
        target = first.target,
    )
}

/**
 * The language servers' own line: progress ("indexing (45%)") outranks the
 * spawn-and-initialize phase, which is what shows the moment a folder opens
 * and its tooling comes up. A server that could not start reports no progress
 * — it has its own note in the bar — so nothing here speaks for it.
 */
internal fun lspActivity(servers: List<LspServer>): Activity? {
    val busy = servers.firstOrNull { it.progress != null }
    if (busy != null) {
        return Activity(
            key = "lsp:${busy.name}",
            message = "${busy.name}: ${busy.progress}",
            target = ActivityTarget.LanguageServerLogs,
        )
    }
    val starting = servers.firstOrNull { it.state == LspServerState.Starting } ?: return null
    return Activity(
        key = "lsp:${starting.name}",
        message = "${starting.name} is starting…",
        target = ActivityTarget.LanguageServerLogs,
    )
}
