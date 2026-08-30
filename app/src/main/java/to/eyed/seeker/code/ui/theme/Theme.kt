package to.eyed.seeker.code.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.core.AppSettings

/**
 * No ripple, anywhere.
 *
 * gpui has no transition machinery and Zed's chrome swaps colours instantly on
 * hover and press (ui/src/styles/animation.rs has a vocabulary and almost no
 * callers) — the animated Material ripple is the loudest single "not Zed" tell
 * a Compose port can carry. Widgets that want press feedback draw their own
 * state from the interaction source, exactly as Zed's components restate
 * their hover/active fills.
 */
private object NoIndication : IndicationNodeFactory {
    private class NoopNode : Modifier.Node()
    override fun create(interactionSource: InteractionSource): DelegatableNode = NoopNode()
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}

// An IDE has its own visual identity: we deliberately skip Material dynamic
// color so the editor looks like Zed everywhere. All colors come from Zed's
// theme JSON parsed by ZedTheme, with `theme_overrides` laid over it; Material
// roles are mapped from the theme's style table for the stock components we
// use. The two fonts and the icon theme come from settings.json the same way.
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

    // Material's light/dark base follows the *theme*, not the mode: previewing
    // a light theme while the device is dark must give light scrollbars and
    // ripples too, or half the stock components fight the palette.
    val colorScheme = remember(theme) {
        val base = if (theme.isDark) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = theme.color("text.accent"),
            background = theme.color("editor.background"),
            surface = theme.color("panel.background"),
            surfaceVariant = theme.color("elevated_surface.background"),
            // Material's menus and dialogs paint themselves from the
            // surfaceContainer roles, not from `surface` — left unmapped they
            // leak Material's own purple-tinted defaults into any stock
            // DropdownMenu or AlertDialog (tokens/MenuTokens.kt maps menus to
            // SurfaceContainer). All of them are Zed's elevated surface.
            surfaceContainerLowest = theme.color("elevated_surface.background"),
            surfaceContainerLow = theme.color("elevated_surface.background"),
            surfaceContainer = theme.color("elevated_surface.background"),
            surfaceContainerHigh = theme.color("elevated_surface.background"),
            surfaceContainerHighest = theme.color("elevated_surface.background"),
            outline = theme.color("border"),
            outlineVariant = theme.color("border.variant"),
            onPrimary = theme.color("editor.background"),
            onBackground = theme.color("text"),
            onSurface = theme.color("text"),
            onSurfaceVariant = theme.color("text.muted"),
            error = theme.color("error"),
        )
    }
    // Zed's `reduce_motion`, answered once for every widget that moves —
    // reading the system's animator scale per animation would be a
    // ContentResolver query per frame and could disagree with itself.
    val reduceMotion = rememberReduceMotion(settings)
    CompositionLocalProvider(
        LocalZedTheme provides theme,
        LocalAppSettings provides settings,
        LocalReduceMotion provides reduceMotion,
        LocalUiFontSize provides fonts.uiSize,
        LocalUiFontFamily provides uiFontFamily,
        LocalBufferFontFamily provides bufferFontFamily,
        LocalBufferFontFeatures provides fonts.featureSettings,
        LocalIconTheme provides iconTheme,
        LocalIndication provides NoIndication,
        // Zed's chrome is LTR-only, and ours draws indent guides, tab borders
        // and focus rails at absolute x in drawBehind — mirroring the layout
        // under an RTL locale would put them on the wrong side of rows that
        // did mirror. Pin the direction instead of mirroring every draw.
        LocalLayoutDirection provides LayoutDirection.Ltr,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = remember(fonts.uiSize, uiFontFamily) {
                zedTypography(fonts.uiSize, uiFontFamily)
            },
            content = content
        )
    }
}
