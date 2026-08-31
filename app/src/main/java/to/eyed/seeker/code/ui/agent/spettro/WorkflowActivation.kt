package to.eyed.seeker.code.ui.agent.spettro

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import to.eyed.seeker.code.ui.theme.LocalReduceMotion
import to.eyed.seeker.code.ui.theme.LocalZedTheme

/*
 * Activation glow — the composer telling the truth about what the next turn
 * can do, before it is sent.
 *
 * Writing "ultracode" (or asking for a workflow in plain English) silently
 * changes the shape of the next turn: Spettro injects the workflow tool and
 * its guidance, and the model may fan the work out across sub-agents that each
 * cost a full agent run. A phrase that does that while looking like every
 * other phrase in the box is an escalation by ambush. So the phrase lights up
 * *as it is typed*, and it stays lit in the sent bubble, and it is lit by
 * exactly the match that decides whether the tool is injected.
 *
 * That last clause is the whole discipline of this file. The matcher below is
 * a port of `internal/agent/workflow.go`'s `workflowActivationRes` /
 * `WorkflowActivationSpans`, pattern for pattern, and it must stay
 * behaviourally identical to it. A UI that lights a phrase the CLI ignores
 * promises a mode the run never enters, and a UI that stays dark on a phrase
 * the CLI honours spends the user's money without warning. Both are worse than
 * no highlight at all.
 *
 * Everything above the ramps is pure and has no Compose in it, so the port can
 * be tested against the Go test-suite's own cases (WorkflowActivationTest.kt).
 */

/**
 * The shorthand that opts a single turn into workflows —
 * `agent.WorkflowKeyword`.
 *
 * A one-shot switch on purpose on the CLI's side: injecting the tool changes
 * the system prompt, so a persistent toggle would pay the prompt-cache cost on
 * every turn for a capability most turns do not need. The phone must not offer
 * a sticky "workflow mode" toggle for the same reason.
 */
const val WORKFLOW_KEYWORD = "ultracode"

/*
 * RE2's `\b`, spelled out.
 *
 * Go's `\b` is ASCII: a boundary between `[0-9A-Za-z_]` and anything else.
 * Java's `\b` is *not* — it is Unicode-aware even when `\w` is not, so
 * `Regex("\\bultracode\\b")` would refuse to match "ö" + "ultracode" where RE2
 * matches it. That is a one-character difference in an accented word, which is
 * exactly the kind of divergence that ends with the highlight and the runtime
 * disagreeing in a language other than English. Writing the boundary out as
 * ASCII lookarounds removes the question.
 *
 * (The remaining gap to RE2 is Unicode case folding — Go's `(?i)` folds `ſ`
 * onto `s`, Kotlin's IGNORE_CASE does not. Reproducing that would need
 * UNICODE_CASE, which would also drag Unicode semantics into these ASCII
 * classes. Since an ASCII word boundary already refuses every `ſ`-containing
 * phrase these patterns could otherwise reach, the gap is unreachable.)
 */
private const val WB_BEFORE = "(?<![0-9A-Za-z_])"
private const val WB_AFTER = "(?![0-9A-Za-z_])"

/**
 * Every way a user turns workflows on for a turn, in `workflow.go`'s order.
 *
 * The keyword is the shorthand, not the only door — "use a workflow to
 * modernise these handlers" is an unmistakable request for orchestration, and
 * making people learn a magic word to reach the feature would be a worse tool.
 * Each pattern has to be a phrase that only makes sense as a request for *this*
 * feature: "our deploy workflow" and ".github/workflows" stay quiet, which is
 * why pattern 2 insists on an indefinite article.
 *
 * The list order is load-bearing in one place only: [workflowPreapproved] is
 * the first element alone.
 */
private val ACTIVATION_RES: List<Regex> = listOf(
    // The keyword itself.
    Regex(WB_BEFORE + WORKFLOW_KEYWORD + WB_AFTER, RegexOption.IGNORE_CASE),
    // "use a workflow", "write a multi-agent workflow", "set up a workflow".
    // An indefinite article only: "run the workflow" almost always means a CI
    // job or an already-named saved script.
    Regex(
        WB_BEFORE +
            "(?:use|using|run|write|author|make|create|build|set ?up|start|launch|" +
            "kick off|do this as|do it as)\\s+(?:a|an|another)\\s+(?:new\\s+)?" +
            "(?:multi[- ]?agent\\s+|orchestration\\s+)?workflow" + WB_AFTER,
        RegexOption.IGNORE_CASE,
    ),
    // "with workflows", "via workflows"
    Regex(
        WB_BEFORE + "(?:use|using|run|with|via)\\s+workflows" + WB_AFTER,
        RegexOption.IGNORE_CASE,
    ),
    // an explicit reference to the tool itself
    Regex(WB_BEFORE + "workflow tool" + WB_AFTER, RegexOption.IGNORE_CASE),
    // "fan this out across sub-agents"
    Regex(
        WB_BEFORE + "fan\\s+(?:this|that|it|them|the \\w+)?\\s*out\\s+" +
            "(?:across|over|to|into)\\s+(?:\\w+\\s+){0,2}(?:sub-?)?agents?" + WB_AFTER,
        RegexOption.IGNORE_CASE,
    ),
    // "orchestrate this with subagents"
    Regex(
        WB_BEFORE + "orchestrate\\s+(?:\\w+\\s+){0,3}(?:with|using|across|over)\\s+" +
            "(?:\\w+\\s+){0,2}(?:sub-?)?agents?" + WB_AFTER,
        RegexOption.IGNORE_CASE,
    ),
    // "multi-agent orchestration"
    Regex(
        WB_BEFORE + "multi[- ]?agent\\s+(?:orchestration|workflow|pipeline|run)" + WB_AFTER,
        RegexOption.IGNORE_CASE,
    ),
)

/**
 * The ranges of [text] that turn workflows on — merged, non-overlapping,
 * earliest first, and inclusive at both ends the way [MatchResult.range] is.
 *
 * Indices are UTF-16 offsets, which is what `AnnotatedString.addStyle` and
 * every Compose text API want. The Go original converts its byte offsets to
 * *rune* indices for a terminal that styles cells; there is no equivalent step
 * here because Kotlin already reports the units the renderer consumes. The two
 * therefore disagree on the numeric value of a span that follows an emoji, and
 * agree on every span's text — which is the property that matters.
 *
 * Overlap is by design ("use a workflow" and "workflow tool" both match inside
 * "use a workflow tool"), so the spans are merged: a renderer handed
 * overlapping ranges would style the same glyph twice, and with a gradient
 * brush that means two shaders fighting over the same letters.
 */
fun workflowActivationSpans(text: String): List<IntRange> {
    if (text.isEmpty()) return emptyList()

    val found = ArrayList<IntRange>()
    for (re in ACTIVATION_RES) {
        for (m in re.findAll(text)) found.add(m.range)
    }
    if (found.isEmpty()) return emptyList()

    // Earliest first; on a tie the longer span wins, so the merge below always
    // starts from the widest candidate at that offset.
    found.sortWith(compareBy<IntRange> { it.first }.thenByDescending { it.last })

    val merged = ArrayList<IntRange>(found.size)
    for (span in found) {
        val last = merged.lastOrNull()
        // `first <= last.last + 1` rather than `<=  last.last`: two spans that
        // merely touch ("…workflow" then "tool") are one lit phrase, not two
        // with an unlit seam. This is the inclusive spelling of the Go
        // original's `sp[0] <= last[1]` on half-open ranges.
        if (last != null && span.first <= last.last + 1) {
            if (span.last > last.last) merged[merged.size - 1] = last.first..span.last
            continue
        }
        merged.add(span)
    }
    return merged
}

/**
 * Whether [text] opts this turn into workflows at all — `WorkflowRequested`.
 *
 * Defined in terms of [workflowActivationSpans] rather than beside it, exactly
 * as the CLI does, so the highlight and the decision can never drift apart.
 */
fun workflowRequested(text: String): Boolean = workflowActivationSpans(text).isNotEmpty()

/**
 * Whether [text] contains the keyword itself — `WorkflowPreapproved`.
 *
 * The distinction is real money: asking for "a workflow" in plain English is a
 * request and Spettro will confirm before it starts spawning agents, while
 * typing `ultracode` is a standing yes and the run begins unprompted. The UI
 * needs to be able to say which of the two it is about to do, so this stays
 * separate from [workflowRequested] rather than folded into it.
 */
fun workflowPreapproved(text: String): Boolean =
    ACTIVATION_RES[0].containsMatchIn(text)

// ---------------------------------------------------------------------------
// The glow itself
// ---------------------------------------------------------------------------

/**
 * Which surface the lit phrase is sitting on, and therefore which ramp reads.
 *
 * Three ramps rather than one with an alpha on it: the user bubble is filled
 * with the theme accent, and a ramp tuned to sit on the composer's background
 * either vanishes into that fill or fights it. The ramps are the one place in
 * this wave where hard-coded hex is correct — they are Spettro's identity for
 * this feature, quoted from docs/SPETTRO.md, not theme colours.
 */
enum class ActivationSurface {
    /** The composer and any plain-background text. */
    COMPOSER,

    /** The accent-filled, right-aligned user bubble in the transcript. */
    BUBBLE,
}

/**
 * Violet → magenta → specular → cyan, sampled as a continuous gradient rather
 * than as discrete steps so the phrase reads as one moving surface instead of
 * a row of separately coloured letters.
 *
 * The pale fourth stop is the specular highlight, and it travels *through* the
 * letters. It is deliberately not a pill, a chip or a highlighter box behind
 * them: two earlier Spettro Desktop versions drew it that way and both read as
 * "found" or "selected" — a search result, not a mode change.
 */
private val DARK_RAMP = listOf(
    Color(0xFF7C3AED), Color(0xFFA855F7), Color(0xFFE879F9),
    Color(0xFFECFEFF), Color(0xFF38BDF8), Color(0xFF22D3EE),
)

/**
 * The light-theme ramp. Not the dark one darkened — the pale specular stop has
 * to become the *most saturated* stop instead, because on a white background a
 * near-white highlight is a hole in the word rather than a shine on it.
 */
private val LIGHT_RAMP = listOf(
    Color(0xFF6D28D9), Color(0xFF9333EA), Color(0xFFC026D3),
    Color(0xFFDB2777), Color(0xFF0284C7), Color(0xFF0891B2),
)

/**
 * The bubble ramp: the same hues lifted into the 200–300 band so they stay
 * above an accent fill of any plausible lightness, with white as the specular.
 * A sent phrase is history rather than an armed control, so it glows a little
 * more quietly than the composer's.
 */
private val BUBBLE_RAMP = listOf(
    Color(0xFFDDD6FE), Color(0xFFF0ABFC), Color(0xFFFBCFE8),
    Color(0xFFFFFFFF), Color(0xFFBAE6FD), Color(0xFFA5F3FC),
)

/**
 * One full trip of the specular stop, in milliseconds.
 *
 * Unhurried on purpose: this sits under the caret while someone is typing, and
 * anything fast enough to notice as motion is fast enough to be a distraction.
 * The number is the terminal's own `ultracodeDriftFrames` (140 frames × 50 ms).
 */
private const val DRIFT_MILLIS = 7_000

/**
 * How many phases one drift is quantised into — the CLI's 50 ms tick, again.
 *
 * A `SpanStyle` brush is applied during *text layout*, so a phase that changed
 * every frame would re-lay-out the composer's text sixty times a second while
 * someone is typing into it. Stepping at the same 20 Hz the terminal renders
 * at costs a third of that and is indistinguishable, and it keeps the phone and
 * the terminal visibly the same effect.
 */
private const val DRIFT_STEPS = 140

/**
 * The travelling gradient, as a brush that only learns the text's width at
 * draw time.
 *
 * A plain [Brush.linearGradient] would need the width up front, which the
 * transformation does not have; a [ShaderBrush] is handed the laid-out size
 * instead. The window is one text-width wide and slides two widths per cycle
 * against [TileMode.Mirror], so the ramp reverses at each end rather than
 * jumping back to its first colour — these six stops do not loop, and a
 * repeating tile would put a hard seam through the word once per pass.
 */
@Immutable
private data class RampBrush(
    private val ramp: List<Color>,
    private val phase: Float,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val width = size.width.coerceAtLeast(1f)
        val head = phase * 2f * width
        return LinearGradientShader(
            from = Offset(head - width, 0f),
            to = Offset(head, 0f),
            colors = ramp,
            tileMode = TileMode.Mirror,
        )
    }
}

/**
 * [text] with every activating phrase painted by [brush] — the one function
 * both the composer and the transcript go through.
 *
 * Only the brush is set. Nothing here touches weight, size or letter spacing,
 * because all three change the text's metrics: a phrase that got bolder the
 * instant it completed would shove the caret sideways mid-word, and the
 * motion policy in docs/SPETTRO.md forbids exactly that kind of reflow.
 * Colour alone is enough — the moving specular is what carries the signal.
 */
fun activationGlow(text: AnnotatedString, brush: Brush): AnnotatedString {
    val spans = workflowActivationSpans(text.text)
    if (spans.isEmpty()) return text
    return buildAnnotatedString {
        append(text)
        for (span in spans) {
            // `last + 1`: IntRange is inclusive, addStyle's end is not.
            addStyle(SpanStyle(brush = brush), span.first, span.last + 1)
        }
    }
}

/** [activationGlow] for a plain string. */
fun activationGlow(text: String, brush: Brush): AnnotatedString =
    activationGlow(AnnotatedString(text), brush)

/**
 * The animated brush for [surface], or a still one when motion is reduced.
 *
 * Reduced motion stops the travel and keeps the colour. The glow is
 * *information* — it says the next turn will be allowed to spawn agents — and
 * information is not what an accessibility setting switches off; the drift is
 * only how that information is said (see ui/theme/Motion.kt). Freezing at
 * phase 0.5 also leaves the specular stop somewhere inside the phrase rather
 * than parked off one end, so the still version still reads as lit.
 */
@Composable
fun rememberActivationBrush(surface: ActivationSurface = ActivationSurface.COMPOSER): Brush {
    val ramp = when {
        surface == ActivationSurface.BUBBLE -> BUBBLE_RAMP
        LocalZedTheme.current.isDark -> DARK_RAMP
        else -> LIGHT_RAMP
    }
    val still = LocalReduceMotion.current
    val drift by rememberInfiniteTransition(label = "activation").animateFloat(
        initialValue = 0f,
        targetValue = DRIFT_STEPS.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = DRIFT_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "activation-drift",
    )
    // Quantise before it reaches `remember`, so a new brush — and with it a
    // new text layout — is produced 20 times a second and not 60.
    val step = if (still) DRIFT_STEPS / 2 else drift.toInt() % DRIFT_STEPS
    return remember(ramp, step) { RampBrush(ramp, step.toFloat() / DRIFT_STEPS) }
}

/**
 * The composer's [VisualTransformation]: identity offsets, activating phrases
 * lit.
 *
 * Identity mapping because nothing is inserted, removed or reordered — this
 * only styles what is already there, so selection, the caret and every
 * `TextRange` the field reports stay exactly where the user put them. Compose
 * can style text in place, so there is no mirror-Text-behind-the-field hack
 * here of the kind a web client needs.
 */
@Composable
fun rememberActivationTransformation(
    surface: ActivationSurface = ActivationSurface.COMPOSER,
): VisualTransformation {
    val brush = rememberActivationBrush(surface)
    return remember(brush) { ActivationTransformation(brush) }
}

/**
 * The transformation itself, kept as a value class rather than a lambda so
 * that an unchanged brush compares equal and does not re-filter the field.
 */
@Immutable
private data class ActivationTransformation(private val brush: Brush) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(activationGlow(text, brush), OffsetMapping.Identity)
}
