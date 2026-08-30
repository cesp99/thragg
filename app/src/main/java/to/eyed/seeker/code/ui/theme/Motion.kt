package to.eyed.seeker.code.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalContext
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
