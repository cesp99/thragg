package to.eyed.seeker.code.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import to.eyed.seeker.code.core.GitignoredFiles
import to.eyed.seeker.code.core.ProjectEntry
import to.eyed.seeker.code.core.ProjectSession
import to.eyed.seeker.code.core.ProjectWorktree

/**
 * One visible line of the project tree: an entry, the folder of the project it
 * is in, its indent depth, and the git status it was last flattened with.
 *
 * Status lives *in the row* rather than being looked up while drawing: rows
 * are built off the main thread and the panel is virtualised, so a per-row map
 * read would otherwise run again for every visible row on every scroll frame.
 */
data class ProjectTreeRow(
    val entry: ProjectEntry,
    val depth: Int,
    val status: GitFileStatus = GitFileStatus.None,
    /**
     * What the row is *labelled*, which is the entry's own name except on a
     * folded chain, where it is `a/b/c` — Zed's `auto_fold_dirs`. The entry
     * itself is always the chain's deepest directory, so expanding, selecting,
     * renaming and deleting all act on the row you can see.
     */
    val label: String = entry.name,
    /**
     * Which folder of the project this row belongs to — [ProjectWorktree.id].
     * A project with one folder has one value here and nothing changes.
     */
    val worktree: Long = 0L,
    /**
     * A folder's own header row, drawn where Zed draws a worktree root.
     * Only present once a project has more than one folder to tell apart.
     */
    val isRoot: Boolean = false,
) {
    /**
     * What identifies the row: the same relative path can exist in two
     * folders, so a path alone is not a key for selection, expansion, marks
     * or the list's own item keys.
     */
    val key: String get() = rowKey(worktree, entry.path)
}

/** [ProjectTreeRow.key] for a path that is not a row yet. */
internal fun rowKey(worktree: Long, path: String): String = "$worktree:$path"

/**
 * Fold a run of single-child directories into one row — Zed's `auto_fold_dirs`
 * (project_panel.rs:4418-4432): while a directory's only child is another
 * directory, the parent is skipped and the chain is drawn as one compact
 * `a/b/c` row.
 *
 * Returns the chain's deepest directory and the label to draw it with. [start]
 * itself when there is nothing to fold, which is every file and every
 * directory with more than one child.
 *
 * [childrenOf] is a directory reader — the engine's, in the panel; a map, in
 * the tests. A directory the worktree has not scanned reads as empty and is
 * therefore never folded, which is the right answer: folding it would claim
 * something about children nobody has looked at.
 *
 * Pure and separate from [ProjectTreeState] so the rule itself is testable
 * (`ProjectTreeFoldTest`).
 */
fun foldDirectoryChain(
    start: ProjectEntry,
    childrenOf: (String) -> List<ProjectEntry>,
): ProjectTreeRow {
    if (!start.isDir || start.isUnloaded || start.isIgnored) {
        return ProjectTreeRow(start, 0)
    }
    var current = start
    var label = start.name
    // Bounded by the tree's own depth cap: a symlink loop the worktree
    // followed must not turn a fold into an unbounded walk.
    var steps = 0
    while (steps < MAX_FOLD_DEPTH) {
        val children = childrenOf(current.path)
        val only = children.singleOrNull() ?: break
        if (!only.isDir || only.isUnloaded || only.isIgnored) break
        label = "$label/${only.name}"
        current = only
        steps++
    }
    return ProjectTreeRow(current, 0, label = label)
}

private const val MAX_FOLD_DEPTH = 64

/**
 * The paths a shift-click marks: everything between [anchor] and [target] in
 * [paths], inclusive, in list order — Zed's range selection
 * (project_panel.rs, `SelectedEntry` ranges under `marked_entries`).
 *
 * An anchor that is no longer in the list — its directory was collapsed under
 * it — degrades to marking [target] alone rather than marking nothing, which
 * is what makes shift-click always do *something* visible.
 *
 * Pure, and tested (`ProjectPanelSelectionTest`).
 */
fun markedRange(paths: List<String>, anchor: String?, target: String): List<String> {
    val to = paths.indexOf(target)
    if (to < 0) return emptyList()
    val from = anchor?.let(paths::indexOf) ?: -1
    if (from < 0) return listOf(target)
    val first = minOf(from, to)
    val last = maxOf(from, to)
    return paths.subList(first, last + 1).toList()
}

/**
 * A flattened tree plus the status version it was flattened at, so the panel
 * can tell whether the rows it is holding already reflect the latest statuses.
 */
data class ProjectTreeSnapshot(
    val rows: List<ProjectTreeRow>,
    val statusVersion: Long,
    /** The project's folders as they were when this was flattened. */
    val worktrees: List<ProjectWorktree> = emptyList(),
)

/**
 * Flattens a project's folders into the rows the panel draws.
 *
 * A project is Zed's ordered list of worktrees (`project.rs`), so the flatten
 * is one section per folder: with a single folder it is exactly the tree it
 * always was, and with more than one each gets a header row of its own —
 * Zed's worktree root — with its own expand state, above its entries.
 *
 * Only expanded directories are ever queried, so the engine's tree stays
 * lazy: collapsing a directory drops its children from the cache, and a
 * directory the worktree hasn't scanned yet ([ProjectEntry.isUnloaded], or an
 * ignored one) is expanded on demand through the engine.
 *
 * [rebuild] does the JNI reads and JSON parsing, so callers should run it off
 * the main thread and then publish the result with [publish]. [restatus] is
 * the cheap half: statuses usually arrive *after* the tree and change far more
 * often than its shape, so re-colouring never re-reads the worktree.
 */
class ProjectTreeState(
    private val session: ProjectSession,
    /** How gitignored entries are treated (listed, dimmed, or left out). */
    private val gitignoredFiles: GitignoredFiles = GitignoredFiles.Dimmed,
    /** Per-path git status; [GitStatusSource.Absent] leaves every row plain. */
    private val gitStatus: GitStatusSource = GitStatusSource.Absent,
    /** Zed's `project_panel.auto_fold_dirs`, on by default. */
    private val autoFoldDirs: Boolean = true,
) {
    /**
     * The rows the next bulk operation applies to — Zed's `marked_entries`,
     * held as [ProjectTreeRow.key]s.
     *
     * Empty means "just the selection": every command falls back to
     * [selected], so nothing has to know whether a multi-select is in
     * progress. Ctrl-click toggles a row in and out; shift-click marks a
     * range from [anchor].
     */
    private val _marked = mutableStateListOf<String>()
    val marked: List<String> get() = _marked

    /** The key the last plain click landed on — what a shift-click ranges from. */
    var anchor by mutableStateOf<String?>(null)
        private set

    /**
     * Touch's way into a multi-select: a long press turns the rows into
     * checkboxes, and every tap then marks rather than opens. A finger has no
     * Ctrl and no Shift, so the mode is the substitute for both.
     */
    var selectionMode by mutableStateOf(false)
        private set

    /** The row keys a command should act on: the marks, else the selection. */
    val targets: List<String>
        get() = if (_marked.isEmpty()) listOfNotNull(selected) else _marked.toList()

    /** The marked rows themselves, in tree order — what an operation needs. */
    val targetRows: List<ProjectTreeRow>
        get() = targets.toSet().let { keys -> rows.filter { it.key in keys } }

    fun isMarked(key: String): Boolean = _marked.contains(key)

    /** Ctrl-click: mark [key], or unmark it if it was marked. */
    fun toggleMark(key: String) {
        if (!_marked.remove(key)) _marked.add(key)
        anchor = key
        if (_marked.isEmpty()) selectionMode = false
    }

    /** Shift-click: mark everything from [anchor] to [key]. */
    fun markRange(key: String) {
        val range = markedRange(rows.map { it.key }, anchor ?: selected, key)
        _marked.clear()
        _marked.addAll(range)
    }

    /** A plain click: one mark, and a fresh anchor for the next shift-click. */
    fun markOnly(key: String?) {
        _marked.clear()
        if (key != null) _marked.add(key)
        anchor = key
    }

    fun clearMarks() {
        _marked.clear()
        selectionMode = false
    }

    /** Enter touch selection mode with [key] marked. */
    fun beginSelectionMode(key: String) {
        selectionMode = true
        if (!_marked.contains(key)) _marked.add(key)
        anchor = key
    }

    /** Drop marks for rows that are no longer on screen. */
    private fun pruneMarks() {
        if (_marked.isEmpty()) return
        val visible = rows.mapTo(HashSet()) { it.key }
        _marked.retainAll { it in visible }
        if (_marked.isEmpty()) selectionMode = false
    }

    /** [ProjectTreeRow.key]s of the expanded directories. */
    private val expanded = mutableStateListOf<String>()

    /**
     * Folders whose section is collapsed. Folders start open — a folder you
     * just added and cannot see is not added as far as anyone can tell — so
     * this is the inverse of [expanded], which starts empty.
     */
    private val collapsedRoots = mutableStateListOf<Long>()

    /** Engine snapshot version the current rows were built from. */
    var version by mutableLongStateOf(-1L)
        private set

    /** Status-source version the current rows were coloured from. */
    var statusVersion by mutableLongStateOf(-1L)
        private set

    var rows by mutableStateOf<List<ProjectTreeRow>>(emptyList())
        private set

    /**
     * The project's folders, as of the last [publish] — what the panel draws
     * headers from and resolves a row's root with. Empty until the first
     * flatten lands.
     */
    var worktrees by mutableStateOf<List<ProjectWorktree>>(emptyList())
        private set

    /**
     * Bumped whenever the tree's *shape* could change — i.e. by a toggle.
     *
     * A re-colour pass reads the rows on the main thread, does its work on
     * another, and publishes; if the user expanded a directory in that window,
     * publishing the old rows would leave the panel showing an expanded
     * chevron with no children under it, and neither version counter would
     * ever fire again to correct it. [publish] uses this to drop a result that
     * was computed against a shape nobody is looking at any more.
     */
    var shape by mutableLongStateOf(0L)
        private set

    /**
     * The row the keyboard is on, as a [ProjectTreeRow.key], or null when the
     * panel has never been driven from the keyboard.
     *
     * Separate from the *open* file the panel highlights: they are usually the
     * same row and occasionally not, exactly as in Zed, where moving the
     * selection through the tree doesn't open anything until you ask.
     */
    var selected by mutableStateOf<String?>(null)
        private set

    /**
     * A row [reveal] was asked for that isn't on screen yet, as a key.
     *
     * Revealing a file inside a directory the worktree hasn't scanned can't
     * finish in one pass: the engine is asked to scan, and the row appears a
     * snapshot or two later. The panel scrolls to it when it does, and clears
     * this with [revealed].
     */
    var pendingReveal by mutableStateOf<String?>(null)
        private set

    /** Whether the project has more than one folder, as last flattened. */
    val isMultiRoot: Boolean get() = worktrees.size > 1

    fun isExpanded(key: String): Boolean = expanded.contains(key)

    /** Whether a folder's section is showing its entries. */
    fun isRootExpanded(worktree: Long): Boolean = !collapsedRoots.contains(worktree)

    /** The row the selection is on, if it is still in the tree. */
    val selectedRow: ProjectTreeRow?
        get() = selected?.let { key -> rows.firstOrNull { it.key == key } }

    fun select(key: String?) {
        selected = key
    }

    /**
     * Move the selection [delta] visible rows, without wrapping — the ends of
     * a file tree are meaningful places to be, and Zed's panel stops there too.
     * Selects the first (or last) row when nothing is selected yet.
     */
    fun moveSelection(delta: Int) {
        if (rows.isEmpty()) return
        val current = rows.indexOfFirst { it.key == selected }
        selected = when {
            current < 0 -> if (delta > 0) rows.first().key else rows.last().key
            else -> rows[(current + delta).coerceIn(0, rows.lastIndex)].key
        }
    }

    /** Jump the selection to the first or last visible row. */
    fun selectEdge(last: Boolean) {
        if (rows.isEmpty()) return
        selected = if (last) rows.last().key else rows.first().key
    }

    /**
     * Expand or collapse a directory — or a folder's whole section, when the
     * row is one of the headers. Expanding a directory the worktree deferred
     * also asks the engine to scan it; its contents then arrive as a version
     * bump.
     */
    fun toggle(row: ProjectTreeRow) {
        if (row.isRoot) {
            shape += 1
            if (!collapsedRoots.remove(row.worktree)) collapsedRoots.add(row.worktree)
            return
        }
        if (!row.entry.isDir) return
        if (isExpanded(row.key)) collapse(row.key) else expand(row)
    }

    /** Expand a directory. Returns false if it was already open, or is a file. */
    fun expand(row: ProjectTreeRow): Boolean {
        if (row.isRoot) {
            if (!collapsedRoots.remove(row.worktree)) return false
            shape += 1
            return true
        }
        if (!row.entry.isDir || expanded.contains(row.key)) return false
        shape += 1
        expanded.add(row.key)
        if (row.entry.isUnloaded || row.entry.isIgnored) {
            session.expand(row.worktree, row.entry.path)
        }
        return true
    }

    /** Collapse a directory by key. Returns false if it wasn't open. */
    fun collapse(key: String): Boolean {
        if (!expanded.remove(key)) return false
        shape += 1
        return true
    }

    /** Collapse everything, back to each folder's top level. */
    fun collapseAll(): Boolean {
        if (expanded.isEmpty()) return false
        shape += 1
        expanded.clear()
        return true
    }

    /**
     * Every directory "expand all" should open, as keys: the ones the
     * worktrees have already scanned, walked breadth-first so the shallow ones
     * are taken first if [limit] runs out. **Blocking** — one engine read per
     * directory, so callers run it off the main thread and hand the result to
     * [expandAll].
     *
     * Directories the worktree deferred ([ProjectEntry.isUnloaded], and
     * gitignored ones) are left closed rather than triggering a scan of
     * everything: `node_modules` is exactly the thing "expand all" must not
     * pull into memory, and it is one tap away for anyone who does want it.
     */
    fun expandableDirectories(limit: Int = MAX_EXPANDED): List<String> {
        val found = mutableListOf<String>()
        val queue = ArrayDeque<Triple<Long, String, Int>>()
        for (folder in session.worktrees()) queue.add(Triple(folder.id, "", 0))
        while (queue.isNotEmpty() && found.size < limit) {
            val (worktree, dir, depth) = queue.removeFirst()
            if (depth > MAX_DEPTH) continue
            for (entry in session.children(worktree, dir)) {
                if (!entry.isDir || entry.isUnloaded || entry.isIgnored) continue
                if (found.size >= limit) break
                found += rowKey(worktree, entry.path)
                queue.add(Triple(worktree, entry.path, depth + 1))
            }
        }
        return found
    }

    /** Open [keys] — the result of [expandableDirectories]. */
    fun expandAll(keys: List<String>) {
        if (keys.isEmpty()) return
        shape += 1
        for (key in keys) if (!expanded.contains(key)) expanded.add(key)
    }

    /**
     * Open everything above [path] in [worktree] and put the selection on it —
     * "reveal the active file", and where the tree lands after a file
     * operation.
     *
     * Ancestors are handed to the engine unconditionally: expanding a
     * directory it has already scanned is a no-op there, and the alternative
     * is looking each ancestor up first just to decide not to ask.
     */
    fun reveal(worktree: Long, path: String) {
        if (path.isEmpty()) return
        shape += 1
        // A folder whose section is closed cannot show the row it holds.
        collapsedRoots.remove(worktree)
        var prefix = ""
        for (part in path.split('/').dropLast(1)) {
            prefix = if (prefix.isEmpty()) part else "$prefix/$part"
            val key = rowKey(worktree, prefix)
            if (!expanded.contains(key)) expanded.add(key)
            session.expand(worktree, prefix)
        }
        selected = rowKey(worktree, path)
        pendingReveal = selected
    }

    /**
     * [reveal], by [ProjectTreeRow.key] — what a caller holding a key rather
     * than a folder and a path has, as the trash's Undo does.
     */
    fun reveal(key: String) {
        val worktree = key.substringBefore(':').toLongOrNull() ?: return
        reveal(worktree, key.substringAfter(':'))
    }

    /** The panel has scrolled to [pendingReveal]; stop watching for it. */
    fun revealed() {
        pendingReveal = null
    }

    /** Walk the expanded directories, reading each one's children. Blocking. */
    fun rebuild(): ProjectTreeSnapshot {
        // Read the status table once per flatten, not once per row.
        val statuses = gitStatus.snapshot()
        val folders = session.worktrees()
        val multi = folders.size > 1
        val rows = mutableListOf<ProjectTreeRow>()
        for (folder in folders) {
            if (multi) {
                // Zed's worktree root: an ordinary row, at the top of its own
                // section, that expands and collapses everything below it.
                rows += ProjectTreeRow(
                    entry = ProjectEntry(
                        path = "",
                        name = folder.name,
                        isDir = true,
                        isIgnored = false,
                        isHidden = false,
                        isUnloaded = false,
                        size = 0L,
                    ),
                    depth = 0,
                    worktree = folder.id,
                    isRoot = true,
                )
                if (collapsedRoots.contains(folder.id)) continue
            }
            appendChildren(folder, folders.first().id, "", if (multi) 1 else 0, rows, statuses)
        }
        return ProjectTreeSnapshot(rows, statuses.version, folders)
    }

    /**
     * Re-colour [current] (normally [rows], read on the main thread before
     * handing it over) from the latest statuses, leaving the tree's shape —
     * and therefore the LazyColumn's keys and layout — alone. Blocking.
     *
     * Returns the *same* list instance when nothing changed, so a status bump
     * that doesn't touch anything visible costs no recomposition and no
     * flicker; otherwise one fresh row per line, off the main thread.
     */
    fun restatus(current: List<ProjectTreeRow>): ProjectTreeSnapshot {
        val statuses = gitStatus.snapshot()
        val primary = worktrees.firstOrNull()?.id ?: current.firstOrNull()?.worktree ?: 0L
        var changed = false
        for (row in current) {
            if (row.status != statusOf(statuses, primary, row.worktree, row.entry.path)) {
                changed = true
                break
            }
        }
        if (!changed) return ProjectTreeSnapshot(current, statuses.version, worktrees)
        return ProjectTreeSnapshot(
            current.map { it.copy(status = statusOf(statuses, primary, it.worktree, it.entry.path)) },
            statuses.version,
            worktrees,
        )
    }

    /**
     * Install a snapshot. [shapeWhenComputed] is the [shape] the caller saw
     * before it started; a mismatch means the tree was expanded or collapsed
     * meanwhile and this result describes a tree that no longer exists, so it
     * is dropped rather than painted.
     */
    fun publish(version: Long, snapshot: ProjectTreeSnapshot, shapeWhenComputed: Long = shape) {
        if (shapeWhenComputed != shape) return
        this.version = version
        this.statusVersion = snapshot.statusVersion
        this.rows = snapshot.rows
        if (snapshot.worktrees.isNotEmpty()) this.worktrees = snapshot.worktrees
        pruneMarks()
    }

    private fun appendChildren(
        folder: ProjectWorktree,
        primary: Long,
        dir: String,
        depth: Int,
        into: MutableList<ProjectTreeRow>,
        statuses: GitStatusSnapshot,
    ) {
        // Guard against a pathological tree (or a symlink loop the worktree
        // followed) turning a UI refresh into an unbounded walk.
        if (depth > MAX_DEPTH) return
        for (child in session.children(folder.id, dir)) {
            if (child.isIgnored && gitignoredFiles == GitignoredFiles.Hide) continue
            // `auto_fold_dirs`: a run of single-child directories is one row.
            // The row *is* the deepest of them, so expanding it, renaming it
            // or deleting it act on what the label's last component names —
            // which is what Zed's folded row does too. Read within this
            // folder: a chain never crosses from one root into another.
            val folded = if (autoFoldDirs) {
                foldDirectoryChain(child) { path -> session.children(folder.id, path) }
            } else {
                ProjectTreeRow(child, 0)
            }
            val entry = folded.entry
            // Directories carry whatever roll-up the engine published for them
            // and nothing more: a summary invented from the children we happen
            // to have scanned would be wrong for the ones we haven't.
            into += ProjectTreeRow(
                entry = entry,
                depth = depth,
                status = statusOf(statuses, primary, folder.id, entry.path),
                label = folded.label,
                worktree = folder.id,
            )
            if (entry.isDir && expanded.contains(rowKey(folder.id, entry.path))) {
                appendChildren(folder, primary, entry.path, depth + 1, into, statuses)
            }
        }
    }

    /**
     * Git status for a row. The engine reports statuses for the repository the
     * project's *own* folder is in, keyed by paths relative to it, so a row in
     * a folder added later has none to show — see docs/ARCHITECTURE.md.
     */
    private fun statusOf(
        statuses: GitStatusSnapshot,
        primary: Long,
        worktree: Long,
        path: String,
    ): GitFileStatus {
        if (worktree != primary) return GitFileStatus.None
        return statuses.statusOf(path)
    }

    private companion object {
        const val MAX_DEPTH = 64

        /**
         * Ceiling on what one "expand all" will open. A project with more
         * directories than this is one where expanding everything was never
         * the useful answer, and the flattening cost is linear in what it
         * opens.
         */
        const val MAX_EXPANDED = 2_000
    }
}

/**
 * The row Zed's sticky headers are computed *for*: the first visible row that
 * is guaranteed to sit below the pinned stack of its own ancestors. The stack
 * itself is that row's ancestor chain.
 *
 * [drifting] is the push-off: the anchor is the last row of the deepest pinned
 * directory and is about to slide under the stack, so the stack's last row
 * rides up with the anchor's bottom edge instead of sitting in its slot
 * (`StickyAnchor.drifting`, ui/src/components/sticky_items.rs:279-283).
 */
internal data class StickyAnchor(
    /** Index into the visible depths handed to [findStickyAnchor]. */
    val localIndex: Int,
    val drifting: Boolean,
)

/**
 * Zed's `find_sticky_anchor` (ui/src/components/sticky_items.rs:285-316), on
 * our depth basis: walk the visible rows from the top; a row whose depth is
 * smaller than its position sits below a fully-formed stack of its ancestors,
 * so it anchors. Before that, a row whose *next* sibling outdents by exactly
 * one is the last child of the deepest pinned directory — it anchors at its
 * own slot (`depth == ix`), or one slot early and still drifting
 * (`depth == ix + 1`).
 *
 * [visibleDepths] are the flattened rows' [ProjectTreeRow.depth]s from the
 * first visible row down.
 *
 * **The basis is one lower than Zed's, and that is not cosmetic.** Zed's list
 * begins with the worktree root at depth 0, so a top-level entry is depth 1
 * (`calculate_depth_and_difference` returns `depth + 1` for any entry whose
 * parent is visible, and the root is visible — project_panel.rs:5529-5553),
 * and the stack pinned for a row of depth `d` is `d` rows *including* that
 * root. Ours holds the root out of the list entirely — it is the permanent
 * header above it — so a top-level row is depth 0 and the stack for depth `d`
 * is `d` rows *without* the root. Every comparison below is between a row's
 * depth and its slot, and the stack covering those slots is shorter by the
 * same one row, so the geometry — which slots the stack covers, when its
 * deepest row starts drifting — comes out where Zed's does.
 *
 * What it costs is the choice of anchor. Fed the same viewport, this picks the
 * state Zed reaches one scroll row earlier, because Zed's root row occupies a
 * list slot that ours does not. At a fold boundary that shows: with rows `a/`,
 * `a/b/`, two files, `a/c/`, three files, and the viewport starting at the
 * first file, this pins `a` and `a/b` (drifting) over the two files they own,
 * while Zed pins root, `a` and `a/c` and covers `a/c`'s own row with the pin.
 * Feeding `depth + 1` here would buy Zed's choice and break the geometry that
 * makes it work: the stack we can draw is one row shorter than Zed's, so its
 * deepest pin would land one slot above the real row it is meant to cover, and
 * the panel would show that directory twice.
 */
internal fun findStickyAnchor(visibleDepths: List<Int>): StickyAnchor? {
    for (ix in visibleDepths.indices) {
        val depth = visibleDepths[ix]
        if (depth < ix) return StickyAnchor(ix, drifting = false)
        val nextDepth = visibleDepths.getOrNull(ix + 1) ?: continue
        if (nextDepth + 1 == depth && (depth == ix || depth == ix + 1)) {
            return StickyAnchor(ix, drifting = depth == ix + 1)
        }
    }
    return null
}

/**
 * The rows that pin for [anchorIndex]: its ancestor directories, outermost
 * first — Zed's `sticky_parents` (project_panel.rs:6824-6846). Zed rebuilds
 * the chain from path prefixes; the flattened list makes it a backward walk,
 * because the parent of a row at depth `d` is the nearest earlier row at depth
 * `d − 1` (the flatten is a strict depth-first walk, so nothing shallower can
 * intervene). One pass, integer compares only.
 */
internal fun stickyAncestorsOf(rows: List<ProjectTreeRow>, anchorIndex: Int): List<Int> {
    val anchor = rows.getOrNull(anchorIndex) ?: return emptyList()
    var want = anchor.depth - 1
    if (want < 0) return emptyList()
    val found = ArrayList<Int>(anchor.depth)
    var i = anchorIndex - 1
    while (i >= 0 && want >= 0) {
        if (rows[i].depth <= want) {
            found += i
            want = rows[i].depth - 1
        }
        i--
    }
    found.reverse()
    return found
}

/**
 * The push-off: how far up the deepest pinned row has been pushed, in pixels,
 * and never positive.
 *
 * Zed's `drifting_y_offset` (sticky_items.rs:179-186): while [drifting], the
 * bottom of the stack is held to the bottom edge of the anchor row — the last
 * child of the deepest pinned directory — so the stack slides out continuously
 * with the scroll instead of swapping when the boundary passes. It is 0 while
 * the anchor still sits in the stack's last slot, and goes negative as the
 * anchor scrolls up past it.
 *
 * [anchorOffsetPx] is the anchor row's top in viewport coordinates and
 * [rowHeightPx] the measured row pitch, so `offset + rowHeight` is Zed's
 * `anchor_top - scroll_top` and `rowHeight * pinnedCount` its
 * `sticky_area_height`.
 */
internal fun stickyDriftPx(
    anchorOffsetPx: Int,
    rowHeightPx: Int,
    pinnedCount: Int,
    drifting: Boolean,
): Int {
    if (!drifting) return 0
    return (anchorOffsetPx + rowHeightPx - rowHeightPx * pinnedCount).coerceAtMost(0)
}

/**
 * The indent-guide run the selection lights up, in `panel.indent_guide_active`.
 *
 * Zed's `find_active_indent_guide` (project_panel.rs:6724-6790): walk up from
 * the selected entry to the nearest *expanded* directory — the selection
 * itself when it is one — and the active guide is the one its children hang
 * from. Every ancestor of a visible row is expanded by construction, so the
 * walk here is one step: the row, else its parent, else the root — whose run
 * is the whole list, exactly as Zed's worktree root is the expanded ancestor
 * of a top-level selection.
 *
 * [level] is in the panel's rendered guide coordinates (a row at
 * [ProjectTreeRow.depth] `d` draws levels `0..d`, being drawn one level in
 * from the root row). Recomputed only when the selection or the tree's shape
 * moves, never per frame.
 */
internal data class ActiveGuideRun(
    val level: Int,
    /** First and last row index (inclusive) the active run spans. */
    val first: Int,
    val last: Int,
)

internal fun activeGuideRun(
    rows: List<ProjectTreeRow>,
    selected: String?,
    isExpanded: (String) -> Boolean,
): ActiveGuideRun? {
    if (selected == null) return null
    val index = rows.indexOfFirst { it.key == selected }
    if (index < 0) return null
    val row = rows[index]
    val dirIndex: Int
    if (row.entry.isDir && isExpanded(row.key)) {
        dirIndex = index
    } else {
        if (row.depth == 0) {
            // The parent is the root row above the list: its run is every row.
            return ActiveGuideRun(level = 0, first = 0, last = rows.lastIndex)
        }
        var i = index - 1
        while (i >= 0 && rows[i].depth >= row.depth) i--
        if (i < 0) return null
        dirIndex = i
    }
    val dirDepth = rows[dirIndex].depth
    var last = dirIndex
    while (last < rows.lastIndex && rows[last + 1].depth > dirDepth) last++
    // An expanded directory with nothing under it hangs no guide.
    if (last == dirIndex) return null
    return ActiveGuideRun(level = dirDepth + 1, first = dirIndex + 1, last = last)
}
