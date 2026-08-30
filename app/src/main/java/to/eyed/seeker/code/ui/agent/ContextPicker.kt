package to.eyed.seeker.code.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import to.eyed.seeker.code.core.AgentMention
import to.eyed.seeker.code.core.AgentSessions
import to.eyed.seeker.code.core.AgentThread
import to.eyed.seeker.code.core.CoreBridge
import to.eyed.seeker.code.core.FetchMention
import to.eyed.seeker.code.core.RulesFiles
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.workspace.parseOutline

/**
 * The sections of the composer's `@` picker — Zed's `ContextPickerMode`
 * (agent_ui's context picker: File, Directory, Symbol, Thread, Fetch,
 * Rules) plus the two Zed reaches another way: the editor's selection,
 * which Zed adds with `agent::AddSelectionToThread`, and the project's
 * diagnostics, which Zed's `MentionUri::Diagnostics` carries.
 *
 * A strip of chips rather than Zed's mode list, because the popup sits
 * above a phone keyboard: one row of words, and the section's matches under
 * it.
 */
internal enum class MentionSection(val title: String) {
    Files("Files"),
    Directories("Directories"),
    Symbols("Symbols"),
    Threads("Threads"),
    Fetch("Fetch"),
    Rules("Rules"),
    Diagnostics("Diagnostics"),
    Selection("Selection"),
}

/** One row of the picker: what it prints, and what taking it attaches. */
internal data class MentionChoice(
    val primary: String,
    val secondary: String,
    val mention: AgentMention,
)

/** An open text buffer, as the Symbols section needs it. */
data class OpenBufferRef(val bufferId: Long, val path: String)

/**
 * What the panel needs from the workspace that only the workspace has: the
 * open buffers, for their outlines; and the editor's selection. Lambdas
 * rather than values, read when the picker opens — reading the caret as
 * state would recompose the panel on every arrow key.
 */
class AgentWorkspaceAccess(
    val openBuffers: () -> List<OpenBufferRef>,
    val selection: () -> AgentMention.Selection?,
) {
    companion object {
        /** A workspace with nothing open — the panel's default. */
        val NONE = AgentWorkspaceAccess(openBuffers = { emptyList() }, selection = { null })
    }
}

/** How many rows a section shows; the query narrows, the strip does not scroll. */
private const val MAX_ROWS = 6

/**
 * The section a bare `@` opens on, and the one a URL jumps to: Zed's picker
 * opens on its mode list and this app's opened on files, so files stay the
 * default — and a query that is plainly a URL is a Fetch whatever section
 * was showing.
 */
internal fun defaultSection(query: String): MentionSection =
    if (FetchMention.looksLikeUrl(query)) MentionSection.Fetch else MentionSection.Files

/**
 * Directory candidates for a query: the directories among a project's root
 * entries, plus every ancestor of the files that matched — the fuzzy finder
 * indexes files only, and a directory is where its files are. Matched by
 * name or by path, case-insensitively, shortest path first.
 */
internal fun directoryCandidates(
    rootDirs: List<String>,
    matchedFiles: List<String>,
    query: String,
    limit: Int = MAX_ROWS,
): List<String> {
    val ancestors = matchedFiles.flatMap { path ->
        val parts = path.split('/').dropLast(1)
        parts.indices.map { depth -> parts.take(depth + 1).joinToString("/") }
    }
    val needle = query.trimEnd('/').lowercase()
    return (rootDirs + ancestors)
        .filter { it.isNotEmpty() }
        .distinct()
        .filter { needle.isEmpty() || it.lowercase().contains(needle) }
        .sortedWith(compareBy({ it.length }, { it }))
        .take(limit)
}

/** Threads other than the one showing, in the same project, newest first. */
internal fun threadChoices(
    threads: List<AgentThread>,
    active: AgentThread?,
    query: String,
): List<MentionChoice> =
    threads
        .filter { it != active && it.projectId == (active?.projectId ?: it.projectId) }
        .filter { query.isBlank() || it.listTitle.contains(query, ignoreCase = true) }
        .sortedByDescending { it.ordinal }
        .take(MAX_ROWS)
        .map { thread ->
            MentionChoice(
                primary = thread.listTitle,
                secondary = "thread",
                mention = AgentMention.Thread(thread.sessionId, thread.listTitle),
            )
        }

/**
 * The rows for [section] under [query]. **Blocking** where it reads the
 * engine — call it off the main thread; the composer debounces it as it
 * does the file search.
 */
internal fun mentionChoices(
    section: MentionSection,
    query: String,
    projectId: Long,
    rootPath: String,
    workspace: AgentWorkspaceAccess,
): List<MentionChoice> = when (section) {
    MentionSection.Files -> findMentionFiles(projectId, query).map { path ->
        MentionChoice(path.substringAfterLast('/'), path, AgentMention.File(path))
    }

    MentionSection.Directories -> {
        val rootDirs = runCatching {
            val entries = JSONArray(CoreBridge.projectEntries(projectId, ""))
            List(entries.length()) { entries.optJSONObject(it) }
                .filterNotNull()
                .filter { it.optBoolean("is_dir") && !it.optBoolean("is_hidden") }
                .map { it.optString("path") }
        }.getOrDefault(emptyList())
        val files = if (query.isBlank()) emptyList() else findMentionFiles(projectId, query, 40)
        directoryCandidates(rootDirs, files, query).map { dir ->
            MentionChoice(dir.substringAfterLast('/') + "/", dir, AgentMention.Directory(dir))
        }
    }

    MentionSection.Symbols -> workspace.openBuffers()
        .flatMap { buffer ->
            parseOutline(runCatching { CoreBridge.bufferOutline(buffer.bufferId) }.getOrNull())
                .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
                .map { item ->
                    MentionChoice(
                        primary = item.label.trim(),
                        secondary = "${buffer.path} L${item.row + 1}",
                        mention = AgentMention.Symbol(
                            path = buffer.path,
                            name = item.label.trim(),
                            startRow = item.row,
                            endRow = item.endRow,
                        ),
                    )
                }
        }
        .take(MAX_ROWS)

    MentionSection.Threads -> threadChoices(AgentSessions.threads, AgentSessions.active, query)

    // The row is an *offer*; nothing is fetched until it is taken.
    MentionSection.Fetch -> if (FetchMention.looksLikeUrl(query)) {
        listOf(MentionChoice(query, "fetch this page as text", AgentMention.Fetch(query, "")))
    } else {
        emptyList()
    }

    MentionSection.Rules -> RulesFiles.present(rootPath)
        .filter { query.isBlank() || it.contains(query, ignoreCase = true) }
        .map { MentionChoice(it, "rules file", AgentMention.Rules(it)) }

    MentionSection.Diagnostics -> {
        val summary = runCatching { JSONObject(CoreBridge.lspDiagnostics(projectId)) }.getOrNull()
        val errors = summary?.optInt("errors") ?: 0
        val warnings = summary?.optInt("warnings") ?: 0
        val detail = when {
            errors == 0 && warnings == 0 -> "no problems right now"
            else -> "$errors errors, $warnings warnings"
        }
        listOf(MentionChoice("Diagnostics", detail, AgentMention.Diagnostics))
    }

    MentionSection.Selection -> workspace.selection()?.let { selection ->
        listOf(MentionChoice(selection.label, "the editor's selection", selection))
    } ?: emptyList()
}

/** The `@` popup's files: [CoreBridge.projectFindFiles], paths only. */
internal fun findMentionFiles(projectId: Long, query: String, limit: Int = MAX_ROWS): List<String> =
    runCatching {
        val matches = JSONArray(CoreBridge.projectFindFiles(projectId, query, limit.toLong()))
        List(matches.length()) { index ->
            matches.optJSONObject(index)?.optString("path").orEmpty()
        }.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

/** The strip of section chips over the picker's rows. */
@Composable
internal fun MentionSectionStrip(
    selected: MentionSection,
    onSelect: (MentionSection) -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (section in MentionSection.entries) {
            val on = section == selected
            Text(
                text = section.title,
                style = MaterialTheme.typography.labelSmall,
                color = if (on) theme.color("text") else theme.color("text.muted"),
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (on) theme.color("element.selected", Color.Transparent) else Color.Transparent,
                    )
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClickLabel = section.title) { onSelect(section) }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}
