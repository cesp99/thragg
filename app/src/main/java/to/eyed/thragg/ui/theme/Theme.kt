package to.eyed.thragg.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.thragg.core.AppSettings

/**
 * The app's one theme root, and the Material half of the hybrid.
 *
 * An IDE has its own visual identity: we deliberately skip Material dynamic
 * colour so the editor looks like Zed everywhere. Every colour comes from
 * Zed's theme JSON parsed by [ZedTheme], with `theme_overrides` laid over it,
 * and `ZedTheme.palette()` turns that one source into both the Material
 * `ColorScheme` and [ThraggColors] in a single pass. A wallpaper primary would
 * appear nowhere in the editor, which is exactly the clash the hybrid exists
 * to prevent. The two fonts come from settings.json the same way.
 *
 * What this root provides is now the APP's rules — real Material type, real
 * Material shapes, ripple, and the locale's own layout direction. The editor's
 * rules are entered explicitly through [ZedSurface], which is the seam.
 */
@Composable
fun ThraggTheme(
    settings: AppSettings = AppSettings(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val preview by ThemeStore.preview.collectAsState()

    // A disk read, so the first frame paints with what the APK ships and
    // swaps once. That is the right trade: an app that blocks its first frame
    // on disk to avoid one repaint is the worse of the two. Nothing else is
    // scanned for here: the themes are the APK's own (docs/UI.md, "What is
    // removed" — no user themes folder), so there is no watcher to start.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ThemeStore.load(context)
        }
    }

    val systemIsDark = isSystemInDarkTheme()
    val selection = settings.themeSelection
    // A live preview outranks the setting; otherwise the mode picks the slot.
    val name = preview ?: selection.themeName(systemIsDark)
    val preferDark = selection.isDark(systemIsDark) ?: systemIsDark
    // Blocking the first time each theme is asked for — one asset read and
    // one JSON parse. The selector warms the cache when it opens, so tapping
    // down the list never pays it. Keyed on the name and the appearance
    // alone: the bundled files are the only source, so what a name resolves
    // to cannot change under a running process.
    val base = remember(name, preferDark) {
        ZedThemes.get(context, name, preferDark)
    }
    val theme = remember(base, settings.themeOverrides) {
        base.withOverrides(settings.themeOverrides)
    }

    val fonts = settings.fonts
    // A name-to-bundled-family lookup, nothing more: the two-directory scan
    // and the font-file opens went with font extensibility (2026-09-08), so
    // this is a map read on the frame, and an unknown name is the bundled face.
    val uiFontFamily = remember(fonts.uiFamily) {
        FontCatalog.family(fonts.uiFamily, BundledFonts.ui)
    }
    val bufferFontFamily = remember(fonts.bufferFamily, fonts.bufferFallbacks) {
        FontCatalog.familyWithFallbacks(fonts.bufferFamily, fonts.bufferFallbacks, BundledFonts.buffer)
    }

    // One icon theme, Zed's own. `icon_theme` went with the icon-theme
    // selector and the watched `icon_themes` folder (docs/UI.md, "What is
    // removed"), so the tree draws from the bundled tables and nothing is
    // scanned for at start-up.
    val iconTheme = IconThemes.bundled

    // ONE DERIVATION, ONE `remember`, TWO LOCALS. The Material half's whole
    // ColorScheme and the small token set M3 has no role for come out of the
    // same pure call on the same theme, so the two halves of the app cannot
    // disagree by a frame — and there is no second palette authored anywhere.
    // The rules, the ladder and the contrast solving all live in
    // MaterialBridge.kt, where a host test can walk all eleven bundled themes
    // through them without a Compose runtime.
    val palette = remember(theme) { theme.palette() }
    // The status bar and the gesture handle are drawn by the *system*, over
    // our background, and nothing was telling it which way round that
    // background is. `enableEdgeToEdge()` runs once in `MainActivity` with
    // the default auto style, which follows the SYSTEM's dark mode — so on a
    // phone in dark mode showing Ayu Light the clock and the icons stayed
    // white on white and simply disappeared (measured over the clock strip:
    // min 252 / max 255, against 40 / 255 on a dark theme; s5).
    //
    // Driven from the resolved theme instead, in a SideEffect so the window
    // is touched after the frame that changed the theme has been applied and
    // never during composition.
    val view = LocalView.current
    val darkIcons = usesDarkSystemBarIcons(palette.scheme.background)
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.activity()?.window ?: return@SideEffect
            WindowInsetsControllerCompat(window, view).apply {
                isAppearanceLightStatusBars = darkIcons
                isAppearanceLightNavigationBars = darkIcons
            }
        }
    }
    // Zed's `reduce_motion`, answered once for every widget that moves —
    // reading the system's animator scale per animation would be a
    // ContentResolver query per frame and could disagree with itself.
    val reduceMotion = rememberReduceMotion(settings)
    CompositionLocalProvider(
        LocalZedTheme provides theme,
        LocalThraggColors provides palette.thragg,
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
            shapes = ThraggShapes,
            // Keyed on the face alone: this scale is fixed sp on purpose, so a
            // Material sheet does not resize because someone changed the size
            // of the font in their editor. ZedSurface re-provides the scale
            // that does follow ui_font_size, for the half where it should.
            typography = remember(uiFontFamily) { materialTypography(uiFontFamily) },
            content = content,
        )
    }
}

/**
 * Whether the system bars must draw their icons **dark** — that is, whether
 * the app's own background behind them is light.
 *
 * A pure function over the one colour the bars sit on, so the rule is a thing
 * a host test pins across all eleven bundled themes rather than a boolean
 * buried in a SideEffect. The threshold is relative luminance at 0.5, and no
 * bundled theme is anywhere near it — SystemBarIconsTest pins both halves:
 * every theme asks for the icons its own appearance needs, and every one of
 * them sits clear of the line.
 */
internal fun usesDarkSystemBarIcons(background: Color): Boolean = background.luminance() > 0.5f

/**
 * The activity behind a composition's context.
 *
 * `LocalView.current.context` is not the activity: Compose hands out a
 * `ContextThemeWrapper` around it, so the obvious cast is null at run time and
 * the bars would never be told anything. Walked rather than cast.
 */
private fun Context.activity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
