package to.eyed.seeker.code.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay
import to.eyed.seeker.code.R
import to.eyed.seeker.code.ui.theme.Durations
import to.eyed.seeker.code.ui.theme.effectSpec

/**
 * Copy, and then say so where the user was already looking.
 *
 * This replaces a `Toast`, deliberately and everywhere. A toast for a copy
 * covers content in order to report something that happened at the other end
 * of the screen, is untappable, is gone in four seconds and cannot be read by
 * anyone who looked away — and Seeker reaches for one at five sites today. The
 * chip flips its own label to "Copied" for [Durations.COPY_CONFIRM] (1600ms)
 * and reverts. The confirmation is at the point of action, and it costs no
 * layout.
 *
 * The label is what changes, not the icon: an icon swap at 14dp is a flicker,
 * where a word is legible at arm's length. The crossfade goes through
 * `effectSpec()` so reduce-motion turns it into a substitution rather than a
 * fade.
 *
 * The timer is keyed on `copied`, so a second tap while the confirmation is up
 * restarts the hold rather than letting the first tap's coroutine revert the
 * second one's label early.
 */
@Composable
fun CopyChip(
    text: String,
    modifier: Modifier = Modifier,
    label: String = "Copy",
) {
    // `LocalClipboardManager` rather than 1.10's `LocalClipboard`, which is
    // deprecated-in-favour-of but suspend-only: the other four clipboard sites
    // in the app (SessionPicker, AboutDialog, EditorPane, ProjectPanel) all
    // still use this one, and migrating five call sites is its own change
    // rather than a rider on a new component.
    val clipboard = LocalClipboardManager.current
    // `transitionSpec` is not a composable lambda, so the spec — and with it
    // the reduce-motion branch inside `effectSpec()` — is read out here.
    val fade = effectSpec<Float>()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(Durations.COPY_CONFIRM)
        copied = false
    }
    AnimatedContent(
        targetState = copied,
        transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
        label = "copy-chip",
        modifier = modifier,
    ) { confirmed ->
        SeekerChip(
            label = if (confirmed) "Copied" else label,
            // A check on the way out and nothing on the way in: Lucide's copy
            // glyph is not in the imported set yet (tools/import-lucide-icons.py
            // decides that), and a wrong icon beside the right word is worse
            // than the word alone.
            leading = if (confirmed) R.drawable.ic_ui_check else null,
            onClick = {
                clipboard.setText(AnnotatedString(text))
                copied = true
            },
        )
    }
}
