package to.eyed.seeker.code.ui.git

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.ui.workspace.Notifications

/**
 * One git mutation at a time, per project — the single-flight the panel's
 * `perform` and the branch picker's checkout, create and delete all go
 * through.
 *
 * Owned here rather than in either composition for the reason [GitClone]'s
 * state is (terminal/GitClone.kt): the panel is a composable that gets
 * *removed* — Escape, a compact screen opening a file, a fold crossing the
 * width threshold — while the JNI git call under a running pull is blocking,
 * non-cancellable, and finishes whether or not the panel lives to see it.
 * With the flag and the outcome kept here, a reopened panel re-attaches to
 * the operation instead of starting a second one against a worktree the
 * first still owns; and the branch picker consulting the same flag is what
 * makes "one git command at a time" true *across* surfaces — Zed serializes
 * every mutation through one per-repo job queue the same way
 * (git_store.rs:6447-6480).
 *
 * Keyed by project id, like [CommitDrafts]. The map and the single-flight
 * check are main-thread only — every caller is a UI event, or a callback
 * this object itself posts back there.
 */
internal object GitOps {

    /**
     * One project's in-flight state, observable: every field the surfaces
     * draw from is snapshot state, so a composition that reads it recomposes
     * when a command starts or lands — including a composition that was not
     * there when it started.
     */
    class Ops internal constructor() {
        /** A mutation is running. Every surface's buttons disable on it. */
        var busy by mutableStateOf(false)
            internal set

        /**
         * The remote command in flight — Zed's `pending_remote_operation`
         * (git_panel.rs:442-447). What turns the split button's spinner;
         * [busy] alone cannot, because it is also every stage and commit.
         */
        var pendingRemote by mutableStateOf(false)

        /**
         * What the last command said when it failed, and the strip the panel
         * shows it in — writable by the panel directly, because refusals that
         * never reach git ("Write a commit message first") belong in the same
         * strip.
         */
        var error by mutableStateOf<String?>(null)

        /**
         * What the last remote command said when it *worked* — Zed's success
         * `StatusToast` (git_panel.rs:5278-5334), worded by
         * [formatRemoteOutput]. Cleared the moment any next command starts,
         * as a toast would have timed out.
         */
        var notice by mutableStateOf<String?>(null)

        /** The running mutation, for whoever needs to know more than [busy]. */
        internal var job: Job? = null
    }

    private val states = mutableMapOf<Long, Ops>()

    /**
     * Its own scope, never a composition's: a mutation must run — and its
     * outcome must be recorded — through any panel teardown, or a pull
     * dismissed mid-flight leaves a busy flag nobody will ever clear.
     * Main-dispatched, because [run]'s callback writes composition state.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The observable state for one project, made on first ask. */
    fun of(project: Long): Ops = states.getOrPut(project) { Ops() }

    /**
     * Run one mutation: [action] off the main thread, [onDone] back on it
     * with whatever the command answered — null for success. Returns false,
     * running nothing, while another mutation for the same project is still
     * in flight; the caller says so or stays silent, but never queues.
     *
     * The busy flag is already down when [onDone] runs: one of those
     * callbacks runs the next command — saving an identity commits straight
     * afterwards — and with the flag still up that command would refuse
     * itself over a command that had finished.
     */
    fun run(
        project: Long,
        action: suspend () -> String?,
        onDone: suspend (String?) -> Unit = {},
    ): Boolean {
        val ops = of(project)
        if (ops.busy) return false
        ops.busy = true
        ops.job = scope.launch {
            val failure = withContext(Dispatchers.IO) { action() }
            ops.busy = false
            ops.job = null
            // Every git failure, said where it can be seen — Zed raises a
            // notification for a failed remote command whether or not its
            // panel is up (git_ui/src/remote_output.rs, notifications.rs:
            // 36-73), and this is the one funnel every mutation goes through.
            // The panel's own error strip still carries the detail for
            // whoever has the panel open; the toast is for whoever does not,
            // which used to be a command that vanished without a word.
            if (failure != null) {
                Notifications.error(failure, key = "git:$project")
            }
            onDone(failure)
        }
        return true
    }
}
