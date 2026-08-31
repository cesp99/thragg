package to.eyed.seeker.code.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
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

/**
 * The Material half's type scale: **fixed sp, all fifteen roles, one face**.
 *
 * The face is the same [zedTypography] draws — the user's `ui_font_family`,
 * defaulting to the bundled IBM Plex Sans — and that is deliberate. What
 * transfers from the reference client is the SLOT DISCIPLINE, not its Roboto:
 * a Roboto agent panel two taps from a Plex editor is a visible seam on one
 * 400dp screen, and the whole hybrid rests on the two halves sharing
 * everything they can (docs/VISUAL.md, "Foundations", TYPE).
 *
 * The sizes are fixed rather than a ratio of `ui_font_size`, which is the one
 * place this scale and [zedTypography] disagree on principle. `ui_font_size`
 * is a *buffer chrome* setting: it exists so a user can make the editor's tabs
 * and gutter match the code they set beside them. A sheet full of switches has
 * nothing to do with that, and a Material dialog that grew because someone
 * bumped their editor font would be a bug nobody could name.
 *
 * All fifteen are filled because six of them leak otherwise. Left unset,
 * `displaySmall`, `headlineLarge/Medium/Small` and friends keep Material's own
 * Roboto metrics at 32-57sp — and they are reachable, not theoretical:
 * `AlertDialog` draws its title in `headlineSmall`, `LargeTopAppBar` its title
 * in `headlineMedium`. Those were the largest type in the app and nobody had
 * chosen them.
 *
 * The numbers, and the two that matter most:
 *
 *  - **[Typography.bodyMedium] 14/20 is the transcript workhorse.** Seeker's
 *    agent transcript is built today mostly out of `labelSmall` (10sp) and
 *    `labelMedium` (12sp) borrowed off the Zed chrome scale, and that is most
 *    of why its rows read cramped rather than composed. Prose in an app is
 *    14sp; a 10sp caption is a caption.
 *  - `bodyLarge` is 15 rather than Material's 16. It is the default text style
 *    the theme provides, so it is what an unstyled `Text` lands on, and 16sp
 *    of Plex on a 400dp column is one word per line more than it should be.
 *
 * Tracking follows Material rather than Zed here: negative at display and
 * headline sizes where letters crowd, +0.1sp on body and +0.2sp on the small
 * labels where they otherwise merge. This is the opposite of
 * [zedTypography]'s zero-everywhere rule and it is right for the same reason
 * that rule is right on the other side — each half is tracked the way its own
 * reference is.
 */
fun materialTypography(family: FontFamily = BundledFonts.ui): Typography = Typography(
    displayLarge = md(family, 48f, 54f, FontWeight.SemiBold, -0.4f),
    displayMedium = md(family, 40f, 46f, FontWeight.SemiBold, -0.4f),
    displaySmall = md(family, 34f, 40f, FontWeight.SemiBold, -0.3f),
    headlineLarge = md(family, 32f, 38f, FontWeight.SemiBold, -0.3f),
    // AlertDialog and LargeTopAppBar reach for these two by default.
    headlineMedium = md(family, 28f, 34f, FontWeight.SemiBold, -0.3f),
    headlineSmall = md(family, 24f, 30f, FontWeight.SemiBold, -0.2f),
    // A screen's or a sheet's own name.
    titleLarge = md(family, 20f, 26f, FontWeight.SemiBold, -0.1f),
    // A card's headline, and a SectionHeader's.
    titleMedium = md(family, 16f, 22f, FontWeight.SemiBold, 0f),
    titleSmall = md(family, 14f, 20f, FontWeight.Medium, 0f),
    // The theme's default text style: what an unstyled Text gets.
    bodyLarge = md(family, 15f, 22f, FontWeight.Normal, 0.1f),
    // The transcript, and every option row's description.
    bodyMedium = md(family, 14f, 20f, FontWeight.Normal, 0.1f),
    bodySmall = md(family, 12f, 16f, FontWeight.Normal, 0.1f),
    // Button labels.
    labelLarge = md(family, 13f, 18f, FontWeight.Medium, 0.1f),
    labelMedium = md(family, 12f, 16f, FontWeight.Medium, 0.2f),
    labelSmall = md(family, 11f, 15f, FontWeight.Medium, 0.2f),
)

/** One [materialTypography] role. Fixed sp on both axes, unlike [ui]. */
private fun md(
    family: FontFamily,
    size: Float,
    lineHeight: Float,
    weight: FontWeight,
    tracking: Float,
): TextStyle = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
)

/**
 * A path, a command, a token count, a line:col — monospace inside the Material
 * half, in **the user's buffer face**.
 *
 * Not `FontFamily.Monospace`. That is Android's own mono (Droid Sans Mono on
 * most devices), and drawing a file path in it beside an editor rendering
 * Lilex is the same seam as drawing the agent panel in Roboto — smaller, and
 * therefore easier to ship eleven times without noticing. It was shipped
 * eleven times: `MonoSheet` in WorkflowCard.kt, three sites in OrchBits.kt,
 * six in BuildLogView.kt, two in ChangesScreen.kt and one in CommitSheet.kt
 * all reach for the system face over Material ink. This is what they use
 * instead.
 *
 * It carries `buffer_font_features` too, so a user who turned Lilex's
 * ligatures off in their editor does not get them back in a sheet.
 *
 * 13/18 rather than [Typography.bodyMedium]'s 14/20: a monospace face at the
 * same nominal size reads about a step larger than a proportional one, because
 * every glyph is set on the widest one's advance.
 */
val MonoBody: TextStyle
    @Composable get() = TextStyle(
        fontFamily = LocalBufferFontFamily.current,
        fontFeatureSettings = LocalBufferFontFeatures.current.ifEmpty { null },
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )

/** [MonoBody] at caption size — a path under a row's title, a hash, a count. */
val MonoSmall: TextStyle
    @Composable get() = TextStyle(
        fontFamily = LocalBufferFontFamily.current,
        fontFeatureSettings = LocalBufferFontFeatures.current.ifEmpty { null },
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )

/**
 * OpenType tabular figures — every digit on the same advance.
 *
 * Apply it to every numeric readout that *ticks*: elapsed time, tokens,
 * context %, `+N −N`, `2/4`. Proportional digits are narrower for a `1` than
 * for a `0`, so a seconds counter re-measures its own text once a second and
 * everything to its right shimmies. That is a rendering fault the eye reads
 * before the number.
 *
 * A constant rather than nine literal `"tnum"` strings (ContextGauge.kt has
 * six, PlanSurface.kt two, SessionPicker.kt one), because a typo in one of
 * them is silent — the tag is simply not applied and the row twitches.
 *
 *     Text(style = MaterialTheme.typography.labelMedium.copy(
 *         fontFeatureSettings = TabularNums))
 */
const val TabularNums = "tnum"
