package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import to.eyed.seeker.code.core.CoreBridge
import to.eyed.seeker.code.ui.editor.DefinitionTarget
import to.eyed.seeker.code.ui.editor.LspRequestState
import to.eyed.seeker.code.ui.editor.matchScore
import to.eyed.seeker.code.ui.editor.pollLspRequest
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.revealItem

/**
 * Zed's project symbols picker — `project_symbols::Toggle` on Ctrl+T
 * (default-linux.json:691; crates/project_symbols/src/project_symbols.rs).
 *
 * Every keystroke asks `workspace/symbol` of every running server with the
 * query typed so far — the servers do the searching, as in Zed — and what
 * comes back is ranked once more here with the completion menu's fuzzy
 * scorer, which is Zed's own second pass (`fuzzy::match_strings` over the
 * symbol names, project_symbols.rs `update_matches`). Enter opens the file
 * with the caret on the symbol; a tap on a row does the same.
 */

/** Zed asks on every change; a moment's debounce collapses a burst of typing. */
private const val QUERY_DEBOUNCE_MILLIS = 120L

/** One symbol, as `lspRequestWorkspaceSymbols` lists it. */
internal data class WorkspaceSymbol(
    val name: String,
    val kind: String,
    val container: String?,
    /** Project-relative where the engine could make it so; else absolute. */
    val path: String,
    val absolutePath: String,
    val row: Int,
    val col: Int,
    val endRow: Int,
    val endCol: Int,
    val server: String,
) {
    fun asDefinition(): DefinitionTarget = DefinitionTarget(absolutePath, row, col, endRow, endCol)
}

internal fun parseWorkspaceSymbols(payload: JSONObject?): List<WorkspaceSymbol> {
    val array = payload?.optJSONArray("symbols") ?: return emptyList()
    val symbols = ArrayList<WorkspaceSymbol>(array.length())
    for (i in 0 until array.length()) {
        val entry = array.optJSONObject(i) ?: continue
        val name = entry.optString("name", "")
        val absolute = entry.optString("absolute_path", "")
        if (name.isEmpty() || absolute.isEmpty()) continue
        val row = entry.optInt("row", 0)
        val col = entry.optInt("col_utf16", 0)
        symbols.add(
            WorkspaceSymbol(
                name = name,
                kind = entry.optString("kind", "symbol"),
                container = if (entry.isNull("container")) null else entry.optString("container").takeIf { it.isNotEmpty() },
                path = entry.optString("path", absolute),
                absolutePath = absolute,
                row = row,
                col = col,
                endRow = entry.optInt("end_row", row),
                endCol = entry.optInt("end_col_utf16", col),
                server = entry.optString("server", ""),
            )
        )
    }
    return symbols
}

/**
 * Zed's second pass: the servers' answers, ranked by how well [query]
 * matches each name, best first; an empty query keeps the servers' order.
 * Names the query does not match at all drop out — a server that answers
 * a prefix query with its whole index is answered with a shorter list.
 */
internal fun rankSymbols(symbols: List<WorkspaceSymbol>, query: String): List<WorkspaceSymbol> {
    if (query.isBlank()) return symbols
    val smartCase = query.any(Char::isUpperCase)
    return symbols
        .mapNotNull { symbol -> matchScore(symbol.name, query, smartCase)?.let { symbol to it } }
        .sortedWith(compareByDescending<Pair<WorkspaceSymbol, Double>> { it.second }.thenBy { it.first.name })
        .map { it.first }
}

@Composable
fun ProjectSymbolsPicker(
    projectId: Long,
    onOpen: (DefinitionTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var answered by remember { mutableStateOf<List<WorkspaceSymbol>?>(null) }
    var noServer by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }

    // The ask: every settled query goes to the servers, and the previous
    // request is superseded at the engine — `collectLatest` cancels the
    // poll of an answer nobody will read.
    LaunchedEffect(projectId) {
        snapshotFlow { query.text }.collectLatest { text ->
            delay(QUERY_DEBOUNCE_MILLIS)
            val id = withContext(Dispatchers.Default) {
                CoreBridge.lspRequestWorkspaceSymbols(projectId, text)
            }
            val answer = pollLspRequest(id) ?: return@collectLatest
            when (answer.state) {
                LspRequestState.Done -> {
                    noServer = false
                    answered = parseWorkspaceSymbols(answer.payload)
                    selected = 0
                }
                LspRequestState.Unavailable -> {
                    noServer = true
                    answered = emptyList()
                }
                else -> {}
            }
        }
    }

    val shown = remember(answered, query.text) { rankSymbols(answered.orEmpty(), query.text) }
    LaunchedEffect(shown) {
        if (selected >= shown.size) selected = 0
    }
    LaunchedEffect(selected) {
        if (selected in shown.indices) listState.revealItem(selected)
    }

    fun confirm() {
        val symbol = shown.getOrNull(selected) ?: return
        onDismiss()
        onOpen(symbol.asDefinition())
    }

    fun move(delta: Int) {
        if (shown.isEmpty()) return
        val size = shown.size
        selected = ((selected + delta) % size + size) % size
    }

    PickerModal(
        onDismiss = onDismiss,
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionDown -> { move(1); true }
                Key.DirectionUp -> { move(-1); true }
                Key.Enter, Key.NumPadEnter -> { confirm(); true }
                Key.Escape -> { onDismiss(); true }
                else -> false
            }
        },
    ) {
        PickerQueryField(
            query = query,
            onQueryChange = { query = it },
            // Zed's own placeholder (project_symbols.rs).
            placeholder = "Search project symbols...",
            focusRequester = focus,
        )

        if (shown.isEmpty()) {
            PickerEmptyState(
                when {
                    noServer -> "No language server is running"
                    answered == null -> "Asking the language servers…"
                    query.text.isEmpty() -> "No symbols yet — type to search"
                    else -> "No matches"
                }
            )
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PickerListPadding,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            ) {
                itemsIndexed(shown, key = { index, symbol -> "${symbol.absolutePath}:${symbol.row}:${symbol.col}:$index" }) { index, symbol ->
                    SymbolRow(
                        symbol = symbol,
                        isSelected = index == selected,
                        onClick = {
                            selected = index
                            confirm()
                        },
                    )
                }
            }
        }
    }
}

/**
 * One symbol as Zed's picker lists it (project_symbols.rs `render_match`):
 * the name, then the path in muted text under it — the container in front
 * of the name where the server gave one.
 */
@Composable
private fun SymbolRow(symbol: WorkspaceSymbol, isSelected: Boolean, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    PickerListItem(isSelected = isSelected, onClick = onClick) {
        Column {
            Text(
                text = if (symbol.container != null) "${symbol.container} › ${symbol.name}" else symbol.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) theme.color("text") else theme.color("text.muted"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${symbol.kind} · ${symbol.path}:${symbol.row + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
