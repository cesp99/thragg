package to.eyed.seeker.code.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.core.AppSettings

/**
 * The app's one theme root, and the Material half of the hybrid.
 *
 * An IDE has its own visual identity: we deliberately skip Material dynamic
 * colour so the editor looks like Zed everywhere. Every colour comes from
 * Zed's theme JSON parsed by [ZedTheme], with `theme_overrides` laid over it,
 * and `ZedTheme.palette()` turns that one source into both the Material
 * `ColorScheme` and [SeekerColors] in a single pass. A wallpaper primary would
 * appear nowhere in the editor, which is exactly the clash the hybrid exists
 * to prevent. The two fonts and the icon theme come from settings.json the
 * same way.
 *
 * What this root provides is now the APP's rules — real Material type, real
 * Material shapes, ripple, and the locale's own layout direction. The editor's
 * rules are entered explicitly through [ZedSurface], which is the seam.
 */
@Composable
fun SeekerCodeByEyedTheme(
    settings: AppSettings = AppSettings(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val preview by ThemeStore.preview.collectAsState()
    val userThemes by UserThemes.scan.collectAsState()
    val iconThemes by IconThemes.scan.collectAsState()

    // Everything here is a disk read, so the first frame paints with what the
    // APK ships and swaps once. That is the right trade: an app that blocks
    // its first frame on disk to avoid one repaint is the worse of the two.
    // The watchers are what make dropping a theme or a font into the folders
    // show up without a restart.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ThemeStore.load(context)
            UserThemes.scan(context)
            UserThemes.watch(context)
            IconThemes.scan(context)
            IconThemes.watch(context)
        }
    }

    val systemIsDark = isSystemInDarkTheme()
    val selection = settings.themeSelection
    // A live preview outranks the setting; otherwise the mode picks the slot.
    val name = preview ?: selection.themeName(systemIsDark)
    val preferDark = selection.isDark(systemIsDark) ?: systemIsDark
    // Blocking the first time each theme is asked for — one file read and one
    // JSON parse. The selector warms the cache when it opens, so previewing
    // down the list never pays it. `userThemes` is a key because a file
    // appearing in the folder can change what a name resolves to.
    val base = remember(name, preferDark, userThemes) {
        ZedThemes.get(context, name, preferDark)
    }
    val theme = remember(base, settings.themeOverrides) {
        base.withOverrides(settings.themeOverrides)
    }

    val fonts = settings.fonts
    // Off the main thread: resolving a family name scans two directories and
    // opens font files. Until it answers, the bundled face draws — which is
    // also the answer for every name that is not installed.
    val uiFontFamily by produceState(BundledFonts.ui, fonts.uiFamily) {
        value = withContext(Dispatchers.IO) {
            FontCatalog.family(context, fonts.uiFamily, BundledFonts.ui)
        }
    }
    val bufferFontFamily by produceState(
        BundledFonts.buffer,
        fonts.bufferFamily,
        fonts.bufferFallbacks,
    ) {
        value = withContext(Dispatchers.IO) {
            FontCatalog.familyWithFallbacks(
                context,
                fonts.bufferFamily,
                fonts.bufferFallbacks,
                BundledFonts.buffer,
            )
        }
    }

    // The icon theme follows the *appearance*, as Zed's `icon_theme` object
    // does; a bare name ignores it.
    val iconTheme = remember(settings.iconTheme, theme.isDark, iconThemes) {
        iconThemes.themes.firstOrNull {
            it.name == settings.iconTheme.iconThemeName(theme.isDark)
        } ?: IconThemes.bundled
    }

    // ONE DERIVATION, ONE `remember`, TWO LOCALS. The Material half's whole
    // ColorScheme and the small token set M3 has no role for come out of the
    // same pure call on the same theme, so the two halves of the app cannot
    // disagree by a frame — and there is no second palette authored anywhere.
    // The rules, the ladder and the contrast solving all live in
    // MaterialBridge.kt, where a host test can walk all eleven bundled themes
    // through them without a Compose runtime.
    val palette = remember(theme) { theme.palette() }
    // Zed's `reduce_motion`, answered once for every widget that moves —
    // reading the system's animator scale per animation would be a
    // ContentResolver query per frame and could disagree with itself.
    val reduceMotion = rememberReduceMotion(settings)
    CompositionLocalProvider(
        LocalZedTheme provides theme,
        LocalSeekerColors provides palette.seeker,
        LocalAppSettings provides settings,
        LocalReduceMotion provides reduceMotion,
        LocalUiFontSize provides fonts.uiSize,
        LocalUiFontFamily provides uiFontFamily,
        LocalBufferFontFamily provides bufferFontFamily,
        LocalBufferFontFeatures provides fonts.featureSettings,
        LocalIconTheme provides iconTheme,
        // LocalIndication and LocalLayoutDirection are deliberately NOT
        // provided here any more. Both used to pin the whole app to the
        // editor's rules: no ripple, and LTR whatever the locale. The reason
        // for each is real and *local* — Zed's chrome does not ripple, and the
        // editor draws indent guides and focus rails at absolute x in
        // drawBehind — so both moved into ZedSurface, which is the only place
        // those reasons apply. What is left out here is the Material half
        // getting press feedback and correct RTL, which is what it should have
        // had (docs/VISUAL.md, "THE BOUNDARY, EXACTLY").
    ) {
        // The root is stock MaterialTheme rather than MaterialExpressiveTheme.
        // The spec called for the expressive entry point, and its JVM method
        // in material3 1.4.0 really is public and unmangled — but the Kotlin
        // declaration is `internal`, and Kotlin resolves visibility from
        // @Metadata, so the call does not compile: "Cannot access 'fun
        // MaterialExpressiveTheme(...)': it is internal in file." Same for
        // MotionScheme, MotionScheme.expressive() and MaterialTheme.motionScheme.
        // Nothing is lost but the motion scheme, because that is all
        // MaterialExpressiveTheme does differently in 1.4.0 — its other effect
        // is an internal LocalUsingExpressiveTheme flag no component reads,
        // and the only stock component that reads a MotionScheme at all in
        // this version is TextField. The expressive motion numbers themselves
        // are reproduced from ExpressiveMotionTokens in Motion.kt, where every
        // animation in the app reads them through effectSpec/spatialSpec.
        MaterialTheme(
            colorScheme = palette.scheme,
            shapes = SeekerShapes,
            // Keyed on the face alone: this scale is fixed sp on purpose, so a
            // Material sheet does not resize because someone changed the size
            // of the font in their editor. ZedSurface re-provides the scale
            // that does follow ui_font_size, for the half where it should.
            typography = remember(uiFontFamily) { materialTypography(uiFontFamily) },
            content = content,
        )
    }
}
