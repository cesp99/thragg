package to.eyed.thragg.ui.git

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import to.eyed.thragg.R
import to.eyed.thragg.core.AskpassKind
import to.eyed.thragg.core.AskpassPrompt
import to.eyed.thragg.core.GitAskpass
import to.eyed.thragg.ui.theme.LocalZedTheme

/**
 * The credential prompt — Zed's `AskPassModal`
 * (crates/git_ui_core/src/askpass_modal.rs): the operation as the title,
 * git's or ssh's own prompt as the text, and a single-line field, masked
 * unless the prompt is a "yes/no" or a "Username" one (askpass_modal.rs:40-46).
 * Confirm sends what was typed; Cancel — or Escape, or tapping outside —
 * refuses, and git fails with its own words in the panel's strip.
 *
 * Two things Zed's modal does not have, both because a phone is not a
 * desktop with a keychain: an eye toggle to show the masked text, since a
 * token typed on a glass keyboard is easy to get wrong and impossible to
 * check blind; and a "Remember for this session" checkbox, since there is
 * no `git-credential` daemon that outlives the proot instance git ran in
 * (askpass.rs). The username is remembered for its host regardless — the
 * checkbox is for the secret.
 *
 * Shown from the workspace root, above the panel and the project picker
 * alike, whenever [GitAskpass.pending] is set — which is only ever while a
 * clone, fetch, pull or push is running.
 */
@Composable
fun AskpassDialog() {
    val prompt = GitAskpass.pending ?: return
    // Keyed on the prompt: a password question following a username one is
    // a new field, not the username left in place.
    AskpassDialogFor(
        prompt = prompt,
        onAnswer = { answer, remember -> GitAskpass.answer(prompt, answer, remember) },
        onCancel = { GitAskpass.cancel(prompt) },
    )
}

/** The dialog itself, for one prompt. Separate so a preview can hand it one. */
@Composable
internal fun AskpassDialogFor(
    prompt: AskpassPrompt,
    onAnswer: (String, Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val theme = LocalZedTheme.current
    var text by remember(prompt.id) {
        val initial = prompt.initialText
        mutableStateOf(TextFieldValue(initial, androidx.compose.ui.text.TextRange(initial.length)))
    }
    var shown by remember(prompt.id) { mutableStateOf(!prompt.masked) }
    var remember by remember(prompt.id) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(prompt.id) { focus.requestFocus() }

    fun submit() {
        onAnswer(text.text, remember)
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(prompt.operation) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // The prompt verbatim, in the buffer font as Zed shows it
                // (askpass_modal.rs:144): the URL, the key path or the
                // fingerprint is the part the user has to read.
                Text(
                    text = prompt.prompt.trimEnd(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.color("text"),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(theme.color("editor.background"), RoundedCornerShape(6.dp))
                        .border(1.dp, theme.color("border"), RoundedCornerShape(6.dp))
                        .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = TextStyle(color = theme.color("editor.foreground")),
                        cursorBrush = SolidColor(theme.color("editor.foreground")),
                        visualTransformation = if (shown) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (prompt.masked) KeyboardType.Password else KeyboardType.Ascii,
                            autoCorrectEnabled = false,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focus)
                            .pointerHoverIcon(PointerIcon.Text)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.Enter, Key.NumPadEnter -> { submit(); true }
                                    Key.Escape -> { onCancel(); true }
                                    else -> false
                                }
                            },
                    )
                    if (prompt.masked) {
                        // Show/hide what was typed. Not in Zed; see above.
                        val interaction = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(
                                    interactionSource = interaction,
                                    indication = null,
                                    onClickLabel = if (shown) "Hide" else "Show",
                                ) { shown = !shown },
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = painterResource(
                                    if (shown) R.drawable.ic_ui_eye_off else R.drawable.ic_ui_eye
                                ),
                                contentDescription = if (shown) "Hide" else "Show",
                                colorFilter = ColorFilter.tint(theme.color("text.muted")),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                if (prompt.rememberable) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClickLabel = "Remember for this session",
                            ) { remember = !remember }
                            .padding(vertical = 2.dp),
                    ) {
                        RememberCheckbox(checked = remember)
                        Text(
                            text = rememberLabel(prompt.kind),
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.color("text.muted"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit) { Text(confirmLabel(prompt.kind)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/**
 * The checkbox's face, in the panel's `ZedCheckbox` clothes (toggle.rs:186-208)
 * — drawn only, the row around it is the target.
 */
@Composable
private fun RememberCheckbox(checked: Boolean) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(theme.color("editor.background"))
            .border(1.dp, theme.color("border"), RoundedCornerShape(2.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("icon.accent"),
            )
        }
    }
}

/** "OK" — except the host-key question, whose pre-filled answer is a yes. */
internal fun confirmLabel(kind: AskpassKind): String =
    if (kind == AskpassKind.HostKey) "Yes" else "OK"

/**
 * The checkbox's wording names what would be kept — the passphrase where
 * ssh said passphrase, the password (a token, on every forge today) where
 * git did. A username is kept for its host without asking (askpass.rs), so
 * its prompt shows no box.
 */
internal fun rememberLabel(kind: AskpassKind): String = when (kind) {
    AskpassKind.Passphrase -> "Remember this passphrase for this session"
    else -> "Remember this password for this session"
}
