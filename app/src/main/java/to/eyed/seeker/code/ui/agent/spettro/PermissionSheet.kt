package to.eyed.seeker.code.ui.agent.spettro

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.AgentEntry
import to.eyed.seeker.code.core.PermissionOption
import to.eyed.seeker.code.core.SpettroAnswers
import to.eyed.seeker.code.core.ToolKind
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.BufferFontFamily
import to.eyed.seeker.code.ui.theme.LocalAppSettings
import to.eyed.seeker.code.ui.theme.IconSize
import to.eyed.seeker.code.ui.theme.SeekerIcon
import to.eyed.seeker.code.ui.theme.LocalZedTheme

// ---------------------------------------------------------------------------
// The pure half
// ---------------------------------------------------------------------------

/**
 * What a permission prompt says, decided without a screen.
 *
 * The reading of a stopped tool call is the part worth testing: "Edit
 * notes.md" does not say what the edit is, and "Run shell command" does not
 * say which one. Everything the user is being asked to approve lives in the
 * call's arguments, so pulling the right field out of them — and admitting it
 * when there is no right field — is the difference between an informed yes and
 * a reflex one.
 */
object PermissionPrompt {

    /**
     * The headline, verbatim from docs/SPETTRO.md except for the name, which
     * is the *connected* agent's and not always Spettro's.
     *
     * A resource with a `%1$s` rather than a constant: this sheet is raised by
     * an ordinary ACP `session/request_permission`, so any `agent_servers`
     * entry reaches it, and an approval dialog that names the wrong program is
     * the one place in the app where a misnamed sentence costs something —
     * "Allow" is being read as a promise about who is being allowed. Do not
     * reword the rest of it.
     */
    @StringRes
    val HEADLINE: Int = R.string.agent_permission_headline

    /**
     * The `_meta` key that says this prompt is really a *question* being
     * walked through the permission channel (W-10).
     *
     * Its presence is K9's cue to open the question sheet instead; it is read
     * here only so that a form which slips through — a payload we could not
     * parse, say — still replies with the tagged `_meta` the agent expects.
     */
    const val QUESTION_META_KEY = "spettro.app/question"

    /**
     * Allow kinds first, reject kinds after, **stable within each group**.
     *
     * The grouping is the phone's: at 400 dp the buttons are a column, and a
     * column whose safe answers are interleaved with its destructive ones is
     * one mis-tap away from a `rm -rf` nobody meant. The stability is the
     * agent's: the order it offered its allows in is its own editorial
     * judgement about which one it expects, and re-sorting inside the group
     * would throw that away.
     */
    fun order(options: List<PermissionOption>): List<PermissionOption> {
        val (allow, reject) = options.partition { it.isAllow }
        return allow + reject
    }

    /**
     * The command, path or URL the call is about — the mono block.
     *
     * Read from the **opening** arguments, because those are the ones the
     * agent was stopped on; a later update's `rawInput` can already be a
     * summary (W-09). Null when the call carries nothing quotable, in which
     * case the sheet shows the title alone rather than an empty box.
     */
    fun subject(call: AgentEntry.ToolCall): String? {
        val args = call.openArgs ?: return null
        for (key in SUBJECT_KEYS) {
            val text = stringy(args.opt(key))
            if (!text.isNullOrBlank()) return text.trim()
        }
        return null
    }

    /** The agent's own sentence about why, when it sent one. */
    fun explanation(call: AgentEntry.ToolCall): String? {
        val args = call.openArgs ?: return null
        for (key in EXPLANATION_KEYS) {
            val text = stringy(args.opt(key))
            if (!text.isNullOrBlank()) return text.trim()
        }
        return null
    }

    /**
     * A prompt that is the context-compaction question rather than a
     * permission — same sheet, its own icon, and the agent's own title
     * ("Context nearly full (~184000/200000 tokens)…") left exactly as sent.
     */
    fun isCompaction(call: AgentEntry.ToolCall): Boolean = call.id.startsWith("compact")

    /**
     * One icon, because a 44 dp header row holds one.
     *
     * `◍`, `▸` and `⛨` before this; the first and the last are outside what a
     * phone's UI face has to carry, and all three drew at the font's optical
     * size rather than an icon's. Same four cases, same meanings.
     */
    @DrawableRes
    fun icon(call: AgentEntry.ToolCall): Int = when {
        isCompaction(call) -> R.drawable.ic_ui_compact
        call.kind == ToolKind.Execute -> R.drawable.ic_ui_play
        call.kind == ToolKind.Think -> R.drawable.ic_ui_circle_dashed
        else -> R.drawable.ic_ui_shield
    }

    /**
     * The sentence under *Always allow*, which exists because that button is
     * the only one in the app that writes a file the user never opened.
     *
     * Network grants and command grants go to different files, and saying the
     * wrong filename is worse than saying none: somebody who later wants to
     * revoke a grant has to be able to find it.
     */
    fun durabilityNote(call: AgentEntry.ToolCall): String {
        val file = if (call.kind == ToolKind.Fetch) "allowed_network.json" else "allowed_commands.json"
        return "Always allow writes this to .spettro/$file, for this project, until you remove it."
    }

    /**
     * The `_meta` to send back with [option].
     *
     * Empty for an ordinary approval — the outcome's option id says
     * everything. Tagged only when the reply is really an *answer*: the
     * synthetic custom-input option, whose typed text has nowhere else to
     * travel, or a prompt that carried a question payload we did not route to
     * the question sheet.
     *
     * `isRecommended` is deliberately **not** a signal here. Spettro marks its
     * preferred option on ordinary permission prompts too, and tagging one of
     * those as `spettro.app/questionAnswer` would tell the agent a security
     * approval was a product preference.
     */
    fun answerMeta(
        call: AgentEntry.ToolCall,
        option: PermissionOption,
        customText: String = "",
    ): String = when {
        option.isCustomInput -> SpettroAnswers.customMeta(customText.trim())
        isWalkedQuestion(call) -> SpettroAnswers.optionMeta(option.id)
        else -> ""
    }

    /** Whether this prompt carries a `spettro.app/question` payload (W-10). */
    fun isWalkedQuestion(call: AgentEntry.ToolCall): Boolean {
        val meta = call.permissionMeta ?: return false
        return runCatching { JSONObject(meta).has(QUESTION_META_KEY) }.getOrDefault(false)
    }

    /**
     * A scalar as text, an argv array as a command line, anything else null.
     *
     * `{"command":["cargo","test"]}` and `{"command":"cargo test"}` are both
     * on the wire, and a raw `["cargo","test"]` in the approval box asks the
     * user to approve a shape rather than a command.
     */
    private fun stringy(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value
        is JSONArray -> (0 until value.length())
            .mapNotNull { value.opt(it) as? String }
            .takeIf { it.size == value.length() && it.isNotEmpty() }
            ?.joinToString(" ")
        is JSONObject -> null
        else -> value.toString()
    }

    /** In order of how specific they are; the first hit wins. */
    private val SUBJECT_KEYS = listOf(
        "command", "cmd", "commandLine", "script", "url", "path", "filePath", "file", "query",
    )

    private val EXPLANATION_KEYS = listOf("description", "explanation", "reason", "why")
}

// ---------------------------------------------------------------------------
// The sheet
// ---------------------------------------------------------------------------

/**
 * The modal form of a `session/request_permission`.
 *
 * The transcript keeps its inline `PermissionRow` for the case where the app
 * is in front of you and the card is on screen; this is the one for coming
 * back to a phone that has been in a pocket, and the differences are all
 * consequences of that. It names its place in the queue, because a queue is
 * otherwise invisible and the agent's turn is blocked on each entry in turn.
 * It quotes the *whole* command in a mono block you can scroll and select,
 * because "Run shell command: cargo test" is a title and titles are truncated.
 * And its buttons are a column of 48 dp rows with the allows above the
 * rejects — never four in a row at 400 dp, where the last one off the right
 * edge is always the rejection.
 *
 * Labels come from the agent verbatim. `Allow once`, `Always allow`, `Reject`
 * are its words for its own grants, and relabelling them would describe a
 * different promise from the one it is making.
 *
 * **Whoever opens this owes the user a notification.** The agent's turn is
 * stopped on this request and stays stopped; a phone face-down shows nothing
 * but a run that has quietly stalled. `AgentNotifier` must raise a
 * high-priority, haptic notification for a parked permission and cancel it as
 * soon as it is answered. Today's `agent` channel is `IMPORTANCE_DEFAULT` with
 * no vibration and one shared notification id, which is enough for "the turn
 * finished" and is not enough for this.
 *
 * **Dismissing does not answer.** ACP's `cancelled` outcome has no path
 * through the engine today — `Engine::acp_respond_permission` only takes an
 * option id and puts the responder back if it does not recognise one — so a
 * back press leaves the prompt parked and the inline row still asking, rather
 * than silently denying. Only the buttons speak to the agent.
 *
 * @param queuePosition 1-based; with [queueDepth] it draws `#1 of 2 waiting`.
 * @param onSelect the chosen option and the `_meta` to send with it — pass
 *   both straight to `AgentSession.respondToPermission`.
 */
@Composable
fun PermissionSheet(
    state: ShellState,
    request: AgentEntry.ToolCall,
    /** The connected agent's own name — whoever asked, not whoever we ship. */
    agentName: String,
    onSelect: (PermissionOption, String) -> Unit,
    onDismiss: () -> Unit,
    queuePosition: Int = 1,
    queueDepth: Int = 1,
) {
    val theme = LocalZedTheme.current
    val settings = LocalAppSettings.current

    val options = remember(request.key, request.options) { PermissionPrompt.order(request.options) }
    val custom = remember(request.key) { options.firstOrNull { it.isCustomInput } }
    var typed by remember(request.key) { mutableStateOf("") }

    // Keyed on nothing, like the question sheet's: its job is to survive the
    // recomposition that answering causes. The engine refuses a second answer,
    // but a button that looks live after the first tap invites the second.
    val delivered = remember { mutableStateOf<String?>(null) }

    fun choose(option: PermissionOption) {
        if (delivered.value == request.key) return
        delivered.value = request.key
        onSelect(option, PermissionPrompt.answerMeta(request, option, typed))
    }

    SheetScaffold(
        state = state,
        onDismiss = onDismiss,
        title = if (queueDepth > 1) "#$queuePosition of $queueDepth waiting" else null,
        field = if (custom == null) {
            null
        } else {
            {
                Column {
                    Text(
                        text = custom.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    CustomAnswerField(value = typed, onValueChange = { typed = it })
                }
            }
        },
        actions = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((index, option) in options.withIndex()) {
                    if (option.isCustomInput) {
                        // Its label is already above the field; the button is
                        // what sends what was typed, and it means nothing
                        // until something has been.
                        OptionButton(
                            option = option,
                            // The filled slot belongs to the agent's first
                            // allow, so the typed answer is outlined however
                            // it is ordered.
                            filled = false,
                            enabled = typed.isNotBlank(),
                            onClick = { choose(option) },
                        )
                    } else {
                        OptionButton(
                            option = option,
                            // Exactly one filled button, and it is the first
                            // allow — the least durable grant, so the
                            // permanent one is never the easiest to hit.
                            filled = option.isAllow && index == 0,
                            enabled = true,
                            onClick = { choose(option) },
                        )
                    }
                    if (option.kind == "allow_always") {
                        Text(
                            text = PermissionPrompt.durabilityNote(request),
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                }
                if (options.isEmpty()) {
                    Text(
                        text = "The agent offered no options — it may have withdrawn the request.",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SeekerIcon(
                    icon = PermissionPrompt.icon(request),
                    contentDescription = null,
                    tint = theme.color("text.accent", MaterialTheme.colorScheme.primary),
                    size = IconSize.Inline,
                )
                Text(
                    text = stringResource(PermissionPrompt.HEADLINE, agentName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = request.title,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            PermissionPrompt.subject(request)?.let { subject ->
                Spacer(Modifier.height(8.dp))
                // Mono, selectable, one long line rather than a wrapped one:
                // a wrapped shell command hides where its arguments end, and
                // "approve this" has to mean approving what you can read.
                SelectionContainer {
                    Text(
                        text = subject,
                        style = TextStyle(
                            fontFamily = BufferFontFamily,
                            fontSize = (settings.bufferFontSize * 0.85f).sp,
                            lineHeight = (settings.bufferFontSize * 1.35f).sp,
                        ),
                        color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                        softWrap = false,
                        // Six lines, then it scrolls: a 400-line patch in the
                        // approval box pushes the buttons off the screen.
                        maxLines = SUBJECT_LINES,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                theme.color("editor.background", MaterialTheme.colorScheme.surface),
                                RoundedCornerShape(8.dp),
                            )
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
            PermissionPrompt.explanation(request)?.let { explanation ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** One 48 dp answer. The recommendation is badged and nothing more. */
@Composable
private fun OptionButton(
    option: PermissionOption,
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val border = theme.color("border", MaterialTheme.colorScheme.outline)
    val background = if (filled) {
        theme.color("element.background", MaterialTheme.colorScheme.primary)
    } else {
        Color.Transparent
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ButtonHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) background else background.copy(alpha = 0.4f))
            .then(
                if (filled) {
                    Modifier
                } else {
                    Modifier.background(border.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                },
            )
            .clickable(enabled = enabled, onClickLabel = option.name, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                // The agent's own label, untouched.
                text = option.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (filled) FontWeight.Medium else FontWeight.Normal,
                color = when {
                    !enabled -> theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
                    option.isAllow -> theme.color("text", MaterialTheme.colorScheme.onSurface)
                    else -> theme.color("error", MaterialTheme.colorScheme.error)
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (option.isRecommended) {
                Text(
                    text = "Recommended",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("created", MaterialTheme.colorScheme.primary),
                    maxLines = 1,
                )
            }
        }
    }
}

/** The synthetic custom-input option's field — free text, pinned above the IME. */
@Composable
private fun CustomAnswerField(value: String, onValueChange: (String) -> Unit) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                theme.color("editor.background", MaterialTheme.colorScheme.surface),
                RoundedCornerShape(8.dp),
            )
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            maxLines = 3,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = theme.color("text", MaterialTheme.colorScheme.onSurface),
            ),
            cursorBrush = SolidColor(
                theme.color("editor.foreground", MaterialTheme.colorScheme.onSurface),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (value.isEmpty()) {
            Text(
                text = "Type your own answer",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}

/** "mono, h-scroll, selectable, 6-line cap" — docs/SPETTRO.md. */
private const val SUBJECT_LINES = 6
