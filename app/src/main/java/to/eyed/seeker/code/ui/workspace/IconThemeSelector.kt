package to.eyed.seeker.code.ui.workspace

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.core.IconThemeSelection
import to.eyed.seeker.code.ui.theme.IconTheme
import to.eyed.seeker.code.ui.theme.IconThemes
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.revealItem

/**
 * The icon theme selector — Zed's `icon_theme_selector::Toggle`
 * (`theme_selector/src/icon_theme_selector.rs`).
 *
 * The same picker as the theme selector, one list shorter: an icon theme has
 * no light and dark halves to keep separate, so confirming writes one name.
 * Each row previews itself — three file icons drawn from the theme the row
 * names — because "Pastel" tells you nothing and a Rust file, a folder and a
 * JSON file tell you everything.
 */
@Composable
fun IconThemeSelector(
    /** The `icon_theme` value from settings.json. */
    selection: IconThemeSelection,
    /** Write a new `icon_theme` value. */
    onSelectionChange: (IconThemeSelection) -> Unit,
    /** The appearance in force, which a dynamic `icon_theme` chooses by. */
    isDark: Boolean,
    onDismiss: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scan by IconThemes.scan.collectAsState()

    var query by remember { mutableStateOf(TextFieldValue("")) }
    var selected by remember { mutableIntStateOf(0) }
    var importResult by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focus.requestFocus()
        withContext(Dispatchers.IO) { IconThemes.scan(context) }
    }

    val inUse = selection.iconThemeName(isDark)
    val results = remember(scan, query.text) {
        val needle = query.text.trim()
        scan.themes.filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
    }

    LaunchedEffect(results) {
        selected = results.indexOfFirst { it.name == inUse }.coerceAtLeast(0)
    }
    LaunchedEffect(selected) {
        if (selected in results.indices) listState.revealItem(selected)
    }

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val name = context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                IconThemes.import(context, uri, name)
            }
            importResult = outcome.fold(
                onSuccess = { names -> "Imported ${names.joinToString(", ")}." },
                onFailure = { "Could not import: ${it.message}" },
            )
        }
    }

    fun move(delta: Int) {
        if (results.isEmpty()) return
        selected = ((selected + delta) % results.size + results.size) % results.size
    }

    fun confirm() {
        val chosen = results.getOrNull(selected) ?: return
        onSelectionChange(selection.with(chosen.name))
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
                event.key == Key.Enter || event.key == Key.NumPadEnter -> { confirm(); true }
                event.key == Key.Escape -> { onDismiss(); true }
                else -> false
            }
        },
    ) {
        PickerQueryField(
            query = query,
            onQueryChange = { query = it },
            // Zed's own placeholder (icon_theme_selector.rs).
            placeholder = "Select Icon Theme...",
            focusRequester = focus,
        )

        for (problem in scan.problems) {
            Text(
                text = "${problem.fileName}: ${problem.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("error"),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        importResult?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("Could not")) {
                    theme.color("error")
                } else {
                    theme.color("text.muted")
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }

        if (results.isEmpty()) {
            PickerEmptyState("No matches")
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PickerListPadding,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            ) {
                itemsIndexed(results, key = { _, it -> it.name }) { index, iconTheme ->
                    IconThemeRow(
                        iconTheme = iconTheme,
                        isSelected = index == selected,
                        isInUse = iconTheme.name == inUse,
                        onClick = { if (index == selected) confirm() else selected = index },
                    )
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth().height(1.dp)
                .background(theme.color("border.variant")),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        ) {
            GhostButton(
                text = "Import icon theme…",
                isPrimary = false,
                onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) },
            )
            Box(modifier = Modifier.weight(1f))
            GhostButton(text = "Cancel", isPrimary = false, onClick = onDismiss)
            GhostButton(text = "Use icons", isPrimary = true, onClick = { confirm() })
        }
    }
}

/** The three sample files every row previews itself with. */
private val SAMPLES = listOf("main.rs", "package.json", "README.md")

@Composable
private fun IconThemeRow(
    iconTheme: IconTheme,
    isSelected: Boolean,
    isInUse: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    PickerListItem(isSelected = isSelected, onClick = onClick) {
        Text(
            text = iconTheme.name,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("text"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (sample in SAMPLES) {
                Image(
                    painter = FileIcons.forFile(iconTheme, sample),
                    contentDescription = null,
                    colorFilter = if (iconTheme.isBundled) {
                        // The bundled set is monochrome, as Zed draws it: the
                        // icon says what kind of file it is and the row's
                        // colour says what git thinks of it. A user theme
                        // ships its own art and is left alone.
                        ColorFilter.tint(theme.color("text.muted"))
                    } else {
                        null
                    },
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (isInUse) {
            Text(
                text = "in use",
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text.accent"),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
