package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.ProjectSummary
import to.eyed.seeker.code.core.ProjectWorktree

/**
 * Zed's `workspace::AddFolderToProject`, on a device where "add a folder"
 * cannot mean what it means on a desktop.
 *
 * The engine scans real paths, and the only place this app can hand it one is
 * its own storage (docs/ARCHITECTURE.md, "Where projects live"). So there are
 * two routes, and the dialog is honest about the difference: a folder that is
 * *already* in app storage — another project — is added where it is, and
 * anything else has to be **copied in** first, exactly as opening it as a
 * project would.
 *
 * [candidates] are the projects on the device that are not already folders of
 * this one.
 */
@Composable
fun AddFolderDialog(
    candidates: List<ProjectSummary>,
    onAddExisting: (ProjectSummary) -> Unit,
    /** Launch the system folder picker; the copy happens after it returns. */
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    PanelDialog(title = stringResource(R.string.folder_add_folder_to_project), onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (candidates.isEmpty()) {
                    "A folder from this device is copied into the app's storage before it " +
                        "can be added — the same copy opening it as a project would make."
                } else {
                    "Pick a project already on this device to add it where it is, or " +
                        "choose a folder from the device, which is copied into the app's " +
                        "storage first."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (candidates.isNotEmpty()) {
                LazyColumn(
                    contentPadding = PickerListPadding,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .padding(top = 8.dp, start = 12.dp, end = 12.dp),
                ) {
                    items(candidates, key = { it.path }) { candidate ->
                        PickerListItem(
                            isSelected = false,
                            onClick = { onAddExisting(candidate) },
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = candidate.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${candidate.entryCount} items",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
        PanelActions {
            PanelTextAction(stringResource(R.string.folder_cancel), onClick = onDismiss)
            PanelTextAction(stringResource(R.string.folder_choose_a_folder), onClick = onImport)
        }
    }
}

/**
 * Zed's `workspace::RemoveWorktreeFromProject` from the palette, where there
 * is no row to have asked on: the folders that can go, one tap each. The
 * folder the project was opened with is not among them — the engine refuses
 * to remove it, and closing the project is what letting go of it means.
 */
@Composable
fun RemoveFolderDialog(
    folders: List<ProjectWorktree>,
    onRemove: (ProjectWorktree) -> Unit,
    onDismiss: () -> Unit,
) {
    PanelDialog(title = stringResource(R.string.folder_remove_folder_from_project), onDismiss = onDismiss) {
        if (folders.isEmpty()) {
            Text(
                text = stringResource(R.string.folder_this_project_has_only_the_folder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        } else {
            LazyColumn(
                contentPadding = PickerListPadding,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .padding(horizontal = 12.dp),
            ) {
                items(folders, key = { it.id }) { folder ->
                    PickerListItem(isSelected = false, onClick = { onRemove(folder) }) {
                        Text(
                            text = folder.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        PanelActions {
            PanelTextAction(stringResource(R.string.folder_close), onClick = onDismiss)
        }
    }
}
