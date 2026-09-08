package to.eyed.thragg.ui.shell.build

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import to.eyed.thragg.R
import to.eyed.thragg.solana.build.BuildAction
import to.eyed.thragg.solana.build.BuildRunner
import to.eyed.thragg.solana.build.ProjectLayout
import to.eyed.thragg.ui.components.elapsedLabel
import to.eyed.thragg.ui.shell.BuildState
import to.eyed.thragg.ui.shell.Route
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.theme.Durations
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.LocalThraggColors
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.TabularNums
import to.eyed.thragg.ui.theme.ThraggIcon
import to.eyed.thragg.ui.theme.effectSpec
import to.eyed.thragg.ui.theme.pressScale

/**
 * The product's verbs, under the thumb: `[▶ Build      ] [Test] [Deploy] [⌨]`,
 * one 56dp band directly above the capsule, shaped like the count of
 * presses each verb gets. Build is one wide primary key as broad as Test
 * and Deploy together (a Seeker developer presses it forty times a
 * session), Test and Deploy are compact filled keys, and Shell — a place,
 * not a verb — is a square icon key parked at the far right where a mode
 * switch belongs.
 *
 * THIS RE-INTRODUCES A BOTTOM ROW, and [BuildScreen]'s own doc records the
 * owner removing one: Deploy "must not sit under the thumb that presses
 * Build forty times a session", because it spends SOL. The mitigation is
 * that Deploy's tap only OPENS the Deploy sheet ([DeployPrompt]), which has
 * its own Cancel/Deploy pair and is where the SOL is spent — the key here
 * never sends a transaction.
 *
 * Every key is an OBJECT — it gives under the thumb ([pressScale]) with a
 * ripple. A key that cannot act keeps its word at full ink and loses its
 * FILL (filled = can act, outlined = cannot — the `outlinedButtonEdge`
 * grammar), so the deck never has a dim hole in it and nothing is drawn at
 * 38 %. The key itself never prints why: the reason is the Build status
 * strip's trailing readout — "Deploy · needs a build", "Build · no
 * toolchain" — where the artifact path sits when nothing is blocked
 * ([deckReadout]). Tapping a blocked key flashes its word and that readout
 * in the warning ink and nothing else: the boundary is shown, not buzzed.
 * The full reason stays as the key's `stateDescription` ([verbReason]).
 * Holding a verb opens the thing that gates it — Deploy → Wallet (the SOL),
 * Build → Problems (the last run's errors) — with the one long-press pulse
 * every door in the app gives.
 *
 * Running, the hero is the Stop in `errorContainer` and the one clock on
 * the screen lives inside it ([DeckClock]) — the elapsed time sits in the
 * key you would press to end it, and the strip keeps only its spinner. Test
 * and Deploy go outline-only and say nothing: the red Stop beside them is
 * the answer ([drawnReason]). One Confirm on Build start and none on Stop.
 * Shell toggles [ShellModes] and takes the pill's own wash while the
 * terminal is up. The deck hides under the IME exactly as the capsule does,
 * so the terminal's extra keys keep their row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RunDeck(
    state: ShellState,
    context: Context,
    layout: ProjectLayout?,
    inShell: Boolean,
    onTest: () -> Unit,
    onWallet: () -> Unit,
    /** A tap on a blocked key, answered by the status strip's readout. */
    onBlockedTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Under the IME the terminal's extra keys own this row: the deck hides
    // with the capsule (a floor decision, hard cut).
    if (WindowInsets.isImeVisible) return

    val root = state.project?.rootPath
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val running = BuildRunner.isRunning
    val toolchainReady = unavailableReason(context, layout) == null
    fun reason(action: BuildAction) =
        verbReason(action, toolchainReady, layout, BuildRunner.freshness, running)
    val buildReason = reason(BuildAction.Build)
    val startedAt = (state.build as? BuildState.Running)?.startedAt

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.background)
            .padding(start = MD.space4, end = MD.space4, top = MD.space2)
            .height(DeckKeyHeight),
        horizontalArrangement = Arrangement.spacedBy(MD.space2),
    ) {
        val buildFill by animateColorAsState(
            targetValue = if (running) scheme.errorContainer else scheme.primary,
            animationSpec = effectSpec(),
            label = "deck-build-fill",
        )
        DeckKey(
            modifier = Modifier.weight(2f),
            label = if (running) "Stop" else "Build",
            icon = if (running) R.drawable.ic_ui_stop else R.drawable.ic_ui_play,
            iconSize = IconSize.Inline,
            padX = MD.space3,
            // The Stop is never gated: a run is stopped by the key that
            // started it.
            reason = if (running) null else buildReason,
            fill = buildFill,
            edge = if (buildReason == null && !running) Color.Transparent else scheme.outlineVariant,
            ink = when {
                running -> scheme.onErrorContainer
                buildReason != null -> scheme.onSurface
                else -> scheme.onPrimary
            },
            trailing = if (running && startedAt != null) {
                { DeckClock(startedAt = startedAt, ink = scheme.onErrorContainer) }
            } else {
                null
            },
            onClick = {
                if (running) {
                    BuildRunner.stop()
                } else {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    BuildRunner.start(context, state, BuildAction.Build)
                }
            },
            onBlockedTap = onBlockedTap,
            onLongClick = { state.push(Route.Problems) },
            longClickLabel = "Problems",
        )
        DeckKey(
            modifier = Modifier.weight(1f),
            label = "Test",
            icon = R.drawable.ic_ui_target,
            iconSize = IconSize.Marker,
            padX = MD.space1,
            reason = reason(BuildAction.Test),
            fill = scheme.surfaceContainer,
            edge = scheme.outlineVariant,
            ink = scheme.onSurface,
            onClick = onTest,
            onBlockedTap = onBlockedTap,
        )
        DeckKey(
            modifier = Modifier.weight(1f),
            label = "Deploy",
            icon = R.drawable.ic_ui_arrow_up,
            iconSize = IconSize.Marker,
            padX = MD.space1,
            reason = reason(BuildAction.Deploy),
            fill = scheme.surfaceContainer,
            edge = scheme.outlineVariant,
            ink = scheme.onSurface,
            onClick = { DeployPrompt.open = true },
            onBlockedTap = onBlockedTap,
            onLongClick = onWallet,
            longClickLabel = "Wallet",
        )
        ShellKey(inShell = inShell, onClick = { ShellModes.toggle(root) })
    }
}

/**
 * One verb key. Disabled = fill gone, everything else stays: transparent
 * fill crossed on [effectSpec], hairline edge, full-alpha label, glyph in
 * the secondary ink. A tap on a disabled key flashes the label in the
 * warning ink for [Durations.TINT] and reports to [onBlockedTap], which
 * flashes the strip's readout the same way; no haptic. [trailing] is the
 * hero's clock, right-aligned inside the key.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeckKey(
    modifier: Modifier,
    label: String,
    @DrawableRes icon: Int,
    iconSize: Dp,
    padX: Dp,
    reason: String?,
    fill: Color,
    edge: Color,
    ink: Color,
    onClick: () -> Unit,
    onBlockedTap: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalThraggColors.current
    val haptic = LocalHapticFeedback.current
    val enabled = reason == null
    val interaction = remember { MutableInteractionSource() }
    var taps by remember { mutableIntStateOf(0) }
    var flashing by remember { mutableStateOf(false) }
    LaunchedEffect(taps) {
        if (taps == 0) return@LaunchedEffect
        flashing = true
        delay(Durations.TINT.toLong())
        flashing = false
    }
    val shownFill by animateColorAsState(
        targetValue = if (enabled) fill else Color.Transparent,
        animationSpec = effectSpec(),
        label = "deck-key-fill",
    )
    val labelInk by animateColorAsState(
        targetValue = if (flashing) colors.warnInk else ink,
        animationSpec = effectSpec(),
        label = "deck-label-ink",
    )
    val glyphInk = if (enabled) ink else scheme.onSurfaceVariant
    val shape = RoundedCornerShape(MD.radiusMd)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .pressScale(interaction)
            .clip(shape)
            .background(shownFill)
            .border(MD.hairline, edge, shape)
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClickLabel = label,
                onLongClickLabel = longClickLabel,
                onLongClick = if (onLongClick != null) {
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                } else {
                    null
                },
                onClick = {
                    if (enabled) {
                        onClick()
                    } else {
                        taps++
                        onBlockedTap()
                    }
                },
            )
            .semantics { if (reason != null) stateDescription = reason },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
            modifier = if (trailing != null) {
                Modifier.fillMaxWidth().padding(horizontal = padX)
            } else {
                Modifier.padding(horizontal = padX)
            },
        ) {
            val fade = effectSpec<Float>()
            AnimatedContent(
                targetState = icon,
                transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
                label = "deck-glyph",
            ) { shown ->
                ThraggIcon(icon = shown, contentDescription = null, tint = glyphInk, size = iconSize)
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = labelInk,
                maxLines = 1,
                softWrap = false,
            )
            if (trailing != null) {
                Spacer(Modifier.weight(1f))
                trailing()
            }
        }
    }
}

/** The Shell key: 48dp square, an object, washed while the terminal is up. */
@Composable
private fun ShellKey(inShell: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(MD.radiusMd)
    val fill by animateColorAsState(
        targetValue = if (inShell) scheme.primary.copy(alpha = 0.16f) else scheme.surfaceContainer,
        animationSpec = effectSpec(),
        label = "deck-shell-fill",
    )
    val edge by animateColorAsState(
        targetValue = if (inShell) scheme.primary.copy(alpha = 0.40f) else scheme.outlineVariant,
        animationSpec = effectSpec(),
        label = "deck-shell-edge",
    )
    val description = if (inShell) "Leave the shell" else "Open the shell"
    Box(
        modifier = Modifier
            .size(DeckKeyHeight)
            .pressScale(interaction)
            .clip(shape)
            .background(fill)
            .border(MD.hairline, edge, shape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClickLabel = description,
                onClick = onClick,
            )
            .semantics { if (inShell) stateDescription = "on" },
        contentAlignment = Alignment.Center,
    ) {
        ThraggIcon(
            icon = R.drawable.ic_ui_terminal,
            contentDescription = description,
            tint = if (inShell) scheme.primary else scheme.onSurface,
            size = IconSize.Action,
        )
    }
}

/** The one clock on the screen while a build runs: inside the key that stops it. */
@Composable
private fun DeckClock(startedAt: Long, ink: Color) {
    var now by remember(startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            delay(Durations.TICKER)
            now = System.currentTimeMillis()
        }
    }
    Text(
        text = elapsedLabel(now - startedAt),
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TabularNums),
        color = ink,
        maxLines = 1,
        softWrap = false,
    )
}

/** The keys' height — the app's row minimum; the band is this plus its top pad. */
private val DeckKeyHeight = MD.rowMin
