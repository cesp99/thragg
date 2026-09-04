# Spettro on the phone

Thragg ships one agent, Spettro, and ships it whole. Spettro's ACP is a
superset of the standard — workflows with live phases, Ultra mode, the
Mode/Model/Permission/Thinking selectors, question forms, a live context gauge,
steering — and the point of this document is that the phone renders **all of
it**, rather than reducing Spettro to the common denominator a generic ACP
client would see.

This spec was produced by reading Spettro's Go protocol implementation, its
Electron desktop client (the reference client), its renderer, and Seeker's own
Rust+Kotlin ACP layer, then reconciling the four.

## Wire work

Each item says where it lands: the Rust engine, Kotlin, or both.

### W-01 initialize `_meta` extension mirror — THE GATE

*Lands in:* **rust-engine**

core/crates/engine/src/acp.rs, `agent_main`, the request built at ~line 1553. Today it is `acp::InitializeRequest::new(ProtocolVersion::V1).client_capabilities(capabilities).client_info(acp::Implementation::new("thragg", crate::ENGINE_VERSION))`. Add `.meta(...)` carrying EXACTLY:
```json
{"spettro.app/extensions":{"version":4,"methods":["_spettro/question/ask"]}}
```
`InitializeRequest` has `pub meta: Option<Meta>` renamed `_meta` (agent-client-protocol-schema-1.5.0/src/v1/agent.rs:81-82) and a `.meta(impl IntoOption<Meta>)` builder. It MUST be the top-level `_meta` on the request, NOT `clientCapabilities._meta` — Spettro's `parseClientExtensions` (internal/acp/question.go) reads `params.Meta["spettro.app/extensions"]["methods"]` and nothing else. Do NOT use the crate's `MetaCapabilityExt` helpers; they write into `clientCapabilities._meta.symposium`. Getting this wrong silently downgrades every ask-user form to a one-question-at-a-time permission walk with no error anywhere. Send this unconditionally for every agent — a non-Spettro agent ignores an unknown `_meta` key.

KEEP the existing `clientCapabilities` block unchanged (fs, terminal, auth.terminal, elicitation.form+url, session.configOptions.boolean). In particular KEEP `elicitation.form`: once `_spettro/question/ask` is served it wins the transport ladder anyway, and elicitation remains the fallback if a future CLI drops the extension. We already serve it end-to-end (acp_elicit.rs).

### W-02 read the agent's advertised extension surface

*Lands in:* **rust-engine**

core/crates/engine/src/acp.rs, `agent_main` after the `initialize` response lands (~line 1573, where `AgentInfo` is built). Read `response.meta` → `["spettro.app/extensions"]` = `{"version":u32,"methods":[String],"clientMethods":[String]}`. Store on `AgentInfo` as `spettro_extensions: Option<serde_json::Value>` and emit it verbatim inside the `"agent"` object of `acp_session_state` as `"spettroExtensions"`.

Everything Spettro-specific in the UI gates on `agent.spettroExtensions != null`. `_spettro/workflow/*` gates on `version >= 4`. Trust the number on the wire (the shipping CLI sends 4; docs/acp.md still says 3). Also treat `agent.agent_name == "spettro"` as a secondary signal but never as the gate.

### W-03 serve inbound `_spettro/*` requests (catch-all)

*Lands in:* **rust-engine**

core/crates/engine/src/acp.rs, `run_connection` (line 1397-1512). Append ONE handler AFTER all existing typed `.on_receive_request(...)` registrations — handlers run in registration order and `Handled::No` falls through, so a trailing catch-all cannot shadow the typed ones:
```rust
.on_receive_request(
    async move |request: acp::UntypedMessage,
                responder: Responder<serde_json::Value>,
                _cx| {
        if request.method.as_ref() == "_spettro/question/ask" {
            question_shared.on_spettro_question(request, responder);
            Ok(())
        } else {
            Ok(Handled::No { message: (request, responder), retry: false })
        }
    },
    agent_client_protocol::on_receive_request!(),
)
```
Use `agent_client_protocol::UntypedMessage` (jsonrpc.rs:4491-4560, `matches_method() == true`, `type Response = serde_json::Value`). Do NOT register `acp::ExtRequest` / `AgentRequest::ExtMethodRequest` — `ExtRequest` does not implement `JsonRpcRequest`, and the typed enum route would swallow every inbound request. Note `UntypedMessage.method` keeps the leading `_`; the typed enums strip it.

Anything else `_`-prefixed keeps falling through to the role default, which answers `-32601`. That is correct and Spettro handles it by degrading.

### W-04 the parked-question store

*Lands in:* **rust-engine**

NEW FILE core/crates/engine/src/acp_question.rs, modelled line-for-line on acp_elicit.rs (`PendingElicitation` / `Elicitations`): an `Arc<PendingQuestion>` holding the raw params `serde_json::Value`, a `Mutex<Option<Responder<serde_json::Value>>>`, a client id, and an `AtomicU64` version counter the panel polls.

Exact public API (frozen — R1 and R4 code against it):
```rust
pub(crate) struct Questions { /* next: AtomicU64, live: Mutex<Vec<Arc<PendingQuestion>>>, version: AtomicU64 */ }
impl Questions {
    pub(crate) fn version(&self) -> u64;
    /// Park a question. `session` is resolved from params.sessionId when the
    /// acp session id is indexed, else None (show it in the open thread).
    pub(crate) fn open(&self, params: serde_json::Value, session: Option<u64>,
                       responder: Responder<serde_json::Value>) -> Arc<PendingQuestion>;
    /// All open questions, newest last: `[{ "id":"question-1", "session":7, "payload": <params verbatim> }]`
    pub(crate) fn view_json(&self) -> serde_json::Value;
    /// Answer once. `answer` is the JSON-RPC RESULT, built in Kotlin.
    pub(crate) fn answer(&self, id: &str, answer: serde_json::Value) -> bool;
    /// Decline every question of a session (session closed) or all (agent died):
    /// responds `{"kind":"cancelled"}` so the turn is never stranded.
    pub(crate) fn cancel_session(&self, session: u64);
    pub(crate) fn cancel_all(&self);
}
```
Hold `Questions` on `AgentShared` next to `Elicitations`; call `cancel_session` from the same places `Elicitations::cancel_session` is called and `cancel_all` on connection teardown. A question nobody answers is an agent that waits forever.

The payload is forwarded VERBATIM — do not model it in Rust. Kotlin parses `version`, `sessionId`, `question`, `context`, `options[]`, `allowCustomInput`, `questions[]`.

### W-05 JNI + externs for questions

*Lands in:* **both**

core/crates/jni-bridge/src/lib.rs, beside the elicitation exports (3944-4010):
```rust
Java_to_eyed_thragg_core_CoreBridge_acpQuestionsVersion(JNIEnv, JClass) -> jlong
Java_to_eyed_thragg_core_CoreBridge_acpPendingQuestions(JNIEnv, JClass) -> jstring   // view_json
Java_to_eyed_thragg_core_CoreBridge_acpRespondQuestion(JNIEnv, JClass, question_id: JString, answer_json: JString) -> jboolean
```
Engine side: `Engine::acp_questions_version()`, `acp_pending_questions() -> String`, `acp_respond_question(&self, question_id: &str, answer_json: &str) -> bool` (parses `answer_json`; malformed JSON → false, no response sent).

app/src/main/java/to/eyed/thragg/core/CoreBridge.kt gets the three matching `external fun`s. Also mirror the questions array into `acpSessionState` as `"questions": [...]` (same objects, filtered to this session) so the panel's existing 120 ms `acpSessionVersion` poll picks them up without a second poll loop; the standalone version counter exists only for the session-less case, exactly as `acpElicitationsVersion` does.

### W-06 handle `_spettro/account/update` notifications

*Lands in:* **rust-engine**

core/crates/engine/src/acp.rs, `run_connection`: append a trailing `.on_receive_notification(async move |n: acp::UntypedMessage, _cx| { ext_shared.on_extension_notification(n); Ok(()) }, ...)`. Route on `n.method`: `"_spettro/account/update"` → store `n.params` in `AgentShared.account: Mutex<Option<serde_json::Value>>` and bump a version counter; anything else → drop with a log line (a notification has no response, so falling through is a no-op either way).

Surface as `Engine::acp_account_status() -> String` (the cached `AccountStatus` JSON, `"null"` when unseen) + `acp_account_version() -> u64`, with the matching JNI exports and externs. This is the ONLY way the device-flow login progresses: the agent owns the 2 s poller and pushes state; the phone must never poll the backend.

### W-07 outbound `_spettro/*` calls — one generic seam

*Lands in:* **both**

core/crates/engine/src/acp.rs, `impl crate::Engine`, beside `acp_set_config_option`:
```rust
pub fn acp_call_extension(&self, project: ProjectId, method: &str, params_json: &str) -> String
```
Builds `acp::UntypedMessage::new(method, serde_json::from_str(params_json)?)`, sends it on the live connection for that project, blocks up to 45 s, and returns a JSON envelope so errors survive JNI (which has no exception channel here):
```json
{"ok":true,"result":{...}}
{"ok":false,"code":-32603,"message":"key rejected (401)","data":{"error":"…"}}
{"ok":false,"code":-32601,"message":"method not found"}     // old CLI → show "update Spettro", not a failure
{"ok":false,"code":0,"message":"the agent is not running"}
```
45 s because `_spettro/account/status` blocks up to 15 s and `_spettro/providers/connect` up to 30 s while it verifies the key against the provider's own API.

JNI export `Java_..._acpCallExtension(JNIEnv, JClass, project: jlong, method: JString, params_json: JString) -> jstring`; extern in CoreBridge.kt. Kotlin MUST call it off the main thread (`Dispatchers.IO`) — it blocks. Every `_spettro/*` call in the app goes through this one seam; do not model any of the 19 methods in Rust.

Methods the phone actually calls: `_spettro/providers/list`, `_spettro/providers/connect` `{providerId,apiKey,activate:true}`, `_spettro/providers/disconnect`, `_spettro/providers/local/probe`, `_spettro/providers/local/add`, `_spettro/providers/local/remove` `{endpoint}`, `_spettro/models/list`, `_spettro/models/favorite`, `_spettro/account/status`, `_spettro/account/login/start|poll|cancel`, `_spettro/account/logout`, `_spettro/workflow/list` `{sessionId}`, `_spettro/workflow/runs` `{sessionId,limit}`. All params objects; `{}` where none. Workflow calls ALWAYS pass `sessionId`, never `cwd` — an unknown session id is an error, an omitted scope silently falls back to the agent's process cwd and can list the wrong repo.

### W-08 usage: keep `_meta["spettro.app/tokensUsed"]` and the turn's accounting

*Lands in:* **rust-engine**

core/crates/engine/src/acp_thread.rs:
(a) `Usage` (line ~355) gains `pub tokens_used: Option<u64>`; the `SessionUpdate::UsageUpdate` arm (~886) reads `update.meta` → `["spettro.app/tokensUsed"]` as u64. Emit it at state_json (~1493) as `"usage":{"used":…,"size":…,"cost":…,"tokensUsed":…}`. Ignore an update whose `size <= 0` (a zero window divides every gauge by zero).
(b) NEW field `turn_usage: Option<serde_json::Value>`, filled in acp.rs `run_prompt` (~1815) from `response.usage` and `response.meta["spettro.app/tokensUsed"]`, cleared at the start of each turn, emitted as:
```json
"turnUsage":{"inputTokens":40120,"outputTokens":3311,"totalTokens":98219,"cachedReadTokens":54200,"cachedWriteTokens":588,"tokensUsed":91772}
```
Semantics the UI must respect: `usage.used` is context OCCUPANCY (the largest single request so far) and CAN DECREASE after a compaction — render it as a gauge, never as a counter. `usage.tokensUsed` is the monotonic spend. `usage.size` falls back to a hard-coded 128000 agent-side when the model's window is unknown.

### W-09 preserve the OPENING rawInput of every tool call

*Lands in:* **rust-engine**

core/crates/engine/src/acp_thread.rs. `apply_tool_fields` (line 1549-1592) overwrites `call.raw_input` on every update that carries one. Spettro's workflow FINISH update replaces `rawInput` with `{run_id,workflow,agents,failed,cached,tokens}`, destroying `phases`, `description` and `origin` — the declared phase list arrives ONLY on the opening `tool_call`.

Fix: `ToolCallEntry` gains `raw_input_open: Option<String>` (serde `rawInputOpen`), set on the FIRST non-null `rawInput` seen for that call id and never again. `raw_input` keeps its current last-write-wins behaviour. Five lines, and it removes the entire need to scrape the CLI's ASCII tree back out of the rendered text.

This is the one Rust change the workflow card depends on. Note: `raw_input` is stored PRETTY-PRINTED as a String, so Kotlin does `JSONObject(entry.rawInputOpen)`.

### W-10 permission requests that are really questions

*Lands in:* **both**

RUST — core/crates/engine/src/acp.rs `AgentShared::on_permission` (line 703) currently passes only `request.tool_call` and `request.options` to `SessionThread::begin_permission`. Add a third argument `meta: Option<serde_json::Value>` (from `request.meta`), stored on `ToolCallEntry` as `permission_meta` and serialized as `"permissionMeta"`. Also extend `Engine::acp_respond_permission` (line 2840) with a fourth parameter `answer_meta_json: &str` (`""` = none) attached to the response as `_meta`:
```json
{"outcome":{"outcome":"selected","optionId":"opt-1"},
 "_meta":{"spettro.app/questionAnswer":{"kind":"option","optionId":"opt-1"}}}
```
JNI signature becomes `acpRespondPermission(session: jlong, tool_call: JString, option_id: JString, answer_meta_json: JString) -> jboolean`; update CoreBridge.kt and every caller.

KOTLIN — `PermissionOption.parse` (AgentSession.kt:119) must stop dropping `_meta`: read `spettro.app/isRecommended` (bool) and `spettro.app/isCustomInput` (bool). A tool call whose `permissionMeta["spettro.app/question"]` exists is NOT a permission prompt — render it with the question sheet, not "Allow / Deny". Reply by selecting the matching option id (or the `isCustomInput` option, whose id is `"custom"`) AND sending the tagged answer in `_meta`. To decline the whole walked form: `{"outcome":{"outcome":"cancelled"}}` + `_meta{"spettro.app/questionAnswer":{"kind":"declined"}}`.

### W-11 mid-run steering

*Lands in:* **both**

RUST — a `session/prompt` sent while a turn runs is NOT a new turn and NOT a cancel: Spettro queues it, emits `agent_message_chunk` `"→ steering queued: the running agent will see this message at its next step"`, and answers the STEERING prompt with `{"stopReason":"end_turn"}` within milliseconds while the real turn keeps running.

core/crates/engine/src/acp.rs: new `pub fn acp_steer(&self, session: u64, text: &str, mentions_json: &str, images_json: &str) -> bool`. It requires `phase == Phase::Running` AND `agent.spettroExtensions != null` (never send a second concurrent prompt to a generic agent), pushes the user entry, and starts a SECOND concurrent `session/prompt`.

THE HAZARD, and the reason this cannot be done in Kotlin: `SessionThread::end_turn` (acp_thread.rs:1101-1140) unconditionally sets `phase = Phase::Ready`, clears `turn_cancelled`, snapshots the plan and drains the queued-prompt list. The steering turn's instant `end_turn` would therefore settle the session and fire a third prompt on top of a live turn. Add `running_turns: usize` to `SessionThread`; `start_prompt` increments, `end_turn` decrements and performs its settle work ONLY when the count reaches zero; a steering turn's `end_turn` does nothing but `bump()`. Cancellation still cancels the whole session, which is correct.

KOTLIN — `AgentSessions.steer(...)` beside `prompt(...)`; the composer stays ENABLED during a run and its primary button reads **Steer** with a long-press menu of Queue / Stop & send. Render the two CLI strings as centred system pills, never as assistant prose: match by prefix `"→ steering queued"` and `"✔ steering delivered"`.

### W-12 plan entries: lift the `(blocked)` suffix

*Lands in:* **rust-engine**

core/crates/engine/src/acp_thread.rs, the `SessionUpdate::Plan` arm (~846) and `snapshot_completed_plan` (~1141). ACP has no blocked status, so Spettro appends the literal `" (blocked)"` to a pending task's `content` when its dependencies are unmet. Strip the suffix and emit a sibling flag, so both the live plan and the `completed_plan` snapshot carry it:
```json
{"content":"Run the test suite","priority":"medium","status":"pending","blocked":true}
```
Also: `plan` is a FULL replacement every time, and an empty `entries: []` is published deliberately when the last task is deleted — the existing wholesale-replace behaviour is already correct; do not add merge logic.

### W-13 config options: stop flattening groups, keep `category`

*Lands in:* **kotlin**

app/src/main/java/to/eyed/thragg/core/AgentSession.kt, `parseConfigOptions` currently splices grouped select children into one flat list and drops `category`. Both are needed.

Detect grouped exactly as Spettro-Desktop does: the option list is GROUPED when its FIRST element itself carries a nested `options` array. Group element = `{"group":"Anthropic","name":"Anthropic","options":[{"name":"Claude Sonnet 4.5","value":"anthropic:claude-sonnet-4-5"}]}`. Keep `category` (`mode`, `model`, `thought_level`; `permission` and `ultra` have none) for chip icons and the mode tint.

Write path is already correct in the engine (acp.rs:2950-2980 builds both union variants). Select: `{sessionId,configId,value:"<string>"}`. Boolean: `{sessionId,configId,type:"boolean",value:true}` — the `type` discriminator is required. The response is the FULL refreshed five-option set; apply it wholesale, never merge. A `config_option_update` notification is also a full replacement and is pushed after ANY handled slash command, so treat it as an unconditional resync.

### W-14 launch argv and the single-agent catalog

*Lands in:* **kotlin**

app/src/main/java/to/eyed/thragg/solana/agents/AgentCatalog.kt: delete `CLAUDE_CODE` and `CODEX`; `ALL = listOf(SPETTRO)`. The `AgentInstallMethod.Npm` branch and its Node-from-apt install become dead code — delete it too.

app/src/main/java/to/eyed/thragg/core/Agents.kt: the spec crossing JNI is `{"name":"Spettro","argv":[...],"env":{...}}`. argv MUST be:
`["/opt/thragg/agents/spettro/spettro", "--acp", "--cwd", "<absolute project root>"]`
Go's flag package accepts `-acp` and `--acp` identically; keep `--acp`. `cwd` must be absolute or `session/new` answers `-32602`.

ENV is load-bearing: `HOME` must point at a writable directory the guest user owns, because the CLI resolves `~/.spettro/config.json`, `~/.spettro/keys.enc`, `~/.spettro/master.key` and `~/.spettro/sessions/` from it. With `HOME` unset or read-only the handshake succeeds and then every config write, provider connect and session save silently fails. Verify the guest login environment supplies it; set it explicitly in the spec env map if not.

Also: `spettro --acp` writes to a GLOBAL config. `model`, `permission`, `thinking` and `ultra` changes persist to `~/.spettro/config.json` and are shared with any concurrently running TUI; only `mode` is session-scoped. Say so once in settings.

### W-15 session picker: list / load / resume

*Lands in:* **kotlin**

Engine already drives all three (`refresh_session_list` acp.rs:486-542 always scopes with `.cwd(root)`; `create_session` prefers `session/load` when `caps.load_session`, else `session/resume`). No Rust change.

Kotlin: `rememberAgentSessionList(enabled, refreshToken)` must pass `refresh = true` when the picker opens and again after every turn settles — Spettro saves after EVERY prompt turn, so an unrefreshed list is stale within one message.

Use `session/load` (replay) when opening a session the phone holds no transcript for — Spettro replays `user_message_chunk` / `agent_thought_chunk` / `agent_message_chunk` BEFORE the response returns, so clear the view first. Use `session/resume` (no replay) to re-attach a session already on screen. A loaded conversation legitimately has NO tool-call cards, no plan and no usage — the on-disk store keeps only the flat transcript. Show a skeleton while the replay streams.

Hide the delete affordance: Spettro does not advertise `SessionCapabilities.Delete`, so `acp_delete_session` will return false. Both load and resume RESET the mode to the manifest default (usually `plan`) — re-apply the remembered mode with `session/set_config_option` right after.

### W-16 commands: render the pushed list, with hints

*Lands in:* **kotlin**

No Rust change (`AvailableCommandsUpdate` already lands in `thread.commands`). Kotlin's `AgentCommand(name, description, inputHint)` parses `input.hint` and then never renders it — fix that: the `/` palette row shows `/name` in mono, the hint as ghost text after the caret, and the description on a second line. Raise the current 4-suggestion cap; a bare `/` opens a full command sheet.

Timing contract: `available_commands_update` is sent from a goroutine 200 ms AFTER the `session/new` / `session/load` response, and re-sent once from inside the first `session/prompt`. Expect it twice, never gate the composer on the first delivery, and cache the last list so a new session's palette is not empty.

Surface as buttons rather than typed text: `/goal <objective>` ("Run autonomously"), `/compact` (on the gauge sheet at >75%), `/clear` ("New conversation"), `/diff`, `/jobs`, `/memory add` ("Remember this"), `/stats`. Never surface `/mode`, `/models`, `/permission`, `/permissions`, `/thinking`, `/ultra`, `/plan` — those are the toolbar chips and typing them is strictly worse. `/resume` is deliberately not advertised; the picker replaces it.

### W-17 tool-call ids repeat across turns

*Lands in:* **both**

Spettro constructs a fresh `turnState` per `session/prompt`, so `call-1`, `wf-1`, `ask-1` recur in EVERY turn. The engine's `tool_call_index` (acp_thread.rs:932) searches from the END and matches on id alone, so a second turn's `call-1` will merge into the first turn's card.

Fix in Rust: stamp `ToolCallEntry` with the turn ordinal (`turn: u64`, bumped in `start_prompt`) and make `tool_call_index` match `(turn, id)`. Emit `"turn"` on the entry JSON. Kotlin then keys tool-call state by `(turn, toolCallId)` too — the orchestration fold uses the same key when attaching members to runs.

### W-18 what stays untouched

*Lands in:* **rust-engine**

Do NOT change: the terminal handlers (`terminal/*`), `fs/read_text_file` / `fs/write_text_file` and their `resolves_inside` confinement, the elicitation module, `session/cancel`, the checkpoint/edited-files machinery, `session/set_mode` (implemented, never called — Spettro advertises no modes; drive mode through `session/set_config_option` with `configId:"mode"`).

Do NOT call `session/close`, `logout`, or `authenticate` against Spettro: the first two answer `-32601` by design and `authenticate` is a no-op. Spettro's advertised auth method is `{"type":"terminal","id":"spettro-setup"}` — a "run this in a terminal" method whose description tells you to launch the TUI. On a phone we ignore it entirely and use the `_spettro/*` onboarding instead; make sure the `Trouble` path in AgentPanel.kt does not offer it.

## State model

## Kotlin state model

Three layers: (1) the raw session snapshot the engine already produces, extended; (2) a pure fold that turns the flat tool-call list into runs; (3) small derived view models the composables consume. Nothing below caches protocol state independently of `acpSessionVersion` — the engine remains the single source of truth and every poll re-derives.

### 1. Session snapshot — `core/AgentSession.kt` (extends what exists)

```kotlin
data class AgentSessionState(
  // …existing: version, project, phase, error, needsAuth, title, stopReason,
  //  entryCount, modes, queue, errorKind, canRetry, notice, acpSessionId,
  //  updatedAt, editedFiles, waitingCount, agent, elicitations…
  val configOptions: List<AgentConfigOption> = emptyList(),
  val commands: List<AgentCommand> = emptyList(),
  val plan: List<AgentPlanEntry> = emptyList(),
  val usage: AgentUsage? = null,
  val turnUsage: AgentTurnUsage? = null,
  val questions: List<SpettroQuestion> = emptyList(),
  val spettro: SpettroSurface? = null,           // agent.spettroExtensions
)

/** Present ⇒ this agent is Spettro. Everything Spettro-specific gates on it. */
data class SpettroSurface(
  val version: Int,                              // 4 on the shipping CLI
  val methods: Set<String>,                      // 19 agent-served `_spettro/*`
  val clientMethods: Set<String>,                // ["_spettro/question/ask"]
) {
  val hasWorkflowAuthoring get() = version >= 4
}

data class AgentUsage(
  val used: Long,        // context OCCUPANCY — can DECREASE after a compaction
  val size: Long,        // window; agent falls back to 128000 when unknown
  val tokensUsed: Long?, // _meta["spettro.app/tokensUsed"] — the monotonic spend
  val cost: AgentCost?,  // Spettro never sends this; keep for other agents
) {
  val fraction get() = if (size > 0) (used.toFloat() / size).coerceIn(0f, 1f) else 0f
  val isWarm get() = fraction >= 0.75f
  val isNearlyFull get() = fraction >= 0.90f
}

data class AgentTurnUsage(
  val inputTokens: Long, val outputTokens: Long, val totalTokens: Long,
  val cachedReadTokens: Long, val cachedWriteTokens: Long, val tokensUsed: Long?,
) {
  val cacheHitRate: Float? // cachedRead / (input + cachedRead + cachedWrite)
}

data class AgentPlanEntry(
  val content: String,                                     // suffix already stripped in Rust
  val priority: Priority,                                  // HIGH | MEDIUM | LOW
  val status: Status,                                      // PENDING | IN_PROGRESS | COMPLETED
  val blocked: Boolean,                                    // lifted from " (blocked)"
)

data class AgentCommand(val name: String, val description: String?, val inputHint: String?)
```

### 2. Config options — grouped, categorised, with the Ultra rule

```kotlin
data class AgentConfigOption(
  val id: String,                 // "mode" | "model" | "permission" | "thinking" | "ultra"
  val name: String,               // display label from the agent — never hard-coded
  val description: String?,
  val category: String?,          // "mode" | "model" | "thought_level" | null
  val kind: Kind,
) {
  sealed interface Kind {
    data class Select(
      val currentValue: String?,
      val groups: List<Group>,    // empty when flat
      val flat: List<Choice>,     // empty when grouped
    ) : Kind
    data class Bool(val currentValue: Boolean) : Kind
  }
  data class Group(val id: String, val name: String, val options: List<Choice>)
  data class Choice(val name: String, val value: String, val description: String? = null)

  val currentLabel: String        // resolve through groups then flat, else raw value, else "—"
}

/** The toolbar's derived view. Built fresh from configOptions on every poll. */
data class SpettroToolbar(val options: List<AgentConfigOption>) {
  val mode       get() = options.firstOrNull { it.id == "mode" }
  val model      get() = options.firstOrNull { it.id == "model" }
  val permission get() = options.firstOrNull { it.id == "permission" }
  val thinking   get() = options.firstOrNull { it.id == "thinking" }
  val ultra      get() = options.firstOrNull { it.id == "ultra" }

  val permissionValue: String? get() = (permission?.kind as? Select)?.currentValue
  val ultraOn: Boolean         get() = (ultra?.kind as? Bool)?.currentValue == true
  val askFirst: Boolean        get() = permissionValue == "ask-first"

  /** THE THREE-STATE RULE. The agent publishes currentValue = cfg.Ultra, not
   *  UltraActive() = Ultra && Permission != ask-first. Under ask-first with
   *  ultra stored true the toggle reads ON while the swarm is suspended. */
  val ultraState: UltraState get() = when {
    ultraOn && askFirst -> UltraState.SUSPENDED
    ultraOn             -> UltraState.ON
    askFirst            -> UltraState.LOCKED     // turning it ON would be refused
    else                -> UltraState.OFF
  }
  /** Turning Ultra OFF is never locked. */
  val canToggleUltra: Boolean get() = ultraState != UltraState.LOCKED
}
enum class UltraState { OFF, ON, SUSPENDED, LOCKED }

const val ULTRA_LOCK_REASON =
  "Ultra requires the Restricted or YOLO permission level — change Permission first"
```

### 3. Transcript entries — `core/AgentSession.kt` (extends `AgentEntry`)

Existing sealed interface stays: `User`, `Assistant`, `ToolCall`, `CompletedPlan`, `Unsupported`. `Unsupported` is the forward-compat escape hatch — never return null from `AgentEntry.parse`, never leave a hole (a hole forces `AgentConversation.apply` to re-read from revision 0 forever).

```kotlin
data class ToolCall(
  val id: String,                 // "call-7" | "wf-3" | "ask-1" | "perm-2" | "compact-1"
  val turn: Long,                 // W-17: ids repeat every turn; key by (turn, id)
  val title: String,
  val toolKind: String,           // read|edit|delete|move|search|execute|think|fetch|other
  val status: ToolStatus,
  val options: List<PermissionOption>,
  val content: List<ToolContent>, // markdown | diff | terminal
  val locations: List<Location>,
  val rawInput: String?,          // LATEST args, pretty-printed JSON
  val rawInputOpen: String?,      // W-09: the OPENING args, never overwritten
  val permissionMeta: String?,    // W-10: raw `_meta` of a request_permission
  override val reverted: Boolean,
) : AgentEntry {
  val args: JSONObject?     by lazy { rawInput?.let { runCatching { JSONObject(it) }.getOrNull() } }
  val openArgs: JSONObject? by lazy { rawInputOpen?.let { runCatching { JSONObject(it) }.getOrNull() } }
  val toolName: String      // first token of title, minus a leading "[agent#n] "
  val agentPrefix: String?  // the "[review#2]" bracket, when present
}

data class PermissionOption(
  val optionId: String, val name: String, val kind: String?,
  val isRecommended: Boolean = false,   // _meta["spettro.app/isRecommended"]
  val isCustomInput: Boolean = false,   // _meta["spettro.app/isCustomInput"]
)
```

**Rule: parse `rawInput`, never the title.** Spettro truncates the title's inline JSON at 120 chars, so it is routinely invalid JSON. The title is useful only for the `[instance]` prefix and as a last-resort fallback.

### 4. The orchestration fold — NEW `core/SpettroOrchestration.kt`

One **pure** function over the entry list, memoised on the list identity. It is safe to do this in Kotlin (rather than Rust) for one decisive reason: `session/load` replays only flat user/assistant/thinking messages — a restored conversation has NO tool calls at all — so there is no "the live fold and the replayed fold must agree" invariant to protect. The one thing the engine must protect is `rawInputOpen` (W-09).

```kotlin
fun foldOrchestration(entries: List<AgentEntry>): List<TranscriptRow>

sealed interface TranscriptRow {
  data class Item(val id: String, val entry: AgentEntry) : TranscriptRow
  data class Agent(val id: String, val member: Member) : TranscriptRow      // "agent-<toolId>"
  data class Run(val id: String, val run: OrchRun) : TranscriptRow          // "run-<toolId>"
  data class Script(val id: String, val script: WorkflowScript) : TranscriptRow
}

enum class OrchStatus { RUNNING, DONE, FAILED }
data class OrchCounts(val total: Int, val running: Int, val done: Int,
                      val failed: Int, val cached: Int)

data class Member(
  val tool: AgentEntry.ToolCall,
  val instance: String,       // "review#2"
  val specId: String,         // "review" — the tint key, before the '#'
  val index: Int?,
  val task: String,
  val phase: String,          // "" = outside any phase
  val cached: Boolean,        // → the "replayed" pill
  val status: OrchStatus,
  val children: List<AgentEntry.ToolCall>,  // its own nested calls
  val resultText: String,     // summary when present, else raw/pretty output
  val resultIsJson: Boolean,
) {
  /** While RUNNING show the member's LATEST child tool call, with its own
   *  "[review#2] " prefix stripped; once finished fall back to `task`.
   *  In a fan-out the launch items are near-identical and say nothing. */
  val liveDetail: String
}

data class WorkflowPhase(val title: String, val detail: String,
                         val members: List<Member>, val counts: OrchCounts,
                         val declared: Boolean)

sealed interface OrchRun {
  val tool: AgentEntry.ToolCall
  val status: OrchStatus
  val counts: OrchCounts

  data class Workflow(
    override val tool: AgentEntry.ToolCall,
    val runId: String, val name: String, val description: String, val origin: String,
    val phases: List<WorkflowPhase>,
    val logs: List<String>,     // ONLY recoverable from the rendered text
    val summary: String,        // "12 agents · 1 failed · 0 replayed"
    val rendered: String,       // the raw tree, for the "raw tree" sheet
    val script: WorkflowScript?,
    override val status: OrchStatus, override val counts: OrchCounts,
  ) : OrchRun

  data class Swarm(
    override val tool: AgentEntry.ToolCall,
    val description: String, val subagentType: String, val isolation: String,
    val items: List<String>, val members: List<Member>,
    val pending: List<String>,  // items.drop(members.size) → ghost cells
    override val status: OrchStatus, override val counts: OrchCounts,
  ) : OrchRun
}

data class WorkflowScript(val tool: AgentEntry.ToolCall, val source: String,
                          val savedAs: String, val runId: String,
                          val returned: String, val status: OrchStatus, val error: String)
```

**Classification, by `openArgs` — never by title:**

| what | test |
|---|---|
| workflow run (lifecycle) | `args.workflow` non-empty AND `args.agent` empty AND `args.kind` ∉ {phase, log} |
| workflow SCRIPT call | `toolName == "workflow"` AND no `run_id` AND no `workflow` AND one of `script`/`save_as`/`script_path`/`name` |
| ultra swarm | `toolName == "ultra"` or starts with `"ultra "` |
| member | `toolName == "agent"`; workflow member has `run_id`+`phase`+`index`+`cached`, swarm member has `swarm:true` and NO `run_id` |
| nested child of a member | the title's `[instance]` bracket names an instance ≠ this call's own `args.agent` |

**Attachment:** workflow members by `run_id`; swarm members to the nearest still-running `ultra` call (else the last one). A script call is claimed by the run whose `run_id` its `<workflow_result run_id="…">` output declares, else by the nearest unclaimed script call above the run. An unclaimed script call survives as its own `Script` row — it is the only evidence a workflow was attempted and failed before any run existed.

**Counts:** for a swarm the denominator is `items.size`, NOT the members launched so far. Ultra ramps (5 immediately, then +1 every 700 ms), so a growing denominator makes the meter run backwards. `total = members.size + pending.size`.

**Status:** `failed`→FAILED, `completed`→DONE, everything else→RUNNING. A member whose parsed `{"status":"error"}` output says so is FAILED regardless.

**Phase ordering:** declared phases (in `openArgs.phases[]` order) first, then phases entered but never declared in first-seen order, then the unnamed `""` bucket last and only if non-empty. A declared phase with no members yet renders as `pending` — the whole plan is visible from t=0, which is the point.

**Text mining, for exactly two things:** the `log:` block (there is no structured channel for `log()` lines at all) and a fallback phase tree when `rawInputOpen` was somehow missed. Parse `content[0].markdown` as blank-line-delimited blocks and take the LAST block in which every line is a phase header (`▸ ` / `○ `) or an indented member row (`^\s+[✓▶✗] `) — a prose description can otherwise fake a phase header. Wrap the whole parse in `runCatching`: a missing description is cosmetic, a thrown exception is a blank chat.

### 5. Questions — NEW in `core/AgentSession.kt`

```kotlin
data class SpettroQuestion(
  val id: String,            // engine-assigned, "question-1"; what we answer by
  val session: Long?,
  val version: Int,          // 1 = flat only, 2 = has questions[]
  val sessionId: String?,
  val context: String?,
  val questions: List<Q>,    // from questions[]; v1 → one Q from the flat fields
  val transport: Transport,  // ASK (extension) | PERMISSION (walked) | ELICITATION
) {
  data class Q(
    val id: String,          // "q-0"
    val header: String,      // model's words — a label, never an identifier
    val question: String,
    val options: List<Opt>,
    val multiSelect: Boolean,
    val allowCustomInput: Boolean,   // (flag ?: false) || options.isEmpty()
  )
  data class Opt(val id: String, val label: String, val description: String?,
                 val preview: String?, val isRecommended: Boolean)
}

/** One draft per question. NOTHING is preselected — the recommended option is
 *  badged, never chosen for the user. */
data class QuestionDraft(val selected: List<String> = emptyList(),
                         val custom: String = "", val note: String = "")

sealed interface QuestionAnswer {
  data class Option(val questionId: String, val optionIds: List<String>,
                    val notes: String?) : QuestionAnswer
  data class Custom(val questionId: String, val text: String,
                    val notes: String?) : QuestionAnswer
}
```

Encoding (`AgentSessions.answerQuestion`), transport ASK, `version >= 2`:
```json
{"answers":[{"questionId":"q-0","kind":"option","optionIds":["opt-1"],"optionId":"opt-1"},
            {"questionId":"q-1","kind":"option","optionIds":["opt-0","opt-1"],"notes":"vet is slow"},
            {"questionId":"q-2","kind":"custom","text":"use the existing MySQL box"}]}
```
Send `optionIds` AND, for a single pick, `optionId` too. Selections come out in OPTION order, never tick order. A question with no selection and no custom text is OMITTED — the model is then told nobody answered it, which is the correct way to skip one and is never the same as defaulting to the recommendation. Version 1 → the bare tagged shape of the first answer. Declining is all-or-nothing: `{"kind":"declined"}` at the TOP level.

### 6. Account / providers / models — NEW `core/SpettroSetup.kt`

Not part of the session snapshot; a separate `StateFlow`-backed store fed by `acpCallExtension` on `Dispatchers.IO`, plus the pushed `_spettro/account/update` cache.

```kotlin
data class ProviderEntry(val id: String, val name: String, val envKey: String?,
                         val connected: Boolean, val suggested: Boolean, val modelCount: Int)
data class LocalEndpoint(val endpoint: String, val name: String,
                         val hasKey: Boolean, val modelCount: Int)
data class ProvidersList(val providers: List<ProviderEntry>, val local: List<LocalEndpoint>,
                         val subscription: ProviderEntry?)
data class ModelEntry(val provider: String, val providerName: String, val name: String,
                      val displayName: String, val vision: Boolean, val reasoning: Boolean,
                      val toolCall: Boolean, val context: Long, val local: Boolean,
                      val favorite: Boolean, val active: Boolean)
data class AccountStatus(val signedIn: Boolean, val email: String?, val plan: String?,
                         val planStatus: String?, val creditsUsed: Double?, val creditLimit: Double?,
                         val remainingCredits: Double?, val modelCount: Int, val pricingUrl: String?,
                         val login: LoginStatus?, val stale: Boolean)
data class LoginStatus(val loginId: String?, val status: String, // idle|starting|pending|complete|expired|cancelled|error
                       val browserUrl: String?, val error: String?)

enum class SetupGate { UNKNOWN, NEEDED, SATISFIED }
```

**The gate FAILS OPEN.** `SetupGate.NEEDED` only when `_spettro/providers/list` returned `ok:true` AND `providers.none { it.connected }` AND `local.isEmpty()` AND `subscription?.connected != true`. A failed call, a `-32601`, a timeout — all `UNKNOWN`, which never blocks the app. Decoding defaults are the same discipline: missing bool → false, missing `displayName` → `name`, missing `providerName` → `provider`, a local endpoint's missing `name` → its `endpoint`, missing login `status` → `"idle"`.

**API keys are write-only.** A key is posted to `_spettro/providers/connect` and nothing on this protocol ever returns one. Never log it, never put it in a `SharedPreferences`, never keep it in a `remember`d field after the call returns. Its only home is the CLI's encrypted `~/.spettro/keys.enc`.

### 7. Activation matcher — NEW `ui/agent/spettro/WorkflowActivation.kt`

A verbatim Kotlin port of the CLI's `workflowActivationRes`. It must stay byte-identical in behaviour, because the highlight the user sees is only honest if it is the same match that decides whether the workflow tool is injected.

```kotlin
const val WORKFLOW_KEYWORD = "ultracode"
fun workflowActivationSpans(text: String): List<IntRange>   // merged, non-overlapping, sorted
fun workflowRequested(text: String): Boolean
```
Patterns (all case-insensitive): `\bultracode\b`; `\b(?:use|using|run|write|author|make|create|build|set ?up|start|launch|kick off|do this as|do it as)\s+(?:a|an|another)\s+(?:new\s+)?(?:multi[- ]?agent\s+|orchestration\s+)?workflow\b`; `\b(?:use|using|run|with|via)\s+workflows\b`; `\bworkflow tool\b`; `\bfan\s+(?:this|that|it|them|the \w+)?\s*out\s+(?:across|over|to|into)\s+(?:\w+\s+){0,2}(?:sub-?)?agents?\b`; `\borchestrate\s+(?:\w+\s+){0,3}(?:with|using|across|over)\s+(?:\w+\s+){0,2}(?:sub-?)?agents?\b`; `\bmulti[- ]?agent\s+(?:orchestration|workflow|pipeline|run)\b`. "our deploy workflow" and ".github/workflows" must stay quiet (indefinite article only).

### 8. Rules that bind the whole model

- **Answer text does not stream.** Only `agent_thought_chunk` is live; the final answer arrives as ONE `agent_message_chunk` at turn end. Do not build a typewriter — show thinking live and let the answer land whole (fade in, keep the ticker running).
- **`plan` and workflow PHASES are different channels.** Phases live only inside the run card; the plan strip is the session task graph. Never merge them.
- **A steering turn's `end_turn` is not the end of the run.** Key busy state on the ORIGINAL prompt.
- **`config_option_update` is a full replacement** and is pushed after any handled slash command — treat it as an unconditional resync, and never assume a value you set locally survived.
- **Memory takes effect next session.** The `~/.spettro/memory.md` snapshot is frozen per session for prompt-cache stability. Any "Remember this" UI must say so.

## UI surfaces

### Screen shell — app bar, transcript, plan strip, live-run peek, chips, composer (400×890 dp)

```
┌──────────────────────────────────────────────┐
│ ←  thragg-ide                  ◕ 41%     ⋮   │ 48
├──────────────────────────────────────────────┤
│                                              │
│                                              │
│            ( transcript, scrolls )           │
│                                              │
│                                              │
│  ◐ 1m 07s · 3.4k tok                         │ 24  run ticker
├──────────────────────────────────────────────┤
│ ◌ Wire the ACP question sheet      3/7    ▲  │ 32  plan strip
├──────────────────────────────────────────────┤
│ ● Live · review-changes  4/7 [████░░░░]   ▲  │ 56  run peek
├──────────────────────────────────────────────┤
│ ⚡Ultra │ ⌁Coding │ ⬢Sonnet 4.5 │ ✻Off │ ⛨A… │ 36  chips ⇄
├──────────────────────────────────────────────┤
│ ┌──────────────────────────────────────────┐ │
│ │ Message Spettro — working in thragg-ide  │ │ 56–120
│ └──────────────────────────────────────────┘ │
│  ＋   /                            ▶ Send    │
└──────────────────────────────────────────────┘
```

Vertical budget: 24 status + 48 app bar + flex transcript + 24 ticker + 32 plan + 56 peek + 36 chips + 56–120 composer + 24 gesture bar. With the IME open (~340 dp gone) the plan strip and run peek collapse to zero so at least two transcript rows stay visible; chips + composer must total ≤ 200 dp.

App bar keeps ONLY the title and the context ring — the ring is the one number that changes what you should do next. Plan/git/stats go in the ⋮ overflow. Plan strip appears only when `plan.isNotEmpty()`; it shows the first `in_progress` entry, else the first pending, with `done/total`. Run peek appears only while ≥1 run is live, and holds a settled run for 1600 ms then shows a one-line settled summary for a further 7000 ms before releasing — a section that vanishes mid-glance is worse than one that lingers.

The run ticker at the transcript tail is a braille-style spinner (⣾⣽⣻⢿⡿⣟⣯⣷, 8 discrete steps, one revolution per 400 ms) tinted with the active mode colour, plus elapsed and the tokens spent since the run started. Do NOT substitute a Material CircularProgressIndicator — this is brand.

Motion policy, enforced everywhere: animate opacity and transform only. Nothing transitions a width, height or margin, because these surfaces grow while they are being read and repaint several times a second during a fan-out. The single exception is the progress meter's fill width, which moves inside a fixed box. All of it respects `prefers-reduced-motion` / `Settings.Global.ANIMATOR_DURATION_SCALE`.

### Conversation rows — user, thinking, answer, steering pills

```
│                     ┌──────────────────────┐ │
│                     │ ultracode: review    │ │  user bubble,
│                     │ the diff and refute  │ │  'ultracode' glows
│                     │ each finding         │ │
│                     └──────────────────────┘ │
│                                              │
│ ⌄ 🧠 Thinking…                               │  collapsed by default
│                                              │
│ I read the diff across four files. Three     │  full-bleed prose,
│ findings survived refutation:                │  no bubble, no avatar
│  1. `steerRunningTurn` drops the queue …     │
│                                              │
│      ┈┈┈ → steering queued ┈┈┈               │  centred pill
│      ┈┈┈ ✔ steering delivered ┈┈┈            │  centred pill
│                                              │
│ ⚠ The agent declined to continue.            │  notice row
```

User is the ONLY row with bubble framing: right-aligned, max 80% width, accent fill, radius 16 dp, image thumbnails above the text. Its text renders through the activation transformation so the phrase that armed orchestration stays lit after sending.

Assistant is plain full-width markdown with 16 dp side padding — it owns the column. Because the answer arrives whole rather than token-by-token, fade it in over ~120 ms and keep the ticker spinning until it lands, so a two-minute silent turn reads as work rather than as a hang.

Reasoning is a collapsible 36 dp header (`Thinking…` while streaming, `Reasoning` once done) over monospace-ish text, collapsed by default.

The two steering strings are matched by PREFIX (`→ steering queued`, `✔ steering delivered`) and rendered as centred system pills, not as assistant prose — on a 400 dp column they otherwise read as the agent talking to itself. Same for goal/loop banners (`↻ goal iteration 3 …`, `✅ goal complete`, `⏸ loop waiting 5m …`) and compaction notices.

Stop-reason notices: `refusal` → "The agent declined to continue." (error tint); `max_tokens`/`max_turn_requests` → "The turn hit a limit before finishing."; `cancelled` → "Turn cancelled."; `end_turn` → silent.

### Composer + activation glow + slash palette

```
┌──────────────────────────────────────────────┐
│ [img][img] ✕                                 │  attachments
│ ┌──────────────────────────────────────────┐ │
│ │ ultracode: sweep every crate for unwrap  │ │  ^^^^^^^^^ glows
│ │ and replace it                           │ │
│ └──────────────────────────────────────────┘ │
│  ＋   /                            ▶ Steer   │  while busy
└──────────────────────────────────────────────┘

  bare '/' opens:
╭──────────────────────────────────────────────╮
│ /goal    <objective> | status                │
│          work autonomously toward an object… │
│ /compact [auto <status|on|off>]              │
│          summarize older history             │
│ /workflows [list|show <n>|run <n> [json]]    │
╰──────────────────────────────────────────────╯
```

Input 2–6 lines (the keyboard eats ~340 dp of 890). Images downsampled to a longest edge of 1568 px, JPEG q=0.85, sent as `{"type":"image","data":"<base64>","mimeType":"…"}` blocks. `@`-mentions become `resource_link` blocks, which Spettro turns into required reads.

THE COMPOSER STAYS ENABLED WHILE BUSY — this is the one place the phone deliberately diverges from Spettro Desktop, and without it mid-run steering is unreachable. The send button reads **Send** when idle and **Steer** while a turn runs; long-press offers *Queue* (hold until the turn settles — the existing `acp_prompt` behaviour) and *Stop & send* (`session/cancel`, wait for `cancelled`, then prompt). Steering is offered only when `state.spettro != null`; against a generic agent the button stays **Queue**.

Activation glow: a `VisualTransformation` applying `SpanStyle(brush = Brush.linearGradient(ramp))` over the matched spans, with the gradient offset animated by an `infiniteRepeatable`. Ramp `#7c3aed → #a855f7 → #e879f9 → #ecfeff → #38bdf8 → #22d3ee` (dark) / `#6d28d9 → #9333ea → #c026d3 → #db2777 → #0284c7 → #0891b2` (light). The specular stop travels THROUGH the letters — never a pill or highlighter box behind them; two earlier Desktop versions did that and read as "found/selected". Compose can style text in place, so no mirror hack is needed. Keep the glow on sent user bubbles with a bubble-surface ramp.

Slash palette: 48 dp rows, `/name` in mono + the input hint as ghost text + description. Tab/tap completes to `"/name "`; a command with no `inputHint` runs on Enter.

### Toolbar chips — Mode / Model / Permission / Thinking / Ultra

```
chips row, horizontally scrollable, 32 dp:

│ ⚡Ultra │ ⌁ Coding │ ⬢ …sonnet-4-5 │ ✻ Off │ ⛨ Ask first │

Ultra, four states:
   OFF      ┆ ⚡ Ultra ┆   amber text, amber 40% border, 8% fill
   ON       ┃ ⚡ Ultra ┃   SOLID amber fill, #1a1205 label, weight 600
   SUSPENDED┆ ⚡ Ultra ⏸┆  amber outline + pause glyph
   LOCKED   ┆ ⚡ Ultra 🔒┆ neutral, opacity .55, still tappable

tap on LOCKED → snackbar:
┌──────────────────────────────────────────────┐
│ Ultra requires the Restricted or YOLO        │
│ permission level — change Permission first.  │
│                              [ Change… ]     │
└──────────────────────────────────────────────┘
```

Data-driven: render exactly what `configOptions` contains, in the agent's order, special-casing only by `id`. Never hard-code plan/coding/ask — the mode list comes from the project's own `spettro.agents.toml` and varies per repo. Priority order on the phone is **Ultra, Mode, Model, Thinking, Permission** — Ultra first because it is the charged one and must be visible without scrolling. Chip icons from `category`: mode → sliders, model → cpu, thought_level → brain, permission → lock-shield.

Ultra is an inline toggle (tap = toggle, no sheet) and keeps the exact four-state look above. The SUSPENDED state is derived client-side by combining the `ultra` and `permission` values — the agent publishes `currentValue: cfg.Ultra`, not `UltraActive()`, so the wire alone would lie. Pre-warn before sending a set that will be rejected: on LOCKED, do not send at all — show the snackbar with the CLI's own sentence verbatim and a *Change…* action that opens the Permission sheet. Turning Ultra OFF is never locked. Show the chip always, even when refused: a control that disappears when you are not allowed to use it is one nobody learns exists.

The model chip truncates from the END (`…sonnet-4-5`) so the variant survives. The mode chip is tinted with the mode colour (plan `#bd93f9`, coding `#34d399`, ask `#60a5fa`).

A rejected `session/set_config_option` (JSON-RPC error) is shown as an inline notice using `data.error` VERBATIM — the CLI's messages are written to be read (`unknown mode: x`, `invalid model: x`). Distinguish refusal from transport failure: a refusal rolls the chip back and drops the change; a transport failure keeps it queued for the next attach. Retrying a refusal forever produces an endless notice; dropping a transport failure loses the user's setting.

Chips are disabled (not hidden) while `phase == starting`.

### Selector bottom sheet (Model shown; Mode/Permission/Thinking identical)

```
╭──────────────────────────────────────────────╮
│                   ▬▬▬                        │
│  Model                                       │
│  Active model for this session               │
├──────────────────────────────────────────────┤
│  ANTHROPIC                                   │  sticky group
│  (•) Claude Sonnet 4.5                       │  56 dp rows
│  ( ) Claude Opus 4.1                         │
│  SPETTRO                                     │
│  ( ) Spettro Fast                            │
│  LM STUDIO (local)                           │
│  ( ) qwen3-coder-30b                         │
╰──────────────────────────────────────────────╯

Permission sheet rows carry their descriptions:
│  ( ) Ask first                               │
│      Prompt before running tools, edits, or  │
│      commands                                │
│  (•) Restricted                              │
│      Allow safe actions; prompt for sensitive│
│  ( ) YOLO                                    │
│      Automatically approve all tool, path,   │
│      and command requests                    │
```

A modal bottom sheet, not a popover — it must be thumb-reachable. Title = option `name`, subtitle = option `description`, then 56 dp radio rows showing `choice.name` and `choice.description`. Grouped selects (model only) get sticky group headers; a synthetic `Active` group is prepended by the agent when the current model's provider is disconnected, so `currentValue` always resolves.

Tap selects, dismisses, and calls `session/set_config_option`; the response's full option set replaces state wholesale (one change can move another — e.g. connecting a provider repopulates the model list).

The Permission sheet adds one footer line when raising the level would unlock something: "Restricted or YOLO also lets Ultra and workflows run." And when LOWERING to Ask first while Ultra is stored on: "Ultra stays on but is suspended until you raise this again."

Thinking is always present, even for non-reasoning models (deliberate agent behaviour — the toolbar must not flicker). Leave it at the CLI's default; do not silently raise it, thinking tokens are billed and the phone user has no cost dashboard.

### Workflow run card

```
┌ ▣ review-changes           ● RUNNING     ⌄ ┐
│ Review the diff, then refute each finding  │
│ [██████░░░░░░]  4/7               1m 07s   │
├────────────────────────────────────────────┤
│ ●─ Review        one agent per dimension   │
│ │   [████░░]              2/3 done, 1 fail │
│ │  ✓ review#1  bash go vet ./...     (2) › │
│ │  ▶ review#2  read internal/acp/bri…    › │
│ │  ✗ review#3  run the test suite        › │
│ │      exit 1: 2 tests failed in acp/…     │
│ ○─ Verify        refute each finding       │
│ │                                  PENDING │
│ │  ⌄ … 3 more                              │
│                                            │
│ ⌄ log (7)   “journal replayed 0 entries”   │
│ ⌄ raw tree                          ⌄ 4 done│
└────────────────────────────────────────────┘

collapsed:
┌ ▣ review-changes  12 agents · 1 failed  ⌃ ┐
```

Header is two lines on a phone: line 1 = mark + name + badge + chevron, line 2 = meter + ratio + elapsed. The counts string (`2 running · 4 done · 1 failed · 0 replayed`) lives only in the expanded body — it does not fit beside a meter at 400 dp. `cached` is ALWAYS spelled **replayed** in the UI: it means restored from the resume journal, not prompt-cached.

The phase spine is 14 dp of rail + a dot: pending = dashed rail, dashed dot, header at 62% opacity, the word `PENDING` where the meter would be; running = solid accent dot with a 3 dp halo pulsing 1→.55 over 2 s; failed = red dot; done = muted dot. Running outranks failed outranks done. A declared phase with no members yet is never hidden — knowing what is still coming is half the value of a declared plan.

Member rows are 44 dp minimum, one line, no wrapping: glyph (✓ ▶ ✗) + instance tinted by `specId` + live detail + child-count badge + chevron. While running the detail is the member's LATEST child tool call with its own bracket stripped; once finished it falls back to the task. Instance names truncate while KEEPING the `#N` suffix (`general-purp…#7`) — the suffix is the only part that distinguishes one member from another. A replayed member gets a small accent `REPLAYED` pill.

Row cap while running: 6 per phase, keeping running rows first, then failed, then done, survivors in dispatch order (never reorder, the list would churn). Overflow row `… 5 more` / `show fewer`.

SETTLED FORM — the load-bearing rule: a finished run drops successful DETAIL, never STRUCTURE. The phase spine, every meter and every `3/3 done` stay; every failed member keeps its row and gains a 2-line-clamped failure reason in red-mixed text; successes fold behind one `4 done` disclosure. The failure reason is `result.summary`, else the first of `error`/`message`/`summary`/`reason` in a JSON output, else the raw text — a provider's plain `429 after 3 attempts` is the case that matters most and never arrives as a report.

Progress meter: 4 dp tall, segmented, done green then failed red then track. Any non-zero failure count is forced to ≥6% width (and done clamped so the sum ≤100) — one failure among fifty rounds to zero pixels otherwise, and a struggling run looks perfect.

`log (N)` is always a disclosure on the phone (never the ≤3 inline form) with a one-line mono peek of the last line. `raw tree` opens a full-screen mono sheet with horizontal scroll — an 80-column text tree cannot wrap. The script call, when claimed, folds in as a `⌄ script` disclosure at the bottom; its `<returned>` value is pretty-printed and shown FIRST, because it is what the script was written to produce.

### Ultra swarm card

```
┌ ⚡ Ultra swarm · code      ⑂ worktree    ⌄ ┐
│ Add doc comments to every exported symbol  │
│ [███████░░░░░░░]  7/20   5 running  8 queue│
├────────────────────────────────────────────┤
│ ▶ code#1   edit internal/acp/bridge.go   › │
│ ▶ code#2   bash go build ./...           › │
│ ▶ code#5   read internal/agent/ultra.go  › │
│ ✗ code#4   internal/config/config.go     › │
│     429 after 3 attempts                   │
│ ✓ code#3   internal/agent/ultra.go       › │
│ … 8 more running                           │
│ ○ queued   internal/tui/view_swarm.go      │
│ ○ queued   internal/tui/glow.go            │
│ ○ +9 more queued                           │
│ ⌄ 12 done                                  │
└────────────────────────────────────────────┘
```

One amber identity, `#f59e0b`: border at 22% mix, background at 6%, the bolt glyph, the words `Ultra swarm`. A failed run flips the border to the red mix and adds no second badge. Every member already carries its own spec tint, so the container stays near-neutral or the card is confetti. Amber is doing double duty here (Ultra identity, and 75–90% context severity elsewhere) — that is intentional; keep both.

Desktop lays members out as a responsive grid because a swarm is flat and wide; at 400 dp it collapses to one column, so the ordering and filtering rules become the whole design. Order: running → failed → done, stable within each group so launch order survives. What is still moving is the only part you can act on; once nothing moves, failures lead because they are what the reader came back for.

GHOST CELLS are the thing nobody else draws: Ultra ramps launches (5 immediately, then +1 every 700 ms), so a 20-item swarm spends its first ~15 s mostly un-launched. `items.drop(members.size)` renders as hollow-dot `queued` rows at 50% opacity with the same geometry, so nothing below moves when a real member replaces one. Cap ghosts at 3 rows plus a `+9 more queued` summary — a phone cannot spend 400 dp on work that has not started.

Live member cap 8, then `… 8 more running`. The denominator is `items.size`, never the members launched so far.

The `⑂ worktree` pill taps to a one-shot sheet with the verbatim explanation: "Each member works in its own git worktree on its own branch, under .spettro/worktrees/. Every branch is merged back into the main checkout and deleted when the swarm finishes; a branch that conflicts is kept for you to resolve." A settled member whose merge outcome is `conflict` or `preserved` gets a trailing pill with that word — those are unrecovered work and are otherwise invisible.

### Tool-call cards

```
│ ⌗ Terminal  cargo test --workspace      ◐  ›│
│ ✎ Edit      …/acp/bridge.go   +24 −6    ●  ›│
│ ⌕ Search    "steering" in internal/acp   ●  ›│
│ 🧠 Agent    review#2: check the migration ✗ ›│
│ ▣ Workflow  review-changes                 ›│  (script row, quiet)

expanded Edit:
│ ✎ Edit  internal/acp/bridge.go  +24 −6  ● ⌃│
│ ┌────────────────────────────────────────┐ │
│ │ - if !ok { return err }                │ │
│ │ + if !ok {                             │ │
│ │ +     return fmt.Errorf("steer: %w",…) │ │
│ └────────────────────────────────────────┘ │
│                        [ View full diff ]  │
```

44 dp rows, one line: kind icon + verb + detail + diff stat + status + chevron. Verbs by ACP kind: execute→Terminal, read→Read, edit→Edit, delete→Delete, move→Move, search→Search (or List when the tool name is `ls`), fetch→Fetch, think→Agent if the name starts with `agent` else Plan, else the capitalised tool name.

Detail is derived from `rawInput`, never shown as raw JSON: bash/shell/exec → `command`; agent → `who: task`; else the first of `path`/`file`/`file_path`/`filename` shortened to its last three components, prefixed `"<pattern> in <path>"` when a pattern is also present; else the first of content/task/description/pattern/query/regex/url/name; else compact `key: value` pairs. Newlines collapse to `⏎`. Middle-ellipsise paths so the filename survives.

Status: pending/in_progress → the braille spinner; completed → a small dot (NOT a green check — the check is reserved for orchestration members); failed → a red ✗.

Diffs inline show at most 40 changed lines (removed then added) with `… 42 more lines` and a `View full diff` action routing to the existing diff view. Full diffs and long terminal output open a mono bottom sheet with horizontal scroll — never wrap code.

Tool-call IMAGES: Spettro's `view-image`/screenshot tools attach base64 image content blocks. The engine currently renders `ContentBlock::Image` as the literal `*[image]*`; render them inline in the expanded card instead (this is a small acp_thread.rs `block_markdown` change, R3).

A workflow SCRIPT call that started no run renders as a deliberately QUIET row — icon, `Workflow · <savedAs>`, status, the first line of the failure — with the source and `<returned>` value behind a mono sheet. It produced nothing; full card framing would make a non-event look like the run it failed to become.

### Question form — full-height bottom sheet

```
╭──────────────────────────────────────────────╮
│                   ▬▬▬                        │
│  ⍰  Spettro has 2 questions                  │
│  both are already provisioned                │
│  ●  ○                              Database  │  step dots
├──────────────────────────────────────────────┤
│  Which database?                             │
│                                              │
│  ( ) Postgres                                │
│      already provisioned                     │
│  ( ) SQLite            ★ Recommended     ◱   │
│      file-backed, zero setup                 │
│                                              │
│  Or answer in your own words                 │
│  ┌────────────────────────────────────────┐  │
│  │ Type your own answer                   │  │
│  └────────────────────────────────────────┘  │
│  ✎ Add a note                                │
├──────────────────────────────────────────────┤
│  Decline                          Next  →    │
╰──────────────────────────────────────────────╯

review page (last step):
│  Review your answers                         │
│  Database   SQLite                         › │
│  Checks     Not answered                   › │
│  ⚠ Questions you left alone are sent as      │
│    unanswered — the agent is told nobody     │
│    answered them, not that you had no        │
│    preference.                               │
│  Decline                     Send answers    │
```

Full-height modal sheet, one question per page, swipeable, IME-aware (resize, do not scroll under the keyboard). The step indicator replaces Desktop's pill tab strip: filled dot = answered, tappable, with the current question's `header` shown on the right.

NOTHING is preselected. The recommended option is badged `★ Recommended` and nothing more — sending back the agent's own `default_option` as if a human had chosen it is exactly what the protocol forbids.

Options are 56 dp rows with 24 dp radio (single) or checkbox (multi), `description` as a muted subline. An option with a `preview` gets a trailing `◱` opening a nested mono sheet titled with the option label — previews exist ONLY on the `_spettro/question/ask` transport and are the single best reason to implement it.

On a single-select question, typing custom text CLEARS the selection (words replace the pick). On a multi-select, custom text and the note are joined with `\n` into `notes` beside the option answer. Selections are emitted in OPTION order so the same boxes always produce the same answer. A one-question single-select form submits on pick, unless the note field is open.

Primary button is right-thumb: `Next` → `Review` → `Send answers`; `Decline` sits left and is all-or-nothing (`{"kind":"declined"}` at the top level). The review page's warning sentence is verbatim. A `doneRef` keyed on the request id prevents double delivery.

Queue: render the head of `state.questions`; show `#2 of 3 waiting` in the header when deeper. A form arriving mid-run BLOCKS the turn — fire a high-priority notification through `AgentNotifier`.

A `session/request_permission` carrying `permissionMeta["spettro.app/question"]` opens THIS sheet, not the permission sheet — it is a product decision an older transport is walking, and rendering it as Allow/Deny mislabels it as a security prompt.

### Permission prompt — bottom sheet

```
╭──────────────────────────────────────────────╮
│           ▬▬▬              #1 of 2 waiting   │
│  ⛨  Spettro needs your approval              │
│  Run shell command: cargo test               │
│  ┌────────────────────────────────────────┐  │
│  │ cargo test --workspace --all-features  │  │  mono, h-scroll,
│  └────────────────────────────────────────┘  │  selectable, 6-line cap
│  verify the steering refactor                │
│                                              │
│  ┌────────────────────────────────────────┐  │
│  │             Allow once                 │  │  filled
│  ├────────────────────────────────────────┤  │
│  │            Always allow                │  │  outlined
│  └────────────────────────────────────────┘  │
│  Always allow writes this command to         │
│  .spettro/allowed_commands.json              │
│                                              │
│                  Deny                        │  text button
╰──────────────────────────────────────────────╯
```

Headline verbatim: **Spettro needs your approval**. Kind icons: execute → terminal, think → question bubble, else lock-shield.

Buttons stack VERTICALLY, one per row, 48 dp, allow-kinds grouped above reject-kinds, primary filled. Never four buttons in a row at 400 dp. Labels come from the agent verbatim (`Allow once` / `Always allow` / `Deny`) — do not relabel. Reply `{"outcome":{"outcome":"selected","optionId":"allow-once"}}`; dismissal → `{"outcome":{"outcome":"cancelled"}}`, which the agent reads as deny.

Say that `Always allow` is durable — it persists a normalised command to `<project>/.spettro/allowed_commands.json` (network targets go to `allowed_network.json`).

The queue counter matters on a phone: a user cannot see the queue any other way, and the agent's turn is blocked on each one in order.

Keep the existing inline `PermissionRow` in the transcript as the non-modal path when the app is foregrounded and the card is on screen; use the sheet when returning from background. Either way the request MUST raise a high-priority notification with haptics — this is the precondition for shipping `ask-first` as the default, because otherwise a run silently stalls behind a sheet nobody saw.

The compaction prompt (`toolCallId: compact-1`, options `Compact now` / `Continue without compacting`) uses the same sheet with the brain icon and the agent's own title (`Context nearly full (~184000/200000 tokens). Compact conversation history now?`).

### Context gauge — ring in the app bar, sheet on tap

```
app bar:  ◕ 41%      →  amber at ≥75%, red at ≥90%

╭──────────────────────────────────────────────╮
│  Context window                              │
│                                              │
│               ◕   84%                        │
│                                              │
│  168.2k of 200.0k tokens                     │
│  Total processed: 412.8k tokens              │
│  Cache hits: 63%                             │
├──────────────────────────────────────────────┤
│  ⚠ Nearly full — compact soon                │
│  ┌──────────────┐  ┌───────────────────────┐ │
│  │  /compact    │  │ auto-compact:  on     │ │
│  └──────────────┘  └───────────────────────┘ │
╰──────────────────────────────────────────────╯
```

A 12 dp SVG-style ring (stroke 2.5, rotated −90°) plus a tabular percent label; the ring alone in the app bar, the label in the sheet. `fraction = used / size`, clamped. Severity: >0.90 red, >0.75 amber, else accent. A non-zero fraction below 1% prints `<1%`, never `0%`. Token formatting: ≥1e6 → `1.2M`, ≥1e3 → `3.4k`, else the integer.

`used` is context OCCUPANCY — the largest single request so far — and it CAN GO DOWN after a compaction. Render it as a gauge, never as a monotonic counter. `Total processed` is `usage.tokensUsed` and is shown only when it exceeds `used`. Cache-hit rate comes from `turnUsage`: `cachedRead / (input + cachedRead + cachedWrite)`.

Ignore any `usage_update` with `size <= 0`. Note the agent falls back to a hard-coded 128000 window for a model the catalog does not know, so an unknown local model can show a wrong denominator — that is upstream and not worth papering over.

The action row appears only at ≥75%: `/compact` sends the slash command, and the auto-compact toggle sends `/compact auto on|off`. Compaction is two-stage and often free (offloading large tool outputs to the on-disk spool alone may free enough, with no summariser call), so the button is cheap to press.

At ≥85% also surface the existing `AgentUsage.isNearlyFull` treatment in the strip above the composer, so the warning is visible without opening the sheet.

### Plan strip + plan sheet

```
strip (32 dp, above the composer):
│ ◌ Wire the ACP question sheet      3/7    ▲  │

╭──────────────────────────────────────────────╮
│  Plan                                 3 / 7  │
├──────────────────────────────────────────────┤
│  ✓  Read the Spettro ACP doc                 │
│  ✓  Mirror the extensions handshake          │
│  ✓  Serve _spettro/question/ask              │
│  ◉  Wire the ACP question sheet              │  in_progress
│  ○  Fold workflow runs                       │
│  ○  Run the test suite            BLOCKED    │
│  ○  Ship the onboarding flow                 │
╰──────────────────────────────────────────────╯
```

The plan is MORE important on a phone than on desktop, because the transcript shows so much less. The strip shows the first `in_progress` entry, falling back to the first pending, plus `completed/total`; it expands to a half-height sheet.

Status glyphs: pending = hollow circle, muted text; in_progress = dotted ring with a solid core, primary text (this is the row the eye should land on); completed = filled check with reduced emphasis. `blocked` (lifted out of the `(blocked)` suffix in Rust) renders as a small uppercase muted pill at the row's trailing edge — the CLI cannot express it any other way.

The agent republishes the WHOLE list in dependency order on every task mutation, including an empty list when the last task is deleted. So: replace wholesale, do not animate reordering, diff by content and cross-fade only the status glyphs.

A settled turn's plan is snapshotted into the transcript as a `CompletedPlanCard`, which stays. The live strip clears when the plan does.

WORKFLOW PHASES NEVER APPEAR HERE. They belong to the run card; the plan channel is the session task graph and merging them would clobber it.

### Live orchestration peek

```
collapsed (56 dp, above the chips, only while a run is live):
│ ● Live · review-changes  4/7 [████░░░░]   ▲  │

expanded (half sheet, ~420 dp):
╭──────────────────────────────────────────────╮
│  ● Live                                 2    │
├──────────────────────────────────────────────┤
│  ▶ review-changes                       4/7  │
│    Review the diff, then refute              │
│    [███████░░░░░]                            │
│    2 running · 4 done · 1 failed             │
│    ● Review                             2/3  │
│      [████░░]                                │
│      ● review#1  bash go vet ./...           │
│      ● review#2  read internal/acp/bri…      │
│      … 1 more running                        │
│    ○ Verify                         pending  │
│      [░░░░░░░░]                              │
│  ✓ ultra swarm · code       12 done · 1 fail │  settled
╰──────────────────────────────────────────────╯
```

Answers one question continuously while a fan-out is in flight: who is still working, on what. The transcript card is the RECORD; this is the live readout.

Only RUNNING members are listed. Caps: 3 members per phase, 5 per swarm, instance names truncated to 12 chars (keeping `#N`), overflow line `… 3 more running`. A pending phase still draws its empty track so a phase tinting in place does not push everything below it down.

Settle choreography: hold the last snapshot for 1600 ms, then collapse to a one-line settled summary (`glyph + name + note`, note = the run's own summary else `9 done · 1 failed` else `finished`), then release at 7000 ms. A settled run keeps the slot the eye found it in — assign position once per run key and never recompute. A run that merely vanished reports `failed` if it had failures; never claim a success you cannot vouch for.

Member dots PULSE rather than spin — a column of eight spinners is noise. Every string is clamped to one line; a wrapping detail makes the whole column shiver on every tool call.

Do not put this in the app bar. It must be reachable one-handed from the bottom.

### Session picker

```
╭──────────────────────────────────────────────╮
│  Sessions          [ This project │  All  ]  │
│  🔍  search                                  │
├──────────────────────────────────────────────┤
│  TODAY                                       │
│  ●  add a jupiter swap route          14:02 ›│  ● = live
│     thragg-ide                               │
│  ○  fix the anchor build              09:41 ›│
│     thragg-ide                               │
│  YESTERDAY                                   │
│  ○  why does the IDL fail to parse    18:20 ›│
│     solana-pay-demo                          │
├──────────────────────────────────────────────┤
│                              ＋  New session │
╰──────────────────────────────────────────────╯
```

Backed by Spettro's own on-disk store via `session/list`, not by an app-local list — so a conversation started in the Spettro TUI on the device shows up here, and vice versa. The segmented control maps to `session/list {cwd}` vs `{}`; the engine currently always scopes with `.cwd(root)`, so the `All` tab needs the cwd made optional (small R1 change) or should be cut for v1.

Rows are 64 dp: title (the first user prompt preview; falls back to the cwd basename) on line 1, relative time + project basename on line 2. Newest first, day-grouped. Tap = `session/load` (replay; clear the view and show a skeleton while it streams). Long-press = *Resume without replay* (`session/resume`, for a session whose transcript the phone already holds) and *Copy session id*.

Refresh on open and after every turn settles — Spettro saves after EVERY prompt turn.

Hide delete: Spettro does not advertise `SessionCapabilities.Delete`.

After a load or resume, re-apply the remembered mode with `session/set_config_option` — both reset it to the manifest default (usually `plan`). And expect no tool-call cards in a loaded conversation: the store keeps only the flat transcript. Say so with a one-line notice at the top of a replayed session ("Earlier tool activity isn't stored — this is the conversation only.") rather than letting it look like data loss.

## Onboarding

## First run: from "app installed" to "agent answering"

The user has never run Spettro. There is no `~/.spettro`, no API key, no TUI to fall back to. The agent will start fine, handshake fine, create a session fine — and then fail at the first prompt with a provider error. Everything below exists to make that never happen.

### Where state lives

Inside the Debian guest userland, under the guest user's `$HOME`:
- `~/.spettro/config.json` — active provider/model, permission, thinking, ultra, budget, favorites, local endpoints. Created `0600` in a `0700` dir.
- `~/.spettro/keys.enc` + `~/.spettro/master.key` — AES-GCM encrypted API keys. **The only place a key is ever stored. The Android side never persists, caches or logs one.**
- `~/.spettro/sessions/<id>/` — transcripts, tasks, workflow run journals.
- `<project>/.spettro/` — `PLAN.md`, `allowed_commands.json`, `allowed_network.json`, `workflows/`, `worktrees/`.

`HOME` must be set and writable for the spawned process (see W-14). Without it the handshake succeeds and every write silently fails — the worst possible failure mode.

### Step 0 — install (existing machinery, unchanged)

`AgentCatalog.SPETTRO` already describes it: GitHub release tarball from `aploide/spettro`, asset suffix `_linux_arm64.tar.gz`, extracted to `/opt/thragg/agents/spettro/spettro`. Verified to run natively on the Seeker. Show the existing install progress UI. On failure, offer retry and a "download manually" path; do not fall through to a provider screen.

### Step 1 — first trust

Spettro requires explicit trust confirmation the first time it runs in a folder (`~/.spettro/trusted.json`). Verify on device whether the ACP path prompts; if it does, pre-trust the opened project root before the first `session/new`, or the first turn hangs behind a dialog that has no phone surface.

### Step 2 — connect, handshake, probe

Spawn `/opt/thragg/agents/spettro/spettro --acp --cwd <project root>`, run `initialize` with the extension mirror (W-01), then IMMEDIATELY — before creating a session, and off the UI thread — call `_spettro/providers/list` with `{}` via `acpCallExtension`.

**With no project open** — every fresh install, before the first project exists — the Agent tab starts the agent anyway, in the *lobby*: a directory of the app's own (`filesDir/agent-lobby`, beside `projects/` and deliberately not inside it, because the picker lists everything in there). `AgentSessions.openLobby` opens it as an engine project and starts a thread flagged `isLobby`; the engine binds the working directory at spawn, so being outside the projects folder costs nothing. The lobby exists only so this probe and the sign-in below can happen: the panel never lets it be prompted, lists it or @-mentions it, and the first real project closes it exactly as a project switch closes any other project's threads. Before it existed, a new phone showed "No project is open" with the sign-in card unreachable (2026-09-04).

Compute the gate:
```
NEEDED   when ok:true AND providers.none{connected} AND local.isEmpty() AND subscription?.connected != true
SATISFIED when ok:true and anything is connected
UNKNOWN  on any error, timeout, or -32601
```
**UNKNOWN never blocks.** A failed load, a transient error or an older CLI means "we don't know", and an unknown answer must never cost someone access to the rest of the app. Only `NEEDED` shows the setup screen.

### Step 3 — the setup screen (three cards, in this order)

```
┌──────────────────────────────────────────────┐
│              Set up Spettro                  │
│                                              │
│  Spettro is the agent built into Thragg. │
│  Give it a model and you're done.            │
│                                              │
│  ┌────────────────────────────────────────┐  │
│  │ ◆ Sign in to Spettro        RECOMMENDED│  │
│  │   No key to type. Opens your browser.  │  │
│  └────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────┐  │
│  │ ⚿ Use my own API key                  ›│  │
│  │   Anthropic · OpenAI · Mistral · xAI…  │  │
│  └────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────┐  │
│  │ ⌂ Connect a local model               ›│  │
│  │   Ollama or LM Studio, on device or LAN│  │
│  └────────────────────────────────────────┘  │
│                                              │
│              Skip for now                    │
└──────────────────────────────────────────────┘
```

**Card 1 — Sign in (the phone default; no keyboard involved).**
1. `_spettro/account/login/start {}` → `{"loginId","status":"pending","browserUrl"}`.
2. Open `browserUrl` in a Chrome Custom Tab. Show a spinner sheet with a *Cancel* button.
3. The AGENT owns the poller (2 s cadence, 10 min ceiling) and pushes `_spettro/account/update` notifications as the state moves — the phone does not poll the backend. Handle the pushed status via the cached `acpAccountStatus`/`acpAccountVersion` (W-06).
4. Belt and braces: every 2 s also call `_spettro/account/login/poll {}` (a purely local read) so a dropped notification cannot strand the sheet on a spinner. Give up after 10 min or 3 consecutive poll failures; a generation counter makes a timer whose flow was superseded bow out.
5. `status: "complete"` → the notification carries a full refresh with `plan`, credits and `modelCount`. Dismiss, re-run `_spettro/providers/list`, drop the gate.
6. *Cancel* → `_spettro/account/login/cancel {}`. Starting a second login cancels the first.

**Card 2 — Own API key.**
```
┌──────────────────────────────────────────────┐
│  ←   Use my own API key                      │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐      │
│  │ Anthropic│ │  OpenAI  │ │ Mistral  │      │
│  └──────────┘ └──────────┘ └──────────┘      │
│  ┌──────────┐ ┌──────────┐                   │
│  │   xAI    │ │  Z.ai    │      ⌄ 9 more     │
│  └──────────┘ └──────────┘                   │
├──────────────────────────────────────────────┤
│  Anthropic                                   │
│  ┌────────────────────────────────────────┐  │
│  │ sk-ant-·········                    👁 │  │
│  └────────────────────────────────────────┘  │
│  Your key is verified with Anthropic, then   │
│  stored encrypted on this device. Thragg │
│  never keeps a copy.                         │
│                                              │
│                            [   Connect   ]   │
└──────────────────────────────────────────────┘
```
The grid comes from `providers/list` — the five featured ids (`anthropic, openai, mistral, x-ai, zai`) first in that order, then the rest alphabetically. The Spettro subscription entry is NOT in this grid; it lives in card 1.

`Connect` → `_spettro/providers/connect {"providerId":"anthropic","apiKey":"<key>","activate":true}`. This can take up to 30 s because the key is VERIFIED against the provider's own API before it is persisted — a bad key is never written. Show a determinate-less progress button, keep the field disabled, do not time out under 45 s. On `ok:false`, show `message` verbatim ("key rejected (401)") and leave the field editable. On success (`{"connected":true,"modelCount":42,"activeModel":"…"}`) clear the field's backing state immediately and drop the gate. `activate:true` is exactly what first-run wants: it also sets `active_provider`/`active_model`.

Use `KeyboardType.Password`, `autoCorrect = false`, and mark the field with `Modifier.semantics { password() }` so it stays out of screenshots and autofill history.

**Card 3 — Local model.** Endpoint field (default `http://127.0.0.1:11434/v1`), optional key. `_spettro/providers/local/probe {"endpoint","apiKey"}` (15 s) shows a preview list of models WITHOUT saving; zero models is an error ("that endpoint returned no models"). `Add` → `_spettro/providers/local/add` with the same params, which probes, registers and persists. On the Seeker this is the route to an on-device llama.cpp or a LAN Ollama and deserves the same weight as the other two.

**Skip for now** dismisses the gate for the session and leaves a persistent banner above the composer: `No model connected — Spettro can't answer yet.  [ Set up ]`. Do not let a skipped setup produce a raw provider error at the first prompt.

### Step 4 — first session

Only after the gate is SATISFIED (or skipped): `session/new {"cwd":"<abs project root>","mcpServers":[]}` → `{sessionId, configOptions}`. Populate the chips from `configOptions` immediately; `available_commands_update` arrives ~200 ms later and again from the first prompt, so never gate the composer on it.

### Step 5 — the permission decision, made once, explicitly

Fresh config defaults to `ask-first`. That is the right default only if the approval path is loud. So, right after the first session opens, show a one-time sheet:

```
╭──────────────────────────────────────────────╮
│  How much should Spettro ask?                │
│                                              │
│  (•) Ask first                               │
│      Prompt before running tools, edits, or  │
│      commands. Safest. Ultra and workflows   │
│      stay off.                               │
│  ( ) Restricted                     SUGGESTED│
│      Allow safe actions; prompt for sensitive│
│      ones. Unlocks Ultra and workflows.      │
│  ( ) YOLO                                    │
│      Approve everything automatically.       │
│      Needed for fully unattended /goal.      │
│                                              │
│  You can change this any time from the       │
│  Permission chip.                            │
│                            [   Continue   ]  │
╰──────────────────────────────────────────────╯
```
Never auto-select YOLO. Ship `ask-first` as the default ONLY once the high-priority approval notification with haptics is working — otherwise a run parks behind a sheet nobody saw and the app looks hung. If that notification path is not ready, ship `Restricted` as the pre-selected suggestion and say why.

### Step 6 — first prompt

The empty state reads `How can I help?` over `Working in <project>`. The first turn now succeeds. If it still fails with a provider error, re-run `providers/list`, flip the gate back to NEEDED and re-open the setup screen with the agent's own error message quoted at the top.

### Ongoing: Settings → Spettro

One screen, reachable from the ⋮ overflow, showing: account (plan, credits, `stale` values muted rather than errored, Sign out), connected providers (Disconnect), local endpoints (Remove), the model list with favourites, budget (`0` = unlimited, and leave it there — `/budget` is per-REQUEST and is validated before the call, so a small "mobile-friendly" value makes long turns fail outright), auto-compact on/off, and one honest sentence: *"Model, permission, thinking and Ultra are stored in Spettro's own config and are shared with any Spettro you run in the terminal. Mode is per-conversation."*

## Deliberately not reproduced on a phone

- The Workflow Studio (WorkflowStudio.tsx, ScriptEditor.tsx, highlight.ts) — a JS authoring IDE inside the agent panel. Thragg already IS a code editor: `_spettro/workflow/list` returns each script's absolute `path`, so the phone lists workflows and offers 'Open in editor' and 'Run'. Cut `_spettro/workflow/write`, `/delete` and the live-validate-on-keystroke loop entirely; the file the editor saves is the same file the agent runs.
- The docked side column (OrchestrationPanel as a persistent second column). There is no room for two columns at 400 dp. Replaced by the 56 dp peek + half sheet.
- The question sheet's side-by-side preview pane. Replaced by a per-option `◱` button opening a nested mono sheet. Keep the previews — cut the two-pane layout.
- All hover-driven behaviour. Option previews follow selection/focus only. Every tooltip becomes a subtitle, a snackbar, or a one-shot info sheet (the worktree explanation, the Ultra lock reason).
- Keyboard shortcuts as the primary path — Enter=primary, Esc=decline, Ctrl+Shift+A=allow-always. Every one gets an explicit visible button. Keep Enter-to-send in the composer only.
- Desktop's app-local sidebar with pinned / archived / project grouping and per-row context menus. Spettro's own `session/list` has no pin or archive flags, and duplicating that state on the phone means two session stores that drift. Use the server-side list only.
- `session/delete` UI. Spettro does not advertise `SessionCapabilities.Delete`; the call will return false. Hide the affordance rather than shipping a button that does nothing.
- The 5-second git-stat poll and its popover chip. Fold uncommitted changes into the existing review pane; polling `git status` every 5 s on a phone is a battery bug and the information is already one tap away.
- The install/locate app phases (`locating`, `installing` as first-class app states from the Desktop model). The binary is bundled and installed by the existing `AgentCatalog` machinery; keep `needsProject`, `connecting`, `needsProvider`, `ready`, `failed`.
- `/loop <interval> <prompt>` as a promoted feature. It runs a recurring LLM loop inside one prompt turn that can last hours. Leave the command reachable through the palette (the agent advertises it) but give it no button, no chip, and no onboarding mention.
- Any client-side timeout on `session/prompt`. `/goal` and `/loop` run their ENTIRE loop inside one prompt turn that can last minutes or hours. A request timeout there breaks the single best feature the phone has.
- Streaming the answer token-by-token, and any typing effect built on `agent_message_chunk`. Spettro's internal stream has draft-reset semantics ACP cannot express; the answer arrives whole at turn end. Building a typewriter produces a dead-looking screen followed by a jump.
- Advertising `fs/*` or `terminal/*` capabilities we do not intend Spettro to use — actually, KEEP them (the engine already serves both correctly and confines paths). What to cut is the instinct to add MORE capabilities: do not advertise anything without a working handler, and answer `-32601` to everything else.
- Any client-side polling of the Spettro backend during device-flow login. The agent owns the poller and pushes `_spettro/account/update`. The client only reads the local `_spettro/account/login/poll` mirror as a dropped-notification safety net.
- Modelling the 19 `_spettro/*` methods as typed Rust calls. One generic `acp_call_extension(method, params_json) -> envelope_json` seam covers all of them; typed decoding happens once, in Kotlin.
- Doing the orchestration fold in Rust. `session/load` replays only flat user/assistant/thinking messages — a restored conversation has no tool calls — so the 'live and replayed must fold identically' invariant that forces this into the engine on desktop does not exist here. The engine's only job is to stop destroying the opening `rawInput` (W-09).
- `/mode`, `/models`, `/permission`, `/permissions`, `/thinking`, `/ultra`, `/plan` as slash commands in the palette. They are the toolbar chips; typing them works but is strictly worse, and two paths to the same state invite disagreement.
- `/init` and `/approve`. Both answer with a canned string over ACP telling you to do something else.
- The memory review inbox (`/memory review|mine|curate|edit`). These are TUI-dialog-only and have no ACP surface. Surface `/memory show|add|clear` only, and say plainly that a saved fact takes effect next session.
- Multi-agent choice. Claude Code and Codex are deleted from `AgentCatalog`, and with them the `AgentInstallMethod.Npm` branch and the Node-from-apt install. One bundled agent, no picker, no per-agent capability branching in the UI beyond the `spettro != null` gate.

## Implementation plan

### R1 · engine ACP wiring

*Owns:* `core/crates/engine/src/acp.rs`

*Depends on:* nothing

THE GATE — land first, alone, and verify against the device before anything else starts.

1. W-01: add `.meta(json!({"spettro.app/extensions":{"version":4,"methods":["_spettro/question/ask"]}}))` to the `InitializeRequest` at ~1553.
2. W-02: read `response.meta["spettro.app/extensions"]` into `AgentInfo.spettro_extensions`; emit as `agent.spettroExtensions` in `acp_session_state`.
3. W-03: trailing `UntypedMessage` request handler in `run_connection`, routing `_spettro/question/ask` into `Questions::open` and returning `Handled::No` for everything else.
4. W-06: trailing `UntypedMessage` notification handler caching `_spettro/account/update`; add `Engine::acp_account_status()` / `acp_account_version()`.
5. W-07: `Engine::acp_call_extension(project, method, params_json) -> String` with the ok/err envelope and a 45 s ceiling.
6. W-10 (rust half): pass `request.meta` into `begin_permission`; add the 4th `answer_meta_json` param to `acp_respond_permission`.
7. W-11 (rust half): `Engine::acp_steer(...)`, gated on `Phase::Running` && `spettro_extensions.is_some()`.
8. W-05/W-04 glue: hold `Questions` on `AgentShared`; call `cancel_session`/`cancel_all` wherever `Elicitations`' equivalents are called; fold the questions array into `acp_session_state`.
9. Optional: make `session/list`'s `.cwd(root)` scoping conditional so the picker's `All` tab can work (else the picker ships project-scoped only).

VERIFY: run the real binary, log the raw initialize exchange, and confirm the response `_meta.spettro.app/extensions.clientMethods` contains `_spettro/question/ask` AND that a real ask-user form arrives as `_spettro/question/ask` rather than as a permission walk. Nothing downstream is worth building until that is on the wire.

### R2 · engine question store

*Owns:* `core/crates/engine/src/acp_question.rs`

*Depends on:* nothing

New self-contained module, a near-copy of acp_elicit.rs's `PendingElicitation`/`Elicitations` pattern: `Arc<PendingQuestion>` holding the raw params, a `Mutex<Option<Responder<serde_json::Value>>>` and an `AtomicU64` version counter.

Implement exactly the API frozen in W-04: `version()`, `open(params, session, responder)`, `view_json()`, `answer(id, answer)`, `cancel_session(session)`, `cancel_all()`. Ids are `question-<n>`. `cancel_*` responds `{"kind":"cancelled"}` — a question nobody answers is an agent that waits forever, and answering twice is a protocol error rather than a second chance.

Forward the payload VERBATIM; model nothing. Unit-test: open two questions, answer one, cancel the session, assert both responders were consumed exactly once and the version moved four times.

Starts in parallel with R1 — the API is frozen in this spec, so R1 can code against it before it exists.

### R3 · engine thread state

*Owns:* `core/crates/engine/src/acp_thread.rs`

*Depends on:* nothing

1. W-08: `Usage.tokens_used` from `update.meta["spettro.app/tokensUsed"]`; ignore `size <= 0`; new `turn_usage` field emitted as `turnUsage` (filled by R1's `run_prompt` — agree the setter signature `pub fn set_turn_usage(&mut self, value: serde_json::Value)` now).
2. W-09: `ToolCallEntry.raw_input_open`, set on the FIRST non-null `rawInput` and never again; serialized `rawInputOpen`. This is the change the workflow card depends on.
3. W-17: `ToolCallEntry.turn` stamped from a per-thread turn counter; `tool_call_index` matches `(turn, id)`. Spettro's ids repeat every turn.
4. W-12: strip the literal `" (blocked)"` suffix in the `SessionUpdate::Plan` arm and emit `"blocked": true`; carry it through `snapshot_completed_plan`.
5. W-10: `ToolCallEntry.permission_meta` → `permissionMeta`.
6. W-11: `running_turns: usize`; `end_turn` performs its settle work (phase → Ready, clear `turn_cancelled`, snapshot plan, drain the queue) ONLY when the count reaches zero. Without this a steering turn's instant `end_turn` settles a live session and fires a third prompt on top of it.
7. Render tool-call image content blocks properly in `block_markdown` instead of the literal `*[image]*`.

Rules for anyone adding an entry field here: never name a field `kind` (it collides with the `#[serde(tag = "kind")]` tag — that bug already shipped once, which is why `ToolCallEntry` renames its to `tool_kind`); every mutation must end in `push_entry` or `touch(index)` or the row is invisible to the poller forever. Extend the `entries_since_hands_back_only_what_moved` test.

### R4 · JNI exports

*Owns:* `core/crates/jni-bridge/src/lib.rs`

*Depends on:* R1 · engine ACP wiring, R2 · engine question store, R3 · engine thread state

Add, beside the existing elicitation exports at 3944-4010, one `Java_to_eyed_thragg_core_CoreBridge_*` per new engine fn, following the file's existing string-in/string-out conventions:

`acpQuestionsVersion() -> jlong`, `acpPendingQuestions() -> jstring`, `acpRespondQuestion(question_id, answer_json) -> jboolean`, `acpCallExtension(project: jlong, method, params_json) -> jstring`, `acpAccountStatus() -> jstring`, `acpAccountVersion() -> jlong`, `acpSteer(session: jlong, text, mentions_json, images_json) -> jboolean`, and the widened `acpRespondPermission(session, tool_call, option_id, answer_meta_json) -> jboolean`.

Signatures are frozen in this spec, so this chunk can be written against them before R1–R3 land; it only needs them to compile.

### K0 · CoreBridge externs

*Owns:* `app/src/main/java/to/eyed/thragg/core/CoreBridge.kt`

*Depends on:* nothing

One `external fun` per R4 export, with the same doc-comment discipline as the surrounding file (each says what to poll and when). Update the `acpRespondPermission` signature and every call site's expectations — the actual call sites move in K2.

No logic. This file is owned solely by this chunk so nobody else has to touch it; it can be written from the frozen signatures immediately and merged before the Rust lands (JNI resolution is lazy, so an extern with no export only fails when called).

### K1 · Kotlin session state

*Owns:* `app/src/main/java/to/eyed/thragg/core/AgentSession.kt`

*Depends on:* K0 · CoreBridge externs

Everything in STATE MODEL §1–3 and §5:

- `SpettroSurface` parsed from `agent.spettroExtensions`; `AgentSessionState.spettro`.
- `AgentUsage.tokensUsed` + `fraction`/`isWarm`/`isNearlyFull`; `AgentTurnUsage`.
- `AgentPlanEntry.blocked`.
- W-13: `parseConfigOptions` stops flattening groups and keeps `category`; `AgentConfigOption.Kind.Select(currentValue, groups, flat)`; `SpettroToolbar` with the four-state `ultraState` and `ULTRA_LOCK_REASON`.
- W-10: `PermissionOption.isRecommended` / `isCustomInput` from `_meta`; `ToolCall.permissionMeta`.
- W-09/W-17: `ToolCall.rawInputOpen`, `ToolCall.turn`, the lazy `args`/`openArgs` JSONObject accessors, `toolName`/`agentPrefix`.
- `SpettroQuestion` + `QuestionDraft` + `QuestionAnswer` and their parser.
- `rememberSpettroQuestions()` beside `rememberPendingElicitations()`.

Discipline: `AgentEntry.parse` must never return null and never throw — parse with `optString`/`optJSONObject`, and let an unknown `kind` fall to `Unsupported`. A hole in the merge makes `AgentConversation.apply` re-read from revision 0 forever.

### K2 · Kotlin actions & launch

*Owns:* `app/src/main/java/to/eyed/thragg/core/AgentSessions.kt`, `app/src/main/java/to/eyed/thragg/core/Agents.kt`, `app/src/main/java/to/eyed/thragg/solana/agents/AgentCatalog.kt`

*Depends on:* K0 · CoreBridge externs

- W-14: collapse the catalog to Spettro; delete `CLAUDE_CODE`, `CODEX` and the now-dead `AgentInstallMethod.Npm` branch. argv = `["/opt/thragg/agents/spettro/spettro","--acp","--cwd",<abs root>]`. Set `HOME` explicitly in the env map and verify it is writable — this is the difference between a working install and one where every config write silently fails.
- `AgentSessions.steer(...)` beside `prompt(...)`, plus the answer encoder `answerQuestion(id, answers | null)` producing the exact `{"answers":[…]}` / `{"kind":"declined"}` shapes.
- `respondPermission(..., answerMeta)` and the question-over-permission reply path.
- `callExtension(method, paramsJson)` on `Dispatchers.IO` returning a sealed `ExtResult { Ok(JSONObject) | Rpc(code, message) | Unsupported | Offline }` — `-32601` maps to `Unsupported` so the UI can say "update Spettro" rather than "failed".
- `setConfigOption` gains refusal-vs-transport handling: an RPC error rolls the chip back and drops the change; anything else keeps it queued for the next attach.
- Session picker actions: `listSessions(refresh)`, `loadSession(id)` (replay), `resumeSession(id)`, and re-applying the remembered mode after either.

### K3 · orchestration fold

*Owns:* `app/src/main/java/to/eyed/thragg/core/SpettroOrchestration.kt`

*Depends on:* K1 · Kotlin session state

One new file, one pure function `foldOrchestration(entries) : List<TranscriptRow>`, plus the types in STATE MODEL §4. Two linear passes: index runs/members/children and claim script calls, then re-emit rows in original order with absorbed items dropped.

Port the classification table exactly (classify by `openArgs`, never by title). Implement `parseRenderedWorkflow` for the `log:` block and as the phase-tree fallback, taking the LAST blank-line-delimited block whose every line is a phase header or an indented member row, everything wrapped in `runCatching`.

Swarm `pending = items.drop(members.size)`; `counts.total = members.size + pending.size`. Member ordering running → failed → done, stable. `liveDetail` = the latest child's derived detail with its own bracket stripped, falling back to `task` once finished. `truncateInstance` keeps the `#N` suffix.

No Compose imports in this file. Unit tests over recorded JSON fixtures: a 3-phase workflow with one failure, a 20-item swarm mid-ramp, an unclaimed script call, a finished run whose finish update overwrote `rawInput`.

### K4 · run cards

*Owns:* `app/src/main/java/to/eyed/thragg/ui/agent/spettro/WorkflowCard.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/SwarmCard.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/OrchBits.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/LiveRunPeek.kt`

*Depends on:* K3 · orchestration fold

`OrchBits.kt` first — the shared primitives, so a run looks identical in the card and the peek: `ProgressMeter` (4 dp, segmented, ≥6% forced for any non-zero failure, done clamped so the sum ≤100), `StatusGlyph` (spinner / check / ✗ with content descriptions — a failure must be legible without colour), `SpettroSpinner` (the 8-dot braille port: dot radius size*0.11, opacity `max(0.12, 1 - i*0.16)`, one revolution per 400 ms in 8 discrete 50 ms steps), `CountsLabel` (zero terms dropped, only `failed` coloured, `cached` always spelled *replayed*), `MemberRow`, `memberTint(specId)`, `truncateInstance`.

Then `WorkflowCard` and `SwarmCard` per the wireframes, and `LiveRunPeek` with the 1600 ms hold / 7000 ms release settle choreography and once-assigned slot positions.

Expose only `@Composable fun WorkflowCard(run, modifier)`, `SwarmCard(...)`, `LiveRunPeek(runs, expanded, onToggle)` — K9 calls them. Colours via `LocalZedTheme.current.color(...)`; the one hard-coded literal permitted anywhere is `#1a1205`, the label on the armed amber Ultra chip.

### K5 · toolbar chips & selector sheets

*Owns:* `app/src/main/java/to/eyed/thragg/ui/agent/spettro/ConfigChips.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/ConfigSheets.kt`

*Depends on:* K1 · Kotlin session state

`ConfigChips(toolbar, busy, onSelect, onToggleUltra, onLockedTap)` — the horizontally scrollable 32 dp row in the order Ultra, Mode, Model, Thinking, Permission, with the four-state Ultra chip and the mode tint. Never hard-code option ids beyond the five special cases; render whatever the agent sends.

`ConfigSheet(option, onPick, onDismiss)` — the modal bottom sheet with sticky group headers for grouped selects and 56 dp rows carrying each choice's description. Permission gets the two footer lines (what Restricted unlocks; that lowering to Ask first suspends rather than clears Ultra).

The locked Ultra tap does NOT send a set that will be refused — it raises the snackbar with `ULTRA_LOCK_REASON` verbatim and a *Change…* action that opens the Permission sheet. Turning Ultra off is never locked.

Expose composables only; K9 wires the callbacks to K2.

### K6 · question & permission sheets

*Owns:* `app/src/main/java/to/eyed/thragg/ui/agent/spettro/QuestionSheet.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/PermissionSheet.kt`

*Depends on:* K1 · Kotlin session state

`QuestionSheet(request, onAnswer, onDecline)` per the wireframe: full-height, IME-aware, one question per page, step dots, nothing preselected, `★ Recommended` as a badge only, per-option preview sheets, custom-text-clears-selection on single-select, notes joined into `notes` on multi-select, the review page with its warning sentence verbatim, a `doneRef` keyed on request id. Answers built in OPTION order; an untouched question is omitted, never defaulted.

`PermissionSheet(request, queueDepth, onSelect, onDismiss)`: headline verbatim, mono command block with horizontal scroll and a 6-line cap, vertically stacked 48 dp buttons with allow-kinds above reject-kinds, the durability note under *Always allow*, and the `#N of M waiting` counter.

Both sheets take a plain data object and callbacks — no engine access. K9 routes a request carrying `permissionMeta["spettro.app/question"]` to the QuestionSheet, not the PermissionSheet.

Also in this chunk: extend `AgentNotifier` usage requirements in the API doc-comment — a pending question or permission MUST raise a high-priority notification, since the agent's turn is blocked on it and the phone may be in a pocket. (The `AgentNotifier.kt` edit itself belongs to K9 to avoid a second owner.)

### K7 · gauge, plan, session picker

*Owns:* `app/src/main/java/to/eyed/thragg/ui/agent/spettro/ContextGauge.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/PlanSurface.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/SessionPicker.kt`

*Depends on:* K1 · Kotlin session state

`ContextRing(usage)` (12 dp, severity accent/amber/red at 0.75/0.90, `<1%` rule, tabular figures) and `ContextSheet(usage, turnUsage, onCompact, onToggleAutoCompact)` with the two lines, the cache-hit rate and the ≥75% action row.

`PlanStrip(plan, onExpand)` and `PlanSheet(plan)` with the three status glyphs and the `BLOCKED` pill; replace wholesale on every update, cross-fade glyphs only, never animate reordering.

`SessionPicker(sessions, scope, query, onOpen, onResume, onNew)`: day-grouped 64 dp rows, search over title + project basename, no delete affordance, and the one-line notice at the top of a replayed session explaining that tool activity is not stored.

All three take data + callbacks; no engine access.

### K8 · onboarding & provider setup

*Owns:* `app/src/main/java/to/eyed/thragg/core/SpettroSetup.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/SetupScreen.kt`, `app/src/main/java/to/eyed/thragg/ui/agent/spettro/SetupSheets.kt`

*Depends on:* K2 · Kotlin actions & launch

`SpettroSetup.kt`: the DTOs and lenient decoders in STATE MODEL §6, the `SetupGate` computation that FAILS OPEN, and the device-flow driver (agent-pushed `_spettro/account/update` as the primary signal, a 2 s local `login/poll` as the dropped-notification safety net, 10 min ceiling, 3-failure limit, a generation counter so a superseded flow's timer bows out). All calls on `Dispatchers.IO`; `providers/connect` gets a 45 s allowance.

`SetupScreen.kt` + `SetupSheets.kt`: the three cards, the provider grid (featured five in order, then alphabetical), the key field (`KeyboardType.Password`, no autocorrect, `semantics { password() }`, cleared the instant the call returns), the local-endpoint probe preview, the permission-choice sheet, and the Settings → Spettro screen.

Never log, cache, persist or even keep a key in a retained composable field. Provider errors are shown verbatim — `message` from the envelope is the provider's own text and is already written to be read.

Can proceed in parallel with all UI chunks; it touches nothing they touch.

### K9 · panel integration

*Owns:* `app/src/main/java/to/eyed/thragg/ui/agent/AgentPanel.kt`, `app/src/main/java/to/eyed/thragg/core/AgentNotifier.kt`

*Depends on:* K3 · orchestration fold, K4 · run cards, K5 · toolbar chips & selector sheets, K6 · question & permission sheets, K7 · gauge, plan, session picker, K8 · onboarding & provider setup, K10 · activation glow

THE ONLY CHUNK THAT TOUCHES AgentPanel.kt (3658 lines). Everything else creates new files and exposes composables this chunk calls — that is what keeps the other nine from colliding.

Work: replace the flat `Conversation` loop with `foldOrchestration(...)` and a `when (row)` over `TranscriptRow`; drop `ComposerChrome`'s inline chips in favour of `ConfigChips`; mount `PlanStrip`, `LiveRunPeek`, `ContextRing` in the app bar and the composer area; route pending questions to `QuestionSheet` and permissions to `PermissionSheet`, sniffing `permissionMeta["spettro.app/question"]` FIRST; keep the composer enabled while busy and switch the send button to **Steer**; render the steering/goal/loop strings as centred pills; mount `SetupScreen` behind the `SetupGate`; replace `ThreadsView`/`PastSessionRow` with `SessionPicker`; make sure the `Trouble` path never offers Spettro's `spettro-setup` terminal auth method.

`AgentNotifier.kt`: high-priority channel with haptics for a pending question or permission, cancelled the moment it is answered. This is the precondition for shipping `ask-first` as the default.

Land LAST, in one pass, once the others are merged.

### K10 · activation glow

*Owns:* `app/src/main/java/to/eyed/thragg/ui/agent/spettro/WorkflowActivation.kt`

*Depends on:* nothing

A verbatim Kotlin port of the CLI's activation regexes plus `workflowActivationSpans(text)`, `workflowRequested(text)`, and a `VisualTransformation` that applies an animated `SpanStyle(brush = …)` over the matched spans (dark and light ramps declared separately, plus a third for the accent-filled user bubble).

The matcher MUST stay behaviourally identical to `internal/agent/workflow.go` — a UI that lights up a phrase the CLI ignores promises a mode the run never enters. `ultracoded`, "our deploy workflow is broken" and `.github/workflows/ci.yml` must all stay plain. Spans merged, non-overlapping, sorted earliest-first with longer winning on a tie.

Unit-test the seven patterns and the three negatives. Zero dependencies; can start immediately and in parallel with everything.
