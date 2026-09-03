package to.eyed.seeker.code.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import to.eyed.seeker.code.core.AppSettings

/**
 * Whether non-essential motion is switched off — Zed's `reduce_motion`, and
 * Android's "Remove animations", answered once at the root.
 *
 * Everything that moves for decoration rather than for meaning reads this: the
 * scroll animations that carry a selection to a row, the toast slide, the
 * pulsing "working" label. Nothing that moves *because the user is dragging
 * it* does — a drag that stopped following the finger would be a bug, not an
 * accommodation.
 *
 * The read is deliberately not per-widget. Vestibular disorders are the reason
 * this setting exists, and a screen where half the motion stopped is worse
 * than one where none did.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * Whether the *system* has been told to remove animations.
 *
 * Android's Accessibility ▸ Remove animations writes 0 to all three of
 * `ANIMATOR_DURATION_SCALE`, `TRANSITION_ANIMATION_SCALE` and
 * `WINDOW_ANIMATION_SCALE`. The animator scale is the one that governs
 * *in-app* animation — the other two are the window manager's — so it is the
 * one to ask, and it is the one `AccessibilityManager` itself checks.
 *
 * A device that has never had the setting touched has no row for it, which is
 * why the default is 1: absent means "animate", not "don't".
 */
fun systemAnimationsOff(context: Context): Boolean =
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f

/**
 * [LocalReduceMotion]'s value: the setting's answer, given what the system was
 * told, kept in step with the system while the app is up.
 *
 * The observer matters more than it looks: turning "Remove animations" on is
 * something a user does *because the app is moving too much*, and they should
 * not have to restart the editor to be believed.
 */
@Composable
fun rememberReduceMotion(settings: AppSettings): Boolean {
    val context = LocalContext.current
    var systemOff by remember { mutableStateOf(systemAnimationsOff(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                systemOff = systemAnimationsOff(context)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        // Read once more on the way in: the setting may have changed while
        // the app was in the background, when no observer was registered.
        systemOff = systemAnimationsOff(context)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    val reduced = settings.reduceMotion.applies(systemOff)
    // The same answer, reachable from a coroutine. A `suspend` scroll cannot
    // read a composition local, and threading a boolean through twenty call
    // sites would guarantee one of them was missed.
    SideEffect { Motion.isReduced = reduced }
    return reduced
}

/**
 * [LocalReduceMotion]'s answer where a composition local cannot reach: the
 * `suspend` bodies of the effects that scroll a list to a row.
 *
 * Written once per settings change from [rememberReduceMotion] and read
 * nowhere else, so there is exactly one source of truth and this is a cache
 * of it, not a second one.
 */
object Motion {
    var isReduced: Boolean by mutableStateOf(false)
        internal set
}

/**
 * Bring the item at [index] into view — gliding there, or arriving there at
 * once when motion is reduced.
 *
 * Every list in the app that follows a selection calls this rather than
 * `animateScrollToItem`: the *destination* is the point, the glide is
 * decoration, and decoration is exactly what `reduce_motion` switches off.
 */
suspend fun LazyListState.revealItem(index: Int) {
    if (Motion.isReduced) scrollToItem(index) else animateScrollToItem(index)
}

/** [revealItem]'s sibling for a scroll by a distance rather than to a row. */
suspend fun LazyListState.revealBy(delta: Float) {
    if (Motion.isReduced) scrollBy(delta) else animateScrollBy(delta)
}

/**
 * The one spring: every expand, every collapse, every chevron, every
 * `animateContentSize`.
 *
 * There is one so that two cards opening beside each other cannot arrive at
 * different times, which is the single most common way a Compose screen stops
 * looking designed. `StiffnessMediumLow` is Compose's own default for
 * `animateContentSize`, and it is the right damping for a box changing size
 * under a finger that is not touching it.
 *
 * Reduce-motion answers [snap], not a shorter duration: the *destination* is
 * the information and the travel is the decoration, so decoration is what goes
 * (see [LocalReduceMotion]).
 */
@Composable
fun <T> seekerSpring(): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else spring(stiffness = Spring.StiffnessMediumLow)

/**
 * Colour and alpha — a tint arriving, a label fading in, a fill changing.
 *
 * These are Material 3's **expressive** default-effects numbers, spelled here
 * rather than read off `MaterialTheme.motionScheme`. That read is not
 * available: in material3 1.4.0 the `MotionScheme` interface,
 * `MotionScheme.expressive()`, `MaterialTheme.motionScheme` and
 * `MaterialExpressiveTheme` are all Kotlin-`internal`. Their *JVM* methods are
 * public and, being top-level or `@Composable`, several are not even
 * name-mangled — which is what an earlier `javap` pass read as "public" — but
 * Kotlin resolves visibility from the `@Metadata` annotation, and the compiler
 * refuses the call. Verified by compiling it: `Cannot access
 * 'val motionScheme: MotionScheme': it is internal in
 * 'androidx/compose/material3/MaterialTheme'`.
 *
 * So the values are copied instead, from
 * `androidx.compose.material3.tokens.ExpressiveMotionTokens`:
 * `SpringDefaultEffectsDamping = 1.0`, `SpringDefaultEffectsStiffness = 1600`.
 * Critically damped and stiff — an opacity has no momentum, so any overshoot
 * in it reads as a flicker rather than as weight.
 */
@Composable
fun <T> effectSpec(): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) {
        snap()
    } else {
        spring(dampingRatio = EFFECTS_DAMPING, stiffness = EFFECTS_STIFFNESS)
    }

/**
 * Size and position — something moving, under or near a finger.
 *
 * `ExpressiveMotionTokens.SpringFastSpatial*`: damping 0.6, stiffness 800.
 * Underdamped on purpose. A thing that overshoots slightly and settles has
 * mass, and mass is what makes a drag feel attached to the finger that made
 * it; the same overshoot applied to a colour ([effectSpec]) would be a bug.
 */
@Composable
fun <T> spatialSpec(): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) {
        snap()
    } else {
        spring(dampingRatio = SPATIAL_DAMPING, stiffness = SPATIAL_STIFFNESS)
    }

/** `ExpressiveMotionTokens.SpringDefaultEffectsDamping`. */
private const val EFFECTS_DAMPING = 1.0f

/** `ExpressiveMotionTokens.SpringDefaultEffectsStiffness`. */
private const val EFFECTS_STIFFNESS = 1600f

/** `ExpressiveMotionTokens.SpringFastSpatialDamping`. */
private const val SPATIAL_DAMPING = 0.6f

/** `ExpressiveMotionTokens.SpringFastSpatialStiffness`. */
private const val SPATIAL_STIFFNESS = 800f

/**
 * The size spring, with the threshold that stops it running on invisibly.
 *
 * [seekerSpring] leaves `visibilityThreshold` null, which resolves to 0.01 —
 * a hundredth of a pixel, so a box "finishes" arriving long after it has
 * stopped moving on screen and holds a frame callback while it does.
 * `IntSize.VisibilityThreshold` is one pixel on each axis, which is the
 * smallest difference a size can actually have. Hoisted out of the composable
 * because it depends on nothing.
 */
private val SizeSpring = spring(
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntSize.VisibilityThreshold,
)

/**
 * Animate this composable's size changes on [seekerSpring].
 *
 * Reach for this rather than `animateContentSize()` — the bare call takes
 * Compose's default spring and, more to the point, reads no reduce-motion
 * signal, so a card that expands is exactly the kind of motion the setting
 * exists to switch off and the bare call keeps it.
 */
@Composable
fun Modifier.animateSize(): Modifier =
    animateContentSize(if (LocalReduceMotion.current) snap() else SizeSpring)

/**
 * The durations that are a duration rather than a spring, each with its
 * reason.
 *
 * Anything that answers "how long" and is not [seekerSpring], [effectSpec] or
 * [spatialSpec] is here, so the same event has the same length everywhere it
 * is drawn. A number that appears in two files eventually appears as two
 * numbers.
 *
 * Every one of these still routes through [LocalReduceMotion] at the call
 * site: reduce-motion removes the *travel*, not the [RUN_HOLD] or the
 * [COPY_CONFIRM], which are how long a piece of information stays readable and
 * are not motion at all.
 */
object Durations {
    /**
     * 180ms — a band appearing: the live-run strip, a notice, a status row.
     * `fadeIn + expandVertically`.
     */
    const val BAND_IN = 180

    /**
     * 240ms — the same band leaving. **Slower out than in, deliberately.** A
     * run that has just finished is the thing the user is most likely to be
     * reading, and a strip that blinks out at the speed it came in reads as a
     * glitch rather than as a completion.
     */
    const val BAND_OUT = 240

    /**
     * 4000ms — how long a finished run stays on screen before its strip
     * collapses, so its FINAL counts can be read. The strip is the only place
     * they are shown; without the hold the last thing it says is whatever it
     * said one frame before it ended.
     */
    const val RUN_HOLD = 4000L

    /**
     * 120ms — an assistant markdown block fading in as it streams. Existing
     * behaviour, kept: the agent's stream has draft-reset semantics ACP cannot
     * express, so a block can be replaced wholesale and a longer fade would
     * make the replacement look like a second block.
     */
    const val BLOCK_FADE = 120

    /**
     * 200ms — every colour and alpha tween that is written as a duration
     * rather than as [effectSpec]. The band is 150-250ms and nothing sits
     * outside it: under 150 a colour change is a flash, over 250 the eye has
     * moved on before it lands.
     */
    const val TINT = 200

    /**
     * 260ms — a route sliding on or off the shell: the Problems screen a
     * diagnostics chip pushes, the diff a changed file opens, Setup, Settings.
     * One number for the push and the pop, so going back takes exactly as
     * long as going forward and the pair reads as one gesture reversed. Longer
     * than [TINT] because a whole screen is travelling a whole screen-width
     * and the eye needs the direction, which is the entire point of the
     * animation; shorter than 300 because it happens on every tap of every
     * row and a navigation that can be *felt* waiting is worse than none.
     */
    const val ROUTE = 260

    /** 8 — quantised frames in the braille spinner's cycle. */
    const val SPINNER_FRAMES = 8

    /**
     * 50ms per frame, so [SPINNER_FRAMES] make a 400ms cycle. Quantised rather
     * than continuous: a braille cell has eight positions and interpolating
     * between two of them draws neither.
     */
    const val SPINNER_FRAME = 50L

    /**
     * 1000ms — the run ticker's step. The spinner beside it carries the
     * motion; re-laying out a text run at 60fps to advance a seconds counter
     * is work with no viewer.
     */
    const val TICKER = 1000L

    /**
     * 1600ms — how long a copy control says "Copied" before reverting. This
     * replaces a snackbar rather than accompanying one: a snackbar for a copy
     * covers content to report something that happened where the user was
     * already looking.
     */
    const val COPY_CONFIRM = 1600L
}
