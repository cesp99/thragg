package to.eyed.thragg.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The Material half's metrics: a 4dp grid in **fixed dp**, never in rems.
 *
 * This is the twin of [ZedRadius]/[rem], and the split of jurisdiction is the
 * whole point. Zed's chrome is measured in rems because `ui_font_size` is a
 * real setting on that half and Zed's own `rems(x)` resolve against it — a
 * port that hardcoded 16 would make the setting do nothing. A Material sheet
 * is the opposite case: it must not resize because someone changed the size of
 * the font in their editor. So the editor keeps `rem()` and `ZedRadius`, the
 * app half keeps [MD], and neither is used on the other side of the seam
 * (docs/VISUAL.md, "Foundations", SHAPE + SPACING).
 *
 * THE RHYTHM these names are for, written once so no screen has to re-decide:
 *
 *  - **[space4] (16dp) is the screen gutter, always** — transcript, sheet,
 *    composer row, status strip. One number, so nothing on a 400dp portrait
 *    screen has a different left edge from the thing above it.
 *  - [space6] (24dp) is the bottom pad on scrolling content, so the last row
 *    clears the nav bar or the sheet's edge rather than dying against it.
 *  - [space4] between sections; [space2] between transcript rows and between
 *    controls in a row; [space1] for a tight pair; [space05] between a label
 *    and its own description, which is the one gap that is not a gap between
 *    two things but a hyphen between two halves of one.
 *  - Card inner padding is [space3] × [rowPadY] for an option row, and
 *    [toolPadX] × [toolPadY] for a plain tool row — the densest thing in the
 *    app, and the reason those two are off the grid.
 *
 * RADII BY ROLE, which is why there are five of them and not one:
 * [radiusSm] code blocks, thumbnails, tool rows, selectable option cards;
 * [radiusMd] cards; [radiusLg] bubbles and a sheet's inner cards; [pill] the
 * composer field, every search field and every chip; [radiusXl] the sheet's
 * own top corners. [radiusXs] is for a mark rather than a container — the 4dp
 * clip an icon button takes so its press state has an edge.
 *
 * TOUCH TARGETS are not here: [touchTarget] is, and it is
 * `minimumInteractiveComponentSize()`, which grows the *hit box* to 48dp
 * around whatever [IconSize] draws. [rowMin] is the minimum height of a row
 * that is drawn, which is a different number that happens to be the same.
 */
object MD {
    // --- The 4dp grid. -----------------------------------------------------

    /** 2dp. A label and the description under it: one thing, not two. */
    val space05 = 2.dp

    /** 4dp. A tight pair — a count beside the noun it counts. */
    val space1 = 4.dp

    /** 8dp. Between transcript rows, and between controls in a row. */
    val space2 = 8.dp

    /** 12dp. A card's horizontal inner padding. */
    val space3 = 12.dp

    /** 16dp. **The screen gutter**, and the gap between sections. */
    val space4 = 16.dp

    /** 24dp. The bottom pad on scrolling content. */
    val space6 = 24.dp

    /** 32dp. Around an empty state's mark; nothing else needs it. */
    val space8 = 32.dp

    // --- Radii, by role rather than by size. -------------------------------

    /** 4dp — a press state's edge, not a container's. */
    val radiusXs = 4.dp

    /** 8dp — code blocks, thumbnails, tool rows, selectable option cards. */
    val radiusSm = 8.dp

    /** 12dp — cards. */
    val radiusMd = 12.dp

    /** 16dp — bubbles, and the cards inside a sheet. */
    val radiusLg = 16.dp

    /** 24dp — a sheet's own top corners. */
    val radiusXl = 24.dp

    /** 20dp — the composer field, every search field, every chip. */
    val pill = 20.dp

    /**
     * 1dp, and it is the only depth cue the design has besides a fill step.
     *
     * Elevation is zero everywhere in both halves — no shadow, no tonal
     * overlay, `surfaceTint = Color.Transparent` in the scheme — so a hairline
     * in `outlineVariant` is what an edge is made of.
     */
    val hairline = 1.dp

    // --- Heights that recur. -----------------------------------------------

    /** 48dp. A row you can tap, drawn; Android's target floor, drawn. */
    val rowMin = 48.dp

    /** 56dp. A top bar. */
    val barHeight = 56.dp

    /** 36dp. A status strip: a bar that reports rather than a bar that acts. */
    val stripHeight = 36.dp

    // --- The six values that are off the grid, and are right anyway. -------
    //
    // Each of these lost an argument with the 4dp grid on purpose, because the
    // grid is a rhythm for *gaps between blocks* and these are all the inside
    // of one small block. Naming them here is the difference between six
    // considered exceptions and sixty unexamined literals.

    /**
     * 6dp — the gap between a 12-14dp glyph and 11-12sp text beside it.
     *
     * 4dp crowds a mark against its label at this size and 8dp unhooks them;
     * the pair has to read as one phrase, and 6dp is where it does.
     */
    val iconGap = 6.dp

    /** 10dp — the vertical inside an option row. Its partner is [space3]. */
    val rowPadY = 10.dp

    /** 9dp — the vertical inside the composer pill, which sets its height. */
    val composerPadY = 9.dp

    /** 3dp — the vertical on the thinking level pill; 10dp horizontal. */
    val pillPadY = 3.dp

    /** 10dp — the horizontal on the thinking level pill. */
    val pillPadX = 10.dp

    /** 2dp — the vertical on a "Recommended" tag; 7dp horizontal. */
    val tagPadY = 2.dp

    /** 7dp — the horizontal on a "Recommended" tag. */
    val tagPadX = 7.dp

    /** 5dp — the vertical on a collapsed tool row; 8dp horizontal. */
    val toolPadY = 5.dp

    /** 8dp — the horizontal on a collapsed tool row. */
    val toolPadX = 8.dp
}

/**
 * Material's five shape slots, filled from [MD].
 *
 * Handed to the root theme so every stock component — `Card`, `Button`,
 * `AlertDialog`, `DropdownMenu`, `ModalBottomSheet` — takes these without
 * being told at the call site. `extraLarge` is 24dp because that is a sheet's
 * top corner, and a sheet is the component that reaches for it.
 *
 * `pill` deliberately has no slot: Material's scale is a ladder of container
 * radii and a pill is a *shape*, not a rung — the chips and fields that want
 * one say `RoundedCornerShape(MD.pill)`.
 */
val SeekerShapes = Shapes(
    extraSmall = RoundedCornerShape(MD.radiusXs),
    small = RoundedCornerShape(MD.radiusSm),
    medium = RoundedCornerShape(MD.radiusMd),
    large = RoundedCornerShape(MD.radiusLg),
    extraLarge = RoundedCornerShape(MD.radiusXl),
)
