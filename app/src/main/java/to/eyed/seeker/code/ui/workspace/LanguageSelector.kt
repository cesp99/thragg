package to.eyed.seeker.code.ui.workspace

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
import to.eyed.seeker.code.core.LanguageChoice
import to.eyed.seeker.code.core.Languages
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.revealItem

/** One row of the language selector: a language and where the query matched. */
data class LanguageMatch(
    val language: LanguageChoice,
    /** Indices into [LanguageChoice.name] that matched, for highlighting. */
    val positions: List<Int>,
)

/**
 * Zed's language selector — `language_selector::Toggle`, `ctrl-k m`
 * (default-linux.json) — reached here from the palette and from the status
 * bar's language item, exactly as Zed's `ActiveBufferLanguage` dispatches it
 * (language_selector/src/active_buffer_language.rs:75-84).
 *
 * A searchable picker of every language the engine can parse, with a check on
 * the one the buffer is using. Confirming assigns the grammar to the buffer
 * and re-parses it — Zed's `LanguageSelectorDelegate::confirm`, which is a
 * *buffer* override and does not touch the file or settings.
 */
@Composable
fun LanguageSelector(
    /** The grammar the buffer is on now, e.g. "rust"; null for none. */
    current: String?,
    onSelect: (LanguageChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalZedTheme.current
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var languages by remember { mutableStateOf(emptyList<LanguageChoice>()) }
    var selected by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focus.requestFocus()
        // One JNI hop and a JSON parse, cached for the process — off the main
        // thread all the same, because the picker is opening on it.
        languages = withContext(Dispatchers.Default) { Languages.all() }
    }

    val results = remember(languages, query.text) { matchLanguages(languages, query.text) }

    // Start on the language in use, as Zed's pickers do, so opening this and
    // pressing Enter changes nothing.
    LaunchedEffect(languages) {
        selected = results.indexOfFirst { it.language.grammar == current }.coerceAtLeast(0)
    }
    LaunchedEffect(query.text) { selected = 0 }
    LaunchedEffect(selected, results) {
        if (selected in results.indices) listState.revealItem(selected)
    }

    fun move(delta: Int) {
        if (results.isEmpty()) return
        val size = results.size
        selected = ((selected + delta) % size + size) % size
    }

    fun confirm() {
        val choice = results.getOrNull(selected)?.language ?: return
        onSelect(choice)
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
            // Zed's own placeholder (language_selector.rs:216-218).
            placeholder = "Select a language…",
            focusRequester = focus,
        )

        if (results.isEmpty()) {
            PickerEmptyState(if (languages.isEmpty()) "Loading languages" else "No matches")
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PickerListPadding,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            ) {
                itemsIndexed(results, key = { _, it -> it.language.grammar }) { index, match ->
                    PickerListItem(
                        isSelected = index == selected,
                        onClick = {
                            if (index == selected) confirm() else selected = index
                        },
                    ) {
                        Text(
                            text = match.language.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = theme.color("text"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (match.language.grammar == current) {
                            // Zed's end slot for the language in use:
                            // `IconName::Check` in `Color::Muted`
                            // (language_selector.rs:300-305).
                            PickerCheckMark()
                        }
                    }
                }
            }
        }
    }
}

/**
 * The selector's list for a query: every language whose display name holds
 * the query as a case-insensitive subsequence, best first.
 *
 * Ranking is the one every picker in this app uses and Zed's `fuzzy` crate
 * agrees with on names this short: a prefix beats a match starting later, a
 * contiguous run beats a scattered one, and ties keep the engine's order —
 * which is alphabetical, so the list is stable and predictable.
 *
 * Pure, so it is unit-tested (`LanguageSelectorTest`) rather than only ever
 * exercised by opening the picker.
 */
fun matchLanguages(languages: List<LanguageChoice>, query: String): List<LanguageMatch> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return languages.map { LanguageMatch(it, emptyList()) }
    return languages
        .mapNotNull { language ->
            nameSubsequence(language.name, trimmed)?.let { LanguageMatch(language, it) }
        }
        .sortedWith(
            compareBy(
                { it.positions.first() },
                { it.positions.last() - it.positions.first() },
                { it.language.name.length },
            )
        )
}

/** Case-insensitive subsequence positions, or null if [query] isn't one. */
private fun nameSubsequence(name: String, query: String): List<Int>? {
    val positions = ArrayList<Int>(query.length)
    var at = 0
    for (character in query) {
        val found = name.indexOf(character, at, ignoreCase = true)
        if (found < 0) return null
        positions += found
        at = found + 1
    }
    return positions
}
