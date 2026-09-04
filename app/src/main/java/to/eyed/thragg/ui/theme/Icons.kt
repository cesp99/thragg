package to.eyed.thragg.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.eyed.thragg.R

/**
 * The one place an icon's size is decided.
 *
 * Before this file the app had two ways of drawing an icon and neither of them
 * had a size. Most chrome controls drew a **Unicode character in a `Text`** —
 * `⌕` for search, `☰` for the file tree, `⋮` for an overflow menu, about
 * seventy of them — and the rest drew a `painterResource` with a `.size()`
 * literal written at the call site. Both are wrong in the same way: nobody
 * decides how big an icon is, so nothing agrees.
 *
 * A glyph in a `Text` is worse than merely undecided. It renders at the
 * *font's* optical size and stroke weight rather than at an icon metric, which
 * on the Seeker's 480dpi panel is why they came out thin and small beside the
 * drawables next to them; the size it lands at is whatever the type scale
 * says, so the same mark drew at three sizes depending on whether the row was
 * `labelSmall` or `titleMedium`; and the codepoint has to exist in the font,
 * so a device whose UI face has no `⛨` or `⌂` draws tofu where a control
 * should be. There is no font fallback story for a control.
 *
 * So: real drawables, and these four sizes.
 *
 *  - [Nav] — 24dp, the bottom bar. Material's navigation-bar icon size, and
 *    the number every other Android app's bottom bar uses; the shell drew 20dp
 *    for a while, which is why its three items read as smaller than the phone's
 *    own chrome.
 *  - [Action] — 22dp, an icon that *is* a button: the file bar's two, the
 *    header's search and overflow, the composer's row. Zed's
 *    `ButtonSize::Default` is `rems_from_px(22)`, so this is the density the
 *    2026-08-17 decision fixed, now stated once instead of implied by a font
 *    size. Its **touch** target is 48dp regardless — see [touchTarget].
 *  - [Inline] — 18dp, an icon sitting inside a line of body text: a card's
 *    leading mark, a sheet row's chevron. Big enough to read, small enough not
 *    to set the row's height.
 *  - [Marker] — 14dp, a *status* mark rather than a control: the dot beside a
 *    finished tool call, the tick on a plan row. Zed's `IconSize::Small` is
 *    `rems_from_px(14)` and this is the same number, because these sit beside
 *    `labelSmall` and an icon taller than its own caption is a bullet point
 *    that shouts.
 *
 * [Hero] is the one exception, and it has one call site: the Setup masthead,
 * where the mark is the artwork rather than a control.
 *
 * The drawables themselves are imported at an intrinsic 16dp on a 24 viewport
 * (tools/import-lucide-icons.py). That intrinsic is not a limit — a
 * VectorDrawable is geometry, so drawing one at 24dp costs nothing and loses
 * nothing, and the stroke scales with it: 1.8 viewport units is 1.2dp at 16dp
 * and 1.8dp at 24dp, which is exactly the extra weight the nav bar wanted.
 */
object IconSize {
    /** The bottom navigation bar. Material's number, not a smaller one. */
    val Nav: Dp = 24.dp

    /** An icon-only button in a bar or a row. */
    val Action: Dp = 22.dp

    /** An icon that shares a line with body text. */
    val Inline: Dp = 18.dp

    /** A status mark beside `labelSmall`. Zed's `IconSize::Small`. */
    val Marker: Dp = 14.dp

    /** The Setup masthead, and nothing else. */
    val Hero: Dp = 40.dp
}

/**
 * One icon, tinted, at one of [IconSize]'s sizes.
 *
 * [contentDescription] is not optional-by-accident. Pass the words a screen
 * reader should say when this icon is the only thing identifying a control,
 * and `null` only when the icon is decoration beside a label that already says
 * it — in which case the surrounding row must carry the description instead.
 * An icon-only button with no description is a control TalkBack announces as
 * "button", which is the same as not having it.
 */
@Composable
fun ThraggIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = IconSize.Action,
) {
    // `Image` with a null description emits no semantics node at all, which is
    // exactly right for decoration — so there is deliberately nothing here that
    // clears or overrides what the caller passed in `modifier`.
    Image(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}

/**
 * An icon that is a button: [IconSize.Action] of ink inside 48dp of target.
 *
 * The 48dp is Android's accessibility floor (and WCAG 2.5.8), and on a phone
 * held in one hand it is not a formality — the thumb's contact patch is wider
 * than the glyph. [touchTarget] grows the *hit box* and leaves the drawn icon
 * alone, so this looks like the dense chrome it replaced and behaves like a
 * control you can actually hit.
 *
 * [description] is both the label a screen reader reads and the action
 * announced for the tap, so it should name what happens ("Search in files")
 * rather than what is drawn ("magnifier").
 */
@Composable
fun ThraggIconButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    tint: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = IconSize.Action,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .touchTarget()
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick),
    ) {
        ThraggIcon(icon = icon, contentDescription = description, tint = tint, size = size)
    }
}

/**
 * The two inks the Zed half draws its icons in, when it is the Zed half doing
 * the drawing.
 *
 * Null everywhere else, which is what makes [mutedIcon] and [accentIcon] work
 * on both sides of the seam without a parameter: absent means "we are in the
 * Material half, solve it from the scheme", and [ZedSurface] is the only thing
 * that provides it.
 */
@Immutable
class IconTint(val muted: Color, val accent: Color)

/** [IconTint] for the editor half; null in the app half. See [ZedSurface]. */
val LocalIconTint = staticCompositionLocalOf<IconTint?> { null }

/**
 * The muted ink every chrome icon defaults to.
 *
 * These two definitions are the highest-leverage lines in the redesign, which
 * is why they are worth a long comment on a short body. They are the default
 * `tint` of [RowChevron], [DisclosureMark], [ChipCaret] and [SelectionMark] —
 * that is, of very nearly every icon in every sheet and every row in the app —
 * so what they resolve to *is* the tint of the Material half. Changing them
 * here retints it in one edit instead of at four hundred call sites.
 *
 * In the Material half the answer is `onSurfaceVariant`, which the bridge has
 * already solved to 4.5:1 against `surface` (MaterialBridge.kt). It used to be
 * `theme.color("text.muted", …)` — a raw Zed read whose Material fallback
 * never fired — and on Ayu Light that lands at 2.79:1, which is the whole
 * argument for the solver.
 *
 * In the Zed half [ZedSurface] provides [LocalIconTint] and the raw Zed reads
 * come back, because that half's job is to look like Zed and Zed draws them
 * raw beside tree-sitter output that is also raw.
 */
val mutedIcon: Color
    @Composable get() =
        LocalIconTint.current?.muted ?: MaterialTheme.colorScheme.onSurfaceVariant

/**
 * The ink of an icon that *is* carrying a state: selected, accented, live.
 *
 * `accentMark` rather than `primary`: an icon is a mark, not text, so it is
 * solved at 3:1 against the ground a card actually has rather than at 4.5:1 —
 * pushing an accent further than it needs to go is how a theme's identity gets
 * washed out one role at a time.
 */
val accentIcon: Color
    @Composable get() =
        LocalIconTint.current?.accent ?: LocalThraggColors.current.accentMark

/**
 * The `›` at the end of a row that opens something.
 *
 * Its own composable because it appeared, as a literal `›`, in nine places at
 * four different type scales — which is how the same mark ended up three
 * different sizes down one screen. Always decoration: the row it sits in has
 * the click label, and "chevron" is not information.
 */
@Composable
fun RowChevron(modifier: Modifier = Modifier, tint: Color = mutedIcon) {
    ThraggIcon(
        icon = R.drawable.ic_ui_chevron_right,
        contentDescription = null,
        tint = tint,
        size = IconSize.Marker,
        modifier = modifier,
    )
}

/**
 * A disclosure mark: down when the thing is open, right when it is closed.
 *
 * Right rather than up for closed, which is the vocabulary a list uses — `⌃`
 * and `⌄` are a pair for a *card* that collapses upward, and a row that opens
 * downward points at where its content will appear.
 */
@Composable
fun DisclosureMark(open: Boolean, modifier: Modifier = Modifier, tint: Color = mutedIcon) {
    ThraggIcon(
        icon = if (open) {
            R.drawable.ic_ui_chevron_down
        } else {
            R.drawable.ic_ui_chevron_right
        },
        contentDescription = null,
        tint = tint,
        size = IconSize.Marker,
        modifier = modifier,
    )
}

/**
 * The caret on a *chip* that opens a picker: a header's project name, a
 * branch name, a filter's current value.
 *
 * Down, always, and deliberately not [DisclosureMark]. The two marks are
 * different sentences. A disclosure says "this row expands in place", and
 * points right when closed because that is where a list's children appear.
 * A chip caret says "there is a menu under this", which is what the `▾` these
 * replaced meant and what Material's exposed dropdown draws — pointing it
 * right would promise a screen the chip does not push.
 *
 * Decoration: the chip's own click label says what it opens.
 */
@Composable
fun ChipCaret(modifier: Modifier = Modifier, tint: Color = mutedIcon) {
    ThraggIcon(
        icon = R.drawable.ic_ui_chevron_down,
        contentDescription = null,
        tint = tint,
        size = IconSize.Marker,
        modifier = modifier,
    )
}

/**
 * Radio for one, checkbox for many — the mark that says whether a second tap
 * adds to the answer or replaces it.
 *
 * Decoration by default: the row carries the option's label and its selected
 * state through `selectable`/`toggleable` semantics, and a mark that also
 * announced itself would say "selected" twice.
 */
@Composable
fun SelectionMark(
    selected: Boolean,
    multi: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = IconSize.Inline,
) {
    ThraggIcon(
        icon = when {
            multi && selected -> R.drawable.ic_ui_checkbox_checked
            multi -> R.drawable.ic_ui_checkbox
            selected -> R.drawable.ic_ui_circle_dot
            else -> R.drawable.ic_ui_circle
        },
        contentDescription = null,
        tint = tint,
        size = size,
        modifier = modifier,
    )
}
