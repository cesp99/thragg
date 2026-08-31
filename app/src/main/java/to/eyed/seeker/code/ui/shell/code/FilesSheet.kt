package to.eyed.seeker.code.ui.shell.code

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.FileMatch
import to.eyed.seeker.code.core.ProjectEntry
import to.eyed.seeker.code.core.ProjectSearchFile
import to.eyed.seeker.code.core.ProjectSearchSession
import to.eyed.seeker.code.core.ProjectSession
import to.eyed.seeker.code.core.SearchQuery
import to.eyed.seeker.code.ui.search.matchLine
import to.eyed.seeker.code.ui.components.HairlineDivider
import to.eyed.seeker.code.ui.components.SectionHeader
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.IconSize
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.MonoSmall
import to.eyed.seeker.code.ui.theme.SeekerIcon
import to.eyed.seeker.code.ui.theme.SeekerIconButton
import to.eyed.seeker.code.ui.theme.mutedIcon
import to.eyed.seeker.code.ui.theme.touchTarget
import to.eyed.seeker.code.ui.workspace.OpenFilesState
import to.eyed.seeker.code.ui.workspace.ProjectPanel

/** Which question the one field is asking. */
enum class FilesMode {
    /** Fuzzy-match file *names* — the engine's matcher, FileFinder's half. */
    Names,

    /** Search the contents of every file — the cancellable engine-side search. */
    InFiles,
}

/**
 * Files & Find — the only file-navigation surface in the app.
 *
 * One sheet replaces five things: the project-panel dock, the Ctrl+P fuzzy
 * finder, the Ctrl+Tab switcher, the project-search panel and the outline
 * picker's file half. That consolidation is not a simplification for its own
 * sake — each of those was a separate surface with a separate keyboard-only
 * route into it, and on a device with no keyboard a surface with no touch
 * target is a surface that does not exist.
 *
 * A sheet rather than a dock, because you spend hours in a buffer and seconds
 * picking a file, and 400dp has no side to dock to (docs/UI.md, "The design
 * chosen").
 *
 * **The field is at the bottom.** That is the one rule [SheetScaffold] exists
 * to enforce and the one this sheet most needs: the IME lands directly under
 * the field, and the results scroll above it, rather than the desktop habit of
 * a search box at the top with the keyboard covering its own answers.
 */
@Composable
fun FilesSheet(
    shell: ShellState,
    project: ProjectSession?,
    files: OpenFilesState,
    initialMode: FilesMode,
    onOpenFile: (path: String) -> Unit,
    /** A hit in the "in files" results: the file, and the 1-based line to land on. */
    onOpenMatch: (path: String, line: Int) -> Unit,
    onOpenChanges: () -> Unit,
    /**
     * Projects & tools. It used to hang off the Code header's project chip,
     * and a [to.eyed.seeker.code.ui.components.SeekerTopBar] has no slot for
     * one — its leading position belongs to back. This sheet is where the
     * question moved to, and it belongs here: this is the surface about
     * *where the files are*, and switching project is the largest version of
     * that question. The host owns the sheet slot, so it does the swap.
     */
    onOpenProjects: () -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(initialMode) }
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }

    // The magnifier opened this sheet to search, so the keyboard comes with
    // it; the tree button opened it to browse, so it does not — a tree with
    // 300dp of keyboard over it is not a tree.
    LaunchedEffect(Unit) {
        if (initialMode == FilesMode.InFiles) runCatching { focus.requestFocus() }
    }

    SheetScaffold(
        state = shell,
        onDismiss = onDismiss,
        title = "Files",
        field = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SeekerIcon(
                    icon = R.drawable.ic_ui_magnifying_glass,
                    contentDescription = null,
                    tint = mutedIcon,
                    size = IconSize.Inline,
                    modifier = Modifier.padding(end = MD.space2),
                )
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .weight(1f, fill = true)
                        .focusRequester(focus)
                        .semantics { contentDescription = "Filter files" },
                )
                ModeChip("names", mode == FilesMode.Names) { mode = FilesMode.Names }
                ModeChip("in files", mode == FilesMode.InFiles) { mode = FilesMode.InFiles }
            }
        },
        actions = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = MD.space2),
                horizontalArrangement = Arrangement.spacedBy(MD.space2),
            ) {
                // "New file" wants a directory to create in and a name to ask
                // for, which is ProjectPanelMenu's long-press sheet — the tree
                // below already carries it, so this row does not duplicate a
                // worse version of it. Changes is P7's route, pushed rather
                // than opened here.
                SheetAction(R.drawable.ic_ui_git_fork, "Changes", onOpenChanges)
                SheetAction(R.drawable.ic_ui_folder_import, "Projects", onOpenProjects)
            }
        },
    ) {
        if (project == null) {
            Box(modifier = Modifier.fillMaxWidth().padding(MD.space6)) {
                Text(
                    text = "No project is open.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@SheetScaffold
        }
        val text = query.text
        when {
            text.isBlank() -> BrowseBody(project, files, onOpenFile)
            mode == FilesMode.Names -> NameResults(project, text, onOpenFile)
            else -> InFileResults(project, text, onOpenMatch)
        }
    }
}

/**
 * The sheet with an empty field: what is open, then the tree.
 *
 * OPEN comes first because it is what the sheet is most often asked, and
 * because it is the Ctrl+Tab switcher's whole job — the list is short, it is
 * at the top of a 65% sheet, and it carries the dirty dot and a close button
 * so the two things you do to an open file are both here.
 */
@Composable
private fun ColumnScope.BrowseBody(
    project: ProjectSession,
    files: OpenFilesState,
    onOpenFile: (String) -> Unit,
) {
    if (files.tabs.isNotEmpty()) {
        SectionHeader(
            text = "Open",
            modifier = Modifier.padding(horizontal = MD.space4, vertical = MD.space1),
        )
        Column(
            // Capped so that a session with twenty files open still leaves the
            // tree on screen: the list scrolls inside its cap rather than
            // pushing the rest of the sheet off the bottom.
            modifier = Modifier.heightIn(max = OpenListMaxHeight).verticalScroll(rememberScrollState()),
        ) {
            files.tabs.forEachIndexed { index, file ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenFile(file.path) }
                        .padding(horizontal = MD.space4, vertical = MD.rowPadY),
                ) {
                    SeekerIcon(
                        icon = if (file.isDirty) R.drawable.ic_ui_dot else R.drawable.ic_ui_circle,
                        contentDescription = if (file.isDirty) "unsaved" else null,
                        tint = if (file.isDirty) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        size = IconSize.Marker,
                        modifier = Modifier.padding(end = MD.space2),
                    )
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        text = file.path.substringBeforeLast('/', "./"),
                        // The buffer face, because it is a path: the eleven
                        // `FontFamily.Monospace` sites this app had were the
                        // *system* mono over Material ink, which matches
                        // neither half (Type.kt, [MonoSmall]).
                        style = MonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.weight(1f, fill = true).padding(start = MD.space2),
                    )
                    // Through the model, never `close` directly: a dirty
                    // buffer must raise the unsaved-changes dialog the host
                    // composes, or the edits since the last save go silently.
                    SeekerIconButton(
                        icon = R.drawable.ic_ui_close,
                        description = "Close ${file.name}",
                        onClick = { files.requestClose(index) },
                        tint = mutedIcon,
                        size = IconSize.Inline,
                    )
                }
            }
        }
        HairlineDivider()
    }
    // The tree: ProjectTreeState's lazy, gitignore-aware worktree and
    // ProjectPanel's rows, git status colours, file icons and long-press menu
    // — kept whole and re-hosted here, with one root and nothing else.
    ProjectPanel(
        project = project,
        onOpenFile = { entry: ProjectEntry, _ -> if (!entry.isDir) onOpenFile(entry.path) },
        openedPath = files.active?.path,
        modifier = Modifier.weight(1f, fill = true),
    )
}

/** The "names" mode: the engine's fuzzy matcher, which is FileFinder's engine half. */
@Composable
private fun ColumnScope.NameResults(
    project: ProjectSession,
    query: String,
    onOpenFile: (String) -> Unit,
) {
    var matches by remember { mutableStateOf(emptyList<FileMatch>()) }
    LaunchedEffect(project, query) {
        // The match takes the engine's project mutex, so a keystroke waits
        // rather than queueing a search that contends with the next one.
        delay(FILTER_DEBOUNCE_MS)
        matches = withContext(Dispatchers.Default) {
            runCatching { project.findFiles(query, MAX_NAME_RESULTS) }.getOrDefault(emptyList())
        }
    }
    LazyColumn(modifier = Modifier.weight(1f, fill = true)) {
        items(matches, key = { it.projectPath.ifEmpty { it.path } }) { match ->
            val path = match.projectPath.ifEmpty { match.path }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenFile(path) }
                    .padding(horizontal = MD.space4, vertical = MD.rowPadY),
            ) {
                Text(
                    text = match.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = path,
                    style = MonoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
    }
}

/**
 * The "in files" mode: the cancellable engine-side project search.
 *
 * Results are a flat, tappable list of `path:line` plus the matched text, as
 * the spec asks — no multibuffer, which is a surface a 400dp column cannot
 * host and which P10 deletes.
 *
 * The session is *cancelled on the way out*: it holds every result it has
 * found, and a sheet that closes without cancelling leaves megabytes alive
 * until the project does.
 */
@Composable
private fun ColumnScope.InFileResults(
    project: ProjectSession,
    query: String,
    onOpenMatch: (path: String, line: Int) -> Unit,
) {
    var found by remember(project) { mutableStateOf(emptyList<ProjectSearchFile>()) }
    var session by remember(project) { mutableStateOf<ProjectSearchSession?>(null) }

    LaunchedEffect(project, query) {
        delay(SEARCH_DEBOUNCE_MS)
        found = emptyList()
        // Starting a search cancels whatever was running for the same project,
        // so the previous one does not have to be tracked down first.
        val started = withContext(Dispatchers.Default) {
            ProjectSearchSession(project, SearchQuery(query = query))
        }
        session = started
        var seen = -1L
        while (true) {
            val version = withContext(Dispatchers.Default) { started.version }
            if (version != seen) {
                seen = version
                // `poll` parses everything found since the last call, which
                // can be megabytes; never on the main thread.
                val results = withContext(Dispatchers.Default) { started.poll() }
                if (results.newFiles.isNotEmpty()) found = found + results.newFiles
                if (!results.state.isLive) break
            }
            delay(SEARCH_POLL_MS)
        }
    }
    DisposableEffect(session) {
        val running = session
        onDispose { running?.cancel() }
    }

    // The Material accent at the wash the design uses for a highlight, not
    // Zed's `search.match_background`: these rows are a Material list in a
    // Material sheet, and the buffer's own hits are painted by the editor
    // (docs/VISUAL.md, "THE BOUNDARY, EXACTLY").
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    LazyColumn(modifier = Modifier.weight(1f, fill = true)) {
        for (file in found) {
            val path = file.projectPath.ifEmpty { file.path }
            item(key = "f/$path") {
                Text(
                    text = path,
                    style = MonoSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = MD.space4, vertical = MD.space1),
                )
            }
            itemsIndexedKeyed(path, file) { match ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenMatch(path, match.line) }
                        .padding(start = MD.space6, end = MD.space4, top = MD.space2, bottom = MD.space2),
                ) {
                    Text(
                        text = "${match.line}",
                        style = MonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = MD.rowPadY),
                    )
                    Text(
                        text = matchLine(match, highlight),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * One file's matches as list items, keyed so the list never re-measures rows
 * it has already drawn while a search is still publishing more.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedKeyed(
    path: String,
    file: ProjectSearchFile,
    row: @Composable (to.eyed.seeker.code.core.ProjectSearchMatch) -> Unit,
) {
    file.matches.forEachIndexed { index, match ->
        item(key = "m/$path/$index") { row(match) }
    }
}

/**
 * `names` / `in files` — which question the field is asking.
 *
 * Selection is a 16% wash of the accent rather than a fill step, which is the
 * design's rule for a state (docs/VISUAL.md, "Foundations", ELEVATION): the
 * two chips sit *inside* the field's own pill, and a rung of the container
 * ladder there would read as a second field rather than as a choice.
 */
@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        modifier = Modifier
            .touchTarget()
            .clip(RoundedCornerShape(MD.pill))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = MD.space2, vertical = MD.iconGap),
    )
}

@Composable
private fun SheetAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        // The words carry the meaning here, so the icon is decoration and the
        // button's own label is what a screen reader reads.
        SeekerIcon(
            icon = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            size = IconSize.Inline,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = MD.iconGap),
        )
    }
}

private val OpenListMaxHeight = 176.dp

/** Shorter than project search's: this reads a snapshot in memory, not the disk. */
private const val FILTER_DEBOUNCE_MS = 80L
private const val SEARCH_DEBOUNCE_MS = 250L
private const val SEARCH_POLL_MS = 120L
private const val MAX_NAME_RESULTS = 50
