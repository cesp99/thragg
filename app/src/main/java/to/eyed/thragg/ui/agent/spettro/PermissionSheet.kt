package to.eyed.thragg.ui.agent.spettro

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import to.eyed.thragg.R
import to.eyed.thragg.core.AgentEntry
import to.eyed.thragg.core.PermissionOption
import to.eyed.thragg.core.SpettroAnswers
import to.eyed.thragg.core.ToolKind
import to.eyed.thragg.ui.components.ZedCodeBlock
import to.eyed.thragg.ui.components.outlinedButtonEdge
import to.eyed.thragg.ui.shell.SheetScaffold
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.LocalSeekerColors
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.SeekerIcon

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
    val colors = LocalSeekerColors.current

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
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = MD.space1),
                    )
                    CustomAnswerField(value = typed, onValueChange = { typed = it })
                }
            }
        },
        actions = {
            Column(verticalArrangement = Arrangement.spacedBy(MD.space2)) {
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
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (options.isEmpty()) {
                    Text(
                        text = "The agent offered no options — it may have withdrawn the request.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MD.space4),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MD.space2),
            ) {
                SeekerIcon(
                    icon = PermissionPrompt.icon(request),
                    contentDescription = null,
                    tint = colors.accentMark,
                    size = IconSize.Action,
                )
                Text(
                    text = stringResource(PermissionPrompt.HEADLINE, agentName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(MD.space2))
            Text(
                text = request.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            PermissionPrompt.subject(request)?.let { subject ->
                Spacer(Modifier.height(MD.space2))
                // THE SEAM: the sheet is Material, the command is a Zed island
                // (docs/VISUAL.md, "The hybrid"). It used to draw the BUFFER
                // text over Material ink with a hand-rolled background, which
                // is a hole in the sheet; [ZedCodeBlock] gives it the editor's
                // own ground and the sheet's own 1dp `outlineVariant` edge,
                // which is what stops it reading as one. Unwrapped and
                // horizontally scrollable, because a wrapped shell command
                // hides where its arguments end and "approve this" has to mean
                // approving what you can read.
                ZedCodeBlock(
                    text = subject,
                    // Six lines, then it scrolls: a 400-line patch in the
                    // approval box pushes the buttons off the screen.
                    maxLines = SUBJECT_LINES,
                    // No copy header. The buttons are the point of this sheet
                    // and every row above them costs one of theirs.
                    copyable = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PermissionPrompt.explanation(request)?.let { explanation ->
                Spacer(Modifier.height(MD.space2))
                Text(
                    text = explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(MD.space3))
        }
    }
}

/**
 * One answer, as a real Material button.
 *
 * This was a hand-rolled `Box` with a `clickable` and two backgrounds, which
 * cost it everything a button gets for free and needs here: a state layer
 * under the finger, a disabled appearance that is not an alpha guess, and
 * TalkBack's "button" / "button, disabled". On the one surface in the app that
 * decides what the agent may do to a working tree, those are not decoration.
 *
 * EXACTLY ONE FILLED BUTTON AND IT IS THE FIRST ALLOW — the least durable
 * grant — so the permanent one is never the easiest to hit. Everything else is
 * outlined, and a reject carries `error` as its CONTENT colour rather than its
 * fill: a filled red button beside a filled primary one is two loud answers,
 * and the loud one has to be the safe one.
 */
@Composable
private fun OptionButton(
    option: PermissionOption,
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(MD.radiusSm)
    val label: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MD.space2),
        ) {
            Text(
                // The agent's own label, untouched.
                text = option.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (option.isRecommended) RecommendedTag()
        }
    }
    if (filled) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            modifier = Modifier.fillMaxWidth().heightIn(min = ButtonHeight),
            content = { label() },
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            // The edge follows the CONTENT here — the one site that asks for
            // it. A reject's label is `error` on purpose, and an accent edge
            // round red text is the control giving two answers about itself.
            border = outlinedButtonEdge(
                enabled = enabled,
                color = if (option.isAllow) scheme.primary else scheme.error,
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (option.isAllow) scheme.onSurface else scheme.error,
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = ButtonHeight),
            content = { label() },
        )
    }
}

/**
 * The agent's preferred option, badged — accent, never green.
 *
 * This was `theme.color("created")`, and that is a real defect rather than a
 * style preference: on a form that decides what the agent does to your working
 * tree, a GREEN recommendation reads as "this is the safe one", which is a
 * claim the agent did not make. An accent-washed capsule reads as "this is the
 * suggested one", which is what it means. It is a TAG and never a
 * preselection.
 */
@Composable
private fun RecommendedTag() {
    val colors = LocalSeekerColors.current
    Text(
        text = "Recommended",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        fontWeight = FontWeight.SemiBold,
        color = colors.accentInk,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            .padding(horizontal = MD.tagPadX, vertical = MD.tagPadY),
    )
}

/**
 * The synthetic custom-input option's field.
 *
 * [SheetTextField] is the shape — the same pill the question sheet's two
 * fields take, because they are the same control in the same place on the same
 * kind of sheet.
 */
@Composable
private fun CustomAnswerField(value: String, onValueChange: (String) -> Unit) {
    SheetTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = "Type your own answer",
        maxLines = 3,
    )
}

/** "mono, h-scroll, selectable, 6-line cap" — docs/SPETTRO.md. */
private const val SUBJECT_LINES = 6
