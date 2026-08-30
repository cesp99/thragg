package to.eyed.seeker.code.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Zed's `HANDLE_HITBOX_SIZE` (workspace/src/pane_group.rs:22): the slack
 * `find_pane_in_direction` allows between a pane's edge and its neighbour's.
 */
internal const val PANE_HANDLE_HITBOX_PX = 4f

/**
 * Zed's `HORIZONTAL_MIN_SIZE` / `VERTICAL_MIN_SIZE` (pane_group.rs:23-24):
 * a resize never takes a pane below these.
 */
internal const val PANE_MIN_WIDTH_PX = 80f
internal const val PANE_MIN_HEIGHT_PX = 100f

/**
 * Zed's `drop_target_size` default (assets/settings/default.json:202): the
 * band along each edge of a pane, as a fraction of its shorter side, where a
 * dropped tab splits rather than joins.
 */
internal const val DROP_TARGET_SIZE = 0.2f

/** The four directions a split or a pane move can take — Zed's `SplitDirection`. */
enum class SplitDirection {
    Up, Down, Left, Right;

    /** `SplitDirection::axis` (pane_group.rs:1115-1120). */
    val axis: PaneAxis
        get() = when (this) {
            Up, Down -> PaneAxis.Vertical
            Left, Right -> PaneAxis.Horizontal
        }

    /** `SplitDirection::increasing` (pane_group.rs:1122-1127): towards the end of the axis. */
    val increasing: Boolean
        get() = this == Down || this == Right

    /** `SplitDirection::opposite` (pane_group.rs:1129-1136). */
    val opposite: SplitDirection
        get() = when (this) {
            Up -> Down
            Down -> Up
            Left -> Right
            Right -> Left
        }
}

/** Which way an axis lays its members out — gpui's `Axis`. */
enum class PaneAxis { Horizontal, Vertical }

/**
 * One node of the pane tree — Zed's `Member` (pane_group.rs:296-299): a leaf
 * that holds tabs, or an axis that holds members.
 */
sealed interface PaneMember

/**
 * One pane: its own tabs, active tab, reopen stack and navigation history —
 * all of which live in [files], which was the whole workspace's tab list
 * until there could be more than one.
 *
 * Identified by [id] rather than by identity so the layout can be written
 * out and read back (a session restore names panes by position, and the
 * tree by ids) and so the geometry the renderer reports can be keyed.
 */
class Pane internal constructor(val id: Int, val files: OpenFilesState = OpenFilesState()) : PaneMember

/**
 * A row or column of members — Zed's `PaneAxis` (pane_group.rs:648-653).
 *
 * N-ary, as Zed's is, not binary: splitting a pane along the axis it already
 * sits in inserts a sibling rather than nesting a new axis
 * (pane_group.rs:685-715), which is what keeps three columns three equal
 * columns and not a half and two quarters. [flexes] sum to the member
 * count, Zed's invariant (`flex_values_in_bounds`, pane_group.rs:1615), so a
 * flex of 1 is "an equal share".
 */
class PaneAxisNode internal constructor(
    val axis: PaneAxis,
    members: List<PaneMember>,
    flexes: List<Float>? = null,
) : PaneMember {
    internal val members = mutableStateListOf<PaneMember>().also { it.addAll(members) }
    internal val flexes = mutableStateListOf<Float>()

    init {
        // Zed's `PaneAxis::load` (pane_group.rs:667-683): flexes that do not
        // fit the members, or do not sum to their count, are reset to equal.
        val given = flexes
        if (given != null && given.size == members.size &&
            kotlin.math.abs(given.sum() - members.size) < 0.001f
        ) {
            this.flexes.addAll(given)
        } else {
            repeat(members.size) { this.flexes.add(1f) }
        }
    }

    val memberList: List<PaneMember> get() = members
    val flexList: List<Float> get() = flexes

    internal fun resetFlexes() {
        flexes.clear()
        repeat(members.size) { flexes.add(1f) }
    }
}

/**
 * The layout without the live objects — what a session restore writes and
 * reads: the tree shape, each pane's paths and active tab, the flexes.
 *
 * [PaneGroupState.layout] produces one and [PaneGroupState.restore] consumes
 * one. What each tab *held* — its carets, its scroll, its pinned flag —
 * travels beside it in the session document
 * ([to.eyed.seeker.code.core.WorkspaceSession]) rather than here, so this
 * stays what its name says: the shape of the tree.
 */
sealed interface PaneLayout {
    data class Leaf(val paths: List<String>, val activeIndex: Int, val isActive: Boolean) : PaneLayout
    data class Split(val axis: PaneAxis, val children: List<PaneLayout>, val flexes: List<Float>) : PaneLayout
}

/**
 * Where a dragged tab would land — the pane under the pointer, and the
 * side it would split if the pointer is in an edge band (null means
 * "into this pane, as a tab").
 */
data class DropTarget(val paneId: Int, val direction: SplitDirection?)

/** A tab being dragged: what, from where, and where the pointer is now. */
class TabDrag internal constructor(
    val file: OpenFile,
    val sourcePane: Pane,
    position: Offset,
) {
    /** In root coordinates, like the pane bounds it is tested against. */
    var position: Offset by mutableStateOf(position)
        internal set
}

/**
 * The centre of the workspace — Zed's `PaneGroup` (pane_group.rs:30-33)
 * with the parts of `Workspace` that decide which pane is active, which is
 * zoomed and which is being dragged into.
 *
 * Everything that reads "the active tab" — saving, closing, the palette,
 * the status bar — goes through [active]`.files`; a pane is the unit Zed
 * splits, and each keeps its own tabs, history and reopen stack
 * (pane.rs:322-330).
 *
 * The tree operations are Zed's, one function each, and pure enough to be
 * tested on the host: [split] (pane_group.rs:61-94), [remove]
 * (171-189), [swap] (218-224), [resize] (`compute_resize`, 1224-1319) and
 * [findPaneInDirection] (257-287), which is a geometry question and is
 * answered from the bounds the renderer reports through [recordBounds].
 */
class PaneGroupState {
    private var nextId = 1

    /** Every pane, in the order it was made — Zed's `Workspace::panes`. */
    private val created = mutableStateListOf<Pane>()

    var root: PaneMember by mutableStateOf(newPane())
        private set

    private var activeId by mutableIntStateOf((root as Pane).id)

    /**
     * The pane that fills the work area on its own, or null. Zed keeps the
     * flag on the pane and the handle on the workspace (`Workspace::zoomed`,
     * workspace.rs:5841-5844); one id here does both jobs.
     */
    var zoomedPaneId: Int? by mutableStateOf(null)
        private set

    /** The tab being dragged, while one is. */
    var drag: TabDrag? by mutableStateOf(null)
        private set

    /** Where each pane was last drawn, in root coordinates, by id. */
    private val bounds = HashMap<Int, PaneBounds>()

    /** The pane whose tabs the workspace's commands act on. */
    val active: Pane
        get() = panes.firstOrNull { it.id == activeId } ?: panes.first()

    /** Every pane in tree order — left to right, top to bottom. */
    val panes: List<Pane>
        get() = ArrayList<Pane>().also { collect(root, it) }

    /** Every open tab across every pane. */
    val allTabs: List<OpenFile>
        get() = panes.flatMap { it.files.tabs }

    val isZoomed: Boolean get() = zoomedPaneId != null

    /** The pane that owns [files], for callbacks that only hold the tab list. */
    fun paneOf(files: OpenFilesState): Pane? = panes.firstOrNull { it.files === files }

    fun paneById(id: Int): Pane? = panes.firstOrNull { it.id == id }

    // ---- Activation ----------------------------------------------------

    /** Make [pane] the one the commands act on. Zooming follows focus, as Zed's does. */
    fun activate(pane: Pane) {
        if (pane.id == activeId) return
        activeId = pane.id
        // Zed dismisses a zoomed pane when focus moves to another one
        // (`dismiss_zoomed_items_to_reveal`, workspace.rs:4670-4674).
        if (zoomedPaneId != null && zoomedPaneId != pane.id) zoomedPaneId = null
    }

    /** Zed's `activate_next_pane` (workspace.rs:5459-5466): tree order, wrapping. */
    fun activateNext(): Boolean = activateRelative(1)

    /** Zed's `activate_previous_pane` (workspace.rs:5468-5475). */
    fun activatePrevious(): Boolean = activateRelative(-1)

    private fun activateRelative(delta: Int): Boolean {
        val all = panes
        if (all.size < 2) return false
        val index = all.indexOf(active)
        activate(all[((index + delta) % all.size + all.size) % all.size])
        return true
    }

    /** Zed's `["workspace::ActivatePane", n]`: the nth pane in tree order. */
    fun activateAt(index: Int): Boolean {
        val pane = panes.getOrNull(index) ?: return false
        activate(pane)
        return true
    }

    /**
     * Zed's `activate_pane_in_direction` for the centre group
     * (workspace.rs:5482-5570 → `find_pane_in_direction`): the neighbour
     * past the active pane's edge, or false when there is none that way.
     */
    fun activateInDirection(direction: SplitDirection): Boolean {
        val target = findPaneInDirection(active, direction) ?: return false
        activate(target)
        return true
    }

    // ---- Zoom ------------------------------------------------------------

    /**
     * Zed's `pane::toggle_zoom` (pane.rs:1443-1454): a zoomed pane unzooms;
     * an unzoomed pane with something in it zooms. An empty pane refuses.
     */
    fun toggleZoom(): Boolean {
        if (zoomedPaneId != null) {
            zoomedPaneId = null
            return true
        }
        if (active.files.tabs.isEmpty()) return false
        zoomedPaneId = active.id
        return true
    }

    // ---- Tree operations -------------------------------------------------

    /**
     * Put a new, empty pane beside [pane] — Zed's `PaneGroup::split`
     * (pane_group.rs:61-94): a lone root becomes an axis of two; a pane in
     * an axis of the same orientation gains a sibling (flexes reset to
     * equal, 717-720); one in an axis of the other orientation is replaced
     * by a nested axis of itself and the newcomer (`Member::new_axis`,
     * 501-516, which puts the new pane first for Up and Left).
     */
    fun split(pane: Pane, direction: SplitDirection): Pane {
        val fresh = newPane()
        val current = root
        if (current is Pane) {
            root = newAxis(current, fresh, direction)
        } else if (current is PaneAxisNode && !splitIn(current, pane, fresh, direction)) {
            // Not found: Zed splits the first pane instead (pane_group.rs:81-92).
            splitIn(current, panes.first(), fresh, direction)
        }
        return fresh
    }

    private fun splitIn(axis: PaneAxisNode, old: Pane, fresh: Pane, direction: SplitDirection): Boolean {
        for ((index, member) in axis.members.withIndex()) {
            when (member) {
                is PaneAxisNode -> if (splitIn(member, old, fresh, direction)) return true
                is Pane -> if (member === old) {
                    if (direction.axis == axis.axis) {
                        axis.members.add(if (direction.increasing) index + 1 else index, fresh)
                        axis.resetFlexes()
                    } else {
                        axis.members[index] = newAxis(old, fresh, direction)
                    }
                    return true
                }
            }
        }
        return false
    }

    private fun newAxis(old: Pane, fresh: Pane, direction: SplitDirection): PaneAxisNode {
        val members = if (direction.increasing) listOf(old, fresh) else listOf(fresh, old)
        return PaneAxisNode(direction.axis, members)
    }

    /**
     * Take [pane] out of the tree — Zed's `PaneGroup::remove`
     * (pane_group.rs:171-189, `PaneAxis::remove` 737-777): an axis left with
     * one member collapses into it. The last pane cannot be removed, which
     * is the `Member::Pane => Ok(false)` arm. The tabs it still holds are
     * released, since nothing will draw them again.
     *
     * Focus goes to the most recently made pane still standing, which is
     * Zed's `force_remove_pane` (`self.panes.last()`).
     */
    fun remove(pane: Pane): Boolean {
        val current = root
        if (current !is PaneAxisNode) return false
        val collapsed = removeFrom(current, pane) ?: return false
        if (collapsed !== current) root = collapsed
        created.remove(pane)
        for (index in pane.files.tabs.indices.reversed()) pane.files.close(index)
        bounds.remove(pane.id)
        if (zoomedPaneId == pane.id) zoomedPaneId = null
        if (activeId == pane.id) activeId = created.last().id
        return true
    }

    /**
     * Returns the axis itself when the pane was removed and the axis still
     * stands, the surviving member when the axis collapsed, or null when
     * the pane was not under this axis.
     */
    private fun removeFrom(axis: PaneAxisNode, pane: Pane): PaneMember? {
        var found = false
        for ((index, member) in axis.members.withIndex()) {
            when (member) {
                is PaneAxisNode -> {
                    val result = removeFrom(member, pane) ?: continue
                    if (result !== member) axis.members[index] = result
                    found = true
                }
                is Pane -> if (member === pane) {
                    axis.members.removeAt(index)
                    axis.resetFlexes()
                    found = true
                }
            }
            if (found) break
        }
        if (!found) return null
        return if (axis.members.size == 1) axis.members[0] else axis
    }

    /**
     * Exchange two panes' places — Zed's `PaneGroup::swap` (pane_group.rs:
     * 218-224, 898-911). Flexes stay with the slots, as they do there.
     */
    fun swap(from: Pane, to: Pane) {
        val current = root as? PaneAxisNode ?: return
        swapIn(current, from, to)
    }

    private fun swapIn(axis: PaneAxisNode, from: Pane, to: Pane) {
        for ((index, member) in axis.members.withIndex()) {
            when (member) {
                is PaneAxisNode -> swapIn(member, from, to)
                is Pane -> when {
                    member === from -> axis.members[index] = to
                    member === to -> axis.members[index] = from
                }
            }
        }
    }

    /** Zed's `swap_pane_in_direction` (workspace.rs:5745-5750). */
    fun swapInDirection(direction: SplitDirection): Boolean {
        val target = findPaneInDirection(active, direction) ?: return false
        swap(active, target)
        return true
    }

    /**
     * Drag the divider after member [index] of [axis] by [deltaPx] — Zed's
     * `compute_resize` (pane_group.rs:1224-1319) reduced to the two members
     * the handle sits between: pixels become flex through
     * `delta / container`, and neither side goes under the minimum.
     */
    fun resize(axis: PaneAxisNode, index: Int, deltaPx: Float, containerPx: Float) {
        if (index !in 0 until axis.members.size - 1 || containerPx <= 0f || deltaPx == 0f) return
        val minimum = if (axis.axis == PaneAxis.Horizontal) PANE_MIN_WIDTH_PX else PANE_MIN_HEIGHT_PX
        val count = axis.flexes.size
        val size = { i: Int -> containerPx * (axis.flexes[i] / count) }
        val current = size(index)
        val next = size(index + 1)
        // Both bounded: the far side keeps at least the minimum, and then
        // the near side does too, in that order (pane_group.rs:1286-1298).
        val nextTarget = maxOf(next - deltaPx, minimum)
        val currentTarget = maxOf(current + next - nextTarget, minimum)
        val change = currentTarget - current
        val flexChange = change / containerPx * count
        axis.flexes[index] = axis.flexes[index] + flexChange
        axis.flexes[index + 1] = axis.flexes[index + 1] - flexChange
    }

    // ---- Items between panes ----------------------------------------------

    /**
     * Move a tab from its pane into [to], splitting first when [direction]
     * says so — what a tab drop does (`Pane::handle_tab_drop`,
     * pane.rs:3870-3900 → `workspace::move_item`). The tab keeps its editor
     * and its buffer; only the pane changes. A pane that empties goes, as
     * every empty pane does.
     */
    fun moveItem(file: OpenFile, from: Pane, to: Pane, direction: SplitDirection?) {
        val index = from.files.tabs.indexOf(file)
        if (index < 0) return
        if (direction == null && from === to) return
        val destination = if (direction != null) split(to, direction) else to
        val moved = from.files.detach(index) ?: return
        destination.files.adopt(moved)
        activate(destination)
        pruneIfEmpty(from)
    }

    /**
     * Zed's `join_pane_into_next` (workspace.rs:6093-6109): every tab of the
     * active pane goes to the neighbour right, down, left or up — the first
     * that exists — and the emptied pane goes with it.
     */
    fun joinIntoNext(): Boolean {
        val from = active
        val next = listOf(SplitDirection.Right, SplitDirection.Down, SplitDirection.Left, SplitDirection.Up)
            .firstNotNullOfOrNull { findPaneInDirection(from, it) }
            ?: return false
        moveAllItems(from, next)
        activate(next)
        pruneIfEmpty(from)
        return true
    }

    /**
     * Zed's `join_all_panes` (workspace.rs:6082-6091): everything into the
     * active pane, which keeps its active tab.
     */
    fun joinAll(): Boolean {
        val into = active
        val others = panes.filter { it !== into }
        if (others.isEmpty()) return false
        val keep = into.files.active
        for (pane in others) {
            moveAllItems(pane, into)
            pruneIfEmpty(pane)
        }
        if (keep != null) into.files.indexOfPath(keep.path).takeIf { it >= 0 }?.let(into.files::select)
        return true
    }

    private fun moveAllItems(from: Pane, to: Pane) {
        while (from.files.tabs.isNotEmpty()) {
            val moved = from.files.detach(0) ?: break
            to.files.adopt(moved)
        }
    }

    /** An empty pane that is not the last one goes (Zed's `Event::Remove`, pane.rs:2196-2204). */
    private fun pruneIfEmpty(pane: Pane) {
        if (pane.files.tabs.isEmpty() && panes.size > 1) remove(pane)
    }

    // ---- Shared buffers ----------------------------------------------------

    /**
     * Whether [file]'s buffer is also showing in another tab, in any pane.
     * Two views of one file share one engine buffer — the engine keys them
     * by path (file.rs:132-137) — so the buffer is released only by the
     * last tab to go, and closing one view of dirty text never asks.
     */
    fun isSharedElsewhere(file: OpenFile): Boolean {
        val id = file.session?.id ?: return false
        return allTabs.any { it !== file && it.session?.id == id }
    }

    /** Another tab, in any pane, on the same path — its buffer is what a clone reuses. */
    fun tabForPath(path: String): OpenFile? = allTabs.firstOrNull { it.path == path }

    // ---- Geometry ------------------------------------------------------------

    /** Where the renderer drew [pane]: the whole leaf, and where its tab bar ends. */
    fun recordBounds(pane: Pane, rect: Rect, tabBarBottom: Float) {
        bounds[pane.id] = PaneBounds(rect, tabBarBottom)
    }

    fun boundsOf(pane: Pane): Rect? = bounds[pane.id]?.rect

    /** The pane left the screen — zoomed out of it, or removed — so nothing may land on its old place. */
    fun forgetBounds(pane: Pane) {
        bounds.remove(pane.id)
    }

    /**
     * Zed's `find_pane_in_direction` (pane_group.rs:257-287): from the
     * active pane's centre line, the nearest pane past its edge that way.
     * Zed probes one point `HANDLE_HITBOX_SIZE` past the edge, which works
     * because its handle is painted *over* the panes; ours sits between
     * them, and its width in pixels depends on the screen, so this scans
     * the recorded bounds for the nearest pane whose span covers the centre
     * line instead — the same answer, without a magic distance. Zed probes
     * from the caret when it is inside the pane; the centre is close
     * enough on a screen where the caret is often hidden. Nothing that way
     * — the edge of the group — is null.
     */
    fun findPaneInDirection(from: Pane, direction: SplitDirection): Pane? {
        val box = bounds[from.id]?.rect ?: return null
        val centre = box.center
        val slack = PANE_HANDLE_HITBOX_PX
        val candidates = bounds.entries.filter { it.key != from.id }.map { it.key to it.value.rect }
        val hit = when (direction) {
            SplitDirection.Right -> candidates
                .filter { (_, r) -> r.left >= box.right - slack && centre.y in r.top..r.bottom }
                .minByOrNull { (_, r) -> r.left }
            SplitDirection.Left -> candidates
                .filter { (_, r) -> r.right <= box.left + slack && centre.y in r.top..r.bottom }
                .maxByOrNull { (_, r) -> r.right }
            SplitDirection.Down -> candidates
                .filter { (_, r) -> r.top >= box.bottom - slack && centre.x in r.left..r.right }
                .minByOrNull { (_, r) -> r.top }
            SplitDirection.Up -> candidates
                .filter { (_, r) -> r.bottom <= box.top + slack && centre.x in r.left..r.right }
                .maxByOrNull { (_, r) -> r.bottom }
        } ?: return null
        return paneById(hit.first)
    }

    /** `pane_at_pixel_position` (pane_group.rs:107-119). */
    fun paneAt(point: Offset): Pane? {
        val hit = bounds.entries.firstOrNull { it.value.rect.contains(point) } ?: return null
        return paneById(hit.key)
    }

    // ---- Dragging a tab ---------------------------------------------------

    fun startDrag(file: OpenFile, from: Pane, position: Offset) {
        drag = TabDrag(file, from, position)
    }

    fun updateDrag(position: Offset) {
        drag?.position = position
    }

    /**
     * Where the drag would land right now — Zed's `handle_drag_move`
     * (pane.rs:3805-3859): within [DROP_TARGET_SIZE] of the shorter side
     * from an edge, the nearest edge's direction; anywhere over the tab bar
     * or the middle, into the pane. A pane that could not take the split —
     * the one the tab came from, with nothing else in it — offers only the
     * join, which for its own tab is nothing.
     */
    val dropTarget: DropTarget?
        get() {
            val current = drag ?: return null
            val point = current.position
            val (id, box) = bounds.entries.firstOrNull { it.value.rect.contains(point) } ?: return null
            if (point.y < box.tabBarBottom) return DropTarget(id, null)
            val rect = box.rect
            val band = minOf(rect.width, rect.height) * DROP_TARGET_SIZE
            val x = point.x - rect.left
            val y = point.y - rect.top
            val direction = if (x < band || x > rect.width - band || y < band || y > rect.height - band) {
                listOf(
                    SplitDirection.Up to y,
                    SplitDirection.Right to rect.width - x,
                    SplitDirection.Down to rect.height - y,
                    SplitDirection.Left to x,
                ).minBy { it.second }.first
            } else {
                null
            }
            val onlyTab = current.sourcePane.id == id && current.sourcePane.files.tabs.size <= 1
            return DropTarget(id, if (onlyTab) null else direction)
        }

    /** The pointer went up: perform the drop, if there is one, and forget the drag. */
    fun finishDrag(): DropTarget? {
        val current = drag ?: return null
        val target = dropTarget
        drag = null
        if (target == null) return null
        val to = paneById(target.paneId) ?: return null
        moveItem(current.file, current.sourcePane, to, target.direction)
        return target
    }

    fun cancelDrag() {
        drag = null
    }

    // ---- Lifecycle -------------------------------------------------------------

    /**
     * Back to one empty pane — what leaving a project does, after every
     * tab is closed. The reopen stacks and histories go with the panes.
     */
    fun reset() {
        for (pane in panes) {
            for (index in pane.files.tabs.indices.reversed()) pane.files.close(index)
            pane.files.clearClosedHistory()
        }
        created.clear()
        bounds.clear()
        zoomedPaneId = null
        drag = null
        root = newPane()
        activeId = (root as Pane).id
    }

    /** The tree as data — see [PaneLayout]. */
    fun layout(): PaneLayout = layoutOf(root)

    /**
     * Put the tree back the shape [layout] describes — Zed's
     * `SerializedPaneGroup::deserialize` (workspace/src/persistence/model.rs),
     * which walks the saved group and builds a pane per leaf.
     *
     * Everything that was open goes first: this is a workspace being *made*,
     * not merged into. The panes come back empty, because a tab is a buffer
     * the caller has to open; they are returned **in tree order — the same
     * order [layout] lists its own leaves** — so the caller can fill each
     * from the leaf it came from. The leaf marked active becomes the active
     * pane, and with none marked it is the first, which is what
     * [PaneGroupState.active] falls back to anyway.
     */
    fun restore(layout: PaneLayout): List<Pane> {
        reset()
        val panes = ArrayList<Pane>()
        // `reset` left exactly one fresh pane behind; the first leaf takes
        // it rather than adding a second and orphaning it.
        var reuse: Pane? = root as? Pane
        fun build(node: PaneLayout): PaneMember = when (node) {
            is PaneLayout.Leaf -> {
                val pane = reuse?.also { reuse = null } ?: newPane()
                panes.add(pane)
                pane
            }
            is PaneLayout.Split -> PaneAxisNode(
                node.axis,
                node.children.map(::build),
                node.flexes,
            )
        }
        val rebuilt = build(layout)
        // An axis with no leaves under it at all — which only a hand-written
        // document can be — would leave a tree with no pane in it, and every
        // reader of `active` assumes there is one. The empty workspace
        // `reset` already made is the answer.
        if (panes.isEmpty()) return listOf(root as Pane)
        // A layout of one leaf and nothing else leaves the pane `reset` made
        // in place; anything else replaces the root outright.
        root = rebuilt
        val leaves = leavesOf(layout)
        val activeIndex = leaves.indexOfFirst { it.isActive }.takeIf { it >= 0 } ?: 0
        activeId = (panes.getOrNull(activeIndex) ?: panes.first()).id
        return panes
    }

    private fun leavesOf(layout: PaneLayout): List<PaneLayout.Leaf> = when (layout) {
        is PaneLayout.Leaf -> listOf(layout)
        is PaneLayout.Split -> layout.children.flatMap(::leavesOf)
    }

    private fun layoutOf(member: PaneMember): PaneLayout = when (member) {
        is Pane -> PaneLayout.Leaf(
            paths = member.files.tabs.map { it.path },
            activeIndex = member.files.activeIndex,
            isActive = member.id == activeId,
        )
        is PaneAxisNode -> PaneLayout.Split(
            axis = member.axis,
            children = member.members.map(::layoutOf),
            flexes = member.flexes.toList(),
        )
    }

    private fun newPane(): Pane {
        val pane = Pane(nextId++)
        created.add(pane)
        pane.files.onRelease = { file -> if (!isSharedElsewhere(file)) file.session?.close() }
        pane.files.isSharedElsewhere = ::isSharedElsewhere
        // A multibuffer's files are the workspace's, not one strip's: a split
        // may be showing one of them, and closing the multibuffer must not
        // take that buffer with it.
        pane.files.heldTabs = { allTabs }
        pane.files.onEmptied = { pruneIfEmpty(pane) }
        return pane
    }

    private fun collect(member: PaneMember, into: MutableList<Pane>) {
        when (member) {
            is Pane -> into.add(member)
            is PaneAxisNode -> member.members.forEach { collect(it, into) }
        }
    }

    private class PaneBounds(val rect: Rect, val tabBarBottom: Float)
}
