package to.eyed.thragg.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * No ripple, anywhere inside [ZedSurface].
 *
 * gpui has no transition machinery and Zed's chrome swaps colours instantly on
 * hover and press (ui/src/styles/animation.rs has a vocabulary and almost no
 * callers) — the animated Material ripple is the loudest single "not Zed" tell
 * a Compose port can carry. Widgets that want press feedback draw their own
 * state from the interaction source, exactly as Zed's components restate their
 * hover/active fills.
 *
 * Moved here out of Theme.kt, and **scoped**, because the argument above is an
 * argument about the *editor*. In a Material sheet the inverse is true and
 * louder: a row that does not respond to a press is the clearest possible tell
 * that a screen is not a real Android app. Both halves were right; neither was
 * right everywhere (docs/VISUAL.md, "THE BOUNDARY, EXACTLY").
 */
internal object NoIndication : IndicationNodeFactory {
    private class NoopNode : Modifier.Node()
    override fun create(interactionSource: InteractionSource): DelegatableNode = NoopNode()
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * The Zed half: everything that has to agree with tree-sitter's output.
 *
 * The root theme is now the MATERIAL half — ripple on, [materialTypography],
 * no layout-direction pin — and this is the explicit way back in. Wrap the
 * editor pane, the terminal dock, the diff panes, the markdown preview and the
 * search/diagnostics roots in it. Inside, the shared [MaterialTheme.colorScheme]
 * is unchanged (both halves come out of the same `ZedTheme.palette()`, so they
 * cannot disagree by a frame) and three things are re-provided:
 *
 *  - **[zedTypography]** at the live `ui_font_size`. The editor's chrome is a
 *    function of that setting because Zed's is: `window.rem_size =
 *    ui_font_size`, so a user who sets 20 must get 20/17.5/15/12.5. The
 *    Material half must *not* do this, which is why the two scales are two
 *    functions and this is where they change over.
 *  - **[NoIndication]**, which restores Zed's no-ripple rule.
 *  - **LTR**, because the editor draws indent guides, tab borders and focus
 *    rails at absolute x in `drawBehind`. Mirroring the layout under an RTL
 *    locale would put them on the wrong side of rows that did mirror. Pinning
 *    it here rather than at the root is what finally makes the *app* half
 *    RTL-correct — the pin was global, and its reason never was.
 *  - **[LocalIconTint]**, which puts [mutedIcon] and [accentIcon] back on the
 *    raw Zed reads. Those two are the default tint of nearly every icon in the
 *    app; in the Material half they resolve from the solved M3 scheme, and
 *    this is the override that keeps the editor's icons matching the buffer
 *    beside them.
 *
 * ORDER MATTERS, and not in the obvious way. The [CompositionLocalProvider] is
 * *inside* [MaterialTheme], not outside it, because `MaterialTheme` provides
 * `LocalIndication` itself — `val rippleIndication = ripple()` is one of the
 * six locals its body installs. A provider wrapped around it is shadowed by
 * it. That is a live bug in the code this replaces: Theme.kt has provided
 * [NoIndication] outside `MaterialTheme` ever since material3 started
 * installing ripple in its own body, so the no-ripple rule the comment
 * describes has not actually been in force.
 *
 * Shapes are inherited rather than restated: the 3-argument `MaterialTheme`
 * defaults `shapes` to the current one, so [ThraggShapes] carries through. The
 * Zed half measures its own corners in [ZedRadius] and only reaches for the
 * Material scale through stock components, which should look the same on both
 * sides of the seam.
 */
@Composable
fun ZedSurface(content: @Composable () -> Unit) {
    val theme = LocalZedTheme.current
    val uiFontSize = LocalUiFontSize.current
    val family = LocalUiFontFamily.current
    val iconTint = remember(theme) {
        IconTint(
            muted = theme.color("text.muted"),
            accent = theme.color("text.accent"),
        )
    }
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = remember(uiFontSize, family) { zedTypography(uiFontSize, family) },
    ) {
        CompositionLocalProvider(
            LocalIndication provides NoIndication,
            LocalLayoutDirection provides LayoutDirection.Ltr,
            LocalIconTint provides iconTint,
            content = content,
        )
    }
}
