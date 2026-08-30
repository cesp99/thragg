package to.eyed.seeker.code.ui.theme

import android.content.Context
import android.net.Uri
import android.os.FileObserver
import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's own themes: the `.json` files in `<filesDir>/themes`, in Zed's
 * own theme file format.
 *
 * Zed installs a theme by dropping a family file into `~/.config/zed/themes`
 * and picks it up without a restart (`theme/src/registry.rs`, watched by
 * `theme_extension`). The folder here is the app-private one settings.json
 * already lives in, so the terminal and the file tree can both reach it, and
 * an "Import theme…" button copies a file in from anywhere the system's
 * document picker can see.
 *
 * **A file that is not a theme is reported, not skipped.** A theme that
 * silently fails to appear is indistinguishable from one the app never
 * noticed, and telling the two apart is the whole reason someone would go
 * looking — so [Scan.problems] carries a sentence per bad file and the theme
 * picker prints them.
 */
object UserThemes {
    private const val TAG = "UserThemes"
    private const val DIRECTORY = "themes"

    /** One theme family file the user installed, and what is in it. */
    data class Installed(val file: File, val themes: List<ZedTheme.Meta>)

    /** A file that is in the folder but is not a theme, and why. */
    data class Problem(val fileName: String, val reason: String)

    /** What the folder holds right now. */
    data class Scan(
        val themes: List<Installed> = emptyList(),
        val problems: List<Problem> = emptyList(),
    ) {
        val count: Int get() = themes.sumOf { it.themes.size }
    }

    private val _scan = MutableStateFlow(Scan())

    /** The last scan, for the picker to print. */
    val scan: StateFlow<Scan> = _scan.asStateFlow()

    /** The folder, created if it is not there. */
    fun directory(context: Context): File =
        File(context.filesDir, DIRECTORY).also { if (!it.exists()) it.mkdirs() }

    /**
     * Read the folder. **Blocking** — call it off the main thread.
     *
     * Idempotent and cheap: a handful of `File` reads, each parsed only far
     * enough to learn what themes it names.
     */
    fun scan(context: Context): Scan {
        val directory = directory(context)
        val themes = mutableListOf<Installed>()
        val problems = mutableListOf<Problem>()
        val files = directory.listFiles()?.filter { it.isFile }.orEmpty().sortedBy { it.name }
        for (file in files) {
            if (!file.name.endsWith(".json", ignoreCase = true)) {
                problems += Problem(file.name, "not a .json file")
                continue
            }
            val text = runCatching { file.readText() }.getOrNull()
            if (text == null) {
                problems += Problem(file.name, "could not be read")
                continue
            }
            val problem = ZedTheme.problemWith(text)
            if (problem != null) {
                problems += Problem(file.name, problem)
                continue
            }
            themes += Installed(file, ZedTheme.index(text))
        }
        val scan = Scan(themes, problems)
        _scan.value = scan
        return scan
    }

    /**
     * Copy a document the user picked into the themes folder.
     *
     * Validated *before* it lands: a file that is not a theme would otherwise
     * sit in the folder as a problem row forever, and the moment to say so is
     * while the picker that produced it is still on screen. Returns the theme
     * names it installed, or a sentence saying why it did not.
     *
     * **Blocking** — call it off the main thread.
     */
    fun import(context: Context, uri: Uri, suggestedName: String?): Result<List<String>> {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: return Result.failure(IllegalArgumentException("the file could not be read"))
        ZedTheme.problemWith(text)?.let {
            return Result.failure(IllegalArgumentException("not a Zed theme file — $it"))
        }
        val metas = ZedTheme.index(text)
        val name = fileNameFor(suggestedName, metas)
        return runCatching {
            val target = File(directory(context), name)
            target.writeText(text)
            rescan(context)
            metas.map { it.name }
        }.onFailure { Log.w(TAG, "theme import failed", it) }
    }

    /** Remove one installed theme file. **Blocking**. */
    fun remove(context: Context, fileName: String): Boolean {
        val file = File(directory(context), fileName)
        // Refuse a name that climbs out of the folder: the name comes from a
        // row the user tapped, but it has been through a `Uri` on the way in.
        if (file.parentFile != directory(context)) return false
        val gone = file.delete()
        if (gone) rescan(context)
        return gone
    }

    /**
     * The name the imported file is stored under: the family's own name where
     * there is one, so the folder reads like a list of themes rather than of
     * `document (3).json`. Sanitised, because it has come from outside.
     */
    private fun fileNameFor(suggested: String?, metas: List<ZedTheme.Meta>): String {
        val base = metas.firstOrNull()?.family?.takeIf { it.isNotBlank() }
            ?: suggested?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: "theme"
        val safe = base.map { if (it.isLetterOrDigit() || it == ' ' || it == '-') it else '_' }
            .joinToString("")
            .trim()
            .ifEmpty { "theme" }
        return "$safe.json"
    }

    /**
     * Rescan and drop every cached palette, so the change is visible on the
     * next frame. **Blocking**.
     */
    fun rescan(context: Context) {
        ZedThemes.rescan()
        scan(context)
    }

    private var observer: FileObserver? = null

    /**
     * Watch the folder for the rest of the process's life, rescanning when it
     * changes.
     *
     * `git clone`ing a themes repository into the folder from the terminal,
     * or writing one with the editor, has to show up without a restart — that
     * is what Zed's own watcher buys, and it is the difference between a
     * folder people use and a folder people forget. One observer for the
     * process; starting twice is a no-op.
     */
    fun watch(context: Context) {
        if (observer != null) return
        val directory = directory(context)
        val watcher = object : FileObserver(
            directory,
            CREATE or DELETE or MOVED_TO or MOVED_FROM or CLOSE_WRITE,
        ) {
            override fun onEvent(event: Int, path: String?) {
                // Any change at all: the folder holds a dozen files at most,
                // and a rescan is cheaper than deciding which event matters.
                runCatching { rescan(context) }
                    .onFailure { Log.w(TAG, "rescan after a folder change failed", it) }
            }
        }
        observer = watcher
        runCatching { watcher.startWatching() }
            .onFailure { Log.w(TAG, "the themes folder could not be watched", it) }
    }
}
