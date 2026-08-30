package to.eyed.seeker.code.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.AppSettings
import to.eyed.seeker.code.core.DockSide

/**
 * A panel that lives in a dock, and what the chrome needs to know about it.
 *
 * Zed's four dockable things minus the ones this app has no version of. Search
 * and the preview are *pane items* in Zed rather than panels; here they are
 * panels, because a phone has no room for a second editor pane and a dock is
 * the shape that works — so they get dock settings of their own name rather
 * than borrowing one of Zed's.
 */
enum class WorkspacePanel(
    /** The key under which its placement lives in settings.json. */
    val settingsKey: String,
    /** What the settings screen and the tooltip call it. */
    val title: String,
    val icon: Int,
) {
    Project("project_panel", "Project panel", R.drawable.ic_ui_file_tree),
    /**
     * The active file's symbol tree — Zed's `outline_panel`, on Zed's own
     * default side (`"dock": "right"`, assets/settings/default.json:957).
     */
    Outline("outline_panel", "Outline panel", R.drawable.ic_ui_hash),
    Git("git_panel", "Git panel", R.drawable.ic_ui_git_branch),
    Search("project_search", "Project search", R.drawable.ic_ui_magnifying_glass),
    Preview("preview", "Preview", R.drawable.ic_ui_eye),
    Agent("agent_panel", "Agent panel", R.drawable.ic_ui_ai_zed);

    /** Which side this panel is docked on right now. */
    fun sideIn(settings: AppSettings): DockSide = settings.panel(settingsKey).dock

    fun widthIn(settings: AppSettings): Dp = settings.panel(settingsKey).defaultWidth.dp
}

/**
 * Which panel each dock is showing, and how wide it is.
 *
 * Zed's rule, and the one the user asked for: **one panel at a time per
 * dock**, and the two docks are independent. Opening git while search is up on
 * the same side replaces it; opening it while the tree is up on the *other*
 * side leaves the tree alone, because closing something on the opposite edge
 * of the screen would be answering a question nobody asked.
 *
 * Widths live here rather than inside each panel so that dragging the edge
 * resizes *the dock* — the space — rather than one panel's idea of itself,
 * which is what made the width jump every time the panel in it changed.
 */
class DockLayout {
    var left by mutableStateOf<WorkspacePanel?>(null)
        private set
    var right by mutableStateOf<WorkspacePanel?>(null)
        private set

    /** Null until a panel opens and seeds it from that panel's setting. */
    var leftWidth by mutableStateOf<Dp?>(null)
    var rightWidth by mutableStateOf<Dp?>(null)

    /**
     * Which dock was opened most recently.
     *
     * Only consulted when both are open and the screen cannot hold both: the
     * more recent act is the more deliberate one, which is the same rule the
     * search panel already used against the terminal.
     */
    var lastOpened by mutableStateOf(DockSide.Left)
        private set

    fun active(side: DockSide): WorkspacePanel? =
        if (side == DockSide.Left) left else right

    fun width(side: DockSide): Dp? = if (side == DockSide.Left) leftWidth else rightWidth

    fun setWidth(side: DockSide, width: Dp) {
        if (side == DockSide.Left) leftWidth = width else rightWidth = width
    }

    /** Whether [panel] is the one its own dock is showing. */
    fun isOpen(panel: WorkspacePanel, settings: AppSettings): Boolean {
        val side = panel.sideIn(settings)
        return side != DockSide.Hidden && active(side) == panel
    }

    /**
     * Bring [panel]'s dock to the front when the screen cannot show both.
     *
     * A dock that is open but *not drawn* — the loser of a tight screen — has
     * a button that says "open" and nothing on screen. Pressing it has to show
     * it, not close it, which is what [toggle] did: it saw the panel as active
     * and shut it, so the first press did nothing visible and the second one
     * finally worked.
     */
    fun raise(panel: WorkspacePanel, settings: AppSettings) {
        val side = panel.sideIn(settings)
        if (side == DockSide.Hidden || active(side) != panel) return
        lastOpened = side
    }

    private fun show(side: DockSide, panel: WorkspacePanel?) {
        if (side == DockSide.Left) left = panel else right = panel
    }

    /**
     * Show [panel], seeding its dock's width the first time. Returns false when
     * it was already showing — the caller decides whether that means "close it"
     * or "put the keyboard back in it", which is the difference between a
     * button press and a chord. A panel whose dock is `"hidden"` never opens:
     * hidden means switched off, not "somewhere else".
     */
    fun open(panel: WorkspacePanel, settings: AppSettings): Boolean {
        val side = panel.sideIn(settings)
        if (side == DockSide.Hidden) return false
        if (active(side) == panel) return false
        if (width(side) == null) setWidth(side, panel.widthIn(settings))
        show(side, panel)
        lastOpened = side
        return true
    }

    fun close(panel: WorkspacePanel, settings: AppSettings) {
        val side = panel.sideIn(settings)
        if (side == DockSide.Hidden) return
        if (active(side) == panel) show(side, null)
    }

    fun closeDock(side: DockSide) = show(side, null)

    /**
     * Put a saved dock state back — Zed's `DockData` (which panel is active
     * on each side, how wide the dock is, whether it is zoomed) coming out
     * of the workspace database.
     *
     * The panels are named by settings key rather than by enum, because that
     * is what the session document holds and what settings.json calls them.
     * A key this build does not know is nothing, and [reconcile] runs
     * afterwards so a panel that has been moved to the other side since it
     * was saved lands where settings now say it lives.
     */
    fun restore(
        settings: AppSettings,
        leftPanel: String,
        leftWidth: Dp?,
        rightPanel: String,
        rightWidth: Dp?,
        lastOpenedSide: DockSide,
    ) {
        left = WorkspacePanel.entries.firstOrNull { it.settingsKey == leftPanel }
        right = WorkspacePanel.entries.firstOrNull { it.settingsKey == rightPanel }
        if (leftWidth != null && leftWidth > 0.dp) this.leftWidth = leftWidth
        if (rightWidth != null && rightWidth > 0.dp) this.rightWidth = rightWidth
        lastOpened = lastOpenedSide
        reconcile(settings)
        // A dock that came back open but whose width was never written gets
        // its panel's own, exactly as [open] would have seeded it.
        if (left != null && width(DockSide.Left) == null) {
            setWidth(DockSide.Left, left!!.widthIn(settings))
        }
        if (right != null && width(DockSide.Right) == null) {
            setWidth(DockSide.Right, right!!.widthIn(settings))
        }
    }

    /** Press: open it, or close it if it was already the one showing. */
    fun toggle(panel: WorkspacePanel, settings: AppSettings): Boolean =
        if (open(panel, settings)) true else { close(panel, settings); false }

    /**
     * Follow a panel that has been moved to the other side in settings.
     *
     * Without this, moving the open panel across leaves it drawn on the side it
     * no longer belongs to until it is closed — and the button for it appears
     * on the *new* side, so the two disagree.
     */
    fun reconcile(settings: AppSettings) {
        // A panel whose dock just became `"hidden"` is closed, not moved:
        // hiding an open panel from settings must take it off the screen, or
        // the row would look like it did nothing.
        if (left?.sideIn(settings) == DockSide.Hidden) left = null
        if (right?.sideIn(settings) == DockSide.Hidden) right = null
        val movedFromLeft = left?.takeIf { it.sideIn(settings) != DockSide.Left }
        val movedFromRight = right?.takeIf { it.sideIn(settings) != DockSide.Right }
        if (movedFromLeft == null && movedFromRight == null) return
        // Both sides are decided before either is written: swapping the two
        // panels at once — one hand-edit of settings.json does it — used to
        // drop whichever moved first, because the second assignment
        // overwrote it.
        val nextLeft = movedFromRight ?: left.takeIf { movedFromLeft == null }
        val nextRight = movedFromLeft ?: right.takeIf { movedFromRight == null }
        left = nextLeft
        right = nextRight
        if (movedFromRight != null) leftWidth = movedFromRight.widthIn(settings)
        if (movedFromLeft != null) rightWidth = movedFromLeft.widthIn(settings)
    }
}

/** What the work area actually draws, once the widths have been argued out. */
data class DockPlan(
    /** Zero when the left dock is not drawn at all. */
    val left: Dp,
    val right: Dp,
    /** A dock drawn over the whole work area, editor and all. */
    val fullScreen: DockSide?,
) {
    fun widthOf(side: DockSide): Dp = if (side == DockSide.Left) left else right

    fun draws(side: DockSide): Boolean = fullScreen == side || widthOf(side) > 0.dp
}

/**
 * How much room each dock gets — the whole layout argument, in one pure
 * function, because it is the part that keeps going wrong.
 *
 * The rules, in order:
 *
 * 1. A dock is never narrower than [minDock] and never leaves the editor
 *    narrower than [minEditor].
 * 2. Two docks on **opposite sides never close each other** — that is the
 *    point of having two — so when both are open and their preferred widths
 *    do not fit, they *shrink* to share what is there, down to [minDock].
 * 3. Only when even two floors will not fit does the most recently opened one
 *    win, and the other waits — not closed, so it returns when there is room.
 * 4. A single dock that still leaves no editor takes the whole work area,
 *    which is what every phone does.
 */
fun planDocks(
    window: Dp,
    leftWanted: Dp?,
    rightWanted: Dp?,
    lastOpened: DockSide,
    minEditor: Dp,
    minDock: Dp,
    /** False on a compact screen, where a dock always takes the work area. */
    canSplit: Boolean = true,
): DockPlan {
    fun clamp(width: Dp): Dp = width.coerceAtLeast(minDock)
    val left = leftWanted?.let(::clamp)
    val right = rightWanted?.let(::clamp)
    if (left == null && right == null) return DockPlan(0.dp, 0.dp, null)

    if (canSplit && left != null && right != null) {
        val forDocks = window - minEditor
        if (left + right <= forDocks) return DockPlan(left, right, null)
        // Share what there is, in proportion to what each asked for, and never
        // below the floor. A tree and a git panel on a foldable land here.
        if (minDock * 2 <= forDocks) {
            val scale = forDocks / (left + right)
            var shrunkLeft = (left * scale).coerceAtLeast(minDock)
            var shrunkRight = (right * scale).coerceAtLeast(minDock)
            // Raising one to the floor can take the pair back over; the other
            // gives up the difference, since it is the one with room to.
            val over = shrunkLeft + shrunkRight - forDocks
            if (over > 0.dp) {
                if (shrunkLeft > shrunkRight) {
                    shrunkLeft = (shrunkLeft - over).coerceAtLeast(minDock)
                } else {
                    shrunkRight = (shrunkRight - over).coerceAtLeast(minDock)
                }
            }
            return DockPlan(shrunkLeft, shrunkRight, null)
        }
    }

    // One of them, either because only one is open or because two floors and
    // an editor will not fit on this screen at all.
    val side = when {
        left == null -> DockSide.Right
        right == null -> DockSide.Left
        else -> lastOpened
    }
    val width = if (side == DockSide.Left) left!! else right!!
    if (!canSplit || window - width < minEditor) {
        return DockPlan(0.dp, 0.dp, side)
    }
    return if (side == DockSide.Left) DockPlan(width, 0.dp, null) else DockPlan(0.dp, width, null)
}
