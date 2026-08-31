package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.CoreBridge
import to.eyed.seeker.code.core.SystemSpecs
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.RowChevron

/**
 * About — Zed's `zed::About` (zed/src/zed.rs), which shows the version and
 * offers to copy the system specs.
 *
 * The list is [SystemSpecs]: everything an issue needs to be reproducible,
 * including the three facts only Android has (the edition, the ABI and the
 * kernel's page size). Copy puts the same lines on the clipboard as plain
 * `Label: value` text, which is what pastes into an issue body unchanged.
 *
 * Reached from the ☰ menu, from an About row in Settings, and from the
 * palette as "zed: about" — three routes because a bug report is exactly the
 * moment when someone cannot find the fourth.
 */
@Composable
fun AboutDialog(
    onDismiss: () -> Unit,
    /**
     * Open the licences screen, if the caller has a shell to push it onto.
     *
     * docs/LICENSING.md §5 asks for a row here as well as in Settings, "so
     * someone who went looking for the version finds the notices too" — and
     * the version is exactly what someone checking what a build is made of
     * comes here for. Nullable because this dialog is also reachable from the
     * old workspace, which has no route stack; a null hides the row rather
     * than showing one that cannot navigate.
     */
    onOpenLicences: (() -> Unit)? = null,
) {
    val theme = LocalZedTheme.current
    val clipboard = LocalClipboardManager.current
    // Read here rather than at the call site: `stringResource` is a
    // composable and the semantics block below is not.
    val licences = stringResource(R.string.licences_settings_row)
    // The engine's version is a JNI hop; every other line is a constant. Read
    // it off the main thread and paint the dialog before it lands.
    val specs by produceState<SystemSpecs?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { SystemSpecs.of(CoreBridge.engineVersion()) }
    }

    PanelDialog(title = stringResource(R.string.about_about), onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                text = stringResource(R.string.about_seeker_code_zed_s_editor_ported),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val report = specs
            if (report == null) {
                Text(
                    text = stringResource(R.string.about_reading_the_engine_s_version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                for ((label, value) in report.lines()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            // One description per row, so a screen reader
                            // reads "Engine, 0.1.0" rather than two floating
                            // fragments in an unclear order.
                            .semantics(mergeDescendants = true) {
                                contentDescription = "$label: $value"
                            },
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = theme.color(
                                "text.muted",
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            modifier = Modifier.width(96.dp),
                            maxLines = 1,
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // The notices, from the screen someone opens to find out what
            // they are running. docs/LICENSING.md §5 asks for exactly this
            // second door.
            if (onOpenLicences != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenLicences)
                        .padding(top = 16.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = licences
                        },
                ) {
                    Text(
                        text = licences,
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.color(
                            "link_text.hover",
                            MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    RowChevron()
                }
            }
        }
        PanelActions {
            PanelTextAction(stringResource(R.string.about_close), onClick = onDismiss)
            PanelTextAction(
                stringResource(R.string.about_copy),
                enabled = specs != null,
                onClick = {
                    specs?.let { clipboard.setText(AnnotatedString(it.report())) }
                    onDismiss()
                },
            )
        }
    }
}
