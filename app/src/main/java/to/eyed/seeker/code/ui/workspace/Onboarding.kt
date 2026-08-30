package to.eyed.seeker.code.ui.workspace

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.AppSettings
import to.eyed.seeker.code.core.ThemeMode
import to.eyed.seeker.code.ui.theme.LocalZedTheme

/**
 * Whether the welcome screen has been shown, and the "don't show again" that
 * settles it.
 *
 * App preferences rather than settings.json: this is not configuration, it is
 * a fact about this install, and putting it in the file people hand-edit and
 * copy between devices would mean a copied settings file silently suppressed
 * the welcome on a machine that had never shown it.
 */
object OnboardingState {
    private const val PREFS = "onboarding"
    private const val KEY_SEEN = "seen"

    /** Whether the first-run screen has already been dismissed. */
    fun hasBeenSeen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

    /**
     * Remember that it has. `apply()` writes on its own thread, so this
     * returns before the disk does; the read in [hasBeenSeen] is the one that
     * has to be kept off the main thread.
     */
    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SEEN, true)
            .apply()
    }

    /** Show it again on the next launch — what a "reset" would want. */
    fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SEEN)
            .apply()
    }
}

/**
 * The welcome screen — Zed's `crates/onboarding`, adapted to a phone.
 *
 * Zed's onboarding is a workspace item with a page rail: a basics page with
 * the theme and the base keymap, an editing page, an AI page. This one is a
 * dialog with the three things a new user has to be told, because on a phone
 * a rail is a scroll and a scroll is a page nobody reads to the end of:
 *
 *  1. **where the code is** — the app ships a sample project, and the project
 *     picker is how you get to your own;
 *  2. **the command palette** — the one surface where everything is reachable,
 *     which matters more here than in Zed because most of these devices have
 *     no keyboard to press the chords on;
 *  3. **the terminal** — the thing that makes this an editor you can actually
 *     work in rather than a viewer.
 *
 * Plus the one choice Zed's first page opens with, the theme, because it is
 * the setting people want before they have read anything.
 *
 * Shown once. Reachable afterwards from the ☰ menu and from the palette as
 * `zed::OpenOnboarding`, which is Zed's own action name for it.
 */
@Composable
fun OnboardingScreen(
    settings: AppSettings,
    /** Writes one settings key, as the settings screen does. */
    onSet: (keyPath: String, valueJson: String) -> Unit,
    /** The full theme list, with a preview — the row is only light/dark. */
    onOpenThemeSelector: () -> Unit,
    /** Open the project picker: the first of the three things to try. */
    onOpenProjects: () -> Unit,
    /** Open the command palette. */
    onOpenPalette: () -> Unit,
    /** Open a terminal. Null in a build with no userland, which hides the row. */
    onOpenTerminal: (() -> Unit)?,
    /** Close it. [dontShowAgain] is true when the box was ticked. */
    onDismiss: (dontShowAgain: Boolean) -> Unit,
) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    // Ticked by default: this is a screen you are meant to see once, and the
    // box is there so somebody who wants it again can leave it clear.
    var dontShowAgain by remember { mutableStateOf(true) }

    PanelDialog(title = stringResource(R.string.welcome_welcome_to_seeker_code), onDismiss = { onDismiss(dontShowAgain) }) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                text = stringResource(R.string.welcome_zed_s_editor_ported_to_android),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.welcome_three_things_to_try),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp),
            )
            OnboardingStep(
                title = stringResource(R.string.welcome_open_a_project),
                detail = stringResource(R.string.welcome_a_sample_project_is_already_here),
                chord = shortcutLabel(WorkspaceCommand.OpenProjects),
                onClick = onOpenProjects,
            )
            OnboardingStep(
                title = stringResource(R.string.welcome_open_the_command_palette),
                detail = stringResource(R.string.welcome_everything_the_editor_can_do_by),
                chord = shortcutLabel(WorkspaceAction.CommandPalette),
                onClick = onOpenPalette,
            )
            if (onOpenTerminal != null) {
                OnboardingStep(
                    title = stringResource(R.string.welcome_open_a_terminal),
                    detail = stringResource(R.string.welcome_a_real_debian_shell_git_python3),
                    chord = shortcutLabel(WorkspaceCommand.ToggleTerminal),
                    onClick = onOpenTerminal,
                )
            }

            Text(
                text = stringResource(R.string.welcome_appearance),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                val mode = settings.themeSelection.mode ?: ThemeMode.System
                for (choice in ThemeMode.entries) {
                    OnboardingChoice(
                        text = when (choice) {
                            ThemeMode.System -> "Follow the system"
                            ThemeMode.Light -> "Light"
                            ThemeMode.Dark -> "Dark"
                        },
                        isSelected = choice == mode,
                        onClick = {
                            onSet(
                                AppSettings.KEY_THEME,
                                settings.themeSelection.withMode(choice).toJson(),
                            )
                        },
                    )
                }
            }
            Text(
                text = stringResource(R.string.welcome_choose_a_theme_by_name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClickLabel = "Open the theme selector", onClick = onOpenThemeSelector),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = if (dontShowAgain) {
                            "Don't show this again, ticked"
                        } else {
                            "Don't show this again, not ticked"
                        }
                    }
                    // The whole row is the target, not the 16dp box: a
                    // checkbox you have to hit exactly is a checkbox nobody
                    // with a tremor can tick.
                    .clickable { dontShowAgain = !dontShowAgain }
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    text = if (dontShowAgain) "☑" else "☐",
                    style = MaterialTheme.typography.bodyLarge,
                    color = theme.color("text.accent"),
                )
                Text(
                    text = stringResource(R.string.welcome_don_t_show_this_again),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(R.string.welcome_it_is_always_in_the_menu),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PanelActions {
            PanelTextAction(stringResource(R.string.welcome_start_editing)) {
                if (dontShowAgain) OnboardingState.markSeen(context)
                onDismiss(dontShowAgain)
            }
        }
    }
}

/** One of the three things to try: a title, a sentence, and a way to do it. */
@Composable
private fun OnboardingStep(
    title: String,
    detail: String,
    chord: String?,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = if (chord != null) "$title. $detail. $chord" else "$title. $detail"
            }
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClickLabel = title, onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (chord != null) {
                Text(
                    text = chord,
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                    maxLines = 1,
                )
            }
        }
        Text(
            text = detail,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The appearance row's pills, in the settings screen's own shape. */
@Composable
private fun OnboardingChoice(text: String, isSelected: Boolean, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (isSelected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .background(
                if (isSelected) theme.color("element.selected") else theme.color("element.background"),
                RoundedCornerShape(6.dp),
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
