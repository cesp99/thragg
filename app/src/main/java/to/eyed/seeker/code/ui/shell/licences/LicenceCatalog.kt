package to.eyed.seeker.code.ui.shell.licences

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The notices bundle, as values — every component in this package, what
 * licence governs it, and which verbatim text says so.
 *
 * Nothing here is a constant. The whole catalogue is read from
 * `assets/licenses/components.json`, which is **generated** by
 * `tools/gen-licenses.py` from the real dependency graphs: the
 * aarch64-linux-android link closure of `libseekercore.so` (471 crates), the
 * release runtime classpath (110 Maven modules), and the checked-in manifest
 * for what neither graph can see — Termux, proot, talloc, the fonts, the
 * icons, the grammars' query files, Eyed's own entry. docs/LICENSING.md §4
 * says why: "a hand-curated list of 471 crates is a list that is wrong within
 * one sprint", and a licences screen with a Kotlin `listOf(...)` in it is that
 * list with an extra step.
 *
 * **The screen must work with the radio off.** Both halves of it — the rows
 * and the licence texts they open — are files in the APK, so a reader on a
 * plane, or on a Seeker that has never been online, gets the same answer as
 * anyone else. That is not a nicety: MIT, ISC, BSD-2/3 and Zlib all require
 * their notice to accompany a *binary* distribution, and a notice you have to
 * fetch is not one that accompanied anything.
 *
 * [parse] and [filter] are pure functions over strings so LicenceCatalogTest
 * can pin them without an emulator; [Licences] is the thin Android half that
 * opens the assets. Both are **blocking** — 260 KB of JSON and up to 35 KB of
 * licence text — and every caller reads them on IO.
 */
data class LicenceCatalog(val groups: List<LicenceGroup>) {

    /** Every component, flattened, in the order the screen lists them. */
    val components: List<LicenceComponent> = groups.flatMap { it.components }

    /** By [LicenceComponent.id], which is what a detail route carries. */
    private val byId: Map<String, LicenceComponent> = components.associateBy { it.id }

    operator fun get(id: String): LicenceComponent? = byId[id]

    val isEmpty: Boolean get() = components.isEmpty()

    companion object {
        val Empty = LicenceCatalog(emptyList())

        /**
         * Read `components.json`.
         *
         * Tolerant of a row it does not understand and intolerant of a file it
         * cannot parse at all: a future generator that adds a field must not
         * blank the screen, but a truncated asset should fail loudly at the
         * one call site that catches it rather than silently list nothing.
         */
        fun parse(json: String): LicenceCatalog {
            val root = JSONObject(json)
            val groups = root.optJSONArray("groups") ?: JSONArray()
            return LicenceCatalog(
                (0 until groups.length()).mapNotNull { index ->
                    groups.optJSONObject(index)?.let(::group)
                }.filter { it.components.isNotEmpty() }
            )
        }

        private fun group(json: JSONObject): LicenceGroup {
            val components = json.optJSONArray("components") ?: JSONArray()
            return LicenceGroup(
                id = json.optString("id"),
                title = json.optString("title"),
                note = json.optString("note").takeIf { it.isNotBlank() },
                components = (0 until components.length()).mapNotNull { index ->
                    components.optJSONObject(index)?.let(::component)
                },
            )
        }

        private fun component(json: JSONObject): LicenceComponent = LicenceComponent(
            id = json.optString("id"),
            name = json.optString("name"),
            version = json.optString("version"),
            spdx = json.optString("spdx"),
            // `optString` turns JSON null into the string "null", which would
            // print a copyright holder called null on a compliance screen.
            copyright = json.optStringOrNull("copyright"),
            url = json.optStringOrNull("url"),
            note = json.optStringOrNull("note"),
            origin = json.optStringOrNull("origin"),
            authors = json.optJSONArray("authors").strings(),
            licenceFiles = json.optJSONArray("licenseFiles").strings(),
        )

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList()
            else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }
}

/** One heading in the list, and the components under it. */
data class LicenceGroup(
    val id: String,
    val title: String,
    /** One sentence on what this group is and, where it matters, is not. */
    val note: String?,
    val components: List<LicenceComponent>,
)

/**
 * One row: what §5 calls "name · version-or-commit · SPDX id", plus what its
 * detail screen needs.
 */
data class LicenceComponent(
    /** Stable across regenerations — "rust/serde@1.0.229". The detail route carries it. */
    val id: String,
    val name: String,
    /** May be empty: Eyed's own icons have no version and do not need one. */
    val version: String,
    val spdx: String,
    /**
     * The copyright line, copied out of the component's own licence file.
     *
     * Null where upstream ships none inside the package — which is 119 of the
     * 471 crates, because a crate's `.crate` archive often carries the MIT
     * *permission* text with no holder and leaves the notice in its git
     * repository. The generator refuses to invent one; [authors] is what it
     * has instead, and the screen labels it as authors rather than passing it
     * off as a copyright notice.
     */
    val copyright: String?,
    val url: String?,
    /** Why this component is here, where that is not obvious from the name. */
    val note: String?,
    /** For a package inside this tree: vendored from where, or written here. */
    val origin: String?,
    val authors: List<String>,
    /**
     * Asset paths, relative to `assets/`, of every text this row obliges us to
     * carry — *every* one, not the first. A row reading "MIT OR Apache-2.0"
     * has both, because a reader cannot choose between two licences they have
     * only been shown one of; an LGPL row has LGPLv3 *and* GPLv3, because
     * LGPLv3 is a set of additional permissions on top of GPLv3 and is
     * meaningless alone.
     */
    val licenceFiles: List<String>,
)

/**
 * Which components match what has been typed.
 *
 * Every token has to match somewhere in the row — so "mit tree-sitter" finds
 * the MIT-licensed grammars and "gpl termux" finds the two terminal modules —
 * and a group with no surviving row drops out rather than showing an empty
 * heading. An empty query returns the catalogue unchanged, which is the state
 * the screen opens in.
 *
 * docs/LICENSING.md §5 says no filter field, "the list is long but it is
 * grouped, and a filter field on a compliance screen is chrome". That was
 * written before the list was generated and turned out to be **615 rows**:
 * scrolling to `unicode-ident` past 470 crates is not reading, it is hunting.
 * The filter is over the rows the screen already shows and changes nothing
 * about what is reachable without it, so the spec's concern — that the screen
 * hide behind a search box — does not arise: the header, the groups and every
 * row are there when the field is empty.
 */
fun LicenceCatalog.filter(query: String): LicenceCatalog {
    val tokens = query.trim().lowercase().split(' ').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return this
    return LicenceCatalog(
        groups.mapNotNull { group ->
            val matched = group.components.filter { component ->
                tokens.all { token -> component.matches(token, group) }
            }
            if (matched.isEmpty()) null else group.copy(components = matched)
        }
    )
}

/**
 * The haystack for one token: everything printed on the row plus the group it
 * sits in, so "android apache" narrows and "font" finds the two typefaces
 * through their group's title rather than through a word neither name has.
 */
private fun LicenceComponent.matches(token: String, group: LicenceGroup): Boolean =
    name.contains(token, ignoreCase = true) ||
        version.contains(token, ignoreCase = true) ||
        spdx.contains(token, ignoreCase = true) ||
        copyright?.contains(token, ignoreCase = true) == true ||
        authors.any { it.contains(token, ignoreCase = true) } ||
        group.title.contains(token, ignoreCase = true)

/**
 * The Android half: opens the assets, once, and keeps what it read.
 *
 * Same shape and the same reasoning as [to.eyed.seeker.code.ui.theme.ZedThemes]
 * — one volatile field, a blocking first call the caller is told about, and no
 * lifecycle. The catalogue is immutable and identical for every reader, so
 * there is nothing here worth a ViewModel.
 */
object Licences {

    private const val TAG = "Licences"
    private const val CATALOG = "licenses/components.json"

    @Volatile
    private var cached: LicenceCatalog? = null

    /**
     * The catalogue. **Blocking** on the first call — 260 KB of JSON — so call
     * it off the main thread; afterwards it is a field read.
     *
     * A failure returns an empty catalogue rather than throwing, and the
     * screen says so in as many words. The alternative — a crash in Settings
     * because an asset is malformed — would take the whole app down over a
     * notice, and an empty screen that admits it is empty is at least honest
     * about the compliance failure it represents.
     */
    fun catalog(context: Context): LicenceCatalog {
        cached?.let { return it }
        val parsed = runCatching { LicenceCatalog.parse(read(context, CATALOG)) }
            .onFailure { Log.e(TAG, "$CATALOG could not be read", it) }
            .getOrDefault(LicenceCatalog.Empty)
        cached = parsed
        return parsed
    }

    /**
     * One verbatim licence text, by the asset path a row names. **Blocking.**
     *
     * Null rather than an exception when the asset is missing: the generator
     * refuses to emit a `licenseFiles` entry that is not in `assets/`, so this
     * can only happen to a build whose assets and JSON were packaged out of
     * step, and the detail screen has something to say about that.
     */
    fun text(context: Context, assetPath: String): String? =
        runCatching { read(context, assetPath) }
            .onFailure { Log.w(TAG, "licence text $assetPath is missing", it) }
            .getOrNull()

    private fun read(context: Context, asset: String): String =
        context.assets.open(asset).bufferedReader().use { it.readText() }
}
