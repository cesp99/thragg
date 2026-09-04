package to.eyed.thragg.ui.workspace

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.thragg.core.GitFileStatus as EngineStatus
import to.eyed.thragg.core.CoreBridge
import to.eyed.thragg.core.EntrySpacing
import to.eyed.thragg.core.GitignoredFiles
import to.eyed.thragg.core.ProjectEntry
import to.eyed.thragg.core.ProjectPanelSettings
import to.eyed.thragg.core.ProjectSession
import to.eyed.thragg.core.ProjectWorktree
import to.eyed.thragg.core.ResumedEffect
import to.eyed.thragg.core.ShareOut
import to.eyed.thragg.core.ShowDiagnostics
import to.eyed.thragg.core.TrashResult
import to.eyed.thragg.core.TrashedEntry
import to.eyed.thragg.ui.editor.FileDiagnostics
import to.eyed.thragg.ui.theme.LocalThraggColors
import to.eyed.thragg.ui.theme.LocalUiFontSize
import to.eyed.thragg.ui.theme.LocalZedTheme
import to.eyed.thragg.ui.theme.glyphHeight
import to.eyed.thragg.ui.theme.rem
import to.eyed.thragg.ui.theme.remsAt
import to.eyed.thragg.ui.theme.revealItem

/**
 * The panel's metrics, as multiples of the rem — which is `ui_font_size`
 * (theme_settings/src/settings.rs:619), so raising the UI font grows the rows
 * and the gaps with the text instead of only the text.
 *
 * Held as bare numbers rather than `Dp` so the whole table is checkable on the
 * host at any font size (`ChromeMetricsTest`); the composable getters
 * below are what the panel actually reads. The px-valued members are the
 * dimensions Zed writes as `px(…)`, which do *not* scale — see [PanelPixels].
 */
internal object PanelMetrics {

    /** `gap_1` = `rems(0.25)` between a row's icon and its name (list_item.rs:363). */
    const val ROW_GAP = 0.25f

    /**
     * `px(DynamicSpacing::Base06.rems(cx))` on the row (list_item.rs:364); the
     * indent is applied inside it.
     */
    const val ROW_PADDING = 0.375f

    /** The row's content box: `h_6` = `rems(1.5)` = 24px (project_panel.rs:6264). */
    const val ROW_CONTENT = 1.5f

    /**
     * The gradient shadow under the pinned stack: `h_1p5` = `rems(0.375)`
     * hanging off the last sticky row, black at 10% fading downward to nothing
     * (project_panel.rs:6893-6907). It belongs to that row, not the stack, so
     * the push-off drags the shadow with it, exactly as Zed's does.
     */
    const val STICKY_SHADOW = 0.375f

    /**
     * How far the git mark sits from the row's right edge.
     *
     * Zed's end slot keeps `pr_3` = `rems(0.75)` (project_panel.rs:6172); ours
     * has been half that since the density pass, and Z-18 is a change of
     * *units*, not of metrics, so `rems(0.375)` is what is recorded here — the
     * same 6px it has been drawing, now scaling like everything else.
     */
    const val STATUS_SLOT_END_PADDING = 0.375f

    /**
     * A directory's change mark: `Indicator::dot()` is `w_1p5`/`h_1p5` =
     * `rems(0.375)` (indicator.rs:73-78, project_panel.rs:6194).
     */
    const val DIRECTORY_DOT = 0.375f

    /** Room under the last row so it is not flush against the panel's end. */
    const val LIST_BOTTOM_PADDING = 0.75f

    /** The inset around the panel's one-line messages ("Scanning…", errors). */
    const val MESSAGE_PADDING = 0.75f

    /**
     * The row's pitch: the `h_6` content box inside a wrapper that always
     * carries a 1px border — usually painted in the row's own background, so
     * invisible (project_panel.rs:5793-5797). 24 + 1 + 1 = the 26px pitch, and
     * only the 24 grows with the font, because `border_1` is `px(1.)`.
     *
     * Per the 2026-08-17 density decision in DECISIONS.md that is our row too:
     * the whole row is the tap target, and everything a small target does is
     * also reachable from the long-press menu or the keyboard.
     */
    fun rowHeight(uiFontSize: Float): Dp =
        remsAt(uiFontSize, ROW_CONTENT) + PanelPixels.RowBorders

    /**
     * The pitch under Zed's `entry_spacing`: `comfortable` is
     * `ListItemSpacing::Dense` and `standard` is `ExtraDense`, whose whole
     * difference is `py_neg_px()` — one pixel off the top and one off the
     * bottom (ui/src/components/list/list_item.rs:366-367,
     * project_panel.rs:6233-6236). So the tighter of the two is exactly the
     * two border pixels shorter, and neither number scales with the font.
     */
    fun rowHeight(uiFontSize: Float, spacing: EntrySpacing): Dp = when (spacing) {
        EntrySpacing.Comfortable -> rowHeight(uiFontSize)
        EntrySpacing.Standard -> rowHeight(uiFontSize) - PanelPixels.RowBorders
    }
}

/**
 * The panel dimensions Zed writes in **pixels**, which therefore do not move
 * with `ui_font_size` and must not be spelled `rem(…)`.
 *
 * Every one is px in the source: `indent_size` is a settings number handed
 * straight to `px()` (project_panel.rs:6140, 7155),
 * `LIST_ITEM_INDENT_GUIDE_LEFT_OFFSET` is `px(15.)` (indent_guides.rs:33),
 * `PADDING_Y` is `px(4.)` (project_panel.rs:7215) and the guide itself and the
 * row's borders are `px(1.)`/`px(2.)`. Growing them with the font would make
 * the tree diverge from Zed's at exactly the setting this task exists to
 * honour.
 */
internal object PanelPixels {

    /** Zed's `indent_size` (assets/settings/default.json:828). */
    val IndentPerLevel = 20.dp

    /** Guides are 1px, at every indent level a row is nested under. */
    val GuideWidth = 1.dp

    /**
     * Guides sit 15px right of each level's start, lining up with the icon
     * column (ui::LIST_ITEM_INDENT_GUIDE_LEFT_OFFSET, indent_guides.rs:33,
     * applied in project_panel.rs:7212-7260).
     */
    val GuideOffset = 15.dp

    /**
     * A guide run stops 4px short of each of its real ends — `PADDING_Y`
     * (project_panel.rs:7215, applied at 7232-7248).
     */
    val GuideEndInset = 4.dp

    /**
     * The open file's border is 1px around plus a 2px rail on the right edge —
     * `border_1().border_r_2()` in `panel.focused_border`
     * (project_panel.rs:5793-5797).
     */
    val ActiveRowRail = 2.dp

    /** The 1px top and bottom of every row's wrapper, which make the 26px pitch. */
    val RowBorders = 2.dp
}

private val RowPadding: Dp
    @Composable @ReadOnlyComposable get() = rem(PanelMetrics.ROW_PADDING)

private val RowGap: Dp
    @Composable @ReadOnlyComposable get() = rem(PanelMetrics.ROW_GAP)

/**
 * The row, with the accessibility floor on top of Zed's metric.
 *
 * `max(26dp-at-the-default, the name's ink + the wrapper's two borders)`: at
 * every ordinary font scale this is exactly [PanelMetrics.rowHeight], and it
 * grows only once the *system's* font scale has made a file name taller than
 * the row Zed specifies — the point at which a fixed height starts cutting the
 * tops off ascenders. See [glyphHeight].
 */
@Composable
@ReadOnlyComposable
private fun rowHeight(spacing: EntrySpacing): Dp = maxOf(
    PanelMetrics.rowHeight(LocalUiFontSize.current, spacing),
    glyphHeight(MaterialTheme.typography.bodyMedium) + PanelPixels.RowBorders,
)

private val StickyShadowHeight: Dp
    @Composable @ReadOnlyComposable get() = rem(PanelMetrics.STICKY_SHADOW)

private val StatusSlotEndPadding: Dp
    @Composable @ReadOnlyComposable get() = rem(PanelMetrics.STATUS_SLOT_END_PADDING)

/**
 * Whether the guide for [level] has one of its ends on this row, given the
 * rendered depth of the neighbouring row that way.
 *
 * A run at level ℓ spans exactly the contiguous rows drawn deeper than ℓ, so
 * this row holds an end of it precisely when the row next door no longer draws
 * ℓ. The neighbours are the *tree's*, not the viewport's, so a run cut off by
 * the top or bottom of the list keeps running where it is cut.
 *
 * **This is a deliberate, measured deviation from Zed, and it is not the one a
 * first reading suggests.** Zed computes its guides over the visible window
 * only, and decides the 4px per *run*, not per end: `offset` is `px(0.)` when
 * the run is flagged `continues_offscreen` and `PADDING_Y` otherwise, and that
 * one number both moves the origin down and shortens the length by twice
 * itself (project_panel.rs:7231-7248). The flag is set only for a run reaching
 * the *last* row of the window whose level continues into it
 * (indent_guides.rs:490-500). Two consequences follow, and they point opposite
 * ways:
 *
 * - a run that starts on screen and continues past the bottom draws flush at
 *   its true top in Zed, where ours insets; and
 * - a run cut off by the *top* of the window — which is never flagged, because
 *   the flag only ever looks at the bottom — gets the full 4px inset at the
 *   window's edge in Zed, a gap that slides with the scroll, where ours draws
 *   flush.
 *
 * Both are artefacts of computing guides per viewport rather than per tree.
 * Reproducing them would mean telling every row where the viewport currently
 * ends, i.e. reading the layout per row on every scroll frame on the main
 * thread, to buy a 4px gap that appears and disappears as you scroll. We draw
 * the ends the data has, and the insets stay where the run really ends.
 */
internal fun guideRunEndsHere(level: Int, neighbourRenderedDepth: Int): Boolean =
    neighbourRenderedDepth <= level

/** How often to check the engine for a newer worktree snapshot. */
private const val SCANNING_POLL_MS = 120L
private const val IDLE_POLL_MS = 1_000L

/**
 * How long the panel keeps polling quickly after a file operation.
 *
 * The worktree's own watcher is what makes a new file appear — this only
 * shortens the wait for a change we know is on its way, because a second is a
 * long time to look at a file you just created and not see it. If the watcher
 * never delivers, nothing here invents the row.
 */
private const val EXPECT_CHANGE_MS = 3_000L

/**
 * Where the panel gets per-path git status: the engine, which runs Debian's
 * git through proot behind a version counter of the same shape as the worktree
 * snapshot's. Both are cheap reads of a cache; neither ever waits on git.
 *
 * In a build with no Linux userland the counter stays 0 and the table is
 * empty, so the tree looks exactly as it always has.
 */
fun gitStatusSourceFor(project: ProjectSession): GitStatusSource = EngineGitStatusSource(project)

/**
 * Poll one project's git status into a snapshot, for `tabs.git_status`.
 *
 * The panel folds its own polling into the loop it already runs for the tree;
 * the tab strip has no such loop and needs the table anyway, so this is the
 * standalone version — the same cheap counter, the same off-main read only
 * when it moves. Returns [GitStatusSnapshot.Empty] while [enabled] is false,
 * so the setting being off costs nothing at all.
 */
@Composable
fun rememberGitStatuses(project: ProjectSession?, enabled: Boolean): GitStatusSnapshot {
    var snapshot by remember(project) { mutableStateOf(GitStatusSnapshot.Empty) }
    ResumedEffect(project, enabled) {
        if (project == null || !enabled) {
            snapshot = GitStatusSnapshot.Empty
            return@ResumedEffect
        }
        val source = gitStatusSourceFor(project)
        withContext(Dispatchers.Default) {
            var seen = -1L
            while (true) {
                val version = source.version
                if (version != seen) {
                    val read = source.snapshot()
                    seen = version
                    withContext(Dispatchers.Main) { snapshot = read }
                }
                delay(IDLE_POLL_MS)
            }
        }
    }
    return snapshot
}

private class EngineGitStatusSource(private val project: ProjectSession) : GitStatusSource {

    override val version: Long get() = project.gitStatusVersion

    override fun snapshot(): GitStatusSnapshot {
        // Read the version first: a bump between here and the table means the
        // next poll picks the change up, rather than this one recording a new
        // version against older rows.
        val version = project.gitStatusVersion
        val engine = project.gitStatus()
        if (engine.isEmpty()) return GitStatusSnapshot.of(version, emptyMap())
        val byPath = HashMap<String, GitFileStatus>(engine.size)
        for ((path, status) in engine) byPath[path] = status.forPanel()
        return GitStatusSnapshot.of(version, byPath)
    }
}

/** The engine's vocabulary, in the panel's. */
private fun EngineStatus.forPanel(): GitFileStatus = when (this) {
    EngineStatus.Modified -> GitFileStatus.Modified
    EngineStatus.Added -> GitFileStatus.Added
    EngineStatus.Deleted -> GitFileStatus.Deleted
    EngineStatus.Renamed -> GitFileStatus.Renamed
    EngineStatus.Conflicted -> GitFileStatus.Conflicted
    EngineStatus.Untracked -> GitFileStatus.Untracked
    EngineStatus.Ignored -> GitFileStatus.Ignored
}

/**
 * What the panel is asking the user before it touches the disk.
 *
 * Every one names the folder of the project it applies to: a project holds an
 * ordered list of them (Zed's worktrees) and the same relative path can exist
 * in more than one.
 */
private sealed interface PanelPrompt {
    data class NewEntry(val worktree: Long, val parent: String, val isDir: Boolean) : PanelPrompt
    data class Rename(val row: ProjectTreeRow) : PanelPrompt

    /**
     * Zed's two destructive actions, which are *not* the same thing:
     * `project_panel::Trash` (the Delete key) moves to the app's trash and can
     * be undone, `project_panel::Delete` (Shift+Delete) unlinks and cannot.
     *
     * Rows rather than paths: a multi-select can span folders of the project,
     * and the same relative path exists in two of them.
     */
    data class Delete(val rows: List<ProjectTreeRow>, val permanent: Boolean) : PanelPrompt

    /** "Stop showing this folder" — Zed's `RemoveWorktreeFromProject`. */
    data class RemoveFolder(val worktree: ProjectWorktree) : PanelPrompt

    /** An operation that didn't happen, in the words it failed with. */
    data class Failure(val message: String) : PanelPrompt
}

/**
 * Which entries the panel marks as having diagnostics — Zed's
 * `project_panel.show_diagnostics` (project_panel.rs, `diagnostic_summary`).
 *
 * **Ancestors are included.** Zed rolls a file's worst severity up through
 * every directory above it, so a collapsed tree still shows you that something
 * under `src/` is broken; the roll-up is baked into the two sets here, at the
 * cost of one pass over the diagnostic files whenever they change, so a row
 * asks a hash set rather than scanning.
 */
data class DiagnosticMarks(
    /** Paths with errors, and every directory above them. */
    val errors: Set<String>,
    /** Paths with warnings but no errors, and every directory above them. */
    val warnings: Set<String>,
) {
    /** The severity to colour [path] with, or null for none. */
    fun severityOf(path: String): DiagnosticMark? = when {
        path in errors -> DiagnosticMark.Error
        path in warnings -> DiagnosticMark.Warning
        else -> null
    }

    companion object {
        val None = DiagnosticMarks(emptySet(), emptySet())

        /**
         * Roll [files] up into the two sets, honouring [show]: `off` marks
         * nothing, `errors` only errors, `all` errors and warnings.
         */
        fun of(files: List<FileDiagnostics>, show: ShowDiagnostics): DiagnosticMarks {
            if (show == ShowDiagnostics.Off || files.isEmpty()) return None
            val errors = HashSet<String>()
            val warnings = HashSet<String>()
            for (file in files) {
                val into = when {
                    file.errors > 0 -> errors
                    show == ShowDiagnostics.All && file.warnings > 0 -> warnings
                    else -> continue
                }
                var prefix = ""
                for (part in file.path.split('/')) {
                    prefix = if (prefix.isEmpty()) part else "$prefix/$part"
                    into.add(prefix)
                }
            }
            // An error anywhere under a directory outranks a warning there, so
            // the warning set never claims a path the error set already has.
            warnings.removeAll(errors)
            return DiagnosticMarks(errors, warnings)
        }
    }
}

/** How a row marked by `show_diagnostics` is coloured. */
enum class DiagnosticMark { Error, Warning }

/**
 * What one project-panel row says to a screen reader.
 *
 * Order matters: the name first, because that is what is being looked for,
 * then what kind of thing it is, then the states that would otherwise only be
 * a colour. The depth goes last and only below the root, since "in a folder"
 * is the one fact the indentation carries and nothing else repeats.
 *
 * Pure, so the sentence can be pinned by a host test — the only way to check
 * a screen-reader string without a screen reader.
 */
internal fun projectRowDescription(
    name: String,
    isDir: Boolean,
    isExpanded: Boolean,
    status: GitFileStatus,
    isOpen: Boolean,
    isMarked: Boolean,
    diagnostic: DiagnosticMark?,
    depth: Int,
): String {
    val parts = mutableListOf(name)
    parts += if (isDir) {
        if (isExpanded) "folder, expanded" else "folder, collapsed"
    } else {
        "file"
    }
    when (status) {
        GitFileStatus.None -> Unit
        GitFileStatus.Modified -> parts += "modified"
        GitFileStatus.Added -> parts += "added"
        GitFileStatus.Untracked -> parts += "untracked"
        GitFileStatus.Deleted -> parts += "deleted"
        GitFileStatus.Renamed -> parts += "renamed"
        GitFileStatus.Conflicted -> parts += "conflicted"
        GitFileStatus.Ignored -> parts += "ignored"
    }
    when (diagnostic) {
        DiagnosticMark.Error -> parts += "has errors"
        DiagnosticMark.Warning -> parts += "has warnings"
        null -> Unit
    }
    if (isOpen) parts += "open"
    if (isMarked) parts += "selected"
    if (depth > 0) parts += "level ${depth + 1}"
    return parts.joinToString(", ")
}

/**
 * The folder a drop on [entry] means: the folder itself, or the folder a file
 * is in — what every file manager does, and what Zed's drop target resolves to
 * (project_panel.rs, `drag_onto`).
 */
private fun dropTargetOf(row: ProjectTreeRow): String = when {
    row.isRoot -> ""
    row.entry.isDir -> row.entry.path
    else -> ProjectFiles.parentOf(row.entry.path)
}

/**
 * A drag in progress: what is being moved, and the row it is currently over.
 *
 * The target is a *directory* path (`""` for the project root) — dropping on a
 * file means dropping into the folder it is in, which is what every file
 * manager does and what Zed's own drop target resolves to.
 */
private data class PanelDrag(
    val rows: List<ProjectTreeRow>,
    /** Which folder of the project [over] is in. */
    val worktree: Long,
    val over: String?,
)

/** A move a drop is waiting on, because it would overwrite something. */
private data class OverwritePrompt(
    val refs: List<PanelRef>,
    val worktree: Long,
    val destination: String,
    /** The names already in [destination]. */
    val clashes: List<String>,
)

/**
 * An entry waiting to be pasted.
 *
 * The panel's own clipboard, not the system one: cutting a directory and
 * pasting it elsewhere is a move within this project, and putting a path on
 * the system clipboard would mean something else entirely to every other app.
 * "Copy Path" is what talks to the system clipboard.
 */
/** One entry on the panel's clipboard: which folder it is in, and where. */
private data class PanelRef(val worktree: Long, val path: String) {
    val key: String get() = rowKey(worktree, path)
}

/**
 * What Cut or Copy is holding. A list, because a multi-select can be cut in
 * one go; folder-qualified, because the same relative path can exist in two
 * folders of the project.
 */
private data class PanelClipboard(val refs: List<PanelRef>, val isCut: Boolean) {
    fun holds(key: String): Boolean = refs.any { it.key == key }

    /** The one folder these came from, or null when they span several. */
    val worktree: Long? get() = refs.map { it.worktree }.distinct().singleOrNull()
}

/** An open context menu: which row it belongs to, and where it was asked for. */
private data class PanelMenu(
    /** Null for the panel header — the menu for the project itself. */
    val row: ProjectTreeRow?,
    val at: Offset,
    /**
     * Asked for on a pinned sticky row rather than the entry's real row. The
     * two can be on screen at once (the real row half-scrolled under the
     * stack), and only the one that was actually clicked should anchor the
     * popup.
     */
    val sticky: Boolean = false,
)

/**
 * Which ancestor rows are pinned over the list's top, and where.
 *
 * [indices] index into the flattened rows, outermost first. [driftPx] is ≤ 0:
 * how far the last pinned row has been pushed up by the anchor row scrolling
 * in under it — Zed's `drifting_y_offset`, which slides continuously with the
 * scroll rather than swapping (sticky_items.rs:179-186, 250-257).
 */
private data class StickyStack(
    val indices: List<Int>,
    val driftPx: Int,
    /** The measured height of one list row, so overlay and list agree in px. */
    val rowHeightPx: Int,
)

/**
 * The project tree, rendered from the engine's worktree, and the file manager
 * that goes with it.
 *
 * Directories are read one level at a time and only while expanded, so the
 * panel never walks the whole project. The engine scans asynchronously, so
 * this polls its snapshot version — fast while the initial scan is running,
 * lazily afterwards, where it doubles as external-change detection. Git status
 * rides the same poll: it is a second cheap version counter, and the rows are
 * re-coloured in place when it moves.
 *
 * File operations (see [ProjectFiles]) write to disk and stop; the rows that
 * follow come back through the watcher, which is what also makes changes from
 * the terminal appear. Everything is reachable three ways — pointer, touch and
 * keyboard — because on DeX there is no touchscreen and on a phone there is no
 * right mouse button.
 */
@Composable
fun ProjectPanel(
    project: ProjectSession?,
    /**
     * Open an entry. [preview] is Zed's `preview_tabs`: a single click asks
     * for a provisional tab, and the panel is where that is decided —
     * `enable_preview_from_project_panel`.
     */
    onOpenFile: (entry: ProjectEntry, preview: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    openedPath: String? = null,
    gitignoredFiles: GitignoredFiles = GitignoredFiles.Dimmed,
    /** The rest of Zed's `project_panel` block. */
    panel: ProjectPanelSettings = ProjectPanelSettings(),
    /** Which entries `show_diagnostics` marks, ancestors rolled up. */
    diagnostics: DiagnosticMarks = DiagnosticMarks.None,
    /**
     * Open a terminal in a directory — Zed's `project_panel::OpenInTerminal`.
     * Null in a build with no userland, where the row is not offered at all.
     */
    onOpenTerminal: ((absoluteDir: String) -> Unit)? = null,
    /**
     * Zed's `project_panel::NewSearchInDirectory`: project search, seeded with
     * an include glob for the directory.
     */
    onSearchInDirectory: ((glob: String) -> Unit)? = null,
    gitStatus: GitStatusSource? = null,
    /**
     * The workspace asking for [openedPath] to be shown and the keyboard moved
     * into the panel — Zed's `pane::RevealInProjectPanel`.
     *
     * A flag the panel clears through [onRevealHandled] rather than a counter,
     * so a request made while the panel was hidden is still waiting when it
     * appears, and a request already served doesn't fire again every time the
     * layout rebuilds the panel.
     */
    revealRequest: Boolean = false,
    /** Called once [revealRequest] has been acted on. */
    onRevealHandled: () -> Unit = {},
    /**
     * Whether the panel holds the keyboard.
     *
     * The workspace needs to know because two of its chords are the panel's
     * too: Zed binds `ctrl-n` to `workspace::NewFile` *and* to
     * `project_panel::NewFile` (default-linux.json:654, 965), and resolves
     * them by context — the panel's context is the more specific one, so it
     * wins while the panel has focus. Our workspace table is matched in a
     * preview pass above the panel, so it has to be told to stand down.
     */
    onFocusChanged: (Boolean) -> Unit = {},
    /** A path that has stopped existing, so its tab can be closed. */
    onEntryRemoved: (String) -> Unit = {},
    /** A path that moved: renamed, or cut and pasted somewhere else. */
    onEntryMoved: (from: String, to: String) -> Unit = { _, _ -> },
    /**
     * Add a folder to the project — Zed's `workspace::AddFolderToProject`.
     * The engine needs a real path, so the workspace runs the import (see
     * docs/ARCHITECTURE.md, "Where projects live"); the panel only asks.
     */
    onAddFolder: (() -> Unit)? = null,
) {
    // The Zed theme, for exactly one thing: the version-control inks below.
    // Everything else in this panel is chrome and reads the Material scheme;
    // `SeamTest.the project tree's only Zed read is its git status` is what
    // holds that line, and it fails on any other `theme.color` in this file.
    val theme = LocalZedTheme.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val density = LocalDensity.current
    var menu by remember(project) { mutableStateOf<PanelMenu?>(null) }

    // NO GROUND OF ITS OWN. The tree used to paint `panel.background` — the
    // M3 `surface` — while its only host is the Files sheet, which is
    // `surfaceContainer` (SheetScaffold.kt:110). Two rungs of the same ladder,
    // one inside the other, with no border between them: that darker slab
    // under the file list is what made the panel read as a desktop app embedded
    // in a phone app. A panel is chrome; chrome takes the ground it is given.
    Column(modifier = modifier.fillMaxSize()) {
        if (project == null) {
            PanelMessage("No project open")
            return@Column
        }

        val statusSource = remember(project, gitStatus) {
            gitStatus ?: gitStatusSourceFor(project)
        }
        val tree = remember(project, gitignoredFiles, statusSource, panel.autoFoldDirs) {
            ProjectTreeState(project, gitignoredFiles, statusSource, panel.autoFoldDirs)
        }
        // Resolved once per theme, never per row: this panel draws one row
        // per visible line per frame, and a scheme read is cheap but a map
        // read is not.
        val scheme = MaterialTheme.colorScheme
        val onSurfaceVariant = scheme.onSurfaceVariant
        // THE ONE ZED READ LEFT IN THIS FILE, and it is deliberate. Git status
        // is the panel's only colour that carries MEANING out of the theme:
        // `modified` amber, `created` green, `conflict` red are the same inks
        // the diff, the git panel and the buffer's gutter paint, and a tree
        // that answered "changed" in a Material role would disagree with all
        // three. So the hue stays Zed's — and only the hue: `solvedOn` moves
        // each one the smallest distance that clears 4.5:1 on the ground it is
        // now drawn on, because the ground is a Material sheet and Ayu Light's
        // `created` is 2.11:1 there (docs/VISUAL.md, "The hybrid").
        //
        // The panel's plain name colour is `text.muted`, not `text` —
        // `entry_label_color(false)` (items.rs:2177-2183); a marked row's name
        // is promoted in the row itself.
        val thragg = LocalThraggColors.current
        val cardGround = thragg.cardGround
        val colours = remember(theme, onSurfaceVariant, cardGround) {
            GitStatusColours
                .forProjectPanel(theme, onSurfaceVariant, onSurfaceVariant)
                .solvedOn(cardGround)
        }
        // One tint for every icon, and it is NOT one of the meaning-carrying
        // reads: the bundled icon set is monochrome by design — "the icon says
        // what kind of file it is, the row's colour says what git thinks of
        // it" (FileIcons.tintable) — so `icon.muted` here was only ever the
        // panel's quiet ink, which is what `onSurfaceVariant` is. A user icon
        // theme's own art is not tinted at all and is untouched by this.
        val iconColour = onSurfaceVariant
        // `warnInk`, not the raw `warning` key: a diagnostic mark outranks git
        // status on a row's NAME, so it is body text on the sheet and has to
        // clear 4.5:1 there — Ayu Light's raw `warning` is 1.64:1.
        val warnInk = thragg.warnInk
        // Zed's `get_item_color` gives the panel `element_hover` for hover and
        // `element_selected` for a marked row rather than the generic ListItem
        // ramp (project_panel.rs:611-629). Only the SELECTED half survives as
        // a colour: hover and press are states, so they become the M3 state
        // layer the ripple draws, which is also how a row in a sheet gets any
        // press feedback at all. The active file stays a 1px border rather
        // than a fill, as in Zed (project_panel.rs:5729-5743).
        val rowColours = remember(scheme, warnInk) {
            RowColours(
                selected = scheme.secondaryContainer,
                activeBorder = scheme.primary,
                indentGuide = scheme.outlineVariant,
                indentGuideActive = scheme.outline,
                stickyBackground = scheme.surfaceContainerHigh,
                selectedText = scheme.onSecondaryContainer,
                dropTarget = scheme.primaryContainer,
                diagnosticError = scheme.error,
                diagnosticWarning = warnInk,
            )
        }
        val dimIgnored = gitignoredFiles == GitignoredFiles.Dimmed
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        val panelFocus = remember { FocusRequester() }

        /** The folder of the project a row belongs to, if it is still open. */
        fun folderOf(worktree: Long): ProjectWorktree? =
            tree.worktrees.firstOrNull { it.id == worktree }

        /**
         * Where a row's file operations happen: its own folder's root, not the
         * project's. Falls back to the project's own folder before the first
         * flatten has told us what the folders are.
         */
        fun rootOf(worktree: Long): File =
            folderOf(worktree)?.let { File(it.path) } ?: File(project.rootPath)

        /**
         * The path the rest of the workspace opens a row by — unprefixed in
         * the project's own folder, `<folder>/<relative>` in any other, which
         * is what the engine resolves (`project_entry_abs_path`).
         */
        fun projectPathOf(worktree: Long, path: String): String {
            val folder = folderOf(worktree) ?: return path
            if (folder.isPrimary) return path
            return if (path.isEmpty()) folder.name else "${folder.name}/$path"
        }

        fun projectPathOf(row: ProjectTreeRow): String =
            projectPathOf(row.worktree, row.entry.path)

        /** The reverse: which row a project path names. */
        fun keyForProjectPath(path: String): String {
            val folders = tree.worktrees
            val primary = folders.firstOrNull() ?: return rowKey(0L, path)
            for (folder in folders.drop(1)) {
                if (path == folder.name) return rowKey(folder.id, "")
                val prefix = folder.name + "/"
                if (path.startsWith(prefix)) {
                    return rowKey(folder.id, path.removePrefix(prefix))
                }
            }
            return rowKey(primary.id, path)
        }
        var prompt by remember(project) { mutableStateOf<PanelPrompt?>(null) }
        var pending by remember(project) { mutableStateOf<PanelClipboard?>(null) }
        var expectChangeUntil by remember(project) { mutableLongStateOf(0L) }
        /** What the last Trash took, until the Undo is spent or superseded. */
        var undo by remember(project) { mutableStateOf<List<TrashedEntry>?>(null) }
        /** A drop that would overwrite, waiting on the user. */
        var overwrite by remember(project) { mutableStateOf<OverwritePrompt?>(null) }
        /** The drag in flight, and the folder it is over. */
        var drag by remember(project) { mutableStateOf<PanelDrag?>(null) }
        val rowHeight = rowHeight(panel.entrySpacing)
        val rowHeightPx = with(density) { rowHeight.roundToPx() }

        // Zed's `sticky_scroll`, on by default (settings/default.json:871):
        // once the list is scrolled, the ancestor directories of the topmost
        // visible entry pin to the panel's top. The anchor row is
        // `find_sticky_anchor` and the push-off is `drifting_y_offset`
        // (sticky_items.rs:179-186, 285-316), both on our depth basis, which
        // is one lower than Zed's because our root is a header above the list
        // rather than the list's first row — [findStickyAnchor] spells out
        // what that changes. The ancestors are `sticky_parents`
        // (project_panel.rs:6824-6846).
        //
        // Kept as a State and read in [StickyOverlay], never in this scope:
        // structural equality already drops the scroll frames that don't move
        // the stack, but the drift slides a pixel at a time for the whole of a
        // push-off, and a read here would invalidate the panel — handing
        // `LazyColumn` a fresh content lambda, and so recomposing every
        // visible row, once per frame on the main thread.
        val stickyStack = remember(tree, listState) {
            derivedStateOf(structuralEqualityPolicy()) {
                // Zed pins a row's ancestors; with several folders open the
                // list also holds their headers, and our anchor arithmetic is
                // written for a tree whose root sits *above* the list (see
                // [findStickyAnchor]). Rather than pin the wrong rows, the
                // stack stands down until the project is back to one folder.
                if (tree.isMultiRoot) return@derivedStateOf null
                if (listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                ) {
                    // Not scrolled — Zed's `is_scrolled` gate
                    // (project_panel.rs:6946-6951).
                    return@derivedStateOf null
                }
                val rows = tree.rows
                val visible = listState.layoutInfo.visibleItemsInfo
                if (visible.isEmpty()) return@derivedStateOf null
                val depths = ArrayList<Int>(visible.size)
                for (item in visible) {
                    // The rows and the layout can be one frame apart while the
                    // tree reshapes; a stack computed across that gap is wrong
                    // either way, and next frame recomputes.
                    depths += rows.getOrNull(item.index)?.depth
                        ?: return@derivedStateOf null
                }
                val anchor = findStickyAnchor(depths) ?: return@derivedStateOf null
                val anchorItem = visible[anchor.localIndex]
                val ancestors = stickyAncestorsOf(rows, anchorItem.index)
                if (ancestors.isEmpty()) return@derivedStateOf null
                val drift = stickyDriftPx(
                    anchorOffsetPx = anchorItem.offset,
                    rowHeightPx = anchorItem.size,
                    pinnedCount = ancestors.size,
                    drifting = anchor.drifting,
                )
                StickyStack(ancestors, drift, anchorItem.size)
            }
        }

        // The guide run containing the selection, in `panel.indent_guide_active`
        // (find_active_indent_guide, project_panel.rs:6724-6790). Derived from
        // selection and shape only — nothing here runs per scroll frame.
        val activeGuide by remember(tree) {
            derivedStateOf(structuralEqualityPolicy()) {
                // A folder header hangs no run of its own: everything below it
                // is its child, which would light the whole section up.
                if (tree.selectedRow?.isRoot == true) {
                    null
                } else {
                    activeGuideRun(tree.rows, tree.selected) { key -> tree.isExpanded(key) }
                }
            }
        }

        // Re-flatten after a change to the tree's shape. The rebuild reads
        // through JNI and parses JSON, so it stays off the main thread, and it
        // is published against the shape it was computed from: if the user
        // expanded something else meanwhile, that toggle's own rebuild is the
        // one that describes the tree now.
        fun reshape(change: () -> Unit) {
            change()
            val shape = tree.shape
            scope.launch {
                val rebuilt = withContext(Dispatchers.Default) { tree.rebuild() }
                tree.publish(tree.version, rebuilt, shape)
            }
        }

        /** Run a file operation off the main thread and report what it did. */
        fun operate(onDone: (String) -> Unit = {}, op: () -> FileOpResult) {
            scope.launch {
                when (val result = withContext(Dispatchers.IO) { op() }) {
                    is FileOpResult.Failed -> prompt = PanelPrompt.Failure(result.reason)
                    is FileOpResult.Done -> {
                        expectChangeUntil = SystemClock.uptimeMillis() + EXPECT_CHANGE_MS
                        onDone(result.path)
                    }
                }
            }
        }

        /**
         * Which folder a menu action lands in when it was asked for on the
         * panel itself: the project's own, as it always was.
         */
        fun worktreeFor(row: ProjectTreeRow?): Long =
            row?.worktree ?: tree.worktrees.firstOrNull()?.id ?: 0L

        /** The directory an action on [row] applies to; its root for null. */
        fun directoryFor(row: ProjectTreeRow?): String = when {
            row == null || row.isRoot -> ""
            row.entry.isDir -> row.entry.path
            else -> ProjectFiles.parentOf(row.entry.path)
        }

        /** The rows a command applies to: the marks, else this one. */
        fun targetsFor(row: ProjectTreeRow?): List<ProjectTreeRow> {
            val key = row?.key ?: return tree.targetRows
            return if (tree.isMarked(key)) tree.targetRows else listOf(row)
        }

        fun activate(row: ProjectTreeRow, preview: Boolean = true) {
            tree.select(row.key)
            tree.markOnly(row.key)
            if (row.entry.isDir || row.isRoot) {
                reshape { tree.toggle(row) }
            } else {
                onOpenFile(row.entry.copy(path = projectPathOf(row)), preview)
            }
        }

        fun createIn(row: ProjectTreeRow?, isDir: Boolean) {
            prompt = PanelPrompt.NewEntry(worktreeFor(row), directoryFor(row), isDir)
        }

        /**
         * Zed's `workspace::OpenWithSystem` on this row (project_panel.rs:3936
         * absolutizes the entry and hands it to the platform), and the share
         * sheet beside it. Files only: a directory is not a thing Android's
         * chooser can open, and the row is absent rather than greyed.
         */
        fun handOver(row: ProjectTreeRow, share: Boolean) {
            val absolute = project.absolutePathOf(row.worktree, row.entry.path) ?: return
            val file = File(absolute)
            if (share) ShareOut.share(context, file) else ShareOut.openWith(context, file)
        }

        fun duplicate(row: ProjectTreeRow) {
            operate(onDone = { path -> reshape { tree.reveal(row.worktree, path) } }) {
                ProjectFiles.duplicate(rootOf(row.worktree), row.entry.path)
            }
        }

        /**
         * Move or copy [refs] into [destination] in [worktree]. One operation
         * per entry, in order, stopping at the first refusal — the panel then
         * says why, and what has already moved has moved, which is the same
         * shape a bulk delete has.
         *
         * Everything must be in the destination's own folder. `ProjectFiles`
         * works within one root, and a move between two roots is a copy plus a
         * delete, which is not what any file manager's Cut means; Zed's panel
         * keeps a paste inside the worktree it was cut from too.
         */
        fun transfer(
            refs: List<PanelRef>,
            worktree: Long,
            destination: String,
            isCut: Boolean,
            overwrite: Boolean,
        ) {
            if (refs.isEmpty()) return
            if (refs.any { it.worktree != worktree }) {
                prompt = PanelPrompt.Failure(
                    "Cut and paste work inside one folder of the project; " +
                        "use Copy to take a file to another folder."
                )
                return
            }
            val root = rootOf(worktree)
            scope.launch {
                var last: String? = null
                for (ref in refs) {
                    val result = withContext(Dispatchers.IO) {
                        if (isCut) {
                            ProjectFiles.moveInto(root, ref.path, destination, overwrite)
                        } else {
                            ProjectFiles.copyInto(root, ref.path, destination)
                        }
                    }
                    when (result) {
                        is FileOpResult.Failed -> {
                            prompt = PanelPrompt.Failure(result.reason)
                            break
                        }

                        is FileOpResult.Done -> {
                            expectChangeUntil = SystemClock.uptimeMillis() + EXPECT_CHANGE_MS
                            if (isCut) {
                                onEntryMoved(
                                    projectPathOf(worktree, ref.path),
                                    projectPathOf(worktree, result.path),
                                )
                            }
                            last = result.path
                        }
                    }
                }
                if (isCut && pending?.isCut == true) pending = null
                tree.clearMarks()
                last?.let { path -> reshape { tree.reveal(worktree, path) } }
            }
        }

        fun paste(row: ProjectTreeRow?) {
            val source = pending ?: return
            transfer(
                source.refs,
                worktreeFor(row),
                directoryFor(row),
                source.isCut,
                overwrite = false,
            )
        }

        /**
         * A drop: move [rows] into [destination] in [worktree], asking first
         * if a name is already taken there. Zed's own drag-and-drop overwrites
         * silently where it can; a phone has no undo for that, so the prompt
         * is the safety the platform does not give us.
         */
        fun drop(rows: List<ProjectTreeRow>, worktree: Long, destination: String) {
            // A drop back where it came from, or a folder dropped onto
            // itself or into its own subtree, is a gesture that changes
            // nothing — dropped silently rather than raised as an error. A
            // row from another folder of the project goes the same way: a
            // move between two roots is a copy plus a delete, which Cut is
            // not, and a drag is not the place to ask about that.
            val moving = rows.filter { row ->
                row.worktree == worktree &&
                    !row.isRoot &&
                    ProjectFiles.parentOf(row.entry.path) != destination &&
                    destination != row.entry.path &&
                    !destination.startsWith("${row.entry.path}/")
            }
            if (moving.isEmpty()) {
                tree.clearMarks()
                return
            }
            val root = rootOf(worktree)
            val clashes = moving.mapNotNull { row ->
                val name = row.entry.path.substringAfterLast('/')
                name.takeIf { ProjectFiles.wouldOverwrite(root, destination, name) }
            }
            val refs = moving.map { PanelRef(it.worktree, it.entry.path) }
            if (clashes.isNotEmpty()) {
                overwrite = OverwritePrompt(refs, worktree, destination, clashes)
            } else {
                transfer(refs, worktree, destination, isCut = true, overwrite = false)
            }
        }

        fun confirmDelete(rows: List<ProjectTreeRow>, permanent: Boolean) {
            val real = rows.filter { !it.isRoot }
            if (real.isEmpty()) return
            prompt = PanelPrompt.Delete(real, permanent)
        }

        /** Where the selection lands once [rows] are gone, as a row key. */
        fun neighbourAfter(rows: List<ProjectTreeRow>): String? {
            val going = rows.mapTo(HashSet()) { it.key }
            fun doomed(candidate: ProjectTreeRow) = candidate.key in going ||
                rows.any {
                    it.worktree == candidate.worktree &&
                        candidate.entry.path.startsWith("${it.entry.path}/")
                }
            val index = tree.rows.indexOfFirst { it.key in going }
            if (index < 0) return null
            val below = tree.rows.drop(index + 1).firstOrNull { !doomed(it) }
            return below?.key ?: tree.rows.take(index).lastOrNull { !doomed(it) }?.key
        }

        /** Zed's `project_panel::Delete`: unlink, and nothing brings it back. */
        fun deleteNow(rows: List<ProjectTreeRow>) {
            val neighbour = neighbourAfter(rows)
            scope.launch {
                for (row in rows) {
                    val result = withContext(Dispatchers.IO) {
                        ProjectFiles.delete(rootOf(row.worktree), row.entry.path)
                    }
                    when (result) {
                        is FileOpResult.Failed -> {
                            prompt = PanelPrompt.Failure(result.reason)
                            break
                        }

                        is FileOpResult.Done -> {
                            expectChangeUntil = SystemClock.uptimeMillis() + EXPECT_CHANGE_MS
                            onEntryRemoved(projectPathOf(row.worktree, result.path))
                            if (pending?.holds(rowKey(row.worktree, result.path)) == true) {
                                pending = null
                            }
                        }
                    }
                }
                tree.clearMarks()
                tree.select(neighbour)
            }
        }

        /**
         * Zed's `project_panel::Trash`, which is what its Delete key does:
         * the entries move to the app's private trash and the panel offers an
         * Undo that puts them back.
         *
         * The engine is handed *project* paths — folder-qualified outside the
         * project's own folder — because that is what `project_entry_abs_path`
         * resolves, and the same relative path can exist in two folders.
         */
        fun trashNow(rows: List<ProjectTreeRow>) {
            val neighbour = neighbourAfter(rows)
            val byProjectPath = rows.associateBy { projectPathOf(it) }
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    project.trash(byProjectPath.keys.toList())
                }
                expectChangeUntil = SystemClock.uptimeMillis() + EXPECT_CHANGE_MS
                when (result) {
                    is TrashResult.Failed -> prompt = PanelPrompt.Failure(result.reason)
                    is TrashResult.Done -> {
                        for (entry in result.entries) {
                            onEntryRemoved(entry.path)
                            val row = byProjectPath[entry.path]
                            if (row != null && pending?.holds(row.key) == true) pending = null
                        }
                        undo = result.entries
                    }
                }
                tree.clearMarks()
                tree.select(neighbour)
            }
        }

        /** Put back what the last Trash took — Zed's `project_panel::Undo`. */
        fun undoTrash() {
            val entries = undo ?: return
            undo = null
            scope.launch {
                val failure = withContext(Dispatchers.IO) { project.restoreTrash(entries) }
                expectChangeUntil = SystemClock.uptimeMillis() + EXPECT_CHANGE_MS
                if (failure != null) {
                    prompt = PanelPrompt.Failure(failure)
                } else {
                    // The engine answers in project paths; the tree reveals by
                    // folder and relative path.
                    entries.lastOrNull()?.let { entry ->
                        reshape { tree.reveal(keyForProjectPath(entry.path)) }
                    }
                }
            }
        }

        fun copyPath(row: ProjectTreeRow, relative: Boolean) {
            val text = targetsFor(row).joinToString("\n") { target ->
                if (relative) {
                    projectPathOf(target)
                } else {
                    project.absolutePathOf(target.worktree, target.entry.path)
                        ?: projectPathOf(target)
                }
            }
            clipboard.setText(AnnotatedString(text))
        }

        fun expandAll() {
            scope.launch {
                val directories = withContext(Dispatchers.Default) { tree.expandableDirectories() }
                reshape { tree.expandAll(directories) }
            }
        }

        /** [path] is a project path — see `projectPathOf`. */
        fun reveal(path: String) {
            val key = keyForProjectPath(path)
            val worktree = key.substringBefore(':').toLongOrNull() ?: return
            reshape { tree.reveal(worktree, key.substringAfter(':')) }
        }

        fun openTerminalIn(row: ProjectTreeRow?) {
            val open = onOpenTerminal ?: return
            val worktree = worktreeFor(row)
            val dir = directoryFor(row)
            open(project.absolutePathOf(worktree, dir) ?: rootOf(worktree).path)
        }

        /**
         * Zed's `NewSearchInDirectory`: project search over one folder. The
         * glob is the same spelling Zed seeds its filter with — the directory
         * and everything under it, named the way project search names a path
         * so it reaches the right folder of the project.
         */
        fun searchIn(row: ProjectTreeRow?) {
            val search = onSearchInDirectory ?: return
            val dir = projectPathOf(worktreeFor(row), directoryFor(row))
            search(if (dir.isEmpty()) "**" else "$dir/**")
        }

        /** Zed's `RemoveWorktreeFromProject`, once confirmed. */
        fun removeFolder(folder: ProjectWorktree) {
            scope.launch {
                val failure = withContext(Dispatchers.IO) { project.removeWorktree(folder.id) }
                if (failure != null) {
                    prompt = PanelPrompt.Failure(failure)
                    return@launch
                }
                expectChangeUntil = SystemClock.uptimeMillis() + EXPECT_CHANGE_MS
                if (tree.selectedRow?.worktree == folder.id) tree.select(null)
                reshape { }
            }
        }

        fun menuFor(row: ProjectTreeRow?): List<PanelMenuEntry> = buildList {
            val entry = row?.entry
            val isFolderRow = row?.isRoot == true
            val marks = targetsFor(row)
            val many = marks.size > 1
            /** "3 items" once a multi-select is on, else the row's own noun. */
            fun subject(one: String): String = if (many) "${marks.size} items" else one
            add(PanelMenuEntry.Action("New File…", "Ctrl N") { createIn(row, isDir = false) })
            add(
                PanelMenuEntry.Action("New Folder…", "Ctrl Shift N") {
                    createIn(row, isDir = true)
                }
            )
            add(PanelMenuEntry.Separator)
            if (row != null && entry != null && !entry.isDir) {
                // From the menu, "Open" is the deliberate one: it makes a
                // permanent tab, never a preview.
                add(PanelMenuEntry.Action("Open", "Enter") { activate(row, preview = false) })
                // Where Zed puts "Open in Default App" (project_panel.rs:1161):
                // straight after the creates, before the terminal entry.
                add(PanelMenuEntry.Action("Open with…", "Ctrl Shift Enter") {
                    handOver(row, share = false)
                })
                add(PanelMenuEntry.Action("Share…") { handOver(row, share = true) })
            }
            // A folder has these two as well, so they sit outside the block
            // above; the separator that used to end it is the one the next
            // block opens with.
            if (onOpenTerminal != null) {
                add(PanelMenuEntry.Action("Open in Terminal") { openTerminalIn(row) })
            }
            if (onSearchInDirectory != null) {
                add(
                    PanelMenuEntry.Action("Search in Directory", "Ctrl Alt Shift F") {
                        searchIn(row)
                    }
                )
            }
            if (row != null && entry != null && !isFolderRow) {
                add(PanelMenuEntry.Separator)
                // Touch's way into a multi-select: a finger has no Ctrl and no
                // Shift, so the mode is the substitute for both.
                add(
                    PanelMenuEntry.Action(
                        if (tree.selectionMode) "Done selecting" else "Select…"
                    ) {
                        if (tree.selectionMode) tree.clearMarks()
                        else tree.beginSelectionMode(row.key)
                    }
                )
                add(PanelMenuEntry.Action("Cut ${subject(entry.name)}", "Ctrl X") {
                    pending = PanelClipboard(marks.map { PanelRef(it.worktree, it.entry.path) }, isCut = true)
                })
                add(PanelMenuEntry.Action("Copy ${subject(entry.name)}", "Ctrl C") {
                    pending = PanelClipboard(marks.map { PanelRef(it.worktree, it.entry.path) }, isCut = false)
                })
                if (!many) {
                    add(PanelMenuEntry.Action("Duplicate", "Ctrl D") { duplicate(row) })
                }
            }
            add(
                PanelMenuEntry.Action("Paste", "Ctrl V", enabled = pending != null) {
                    paste(row)
                }
            )
            if (row != null && entry != null && !isFolderRow) {
                add(PanelMenuEntry.Separator)
                add(PanelMenuEntry.Action("Copy Path", "Ctrl Alt C") {
                    copyPath(row, relative = false)
                })
                add(PanelMenuEntry.Action("Copy Relative Path", "Ctrl Alt Shift C") {
                    copyPath(row, relative = true)
                })
                add(PanelMenuEntry.Separator)
                if (!many) {
                    add(PanelMenuEntry.Action("Rename…", "F2") {
                        prompt = PanelPrompt.Rename(row)
                    })
                }
                // Zed's pair, and its own wording: Delete is the trash and
                // "Delete Permanently" is the one that cannot be undone
                // (default-linux.json:996-1000).
                add(
                    PanelMenuEntry.Action("Move ${subject(entry.name)} to Trash…", "Del") {
                        confirmDelete(marks, permanent = false)
                    }
                )
                add(
                    PanelMenuEntry.Action("Delete Permanently…", "Shift Del") {
                        confirmDelete(marks, permanent = true)
                    }
                )
                if (undo != null) {
                    add(PanelMenuEntry.Action("Undo Trash", "Ctrl Z") { undoTrash() })
                }
            }
            add(PanelMenuEntry.Separator)
            // Zed's `workspace::AddFolderToProject` and
            // `workspace::RemoveWorktreeFromProject`, which its project panel
            // puts on the worktree root's menu (project_panel.rs:1235-1247).
            if (onAddFolder != null) {
                add(PanelMenuEntry.Action("Add Folder to Project…") { onAddFolder() })
            }
            val folder = row?.let { folderOf(it.worktree) }
            if (folder != null && !folder.isPrimary) {
                add(
                    PanelMenuEntry.Action("Remove Folder from Project") {
                        prompt = PanelPrompt.RemoveFolder(folder)
                    }
                )
            }
            add(PanelMenuEntry.Separator)
            add(
                PanelMenuEntry.Action(
                    "Reveal Active File",
                    enabled = openedPath != null,
                ) { openedPath?.let(::reveal) }
            )
            add(PanelMenuEntry.Action("Expand All", "Ctrl →") { expandAll() })
            add(PanelMenuEntry.Action("Collapse All", "Ctrl ←") { reshape { tree.collapseAll() } })
        }

        /**
         * The panel's own keyboard. Matched in a preview pass on the panel, so
         * it only fires while focus is in here — the workspace table upstream
         * has already had its say, and the editor's chords live in the editor.
         */
        fun handleKey(event: KeyEvent): Boolean {
            if (event.type != KeyEventType.KeyDown) return false
            val row = tree.selectedRow
            val entry = row?.entry
            val marks = targetsFor(row)
            if (event.isCtrlPressed) {
                if (event.isAltPressed) {
                    return when (event.key) {
                        // `ctrl-alt-c` is CopyPath and `ctrl-alt-shift-c` is
                        // CopyRelativePath (default-linux.json:987-989).
                        Key.C -> {
                            copyPath(row ?: return false, relative = event.isShiftPressed)
                            true
                        }
                        // Zed's `ctrl-alt-shift-f` — NewSearchInDirectory
                        // (default-linux.json:1005).
                        Key.F -> {
                            if (!event.isShiftPressed || onSearchInDirectory == null) return false
                            searchIn(row)
                            true
                        }
                        // Zed's own binding for a new directory, kept next to
                        // the Ctrl Shift N every file manager uses.
                        Key.N -> {
                            createIn(row, isDir = true)
                            true
                        }
                        else -> false
                    }
                }
                return when (event.key) {
                    Key.N -> {
                        createIn(row, isDir = event.isShiftPressed)
                        true
                    }
                    // Zed's ctrl-shift-enter in the ProjectPanel context
                    // (default-linux.json:1002). The workspace's own binding
                    // stands aside while the panel has focus, so this is the
                    // handler that sees it here.
                    Key.Enter, Key.NumPadEnter -> {
                        if (!event.isShiftPressed) return false
                        val target = row ?: return false
                        if (target.entry.isDir) return false
                        handOver(target, share = false)
                        true
                    }
                    Key.X -> {
                        val cutting = marks.filter { !it.isRoot }
                        if (cutting.isEmpty()) return false
                        pending = PanelClipboard(
                            cutting.map { PanelRef(it.worktree, it.entry.path) },
                            isCut = true,
                        )
                        true
                    }
                    Key.C -> {
                        val copying = marks.filter { !it.isRoot }
                        if (copying.isEmpty()) return false
                        pending = PanelClipboard(
                            copying.map { PanelRef(it.worktree, it.entry.path) },
                            isCut = false,
                        )
                        true
                    }
                    Key.V -> {
                        paste(row)
                        true
                    }
                    Key.D -> {
                        duplicate(row?.takeIf { !it.isRoot } ?: return false)
                        true
                    }
                    // Zed's `project_panel::Undo` on `ctrl-z`
                    // (default-linux.json:990-991): the way back from a Trash.
                    Key.Z -> {
                        if (undo == null) return false
                        undoTrash()
                        true
                    }
                    // `ctrl-delete` and `ctrl-backspace` are Zed's other two
                    // spellings of the permanent Delete (default-linux.json:
                    // 999-1000).
                    Key.Delete, Key.Backspace -> {
                        if (marks.isEmpty()) return false
                        confirmDelete(marks, permanent = true)
                        true
                    }
                    Key.DirectionLeft -> {
                        reshape { tree.collapseAll() }
                        true
                    }
                    Key.DirectionRight -> {
                        expandAll()
                        true
                    }
                    else -> false
                }
            }
            return when (event.key) {
                Key.DirectionUp, Key.DirectionDown -> {
                    val before = tree.selected
                    tree.moveSelection(if (event.key == Key.DirectionUp) -1 else 1)
                    // Shift+arrow extends the marked range, which is Zed's
                    // `menu::SelectNext`/`SelectPrevious` inside the panel
                    // (default-linux.json:1006-1007) plus the shift its
                    // `select_next` checks for (project_panel.rs:1754-1756).
                    val now = tree.selected
                    when {
                        !event.isShiftPressed -> tree.markOnly(now)
                        now != null && before != null -> tree.markRange(now)
                    }
                    true
                }
                Key.DirectionLeft -> {
                    // Collapse, or step out to the directory this row is in —
                    // the same left-arrow every tree has.
                    when {
                        row == null || entry == null -> tree.moveSelection(-1)
                        row.isRoot && tree.isRootExpanded(row.worktree) ->
                            reshape { tree.toggle(row) }
                        row.isRoot -> return false
                        entry.isDir && tree.isExpanded(row.key) ->
                            reshape { tree.collapse(row.key) }
                        entry.path.contains('/') ->
                            tree.select(rowKey(row.worktree, ProjectFiles.parentOf(entry.path)))
                        tree.isMultiRoot -> tree.select(rowKey(row.worktree, ""))
                        else -> return false
                    }
                    true
                }
                Key.DirectionRight -> {
                    when {
                        row == null || entry == null -> tree.moveSelection(1)
                        row.isRoot && !tree.isRootExpanded(row.worktree) ->
                            reshape { tree.toggle(row) }
                        row.isRoot -> tree.moveSelection(1)
                        entry.isDir && !tree.isExpanded(row.key) -> reshape { tree.expand(row) }
                        entry.isDir -> tree.moveSelection(1)
                        else -> return false
                    }
                    true
                }
                Key.Enter, Key.NumPadEnter -> {
                    // Enter opens permanently; Space previews, which is Zed's
                    // split between `Open` and `OpenPermanent` reversed to
                    // match `enable_preview_from_project_panel`.
                    activate(row ?: return false, preview = false)
                    true
                }
                Key.Spacebar -> {
                    activate(row ?: return false, preview = true)
                    true
                }
                Key.F2 -> {
                    prompt = PanelPrompt.Rename(row?.takeIf { !it.isRoot } ?: return false)
                    true
                }
                // Zed's default is Trash, and Shift makes it permanent
                // (default-linux.json:996-998).
                Key.Delete, Key.Backspace -> {
                    if (marks.none { !it.isRoot }) return false
                    confirmDelete(marks, permanent = event.isShiftPressed)
                    true
                }
                Key.Escape -> {
                    if (tree.marked.size <= 1 && !tree.selectionMode) return false
                    tree.markOnly(tree.selected)
                    true
                }
                Key.MoveHome -> {
                    tree.selectEdge(last = false)
                    tree.markOnly(tree.selected)
                    true
                }
                Key.MoveEnd -> {
                    tree.selectEdge(last = true)
                    tree.markOnly(tree.selected)
                    true
                }
                // The keyboard's way to the context menu. Both spellings: the
                // menu key isn't on every keyboard, and Shift F10 is what the
                // ones without it use.
                Key.Menu, Key.F10 -> {
                    if (event.key == Key.F10 && !event.isShiftPressed) return false
                    // No pointer to place it under: it drops from the row's start.
                    menu = PanelMenu(row, Offset.Zero)
                    true
                }
                else -> false
            }
        }

        // Keyed on `tree`, not `project`: changing a setting that affects the
        // tree (showing gitignored files) builds a fresh, empty
        // ProjectTreeState, and an effect still holding the old one would
        // leave the panel permanently blank. The loop lives on Default — the
        // counters it compares are JNI reads — and comes back to the main
        // thread only to publish.
        ResumedEffect(tree) {
            withContext(Dispatchers.Default) {
                while (true) {
                    val version = project.version
                    val shape = tree.shape
                    if (version != tree.version) {
                        val rows = tree.rebuild()
                        withContext(Dispatchers.Main) { tree.publish(version, rows, shape) }
                    } else if (statusSource.version != tree.statusVersion) {
                        // Statuses normally land after the tree has been
                        // drawn. Re-colouring keeps the same rows and the
                        // same keys, so the list doesn't blink, scroll, or
                        // re-measure.
                        val rows = tree.restatus(tree.rows)
                        withContext(Dispatchers.Main) { tree.publish(version, rows, shape) }
                    }
                    val eager = !project.scanComplete ||
                        SystemClock.uptimeMillis() < expectChangeUntil
                    delay(if (eager) SCANNING_POLL_MS else IDLE_POLL_MS)
                }
            }
        }

        // A reveal can outlive the frame that asked for it: the row may be
        // inside a directory the worktree has yet to scan, and only exists
        // once the engine reports it.
        LaunchedEffect(tree.rows, tree.pendingReveal) {
            val target = tree.pendingReveal ?: return@LaunchedEffect
            val index = tree.rows.indexOfFirst { it.key == target }
            if (index < 0) return@LaunchedEffect
            // Land the row below the ancestors that will pin over the top —
            // Zed offsets every autoscroll by the sticky count
            // (project_panel.rs:3309-3317). A row's pinned ancestors are
            // exactly its depth, and rows are fixed-height, so scrolling that
            // many rows earlier puts it in the first uncovered slot.
            listState.scrollToItem((index - tree.rows[index].depth).coerceAtLeast(0))
            tree.revealed()
        }

        // Keyboard selection has to stay on screen. Clicking a row that is
        // already visible must not scroll anything, so this only moves the
        // list when the selected row isn't fully in the viewport.
        LaunchedEffect(tree.selected) {
            val key = tree.selected ?: return@LaunchedEffect
            if (tree.pendingReveal != null) return@LaunchedEffect
            val pinned = stickyStack.value
            // A row that is *in* the pinned stack is on screen — pinned at the
            // top, selection colour and all. Its real row is by definition
            // scrolled off or covered, so measuring that one would say "not
            // visible" and animate the list back to it: right-clicking a
            // pinned directory would throw away the scroll position it was
            // pinned to preserve.
            if (pinned != null &&
                pinned.indices.any { tree.rows.getOrNull(it)?.key == key }
            ) {
                return@LaunchedEffect
            }
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo.firstOrNull { it.key == key }
            // A row under the pinned ancestor stack is covered, not visible,
            // so the top of the usable viewport starts below it — the same
            // allowance Zed's autoscroll makes with its sticky count
            // (project_panel.rs:3309-3317).
            val stackPx = pinned?.let { it.indices.size * it.rowHeightPx } ?: 0
            if (visible != null &&
                visible.offset >= info.viewportStartOffset + stackPx &&
                visible.offset + visible.size <= info.viewportEndOffset
            ) {
                return@LaunchedEffect
            }
            val index = tree.rows.indexOfFirst { it.key == key }
            if (index >= 0) {
                // As in the reveal above: the row's own ancestors will pin, so
                // aim `depth` rows earlier and it lands just under them.
                listState.revealItem((index - tree.rows[index].depth).coerceAtLeast(0))
            }
        }

        // Zed's `project_panel.auto_reveal_entries`, which is on by default:
        // the tree follows the file being edited. It never takes the keyboard
        // — only an explicit request does that.
        // Also keyed on the folder list: a project path names a folder, and
        // until the first flatten has told us what the folders are there is
        // nothing to resolve it against.
        LaunchedEffect(openedPath, tree.worktrees) {
            openedPath?.let { reveal(it) }
        }

        LaunchedEffect(revealRequest) {
            if (!revealRequest) return@LaunchedEffect
            onRevealHandled()
            panelFocus.requestFocus()
            openedPath?.let { reveal(it) }
        }

        // Zed's `hide_root`: with one worktree open the project's own name is
        // noise, and its row is where the tree starts instead. The header's
        // menu is the project's, so hiding it moves that menu to the panel's
        // empty space, which is what Zed does too.
        if (!panel.hideRoot) ProjectRootRow(
            name = project.rootName,
            spacing = panel.entrySpacing,
            rowColours = rowColours,
            iconColour = iconColour,
            isDropTarget = drag?.over == "",
            onClick = { reshape { tree.collapseAll() } },
            onContextMenu = { at -> menu = PanelMenu(null, at) },
            // Zed's `workspace::AddFolderToProject`, as a button: the palette
            // and this are the two ways in, and one of them has to work with
            // no keyboard attached.
            onAddFolder = onAddFolder,
            menu = {
                // Also where the keyboard's menu key lands when nothing in
                // the tree is selected: a menu for the project root.
                val open = menu
                if (open != null && open.row == null) {
                    ProjectContextMenu(
                        entries = menuFor(null),
                        offset = with(density) { DpOffset(open.at.x.toDp(), open.at.y.toDp()) },
                        onDismiss = { menu = null },
                    )
                }
            },
        )

        // The way back from a Trash, for a finger — the chord is Ctrl+Z.
        undo?.let { entries ->
            TrashUndoBar(
                entries = entries,
                onUndo = { undoTrash() },
                onDismiss = { undo = null },
            )
        }

        // Which row the open tab is, if any: the workspace hands us a project
        // path, which in a project with several folders carries the folder's
        // name in front of it.
        val openedKey = openedPath?.let { keyForProjectPath(it) }

        val error = project.error
        Column(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(panelFocus)
                .onFocusChanged { onFocusChanged(it.hasFocus) }
                .onPreviewKeyEvent(::handleKey)
                .focusable()
        ) {
            when {
                error != null -> PanelMessage(error)
                tree.rows.isEmpty() && !project.scanComplete -> PanelMessage("Scanning…")
                tree.rows.isEmpty() -> PanelMessage("Empty project")
                else -> {
                    val rows = tree.rows
                    // With one folder the panel's header *is* the root row, so
                    // top-level entries are drawn one level in
                    // (project_panel.rs:5547). With several, each folder's own
                    // header is a row in the list at that level and its
                    // entries are already a level deeper, so nothing is added.
                    // With `hide_root` there is no row above for the tree to
                    // sit in from, so it starts flush.
                    val rootIndent = if (panel.hideRoot) 0 else 1
                    val renderedDepth =
                        { depth: Int -> if (tree.isMultiRoot) depth else depth + rootIndent }
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                bottom = rem(PanelMetrics.LIST_BOTTOM_PADDING),
                            ),
                        ) {
                            itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                                val active = activeGuide
                                ProjectRow(
                                    entry = row.entry,
                                    label = row.label,
                                    depth = renderedDepth(row.depth),
                                    indentSize = panel.indentSize.dp,
                                    spacing = panel.entrySpacing,
                                    status = row.status,
                                    diagnostic = diagnostics.severityOf(row.entry.path),
                                    colours = colours,
                                    iconColour = iconColour,
                                    rowColours = rowColours,
                                    isExpanded = if (row.isRoot) {
                                        tree.isRootExpanded(row.worktree)
                                    } else {
                                        tree.isExpanded(row.key)
                                    },
                                    isOpen = openedKey == row.key,
                                    isSelected = row.key == tree.selected ||
                                        tree.isMarked(row.key),
                                    isMarked = tree.isMarked(row.key),
                                    showCheckbox = tree.selectionMode,
                                    isDropTarget = drag != null &&
                                        drag?.worktree == row.worktree &&
                                        drag?.over == dropTargetOf(row),
                                    isCut = pending?.isCut == true &&
                                        pending?.holds(row.key) == true,
                                    dimIgnored = dimIgnored,
                                    // Neighbour depths, for the 4px guide-run end
                                    // insets: a run's slice is inset only where the
                                    // next row over no longer draws that level. The
                                    // root row above the list and the space below
                                    // it draw nothing, hence 0 at both edges.
                                    prevRenderedDepth = if (index == 0) {
                                        0
                                    } else {
                                        renderedDepth(rows[index - 1].depth)
                                    },
                                    nextRenderedDepth = if (index == rows.lastIndex) {
                                        0
                                    } else {
                                        renderedDepth(rows[index + 1].depth)
                                    },
                                    activeGuideLevel = if (
                                        active != null && index >= active.first &&
                                        index <= active.last
                                    ) {
                                        if (tree.isMultiRoot) active.level - 1 else active.level
                                    } else {
                                        -1
                                    },
                                    onClick = { modifiers ->
                                        panelFocus.requestFocus()
                                        when {
                                            // Ctrl-click toggles, shift-click
                                            // ranges — Zed's `marked_entries`
                                            // (project_panel.rs:1754-1760). In
                                            // touch selection mode every tap is
                                            // a toggle, because a finger has
                                            // neither modifier.
                                            tree.selectionMode || modifiers.ctrl -> {
                                                tree.select(row.key)
                                                tree.toggleMark(row.key)
                                            }

                                            modifiers.shift -> {
                                                tree.select(row.key)
                                                tree.markRange(row.key)
                                            }

                                            else -> activate(row)
                                        }
                                    },
                                    onContextMenu = { at ->
                                        panelFocus.requestFocus()
                                        tree.select(row.key)
                                        if (!tree.isMarked(row.key)) tree.markOnly(row.key)
                                        menu = PanelMenu(row, at)
                                    },
                                    // Long-press-drag moves entries between
                                    // folders. The row's own y is turned into a
                                    // list coordinate here, where the layout is
                                    // known; rows are a fixed height, so the row
                                    // under the finger is a division.
                                    onDragBy = { offsetY ->
                                        val item = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.index == index }
                                        if (item != null && rowHeightPx > 0) {
                                            val y = item.offset + offsetY
                                            val over = listState.layoutInfo.visibleItemsInfo
                                                .firstOrNull {
                                                    y >= it.offset && y < it.offset + it.size
                                                }
                                                ?.let { rows.getOrNull(it.index) }
                                            drag = PanelDrag(
                                                rows = targetsFor(row),
                                                // Nothing under the finger is
                                                // this row's own folder root.
                                                worktree = over?.worktree ?: row.worktree,
                                                over = over?.let(::dropTargetOf) ?: "",
                                            )
                                        }
                                    },
                                    onDragEnd = {
                                        val moving = drag
                                        drag = null
                                        if (moving != null && moving.over != null) {
                                            drop(moving.rows, moving.worktree, moving.over)
                                        }
                                    },
                                    onDragCancel = { drag = null },
                                    menu = {
                                        val open = menu
                                        if (open != null && !open.sticky &&
                                            open.row?.key == row.key
                                        ) {
                                            ProjectContextMenu(
                                                entries = menuFor(row),
                                                offset = with(density) {
                                                    DpOffset(open.at.x.toDp(), open.at.y.toDp())
                                                },
                                                onDismiss = { menu = null },
                                            )
                                        }
                                    },
                                )
                            }
                        }

                        StickyOverlay(
                            stack = stickyStack,
                            rows = rows,
                            tree = tree,
                            listState = listState,
                            colours = colours,
                            iconColour = iconColour,
                            rowColours = rowColours,
                            dimIgnored = dimIgnored,
                            spacing = panel.entrySpacing,
                            indentSize = panel.indentSize.dp,
                            hideRoot = panel.hideRoot,
                            diagnostics = diagnostics,
                            isCut = { key ->
                                pending?.isCut == true && pending?.holds(key) == true
                            },
                            onClick = { row, index ->
                                panelFocus.requestFocus()
                                // Zed scrolls the clicked directory to its own
                                // sticky slot, so its ancestors stay pinned
                                // above it (project_panel.rs:6087-6101); with
                                // fixed-height rows that slot is `depth` rows
                                // down. Selection follows once the scroll
                                // lands, so the autoscroll effect finds it
                                // already placed and stays put.
                                scope.launch {
                                    listState.scrollToItem(
                                        (index - row.depth).coerceAtLeast(0)
                                    )
                                    tree.select(row.key)
                                }
                            },
                            onContextMenu = { pinnedRow, at ->
                                panelFocus.requestFocus()
                                // Safe to move the selection without moving the
                                // list: the autoscroll effect above leaves a
                                // row that is pinned in the stack where it is.
                                tree.select(pinnedRow.key)
                                menu = PanelMenu(pinnedRow, at, sticky = true)
                            },
                            rowMenu = { pinnedRow ->
                                val open = menu
                                if (open != null && open.sticky &&
                                    open.row?.key == pinnedRow.key
                                ) {
                                    ProjectContextMenu(
                                        entries = menuFor(pinnedRow),
                                        offset = with(density) {
                                            DpOffset(open.at.x.toDp(), open.at.y.toDp())
                                        },
                                        onDismiss = { menu = null },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        when (val current = prompt) {
            null -> Unit

            is PanelPrompt.NewEntry -> EntryNameDialog(
                title = if (current.isDir) "NEW FOLDER" else "NEW FILE",
                confirmLabel = "Create",
                initial = "",
                selectionEnd = 0,
                placeholder = if (current.parent.isEmpty()) {
                    "Name, or a path like src/main.rs"
                } else {
                    "Name, inside ${current.parent}"
                },
                errorFor = { name ->
                    ProjectFiles.pathError(
                        name,
                        ProjectFiles.resolve(rootOf(current.worktree), current.parent),
                    )
                },
                onConfirm = { name ->
                    prompt = null
                    operate(
                        onDone = { path ->
                            reshape { tree.reveal(current.worktree, path) }
                            if (!current.isDir) {
                                // A file you just asked for by name is not a
                                // file you are browsing: it opens permanently.
                                onOpenFile(
                                    newFileEntry(projectPathOf(current.worktree, path)),
                                    false,
                                )
                            }
                        }
                    ) {
                        ProjectFiles.create(
                            rootOf(current.worktree),
                            current.parent,
                            name,
                            current.isDir,
                        )
                    }
                },
                onDismiss = { prompt = null },
            )

            is PanelPrompt.Rename -> EntryNameDialog(
                title = "RENAME",
                confirmLabel = "Rename",
                initial = current.row.entry.name,
                selectionEnd = stemLength(current.row.entry.name, current.row.entry.isDir),
                placeholder = "Name",
                errorFor = { name ->
                    if (name.trim() == current.row.entry.name) {
                        null
                    } else {
                        ProjectFiles.nameError(
                            name,
                            ProjectFiles.resolve(
                                rootOf(current.row.worktree),
                                ProjectFiles.parentOf(current.row.entry.path),
                            ),
                        )
                    }
                },
                onConfirm = { name ->
                    prompt = null
                    val worktree = current.row.worktree
                    val from = current.row.entry.path
                    operate(
                        onDone = { path ->
                            if (path != from) {
                                onEntryMoved(
                                    projectPathOf(worktree, from),
                                    projectPathOf(worktree, path),
                                )
                            }
                            reshape { tree.reveal(worktree, path) }
                        }
                    ) {
                        ProjectFiles.rename(rootOf(worktree), from, name)
                    }
                },
                onDismiss = { prompt = null },
            )

            is PanelPrompt.Delete -> ConfirmDeleteDialog(
                paths = current.rows.map { projectPathOf(it) },
                permanent = current.permanent,
                onConfirm = {
                    prompt = null
                    if (current.permanent) deleteNow(current.rows) else trashNow(current.rows)
                },
                onDismiss = { prompt = null },
            )

            // Zed's `RemoveWorktreeFromProject` asks nothing, but here the
            // folder was *imported* into app storage to be opened at all, so
            // saying what does and does not happen to the copy is the whole
            // point of the dialog.
            is PanelPrompt.RemoveFolder -> ConfirmRemoveFolderDialog(
                folder = current.worktree,
                onConfirm = {
                    prompt = null
                    removeFolder(current.worktree)
                },
                onDismiss = { prompt = null },
            )

            is PanelPrompt.Failure -> PanelErrorDialog(
                message = current.message,
                onDismiss = { prompt = null },
            )
        }

        overwrite?.let { pendingDrop ->
            ConfirmOverwriteDialog(
                names = pendingDrop.clashes,
                destination = pendingDrop.destination,
                onConfirm = {
                    overwrite = null
                    transfer(
                        pendingDrop.refs,
                        pendingDrop.worktree,
                        pendingDrop.destination,
                        isCut = true,
                        overwrite = true,
                    )
                },
                onDismiss = { overwrite = null },
            )
        }
    }
}

/**
 * The Undo strip a Trash leaves behind.
 *
 * Zed's `project_panel::Trash` is undoable through `project_panel::Undo`, a
 * chord; a phone has no chords, so the offer is on screen for as long as the
 * panel keeps it — until the next trash, or until it is taken. It sits at the
 * panel's foot rather than as a toast, because a toast that carries the only
 * way back from a delete is a toast you can miss.
 */
@Composable
private fun TrashUndoBar(
    entries: List<TrashedEntry>,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(rem(0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            // One rung up from whatever the panel was given, which is what
            // `status_bar.background` was doing relative to `panel.background`
            // — a foot strip that is visibly not another row of the tree.
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = rem(0.5f), vertical = rem(0.375f)),
    ) {
        Text(
            text = if (entries.size == 1) {
                "${entries.first().name} moved to the trash"
            } else {
                "${entries.size} items moved to the trash"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "Undo",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onUndo)
                .padding(horizontal = rem(0.25f)),
        )
        Text(
            text = "✕",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onDismiss)
                .padding(horizontal = rem(0.25f)),
        )
    }
}

/**
 * The pinned ancestors, over the list's top.
 *
 * Each row is the ordinary row composable on the overlay colours; the last one
 * alone drifts and carries the shadow, and the rest are painted over it so a
 * push-off slides *under* the stack — Zed paints the drifting element first for
 * the same reason (sticky_items.rs:110-132).
 *
 * A composable of its own, and the only place [stack] is read in composition:
 * the drift moves with the scroll, so the value is different every frame of a
 * push-off, and reading it in the panel's scope would rebuild the `LazyColumn`
 * content lambda — and with it every visible row — once per frame on the main
 * thread. Here that invalidation costs the two or three rows of the stack.
 */
@Composable
private fun StickyOverlay(
    stack: State<StickyStack?>,
    rows: List<ProjectTreeRow>,
    tree: ProjectTreeState,
    listState: LazyListState,
    colours: GitStatusColours,
    iconColour: Color,
    rowColours: RowColours,
    dimIgnored: Boolean,
    spacing: EntrySpacing,
    indentSize: Dp,
    hideRoot: Boolean,
    diagnostics: DiagnosticMarks,
    /** Whether a row, by [ProjectTreeRow.key], is one waiting to be cut. */
    isCut: (String) -> Boolean,
    /** A pinned row and where it sits in [rows]. */
    onClick: (ProjectTreeRow, Int) -> Unit,
    onContextMenu: (ProjectTreeRow, Offset) -> Unit,
    rowMenu: @Composable (ProjectTreeRow) -> Unit,
) {
    val pinned = stack.value ?: return

    @Composable
    fun PinnedRow(stackIndex: Int) {
        val index = pinned.indices[stackIndex]
        val row = rows.getOrNull(index) ?: return
        key(row.key) {
            // Zed's `block_mouse_except_scroll()`, on the sticky row itself
            // (project_panel.rs:5798). A pinned row is the later sibling, so
            // Compose hit-tests it first and stops there — hover and clicks
            // stay on the pinned copy rather than reaching the row beneath,
            // which is what we want, but it also means the list's own gesture
            // never sees a wheel or a drag that starts on the stack. This
            // hands those deltas to the list directly: one `scrollable` above
            // the row, so a tap still lands on the row and only movement past
            // touch slop becomes a scroll. It wraps the row alone, not the
            // shadow below it, because Zed's shadow hangs outside its row's
            // hitbox and blocks nothing.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .scrollable(
                        state = listState,
                        orientation = Orientation.Vertical,
                        // What a LazyColumn passes for itself when it isn't
                        // reversed (ScrollableDefaults.reverseDirection): a
                        // finger moves with the content, not the viewport.
                        reverseDirection = true,
                    )
            ) {
                ProjectRow(
                    entry = row.entry,
                    label = row.label,
                    depth = row.depth + if (hideRoot) 0 else 1,
                    indentSize = indentSize,
                    spacing = spacing,
                    status = row.status,
                    diagnostic = diagnostics.severityOf(row.entry.path),
                    colours = colours,
                    iconColour = iconColour,
                    rowColours = rowColours,
                    // Pinned rows are ancestors of a visible row: expanded by
                    // definition.
                    isExpanded = true,
                    isOpen = false,
                    isSelected = row.key == tree.selected || tree.isMarked(row.key),
                    isMarked = tree.isMarked(row.key),
                    showCheckbox = tree.selectionMode,
                    isDropTarget = false,
                    isCut = isCut(row.key),
                    dimIgnored = dimIgnored,
                    isSticky = true,
                    onClick = { onClick(row, index) },
                    onContextMenu = { at -> onContextMenu(row, at) },
                    menu = { rowMenu(row) },
                )
            }
        }
    }

    val lastPos = pinned.indices.lastIndex
    Box(modifier = Modifier.fillMaxWidth()) {
        // The deepest pinned row, at its slot plus the push-off, with the
        // shadow hanging below it.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, pinned.rowHeightPx * lastPos + pinned.driftPx) }
        ) {
            PinnedRow(lastPos)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(StickyShadowHeight)
                    .background(
                        Brush.verticalGradient(
                            // hsla(0,0,0,0.1) → clear
                            // (project_panel.rs:6894-6895).
                            listOf(
                                Color.Black.copy(alpha = 0.10f),
                                Color.Black.copy(alpha = 0f),
                            )
                        )
                    )
            )
        }
        // The rest of the stack, painted over it.
        Column(modifier = Modifier.fillMaxWidth()) {
            for (position in 0 until lastPos) PinnedRow(position)
        }
    }
}

/**
 * The worktree root — an ordinary row, not a header.
 *
 * Zed has no panel title: the project's own name is the first row of the tree
 * and everything else is indented under it (project_panel.rs:6138). Ours is
 * held out of the scrolling list because a phone's panel is short and the row
 * that says which project you are in is the one worth never losing.
 *
 * The root cannot be hidden the way a folder can, so its chevron stays open
 * and a tap means the only thing collapsing a root can mean here: shut
 * everything underneath. Its menu is the project's, reached the same three
 * ways every other row's is — right-click, long-press, or the menu key.
 */
@Composable
private fun ProjectRootRow(
    name: String,
    spacing: EntrySpacing,
    rowColours: RowColours,
    iconColour: Color,
    /** A drag is hovering the root: the drop would move into the project. */
    isDropTarget: Boolean,
    onClick: () -> Unit,
    onContextMenu: (Offset) -> Unit,
    /** Zed's `workspace::AddFolderToProject`; absent leaves the row plain. */
    onAddFolder: (() -> Unit)?,
    menu: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = remember { mutableStateOf(Offset.Zero) }
    val contextGesture = rememberPointerContextMenu(
        onPress = { at, _ -> pressed.value = at },
        onContext = onContextMenu,
    )
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RowGap),
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight(spacing))
                // Only the drop highlight is a fill; hover and press come
                // back from the indication below.
                .background(
                    if (isDropTarget) rowColours.dropTarget else Color.Transparent
                )
                .pointerHoverIcon(PointerIcon.Hand)
                .then(contextGesture)
                .focusProperties { canFocus = false }
                .combinedClickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClick = onClick,
                    onLongClick = { onContextMenu(pressed.value) },
                )
                .padding(horizontal = RowPadding),
        ) {
            Box(
                modifier = Modifier.width(EntryIconWidth),
                contentAlignment = Alignment.CenterStart,
            ) {
                EntryIconMark(
                    name = name,
                    isDir = true,
                    isExpanded = true,
                    color = iconColour,
                )
            }
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                // Muted like every other plain entry (items.rs:2177-2183);
                // the root is an ordinary row in Zed, not a header.
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onAddFolder != null) {
                Box(modifier = Modifier.weight(1f))
                Text(
                    text = "+",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .semantics { contentDescription = "Add Folder to Project" }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClickLabel = "Add Folder to Project", onClick = onAddFolder)
                        .padding(horizontal = RowGap),
                )
            }
        }
        menu()
    }
}

/**
 * The mark in front of a row while touch selection mode is on.
 *
 * Drawn rather than a Material `Checkbox`, for the reason every other mark in
 * this panel is drawn: a Checkbox brings a 48dp touch target, a ripple and a
 * Material colour ramp, and the row is 26dp tall and has its own.
 */
@Composable
private fun SelectionCheckbox(isMarked: Boolean, color: Color) {
    val stroke = PanelPixels.GuideWidth
    Box(
        modifier = Modifier
            .size(rem(PanelMetrics.ROW_CONTENT) * 0.6f)
            .drawBehind {
                val width = stroke.toPx()
                drawRect(
                    color = color,
                    topLeft = Offset(width / 2f, width / 2f),
                    size = Size(size.width - width, size.height - width),
                    style = Stroke(width = width),
                )
                if (!isMarked) return@drawBehind
                // A tick, two strokes: down to the low point, then up.
                val low = Offset(size.width * 0.45f, size.height * 0.72f)
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.24f, size.height * 0.5f),
                    end = low,
                    strokeWidth = width * 1.5f,
                )
                drawLine(
                    color = color,
                    start = low,
                    end = Offset(size.width * 0.76f, size.height * 0.28f),
                    strokeWidth = width * 1.5f,
                )
            },
    )
}

/** The backgrounds a row can have, resolved once per theme. */
private class RowColours(
    /**
     * A marked row's fill — `element_selected` (project_panel.rs:611-629),
     * which is what the bridge derives `secondaryContainer` from.
     *
     * Hover and press are gone from this table on purpose. They are STATES,
     * and under M3 a state is a layer the indication draws, not a colour a row
     * paints; keeping them here as fills is what made a phone's file tree
     * silent under a finger.
     */
    val selected: Color,
    /** The 1px border marking the open file (project_panel.rs:5729-5743). */
    val activeBorder: Color,
    val indentGuide: Color,
    /** The guide run the selection hangs from (project_panel.rs:7218-7222). */
    val indentGuideActive: Color,
    /**
     * A pinned row's resting colour — the `is_sticky` branch of
     * `get_item_color` (project_panel.rs:611-629), one rung up the Material
     * ladder so the stack reads as raised over the list it is covering.
     * Marked and focused stay the shared colours; the hover twin went with the
     * state layer.
     */
    val stickyBackground: Color,
    /**
     * What a marked row's plain name turns (items.rs:2177-2183) — the ink
     * solved for [selected], since that is the fill it lands on.
     */
    val selectedText: Color,
    /**
     * The folder a drag is hovering — Zed's `drop_target.background`
     * (project_panel.rs, `drag_target`), and `primaryContainer` here because
     * it has to out-read [selected] while a finger is on top of it, and the
     * primary and secondary containers are the only pair on this scheme
     * guaranteed to differ in both appearances.
     */
    val dropTarget: Color,
    /** `error` and `warning`, for `show_diagnostics`. */
    val diagnosticError: Color,
    val diagnosticWarning: Color,
)

@Composable
private fun ProjectRow(
    entry: ProjectEntry,
    /** The name to draw — `a/b/c` on a folded chain. */
    label: String,
    depth: Int,
    /** Zed's `project_panel.indent_size`, in dp. */
    indentSize: Dp,
    spacing: EntrySpacing,
    status: GitFileStatus,
    /** What `show_diagnostics` says about this row, or null. */
    diagnostic: DiagnosticMark?,
    colours: GitStatusColours,
    iconColour: Color,
    rowColours: RowColours,
    isExpanded: Boolean,
    isOpen: Boolean,
    isSelected: Boolean,
    /** In the marked set — one of several the next command applies to. */
    isMarked: Boolean,
    /** Touch selection mode: every row carries a checkbox. */
    showCheckbox: Boolean,
    /** A drag is hovering this folder, or the folder this file is in. */
    isDropTarget: Boolean,
    /** Cut and waiting to be pasted: shown faded, as every file manager does. */
    isCut: Boolean,
    dimIgnored: Boolean,
    onClick: (RowModifiers) -> Unit,
    onContextMenu: (Offset) -> Unit,
    menu: @Composable () -> Unit,
    /** The pointer's y inside this row, while a move drag is running. */
    onDragBy: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
    /**
     * The rendered depths of the rows above and below, deciding where each
     * guide run really ends so its 4px end insets go there and nowhere else
     * (PADDING_Y, project_panel.rs:7214). The defaults draw full-height
     * slices — what a pinned sticky row wants, since Zed's sticky guide
     * decoration has no insets (project_panel.rs:7280-7311).
     */
    prevRenderedDepth: Int = Int.MAX_VALUE,
    nextRenderedDepth: Int = Int.MAX_VALUE,
    /** Guide level painted `panel.indent_guide_active`, or -1 for none. */
    activeGuideLevel: Int = -1,
    /**
     * A pinned copy of the row in the sticky stack: overlay colours instead
     * of transparent-on-panel (`get_item_color`'s `is_sticky` branch,
     * project_panel.rs:611-629); everything else renders identically.
     */
    isSticky: Boolean = false,
) {
    // Zed tints the *name* by git status and greys gitignored entries rather
    // than hiding them; "show" opts out of even that, for people who don't
    // want their tree to editorialise. A real change wins over ignored-ness
    // (`entry_git_aware_label_color` checks conflict/deleted/modified/created
    // before ignored — editor/src/items.rs:2205-2219), and a plain entry is
    // `text.muted`, turning `text` only when its row is marked
    // (`entry_label_color`, items.rs:2177-2183).
    //
    // Colours were resolved once for the whole panel, so this is a `when` over
    // an enum — no theme lookup and no allocation per row, per frame.
    val tinted = colours.colorFor(status, entry.isIgnored, dimIgnored)
    val color = when {
        // `show_diagnostics` outranks git status on the *name*, as Zed's
        // `entry_diagnostic_aware_label_color` does: a file that will not
        // compile is worth more than the fact that it changed.
        diagnostic == DiagnosticMark.Error -> rowColours.diagnosticError
        diagnostic == DiagnosticMark.Warning -> rowColours.diagnosticWarning
        isSelected && status == GitFileStatus.None && !(entry.isIgnored && dimIgnored) ->
            rowColours.selectedText

        else -> tinted
    }
    val interaction = remember { MutableInteractionSource() }
    // What is left of Zed's precedence once hover and press have gone to the
    // state layer: three fills, all of which say something about the row
    // rather than about the finger. Zed's own rule survives in the order —
    // a marked row stays marked under the pointer (bg_hover_color keeps
    // `marked`, project_panel.rs:5708-5711) — and it now survives for free,
    // because the ripple composites OVER whichever of these is showing
    // instead of replacing it.
    val background = when {
        // The drop highlight wins over everything: it is the answer to "will
        // this land here?", and it has to be legible while the finger is on
        // top of the row.
        isDropTarget -> rowColours.dropTarget
        isSelected -> rowColours.selected
        // A pinned row rests one rung up so the sticky stack reads as raised
        // over the list scrolling under it (`is_sticky`, project_panel.rs:611-629).
        isSticky -> rowColours.stickyBackground
        else -> Color.Transparent
    }
    // A long press has no coordinates of its own, so the last press is
    // remembered: the menu should open under the finger, not at the row's edge.
    val pressed = remember { mutableStateOf(Offset.Zero) }
    val heldModifiers = remember { mutableStateOf(RowModifiers()) }
    /**
     * A long press has fired on this row, so the click that follows it is not
     * a click — `clickable`'s tap detector has no timeout and reports one
     * anyway, which would open the file *and* the menu. Cleared on the next
     * press, which is the only moment unambiguously before both.
     */
    val longPressed = remember { mutableStateOf(false) }
    val contextGesture = rememberPointerContextMenu(
        onPress = { at, modifiers ->
            pressed.value = at
            heldModifiers.value = modifiers
            longPressed.value = false
        },
        onContext = onContextMenu,
    )
    // Read out here, not inside `drawBehind`: these are composition values now
    // (the rem-scaled ones read the UI font size), and a draw lambda is not a
    // composable scope. The px-valued ones come along for the ride so the draw
    // block reads as one table.
    val guideWidth = PanelPixels.GuideWidth
    val guideStep = PanelPixels.IndentPerLevel
    val guideLeftOffset = PanelPixels.GuideOffset
    val guideEndInset = PanelPixels.GuideEndInset
    val activeRail = PanelPixels.ActiveRowRail
    val rowIndent = indentSize * depth
    val rowPadding = RowPadding
    // The menu is a child of this box rather than of the row's content, so the
    // popup is placed against the whole row and the offset it is given is the
    // press position unchanged.
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RowGap),
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight(spacing))
                // Everything a sighted reader gets from this row that is not
                // the name — the folder chevron, the git tint, the diagnostic
                // dot, the open-file border, the checkbox — is a *colour* or a
                // *glyph*, and none of it survives being read aloud. One
                // merged description says all of it in words.
                .semantics(mergeDescendants = true) {
                    contentDescription = projectRowDescription(
                        name = label,
                        isDir = entry.isDir,
                        isExpanded = isExpanded,
                        status = status,
                        isOpen = isOpen,
                        isMarked = isMarked && showCheckbox,
                        diagnostic = diagnostic,
                        depth = depth,
                    )
                }
                .background(background)
                .drawBehind {
                    // Zed's indent guides: 1px at every level this row is
                    // nested under, at `level × indent_size + 15` — the offset
                    // lines them up with the icon column (project_panel.rs:
                    // 7212-7260). Drawn per row, they join into the same
                    // continuous runs the uniform-list decoration computes,
                    // because a guide at level ℓ spans exactly the contiguous
                    // rows deeper than ℓ. A run's true ends pull in 4px
                    // (PADDING_Y, project_panel.rs:7215), which is
                    // [guideRunEndsHere] — and where that rule parts company
                    // with Zed's viewport-relative one, and why, is written out
                    // there.
                    // The run under the selection is `panel.indent_guide_active`
                    // (find_active_indent_guide, project_panel.rs:6724-6790).
                    val guide = guideWidth.toPx()
                    val step = indentSize.toPx()
                    val guideOffset = guideLeftOffset.toPx()
                    val endInset = guideEndInset.toPx()
                    for (level in 0 until depth) {
                        val topInset =
                            if (guideRunEndsHere(level, prevRenderedDepth)) endInset else 0f
                        val bottomInset =
                            if (guideRunEndsHere(level, nextRenderedDepth)) endInset else 0f
                        drawRect(
                            color = if (level == activeGuideLevel) {
                                rowColours.indentGuideActive
                            } else {
                                rowColours.indentGuide
                            },
                            topLeft = Offset(level * step + guideOffset, topInset),
                            size = Size(guide, size.height - topInset - bottomInset),
                        )
                    }
                    // The open file wears a 1px border with a 2px rail on the
                    // right edge, not a fill — `border_1().border_r_2()` in
                    // `panel.focused_border` (project_panel.rs:5729-5797).
                    // Ours shows regardless of panel focus: on a touch screen
                    // the panel is unfocused almost always, and the open file
                    // is worth finding.
                    if (isOpen && !isSelected) {
                        drawRect(
                            color = rowColours.activeBorder,
                            topLeft = Offset(guide / 2f, guide / 2f),
                            size = Size(size.width - guide, size.height - guide),
                            style = Stroke(width = guide),
                        )
                        drawRect(
                            color = rowColours.activeBorder,
                            topLeft = Offset(size.width - activeRail.toPx(), 0f),
                            size = Size(activeRail.toPx(), size.height),
                        )
                    }
                }
                .pointerHoverIcon(PointerIcon.Hand)
                .then(contextGesture)
                // The panel is the single focus target; rows would otherwise
                // take it in turn and fight the arrows for the selection.
                .focusProperties { canFocus = false }
                // A long press does double duty here, exactly as it does on a
                // tab: hold and move to drag the entry into another folder,
                // hold and let go to open the context menu. Two gesture
                // detectors would both fire, so the drag detector owns the
                // press and decides at the end which it was.
                .pointerInput(entry.path) {
                    var moved = false
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            moved = false
                            longPressed.value = true
                        },
                        onDragEnd = {
                            if (moved) onDragEnd() else onContextMenu(pressed.value)
                        },
                        onDragCancel = { if (moved) onDragCancel() },
                        onDrag = { change, _ ->
                            change.consume()
                            moved = true
                            onDragBy(change.position.y)
                        },
                    )
                }
                .clickable(
                    interactionSource = interaction,
                    // Zed swaps a row's colour instantly and has no ripple at
                    // all — which is right inside `ZedSurface` and wrong here,
                    // because this tree's only host is the Files sheet. A 48dp
                    // row that does not answer a press is the clearest "not a
                    // real Android app" tell there is (docs/VISUAL.md,
                    // "Projects (sheet)").
                    indication = LocalIndication.current,
                ) {
                    // The tail of a long press, not a click: the menu it
                    // opened is up, and opening the file behind it as well
                    // would be two answers to one gesture.
                    if (!longPressed.value) onClick(heldModifiers.value)
                }
                .padding(start = rowPadding + rowIndent, end = rowPadding),
        ) {
            // In touch selection mode every row carries a checkbox, because a
            // finger has no Ctrl to hold: the box is what says the row is in
            // the set and the tap is what puts it there.
            if (showCheckbox) {
                SelectionCheckbox(
                    isMarked = isMarked,
                    color = if (isMarked) rowColours.selectedText else iconColour,
                )
            }
            Box(
                modifier = Modifier.width(EntryIconWidth),
                contentAlignment = Alignment.CenterStart,
            ) {
                EntryIconMark(
                    name = entry.name,
                    isDir = entry.isDir,
                    isExpanded = isExpanded,
                    color = iconColour.copy(alpha = if (isCut) CUT_ALPHA else 1f),
                )
            }
            // The name takes ALL remaining width — Zed's content group is
            // `flex_grow_1` with `justify_between` against the end slot
            // (list_item.rs:425-441) — so the ellipsis uses the full row and
            // the git mark below is pinned to the row's end.
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isCut) color.copy(alpha = CUT_ALPHA) else color,
                maxLines = 1,
                // A folded `a/b/c` label is worth reading from its *end* —
                // the directory it names is the last component — so the
                // ellipsis goes in the middle, where Zed puts it for paths.
                overflow = if (label.contains('/')) {
                    TextOverflow.MiddleEllipsis
                } else {
                    TextOverflow.Ellipsis
                },
                modifier = Modifier.weight(1f),
            )
            // Zed's trailing git mark, in the row's end slot: a status letter
            // for files, a half-opacity dot for a directory with changes
            // (project_panel.rs:6188-6205, 7786-7809).
            val letter = statusLetter(status)
            if (letter != null && !(entry.isIgnored && dimIgnored)) {
                if (entry.isDir) {
                    Box(
                        modifier = Modifier
                            .padding(end = StatusSlotEndPadding)
                            .size(rem(PanelMetrics.DIRECTORY_DOT))
                            .clip(CircleShape)
                            .background(tinted.copy(alpha = 0.5f)),
                    )
                } else {
                    Text(
                        text = letter,
                        style = MaterialTheme.typography.labelMedium,
                        color = tinted,
                        maxLines = 1,
                        modifier = Modifier.padding(end = StatusSlotEndPadding),
                    )
                }
            }
        }
        menu()
    }
}

/**
 * Zed's letter for a change, from `git_status_indicator`
 * (project_panel.rs:7786-7809): conflicts shout, then the worktree's own
 * state. Renames surface as the index modification they are.
 */
private fun statusLetter(status: GitFileStatus): String? = when (status) {
    GitFileStatus.Conflicted -> "!"
    GitFileStatus.Untracked -> "U"
    GitFileStatus.Deleted -> "D"
    GitFileStatus.Modified -> "M"
    GitFileStatus.Added -> "A"
    GitFileStatus.Renamed -> "M"
    GitFileStatus.None, GitFileStatus.Ignored -> null
}

@Composable
private fun PanelMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(rem(PanelMetrics.MESSAGE_PADDING))) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A right-click opens the context menu where it happened, before anything else
 * sees the press.
 *
 * Watched in the initial pass and consumed only when it is the secondary
 * button, so an ordinary click still reaches the clickable below — and a mouse
 * gets the menu without the half-second a long press costs. [onPress] sees
 * every press, which is how a long press knows where the finger was.
 *
 * The gesture is installed once and reads its callbacks through state, because
 * restarting a `pointerInput` on every recomposition would drop presses that
 * are in flight.
 */
@Composable
private fun rememberPointerContextMenu(
    onPress: (Offset, RowModifiers) -> Unit = { _, _ -> },
    onContext: (Offset) -> Unit,
): Modifier {
    val press by rememberUpdatedState(onPress)
    val context by rememberUpdatedState(onContext)
    return remember {
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type != PointerEventType.Press) continue
                    val change = event.changes.firstOrNull() ?: continue
                    press(
                        change.position,
                        rowModifiersOf(event.keyboardModifiers),
                    )
                    if (!event.buttons.isSecondaryPressed) continue
                    change.consume()
                    context(change.position)
                }
            }
        }
    }
}

/** A file the panel just created, before the worktree has reported it. */
private fun newFileEntry(path: String): ProjectEntry {
    val name = path.substringAfterLast('/')
    return ProjectEntry(
        path = path,
        name = name,
        isDir = false,
        isIgnored = false,
        isHidden = name.startsWith("."),
        isUnloaded = false,
        size = 0L,
    )
}

/** How much of a name a rename should start out selecting. */
private fun stemLength(name: String, isDir: Boolean): Int {
    val dot = name.lastIndexOf('.')
    return if (isDir || dot <= 0) name.length else dot
}

private const val CUT_ALPHA = 0.45f
