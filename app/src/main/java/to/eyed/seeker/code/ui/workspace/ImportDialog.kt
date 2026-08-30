package to.eyed.seeker.code.ui.workspace

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.StagedFile

/**
 * "Add `name` to `project`?" — the question asked when another app hands us
 * a file while a project is open.
 *
 * The field is the destination: for one file, its path in the project
 * (pre-filled with the file's own name, the stem selected so a rename is one
 * keystroke away); for several, the folder they all go into, where an empty
 * field is the project root. A clash is *not* an error here — the file
 * gets ` copy` appended when it lands (Zed's duplicate scheme) — because
 * the sender chose the name, not the user, and refusing would make them
 * invent one.
 *
 * "Scratch" is the third answer: keep the file out of this project and put
 * it in the Scratch project instead — for a file that was shared to be
 * *looked at*, not to join the tree.
 */
@Composable
fun ImportFileDialog(
    files: List<StagedFile>,
    projectName: String,
    errorFor: (String) -> String?,
    onConfirm: (destination: String) -> Unit,
    onScratch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val single = files.singleOrNull()
    val initial = single?.name.orEmpty()
    var value by remember {
        val dot = initial.lastIndexOf('.')
        mutableStateOf(TextFieldValue(initial, TextRange(0, if (dot > 0) dot else initial.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // Several files: the field names a folder, and blank means the root.
    val error = if (single != null || value.text.isNotBlank()) errorFor(value.text) else null
    val canConfirm = (single == null || value.text.isNotBlank()) && error == null

    val what = if (single != null) "“${single.name}”" else "${files.size} files"
    PanelDialog(title = "ADD TO ${projectName.uppercase()}", onDismiss = onDismiss) {
        Text(
            text = "Add $what to $projectName?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp),
        )
        PanelTextField(
            value = value,
            onValueChange = { value = it },
            placeholder = if (single != null) "Path in the project" else "Folder (blank for the root)",
            focusRequester = focus,
            onSubmit = { if (canConfirm) onConfirm(value.text) },
            onEscape = onDismiss,
        )
        if (error != null) PanelMessage(error, isError = true)
        PanelActions {
            PanelTextAction(stringResource(R.string.import_cancel), onClick = onDismiss)
            PanelTextAction(stringResource(R.string.import_scratch), onClick = onScratch)
            PanelTextAction(stringResource(R.string.import_add), enabled = canConfirm) { onConfirm(value.text) }
        }
    }
}
