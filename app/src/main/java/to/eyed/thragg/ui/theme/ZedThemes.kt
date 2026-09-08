package to.eyed.thragg.ui.theme

import android.content.Context
import android.util.Log
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap

/**
 * Every theme the app can paint with — the eleven themes in the three family
 * files under `assets/themes/` — and the cache that keeps switching between
 * them cheap.
 *
 * Zed's own registry is the model: themes are discovered rather than listed in
 * code, they are keyed by full name across families, and a name that no longer
 * exists resolves to the default for its appearance rather than failing
 * (`crates/theme/src/registry.rs`). Ours discovers by listing `assets/themes/`,
 * so vendoring another family file is the whole change — no Kotlin edit, no
 * enum to extend.
 *
 * Only the bundled files are read. The watched `<filesDir>/themes` folder and
 * "Import theme…" went with the rest of theme extensibility (docs/UI.md,
 * "What is removed"): the APK is the one source, so the index is built once
 * per process and nothing has to be rescanned.
 *
 * The index is names only: listing eleven themes in the picker must not cost
 * eleven palette parses. Palettes are parsed on first use and kept in a
 * bounded cache ([parsed]) sized so the selector's warm-up — which walks the
 * whole list — never evicts what it is about to revisit.
 */
object ZedThemes {
    /** Zed's own defaults (`settings_content/src/theme.rs:353-354`). */
    const val DEFAULT_DARK = "One Dark"
    const val DEFAULT_LIGHT = "One Light"

    private const val TAG = "ZedThemes"
    private const val DIRECTORY = "themes"

    @Volatile
    private var index: List<ZedTheme.Meta>? = null

    /**
     * Parsed palettes, bounded. Sixteen because [warm] only works if every
     * bundled theme fits at once — the selector reads each one for its
     * swatches, and evicting mid-walk would put the parse back on the frame
     * that paints it. Eleven ship today; the rest is headroom for another
     * vendored family before this number has to move.
     */
    private val parsed = LruCache<String, ZedTheme>(16)

    /** Which asset each theme name came from, so [get] reads one file. */
    private val sources = ConcurrentHashMap<String, String>()

    /**
     * Every bundled theme, dark first and then by name — Zed's own order
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
                // First file wins, so a broken duplicate cannot shadow a
                // working theme the user is on.
                if (found.putIfAbsent(meta.name, meta) == null) {
                    sources[meta.name] = asset
                }
            }
        }
        val sorted = found.values.sortedWith(compareBy({ !it.isDark }, { it.name }))
        index = sorted
        return sorted
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

    private fun load(context: Context, name: String): ZedTheme? {
        val asset = sources[name] ?: return null
        val text = runCatching { readAsset(context, asset) }
            .onFailure { Log.w(TAG, "theme \"$name\" could not be read", it) }
            .getOrNull()
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
