package to.eyed.thragg.core

import org.json.JSONObject

/**
 * The fold that turns Spettro's flat tool-call stream into the nested run the
 * panel draws (SPETTRO.md § state model 4).
 *
 * ACP has one shape for everything a model does: a tool call. A workflow run,
 * the phases inside it, each sub-agent launched under a phase and every tool
 * that sub-agent then calls all arrive as siblings in one list, related only
 * by fields inside their arguments. Drawing that list literally is a wall of
 * two hundred rows in which the four that matter are invisible; this file is
 * the one place that reassembles it.
 *
 * Pure, and deliberately so: no Compose, no engine calls, no clock. It is
 * re-run from scratch on every poll — which is also the whole re-entrancy
 * story, because Spettro **rewrites** a run's tool call as it progresses
 * (the finish update replaces `rawInput` with a summary, destroying the
 * declared phase list) and a fold that accumulated state across polls would
 * have to unpick that. Folding the list again costs a few hundred string
 * compares and cannot drift.
 *
 * Why Kotlin and not the engine: `session/load` replays only flat
 * user/assistant/thought messages, so a restored conversation has no tool
 * calls at all and there is no "the live fold and the replayed fold must
 * agree" invariant to protect. The single thing the engine must hold on to is
 * the opening `rawInput` (W-09) — everything else is derivable here.
 *
 * **Classify by [AgentEntry.ToolCall.openArgs], never by the title.** Spettro
 * truncates the title's inline JSON at 120 characters, so a title is
 * routinely invalid JSON and is trustworthy for exactly two things: the tool
 * name and the `[instance]` bracket.
 */

/** Where one thing in a run got to. */
enum class OrchStatus {
    Running,
    Done,
    Failed;

    val isMoving: Boolean get() = this == Running

    internal companion object {
        /**
         * Everything that is not plainly over is running.
         *
         * Deliberately generous: a member the agent forgot to close out reads
         * as still working, which is recoverable, where reading it as done
         * would claim a success nobody can vouch for.
         */
        fun of(status: ToolCallStatus): OrchStatus = when (status) {
            ToolCallStatus.Failed -> Failed
            ToolCallStatus.Completed -> Done
            else -> Running
        }
    }
}

/**
 * How a run or a phase is doing.
 *
 * [total] is the **denominator the user was promised** — a swarm's item list,
 * a phase's member list — not the number launched so far. Ultra ramps its
 * launches (five at once, then one every 700 ms), so a denominator that grew
 * with the launches would make a 7/20 meter run backwards to 7/12.
 */
data class OrchCounts(
    val total: Int,
    val running: Int,
    val done: Int,
    val failed: Int,
    val cached: Int,
) {
    /** How far along, 0..1. Failures count as settled: they are not coming back. */
    val fraction: Float
        get() = if (total > 0) ((done + failed).toFloat() / total).coerceIn(0f, 1f) else 0f

    /**
     * `4/7`, or `—` for a run that has declared work but launched none.
     *
     * A run can be drawn before a single member exists — a workflow that
     * declared three phases publishes them as PENDING first, which is the
     * whole point of declaring a plan. [total] is 0 there, and `0/0` reads as
     * a run with nothing in it rather than one that has not started, which is
     * the opposite of what the card is saying.
     */
    val ratio: String get() = if (total > 0) "${done + failed}/$total" else "—"

    val settled: Int get() = done + failed

    companion object {
        val NONE = OrchCounts(0, 0, 0, 0, 0)

        internal fun of(members: List<Member>, total: Int = members.size) = OrchCounts(
            total = total,
            running = members.count { it.status == OrchStatus.Running },
            done = members.count { it.status == OrchStatus.Done },
            failed = members.count { it.status == OrchStatus.Failed },
            cached = members.count { it.cached },
        )
    }
}

/**
 * One sub-agent, with the tool calls it made underneath it.
 *
 * [instance] (`review#2`) is what distinguishes one member from another and
 * [specId] (`review`) is what tints it — the same spec fanned out twelve ways
 * must read as twelve of one thing, not twelve unrelated things.
 */
data class Member(
    val tool: AgentEntry.ToolCall,
    /** `review#2` — the name its own children's titles are prefixed with. */
    val instance: String,
    /** `review` — the part before the `#`, and the tint key. */
    val specId: String,
    val index: Int?,
    /** What it was asked to do. */
    val task: String,
    /** `""` when it belongs to no phase. */
    val phase: String,
    /** Restored from the resume journal. Spelled **replayed** in the UI. */
    val cached: Boolean,
    val status: OrchStatus,
    /** Its own nested tool calls, in the order they were made. */
    val children: List<AgentEntry.ToolCall>,
    /** Its summary if it wrote one, else whatever it printed. */
    val resultText: String,
    val resultIsJson: Boolean,
    /** Everything it printed, before any summary was lifted out of it. */
    val rawResult: String = "",
) {
    /**
     * The one line the row shows.
     *
     * While it is running that is its LATEST tool call with its own bracket
     * stripped — in a fan-out the launch tasks are near-identical ("add doc
     * comments to internal/x") and say nothing about who is stuck; what a
     * member is *doing right now* is the only part worth a row. Once it has
     * finished there is no current call, so the task comes back.
     */
    val liveDetail: String
        get() {
            if (status == OrchStatus.Running) {
                val latest = children.lastOrNull()
                if (latest != null) {
                    val stripped = latest.agentPrefix
                        ?.let { latest.title.removePrefix("[$it]").trimStart() }
                        ?: latest.title
                    if (stripped.isNotBlank()) return stripped
                }
            }
            return task.ifBlank { tool.title }
        }

    /**
     * Why it failed, in the words most likely to be actionable.
     *
     * The summary first, then the usual JSON error keys, then the raw text —
     * because the case that matters most, a provider's bare
     * `429 after 3 attempts`, never arrives as a structured report and would
     * otherwise render as an empty red row.
     */
    val failureReason: String
        get() {
            if (status != OrchStatus.Failed) return ""
            val raw = rawResult
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return raw.trim()
            for (key in listOf("summary", "error", "message", "reason")) {
                val value = json.optString(key)
                if (value.isNotEmpty()) return value
            }
            return raw.trim()
        }

}

/**
 * One phase of a workflow run.
 *
 * [declared] separates "the run said it would do this" from "work turned up
 * under a name nobody announced". A declared phase with no members yet is
 * drawn *pending* rather than hidden: seeing the whole plan at t=0 is half
 * the value of a run that declares one.
 */
data class WorkflowPhase(
    val title: String,
    val detail: String,
    val members: List<Member>,
    val counts: OrchCounts,
    val declared: Boolean,
) {
    /** Nothing has landed here yet. */
    val isPending: Boolean get() = members.isEmpty()

    /** Running outranks failed outranks done — the header's glyph. */
    val status: OrchStatus
        get() = when {
            members.isEmpty() -> OrchStatus.Running
            counts.running > 0 -> OrchStatus.Running
            counts.failed > 0 -> OrchStatus.Failed
            else -> OrchStatus.Done
        }
}

/** The script call that authored a workflow, when one is claimed. */
data class WorkflowScript(
    val tool: AgentEntry.ToolCall,
    /** The script's source, or the path it was loaded from. */
    val source: String,
    /** What it was saved as, when it was saved. */
    val savedAs: String,
    /** The run it declared, from `<workflow_result run_id="…">`. */
    val runId: String,
    /** Its `<returned>` value, pretty-printed — what it was written to produce. */
    val returned: String,
    val status: OrchStatus,
    val error: String,
)

/** A run: a declared workflow, or an Ultra swarm. */
sealed interface OrchRun {
    val tool: AgentEntry.ToolCall
    val status: OrchStatus
    val counts: OrchCounts

    /** Every member of the run, phases flattened. */
    val members: List<Member>

    data class Workflow(
        override val tool: AgentEntry.ToolCall,
        val runId: String,
        val name: String,
        val description: String,
        /** Where it came from — a saved workflow, an inline script. */
        val origin: String,
        val phases: List<WorkflowPhase>,
        /** `log()` lines. There is no structured channel for these at all. */
        val logs: List<String>,
        /** `12 agents · 1 failed · 3 replayed`. */
        val summary: String,
        /** The CLI's own ASCII tree, for the raw sheet. Never wrapped. */
        val rendered: String,
        val script: WorkflowScript?,
        override val status: OrchStatus,
        override val counts: OrchCounts,
    ) : OrchRun {
        override val members: List<Member> get() = phases.flatMap { it.members }
    }

    data class Swarm(
        override val tool: AgentEntry.ToolCall,
        val description: String,
        /** The spec every member runs — `code`, `review`. */
        val subagentType: String,
        /** `worktree`, `none`; the pill on the card. */
        val isolation: String,
        /** Everything the swarm was asked to cover, in order. */
        val items: List<String>,
        override val members: List<Member>,
        /** `items.drop(members.size)` — the ghost cells nobody else draws. */
        val pending: List<String>,
        override val status: OrchStatus,
        override val counts: OrchCounts,
    ) : OrchRun
}

/**
 * One row of the folded transcript.
 *
 * [id] is stable across polls and unique across turns: tool-call ids repeat
 * in every turn (W-17), so it is keyed on `turn:id` rather than on the id
 * alone — two turns that both raised `wf-1` must not share a card.
 */
sealed interface TranscriptRow {
    val id: String

    /** Anything the fold did not absorb: a message, a plain tool call. */
    data class Item(override val id: String, val entry: AgentEntry) : TranscriptRow

    /** A sub-agent that belongs to no run. */
    data class Agent(override val id: String, val member: Member) : TranscriptRow

    data class Run(override val id: String, val run: OrchRun) : TranscriptRow

    /**
     * A script call no run claimed.
     *
     * Kept as its own row because it is the only evidence a workflow was
     * attempted and failed before any run existed — dropping it leaves the
     * transcript saying nothing happened.
     */
    data class Script(override val id: String, val script: WorkflowScript) : TranscriptRow
}

/**
 * Fold a transcript into rows.
 *
 * Two linear passes. The first indexes every tool call by what it *is* —
 * run, script, swarm, member, child of a member — and hangs members off runs
 * and children off members; the second re-emits the entry list in its
 * original order, dropping everything the first pass absorbed. Order is
 * never invented: a run appears where its tool call appeared, which is where
 * the reader watched it start.
 *
 * Out-of-order arrival is not a special case. Both passes see the whole list,
 * so a child that streams in before the member that owns it, or a member
 * before its run's opening call, lands correctly with no fixups.
 *
 * Memoise on the caller's side (`remember(entries) { foldOrchestration(it) }`)
 * — this file holds no cache of its own, because the entry list is replaced
 * wholesale on every poll and a cache keyed on anything else would go stale
 * exactly when a run is being rewritten.
 */
fun foldOrchestration(entries: List<AgentEntry>): List<TranscriptRow> {
    val calls = entries.withIndex().mapNotNull { (index, entry) ->
        (entry as? AgentEntry.ToolCall)?.let { IndexedCall(index, it) }
    }
    if (calls.isEmpty()) {
        return entries.mapIndexed { index, entry -> TranscriptRow.Item("e-$index", entry) }
    }

    // --- pass 1: what is each call? -----------------------------------------
    val runs = mutableListOf<IndexedCall>()
    val swarms = mutableListOf<IndexedCall>()
    val scripts = mutableListOf<IndexedCall>()
    val memberDrafts = mutableListOf<MemberDraft>()
    val childCalls = mutableListOf<IndexedCall>()
    val progressCalls = mutableListOf<IndexedCall>()
    for (call in calls) {
        when (classify(call.tool)) {
            Role.Run -> runs += call
            Role.Script -> scripts += call
            Role.Swarm -> swarms += call
            Role.Member -> memberDrafts += MemberDraft(call)
            Role.Child -> childCalls += call
            Role.Progress -> progressCalls += call
            Role.Plain -> Unit
        }
    }

    // Children by instance, newest owner above them: an instance name can
    // come round again in a later turn, so the owner is the nearest member
    // with that name *above* the child, never merely the first.
    for (child in childCalls) {
        val prefix = child.tool.agentPrefix ?: continue
        val owner = memberDrafts.lastOrNull { it.instance == prefix && it.call.index < child.index }
            ?: memberDrafts.firstOrNull { it.instance == prefix }
            ?: continue
        owner.children += child.tool
    }

    val members = memberDrafts.associateWith { it.build() }

    // Workflow members attach by `run_id`; swarm members to the nearest ultra
    // call still running above them, else to the last one above them. A
    // member whose run never turned up keeps its own row rather than
    // vanishing.
    val byRun = mutableMapOf<String, MutableList<Member>>()
    val bySwarm = mutableMapOf<Int, MutableList<Member>>()
    val absorbed = mutableSetOf<Int>()
    for (draft in memberDrafts) {
        val member = members.getValue(draft)
        val runId = draft.runId
        if (runId.isNotEmpty()) {
            val run = runs.lastOrNull { runIdOf(it.tool) == runId }
            if (run != null) {
                byRun.getOrPut(runId) { mutableListOf() } += member
                absorbed += draft.call.index
            }
            continue
        }
        if (!draft.swarm) continue
        val above = swarms.filter { it.index < draft.call.index }.ifEmpty { swarms }
        val host = above.lastOrNull { OrchStatus.of(it.tool.status) == OrchStatus.Running }
            ?: above.lastOrNull()
            ?: continue
        bySwarm.getOrPut(host.index) { mutableListOf() } += member
        absorbed += draft.call.index
    }
    absorbed += childCalls.map { it.index }
    absorbed += progressCalls.map { it.index }

    // Script calls: claimed by the run their own output names, else by the
    // nearest unclaimed one above the run.
    val scriptDrafts = scripts.associate { it.index to parseScript(it.tool) }
    val claimed = mutableSetOf<Int>()
    val scriptForRun = mutableMapOf<Int, WorkflowScript>()
    for (run in runs) {
        val runId = runIdOf(run.tool)
        val byId = scripts.firstOrNull { candidate ->
            candidate.index !in claimed &&
                runId.isNotEmpty() &&
                scriptDrafts.getValue(candidate.index).runId == runId
        }
        val chosen = byId ?: scripts.lastOrNull { it.index !in claimed && it.index < run.index }
        if (chosen != null) {
            claimed += chosen.index
            scriptForRun[run.index] = scriptDrafts.getValue(chosen.index)
        }
    }
    absorbed += claimed

    // --- pass 2: re-emit in the transcript's own order -----------------------
    val runRows = runs.associate { call ->
        call.index to buildWorkflow(
            call.tool,
            byRun[runIdOf(call.tool)].orEmpty(),
            scriptForRun[call.index],
        )
    }
    val swarmRows = swarms.associate { call ->
        call.index to buildSwarm(call.tool, bySwarm[call.index].orEmpty())
    }
    val standalone = memberDrafts
        .filter { it.call.index !in absorbed }
        .associate { it.call.index to members.getValue(it) }

    return entries.mapIndexedNotNull { index, entry ->
        when {
            index in absorbed -> null
            runRows.containsKey(index) ->
                TranscriptRow.Run("run-${keyOf(entry)}", runRows.getValue(index))
            swarmRows.containsKey(index) ->
                TranscriptRow.Run("run-${keyOf(entry)}", swarmRows.getValue(index))
            standalone.containsKey(index) ->
                TranscriptRow.Agent("agent-${keyOf(entry)}", standalone.getValue(index))
            scriptDrafts.containsKey(index) ->
                TranscriptRow.Script("script-${keyOf(entry)}", scriptDrafts.getValue(index))
            else -> TranscriptRow.Item("e-$index", entry)
        }
    }
}

private fun keyOf(entry: AgentEntry): String = (entry as? AgentEntry.ToolCall)?.key.orEmpty()

private class IndexedCall(val index: Int, val tool: AgentEntry.ToolCall)

private enum class Role { Run, Progress, Script, Swarm, Member, Child, Plain }

/** The title with any `[review#2] ` orchestration bracket taken off. */
private fun unprefixed(tool: AgentEntry.ToolCall): String =
    tool.agentPrefix?.let { tool.title.removePrefix("[$it]").trimStart() } ?: tool.title

/**
 * The classification table, in the order the tests must be made.
 *
 * Every test reads [AgentEntry.ToolCall.openArgs] — the arguments the call
 * *opened* with. A finished run's latest `rawInput` is a summary object with
 * no `phases` and no `description`, so classifying on it would turn a
 * completed workflow back into an anonymous tool call the moment it
 * succeeded.
 */
private fun classify(tool: AgentEntry.ToolCall): Role {
    val args = tool.openArgs
    val name = tool.toolName
    val workflow = args?.optString("workflow").orEmpty()
    val agent = args?.optString("agent").orEmpty()
    val kind = args?.optString("kind").orEmpty()
    // A run's lifecycle call. `kind` phase/log are the *progress* calls that
    // ride the same tool name and must not each open a card.
    if (workflow.isNotEmpty() && agent.isEmpty()) {
        // `phase` and `log` ride the same tool name to report progress on a
        // run that already has a card. They are absorbed rather than drawn:
        // what they carry is already in the run's own rendered tree, and a
        // row per phase transition buries the run it belongs to.
        return if (kind == "phase" || kind == "log") Role.Progress else Role.Run
    }
    if (name == "workflow" &&
        runIdOf(tool).isEmpty() &&
        workflow.isEmpty() &&
        SCRIPT_KEYS.any { !args?.optString(it).isNullOrEmpty() }
    ) {
        return Role.Script
    }
    if (name == "ultra" || unprefixed(tool).startsWith("ultra ")) return Role.Swarm
    // A bracket naming somebody *else* makes this a call made by that member,
    // whatever the tool is — including another `agent` call, which is a
    // sub-agent launching a sub-agent.
    val prefix = tool.agentPrefix
    if (prefix != null && prefix.substringBefore('#') != agent) return Role.Child
    if (name == "agent") return Role.Member
    return Role.Plain
}

private val SCRIPT_KEYS = listOf("script", "save_as", "script_path", "name")

private fun runIdOf(tool: AgentEntry.ToolCall): String =
    tool.openArgs?.optString("run_id").orEmpty().ifEmpty {
        tool.args?.optString("run_id").orEmpty()
    }

/** A member being assembled: its children arrive as separate entries. */
private class MemberDraft(val call: IndexedCall) {
    val args: JSONObject? = call.tool.openArgs
    val children = mutableListOf<AgentEntry.ToolCall>()

    val specId: String = args?.optString("agent").orEmpty()
        .ifEmpty { call.tool.agentPrefix?.substringBefore('#').orEmpty() }

    val index: Int? = args?.takeIf { it.has("index") && !it.isNull("index") }?.optInt("index")

    val instance: String = call.tool.agentPrefix
        ?.takeIf { it.substringBefore('#') == specId }
        ?: if (index != null && specId.isNotEmpty()) "$specId#$index" else specId

    val runId: String = args?.optString("run_id").orEmpty()

    val swarm: Boolean = args?.optBoolean("swarm") == true

    fun build(): Member {
        val raw = markdownOf(call.tool)
        val json = runCatching { JSONObject(raw) }.getOrNull()
        // A member that reported an error is failed even when the tool call
        // itself closed cleanly — the sub-agent returning "I could not" is a
        // successful *call* and a failed *member*, and the card is about the
        // member.
        val erroring = json?.optString("status") == "error"
        val status = if (erroring) OrchStatus.Failed else OrchStatus.of(call.tool.status)
        val summary = json?.optString("summary").orEmpty()
        return Member(
            tool = call.tool,
            instance = instance,
            specId = specId.ifEmpty { instance.substringBefore('#') },
            index = index,
            task = args?.optString("task").orEmpty()
                .ifEmpty { args?.optString("prompt").orEmpty() }
                .ifEmpty { args?.optString("description").orEmpty() },
            phase = args?.optString("phase").orEmpty(),
            cached = args?.optBoolean("cached") == true,
            status = status,
            children = children.toList(),
            resultText = summary.ifEmpty { json?.toString(2) ?: raw.trim() },
            resultIsJson = json != null,
            rawResult = raw,
        )
    }
}

/** The first markdown block a tool call produced; "" when it produced none. */
private fun markdownOf(tool: AgentEntry.ToolCall): String =
    tool.content.filterIsInstance<ToolContent.Markdown>()
        .firstOrNull()
        ?.markdown
        .orEmpty()

private fun buildWorkflow(
    tool: AgentEntry.ToolCall,
    members: List<Member>,
    script: WorkflowScript?,
): OrchRun.Workflow {
    val open = tool.openArgs
    val rendered = markdownOf(tool)
    val mined = parseRenderedWorkflow(rendered)

    // Declared phases in the order the run announced them, then anything that
    // turned up under a name it never announced, then the unnamed bucket —
    // which only exists when work arrived with no phase at all.
    val declared = declaredPhases(open).ifEmpty { mined.phases }
    val byPhase = members.groupBy { it.phase }
    val rows = mutableListOf<WorkflowPhase>()
    for ((title, detail) in declared) {
        val own = order(byPhase[title].orEmpty())
        rows += WorkflowPhase(title, detail, own, OrchCounts.of(own), declared = true)
    }
    val declaredTitles = declared.map { it.first }.toSet()
    for (title in byPhase.keys) {
        if (title.isEmpty() || title in declaredTitles) continue
        val own = order(byPhase.getValue(title))
        rows += WorkflowPhase(title, "", own, OrchCounts.of(own), declared = false)
    }
    val unnamed = order(byPhase[""].orEmpty())
    if (unnamed.isNotEmpty()) {
        rows += WorkflowPhase("", "", unnamed, OrchCounts.of(unnamed), declared = false)
    }

    // The finish update's summary is authoritative when it exists: it counts
    // members the transcript may never have carried a tool call for, because
    // a replayed run reports agents it did not re-launch.
    val finish = tool.args
    val counts = OrchCounts(
        total = maxOf(members.size, finish?.optInt("agents") ?: 0),
        running = members.count { it.status == OrchStatus.Running },
        done = members.count { it.status == OrchStatus.Done },
        failed = maxOf(members.count { it.status == OrchStatus.Failed }, finish?.optInt("failed") ?: 0),
        cached = maxOf(members.count { it.cached }, finish?.optInt("cached") ?: 0),
    )
    return OrchRun.Workflow(
        tool = tool,
        runId = runIdOf(tool),
        name = open?.optString("workflow").orEmpty().ifEmpty { tool.title },
        description = open?.optString("description").orEmpty(),
        origin = open?.optString("origin").orEmpty(),
        phases = rows,
        logs = mined.logs,
        summary = summaryOf(counts),
        rendered = rendered,
        script = script,
        status = OrchStatus.of(tool.status),
        counts = counts,
    )
}

/** `12 agents · 1 failed · 3 replayed`, with the zero terms left out. */
private fun summaryOf(counts: OrchCounts): String {
    val parts = mutableListOf("${counts.total} agent${if (counts.total == 1) "" else "s"}")
    if (counts.failed > 0) parts += "${counts.failed} failed"
    // Never "cached": it means restored from the resume journal, not
    // prompt-cached, and the two are different claims about the same run.
    if (counts.cached > 0) parts += "${counts.cached} replayed"
    return parts.joinToString(" · ")
}

/**
 * Running first, then failed, then done, stable within each group.
 *
 * What is still moving is the only part that can be acted on; once nothing
 * moves, failures lead, because they are what the reader came back for.
 * Within a group the launch order survives untouched — resorting a live list
 * makes the rows churn under the thumb.
 */
private fun order(members: List<Member>): List<Member> =
    members.sortedBy {
        when (it.status) {
            OrchStatus.Running -> 0
            OrchStatus.Failed -> 1
            OrchStatus.Done -> 2
        }
    }

/** `phases` as the run declared it: strings, or objects with a description. */
private fun declaredPhases(args: JSONObject?): List<Pair<String, String>> {
    val array = args?.optJSONArray("phases") ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val row = array.optJSONObject(index)
        if (row == null) {
            array.optString(index).takeIf { it.isNotEmpty() }?.let { it to "" }
        } else {
            val title = row.optString("name").ifEmpty { row.optString("title") }
            title.takeIf { it.isNotEmpty() }?.let {
                it to row.optString("description").ifEmpty { row.optString("detail") }
            }
        }
    }
}

private fun buildSwarm(tool: AgentEntry.ToolCall, members: List<Member>): OrchRun.Swarm {
    val open = tool.openArgs
    val items = open?.optJSONArray("items")?.let { array ->
        (0 until array.length()).map { array.optString(it) }
    }.orEmpty()
    val ordered = order(members)
    // Ghost cells: the items no member has been launched for yet. Rendered at
    // the same geometry as a real row so nothing below moves when one is
    // filled in.
    val pending = items.drop(members.size)
    val counts = OrchCounts.of(ordered, total = members.size + pending.size)
    return OrchRun.Swarm(
        tool = tool,
        description = open?.optString("description").orEmpty()
            .ifEmpty { open?.optString("task").orEmpty() },
        subagentType = open?.optString("subagent_type").orEmpty()
            .ifEmpty { open?.optString("agent").orEmpty() },
        isolation = open?.optString("isolation").orEmpty(),
        items = items,
        members = ordered,
        pending = pending,
        status = OrchStatus.of(tool.status),
        counts = counts,
    )
}

private fun parseScript(tool: AgentEntry.ToolCall): WorkflowScript {
    val args = tool.openArgs
    val output = markdownOf(tool)
    return WorkflowScript(
        tool = tool,
        source = args?.optString("script").orEmpty()
            .ifEmpty { args?.optString("script_path").orEmpty() },
        savedAs = args?.optString("save_as").orEmpty()
            .ifEmpty { args?.optString("name").orEmpty() },
        runId = RESULT_RUN_ID.find(output)?.groupValues?.get(1).orEmpty(),
        // Shown first on the card: the returned value is what the script was
        // written to produce, and the tree above it is only how it got there.
        returned = RETURNED.find(output)?.groupValues?.get(1)?.let(::prettyOrRaw).orEmpty(),
        status = OrchStatus.of(tool.status),
        error = if (tool.status == ToolCallStatus.Failed) output.trim() else "",
    )
}

private val RESULT_RUN_ID = Regex("""<workflow_result[^>]*\brun_id="([^"]*)"""")
private val RETURNED = Regex("""<returned>([\s\S]*?)</returned>""")

private fun prettyOrRaw(text: String): String {
    val trimmed = text.trim()
    return runCatching { JSONObject(trimmed).toString(2) }.getOrNull() ?: trimmed
}

/** What could be mined back out of the CLI's rendered tree. */
data class RenderedWorkflow(
    val logs: List<String>,
    val phases: List<Pair<String, String>>,
)

/**
 * Read the two things that exist only in the rendered text.
 *
 * `log()` lines have **no structured channel at all** — the CLI prints them
 * into the tree and nowhere else — and the phase list is mined only as a
 * fallback for a run whose opening `rawInput` was somehow missed.
 *
 * The block test is deliberately strict. A run description is prose in the
 * same text, and a sentence beginning with a bullet would otherwise be read
 * as a phase header; requiring that *every* line of the block be a header or
 * an indented member row, and taking the last such block, is what keeps a
 * description out of the phase spine.
 *
 * Wrapped whole in [runCatching] by its caller's contract: a missing
 * description is cosmetic, a thrown exception is a blank chat.
 */
fun parseRenderedWorkflow(text: String): RenderedWorkflow = runCatching {
    if (text.isBlank()) return@runCatching RenderedWorkflow(emptyList(), emptyList())
    val lines = text.lines()

    val logs = mutableListOf<String>()
    val at = lines.indexOfFirst { it.trimStart().startsWith("log:") }
    if (at >= 0) {
        val head = lines[at].trimStart().removePrefix("log:").trim()
        if (head.isNotEmpty() && !head.startsWith("(")) logs += unquote(head)
        for (line in lines.drop(at + 1)) {
            if (line.isBlank()) break
            // A line back at the left margin has left the block; the tree
            // indents everything that belongs to it.
            if (!line.first().isWhitespace()) break
            logs += unquote(line.trim())
        }
    }

    val phases = lines.fold(mutableListOf(mutableListOf<String>())) { blocks, line ->
        if (line.isBlank()) blocks += mutableListOf<String>() else blocks.last() += line
        blocks
    }.lastOrNull { block ->
        block.isNotEmpty() &&
            block.any { PHASE_HEADER.matches(it) } &&
            block.all { PHASE_HEADER.matches(it) || MEMBER_ROW.matches(it) }
    }.orEmpty().mapNotNull { line ->
        PHASE_HEADER.find(line)?.groupValues?.get(1)?.trim()
    }.map { header ->
        // Title and detail are separated by a run of spaces in the tree; one
        // space is part of the title.
        val split = COLUMN_GAP.split(header, 2)
        split[0].trim() to split.getOrElse(1) { "" }.trim()
    }
    RenderedWorkflow(logs, phases)
}.getOrDefault(RenderedWorkflow(emptyList(), emptyList()))

private val PHASE_HEADER = Regex("""^ {0,3}[▸○]\s*─?\s*(\S.*?)\s*$""")
private val MEMBER_ROW = Regex("""^\s+[✓▶✗●○]\s+\S.*$""")
private val COLUMN_GAP = Regex(" {2,}")

private fun unquote(text: String): String =
    text.trim().removeSurrounding("“", "”").removeSurrounding("\"")
