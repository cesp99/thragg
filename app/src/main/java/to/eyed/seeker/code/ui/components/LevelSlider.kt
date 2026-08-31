@file:OptIn(ExperimentalMaterial3Api::class)

package to.eyed.seeker.code.ui.components

import android.graphics.Color as AndroidColor
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.effectSpec
import to.eyed.seeker.code.ui.theme.spatialSpec

/**
 * An ordered scale as a slider whose FILL SAYS HOW FAR UP THE SCALE YOU ARE
 * before you read a word of it.
 *
 * This is the component the redesign is really about. Thinking effort is an
 * ordered intensity — off, low, medium, high, ultra — and Seeker drew it as a
 * radio list, which hides the one property that matters: that the options are
 * a sequence and that picking a later one costs more. A slider says it in the
 * control's shape, and the saturation ramp says it again in colour, so the
 * setting is legible from across the room.
 *
 * THE SATURATION ENCODES THE LEVEL. [saturated] takes the theme's accent into
 * HSV and scales S to `0.08 + 0.92 * fraction`, so the bottom of the scale is
 * a near-grey that reads as "off" and the top is the accent at full strength.
 * The hue never moves — it is the user's own accent the whole way up, not a
 * green-to-red ramp borrowed from a dashboard — and the fill animates on
 * [effectSpec] because a colour arriving is an effect, not a movement.
 *
 * THE TICKS FOLLOW THE FILL, NOT THE THEME: black at 55% when the fill is
 * light, white at 85% when it is dark, decided at the 0.45 luminance
 * threshold. They sit ON the fill, so a tick coloured from `onSurface` is
 * legible at one end of the ramp and gone at the other.
 *
 * COMMIT ON RELEASE. `onValueChange` moves a local drag position and ticks the
 * haptics; `onValueChangeFinished` snaps to a detent and calls [onSelect] only
 * if the value actually changed. Every ACP config write is a
 * `session/set_config_option` round trip, and a drag across five levels would
 * otherwise send four of them — the same argument SettingsScreen.kt's
 * `SliderRow` already makes about not rewriting settings.json sixty times a
 * second.
 *
 * HAPTICS ONCE PER DETENT, guarded by `tickedIndex`. Compose delivers
 * `onValueChange` at the frame rate, so an unguarded tick fires several times
 * inside one segment on a slow drag and the control buzzes like a fault.
 * `SegmentTick` is the right constant (compose-ui 1.10.4 has it) — it is the
 * one Android reserves for crossing a discrete stop.
 *
 * WHAT REDUCE-MOTION DOES AND DOES NOT TOUCH. The fill colour, the handle's
 * morph and the level pill's slide all go through [effectSpec]/[spatialSpec]
 * and stop. The thumb's position under the finger does not: a drag that
 * stopped following the finger is a bug, not an accommodation.
 *
 * THE HANDLE IS NOT THE FILL, AND IT IS NOT HELD OFF THE TRACK BY A GAP. Two
 * measured corrections to the stock recipe, and they are the same correction
 * twice. `thumbColor` was the level colour, so at the top of the ramp the handle
 * was a stripe of accent on a bar of the same accent and the only thing
 * marking it was that it is taller; it is `onSurface` now — the one ink the
 * bridge solves against every rung of the ladder — and 12dp of it stands proud
 * of the 16dp track at each end, on the sheet's own ground, which is where a
 * grip is most legible anyway.
 *
 * And `thumbTrackGapSize` is 0 rather than 8dp, because material3 measures
 * that gap from the handle's CENTRE (`thumbWidth / 2 + gap`, Slider.kt's
 * `drawTrack`). At "off" the active track has zero width and is not drawn at
 * all, so the *inactive* track began 11dp to the right of a handle sitting at
 * x = 0: the handle hung off the left cap of its own scale, with the first
 * tick stranded on bare sheet between the two. Widening the handle cannot fix
 * it — the gap grows by exactly what the handle grows by — and the same hole
 * opens mirrored at the top of the scale. What replaces the gap is
 * `trackInsideCornerSize`, which rounds both halves toward the handle at 6dp
 * whether or not a gap is set: the track now pinches AT the handle instead of
 * stopping short of it, and a pinch cannot fall off the end of a track.
 *
 * `SliderDefaults.Track(trackCornerSize = …)` is internal in material3 1.4.0,
 * so the track keeps its default pill ends instead of 12dp ones; the 6dp
 * inside corners and the 2.5dp per-level dots are public and are here.
 */
@Composable
fun LevelSlider(
    choices: List<Choice>,
    selectedValue: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    if (choices.size < 2) return
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val lastIndex = choices.lastIndex
    val activeIndex = choices.indexOfFirst { it.value == selectedValue }.coerceAtLeast(0)

    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()

    // The position the *thumb* is at, which is the committed index except
    // while a finger is on it. Keyed on the committed index so a value that
    // arrives from the host (an optimistic overlay being confirmed, or another
    // client changing it) moves the thumb.
    var position by remember(activeIndex) { mutableFloatStateOf(activeIndex.toFloat()) }
    var tickedIndex by remember(activeIndex) { mutableIntStateOf(activeIndex) }

    val shownIndex = position.roundToInt().coerceIn(0, lastIndex)
    val fraction = shownIndex.toFloat() / lastIndex.toFloat()
    val levelColor by animateColorAsState(
        targetValue = saturated(accent, fraction),
        animationSpec = effectSpec(),
        label = "level-fill",
    )
    // The ticks sit on top of the fill, so their ink is decided by the fill's
    // own luminance rather than by the theme's appearance.
    val tickColor = if (levelColor.luminance() > 0.45f) {
        Color.Black.copy(alpha = 0.55f)
    } else {
        Color.White.copy(alpha = 0.85f)
    }
    // The handle does NOT follow the fill, for the reason the ticks do: a tick
    // is only ever on the fill, and the handle is only ever half on it. Most
    // of its 40dp is above and below a 16dp track, on `surface`, and the
    // 16dp that crosses the track crosses BOTH halves of it — so the one ink
    // solved against the ladder and against the surface is the only one that
    // works at every value.
    val handleColor = scheme.onSurface

    // AnimatedContent's `transitionSpec` is NOT a composable lambda in this
    // version of compose-animation, so every spec it uses has to be resolved
    // out here. That is also the only place reduce-motion is read, which is
    // why hoisting is not merely a workaround: the branch happens once per
    // composition rather than once per transition.
    val fade = effectSpec<Float>()
    val slide = spatialSpec<IntOffset>()

    Column(modifier = modifier.fillMaxWidth()) {
        LevelPill(
            index = shownIndex,
            names = choices.map { it.name },
            tint = levelColor,
            fade = fade,
            slide = slide,
        )
        Slider(
            value = position,
            onValueChange = { raw ->
                position = raw
                val detent = raw.roundToInt().coerceIn(0, lastIndex)
                if (detent != tickedIndex) {
                    tickedIndex = detent
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
            },
            onValueChangeFinished = {
                val detent = position.roundToInt().coerceIn(0, lastIndex)
                position = detent.toFloat()
                if (detent != activeIndex) onSelect(choices[detent].value)
            },
            valueRange = 0f..lastIndex.toFloat(),
            // One step per interior detent: five levels are four steps, and
            // `steps` counts the stops BETWEEN the ends.
            steps = (choices.size - 2).coerceAtLeast(0),
            interactionSource = interaction,
            modifier = Modifier.semantics {
                stateDescription = choices[shownIndex].name
            },
            colors = SliderDefaults.colors(
                activeTrackColor = levelColor,
                inactiveTrackColor = scheme.surfaceContainerHighest,
                thumbColor = handleColor,
                activeTickColor = tickColor,
                inactiveTickColor = scheme.onSurfaceVariant.copy(alpha = 0.35f),
            ),
            thumb = { LevelHandle(color = handleColor, active = dragged || pressed) },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    colors = SliderDefaults.colors(
                        activeTrackColor = levelColor,
                        inactiveTrackColor = scheme.surfaceContainerHighest,
                        activeTickColor = tickColor,
                        inactiveTickColor = scheme.onSurfaceVariant.copy(alpha = 0.35f),
                    ),
                    drawStopIndicator = null,
                    drawTick = { offset, color -> drawCircle(color, 2.5.dp.toPx(), offset) },
                    // Zero, and it is load-bearing — see the note on the gap
                    // above. `SliderDefaults.Track` reads it as a distance
                    // from the handle's centre, so any positive value strands
                    // the handle outside the track at both ends of the scale.
                    thumbTrackGapSize = 0.dp,
                    // 6dp, written as a literal: MD has no 6dp radius, and
                    // borrowing `iconGap` for a corner would name it after the
                    // wrong thing.
                    trackInsideCornerSize = 6.dp,
                )
            },
        )
        // The two ends, named. Without them the fill is a bar with nothing to
        // be measured against, and the ramp's whole argument is comparative.
        // Each one yields to the pill when the pill is saying its word, so the
        // pill always carries something the scale does not — see [EndLabel].
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MD.space1),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            EndLabel(choices.first().name, taken = shownIndex == 0, fade = fade)
            EndLabel(choices.last().name, taken = shownIndex == lastIndex, fade = fade)
        }
        val description = choices[shownIndex].description
        if (!description.isNullOrBlank()) {
            AnimatedContent(
                targetState = description,
                transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
                label = "level-description",
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MD.space2, start = MD.space1),
                )
            }
        }
    }
}

/**
 * The level's name, entering from the direction the value moved.
 *
 * A label that slides UP when the setting goes up is a large part of what
 * makes the control feel designed, for about eight lines of code: the motion
 * restates the ordering that the fill and the position already carry, so all
 * three agree. `rising` picks the direction; [effectSpec] and the slide both
 * stop under reduce-motion, leaving a substitution.
 */
@Composable
private fun LevelPill(
    index: Int,
    names: List<String>,
    tint: Color,
    fade: FiniteAnimationSpec<Float>,
    slide: FiniteAnimationSpec<IntOffset>,
) {
    AnimatedContent(
        targetState = index,
        transitionSpec = {
            // The INDEX is the state, not the label: the pill's direction is
            // the scale's ordering, and comparing two names would compare them
            // alphabetically, which puts "low" below "off".
            val rising = targetState > initialState
            val enter = slideInVertically(slide) { height ->
                if (rising) height else -height
            } + fadeIn(fade)
            val exit = slideOutVertically(slide) { height ->
                if (rising) -height else height
            } + fadeOut(fade)
            enter togetherWith exit
        },
        label = "level-pill",
        modifier = Modifier.fillMaxWidth(),
    ) { shown ->
        // MEASURED ON DEVICE. This was `Arrangement.End`, which pinned the pill
        // to the right whatever the value — so a thinking level of "Off" drew
        // the word "Off" hard against the right edge, directly above the "High"
        // scale-end label, while the handle sat at the far LEFT. The pill names
        // the value the handle is pointing at, so it has to be over the handle:
        // a discrete slider's handle sits at `index / (size - 1)` of the track,
        // and a bias of `2f * fraction - 1f` maps that to start-aligned at 0
        // and end-aligned at 1, which also keeps the pill inside the row at
        // both ends instead of overhanging them.
        val fraction = if (names.size > 1) shown.toFloat() / (names.size - 1) else 0f
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = BiasAlignment(
                horizontalBias = 2f * fraction - 1f,
                verticalBias = 0f,
            ),
        ) {
            Text(
                text = names[shown],
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(MD.pill))
                    .background(tint.copy(alpha = 0.16f))
                    .padding(horizontal = MD.pillPadX, vertical = MD.pillPadY),
            )
        }
    }
}

/**
 * The handle: a 6 × 52dp box with a bar inside it that gets THINNER and TALLER
 * while it is being dragged.
 *
 * Material's own expressive slider does this, and the reason is not
 * decoration: a thumb under a fingertip hides the level it is pointing at, so
 * narrowing it from 5dp to 3dp gives the fill back the pixels the finger is
 * covering, and growing it from 40dp to 52dp keeps the handle findable while
 * it is thinner. The box does not change size, so the track's layout never
 * moves.
 */
@Composable
private fun LevelHandle(color: Color, active: Boolean) {
    val width by animateDpAsState(
        targetValue = if (active) 3.dp else 5.dp,
        animationSpec = spatialSpec(),
        label = "handle-width",
    )
    val height by animateDpAsState(
        targetValue = if (active) 52.dp else 40.dp,
        animationSpec = spatialSpec(),
        label = "handle-height",
    )
    Box(
        modifier = Modifier.size(width = 6.dp, height = 52.dp),
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            modifier = Modifier
                .width(width)
                .height(height)
                .clip(RoundedCornerShape(MD.radiusXs))
                .background(color),
        )
    }
}

/**
 * One end of the scale, named under the track it belongs to — and blank while
 * the pill is saying the same word.
 *
 * MEASURED ON DEVICE, and the [taken] parameter is the whole of it. The pill
 * rides over the handle, so at the bottom of the scale "Off" was printed
 * twice — once in the pill and once directly beneath it as the left end — and
 * the pair read as a caption on the pill rather than as the end of an axis;
 * the top of the scale did the same with its last name. A pill that repeats
 * what is already written under it carries no information, so the *scale*
 * gives the word up: the end that the value is currently sitting on fades out
 * and the pill is the only place the word appears. Which end is named still
 * bounds the axis, because the pill is standing on the other one.
 *
 * It fades rather than disappearing: the label keeps its space, so the row
 * never reflows as the value moves, and [fade] is [effectSpec], so
 * reduce-motion turns the fade into a substitution instead of stopping it.
 */
@Composable
private fun EndLabel(text: String, taken: Boolean, fade: FiniteAnimationSpec<Float>) {
    val ink by animateFloatAsState(
        targetValue = if (taken) 0f else 0.7f,
        animationSpec = fade,
        label = "end-label",
    )
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ink),
        maxLines = 1,
    )
}

/**
 * [color] with its hue and value kept and its SATURATION scaled to
 * `0.08 + 0.92 * fraction`.
 *
 * Not a `lerp` toward grey, which would drag the value with it and make the
 * bottom of the scale darker as well as duller — on a light theme that reads
 * as "disabled", which is the wrong word for "off". Scaling S in HSV keeps the
 * ramp at one brightness, so the only thing changing along the track is how
 * much colour there is, which is exactly the quantity being encoded.
 *
 * The floor is 0.08 rather than 0: a fully desaturated fill is a grey bar,
 * indistinguishable from the inactive track beside it, and the bottom of the
 * scale is a chosen value rather than an absence.
 */
private fun saturated(color: Color, fraction: Float): Color {
    val hsv = FloatArray(3)
    AndroidColor.RGBToHSV(
        (color.red * 255f).roundToInt(),
        (color.green * 255f).roundToInt(),
        (color.blue * 255f).roundToInt(),
        hsv,
    )
    hsv[1] = hsv[1] * (0.08f + 0.92f * fraction.coerceIn(0f, 1f))
    return Color(AndroidColor.HSVToColor(hsv)).copy(alpha = color.alpha)
}
