package to.eyed.seeker.code.terminal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the userland install, outside the composition.
 *
 * It has to live here rather than in a `rememberCoroutineScope`: hiding the
 * terminal dock removes the composable, which would cancel the scope and leave
 * a 30 MB download half-finished with no way to resume or clean up. An install
 * is a piece of work the *app* is doing, not a piece of work a panel is doing.
 *
 * The state is ordinary Compose state, so the UI still observes it directly.
 */
object UserlandInstaller {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Null until something has looked; then whatever the backend last said. */
    var state by mutableStateOf<UserlandState?>(null)
        private set

    val isInstalling: Boolean get() = state is UserlandState.Installing

    /** Read the backend's own view, unless an install is in flight. */
    fun refresh(context: Context) {
        if (isInstalling) return
        state = Userland.backend.state(context.applicationContext)
    }

    /**
     * Install the userland, then call [onInstalled] **on the main thread**.
     *
     * That guarantee is the whole signature: the caller re-enters the shell so
     * the session lands in Debian rather than the fallback it started in, and
     * constructing a `TerminalSession` binds a `Handler` to the calling
     * thread's looper — on this scope's IO thread that is a crash, and it
     * fired the moment a fresh emulator ran the install from the banner.
     */
    fun install(context: Context, onInstalled: () -> Unit) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        state = UserlandState.Installing("Starting", null)
        job = scope.launch {
            val result = Userland.backend.install(
                app,
                isActive = { job?.isActive != false },
                onProgress = { step, fraction ->
                    state = UserlandState.Installing(step, fraction)
                },
            )
            job = null
            result.fold(
                onSuccess = {
                    state = UserlandState.Ready
                    withContext(Dispatchers.Main) { onInstalled() }
                },
                onFailure = { error ->
                    state = if (error is InstallCancelledMarker) {
                        UserlandState.NotInstalled
                    } else {
                        UserlandState.Failed(error.message ?: error.javaClass.simpleName)
                    }
                },
            )
        }
    }

    /**
     * Stop an install in progress. The download loop notices within a chunk and
     * the failure path deletes the partial rootfs.
     */
    fun cancel() {
        job?.cancel()
        job = null
        state = UserlandState.NotInstalled
    }
}

/**
 * Marks a cancellation rather than a failure, so the UI offers "Install" again
 * instead of reporting an error the user caused deliberately. DebianUserland
 * throws its own subclass of this.
 */
open class InstallCancelledMarker(message: String) : Exception(message)
