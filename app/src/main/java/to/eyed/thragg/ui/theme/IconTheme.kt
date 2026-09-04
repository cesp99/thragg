package to.eyed.thragg.ui.theme

/**
 * An icon theme: which icon a file name gets, and what that icon *is*.
 *
 * Zed's `IconTheme` (theme/src/icon_theme.rs:21-40) in the three tables that
 * matter here — `file_stems`, `file_suffixes` and `file_icons` — plus the
 * directory pair. The lookup is Zed's own (`file_icons/src/file_icons.rs`),
 * and it matters that it is *this* lookup rather than "split on the last dot":
 *
 *  1. the whole file name first, so `Dockerfile` and `.gitignore` resolve at
 *     all, and `eslint.config.js` gets eslint's icon rather than JavaScript's;
 *  2. then progressively shorter suffixes, so `auth.module.js` can match
 *     `module.js` before it falls back to `js`.
 *
 * Free of Android types on purpose — this is the part with rules in it.
 *
 * An icon is named by a [String] that is either the name of a drawable in the
 * APK (`ic_file_rust`) or an absolute path to an image file a user theme
 * shipped. The two are told apart by the leading `/`, which a resource name
 * cannot have.
 */
data class IconTheme(
    val name: String,
    /** Whether this is the set baked into the APK, which cannot be removed. */
    val isBundled: Boolean,
    /** Whole file names, e.g. `Dockerfile` → `docker`. */
    val fileStems: Map<String, String>,
    /** Suffixes, e.g. `rs` → `rust`, `eslint.config.js` → `eslint`. */
    val fileSuffixes: Map<String, String>,
    /** Icon key → drawable name or absolute image path. */
    val icons: Map<String, String>,
    val collapsedDirectory: String,
    val expandedDirectory: String,
    /** What a file no table answers for gets. */
    val defaultFile: String,
) {
    /**
     * The icon for [fileName]: this theme's, falling back to [fallback]'s for
     * a key it names but has no image for, and finally to the plain sheet.
     *
     * The fallback is what makes a user icon theme worth writing: overriding
     * the six file types you care about should not cost you the other
     * seventy-three, which is what a theme replacing the set wholesale would
     * do. Zed's registry has no such rule — an icon theme there is complete —
     * but there the alternative is an extension, and here it is a JSON file
     * someone wrote by hand.
     */
    fun iconFor(fileName: String, fallback: IconTheme? = null): String {
        val key = iconKey(fileName)
            ?: fallback?.iconKey(fileName)
            ?: return icons[defaultFile] ?: defaultFile
        return icons[key]
            ?: fallback?.icons?.get(key)
            ?: fallback?.defaultFile
            ?: defaultFile
    }

    /** The directory icon, open when the row is expanded — as in Zed. */
    fun directoryIcon(isExpanded: Boolean): String =
        if (isExpanded) expandedDirectory else collapsedDirectory

    /** The icon *key* [fileName] resolves to, or null when no table names it. */
    fun iconKey(fileName: String): String? {
        var candidate = fileName
        fileStems[candidate]?.let { return it }
        fileSuffixes[candidate]?.let { return it }
        // `a.b.c` asks about `b.c`, then `c` — the loop Zed runs, and the
        // reason a dotfile like `.gitignore` is answered by its *whole* name
        // above rather than by the empty stem before its dot.
        while (true) {
            val dot = candidate.indexOf('.')
            if (dot < 0) return null
            candidate = candidate.substring(dot + 1)
            if (candidate.isEmpty()) return null
            fileSuffixes[candidate]?.let { return it }
        }
    }

    companion object {
        /** Zed's own icon theme name (theme/src/icon_theme.rs:424). */
        const val DEFAULT_NAME = "Zed (Default)"

        /** The generic file sheet. */
        const val DEFAULT_FILE = "ic_file_file"
    }
}
