package to.eyed.seeker.code.ui.theme

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat
import to.eyed.seeker.code.R

/**
 * The two typefaces Zed itself ships, vendored from its own assets.
 *
 * Zed's `.ZedSans` is IBM Plex Sans and its `.ZedMono` is Lilex
 * (crates/gpui/src/text_system.rs:1185-1186), and both are SIL Open Font
 * License 1.1 — the licence text travels with them in assets/fonts/. Android's
 * defaults are Roboto and Droid Sans Mono, and no amount of matching sizes and
 * spacing gets to "the same look" while every glyph is a different shape.
 *
 * Only the weights we actually draw are bundled: a full family of both would
 * be 1.6 MB for italics the UI never asks for. Compose synthesises the rest.
 *
 * These are the fallbacks for every name `buffer_font_family` and
 * `ui_font_family` can be set to, and they are also *named* — a settings file
 * that says `"Lilex"` gets the copy in the APK rather than whatever a device
 * happens to have installed under that name.
 */
object BundledFonts {
    const val UI = "IBM Plex Sans"
    const val BUFFER = "Lilex"

    /** What the font picker lists first. */
    val NAMES = listOf(BUFFER, UI)

    val ui: FontFamily = FontFamily(
        Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
        // Zed's UI has no bold; SemiBold is what it reaches for, and Compose
        // maps a request for Bold onto the nearest weight it has.
        Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
    )

    /**
     * The editor and terminal face. Italic and bold are here because the
     * syntax theme asks for them — One Dark sets comments italic and keywords
     * bold — and a synthesised slant on a monospace face makes code look
     * wrong.
     */
    val buffer: FontFamily = FontFamily(
        Font(R.font.lilex_regular, FontWeight.Normal),
        Font(R.font.lilex_bold, FontWeight.Bold),
        Font(R.font.lilex_italic, FontWeight.Normal, FontStyle.Italic),
    )

    /** The bundled family called [name], or null for a name we do not ship. */
    fun family(name: String): FontFamily? = when {
        name.equals(BUFFER, ignoreCase = true) -> buffer
        name.equals(UI, ignoreCase = true) -> ui
        else -> null
    }

    /** The same, as a `Typeface` for the terminal's classic `View`. */
    fun typeface(context: Context, name: String): Typeface? = when {
        name.equals(BUFFER, ignoreCase = true) ->
            ResourcesCompat.getFont(context, R.font.lilex_regular)
        name.equals(UI, ignoreCase = true) ->
            ResourcesCompat.getFont(context, R.font.ibm_plex_sans_regular)
        else -> null
    }
}

/**
 * The chrome's face, as `ui_font_family` resolved it.
 *
 * A composition local rather than a constant because the setting can name a
 * font the user installed, and the resolution needs a `Context` and a disk
 * read — neither of which belongs in a leaf composable drawing a label.
 */
val LocalUiFontFamily = staticCompositionLocalOf { BundledFonts.ui }

/** The editor and terminal face, as `buffer_font_family` resolved it. */
val LocalBufferFontFamily = staticCompositionLocalOf { BundledFonts.buffer }

/**
 * Android's `fontFeatureSettings` for the buffer font — `buffer_font_features`
 * as one string, empty when the file asks for nothing.
 */
val LocalBufferFontFeatures = staticCompositionLocalOf { "" }

/** The chrome's face. Reads [LocalUiFontFamily]; the name is what it was. */
val UiFontFamily: FontFamily
    @Composable @ReadOnlyComposable get() = LocalUiFontFamily.current

/** The editor and terminal face. Reads [LocalBufferFontFamily]. */
val BufferFontFamily: FontFamily
    @Composable @ReadOnlyComposable get() = LocalBufferFontFamily.current
