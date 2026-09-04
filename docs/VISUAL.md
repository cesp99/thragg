# The visual system

Thragg looks like two things on purpose. Where it is an editor it looks
like Conquest, because the editor, terminal and diff have to agree with
tree-sitter's syntax colours and a Material palette would fight them. Where it
is an app — the agent, the build loop, setup, settings, every sheet — it looks
like Android, because it is one.

This page is how those two halves are made to agree rather than clash. The
reference for the Material half is `spettro-android`, a native ACP client for
the same agent Seeker bundles, and its `ChatConfigSheet.kt` in particular.

## The hybrid

THE HYBRID, DECIDED.

VERIFIED FACTS THIS SPEC RESTS ON (I re-measured all of these; where the four studies disagreed, the measurement wins):
- Compose BOM 2026.02.01 resolves material3 **1.4.0** (read from the BOM POM). BOM 2026.06.01 also resolves 1.4.0. **The BOM is not the lever.**
- javap on material3-android/1.4.0: `MaterialThemeKt.MaterialExpressiveTheme(ColorScheme, MotionScheme, Shapes, Typography, content)` is PUBLIC. `MaterialTheme.getMotionScheme(...)` is PUBLIC. `SliderDefaults.Track-4EFweAY(SliderState, Modifier, Boolean, SliderColors, drawStopIndicator, drawTick, thumbTrackGapSize, trackInsideCornerSize)` is PUBLIC. `TopAppBar-cJHQLPU(title, subtitle, modifier, navigationIcon, …)` is PUBLIC. `SegmentedButtonKt`, `SliderKt` present.
- INTERNAL in 1.4.0: `MotionScheme.Companion.expressive$material3()`, `SliderDefaults.Track-mnvyFg4$material3` (the `trackCornerSize` overload). ABSENT in 1.4.0: LoadingIndicator, ButtonGroup, SplitButton, ToggleButton, MaterialShapes, WavyProgressIndicator, FloatingToolbar, AppBarRow.
- compose-ui 1.10.4 (current BOM) has `HapticFeedbackType.SegmentTick`.
DECISION: **stay on stock material3 1.4.0. Do not pin 1.5.0-alpha25.** Studies sa-system and sa-chat asserted the alpha pin is a prerequisite; it is not. Everything the owner named — the thinking slider, the model selector, segmented controls, switches — is buildable today. Two stock-M3 substitutions are required and are listed under FOUNDATIONS.

ONE DERIVATION, ONE `remember`, TWO LOCALS.
`ZedTheme` stays the single source of colour truth. A pure function turns it into an M3 `ColorScheme` plus a small non-Material token set, both produced by the SAME `remember(theme)` inside `ThraggTheme`, so the Zed half and the Material half physically cannot disagree by a frame. No second palette is authored anywhere; no dynamic/Material You colour, ever (a wallpaper primary would appear nowhere in the editor — the exact clash decision 1 exists to prevent).

NEW FILE `ui/theme/Contrast.kt` — the solver, ported from spettro-android SpettroColors.kt:118-181, pure, host-testable:
```kotlin
const val TEXT_RATIO = 4.5f
const val MARK_RATIO = 3.0f
private const val BISECTION_STEPS = 12

fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance(); val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

/** [color] with its hue kept and its lightness moved the smallest distance
 *  that clears [minRatio] against [on]. Returns [color] untouched if it already does. */
fun readable(color: Color, on: Color, minRatio: Float = TEXT_RATIO): Color {
    if (contrastRatio(color, on) >= minRatio) return color
    val target = if (on.luminance() > 0.5f) Color.Black else Color.White
    var lo = 0f; var hi = 1f
    repeat(BISECTION_STEPS) {
        val mid = (lo + hi) / 2f
        if (contrastRatio(lerp(color, target, mid), on) >= minRatio) hi = mid else lo = mid
    }
    return lerp(color, target, hi)
}

/** Whichever of [prefer]/[alt] clears the bar on [on]; black or white if neither does. */
fun pickInk(on: Color, prefer: Color, alt: Color, minRatio: Float = TEXT_RATIO): Color
```
WHY THIS IS NOT OPTIONAL — measured across all 11 bundled themes, `text.muted` on `panel.background`: Ayu Light **2.79:1**, One Light 5.96, Gruvbox Light 5.31, Ayu Mirage 4.09. `text.accent` on `editor.background`: Ayu Light **2.84:1**, One Light **3.84:1**. `created` on panel: Ayu Light **2.11:1**, One Light 2.64. `warning` on panel: Ayu Light **1.64:1**. Today `onPrimary = editor.background` (Theme.kt:139) ships a 2.84:1 filled-button label on Ayu Light, and 455 `text.muted` call sites are illegible on it. The rule: **inks in the Material half are solved; inks in the Zed half are drawn raw**, because the Zed half's job is to look like Zed and Zed itself draws them raw. That rule is exactly the hybrid boundary, which is why it is the right rule.

NEW FILE `ui/theme/MaterialBridge.kt`:
```kotlin
@Immutable
class SeekerPalette(val scheme: ColorScheme, val seeker: SeekerColors)

fun ZedTheme.palette(): SeekerPalette
```
BAND A — PINNED, no maths, straight from Zed's own keys. Zed's JSON already contains a real five-rung neutral ladder and it runs the right way in BOTH appearances (measured luminance, One Dark: .025 .034 .034 .045 .052; One Light: .956 .831 .831 .738 .716 — away from the canvas in both, which is exactly M3's convention). So the ladder maps unmodified:
`background←editor.background · surface←panel.background · surfaceVariant←element.background · surfaceContainerLowest←editor.background · surfaceContainerLow←element.background · surfaceContainer←elevated_surface.background · surfaceContainerHigh←element.hover · surfaceContainerHighest←background · outline←border · outlineVariant←border.variant · error←error · inverseSurface←text · inverseOnSurface←editor.background`.
`surfaceBright` / `surfaceDim` = the lighter / darker of {editor.background, background} by `luminance()`, so one expression is right in both appearances.
`scrim = Color.Black.copy(alpha = if (isDark) 0.60f else 0.32f)` (Zed has no scrim key).
**`surfaceTint = Color.Transparent` — mandatory.** Today it is left as `primary`, so Material's tonal elevation washes the accent over every raised Surface. This one line is the single biggest existing bug in the bridge.
LADDER DE-DUPE: in nine of eleven bundled themes `element.background == elevated_surface.background`, so rungs 2 and 3 collide. Walk lowest→highest and, when `abs(lum(a)-lum(b)) < 0.006f`, replace the higher rung with `lerp(it, if (isDark) Color.White else Color.Black, 0.03f)`. Monotone in both appearances; invisible when it does not fire.

BAND B — DERIVED from one seed. Seed = `color("text.accent")`, falling back `players[0].cursor` → `border.focused` → `icon.accent`. Every bundled theme writes `text.accent`; One Dark's cursor is the same #74ade8; the existing bridge already treats it as `primary`; `DERIVED` already routes `debugger.accent` to it.
```
primary            = readable(seed, on = surfaceContainer, TEXT_RATIO)
onPrimary          = pickInk(on = primary, prefer = editorBackground, alt = text)
primaryContainer   = lerp(primary, surfaceContainerHigh, 0.80f)
onPrimaryContainer = readable(primary, on = primaryContainer)
inversePrimary     = lerp(primary, onBackground, 0.35f)
secondary          = readable(color("text.muted"), on = surface)
secondaryContainer = color("element.selected")
onSecondaryContainer = readable(color("text"), on = secondaryContainer)
tertiary           = readable(AgentAccent, on = surface)          // AgentAccent = 0xFFAD7BF9, fixed
tertiaryContainer  = lerp(tertiary, surfaceContainerHigh, 0.86f)
errorContainer     = lerp(error, surfaceContainerHigh, 0.86f)
onError / onErrorContainer / onTertiaryContainer = pickInk / readable
onBackground onSurface = readable(color("text"), on = …)
onSurfaceVariant       = readable(color("text.muted"), on = surface)
```
Derivation is by BLENDING TOWARD THE THEME'S OWN EXTREMES, never by HCT tone steps. There is no public seed→ColorScheme API at any version here (`dynamicTonalPalette` takes a Context; `HctSolver` is `androidx.compose.material3.internal`), and an HCT tone-90 container would be a colour no Zed theme contains. Do not add material-kolor: a third-party dependency plus a THIRD_PARTY.md entry plus a LicenceCatalog.kt row, to produce neutrals that fight Zed's.
`tertiary` is the ONE fixed hue: sub-agents and skills are Spettro's shared vocabulary across the TUI, desktop and mobile, and deriving them from the user's editor theme would break a cross-front-end agreement. This is the argument ConfigChips.kt:412 already makes for mode tints, applied consistently.
Base scheme is `darkColorScheme()` / `lightColorScheme()` chosen by `theme.isDark` — keep Theme.kt:117-119's rule and its comment (previewing a light theme on a dark device must give light scrollbars). Do NOT use `expressiveLightColorScheme()`: there is no dark twin in any version, so it would make the two appearances structurally different.

BAND C — `SeekerColors`, the second local, for what M3 has no role for. Produced by the same call, so it cannot drift (this answers the sa-chat study's objection to a second layer while keeping the sa-system and seek studies' benefit):
```kotlin
@Immutable
class SeekerColors(
    val isDark: Boolean,
    val hairline: Color,          // color("border.variant") — Zed's own hairline, not an 8% wash
    val cardGround: Color,        // lerp(surfaceContainer, worstTint, 0.08f)
    val accentInk: Color, val accentMark: Color,
    val addedInk: Color,  val addedMark: Color,     // from color("created")
    val removedInk: Color, val removedMark: Color,  // from color("deleted")
    val warnInk: Color,   val warnMark: Color,      // from color("warning")
    val agentAccent: Color, val agentInk: Color,    // 0xFFAD7BF9 solved
    val ultraAmber: Color,                          // color("warning") ?: 0xFFF5A524
) {
    fun modeColor(name: String?): Color
    fun modeInk(name: String?): Color = readable(modeColor(name), cardGround)
}
val LocalSeekerColors = staticCompositionLocalOf<SeekerColors> { error("SeekerColors not provided") }
```
Every `*Ink` is solved at TEXT_RATIO against `cardGround`, every `*Mark` at MARK_RATIO. `cardGround` is not the bare canvas: a card is the surface under a 5-7% tint wash, and an ink solved to exactly 4.5:1 on the canvas arrives at ~4.16:1 on the card. `modeColor` adopts spettro-android's COMPLETE table (SpettroColors.kt:69-96) because `spettro.agents.toml` emits manifest colour NAMES, which Seeker's `modeTintArgb` (ConfigChips.kt:412-423) does not handle: blue #A78BFA, green #34D399, cyan #60A5FA, yellow #F59E0B, magenta #C084FC, purple #BD93F9, red #EF4444, then id fallbacks plan→#BD93F9, coding/code→#34D399, chat/ask→#60A5FA, else accent. Keep Seeker's `category != "mode" → null` guard.

RUNTIME PROPAGATION. Unchanged in shape, one line added. `ThraggTheme` already funnels three triggers into one `remember` — `ThemeStore.preview` (the picker's live cursor), `settings.themeSelection`/`themeOverrides`, and `UserThemes.scan` (a FileObserver on `<filesDir>/themes`). Add:
```kotlin
val palette = remember(theme) { theme.palette() }
CompositionLocalProvider(
    LocalZedTheme provides theme,
    LocalSeekerColors provides palette.seeker,
    LocalReduceMotion provides reduceMotion,
    /* … existing font/icon/settings locals … */
    // NOTE: LocalIndication and LocalLayoutDirection are NO LONGER provided here.
) {
    MaterialExpressiveTheme(              // public in 1.4.0; motionScheme omitted so the
        colorScheme = palette.scheme,     // expressive default (internal to name) is taken
        shapes = SeekerShapes,
        typography = remember(uiFontFamily) { materialTypography(uiFontFamily) },
        content = content,
    )
}
```
Cost: ~30 colours plus a handful of 12-step bisections per theme change. The picker's preview walk touches eleven of them — the same order of magnitude as the palette parses `ZedThemes.warm()` already pre-pays.

THE BOUNDARY, EXACTLY. Two composables, one file `ui/theme/Surfaces.kt`. The root is now the MATERIAL half — ripple on, `materialTypography`, no layout-direction pin. The Zed half is entered explicitly:
```kotlin
/** Everything that has to agree with tree-sitter output. Re-provides Zed's
 *  metrics, Zed's no-ripple rule and Zed's LTR pin over the shared scheme. */
@Composable
fun ZedSurface(content: @Composable () -> Unit) {
    val fonts = LocalAppSettings.current.fonts
    val family = LocalUiFontFamily.current
    CompositionLocalProvider(
        LocalIndication provides NoIndication,
        LocalLayoutDirection provides LayoutDirection.Ltr,
    ) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme,
            typography = remember(fonts.uiSize, family) { zedTypography(fonts.uiSize, family) },
            content = content,
        )
    }
}
```
ZED-PAINTED (wrapped in `ZedSurface`, keep `theme.color(...)`, keep `rem()`/`ZedRadius`, keep `NoIndication`) — 499 call sites: `ui/editor/` (94), `ui/git/` (230), `ui/preview/` (60), `ui/search/` (43), `ui/diagnostics/` (28), `ui/terminal/` (24), `ui/media/` (14), `ui/tasks/` (6), plus `ui/shell/changes/DiffScreen.kt` and the editor host inside `ui/shell/code/CodeScreen.kt`.
MATERIAL-PAINTED (read `MaterialTheme.colorScheme` + `LocalSeekerColors`, `MD.*` spacing, ripple on) — 760 call sites across 46 live files: all of `ui/shell/` except the two above, all of `ui/agent/`, all of `ui/common/`.
NoIndication was defended as "the loudest single 'not Zed' tell a Compose port can carry", and that is correct — for the editor. In a Material sheet a row that does not respond to a press is the loudest single "not a real Android app" tell, which is the owner's verdict. Scoping it is the resolution; deleting it in either direction is not.
`LocalLayoutDirection provides Ltr` moves into `ZedSurface` because the reason for it is real and local: the editor draws indent guides, tab borders and focus rails at absolute x in `drawBehind`. The Material half becomes RTL-correct. Chunk owners must grep their files for `drawBehind`/`drawWithContent` before landing.

THE SEAM — a code snippet inside a Material sheet. Today this is wrong twice: `MonoSheet` (WorkflowCard.kt:568-592), OrchBits.kt:642/653/770, BuildLogView.kt (six sites), ChangesScreen.kt:557/662, CommitSheet.kt:139 all draw with `FontFamily.Monospace` — the SYSTEM mono, not the user's buffer face — over Material ink. The rule is **the sheet is Material, the snippet is a Zed island**, expressed as one component:
```kotlin
@Composable
fun ZedCodeBlock(
    text: String, modifier: Modifier = Modifier,
    spans: List<AnnotatedString.Range<SpanStyle>>? = null,
    maxLines: Int = Int.MAX_VALUE, wrap: Boolean = false,
)
```
Fill `LocalZedTheme.current.color("editor.background")`, ink `editor.foreground`, face `LocalBufferFontFamily.current` with `LocalBufferFontFeatures.current`, spans from `theme.spanStyle()`, `softWrap = false` + horizontal scroll, `SelectionContainer`, radius `MD.radiusSm` (8dp) — and a **1dp `MaterialTheme.colorScheme.outlineVariant` border**, because the island's EDGE belongs to the sheet and that is what stops it reading as a hole. `+`/`-` lines inside the island use Zed's `created`/`deleted` raw. Outside the island — a `+24 −6` on a card header, a "3 failed" label — use `LocalSeekerColors.addedInk/removedInk/dangerInk`, solved.

TWO SMALL FIXES TO SHIP WITH THE BRIDGE. Add to `ZedTheme.DERIVED` (ZedTheme.kt:133): `"success" to "created"`, `"info" to "text.accent"` — that collapses the five double-fallback idioms at ShellNavBar.kt:259, GitGraphPane.kt:1000/1095, OrchBits.kt:134. And fix two real bugs: OrchBits.kt:150-157 bakes One Dark's ANSI hexes as a fallback table (under Gruvbox it paints One Dark — read the live `terminal.ansi.*` keys), and GitGraphPane.kt:528-532 bakes a five-lane graph palette when `theme.playerColor(index)` (ZedTheme.kt:41-45) exists for exactly that.

## Foundations

FILE MAP. `ui/theme/Contrast.kt` (new) — solver. `ui/theme/MaterialBridge.kt` (new) — `ZedTheme.palette()`, `SeekerColors`, `LocalSeekerColors`. `ui/theme/Surfaces.kt` (new) — `ZedSurface`, `NoIndication` (moved out of Theme.kt). `ui/theme/Shape.kt` (new) — `object MD`, `SeekerShapes`. `ui/theme/Theme.kt` (edit) — root, now Material-first. `ui/theme/Type.kt` (edit) — `zedTypography` unchanged, `materialTypography` added, `MonoBody`/`MonoSmall`/`TabularNums` added. `ui/theme/Motion.kt` (edit) — `LocalReduceMotion` unchanged, motion tokens added. `ui/theme/Rem.kt`, `Icons.kt`, `TouchTarget.kt`, `Fonts.kt`, `ZedTheme.kt`, `ZedThemes.kt`, `UserThemes.kt`, `ThemeStore.kt` — unchanged except the two `DERIVED` entries.

COLOUR ROLES — see THE HYBRID for the full table. Call-site idiom, both halves:
```kotlin
val colors = LocalSeekerColors.current            // once, at the top
Text(style = …, color = MaterialTheme.colorScheme.onSurfaceVariant)
Icon(tint = colors.accentMark)
```
The 760 Material-half sites invert their polarity. Today every one is `theme.color("key", MaterialTheme.colorScheme.role)` — a Zed read with an M3 fallback that never fires. It becomes a bare `MaterialTheme.colorScheme.role` with no Zed read at all. This is mechanical but NOT sed-able, and there are three traps: `text.muted` is `onSurfaceVariant` on a surface but `onSurface.copy(alpha = 0.6f)` inside a container; `ghost_element.hover` (51 sites) has no colour answer at all under M3 — it is a state layer and it comes back from the restored ripple, so those sites DELETE their background rather than remap it; `element.selected` is `secondaryContainer`. Highest-leverage two lines in the codebase: `mutedIcon` and `accentIcon` (Icons.kt:216-227) are `@Composable get()` properties reading `LocalZedTheme`, and they are the default tint of `RowChevron`, `DisclosureMark`, `ChipCaret` and `SelectionMark` — i.e. of nearly every icon in every sheet and row. Redefine them to read `MaterialTheme.colorScheme.onSurfaceVariant` / `LocalSeekerColors.current.accentMark`, and give `ZedSurface` a `LocalIconTint` override that restores the Zed reads for the editor half. Changing those two definitions retints most of the Material half in one commit.

THE `secondaryContainer` TRAP — read this before dropping in any stock M3 component. `element.selected` maps to `secondaryContainer`, and that mapping is right for what the role *is* (a fill) and wrong for what M3 *uses it for* (a state). Material spends the role on selection: `NavigationBarTokens.ItemActiveIndicatorColor`, `SliderTokens.InactiveTrackColor` (plus the tick cross-wiring in `defaultSliderColors`, which paints each half's ticks in the other half's track colour), `ProgressIndicatorTokens.TrackColor`, `FilterChipTokens.FlatSelectedContainerColor`, `InputChipTokens.SelectedContainerColor`, `OutlinedSegmentedButtonTokens.SelectedContainerColor`, `NavigationDrawerTokens.ActiveIndicatorColor`, `FilledTonalButtonTokens`/`FilledTonalIconButtonTokens.ContainerColor`. In a stock palette that role is a pale tint of the seed, far from every surface. Here it is a real fill measured a step BEYOND `surfaceContainerHighest` on all eleven bundled themes in both appearances, at 1.31–1.57:1 against `surface` — so a stock default draws the loudest raised panel in the app where a *state* was wanted, and no theme rescues it. THE RULE: any component in the list above names its colour parameter at the call site. A selection is `primary.copy(alpha = 0.16f)`; an unspent track is `surfaceContainerHighest`; in the Zed half it is a Zed key (`element.background`) rather than an M3 role. This shipped twice — `ShellNavBar`'s pill and `SettingsScreen`'s `SliderRow` track — and both were caught by looking at the phone, so it is now two tests: `MaterialBridgeTest.secondaryContainer is a sixth fill rung, not a state, on every theme` measures the role against the ladder, and `StockDefaultsTest` fails the build when a file imports one of those components without writing the override.

TYPE — two scales, ONE FACE. The face for both halves is the user's `ui_font_family`, defaulting to bundled **IBM Plex Sans** (`BundledFonts.ui`). Do NOT take spettro-android's `FontFamily.Default`: a Roboto Agent panel two taps from a Plex editor is a visible seam on a 400dp phone. What transfers from spettro-android is the SLOT DISCIPLINE, not the face.
`zedTypography(uiFontSize, family)` — unchanged, owned by `ZedSurface`. Ratios 1.0 / 0.875 / 0.75 / 0.625 × the live `ui_font_size`, `lineHeight = size × φ (1.618034)`, `letterSpacing = 0.sp` everywhere. It must stay ratio-based because `ui_font_size` is a real setting on that half.
`materialTypography(family)` — NEW, fixed sp, owned by the root. A Material sheet must not resize because someone changed their editor font. All fifteen roles are defined; today six leak Material's Roboto-metric defaults at 32-57sp, which is a live bug (`headlineSmall` and `displaySmall` are reachable from `AlertDialog` and `LargeTopAppBar`).
```
displayLarge   48/54 SemiBold  -0.4sp     headlineLarge  32/38 SemiBold -0.3sp
displayMedium  40/46 SemiBold  -0.4sp     headlineMedium 28/34 SemiBold -0.3sp
displaySmall   34/40 SemiBold  -0.3sp     headlineSmall  24/30 SemiBold -0.2sp
titleLarge     20/26 SemiBold  -0.1sp     bodyLarge      15/22 Normal    0.1sp
titleMedium    16/22 SemiBold   0sp       bodyMedium     14/20 Normal    0.1sp
titleSmall     14/20 Medium     0sp       bodySmall      12/16 Normal    0.1sp
labelLarge     13/18 Medium     0.1sp     labelMedium    12/16 Medium    0.2sp
labelSmall     11/15 Medium     0.2sp
```
`bodyMedium` 14/20 is the transcript workhorse. Seeker's Agent transcript is today built mostly from `labelSmall` (10sp) and `labelMedium` (12sp) off the Zed chrome scale — that is most of why rows read cramped rather than composed.
Outside `Typography`, in Type.kt:
```kotlin
val MonoBody  @Composable get() = TextStyle(fontFamily = LocalBufferFontFamily.current,
    fontFeatureSettings = LocalBufferFontFeatures.current, fontSize = 13.sp, lineHeight = 18.sp)
val MonoSmall @Composable get() = /* same, 11sp / 15sp */
const val TabularNums = "tnum"
```
`MonoBody`/`MonoSmall` use the BUFFER face (Lilex by default), not `FontFamily.Monospace`. That is the fix for the eleven `FontFamily.Monospace` sites listed under the seam. `TabularNums` replaces nine literal `"tnum"` strings (ContextGauge.kt ×6, PlanSurface.kt ×2, SessionPicker.kt ×1) and gets applied to every numeric readout that ticks — elapsed, tokens, context %, `+N −N`, `2/4` — so figures stop shimmying.

SHAPE + SPACING — `ui/theme/Shape.kt`. Two scales with clear jurisdictions; `rem()`/`ZedRadius` keep the editor and are never used in the Material half.
```kotlin
object MD {                      // the Material half's 4dp grid — fixed dp, never rem
    val space1 = 4.dp;  val space2 = 8.dp;  val space3 = 12.dp
    val space4 = 16.dp; val space6 = 24.dp; val space8 = 32.dp
    val radiusXs = 4.dp; val radiusSm = 8.dp; val radiusMd = 12.dp
    val radiusLg = 16.dp; val radiusXl = 24.dp
    val pill = 20.dp; val hairline = 1.dp
    val rowMin = 48.dp; val barHeight = 56.dp; val stripHeight = 36.dp
}
val SeekerShapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp))
```
RHYTHM: **16dp is the screen gutter, always** — transcript, sheet, composer row, status strip. 24dp is the bottom pad on scrolling content so the last row clears the nav bar or sheet edge. 16dp between sections, 8dp between transcript rows, 8dp between controls in a row, 4dp for tight pairs, 2dp between a label and its description. Card inner padding 12dp × 10dp for option rows, 8dp × 6dp for a plain tool row (the densest thing in the app). Radii by role: 8dp code blocks / thumbnails / tool rows / selectable option cards, 12dp cards, 16dp bubbles and sheets' inner cards, 20dp pills (composer field, every search field, chips), 24dp the sheet's own top corners.
Six off-grid values are permitted and named, because they are right: 6dp as the icon↔label gap wherever a 12-14dp glyph sits beside 11-12sp text; 10dp vertical on option rows; 9dp vertical inside the composer pill; 3dp × 10dp on the level pill; 2dp × 7dp on a "Recommended" tag; 5dp × 8dp on a collapsed tool row.
TOUCH TARGETS: keep `Modifier.touchTarget()` = `minimumInteractiveComponentSize()` (TouchTarget.kt) and apply it to the new Material controls too. Do NOT adopt spettro-android's 40dp icon-button boxes — Seeker's grows the TARGET to 48dp around a 22dp drawn glyph without changing a pixel of what is drawn, which meets WCAG 2.5.8 where 40dp does not. `IconSize` (Icons.kt) is unchanged: Nav 24, Action 22, Inline 18, Marker 14, Hero 40.

ELEVATION — **zero, everywhere, in both halves.** `surfaceTint = Color.Transparent` in the scheme; `shadowElevation = 0.dp, tonalElevation = 0.dp` on every `Surface`, `Card`, `ModalBottomSheet`, `DropdownMenu` and `AlertDialog`. Depth is carried by exactly two devices: a fill step on the surfaceContainer ladder, and one 1dp `outlineVariant` hairline. Meaning is carried by a third: a tint wash at 5-16% (`agentAccent` 7% dark / 5% light on a sub-agent card, mode chip 14%, selection 16%, "Recommended" 16%). Selection is a BORDER change, not a fill change: `BorderStroke(1.dp, outlineVariant)` becomes `BorderStroke(1.5.dp, primary.copy(alpha = 0.7f))`. This is simultaneously the most Zed-compatible and the most modern-Android choice, which makes it the one place the hybrid needs no seam at all. Shadows and tonal overlays are what make Compose look like a floating desktop panel.
`TopAppBar` colours: `containerColor = surface`, `scrolledContainerColor = surface` — nothing tints on scroll; the seam under it is a `HairlineDivider`.
Sheets: `containerColor = surfaceContainer` for a sheet whose body is a bare list; `containerColor = background` for a sheet whose body is cards, so the cards read. (spettro-android splits exactly this way: ChatConfigSheet.kt:117 vs ChatComposer.kt:369.)

MOTION — `ui/theme/Motion.kt`. `LocalReduceMotion` + `rememberReduceMotion` + `Motion.isReduced` + `revealItem`/`revealBy` are unchanged and are the part of Seeker that is already better than both references; every token below routes through them.
```kotlin
/** The one spring. Every expand/collapse, every chevron, every animateContentSize. */
@Composable fun <T> seekerSpring(): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else spring(stiffness = Spring.StiffnessMediumLow)

/** Colour and alpha. MaterialTheme.motionScheme is public in 1.4.0. */
@Composable fun <T> effectSpec(): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else MaterialTheme.motionScheme.defaultEffectsSpec()

/** Size and position under a finger. */
@Composable fun <T> spatialSpec(): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else MaterialTheme.motionScheme.fastSpatialSpec()

@Composable fun Modifier.animateSize(): Modifier = animateContentSize(seekerSpring<IntSize>())
```
NAMED DURATIONS, each with its reason: **180ms in / 240ms out** on the live-run strip and any band that appears and disappears (`fadeIn + expandVertically` / `fadeOut + shrinkVertically`) — slower out than in so a finishing run settles rather than blinking away, reinforced by a **4000ms hold** during which a finished run is re-read for its FINAL counts. **120ms** fade for an assistant markdown block (existing, keep — Spettro's stream has draft-reset semantics ACP cannot express). **150-250ms** for every colour/alpha tween; nothing outside that band. **400ms / 8 quantised frames** for the braille spinner (already correct at OrchBits.kt:303). **1Hz** for the run ticker — the spinner carries the motion; re-laying out a text run at 60fps to advance a seconds counter is wasted work. **1600ms** then revert, for a copy confirmation (replaces any snackbar). **±width/3 horizontal slide + fade** for an in-sheet drill push, direction chosen by whether a page is opening or closing. **Vertical slide in the direction of travel** for the thinking level pill (`rising = target > initial` → enter from +h, else −h): a label that moves up when the value goes up carries a large share of the "feels designed" impression for about eight lines of code.
PRESS, HAPTICS AND TOASTS — the three things that answer the finger rather than the state. **Press** is `pressScale` (see the component library): 97% on the frame of the press, critically damped back, on every control drawn as an object and on nothing drawn as a row. **Haptics** fire on the two acts that COMMIT and on nothing else: `HapticFeedbackType.Confirm` when a message leaves the composer and when a build starts. Not on Stop (a cancel is not a success), not on a nav-bar tap, not on a keystroke — feedback that fires on everything trains the hand to ignore all of it. The level slider's `SegmentTick` per detent is the one other haptic and it is a different kind: a texture under a drag, not a confirmation. **Toasts** enter the way they leave — up from the bottom edge the stack sits on, `fadeIn + slideInVertically(height/2)` over BAND_IN — and leave by `LazyColumn.animateItem`'s BAND_OUT fade with the stack settling into the gap on the size spring, so a message never vanishes between two frames. A toast is SWIPED AWAY sideways: 1:1 with the finger, thinning as it travels, and on release the resting place is PROJECTED from the velocity (`(v/1000)·0.998/0.002`, Apple's scroll-deceleration form) before it is compared with half the width — so a quick flick that lets go 40px in dismisses, and a long drag thrown back home does not. The settle is damping 0.8: the card was thrown, and a little overshoot is what makes the release the same object the finger was holding. `dismissesToast()` is a pure function with a host test.
MUST RESPECT REDUCE-MOTION: every item above; the press scale (snaps, does not vanish); the toast entrance and exit; the swipe's SETTLE but never the swipe's TRACKING; the transcript's follow-the-tail scroll (already, via `revealItem`); chevron rotation; card expand/collapse; the status strip's `animateContentSize`; the spinner (stands still rather than disappearing — it still marks "running"); the slider's *fill colour* animation.
MUST NOT: the slider thumb's position while dragged (a drag that stopped following the finger is a bug, not an accommodation); IME insets; scroll physics; the slider handle's morph is decorative and DOES respect it.
NOTHING ANIMATES IN SCROLLBACK. No shimmer sweep over prose, no gradient crossing a phrase. spettro-android restricts its `GlareText` to the single active orchestrator and the streaming "Thinking…" label for a written reason (ActivationHighlight.kt:51-55): a gradient crossing a sentence re-shades each word independently, so at any instant one half of a matched phrase is brighter than the other, which reads as a rendering fault — and in scrollback it never stops. Take the `isActive: Boolean` PARAMETER pattern (toggle liveness without swapping composables, so layout is never lost) and skip the effect.

STOCK-M3 SUBSTITUTIONS (things 1.4.0 cannot do, and what to build instead):
1. `SliderDefaults.Track(trackCornerSize = 12.dp, …)` is internal. Use the public `Track(sliderState, modifier, enabled, colors, drawStopIndicator = null, drawTick = { off, c -> drawCircle(c, 2.5.dp.toPx(), off) }, thumbTrackGapSize = 0.dp, trackInsideCornerSize = 6.dp)`. The only loss is the outer corner radius — the track keeps its default full-pill ends instead of a 12dp square-ish end; the inside corners and the per-level dots are there. **THE GAP IS 0, and that was a correction on the device, not a preference.** `drawTrack` measures `thumbTrackGapSize` from the handle's CENTRE (`thumbWidth / 2 + gap`), so at a value of zero — where the active track has no width and is not drawn at all — the *inactive* track began 11dp to the right of a handle sitting at x = 0: the handle hung off the left cap of its own scale with the first tick stranded on bare sheet between the two, and the top of the scale did it mirrored. Widening the handle cannot fix it, because the gap grows by exactly what the handle grows by. `trackInsideCornerSize` does the separating instead — it rounds both halves toward the handle whether or not a gap is set, so the track pinches AT the handle, and a pinch cannot fall off the end of a track.
2. `MotionScheme.expressive()` cannot be named. Call `MaterialExpressiveTheme(colorScheme = …, shapes = …, typography = …, content = …)` and OMIT the `motionScheme` argument; its default is the expressive scheme, and `MaterialTheme.motionScheme` reads it back publicly.
3. No `LoadingIndicator`. Promote Seeker's existing braille spinner (OrchBits.kt:303) into `ui/components/SeekerSpinner.kt` — it already has the right cadence and a reduce-motion branch.
4. No `MaterialShapes`, `ButtonGroup`, `SplitButton`, `ToggleButton`, `FloatingToolbar`, `WavyProgressIndicator`, `AppBarRow`. Nothing in this spec uses them. The config chip row is a `LazyRow` of `SeekerChip`, not a `ButtonGroup`.
5. `TopAppBar(title, subtitle, …)` IS public in 1.4.0 (`TopAppBar-cJHQLPU`), behind `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`. Use it; do not hand-roll the 48dp `AgentBar`.
6. `HapticFeedbackType.SegmentTick` is present in compose-ui 1.10.4. Use it, not `SegmentFrequentTick`.

## The Agent destination

### Agent — the screen at rest (400 × 890dp)

```
┌──────────────────────────────────────────────────────────────┐
│  Add a mint_nft instruction                     ⌄   ＋   ⋮   │ 56  TopAppBar
│  Spettro · seeker-ide                                        │     subtitle 12sp
├──────────────────────────────────────────────────────────────┤ 1dp outlineVariant
│ ◐ 1m04s · 12.3k     ☑ 2/4 ▬▬▭▭      210k tok   37%           │ 36  AgentStatusStrip
├──────────────────────────────────────────────────────────────┤ 1dp
│                                                              │
│                     ┌──────────────────────────────────┐     │
│                     │ Add a mint_nft instruction and   │     │ user bubble, 80%
│                     │ wire it into lib.rs              │     │ secondaryContainer r16
│                     └──────────────────────────────────┘     │
│                                                              │
│  ⌄ ⌁ Thought for 8s                                          │ reasoning, collapsed
│                                                              │
│  I'll add the instruction. First, the current entrypoint.    │ assistant prose
│  bodyMedium 14/20, full width, no bubble, no avatar          │ full column
│                                                              │
│  ▤ Read    programs/src/lib.rs                       ·     › │ tool rows, transparent
│  ✎ Edit    programs/src/lib.rs      +24 −6           ·     › │ +green −red, tabular
│  ⌗ Run     cargo build-sbf                           ◐     › │ spinner = in flight
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ ⬓ mint-nft scaffold        ▸running◂               ⌄   │  │ WorkflowRunCard
│  │ ▮▮▮▯▯▯▯▯  4/8   3 running · 1 done            1m 12s   │  │ agentAccent 7% wash
│  │ ▪▪▪▫▫▫▫▫                                              │  │ cell strip, in header
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
├──────────────────────────────────────────────────────────────┤
│  ⬔ Spettro is waiting on you — 1 request          Answer     │ 40 AttentionBar (only
├──────────────────────────────────────────────────────────────┤    when needsUser > 0)
│  ⚙ coding · Sonnet 4.6 · Ask · high                      ⌃⌄  │ 24 ConfigSummaryRow
│  ⊕   ╭────────────────────────────────────────────╮    ⬆    │ 56 composer row
│      │ Message Spettro…                           │         │    pill r20
│      ╰────────────────────────────────────────────╯         │
├──────────────────────────────────────────────────────────────┤
│       ▤ Code           ✦ Agent           ⚒ Build             │ 56 NavigationBar
└──────────────────────────────────────────────────────────────┘
```

**Behaviour.** Scaffold(containerColor = colorScheme.background). Exactly THREE fixed bands survive: TopAppBar, AgentStatusStrip, composer.

THE BAR NAMES THE THREAD, not the project: `barTitle(threadTitle, projectName)` is the thread's own name, falling back to the project when the thread has none and to "No project" last. The project the title gave up moves into the subtitle — `barSubtitle(agentName, projectName, threadTitle)` → `Spettro · seeker-ide` — and is dropped there when the title is already showing it, so no word is printed twice. THE MODE IS NOT IN THE BAR. It is said by the ModeChip in the strip (which carries the mode's identity colour and sits with the run readouts) and by ConfigSummaryRow (which *is* the control that changes it, one tap from the composer); a subtitle is a caption on a title that is not about the mode, and it never changes with the run.

THE STRIP DRAWS ONLY WHEN THERE IS RUN STATE — `stripReports(busy, hasPlan, hasUsage)`. The mode alone does not hold it open: a band standing from the first frame of every thread with one pill in it costs the transcript 37dp for a fact the composer states permanently 20dp above the keyboard. Empty → gone; present → carrying everything the snapshot has. AttentionBar is a fourth that exists only while session.needsUser > 0 and is a link, not a form. Transcript is the only scrolling region: LazyColumn, contentPadding 16dp all round, verticalArrangement spacedBy(8.dp), items keyed by row.id, wrapped in Modifier.imePadding().consumeWindowInsets(padding). Keep every existing behaviour: the LATCHED follow-the-tail (followsTail(previous, dragging, atTail) — turned off only by the reader's own drag ending away from the tail, back on the moment they return), the retap-scrolls-to-newest contract, the `expanded` map held OUTSIDE the LazyColumn so a card the user opened is not forgotten when it scrolls off, `showsSecondaryBands(imeVisible)`, and the nav bar hiding under the IME. AgentStatusStrip is Modifier.animateSize() so it grows into the plan list without a jump. Ordering of the strip, left to right: RunTicker (busy) or ModeChip (idle) · PlanProgress · Spacer(weight 1f) · UsageReadout. Tapping PlanProgress unfolds the plan inline in the strip; tapping UsageReadout opens the context-gauge sheet.

**Why this is better.** Today AgentScreen stacks up to SEVEN pinned bands between the transcript and the composer — ReviewBar, SpettroSetupBanner, ContextWarningRow, two ConfigNotices, PlanStrip (32dp), LiveRunPeek, ConfigChips (36dp) — and only two of them collapse with the IME. On an 890dp column with the keyboard up that leaves the transcript a slot. The new strip merges four surfaces into one 36dp line: the app-bar mode text, the app-bar context ring, the 32dp PlanStrip, and the elapsed/token readout that currently lives at the SCROLLING transcript tail where it disappears the moment you read anything. Elapsed time that scrolls away is not status. The review count, the setup banner and both notices become, respectively: an action in the ⋮ overflow badged with a count; a card in the empty state; and an inline notice card in the transcript at the point it happened — each of them in the one place where it is still true, instead of a permanent band. The bar itself becomes a real M3 TopAppBar with the public title+subtitle overload, so it inherits scroll behaviour, insets and TalkBack ordering instead of being a hand-rolled 48dp Row on status_bar.background with two caret-suffixed text buttons.

### Agent — the composer, three states

```
IDLE
├──────────────────────────────────────────────────────────────┤ hairline seam
│  ⚙ coding · Sonnet 4.6 · Ask · high                      ⌃⌄  │ 24dp, whole row tappable
│  ⊕   ╭────────────────────────────────────────────╮    ⬆    │ ⊕ 22dp accent, 48 target
│      │ Message Spettro…                           │         │ pill r20, 1dp hairline
│      ╰────────────────────────────────────────────╯         │ 40dp circle, primary@35%

FOCUSED, with mentions and an attachment
├──────────────────────────────────────────────────────────────┤
│  ⟨ lib.rs ⨯ ⟩ ⟨ Anchor.toml ⨯ ⟩ ⟨ ▦ shot.png ⨯ ⟩  →         │ 32dp LazyRow, scrolls
│  ⚙ coding · Sonnet 4.6 · Ask · high                      ⌃⌄  │
│  ⊕   ╭────────────────────────────────────────────╮    ⬆    │ border → primary@50%
│      │ wire @lib.rs into the new instruction▏     │         │ cursorBrush = primary
│      ╰────────────────────────────────────────────╯         │ ⬆ solid primary

AGENT RUNNING (steerable)
├──────────────────────────────────────────────────────────────┤
│  ⚙ coding · Sonnet 4.6 · Ask · high                      ⌃⌄  │
│  ⊕   ╭──────────────────────────────────────╮   ◼    ⬆     │ ◼ stop, error fill
│      │ also add a test for it▏              │              │ ⬆ steer, primary fill
│      ╰──────────────────────────────────────╯              │
│      Steering — the agent takes it at its next step.        │ 11sp onSurfaceVariant

SLASH PALETTE (was 5 inline rows; now a sheet)
╭──────────────────────────────────────────────────────────────╮
│                        ▁▁▁▁▁▁                                │
│  Commands                                                    │
│  ╭──────────────────────────────────────────────────────╮    │
│  │ ⌕ Find a command                                     │    │
│  ╰──────────────────────────────────────────────────────╯    │
│  /compact       Summarise the thread and free context        │
│  /review        Read the working tree and report             │
╰──────────────────────────────────────────────────────────────╯
```

**Behaviour.** Column(background = surfaceContainer) → HairlineDivider → optional attachment/mention LazyRow → ConfigSummaryRow → input Row(Alignment.Bottom, 8dp gaps, 16dp gutter). The field stays a BasicTextField (M3's TextField brings label/indicator chrome that cannot make a pill): maxLines 6, bodyLarge 15/22, heightIn(min = 40.dp), clip(RoundedCornerShape(20.dp)), background(surfaceContainerHigh), border(1.dp, animateColorAsState(focused ? primary@50% : outlineVariant, effectSpec())), cursorBrush = SolidColor(primary), padding 12 × 9, visualTransformation = the existing activationHighlight. ConfigSummaryRow: a 14dp Tune glyph in accentMark + the summary at labelLarge in accentInk (maxLines 1, ellipsis) + a 12dp UnfoldMore at onSurfaceVariant@60%, vertical padding 2dp, whole row clickable → the config sheet. UnfoldMore is not in the pinned Lucide snapshot, and the stand-in has to MEAN "change": `arrow-up-from-line` (`ic_ui_expand_up`) reads as *raise* — which is what it means at its other call sites — so the pair is composed instead, `ic_ui_chevron_up` and `ic_ui_chevron_down` in an 18dp box (each chevron's ink is the middle quarter of its 24-unit viewport, so one-and-a-half boxes of overlap puts the two marks 6dp apart), decorative, the row's own semantics naming the whole thing. KEEP UNCHANGED, all of it protocol-correct: SendMode.Send/Steer/Queue with its three labels, the SEPARATE stop control, the long-press SendOptionsSheet, the steer note, the Ctrl/Shift-Enter key handling.

**Why this is better.** Four measurable wins. (1) The field today is Modifier.clip(RoundedCornerShape(10.dp)).background(editor.background) with NO border and NO focus state, and the send control is the WORD "Send" in a transparent 8dp box. That is the single largest visual gap the owner is pointing at; a 20dp pill whose hairline warms to the accent and a 40dp filled circle is the same three controls, correctly dressed. (2) Mentions and attachments stack VERTICALLY today, one full-width chip per row — three mentions is three rows shoving the composer up the screen. One horizontally-scrolling 32dp strip is a fixed cost regardless of count, and attachments become 32dp thumbnails instead of filenames. (3) The slash palette renders up to five 48dp rows INLINE above the field, so the composer jumps as you type; moving it into a searchable SheetScaffold makes it a fixed-height surface with a filter. (4) ConfigSummaryRow buys back the whole 36dp ConfigChips band — the same five selectors in ~20dp of height instead of 36dp of horizontally-scrolling pills. Ultra's four-state amber gating is NOT lost: it is real protocol behaviour, so when Ultra is armed or locked the summary row gains a trailing ultraAmber dot and the config sheet's Ultra section carries the full explanation. WHAT I AM DEFENDING AGAINST spettro-android: it turns Send into Stop mid-turn. Seeker must not. AgentComposer.kt:425 already argues it — steering and stopping are opposite acts, and a Send that turns into a Stop makes a steer look like a cancel. Two controls, restyled, both present.

### Agent — the config sheet (root page): the thinking slider and every other selector on ONE page

```
╭──────────────────────────────────────────────────────────────╮
│                        ▁▁▁▁▁▁                                │ SheetScaffold handle
│  Agent settings                                              │ titleMedium 16 SemiBold
│                                                              │
│  ⚙ MODE                                                      │ SectionHeader 12/0.8sp
│  ┌──────────┬──────────────┬──────────────────────────────┐  │
│  │   ask    │    coding    │            plan              │  │ SingleChoiceSegmented
│  └──────────┴──────────────┴──────────────────────────────┘  │ active: primary@16%
│   Edits files and runs commands as it goes.                  │ ACTIVE description
│                                                              │
│  ⌸ MODEL                                                     │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  Claude Sonnet 4.6                                  ›  │  │ drill entry, SeekerCard
│  └────────────────────────────────────────────────────────┘  │
│   Anthropic · balanced for long coding turns                 │
│                                                              │
│  ⛊ PERMISSION                                                │
│  ┌───────────────┬──────────────────┬───────────────────┐    │
│  │   Ask each    │  Ask once each   │      YOLO         │    │
│  └───────────────┴──────────────────┴───────────────────┘    │
│   Never asks; runs everything.                               │
│                                                              │
│  ⌁ THINKING                                    ╭────────╮   │ level pill slides in
│                                                │  high  │   │ the direction of travel
│                                                ╰────────╯   │
│    ●━━━━━━━●━━━━━━━●━━━━━━━█━━━━━━━○━━━━━━━○                 │ Track: no thumb gap,
│                                                              │ inside corner 6dp,
│    off                                          ultra        │ tick = 2.5dp circle,
│                                                              │ handle onSurface
│    Long chains of reasoning before each edit. Slower.        │ crossfades per level
│                                                              │
│  ⚡ ULTRA                                              ( ●)  │ stock M3 Switch
│  ┌────────────────────────────────────────────────────────┐  │ NoticeCard(Warn) when
│  │ ⚠ Ultra requires the Restricted or YOLO permission     │  │ Ultra is LOCKED or
│  │   level — change Permission first                      │  │ SUSPENDED; a plain
│  └────────────────────────────────────────────────────────┘  │ bodySmall otherwise
╰──────────────────────────────────────────────────────────────╯
```

**Behaviour.** ONE page, one LazyColumn (contentPadding start/end 16dp, bottom 24dp, spacedBy 16dp). Order fixed by categoryRank: mode 0, model 1, permission 2, thinking/thought_level 3, ultra 4, rest 5 — Seeker already has this as `chipOrder`, and the icons as `chipIcon`. The load-bearing eight lines are `selectStyle(option, kind)`: `isThinking && !grouped && count >= 3 -> Slider; grouped || count > 6 -> Drill; count in 2..4 -> Segmented; else -> Rows`, and `Kind.Bool -> Switch`. THE SLIDER, exactly: discrete M3 Slider, valueRange 0f..lastIndex (index space), steps = (size − 2).coerceAtLeast(0). The FILL'S SATURATION ENCODES THE LEVEL — `saturated(primary, f)` converts to HSV and scales S to `0.08f + 0.92f * f`, so the bottom of the scale is near-grey ("off") and the top is the full accent, animated on effectSpec(). Tick colour follows the FILL, not the theme: `if (levelColor.luminance() > 0.45f) Black@55% else White@85%`, because ticks sit on top of the fill. `HapticFeedbackType.SegmentTick` once per detent crossed while dragging, guarded by a `tickedIndex` so a slow drag does not buzz twice. Handle morphs under the finger: a 6 × 52dp box whose inner spacer animates 5dp→3dp wide and 40dp→52dp tall on spatialSpec() while dragged||pressed, so the level under the thumb stays visible. THE HANDLE IS `onSurface`, NOT THE FILL — as the fill colour it was a stripe of accent on a bar of the same accent at the top of the ramp, distinguishable only by being taller; it crosses both halves of the track and stands 12dp proud of it at each end, so the one ink solved against every rung is the only one that works at every value. Scale-end labels under the track (`choices.first().name` / `choices.last().name`, labelSmall, onSurfaceVariant@70%) so the fill has something to be measured against — and the end the value is currently sitting on FADES OUT, because the pill rides over the handle and would otherwise print "off" twice, once in the pill and once directly beneath it; the pill must always carry something the scale does not. The label keeps its space (alpha only), so the row never reflows as the value moves. `Modifier.semantics { stateDescription = choices[activeIndex].name }`. COMMIT ON RELEASE: onValueChange moves local dragPosition and ticks haptics only; onValueChangeFinished snaps and fires onSelect ONLY IF THE VALUE CHANGED — the same argument SettingsScreen.kt:403's SliderRow already makes about not rewriting settings.json sixty times a second, applied to session/set_config_option over ACP. OPTIMISTIC OVERLAY: every section takes `pending: ConfigValue?` and `currentValue()` prefers it over `kind.current`, so the selection follows the tap while the host confirms.

**Why this is better.** This is the owner's verdict, answered directly. Today `AgentConfigSheet.kt` is 288 lines of the IDENTICAL 56dp ConfigRow(icon, name, value, chevron) for mode, model, permission, thinking and Ultra alike, each opening a SECOND ModalBottomSheet nested inside the first (sheet-over-sheet), which renders every select as the same radio list of 32dp SelectionMark rows. Verified by grep: there is not one Slider, not one SegmentedButton and not one Switch anywhere in Seeker's agent UI. Four consequences the new sheet fixes at once. (1) Thinking is an ORDERED INTENSITY SCALE and a radio list hides that; a slider whose fill desaturates toward "off" lets you read how hard the agent will think before reading any label. (2) Mode and permission are three flat choices each — exactly what a segmented row is for — and because segments have no room for descriptions the ACTIVE choice's description prints beneath, which is what stops "Restricted" from telling nobody what it restricts. (3) Everything is one tap shallower: five selectors on one page, and only the 30-model list drills. (4) The radio no longer waits on a full config_option_update round trip before it moves. And it is all stock 1.4.0: I verified `SliderDefaults.Track-4EFweAY` with drawTick/thumbTrackGapSize/trackInsideCornerSize is public. The only casualty is `trackCornerSize` — the track keeps default pill ends. Nobody will notice; the gap, the inside corner and the dots are what read as Expressive.

### Agent — the model selector (drill page, pushed inside the same sheet)

```
╭──────────────────────────────────────────────────────────────╮
│                        ▁▁▁▁▁▁                                │
│  ←   Model                                                   │ titleMedium SemiBold
│  ╭────────────────────────────────────────────────────────╮  │
│  │ ⌕  Search models…                                   ⨯  │  │ same pill as composer
│  ╰────────────────────────────────────────────────────────╯  │ border → primary@50%
│                                                              │
│  ANTHROPIC                                                   │ labelSmall, @70%
│  Claude Sonnet 4.6                                        ✓  │ 18dp Check, primary
│  Balanced for long coding turns                              │ bodySmall
│  ────────────────────────────────────────────────────────    │ HairlineDivider
│  Claude Opus 4.6                                             │
│  Deepest reasoning; slowest and dearest                      │
│  ────────────────────────────────────────────────────────    │
│                                                              │
│  OPENAI                                                      │ 16dp gap above group
│  GPT-5                                                       │ (0 for the first)
│  ────────────────────────────────────────────────────────    │
│  GPT-5 mini                                                  │
╰──────────────────────────────────────────────────────────────╯
```

**Behaviour.** AnimatedContent inside the sheet keyed on the drilled option id (rememberSaveable, so it survives process death), sliding ±width/3 with fade, direction chosen by whether a page is opening or closing. SEARCH SEMANTICS, which are the whole point: a group whose NAME matches keeps ALL its options (typing "anthropic" lists that provider's models); otherwise an option matches on its name, its wire value, or its description; no matches prints `Nothing matches "query".` at bodyMedium onSurfaceVariant, padding 16 × 24. Rows are 4dp × 10dp, name at bodyMedium onSurface over description at bodySmall onSurfaceVariant, a `HairlineDivider` after each, keys `"$groupIndex-${it.value}"`. Keep Seeker's two existing wins from ConfigSheets.kt: the list opens SCROLLED TO THE CURRENT VALUE (`currentRowIndex()`, via `revealItem` so it respects reduce-motion), and provider group headers.

**Why this is better.** Seeker's model list already has sticky provider headers and scroll-to-current, which is good work — but with thirty-odd models across providers there is NO FILTER FIELD AT ALL, which is the difference between usable and unusable at that length. The provider-name match matters more than it sounds: users think "which Anthropic model am I on", not "which model contains the substring I am typing". Drilling INSIDE the sheet rather than opening a second sheet also removes the sheet-over-sheet stack, which on a 400dp phone currently means two scrims, two drag handles and an ambiguous back gesture — and Seeker's ordered back handler (SheetScaffold registers each sheet with `state.sheetOpened`) then has to unwind two levels for what the user experiences as one screen.

### Agent — a tool-call row, collapsed and expanded

```
COLLAPSED (background fully transparent)
│  ▤ Read    programs/src/lib.rs                       ·     › │ 8 × 6dp, 16dp icon
│  ✎ Edit    programs/src/lib.rs      +24 −6           ·     › │ +24 addedInk / −6 removedInk
│  ⌗ Run     cargo build-sbf                           ◐     › │ 12dp spinner, accentMark
│  ⌕ Search  "mint_nft" in programs/                   ·     › │
│  ⤫ Run     cargo test                                ✕     › │ removedInk, 1dp error
│    Compilation failed: 3 errors                              │   border round the row
│  ⬔ Ask     Overwrite Anchor.toml?      Waiting — tap to answer│ primary border

EXPANDED (raised fill fades in)
│  ✎ Edit    programs/src/lib.rs      +24 −6           ·     ⌄ │
│      ⌸ programs/src/lib.rs                    +24 −6         │ 12dp glyph + MonoSmall
│      ┌────────────────────────────────────────────────────┐  │
│      │ − pub fn initialize(ctx: Context<Init>) -> Result  │  │ ZedCodeBlock island:
│      │ + pub fn mint_nft(ctx: Context<MintNft>, uri: Str  │  │ editor.background fill,
│      │ +     require!(uri.len() <= 200, ErrorCode::TooLon │  │ buffer face, tinted
│      └────────────────────────────────────────────────────┘  │ line grounds at 10%
│      … 12 more lines                          View full diff │
```

**Behaviour.** Row: 16dp kind glyph in onSurfaceVariant · verb at labelLarge onSurface · human-readable detail (NEVER raw JSON) at MonoSmall onSurfaceVariant maxLines 1 ellipsis weight(1f) · DiffStatLabel when non-zero · status glyph · 14dp chevron rotated 0→90 on `animateFloatAsState(seekerSpring())`. The whole row is `Modifier.animateSize()` and is clickable ONLY when it has detail. Background is `surfaceContainer.copy(alpha = 0f)` collapsed and `surfaceContainer` expanded, so it fades in rather than swapping. Expanded body: padding start 24 / end 8 / bottom 8, diffs first, then output, both as `ZedCodeBlock`. A sub-agent call promotes to a tinted card: 12dp radius, `agentAccent.copy(alpha = if (isDark) 0.07f else 0.05f)` fill, `agentAccent@25%` border, a 16dp bot glyph, the agent name at labelLarge SemiBold in agentInk, an "AGENT" capsule at 9sp on agentAccent@18%, and the launch task at bodySmall maxLines 2. KEEP Seeker's status vocabulary and its argument: completed is a muted 8dp dot, not a green check, because a transcript of green checks reads as a list of achievements; only FAILED (1dp error border, 8dp radius round the row) and WAITING (1dp primary border, "Waiting for you — tap to answer") get framing. Ten reads stay ten unframed quiet rows.

**Why this is better.** Three changes, each earning its place. (1) COLOUR SEMANTICS ARRIVE. The diff stat today is plain muted text ("+24 −6" at labelSmall in text.muted); at 12sp on a 400dp column that is the difference between scannable and not. It becomes DiffStatLabel — addedInk/removedInk, Medium weight, tabular figures so the numbers do not shimmy as they tick. Same for diff line grounds: InlineDiff currently draws each line as coloured INK on a flat editor.background box; painting the row ground at tint@10% with full-strength ink is what makes a diff readable at a glance. (2) THE COLLAPSED ROW GOES FULLY TRANSPARENT. A long run of tool calls should be visually quiet until one is opened; today each carries its own fill and a wall of them dominates the transcript. (3) THE SNIPPET BECOMES A REAL ZED ISLAND. Today the expanded body draws in `FontFamily.Monospace` — the system mono, not the user's Lilex — so the same file looks like two different files two taps apart. `ZedCodeBlock` fixes the face, the background and the syntax spans in one component, and puts an `outlineVariant` border round it so the island's edge belongs to the Material sheet. What I am NOT changing: the muted completed dot survives, against spettro-android's green check, because Seeker's argument for it is better than spettro's reason for the check.

### Agent — a workflow run card

```
COLLAPSED (the default on a phone)
┌────────────────────────────────────────────────────────────┐
│ ⬓ mint-nft scaffold          ▸running◂                 ⌄   │ mark 14dp · title
│ ▮▮▮▯▯▯▯▯  4/8    3 running · 1 done · 1 failed     1m 12s   │ meter 72 × 4dp, ratio
│ ▪▪▪▫▫▫▪▫                                                   │ cell strip IN HEADER
└────────────────────────────────────────────────────────────┘

EXPANDED, and a failed run opens expanded
┌────────────────────────────────────────────────────────────┐
│ ⬓ mint-nft scaffold          ✕ failed                  ⌃   │ dangerInk
│ ▮▮▮▮▮▮▮▯  7/8    6 done · 1 failed                 2m 41s   │ meter 96dp expanded
│ ▪▪▪▪▪▪▪▫                                                   │
│ ├─● scaffold                                               │ rail painted in a 14dp
│ │   ✓ writer      wrote programs/src/lib.rs           ⌄     │ gutter with drawBehind
│ │   ✓ 3 done                                          ›     │ successes fold to one row
│ ├─● verify                                                 │
│ │   ✕ tester      cargo test                          ⌄     │ dangerMark glyph
│ │     3 tests failed: mint_nft::over_long_uri              │ reason inline, never
│ │   ◐ linter      clippy --all-targets                     │ behind a tap
│ ├─○ publish                                                │ unreached phase, still
└────────────────────────────────────────────────────────────┘   drawn as part of a plan
```

**Behaviour.** clip(12dp), fill `wash.copy(alpha = if (isDark) 0.06f else 0.045f)`, border 1dp `wash@25%`, `Modifier.animateSize()`. INK AND WASH ARE SEPARATE VALUES — ink = accentInk/dangerInk/agentInk by status, wash = the raw primary/removed/agentAccent by status; darkening a wash to clear a text ratio only makes the card muddy. Opens COLLAPSED on a phone (a desktop has a column beside the transcript; a phone's transcript IS the screen) with one exception: a FAILED run opens EXPANDED, because a failure folded behind a chevron is a failure the card hid. The cell strip lives in the HEADER, not the body, so closing the card never hides what broke; running cells use the ACCENT and not the member's mode tint, because half the mode palette is a green and a swarm of `code` agents drew running-green beside done-green. Any failure forces the meter's failed segment to at least 6% width so one failure among fifty does not round to zero pixels. A settled run drops successful DETAIL but never STRUCTURE: successes fold into one "N done" row that expands; a failed member keeps its row AND gains its reason. Cap phase rows at 6 before hiding. Seeker already has all of this logic in WorkflowCard.kt / SwarmCard.kt / OrchBits.kt.

**Why this is better.** This is the one part of the Agent destination that is already structurally right; the change is almost entirely token substitution, and that is the point of writing it down — an implementation agent must not rebuild it. What actually changes: the wash and border stop being raw `theme.color(...)` reads and become `LocalSeekerColors` values, so the card is legible on Ayu Light where today `warning` sits at 1.64:1 and `created` at 2.11:1 against the panel; the elapsed and ratio readouts gain `TabularNums`; the expand/collapse joins the one shared `seekerSpring()` instead of an ad-hoc tween; `MonoSheet`'s `FontFamily.Monospace` becomes `ZedCodeBlock`; and the hard-coded One-Dark ANSI fallback table at OrchBits.kt:150-157 is deleted in favour of the live theme's own `terminal.ansi.*` keys, which is a correctness fix, not a visual one — today that table paints One Dark under Gruvbox.

### Agent — the question sheet (ask-user form)

```
╭──────────────────────────────────────────────────────────────╮
│                        ▁▁▁▁▁▁                                │
│  ⍰  Spettro has 2 questions                                  │ 22dp primary, 16 SemiBold
│  Before scaffolding the mint instruction.                     │ 13/18 onSurfaceVariant
│  ⟨ ✓ 1 ⟩ ⟨ ○ 2 ⟩ ⟨ ➤ Review ⟩                                │ tab strip, only when >1
│                                                              │
│  QUESTION 2 OF 2                                             │ labelSmall @70%
│  Which token standard should the mint use?                   │ 14/20 Medium
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │ SelectableCard:
│  │ ◉  Metaplex Token Metadata          ⟨Recommended⟩      │  │ selected → 1.5dp
│  │    Widest wallet support. Adds one CPI.            ⌄   │  │ primary@70% border
│  └────────────────────────────────────────────────────────┘  │ tag ≠ preselection
│  ┌────────────────────────────────────────────────────────┐  │
│  │ ○  SPL Token 2022 extensions                           │  │ 1dp outlineVariant
│  │    Newer; some wallets do not read it yet.             │  │
│  └────────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ ○  Other…                                              │  │ expands to a field
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
├──────────────────────────────────────────────────────────────┤ pinned, above the IME
│  ╭────────────────────────────────────────────────────────╮  │ SheetScaffold `field`
│  │ Anything else it should know…                          │  │
│  ╰────────────────────────────────────────────────────────╯  │
│  Decline            ┌────────┐  ┌────────────────────────┐   │ SheetScaffold `actions`
│                     │  Back  │  │        Review          │   │ Decline = TextButton,
│                     └────────┘  └────────────────────────┘   │ removedInk
╰──────────────────────────────────────────────────────────────╯
```

**Behaviour.** Stays a SheetScaffold. NOTHING IS PRESELECTED and an unanswered question is OMITTED from the reply rather than defaulted — the Review page prints "Not answered" in warnInk and says so in words, because reporting a default as a preference is a lie to the agent. "Recommended" is a TAG and never a preselection: `primary@16%` on CircleShape, 10sp SemiBold in accentInk, padding 7 × 2. Options are SelectableCards — `surfaceContainer` fill, 8dp radius, `BorderStroke(1.dp, outlineVariant)` becoming `BorderStroke(1.5.dp, primary@70%)` when selected, a 20dp RadioButton/Checkbox tinted primary, label 14sp Medium, description 12/16, and an optional `ZedCodeBlock` preview behind a chevron. Answers are returned in DECLARED option order, not tap order. A form of exactly one single-select question submits on tap. The tab chips carry a 13dp leading glyph: CheckCircle in successMark when answered, an empty circle when not, a send glyph on the trailing Review tab. Free text and options are mutually exclusive on a single-select. THE SHEET, NOT AN INLINE CARD — spettro-chat-android renders this inline at the bottom of the transcript, and I am rejecting that: Seeker's SheetScaffold pins the text field above the IME with the list scrolling ABOVE it, which is the correct phone behaviour and the opposite of the desktop habit, and docs/UI.md's rule is sheets not docks. What the transcript gets instead is the WAITING TOOL ROW (see the tool-call wireframe) with a primary border and "tap to answer", so the transcript still shows that something is blocked.

**Why this is better.** The structure and the semantics are already right in QuestionSheet.kt (911 lines) — this is a re-dress plus three additions. The additions: real M3 buttons instead of hand-rolled Boxes, so the primary action gets a state layer, a disabled state and TalkBack's "button, disabled" for free; SelectableCard's border-not-fill selection so a selected option does not become the loudest thing on the sheet; and the "Recommended" tag moving off `theme.color("created")`. That last one is a real defect, not a style preference: on a form that decides what the agent does to your working tree, a GREEN recommendation reads as "this is the safe one", which is a claim the agent did not make. An accent-washed capsule reads as "this is the suggested one", which is what it means. Same fix in PermissionSheet.kt, where the stakes are higher — keep Seeker's rule that exactly one button is filled and it is the FIRST allow, so the least durable grant is the easiest to hit and the permanent one never is.

### Agent — the context gauge

```
IN THE STATUS STRIP (36dp, always visible, tappable)
│ ◐ 1m04s · 12.3k     ☑ 2/4 ▬▬▭▭      210k tok   37%           │ calm: onSurfaceVariant
│ ◐ 4m11s · 71.0k     ☑ 3/4 ▬▬▬▭      168k tok   84%           │ warm ≥75%: warnInk
│ ◐ 8m02s · 96.4k     ☑ 4/4 ▬▬▬▬      191k tok   96%           │ full ≥90%: removedInk

THE SHEET (tap the percentage)
╭──────────────────────────────────────────────────────────────╮
│                        ▁▁▁▁▁▁                                │
│  Context                                                     │
│                                                              │
│         ╭─────────╮                                          │
│         │   96%   │   191,402 of 200,000 tokens              │ ring 96dp, tabular
│         ╰─────────╯   8,598 left                             │
│                                                              │
│  ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▭▭            │
│  Prompt 148k · Output 43k · Cache 12k                        │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │ NoticeCard, tier 3
│  │ ⚠ Nearly full                                          │  │ removedInk glyph,
│  │ New messages may be refused. Compact the thread to      │  │ errorContainer wash
│  │ summarise it and free space, or start a new one.        │  │
│  │  ┌──────────────────┐  ┌───────────────────────────┐    │  │
│  │  │ Compact thread   │  │      New thread           │    │  │ filled + outlined
│  │  └──────────────────┘  └───────────────────────────┘    │  │
│  └────────────────────────────────────────────────────────┘  │
╰──────────────────────────────────────────────────────────────╯
```

**Behaviour.** Three severities from docs/SPETTRO.md, unchanged: CALM, WARM at 75%, FULL at 90% — matching AgentUsage.isWarm/isNearlyFull. Every existing pure function in ContextGauge.kt survives (the 0-window NaN guard, 0.4% not reading "0%", 99.6% not reading "100%"); this is a re-dress of the drawing, not of the arithmetic. Every figure carries `TabularNums` — today the literal "tnum" appears six times in this file and nowhere consistently. The ring is a Canvas with `animateFloatAsState(effectSpec())` on the sweep, `clearAndSetSemantics { contentDescription = "Context 96 percent full" }`. THE THIRD TIER IS THE ONE ADDITION: at ≥90%, above the composer, a `NoticeCard` appears with the two ways out — and if the host actually refuses the next prompt, the notice REPLACES the composer rather than sitting above it. Removing the composer is what makes the state unmissable and unavoidable without a dialog, and it is the only pattern I am taking wholesale from spettro-chat-android (ChatRoot.kt:752).

**Why this is better.** Today the number lives in a 22dp app-bar ring — the smallest, least-read pixel on the screen, carrying the one value that decides whether the next message works — and the warning is a `ContextWarningRow` band that is permanently in the vertical budget once it fires. The new arrangement inverts both: at rest the percentage is a plain tabular figure on the same 36dp line as everything else it belongs with, costing zero extra height; as it climbs it changes colour in place; and only at the point where the agent will actually refuse does it take space, and then it takes the composer's space, because there is nothing useful to type. That is severity expressed by placement and by what it takes away, which is the right vocabulary for a tool — no dialogs, and no toasts.

## Every other screen

### Build

```
┌──────────────────────────────────────────────────────────────┐
│  Build                                              ▶   ⋮    │ TopAppBar 56
│  seeker-program · devnet                                     │ subtitle
├──────────────────────────────────────────────────────────────┤
│ ◐ anchor build          2m 08s          3 warnings           │ 36 BuildStatusStrip
├──────────────────────────────────────────────────────────────┤
│  ┌────────────────────────────────────────────────────────┐  │
│  │ ⚠  unused variable: `bump`                             │  │ SeekerCard per
│  │    programs/src/lib.rs:88:13                     ›     │  │ diagnostic; warnInk
│  └────────────────────────────────────────────────────────┘  │ glyph, tap → Diff
│  ┌────────────────────────────────────────────────────────┐  │
│  │ ✕  cannot find `MintNft` in this scope                 │  │ removedInk
│  │    programs/src/lib.rs:104:31                    ›     │  │
│  └────────────────────────────────────────────────────────┘  │
│  ──────────────────────────────────────────────────────────  │ HairlineDivider
│  ┌────────────────────────────────────────────────────────┐  │
│  │  Compiling seeker-program v0.1.0                       │  │ ZedCodeBlock,
│  │  error[E0433]: failed to resolve                       │  │ buffer face, ANSI
│  │     --> programs/src/lib.rs:104:31                     │  │ from the live theme
│  └────────────────────────────────────────────────────────┘  │ softWrap=false
└──────────────────────────────────────────────────────────────┘
```

BuildScreen.kt (18 colour reads) + BuildLogView.kt (14). The log becomes a `ZedCodeBlock` — that is the one surface on this screen that is legitimately a Zed island, because it is compiler output in the user's buffer face with ANSI colour, and today it draws in `FontFamily.Monospace` at six sites with Material ink. Everything around it becomes Material: a real TopAppBar with the target as subtitle, a 36dp status strip carrying `RunTicker` + elapsed + a counts summary (the same component the Agent uses), and diagnostics as `SeekerCard`s with `warnInk`/`removedInk` glyphs rather than raw `theme.color("warning")` — which measures 1.64:1 on Ayu Light today. The run/stop control is a filled `IconButton` in the app bar that swaps glyph, not label; a build IS a cancel-not-steer situation, unlike the agent composer, so the swap is correct here. Failures use the three-tier error model: a failed build is an inline `NoticeCard` at the top of the log with a "Retry" action, never a toast.

### Setup (Spettro install / provider gate)

```
┌──────────────────────────────────────────────────────────────┐
│                                                              │
│                            ◈                                 │ IconSize.Hero 40dp,
│                                                              │ static, no morph
│                    Set up Spettro                            │ headlineSmall 24/30
│         Seeker bundles the agent. It needs one thing         │ bodyMedium, @70%,
│              before it can run: a provider.                  │ centred, 2 lines max
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ ✓  Runtime installed                     spettro 0.9.4 │  │ SeekerCard, successMark
│  ├────────────────────────────────────────────────────────┤  │
│  │ ◐  Fetching provider list                              │  │ SeekerSpinner 14dp
│  ├────────────────────────────────────────────────────────┤  │
│  │ ○  Sign in to a provider                          ›    │  │ pending, @50%
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
│      ┌──────────────────────────────────────────────────┐    │
│      │                  Sign in                         │    │ filled Button,
│      └──────────────────────────────────────────────────┘    │ full width, 48dp
│                  Use a different agent                       │ TextButton
└──────────────────────────────────────────────────────────────┘
```

ui/shell/setup/SetupScreen.kt (14 reads) and ui/agent/spettro/SetupScreen.kt (16) + SetupSheets.kt (38 — the largest single file in the agent package by colour reads). One shared `StepList` of `SeekerCard` rows replaces the several bespoke `SetupCard`/`FrameworkCard`/`UnsupportedCard` composables. The mark is Seeker's own 40dp icon, drawn STATIC — this is the slot spettro-chat-android fills with LiquidMorph, and the slot is worth having while the blob is not. The whole screen is a `Scaffold` with a 16dp gutter and one filled primary action pinned at the bottom above the nav bar. Auth flows (device-code, API key) keep their SheetScaffold with the field pinned above the IME; the code itself renders in a `ZedCodeBlock` with a `CopyChip` that flips to "Copied" for 1600ms — replacing the toast.

### Projects (sheet)

```
╭──────────────────────────────────────────────────────────────╮
│                        ▁▁▁▁▁▁                                │
│  Projects                                                    │ titleMedium
│  ╭────────────────────────────────────────────────────────╮  │
│  │ ⌕  Filter projects…                                    │  │ the shared pill
│  ╰────────────────────────────────────────────────────────╯  │
│  RECENT                                                      │ SectionHeader
│  ┌────────────────────────────────────────────────────────┐  │
│  │ ⬢ seeker-program            main ● 3          2h ago   │  │ 56dp row, branch +
│  ├────────────────────────────────────────────────────────┤  │ dirty dot + relative
│  │ ⬢ nft-mint                  main             yesterday │  │ time, tabular
│  └────────────────────────────────────────────────────────┘  │
├──────────────────────────────────────────────────────────────┤
│  ┌──────────────────────┐  ┌──────────────────────────────┐  │ SheetScaffold actions
│  │     New program      │  │        Clone…                │  │ filled + outlined
│  └──────────────────────┘  └──────────────────────────────┘  │
╰──────────────────────────────────────────────────────────────╯
```

ProjectsSheet.kt (26 reads). Rows become `ListItem`-shaped `SeekerCard` groups with a `HairlineDivider` between, replacing per-row hand-drawn fills; the dirty count uses `warnInk`, the branch name `MonoSmall`; the filter field is the SAME pill as the composer and the model drill, which is the point of having one. The two actions move into SheetScaffold's pinned `actions` slot so they cannot be pushed off the bottom by a long list. Press feedback returns here for free with the ripple — a 56dp project row that does not respond to a press is the clearest instance of the "not a real Android app" tell.

### New program / Clone

```
┌──────────────────────────────────────────────────────────────┐
│  ←   New program                                             │
├──────────────────────────────────────────────────────────────┤
│  NAME                                                        │ SectionHeader
│  ╭────────────────────────────────────────────────────────╮  │ OutlinedTextField —
│  │ mint-nft                                               │  │ stock M3, supporting
│  ╰────────────────────────────────────────────────────────╯  │ text slot used for
│   Lowercase, hyphens. Becomes the crate name.                │ the rule, not a
│                                                              │ separate line
│  FRAMEWORK                                                   │
│  ┌───────────────┬───────────────┬────────────────────────┐  │ SingleChoiceSegmented
│  │    Anchor     │    Native     │        Steel           │  │ — 3 flat choices,
│  └───────────────┴───────────────┴────────────────────────┘  │ exactly selectStyle's
│   Accounts, IDL and tests generated. Recommended.            │ Segmented case
│                                                              │
│  LOCATION                                                    │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ /storage/emulated/0/Seeker/mint-nft              ›     │  │ SeekerCard drill row
│  └────────────────────────────────────────────────────────┘  │
├──────────────────────────────────────────────────────────────┤
│  ┌────────────────────────────────────────────────────────┐  │
│  │                       Create                           │  │ filled, disabled
│  └────────────────────────────────────────────────────────┘  │ until valid
└──────────────────────────────────────────────────────────────┘
```

NewProgramScreen.kt (18) + CloneScreen.kt (6). The framework picker is the first non-agent use of `SegmentedSelect`, which is the argument for putting it in `ui/components/` rather than in the config sheet: three flat choices with an active description is the same problem in both places. Fields become stock `OutlinedTextField` with their validation in the `supportingText` slot and `isError` driving the colour — today validation is a separately drawn line, which is more code and worse semantics (TalkBack does not announce it as an error). The Create button is disabled rather than hidden, so the reason for its state can be read from the field.

### Settings

```
┌──────────────────────────────────────────────────────────────┐
│  Settings                                                    │
├──────────────────────────────────────────────────────────────┤
│  APPEARANCE                                                  │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ Theme                                One Dark      ›   │  │ 56dp drill row
│  ├────────────────────────────────────────────────────────┤  │
│  │ Icon theme                           Seeker        ›   │  │
│  ├────────────────────────────────────────────────────────┤  │
│  │ UI font size                                    16     │  │ LevelSlider's sibling
│  │  ●━━━━━━━━━━━━█━━━━━━━━━━━━○                           │  │ (continuous, no
│  │  12                                    24              │  │ detents, no haptics)
│  ├────────────────────────────────────────────────────────┤  │
│  │ Reduce motion                                  ( ●)    │  │ stock M3 Switch
│  └────────────────────────────────────────────────────────┘  │
│  EDITOR                                                      │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ Buffer font                          Lilex         ›   │  │
│  ├────────────────────────────────────────────────────────┤  │
│  │ Show whitespace                                (● )    │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

SettingsScreen.kt (14) + ThemeList.kt (8). Its private `SectionHeader` duplicate (SettingsScreen.kt:276) is deleted in favour of the shared one; the same duplicate at ChangesScreen.kt:466 goes with it. `SliderRow` keeps its documented write-on-release rule (`onValueChangeFinished`, because `AppSettings.set` rewrites settings.json through the engine) and simply loses its three `theme.color(...)` overrides — stock `SliderDefaults.colors()` now gives it the accent for free. Booleans become stock `Switch`. The theme list keeps its live-preview-on-scroll behaviour (`ThemeStore.preview`) exactly as is, and gains a swatch row per theme drawn from that theme's own accent/canvas/raised, which is a two-line change and the best possible advert for the bridge.

### Changes

```
┌──────────────────────────────────────────────────────────────┐
│  Changes                                        ⑂ main   ⋮   │
│  3 files · +128 −47                                          │ subtitle, tabular
├──────────────────────────────────────────────────────────────┤
│  STAGED                                                      │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ ☑  M  programs/src/lib.rs             +24 −6      ›     │  │ status letter in the
│  ├────────────────────────────────────────────────────────┤  │ Zed status colour,
│  │ ☑  A  programs/src/mint.rs            +98 −0      ›     │  │ stat in added/removed
│  └────────────────────────────────────────────────────────┘  │ Ink, tabular
│  UNSTAGED                                                    │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ ☐  M  Anchor.toml                     +6 −41     ›     │  │
│  └────────────────────────────────────────────────────────┘  │
├──────────────────────────────────────────────────────────────┤
│  ┌────────────────────────────────────────────────────────┐  │
│  │                    Commit 2 files                      │  │ filled, opens
│  └────────────────────────────────────────────────────────┘  │ CommitSheet
└──────────────────────────────────────────────────────────────┘
```

ChangesScreen.kt, CommitSheet.kt, BranchSheet.kt (37 reads together). Material chrome, Material rows, real `Checkbox`. The single most valuable change here is the diff stat: `DiffStatLabel` with tabular figures, so a file list stops jittering as a build touches it. CommitSheet's message field stays pinned above the IME in SheetScaffold's `field` slot — that behaviour is better than any reference and must survive. The commit message's own body is plain text in the Material face, NOT a code block; only the diff is an island.

### Diff

NO VISUAL CHANGE, and that is the decision. `ui/shell/changes/DiffScreen.kt` is wrapped in `ZedSurface` and keeps every one of its Zed colour reads, its rem metrics, its no-ripple rule and its LTR pin, because the gutters, hunk fills, blame rows (`theme.playerColor(index)`) and syntax spans must agree with the same file open in the editor two taps away. The only edits: its top bar becomes the shared `SeekerTopBar` (which lives OUTSIDE the ZedSurface wrapper, above it), and `HunkControls`' stage/revert buttons gain `touchTarget()` if they lack it. A Material-styled diff would be the single most damaging change this project could make, because it would put two different renderings of the same hunk on two screens of one app.

### Problems

```
┌──────────────────────────────────────────────────────────────┐
│  Problems                                                    │
│  2 errors · 5 warnings                                       │
├──────────────────────────────────────────────────────────────┤
│  ⟨ All 7 ⟩  ⟨ Errors 2 ⟩  ⟨ Warnings 5 ⟩  ⟨ Hints 0 ⟩         │ stock M3 FilterChip
├──────────────────────────────────────────────────────────────┤ row, scrolls
│  programs/src/lib.rs                                         │ SectionHeader per file
│  ┌────────────────────────────────────────────────────────┐  │
│  │ ✕  cannot find `MintNft` in this scope        104:31 ›  │  │ removedInk
│  ├────────────────────────────────────────────────────────┤  │
│  │ ⚠  unused variable: `bump`                     88:13 ›  │  │ warnInk, tabular
│  └────────────────────────────────────────────────────────┘  │ line:col
└──────────────────────────────────────────────────────────────┘
```

ProblemsScreen.kt. Its hand-rolled filter chip is replaced by stock `FilterChip` — the one place in the app that already tried to be a Material chip and had to draw it itself. Severity glyphs move to `LocalSeekerColors` inks. `InlineDiagnosticCard` folds into `SeekerCard`. The counts get `TabularNums`.

### Licences

LicencesScreen.kt (20) + LicenceDetailScreen.kt. Purely mechanical: a `SeekerTopBar`, `SectionHeader` per licence family, `SeekerCard` groups of rows with `HairlineDivider`, and the licence text itself in `bodySmall` Material prose — NOT a code block, because it is prose and rendering it in Lilex at 13sp would be both harder to read and a category error about what an island is. `BuildConfig.SOURCE_URL`/`VERSION_NAME`/`ZED_COMMIT` render in `MonoSmall`. NOTE FOR THE PLAY DELETION: this screen reads `BuildConfig` but not `FLAVOR`, so it is unaffected; `core/SystemSpecs.kt:83-84` reads `BuildConfig.FLAVOR` and `USERLAND` and is the file that needs attention.

### Code destination — chrome only

```
┌──────────────────────────────────────────────────────────────┐
│  lib.rs                                          ⌕   ⊞   ⋮   │ 56 SeekerTopBar
│  programs/src · main ● 3                                     │ subtitle, MonoSmall
├──────────────────────────────────────────────────────────────┤ ← MATERIAL ABOVE
│ ⟨ lib.rs ● ⟩ ⟨ mint.rs ⟩ ⟨ Anchor.toml ⟩                     │ 36 FileBar, Material
├══════════════════════════════════════════════════════════════┤ ← ZedSurface BELOW
│  1  use anchor_lang::prelude::*;                             │
│  2                                                           │ the drawing surface:
│  3  #[program]                                               │ NOT TOUCHED
│  4  pub mod seeker_program {                                 │
│  5      use super::*;                                        │
│ ⋮                                                            │
├──────────────────────────────────────────────────────────────┤
│  Ln 104, Col 31   ·  Rust  ·  UTF-8  ·  2 problems           │ 28 status line, Material
├──────────────────────────────────────────────────────────────┤
│       ▤ Code           ✦ Agent           ⚒ Build             │ NavigationBar
└──────────────────────────────────────────────────────────────┘
```

CodeScreen.kt (10 reads) SPLITS. The top bar, the tab/file bar, the status line, `FilesSheet.kt`, `CodeOverflowSheet.kt` and the code-actions sheet become Material and sit outside `ZedSurface`. Everything between the double rule — `EditorPane`, its gutter, its indent guides, its completions popup, its LSP action list, its selection handles — is wrapped in `ZedSurface` and is not touched by this work at all: same colours, same rem metrics, same no-ripple, same LTR pin. The double rule in the wireframe is the boundary, drawn as a 1dp `outlineVariant` hairline. `ShellNavBar.kt` becomes a real `NavigationBar` + three `NavigationBarItem`s: the M3 selection indicator is a pill SHAPE, which is what its `indication = null` comment was actually asking for when it complained about a 133dp splash, and it arrives with the correct semantics, the correct 56dp height and the correct insets. Keep the hide-on-`isImeVisible` rule, the landscape height branch, `touchTarget()`, the 24dp Lucide glyphs and the running-ring badge.

## The component library

### SeekerCard

```kotlin
@Composable fun SeekerCard(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(MD.radiusMd), fill: Color = MaterialTheme.colorScheme.surfaceContainer, border: Color = MaterialTheme.colorScheme.outlineVariant, filled: Boolean = true, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit)
```

*Used by:* Agent config sheet, Agent transcript (notice, sub-agent, reasoning body), Question sheet, Permission sheet, Build diagnostics, Setup step list, Projects sheet, Settings groups, Changes, Problems, Licences, Session picker

### HairlineDivider

```kotlin
@Composable fun HairlineDivider(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.outlineVariant)
```

*Used by:* every Material screen — it replaces 96 raw HorizontalDivider calls with per-site colours

### outlinedButtonEdge

```kotlin
@Composable fun outlinedButtonEdge(enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.primary): BorderStroke
```

MATERIAL'S DEFAULT IS THE WRONG ROLE UNDER THIS SCHEME, and it is the one place a stock M3 default has to be overridden app-wide. material3 1.4.0 borders an `OutlinedButton` with `outlineVariant` (`OutlinedButtonTokens.OutlineColor`), which the bridge derives from Zed's `border.variant` — the same ink `HairlineDivider` uses, chosen precisely so it would not be noticed. On a `surfaceContainer` sheet the edge vanishes and every filled/outlined pair in the app reads as filled + TEXT: Material's grammar for "do this, or back out" rather than "here are two things you can do". The edge is therefore `primary@50%` at `MD.hairline` — the same hairline the composer's focused field warms to, quieter than the filled button beside it, and unmistakably an edge. Disabled fades to 12%. `color` exists for ONE case and should stay rare — PermissionSheet's reject option draws its label in `error`, and an accent edge round red text is the control giving two answers about itself, so there the edge follows the content. `OutlinedButtonEdgeTest` fails the build on any `OutlinedButton` that does not spell a `border`, because inheriting the divider ink looks exactly like correct code.

*Used by:* Projects (Clone…), Changes (Commit), Commit sheet, Diff (Reject / Discard…), Setup sheets, Permission sheet, Context gauge (New thread / Auto compact)

### SectionHeader

```kotlin
@Composable fun SectionHeader(text: String, modifier: Modifier = Modifier, icon: (@DrawableRes Int)? = null)
```

*Used by:* Agent config sheet, Question sheet, Settings, New program, Changes, Problems, Licences, Projects

### SeekerTopBar

```kotlin
@Composable fun SeekerTopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}, scrollBehavior: TopAppBarScrollBehavior? = null)
```

*Used by:* Agent, Build, Setup, New program, Clone, Settings, Changes, Diff, Problems, Licences, Code

### SheetScaffold (existing — restyled, not replaced)

```kotlin
unchanged: @Composable fun SheetScaffold(state: ShellState, title: String?, onDismiss: () -> Unit, field: @Composable (() -> Unit)? = null, actions: @Composable (() -> Unit)? = null, openFraction: Float = 0.65f, content: @Composable ColumnScope.() -> Unit)
```

*Used by:* every modal in the app — agent config, model drill, commands, permission, question, sessions, mention, context gauge, projects, commit, branch, files, code overflow, wallet, deploy

### SeekerSpinner

```kotlin
@Composable fun SeekerSpinner(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = MaterialTheme.colorScheme.primary)
```

*Used by:* tool rows, workflow/swarm member rows, RunTicker, Setup steps, Build strip, live run strip

### StatusDot

```kotlin
@Composable fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp, pulsing: Boolean = false)
```

*Used by:* tool rows (the muted completed dot), nav bar attention badge, Changes dirty marker, Build strip, project rows

### RunTicker

```kotlin
@Composable fun RunTicker(startedAt: Long, tokens: Long?, tint: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier); internal fun elapsedLabel(ms: Long): String; internal fun formatTokens(n: Long): String
```

*Used by:* Agent status strip, Build status strip, live run strip, workflow card, transcript tail replacement

### DiffStatLabel

```kotlin
@Composable fun DiffStatLabel(added: Int, removed: Int, modifier: Modifier = Modifier, fontSize: TextUnit = 11.sp)
```

*Used by:* tool rows, workflow member rows, Changes file rows, Changes subtitle, Agent review pane, Diff header (Material chrome above the Zed body)

### ModeChip

```kotlin
@Composable fun ModeChip(name: String, modifier: Modifier = Modifier, colorName: String? = null)
```

*Used by:* Agent status strip (idle), session picker rows, workflow member rows, config summary row

### SeekerChip

```kotlin
@Composable fun SeekerChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, leading: (@DrawableRes Int)? = null, trailing: (@DrawableRes Int)? = null, tint: Color? = null, enabled: Boolean = true)
```

*Used by:* composer mention/attachment strip, question sheet tab strip, Problems filter row, Changes branch chip, Code file bar tabs

### SeekerSearchField

```kotlin
@Composable fun SeekerSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, focusRequester: FocusRequester? = null)
```

*Used by:* model drill page, command palette, mention sheet, projects sheet, session picker, files sheet, theme list, licences

### SelectRow / SelectableCard

```kotlin
@Composable fun SelectRow(label: String, description: String?, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier, multi: Boolean = false, trailing: @Composable (() -> Unit)? = null)
```

*Used by:* config sheet Rows style, model drill page, question sheet options, permission sheet custom options, agent picker, settings pickers

### SegmentedSelect

```kotlin
@Composable fun SegmentedSelect(options: List<Choice>, selectedValue: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier, showActiveDescription: Boolean = true)
```

*Used by:* Agent config sheet (mode, permission), New program (framework), Changes (staged/unstaged filter), Setup (auth method)

### LevelSlider

```kotlin
@Composable fun LevelSlider(choices: List<Choice>, selectedValue: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary)
```

*Used by:* Agent config sheet (thinking / thought_level), any future ordered ACP scale

### DrillPage

```kotlin
@Composable fun <T> DrillPage(title: String, groups: List<Group<T>>, currentValue: String?, onSelect: (T) -> Unit, onBack: () -> Unit, searchable: Boolean = true, row: @Composable (T, Boolean) -> Unit)
```

*Used by:* Agent config sheet (model, and any grouped or >6-option select), agent picker, theme list

### ZedCodeBlock

```kotlin
@Composable fun ZedCodeBlock(text: String, modifier: Modifier = Modifier, spans: List<AnnotatedString.Range<SpanStyle>>? = null, language: String? = null, maxLines: Int = Int.MAX_VALUE, wrap: Boolean = false, copyable: Boolean = true)
```

*Used by:* tool-row output and diffs, workflow MonoSheet, script call source, Build log, Setup auth codes, Commit sheet diff preview, permission sheet command preview, question sheet option previews

### CopyChip

```kotlin
@Composable fun CopyChip(text: String, modifier: Modifier = Modifier, label: String = "Copy")
```

*Used by:* ZedCodeBlock header, Setup auth code, Build log, About/Licences version block, error notices

### NoticeCard

```kotlin
@Composable fun NoticeCard(severity: Severity, title: String?, body: String, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {})  // Severity = Info | Warn | Error
```

*Used by:* Agent transcript (agent notices, refusals, compaction), context-limit block, Build failure, Setup failure, attach errors, clone failure, every place a Toast would otherwise be reached for

### EmptyState

```kotlin
@Composable fun EmptyState(headline: String, body: String, modifier: Modifier = Modifier, icon: (@DrawableRes Int)? = R.drawable.ic_launcher_monochrome, action: (@Composable () -> Unit)? = null)
```

The mark sits on a 72dp `surfaceContainer` disc with the house hairline, in `onSurfaceVariant` at full strength — the same fill step and 1dp edge every card is made of, so an empty screen is built from the vocabulary of the full ones. Bare at 45% on the canvas it read as a watermark: the only element on an 890dp column, and the faintest. NOTHING ANIMATES IN: a staggered rise was tried and removed on the device, because a destination is switched to tens of times a session and contents that arrive a piece at a time after every tap make the user wait for what they asked for. Every tab shows what it has on the frame it is shown. The Agent's empty thread puts three `SeekerChip`s in the action slot (`starterPrompts(projectName)`: "Explain what escrow does" / "Look for bugs before I deploy" / "Write tests for the instructions"), each of which seeds the composer through `AgentSeams.offer` — so a tap lands the user at the end of a sentence they can send or finish, never in a sent message.

*Used by:* Agent (no thread), Changes (clean tree), Problems (none), Projects (none), Files sheet (no matches), search results, session picker

### BottomActions

```kotlin
@Composable fun BottomActions(modifier: Modifier = Modifier, horizontalAlignment: Alignment.Horizontal = Alignment.Start, content: @Composable ColumnScope.() -> Unit)
val BottomActionsGap: Dp = MD.space6
fun Modifier.fadeUnderBottomActions(height: Dp = BottomActionsGap): Modifier
```

The pinned bar, and the seam that stops it eating the row above it. Three parts, and all three are required: the bar draws a `HairlineDivider` as its top EDGE (elevation is zero in both halves, so a hairline is what a shadow does elsewhere) and clears the system with `WindowInsets.ime.union(WindowInsets.navigationBars)` — the union, never two chained paddings, or with the keyboard up the bar floats a gesture handle above the keys. `fadeUnderBottomActions()` goes on the SCROLLING CONTAINER above it, and turns the row that happens to straddle the bar from a guillotined half-glyph into something that dissolves; on a `verticalScroll` Column it must come BEFORE `verticalScroll` in the chain, or the masked node is the page and the gradient rides down with the text. `BottomActionsGap` is the container's trailing pad, deliberately equal to the fade height so that at full scroll the gradient lands on padding rather than on the row you came for. Never apply the fade to a body that does not scroll.

*Used by:* SheetScaffold (so every sheet inherits it), Projects, New program, Setup, Changes (commit bar), Problems (Fix with agent)

### pressScale (not a composable — a modifier, in ui/theme/Press.kt)

```kotlin
@Composable fun Modifier.pressScale(interactionSource: InteractionSource, scale: Float = PRESS_SCALE): Modifier
const val PRESS_SCALE = 0.97f
```

Press feedback for anything drawn as an OBJECT: the control shrinks to 97% the frame it is pressed and springs back on release, critically damped at stiffness 1600 (settles in ~100ms, no wobble). It reads the same `MutableInteractionSource` the caller hands its `clickable`/`Surface`, and that is what makes it scroll-safe — `clickable` inside a scrolling container delays its press until the gesture has proven not to be a scroll, so a list of cards does not twitch on every flick; a pointer listener of its own would. Reduce-motion snaps between the two sizes rather than removing the change, because the pressed size is a state the finger is holding. WHERE: `SeekerCard` with an `onClick`, `SelectableCard`, `SeekerChip`, the composer's circles, the Build run square. NOT: rows in a list (a row is a region of a surface and a region does not shrink) and the nav bar (touched a hundred times a day, the frequency at which every animation is a delay).

*Used by:* SeekerCard, SelectableCard, SeekerChip, AgentComposer (ComposerCircle), BuildScreen (RunControl)

### MonoText helpers (not a composable — two TextStyles and a constant, in Type.kt)

```kotlin
val MonoBody: TextStyle; val MonoSmall: TextStyle; const val TabularNums = "tnum"
```

*Used by:* everywhere a path, a command, a token count, an elapsed time, a percentage, a line:col or a +N/−N is drawn — replaces 11 FontFamily.Monospace sites and 9 literal "tnum" strings

## What we deliberately do not copy

The references are good, and two of them are consumer apps. Not everything
that suits a chat app suits a developer tool.

- The brand palette. #526FFF / #6073CC accent, #F9F9F7 / #0E0E0E canvas, #FFFFFF / #1C1C1C raised, the 8% hairline wash. These are Spettro's identity and they would fight whatever Zed theme is loaded — an accent that appears nowhere in the editor is precisely the clash decision 1 exists to prevent. Seeker's accent is text.accent, its canvas is editor.background, its hairline is border.variant, and all three change eleven ways at runtime. The ONLY defensible literal is the agent purple #AD7BF9 for `tertiary`, and only because sub-agents are a vocabulary shared with the TUI and desktop client.
- Material You / dynamic wallpaper colour. spettro-android defaults `dynamicColor = true`; Theme.kt already refuses it in writing ("we deliberately skip Material dynamic color so the editor looks like Zed everywhere") and decision 1 says M3 is seeded from Zed's accent. Keep the mechanism shape — a boolean that swaps the scheme, with LocalInspectionMode excluded so previews render — and default it off. Do not offer it as a setting either: a wallpaper primary beside tree-sitter output is a bug the user would have to diagnose.
- FontFamily.Default for the UI face. spettro-android's Roboto is part of why it reads native, but Seeker bundles IBM Plex Sans on purpose, because that is what Zed draws its chrome in, and a Roboto Agent panel two taps from a Plex editor is a visible seam on one 400dp screen. Take the SLOT DISCIPLINE (fixed sp, six body/label roles tuned, the rest stock) and keep the face.
- spettro-chat-android's LiquidMorph.kt, in any form. The 7000ms oscillating border-radius blob and the three-layer blurred thinking mark. Four reasons, in order: it is a consumer chat mascot and would sit directly beside a tree-sitter buffer; it creates four independent infinite transitions and reallocates a Shape every frame, forcing an Outline recompute plus two RenderEffect blurs per frame; it reads no reduce-motion signal at all, which violates the contract Motion.kt documents (vestibular disorders are why that setting exists); and the reference itself uses it at exactly two call sites and never during streaming. Take the SLOT — a quiet identity mark on the Agent empty state and the Setup masthead — and fill it with Seeker's own 40dp icon, static.
- PlanBadge's animated six-colour rainbow MAX tier. A subscription flourish. An IDE does not advertise a plan tier with a scrolling shader.
- GlareText's shimmer sweep, anywhere in scrollback. Even the reference restricts it to the single active orchestrator and the streaming "Thinking…" label, for the reason written at ActivationHighlight.kt:51-55: a gradient crossing a phrase re-shades each word independently, so one half of a matched phrase is always brighter than the other, which reads as a rendering fault — and in scrollback it never stops moving. Take the `isActive: Boolean` parameter pattern (toggle liveness without swapping composables, so layout is never lost) and skip the effect. Seeker's existing WorkflowActivation.kt ramps stay: they are a live composer affordance, not scrollback, and they already have both a dark and a separately-authored light ramp.
- android.widget.Toast as an error channel. spettro-chat-android uses it at five call sites for export/import outcomes, TTS failures, dictation failures and unusable attachments. A toast is untappable, uncopyable and gone in four seconds — the wrong medium for a build error, a failed tool call or a bad path. Use NoticeCard's three tiers: a dismissible card in place for transient failures, an in-transcript pill for a recoverable wait, and a composer-replacing panel for a hard stop.
- Emoji anywhere in the UI. spettro-chat-android badges its `+` menu with skill emoji. Seeker's own `app/src/test/java/to/eyed/thragg/NoEmojiInUiTest.kt` would fail the build for it, and correctly.
- The consumer copy voice. "Where should we begin?", "Ask anything. Think out loud.", "Spettro has a question" as a headline. Seeker's existing register is right: "No thread open. Start one to talk to Spettro." — it names the way out, because unlike a chat app the composer is not always available.
- material-icons-extended, and any Material Symbols glyph. Seeker imports Lucide via tools/import-lucide-icons.py at an intrinsic 16dp on a 24 viewport, exposed through SeekerIcon/IconSize. A Material Symbols glyph beside a Lucide glyph in the same row is instantly visible. Extend the import script; do not add the dependency.
- The 820dp ReadableColumn cap. Seeker is portrait-first at 400dp; the cap can never engage, and it costs two extra Box layers on every transcript row plus a comment about desktop measure that will confuse the next reader.
- AppIconImage — rendering the launcher drawable at runtime for empty states. That is a workaround for an iOS bundle constraint that does not exist here. IconSize.Hero already covers it.
- Replacing SheetScaffold with plain ModalBottomSheets. Seeker's scaffold pins the text field at the BOTTOM so the IME lands under it and results scroll ABOVE — the opposite of the desktop habit, better than either reference, and the reason the question sheet and commit sheet work on a phone. It also registers every sheet with the ordered back handler and gives one shape to all of them. Restyle its chrome (a real titleMedium header instead of a 12sp muted line, surfaceContainer, 24dp top corners); do not replace it.
- The send button that becomes a stop button mid-turn. AgentComposer.kt:425 already argues this and is right: steering and stopping are opposite acts, and a Send that turns into a Stop makes a steer look like a cancel. Keep Send/Steer/Queue plus a separate stop control and the long-press SendOptionsSheet; restyle both as circles.
- A green check on every completed tool call. Seeker deliberately uses a muted 8dp dot, arguing that a transcript of green checks reads as a list of achievements. That is the better position; keep it. The colour semantics arrive on the diff stat and on the failure/waiting framing, where they carry information.
- Dropping the activation glow from the sent user bubble. spettro-android's argument is specific to white ink on a saturated accent fill having no headroom; Seeker's bubble is a neutral secondaryContainer with normal ink, so the glow is legible, and removing it would read as the mode having been declined between the box and the wire. Keep ActivationSurface.BUBBLE.
- Pinning material3 1.5.0-alpha25 as a prerequisite. Measured: both candidate BOMs resolve 1.4.0, and MaterialExpressiveTheme, MaterialTheme.motionScheme, the expressive Slider track and the title+subtitle TopAppBar are all public there, with HapticFeedbackType.SegmentTick already in compose-ui 1.10.4. An alpha on a shipping app's critical path buys `trackCornerSize`, `MotionScheme.expressive()` by name, LoadingIndicator, ButtonGroup and MaterialShapes — none of which this spec needs. Revisit it as a reversible enhancement after the redesign lands, never as its foundation.
- expressiveLightColorScheme() as the scheme base. It is public in 1.4.0, and it has no dark twin in any version, so using it would make Seeker's two appearances structurally different. Use lightColorScheme()/darkColorScheme() keyed on theme.isDark.
- spettro-chat-android's shell: the fixed 300dp Sidebar, ModalNavigationDrawer, the 840dp permanent-pane split, and the 160dp transcript image gallery. docs/UI.md has already fixed Seeker's shell — three destinations, three back stacks, sheets not docks, a nav bar that hides under the IME. A code agent attaches files and paths, not photos.

## Implementation plan

### P0 — Delete the play flavour

*Owns:* `app/build.gradle.kts`, `app/src/play/AndroidManifest.xml`, `app/src/play/java/to/eyed/thragg/terminal/PlayUserland.kt`, `app/src/full/AndroidManifest.xml`, `app/src/full/java/to/eyed/thragg/terminal/DebianUserland.kt`, `app/src/full/jniLibs/**`, `baselineprofile/build.gradle.kts`, `app/src/main/java/to/eyed/thragg/core/SystemSpecs.kt`, `docs/BUILDING.md`, `docs/LICENSING.md`, `docs/THIRD_PARTY.md`, `docs/TASKS.md`, `docs/ARCHITECTURE.md`, `docs/UI.md`, `docs/SPETTRO.md`, `docs/ZED_GAP_REPORT.md`, `docs/SHORTCUTS.md`, `README.md`, `.github/RELEASE_NOTES_PART_APK.md`, `.github/PULL_REQUEST_TEMPLATE.md`, `agent-docs/DECISIONS.md`

*Depends on:* nothing

Remove `flavorDimensions += "distribution"` and both `productFlavors` blocks. Promote `app/src/full/` into `app/src/main/`: DebianUserland.kt moves to main, the two `libproot_exec.so` jniLibs move to `app/src/main/jniLibs/`, and full's AndroidManifest merges into main's. Delete `app/src/play/` entirely. Move `targetSdk = 28` from the full flavour to `defaultConfig` — this is the SELinux exec-domain constraint measured in agent-docs/archive/research/android-exec-policy.md and it is now unconditional. Keep `buildConfigField("boolean", "USERLAND", "true")` in defaultConfig rather than deleting it, so `SystemSpecs.kt:84` and `BuildRunner.kt:373`'s NO_USERLAND path keep compiling and a diagnostic report still records the fact; `SystemSpecs.kt:83`'s `BuildConfig.FLAVOR` becomes the literal "full" or is dropped from the report. Fix `app/build.gradle.kts:329-330`, which zips `fullReleaseRuntimeClasspath` with `playReleaseRuntimeClasspath` for the licence catalogue — it becomes the single classpath. Grep the CI workflows and `baselineprofile/build.gradle.kts` for variant names. Verify with `./gradlew :app:assembleRelease` and by confirming the licence catalogue still generates. NO UI FILES ARE TOUCHED; this runs fully in parallel with everything else.

### P1 — Colour foundation: contrast solver and the bridge

*Owns:* `app/src/main/java/to/eyed/thragg/ui/theme/Contrast.kt`, `app/src/main/java/to/eyed/thragg/ui/theme/MaterialBridge.kt`, `app/src/main/java/to/eyed/thragg/ui/theme/ZedTheme.kt`, `app/src/test/java/to/eyed/thragg/ui/theme/ContrastTest.kt`, `app/src/test/java/to/eyed/thragg/ui/theme/MaterialBridgeTest.kt`

*Depends on:* nothing

Write Contrast.kt exactly as specified (contrastRatio, readable with 12-step bisection, pickInk, TEXT_RATIO 4.5f, MARK_RATIO 3f) and MaterialBridge.kt (`fun ZedTheme.palette(): SeekerPalette`, `SeekerColors`, `LocalSeekerColors`, the ladder de-dupe, `surfaceTint = Color.Transparent`, the full mode-colour table). Both are PURE — no Compose runtime, no Context — so both are host-testable, which is the whole reason they are their own files. Add `"success" to "created"` and `"info" to "text.accent"` to ZedTheme.DERIVED. The tests are not optional: ContrastTest parses all three bundled family JSONs (org.json is already a test dependency at libs.versions.toml `json = "20250107"` for exactly this) and asserts, over all 11 themes × both appearances, that every M3 on-role clears 4.5:1 against its pairing role and every SeekerColors *Mark clears 3.0:1. The measured baseline this must fix: Ayu Light text.muted 2.79:1, accent-on-editor 2.84:1, created 2.11:1, warning 1.64:1; One Light accent 3.84:1, created 2.64:1. MaterialBridgeTest asserts the five surfaceContainer rungs are pairwise distinct in every theme after de-dupe, and that the ladder is monotone away from the canvas in both appearances. Nothing else in the app compiles against these yet, so this chunk cannot break a build.

### P2 — Token foundation: type, shape, motion, and the theme root rewire

*Owns:* `app/src/main/java/to/eyed/thragg/ui/theme/Theme.kt`, `app/src/main/java/to/eyed/thragg/ui/theme/Surfaces.kt`, `app/src/main/java/to/eyed/thragg/ui/theme/Shape.kt`, `app/src/main/java/to/eyed/thragg/ui/theme/Type.kt`, `app/src/main/java/to/eyed/thragg/ui/theme/Motion.kt`, `app/src/main/java/to/eyed/thragg/ui/theme/Icons.kt`, `app/src/test/java/to/eyed/thragg/ui/theme/TypeScaleTest.kt`

*Depends on:* P1

Add `materialTypography(family)` with all fifteen roles filled (the six headline/display roles currently leak Roboto metrics), plus MonoBody/MonoSmall reading LocalBufferFontFamily and `const val TabularNums`. Leave `zedTypography` byte-identical. Write Shape.kt (`object MD`, `SeekerShapes`). Add seekerSpring/effectSpec/spatialSpec/Modifier.animateSize to Motion.kt, each branching on LocalReduceMotion. Move `NoIndication` out of Theme.kt into Surfaces.kt and write `ZedSurface`. Rewire Theme.kt: `val palette = remember(theme) { theme.palette() }`, provide `LocalSeekerColors`, STOP providing `LocalIndication` and `LocalLayoutDirection` at the root, and call `MaterialExpressiveTheme(colorScheme = palette.scheme, shapes = SeekerShapes, typography = materialTypography(uiFontFamily))` with the motionScheme argument OMITTED (the expressive default cannot be named on 1.4.0, and omitting it takes it). Redefine Icons.kt's `mutedIcon`/`accentIcon` to read the M3 scheme, and add a `LocalIconTint` that ZedSurface overrides back to the Zed reads. THIS CHUNK MAKES THE WHOLE APP LOOK WRONG AT ONCE and that is expected — every screen is temporarily half-migrated. Land it behind nothing; the following chunks converge it. Wrap the Zed-half packages in `ZedSurface` in this chunk too (one call site each in EditorPane's host, TerminalPane, DiffPane/GitGraphPane's hosts, MarkdownPreview, the search/diagnostics/media/tasks roots and DiffScreen) so the editor is never wrong for more than one commit. Grep the Material half for `drawBehind`/`drawWithContent` before dropping the LTR pin.

### P3 — The component library

*Owns:* `app/src/main/java/to/eyed/thragg/ui/components/SeekerCard.kt`, `app/src/main/java/to/eyed/thragg/ui/components/HairlineDivider.kt`, `app/src/main/java/to/eyed/thragg/ui/components/SectionHeader.kt`, `app/src/main/java/to/eyed/thragg/ui/components/SeekerTopBar.kt`, `app/src/main/java/to/eyed/thragg/ui/components/SeekerSpinner.kt`, `app/src/main/java/to/eyed/thragg/ui/components/StatusDot.kt`, `app/src/main/java/to/eyed/thragg/ui/components/RunTicker.kt`, `app/src/main/java/to/eyed/thragg/ui/components/DiffStatLabel.kt`, `app/src/main/java/to/eyed/thragg/ui/components/ModeChip.kt`, `app/src/main/java/to/eyed/thragg/ui/components/SeekerChip.kt`, `app/src/main/java/to/eyed/thragg/ui/components/SeekerSearchField.kt`, `app/src/main/java/to/eyed/thragg/ui/components/SelectRow.kt`, `app/src/main/java/to/eyed/thragg/ui/components/SegmentedSelect.kt`, `app/src/main/java/to/eyed/thragg/ui/components/LevelSlider.kt`, `app/src/main/java/to/eyed/thragg/ui/components/DrillPage.kt`, `app/src/main/java/to/eyed/thragg/ui/components/ZedCodeBlock.kt`, `app/src/main/java/to/eyed/thragg/ui/components/CopyChip.kt`, `app/src/main/java/to/eyed/thragg/ui/components/NoticeCard.kt`, `app/src/main/java/to/eyed/thragg/ui/components/EmptyState.kt`, `app/src/main/java/to/eyed/thragg/ui/components/ComponentPreviews.kt`

*Depends on:* P2

Twenty small files, one component each, every one taking `modifier: Modifier = Modifier` and carrying a @Preview PAIR — dark on One Dark and light on Ayu Light, because Ayu Light is the theme that breaks things. This is the highest-value chunk in the plan and the one most worth doing before any screen work: the polish in the reference is not in any one screen, it is that the same twenty pieces appear on every screen. LevelSlider is the exemplar and carries the most detail (saturated() HSV ramp with S scaled to 0.08 + 0.92f, tick colour from the FILL's luminance at the 0.45 threshold, SegmentTick haptics guarded by tickedIndex, Track's thumbTrackGapSize 8dp / trackInsideCornerSize 6dp / drawTick as a 2.5dp circle / drawStopIndicator = null, the 5→3dp × 40→52dp handle morph on spatialSpec, the direction-of-travel level pill, scale-end labels, stateDescription semantics, commit-on-release-only-if-changed). SeekerSpinner is a MOVE, not a rewrite: lift the existing implementation out of OrchBits.kt:303, which already has the right 8-frame/50ms cadence and a reduce-motion branch. ZedCodeBlock is the only component that reads LocalZedTheme, and it must be the only one — that is the seam contract, and a reviewer should be able to check it with one grep.

### P4 — Delete the dead half

*Owns:* `app/src/main/java/to/eyed/thragg/ui/workspace/**`, `app/src/main/java/to/eyed/thragg/ui/agent/AgentPanel.kt`

*Depends on:* nothing

Runs fully in parallel with P0-P3 and MUST land before any screen migration, or roughly 440 of the ~1200 edits downstream are wasted. `ui/workspace/WorkspaceScreen.kt` is 6042 lines and unreachable — MainActivity.kt:137 hosts `SeekerShell`, not it. `ui/agent/AgentPanel.kt` is 3658 lines carrying 171 colour reads with no live caller; `grep 'AgentPanel('` finds only `opensAgentPanel` and a Keymap enum. ui/workspace/ holds 270 colour reads total, of which only AboutDialog, NotificationHost/Notifications, ContextMenu, GitStatusColours, GoToLine, OutlinePicker, ProjectPanel, AutosaveTracker and OpenFiles are imported by the shell — move those nine into the packages that use them (ui/shell/, ui/editor/, ui/git/) and delete the rest of the directory. Verify by a clean `assembleFullDebug` plus the existing instrumentation suite; the risk here is a reflective or resource-name reference, so also grep XML and keep rules for the deleted class names.

### P5 — Agent config surface

*Owns:* `app/src/main/java/to/eyed/thragg/ui/shell/agent/AgentConfigSheet.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/ConfigSheets.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/ConfigChips.kt`

*Depends on:* P3

THE ANSWER TO THE OWNER'S VERDICT — do this one first among the screen chunks and demo it. Replace the 288 lines of identical 56dp ConfigRow with one LazyColumn page driven by `selectStyle(option, kind)`, dispatching to LevelSlider / SegmentedSelect / DrillPage / SelectRow / Switch. Keep `chipOrder` and `chipIcon` as the section rank and glyph. Add the `pending: Map<String, ConfigValue>?` optimistic overlay so a tap moves the control before session/set_config_option lands. Kill the sheet-over-sheet: the model list drills INSIDE the sheet via AnimatedContent with a directional ±width/3 slide, and the drill id is rememberSaveable. Keep the scroll-to-current-value behaviour from ConfigSheets.kt (via revealItem, so it respects reduce-motion) and add the drill search with provider-name matching. ConfigChips.kt shrinks to the one-line ConfigSummaryRow plus the Ultra state model — preserve Ultra's four-state amber gating verbatim, it is protocol behaviour, and move its literals (UltraAmberFallback #F5A524, UltraArmedLabel #1A1205) into SeekerColors. Delete `modeTintArgb` in favour of `LocalSeekerColors.modeColor`, keeping the `category != "mode" -> null` guard.

### P6 — Agent shell and status strip

*Owns:* `app/src/main/java/to/eyed/thragg/ui/shell/agent/AgentScreen.kt`, `app/src/main/java/to/eyed/thragg/ui/shell/agent/AgentSeams.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/PlanSurface.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/ContextGauge.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/LiveRunPeek.kt`

*Depends on:* P3

Collapse seven pinned bands to three. Build `AgentStatusStrip` (36dp: RunTicker-or-ModeChip · PlanProgress · Spacer · UsageReadout, animateSize, HairlineDivider under). Replace the hand-rolled 48dp AgentBar with `SeekerTopBar(title = projectName, subtitle = "Spettro · coding")`. PlanStrip folds into the strip's PlanProgress and its unfoldable list; ContextGauge's ring moves into the strip as a tabular percentage plus a sheet, and gains the tier-3 composer-replacing NoticeCard at ≥90%; LiveRunPeek becomes the 180ms-in/240ms-out live strip with the 4000ms settled-run hold. ReviewBar becomes a badged overflow action; the setup banner becomes a card in the empty state; both ConfigNotices become in-transcript NoticeCards. PRESERVE, without touching the logic: followsTail/transcriptAtTail/the latched follow, the retap-to-newest contract, the `expanded` map held outside the LazyColumn, showsSecondaryBands, the AgentNotifier and panelVisible lifecycle, the leavingBlocked badge effect, and every one of the pure functions AgentScreen.kt:120-236 exposes for host tests. Every one of those has a test or a written argument behind it; this chunk is a re-layout, not a rewrite.

### P7 — Agent composer

*Owns:* `app/src/main/java/to/eyed/thragg/ui/shell/agent/AgentComposer.kt`, `app/src/main/java/to/eyed/thragg/ui/shell/agent/MentionSheet.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/WorkflowActivation.kt`

*Depends on:* P3

The pill field (20dp radius, surfaceContainerHigh, hairline animating to primary@50% on focus, primary cursorBrush, bodyLarge 15/22, maxLines 6, heightIn(min=40dp), padding 12×9), the 40dp filled circular send, the separate stop circle in `error`, the horizontally-scrolling 32dp mention/attachment strip with real thumbnails, and ConfigSummaryRow. Move the inline slash palette into a SheetScaffold with a SeekerSearchField. KEEP EVERY BEHAVIOUR: SendMode.Send/Steer/Queue and their three labels, the separate stop, the long-press SendOptionsSheet, the steer note, ComposerHint's states, the key handling, and the ActivationSurface.BUBBLE glow. WorkflowActivation.kt keeps all three of its 6-stop ramps unchanged — DARK_RAMP, LIGHT_RAMP (which is deliberately not the dark one darkened) and BUBBLE_RAMP, at 7000ms / 140 steps matching the CLI's 50ms tick — because that is identity, not chrome, and it already handles both appearances correctly.

### P8 — Agent transcript and orchestration cards

*Owns:* `app/src/main/java/to/eyed/thragg/ui/shell/agent/AgentTranscript.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/OrchBits.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/WorkflowCard.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/SwarmCard.kt`, `app/src/main/java/to/eyed/thragg/ui/common/MarkdownText.kt`

*Depends on:* P3

Row-by-row token substitution, plus four real changes: tool rows go transparent when collapsed and fade in their raised fill when expanded; DiffStatLabel and tinted diff line grounds at 10% replace muted stat text and flat grounds; every mono site becomes ZedCodeBlock (this is where the eleven FontFamily.Monospace bugs die, including WorkflowCard.kt's MonoSheet); and every expand/collapse joins the single seekerSpring. Two correctness fixes ship here: delete OrchBits.kt:150-157's baked One-Dark ANSI fallback table in favour of the live theme's `terminal.ansi.*` keys, and delete PlanSurface.kt:314's `Color.Gray`. KEEP: the muted completed dot and its argument, the failure and waiting framing, the system pills (steering/goal/compaction detected by prefix with the glyph swapped for a drawable), the user bubble's checkpoint affordance, the 120ms assistant block fade and its draft-reset reason, and MarkdownText's MARKDOWN_REPARSE_MS = 180 throttle. ADD ONE THING from spettro-chat-android: a fence-aware, list-run-aware block chunker in front of that throttle, so a 6KB reply re-parses only its growing tail. The two mechanisms are orthogonal — chunking bounds parse SIZE, the throttle bounds parse FREQUENCY — and together they are the best streaming-markdown story in any of the four codebases.

### P9 — Agent sheets

*Owns:* `app/src/main/java/to/eyed/thragg/ui/shell/SheetScaffold.kt`, `app/src/main/java/to/eyed/thragg/ui/shell/agent/AgentPickerSheet.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/PermissionSheet.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/QuestionSheet.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/SessionPicker.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/SetupSheets.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/SetupScreen.kt`

*Depends on:* P3

SheetScaffold keeps all three of its behaviours (bottom-pinned field above the IME, ordered back-handler registration, drag-to-resize from 65% with a 45% dismiss threshold) and gains Material chrome: containerColor = surfaceContainer, 24dp top corners, tonalElevation 0, and a real titleMedium SemiBold header instead of the 12sp muted line. Permission and question options become SelectableCards with border-not-fill selection; the hand-rolled OptionButton becomes stock Button/OutlinedButton/TextButton; "Recommended" moves off `theme.color("created")` — green on a security prompt reads as a safety claim the agent did not make — onto an accent@16% capsule. KEEP every semantic: nothing preselected, unanswered omitted rather than defaulted, answers in declared option order, single-question single-select submits on tap, exactly one filled button and it is the first allow, the durability note under allow_always, the delivered-once guard, the custom-input option pinned as the sheet's field, and the "#2 of 3 waiting" queue title. SetupSheets.kt is the largest file here (38 colour reads) and is mostly mechanical.

### P10 — Build and Setup destinations

*Owns:* `app/src/main/java/to/eyed/thragg/ui/shell/build/BuildScreen.kt`, `app/src/main/java/to/eyed/thragg/ui/shell/build/BuildLogView.kt`, `app/src/main/java/to/eyed/thragg/ui/shell/build/ShellMode.kt`, `app/src/main/java/to/eyed/thragg/ui/shell/setup/SetupScreen.kt`

*Depends on:* P3

SeekerTopBar with the target as subtitle; a 36dp BuildStatusStrip reusing RunTicker; diagnostics as SeekerCards with warnInk/removedInk; the log as a ZedCodeBlock reading the live theme's ANSI keys (six FontFamily.Monospace sites die here); failures as NoticeCards with a Retry action rather than banners or toasts. Setup becomes the shared StepList of SeekerCards with a static 40dp Hero mark, one filled bottom action, and CopyChip on any auth code.

### P11 — Projects, New program, Clone

*Owns:* `app/src/main/java/to/eyed/thragg/ui/shell/projects/ProjectsSheet.kt`, `app/src/main/java/to/eyed/thragg/ui/shell/projects/NewProgramScreen.kt`, `app/src/main/java/to/eyed/thragg/ui/shell/projects/CloneScreen.kt`

*Depends on:* P3

Projects: SeekerSearchField, SeekerCard row groups with HairlineDivider, ModeChip-shaped branch chips, StatusDot dirty markers, the two actions in SheetScaffold's pinned slot. New program: SegmentedSelect for the framework (its first non-agent use, which is the argument for it being a shared component), stock OutlinedTextField with validation in `supportingText` and `isError`, a drill row for the location, one disabled-until-valid filled Create. Clone: the same field treatment plus a NoticeCard for a failed fetch.

### P12 — Settings, theme list, licences

*Owns:* `app/src/main/java/to/eyed/thragg/ui/shell/settings/SettingsScreen.kt`, `app/src/main/java/to/eyed/thragg/ui/shell/settings/ThemeList.kt`, `app/src/main/java/to/eyed/thragg/ui/shell/licences/LicencesScreen.kt`, `app/src/main/java/to/eyed/thragg/ui/shell/licences/LicenceDetailScreen.kt`, `app/src/main/java/to/eyed/thragg/ui/shell/licences/LicenceCatalog.kt`

*Depends on:* P3

SeekerCard groups, the shared SectionHeader (deleting the private duplicate at SettingsScreen.kt:276), stock Switch for booleans, SliderRow keeping its write-on-release rule and losing its three colour overrides. ThemeList keeps its live-preview-on-scroll and gains a three-swatch strip per theme drawn from that theme's own accent/canvas/raised — two lines, and the best advert the bridge could have. Licences is mechanical; note it reads BuildConfig but not FLAVOR, so P0 does not touch it.

### P13 — Changes, Problems, Code chrome, nav bar, and the boundary audit

*Owns:* `app/src/main/java/to/eyed/thragg/ui/shell/changes/ChangesScreen.kt`, `app/src/main/java/to/eyed/thragg/ui/shell/changes/CommitSheet.kt`, `app/src/main/java/to/eyed/thragg/ui/shell/changes/BranchSheet.kt`, `app/src/main/java/to/eyed/thragg/ui/shell/changes/ProblemsScreen.kt`, `app/src/main/java/to/eyed/thragg/ui/shell/changes/DiffScreen.kt`, `app/src/main/java/to/eyed/thragg/ui/shell/code/CodeScreen.kt`, `app/src/main/java/to/eyed/thragg/ui/shell/code/FileBar.kt`, `app/src/main/java/to/eyed/thragg/ui/shell/code/FilesSheet.kt`, `app/src/main/java/to/eyed/thragg/ui/shell/code/CodeOverflowSheet.kt`, `app/src/main/java/to/eyed/thragg/ui/shell/ShellNavBar.kt`, `app/src/main/java/to/eyed/thragg/ui/shell/SeekerShell.kt`, `app/src/main/java/to/eyed/thragg/ui/common/BinaryPlaceholder.kt`, `app/src/main/java/to/eyed/thragg/ui/common/UnsavedChangesDialog.kt`

*Depends on:* P3

ShellNavBar becomes a real NavigationBar + NavigationBarItem — the M3 selection indicator is a SHAPE, which is what its `indication = null` comment was actually asking for when it complained about a 133dp splash — keeping the hide-on-IME rule, the landscape height branch, touchTarget(), the 24dp Lucide glyphs and the running-ring badge. CodeScreen splits: bar, file bar, status line and its three sheets go Material; the editor host is wrapped in ZedSurface and untouched. DiffScreen is wrapped in ZedSurface and gets only a new top bar. THE AUDIT IS THIS CHUNK'S REAL DELIVERABLE: land a `SeamTest` that greps the source tree and fails the build if a file under ui/editor, ui/terminal, ui/git, ui/preview, ui/search, ui/diagnostics, ui/media or ui/tasks reads `MaterialTheme.colorScheme`, or if a file under ui/shell (excluding DiffScreen and the CodeScreen editor host) or ui/agent reads `LocalZedTheme` — with ZedCodeBlock.kt as the single named exception. A one-grep contract is the only way a boundary this size survives its second year.

### P14 — Previews, fixtures and the visual regression net

*Owns:* `app/src/main/java/to/eyed/thragg/ui/shell/agent/AgentPreviewData.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/OrchPreviewData.kt`, `app/src/main/java/to/eyed/thragg/ui/components/ComponentPreviews.kt`, `app/src/test/java/to/eyed/thragg/ui/theme/SeamTest.kt`, `app/src/androidTest/java/to/eyed/thragg/ui/AgentScreenshotTest.kt`

*Depends on:* P3, P5, P6, P7, P8

There are currently ZERO @Preview functions anywhere in Seeker's Agent code, which means this redesign can otherwise only be judged on a device, one state at a time. Write one internal AgentPreviewData object carrying: a settled transcript AND a streaming one; every tool status (completed-with-output, running-with-none, failed-with-a-stack-tail, an edit with a real diff, a completed sub-agent, a running sub-agent, a waiting permission); a plan with one entry in each state; usage at 37% AND at 96%; a configOptions fixture that exercises all five presentation styles at once (3 flat modes → Segmented, provider-grouped models → Drill, 3 permissions → Segmented, 3 thinking levels → Slider, boolean Ultra → Switch); and a CONFIG_SUMMARY string. Then @Preview pairs at 400×890 for every surface, in BOTH appearances, previewing ONE DARK and AYU LIGHT specifically — Ayu Light is the theme every contrast bug shows up on, so it is the one that must be on screen while the work happens. One preview passes a `pending` value so the optimistic overlay is visible. The androidTest screenshot suite pins the Agent screen at rest, the config sheet, and the composer in all three states, on both themes.
