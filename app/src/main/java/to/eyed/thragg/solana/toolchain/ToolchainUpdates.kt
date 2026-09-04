package to.eyed.thragg.solana.toolchain

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/** What the last update check found, for the line on the toolchain page. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus

    /** Nothing newer, and every row is at the manifest's revision. */
    data class UpToDate(val released: String) : UpdateStatus

    /** Rows that are not at the manifest's revision, and the bytes to fetch them. */
    data class Available(val names: List<String>, val downloadBytes: Long, val released: String) : UpdateStatus

    data class Failed(val message: String) : UpdateStatus
}

/**
 * The Update button's other half: fetch [ToolchainManifest.REMOTE_URL], adopt
 * it if it is newer, and say which rows are behind.
 *
 * What "behind" means is decided by the install record, not by the fetch: a
 * row is behind when its recorded revision is not the manifest's, which is
 * also true after an *app* update shipped a newer asset. So the check is
 * useful even when the network answers nothing new, and the same rows are
 * what [ToolchainInstaller.start] then (re)installs — an outdated tarball's
 * directory is cleared and unpacked afresh, an outdated apt list is re-run,
 * and everything at the current revision is left alone.
 *
 * State is Compose state, like the installer's, so the page observes it.
 */
object ToolchainUpdates {

    private const val TAG = "thragg-toolchain"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var status: UpdateStatus by mutableStateOf(UpdateStatus.Idle)
        private set

    val isChecking: Boolean get() = status is UpdateStatus.Checking

    fun check(context: Context) {
        if (isChecking) return
        val app = context.applicationContext
        status = UpdateStatus.Checking
        scope.launch {
            status = runCatching {
                val text = fetch(ToolchainManifest.REMOTE_URL)
                val manifest = ToolchainManifest.adopt(app, text)
                Log.i(TAG, "update check: manifest in use is of ${manifest.released}")
                ToolchainInstaller.refresh(app)
                val behind = manifest.components.filterNot { SolanaToolchain.isInstalled(app, it) }
                if (behind.isEmpty()) {
                    UpdateStatus.UpToDate(manifest.released)
                } else {
                    UpdateStatus.Available(
                        names = behind.map { it.name },
                        downloadBytes = behind.sumOf { it.downloadBytes },
                        released = manifest.released,
                    )
                }
            }.getOrElse { error ->
                Log.w(TAG, "update check failed", error)
                UpdateStatus.Failed(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    /** Forget the last result — when the page leaves, or an install starts. */
    fun reset() {
        if (!isChecking) status = UpdateStatus.Idle
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 15_000
            // GitHub's raw endpoint caches for minutes; a check is rare and
            // wants the file as it is now.
            setRequestProperty("Cache-Control", "no-cache")
        }
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) error("$url answered HTTP $code")
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}
