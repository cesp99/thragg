package to.eyed.seeker.code.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/**
 * The legibility solver for the Material half.
 *
 * Ported from spettro-android's `SpettroColors.kt:118-181`, with its argument
 * intact: a palette drawn for a terminal — or, here, for a *syntax* theme — is
 * not a palette that is legible as chrome text, and the amount it is short by
 * is different for every hue and every theme. A fixed darkening factor per
 * appearance was that client's first fix and it was wrong twice over: it did
 * nothing for the accent, and it applied one correction to hues that needed
 * very different ones. So the amount is solved for rather than guessed.
 *
 * Measured over the eleven bundled themes, this is not a theoretical problem.
 * `text.muted` on `panel.background`: Ayu Light **2.79:1**. `text.accent` on
 * `editor.background`: Ayu Light **2.84:1**, One Light **3.84:1**. `created`
 * on a card: Ayu Light **2.11:1**. `warning`: **1.64:1**. Every one of those
 * is a string of chrome text a user is expected to read.
 *
 * WHERE IT APPLIES, AND WHERE IT DELIBERATELY DOES NOT: inks in the **Material
 * half** are solved; inks in the **Zed half** — the editor, the terminal, the
 * diff — are drawn raw, because that half's job is to agree with tree-sitter
 * output and Zed itself draws them raw. That rule is exactly the hybrid
 * boundary docs/VISUAL.md draws, which is why it is the right rule.
 *
 * Pure Kotlin on purpose: no Compose runtime, no `Context`, so the whole thing
 * is host-testable and `ContrastTest` can walk every bundled theme in a JVM
 * test rather than on a device.
 */

/** WCAG 1.4.3 for body text and anything a user has to read. */
const val TEXT_RATIO = 4.5f

/** WCAG 1.4.11: a glyph, a dot or a cell that only has to be *told apart*. */
const val MARK_RATIO = 3.0f

/**
 * 12 halvings resolve the blend to ~0.02%, far finer than 8-bit channels can
 * express, so the answer is exact in practice.
 */
private const val BISECTION_STEPS = 12

/** WCAG 2.x relative-luminance contrast between two opaque colours. */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

/**
 * [color] with its hue kept and its lightness moved the smallest distance that
 * clears [minRatio] against [on]. Returns [color] untouched if it already does.
 *
 * Blending toward an extreme moves contrast monotonically, so a bisection finds
 * the *smallest* adjustment that clears the bar. Hue is preserved exactly,
 * which is the part carrying the meaning: `created` stays the green one, it
 * just becomes a green you can read.
 *
 * The first extreme tried is the one the ground implies — black on a light
 * ground, white on a dark one. It is the right first guess and it is the only
 * one spettro-android needs, because its two canvases are near-white and
 * near-black. Ours are not: `primaryContainer` is an 80% blend toward the
 * surface ladder, and on the Gruvbox Light family it lands at luminance 0.42 —
 * a *mid* ground, where `lum > 0.5` picks white and nothing white-ward can ever
 * reach 4.5:1. Measured, that shipped `onPrimaryContainer` at **2.03:1** and
 * `onErrorContainer` at **1.94:1** on three of the eleven themes. So when the
 * implied extreme cannot get there, the other one is solved for too — still by
 * bisection, so the hue survives as far as it can — and the better of the two
 * answers is returned. On every ground where the first extreme works, this is
 * byte-identical to the spettro version.
 */
fun readable(color: Color, on: Color, minRatio: Float = TEXT_RATIO): Color {
    if (contrastRatio(color, on) >= minRatio) return color
    val implied = if (on.luminance() > 0.5f) Color.Black else Color.White
    val first = solve(color, on, implied, minRatio)
    if (contrastRatio(first, on) >= minRatio) return first
    val other = if (implied == Color.Black) Color.White else Color.Black
    val second = solve(color, on, other, minRatio)
    return if (contrastRatio(second, on) >= contrastRatio(first, on)) second else first
}

/**
 * Whichever of [prefer] / [alt] clears [minRatio] on [on]; black or white if
 * neither does.
 *
 * This is the *label* problem rather than the ink problem: the fill is already
 * decided (a filled button is `primary`, a badge is `error`) and the question
 * is only which of the theme's two existing inks to print on it. Nudging a
 * label's hue would be wrong here — a label is not carrying a colour meaning —
 * so the answer is a choice, and the last resort is a real black or white
 * rather than a compromise. That resort always clears 4.5:1: the worst
 * possible fill is luminance 0.179, where black and white both land at 4.58:1.
 *
 * Today `onPrimary = editor.background` unconditionally (Theme.kt:139), which
 * is a **2.84:1** filled-button label on Ayu Light. This is the fix.
 */
fun pickInk(on: Color, prefer: Color, alt: Color, minRatio: Float = TEXT_RATIO): Color {
    if (contrastRatio(prefer, on) >= minRatio) return prefer
    if (contrastRatio(alt, on) >= minRatio) return alt
    return if (contrastRatio(Color.Black, on) >= contrastRatio(Color.White, on)) {
        Color.Black
    } else {
        Color.White
    }
}

/** The bisection itself: the smallest blend of [color] toward [target] that clears [minRatio]. */
private fun solve(color: Color, on: Color, target: Color, minRatio: Float): Color {
    var low = 0f
    var high = 1f
    repeat(BISECTION_STEPS) {
        val mid = (low + high) / 2f
        if (contrastRatio(lerp(color, target, mid), on) >= minRatio) high = mid else low = mid
    }
    return lerp(color, target, high)
}
