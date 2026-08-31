@file:OptIn(ExperimentalMaterial3Api::class)

package to.eyed.seeker.code.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import to.eyed.seeker.code.R
import to.eyed.seeker.code.ui.theme.SeekerIconButton
import to.eyed.seeker.code.ui.theme.mutedIcon

/**
 * Every screen's top bar: a real `TopAppBar`, with the second line the app
 * kept hand-rolling.
 *
 * The bars this replaces were `Row`s with a hard-coded height, a hand-placed
 * back arrow and no insets — which is why the agent's bar was 48dp and the
 * build's was 56dp, and why neither of them announced itself as a bar to
 * TalkBack. Taking the stock component brings the window insets, the title
 * semantics, the scroll behaviour and the action-row spacing for free, and
 * those are the parts nobody hand-rolls correctly twice.
 *
 * COLOURS ARE FLAT AND STAY FLAT: `containerColor` and
 * `scrolledContainerColor` are both `surface`, so nothing tints as content
 * passes under it. With elevation pinned to zero across the app, a bar that
 * changed colour on scroll would be the only surface in the design that moved
 * on the z axis, and the seam under it — a [HairlineDivider] the caller draws
 * — carries the separation instead (docs/VISUAL.md, "Foundations",
 * ELEVATION).
 *
 * THE SUBTITLE IS DRAWN HERE rather than taken from material3's
 * `TopAppBar(title, subtitle, …)`: that overload exists in 1.4.0 but is part
 * of the expressive surface, which is Kotlin-`internal` in this version — the
 * same wall Theme.kt documents for `MaterialExpressiveTheme`. Two `Text`s in
 * the title slot cost nothing and give the pair the exact metrics the spec
 * asks for: `titleLarge` over `labelMedium` in `onSurfaceVariant`, one line
 * each, both ellipsised, because a project name and a "Spettro · coding"
 * status are both things that can be arbitrarily long and neither may push the
 * actions off a 400dp bar.
 *
 * CALLERS NEED ONE LINE: `@file:OptIn(ExperimentalMaterial3Api::class)`.
 * `TopAppBar`, `TopAppBarDefaults` and `TopAppBarScrollBehavior` are all still
 * `@ExperimentalMaterial3Api` in 1.4.0, and Kotlin's opt-in propagates through
 * a signature — [scrollBehavior]'s type is experimental, so opting in here
 * covers this file's own use of the API but not a screen's call to it. The
 * parameter stays anyway, and keeping it is the right trade: a bar that cannot
 * take a scroll behaviour cannot collapse, and any screen that wants one has
 * to name `TopAppBarDefaults` — the same annotation — regardless.
 *
 * [onBack] null means this is a root destination and no arrow is drawn — the
 * three shell destinations have their own back stacks and a bar that always
 * shows an arrow teaches the wrong thing about where back goes.
 */
@Composable
fun SeekerTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                SeekerIconButton(
                    icon = R.drawable.ic_ui_arrow_left,
                    description = "Back",
                    onClick = onBack,
                    tint = mutedIcon,
                )
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        scrollBehavior = scrollBehavior,
    )
}
