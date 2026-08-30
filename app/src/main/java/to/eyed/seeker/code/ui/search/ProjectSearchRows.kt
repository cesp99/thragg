package to.eyed.seeker.code.ui.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import to.eyed.seeker.code.core.ProjectSearchFile
import to.eyed.seeker.code.core.ProjectSearchMatch

/**
 * One drawn line of the results list.
 *
 * The engine hands back files with their matches nested; a `LazyColumn` wants
 * one flat list, and so does keyboard navigation — the arrows walk what is on
 * screen, not a tree. Flattening is pure, which is why it lives here and is
 * tested rather than being written inline in the panel.
 */
sealed interface ProjectSearchRow {
    /**
     * The project path of the file this row belongs to — the spelling the
     * workspace opens files by, which in a project with several folders
     * carries the folder's name in front of it.
     */
    val path: String

    /**
     * List key. Results only ever grow and a file is published once, so a
     * row's key never moves and the list never re-measures what it already
     * drew while a search is still running.
     */
    val key: String

    data class FileRow(
        override val path: String,
        val name: String,
        /**
         * Everything before the name, "" at a folder's root. Includes the
         * folder's own name once the project has more than one.
         */
        val directory: String,
        /** Matches in the file in all — larger than the rows below when capped. */
        val matchCount: Int,
        val isCollapsed: Boolean,
    ) : ProjectSearchRow {
        override val key: String get() = "f/$path"
    }

    data class MatchRow(
        override val path: String,
        /** Position within the file's own matches, and half of the row key. */
        val index: Int,
        val match: ProjectSearchMatch,
    ) : ProjectSearchRow {
        override val key: String get() = "m/$path/$index"
    }
}

/** The files, flattened to rows, with [collapsed] files showing their header only. */
fun projectSearchRows(
    files: List<ProjectSearchFile>,
    collapsed: Set<String>,
): List<ProjectSearchRow> {
    val rows = ArrayList<ProjectSearchRow>(files.size)
    for (file in files) {
        // The engine's `project_path` is what opens the file; it falls back to
        // the folder-relative path for anything that predates it, which is
        // also the right answer for a project with one folder.
        val path = file.projectPath.ifEmpty { file.path }
        val isCollapsed = path in collapsed
        val cut = path.lastIndexOf('/')
        rows.add(
            ProjectSearchRow.FileRow(
                path = path,
                name = path.substring(cut + 1),
                directory = if (cut < 0) "" else path.substring(0, cut),
                matchCount = file.matchCount,
                isCollapsed = isCollapsed,
            )
        )
        if (isCollapsed) continue
        file.matches.forEachIndexed { index, match ->
            rows.add(ProjectSearchRow.MatchRow(path, index, match))
        }
    }
    return rows
}

/** Zed's own separator for a glob list (`Include: e.g. src/**/*.rs`). */
private const val GLOB_SEPARATOR = ','

/** The globs a filter field holds. Blank entries are nothing, not "match all". */
fun globsOf(text: String): List<String> = text
    .split(GLOB_SEPARATOR)
    .map { it.trim() }
    .filter { it.isNotEmpty() }

private const val ELLIPSIS = "…"

/**
 * The line a match sits on, with the hit washed in [highlight].
 *
 * The engine gives the hit as UTF-16 offsets into this very string precisely
 * so that no re-scanning happens here — but they are clamped all the same,
 * because the ellipses this adds for a windowed line would otherwise shift
 * every offset after them and paint the wash over the wrong characters.
 */
fun matchLine(match: ProjectSearchMatch, highlight: Color): AnnotatedString {
    val text = match.text
    val start = match.startUtf16.coerceIn(0, text.length)
    val end = match.endUtf16.coerceIn(start, text.length)
    return buildAnnotatedString {
        if (match.clippedStart) append(ELLIPSIS)
        append(text, 0, start)
        if (end > start) {
            withStyle(SpanStyle(background = highlight)) { append(text, start, end) }
        }
        append(text, end, text.length)
        if (match.clippedEnd) append(ELLIPSIS)
    }
}
