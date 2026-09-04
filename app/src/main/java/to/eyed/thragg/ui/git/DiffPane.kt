package to.eyed.thragg.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.thragg.core.FileDiff
import to.eyed.thragg.core.GitHunk
import to.eyed.thragg.core.GitSession
import to.eyed.thragg.core.PatchLine
import to.eyed.thragg.core.PatchResult
import to.eyed.thragg.core.ProjectSession
import to.eyed.thragg.core.ResumedEffect
import to.eyed.thragg.core.pollVersion
import to.eyed.thragg.ui.theme.BufferFontFamily
import to.eyed.thragg.ui.theme.LocalAppSettings
import to.eyed.thragg.ui.theme.LocalZedTheme
import to.eyed.thragg.ui.theme.touchTarget

/** What a diff tab is looking at. */
data class DiffTarget(
    /** A project-relative path, or null for every changed file. */
    val path: String?,
    /** The index against HEAD, rather than the working tree against HEAD. */
    val staged: Boolean = false,
    /**
     * A commit's hash: the tab is then that commit against its first parent —
     * Zed's CommitView — rather than the working tree, and [path] narrows it
     * to one file (the graph sidebar's per-file "View Changes"). A commit
     * never changes, so the pane reads it once instead of polling.
     */
    val commit: String? = null,
    /** The commit's subject line, which is most of its tab title. */
    val subject: String = "",
    /**
     * A branch name: the tab is then the whole branch against its merge base
     * with that branch — Zed's Branch Diff ("Changes since {branch}",
     * branch_diff.rs:43), the clean tree's "View Branch Diff". Worktree
     * contents included, so it polls like the plain diff.
     */
    val mergeBase: String? = null,
) {
    /** What the tab strip calls it. */
    val title: String = when {
        commit != null -> commitTabTitle(commit, subject)
        mergeBase != null -> "Changes since $mergeBase"
        path == null -> "All changes"
        else -> "Diff: ${path.substringAfterLast('/')}"
    }
}

/**
 * Zed's commit tab title: `"{7-char sha} — {subject truncated to 20 chars}"`,
 * the truncation adding an ellipsis (commit_view.rs:1073-1077, via
 * `truncate_and_trailoff`).
 */
internal fun commitTabTitle(sha: String, subject: String): String {
    val short = sha.take(7)
    val trimmed = if (subject.length > 20) subject.take(20) + "…" else subject
    return "$short — $trimmed"
}

/**
 * A unified diff, drawn the way Zed's own diffs are: the old and the new text
 * in one column, added lines on green, removed on red, with both line numbers
 * down the left.
 *
 * Unified rather than side-by-side, deliberately. Side by side is the better
 * view when there is room for two 80-column panes; on a phone it is two 20-
 * column panes, and Zed's own `project_diff` is unified for the same reason.
 *
 * On the working tree's diff it is also where hunks are staged: each file
 * header carries Stage or Unstage for the whole file and each `@@` header
 * Stage / Unstage / Restore for that hunk — Zed's project diff staging
 * (git_ui/src/project_diff.rs), through the same engine calls the editor's
 * expanded hunks use. A commit's diff and a branch diff are history, and
 * carry no buttons.
 */
@Composable
fun DiffPane(
    project: ProjectSession,
    target: DiffTarget,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val session = remember(project) { GitSession(project) }
    // One read per *change*, not one per poll: the version started at zero and
    // was corrected a frame later, so opening a diff ran git twice within
    // fifteen milliseconds — which is wasteful at best, and the second of the
    // pair is what the pane ended up showing.
    var patch by remember(session, target) { mutableStateOf<PatchResult?>(null) }
    if (target.commit != null) {
        // A commit is immutable: one read, no poll loop watching it.
        LaunchedEffect(session, target) {
            patch = withContext(Dispatchers.IO) {
                session.commitPatch(target.commit, target.path)
            }
        }
    } else {
        ResumedEffect(session, target) {
            pollVersion(
                intervalMs = 400,
                version = { session.version },
                // IO, not the loop's own Default: the patch is git under proot.
                read = {
                    withContext(Dispatchers.IO) {
                        val base = target.mergeBase
                        if (base != null) {
                            session.branchPatch(base)
                        } else {
                            session.patch(target.path, target.staged)
                        }
                    }
                },
                apply = { patch = it },
            )
        }
    }

    // The staging controls, on the working tree's diff only: the staged bit
    // per hunk comes from the engine (one `git show` of the index per file),
    // re-read whenever the patch itself moves — a stage moves the panel's
    // counter, which moves the patch, which lands here.
    val stageable = target.commit == null && target.mergeBase == null && !target.staged
    val ops = remember(project) { GitOps.of(project.id) }
    var hunkStates by remember(session) { mutableStateOf<Map<String, List<GitHunk>>>(emptyMap()) }
    var stageError by remember(session) { mutableStateOf<String?>(null) }
    LaunchedEffect(patch, stageable) {
        val files = patch?.files ?: return@LaunchedEffect
        if (!stageable) return@LaunchedEffect
        hunkStates = withContext(Dispatchers.IO) {
            files.filter { !it.isBinary }.associate { file ->
                file.path to session.hunkStates(file.path).hunks
            }
        }
    }
    val controls = if (!stageable) {
        null
    } else {
        DiffControls(
            hunkStates = hunkStates,
            busy = ops.busy,
            onStageFile = { path, stage ->
                val started = GitOps.run(project.id, {
                    if (stage) session.stage(listOf(path)) else session.unstage(listOf(path))
                }) { stageError = it }
                if (!started) stageError = "Still running the last git command…"
            },
            onStageHunk = { path, rows, stage ->
                val started = GitOps.run(project.id, { session.stageHunk(path, rows, stage) }) {
                    stageError = it
                }
                if (!started) stageError = "Still running the last git command…"
            },
            onRestoreHunk = { path, rows ->
                val started = GitOps.run(project.id, { session.restoreHunk(path, rows) }) {
                    stageError = it
                }
                if (!started) stageError = "Still running the last git command…"
            },
        )
    }

    val result = patch
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.color("editor.background"))
    ) {
        stageError?.let { message ->
            HunkErrorBanner(
                message = message,
                onDismiss = { stageError = null },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when {
            result == null -> Notice("Reading the diff…")
            result.error != null -> Notice(result.error!!, isError = true)
            result.files.isEmpty() -> Notice(
                when {
                    // An empty commit — `--allow-empty` exists — or a merge
                    // whose first-parent diff is nothing.
                    target.commit != null -> "This commit changed no files"
                    // Nothing since the merge base: the branch has not
                    // actually diverged from the base branch.
                    target.mergeBase != null -> "This branch matches ${target.mergeBase}"
                    target.path == null -> "Nothing has changed since the last commit"
                    else -> "${target.path} matches the last commit"
                }
            )
            else -> DiffBody(result.files, onOpenFile, controls)
        }
    }
}

/**
 * The project diff's staging hooks — what the file and hunk headers show
 * and call. Null on a diff of history, which has nothing to stage.
 */
internal class DiffControls(
    /** The engine's hunks with their staged bit, by path. */
    val hunkStates: Map<String, List<GitHunk>>,
    /** A git command is running; the buttons grey out meanwhile. */
    val busy: Boolean,
    val onStageFile: (path: String, stage: Boolean) -> Unit,
    val onStageHunk: (path: String, rows: IntRange, stage: Boolean) -> Unit,
    val onRestoreHunk: (path: String, rows: IntRange) -> Unit,
)

@Composable
internal fun DiffBody(
    files: List<FileDiff>,
    onOpenFile: (String) -> Unit,
    controls: DiffControls? = null,
) {
    val theme = LocalZedTheme.current
    val settings = LocalAppSettings.current
    val bufferFont = BufferFontFamily
    val code = remember(settings.bufferFontSize, bufferFont) {
        TextStyle(
            fontFamily = bufferFont,
            fontSize = settings.bufferFontSize.sp,
            // `buffer_line_height: "comfortable"` = φ, as in the editor
            // itself (theme_settings/src/settings.rs:390).
            lineHeight = (settings.bufferFontSize * 1.618034f).sp,
        )
    }
    // There is no shared horizontal scroll any more, and no measured content
    // width to share across the rows: the lines wrap (see [DiffLineRow]). The
    // patch is one vertical list and nothing in it is wider than the screen.
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        for ((fileIndex, file) in files.withIndex()) {
            item(key = "file:$fileIndex") {
                FileHeader(file, onOpenFile, controls)
            }
            if (file.isBinary) {
                item(key = "binary:$fileIndex") {
                    Notice("Binary file — nothing to show line by line.")
                }
                continue
            }
            if (file.hunks.isEmpty()) {
                item(key = "empty:$fileIndex") {
                    Notice(hunklessCaption(file))
                }
                continue
            }
            for ((index, hunk) in file.hunks.withIndex()) {
                item(key = "hunk:$fileIndex:$index") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(theme.color("element.background", theme.color("border.variant")))
                            .padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            // git's own header, minus the line counts, which are
                            // in the numbers down the side anyway.
                            text = "@@ -${hunk.oldStart},${hunk.oldCount} " +
                                "+${hunk.newStart},${hunk.newCount} @@ ${hunk.heading}".trimEnd(),
                            style = code.copy(fontSize = settings.bufferFontSize.sp * 0.85f),
                            color = theme.color("text.muted"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // Zed's hunk controls on the block header: Stage or
                        // Unstage by the staged bit, then Restore
                        // (editor/src/git.rs:3077-3175).
                        val rows = if (controls == null) null else changedRows(hunk)
                        if (controls != null && rows != null) {
                            val staged = hunkStagedState(controls.hunkStates[file.path].orEmpty(), rows)
                            HeaderButton(
                                label = if (staged == true) "Unstage" else "Stage",
                                enabled = !controls.busy,
                                onClick = { controls.onStageHunk(file.path, rows, staged != true) },
                            )
                            HeaderButton(
                                label = "Restore",
                                enabled = !controls.busy,
                                onClick = { controls.onRestoreHunk(file.path, rows) },
                            )
                        }
                    }
                }
                itemsIndexed(
                    items = hunk.lines,
                    // Keyed by *position*, not by content: two files whose
                    // names git could not give us would otherwise collide, and
                    // a duplicate key throws inside LazyLayout.
                    key = { at, _ -> "line:$fileIndex:$index:$at" },
                ) { _, line ->
                    DiffLineRow(line, code)
                }
            }
        }
    }
}

/**
 * What a text file's section with no hunks means. git prints a bare header
 * for four different reasons, and only one of them is about modes: calling an
 * empty new file "Only the file's mode changed." was wrong on its face — a
 * new file has no old mode.
 */
internal fun hunklessCaption(file: FileDiff): String = when {
    file.created -> "Empty file added."
    file.deleted -> "Empty file deleted."
    // A pure rename: `rename from`/`rename to` and not a line of content.
    file.original != null -> "Renamed — the contents are unchanged."
    else -> "Only the file's mode changed."
}

/**
 * A file's header, in the clothes of Zed's multibuffer excerpt header: the
 * whole strip is `FILE_HEADER_HEIGHT` = 2 buffer lines with 4px of padding
 * around a card (`BUFFER_HEADER_PADDING` = rems(0.25); editor.rs:290-291),
 * and the card is `rounded_sm`, 1px `border`, `editor.subheader.background`,
 * `pl_1`/`pr_2` with a `gap_1p5`, the filename set in the buffer font
 * (element/header.rs:707-733, 843-851). The +/− counts are the header's diff
 * stat; "open" stands in for its open-file button, one label instead of an
 * icon we do not ship.
 */
@Composable
private fun FileHeader(file: FileDiff, onOpenFile: (String) -> Unit, controls: DiffControls?) {
    val theme = LocalZedTheme.current
    val settings = LocalAppSettings.current
    val colours = remember(theme) {
        to.eyed.thragg.ui.workspace.GitStatusColours.from(
            theme,
            theme.color("text"),
            theme.color("text.muted"),
        )
    }
    val bufferLine = settings.bufferFontSize * 1.618034f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height((bufferLine * 2).dp)
            .padding(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .background(theme.color("editor.subheader.background"))
                .border(1.dp, theme.color("border"), RoundedCornerShape(4.dp))
                .padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = file.original?.let { "$it → ${file.path}" } ?: file.path,
                style = TextStyle(
                    fontFamily = BufferFontFamily,
                    fontSize = settings.bufferFontSize.sp,
                ),
                color = theme.color("text"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = "+${file.added}",
                style = MaterialTheme.typography.labelMedium,
                color = colours.added,
            )
            Text(
                text = "−${file.removed}",
                style = MaterialTheme.typography.labelMedium,
                color = colours.deleted,
            )
            Spacer(modifier = Modifier.weight(1f))
            // Zed's per-file Stage / Unstage on the excerpt header
            // (project_diff.rs `render_file_header` buttons): one button,
            // reading by whether every hunk of the file is in the index.
            if (controls != null && !file.isBinary) {
                val staged = fileStagedState(controls.hunkStates[file.path].orEmpty())
                HeaderButton(
                    label = if (staged == true) "Unstage" else "Stage",
                    enabled = !controls.busy,
                    onClick = { controls.onStageFile(file.path, staged != true) },
                )
            }
            val openInteraction = remember { MutableInteractionSource() }
            val openHovered by openInteraction.collectIsHoveredAsState()
            Text(
                text = "open",
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (openHovered) {
                            theme.color("ghost_element.hover", Color.Transparent)
                        } else {
                            Color.Transparent
                        }
                    )
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        interactionSource = openInteraction,
                        // Instant swap, no ripple, as everywhere in Zed.
                        indication = null,
                        onClickLabel = "Open the file",
                    ) { onOpenFile(file.path) }
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * A header's verb — a ghost button of small muted text, as the file
 * header's "open" is drawn, greyed while git is running.
 *
 * [touchTarget] is the one change this file took in the Material pass, and it
 * is the exception that proves the rule: the diff keeps every colour, every
 * rem and its no-ripple rule, because it must agree with the same hunk drawn
 * in the editor two taps away (docs/VISUAL.md, "Diff"). But Stage / Unstage /
 * Restore were 16dp of drawn text and 16dp of hit box on a 480dpi phone, and
 * "which of these three verbs did my thumb just land on" is not a question a
 * staging control may ask. Outermost in the chain, as `ThraggIconButton` puts
 * it: the ink is unchanged and the pointer bounds around it are not.
 */
@Composable
private fun HeaderButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (enabled) theme.color("text.muted") else theme.color("text.disabled", theme.color("text.muted")),
        modifier = Modifier
            .touchTarget()
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (hovered && enabled) {
                    theme.color("ghost_element.hover", Color.Transparent)
                } else {
                    Color.Transparent
                }
            )
            .then(
                if (enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = label,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * One line of a patch: its two line numbers, and its text — which **wraps**.
 *
 * Wrapping is the phone's rule and it is not a preference. 400dp of column at
 * the buffer font is a little over forty characters, and a diff that has to be
 * dragged sideways to be read is a diff that is not read: "diffs word-wrap,
 * never scroll horizontally" (docs/UI.md, "Diff"). What this used to do — one
 * `horizontalScroll` shared by every row and sized to the longest line — is
 * what [across] and [contentWidth] carried. They are kept and ignored rather
 * than removed: the two call sites still passing them are the agent's diff
 * cards in ui/agent/, which P7 does not own, and they go when those do.
 *
 * The tint is the second half of the same change, and it is on the **row**
 * rather than on the first visual line. A wrapped continuation of a `−` line
 * carries no sign of its own and is otherwise indistinguishable from a `+`
 * line — the one way a diff can lie about which side of the change you are
 * reading. The Row is as tall as the wrapped text, so every visual line of a
 * changed line sits on `created.background` / `deleted.background`.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun DiffLineRow(
    line: PatchLine,
    code: TextStyle,
    /** Ignored — see above. Kept so ui/agent/'s two call sites still compile. */
    across: ScrollState? = null,
    /** Ignored — see above. */
    contentWidth: Int = 0,
) {
    val theme = LocalZedTheme.current
    // The tokens Zed highlights expanded hunk rows with: the status pair
    // `created.background` / `deleted.background` (crates/theme/src/styles/
    // status.rs:19, 96), whose alpha is baked into the theme hex.
    val background = when (line.kind) {
        '+' -> theme.color("created.background", theme.color("created").copy(alpha = 0.16f))
        '-' -> theme.color("deleted.background", theme.color("deleted").copy(alpha = 0.16f))
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background),
        verticalAlignment = Alignment.Top,
    ) {
        LineNumber(if (line.oldLine == 0) "" else line.oldLine.toString(), code)
        LineNumber(if (line.newLine == 0) "" else line.newLine.toString(), code)
        Text(
            text = "${line.kind}${line.text}",
            style = code,
            color = theme.color("editor.foreground"),
            // No `maxLines`: the whole line is the point. A tab-indented Rust
            // line wraps to three visual rows on this screen and all three are
            // tinted.
            softWrap = true,
            modifier = Modifier.weight(1f).padding(end = 4.dp),
        )
    }
}

@Composable
internal fun LineNumber(text: String, code: TextStyle) {
    val theme = LocalZedTheme.current
    Text(
        text = text,
        style = code.copy(fontSize = code.fontSize * 0.85f),
        color = theme.color("editor.line_number"),
        maxLines = 1,
        modifier = Modifier
            .width(44.dp)
            .padding(end = 6.dp),
    )
}

@Composable
private fun Notice(text: String, isError: Boolean = false) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            // Zed centres a muted default-size label in an empty surface.
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) theme.color("error") else theme.color("text.muted"),
        )
    }
}
