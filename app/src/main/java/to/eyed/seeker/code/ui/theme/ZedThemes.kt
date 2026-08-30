package to.eyed.seeker.code.ui.theme

import android.content.Context
import android.util.Log
import android.util.LruCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Every theme the app can paint with — the eleven families it ships and the
 * ones the user has dropped into `<filesDir>/themes` — and the cache that
 * keeps switching between them cheap.
 *
 * Zed's own registry is the model: themes are discovered rather than listed in
 * code, they are keyed by full name across families, and a name that no longer
 * exists resolves to the default for its appearance rather than failing
 * (`crates/theme/src/registry.rs`). Ours discovers by listing `assets/themes/`
 * and then the user's folder, so vendoring another family file is the whole
 * change — no Kotlin edit, no enum to extend.
 *
 * **A user theme with a bundled theme's name wins**, which is the opposite of
 * the rule between two bundled files. Zed's registry is last-write-wins for
 * the same reason: overriding a shipped theme by putting your own copy of it
 * in your themes folder is the point of having the folder, and a user who
 * names a file after One Dark has said what they meant.
 *
 * The index is names only: listing eleven themes in the picker must not cost
 * eleven palette parses. Palettes are parsed on first use and kept in a
 * bounded cache ([parsed]) sized so the selector's live preview — which walks
 * the whole list — never evicts what it is about to revisit.
 */
object ZedThemes {
    /** Zed's own defaults (`settings_content/src/theme.rs:353-354`). */
    const val DEFAULT_DARK = "One Dark"
    const val DEFAULT_LIGHT = "One Light"

    private const val TAG = "ZedThemes"
    private const val DIRECTORY = "themes"

    /** Where a theme file comes from: the APK, or the user's folder. */
    private sealed interface Source {
        data class Asset(val path: String) : Source
        data class UserFile(val file: File) : Source
    }

    @Volatile
    private var index: List<ZedTheme.Meta>? = null

    /**
     * Parsed palettes, bounded. Thirty-two because [warm] only works if every
     * installed theme fits at once — the selector's walk previews each one,
     * and evicting mid-walk would put the parse back on the frame that paints
     * it. Eleven ship today and the rest is headroom for a user's folder;
     * past it the least recently previewed palettes go rather than the process
     * keeping every theme it has ever painted.
     */
    private val parsed = LruCache<String, ZedTheme>(32)

    /** Which file each theme name came from, so [get] reads one file. */
    private val sources = ConcurrentHashMap<String, Source>()

    /**
     * Every installed theme, dark first and then by name — Zed's own order
     * (`theme_selector.rs:171-176`), which puts the half you are likely to
     * want at the top rather than interleaving the two appearances.
     *
     * **Blocking** on first call: call it off the main thread.
     */
    fun installed(context: Context): List<ZedTheme.Meta> {
        index?.let { return it }
        val found = LinkedHashMap<String, ZedTheme.Meta>()
        val files = runCatching { context.assets.list(DIRECTORY) }.getOrNull().orEmpty()
        for (file in files) {
            if (!file.endsWith(".json")) continue
            val asset = "$DIRECTORY/$file"
            val metas = runCatching { ZedTheme.index(readAsset(context, asset)) }
                .onFailure { Log.w(TAG, "$asset is not a theme family", it) }
                .getOrDefault(emptyList())
            for (meta in metas) {
                // First file wins among the bundled ones, so a broken
                // duplicate cannot shadow a working theme the user is on.
                if (found.putIfAbsent(meta.name, meta) == null) {
                    sources[meta.name] = Source.Asset(asset)
                }
            }
        }
        for (installed in UserThemes.scan(context).themes) {
            for (meta in installed.themes) {
                // The user's folder is last, and last wins.
                found[meta.name] = meta
                sources[meta.name] = Source.UserFile(installed.file)
                parsed.remove(meta.name)
            }
        }
        val sorted = found.values.sortedWith(compareBy({ !it.isDark }, { it.name }))
        index = sorted
        return sorted
    }

    /**
     * Forget the index and every parsed palette — after the user's themes
     * folder changed. The next [installed] rescans.
     */
    fun rescan() {
        index = null
        sources.clear()
        parsed.evictAll()
    }

    /**
     * The theme called [name], falling back to the default for [preferDark].
     *
     * A miss is expected rather than exceptional: settings.json is
     * hand-editable, and a name that was valid before a family was removed has
     * to resolve to *something* — the alternative is an app that cannot paint
     * its own settings screen to be fixed from.
     *
     * **Blocking** the first time a theme is asked for: it parses a family
     * file. Every call after that is a map lookup.
     */
    fun get(context: Context, name: String, preferDark: Boolean): ZedTheme {
        parsed[name]?.let { return it }
        installed(context)
        load(context, name)?.let { return it }
        val fallback = if (preferDark) DEFAULT_DARK else DEFAULT_LIGHT
        Log.w(TAG, "theme \"$name\" is not installed; using $fallback")
        return parsed[fallback]
            ?: load(context, fallback)
            ?: error("the bundled $fallback theme is missing from the APK")
    }

    /**
     * Parse every installed theme.
     *
     * The selector calls this when it opens: moving the cursor down the list
     * applies each theme in turn, and a parse on the frame that paints it is a
     * stutter the user reads as the app struggling. **Blocking** — it is an
     * `IO` job, not a main-thread one.
     */
    fun warm(context: Context) {
        for (meta in installed(context)) load(context, meta.name)
    }

    private fun load(context: Context, name: String): ZedTheme? {
        val source = sources[name] ?: return null
        val text = runCatching {
            when (source) {
                is Source.Asset -> readAsset(context, source.path)
                is Source.UserFile -> source.file.readText()
            }
        }.onFailure { Log.w(TAG, "theme \"$name\" could not be read", it) }.getOrNull()
            ?: return null
        val theme = runCatching { ZedTheme.parse(text, name) }
            .onFailure { Log.w(TAG, "theme \"$name\" failed to parse", it) }
            .getOrNull()
            ?: return null
        parsed.put(name, theme)
        return theme
    }

    private fun readAsset(context: Context, asset: String): String =
        context.assets.open(asset).bufferedReader().use { it.readText() }
}
