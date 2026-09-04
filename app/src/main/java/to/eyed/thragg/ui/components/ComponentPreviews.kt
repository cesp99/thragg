@file:OptIn(ExperimentalMaterial3Api::class)

package to.eyed.thragg.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.eyed.thragg.R
import to.eyed.thragg.core.AppSettings
import to.eyed.thragg.core.ThemeMode
import to.eyed.thragg.core.ThemeSelection
import to.eyed.thragg.ui.theme.LocalSeekerColors
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.ThraggTheme

/**
 * Every component in the library, drawn twice.
 *
 * ONE DARK AND AYU LIGHT, and the second one is not decoration: Ayu Light is
 * the theme that breaks things. Its raw `text.muted` measures 2.79:1 on a
 * panel, its accent 2.84:1 on the editor ground, its `created` 2.11:1 and its
 * `warning` 1.64:1 — every one of those is a component in this file drawn with
 * a label nobody can read, unless the bridge's solver did its job. A pair of
 * previews is the cheapest possible standing check that it did, and the only
 * one that shows the answer rather than asserting it.
 *
 * The previews live together rather than one pair per component file. The
 * argument for splitting them is locality; the argument against is that a
 * gallery is the thing you actually want to look at when you change a token —
 * `MD.space3`, a radius, an ink — because it shows every component moving at
 * once, and twenty separate previews show you one. `@Preview` costs nothing at
 * runtime beyond a couple of never-called functions, and the tooling artifact
 * is `implementation`, not `debugImplementation`, so this compiles in release
 * as well.
 *
 * Each host renders the real [ThraggTheme] rather than a stub, so
 * what a preview shows is what the bridge produced from the theme's own JSON —
 * a preview painted from hand-picked colours would be a picture of a
 * different app.
 */
@Composable
private fun PreviewHost(dark: Boolean, content: @Composable () -> Unit) {
    ThraggTheme(
        settings = AppSettings(
            themeSelection = ThemeSelection(
                mode = if (dark) ThemeMode.Dark else ThemeMode.Light,
                light = "Ayu Light",
                dark = "One Dark",
            ),
        ),
    ) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(MD.space4)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MD.space3),
            content = { content() },
        )
    }
}

// ---------------------------------------------------------------------------
// The gallery: one body, two hosts. Every pair below is the same call.
// ---------------------------------------------------------------------------

@Preview(name = "Components — One Dark", widthDp = 400, showBackground = true)
@Composable
private fun ComponentsDarkPreview() {
    PreviewHost(dark = true) { Gallery() }
}

@Preview(name = "Components — Ayu Light", widthDp = 400, showBackground = true)
@Composable
private fun ComponentsLightPreview() {
    PreviewHost(dark = false) { Gallery() }
}

@Preview(name = "Selectors — One Dark", widthDp = 400, showBackground = true)
@Composable
private fun SelectorsDarkPreview() {
    PreviewHost(dark = true) { Selectors() }
}

@Preview(name = "Selectors — Ayu Light", widthDp = 400, showBackground = true)
@Composable
private fun SelectorsLightPreview() {
    PreviewHost(dark = false) { Selectors() }
}

@Preview(name = "Bottom bar — One Dark", widthDp = 400, heightDp = 320, showBackground = true)
@Composable
private fun BottomBarDarkPreview() {
    PreviewHost(dark = true) { PinnedBar() }
}

@Preview(name = "Bottom bar — Ayu Light", widthDp = 400, heightDp = 320, showBackground = true)
@Composable
private fun BottomBarLightPreview() {
    PreviewHost(dark = false) { PinnedBar() }
}

@Preview(name = "Notices — One Dark", widthDp = 400, showBackground = true)
@Composable
private fun NoticesDarkPreview() {
    PreviewHost(dark = true) { Notices() }
}

@Preview(name = "Notices — Ayu Light", widthDp = 400, showBackground = true)
@Composable
private fun NoticesLightPreview() {
    PreviewHost(dark = false) { Notices() }
}

/** Chrome, marks and readouts: the pieces that appear on every screen. */
@Composable
private fun Gallery() {
    val colors = LocalSeekerColors.current
    SeekerTopBar(
        title = "seeker-ide",
        subtitle = "Spettro · coding",
        onBack = {},
        actions = {},
    )
    SectionHeader(text = "Marks", icon = R.drawable.ic_ui_sparkles)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space3),
    ) {
        SeekerSpinner()
        StatusDot(color = MaterialTheme.colorScheme.onSurfaceVariant)
        StatusDot(color = colors.warnMark, pulsing = true)
        DiffStatLabel(added = 24, removed = 6)
        ModeChip(name = "coding", colorName = "green")
    }
    RunTicker(startedAt = remember { System.currentTimeMillis() - 64_000L }, tokens = 12_300L)
    HairlineDivider()
    SectionHeader(text = "Chips")
    Row(horizontalArrangement = Arrangement.spacedBy(MD.space2)) {
        SeekerChip(label = "lib.rs", onClick = {}, leading = R.drawable.ic_file_rust)
        SeekerChip(label = "main", onClick = {}, tint = colors.accentInk)
        SeekerChip(label = "Off", onClick = {}, enabled = false)
    }
    CopyChip(text = "cargo build-sbf")
    SeekerCard {
        Column(modifier = Modifier.padding(MD.space3)) {
            Text(
                text = "A card is a fill step and a hairline.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    ZedCodeBlock(text = "fn main() {\n    println!(\"hello\");\n}", language = "rust")
    EmptyState(
        headline = "No thread open",
        body = "Start one to talk to Spettro.",
        action = { Button(onClick = {}) { Text("New thread") } },
    )
}

/**
 * The pinned bar and its seam, which is the only component you cannot judge
 * from a still of the component alone.
 *
 * [BottomActions] is three decisions and two of them are about the thing
 * ABOVE it, so the preview draws the thing above it: a list tall enough to
 * run under the bar, wearing [fadeUnderBottomActions] and ending in a
 * [BottomActionsGap]. What you are looking for is the last visible row
 * DISSOLVING into the hairline rather than being cut across the middle of its
 * glyphs — the defect this component exists to remove, and the one that is
 * invisible in a screenshot of a bar on its own.
 *
 * A fixed `heightDp` on the two `@Preview`s, deliberately: the fade only says
 * anything when the content overflows, and a preview that wraps its content
 * would show the honest-at-rest case where there is nothing under the
 * gradient to dim.
 */
@Composable
private fun PinnedBar() {
    Column(modifier = Modifier.height(200.dp)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .fadeUnderBottomActions()
                .verticalScroll(rememberScrollState()),
        ) {
            repeat(8) { i ->
                Text(
                    text = "A row that has to survive the bar — ${i + 1}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = MD.space2),
                )
            }
            Spacer(Modifier.height(BottomActionsGap))
        }
        BottomActions {
            Row(horizontalArrangement = Arrangement.spacedBy(MD.space3)) {
                Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Create") }
                TextButton(onClick = {}, modifier = Modifier.weight(1f)) { Text("Cancel") }
            }
        }
    }
}

/** Everything that takes an answer from the user. */
@Composable
private fun Selectors() {
    var mode by remember { mutableStateOf("coding") }
    var level by remember { mutableStateOf("high") }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf("sonnet") }

    SectionHeader(text = "Mode")
    SegmentedSelect(
        options = listOf(
            Choice("ask", "ask", "Answers without touching the tree."),
            Choice("coding", "coding", "Edits files and runs commands as it goes."),
            Choice("plan", "plan", "Writes a plan first and waits for you."),
        ),
        selectedValue = mode,
        onSelect = { mode = it },
    )
    SectionHeader(text = "Thinking")
    LevelSlider(
        choices = listOf(
            Choice("off", "off", "No reasoning pass."),
            Choice("low", "low", "A short think before acting."),
            Choice("medium", "medium", "Reasons about each step."),
            Choice("high", "high", "Long chains of reasoning before each edit. Slower."),
            Choice("ultra", "ultra", "Everything it has. Slowest."),
        ),
        selectedValue = level,
        onSelect = { level = it },
    )
    SectionHeader(text = "Rows")
    SelectRow(
        label = "Ask each time",
        description = "Every tool call waits for you.",
        selected = picked == "ask",
        onSelect = { picked = "ask" },
    )
    SelectRow(
        label = "Ask once each",
        description = "The first of a kind asks; the rest follow it.",
        selected = picked == "once",
        onSelect = { picked = "once" },
    )
    SeekerSearchField(
        value = query,
        onValueChange = { query = it },
        placeholder = "Search models…",
    )
    DrillPage(
        title = "Model",
        groups = listOf(
            Group(
                "Anthropic",
                listOf(
                    Choice("sonnet", "Claude Sonnet 4.6", "Balanced for long coding turns"),
                    Choice("opus", "Claude Opus 4.6", "Deepest reasoning; slowest and dearest"),
                ),
            ),
            Group("OpenAI", listOf(Choice("gpt5", "GPT-5", null))),
        ),
        currentValue = picked,
        onSelect = { picked = it.value },
        onBack = {},
        // Bounded, because a preview measures with an infinite maximum height
        // and the page's LazyColumn cannot be measured against one.
        modifier = Modifier.fillMaxWidth().height(320.dp),
        valueOf = { it.value },
        searchText = { "${it.name} ${it.value} ${it.description.orEmpty()}" },
        row = { choice, selected ->
            DrillRow(name = choice.name, description = choice.description, selected = selected)
        },
    )
}

/** The three tiers of the error channel, and the selected-card state. */
@Composable
private fun Notices() {
    var chosen by remember { mutableStateOf(true) }
    NoticeCard(
        severity = Severity.Info,
        title = "Compacted",
        body = "The thread was summarised to free space.",
        onDismiss = {},
    )
    NoticeCard(
        severity = Severity.Warn,
        title = "Nearly full",
        body = "New messages may be refused. Compact the thread to summarise it " +
            "and free space, or start a new one.",
        actions = {
            Button(onClick = {}) { Text("Compact thread") }
            TextButton(onClick = {}) { Text("New thread") }
        },
    )
    NoticeCard(
        severity = Severity.Error,
        title = null,
        body = "cargo build-sbf failed with 2 errors.",
        actions = { TextButton(onClick = {}) { Text("Retry") } },
    )
    SelectableCard(
        selected = chosen,
        onSelect = { chosen = !chosen },
        modifier = Modifier.width(220.dp),
    ) {
        Column(modifier = Modifier.padding(MD.space3)) {
            Text(
                text = "Selection is a border change",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
