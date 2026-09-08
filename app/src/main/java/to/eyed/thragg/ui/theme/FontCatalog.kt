package to.eyed.thragg.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily

/**
 * The lookup from a `buffer_font_family` or `ui_font_family` name to
 * something Compose or a `TextView` can draw with.
 *
 * Only the bundled faces can be named — the two Zed itself ships, vendored
 * into `res/font` (see [BundledFonts]). There is no font family picker on the
 * phone, and with it went the `fonts` folder beside settings.json and the
 * `SystemFonts` enumeration that used to feed it (docs/UI.md, "What is
 * removed"): a catalogue nobody can browse is a directory scan on every
 * start-up for the benefit of no screen. What is kept is the settings
 * contract. A hand-edited settings.json that says `"Lilex"` or
 * `"IBM Plex Sans"` still gets that face, and a name we do not ship falls
 * back to the bundled one for that slot — logged, not thrown, because a
 * typo in a settings file is the normal state of a settings file and not
 * worth breaking a screen over.
 *
 * Nothing here blocks: a bundled family is a resource reference, and the
 * fallback is already built.
 */
object FontCatalog {
    private const val TAG = "FontCatalog"

    /**
     * The Compose family called [name], or [fallback] when the name is
     * empty or is not one we bundle.
     */
    fun family(name: String?, fallback: FontFamily): FontFamily {
        if (name.isNullOrBlank()) return fallback
        BundledFonts.family(name)?.let { return it }
        Log.w(TAG, "font \"$name\" is not bundled; using the bundled face")
        return fallback
    }

    /**
     * [family], with [fallbacks] appended — Zed's `buffer_font_fallbacks`
     * (`settings_content/src/theme.rs:196-197`).
     *
     * Compose resolves a glyph by walking a `FontFamily`'s fonts in order and
     * taking the first that has it, so a family built out of the primary's
     * faces followed by each fallback's *is* the fallback chain. Nothing to
     * fall back to gives the primary unchanged, which is the common case.
     */
    fun familyWithFallbacks(
        name: String?,
        fallbacks: List<String>,
        fallback: FontFamily,
    ): FontFamily {
        val primary = family(name, fallback)
        if (fallbacks.isEmpty()) return primary
        // `FontListFontFamily` *is* a `List<Font>`; its own `fonts` property
        // is internal, so the list interface is the way in.
        val chain = mutableListOf<Font>()
        chain += (primary as? FontListFontFamily).orEmpty()
        for (next in fallbacks) {
            chain += (family(next, fallback) as? FontListFontFamily).orEmpty()
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
     * bold and italic off it itself. Reading a font resource is a disk read,
     * so the terminal still asks for this off the main thread.
     */
    fun typeface(context: Context, name: String?, fallback: Typeface): Typeface {
        if (name.isNullOrBlank()) return fallback
        BundledFonts.typeface(context, name)?.let { return it }
        Log.w(TAG, "font \"$name\" is not bundled; using the bundled face")
        return fallback
    }
}
