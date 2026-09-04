package to.eyed.thragg.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.thragg.core.Excerpt
import to.eyed.thragg.core.LanguageSettings
import to.eyed.thragg.core.MultiBufferSession
import to.eyed.thragg.ui.theme.LocalZedTheme

/**
 * A multibuffer tab: the ordinary editor over the engine's composed buffer,
 * with the header of the excerpt currently at the top of the viewport pinned
 * above it.
 *
 * Zed draws its per-file headers as *block decorations* inside the editor
 * (editor/src/element.rs, `render_buffer_header`), sticking the topmost one to
 * the top of the pane. Our renderer has no block rows, so the engine writes
 * the header as a real row of the composition — `// src/main.rs:12-18`, a
 * comment in the file's own language when they all share one — and refuses
 * edits that touch it. This bar is the sticky half: it names the file the rows
 * under the top edge came from, and tapping it opens that file at that spot,
 * which is Zed's `editor::OpenExcerpts` (alt-enter in
 * assets/keymaps/default-linux.json:915) reachable by touch.
 */
@Composable
fun MultiBufferPane(
    state: EditorState,
    multibuffer: MultiBufferSession,
    /** Open the file behind an excerpt, with the caret on [row] of it. */
    onOpenExcerpt: (path: String, row: Int) -> Unit,
    onSaveAll: () -> Unit,
    /** The buffer's resolved settings, as any other pane takes them. */
    languageSettings: LanguageSettings,
    showInlineBlame: Boolean,
    onOpenDefinition: (DefinitionTarget) -> Unit,
    onWorkspaceEditApplied: (EditReceipt) -> Unit,
    onRenameSymbol: () -> Unit,
    onOpenReferences: ((List<ReferenceTarget>) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        EditorPane(
            state = state,
            modifier = Modifier.fillMaxSize(),
            languageSettings = languageSettings,
            showInlineBlame = showInlineBlame,
            // Vim's `:w` over a multibuffer is Zed's SaveAll, as Ctrl+S is.
            onSaveFile = {
                onSaveAll()
                true
            },
            onOpenDefinition = onOpenDefinition,
            onWorkspaceEditApplied = onWorkspaceEditApplied,
            onRenameSymbol = onRenameSymbol,
            onOpenReferences = onOpenReferences,
        )
        // Recomputed only when the row under the top edge crosses into another
        // excerpt — a scroll inside one excerpt invalidates nothing.
        val sticky by remember(state, multibuffer) {
            derivedStateOf {
                // `revision` is read for its own sake: the display map is not
                // snapshot state, so an edit that moved the rows would leave
                // this cached against the old ones without it.
                @Suppress("UNUSED_EXPRESSION") state.revision
                val display = if (state.lineHeightPx > 0f) {
                    (state.scrollY / state.lineHeightPx).toInt()
                } else {
                    0
                }
                multibuffer.info.stickyAt(state.displayMap.bufferRowOf(display))
            }
        }
        sticky?.let { excerpt ->
            ExcerptHeaderBar(
                excerpt = excerpt,
                onClick = { onOpenExcerpt(excerpt.path, excerpt.fileStartRow) },
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}

/**
 * The pinned header. `tab_bar.background` with the `border.variant` underline
 * every Zed strip has, so it reads as chrome over the text rather than as a
 * row of it.
 */
@Composable
private fun ExcerptHeaderBar(
    excerpt: Excerpt,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.color("tab_bar.background"))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = excerpt.path,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = theme.color("text"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = "${excerpt.fileStartRow + 1}–${excerpt.fileEndRow + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted"),
                maxLines = 1,
            )
            if (excerpt.dirty) {
                Text(
                    text = "●",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.accent"),
                )
            }
        }
        HorizontalDivider(
            color = theme.color("border.variant"),
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}
