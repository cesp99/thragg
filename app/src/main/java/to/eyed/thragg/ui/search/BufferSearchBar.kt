package to.eyed.thragg.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.thragg.core.BufferMatch
import to.eyed.thragg.core.SearchQuery
import to.eyed.thragg.core.replaceAllInBuffer
import to.eyed.thragg.core.replaceNextInBuffer
import to.eyed.thragg.core.searchBuffer
import to.eyed.thragg.ui.editor.EditorState
import to.eyed.thragg.ui.theme.BufferFontFamily
import to.eyed.thragg.ui.theme.LocalZedTheme

/**
 * The toolbar chrome the bar sits in: `py(Base06)` = 6px and `px(Base08)` =
 * 8px around the items, with a 1px `border.variant` underline
 * (workspace/src/toolbar.rs:123-129). Zed's bar is a toolbar item; ours is
 * the whole toolbar, so it carries the chrome itself.
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
 * 102-107; icon_button.rs:258-260). Sub-40dp on purpose, per the 2026-08-17
 * density decision; Enter/Shift+Enter and Escape are the keyboard routes to
 * the arrows and the close button.
 */
private val ButtonBox = 20.dp

/** `min_w(rems_from_px(40))` under the match counter (buffer_search.rs:334). */
private val CountMinWidth = 40.dp

/**
 * How long the search waits after its keys move. The search itself is cheap,
 * but the keys include the buffer's revision, so typing in the *editor* while
 * the bar is open re-runs it too — this folds a burst of edits, or of query
 * keystrokes, into one scan. Short, because until it fires the highlights sit
 * over text that may have moved.
 */
private const val SEARCH_DEBOUNCE_MS = 150L

/**
 * Something the workspace asks an open bar to do — the palette's route to
 * Zed's `search::*` actions, which in Zed are only reachable while the
 * `BufferSearchBar` context is on the stack.
 */
enum class SearchBarAction {
    /** `search::ToggleReplace`: show or hide the replace row. */
    ToggleReplace,

    /** `search::ReplaceNext`: rewrite the current match and step on. */
    ReplaceNext,

    /** `search::ReplaceAll`: rewrite every match, as one undo step. */
    ReplaceAll,

    /** `search::SelectAllMatches`: a caret on every match, back in the editor. */
    SelectAllMatches,
}

/**
 * One deployment of the bar — what `buffer_search::Deploy` carries
 * (buffer_search.rs:862-920). The workspace makes a new one, with a fresh
 * [token], for every Ctrl+F, Ctrl+H, toolbar tap or palette command while
 * the bar is up, and the bar acts on each exactly once.
 */
data class SearchDeploy(
    /** Distinguishes one deployment from the next; the bar keys an effect on it. */
    val token: Int,
    /**
     * Text to seed the query with — the editor's selection or the word under
     * its caret — or null to leave the query alone and only focus it, which
     * is what Zed's `search::FocusSearch` does from inside the bar.
     */
    val seed: String? = null,
    /** Open the replace row too: `DeployReplace` rather than `Deploy`. */
    val replace: Boolean = false,
    /** Instead of a deployment, an action on the bar as it stands. */
    val action: SearchBarAction? = null,
)

/**
 * Find and replace within the open buffer — Zed's buffer search, in its
 * shape: a row above the editor with the query (the three toggles live
 * *inside* its right edge, as in Zed), the replace and select-all buttons,
 * the prev/next arrows behind a hairline, the match count and the close
 * button; under it, when replace is on, the replacement field with its two
 * buttons (buffer_search.rs:379-411).
 *
 * The search itself is an engine call that scans the whole buffer in a few
 * milliseconds even at 100k lines, and there is no incremental state to get
 * wrong. It re-runs behind a short debounce — not for its own cost, but
 * because it also re-runs on every edit to the buffer while the bar is open,
 * and a typing burst should cost one scan, not one per keystroke. It still
 * goes through [withContext] on the default dispatcher, because "a few
 * milliseconds" is measured on a desktop and the main thread has 16 of them
 * for everything. Enter/F3 walk matches already computed, so stepping never
 * waits on the debounce.
 *
 * Replacing is one engine call each — the scan and the edit under one lock —
 * after which the editor resyncs and the search re-runs at once, so the
 * counter is right before the next frame.
 *
 * The matches are handed to [EditorState] rather than drawn here: only the
 * canvas can paint over the text.
 */
@Composable
fun BufferSearchBar(
    editor: EditorState,
    deploy: SearchDeploy,
    onDismiss: () -> Unit,
    /** Whether the bar (either field) holds the keyboard. */
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var replacement by remember { mutableStateOf(TextFieldValue("")) }
    var replaceOpen by remember { mutableStateOf(false) }
    var caseSensitive by remember { mutableStateOf(false) }
    var wholeWord by remember { mutableStateOf(false) }
    var regex by remember { mutableStateOf(false) }
    var matches by remember { mutableStateOf(emptyList<BufferMatch>()) }
    /** The same matches as rows and columns, computed once off the main thread. */
    var ranges by remember { mutableStateOf(emptyList<EditorState.SelectionRange>()) }
    var total by remember { mutableIntStateOf(0) }
    var current by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    /** Bumped to re-run the search now, skipping the debounce. */
    var searchNow by remember { mutableIntStateOf(0) }
    val lastSearchNow = remember { intArrayOf(0) }
    /**
     * After a replace: the byte offset the next search should pick its
     * current match from — the end of the rewritten text — or -1.
     */
    var resumeFrom by remember { mutableStateOf(-1L) }
    var replaceFocused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val replaceFocus = remember { FocusRequester() }

    // The bar outlives no editor: switching tabs hands it a different one, and
    // the old one must not keep painting this query's highlights — nor may a
    // step() land its byte offsets in a buffer they were never measured
    // against. Both were real: every unedited buffer has engine version 0, so
    // keying on that alone could not tell two of them apart.
    DisposableEffect(editor) {
        onDispose { editor.clearSearchMatches() }
    }

    fun step(delta: Int) {
        if (ranges.isEmpty()) return
        current = ((current + delta) % ranges.size + ranges.size) % ranges.size
        // The ranges are already computed; walking the hits must not re-measure
        // every one of them, twice, on the main thread.
        editor.showSearchMatches(ranges, current)
        editor.selectRange(ranges[current])
    }

    val search = SearchQuery(
        query = query.text,
        regex = regex,
        caseSensitive = caseSensitive,
        wholeWord = wholeWord,
    )

    /**
     * Zed's `ReplaceNext`: rewrite the current match, then select the next
     * (buffer_search.rs:1762-1785). The engine finds the match again from
     * the current one's offset, so a buffer edited since the last scan is
     * never rewritten at a stale range.
     */
    fun replaceNext() {
        val from = matches.getOrNull(current)?.start ?: return
        val replaceWith = replacement.text
        scope.launch {
            val outcome = withContext(Dispatchers.Default) {
                replaceNextInBuffer(editor.session.id, search, replaceWith, from)
            }
            if (outcome == null || outcome.replaced == 0) return@launch
            editor.noteExternalEdit()
            resumeFrom = outcome.resumeAt
            searchNow++
        }
    }

    /** Zed's `ReplaceAll` (buffer_search.rs:1787-1802): every match, one undo. */
    fun replaceAll() {
        if (matches.isEmpty()) return
        val replaceWith = replacement.text
        scope.launch {
            val outcome = withContext(Dispatchers.Default) {
                replaceAllInBuffer(editor.session.id, search, replaceWith)
            }
            if (outcome == null || outcome.replaced == 0) return@launch
            editor.noteExternalEdit()
            searchNow++
        }
    }

    /** Zed's `SelectAllMatches` (buffer_search.rs:1280-1296): carets, then the editor. */
    fun selectAllMatches() {
        if (editor.selectAllSearchMatches(ranges, current)) onDismiss()
    }

    /**
     * The replace field is only in the tree once its row is open, and a
     * [FocusRequester] with no node throws — so a focus meant for it is
     * noted here and granted by the effect below, once the row composes.
     */
    var pendingReplaceFocus by remember { mutableStateOf(false) }

    fun focusReplacement() {
        if (replaceOpen) replaceFocus.requestFocus() else pendingReplaceFocus = true
    }

    fun toggleReplace() {
        // Zed moves the caret into the row it just opened, and back to the
        // query when it closes (buffer_search.rs:1749-1760).
        if (replaceOpen) {
            replaceOpen = false
            focus.requestFocus()
        } else {
            focusReplacement()
            replaceOpen = true
        }
    }

    // Each deployment acts once. A fresh Ctrl+F seeds the query and selects
    // it, so typing replaces the suggestion (buffer_search.rs:1038-1053,
    // `select_query`); Ctrl+H with a seed goes straight to the replace field
    // (buffer_search.rs:891-899). A palette action skips the seeding.
    LaunchedEffect(deploy.token) {
        when (deploy.action) {
            SearchBarAction.ToggleReplace -> toggleReplace()
            SearchBarAction.ReplaceNext -> replaceNext()
            SearchBarAction.ReplaceAll -> replaceAll()
            SearchBarAction.SelectAllMatches -> selectAllMatches()
            null -> {
                val seed = deploy.seed
                val seeded = !seed.isNullOrEmpty()
                query = if (seeded) {
                    TextFieldValue(seed, TextRange(0, seed.length))
                } else {
                    query.copy(selection = TextRange(0, query.text.length))
                }
                if (deploy.replace && seeded) focusReplacement() else focus.requestFocus()
                replaceOpen = replaceOpen || deploy.replace
            }
        }
    }

    // Re-run whenever the query or a toggle changes. The buffer's own version
    // is in the key as well, so typing in the file keeps the highlights honest
    // rather than leaving them over text that has moved.
    LaunchedEffect(query.text, caseSensitive, wholeWord, regex, editor, editor.revision, searchNow) {
        val text = query.text
        if (text.isEmpty()) {
            matches = emptyList()
            // The rows-and-columns twin clears with them: Enter must not walk
            // the previous query's hits, least of all as byte offsets into a
            // buffer they were never measured against.
            ranges = emptyList()
            current = 0
            total = 0
            error = null
            editor.clearSearchMatches()
            return@LaunchedEffect
        }
        // A replace just landed: the counter has to be right now, not in
        // 150 ms, and the buffer is not being typed in.
        val immediate = searchNow != lastSearchNow[0]
        lastSearchNow[0] = searchNow
        if (!immediate) delay(SEARCH_DEBOUNCE_MS)
        // The range conversion belongs in here with the search. It reads a
        // line per match, and a line outside the drawn window is a JNI call
        // that takes the engine's buffer lock — ten thousand of those on the
        // main thread is not a frame, it is a freeze. The error string rides
        // back with the result for the same reason: `search.error()` is a
        // second regex compile through JNI, not a getter.
        val found = withContext(Dispatchers.Default) {
            when (val message = search.error()) {
                null -> {
                    val result = searchBuffer(editor.session.id, search)
                    Triple(result, result.matches.map { editor.rangeOf(it) }, null)
                }
                else -> Triple(null, emptyList(), message)
            }
        }
        if (found.first == null) {
            // A half-typed regex — "[" — is the normal state of the field, not
            // a failure to report loudly. Say it quietly and keep the old
            // highlights off the screen.
            error = found.third
            matches = emptyList()
            ranges = emptyList()
            current = 0
            total = 0
            editor.clearSearchMatches()
            return@LaunchedEffect
        }
        error = null
        val result = found.first ?: return@LaunchedEffect
        val converted = found.second
        matches = result.matches
        ranges = converted
        total = result.total
        val resume = resumeFrom
        resumeFrom = -1
        if (resume >= 0 && result.matches.isNotEmpty()) {
            // Zed's `select_next_match` after a ReplaceNext: the hit after
            // the text just rewritten, wrapping to the first.
            current = result.matches.indexOfFirst { it.start >= resume }.coerceAtLeast(0)
            editor.showSearchMatches(converted, current)
            editor.selectRange(converted[current])
            return@LaunchedEffect
        }
        current = current.coerceIn(0, (result.matches.size - 1).coerceAtLeast(0))
        editor.showSearchMatches(converted, current)
    }

    // Zed renders search input text in the *buffer* font at `text_ui` size
    // (rems 0.875 = 14px) with `relative(1.3)` line height
    // (search_bar.rs:112-120), not in the UI font of the chrome around it.
    val inputStyle = MaterialTheme.typography.bodyMedium.let { base ->
        base.copy(fontFamily = BufferFontFamily, lineHeight = base.fontSize * 1.3)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.color("toolbar.background"))
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val alt = event.isAltPressed
                when (event.key) {
                    Key.Escape -> {
                        onDismiss()
                        true
                    }
                    // Enter walks the hits, as it does in Zed and everywhere
                    // else; shift walks them backwards. In the replace field
                    // it replaces (`in_replace`, default-linux.json:420-425),
                    // and with Alt it selects every hit (the Pane context's
                    // `alt-enter`, default-linux.json:522).
                    Key.Enter, Key.NumPadEnter -> {
                        when {
                            alt -> selectAllMatches()
                            replaceFocused && replaceOpen && event.isCtrlPressed -> replaceAll()
                            replaceFocused && replaceOpen -> replaceNext()
                            else -> step(if (event.isShiftPressed) -1 else 1)
                        }
                        true
                    }
                    Key.F3 -> {
                        step(if (event.isShiftPressed) -1 else 1)
                        true
                    }
                    else -> false
                }
            }
            // Zed's `alt-c`, `alt-w`, `alt-r` for the three toggles
            // (default-linux.json:523-530), claimed *before* the input
            // method sees the key. The IME is first in Android's chain
            // for a focused text field, and a keyboard layout that gives
            // Alt+C a character of its own — 'ç' on the stock layouts —
            // commits it through the InputConnection and consumes the
            // event, so an onPreviewKeyEvent handler never hears of it.
            // Alt+W and Alt+R only worked because no layout maps them.
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
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ToolbarHPad, vertical = ToolbarVPad),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                // `gap_2` between the input column and the mode column
                // (buffer_search.rs:372).
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchField(
                    value = query,
                    onValue = { query = it },
                    // Zed's own placeholder (buffer_search.rs:180).
                    placeholder = "Search…",
                    // Zed turns the query text itself `error` when a
                    // non-empty query has no matches (buffer_search.rs:188-208).
                    textColor = theme.color(
                        if (query.text.isNotEmpty() && matches.isEmpty()) "error" else "text"
                    ),
                    // The border in `border` — `error` while the regex will
                    // not compile (buffer_search.rs:213-217).
                    hasError = error != null,
                    inputStyle = inputStyle,
                    focus = focus,
                    modifier = Modifier.weight(1f),
                ) {
                    // The three toggles live inside the input's right edge,
                    // `gap_1` apart (buffer_search.rs:238-262).
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        BarButton("Aa", "Match case", selected = caseSensitive) {
                            caseSensitive = !caseSensitive
                        }
                        BarButton("ab", "Whole word", selected = wholeWord) {
                            wholeWord = !wholeWord
                        }
                        BarButton(".*", "Regular expression", selected = regex) {
                            regex = !regex
                        }
                    }
                }

                // The mode column, `gap_1` (buffer_search.rs:264-272): Zed's
                // replace toggle, toggled while the row shows, and — ours —
                // the touch route to `SelectAllMatches`, which Zed leaves to
                // `alt-enter` alone.
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BarButton("⇄", "Toggle replace", selected = replaceOpen) { toggleReplace() }
                    BarButton("∗", "Select all matches", enabled = matches.isNotEmpty()) {
                        selectAllMatches()
                    }
                }

                // The matches column: `ml_2 border_l_1` in `border.variant`,
                // then `pl_2`, the two arrows flush against each other, and
                // the counter `ml_2` further right (buffer_search.rs:308-343).
                // The row's own 8dp gap is the `ml_2`; the divider and spacer
                // supply the border and the `pl_2`.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(ButtonBox)
                            .background(theme.color("border.variant"))
                    )
                    Box(modifier = Modifier.width(8.dp))
                    BarButton(
                        "‹",
                        "Previous match",
                        enabled = matches.isNotEmpty(),
                        textStyle = MaterialTheme.typography.bodyMedium,
                    ) { step(-1) }
                    BarButton(
                        "›",
                        "Next match",
                        enabled = matches.isNotEmpty(),
                        textStyle = MaterialTheme.typography.bodyMedium,
                    ) { step(1) }
                    Text(
                        // Zed's counter is "3/12", "0/0" when nothing matches
                        // — including the empty query (buffer_search.rs:189-208).
                        text = if (matches.isNotEmpty()) "${current + 1}/$total" else "0/0",
                        // `LabelSize::Small` (12px); `text` with a live match,
                        // `text.disabled` without one (buffer_search.rs:334-342).
                        style = MaterialTheme.typography.labelMedium,
                        color = theme.color(if (matches.isNotEmpty()) "text" else "text.disabled"),
                        maxLines = 1,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .widthIn(min = CountMinWidth),
                    )
                }

                // Zed pins the close button to the bar's right edge
                // (buffer_search.rs:436-454); at a row's end it lands there too.
                BarButton(
                    "✕",
                    "Close",
                    textStyle = MaterialTheme.typography.bodyMedium,
                ) { onDismiss() }
            }

            if (replaceOpen) {
                // The focus noted for this row is granted from inside it,
                // so it runs once the field's node exists. An effect keyed
                // on `replaceOpen` outside the row ran in the same effect
                // pass as a fresh Ctrl+H's deployment — after that effect
                // had already flipped the flag, but before the row was
                // composed — and asked a requester with no node, which
                // spent the request on nothing: the editor kept the keys
                // and the replacement went into the buffer.
                LaunchedEffect(Unit) {
                    if (pendingReplaceFocus) {
                        pendingReplaceFocus = false
                        replaceFocus.requestFocus()
                    }
                }
                // The replace line: the replacement input, then the two
                // action buttons `gap_1` apart, the whole row `gap_2` under
                // the search line (buffer_search.rs:379-411, 462-464).
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SearchField(
                        value = replacement,
                        onValue = { replacement = it },
                        // Zed's placeholder (buffer_search.rs:185).
                        placeholder = "Replace with…",
                        textColor = theme.color("text"),
                        hasError = false,
                        inputStyle = inputStyle,
                        focus = replaceFocus,
                        onFocused = { replaceFocused = it },
                        modifier = Modifier.weight(1f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        BarButton("↦", "Replace next match", enabled = matches.isNotEmpty()) {
                            replaceNext()
                        }
                        BarButton("⇉", "Replace all matches", enabled = matches.isNotEmpty()) {
                            replaceAll()
                        }
                    }
                }
            }

            error?.let { message ->
                Text(
                    // The regex error under the bar: `LabelSize::Small`,
                    // `Color::Error`, `ml_2`, and `mt_neg_1` pulling it 4px
                    // back into the 8px line gap (buffer_search.rs:423-429).
                    text = message,
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color("error"),
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                )
            }
        }
        // Zed underlines the toolbar with 1px of `border.variant`
        // (toolbar.rs:128-129). The workspace already draws its divider
        // right under this bar, so a second hairline here would double it —
        // the underline is that divider's job.
    }
}

/**
 * Zed's search input: `min_w_32 min_h_8 pl_2 pr_1 border_1 rounded_md`
 * (search_bar.rs:69-79), the border in `border` or `error`. No fill of its
 * own: Zed paints the text on `toolbar.background` (search_bar.rs:124). On a
 * phone the viewport is under Zed's 1200px threshold, so the input takes the
 * whole remaining width (ui/src/utils/search_input.rs:13-18). [trailing] is
 * the toggle strip Zed nests inside the query box's right edge.
 */
@Composable
private fun SearchField(
    value: TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    placeholder: String,
    textColor: Color,
    hasError: Boolean,
    inputStyle: TextStyle,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
    onFocused: ((Boolean) -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val theme = LocalZedTheme.current
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
                // Inner `py_1` around the text (buffer_search.rs:233).
                .padding(vertical = 4.dp)
                .pointerHoverIcon(PointerIcon.Text),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = inputStyle.copy(color = textColor),
                cursorBrush = SolidColor(theme.cursor),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .then(
                        if (onFocused != null) Modifier.onFocusChanged { onFocused(it.isFocused) }
                        else Modifier
                    ),
            )
            if (value.text.isEmpty()) {
                Text(
                    text = placeholder,
                    style = inputStyle,
                    color = theme.color("text.placeholder"),
                )
            }
        }
        trailing?.invoke(this)
    }
}

/**
 * Zed's `IconButton` with `IconButtonShape::Square`, drawn with a text glyph
 * where Zed has an SVG: a [ButtonBox] square, `rounded_sm` 4px
 * (button_like.rs:527 via `ButtonLikeRounding::ALL`), `ButtonStyle::Subtle`
 * state colours — `ghost_element.background`, hover `ghost_element.hover`,
 * pressed `ghost_element.active`, disabled `ghost_element.disabled`
 * (button_like.rs:242-243, 298-299, 324-325, 417-418) — swapped instantly,
 * no ripple. Selected keeps the ghost background and turns the glyph
 * `Color::Selected` = `text.accent` (icon_button.rs:243-252; color.rs:108);
 * disabled turns it `text.disabled` (icon_button.rs:243-244).
 */
@Composable
private fun BarButton(
    glyph: String,
    description: String,
    selected: Boolean = false,
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
            .size(ButtonBox)
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClickLabel = description,
                onClick = onClick,
            ),
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

/** A match's byte range, as rows and UTF-16 columns the renderer can draw. */
internal fun EditorState.rangeOf(match: BufferMatch): EditorState.SelectionRange {
    val startRow = match.row
    val startLine = line(startRow)
    val startCol = utf16Col(startLine, match.column)
    // Multi-line matches are rare but a regex can make one; walk forward from
    // the start rather than asking the engine again for every hit.
    var row = startRow
    var remaining = (match.end - match.start).toInt() -
        (utf8Length(startLine) - match.column).coerceAtLeast(0)
    if (remaining <= 0) {
        val endCol = utf16Col(startLine, match.column + (match.end - match.start).toInt())
        return EditorState.SelectionRange(startRow, startCol, startRow, endCol)
    }
    // Each row costs its own newline byte as we cross it.
    while (remaining > 0 && row + 1 < lineCount) {
        row++
        val text = line(row)
        val bytes = utf8Length(text)
        remaining -= 1
        if (remaining <= bytes) return EditorState.SelectionRange(startRow, startCol, row, utf16Col(text, remaining))
        remaining -= bytes
    }
    return EditorState.SelectionRange(startRow, startCol, row, line(row).length)
}
