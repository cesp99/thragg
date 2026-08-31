package to.eyed.seeker.code.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.core.ProjectReplaceReceipt
import to.eyed.seeker.code.core.ProjectSearchFile
import to.eyed.seeker.code.core.ProjectSearchMatch
import to.eyed.seeker.code.core.ProjectSearchSession
import to.eyed.seeker.code.core.ProjectSearchState
import to.eyed.seeker.code.core.ProjectSession
import to.eyed.seeker.code.core.SearchQuery
import to.eyed.seeker.code.core.replaceAllInProject
import to.eyed.seeker.code.ui.theme.BufferFontFamily
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.revealItem
import to.eyed.seeker.code.ui.workspace.Activities
import to.eyed.seeker.code.ui.workspace.ActivityTarget
import to.eyed.seeker.code.ui.workspace.EntryIconMark


/**
 * The toolbar chrome around the bars: `py(Base06)` = 6px and `px(Base08)` =
 * 8px, with a 1px `border.variant` underline (workspace/src/toolbar.rs:123-129).
 * Zed's project search bar is a toolbar item; ours is the whole toolbar.
 */
private val ToolbarVPad = 6.dp
private val ToolbarHPad = 8.dp

/** `min_h_8` = 32px, the search input's floor (search/src/search_bar.rs:73). */
private val InputMinHeight = 32.dp

/** `rounded_md`, the radius Zed gives a search input (styles.rs:1246). */
private val FieldRadius = 6.dp

/**
 * An `IconButtonShape::Square` button: a 16px `IconSize::Medium` glyph plus
 * `Base02` = 2px of padding a side (ui/src/components/icon.rs:75, 89-92,
 * 102-107; icon_button.rs:258-260). Sub-40dp per the 2026-08-17 density
 * decision; the panel's keyboard (arrows, Enter, Escape) is the other route.
 */
private val ButtonBox = 20.dp

/**
 * A results file header is Zed's multibuffer buffer header: an outer block
 * `FILE_HEADER_HEIGHT` = 2 buffer lines tall (editor.rs:290 — ≈48.5px at
 * Zed's 15px × 1.618) with `BUFFER_HEADER_PADDING` = 4px around the card
 * (editor.rs:291), leaving the card itself ≈40px.
 */
private val HeaderPadding = 4.dp
private val HeaderCardMinHeight = 40.dp

/**
 * Zed's results gutter never drops under `min_line_number_digits` = 4 digits
 * (editor.rs:11712-11715; default.json:697), and *grows* with the widest
 * number rather than clipping it. Four digits of the 12sp result font are
 * ≈29dp; a hit on line 10000+ gets one more digit's width per digit.
 */
private val LineNumberWidth = 30.dp
private val LineNumberDigitWidth = 7.5.dp

private fun lineNumberWidth(line: Int): Dp {
    val digits = line.toString().length
    return if (digits <= 4) LineNumberWidth else LineNumberWidth + LineNumberDigitWidth * (digits - 4)
}


/**
 * How long the query rests before a search starts.
 *
 * Buffer search runs on every keystroke because it is one pass over a rope.
 * This one reads every file in the project, so a keystroke that started one
 * would spin a thread over thousands of files it is about to throw away, and
 * typing `foobar` would start six of them.
 */
private const val QUERY_DEBOUNCE_MS = 250L

/** The engine publishes every 100 ms; polling faster only costs JNI calls. */
private const val POLL_MS = 100L

/** How far PageUp and PageDown move the selection. */
private const val PAGE_ROWS = 10

/**
 * What the panel had in its fields when it was last closed.
 *
 * A compact screen gives the whole work area to the panel, so opening a result
 * closes it — and without this, walking three hits in turn would mean typing
 * the query three times. Session-lived on purpose: it is Zed's search history
 * in the small, not a setting, and it never reaches disk.
 */
private object LastProjectSearch {
    var query: String = ""
    var regex: Boolean = false
    var caseSensitive: Boolean = false
    var wholeWord: Boolean = false
    var includeIgnored: Boolean = false
    var include: String = ""
    var exclude: String = ""
    var replacement: String = ""
    var replaceOpen: Boolean = false

    /** Whether the filter row was carrying anything worth reopening it for. */
    val hasFilters: Boolean
        get() = include.isNotEmpty() || exclude.isNotEmpty() || includeIgnored

    fun store(search: SearchQuery, includeText: String, excludeText: String) {
        query = search.query
        regex = search.regex
        caseSensitive = search.caseSensitive
        wholeWord = search.wholeWord
        includeIgnored = search.includeIgnored
        include = includeText
        exclude = excludeText
    }
}

/** Which field an error belongs to, and therefore which one is outlined. */
private enum class ErrorField { Query, Filters, Neither }

/** The counters a running search publishes, as the status line reads them. */
private data class SearchProgress(
    /** Null before anything has started — an empty query, or the debounce. */
    val state: ProjectSearchState? = null,
    val filesSearched: Int = 0,
    val totalFiles: Int = 0,
    val fileCount: Int = 0,
    val matchCount: Int = 0,
    val truncated: Boolean = false,
    val error: String? = null,
    val errorField: ErrorField = ErrorField.Neither,
) {
    val isLive: Boolean get() = state?.isLive == true
}

/**
 * Search every file in the project — Zed's project search
 * (crates/search/src/project_search.rs), which there is an item inside a pane.
 *
 * We have no pane, so this is a panel: a dock beside the editor on a wide
 * screen, and the whole work area on a compact one, which is the split the
 * terminal already makes. What that costs is Zed's "results as one editable
 * multi-buffer"; what it buys is a results list that survives on a phone,
 * where a second pane would leave neither half usable.
 *
 * The engine reads the files on a thread of its own and publishes every
 * 100 ms, so results arrive while the search runs and the list grows under the
 * reader. Only the cheap version counter is read on the main thread; the parse
 * — which can be megabytes of JSON — is not.
 *
 * Keyboard, mouse and touch all reach every result: the arrows walk the rows
 * while the query field keeps the caret, Enter opens the selected one and
 * Escape closes; a mouse hovers, clicks and scrolls; a finger taps.
 */
@Composable
fun ProjectSearchPanel(
    project: ProjectSession,
    /**
     * Bumped by the workspace whenever Ctrl+Shift+F is pressed, so pressing it
     * with the panel already open puts the caret back in the query rather than
     * doing nothing visible.
     */
    focusToken: Int,
    onOpenMatch: (path: String, match: ProjectSearchMatch) -> Unit,
    /**
     * Replace-all landed: the receipt names the open buffers the engine
     * edited, so the workspace can resync their editors and tab statuses.
     */
    onReplaced: (ProjectReplaceReceipt) -> Unit,
    /**
     * Open the results as an editable multibuffer — which in Zed *is* the
     * project search: `project_search::Deploy` opens a results editor over a
     * multibuffer of every hit (search/src/project_search.rs). [focus] names
     * the hit to land the caret on, or null to land at the top.
     *
     * **Null is the shell's answer.** There is no multibuffer on this device
     * and there will not be one: the portrait shell has a single editor and
     * the multibuffer's host — ui/workspace/ — is deleted in P10 along with
     * core/MultiBufferSession.kt. Nulled here rather than in P10 so that
     * deletion is a deletion and not a redesign; with it null the chip and
     * Alt+Enter both go quiet and a hit opens its file, which is the
     * behaviour a phone wanted anyway (docs/UI.md, P7).
     */
    onOpenMultibuffer: ((
        query: String,
        files: List<ProjectSearchFile>,
        focus: Pair<String, Int>?,
    ) -> Unit)? = null,
    /**
     * Whether the multibuffer is what a hit opens.
     *
     * True on a wide layout, where Zed's behaviour — every result in one
     * editable document — fits; false on a compact one, where the list is the
     * better phone surface and a hit opens its file, which is what this panel
     * has always done. The chip and Alt+Enter reach the multibuffer either way.
     */
    multibufferIsDefault: Boolean = false,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * An include glob to start from — Zed's
     * `project_panel::NewSearchInDirectory`, which deploys search with the
     * chosen folder already in the filter. Applied on every bump of
     * [focusToken], which is the same token the panel is raised with, so a
     * second Search-in-Directory on a panel that is already open reseeds it.
     */
    seedInclude: String? = null,
    /** The seed has been applied; the workspace forgets it. */
    onSeedApplied: () -> Unit = {},
) {
    val theme = LocalZedTheme.current
    val scope = rememberCoroutineScope()
    var replacement by remember { mutableStateOf(TextFieldValue(LastProjectSearch.replacement)) }
    var replaceOpen by remember { mutableStateOf(LastProjectSearch.replaceOpen) }
    /** "Replaced 12 matches in 3 files", until the query moves on. */
    var replaceSummary by remember { mutableStateOf<String?>(null) }
    /** A replace is in flight; the button greys so it cannot be pressed twice. */
    var replacing by remember { mutableStateOf(false) }
    val replaceFocus = remember { FocusRequester() }
    var query by remember {
        mutableStateOf(
            TextFieldValue(
                LastProjectSearch.query,
                TextRange(LastProjectSearch.query.length),
            )
        )
    }
    var caseSensitive by remember { mutableStateOf(LastProjectSearch.caseSensitive) }
    var wholeWord by remember { mutableStateOf(LastProjectSearch.wholeWord) }
    var regex by remember { mutableStateOf(LastProjectSearch.regex) }
    var includeIgnored by remember { mutableStateOf(LastProjectSearch.includeIgnored) }
    var include by remember { mutableStateOf(TextFieldValue(LastProjectSearch.include)) }
    var exclude by remember { mutableStateOf(TextFieldValue(LastProjectSearch.exclude)) }
    var filtersOpen by remember { mutableStateOf(LastProjectSearch.hasFilters) }

    // Appended to, never rebuilt: a poll hands back only what is new, and a
    // finished search over a big repository is thousands of files.
    val files = remember { mutableStateListOf<ProjectSearchFile>() }
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    var progress by remember { mutableStateOf(SearchProgress()) }
    var selected by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    LaunchedEffect(focusToken) { focus.requestFocus() }

    LaunchedEffect(focusToken, seedInclude) {
        val glob = seedInclude ?: return@LaunchedEffect
        include = TextFieldValue(glob, TextRange(glob.length))
        filtersOpen = true
        onSeedApplied()
    }

    val search = remember(
        query.text,
        regex,
        caseSensitive,
        wholeWord,
        includeIgnored,
        include.text,
        exclude.text,
    ) {
        SearchQuery(
            query = query.text,
            regex = regex,
            caseSensitive = caseSensitive,
            wholeWord = wholeWord,
            includeIgnored = includeIgnored,
            includeGlobs = globsOf(include.text),
            excludeGlobs = globsOf(exclude.text),
        )
    }
    val rows = remember(files.size, collapsed) { projectSearchRows(files, collapsed) }

    /** This project's entry in the status bar's activity indicator. */
    val searchActivity = "project-search:${project.id}"

    // One search at a time, restarted whenever anything about the query moves.
    // Nothing here cancels the previous one by hand: starting a search already
    // cancels whatever was running for the project, and the cancel below is
    // matched on the id it was given — so a restart that overtakes this
    // coroutine's cleanup cannot kill its successor.
    LaunchedEffect(project, search) {
        LastProjectSearch.store(search, include.text, exclude.text)
        files.clear()
        collapsed = emptySet()
        selected = -1
        progress = SearchProgress()
        replaceSummary = null
        if (search.query.isEmpty()) return@LaunchedEffect
        delay(QUERY_DEBOUNCE_MS)

        // Compiling a pathological regex costs tens of milliseconds, so it is
        // asked once, here, rather than on the keystroke path.
        val invalid = withContext(Dispatchers.Default) { search.error() }
        if (invalid != null) {
            progress = SearchProgress(error = invalid, errorField = ErrorField.Query)
            return@LaunchedEffect
        }
        var session: ProjectSearchSession? = null
        try {
            // Assigned from inside the block rather than taken as its result:
            // cancelling this coroutine while the engine is starting the
            // search would skip the assignment, and the search would then run
            // to the end holding results nobody will ever read.
            withContext(NonCancellable + Dispatchers.Default) {
                session = ProjectSearchSession(project, search)
            }
            val started = session ?: return@LaunchedEffect
            if (started.id < 0) {
                // The query compiled a moment ago and the project is one we
                // hold open, so what the engine refused is a glob. The
                // contract cannot say which of the two patterns it was.
                progress = SearchProgress(
                    error = "Check the include and exclude patterns",
                    errorField = ErrorField.Filters,
                )
                return@LaunchedEffect
            }
            progress = SearchProgress(state = ProjectSearchState.Scanning)
            // In the status bar while it runs — a search over a big
            // repository is seconds, and on a compact screen the panel is
            // often not the thing on screen by then. Ended in the `finally`
            // below, however this coroutine ends: a search restarted on the
            // next keystroke cancels this one.
            Activities.begin(
                key = searchActivity,
                message = "Searching ${project.rootName}…",
                target = ActivityTarget.ProjectSearch,
            )
            // The whole wait lives on Default — the counter is one JNI read
            // per tick — and the main thread sees only the merges.
            withContext(Dispatchers.Default) {
                var seen = 0L
                while (true) {
                    val version = started.version
                    // 0 means forgotten, never "not yet": nothing more is coming.
                    if (version == 0L) break
                    if (version != seen) {
                        seen = version
                        val results = started.poll()
                        withContext(Dispatchers.Main) {
                            files.addAll(results.newFiles)
                            progress = SearchProgress(
                                state = results.state,
                                filesSearched = results.filesSearched,
                                totalFiles = results.totalFiles,
                                fileCount = results.fileCount,
                                matchCount = results.matchCount,
                                truncated = results.truncated,
                                error = results.error,
                            )
                        }
                        if (!results.state.isLive) break
                    }
                    delay(POLL_MS)
                }
            }
        } catch (cancelled: CancellationException) {
            // The query moved on, or the panel closed: nobody will read
            // these results again, and the engine is holding its own copy
            // of them — megabytes, over a big repository. A search that
            // merely *finished* is not forgotten here: replace-all works
            // over the engine's list for the search the user was shown
            // (`project_replace_all` refuses a project with no search),
            // and cancelling on completion was exactly what left it
            // "Nothing to replace — search again" every time.
            session?.let { started ->
                withContext(NonCancellable + Dispatchers.Default) { started.cancel() }
            }
            throw cancelled
        } finally {
            Activities.end(searchActivity)
        }
    }

    // Bumped only by the arrows: a row the mouse just clicked is already on
    // screen, and scrolling it to the top under the pointer would move the
    // next row out from under it.
    var scrollToSelected by remember { mutableIntStateOf(0) }
    LaunchedEffect(scrollToSelected) {
        if (scrollToSelected > 0 && selected in rows.indices) {
            listState.revealItem(selected)
        }
    }

    fun move(delta: Int) {
        if (rows.isEmpty()) return
        selected = when {
            selected < 0 -> if (delta > 0) 0 else rows.lastIndex
            else -> (selected + delta).coerceIn(0, rows.lastIndex)
        }
        scrollToSelected++
    }

    fun toggle(path: String) {
        collapsed = if (path in collapsed) collapsed - path else collapsed + path
    }

    /**
     * A hit was chosen. On a wide layout that means the multibuffer, with the
     * caret on this hit — Zed opens the whole search there and never opens a
     * lone file; on a compact one it means the file, which is the surface that
     * fits a phone.
     */
    fun openHit(path: String, match: ProjectSearchMatch) {
        val multibuffer = onOpenMultibuffer
        if (multibufferIsDefault && multibuffer != null) {
            multibuffer(query.text, files.toList(), path to (match.line - 1))
        } else {
            onOpenMatch(path, match)
        }
    }

    /** What Enter and a click both mean: a file folds, a match opens. */
    fun activate(row: ProjectSearchRow) {
        when (row) {
            is ProjectSearchRow.FileRow -> toggle(row.path)
            is ProjectSearchRow.MatchRow -> openHit(row.path, row.match)
        }
    }

    // The replace row and its text outlive a close for the same reason the
    // query does: on a phone, opening a result closes the panel.
    LaunchedEffect(replacement.text, replaceOpen) {
        LastProjectSearch.replacement = replacement.text
        LastProjectSearch.replaceOpen = replaceOpen
    }

    /**
     * Zed's `search::ToggleReplace` on the project bar
     * (project_search.rs:2723-2740): show the row and move the caret into
     * it, or hide it and put the caret back in the query.
     */
    fun toggleReplace() {
        replaceOpen = !replaceOpen
        if (!replaceOpen) focus.requestFocus()
    }
    LaunchedEffect(replaceOpen) {
        if (replaceOpen) replaceFocus.requestFocus()
    }

    /** Only a finished search with something in it can be replaced over. */
    val canReplace = progress.state == ProjectSearchState.Done && progress.matchCount > 0 && !replacing

    /**
     * Zed's `search::ReplaceAll` over the results
     * (project_search.rs:1384-1420). The engine walks the files the search
     * found: open ones through their buffers, so the editors update and undo
     * works; closed ones on disk. What was replaced no longer matches, so
     * the list clears and the status line says what happened.
     */
    fun replaceAll() {
        if (!canReplace) return
        val replaceWith = replacement.text
        replacing = true
        scope.launch {
            val receipt = withContext(Dispatchers.IO) {
                replaceAllInProject(project, search, replaceWith)
            }
            replacing = false
            if (receipt == null) {
                // The search finished a moment ago and the query compiled,
                // so the engine's refusal can only be a race with a restart.
                progress = progress.copy(error = "Nothing to replace — search again")
                return@launch
            }
            onReplaced(receipt)
            files.clear()
            collapsed = emptySet()
            selected = -1
            progress = SearchProgress()
            replaceSummary = buildString {
                append(receipt.summary)
                if (receipt.errors.isNotEmpty()) {
                    append(" · ")
                    append(count(receipt.errors.size, "file"))
                    append(" could not be written: ")
                    append(receipt.errors.first())
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.color("panel.background"))
            // Arrows, Enter and Escape have to reach us while the query field
            // holds the caret, so they are taken before it sees them. Anything
            // with Ctrl is left alone: those belong to the workspace's table,
            // which matched them before this pass ever ran.
            // Zed's `alt-c`, `alt-w`, `alt-r` toggles, which the Pane
            // context gives every search (default-linux.json:523-530),
            // claimed before the input method sees the key: the IME is
            // first in line for a focused text field, and a layout that
            // gives Alt+C a character ('ç' on the stock ones) commits it
            // and consumes the event before any onPreviewKeyEvent runs.
            // See BufferSearchBar for the same handler.
            .onPreInterceptKeyBeforeSoftKeyboard { event ->
                if (event.type != KeyEventType.KeyDown || !event.isAltPressed) {
                    return@onPreInterceptKeyBeforeSoftKeyboard false
                }
                when (event.key) {
                    Key.C -> { caseSensitive = !caseSensitive; true }
                    Key.W -> { wholeWord = !wholeWord; true }
                    Key.R -> { regex = !regex; true }
                    else -> false
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // The two replace chords Zed gives the project bar
                // (default-linux.json:448, 465): `ctrl-shift-h` toggles the
                // row, `ctrl-alt-enter` replaces everything. Neither is in
                // the workspace's table, so they reach us.
                if (event.isCtrlPressed) {
                    return@onPreviewKeyEvent when {
                        event.key == Key.H && event.isShiftPressed && !event.isAltPressed -> {
                            toggleReplace()
                            true
                        }
                        (event.key == Key.Enter || event.key == Key.NumPadEnter) && event.isAltPressed -> {
                            replaceAll()
                            true
                        }
                        else -> false
                    }
                }
                // Zed's `editor::OpenExcerpts` chord (alt-enter), which is how
                // the results reach the multibuffer from the keyboard whatever
                // the layout. The workspace's own Alt+Enter refuses outside a
                // multibuffer, so it arrives here.
                if (event.isAltPressed &&
                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    val multibuffer = onOpenMultibuffer
                    if (files.isEmpty() || multibuffer == null) return@onPreviewKeyEvent false
                    multibuffer(query.text, files.toList(), null)
                    return@onPreviewKeyEvent true
                }
                when (event.key) {
                    Key.DirectionDown -> { move(1); true }
                    Key.DirectionUp -> { move(-1); true }
                    Key.PageDown -> { move(PAGE_ROWS); true }
                    Key.PageUp -> { move(-PAGE_ROWS); true }
                    Key.Enter, Key.NumPadEnter -> {
                        // With nothing selected, Enter is how the keyboard
                        // steps out of the query and into the results.
                        val row = rows.getOrNull(selected)
                        if (row == null) move(1) else activate(row)
                        true
                    }
                    Key.Escape -> { onDismiss(); true }
                    else -> false
                }
            },
    ) {
        // The toolbar block: the query line, the filter line and the status
        // line stacked `gap_2` apart (project_search.rs:2663-2664), on
        // `toolbar.background` behind its 1px `border.variant` underline
        // (toolbar.rs:128-130).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.color("toolbar.background"))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ToolbarHPad, vertical = ToolbarVPad),
            ) {
                QueryBar(
                    query = query,
                    onQuery = { query = it },
                    caseSensitive = caseSensitive,
                    onCaseSensitive = { caseSensitive = !caseSensitive },
                    wholeWord = wholeWord,
                    onWholeWord = { wholeWord = !wholeWord },
                    regex = regex,
                    onRegex = { regex = !regex },
                    filtersOpen = filtersOpen,
                    onFilters = { filtersOpen = !filtersOpen },
                    replaceOpen = replaceOpen,
                    onReplace = ::toggleReplace,
                    hasError = progress.errorField == ErrorField.Query,
                    focus = focus,
                    onClose = onDismiss,
                )
                if (replaceOpen) {
                    // Zed's replace line: the replacement input and its
                    // actions, `gap_2` under the query line
                    // (project_search.rs:3191-3226). Only replace-all here:
                    // Zed's ReplaceNext walks its results editor, and this
                    // list opens a match in the editor instead.
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Field(
                            value = replacement,
                            onValue = { replacement = it },
                            // Zed's placeholder (search.rs:84).
                            placeholder = "Replace in project…",
                            hasError = false,
                            focus = replaceFocus,
                            modifier = Modifier.weight(1f),
                        )
                        BarButton(
                            "⇉",
                            "Replace all matches",
                            enabled = canReplace,
                            onClick = ::replaceAll,
                        )
                    }
                }
                if (filtersOpen) {
                    FilterBar(
                        include = include,
                        onInclude = { include = it },
                        exclude = exclude,
                        onExclude = { exclude = it },
                        includeIgnored = includeIgnored,
                        onIncludeIgnored = { includeIgnored = !includeIgnored },
                        hasError = progress.errorField == ErrorField.Filters,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        StatusLine(
                            progress = progress,
                            queryIsEmpty = query.text.isEmpty(),
                            summary = replaceSummary,
                        )
                    }
                    // The touch twin of Alt+Enter, and the only way to the
                    // multibuffer on a compact layout — where a hit still
                    // opens its file.
                    val multibuffer = onOpenMultibuffer
                    if (files.isNotEmpty() && multibuffer != null) {
                        BarButton(
                            glyph = "Multibuffer",
                            description = "Open the results in an editable multibuffer",
                            wide = true,
                            onClick = { multibuffer(query.text, files.toList(), null) },
                        )
                    }
                }
            }
            // A bar rather than Zed's rotating-arrow spinner
            // (project_search.rs:2449-2456): the contract hands over a real
            // fraction, and chrome here does not animate. It leaves when the
            // search does, as the numbers do.
            if (progress.isLive && progress.totalFiles > 0) {
                val done = (progress.filesSearched.toFloat() / progress.totalFiles).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(theme.color("border.variant"))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(done)
                            .height(2.dp)
                            .background(theme.color("border.focused"))
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(theme.color("border.variant"))
            )
        }
        // The results live on `editor.background`, as Zed's results editor
        // and its landing page do (project_search.rs:673).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(theme.color("editor.background")),
        ) {
            if (rows.isEmpty()) {
                Landing(progress = progress, queryIsEmpty = query.text.isEmpty())
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                        when (row) {
                            is ProjectSearchRow.FileRow -> FileHeaderRow(
                                row = row,
                                isSelected = index == selected,
                                onClick = {
                                    selected = index
                                    toggle(row.path)
                                },
                            )
                            is ProjectSearchRow.MatchRow -> MatchResultRow(
                                row = row,
                                isSelected = index == selected,
                                onClick = {
                                    selected = index
                                    openHit(row.path, row.match)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueryBar(
    query: TextFieldValue,
    onQuery: (TextFieldValue) -> Unit,
    caseSensitive: Boolean,
    onCaseSensitive: () -> Unit,
    wholeWord: Boolean,
    onWholeWord: () -> Unit,
    regex: Boolean,
    onRegex: () -> Unit,
    filtersOpen: Boolean,
    onFilters: () -> Unit,
    replaceOpen: Boolean,
    onReplace: () -> Unit,
    hasError: Boolean,
    focus: FocusRequester,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        // `gap_2` between the input and the mode column (project_search.rs:2538).
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Field(
            value = query,
            onValue = onQuery,
            // Zed's own placeholder (project_search.rs:1073).
            placeholder = "Search all files…",
            hasError = hasError,
            focus = focus,
            modifier = Modifier.weight(1f),
        ) {
            // The three toggles sit inside the input's right edge, `gap_1`
            // apart (project_search.rs:2387-2404).
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BarButton("Aa", "Match case", selected = caseSensitive, onClick = onCaseSensitive)
                BarButton("ab", "Whole word", selected = wholeWord, onClick = onWholeWord)
                BarButton(".*", "Regular expression", selected = regex, onClick = onRegex)
            }
        }
        // The mode column: the filter toggle first, `gap_1` from what follows
        // (project_search.rs:2466-2478). Zed has no close button here — its
        // bar dismisses with the pane's tab — but a dock has to carry its own.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Zed's replace toggle leads the mode column
            // (project_search.rs:2657-2668), toggled while the row shows.
            BarButton("⇄", "Toggle replace", selected = replaceOpen, onClick = onReplace)
            BarButton("…", "Include and exclude paths", selected = filtersOpen, onClick = onFilters)
            BarButton(
                "✕",
                "Close",
                textStyle = MaterialTheme.typography.bodyMedium,
                onClick = onClose,
            )
        }
    }
}

@Composable
private fun FilterBar(
    include: TextFieldValue,
    onInclude: (TextFieldValue) -> Unit,
    exclude: TextFieldValue,
    onExclude: (TextFieldValue) -> Unit,
    includeIgnored: Boolean,
    onIncludeIgnored: () -> Unit,
    hasError: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // `gap_2` below the query line (project_search.rs:2663-2664).
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        // Include and exclude grow side by side, `gap_2` apart
        // (project_search.rs:2331-2334, 2616-2626).
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Zed's placeholders, shortened to what a phone's width can show
        // (crates/search/src/search.rs:85-86).
        Field(
            value = include,
            onValue = onInclude,
            placeholder = "Include: src/**/*.rs",
            hasError = hasError,
            modifier = Modifier.weight(1f),
        )
        Field(
            value = exclude,
            onValue = onExclude,
            placeholder = "Exclude: *.lock",
            hasError = hasError,
            modifier = Modifier.weight(1f),
        )
        // Zed's is a square `FileIgnored` icon toggle (project_search.rs:
        // 2610-2614); spelt out because no glyph we have says "the files git
        // is hiding from you", in the same button grammar grown wide.
        BarButton(
            "ignored",
            "Search ignored files",
            selected = includeIgnored,
            wide = true,
            onClick = onIncludeIgnored,
        )
    }
}

/**
 * Zed's search input: `min_h_8` 32px, `pl_2 pr_1`, 1px border in `border` —
 * `error` when its pattern will not compile — `rounded_md`
 * (search_bar.rs:69-79), no fill of its own over the toolbar
 * (search_bar.rs:124). The text inside is the *buffer* font at `text_ui` size
 * with `relative(1.3)` line height (search_bar.rs:112-120). [trailing] is the
 * toggle strip Zed nests inside the box's right edge.
 */
@Composable
private fun Field(
    value: TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    placeholder: String,
    hasError: Boolean,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val theme = LocalZedTheme.current
    val inputStyle = MaterialTheme.typography.bodyMedium.let { base ->
        base.copy(fontFamily = BufferFontFamily, lineHeight = base.fontSize * 1.3)
    }
    Row(
        modifier = modifier
            .heightIn(min = InputMinHeight)
            .clip(RoundedCornerShape(FieldRadius))
            .border(
                1.dp,
                theme.color(if (hasError) "error" else "border"),
                RoundedCornerShape(FieldRadius),
            )
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                // Inner `py_1` around the text (project_search.rs:2382).
                .padding(vertical = 4.dp)
                .pointerHoverIcon(PointerIcon.Text),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = inputStyle.copy(color = theme.color("text")),
                cursorBrush = SolidColor(theme.cursor),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
            )
            if (value.text.isEmpty()) {
                Text(
                    text = placeholder,
                    style = inputStyle,
                    color = theme.color("text.placeholder"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke(this)
    }
}

/**
 * The line under the bars: errors in Zed's error-line grammar —
 * `LabelSize::Small`, `Color::Error`, `ml_2`, `mt_neg_1` pulling it 4px back
 * into the 8px line gap (project_search.rs:2640-2661) — and the running
 * counts, which Zed keeps as a "3/12" label in the bar (project_search.rs:
 * 2434-2448) but a dock this narrow spells out in the same small muted type.
 */
@Composable
private fun StatusLine(progress: SearchProgress, queryIsEmpty: Boolean, summary: String?) {
    val theme = LocalZedTheme.current
    val error = progress.error
    val text = when {
        error != null -> error
        // What replace-all did, in place of the counts it emptied.
        summary != null -> summary
        queryIsEmpty || progress.state == null -> null
        progress.matchCount > 0 -> statusOf(progress)
        // Nothing matched yet: the landing page says "Searching…" or
        // "No Results" in the results area, as Zed's does, so only the live
        // file counter is worth a line here.
        progress.isLive && progress.totalFiles > 0 ->
            "searched ${progress.filesSearched} of ${progress.totalFiles}"
        else -> null
    } ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (error != null) theme.color("error") else theme.color("text.muted"),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
    )
}

/**
 * "12 results in 3 files · searched 480 of 1 200".
 *
 * The counts are the engine's own, which include matches it dropped at its
 * per-file cap — so a capped search says how much it found, not how much it
 * kept, and says separately that a limit was reached.
 */
private fun statusOf(progress: SearchProgress): String {
    val parts = ArrayList<String>(3)
    parts += "${count(progress.matchCount, "result")} in ${count(progress.fileCount, "file")}"
    if (progress.isLive && progress.totalFiles > 0) {
        parts += "searched ${progress.filesSearched} of ${progress.totalFiles}"
    }
    if (progress.truncated && !progress.isLive) parts += "limit reached"
    return parts.joinToString(" · ")
}

private fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

/**
 * Zed's landing page, drawn whenever the results editor has nothing to show:
 * a centred `LabelSize::Large` heading over a `LabelSize::Small` line, `gap_1`
 * apart, on `editor.background` (project_search.rs:640-682).
 */
@Composable
private fun Landing(progress: SearchProgress, queryIsEmpty: Boolean) {
    val theme = LocalZedTheme.current
    // Zed's headings, state for state (project_search.rs:643-648); our
    // engine's Scanning is its WaitingForScan.
    val heading = when {
        queryIsEmpty -> "Search All Files"
        progress.state == ProjectSearchState.Scanning -> "Loading project…"
        progress.isLive -> "Searching…"
        progress.state == ProjectSearchState.Done && progress.matchCount == 0 -> "No Results"
        else -> "Search All Files"
    }
    val minor = when {
        queryIsEmpty -> "Search every file in the project"
        progress.state == ProjectSearchState.Done && progress.matchCount == 0 ->
            // Zed's wording (project_search.rs:657).
            "No results found in this project for the provided query"
        else -> null
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = heading,
            style = MaterialTheme.typography.bodyLarge,
            color = theme.color("text"),
            textAlign = TextAlign.Center,
        )
        if (minor != null) {
            Text(
                text = minor,
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
                textAlign = TextAlign.Center,
                // `gap_1` under the heading (project_search.rs:679).
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * A file's header, in the shape of Zed's multibuffer buffer header
 * (editor/src/element/header.rs:617-915): a card floated on 4px of editor
 * background (`BUFFER_HEADER_PADDING`, editor.rs:291), `rounded_sm`, 1px
 * `border`, filled `editor.subheader.background` with `element.hover` under
 * the pointer (header.rs:716-734), filename and path in the buffer font
 * (header.rs:849-852, 878-891). Zed marks the focused header with
 * `border.focused` (header.rs:721-727); the keyboard selection borrows that.
 * The match count at the right edge is ours — Zed's headers fold on a
 * chevron and count nothing — kept because a phone cannot hover to peek.
 */
@Composable
private fun FileHeaderRow(
    row: ProjectSearchRow.FileRow,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bufferStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = BufferFontFamily)
    Box(modifier = Modifier.fillMaxWidth().padding(HeaderPadding)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HeaderCardMinHeight)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (hovered) theme.color("element.hover")
                    else theme.color("editor.subheader.background")
                )
                .border(
                    1.dp,
                    theme.color(if (isSelected) "border.focused" else "border"),
                    RoundedCornerShape(4.dp),
                )
                .pointerHoverIcon(PointerIcon.Hand)
                // The panel is the one focus target; rows taking it in turn
                // would fight the arrows for the selection.
                .focusProperties { canFocus = false }
                .clickable(
                    interactionSource = interaction,
                    // Zed swaps the header's colour instantly, no ripple.
                    indication = null,
                    onClick = onClick,
                )
                // `pl_1 pr_2` inside the card (header.rs:716-717).
                .padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            // `gap_1p5` between the card's children (header.rs:719).
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Zed's fold chevron, as the glyph the panel already draws.
            Text(
                text = if (row.isCollapsed) "▸" else "▾",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("icon.muted", theme.color("text.muted")),
                modifier = Modifier.width(10.dp),
            )
            // The same icon the panel and the finder draw: a result here and
            // the file itself have to read as the same thing.
            EntryIconMark(
                name = row.name,
                isDir = false,
                isExpanded = false,
                color = theme.color("icon.muted", theme.color("text.muted")),
            )
            Text(
                text = row.name,
                style = bufferStyle,
                color = theme.color("text"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.directory,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = BufferFontFamily),
                color = theme.color("text.muted"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = row.matchCount.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
            )
        }
    }
}

/**
 * One matching line: its number where the gutter would put it —
 * `editor.line_number`, buffer font, right-aligned — then the line itself.
 * The row is its text's line box, nothing taller: Zed's results are editor
 * lines, and editor lines have no padding. Zed separates gutter from text
 * with ch-based padding (editor.rs:11757-11765) that a 240dp dock cannot
 * afford; `Base08` stands in.
 */
@Composable
private fun MatchResultRow(
    row: ProjectSearchRow.MatchRow,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val line = remember(row.match, theme) {
        matchLine(row.match, theme.color("search.match_background"))
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        // Zed's results are one editor and mark the active match in the text
        // itself; a row list marks the row, with the ghost keys every list
        // row here uses (list_item.rs:323-329).
        isSelected -> theme.color("ghost_element.selected")
        hovered -> theme.color("ghost_element.hover", Color.Transparent)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .pointerHoverIcon(PointerIcon.Hand)
            // The panel is the one focus target; rows taking it in turn would
            // fight the arrows for the selection.
            .focusProperties { canFocus = false }
            .clickable(
                interactionSource = interaction,
                // Zed swaps a row's colour instantly and has no ripple at all.
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = row.match.line.toString(),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = BufferFontFamily),
            color = theme.color("editor.line_number", theme.color("text.muted")),
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(lineNumberWidth(row.match.line)),
        )
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = BufferFontFamily),
            color = theme.color("editor.foreground"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Zed's `IconButton` with `IconButtonShape::Square`, drawn with a text glyph
 * where Zed has an SVG: a [ButtonBox] square, `rounded_sm` 4px
 * (button_like.rs:527 via `ButtonLikeRounding::ALL`), `ButtonStyle::Subtle`
 * state colours — `ghost_element.background`, hover `ghost_element.hover`,
 * pressed `ghost_element.active` (button_like.rs:242-243, 298-299, 324-325) —
 * swapped instantly, no ripple. Selected keeps the ghost background and turns
 * the glyph `Color::Selected` = `text.accent` (icon_button.rs:243-252;
 * color.rs:108). [wide] keeps the height and lets a word set the width, with
 * the `px(Base08)` a wide button gets (button_like.rs:799).
 */
@Composable
private fun BarButton(
    glyph: String,
    description: String,
    selected: Boolean = false,
    /** A word instead of a glyph, so the chip grows to fit it. */
    wide: Boolean = false,
    /** Greyed (`ghost_element.disabled`, `text.disabled`; icon_button.rs:243-244). */
    enabled: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val background = when {
        !enabled -> theme.color("ghost_element.disabled")
        pressed -> theme.color("ghost_element.active")
        hovered -> theme.color("ghost_element.hover")
        else -> theme.color("ghost_element.background")
    }
    Box(
        modifier = Modifier
            .then(if (wide) Modifier.height(ButtonBox) else Modifier.size(ButtonBox))
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClickLabel = description,
                onClick = onClick,
            )
            .then(if (wide) Modifier.padding(horizontal = 8.dp) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = textStyle,
            color = theme.color(
                when {
                    !enabled -> "text.disabled"
                    selected -> "text.accent"
                    else -> "text"
                }
            ),
        )
    }
}
