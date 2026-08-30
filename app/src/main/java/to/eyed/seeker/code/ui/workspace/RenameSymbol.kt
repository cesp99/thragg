package to.eyed.seeker.code.ui.workspace

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.core.CoreBridge
import to.eyed.seeker.code.ui.editor.EditReceipt
import to.eyed.seeker.code.ui.editor.EditSummary
import to.eyed.seeker.code.ui.editor.EditorState
import to.eyed.seeker.code.ui.editor.LspRequestState
import to.eyed.seeker.code.ui.editor.requestRename
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.rem

/**
 * Rename symbol — Zed's `editor::Rename`, on `F2`.
 *
 * Zed renames inline, in a little editor floated over the name itself; a
 * phone's soft keyboard would cover exactly that spot, so this is the
 * GoToLine panel's shape instead: a small surface at the top of the work
 * area, prefilled with the name under the caret, Enter to rename and Escape
 * to leave everything alone.
 *
 * Nothing changes until the server has answered *and* the engine has applied
 * the whole edit: the status line walks through asking and applying, an
 * error keeps the dialog open with the sentence, and success hands the
 * receipt to the workspace so every touched editor is resynced.
 */
@Composable
internal fun RenameSymbol(
    editor: EditorState,
    /** The receipt goes here on success; the workspace resyncs its editors. */
    onApplied: (EditReceipt) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val scope = rememberCoroutineScope()
    val originalName = remember(editor) { editor.wordUnderCaret() }
    var query by remember(editor) {
        // Selected whole, so typing replaces the old name — the convention
        // every rename field follows.
        mutableStateOf(TextFieldValue(originalName, TextRange(0, originalName.length)))
    }
    var working by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    // Where the caret sat when the dialog opened. The rename is asked at that
    // position even if the panel's field takes the focus — the caret does not
    // move while the dialog is up.
    val row = remember(editor) { editor.cursorRow }
    val col = remember(editor) { editor.cursorCol }

    LaunchedEffect(editor) { focus.requestFocus() }

    fun confirm() {
        if (working) return
        val newName = query.text.trim()
        if (newName.isEmpty() || newName == originalName) {
            onDismiss()
            return
        }
        working = true
        note = null
        scope.launch {
            val session = editor.sessionOrNull
            if (session == null) {
                onDismiss()
                return@launch
            }
            val answer = requestRename(session.id, row, col, newName)
            if (answer == null || answer.state != LspRequestState.Done) {
                working = false
                note = when (answer?.state) {
                    LspRequestState.Timeout -> "The server did not answer in time"
                    else -> "No server can rename here"
                }
                return@launch
            }
            val summary = EditSummary.parse(answer.payload)
            when {
                summary.error != null -> {
                    working = false
                    note = summary.error
                }
                summary.resourceOps -> {
                    working = false
                    note = "This rename moves files, which is not supported yet"
                }
                summary.isEmpty -> {
                    working = false
                    note = "Nothing to rename"
                }
                else -> {
                    // The engine edits buffers whatever happens next, so the
                    // receipt that names them must land even through a
                    // cancellation.
                    val receipt = withContext(NonCancellable + Dispatchers.IO) {
                        EditReceipt.parse(CoreBridge.lspApplyPendingEdit(answer.id))
                    }
                    if (receipt.files.isNotEmpty()) onApplied(receipt)
                    if (!receipt.applied || receipt.error != null) {
                        working = false
                        note = receipt.error ?: "Nothing was applied"
                    } else {
                        onDismiss()
                    }
                }
            }
        }
    }

    val statusText = when {
        working -> "Renaming…"
        note != null -> note!!
        else -> "Rename \"$originalName\" everywhere"
    }

    Column(
        modifier = modifier
            .padding(8.dp)
            .widthIn(max = rem(24f))
            .clip(RoundedCornerShape(8.dp))
            .background(theme.color("elevated_surface.background"))
            .border(1.dp, theme.color("border.variant"), RoundedCornerShape(8.dp))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> {
                        onDismiss()
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        confirm()
                        true
                    }
                    else -> false
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .pointerHoverIcon(PointerIcon.Text),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { value ->
                        query = value
                        note = null
                    },
                    singleLine = true,
                    readOnly = working,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { confirm() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = theme.color("text"),
                    ),
                    cursorBrush = SolidColor(theme.cursor),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (query.text.isEmpty()) {
                    Text(
                        text = "New name",
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.color("text.placeholder"),
                        maxLines = 1,
                    )
                }
            }
            RenameAction("↵", "Rename", onClick = ::confirm)
            RenameAction("✕", "Cancel", onClick = onDismiss)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(theme.color("border.variant")),
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            // An error reads as one; the rest stays muted like GoToLine's
            // status line.
            color = if (note != null) theme.color("error") else theme.color("text.muted"),
            maxLines = 2,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** GoToLine's ghost action button, verbatim in dress. */
@Composable
private fun RenameAction(glyph: String, description: String, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .height(22.dp)
            .widthIn(min = 22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    pressed -> theme.color("ghost_element.active")
                    hovered -> theme.color("ghost_element.hover")
                    else -> Color.Transparent
                }
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = description,
                onClick = onClick,
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("icon"),
        )
    }
}
