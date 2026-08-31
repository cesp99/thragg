package to.eyed.seeker.code.ui.agent.spettro

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import to.eyed.seeker.code.core.PermissionOption
import to.eyed.seeker.code.core.QuestionAnswer
import to.eyed.seeker.code.core.QuestionDraft
import to.eyed.seeker.code.core.SpettroAnswer
import to.eyed.seeker.code.core.SpettroAnswers
import to.eyed.seeker.code.core.SpettroQuestion
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.BufferFontFamily
import to.eyed.seeker.code.ui.theme.LocalAppSettings
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.touchTarget

// ---------------------------------------------------------------------------
// The pure half — everything the sheet decides that can be decided without a
// screen, so that the rules the protocol actually cares about are testable.
// ---------------------------------------------------------------------------

/**
 * The form's answering rules, separated from its pixels.
 *
 * Everything in here is a rule the *model* can tell the difference between,
 * which is why none of it is inlined into a composable: a question left alone
 * is reported unanswered rather than defaulted, selections come out in option
 * order rather than tick order, and a note typed beside a multi-select pick
 * travels as `notes` instead of quietly replacing the pick. Each of those is
 * one line of code and one wrong turn away from lying to the agent about what
 * a human said.
 */
object QuestionForm {

    /**
     * Fold [drafts] into the answers to send, **in question order**.
     *
     * Questions with an empty draft are missing from the result on purpose:
     * `SpettroAnswers.encode` omits them, and Spettro then tells the model
     * plainly that nobody answered them. That is not the same statement as
     * "the user had no preference", and it is very much not the same
     * statement as the recommended option — see docs/SPETTRO.md §5.
     */
    fun answers(
        question: SpettroQuestion,
        drafts: Map<String, QuestionDraft>,
    ): List<SpettroAnswer> = question.questions.mapNotNull { q ->
        val draft = normalise(q, drafts[q.id] ?: QuestionDraft())
        q.answer(draft)?.toSpettro()
            // A note with nothing beside it is still something the user typed.
            // `SpettroQuestion.Q.answer` calls that no answer, but the encoder
            // has a shape for it — an option answer with an empty `optionIds`
            // and a `notes` — and dropping the words somebody wrote in the box
            // the sheet offered them is the wrong end of that disagreement.
            ?: draft.note.trim().takeIf { it.isNotEmpty() }
                ?.let { SpettroAnswer.Option(q.id, emptyList(), it) }
    }

    /**
     * The draft as the answer rules want to see it.
     *
     * Two adjustments, both from the wireframe:
     *
     *  * **Multi-select**: typed words are *additional* to the ticks, so they
     *    join the note with a newline and travel as `notes` beside the option
     *    answer. Dropping them — which is what happens if the draft goes
     *    through unmodified, since an option answer carries no free text —
     *    silently discards something the user typed.
     *  * **Single-select**: words replace the pick. The sheet already clears
     *    the other side as you touch either one, so this is a belt-and-braces
     *    resolution of a draft that should never arrive holding both.
     */
    fun normalise(q: SpettroQuestion.Q, draft: QuestionDraft): QuestionDraft {
        val custom = draft.custom.trim()
        if (custom.isEmpty()) return draft
        if (!q.multiSelect) return draft.copy(selected = emptyList())
        if (draft.selected.isEmpty()) return draft
        val note = listOf(custom, draft.note.trim())
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        return draft.copy(custom = "", note = note)
    }

    /** Whether this draft says anything at all — the step dot's fill. */
    fun isAnswered(q: SpettroQuestion.Q, draft: QuestionDraft): Boolean {
        val normalised = normalise(q, draft)
        return q.answer(normalised) != null || normalised.note.isNotBlank()
    }

    /**
     * The review page's right-hand column: what was chosen, in option order,
     * or the verbatim `Not answered` the warning sentence below it explains.
     */
    fun summary(q: SpettroQuestion.Q, draft: QuestionDraft): String {
        val normalised = normalise(q, draft)
        val picked = q.options.filter { it.id in normalised.selected }
        if (picked.isNotEmpty()) return picked.joinToString(", ") { it.label }
        val custom = normalised.custom.trim()
        if (custom.isNotEmpty()) return custom
        // A note with nothing else is still an answer — the encoder keeps it —
        // so the review page has to admit it rather than say "Not answered".
        val note = normalised.note.trim()
        if (note.isNotEmpty()) return note
        return NOT_ANSWERED
    }

    /**
     * Whether picking an option finishes the form.
     *
     * One question, one pick, no options to compare against a note: the Next
     * button would be a second tap that can only mean "yes, that one". The
     * note field being open is the exception the wireframe names — somebody
     * mid-sentence has not finished answering.
     */
    fun submitsOnPick(question: SpettroQuestion): Boolean =
        question.questions.size == 1 && !question.questions[0].multiSelect

    /**
     * The reply to a form that arrived over the **permission** channel
     * (docs/SPETTRO.md W-10).
     *
     * That transport has no `answers` array: the answer is an option id on the
     * stopped tool call, plus the same decision tagged into the reply's
     * `_meta` so Spettro can tell an *answer* from an *approval*. Free text
     * can only travel in the `_meta`, and only against the synthetic
     * custom-input option — the one whose `spettro.app/isCustomInput` is set,
     * conventionally `"custom"`.
     *
     * Null when there is nothing to send, because a walked form has no way to
     * express "unanswered": the tool call is stopped and wants exactly one id.
     */
    fun permissionReply(
        answers: List<SpettroAnswer>,
        options: List<PermissionOption>,
    ): WalkedReply? = when (val answer = answers.firstOrNull()) {
        is SpettroAnswer.Option -> answer.optionIds.firstOrNull()
            ?.let { WalkedReply(it, SpettroAnswers.optionMeta(it)) }
        is SpettroAnswer.Custom -> {
            val id = options.firstOrNull { it.isCustomInput }?.id ?: CUSTOM_OPTION_ID
            WalkedReply(id, SpettroAnswers.customMeta(answer.text.trim()))
        }
        null -> null
    }

    /** What a walked form sends back: one option id and the tagged `_meta`. */
    data class WalkedReply(val optionId: String, val answerMetaJson: String)

    /** Spettro's own id for the synthetic "let me type it" option. */
    const val CUSTOM_OPTION_ID = "custom"

    /** The review page's word for a question nobody touched. */
    const val NOT_ANSWERED = "Not answered"

    /**
     * The review page's warning, **verbatim** from docs/SPETTRO.md.
     *
     * It is the one sentence in the sheet that describes the protocol rather
     * than the product, and it is here because "Not answered" reads like a
     * shrug and is not one.
     */
    const val UNANSWERED_WARNING =
        "Questions you left alone are sent as unanswered — the agent is told " +
            "nobody answered them, not that you had no preference."

    /** K1's answer type and K2's encoder type are the same value, twice. */
    private fun QuestionAnswer.toSpettro(): SpettroAnswer = when (this) {
        is QuestionAnswer.Option -> SpettroAnswer.Option(questionId, optionIds, notes)
        is QuestionAnswer.Custom -> SpettroAnswer.Custom(questionId, text, notes)
    }
}

// ---------------------------------------------------------------------------
// The sheet
// ---------------------------------------------------------------------------

/**
 * A whole ask-user form — up to four related questions — answered in one pass.
 *
 * This is the surface the `_spettro/question/ask` extension exists for. The
 * fallback transports can only ask one thing at a time, which on a phone is
 * five sheets deep and loses the relationship between the answers; here the
 * form is one sheet with one page per question, a dot strip that says how many
 * are left and which are done, and a review page that shows the whole set
 * before any of it is sent.
 *
 * The rules that are not cosmetic:
 *
 *  * **Nothing is preselected.** The agent's own recommendation is a `★
 *    Recommended` badge and nothing more. Preselecting it would send the
 *    model's guess back as the user's decision, which is the one thing the
 *    protocol's designers wrote the extension to prevent.
 *  * **An untouched question is omitted**, never defaulted — see
 *    [QuestionForm.answers].
 *  * **Declining is all-or-nothing.** There is no per-question decline; the
 *    reply is `{"kind":"declined"}` at the top level, which is why [onDecline]
 *    takes no arguments.
 *  * **Dismissing is not declining.** Back, the scrim and a downward drag
 *    leave the form parked in `state.questions` and the sheet re-openable —
 *    the agent is blocked on this and a stray back-swipe must not answer for
 *    the user. Only the two buttons speak to the agent.
 *
 * [request] is a plain value and both callbacks are plain; the sheet never
 * touches the engine, so K9 can route an ASK form and a permission-walked one
 * (`permissionMeta["spettro.app/question"]`) to the same composable and reply
 * on whichever transport the request arrived on — see
 * [QuestionForm.permissionReply].
 *
 * **Whoever opens this owes the user a notification.** A form arriving mid-run
 * *blocks the turn*: the agent is stopped until it is answered, and a phone in
 * a pocket shows a run that has silently stalled and an app that looks hung.
 * `AgentNotifier` must raise a high-priority, haptic notification for a parked
 * question and cancel it the moment one is answered — the same requirement the
 * approval sheet carries, and the precondition for shipping `ask-first` as the
 * default permission mode.
 *
 * @param queuePosition 1-based place in `state.questions`; with [queueDepth]
 *   it draws `#2 of 3 waiting`, which is the only way to see the queue at all
 *   on a phone.
 */
@Composable
fun QuestionSheet(
    state: ShellState,
    request: SpettroQuestion,
    onAnswer: (List<SpettroAnswer>) -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
    queuePosition: Int = 1,
    queueDepth: Int = 1,
) {
    val theme = LocalZedTheme.current
    val scope = rememberCoroutineScope()

    // Keyed on the request id so the queue's next form starts blank: two forms
    // in a row asking "which database?" must not inherit the first answer.
    val drafts = remember(request.id) { mutableStateMapOf<String, QuestionDraft>() }
    val notesOpen = remember(request.id) { mutableStateMapOf<String, Boolean>() }
    var preview by remember(request.id) { mutableStateOf<SpettroQuestion.Opt?>(null) }

    // The `doneRef` of the wireframe. Not keyed on the request: its whole job
    // is to remember, across the recomposition that answering causes, that
    // *this* id has already been spoken for. A second delivery is a protocol
    // error at the far end — the responder has been consumed — and here it
    // would look like the sheet ignoring the first tap.
    val delivered = remember { mutableStateOf<String?>(null) }

    // Review is a page of its own only when there is something to review: one
    // question is its own summary, and a page that says "SQLite ›" under a
    // page that says "SQLite" is a tap for nothing.
    val hasReview = request.questions.size > 1
    val pageCount = request.questions.size + if (hasReview) 1 else 0
    val pager = rememberPagerState(pageCount = { pageCount })
    val page = pager.currentPage
    val current = request.questions.getOrNull(page)

    fun draftOf(q: SpettroQuestion.Q): QuestionDraft = drafts[q.id] ?: QuestionDraft()

    fun deliver() {
        if (delivered.value == request.id) return
        delivered.value = request.id
        onAnswer(QuestionForm.answers(request, drafts))
    }

    fun decline() {
        if (delivered.value == request.id) return
        delivered.value = request.id
        onDecline()
    }

    val answers = QuestionForm.answers(request, drafts)
    // A version-1 payload has no `answers` envelope, so an empty answer set
    // encodes as a decline rather than as an empty form. Sending would then
    // mean declining, silently, which is worse than a disabled button.
    val sendable = request.version >= 2 || answers.isNotEmpty()

    SheetScaffold(
        state = state,
        onDismiss = onDismiss,
        // The handle carries only the queue depth; the headline is a real
        // heading in the body, next to the icon, the way the wireframe draws
        // it and the way the permission sheet does.
        title = if (queueDepth > 1) "#$queuePosition of $queueDepth waiting" else null,
        // Full height, which is the one place in the app that asks for it: a
        // form is not a menu drawn over the screen the user was reading, it is
        // the only thing left to do — the agent has stopped until it is
        // answered. At the house two thirds a four-question form's dots, its
        // options and its review page do not fit at once, and the sheet's own
        // pager would be the thing that had to be dragged into view.
        openFraction = 1f,
        field = {
            val q = current
            if (q != null) {
                Column {
                    if (q.allowCustomInput) {
                        Text(
                            text = if (q.options.isEmpty()) {
                                "Answer in your own words"
                            } else {
                                "Or answer in your own words"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        FormField(
                            value = draftOf(q).custom,
                            placeholder = "Type your own answer",
                            onValueChange = { text ->
                                val was = draftOf(q)
                                // Words replace the pick on a single-select —
                                // the two are one answer, not two.
                                drafts[q.id] = if (!q.multiSelect && text.isNotBlank()) {
                                    was.copy(custom = text, selected = emptyList())
                                } else {
                                    was.copy(custom = text)
                                }
                            },
                        )
                    }
                    if (notesOpen[q.id] == true) {
                        Spacer(Modifier.height(6.dp))
                        FormField(
                            value = draftOf(q).note,
                            placeholder = "Why — the agent gets this too",
                            onValueChange = { drafts[q.id] = draftOf(q).copy(note = it) },
                        )
                    }
                }
            }
        },
        actions = {
            Column {
                if (page == pageCount - 1 && !hasReview && answers.isEmpty()) {
                    // The single-question form has no review page to carry the
                    // warning, and "Send answer" with nothing chosen is
                    // exactly the case it warns about.
                    Text(
                        text = QuestionForm.UNANSWERED_WARNING,
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Decline",
                        style = MaterialTheme.typography.labelLarge,
                        color = theme.color("error", MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .touchTarget()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClickLabel = "Decline the whole form") { decline() }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    val last = page == pageCount - 1
                    PrimaryButton(
                        label = when {
                            !last -> "Next  →"
                            hasReview -> "Send answers"
                            else -> "Send answer"
                        },
                        enabled = !last || sendable,
                        onClick = {
                            if (last) deliver() else scope.launch { pager.animateScrollToPage(page + 1) }
                        },
                    )
                }
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "⍰",
                    style = MaterialTheme.typography.titleMedium,
                    color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
                )
                Text(
                    text = headline(request),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                )
            }
            Spacer(Modifier.height(6.dp))
            request.context?.takeIf { it.isNotBlank() }?.let { context ->
                Text(
                    text = context,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (pageCount > 1) {
                StepDots(
                    pageCount = pageCount,
                    current = page,
                    // Filled = answered. The review dot is filled once
                    // anything at all has been said.
                    answered = { index ->
                        val q = request.questions.getOrNull(index)
                        if (q == null) answers.isNotEmpty() else QuestionForm.isAnswered(q, draftOf(q))
                    },
                    label = current?.header?.takeIf { it.isNotBlank() } ?: "Review",
                    onGoTo = { index -> scope.launch { pager.animateScrollToPage(index) } },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        HorizontalPager(
            state = pager,
            // Top, not the default CenterVertically. A page here is a
            // wrap-height scrolling column, so the default centres a short
            // question in the middle of a sheet that opens full height —
            // the question ends up a third of the way down with nothing
            // above it, and it *moves* as options are revealed. Top-aligned
            // it sits under the step dots and stays there.
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { index ->
            val q = request.questions.getOrNull(index)
            if (q == null) {
                ReviewPage(
                    request = request,
                    draftOf = ::draftOf,
                    onGoTo = { target -> scope.launch { pager.animateScrollToPage(target) } },
                )
            } else {
                QuestionPage(
                    q = q,
                    draft = draftOf(q),
                    noteOpen = notesOpen[q.id] == true,
                    onToggleNote = { notesOpen[q.id] = notesOpen[q.id] != true },
                    onPreview = { preview = it },
                    onPick = { option ->
                        val was = draftOf(q)
                        // Picking clears typed words on a single-select for
                        // the same reason typing clears the pick: one answer.
                        val next = was.toggle(option.id, q.multiSelect)
                        drafts[q.id] = if (q.multiSelect) next else next.copy(custom = "")
                        if (QuestionForm.submitsOnPick(request) &&
                            notesOpen[q.id] != true &&
                            next.selected.isNotEmpty()
                        ) {
                            deliver()
                        }
                    },
                )
            }
        }
    }

    preview?.let { option ->
        PreviewSheet(state = state, option = option, onDismiss = { preview = null })
    }
}

/** `Spettro has 2 questions` — the form's size, said once, at the top. */
private fun headline(request: SpettroQuestion): String {
    val count = request.questions.size
    return if (count <= 1) "Spettro has a question" else "Spettro has $count questions"
}

/**
 * The step strip: one dot per page, filled when that page has an answer, and
 * tappable so the review page's rows are not the only way back.
 */
@Composable
private fun StepDots(
    pageCount: Int,
    current: Int,
    answered: (Int) -> Boolean,
    label: String,
    onGoTo: (Int) -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (index in 0 until pageCount) {
            val filled = answered(index)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .touchTarget()
                    .clickable(onClickLabel = "Question ${index + 1}") { onGoTo(index) },
            ) {
                Box(
                    modifier = Modifier
                        .size(if (index == current) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                filled -> theme.color("text.accent", MaterialTheme.colorScheme.primary)
                                index == current -> theme.color("text", MaterialTheme.colorScheme.onSurface)
                                else -> theme.color("border", MaterialTheme.colorScheme.outline)
                            },
                        ),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One question: its text, its options, and the affordance for a note. */
@Composable
private fun QuestionPage(
    q: SpettroQuestion.Q,
    draft: QuestionDraft,
    noteOpen: Boolean,
    onToggleNote: () -> Unit,
    onPreview: (SpettroQuestion.Opt) -> Unit,
    onPick: (SpettroQuestion.Opt) -> Unit,
) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = q.question.ifBlank { q.header },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = theme.color("text", MaterialTheme.colorScheme.onSurface),
        )
        if (q.multiSelect && q.options.isNotEmpty()) {
            Text(
                text = "Choose as many as apply",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        Spacer(Modifier.height(8.dp))
        for (option in q.options) {
            OptionRow(
                option = option,
                checked = option.id in draft.selected,
                multi = q.multiSelect,
                onClick = { onPick(option) },
                onPreview = { onPreview(option) },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (noteOpen) "✎ Hide note" else "✎ Add a note",
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .touchTarget()
                .clickable(onClickLabel = "Add a note") { onToggleNote() }
                .padding(vertical = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * One option: the tick, the label, the muted description, the recommendation
 * badge and — only on the extension transport, which is the only one that
 * carries them — the `◱` that opens the preview.
 */
@Composable
private fun OptionRow(
    option: SpettroQuestion.Opt,
    checked: Boolean,
    multi: Boolean,
    onClick: () -> Unit,
    onPreview: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = OptionRowHeight)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = option.label, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Text(
            // Radio for one, checkbox for many: the glyph is the only thing
            // that says whether a second tap adds or replaces.
            text = when {
                multi && checked -> "☑"
                multi -> "☐"
                checked -> "(•)"
                else -> "( )"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (checked) {
                theme.color("text.accent", MaterialTheme.colorScheme.primary)
            } else {
                theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
            },
            modifier = Modifier.width(TickWidth),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (option.isRecommended) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        // A badge and nothing else. The row is not selected,
                        // not highlighted and not first — the agent's guess
                        // is information, not a decision.
                        text = "★ Recommended",
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.color("created", MaterialTheme.colorScheme.primary),
                        maxLines = 1,
                    )
                }
            }
            option.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
        if (option.preview != null) {
            Text(
                text = "◱",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier
                    .touchTarget()
                    .clickable(onClickLabel = "Preview ${option.label}", onClick = onPreview),
            )
        }
    }
}

/**
 * What the option would produce — a diff, a command, a generated name.
 *
 * A nested sheet rather than Desktop's side-by-side pane: at 400 dp there is
 * no second column, and the preview is read once and then dismissed. Mono,
 * because everything that arrives here is code or a path.
 */
@Composable
private fun PreviewSheet(state: ShellState, option: SpettroQuestion.Opt, onDismiss: () -> Unit) {
    val theme = LocalZedTheme.current
    val settings = LocalAppSettings.current
    SheetScaffold(state = state, onDismiss = onDismiss, title = option.label) {
        Text(
            text = option.preview.orEmpty(),
            style = TextStyle(
                fontFamily = BufferFontFamily,
                fontSize = (settings.bufferFontSize * 0.85f).sp,
                lineHeight = (settings.bufferFontSize * 1.35f).sp,
            ),
            color = theme.color("text", MaterialTheme.colorScheme.onSurface),
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        )
    }
}

/**
 * The last page: every answer in one list, and the sentence that explains what
 * a blank one means.
 *
 * The warning is verbatim from the spec because the distinction it draws is
 * the whole reason the form is worth building: "unanswered" is a fact the
 * model is told, not an absence it has to guess at.
 */
@Composable
private fun ReviewPage(
    request: SpettroQuestion,
    draftOf: (SpettroQuestion.Q) -> QuestionDraft,
    onGoTo: (Int) -> Unit,
) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "Review your answers",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = theme.color("text", MaterialTheme.colorScheme.onSurface),
        )
        Spacer(Modifier.height(4.dp))
        request.questions.forEachIndexed { index, q ->
            val summary = QuestionForm.summary(q, draftOf(q))
            val empty = summary == QuestionForm.NOT_ANSWERED
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = OptionRowHeight)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "Edit ${q.header}") { onGoTo(index) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            ) {
                Text(
                    text = q.header.ifBlank { "Question ${index + 1}" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.width(ReviewLabelWidth),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (empty) {
                        theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        theme.color("text", MaterialTheme.colorScheme.onSurface)
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "›",
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "⚠ ${QuestionForm.UNANSWERED_WARNING}",
            style = MaterialTheme.typography.bodySmall,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Spacer(Modifier.height(12.dp))
    }
}

/** The sheet's one text-field shape, shared by the custom answer and the note. */
@Composable
private fun FormField(value: String, placeholder: String, onValueChange: (String) -> Unit) {
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
            // Not single-line: an answer in your own words is a sentence, and
            // the IME's enter key should give a second line rather than
            // dismiss a form the agent is blocked on.
            maxLines = 4,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Default,
            ),
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
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}

/** The right-thumb button both sheets end with. */
@Composable
internal fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val background = theme.color("element.background", MaterialTheme.colorScheme.primary)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = ButtonHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) background else background.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = theme.color("text", MaterialTheme.colorScheme.onSurface)
                .copy(alpha = if (enabled) 1f else 0.5f),
        )
    }
}

/** The wireframe's 56 dp option row. */
private val OptionRowHeight = 56.dp

/** 24 dp of tick plus its breathing room. */
private val TickWidth = 32.dp

private val ReviewLabelWidth = 96.dp

internal val ButtonHeight = 48.dp
