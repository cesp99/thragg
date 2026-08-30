package to.eyed.seeker.code.ui.workspace

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.core.AppSettings
import to.eyed.seeker.code.core.DockSide
import to.eyed.seeker.code.core.SessionAxis
import to.eyed.seeker.code.core.SessionDock
import to.eyed.seeker.code.core.SessionDocks
import to.eyed.seeker.code.core.SessionItem
import to.eyed.seeker.code.core.SessionItemKind
import to.eyed.seeker.code.core.SessionNavEntry
import to.eyed.seeker.code.core.SessionNavHistory
import to.eyed.seeker.code.core.SessionPane
import to.eyed.seeker.code.core.SessionSelection
import to.eyed.seeker.code.core.SessionTerminal
import to.eyed.seeker.code.core.SessionTerminalDock
import to.eyed.seeker.code.core.WorkspaceSession
import to.eyed.seeker.code.terminal.TerminalPanelState
import to.eyed.seeker.code.terminal.TerminalSessionHost
import to.eyed.seeker.code.ui.editor.Caret

/**
 * Turning the live workspace into a session document, and back.
 *
 * The document itself and every rule about what survives a relaunch live
 * elsewhere — [WorkspaceSession] is the value, and `engine/src/session.rs`
 * writes it, reads it and decides what is still true (a file that has gone
 * is dropped, a caret past the end of a file is clamped, a corrupt document
 * is discarded). This file is the two functions that stand between that and
 * Compose: [captureSession] reads the workspace, [applySession] rebuilds it.
 *
 * Zed's equivalents are `Workspace::serialize_workspace` and
 * `Workspace::load_workspace` (workspace/src/workspace.rs), which walk the
 * pane group the same way and open the items of each pane in order.
 */

/**
 * The tab keys that are not files: a diff, the commit graph, the
 * diagnostics, the agent review, a language-server log, a **multibuffer**.
 * Zed writes an item's kind and rebuilds the view; ours are views of live
 * state — a patch against a HEAD that has moved, a thread that has ended, a
 * search whose hits have — and a stale one is a lie rather than a restore.
 * [OpenFile.isReopenable] is the same question asked by Ctrl+Shift+T, and
 * answered the same way; a multibuffer answers no there for the same reason
 * it answers no here, so nothing extra is asked of it.
 */
private fun OpenFile.isPersistable(): Boolean =
    isReopenable && !path.contains("://") && !path.startsWith("/")

/**
 * The workspace as a document: the pane tree with each pane's tabs, carets,
 * scroll and jump list; the docks; the terminal tabs.
 *
 * Cheap enough to run on every caret move — it allocates a few dozen small
 * objects and touches neither the engine nor the disk — which is what lets
 * the workspace watch it as snapshot state and write it out on a debounce.
 */
fun captureSession(
    root: String,
    panes: PaneGroupState,
    docks: DockLayout,
    terminals: List<TerminalSessionHost>,
    terminalOpen: Boolean,
    terminalHeight: Dp,
): WorkspaceSession = WorkspaceSession(
    root = root,
    panes = captureMember(panes, panes.root),
    docks = SessionDocks(
        left = SessionDock(
            panel = docks.left?.settingsKey.orEmpty(),
            width = docks.leftWidth?.value ?: 0f,
        ),
        right = SessionDock(
            panel = docks.right?.settingsKey.orEmpty(),
            width = docks.rightWidth?.value ?: 0f,
        ),
        lastOpenedRight = docks.lastOpened == DockSide.Right,
        terminal = SessionTerminalDock(open = terminalOpen, height = terminalHeight.value),
    ),
    terminals = terminals.map { SessionTerminal(title = it.title, cwd = it.cwd) },
    zoomed = panes.isZoomed,
)

private fun captureMember(panes: PaneGroupState, member: PaneMember): SessionPane = when (member) {
    is Pane -> capturePane(panes, member)
    is PaneAxisNode -> SessionPane.Split(
        axis = if (member.axis == PaneAxis.Horizontal) SessionAxis.Horizontal else SessionAxis.Vertical,
        children = member.memberList.map { captureMember(panes, it) },
        flexes = member.flexList,
    )
}

private fun capturePane(panes: PaneGroupState, pane: Pane): SessionPane.Leaf {
    val files = pane.files
    val kept = files.tabs.filter { it.isPersistable() }
    val active = files.active?.takeIf { it.isPersistable() }
    val (back, forward) = files.navigationSnapshot()
    return SessionPane.Leaf(
        items = kept.map(::captureItem),
        // By path, not by index: the unpersistable tabs have just been left
        // out, so the live index means nothing here.
        activeIndex = active?.let { current -> kept.indexOfFirst { it === current } } ?: -1,
        active = pane === panes.active,
        history = SessionNavHistory(
            back = back.filter { it.isReopenable }.map(::captureNavEntry),
            forward = forward.filter { it.isReopenable }.map(::captureNavEntry),
        ),
    )
}

private fun captureItem(file: OpenFile): SessionItem = SessionItem(
    path = file.path,
    kind = if (file.media != null) SessionItemKind.Media else SessionItemKind.Text,
    pinned = file.isPinned,
    // A preview tab comes back a preview: the pane had one provisional slot
    // when it was left and it has one when it comes back, so the next single
    // click replaces the same tab it would have replaced.
    preview = file.isPreview,
    scroll = file.editor?.scrollY ?: 0f,
    selections = file.editor?.caretsInOrder().orEmpty().map {
        SessionSelection(it.anchorRow, it.anchorCol, it.headRow, it.headCol)
    },
)

private fun captureNavEntry(entry: NavEntry): SessionNavEntry =
    SessionNavEntry(entry.path, entry.row, entry.col, entry.scroll)

/**
 * Put a document back on screen.
 *
 * The order is Zed's `load_workspace`: the pane tree first, then each pane's
 * items in the order they were listed, then the active item, then the docks.
 * Opening is sequential — [openInto] is awaited once per tab — because tab
 * order is the point: opening them all at once would put them back in
 * whichever order the buffers happened to load.
 *
 * Nothing here validates: the engine has already dropped the tabs whose
 * files are gone and clamped the carets that pointed past the end of one.
 * What is left is the part that needs the live objects.
 */
suspend fun applySession(
    session: WorkspaceSession,
    panes: PaneGroupState,
    docks: DockLayout,
    settings: AppSettings,
    terminals: TerminalPanelState,
    onTerminalHeight: (Dp) -> Unit,
    /**
     * Open one tab in one pane, or null when it could not be opened.
     * [preview] puts it back in the pane's provisional slot.
     */
    openInto: suspend (path: String, pane: Pane, preview: Boolean) -> OpenFile?,
) {
    val leaves = leavesOf(session.panes)
    val built = panes.restore(layoutOf(session.panes))
    for ((pane, leaf) in built.zip(leaves)) {
        for (item in leaf.items) {
            val opened = openInto(item.path, pane, item.preview) ?: continue
            opened.isPinned = item.pinned
            // Pinning wins, as it does live: a pinned tab is never the
            // pane's preview slot.
            if (opened.isPinned) pane.files.promote(opened.path)
            restoreEditorState(opened, item)
        }
        leaf.activeIndex.takeIf { it in pane.files.tabs.indices }?.let(pane.files::select)
        // Last, because opening and selecting tabs is itself navigation: it
        // pushes departures onto the very list being restored.
        pane.files.restoreNavigation(
            back = leaf.history.back.map(::liveNavEntry),
            forward = leaf.history.forward.map(::liveNavEntry),
        )
    }
    // A pane whose every tab refused to open would be an empty pane in a
    // split, which is not what was saved. Zed removes an emptied pane the
    // same way (`Event::Remove`, pane.rs:2196-2204).
    for (pane in panes.panes) {
        if (pane.files.tabs.isEmpty() && panes.panes.size > 1) panes.remove(pane)
    }
    if (session.zoomed) panes.toggleZoom()

    docks.restore(
        settings = settings,
        leftPanel = session.docks.left.panel,
        leftWidth = session.docks.left.width.takeIf { it > 0f }?.dp,
        rightPanel = session.docks.right.panel,
        rightWidth = session.docks.right.width.takeIf { it > 0f }?.dp,
        lastOpenedSide = if (session.docks.lastOpenedRight) DockSide.Right else DockSide.Left,
    )
    session.docks.terminal.height.takeIf { it > 0f }?.let { onTerminalHeight(it.dp) }

    // Terminals are processes and died with the app, so these are fresh
    // shells in the directories the old ones stood in — and only when the
    // dock was open, because starting shells nobody asked to see costs a
    // process tree and a foreground-service notification for nothing.
    if (session.docks.terminal.open && terminals.sessions.isEmpty()) {
        for (terminal in session.terminals) {
            terminals.newSession(terminal.cwd)
            if (terminal.title.isNotBlank()) {
                terminals.rename(terminals.sessions.lastIndex, terminal.title)
            }
        }
        terminals.select(0)
    }
}

/**
 * The carets and the scroll, put back.
 *
 * Two frames, for the reason [NavEntry.restoreIn] waits them: a tab that has
 * just been opened has no measured viewport, so the scroll it is given would
 * be clamped against a zero-height pane. Setting the carets first and the
 * scroll second is deliberate too — `setCarets` scrolls the primary caret
 * into view, and the saved scroll is the one the user actually left behind.
 */
private suspend fun restoreEditorState(file: OpenFile, item: SessionItem) {
    val editor = file.editor ?: return
    val carets = item.selections.map { Caret(it.anchorRow, it.anchorCol, it.headRow, it.headCol) }
    if (carets.isNotEmpty()) {
        // The last in document order stands in for the primary: which of a
        // set was primary is not something a document can say, and Zed's
        // `SerializedSelection` does not record it either.
        editor.setCarets(carets, carets.last(), notify = false)
    }
    withFrameNanos { }
    withFrameNanos { }
    editor.scrollToY(item.scroll)
}

private fun liveNavEntry(entry: SessionNavEntry): NavEntry =
    NavEntry(entry.path, entry.row, entry.col, entry.scroll)

/** The document's tree as the pane group's own shape. */
private fun layoutOf(pane: SessionPane): PaneLayout = when (pane) {
    is SessionPane.Leaf -> PaneLayout.Leaf(
        paths = pane.items.map { it.path },
        activeIndex = pane.activeIndex,
        isActive = pane.active,
    )
    is SessionPane.Split -> PaneLayout.Split(
        axis = if (pane.axis == SessionAxis.Horizontal) PaneAxis.Horizontal else PaneAxis.Vertical,
        children = pane.children.map(::layoutOf),
        flexes = pane.flexes,
    )
}

/**
 * The document's leaves in tree order — the order [layoutOf] lists its own,
 * and therefore the order [PaneGroupState.restore] returns its panes in.
 */
private fun leavesOf(pane: SessionPane): List<SessionPane.Leaf> = when (pane) {
    is SessionPane.Leaf -> listOf(pane)
    is SessionPane.Split -> pane.children.flatMap(::leavesOf)
}
