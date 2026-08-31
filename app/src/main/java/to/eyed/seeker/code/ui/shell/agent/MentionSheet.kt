package to.eyed.seeker.code.ui.shell.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.core.AgentMention
import to.eyed.seeker.code.core.ProjectSession
import to.eyed.seeker.code.ui.agent.AgentWorkspaceAccess
import to.eyed.seeker.code.ui.agent.MentionChoice
import to.eyed.seeker.code.ui.agent.MentionSection
import to.eyed.seeker.code.ui.agent.OpenBufferRef
import to.eyed.seeker.code.ui.agent.defaultSection
import to.eyed.seeker.code.ui.agent.mentionChoices
import to.eyed.seeker.code.ui.components.SeekerChip
import to.eyed.seeker.code.ui.components.SeekerSearchField
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.shell.code.CodeState
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.MonoSmall

/**
 * How long a keystroke waits before the picker's files are matched.
 *
 * [mentionChoices] blocks under the engine's project mutex, so firing on every
 * keystroke queues stale searches that contend with the fresh one — the file
 * finder's debounce, for the same reason.
 */
private const val SearchDebounceMillis = 120L

/**
 * The `@` picker, as a sheet rather than as a popup over the composer.
 *
 * On a desktop this is an inline completion strip; on a 400 dp column with the
 * keyboard up there are perhaps two rows of space above the box, and a picker
 * that shows two of eleven matches is a picker nobody uses. So it is a modal
 * sheet with the **field pinned at the bottom**, where the IME lands under it
 * and the matches scroll above (docs/UI.md, "Navigation" — every sheet in this
 * app does this).
 *
 * The sections are Zed's context-picker modes, kept whole: files, directories,
 * symbols, other threads, a fetched page, the project's rules files, its
 * diagnostics and the editor's current selection. Two of them — Symbols and
 * Selection — need the editor, which is why [workspace] exists; with no buffer
 * open they say so rather than showing an empty list.
 */
@Composable
fun MentionSheet(
    shell: ShellState,
    project: ProjectSession?,
    workspace: AgentWorkspaceAccess,
    onPick: (AgentMention) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var section by remember { mutableStateOf(MentionSection.Files) }
    var rows by remember { mutableStateOf(emptyList<MentionChoice>()) }
    var searching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    // A query that is plainly a URL is a Fetch whatever section was showing —
    // the same rule the inline picker follows.
    LaunchedEffect(query) {
        if (query.isNotBlank() && defaultSection(query) == MentionSection.Fetch) {
            section = MentionSection.Fetch
        }
    }

    LaunchedEffect(query, section, project?.id) {
        val open = project
        if (open == null) {
            rows = emptyList()
            return@LaunchedEffect
        }
        searching = true
        delay(SearchDebounceMillis)
        rows = withContext(Dispatchers.IO) {
            mentionChoices(section, query, open.id, open.rootPath, workspace)
        }
        searching = false
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    SheetScaffold(
        state = shell,
        onDismiss = onDismiss,
        title = "Add context",
        // The same pill the composer has, in the same place the composer's is:
        // a user who has typed a message knows what this is before reading it
        // (docs/VISUAL.md, "The component library", SeekerSearchField).
        field = {
            SeekerSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search " + section.title.lowercase(),
                focusRequester = focus,
            )
        },
    ) {
        SectionStrip(selected = section, onSelect = { section = it })
        if (rows.isEmpty()) {
            Text(
                text = emptySectionLine(section, project != null, searching),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MD.space4, vertical = MD.space3),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(rows.size, key = { rows[it].primary + " " + rows[it].secondary }) { index ->
                val choice = rows[index]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(RowHeight)
                        .clickable(onClickLabel = choice.primary) {
                            onPick(choice.mention)
                            onDismiss()
                        }
                        .padding(horizontal = MD.space4),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = choice.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    // The second line is a path, a range or a count — figures
                    // and separators that want the buffer face and its tabular
                    // figures, which is exactly what [MonoSmall] is for.
                    Text(
                        text = choice.secondary,
                        style = MonoSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            }
        }
    }
}

/** Two lines of row, with room for the descenders on both. */
private val RowHeight = 60.dp

/**
 * Zed's eight context-picker modes, as a scrolling strip of chips.
 *
 * It replaces `MentionSectionStrip` (ContextPicker.kt), which drew each mode
 * as a 3dp-padded `Text` on `element.selected` — a Zed read, in a sheet that
 * is Material on both sides of it, at a target well under 48dp. These are
 * [SeekerChip]s: the same pill the rest of the app filters with, tinted at 14%
 * when they are the mode showing, and each one announcing its own selected
 * state rather than relying on a colour a screen reader cannot see.
 */
@Composable
private fun SectionStrip(
    selected: MentionSection,
    onSelect: (MentionSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = MD.space4, vertical = MD.space2),
        horizontalArrangement = Arrangement.spacedBy(MD.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(MentionSection.entries.size, key = { MentionSection.entries[it].name }) { index ->
            val entry = MentionSection.entries[index]
            val on = entry == selected
            SeekerChip(
                label = entry.title,
                onClick = { onSelect(entry) },
                tint = if (on) scheme.primary else null,
                modifier = Modifier.semantics { this.selected = on },
            )
        }
    }
}

/**
 * What an empty section says.
 *
 * Every one of these names the thing that would fill it, because "Nothing
 * matches" over a section that *cannot* have matches — Symbols with no file
 * open — is the sentence that makes a working feature look broken.
 */
internal fun emptySectionLine(
    section: MentionSection,
    hasProject: Boolean,
    searching: Boolean,
): String = when {
    !hasProject -> "Open a project first."
    searching -> "Searching…"
    section == MentionSection.Selection -> "Select something in the editor first."
    section == MentionSection.Symbols -> "Open a file with symbols first."
    section == MentionSection.Threads -> "No other thread in this project."
    section == MentionSection.Fetch -> "Type an https:// address."
    section == MentionSection.Rules ->
        "No rules file (AGENTS.md, CLAUDE.md, .rules…) at the project root."
    else -> "Nothing matches."
}

/**
 * The editor, as the picker needs it.
 *
 * Read through lambdas rather than as values, because the caret moves on every
 * arrow key and reading it as state would recompose the whole destination.
 * Code's buffers are process-wide ([CodeState]), so this works from the Agent
 * destination even though Code is not composed — which is exactly the case
 * that matters: you come here *to talk about* the file you were just in.
 */
@Composable
fun rememberCodeWorkspace(): AgentWorkspaceAccess = remember {
    AgentWorkspaceAccess(
        openBuffers = {
            CodeState.current.files.tabs.mapNotNull { tab ->
                tab.session?.let { OpenBufferRef(it.id, tab.path) }
            }
        },
        selection = { codeSelectionMention() },
    )
}

/** The editor's selection as a mention, or null when nothing is selected. */
private fun codeSelectionMention(): AgentMention.Selection? {
    val tab = CodeState.current.files.active ?: return null
    val editor = tab.editor ?: return null
    val range = editor.selectionRange() ?: return null
    val text = editor.selectionText()
    if (text.isEmpty()) return null
    return AgentMention.Selection(
        path = tab.path,
        startRow = range.startRow,
        // An end at column 0 is the line break, not the next line.
        endRow = if (range.endCol == 0 && range.endRow > range.startRow) {
            range.endRow - 1
        } else {
            range.endRow
        },
        text = text,
    )
}
