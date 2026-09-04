package to.eyed.thragg.ui.common

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.thragg.R
import to.eyed.thragg.ui.workspace.OpenFilesState

/**
 * "Save changes to main.rs?" — the question a close has to ask.
 *
 * Closing a dirty tab used to drop the buffer, and with it every edit since
 * the last save; the engine has no autosave and no crash journal, so that was
 * the one place in the app where work could vanish without being reported.
 * The three answers are Zed's, and the file is named in the title because
 * "you have unsaved changes" is not enough to decide on.
 *
 * Nothing is closed until an answer comes back: [OpenFilesState] holds the
 * rest of the request — closing five tabs asks about each dirty one in turn —
 * and Cancel abandons all of it.
 *
 * It lives in `ui/common/` rather than beside the tab strip that used to host
 * it because the strip is not where files get closed any more: the portrait
 * shell closes one from a long-press on the file bar, from the Files sheet and
 * from the project panel, and *every* one of those routes has to raise this
 * dialog or the edits since the last save go silently. Hosting it once, next
 * to the composable that owns the screen, is what makes that hard to get
 * wrong. A host that forgets to compose it fails *silently* — the close
 * request simply parks in [OpenFilesState.closeConfirmation] and nothing is
 * drawn — so every destination that can close a file composes this.
 */
@Composable
fun UnsavedChangesDialog(files: OpenFilesState) {
    val file = files.closeConfirmation ?: return
    val scope = rememberCoroutineScope()
    var saving by remember(file) { mutableStateOf(false) }
    var saveFailed by remember(file) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { files.cancelClose() },
        // Elevation is zero everywhere in both halves: the scheme's
        // `surfaceTint` is transparent, so a tonal elevation here would tint
        // nothing and a shadow would make this the only floating thing in the
        // app (docs/VISUAL.md, "Foundations", ELEVATION). The scrim and the
        // container's own fill step are what separate a dialog from the screen
        // behind it.
        tonalElevation = 0.dp,
        title = { Text(stringResource(R.string.unsaved_title, file.name)) },
        text = {
            Text(
                text = if (saveFailed) {
                    stringResource(R.string.unsaved_write_failed, file.path)
                } else {
                    stringResource(R.string.unsaved_not_on_disk, file.path)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        // A multibuffer saves every file in it — Zed's
                        // SaveAll — and the composed buffer has no file of its
                        // own to write to.
                        val multibuffer = file.multibuffer
                        val saved = withContext(Dispatchers.IO) {
                            if (multibuffer != null) {
                                multibuffer.saveAll().failed.isEmpty()
                            } else {
                                file.session?.save() == true
                            }
                        }
                        file.refreshStatus()
                        saving = false
                        // A failed write is the one case where closing anyway
                        // would be exactly the data loss this dialog exists to
                        // prevent, so the tab stays and the dialog says why.
                        if (saved) files.confirmClose() else saveFailed = true
                    }
                },
            ) { Text(stringResource(R.string.unsaved_save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { files.cancelClose() }) { Text(stringResource(R.string.unsaved_cancel)) }
                TextButton(enabled = !saving, onClick = { files.confirmClose() }) {
                    Text(stringResource(R.string.unsaved_dont_save))
                }
            }
        },
    )
}
