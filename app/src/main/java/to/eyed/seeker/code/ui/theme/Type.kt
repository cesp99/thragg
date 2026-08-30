package to.eyed.seeker.code.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Zed's UI type scale, not Material's.
 *
 * Zed sets `window.rem_size = ui_font_size` and `ui_font_size` defaults to 16,
 * so its `rems(x)` land on round numbers: the label sizes are 1rem / 0.875rem
 * / 0.75rem / 0.625rem — 16, **14**, 12 and 10 — with 14 the one nearly all
 * chrome uses (theme_settings/src/settings.rs:619, assets/settings/default.json:71,
 * ui/src/styles/typography.rs:138-141). Material's defaults are 16sp bodies
 * with tracking, which is why our chrome reads looser and larger than Zed's.
 *
 * The ratios rather than the sizes are what is written down here, because the
 * scale is a function of `ui_font_size` — a user who sets 20 gets 20/17.5/15/
 * 12.5, exactly as Zed does, instead of a setting that moves nothing.
 *
 * **Tracking is zero everywhere.** Zed sets none, anywhere; Material3 ships
 * 0.25sp on bodyMedium and 0.5sp on the labels, and at 12-14sp that is
 * visible — it is most of why a row of ours never quite matched a row of
 * Zed's. Line height follows Zed's default `LineHeightStyle::TextLabel`,
 * which is gpui's φ (1.618), except where a widget asks for `UiLabel`
 * (relative 1.0) and sets its own.
 */
private const val PHI = 1.618034f

/** `TextSize::Large` — `rems(1.0)`, `ui/src/styles/typography.rs:138`. */
private const val LARGE = 1.0f

/** `TextSize::Default` — `rems(0.875)`, the size nearly all chrome uses. */
private const val DEFAULT = 0.875f

/** `TextSize::Small` — `rems(0.75)`. */
private const val SMALL = 0.75f

/** `TextSize::XSmall` — `rems(0.625)`, keybinding chips and the like. */
private const val XSMALL = 0.625f

private fun ui(
    uiFontSize: Float,
    family: FontFamily,
    ratio: Float,
    weight: FontWeight = FontWeight.Normal,
): TextStyle {
    val size = uiFontSize * ratio
    return TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = (size * PHI).sp,
        letterSpacing = 0.sp,
    )
}

/**
 * The scale at a given `ui_font_size`, in a given face.
 *
 * The face is a parameter rather than a constant because `ui_font_family` can
 * name any font on the device (see `FontCatalog`); it defaults to the bundled
 * IBM Plex Sans, which is what Zed draws its chrome in.
 */
fun zedTypography(
    uiFontSize: Float,
    family: FontFamily = BundledFonts.ui,
): Typography = Typography(
    // Zed's TextSize::Large — the biggest thing chrome uses.
    bodyLarge = ui(uiFontSize, family, LARGE),
    // TextSize::Default. Tabs, panel rows, menu items, the status bar: if a
    // widget does not say otherwise, it is this.
    bodyMedium = ui(uiFontSize, family, DEFAULT),
    bodySmall = ui(uiFontSize, family, SMALL),
    labelLarge = ui(uiFontSize, family, DEFAULT, FontWeight.Medium),
    labelMedium = ui(uiFontSize, family, SMALL),
    labelSmall = ui(uiFontSize, family, XSMALL),
    // Zed has no type larger than 1rem in its chrome; the three title roles
    // exist because Material components reach for them, and they step up from
    // Large rather than jumping to Material's 22 and 28sp.
    titleLarge = ui(uiFontSize, family, 1.125f, FontWeight.Medium),
    titleMedium = ui(uiFontSize, family, LARGE, FontWeight.Medium),
    titleSmall = ui(uiFontSize, family, DEFAULT, FontWeight.Medium),
)
