package to.eyed.seeker.code.ui.git

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Commit author avatars, fetched from GitHub's email→avatar CDN — the lookup
 * Zed's GitHub provider does per author (github.rs:75-82), done once per
 * author here and remembered.
 *
 * The caller gates on the remote: a repository whose origin is not github.com
 * never asks (Zed's `host_supports_avatars`, github.rs:178-182), and the
 * sidebar draws its initials disc instead.
 *
 * Two caches, deliberately layered:
 *  - **disk** — the app's cache directory, keyed by the normalized author
 *    identity ([avatarCacheKey]), so one author is one fetch across commits
 *    *and* sessions, and the OS may evict it whenever it likes;
 *  - **memory** — a small LRU of decoded bitmaps on top, so scrolling between
 *    commits by the same few people never touches the filesystem again.
 *
 * The disk half is also trimmed by us, not just the OS: once per process, the
 * first load drops the oldest files until the directory fits
 * [CommitAvatars.CACHE_BUDGET_BYTES] — an avatar is a few KB, but a long
 * history's authors add up and nothing else ever deletes them.
 *
 * A failed fetch is remembered for the session only — offline is not forever,
 * but it is for the next few minutes — and never written to disk, so a
 * rate-limited answer cannot become a permanently blank avatar.
 *
 * A module-level object, like [to.eyed.seeker.code.terminal.GitClone]: the
 * cache outlives any one pane.
 */
object CommitAvatars {
    /** How much the avatar directory may hold before the prune trims it. */
    private const val CACHE_BUDGET_BYTES = 5L * 1024 * 1024

    private val memory = LruCache<String, Bitmap>(32)
    private val missing = HashSet<String>()

    /**
     * One fetch per author at a time: selecting two commits by the same
     * author in quick succession must not start two downloads racing each
     * other to the same cache file — the latecomer waits on the first
     * caller's answer instead.
     */
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Bitmap?>>()

    /** The prune runs once per process, on the first load. */
    private val pruned = AtomicBoolean(false)

    /**
     * The avatar for [email], or null when there is none to be had — no
     * email, a bot, offline, or GitHub does not know the address.
     *
     * **Blocking** — it may use the network, or wait on another thread's
     * fetch of the same author. Call it from
     * [kotlinx.coroutines.Dispatchers.IO].
     */
    fun load(context: Context, email: String): Bitmap? {
        val url = githubAvatarUrl(email) ?: return null
        val key = avatarCacheKey(email)
        synchronized(this) {
            memory.get(key)?.let { return it }
            if (key in missing) return null
        }
        val dir = File(context.cacheDir, "git-avatars")
        if (pruned.compareAndSet(false, true)) prune(dir)
        // Single-flight: whoever installs the future does the work; everyone
        // else joins it.
        val flight = CompletableFuture<Bitmap?>()
        inFlight.putIfAbsent(key, flight)?.let { winner ->
            return try {
                winner.get()
            } catch (_: Exception) {
                null
            }
        }
        try {
            val file = File(dir, key)
            val bitmap = decode(file) ?: fetch(url, file)
            synchronized(this) {
                if (bitmap != null) memory.put(key, bitmap) else missing.add(key)
            }
            flight.complete(bitmap)
            return bitmap
        } finally {
            // Belt and braces: a throw on the way through must not leave the
            // waiters hanging on a future nobody will complete. A second
            // complete() is a no-op.
            flight.complete(null)
            inFlight.remove(key, flight)
        }
    }

    private fun decode(file: File): Bitmap? =
        if (file.isFile) BitmapFactory.decodeFile(file.path) else null

    /** Download to [file], then decode — the file *is* the disk cache. */
    private fun fetch(url: String, file: File): Bitmap? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                val bytes = connection.inputStream.use { it.readBytes() }
                file.parentFile?.mkdirs()
                // Write-then-rename, so a fetch killed halfway never leaves a
                // truncated file that decodes to garbage on every next launch
                // — under a name of this download's own, so no two writers
                // can ever interleave into one temp file.
                val partial = File.createTempFile("fetch", ".part", file.parentFile)
                partial.writeBytes(bytes)
                if (!partial.renameTo(file)) partial.delete()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    /** Trim the disk cache to its budget, per [avatarPrunePlan]. */
    private fun prune(dir: File) {
        val files = dir.listFiles() ?: return
        val plan = avatarPrunePlan(
            files.map { AvatarCacheFile(it.name, it.length(), it.lastModified()) },
            CACHE_BUDGET_BYTES,
        )
        if (plan.isEmpty()) return
        val doomed = plan.toSet()
        for (file in files) {
            if (file.name in doomed) file.delete()
        }
    }
}

/** One file of the avatar cache directory, as the prune weighs it. */
internal data class AvatarCacheFile(val name: String, val bytes: Long, val modified: Long)

/**
 * Which cache files to delete: every orphaned `.part` (a download the process
 * died under — a finished one is renamed away within milliseconds), then the
 * oldest avatars — mtime as LRU, coarse but free — until what remains fits
 * [budget]. Pure, so the policy is testable without a filesystem.
 */
internal fun avatarPrunePlan(files: List<AvatarCacheFile>, budget: Long): List<String> {
    val plan = files.filter { it.name.endsWith(".part") }.map { it.name }.toMutableList()
    val avatars = files.filterNot { it.name.endsWith(".part") }.sortedBy { it.modified }
    var total = avatars.sumOf { it.bytes }
    for (file in avatars) {
        if (total <= budget) break
        plan.add(file.name)
        total -= file.bytes
    }
    return plan
}
