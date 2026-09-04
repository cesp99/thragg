package to.eyed.thragg.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.SystemFonts
import android.util.Log
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Every font `buffer_font_family` and `ui_font_family` can name, and the
 * lookup from a name to something Compose or a `TextView` can draw with.
 *
 * Three sources, in the order a name is resolved against them:
 *
 *  1. **Bundled** — the two faces Zed itself ships, vendored into `res/font`
 *     (see [BundledFonts]). Always present, always first: a settings file
 *     that names Lilex must get *our* Lilex, not a stale copy someone
 *     installed.
 *  2. **User** — anything dropped into `<filesDir>/fonts`. The engine's
 *     settings directory is app-private, so this folder sits beside
 *     settings.json and is reachable from the terminal and from the file
 *     tree; a `.ttf` copied in is offered on the next scan.
 *  3. **System** — `/system/fonts` as `SystemFonts.getAvailableFonts()`
 *     reports it (API 29+). Cheap: it is one call answering with files, and
 *     nothing is opened until a family is actually asked for.
 *
 * Families are keyed by the name [FontNames] reads off the file, because
 * Android exposes no family names of its own — see the note there.
 */
object FontCatalog {
    private const val TAG = "FontCatalog"

    /** Where a font came from, which is what the picker groups by. */
    enum class Origin { Bundled, User, System }

    /** One family the user can name, and the files that make it up. */
    data class Family(
        val name: String,
        val origin: Origin,
        /** Empty for [Origin.Bundled], whose faces are app resources. */
        val files: List<Face>,
    )

    /** One file of a family: its weight and whether it slants. */
    data class Face(val file: File, val weight: Int, val italic: Boolean)

    /** The folder a user drops fonts into, beside settings.json. */
    fun directory(context: Context): File = File(context.filesDir, "fonts")

    @Volatile
    private var index: List<Family>? = null

    private val families = ConcurrentHashMap<String, FontFamily>()
    private val typefaces = ConcurrentHashMap<String, Typeface>()

    /**
     * Every family that can be named, bundled first and then by name.
     *
     * **Blocking** on first call — it lists two directories and asks the
     * platform for its font files. Call it off the main thread; the result is
     * cached until [rescan].
     */
    fun installed(context: Context): List<Family> {
        index?.let { return it }
        val found = LinkedHashMap<String, Family>()
        for (name in BundledFonts.NAMES) {
            found[name] = Family(name, Origin.Bundled, emptyList())
        }
        collect(userFiles(context), Origin.User, found)
        collect(systemFiles(), Origin.System, found)
        val sorted = found.values.sortedWith(compareBy({ it.origin.ordinal }, { it.name }))
        index = sorted
        return sorted
    }

    /**
     * Forget what was scanned — after a font is copied in, or removed.
     * The parsed families go too: a file replaced under the same name must
     * not keep drawing with the old one.
     */
    fun rescan() {
        index = null
        families.clear()
        typefaces.clear()
    }

    /**
     * The Compose family called [name], or [fallback] when the name is not
     * installed — which is the normal state of a hand-edited settings file,
     * not an error worth breaking a screen over.
     *
     * **Blocking** the first time each family is asked for: it opens its
     * files. Every call after that is a map lookup.
     */
    fun family(context: Context, name: String?, fallback: FontFamily): FontFamily {
        if (name.isNullOrBlank()) return fallback
        BundledFonts.family(name)?.let { return it }
        families[name]?.let { return it }
        val faces = installed(context).firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.files
            .orEmpty()
        if (faces.isEmpty()) {
            Log.w(TAG, "font \"$name\" is not installed; using the bundled face")
            return fallback
        }
        val family = runCatching {
            FontFamily(
                faces.sortedBy { it.weight }.map { face ->
                    Font(
                        file = face.file,
                        weight = FontWeight(face.weight),
                        style = if (face.italic) FontStyle.Italic else FontStyle.Normal,
                    )
                }
            )
        }.onFailure { Log.w(TAG, "font \"$name\" could not be loaded", it) }.getOrNull()
            ?: return fallback
        families[name] = family
        return family
    }

    /**
     * [family], with [fallbacks] appended — Zed's `buffer_font_fallbacks`
     * (`settings_content/src/theme.rs:196-197`).
     *
     * Compose resolves a glyph by walking a `FontFamily`'s fonts in order and
     * taking the first that has it, so a family built out of the primary's
     * faces followed by each fallback's *is* the fallback chain. Nothing to
     * fall back to gives the primary unchanged, which is the common case.
     *
     * **Blocking** — call it off the main thread.
     */
    fun familyWithFallbacks(
        context: Context,
        name: String?,
        fallbacks: List<String>,
        fallback: FontFamily,
    ): FontFamily {
        val primary = family(context, name, fallback)
        if (fallbacks.isEmpty()) return primary
        // `FontListFontFamily` *is* a `List<Font>`; its own `fonts` property
        // is internal, so the list interface is the way in.
        val chain = mutableListOf<Font>()
        chain += (primary as? FontListFontFamily).orEmpty()
        for (next in fallbacks) {
            chain += (family(context, next, fallback) as? FontListFontFamily).orEmpty()
        }
        // A family with no listed fonts is a generic one (the platform
        // default); there is nothing to chain, so the primary stands.
        return if (chain.isEmpty()) primary else FontFamily(chain)
    }

    /**
     * The same family as a `Typeface`, for the terminal — which is a classic
     * `View` and takes one of those rather than a Compose family.
     *
     * The upright regular face is what a terminal wants: the emulator picks
     * bold and italic off it itself.
     */
    fun typeface(context: Context, name: String?, fallback: Typeface): Typeface {
        if (name.isNullOrBlank()) return fallback
        BundledFonts.typeface(context, name)?.let { return it }
        typefaces[name]?.let { return it }
        val faces = installed(context).firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.files
            .orEmpty()
        val regular = faces.filter { !it.italic }.minByOrNull { kotlin.math.abs(it.weight - 400) }
            ?: faces.firstOrNull()
            ?: return fallback
        val typeface = runCatching { Typeface.createFromFile(regular.file) }
            .onFailure { Log.w(TAG, "font \"$name\" could not be loaded as a typeface", it) }
            .getOrNull()
            ?: return fallback
        typefaces[name] = typeface
        return typeface
    }

    private fun collect(files: List<File>, origin: Origin, into: MutableMap<String, Family>) {
        for (file in files) {
            if (!FontNames.isFontFile(file.name)) continue
            val face = FontNames.faceOf(file.name)
            val existing = into[face.family]
            // A bundled name is never displaced: settings that say Lilex mean
            // the one in the APK.
            if (existing != null && existing.origin == Origin.Bundled) continue
            val entry = Face(file, face.weight, face.italic)
            into[face.family] = when {
                existing == null -> Family(face.family, origin, listOf(entry))
                else -> existing.copy(files = existing.files + entry)
            }
        }
    }

    private fun userFiles(context: Context): List<File> {
        val directory = directory(context)
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles()?.filter { it.isFile }.orEmpty().sortedBy { it.name }
    }

    /**
     * The device's own fonts. `SystemFonts` answers with files and style
     * metrics but no family names, so the names come from the file names —
     * see [FontNames]. A variable font (one file, every weight) is listed at
     * whatever weight its name claims, which is the best any name-based
     * reading can do.
     */
    private fun systemFiles(): List<File> = runCatching {
        SystemFonts.getAvailableFonts().mapNotNull { it.file }.distinct().sortedBy { it.name }
    }.onFailure { Log.w(TAG, "the system font list could not be read", it) }
        .getOrDefault(emptyList())
}
