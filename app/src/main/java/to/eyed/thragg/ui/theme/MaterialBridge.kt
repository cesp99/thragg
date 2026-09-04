package to.eyed.thragg.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs

/**
 * The one derivation: a parsed [ZedTheme] becomes the Material half's
 * [ColorScheme] and the small set of tokens Material has no role for, together,
 * in one pass.
 *
 * [ZedTheme] stays the single source of colour truth. Both halves of the app
 * come out of this function and out of the same `remember(theme)` in
 * `ThraggTheme`, so the Zed half and the Material half physically
 * cannot disagree by a frame. No second palette is authored anywhere, and
 * there is no dynamic/Material You colour, ever: a wallpaper primary would
 * appear nowhere in the editor, which is precisely the clash the hybrid exists
 * to prevent (docs/VISUAL.md, "The hybrid").
 *
 * Three bands, and they are derived very differently on purpose:
 *
 *  - **Band A is pinned.** Zed's JSON already contains a real five-rung neutral
 *    ladder and it runs the right way in both appearances (measured luminance,
 *    One Dark `.025 .034 .034 .045 .052`; One Light `.956 .831 .831 .738
 *    .716` — away from the canvas in both, which is exactly M3's convention).
 *    So the ladder maps unmodified rather than being recomputed.
 *  - **Band B is derived from one seed, by blending toward the theme's own
 *    extremes**, never by HCT tone steps. There is no public seed-to-scheme API
 *    at any version available here (`dynamicTonalPalette` takes a `Context`;
 *    `HctSolver` is `androidx.compose.material3.internal`), and an HCT tone-90
 *    container would be a colour no Zed theme contains. material-kolor is not
 *    the answer either: a third-party dependency plus a THIRD_PARTY.md entry
 *    plus a LicenceCatalog.kt row, to produce neutrals that fight Zed's.
 *  - **Band C is [SeekerColors]**, produced by the same call so it cannot
 *    drift, for the things M3 has no role for at all — a diff stat, a mode
 *    tint, the hairline.
 *
 * Pure Kotlin: no Compose runtime, no `Context`, so `MaterialBridgeTest` walks
 * all eleven bundled themes on the JVM.
 */

/**
 * Sub-agents and skills — Spettro's purple, the one fixed hue in the scheme.
 *
 * It is shared vocabulary across the TUI, the desktop client and this app, so
 * deriving it from the user's editor theme would break a cross-front-end
 * agreement: "the purple one" has to mean the same thing in both windows. This
 * is the argument ConfigChips.kt:412 already makes for mode tints, applied
 * consistently. It is also the *only* literal the Material half is allowed.
 */
val AgentAccent = Color(0xFFAD7BF9)

/**
 * The amber a "thought harder" run is marked with, when a theme writes no
 * `warning` of its own.
 */
private val UltraAmber = Color(0xFFF5A524)

/**
 * Two rungs of the surface ladder are the same colour when their luminance is
 * within this — an eighth of the smallest step Zed's own themes use between
 * adjacent rungs, so a real step is never mistaken for a collision.
 */
private const val LADDER_STEP = 0.006f

/** How far a collided rung is pushed away from the canvas: 3%, invisible alone. */
private const val LADDER_NUDGE = 0.03f

/**
 * A hair over the heaviest tint wash any card applies (7%), so an ink solved
 * here clears the bar on the plain canvas too, with a little to spare.
 */
private const val CARD_WASH = 0.08f

/** The theme's two halves, derived together. */
@Immutable
class SeekerPalette(val scheme: ColorScheme, val seeker: SeekerColors)

/**
 * The tokens Material has no role for.
 *
 * Every `*Ink` is solved at [TEXT_RATIO] against [cardGround] and every `*Mark`
 * at [MARK_RATIO], because that is the hardest ground they actually land on —
 * see [cardGround]. Nothing here is a second palette: it is the same
 * derivation, exposed for the meanings M3's fourteen roles do not name.
 */
@Immutable
class SeekerColors(
    val isDark: Boolean,
    /** Zed's own hairline (`border.variant`), not an 8% wash of the ink. */
    val hairline: Color,
    /**
     * The ground the inks are solved against: the surface under a card's tint
     * wash, using whichever tint pushes it *toward* the ink and therefore hurts
     * most. An ink solved to exactly 4.5:1 on the bare canvas arrives at about
     * 4.16:1 on a washed card, and 4.16 is not 4.5.
     */
    val cardGround: Color,
    val accentInk: Color,
    val accentMark: Color,
    val addedInk: Color,
    val addedMark: Color,
    val removedInk: Color,
    val removedMark: Color,
    val warnInk: Color,
    val warnMark: Color,
    val agentAccent: Color,
    val agentInk: Color,
    val ultraAmber: Color,
) {
    /**
     * Failure red as text — a reason, "N failed", an error row.
     *
     * The same colour as [removedInk]; docs/VISUAL.md names it under both
     * spellings, and a deleted line and a failure are the same red in Zed.
     */
    val dangerInk: Color get() = removedInk

    /**
     * The per-agent-mode tint, matching `modeColor()` in the TUI's styles.go and
     * spettro-android's `SpettroColors.kt:69-96`, so the same mode reads as the
     * same colour in every front-end.
     *
     * It takes a manifest colour NAME ("green", "cyan") or a mode id ("plan",
     * "coding") — the same fallback chain the TUI applies, and the reason this
     * table is longer than Seeker's own `modeTintArgb` (ConfigChips.kt:412-423):
     * `spettro.agents.toml` emits names, which that function answers `null` for.
     * Its `category != "mode"` guard stays where it is; this only decides what a
     * name means once the caller has decided the name is a mode's.
     *
     * The raw hue, unadjusted — anything drawing it as *text* wants [modeInk].
     */
    fun modeColor(name: String?): Color = when (name?.lowercase()) {
        "blue" -> Color(0xFFA78BFA)
        "green" -> Color(0xFF34D399)
        "cyan" -> Color(0xFF60A5FA)
        "yellow" -> Color(0xFFF59E0B)
        "magenta" -> Color(0xFFC084FC)
        "purple" -> Color(0xFFBD93F9)
        "red" -> Color(0xFFEF4444)
        // Mode-id fallbacks, as in the TUI.
        "plan" -> Color(0xFFBD93F9)
        "planning" -> Color(0xFFA78BFA)
        "coding", "code" -> Color(0xFF34D399)
        "chat", "ask" -> Color(0xFF60A5FA)
        // The theme's accent, already solved: the raw seed is not carried here
        // because nothing outside this file has a use for an unreadable one.
        else -> accentInk
    }

    /** [modeColor], made legible as small text on a card. */
    fun modeInk(name: String?): Color = readable(modeColor(name), cardGround)
}

/** The Material half's colours, for the composables that are not the editor. */
val LocalSeekerColors = staticCompositionLocalOf<SeekerColors> {
    error("SeekerColors not provided — wrap content in ThraggTheme")
}

/**
 * This theme as the Material half sees it.
 *
 * Cost is about thirty colours and a handful of 12-step bisections per theme
 * change — the same order of magnitude as the palette parse `ZedThemes.warm()`
 * already pre-pays for the picker's preview walk.
 */
fun ZedTheme.palette(): SeekerPalette {
    val editorBackground = color("editor.background")
    val panel = color("panel.background")
    val chrome = color("background")
    val text = color("text")
    val muted = color("text.muted")

    // BAND A — pinned, straight from Zed's own keys.
    //
    // Material's menus, sheets and dialogs paint themselves from the
    // surfaceContainer roles rather than from `surface` (tokens/MenuTokens.kt),
    // so all five rungs have to be real and distinct or a DropdownMenu is
    // indistinguishable from the sheet under it. Today every one of them is
    // `elevated_surface.background` (Theme.kt:128-133), which is that bug.
    val rungs = ladder(
        listOf(
            editorBackground,
            color("element.background"),
            color("elevated_surface.background"),
            color("element.hover"),
            chrome,
        ),
        isDark = isDark,
    )
    val (lowest, low, container, high, highest) = rungs
    // One expression that is right in both appearances: `bright` is whichever
    // of the two canvases is lighter, `dim` the other.
    val bright = if (editorBackground.luminance() >= chrome.luminance()) editorBackground else chrome
    val dim = if (bright == editorBackground) chrome else editorBackground

    // BAND B — derived from one seed.
    //
    // Every bundled theme writes `text.accent`; the chain behind it is for a
    // user's own theme file. One Dark's `players[0].cursor` is the same
    // #74ade8, the existing bridge already treats `text.accent` as `primary`,
    // and DERIVED already routes `debugger.accent` to it.
    val seed = colorOrNull("text.accent")
        ?: players.firstOrNull()
        ?: colorOrNull("border.focused")
        ?: colorOrNull("icon.accent")
        ?: cursor

    val primary = readable(seed, on = container, TEXT_RATIO)
    val primaryContainer = lerp(primary, high, 0.80f)
    val secondary = readable(muted, on = panel)
    val secondaryContainer = color("element.selected")
    val tertiary = readable(AgentAccent, on = panel)
    val tertiaryContainer = lerp(tertiary, high, 0.86f)
    val error = color("error")
    val errorContainer = lerp(error, high, 0.86f)
    val onBackground = readable(text, on = editorBackground)

    // Material's light/dark base follows the *theme*, not the device mode:
    // previewing a light theme while the device is dark must give light
    // scrollbars and ripples too, or half the stock components fight the
    // palette. `expressiveLightColorScheme()` is public here but has no dark
    // twin at any version, so using it would make the two appearances
    // structurally different.
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = primary,
        onPrimary = pickInk(on = primary, prefer = editorBackground, alt = text),
        primaryContainer = primaryContainer,
        onPrimaryContainer = readable(primary, on = primaryContainer),
        inversePrimary = lerp(primary, onBackground, 0.35f),
        secondary = secondary,
        onSecondary = pickInk(on = secondary, prefer = editorBackground, alt = text),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = readable(text, on = secondaryContainer),
        tertiary = tertiary,
        onTertiary = pickInk(on = tertiary, prefer = editorBackground, alt = text),
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = readable(tertiary, on = tertiaryContainer),
        background = editorBackground,
        onBackground = onBackground,
        surface = panel,
        onSurface = readable(text, on = panel),
        surfaceVariant = low,
        // Solved on `surface`, which is where a secondary line lives. It is
        // deliberately NOT solved on the top of the ladder: on the Ayu family
        // it lands at 3.35-4.0:1 there, which is why the call-site rule for a
        // muted line *inside a container* is `onSurface.copy(alpha = 0.6f)`
        // rather than this role (docs/VISUAL.md, "Foundations", the three traps).
        onSurfaceVariant = readable(muted, on = panel),
        // MANDATORY. Left as `primary` — Material's default, and what
        // Theme.kt ships today — tonal elevation washes the accent over every
        // raised Surface, Card, menu and sheet in the app. Elevation in this
        // design is carried by a fill step and one hairline, nothing else.
        surfaceTint = Color.Transparent,
        surfaceBright = bright,
        surfaceDim = dim,
        surfaceContainerLowest = lowest,
        surfaceContainerLow = low,
        surfaceContainer = container,
        surfaceContainerHigh = high,
        surfaceContainerHighest = highest,
        inverseSurface = text,
        inverseOnSurface = editorBackground,
        error = error,
        onError = pickInk(on = error, prefer = editorBackground, alt = text),
        errorContainer = errorContainer,
        onErrorContainer = readable(error, on = errorContainer),
        outline = color("border"),
        outlineVariant = color("border.variant"),
        // Zed has no scrim key: it has no modal sheets. Material's own values.
        scrim = Color.Black.copy(alpha = if (isDark) 0.60f else 0.32f),
    )

    // BAND C.
    // A theme that writes no diff colours is not one Zed would load; these
    // fallbacks only have to be *a* colour rather than the missing-key magenta.
    val created = color("created", primary)
    val deleted = color("deleted", error)
    val warning = color("warning", UltraAmber)
    val ground = cardGround(container, listOf(primary, AgentAccent, deleted), isDark)
    val seeker = SeekerColors(
        isDark = isDark,
        hairline = color("border.variant"),
        cardGround = ground,
        accentInk = readable(primary, on = ground),
        accentMark = readable(primary, on = ground, MARK_RATIO),
        addedInk = readable(created, on = ground),
        addedMark = readable(created, on = ground, MARK_RATIO),
        removedInk = readable(deleted, on = ground),
        removedMark = readable(deleted, on = ground, MARK_RATIO),
        warnInk = readable(warning, on = ground),
        warnMark = readable(warning, on = ground, MARK_RATIO),
        agentAccent = AgentAccent,
        agentInk = readable(AgentAccent, on = ground),
        ultraAmber = warning,
    )
    return SeekerPalette(scheme, seeker)
}

/**
 * The five surface rungs, with collisions pushed apart.
 *
 * In nine of the eleven bundled themes `element.background` and
 * `elevated_surface.background` are the same hex, and in One Dark they differ
 * by one unit of red — so rungs 2 and 3 collide and a menu over a card has no
 * edge but its hairline. The walk is lowest to highest, comparing against the
 * rung already placed, so a nudge propagates rather than being undone by the
 * next comparison. 3% toward the appearance's own extreme is enough to clear
 * [LADDER_STEP] on every bundled theme and is invisible when it does not fire.
 */
private fun ladder(rungs: List<Color>, isDark: Boolean): List<Color> {
    val out = ArrayList<Color>(rungs.size)
    val away = if (isDark) Color.White else Color.Black
    for (rung in rungs) {
        val below = out.lastOrNull()
        out += if (below != null && abs(below.luminance() - rung.luminance()) < LADDER_STEP) {
            lerp(rung, away, LADDER_NUDGE)
        } else {
            rung
        }
    }
    return out
}

/**
 * [surface] under the tint wash that hurts a solved ink most: the darkest tint
 * on a light theme, the lightest on a dark one, because that is the one that
 * moves the card *toward* the ink.
 */
private fun cardGround(surface: Color, tints: List<Color>, isDark: Boolean): Color {
    val worst = if (isDark) {
        tints.maxByOrNull { it.luminance() }
    } else {
        tints.minByOrNull { it.luminance() }
    } ?: return surface
    return lerp(surface, worst, CARD_WASH)
}

/**
 * [ZedTheme.color] without its magenta: null when neither the theme nor the
 * derivation table answers, which is what a fallback *chain* needs.
 *
 * [Color.Unspecified] is the sentinel because a theme file cannot produce it —
 * every hex the parser accepts is a real sRGB colour.
 */
private fun ZedTheme.colorOrNull(key: String): Color? =
    color(key, Color.Unspecified).takeIf { it != Color.Unspecified }
