package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.RecentProject
import to.eyed.seeker.code.ui.editor.matchScore
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.revealItem

/**
 * Zed's recent-projects picker — `projects::OpenRecent` on Ctrl+Alt+O
 * (default-linux.json:639; crates/recent_projects/src/recent_projects.rs).
 *
 * The rows are the projects the engine has seen opened, **newest first**,
 * which is Zed's order (its query is `ORDER BY timestamp DESC`), fuzzy-matched
 * on the name and the path as you type. Enter opens; each row carries Zed's
 * "Remove from Recent Projects" — which takes the project off the list and
 * forgets its saved workspace without touching a byte on disk. Deleting a
 * project is the *other* picker's job, and says so.
 */
@Composable
fun RecentProjectsPicker(
    projects: List<RecentProject>,
    /** The project already open, marked so nobody reopens what they are in. */
    currentPath: String?,
    onOpen: (RecentProject) -> Unit,
    onRemove: (RecentProject) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var selected by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }

    val shown = remember(projects, query.text) { rankRecentProjects(projects, query.text) }
    LaunchedEffect(shown) { if (selected >= shown.size) selected = 0 }
    LaunchedEffect(selected) {
        if (selected in shown.indices) listState.revealItem(selected)
    }

    fun confirm() {
        val project = shown.getOrNull(selected) ?: return
        onOpen(project)
    }

    fun move(delta: Int) {
        if (shown.isEmpty()) return
        val size = shown.size
        selected = ((selected + delta) % size + size) % size
    }

    PickerModal(
        onDismiss = onDismiss,
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionDown -> { move(1); true }
                Key.DirectionUp -> { move(-1); true }
                Key.Enter, Key.NumPadEnter -> { confirm(); true }
                Key.Escape -> { onDismiss(); true }
                else -> false
            }
        },
    ) {
        PickerQueryField(
            query = query,
            onQueryChange = { query = it },
            // Zed's own placeholder (recent_projects.rs).
            placeholder = "Recent projects...",
            focusRequester = focus,
        )

        if (shown.isEmpty()) {
            PickerEmptyState(
                if (projects.isEmpty()) {
                    "No projects opened yet"
                } else {
                    "No matches"
                }
            )
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PickerListPadding,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            ) {
                itemsIndexed(shown, key = { _, project -> project.path }) { index, project ->
                    RecentProjectRow(
                        project = project,
                        isCurrent = project.path == currentPath,
                        isSelected = index == selected,
                        onClick = {
                            selected = index
                            confirm()
                        },
                        onRemove = { onRemove(project) },
                    )
                }
            }
        }
    }
}

/**
 * The list [query] leaves, best match first — an empty query keeps the
 * engine's order, which is newest first.
 *
 * The name is what is matched, then the path, so typing `src` finds a
 * project called that before one that merely lives under a `src` directory.
 * Zed ranks the same two strings (`recent_projects.rs` matches over the
 * "match candidates" it builds from each workspace's paths).
 */
internal fun rankRecentProjects(
    projects: List<RecentProject>,
    query: String,
): List<RecentProject> {
    if (query.isBlank()) return projects
    val smartCase = query.any(Char::isUpperCase)
    return projects
        .mapNotNull { project ->
            val name = matchScore(project.name, query, smartCase)
            val path = matchScore(project.path, query, smartCase)
            // A name hit outranks a path hit outright: the score scales are
            // not comparable across strings of very different lengths.
            val score = when {
                name != null -> name + 1.0
                path != null -> path
                else -> return@mapNotNull null
            }
            project to score
        }
        .sortedWith(
            compareByDescending<Pair<RecentProject, Double>> { it.second }
                .thenByDescending { it.first.lastOpened }
        )
        .map { it.first }
}

/**
 * One row: the project's name, its path underneath, and Zed's
 * "Remove from Recent Projects" as the trailing button — the touch path for
 * a row action that is a keyboard menu in Zed.
 */
@Composable
private fun RecentProjectRow(
    project: RecentProject,
    isCurrent: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val theme = LocalZedTheme.current
    PickerListItem(isSelected = isSelected, onClick = onClick) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isCurrent) "${project.name} — open" else project.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) theme.color("text") else theme.color("text.muted"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = project.path,
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_ui_close),
                contentDescription = "Remove ${project.name} from the recent list",
                tint = theme.color("text.muted"),
                modifier = Modifier.padding(6.dp),
            )
        }
    }
}
