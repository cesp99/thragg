package to.eyed.seeker.code.ui.git

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.GitBranch
import to.eyed.seeker.code.ui.theme.LocalReduceMotion
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.workspace.ContextMenu
import to.eyed.seeker.code.ui.workspace.ContextMenuItem

/**
 * The remote split button — Zed's `render_remote_button`
 * (crates/git_ui/src/git_ui.rs:785-838): one button whose face answers "what
 * would syncing this branch mean right now", with the whole remote menu behind
 * its chevron.
 *
 * The face is a pure function of the branch record, kept apart from the
 * drawing so the decision tree is checkable on the host ([remoteButtonSpec]);
 * the composables below only dress what it decided.
 */

/** What a plain click on the button's left half dispatches. */
enum class RemoteButtonAction { Fetch, Push, Pull }

/** The left-hand glyph a variant carries, when it carries one. */
enum class RemoteButtonIcon { ArrowCircle, ExpandUp }

/** The button's face: its word, its glyph, and the drift it reports. */
data class RemoteButtonSpec(
    val label: String,
    val icon: RemoteButtonIcon?,
    val ahead: Int,
    val behind: Int,
    val action: RemoteButtonAction,
) {
    /**
     * Counts render only when there is no left icon and something has
     * actually drifted (git_ui.rs:1078-1108) — so Fetch, Publish and
     * Republish never show them, and "Push"/"Pull" always do.
     */
    val showsCounts: Boolean get() = icon == null && (ahead > 0 || behind > 0)
}

/**
 * Zed's decision tree over the branch's upstream (git_ui.rs:785-838), row for
 * row. Null is "render no button at all": no branch to speak for — a detached
 * HEAD, or a repository with nothing committed (git_panel.rs:5851, and the
 * handlers' own early-returns at git_panel.rs:3837, 3908).
 *
 * `show_fetch_button` is hard-coded true in Zed (git_panel.rs:5853), so the
 * in-sync row is always "Fetch" and never nothing.
 */
fun remoteButtonSpec(branch: GitBranch?): RemoteButtonSpec? {
    if (branch?.name == null || branch.unborn) return null
    val tracked = branch.hasUpstream && !branch.upstreamGone
    return when {
        // Behind at all — any ahead — is a Pull, wearing both counts.
        tracked && branch.behind > 0 -> RemoteButtonSpec(
            label = "Pull",
            icon = null,
            ahead = branch.ahead,
            behind = branch.behind,
            action = RemoteButtonAction.Pull,
        )
        tracked && branch.ahead > 0 -> RemoteButtonSpec(
            label = "Push",
            icon = null,
            ahead = branch.ahead,
            behind = 0,
            action = RemoteButtonAction.Push,
        )
        // In sync: offer a fetch, under the circling arrows.
        tracked -> RemoteButtonSpec(
            label = "Fetch",
            icon = RemoteButtonIcon.ArrowCircle,
            ahead = 0,
            behind = 0,
            action = RemoteButtonAction.Fetch,
        )
        // The upstream is configured but its ref was deleted on the remote:
        // "Republish", and the push re-creates it with `--set-upstream`
        // (git_ui.rs:822-829).
        branch.upstreamGone -> RemoteButtonSpec(
            label = "Republish",
            icon = RemoteButtonIcon.ExpandUp,
            ahead = 0,
            behind = 0,
            action = RemoteButtonAction.Push,
        )
        // Never pushed at all: "Publish" (git_ui.rs:831-836).
        else -> RemoteButtonSpec(
            label = "Publish",
            icon = RemoteButtonIcon.ExpandUp,
            ahead = 0,
            behind = 0,
            action = RemoteButtonAction.Push,
        )
    }
}

/**
 * A remote choice the panel is waiting on — the state behind
 * [RemotePickerDialog]. Zed's `picker_prompt::prompt`, which its Fetch From,
 * Push To, and every unconfigured pull and push funnel through
 * (git_panel.rs:4160-4166).
 */
internal class RemotePickerRequest(
    /** The picker's placeholder line — "Pick which remote to …". */
    val prompt: String,
    val options: List<String>,
    val onPick: (String) -> Unit,
)

/**
 * Zed's remote picker is a modal fuzzy `Picker` whose search-field
 * placeholder is the prompt (picker_prompt.rs:42, 122-124); with the handful
 * of names a remote list holds, ours is the same modal as a plain list — the
 * prompt as its title, one row per remote. The zero- and one-option cases
 * never get here: the caller resolves them without a modal, as Zed does
 * (picker_prompt.rs:27-31).
 */
@Composable
internal fun RemotePickerDialog(request: RemotePickerRequest, onDismiss: () -> Unit) {
    val theme = LocalZedTheme.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.prompt) },
        text = {
            Column {
                for (option in request.options) {
                    val interaction = remember { MutableInteractionSource() }
                    val hovered by interaction.collectIsHoveredAsState()
                    Text(
                        text = option,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (hovered) {
                                    theme.color(
                                        "ghost_element.hover",
                                        MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                } else {
                                    Color.Transparent
                                }
                            )
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                                onClickLabel = option,
                            ) {
                                onDismiss()
                                request.onPick(option)
                            }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The split button itself, in [CommitSplitButton]'s clothes — Zed builds both
 * from the same `SplitButton` (git_ui.rs:1146): the action on the left, a
 * 20px chevron on the right deploying the remote menu, one `border`-at-0.8
 * ring around the pair (split_button.rs:71-95).
 *
 * While a remote command runs the left half is disabled with its label in the
 * disabled colour, and a variant's icon is swapped for the loading circle
 * turning on a 2s clock (git_ui.rs:1110-1123). The chevron stays live — the
 * menu can be read, and a second command is refused with words, not greyed
 * away (git_panel.rs:4116-4118 refuses too, silently).
 */
@Composable
internal fun RemoteSplitButton(
    spec: RemoteButtonSpec,
    enabled: Boolean,
    /** A fetch, pull or push is in flight — what turns the spinner. */
    remotePending: Boolean,
    onFetch: () -> Unit,
    onFetchFrom: () -> Unit,
    onPull: () -> Unit,
    onPullRebase: () -> Unit,
    onPush: () -> Unit,
    onPushTo: () -> Unit,
    onForcePush: () -> Unit,
) {
    val theme = LocalZedTheme.current
    var menuOpen by remember { mutableStateOf(false) }
    val leftInteraction = remember { MutableInteractionSource() }
    val leftHovered by leftInteraction.collectIsHoveredAsState()
    val leftPressed by leftInteraction.collectIsPressedAsState()
    val rightInteraction = remember { MutableInteractionSource() }
    val rightHovered by rightInteraction.collectIsHoveredAsState()
    val rightPressed by rightInteraction.collectIsPressedAsState()
    val fill = theme.color("background")
    val ring = theme.color("border").copy(alpha = 0.8f)
    val shape = RoundedCornerShape(4.dp)
    val textColour = theme.color(if (enabled) "text" else "text.disabled")
    val onAction = when (spec.action) {
        RemoteButtonAction.Fetch -> onFetch
        RemoteButtonAction.Push -> onPush
        RemoteButtonAction.Pull -> onPull
    }
    // As [FilledButton]: the 18dp pill is the visual, the tap target is the
    // taller invisible wrapper (density decision, DECISIONS.md).
    Box(
        modifier = Modifier
            .heightIn(min = 30.dp)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(18.dp)
                .clip(shape)
                .border(1.dp, ring, shape),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .then(
                        if (enabled) {
                            Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(
                                    interactionSource = leftInteraction,
                                    indication = null,
                                    onClickLabel = spec.label,
                                    onClick = onAction,
                                )
                        } else {
                            Modifier
                        }
                    )
                    .background(
                        when {
                            leftPressed && enabled -> theme.color("element.active")
                            leftHovered && enabled -> fill.copy(alpha = fill.alpha * 0.5f)
                            else -> fill
                        }
                    )
                    .padding(start = 4.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (spec.icon != null) {
                    if (remotePending) {
                        // The variant's glyph steps aside for the loading
                        // circle, turning once every two seconds
                        // (git_ui.rs:1110-1123).
                        val spin by rememberInfiniteTransition(label = "remote-op")
                            .animateFloat(
                                initialValue = 0f,
                                targetValue = 360f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(durationMillis = 2000, easing = LinearEasing),
                                    repeatMode = RepeatMode.Restart,
                                ),
                                label = "remote-op-turn",
                            )
                        // Still under `reduce_motion`, as Zed renders its own.
                        val turn = if (LocalReduceMotion.current) 0f else spin
                        Image(
                            painter = painterResource(R.drawable.ic_ui_load_circle),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(theme.color("text.disabled")),
                            modifier = Modifier.size(12.dp).rotate(turn),
                        )
                    } else {
                        Image(
                            painter = painterResource(
                                when (spec.icon) {
                                    RemoteButtonIcon.ArrowCircle -> R.drawable.ic_ui_arrow_circle
                                    RemoteButtonIcon.ExpandUp -> R.drawable.ic_ui_expand_up
                                }
                            ),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(textColour),
                            // `IconSize::XSmall` = 12px (git_ui.rs:1131-1136).
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
                Text(
                    text = spec.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = textColour,
                )
                if (spec.showsCounts) {
                    // Behind first, then ahead, each an XSmall arrow with its
                    // number (git_ui.rs:1078-1108) — so a "Pull" wears both
                    // when both are nonzero.
                    if (spec.behind > 0) {
                        RemoteCount(R.drawable.ic_ui_arrow_down, spec.behind, textColour)
                    }
                    if (spec.ahead > 0) {
                        RemoteCount(R.drawable.ic_ui_arrow_up, spec.ahead, textColour)
                    }
                }
            }
            // `border_l` between the halves (split_button.rs:88-95).
            Box(Modifier.width(1.dp).fillMaxHeight().background(ring))
            Box(
                modifier = Modifier
                    .width(20.dp)
                    .fillMaxHeight()
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        interactionSource = rightInteraction,
                        indication = null,
                        onClickLabel = "Remote actions",
                    ) { menuOpen = !menuOpen }
                    .background(
                        when {
                            rightPressed || menuOpen -> theme.color("element.active")
                            rightHovered -> fill.copy(alpha = fill.alpha * 0.5f)
                            else -> fill
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(
                        if (menuOpen) R.drawable.ic_ui_chevron_up else R.drawable.ic_ui_chevron_down
                    ),
                    contentDescription = if (menuOpen) "Close remote actions" else "Remote actions",
                    colorFilter = ColorFilter.tint(theme.color("text")),
                    // `IconSize::XSmall` = 12px (git_ui.rs:1160).
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        // The remote menu, entry for entry and in order — Zed's
        // `render_git_action_menu` (git_ui.rs:1027-1061). No entry is ever
        // disabled there, and the chord labels are the GitPanel keymap's
        // (default-linux.json:1060-1068); Fetch From and Push To ship
        // unbound.
        ContextMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            offset = DpOffset(0.dp, 2.dp),
            items = listOf(
                ContextMenuItem(label = "Fetch", shortcut = "Ctrl G Ctrl G", onClick = onFetch),
                ContextMenuItem(label = "Fetch From", onClick = onFetchFrom),
                ContextMenuItem(label = "Pull", shortcut = "Ctrl G Down", onClick = onPull),
                ContextMenuItem(
                    label = "Pull (Rebase)",
                    shortcut = "Ctrl G Shift Down",
                    onClick = onPullRebase,
                ),
                // The separator between the fetch-and-pull group and the
                // pushes (git_ui.rs:1050).
                ContextMenuItem(
                    label = "Push",
                    shortcut = "Ctrl G Up",
                    separatorAbove = true,
                    onClick = onPush,
                ),
                ContextMenuItem(label = "Push To", onClick = onPushTo),
                ContextMenuItem(
                    label = "Force Push",
                    shortcut = "Ctrl G Shift Up",
                    onClick = onForcePush,
                ),
            ),
        )
    }
}

/** One drift count: an XSmall arrow beside its number (git_ui.rs:1078-1096). */
@Composable
private fun RemoteCount(icon: Int, count: Int, colour: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colour),
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = colour,
        )
    }
}
