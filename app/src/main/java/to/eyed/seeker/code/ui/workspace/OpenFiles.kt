package to.eyed.seeker.code.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import to.eyed.seeker.code.core.ActivateOnClose
import to.eyed.seeker.code.core.BufferEncoding
import to.eyed.seeker.code.core.BufferSession
import to.eyed.seeker.code.core.LineEnding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.core.LanguageSettings
import to.eyed.seeker.code.core.MultiBufferSession
import to.eyed.seeker.code.ui.editor.EditorState
import to.eyed.seeker.code.ui.git.DiffTarget
import to.eyed.seeker.code.ui.media.MediaKind

/** How many closed files Ctrl+Shift+T can walk back through. */
private const val REOPEN_HISTORY = 24

/** Zed's `MAX_NAVIGATION_HISTORY_LEN` (workspace/src/pane.rs:322). */
private const val MAX_NAVIGATION_HISTORY = 1024

/**
 * One place the user has been — what GoBack returns to.
 *
 * A path and a position, not a tab reference: Zed's entries hold a weak item
 * handle exactly so a closed item doesn't keep the history alive, and falls
 * back to the item's path to reopen it (workspace.rs:2846-2860). Ours are a
 * path from the start, because closing a tab here releases the engine buffer
 * and reopening always goes back through the workspace's own open path.
 */
class NavEntry(
    /** Project-relative path — the same key the tab strip uses. */
    val path: String,
    /** Caret, 0-based — Zed pushes the cursor row with each entry (pane.rs:4664-4678). */
    val row: Int = 0,
    val col: Int = 0,
    /** [EditorState.scrollY] at departure — the vertical anchor Zed's `NavigationData` keeps. */
    val scroll: Float = 0f,
    /**
     * Whether the path can be opened again once its tab is gone. A diff or
     * the graph is opened by a *view*, not by a path the file opener knows —
     * the same reason [OpenFile.isReopenable] exists — so a stale entry for
     * one is discarded rather than returned, mirroring how Zed's loop skips
     * entries it has no path info for (workspace.rs:2845-2853).
     */
    val isReopenable: Boolean = true,
) {
    /**
     * Put the caret and the view back where this entry says they were.
     *
     * Suspending for the same reason `revealProjectSearchMatch` is: a file
     * this navigation has just reopened has no measured viewport yet, so the
     * scroll restore waits two frames — one to compose the pane, one to
     * measure it — before `ensureCursorVisible` clamps everything into range.
     * Clamped against the file as it is *now*: the entry may describe a file
     * that has shrunk since, and Zed treats stale anchors the same way —
     * resolve what still resolves, never refuse.
     */
    suspend fun restoreIn(file: OpenFile) {
        val editor = file.editor ?: return
        val targetRow = row.coerceIn(0, (editor.lineCount - 1).coerceAtLeast(0))
        val targetCol = col.coerceIn(0, editor.line(targetRow).length)
        editor.selectRange(
            EditorState.SelectionRange(targetRow, targetCol, targetRow, targetCol)
        )
        withFrameNanos { }
        withFrameNanos { }
        editor.scrollToY(scroll)
        editor.ensureCursorVisible()
    }
}

/**
 * The jump list behind GoBack/GoForward — Zed's `NavHistory`
 * (workspace/src/pane.rs:4707-4860), reduced to the two stacks.
 *
 * The rules are Zed's own, kept testable in one place:
 * - a normal push lands on the backward stack and **clears the forward
 *   stack** — new navigation after a GoBack throws the "forward" branch away,
 *   as a browser does (pane.rs:4801-4815);
 * - every push first drops older entries at the same place — same path, same
 *   row, Zed's `is_same_location` (pane.rs:4795-4797) — so hopping between
 *   two files doesn't fill the list with copies;
 * - each stack is capped at [MAX_NAVIGATION_HISTORY], oldest dropped
 *   (pane.rs:4806-4808);
 * - going back pushes the departing location onto the forward stack, going
 *   forward pushes it onto the backward stack, and neither clears anything
 *   (pane.rs:4817-4846).
 *
 * The stacks are snapshot state so the tab bar's arrow buttons grey and
 * ungrey themselves as the history changes, the way Zed's do
 * (pane.rs:3407-3452).
 */
class NavHistory {
    private val backward = mutableStateListOf<NavEntry>()
    private val forward = mutableStateListOf<NavEntry>()

    val canGoBack: Boolean get() = backward.isNotEmpty()
    val canGoForward: Boolean get() = forward.isNotEmpty()

    /** A place just departed, in Zed's `NavigationMode::Normal` (pane.rs:4801-4815). */
    fun push(entry: NavEntry) {
        pushOnto(backward, entry)
        forward.clear()
    }

    /**
     * Pop back to the nearest [usable] entry, discarding the dead ones on
     * the way — Zed's `navigate_history_impl` loops exactly like this,
     * popping until an entry actually navigates (workspace.rs:2822-2854).
     * [from] is where the user is now; it goes onto the forward stack only
     * when there is somewhere to go back *to*.
     */
    fun back(from: NavEntry?, usable: (NavEntry) -> Boolean): NavEntry? =
        travel(source = backward, opposite = forward, from = from, usable = usable)

    /** The mirror image, replaying what [back] set aside (pane.rs:4831-4846). */
    fun forward(from: NavEntry?, usable: (NavEntry) -> Boolean): NavEntry? =
        travel(source = forward, opposite = backward, from = from, usable = usable)

    /** Put back an entry whose navigation could not be performed. */
    fun restore(entry: NavEntry, toBackward: Boolean) {
        val stack = if (toBackward) backward else forward
        val opposite = if (toBackward) forward else backward
        // The travel that spent it pushed the departure onto the opposite
        // stack; unwind that too, or a failed GoBack would leave a forward
        // entry pointing at a move that never happened.
        opposite.removeLastOrNull()
        stack.add(entry)
    }

    /** Forget everything — the paths belong to a project being left. */
    fun clear() {
        backward.clear()
        forward.clear()
    }

    /** The two stacks as plain lists, oldest first — what a session writes down. */
    fun snapshot(): Pair<List<NavEntry>, List<NavEntry>> =
        backward.toList() to forward.toList()

    /**
     * Replace both stacks — a session being restored. Bounded like a push,
     * so a hand-edited document cannot hand the pane a million entries.
     */
    fun reload(back: List<NavEntry>, forward: List<NavEntry>) {
        backward.clear()
        this.forward.clear()
        backward.addAll(back.takeLast(MAX_NAVIGATION_HISTORY))
        this.forward.addAll(forward.takeLast(MAX_NAVIGATION_HISTORY))
    }

    private fun travel(
        source: MutableList<NavEntry>,
        opposite: MutableList<NavEntry>,
        from: NavEntry?,
        usable: (NavEntry) -> Boolean,
    ): NavEntry? {
        while (source.isNotEmpty()) {
            val entry = source.removeAt(source.lastIndex)
            // An entry for the place the user is already standing navigates
            // nowhere; Zed's loop notices `navigated` stayed false and keeps
            // popping (workspace.rs:2837-2843).
            val standingStill = from != null && entry.path == from.path && entry.row == from.row
            if (standingStill || !usable(entry)) continue
            if (from != null) pushOnto(opposite, from)
            return entry
        }
        return null
    }

    private fun pushOnto(stack: MutableList<NavEntry>, entry: NavEntry) {
        stack.removeAll { it.path == entry.path && it.row == entry.row }
        if (stack.size >= MAX_NAVIGATION_HISTORY) stack.removeAt(0)
        stack.add(entry)
    }
}

/**
 * One open editor tab.
 *
 * The engine is the authority on dirty and on-disk state, but those are plain
 * JNI getters, not observable — so the flags are mirrored here as snapshot
 * state and refreshed by [refreshStatus]. Reading them during composition
 * would be a JNI call in the draw path; this way the tab strip redraws only
 * when something actually changed.
 */
class OpenFile(
    /** Project-relative path — what the panel and tab strip display. */
    val path: String,
    /**
     * The text editor, or null for a tab that is not text at all.
     *
     * A picture has no buffer: nothing to parse, nothing to save, nothing to
     * be dirty, and closing it cannot lose work. Everything that assumes an
     * editor has to ask first, which is the point of making it nullable
     * rather than inventing an empty one.
     */
    val editor: EditorState?,
    /** What this file is, when it is not text. */
    val media: MediaKind? = null,
    /**
     * A diff, when the tab is one. Like a picture, it has no buffer: it is a
     * view of what git says, and it cannot be edited or saved.
     */
    val diff: DiffTarget? = null,
    /** True for the commit graph, which is a view of the repository itself. */
    val graph: Boolean = false,
    /** True for project diagnostics, a view of what the servers published. */
    val diagnostics: Boolean = false,
    /**
     * True for the agent review — every file the active agent thread edited,
     * with Keep and Reject; a view of the conversation, not of a file.
     */
    val agentReview: Boolean = false,
    /**
     * The name of the language server whose log this tab shows — Zed's
     * `dev::OpenLanguageServerLogs` view; a view of the engine's ring, not
     * of a file.
     */
    val lspLogs: String? = null,
    /**
     * A multibuffer, when the tab is one — Zed's project search, references
     * and diagnostics all open as one (see core/MultiBufferSession.kt).
     *
     * It still has an [editor]: the engine composes the excerpts into one
     * ordinary buffer, so the pane that draws it is the ordinary pane and the
     * edits it makes are routed back to the files inside the engine. What the
     * handle adds is the headers, the row map and the save-all.
     */
    val multibuffer: MultiBufferSession? = null,
    /** The file on disk, for a tab the engine never opened. */
    val absolutePath: String? = null,
) {
    val session: BufferSession? get() = editor?.session

    /**
     * A tab whose buffer refuses edits — the default settings, shown as
     * documentation. It is never dirty and never saved.
     */
    val isReadOnly: Boolean get() = editor?.isReadOnly == true

    /**
     * The settings in force for this buffer — `tab_size`, `hard_tabs`,
     * `soft_wrap`, `format_on_save` and the rest — as the engine resolves
     * them from the user file, the project's `.zed/settings.json` and the
     * language's entry in each. Snapshot state, so the pane re-reads the
     * width of a tab the moment a settings write lands; refreshed by
     * [refreshLanguageSettings] and never during composition, because the
     * engine reads settings.json to answer.
     */
    var languageSettings: LanguageSettings by mutableStateOf(LanguageSettings())
        private set

    /**
     * Ask the engine again. Called when the tab opens, when settings.json is
     * written, and when the project's settings version moves. Off the main
     * thread for the file read; the state write lands back on it.
     */
    suspend fun refreshLanguageSettings() {
        val open = session ?: return
        val fresh = withContext(Dispatchers.IO) { open.languageSettings() }
        if (fresh != languageSettings) languageSettings = fresh
    }

    /**
     * Whether Ctrl+Shift+T could bring this back. A diff, the graph, the
     * diagnostics and a picture are opened by a *view*, not by a path the
     * file opener knows, and pushing their keys onto the reopen stack spent a
     * keypress on a file that cannot be opened — and skipped the real last
     * file.
     */
    val isReopenable: Boolean get() = diff == null && !graph && !diagnostics &&
        !agentReview && lspLogs == null && multibuffer == null

    val name: String = when {
        graph -> "Git graph"
        diagnostics -> "Diagnostics"
        agentReview -> "Review changes"
        lspLogs != null -> "LSP logs: $lspLogs"
        // "Search: needle", "References to foo", "Project diagnostics" — the
        // title Zed gives the multibuffer it opens.
        multibuffer != null -> multibuffer.title
        diff != null -> diff.title
        else -> path.substringAfterLast('/')
    }

    /**
     * A media tab has no buffer, so nothing in the engine is watching its
     * file. The one thing worth knowing is whether it is still there — the
     * pane itself watches for the contents changing.
     */
    private fun refreshMediaStatus(): Boolean {
        val disk = absolutePath ?: return false
        val deleted = !java.io.File(disk).exists()
        if (deleted == isDeleted) return false
        isDeleted = deleted
        return true
    }

    var isDirty by mutableStateOf(false)
        private set
    var hasDiskChange by mutableStateOf(false)
        private set
    var isDeleted by mutableStateOf(false)
        private set

    /**
     * Pinned tabs sit at the left of the strip and are left alone by the bulk
     * closes, as in Zed. Owned by [OpenFilesState], which also keeps the
     * pinned tabs together at the head of the list.
     */
    var isPinned by mutableStateOf(false)
        internal set

    /**
     * A *preview* tab — Zed's `preview_tabs` (workspace/src/pane.rs, the
     * `preview_item_id` slot).
     *
     * One per pane at most: a single click in the panel or the finder opens
     * the file here, and the next single click *replaces* it rather than
     * adding a tab, so browsing a project does not leave thirty tabs behind.
     * Its title is drawn in italics, which is the whole visual difference.
     *
     * Promoted to permanent by an edit or a double-click
     * (`Pane::set_preview_item_id` is cleared on both) — see
     * [OpenFilesState.promote].
     */
    var isPreview by mutableStateOf(false)
        internal set

    /**
     * Grammar the engine is highlighting with, for the status bar.
     *
     * Chosen from the file name when the buffer opens, and settable
     * afterwards from the language selector — Zed's `language_selector`
     * override, which changes the buffer and nothing on disk. State rather
     * than a plain `val` for exactly that: the bar and the syntax have to
     * follow the choice.
     */
    var language: String? by mutableStateOf(session?.language)
        internal set

    /**
     * The file's line ending and encoding, for the status bar. Mirrored like
     * the flags above and for the same reason; they move when the file is
     * reloaded, reinterpreted, or given a new shape to save in.
     */
    var lineEnding by mutableStateOf<LineEnding?>(null)
        private set
    var encoding by mutableStateOf<BufferEncoding?>(null)
        private set

    /** Whether anything changed, so callers can skip needless work. */
    fun refreshStatus(): Boolean {
        // A multibuffer's composed buffer has no file of its own; what is
        // dirty is the files behind it, which the engine counts.
        multibuffer?.let { open ->
            val dirty = open.isDirty
            if (dirty == isDirty) return false
            isDirty = dirty
            return true
        }
        val open = session ?: return refreshMediaStatus()
        val dirty = open.isDirty
        val disk = open.hasDiskChange
        val deleted = open.isFileDeleted
        val ending = open.lineEnding
        val encoded = open.encoding
        if (dirty == isDirty && disk == hasDiskChange && deleted == isDeleted &&
            ending == lineEnding && encoded == encoding
        ) {
            return false
        }
        isDirty = dirty
        hasDiskChange = disk
        isDeleted = deleted
        lineEnding = ending
        encoding = encoded
        return true
    }
}

/**
 * The set of open tabs and which one is showing.
 *
 * Opening a file already open selects its tab rather than adding a second —
 * matching the engine, which returns one buffer per path however many times
 * it is asked.
 *
 * Two rules come from Zed and are enforced here rather than in the strip:
 * **pinned tabs live at the head of the list**, so "pinned tabs sit on the
 * left" is a property of the model and not of the drawing; and **a tab with
 * unsaved edits is never closed without asking** — [requestClose] and its
 * siblings hand such tabs to [closeConfirmation] instead of dropping the
 * buffer, which is what the plain [close] would do.
 */
class OpenFilesState {
    private val _tabs = mutableStateListOf<OpenFile>()
    val tabs: List<OpenFile> get() = _tabs

    var activeIndex by mutableIntStateOf(-1)
        private set

    val active: OpenFile? get() = _tabs.getOrNull(activeIndex)

    /** Paths of tabs closed in this session, oldest first — Ctrl+Shift+T's stack. */
    private val closedPaths = mutableStateListOf<String>()

    /**
     * The jump list. Entries are recorded when the active tab *changes* — a
     * tab click, Ctrl+Tab, a file being opened — which is Zed's
     * "deactivated item pushes its position" (editor pushes on deactivate,
     * items.rs via `push_to_nav_history(.., is_deactivate=true, ..)`). Zed
     * also records large caret jumps inside one item
     * (`MIN_NAVIGATION_HISTORY_ROW_DELTA` = 10, editor.rs:295,
     * navigation.rs:1560-1566); that half waits until the editor can report
     * them — noted in the class doc rather than half-built here.
     */
    private val nav = NavHistory()

    /**
     * A path GoBack/GoForward has asked the workspace to reopen. The reopen
     * arrives later as an ordinary [open] call, and *that* open is the
     * navigation itself, not new travel — pushing it would clear the forward
     * stack and break the GoForward that should follow. Zed brackets the
     * open in `NavigationMode::GoingBack` for exactly this
     * (workspace.rs:2833-2835); this is the same bracket for an async open.
     */
    private var pendingNavPath: String? = null

    val canGoBack: Boolean get() = nav.canGoBack
    val canGoForward: Boolean get() = nav.canGoForward

    /** The jump list as data, oldest first — what a session document keeps. */
    fun navigationSnapshot(): Pair<List<NavEntry>, List<NavEntry>> = nav.snapshot()

    /** Put a saved jump list back into this pane. */
    fun restoreNavigation(back: List<NavEntry>, forward: List<NavEntry>) {
        nav.reload(back, forward)
    }

    /** Tabs a close request is still working through, head first. */
    private val closing = mutableStateListOf<OpenFile>()

    /**
     * Activation history: paths, oldest first, the most recent one last.
     *
     * This is what the Ctrl+Tab switcher walks and what
     * [ActivateOnClose.History] falls back to. Zed keeps the same list per
     * pane (`Pane::activation_history`, workspace/src/pane.rs) and the tab
     * switcher reads it in reverse (tab_switcher/src/tab_switcher.rs).
     */
    private val activationHistory = mutableStateListOf<String>()

    /**
     * Zed's `tabs.activate_on_close`, and `max_tabs`. Plain vars rather than
     * constructor arguments: the state outlives any one settings value, and
     * the workspace re-points them whenever settings.json changes.
     */
    var activateOnClose: ActivateOnClose = ActivateOnClose.History
    var maxTabs: Int? = null

    /** How many tabs at the head of the strip are pinned. */
    val pinnedCount: Int get() = _tabs.count { it.isPinned }

    /** Whether there is anything for "reopen closed tab" to reopen. */
    val hasClosedTabs: Boolean get() = closedPaths.isNotEmpty()

    /**
     * The unsaved tab a close is waiting on, or null when nothing is pending.
     *
     * One file at a time, as Zed asks: a prompt that names the file is the
     * only kind worth showing, and a list of five is not a decision anyone can
     * make. [confirmClose] answers for the head and carries on to the rest.
     */
    val closeConfirmation: OpenFile? get() = closing.firstOrNull()

    /**
     * How a closed tab lets go of its buffer. The default is the buffer's
     * own close; a pane in a [PaneGroupState] gets one that first asks
     * whether another pane still shows the same buffer — two views of one
     * file share one engine buffer, and only the last view out releases it.
     */
    internal var onRelease: (OpenFile) -> Unit = { it.session?.close() }

    /**
     * Whether the same buffer is showing in another pane, so a dirty tab
     * can close without asking: the edits are not lost, they are still on
     * screen next door — which is when Zed's `close_items` skips the prompt
     * too (pane.rs, `should_prompt_to_save` is false for an item whose
     * buffer another pane holds).
     */
    internal var isSharedElsewhere: (OpenFile) -> Boolean = { false }

    /**
     * Every tab the workspace still has, across panes — what a multibuffer's
     * close is told to keep, and what says a buffer is still excerpted
     * somewhere. This pane's own tabs until the pane group says otherwise.
     */
    internal var heldTabs: () -> List<OpenFile> = { _tabs }

    /**
     * The last tab has just closed. A pane group removes an emptied pane
     * that is not the last one, as Zed's `Event::Remove` does
     * (pane.rs:2196-2204); a lone tab list has nothing to do.
     */
    internal var onEmptied: (() -> Unit)? = null

    fun indexOfPath(path: String): Int = _tabs.indexOfFirst { it.path == path }

    /** The tabs in most-recently-used order, newest first — the switcher's list. */
    fun mruTabs(): List<OpenFile> =
        mruOrder(_tabs.map { it.path }, activationHistory).mapNotNull { path ->
            _tabs.firstOrNull { it.path == path }
        }

    fun select(index: Int) {
        if (index !in _tabs.indices || index == activeIndex) return
        recordDeparture()
        pendingNavPath = null
        activate(index)
    }

    /** Move [delta] tabs along, wrapping — what Ctrl+PageDown does. */
    fun selectRelative(delta: Int) {
        if (_tabs.isEmpty()) return
        val size = _tabs.size
        select(((activeIndex + delta) % size + size) % size)
    }

    /**
     * Add a tab (or select the existing one) and make it active.
     *
     * [preview] opens it as a *provisional* tab — Zed's `preview_tabs`. There
     * is at most one: an existing preview tab is closed and the new file takes
     * its place in the strip, which is what makes clicking through a directory
     * cost one tab rather than twenty. Opening a file that is already open
     * leaves its preview-ness alone in one direction only: a permanent tab is
     * never demoted, and a preview tab asked for permanently is promoted.
     */
    fun open(file: OpenFile, preview: Boolean = false) {
        // The open GoBack/GoForward asked for — the navigation landing, not
        // new travel, so it must not push (which would clear forward). Any
        // *other* open supersedes a pending one, exactly as any keypress
        // between GoBack and its async open would in Zed's synchronous world.
        val navigated = file.path == pendingNavPath
        pendingNavPath = null
        val existing = indexOfPath(file.path)
        if (existing >= 0) {
            if (!preview) _tabs[existing].isPreview = false
            if (existing != activeIndex) {
                if (!navigated) recordDeparture()
                activate(existing)
            }
            return
        }
        if (!navigated) recordDeparture()
        // Zed replaces the preview tab *in place*, so the new file appears
        // where the last previewed one was rather than jumping to the end.
        val replacing = if (preview) _tabs.indexOfFirst { it.isPreview } else -1
        if (replacing >= 0) {
            val old = _tabs[replacing]
            _tabs[replacing] = file
            activationHistory.remove(old.path)
            old.session?.close()
            if (old.isReopenable) rememberClosed(old.path)
            file.isPreview = true
            activate(replacing)
        } else {
            _tabs.add(file)
            file.isPreview = preview
            activate(_tabs.lastIndex)
        }
        closedPaths.remove(file.path)
        enforceMaxTabs()
    }

    /**
     * This tab is no longer provisional — Zed clears `preview_item_id` on the
     * first edit and on a double-click of the tab.
     */
    fun promote(path: String) {
        _tabs.firstOrNull { it.path == path }?.isPreview = false
    }

    /** Whether any tab is provisional right now. */
    val hasPreviewTab: Boolean get() = _tabs.any { it.isPreview }

    /**
     * Drag a tab to a new position — Zed's `Pane::move_item`.
     *
     * Clamped to the pinned/unpinned boundary rather than refused: a pinned
     * tab cannot be dragged past the last pinned one and an unpinned one
     * cannot be dragged in among them, because "pinned tabs sit on the left"
     * is a property of this list (see the class doc) and dropping one in the
     * middle would break it silently.
     */
    fun move(from: Int, to: Int) {
        if (from !in _tabs.indices || to !in _tabs.indices || from == to) return
        val file = _tabs[from]
        val pinned = pinnedCount
        val clamped = if (file.isPinned) {
            to.coerceIn(0, (pinned - 1).coerceAtLeast(0))
        } else {
            to.coerceIn(pinned, _tabs.lastIndex)
        }
        if (clamped == from) return
        val current = active
        _tabs.removeAt(from)
        _tabs.add(clamped, file)
        activeIndex = if (current == null) -1 else _tabs.indexOfFirst { it === current }
    }

    /**
     * Step back along the jump list — Zed's `pane::GoBack` (pane.rs:929-938).
     *
     * If the entry's tab is still open it becomes active here; either way the
     * entry is returned so the caller can restore its caret and scroll
     * ([NavEntry.restoreIn]) — or reopen the file first, through the same
     * open path Ctrl+Shift+T uses, when [indexOfPath] says it is gone. Null
     * when there is nowhere to go.
     */
    fun goBack(): NavEntry? = navigateHistory(back = true)

    /** Zed's `pane::GoForward` (pane.rs:940-950) — replays what [goBack] left. */
    fun goForward(): NavEntry? = navigateHistory(back = false)

    private fun navigateHistory(back: Boolean): NavEntry? {
        val from = active?.let(::locationOf)
        // Usable = still on the strip, or reopenable by path. A dead diff or
        // graph entry is skipped, as Zed skips entries with no path info
        // (workspace.rs:2845-2853).
        val usable = { entry: NavEntry -> indexOfPath(entry.path) >= 0 || entry.isReopenable }
        val entry = (if (back) nav.back(from, usable) else nav.forward(from, usable))
            ?: return null
        val index = indexOfPath(entry.path)
        if (index >= 0) {
            // Straight to the index, not [select]: navigating is what Zed
            // brackets in GoingBack/GoingForward mode so the activation it
            // causes doesn't record as new travel (workspace.rs:2833-2835).
            activate(index)
        } else {
            pendingNavPath = entry.path
        }
        return entry
    }

    /**
     * A navigation that could not land — the file would not open at all.
     *
     * Without this the entry is spent and the arrow it lit stays lit for a
     * move that never happened, and the [pendingNavPath] bracket stays armed
     * so the user's *next* open of that path would be mistaken for the
     * landing. Put the entry back where it came from and disarm.
     */
    fun navigationFailed(entry: NavEntry, wasBack: Boolean) {
        if (pendingNavPath == entry.path) pendingNavPath = null
        nav.restore(entry, toBackward = wasBack)
    }

    /** Where [file] is right now, as a history entry. */
    private fun locationOf(file: OpenFile) = NavEntry(
        path = file.path,
        row = file.editor?.cursorRow ?: 0,
        col = file.editor?.cursorCol ?: 0,
        scroll = file.editor?.scrollY ?: 0f,
        isReopenable = file.isReopenable,
    )

    /** The active tab is being left: remember where it was. */
    private fun recordDeparture() {
        val leaving = active ?: return
        nav.push(locationOf(leaving))
    }

    /**
     * Make [index] active and record it as the most recent — the one write
     * that must always happen together, since [activationHistory] is what both
     * the Ctrl+Tab switcher and `activate_on_close` read.
     */
    private fun activate(index: Int) {
        activeIndex = index
        val path = _tabs.getOrNull(index)?.path ?: return
        activationHistory.remove(path)
        activationHistory.add(path)
    }

    /**
     * Close a tab and release its engine buffer. Which tab takes over is
     * `tabs.activate_on_close`'s to say — see [activeAfterClose].
     *
     * Unconditional: unsaved edits go with it. Only callers that have already
     * asked — [requestClose] and friends — or that are tearing the workspace
     * down should use it.
     */
    fun close(index: Int) {
        val file = detach(index) ?: return
        // A multibuffer owns its composed buffer *and* the files it opened on
        // demand; the engine releases both, minus the ones the tabs that are
        // left still hold — it has no way to know those. "Left" is every
        // pane's, not this one's: a file this multibuffer opened may be the
        // tab a split is showing.
        val multibuffer = file.multibuffer
        when {
            multibuffer != null ->
                multibuffer.close(heldTabs().mapNotNull { it.session?.id }.toLongArray())
            // A file another tab's multibuffer excerpts stays open for it, as
            // one open in another pane does.
            isHeldByAMultibuffer(file) -> Unit
            else -> onRelease(file)
        }
        if (file.isReopenable) rememberClosed(file.path)
        if (_tabs.isEmpty()) onEmptied?.invoke()
    }

    /**
     * Take a tab off the strip *without* releasing its buffer — the half of
     * [close] a move between panes needs, since the tab is about to be
     * [adopt]ed by another list with its editor, caret and buffer intact.
     *
     * The successor is chosen exactly as after a close, and the departing
     * tab leaves [activationHistory] either way: a tab that has moved to
     * another pane is no more this pane's "one before" than a closed one is.
     */
    fun detach(index: Int): OpenFile? {
        val file = _tabs.getOrNull(index) ?: return null
        val wasActive = index == activeIndex
        val stayingActive = if (wasActive) null else active
        _tabs.removeAt(index)
        closing.remove(file)
        activationHistory.remove(file.path)
        activeIndex = when {
            _tabs.isEmpty() -> -1
            // Closing a tab that wasn't the active one must not move the
            // selection — only its index shifts.
            !wasActive -> _tabs.indexOfFirst { it === stayingActive }.coerceAtLeast(0)
            else -> activeAfterClose(index)
        }
        activationHistory.remove(active?.path)
        active?.path?.let { activationHistory.add(it) }
        return file
    }

    /**
     * Which tab takes over — Zed's `tabs.activate_on_close`
     * (assets/settings/default.json): the one you were on before, the right
     * neighbour, or the left one. History falls back to the left neighbour
     * when there is no history left, which is where every editor lands.
     */
    private fun activeAfterClose(closedIndex: Int): Int = when (activateOnClose) {
        ActivateOnClose.History ->
            activationHistory.lastOrNull()
                ?.let(::indexOfPath)
                ?.takeIf { it >= 0 }
                ?: (closedIndex - 1).coerceIn(0, _tabs.lastIndex)

        ActivateOnClose.Neighbour -> closedIndex.coerceIn(0, _tabs.lastIndex)
        ActivateOnClose.LeftNeighbour -> (closedIndex - 1).coerceIn(0, _tabs.lastIndex)
    }

    /**
     * Zed's `max_tabs`: once the pane holds more than the cap, the tab gone
     * longest without being looked at is closed (workspace/src/pane.rs, where
     * `max_tabs` walks `activation_history` from the oldest end).
     *
     * Pinned tabs are never the victim — pinning is how you say "not this
     * one" — and neither is a tab with unsaved edits: a cap is a tidiness
     * setting, and losing work to one would be indefensible. When everything
     * left is pinned or dirty the pane simply runs over the cap, and the next
     * save brings it back down.
     */
    private fun enforceMaxTabs() {
        val cap = maxTabs ?: return
        while (_tabs.size > cap) {
            val victim = mruOrder(_tabs.map { it.path }, activationHistory)
                .asReversed()
                .asSequence()
                .mapNotNull { path -> _tabs.firstOrNull { it.path == path } }
                .firstOrNull { candidate ->
                    if (candidate.isPinned || candidate === active) return@firstOrNull false
                    // The poll loop is up to a quarter of a second behind, and
                    // this decision throws a buffer away: ask the engine now.
                    candidate.refreshStatus()
                    !candidate.isDirty
                } ?: return
            close(indexOfPath(victim.path))
        }
    }

    /**
     * Take in a tab another pane [detach]ed, and make it active. A tab on
     * the same path is already here — Zed's `add_item` finds the existing
     * singleton and activates it instead of adding a twin
     * (pane.rs:1500-1530) — so the newcomer is released and the one that
     * was here is selected. A pinned tab lands among the pinned ones.
     */
    fun adopt(file: OpenFile) {
        val existing = indexOfPath(file.path)
        if (existing >= 0) {
            onRelease(file)
            select(existing)
            return
        }
        recordDeparture()
        pendingNavPath = null
        val at = if (file.isPinned) _tabs.count { it.isPinned } else _tabs.size
        _tabs.add(at, file)
        // Through [activate], so the tab this pane just took in is the most
        // recent one *here* — the Ctrl+Tab switcher and `activate_on_close`
        // both read that list, and a tab nobody had ever activated would sit
        // at the wrong end of both.
        activate(at)
        closedPaths.remove(file.path)
    }

    /**
     * Whether an open multibuffer is still showing this file's buffer.
     *
     * The engine keys buffers by path and counts no references, so closing a
     * tab on a file a multibuffer excerpts would pull the text out from under
     * it — and the excerpt would silently vanish on its next recomposition.
     * The tab goes; the buffer stays until the multibuffer lets it go too.
     */
    private fun isHeldByAMultibuffer(file: OpenFile): Boolean {
        val id = file.session?.id ?: return false
        return heldTabs().any { tab ->
            tab.multibuffer?.info?.excerpts?.any { it.bufferId == id } == true
        }
    }

    /** Pin or unpin a tab, moving it across the pinned/unpinned boundary. */
    fun togglePin(index: Int) {
        val file = _tabs.getOrNull(index) ?: return
        val current = active
        file.isPinned = !file.isPinned
        // Pinning is the strongest possible "keep this", so it promotes: a
        // pinned tab that was still the pane's preview slot would be replaced
        // in place by the next single click, which is the opposite of pinning.
        if (file.isPinned) file.isPreview = false
        _tabs.removeAt(index)
        // The boundary counts only the *other* pinned tabs now that this one is
        // out of the list, so both directions land the tab on the right side.
        _tabs.add(_tabs.count { it.isPinned }, file)
        activeIndex = if (current == null) -1 else _tabs.indexOfFirst { it === current }
    }

    /** Close one tab, asking first if it has unsaved edits. */
    fun requestClose(index: Int) {
        val file = _tabs.getOrNull(index) ?: return
        request(listOf(file))
    }

    /** Close every other tab, leaving the pinned ones alone. */
    fun requestCloseOthers(index: Int) {
        val kept = _tabs.getOrNull(index) ?: return
        request(_tabs.filter { it !== kept && !it.isPinned })
    }

    /** Close everything to the right of [index], leaving the pinned ones alone. */
    fun requestCloseToTheRight(index: Int) {
        if (index !in _tabs.indices) return
        request(_tabs.drop(index + 1).filter { !it.isPinned })
    }

    /** Zed's `pane::CloseItemsToTheLeft` (default-linux.json:505, `ctrl-k e`). */
    fun requestCloseToTheLeft(index: Int) {
        if (index !in _tabs.indices) return
        request(_tabs.take(index).filter { !it.isPinned })
    }

    /**
     * Zed's `pane::CloseCleanItems` (`ctrl-k u`): every tab with nothing to
     * lose. Statuses are refreshed first — the poll loop is up to a quarter of
     * a second behind, and "clean" is the whole question this command asks.
     */
    fun requestCloseClean() {
        refreshStatuses()
        request(_tabs.filter { !it.isPinned && !it.isDirty })
    }

    /** Close every tab. Pinned tabs survive, which is the point of pinning them. */
    fun requestCloseAll() {
        request(_tabs.filter { !it.isPinned })
    }

    /** The user said discard, or has just saved: close the tab and move on. */
    fun confirmClose() {
        val file = closing.firstOrNull() ?: return
        closing.removeAt(0)
        close(indexOfPath(file.path))
        drainClean()
    }

    /** The user said no. The rest of the request goes with it — nothing closes. */
    fun cancelClose() {
        closing.clear()
    }

    /**
     * The most recently closed file that isn't open again, or null.
     *
     * A path, not a buffer: closing released the engine's buffer, so reopening
     * goes back through the workspace's normal open path rather than trying to
     * resurrect one.
     */
    fun takeReopenPath(): String? {
        while (closedPaths.isNotEmpty()) {
            val path = closedPaths.removeAt(closedPaths.lastIndex)
            if (indexOfPath(path) < 0) return path
        }
        return null
    }

    /** Forget the reopen history — the paths belong to a project being left. */
    fun clearClosedHistory() {
        closedPaths.clear()
        activationHistory.clear()
        // The jump list goes with it, for the same reason: its paths are
        // relative to a root that is about to change, which is Zed's
        // `NavHistory::clear` on workspace teardown (pane.rs:4748-4768).
        nav.clear()
        pendingNavPath = null
    }

    fun refreshStatuses() {
        for (tab in _tabs) tab.refreshStatus()
    }

    private fun request(targets: List<OpenFile>) {
        closing.clear()
        closing.addAll(targets)
        drainClean()
    }

    /** Close as far as the first tab that has something to lose. */
    private fun drainClean() {
        while (closing.isNotEmpty()) {
            val file = closing.first()
            // The poll loop is up to a quarter of a second behind; ask the
            // engine now rather than about a state that has already changed.
            file.refreshStatus()
            if (file.isDirty && !isSharedElsewhere(file)) return
            closing.removeAt(0)
            close(indexOfPath(file.path))
        }
    }

    private fun rememberClosed(path: String) {
        closedPaths.remove(path)
        closedPaths.add(path)
        if (closedPaths.size > REOPEN_HISTORY) closedPaths.removeAt(0)
    }
}

/**
 * The tabs in most-recently-used order, newest first.
 *
 * [history] is the pane's activation history, oldest first — Zed's
 * `Pane::activation_history`, which its tab switcher read in reverse
 * (tab_switcher/src/tab_switcher.rs). Tabs the history has never heard of —
 * opened before tracking started, or restored from a session — come last, in
 * strip order, rather than being dropped: an order that hid one would be
 * worse than an imperfect one.
 *
 * Pure, and the reason it lives outside [OpenFilesState]: this is the part
 * worth testing (`MruOrderTest`).
 *
 * It arrived here from ui/workspace/TabSwitcher.kt, which went with the tab
 * strip and the Ctrl+Tab overlay (docs/UI.md, "What is removed"). The
 * switcher is gone; the ordering is not, because [OpenFilesState] evicts by
 * it — the tab a `max_tabs` overflow closes is the one you have been away
 * from longest, and that decision has to outlive the UI that displayed it.
 */
fun mruOrder(paths: List<String>, history: List<String>): List<String> {
    val open = paths.toHashSet()
    val ordered = ArrayList<String>(paths.size)
    val seen = HashSet<String>(paths.size)
    for (index in history.indices.reversed()) {
        val path = history[index]
        if (path in open && seen.add(path)) ordered += path
    }
    for (path in paths) if (seen.add(path)) ordered += path
    return ordered
}
