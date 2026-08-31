package to.eyed.seeker.code.ui.shell.code

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import to.eyed.seeker.code.core.FileMatch
import to.eyed.seeker.code.core.ProjectEntry
import to.eyed.seeker.code.core.ProjectSearchFile
import to.eyed.seeker.code.core.ProjectSearchSession
import to.eyed.seeker.code.core.ProjectSession
import to.eyed.seeker.code.core.SearchQuery
import to.eyed.seeker.code.ui.search.matchLine
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.LocalZedTheme
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
    onDismiss: () -> Unit,
) {
    val theme = LocalZedTheme.current
    var mode by remember { mutableStateOf(initialMode) }
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }

    // ⌕ opened this sheet to search, so the keyboard comes with it; ☰ opened
    // it to browse, so it does not — a tree with 300dp of keyboard over it is
    // not a tree.
    LaunchedEffect(Unit) {
        if (initialMode == FilesMode.InFiles) runCatching { focus.requestFocus() }
    }

    SheetScaffold(
        state = shell,
        onDismiss = onDismiss,
        title = "Files",
        field = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "⌕",
                    style = MaterialTheme.typography.labelLarge,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.padding(end = 8.dp),
                )
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                    ),
                    cursorBrush = SolidColor(theme.color("text.accent", MaterialTheme.colorScheme.primary)),
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
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // "＋ New file" wants a directory to create in and a name to
                // ask for, which is ProjectPanelMenu's long-press sheet — the
                // tree below already carries it, so this row does not
                // duplicate a worse version of it. ⑂ Changes is P7's route,
                // pushed rather than opened here.
                SheetAction("⑂ Changes", onOpenChanges)
            }
        },
    ) {
        if (project == null) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text(
                    text = "No project is open.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
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
 * at the top of a 65% sheet, and it carries the dirty dot and a ✕ so the two
 * things you do to an open file are both here.
 */
@Composable
private fun ColumnScope.BrowseBody(
    project: ProjectSession,
    files: OpenFilesState,
    onOpenFile: (String) -> Unit,
) {
    val theme = LocalZedTheme.current
    if (files.tabs.isNotEmpty()) {
        Text(
            text = "OPEN",
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
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
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = if (file.isDirty) "●" else "○",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (file.isDirty) {
                            theme.color("text.accent", MaterialTheme.colorScheme.primary)
                        } else {
                            theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                        maxLines = 1,
                    )
                    Text(
                        text = file.path.substringBeforeLast('/', "./"),
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.weight(1f, fill = true).padding(start = 8.dp),
                    )
                    Text(
                        text = "✕",
                        style = MaterialTheme.typography.labelMedium,
                        color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier
                            .touchTarget()
                            // Through the model, never `close` directly: a
                            // dirty buffer must raise the unsaved-changes
                            // dialog the host composes, or the edits since the
                            // last save go silently.
                            .clickable { files.requestClose(index) }
                            .padding(horizontal = 8.dp)
                            .semantics { contentDescription = "Close ${file.name}" },
                    )
                }
            }
        }
        HorizontalDivider(color = theme.color("border", MaterialTheme.colorScheme.outline))
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
    val theme = LocalZedTheme.current
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
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = match.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                    maxLines = 1,
                )
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
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
    val theme = LocalZedTheme.current
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

    val highlight = theme.color("search.match_background", MaterialTheme.colorScheme.primaryContainer)
    LazyColumn(modifier = Modifier.weight(1f, fill = true)) {
        for (file in found) {
            val path = file.projectPath.ifEmpty { file.path }
            item(key = "f/$path") {
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            itemsIndexedKeyed(path, file) { match ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenMatch(path, match.line) }
                        .padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    Text(
                        text = "${match.line}",
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.padding(end = 10.dp),
                    )
                    Text(
                        text = matchLine(match, highlight),
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.color("text", MaterialTheme.colorScheme.onSurface),
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

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) {
            theme.color("text", MaterialTheme.colorScheme.onSurface)
        } else {
            theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
        },
        maxLines = 1,
        modifier = Modifier
            .touchTarget()
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (selected) {
                    theme.color("element.selected", MaterialTheme.colorScheme.surfaceVariant)
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .touchTarget()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

private val OpenListMaxHeight = 176.dp

/** Shorter than project search's: this reads a snapshot in memory, not the disk. */
private const val FILTER_DEBOUNCE_MS = 80L
private const val SEARCH_DEBOUNCE_MS = 250L
private const val SEARCH_POLL_MS = 120L
private const val MAX_NAME_RESULTS = 50
