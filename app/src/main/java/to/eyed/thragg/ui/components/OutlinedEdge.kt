package to.eyed.thragg.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import to.eyed.thragg.ui.theme.MD

/**
 * The edge on every [androidx.compose.material3.OutlinedButton] in the app.
 *
 * MATERIAL'S DEFAULT IS THE WRONG ROLE UNDER THIS SCHEME. material3 1.4.0
 * draws an outlined button's border in `outlineVariant`
 * (`OutlinedButtonTokens.OutlineColor`), which `MaterialBridge` derives from
 * Zed's `border.variant` — the same value [HairlineDivider] uses, a colour
 * whose entire job is to seam two surfaces WITHOUT being looked at. On a
 * `surfaceContainer` sheet it disappears, and the filled/outlined pair then
 * reads as filled + TEXT: Material's grammar for "an action and a way out of
 * it" rather than "two things you can do". That is the wrong sentence on
 * Projects (New program / Clone), on Changes and Commit (Commit / Commit &
 * Push), on Diff (Reject / Keep, Discard / Stage) and on the context sheet
 * (Compact / New thread) — eight pairs, one cause.
 *
 * THE ACCENT AT HALF STRENGTH is the answer, not a darker neutral: it is the
 * same hairline the composer's focused field warms to, so the edge is
 * unmistakably an edge, and at 50% it is still quieter than the filled button
 * standing beside it. The width is unchanged — 1dp is what the token asks for
 * and what [MD.hairline] is — because the defect was never that the line was
 * thin, it was that the line was not there.
 *
 * THE DISABLED CASE keeps Material's own shape: the border fades with the
 * button rather than staying at full accent on a control that will not
 * answer. 12% is `OutlinedButtonTokens.DisabledContainerOpacity` rounded to
 * the alpha the rest of the app disables with; the exact figure matters less
 * than that a disabled edge reads as absent while an enabled one reads as an
 * edge.
 *
 * [color] EXISTS FOR ONE CASE and should stay rare: a button whose CONTENT is
 * not the ordinary ink. `PermissionSheet`'s reject option draws its label in
 * `error` — deliberately, so a deny is legible as a deny without becoming a
 * filled red button beside a filled primary one — and an accent edge round
 * red text is two answers about the same control. The edge follows the
 * content there. It does not follow it anywhere else: an outlined button with
 * an ordinary label gets the accent, or the grammar the app is enforcing
 * stops meaning anything.
 *
 * This is a value and not a wrapper composable on purpose. Nine call sites
 * pass nine different combinations of `shape`, `colors`, `contentPadding`
 * and `enabled`; a `ThraggOutlinedButton` would have to re-declare all of
 * them and would then be a copy of `OutlinedButton` with one line changed.
 * `border = outlinedButtonEdge()` is the one decision, made once, spelled at
 * the site so it is visible in the diff. `OutlinedButtonEdgeTest` is what
 * keeps a tenth site from forgetting it.
 */
@Composable
fun outlinedButtonEdge(
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
): BorderStroke = BorderStroke(
    width = MD.hairline,
    color = color.copy(alpha = if (enabled) 0.5f else 0.12f),
)
