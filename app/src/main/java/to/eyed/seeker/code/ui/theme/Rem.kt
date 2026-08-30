package to.eyed.seeker.code.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * Zed's rem, which is the unit its entire chrome is measured in.
 *
 * gpui sets `window.rem_size = ui_font_size`
 * (`theme_settings/src/settings.rs:619`), so every `rems(x)` and every
 * `DynamicSpacing::BaseNN` in Zed's UI resolves against the user's UI font
 * size rather than against a constant. That is why bumping `ui_font_size` in
 * Zed grows the tab bar, the rows and the gaps together instead of only the
 * text: the numbers *are* multiples of it. A port that hardcodes 16 has the
 * setting do nothing, which is item 24 of the look spec.
 *
 * Chrome should therefore reach for [rem] and [remSp] rather than writing
 * `.dp` literals, using the spec's own table: 1rem = 16dp at the default, so
 * Zed's 32px tab bar is `rem(2f)` and its 4px gap is `rem(0.25f)`.
 *
 * **Not everything in Zed is a rem, and the difference is not cosmetic.** A
 * number written `px(…)` in the source stays that many pixels however big the
 * UI font is: the 1px borders (`border_1`, styles.rs), the project panel's
 * `indent_size` setting (`px(settings.indent_size)`,
 * project_panel.rs:6140), its guide offset and end padding
 * (`LIST_ITEM_INDENT_GUIDE_LEFT_OFFSET = px(15.)`, indent_guides.rs:33;
 * `PADDING_Y = px(4.)`, project_panel.rs:7215) and the tab's start/end slots
 * (`px(12.)` / `px(14.)`, tab.rs:8-9) are all px in Zed and stay `.dp` here.
 * Converting them would make the app *diverge* from Zed at any non-default
 * font size, which is the opposite of the point.
 *
 * `DynamicSpacing::BaseNN` is a rem despite the name and despite `.px(cx)`
 * being one of its two spellings — the macro divides NN by a constant 16 and
 * multiplies by the live `ui_font_size` (ui_macros/src/dynamic_spacing.rs:
 * 147-162), so `BaseNN` is exactly `rem(NN / 16)`. So are `IconSize`
 * (`rems_from_px(14)` for Small — icon.rs:70-78) and `ButtonSize`
 * (`rems_from_px(22)` for Default — button_like.rs:465-473).
 */
val LocalUiFontSize = staticCompositionLocalOf { ThemeStore.DEFAULT_UI_FONT_SIZE }

/** `rems(x)` in dp, at the user's UI font size. */
@Composable
@ReadOnlyComposable
fun rem(multiple: Float): Dp = remsAt(LocalUiFontSize.current, multiple)

/** The same, for a text size. */
@Composable
@ReadOnlyComposable
fun remSp(multiple: Float): TextUnit = (LocalUiFontSize.current * multiple).sp

/**
 * The arithmetic behind [rem], with the composition local factored out.
 *
 * Every chrome metric goes through here, so a host test can pin the whole
 * table at any `ui_font_size` without a Compose runtime — which is how the
 * "nothing moved at 16" invariant is checked rather than asserted in prose.
 */
fun remsAt(uiFontSize: Float, multiple: Float): Dp = (uiFontSize * multiple).dp

/**
 * Zed's corner scale, which is a rem scale like everything else.
 *
 * `rounded_xs`/`sm`/`md`/`lg` are `rems(0.125)`, `rems(0.25)`, `rems(0.375)`
 * and `rems(0.5)` — 2, 4, 6 and 8px at the default rem, and the numbers the
 * house style quotes (gpui_macros/src/styles.rs:1235-1253). `rounded_none` is
 * the only one written in pixels, and it is zero.
 *
 * Checkbox and keycap chips are `xs`; buttons and list rows `sm`; inputs `md`;
 * menus and modals `lg`.
 */
object ZedRadius {
    const val XS = 0.125f
    const val SM = 0.25f
    const val MD = 0.375f
    const val LG = 0.5f
}

/**
 * How tall the *ink* of a one-line [style] is, in dp.
 *
 * Chrome boxes are dp and the text inside them is sp, and those two scale
 * independently: `ui_font_size` moves the rem, and the *system's* font scale
 * moves only the sp. So a box that is exactly right at Zed's metrics —
 * a 22dp `ButtonSize::Default`, a 26dp panel row — starts cutting the tops off
 * ascenders once the user turns Android's font size up, because the glyph
 * outgrew the box that never heard about it.
 *
 * This is the size to compare a box against. It is deliberately *not* the line
 * height: `TextStyle.lineHeight` is Zed's φ leading (1.618em), most of which is
 * empty space above and below the letters, and a box sized to it would be 45%
 * taller than it needs to be at every ordinary font scale — which would change
 * the chrome at `ui_font_size` = 16, where nothing is supposed to change.
 *
 * [GLYPH_EXTENT] is the em box the letters themselves occupy: IBM Plex Sans,
 * the UI face we ship, has an ascender of 1.025em and a descender of 0.275em,
 * so 1.3em covers every glyph in the font with nothing to spare and nothing
 * wasted. At the default font scale this is 18.2dp for 14sp chrome text, well
 * inside every box Zed specifies, so `max(box, glyphHeight)` leaves the whole
 * chrome untouched — and grows it, box and all, exactly when it would
 * otherwise start clipping.
 */
const val GLYPH_EXTENT = 1.3f

@Composable
@ReadOnlyComposable
fun glyphHeight(style: TextStyle): Dp {
    val size = style.fontSize
    if (!size.isSpecified || size.type != TextUnitType.Sp) return 0.dp
    return with(LocalDensity.current) { size.toDp() } * GLYPH_EXTENT
}
