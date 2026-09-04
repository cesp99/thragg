package to.eyed.thragg.ui.theme

import android.content.Context
import android.net.Uri
import android.os.FileObserver
import android.util.Log
import androidx.compose.runtime.staticCompositionLocalOf
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import to.eyed.thragg.ui.workspace.FileIcons
import to.eyed.thragg.ui.workspace.ZED_ICON_BY_STEM
import to.eyed.thragg.ui.workspace.ZED_ICON_BY_SUFFIX
import to.eyed.thragg.ui.workspace.ZED_ICON_DRAWABLE

/**
 * The icon themes the app can draw the file tree with: the bundled set, and
 * any the user has put in `<filesDir>/icon_themes`.
 *
 * The bundled one is Zed's own — the tables in `ZedFileIcons.kt` generated
 * from `theme/src/icon_theme.rs` — given the name Zed gives it,
 * `"Zed (Default)"`. Naming it is most of what this file is for: an icon set
 * that is not a *theme* cannot be chosen between, and `icon_theme` had
 * nothing to point at.
 *
 * A user theme is Zed's icon theme JSON
 * (`{"name":…, "themes":[{"name":…, "file_suffixes":{…}, "file_icons":{…}}]}`),
 * with one platform difference: `file_icons[key].path` is resolved against
 * the theme's own folder and must be a **raster** image — PNG, WebP or JPEG.
 * Zed's icons are SVG and Android's `ImageView` cannot draw one; converting a
 * theme's SVGs at import time would be a build tool, not a setting. A path
 * that does not resolve falls back to the bundled icon for the same key, so a
 * theme that renames types without shipping art still works.
 */
object IconThemes {
    private const val TAG = "IconThemes"
    private const val DIRECTORY = "icon_themes"

    /** The extensions Android can decode without help. */
    private val IMAGE_EXTENSIONS = setOf("png", "webp", "jpg", "jpeg")

    /** A file in the folder that is not an icon theme, and why. */
    data class Problem(val fileName: String, val reason: String)

    data class Scan(
        val themes: List<IconTheme> = emptyList(),
        val problems: List<Problem> = emptyList(),
    )

    /**
     * Zed's own set, named. The tables are generated from Zed's
     * `icon_theme.rs`; this only gives them an identity.
     */
    val bundled: IconTheme = IconTheme(
        name = IconTheme.DEFAULT_NAME,
        isBundled = true,
        fileStems = ZED_ICON_BY_STEM,
        fileSuffixes = ZED_ICON_BY_SUFFIX,
        icons = ZED_ICON_DRAWABLE,
        collapsedDirectory = "ic_file_folder",
        expandedDirectory = "ic_file_folder_open",
        defaultFile = IconTheme.DEFAULT_FILE,
    )

    private val _scan = MutableStateFlow(Scan(listOf(bundled)))

    /** Every icon theme installed, the bundled one first. */
    val scan: StateFlow<Scan> = _scan.asStateFlow()

    /** The folder, created if it is not there. */
    fun directory(context: Context): File =
        File(context.filesDir, DIRECTORY).also { if (!it.exists()) it.mkdirs() }

    /** Read the folder. **Blocking** — call it off the main thread. */
    fun scan(context: Context): Scan {
        val directory = directory(context)
        val themes = mutableListOf(bundled)
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
            val parsed = runCatching { parse(text, directory) }.getOrNull()
            when {
                parsed == null -> problems += Problem(file.name, "not valid JSON")
                parsed.isEmpty() ->
                    problems += Problem(file.name, "no \"themes\" with a name and any icons")
                else -> themes += parsed
            }
        }
        val scan = Scan(themes, problems)
        // The art a previous scan decoded may have been replaced under the
        // same path; a cache keyed by path alone would keep drawing the old
        // one for the life of the process.
        FileIcons.clearImageCache()
        _scan.value = scan
        return scan
    }

    /** The theme called [name], or the bundled one. */
    fun get(name: String): IconTheme =
        _scan.value.themes.firstOrNull { it.name == name } ?: bundled

    /**
     * Copy a document the user picked into the icon-themes folder, validating
     * it first for [UserThemes.import]'s reason: the moment to say a file is
     * not an icon theme is while the picker is still on screen.
     *
     * **Blocking** — call it off the main thread.
     */
    fun import(context: Context, uri: Uri, suggestedName: String?): Result<List<String>> {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("the file could not be read"))
        val directory = directory(context)
        val parsed = runCatching { parse(text, directory) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("not valid JSON"))
        if (parsed.isEmpty()) {
            return Result.failure(
                IllegalArgumentException("not a Zed icon theme — no \"themes\" with icons")
            )
        }
        val base = JSONObject(text).optString("name").ifBlank {
            suggestedName?.substringBeforeLast('.').orEmpty()
        }.ifBlank { "icons" }
        val safe = base.map { if (it.isLetterOrDigit() || it == ' ' || it == '-') it else '_' }
            .joinToString("")
            .trim()
            .ifEmpty { "icons" }
        return runCatching {
            File(directory, "$safe.json").writeText(text)
            scan(context)
            parsed.map { it.name }
        }.onFailure { Log.w(TAG, "icon theme import failed", it) }
    }

    /** Remove one installed icon theme file. **Blocking**. */
    fun remove(context: Context, fileName: String): Boolean {
        val directory = directory(context)
        val file = File(directory, fileName)
        if (file.parentFile != directory) return false
        val gone = file.delete()
        if (gone) scan(context)
        return gone
    }

    /**
     * A family file's themes. Icon keys whose `path` names an image in the
     * theme's folder become absolute paths; every other key is left for the
     * bundled theme to answer, which is what [IconTheme.iconFor] does.
     */
    private fun parse(text: String, directory: File): List<IconTheme> {
        val family = JSONObject(text)
        val themes = family.optJSONArray("themes") ?: return emptyList()
        return (0 until themes.length()).mapNotNull { index ->
            val theme = themes.optJSONObject(index) ?: return@mapNotNull null
            val name = theme.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val icons = mutableMapOf<String, String>()
            theme.optJSONObject("file_icons")?.let { table ->
                for (key in table.keys()) {
                    val path = when (val entry = table.opt(key)) {
                        is JSONObject -> entry.optString("path")
                        is String -> entry
                        else -> ""
                    }
                    resolve(path, directory)?.let { icons[key] = it }
                }
            }
            val stems = stringMap(theme.optJSONObject("file_stems"))
            val suffixes = stringMap(theme.optJSONObject("file_suffixes"))
            if (icons.isEmpty() && stems.isEmpty() && suffixes.isEmpty()) return@mapNotNull null
            val directories = theme.optJSONObject("directory_icons")
            IconTheme(
                name = name,
                isBundled = false,
                fileStems = stems,
                fileSuffixes = suffixes,
                icons = icons,
                collapsedDirectory = directories?.optString("collapsed")
                    ?.let { resolve(it, directory) }
                    ?: bundled.collapsedDirectory,
                expandedDirectory = directories?.optString("expanded")
                    ?.let { resolve(it, directory) }
                    ?: bundled.expandedDirectory,
                defaultFile = IconTheme.DEFAULT_FILE,
            )
        }
    }

    private fun stringMap(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        return json.keys().asSequence().mapNotNull { key ->
            (json.opt(key) as? String)?.takeIf { it.isNotBlank() }?.let { key to it }
        }.toMap()
    }

    /**
     * A theme-relative `path` as an absolute one, or null when it does not
     * name a raster image inside the folder. Refusing to climb out of the
     * folder is not paranoia: the path comes from a JSON file the user may
     * have downloaded, and an icon theme has no business naming
     * `/data/data/…/settings.json`.
     */
    private fun resolve(path: String, directory: File): String? {
        if (path.isBlank()) return null
        if (path.substringAfterLast('.', "").lowercase() !in IMAGE_EXTENSIONS) return null
        val file = File(directory, path).canonicalFile
        if (!file.path.startsWith(directory.canonicalFile.path + File.separator)) return null
        return if (file.isFile) file.path else null
    }

    private var observer: FileObserver? = null

    /** Watch the folder, for [UserThemes.watch]'s reason. */
    fun watch(context: Context) {
        if (observer != null) return
        val directory = directory(context)
        val watcher = object : FileObserver(
            directory,
            CREATE or DELETE or MOVED_TO or MOVED_FROM or CLOSE_WRITE,
        ) {
            override fun onEvent(event: Int, path: String?) {
                runCatching { scan(context) }
                    .onFailure { Log.w(TAG, "rescan after a folder change failed", it) }
            }
        }
        observer = watcher
        runCatching { watcher.startWatching() }
            .onFailure { Log.w(TAG, "the icon themes folder could not be watched", it) }
    }
}

/**
 * The icon theme in force. Static rather than threaded through every row:
 * the file tree, the tab strip and the pickers all draw file icons, and none
 * of them has anything else to do with settings.
 */
val LocalIconTheme = staticCompositionLocalOf { IconThemes.bundled }
