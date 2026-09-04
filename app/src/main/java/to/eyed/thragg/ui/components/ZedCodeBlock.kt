package to.eyed.thragg.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import to.eyed.thragg.ui.theme.LocalZedTheme
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.MonoBody

/**
 * A Zed island inside a Material sheet: the buffer's face, the buffer's
 * colours, and an edge that belongs to the sheet.
 *
 * THIS IS THE ONLY COMPONENT IN THE LIBRARY THAT READS `LocalZedTheme`, and it
 * must stay the only one — that is the seam contract, and a reviewer should be
 * able to check it with one grep over `ui/components/`. Everything else here
 * reads `MaterialTheme.colorScheme` and `LocalSeekerColors`.
 *
 * It exists because the same snippet was being drawn eleven different ways in
 * `FontFamily.Monospace` — the SYSTEM mono, not the user's buffer face — over
 * Material ink (`WorkflowCard.kt`'s MonoSheet, OrchBits, BuildLogView's six
 * sites, ChangesScreen, CommitSheet). The result is that the same file looks
 * like two different files two taps apart. Here the fill is
 * `editor.background`, the ink is `editor.foreground`, the face is the user's
 * buffer family with its feature settings, and the syntax spans are the
 * theme's own — so the block agrees with the editor because it IS the editor's
 * palette.
 *
 * THE BORDER IS MATERIAL, ON PURPOSE: 1dp of `outlineVariant` at 8dp radius.
 * The island's edge belongs to the sheet, and that hairline is exactly what
 * stops a dark block on a dark sheet reading as a hole punched through it.
 *
 * NO SOFT WRAP BY DEFAULT. Code that wraps at 400dp is code you cannot read
 * the shape of; it scrolls horizontally instead, which is what every terminal
 * and every diff view on this device does. [wrap] is there for prose-shaped
 * payloads — a commit message body, an error paragraph — where the opposite is
 * true.
 *
 * [copyable] draws a [CopyChip] in a header strip, which is also where
 * [language] prints when the caller knows it. With neither, no header is drawn
 * at all and the block is just the code.
 */
@Composable
fun ZedCodeBlock(
    text: String,
    modifier: Modifier = Modifier,
    spans: List<AnnotatedString.Range<SpanStyle>>? = null,
    language: String? = null,
    maxLines: Int = Int.MAX_VALUE,
    wrap: Boolean = false,
    copyable: Boolean = true,
) {
    val theme = LocalZedTheme.current
    val ground = theme.color("editor.background")
    val ink = theme.color("editor.foreground")
    val annotated = remember(text, spans) {
        if (spans.isNullOrEmpty()) AnnotatedString(text) else AnnotatedString(text, spans)
    }
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(MD.radiusSm))
            .background(ground)
            .border(
                MD.hairline,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(MD.radiusSm),
            ),
    ) {
        if (language != null || copyable) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = MD.space2, end = MD.space1, top = MD.space1),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = language.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    // The header sits ON the island, so its ink is the
                    // editor's, dimmed — a Material onSurfaceVariant here
                    // would be solved against a surface this block is not on.
                    color = ink.copy(alpha = 0.6f),
                    maxLines = 1,
                )
                if (copyable) CopyChip(text = text)
            }
        }
        SelectionContainer {
            Text(
                text = annotated,
                style = MonoBody.copy(color = ink),
                softWrap = wrap,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (wrap) Modifier else Modifier.horizontalScroll(scroll))
                    .padding(horizontal = MD.space2, vertical = MD.space2),
            )
        }
    }
}
