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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.core.Toolchain
import to.eyed.seeker.code.core.Toolchains
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.revealItem

/**
 * Zed's toolchain selector — `toolchain::Select`
 * (crates/toolchain_selector), reached from the palette and from the status
 * bar's toolchain item, which is what Zed's `ActiveToolchain` item does when
 * it is clicked (active_toolchain.rs).
 *
 * The list is the project's virtualenvs, poetry's environment, rustup's
 * toolchains and the guest's own `python3`/`cargo`. Confirming one makes it
 * the project's toolchain for that *language* — a Python choice does not
 * disturb a Rust one — and restarts the language servers, since a server
 * already running holds the old environment.
 *
 * The first row is always "None", which clears every language's choice: a
 * toolchain picked once must be un-pickable without editing a file by hand,
 * and a row that says the project has no toolchain can mean nothing else.
 */
@Composable
fun ToolchainSelector(
    projectId: Long,
    onSelected: (Toolchain?) -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalZedTheme.current
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var toolchains by remember { mutableStateOf(emptyList<Toolchain>()) }
    var active by remember { mutableStateOf(emptyList<Toolchain>()) }
    var loaded by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    LaunchedEffect(projectId) {
        focus.requestFocus()
        // Detection runs `poetry` and `rustup` inside the userland — seconds,
        // not milliseconds, and never on the thread the picker is drawn on.
        val found = withContext(Dispatchers.IO) {
            Toolchains.available(projectId) to Toolchains.active(projectId)
        }
        toolchains = found.first
        active = found.second
        loaded = true
    }

    val results = remember(toolchains, query.text) { matchToolchains(toolchains, query.text) }
    LaunchedEffect(query.text) { selected = 0 }
    LaunchedEffect(selected, results) {
        // Row 0 is "None", so a result's row is one further down.
        if (selected in 0..results.size) listState.revealItem(selected)
    }

    fun move(delta: Int) {
        val size = results.size + 1
        selected = ((selected + delta) % size + size) % size
    }

    fun confirm() {
        if (selected == 0) {
            onSelected(null)
        } else {
            val choice = results.getOrNull(selected - 1) ?: return
            onSelected(choice)
        }
        onDismiss()
    }

    PickerModal(
        onDismiss = onDismiss,
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                event.key == Key.DirectionDown -> { move(1); true }
                event.key == Key.DirectionUp -> { move(-1); true }
                event.isCtrlPressed && event.key == Key.N -> { move(1); true }
                event.isCtrlPressed && event.key == Key.P -> { move(-1); true }
                event.key == Key.Tab -> {
                    move(if (event.isShiftPressed) -1 else 1)
                    true
                }
                event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                    confirm()
                    true
                }
                event.key == Key.Escape -> { onDismiss(); true }
                else -> false
            }
        },
    ) {
        PickerQueryField(
            query = query,
            onQueryChange = { query = it },
            // Zed's own placeholder (toolchain_selector.rs).
            placeholder = "Select a toolchain…",
            focusRequester = focus,
        )

        LazyColumn(
            state = listState,
            contentPadding = PickerListPadding,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        ) {
            item(key = "none") {
                PickerListItem(
                    isSelected = selected == 0,
                    onClick = { if (selected == 0) confirm() else selected = 0 },
                ) {
                    Text(
                        text = "None",
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.color("text.muted"),
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (active.isEmpty() && loaded) PickerCheckMark()
                }
            }
            itemsIndexed(results, key = { _, it -> it.path }) { index, toolchain ->
                PickerListItem(
                    isSelected = index + 1 == selected,
                    onClick = {
                        if (index + 1 == selected) confirm() else selected = index + 1
                    },
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = toolchain.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = theme.color("text"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Zed shows the interpreter's path under its name,
                        // because two virtualenvs are told apart by nothing
                        // else (toolchain_selector.rs, the row's subtitle).
                        Text(
                            text = toolchain.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.color("text.muted"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (active.any { it.path == toolchain.path }) PickerCheckMark()
                }
            }
            if (results.isEmpty()) {
                item(key = "empty") {
                    PickerEmptyState(
                        if (!loaded) {
                            "Looking for toolchains"
                        } else if (toolchains.isEmpty()) {
                            "No virtualenv or rustup toolchain in this project"
                        } else {
                            "No matches"
                        }
                    )
                }
            }
        }
    }
}

/**
 * The selector's list for a query: every toolchain whose name or path holds
 * the query as a case-insensitive subsequence, in the engine's order — which
 * is the local virtualenvs first, then poetry, rustup and the system ones.
 *
 * Pure, so it is unit-tested (`ToolchainSelectorTest`) rather than only ever
 * exercised by opening the picker.
 */
fun matchToolchains(toolchains: List<Toolchain>, query: String): List<Toolchain> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return toolchains
    return toolchains.filter {
        isSubsequence(it.name, trimmed) || isSubsequence(it.path, trimmed)
    }
}

private fun isSubsequence(haystack: String, query: String): Boolean {
    var at = 0
    for (character in query) {
        val found = haystack.indexOf(character, at, ignoreCase = true)
        if (found < 0) return false
        at = found + 1
    }
    return true
}

/**
 * The name the status bar prints for a project's toolchains.
 *
 * Zed's `ActiveToolchain` item follows the active buffer's language
 * (active_toolchain.rs:256-266), so the choice made for the open file's
 * language wins when there is one. Where Zed then renders nothing, this
 * falls back to the first choice: on a phone the status-bar item is the only
 * touchable way back to the picker, and a `.venv` is still the environment
 * `pytest` would run in while a README is the tab in front.
 *
 * [language] is the display name the status bar shows — "Python", not
 * "python" — which is how a toolchain names its language too. Pure, so it is
 * unit-tested (`ToolchainSelectorTest`).
 */
fun statusBarToolchain(active: List<Toolchain>, language: String?): String? =
    (active.firstOrNull { it.language == language } ?: active.firstOrNull())?.name
