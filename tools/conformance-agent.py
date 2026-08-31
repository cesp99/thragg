#!/usr/bin/env python3
"""A first-party ACP agent, for proving the agent panel end to end.

The panel's exit criterion needs a real agent process speaking the Agent
Client Protocol over real pipes — and installing somebody's actual agent is
the owner's call, not the build's. This is the conformance stand-in: ~250
lines of stdlib Python (the Debian guest ships python3, so it needs no
install of any kind), configured through the same `agent_servers` settings
entry any user-supplied agent uses:

    "agent_servers": {
      "Conformance": { "command": "python3", "args": ["/root/conformance-agent.py"] }
    }

Per prompt it exercises every surface the panel has: streamed thought and
message chunks, a plan that progresses, a tool call, a permission request
(so allow/deny and cancellation are all drivable), file access through the
client's own fs capability (so the engine's project-root confinement is on
the path), and a diff on the completed call.

The host test `a_python_conformance_agent_survives_the_whole_flow` in
core/crates/engine/src/acp.rs drives this same file through the production
spawn path, so a wire-shape mistake here is a red test, not a device session.

Wire notes, all load-bearing:
  - One JSON object per line, "jsonrpc":"2.0" on everything, stdout flushed
    per line (block-buffered stdout would arrive as one blob at exit).
  - Field casing is the protocol's: camelCase keys, snake_case enum values,
    and the session/update payload tagged by "sessionUpdate".
  - A request id is echoed verbatim — the client may use strings or numbers.
  - "oldText" on a diff is *omitted* for a new file, never null: the schema
    treats null and absent differently on other fields, so absence is the
    only spelling that cannot be misread.
  - Nothing human-readable ever goes to stdout; logs go to stderr, which the
    engine forwards to logcat.

THE SPETTRO SUPERSET, and why it is behind a flag
-------------------------------------------------
Run with `--spettro` this file stops being a generic ACP agent and starts
impersonating Spettro's *extended* surface: five session config options with
the Ultra gate, workflow runs with declared phases, Ultra swarms with a
promised denominator, `usage_update` several times inside one turn, a
dependency-blocked plan entry and a whole four-question ask-user form. Those
surfaces are the reason Spettro is bundled rather than any ACP agent, and
until now nothing on this side could make one appear: the panel's workflow
card, phase spine and swarm ghosts had never been seen drawing.

It is a flag rather than the default for one reason: the host test
`a_python_conformance_agent_survives_the_whole_flow` (core/crates/engine/src/acp.rs)
asserts the exact command list and the exact config-option ids this agent
advertises, and spawns it with no arguments. The default surface is therefore
byte-for-byte what it always was, and everything below is additive.

Every shape emitted under `--spettro` is taken from docs/SPETTRO.md (W-08,
W-09, W-10, W-12, W-13) and from what `core/SpettroOrchestration.kt` actually
classifies on. That constraint is the whole value: a conformance agent that
emits a shape real Spettro never sends would make a broken client look
correct, so nothing here is invented for the client's convenience —
in particular a workflow run's `rawInput` is rewritten only ONCE, by the
finish update, because that is the only rewrite the CLI does and the only one
W-09 exists to survive.

DETERMINISM. Scenarios are step lists, not races: `--pace` is the gap between
steps and `--hold` is how long the run sits still at each named hold point, so
a screenshot taken anywhere inside a hold window shows the same frame. Every
hold announces itself on stderr — `conformance-agent: HOLD workflow:mid (12s)`
— which the engine forwards to logcat, so a driver script can wait for the
line instead of guessing. `--dump NAME` prints a scenario's outbound messages
and exits, which is how the Kotlin fold test gets fixtures that cannot drift.

    python3 tools/conformance-agent.py --spettro --pace 0.7 --hold 12
    python3 tools/conformance-agent.py --dump workflow
    python3 tools/conformance-agent.py --selftest
"""

import json
import os
import sys
import time

CHUNK_PAUSE_SECONDS = 0.15
DEFAULT_TARGET = "AGENT_NOTE.md"


class Options:
    """How the run behaves, from argv. One instance, handed to the Agent.

    Defaults are the historical behaviour exactly: `spettro` off means the
    advertised surface is what the Rust round-trip test asserts.
    """

    def __init__(self):
        self.spettro = False
        # Gap between scenario steps. 0 makes a scenario a single burst, which
        # is what `--dump` and the self-test want and what a screenshot does
        # not: the panel polls the engine every 120 ms, so a burst arrives as
        # one frame and the ramp nobody saw is the ramp nobody can prove.
        self.pace = 0.7
        # How long a named hold sits still. Long enough that a driver which
        # sees the stderr line still has time to take the screenshot.
        self.hold = 12.0
        # The scenario an unrecognised prompt runs, so a device can be driven
        # with `adb shell input text go` instead of a slash command.
        self.default_scenario = None

    def pause(self, seconds=None):
        seconds = self.pace if seconds is None else seconds
        if seconds > 0:
            time.sleep(seconds)


OPTIONS = Options()


def send(message):
    sys.stdout.write(json.dumps(message) + "\n")
    sys.stdout.flush()


def log(text):
    sys.stderr.write("conformance-agent: " + text + "\n")
    sys.stderr.flush()


def read_message():
    """The next JSON-RPC message, or None on EOF. Unparseable lines are
    skipped with a log rather than fatal: the transport owns framing, and a
    conformance tool that dies on garbage proves nothing about the client."""
    while True:
        line = sys.stdin.readline()
        if line == "":
            return None
        line = line.strip()
        if not line:
            continue
        try:
            return json.loads(line)
        except ValueError:
            log("skipping unparseable line: " + line[:120])


def describe_answer(item):
    """One answer as `question=value`, in either spelling of the shape.

    docs/SPETTRO.md W-10 is the real wire: `questionId`, plus `optionIds`
    (always) and `optionId` (for a single pick), or `text` for a custom
    answer, with an optional `notes`. The older `{"id","value"}` pair is what
    the engine's own round-trip test hands back
    (acp.rs::a_python_conformance_agent_survives_the_whole_flow), and it is
    kept working rather than replaced — this file is a conformance *stand-in*,
    so a shape it refuses is a shape nobody can prove, and refusing the test's
    own fixture would make a red test out of an unrelated change.
    """
    name = item.get("questionId") or item.get("id")
    if "value" in item:
        value = item.get("value")
    elif item.get("text"):
        value = item.get("text")
    else:
        picked = item.get("optionIds")
        if picked is None:
            single = item.get("optionId")
            picked = [single] if single is not None else []
        value = "+".join(str(one) for one in picked)
    notes = item.get("notes")
    if notes:
        value = "%s (%s)" % (value, notes) if value else "(%s)" % notes
    return "%s=%s" % (name, value)


class Cancelled(Exception):
    """The client cancelled the turn; unwind to the prompt handler, which
    answers with stopReason "cancelled" as the spec requires."""


class Agent:
    def __init__(self):
        self.cancelled = False
        self.next_request = 0
        self.tool_calls = 0
        # What the client said it can do, from `initialize`. A conformance
        # agent must *honour* the negotiation, not just perform it: anything
        # offered that the client did not claim is a bug on this side, and an
        # agent that offers it anyway hides the client's missing capability
        # instead of exposing it. Empty until initialize, which the protocol
        # requires before anything else.
        self.client_capabilities = {}
        # The `_spettro/*` methods the client said *it* serves, from the
        # top-level `_meta` of `initialize`. Same rule as the capabilities
        # above: nothing is offered that the client did not claim.
        self.client_extensions = []
        # Per-session ids, "conf-<n>": the spec wants unique ids, and a
        # constant would collide the moment a client opened a second session
        # on the same process.
        self.sessions = 0
        # **State per session, keyed by id.** One client process holds several
        # threads against one agent — the panel does exactly that — and every
        # one of them is a separate ACP session over the same pipes. Holding a
        # single `self.session_id` meant a prompt for conf-1 emitted updates
        # tagged conf-2, so a whole turn (chunks, plan, tool call, permission
        # request) landed in the wrong thread's transcript.
        self.state = {}

    def session(self, session_id):
        """The state for `session_id`, created on first sight."""
        return self.state.setdefault(
            session_id,
            # `history` is what makes `session/load` possible: an agent that
            # kept nothing would have nothing to replay, and a client that
            # never checks would look identical either way.
            {"cwd": os.getcwd(),
             # `model` is shared by both surfaces; the Spettro one starts on
             # the id the grouped list carries, so `currentValue` resolves
             # through a group rather than falling back to the raw string.
             "model": "anthropic:claude-sonnet-4-5" if OPTIONS.spettro else "conf-one",
             "verbose": False,
             # The Spettro five. `permission` starts at `ask-first` because a
             # fresh CLI config does, which is also what puts the Ultra chip in
             # its LOCKED state at t=0 — the state nobody had seen.
             "mode": "coding", "permission": "ask-first", "thinking": "off",
             "ultra": False,
             "history": [], "title": None, "closed": False, "mcp": []},
        )

    def can(self, *path):
        """Whether the client advertised the capability at `path`.

        ACP capabilities are "present and non-null means yes": an empty object
        is the affirmative spelling, so anything that is not None counts.
        """
        node = self.client_capabilities
        for key in path:
            if not isinstance(node, dict):
                return False
            node = node.get(key)
            if node is None:
                return False
        return node is not False

    def config_options(self, session_id):
        """The session's config options — *if* the client can render them.

        Gated on `session.configOptions`, and the boolean one gated again on
        `session.configOptions.boolean`, because that is what the capability
        means. It is also the only way this file can prove the engine sends
        those capabilities: no advertisement, no options, no chips.

        Under `--spettro` it is the CLI's five instead: mode, model,
        permission, thinking, ultra — ids and all, because the panel's chips
        special-case by id (`core/AgentSession.kt`'s `SpettroToolbar`) and a
        near-miss id draws a nameless chip that cannot be tapped.
        """
        if not self.can("session", "configOptions"):
            return []
        state = self.session(session_id)
        if OPTIONS.spettro:
            return self.spettro_config_options(state)
        options = [
            {"id": "model", "name": "Model", "type": "select",
             "category": "model", "currentValue": state["model"],
             "options": [
                 {"value": "conf-one", "name": "Conformance One"},
                 {"value": "conf-two", "name": "Conformance Two"},
             ]},
        ]
        if self.can("session", "configOptions", "boolean"):
            options.append({"id": "verbose", "name": "Verbose",
                            "type": "boolean", "currentValue": state["verbose"]})
        return options

    def spettro_config_options(self, state):
        """The five selectors, in the agent's own order (SPETTRO.md W-13).

        Three things here are the point of the whole scenario and are easy to
        get wrong in a stand-in:

          * **Model is GROUPED.** A group element is
            `{"group":…,"name":…,"options":[…]}` — v1 of the schema calls the
            key `group`, not `groupId`, and a group that misses it is dropped
            by the engine's own deserializer (`VecSkipError`) with no error
            anywhere, leaving a model chip with nothing under it. The client
            decides grouped-vs-flat by whether the FIRST element carries a
            nested `options` array, so a flat list with one odd entry must
            never be sent.
          * **`category` survives.** `mode`, `model`, `thought_level` pick the
            chip icons; permission and ultra have none, deliberately.
          * **`ultra` publishes `cfg.Ultra`, not `UltraActive()`** — it stays
            `true` under `ask-first`, where the swarm is suspended rather than
            off. That three-state lie is exactly what the client derives
            SUSPENDED from, so sending the honest-looking `false` would hide
            the bug this exists to catch.
        """
        options = [
            {"id": "mode", "name": "Mode", "category": "mode", "type": "select",
             "description": "How Spettro works in this session",
             "currentValue": state["mode"],
             "options": [
                 {"value": "plan", "name": "Plan",
                  "description": "Read and reason; propose, never edit"},
                 {"value": "coding", "name": "Coding",
                  "description": "Edit files and run commands"},
                 {"value": "ask", "name": "Ask",
                  "description": "Answer questions about the code only"},
             ]},
            {"id": "model", "name": "Model", "category": "model", "type": "select",
             "description": "Active model for this session",
             "currentValue": state["model"],
             "options": [
                 {"group": "anthropic", "name": "Anthropic", "options": [
                     {"value": "anthropic:claude-sonnet-4-5",
                      "name": "Claude Sonnet 4.5",
                      "description": "Balanced; the CLI default"},
                     {"value": "anthropic:claude-opus-4-1",
                      "name": "Claude Opus 4.1",
                      "description": "Slower, stronger, dearer"},
                 ]},
                 {"group": "spettro", "name": "Spettro", "options": [
                     {"value": "spettro:fast", "name": "Spettro Fast"},
                 ]},
                 {"group": "lmstudio", "name": "LM Studio (local)", "options": [
                     {"value": "lmstudio:qwen3-coder-30b", "name": "qwen3-coder-30b",
                      "description": "On this machine; no key needed"},
                 ]},
             ]},
            {"id": "permission", "name": "Permission", "type": "select",
             "description": "How much Spettro asks before acting",
             "currentValue": state["permission"],
             "options": [
                 {"value": "ask-first", "name": "Ask first",
                  "description": "Prompt before running tools, edits, or commands"},
                 {"value": "restricted", "name": "Restricted",
                  "description": "Allow safe actions; prompt for sensitive ones"},
                 {"value": "yolo", "name": "YOLO",
                  "description": "Automatically approve all tool, path, and command requests"},
             ]},
            {"id": "thinking", "name": "Thinking", "category": "thought_level",
             "type": "select", "description": "How much reasoning to spend",
             "currentValue": state["thinking"],
             "options": [
                 {"value": "off", "name": "Off"},
                 {"value": "low", "name": "Low"},
                 {"value": "medium", "name": "Medium"},
                 {"value": "high", "name": "High"},
             ]},
        ]
        if self.can("session", "configOptions", "boolean"):
            options.append({
                "id": "ultra", "name": "Ultra", "type": "boolean",
                "description": "Fan work out across parallel sub-agents",
                "currentValue": state["ultra"],
            })
        return options

    ULTRA_LOCK_REASON = ("Ultra requires the Restricted or YOLO permission level "
                         "\u2014 change Permission first")

    def apply_spettro_config(self, state, config, value):
        """Apply one config change, or say in the CLI's own words why not.

        Returns None when it took, and the refusal sentence when it did not.
        The one rule with teeth is Ultra's: `UltraActive() = Ultra &&
        Permission != ask-first`, so turning Ultra ON while the level is
        `ask-first` is refused outright — and the client is expected never to
        send it (the chip is LOCKED and shows this same sentence), so a
        request arriving here at all is itself a finding.

        Lowering the level back to `ask-first` while Ultra is stored on is
        **accepted**, and Ultra stays `true`. That is not sloppiness: it is
        the SUSPENDED state, and clearing the flag instead would silently lose
        the user's setting the moment they asked a question carefully.
        """
        if config == "ultra":
            if bool(value) and state["permission"] == "ask-first":
                log("refusing ultra=on under ask-first")
                return self.ULTRA_LOCK_REASON
            state["ultra"] = bool(value)
            return None
        for name, allowed in (
            ("mode", ("plan", "coding", "ask")),
            ("permission", ("ask-first", "restricted", "yolo")),
            ("thinking", ("off", "low", "medium", "high")),
        ):
            if config == name:
                if value not in allowed:
                    return "%s is not one of %s" % (value, ", ".join(allowed))
                state[name] = value
                return None
        if config == "model":
            if not isinstance(value, str) or ":" not in value:
                return "%r is not a model this build knows" % (value,)
            state["model"] = value
            return None
        return "there is no %r option" % (config,)

    def turn_usage(self, session_id):
        """`usage` + the monotonic spend on `_meta`, for a finished turn.

        Accumulated per session rather than fixed, so a second turn in the
        same thread reports a bigger total — a client that showed the turn's
        cost as the session's would look identical on the first turn and only
        on the first.
        """
        state = self.session(session_id)
        state["turns"] = state.get("turns", 0) + 1
        turn = state["turns"]
        usage = {
            "inputTokens": 40_120 * turn,
            "outputTokens": 3_311 * turn,
            "totalTokens": 98_219 * turn,
            "cachedReadTokens": 54_200 * turn,
            "cachedWriteTokens": 588 * turn,
        }
        return {"usage": usage,
                "_meta": {"spettro.app/tokensUsed": 91_772 * turn}}

    # -- outbound ------------------------------------------------------------

    def update(self, session_id, update):
        send({
            "jsonrpc": "2.0",
            "method": "session/update",
            "params": {"sessionId": session_id, "update": update},
        })

    def chunk(self, session_id, kind, text):
        if self.cancelled:
            raise Cancelled()
        update = {"sessionUpdate": kind, "content": {"type": "text", "text": text}}
        # Remembered so `session/load` has something to replay. Only the
        # message-shaped updates: a plan or a tool call belongs to the turn
        # that produced it, and replaying a permission request would ask the
        # user to allow a write that happened days ago.
        if kind in ("user_message_chunk", "agent_message_chunk"):
            self.session(session_id)["history"].append(update)
        self.update(session_id, update)
        time.sleep(CHUNK_PAUSE_SECONDS)

    def plan(self, session_id, *entries):
        # The whole plan every time — the protocol's rule is that the client
        # replaces it, so a partial send would erase the rest.
        self.update(session_id, {
            "sessionUpdate": "plan",
            "entries": [
                {"content": content, "priority": "medium", "status": status}
                for content, status in entries
            ],
        })

    def request(self, method, params):
        """Send a request and block until its response arrives.

        Anything else that turns up while waiting is handled in place: a
        session/cancel flips the flag (and the wait keeps going — the client
        still owes an answer, by the spec a `cancelled` outcome), and an
        unexpected request gets method-not-found rather than a hang on the
        other side. Returns the "result", or None for an error response —
        which for fs/read_text_file is the ordinary "no such file yet".
        """
        self.next_request += 1
        request_id = "conf-req-%d" % self.next_request
        send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
        while True:
            message = read_message()
            if message is None:
                sys.exit(0)
            if message.get("id") == request_id and "method" not in message:
                if "error" in message:
                    log("%s answered with an error: %s" % (method, message["error"]))
                    return None
                return message.get("result")
            self.handle_out_of_band(message)

    # Requests that are safe to answer while a turn is in flight — they read
    # or edit session bookkeeping and do not touch the running turn. Anything
    # that would *start* work (session/prompt) is not here: serving it
    # re-entrantly would run two turns in one agent.
    REENTRANT = (
        "session/list",
        "session/delete",
        "session/close",
        "session/load",
        "session/resume",
        "session/set_config_option",
        "logout",
    )

    def handle_out_of_band(self, message):
        method = message.get("method")
        if method == "session/cancel":
            log("cancel received")
            self.cancelled = True
        elif method == "$/cancel_request":
            pass
        elif method in self.REENTRANT and "id" in message:
            # **Served, not refused.** A client asks for the session list
            # whenever the user opens their history, which is exactly the
            # moment they are waiting on a permission prompt — so these
            # arrive while this agent is blocked inside `request()`. Refusing
            # them put "method not found: session/list" in the panel's
            # history view, which is a conformance bug on this side and would
            # be one in any agent that made it.
            self.handle(message)
        elif "id" in message and method is not None:
            # A request this agent does not serve. Answer, or the client's
            # dispatch waits on it for ever.
            send({
                "jsonrpc": "2.0",
                "id": message["id"],
                "error": {"code": -32601, "message": "method not found: " + method},
            })
        # Stray responses to ids we no longer hold are dropped.

    # -- inbound -------------------------------------------------------------

    def handle(self, message):
        method = message.get("method")
        if method is None or "id" not in message:
            self.handle_out_of_band(message)
            return
        request_id = message["id"]
        params = message.get("params") or {}

        if method == "initialize":
            self.client_capabilities = params.get("clientCapabilities") or {}
            log("client capabilities: " + json.dumps(self.client_capabilities))
            # The extension handshake, read from the **top-level** `_meta` —
            # not from `clientCapabilities._meta`, which is where a client
            # that used the SDK's own helper would have put it, and which is
            # the mistake this agent exists to catch. Spettro reads exactly
            # this path and nothing else, and a client that gets it wrong is
            # silently walked through forms one question at a time instead.
            extensions = ((params.get("_meta") or {})
                          .get("spettro.app/extensions") or {})
            self.client_extensions = [
                method_name for method_name in (extensions.get("methods") or [])
                if isinstance(method_name, str)
            ]
            log("client extension methods: " + json.dumps(self.client_extensions))
            send({
                "jsonrpc": "2.0",
                "id": request_id,
                "result": {
                    # The other half of the handshake, on the *response's*
                    # `_meta`: what this agent serves, and what it heard the
                    # client say it serves. A client gates its whole extended
                    # surface on this object being present.
                    "_meta": {
                        "spettro.app/extensions": {
                            "version": 4,
                            "methods": ["_spettro/account/status", "_spettro/models/list"],
                            "clientMethods": self.client_extensions,
                        },
                    },
                    "protocolVersion": 1,
                    # The whole session lifecycle, so a client that implements
                    # only `session/new` has somewhere to be caught out. Each
                    # of these is "present means yes"; `{}` is the
                    # affirmative.
                    "agentCapabilities": {
                        "loadSession": True,
                        "sessionCapabilities": {
                            "list": {}, "delete": {}, "close": {}, "resume": {},
                        },
                        "auth": {"logout": {}},
                    },
                    "authMethods": [],
                    "agentInfo": {"name": "conformance-agent", "version": "1.0.0"},
                },
            })
        elif method == "session/new":
            self.sessions += 1
            session_id = "conf-%d" % self.sessions
            state = self.session(session_id)
            state["cwd"] = params.get("cwd") or os.getcwd()
            # The client's `context_servers`, as the protocol's `mcpServers`:
            # a stdio entry is `{name, command, args, env: [{name, value}]}`,
            # an HTTP one is tagged `"type": "http"`. Nothing is started —
            # this agent has no MCP client — but the names are remembered and
            # advertised as a slash command below, so the host test can see
            # the list arrived in the shape the schema demands.
            state["mcp"] = [
                "%s%s" % (server.get("name", "?"),
                          " (http)" if server.get("type") == "http" else "")
                for server in params.get("mcpServers", [])
            ]
            log("session %s in %s, mcp servers: %s"
                % (session_id, state["cwd"], state["mcp"] or "none"))
            send({"jsonrpc": "2.0", "id": request_id,
                  "result": {"sessionId": session_id,
                             "configOptions": self.config_options(session_id)}})
            # Slash commands arrive as an update, the way live agents send
            # them (the panel's / popup completes from these). /run is offered
            # only to a client that can run it — that is what the terminal
            # capability means, and offering it anyway would hide a client
            # that never implemented `terminal/*`.
            commands = [
                {"name": "plan", "description": "Show a plan and stop"},
                {"name": "echo",
                 "description": "Repeat the text back",
                 "input": {"hint": "text to repeat"}},
            ]
            if self.can("terminal"):
                commands.append({
                    "name": "run",
                    "description": "Run a shell command in a terminal",
                    "input": {"hint": "shell command"},
                })
            if self.can("elicitation", "form"):
                commands.append({"name": "ask", "description": "Ask a form question"})
                commands.append({"name": "withdraw",
                                 "description": "Ask, then take the question back"})
            if self.can("elicitation", "url"):
                commands.append({"name": "login", "description": "Ask you to visit a URL"})
            if "_spettro/question/ask" in self.client_extensions:
                commands.append({"name": "question",
                                 "description": "Ask a whole form in one request"})
            if OPTIONS.spettro:
                commands.extend({"name": name, "description": description}
                                for name, description in SCENARIO_COMMANDS)
            if state["mcp"]:
                commands.append({"name": "mcp",
                                 "description": "Context servers: " + ", ".join(state["mcp"])})
            self.update(session_id, {
                "sessionUpdate": "available_commands_update",
                "availableCommands": commands,
            })
        elif method == "session/set_config_option":
            session_id = params.get("sessionId")
            state = self.session(session_id)
            config = params.get("configId")
            # A select's value id arrives as a bare string; a boolean arrives
            # tagged ({"type": "boolean", "value": …}). Take both.
            value = params.get("value")
            if isinstance(value, dict):
                value = value.get("value")
            if OPTIONS.spettro:
                refusal = self.apply_spettro_config(state, config, value)
                if refusal is not None:
                    # **A refusal is an ERROR RESPONSE, not a quiet no-op.**
                    # The engine turns it into the session's `notice` (acp.rs
                    # `acp_set_config_option`), which is the only way the user
                    # ever learns the change did not take; answering with the
                    # unchanged options instead would roll the chip back with
                    # no sentence anywhere, which is the failure mode the
                    # three-state Ultra chip exists to avoid.
                    send({"jsonrpc": "2.0", "id": request_id,
                          "error": {"code": -32602, "message": refusal}})
                    return
            elif config == "model" and value in ("conf-one", "conf-two"):
                state["model"] = value
            elif config == "verbose":
                state["verbose"] = bool(value)
            send({"jsonrpc": "2.0", "id": request_id,
                  "result": {"configOptions": self.config_options(session_id)}})
        elif method == "session/list":
            listed = [
                {"sessionId": sid,
                 "cwd": state["cwd"],
                 "title": state["title"] or "Conformance conversation",
                 "updatedAt": "2026-08-19T00:00:00Z"}
                for sid, state in sorted(self.state.items())
                if not state["closed"]
            ]
            send({"jsonrpc": "2.0", "id": request_id, "result": {"sessions": listed}})
        elif method == "session/delete":
            self.state.pop(params.get("sessionId"), None)
            send({"jsonrpc": "2.0", "id": request_id, "result": {}})
        elif method == "session/close":
            state = self.state.get(params.get("sessionId"))
            if state is not None:
                state["closed"] = True
            send({"jsonrpc": "2.0", "id": request_id, "result": {}})
        elif method == "logout":
            log("logged out")
            send({"jsonrpc": "2.0", "id": request_id, "result": {}})
        elif method in ("session/load", "session/resume"):
            session_id = params.get("sessionId")
            state = self.state.get(session_id)
            if state is None:
                send({"jsonrpc": "2.0", "id": request_id,
                      "error": {"code": -32602, "message": "no such session"}})
                return
            state["closed"] = False
            # `session/load` replays the conversation as ordinary updates
            # *before* it answers; `session/resume` does not. That is the
            # whole difference between them, and a client that treats them
            # alike would show an empty transcript for one of the two.
            if method == "session/load":
                for update in state["history"]:
                    self.update(session_id, update)
            send({"jsonrpc": "2.0", "id": request_id,
                  "result": {"configOptions": self.config_options(session_id)}})
        elif method == "session/prompt":
            stop_reason = self.run_turn(params)
            result = {"stopReason": stop_reason}
            if OPTIONS.spettro:
                # THE TURN'S ACCOUNTING, which is a different number from the
                # gauge: `usage_update` reports context OCCUPANCY and may fall
                # after a compaction, while this is what the turn SPENT and
                # never does. The engine reads both (W-08) and the panel shows
                # them in different places, so an agent that sent one number
                # twice would make a client that confuses them look right.
                result.update(self.turn_usage(params.get("sessionId")))
            send({"jsonrpc": "2.0", "id": request_id, "result": result})
        else:
            self.handle_out_of_band(message)

    # -- the turn ------------------------------------------------------------

    def run_turn(self, params):
        self.cancelled = False
        # Every reply is stamped with the session the prompt came in on —
        # never a remembered one, or a second thread's turn would land in the
        # first thread's transcript.
        session_id = params.get("sessionId")
        state = self.session(session_id)
        blocks = params.get("prompt", [])
        prompt = " ".join(
            block.get("text", "")
            for block in blocks
            if block.get("type") == "text"
        ).strip()
        # Mentions arrive as resource blocks beside the text: embedded file
        # text, or a link. Named back, so the panel's @ flow is visible
        # end to end.
        context = []
        for block in blocks:
            if block.get("type") == "resource":
                uri = (block.get("resource") or {}).get("uri", "")
                context.append(os.path.basename(uri) + " (embedded)")
            elif block.get("type") == "resource_link":
                context.append(block.get("name", "?") + " (link)")
        try:
            if context:
                self.chunk(session_id, "agent_message_chunk",
                           "Context received: %s. " % ", ".join(context))
            if prompt.startswith("/echo"):
                self.chunk(session_id, "agent_message_chunk",
                           "Echo [%s]: %s" % (state["model"], prompt[5:].strip()))
                return "end_turn"
            if prompt.startswith("/run"):
                return self.run_command(session_id, prompt[4:].strip())
            if prompt.startswith("/ask"):
                return self.ask_form(session_id)
            if prompt.startswith("/withdraw"):
                return self.withdraw_question(session_id)
            if prompt.startswith("/login"):
                return self.ask_url(session_id)
            if prompt.startswith("/question"):
                return self.ask_question(session_id)
            scenario = self.scenario_for(prompt)
            if scenario is not None:
                if scenario == "form" and "_spettro/question/ask" not in self.client_extensions:
                    # Never ask on a channel the client did not claim: the
                    # request would park for ever and look like a hung agent
                    # rather than like a missing capability.
                    self.chunk(session_id, "agent_message_chunk",
                               "This editor serves no question form; try /walk.")
                    return "end_turn"
                if scenario == "config":
                    CONFIG_SNAPSHOT[0] = self.config_options(session_id)
                log("scenario %s starting on %s" % (scenario, session_id))
                return self.run_scenario(scenario, session_id)
            if prompt.startswith("/plan"):
                self.plan(session_id,
                          ("Look around", "in_progress"), ("Report back", "pending"))
                self.chunk(session_id, "agent_message_chunk",
                           "That is the plan; say more when ready.")
                self.plan(session_id,
                          ("Look around", "completed"), ("Report back", "completed"))
                return "end_turn"
        except Cancelled:
            log("turn cancelled")
            return "cancelled"
        target = self.pick_target(prompt)
        path = os.path.join(state["cwd"], target)
        try:
            self.chunk(session_id, "agent_thought_chunk",
                       "The user wants an edit; %s is the file to touch." % target)
            self.chunk(session_id, "agent_message_chunk",
                       "I'll make a small edit to `%s`. " % target)
            self.plan(session_id,
                      ("Read the file", "in_progress"), ("Write the change", "pending"))

            self.tool_calls += 1
            tool_id = "t-%d" % self.tool_calls
            self.update(session_id, {
                "sessionUpdate": "tool_call",
                "toolCallId": tool_id,
                "title": "Edit " + target,
                "kind": "edit",
                "status": "pending",
            })
            outcome = self.request("session/request_permission", {
                "sessionId": session_id,
                "toolCall": {
                    "toolCallId": tool_id,
                    "title": "Edit " + target,
                    "kind": "edit",
                    "status": "pending",
                },
                "options": [
                    {"optionId": "allow", "name": "Allow", "kind": "allow_once"},
                    {"optionId": "reject", "name": "Reject", "kind": "reject_once"},
                ],
            })
            decision = (outcome or {}).get("outcome") or {}
            if self.cancelled or decision.get("outcome") == "cancelled":
                raise Cancelled()
            if decision.get("optionId") != "allow":
                self.plan(session_id,
                          ("Read the file", "completed"),
                          ("Write the change", "completed"))
                self.chunk(session_id, "agent_message_chunk", "Understood — leaving %s alone." % target)
                return "end_turn"

            self.update(session_id, {"sessionUpdate": "tool_call_update",
                                     "toolCallId": tool_id,
                                     "status": "in_progress"})
            read = self.request("fs/read_text_file", {"sessionId": session_id, "path": path})
            old_text = None if read is None else read.get("content", "")
            # A cancel that arrived while the read was in flight stops the
            # turn *here* — a cancelled turn must not write.
            if self.cancelled:
                raise Cancelled()
            self.plan(session_id,
                      ("Read the file", "completed"),
                      ("Write the change", "in_progress"))

            new_text = (old_text or "") + "Edited by the conformance agent: %s\n" % prompt
            wrote = self.request("fs/write_text_file", {
                "sessionId": session_id, "path": path, "content": new_text,
            })
            if wrote is None:
                self.update(session_id, {"sessionUpdate": "tool_call_update",
                                         "toolCallId": tool_id,
                                         "status": "failed"})
                self.chunk(session_id, "agent_message_chunk", "The editor refused that write.")
                return "end_turn"

            diff = {"type": "diff", "path": path, "newText": new_text}
            if old_text is not None:
                diff["oldText"] = old_text
            self.update(session_id, {
                "sessionUpdate": "tool_call_update",
                "toolCallId": tool_id,
                "status": "completed",
                "content": [diff],
            })
            self.plan(session_id,
                          ("Read the file", "completed"),
                          ("Write the change", "completed"))
            self.chunk(session_id, "agent_message_chunk", "Done — `%s` updated." % target)
            return "end_turn"
        except Cancelled:
            log("turn cancelled")
            return "cancelled"

    # -- scenarios -----------------------------------------------------------

    def hold(self, label):
        """Sit still, loudly.

        A screenshot is only reproducible if the frame it wants is stationary
        for longer than the shell takes to draw it, and a driver that guesses
        the moment is exactly the timing luck this file exists to remove. The
        stderr line reaches logcat through the engine, so a script can wait
        for `HOLD workflow:mid` and shoot inside the window it announces.
        """
        log("HOLD %s (%ds)" % (label, int(OPTIONS.hold)))
        waited = 0.0
        while waited < OPTIONS.hold:
            if self.cancelled:
                raise Cancelled()
            time.sleep(min(0.25, OPTIONS.hold - waited))
            waited += 0.25

    def run_scenario(self, name, session_id):
        """Drive one scenario to the end, or until the turn is cancelled.

        The scenario decides *what*; this decides *when* — which is what keeps
        cancellation honest (checked between every pair of steps, never inside
        one) and what lets the same generator be dumped with no client at all.
        """
        steps = SCENARIOS[name](session_id)
        reply = None
        while True:
            try:
                step = steps.send(reply)
            except StopIteration:
                return "end_turn"
            reply = None
            if self.cancelled:
                steps.close()
                raise Cancelled()
            kind = step[0]
            if kind == "send":
                send(step[1])
            elif kind == "wait":
                OPTIONS.pause(step[1])
            elif kind == "hold":
                self.hold(step[1])
            elif kind == "chunk":
                self.chunk(session_id, step[1], step[2])
            elif kind == "request":
                reply = self.request(step[1], step[2])
            else:
                log("unknown scenario step: %r" % (kind,))

    def scenario_for(self, prompt):
        """Which scenario a prompt asks for, or None.

        A bare `--scenario` default answers anything unrecognised, so a device
        can be driven with `adb shell input text go` — typing a slash on a
        soft keyboard opens the command palette over the composer, which is
        the wrong thing to fight while taking screenshots.
        """
        if not OPTIONS.spettro:
            return None
        word = prompt[1:].split(" ", 1)[0] if prompt.startswith("/") else ""
        if word in SCENARIOS:
            return word
        if prompt.startswith("/"):
            return None
        return OPTIONS.default_scenario

    def withdraw_question(self, session_id):
        """Ask something and immediately take it back with `$/cancel_request`.

        A client that parks the request and never watches for the withdrawal
        leaves the card on screen for ever, and whatever the user then answers
        goes to a request that is no longer live.
        """
        if not self.can("elicitation", "form"):
            self.chunk(session_id, "agent_message_chunk", "This editor cannot show forms.")
            return "end_turn"
        self.next_request += 1
        request_id = "conf-req-%d" % self.next_request
        send({"jsonrpc": "2.0", "id": request_id, "method": "elicitation/create", "params": {
            "mode": "form",
            "message": "Changed my mind about this one.",
            "sessionId": session_id,
            "requestedSchema": {
                "type": "object",
                "properties": {"never": {"type": "string", "title": "Never mind"}},
            },
        }})
        time.sleep(CHUNK_PAUSE_SECONDS)
        # The field is `requestId`, not `id`: this is a notification whose
        # payload names the request, not a request of its own.
        send({"jsonrpc": "2.0", "method": "$/cancel_request",
              "params": {"requestId": request_id}})
        # The client may answer the cancelled request with an error; either
        # way it is not our question any more, so stop waiting for it.
        self.chunk(session_id, "agent_message_chunk", "Never mind, question withdrawn.")
        return "end_turn"

    def ask_form(self, session_id):
        """A form elicitation, covering every property kind the schema has.

        One of each so a client that gets any of them wrong shows it: a
        required free-text field, a titled single-select, an integer with
        bounds, a boolean, and a multi-select. The reply names what came back
        *and its Python type*, because the type is the half that silently
        rots — an integer field answered with the string "3" looks identical
        in a transcript.
        """
        if not self.can("elicitation", "form"):
            self.chunk(session_id, "agent_message_chunk", "This editor cannot show forms.")
            return "end_turn"
        answer = self.request("elicitation/create", {
            # The scope is *flattened* into the request beside the mode —
            # `{"mode":"form","sessionId":…}` — not nested under a "scope"
            # key. The schema flattens both `mode` and `scope`, and an
            # untagged scope means a nested one simply fails to match.
            "mode": "form",
            "message": "The conformance agent would like some details.",
            "sessionId": session_id,
            "requestedSchema": {
                "type": "object",
                "title": "Conformance form",
                "required": ["note"],
                "properties": {
                    "note": {"type": "string", "title": "A note",
                             "description": "Anything at all."},
                    "branch": {"type": "string", "title": "Branch",
                               "oneOf": [
                                   {"const": "main", "title": "main"},
                                   {"const": "dev", "title": "development"},
                               ]},
                    "depth": {"type": "integer", "title": "Depth",
                              "minimum": 1, "maximum": 9, "default": 3},
                    "dry": {"type": "boolean", "title": "Dry run", "default": True},
                    "tags": {"type": "array", "title": "Tags",
                             "items": {"type": "string", "enum": ["a", "b", "c"]}},
                },
            },
        })
        if answer is None:
            self.chunk(session_id, "agent_message_chunk", "The editor refused the question.")
            return "end_turn"
        action = answer.get("action")
        if action != "accept":
            self.chunk(session_id, "agent_message_chunk", "Fair enough — %s." % action)
            return "end_turn"
        content = answer.get("content") or {}
        described = ", ".join(
            "%s=%r (%s)" % (key, value, type(value).__name__)
            for key, value in sorted(content.items())
        )
        self.chunk(session_id, "agent_message_chunk", "Form answered: %s" % described)
        return "end_turn"

    def ask_question(self, session_id):
        """The extension's ask-user form: every question in one request.

        Offered only to a client that named `_spettro/question/ask` in the
        top-level `_meta` of `initialize` — which is the whole point. A client
        that puts that key anywhere else never sees this command, and gets
        walked through the same questions one permission prompt at a time
        without any error to say why.
        """
        if "_spettro/question/ask" not in self.client_extensions:
            self.chunk(session_id, "agent_message_chunk", "This editor has no question form.")
            return "end_turn"
        answer = self.request("_spettro/question/ask", {
            "version": 4,
            "sessionId": session_id,
            "question": "How should the conformance agent proceed?",
            "context": "Asked as one form rather than as a walk.",
            "allowCustomInput": True,
            "questions": [
                {"id": "branch", "question": "Which branch?",
                 "options": [{"id": "main", "label": "main"},
                             {"id": "dev", "label": "development"}]},
                {"id": "note", "question": "Anything to add?", "options": []},
            ],
        })
        if answer is None:
            self.chunk(session_id, "agent_message_chunk", "The editor refused the question.")
            return "end_turn"
        if answer.get("kind") in ("declined", "cancelled"):
            self.chunk(session_id, "agent_message_chunk",
                       "Fair enough — %s." % answer.get("kind"))
            return "end_turn"
        described = ", ".join(describe_answer(item) for item in answer.get("answers") or [])
        self.chunk(session_id, "agent_message_chunk", "Question answered: %s" % described)
        return "end_turn"

    def ask_url(self, session_id):
        """A URL elicitation: the sign-in-and-come-back shape.

        The client answers as soon as the user says they have been; the card
        stays until this agent — which is the only one that can see whether
        the sign-in actually happened — sends `elicitation/complete` naming
        the same id.
        """
        if not self.can("elicitation", "url"):
            self.chunk(session_id, "agent_message_chunk", "This editor cannot show URL prompts.")
            return "end_turn"
        elicitation_id = "conf-login-%d" % self.next_request
        answer = self.request("elicitation/create", {
            "mode": "url",
            "message": "Sign in to the conformance service, then come back.",
            "sessionId": session_id,
            "elicitationId": elicitation_id,
            "url": "https://example.com/conformance/login",
        })
        action = (answer or {}).get("action")
        if action != "accept":
            self.chunk(session_id, "agent_message_chunk", "No sign-in then — %s." % action)
            return "end_turn"
        send({
            "jsonrpc": "2.0",
            "method": "elicitation/complete",
            "params": {"elicitationId": elicitation_id},
        })
        self.chunk(session_id, "agent_message_chunk", "Signed in; carrying on.")
        return "end_turn"

    def run_command(self, session_id, shell):
        """The `terminal/*` round trip, in the order an agent really uses it.

        create -> show the terminal on a tool call -> wait_for_exit -> output
        -> release. The tool call carries `{"type": "terminal", "terminalId"}`
        content, which is the only way the client learns *which* terminal
        belongs to *which* call — the terminal methods themselves say nothing
        about tool calls.
        """
        if not self.can("terminal"):
            self.chunk(session_id, "agent_message_chunk",
                       "This editor has no terminal capability.")
            return "end_turn"
        shell = shell or "echo hello from the conformance agent"
        created = self.request("terminal/create", {
            "sessionId": session_id,
            "command": "/bin/sh",
            "args": ["-c", shell],
            "outputByteLimit": 16 * 1024,
        })
        if created is None:
            self.chunk(session_id, "agent_message_chunk", "The editor refused a terminal.")
            return "end_turn"
        terminal_id = created["terminalId"]

        self.tool_calls += 1
        tool_id = "t-%d" % self.tool_calls
        self.update(session_id, {
            "sessionUpdate": "tool_call",
            "toolCallId": tool_id,
            "title": "$ " + shell,
            "kind": "execute",
            "status": "in_progress",
            "content": [{"type": "terminal", "terminalId": terminal_id}],
        })

        # `terminal/wait_for_exit` answers with the exit status *flattened*
        # into the result — {"exitCode": 3} — while `terminal/output` nests
        # the same object under "exitStatus". The asymmetry is the schema's
        # (WaitForTerminalExitResponse flattens, TerminalOutputResponse does
        # not), and reading the wrong one gives a silent None.
        status = self.request("terminal/wait_for_exit",
                              {"sessionId": session_id, "terminalId": terminal_id}) or {}
        read = self.request("terminal/output",
                            {"sessionId": session_id, "terminalId": terminal_id}) or {}
        self.update(session_id, {
            "sessionUpdate": "tool_call_update",
            "toolCallId": tool_id,
            "status": "completed" if status.get("exitCode") == 0 else "failed",
        })
        summary = "exit code %s" % status.get("exitCode")
        if status.get("signal"):
            summary = "killed by %s" % status["signal"]
        self.chunk(session_id, "agent_message_chunk",
                   "Ran `%s` (%s), output:\n\n```\n%s```\n"
                   % (shell, summary, read.get("output", "")))
        # Release last: the output has been read, and a terminal nobody
        # released is a process nobody stopped.
        self.request("terminal/release", {"sessionId": session_id, "terminalId": terminal_id})
        return "end_turn"

    @staticmethod
    def pick_target(prompt):
        """The last dotted token of the prompt names the file; everything that
        could climb out of the project is cut down to a basename. The engine
        refuses escapes anyway (its resolves_inside guard), but a conformance
        agent should not be the thing probing it."""
        target = DEFAULT_TARGET
        for token in prompt.split():
            token = token.strip("\"'`.,;:!?")
            name = os.path.basename(token)
            if "." in name and name not in (".", "..") and not name.startswith("."):
                target = name
        return target


# ---------------------------------------------------------------------------
# The Spettro scenarios (--spettro)
# ---------------------------------------------------------------------------
#
# A scenario is a GENERATOR of steps, never a thread and never a sleep loop
# hidden inside a handler. Three reasons, all learned the hard way:
#
#   * it can be *dumped* without a client (`--dump workflow`), which is how
#     the Kotlin fold test gets fixtures that cannot drift from what the
#     device actually receives;
#   * it can be paced from argv, so the same script produces a burst for a
#     test and a watchable ramp for a screenshot;
#   * cancellation is honoured between any two steps, because the runner —
#     not the scenario — decides whether to take the next one.
#
# Steps:
#   ("send", message)        one JSON-RPC message, verbatim
#   ("wait", seconds)        a pause of its own length (None = --pace)
#   ("hold", label)          sit still for --hold, announced on stderr
#   ("chunk", kind, text)    an ordinary message/thought chunk
#   ("request", method, p)   a round trip; the result is sent back in

def update_message(session_id, payload):
    """One `session/update`, as a message. Built here rather than in
    `Agent.update` so a scenario can be dumped with no connection at all."""
    return {
        "jsonrpc": "2.0",
        "method": "session/update",
        "params": {"sessionId": session_id, "update": payload},
    }


def tool_call(session_id, tool_id, title, kind, status, raw_input=None, text=None):
    """An OPENING tool call.

    `rawInput` here is the one the client keeps for ever (W-09): the engine
    stores the first non-null it sees as `rawInputOpen` and the orchestration
    fold classifies on nothing else. Everything that decides what this call
    *is* — `workflow`, `agent`, `run_id`, `phase`, `items` — has to be in
    this object and not merely in the title, because the CLI truncates a
    title's inline JSON at 120 characters and the client is right not to
    parse it.
    """
    payload = {
        "sessionUpdate": "tool_call",
        "toolCallId": tool_id,
        "title": title,
        "kind": kind,
        "status": status,
    }
    if raw_input is not None:
        payload["rawInput"] = raw_input
    if text is not None:
        payload["content"] = [{"type": "content",
                               "content": {"type": "text", "text": text}}]
    return update_message(session_id, payload)


def tool_update(session_id, tool_id, status=None, title=None, raw_input=None, text=None):
    """An update to a call already on screen.

    `content` REPLACES rather than appends (acp_thread.rs `apply_tool_fields`),
    which is what lets a run's rendered tree be rewritten in place — and is
    why the client reads the FIRST markdown block and still sees the newest
    tree.
    """
    payload = {"sessionUpdate": "tool_call_update", "toolCallId": tool_id}
    if status is not None:
        payload["status"] = status
    if title is not None:
        payload["title"] = title
    if raw_input is not None:
        payload["rawInput"] = raw_input
    if text is not None:
        payload["content"] = [{"type": "content",
                               "content": {"type": "text", "text": text}}]
    return update_message(session_id, payload)


def usage_message(session_id, used, size, tokens_used):
    """`usage_update`, with the monotonic spend on `_meta` (W-08).

    `used` is OCCUPANCY and is allowed to fall; `tokensUsed` is the spend and
    never does. Sending the same number for both — the obvious shortcut —
    would make a client that confuses them look right.
    """
    return update_message(session_id, {
        "sessionUpdate": "usage_update",
        "used": used,
        "size": size,
        "_meta": {"spettro.app/tokensUsed": tokens_used},
    })


def plan_message(session_id, entries):
    """A whole plan. Always whole: the client replaces, so a partial send
    deletes the rest (W-12)."""
    return update_message(session_id, {
        "sessionUpdate": "plan",
        "entries": [{"content": content, "priority": priority, "status": status}
                    for content, priority, status in entries],
    })


# --- the workflow run ------------------------------------------------------

WORKFLOW_RUN_ID = "run-7"
WORKFLOW_NAME = "review-changes"
WORKFLOW_DESCRIPTION = "Review the diff, then refute each finding"
WORKFLOW_PHASES = [
    ("Review", "one agent per dimension"),
    ("Verify", "refute each finding"),
    ("Report", "write it up"),
]

# Seven sub-agents over three phases, with the two failure shapes that look
# identical on a card and arrive completely differently on the wire:
# `review#3` fails its tool call, `verify#3` returns a successful call whose
# *output* says `{"status":"error"}`. A client that only reads the call status
# draws six greens and one red for a run that lost two agents.
WORKFLOW_MEMBERS = [
    {"id": "call-1", "spec": "review", "index": 1, "phase": "Review",
     "task": "vet the changed packages", "child": "bash go vet ./...",
     "child_kind": "execute", "fail": False,
     "result": {"status": "ok", "summary": "no findings in 12 packages"}},
    {"id": "call-2", "spec": "review", "index": 2, "phase": "Review",
     "task": "read the ACP bridge", "child": "read internal/acp/bridge.go",
     "child_kind": "read", "fail": False,
     "result": {"status": "ok", "summary": "two unchecked errors on the write path"}},
    {"id": "call-3", "spec": "review", "index": 3, "phase": "Review",
     "task": "run the test suite", "child": "bash go test ./...",
     "child_kind": "execute", "fail": True,
     "result": {"status": "error",
                "summary": "exit 1: 2 tests failed in acp/bridge_test.go"}},
    {"id": "call-4", "spec": "verify", "index": 1, "phase": "Verify",
     "task": "refute the vet finding", "child": "read internal/acp/write.go",
     "child_kind": "read", "fail": False,
     "result": {"status": "ok", "summary": "refuted: the errors are checked one frame up"}},
    {"id": "call-5", "spec": "verify", "index": 2, "phase": "Verify",
     "task": "refute the bridge finding", "child": None, "cached": True,
     "child_kind": "read", "fail": False,
     "result": {"status": "ok", "summary": "restored from the resume journal"}},
    {"id": "call-6", "spec": "verify", "index": 3, "phase": "Verify",
     "task": "refute the test failure", "child": "bash go test ./internal/acp/",
     "child_kind": "execute", "fail": False,
     # A completed call whose output reports an error. The member failed; the
     # call did not.
     "result": {"status": "error",
                "summary": "could not refute: the test is genuinely broken"}},
    {"id": "call-7", "spec": "report", "index": 1, "phase": "Report",
     "task": "write it up", "child": "edit REVIEW.md",
     "child_kind": "edit", "fail": False,
     "result": {"status": "ok", "summary": "REVIEW.md written, 2 findings kept"}},
]

WORKFLOW_LOGS = [
    "journal replayed 1 entry",
    "phase Review complete: 1 done, 1 failed",
    "verify#2 restored from the journal",
    "phase Verify complete: 2 done, 1 failed",
]


def member_instance(member):
    return "%s#%d" % (member["spec"], member["index"])


def member_open_args(member):
    """A workflow member's opening arguments.

    `run_id` + `phase` + `index` + `cached` is the whole of what makes this a
    member of *that* run under *that* phase — the fold attaches by `run_id`
    alone, so an omitted one silently promotes the sub-agent to a card of its
    own beside the run it belongs to.
    """
    return {
        "agent": member["spec"],
        "index": member["index"],
        "phase": member["phase"],
        "cached": bool(member.get("cached")),
        "run_id": WORKFLOW_RUN_ID,
        "task": member["task"],
    }


class WorkflowState:
    """Who has landed, for the rendered tree.

    The tree is not decoration: `log()` lines have **no structured channel at
    all** and are mined back out of exactly this text
    (`SpettroOrchestration.parseRenderedWorkflow`), and the phase list is
    mined from it too when a client somehow missed the opening `rawInput`.
    Building it from live state rather than pasting a fixed string is what
    keeps the mined tree agreeing with the tool calls beside it.
    """

    def __init__(self):
        self.status = {}          # instance -> pending|running|done|failed
        self.detail = {}          # instance -> the row's right-hand text
        self.logs = []

    def set(self, member, status, detail=None):
        instance = member_instance(member)
        self.status[instance] = status
        self.detail[instance] = detail or member["task"]

    def render(self):
        lines = ["%s · saved workflow" % WORKFLOW_NAME, WORKFLOW_DESCRIPTION, ""]
        if self.logs:
            # `log:` then continuation lines indented — the client stops at the
            # first line back at the left margin, so the indent is load-bearing.
            lines.append("log: “%s”" % self.logs[0])
            for entry in self.logs[1:]:
                lines.append("     “%s”" % entry)
            lines.append("")
        for title, detail in WORKFLOW_PHASES:
            members = [m for m in WORKFLOW_MEMBERS if m["phase"] == title]
            landed = [m for m in members if member_instance(m) in self.status]
            # A phase nothing has landed in yet is hollow; the client draws it
            # pending from the DECLARED list either way, and the two must not
            # disagree.
            glyph = "▸" if landed else "○"
            lines.append("%s %-12s %s" % (glyph, title, detail))
            for member in landed:
                instance = member_instance(member)
                mark = {"done": "✓", "running": "▶",
                        "failed": "✗"}[self.status[instance]]
                lines.append("   %s %-11s %s" % (mark, instance, self.detail[instance]))
        return "\n".join(lines) + "\n"


def workflow_scenario(session_id):
    """A whole run: declared at t=0, filled in over time, rewritten in place.

    The three holds are the three frames worth a screenshot — every phase
    pending, the middle of Review with a live agent beside a failed one, and
    the settled form after the finish update has overwritten `rawInput`.
    """
    state = WorkflowState()
    state.logs.append(WORKFLOW_LOGS[0])
    yield ("chunk", "agent_thought_chunk",
           "Seven agents over three phases; Review can fan out, Verify cannot.")
    yield ("chunk", "agent_message_chunk",
           "Running the `review-changes` workflow.\n\n")

    open_args = {
        "workflow": WORKFLOW_NAME,
        "run_id": WORKFLOW_RUN_ID,
        "origin": "saved",
        "description": WORKFLOW_DESCRIPTION,
        # Objects rather than bare strings, so each phase carries the sentence
        # that says what it is for. The client accepts either; only this one
        # can fill the second line of a phase header.
        "phases": [{"name": name, "description": detail}
                   for name, detail in WORKFLOW_PHASES],
    }
    yield ("send", tool_call(session_id, "wf-1", "workflow " + WORKFLOW_NAME,
                             "other", "pending", open_args, state.render()))
    # Every phase declared and empty: the frame that proves a plan is visible
    # from t=0, which is the whole argument for declaring one.
    yield ("hold", "workflow:t0")
    yield ("send", tool_update(session_id, "wf-1", status="in_progress"))

    def land(member, status, detail=None):
        """Open a member, run its child, and leave it in `status`."""
        yield ("send", tool_call(
            session_id, member["id"],
            "agent %s: %s" % (member_instance(member), member["task"]),
            "think", "in_progress", member_open_args(member)))
        state.set(member, "running", member["task"])
        yield ("send", tool_update(session_id, "wf-1", text=state.render()))
        yield ("wait", None)
        if member["child"]:
            # A child is prefixed with the instance that made it — that
            # bracket is the only thing relating a tool call to a sub-agent,
            # and it is what the member row shows while it is still moving.
            yield ("send", tool_call(
                session_id, member["id"] + "-c",
                "[%s] %s" % (member_instance(member), member["child"]),
                member["child_kind"], "in_progress",
                {"command": member["child"]}))
            state.set(member, "running", member["child"])
            yield ("send", tool_update(session_id, "wf-1", text=state.render()))
            yield ("wait", None)
        if status == "running":
            return
        if member["child"]:
            yield ("send", tool_update(session_id, member["id"] + "-c",
                                       status="failed" if member["fail"] else "completed"))
        yield ("send", tool_update(
            session_id, member["id"],
            status="failed" if member["fail"] else "completed",
            text=json.dumps(member["result"])))
        # The TREE follows the member, not the call: `verify#3` returned a
        # successful call whose output reports an error, and the CLI's own
        # tree marks that ✗. A tree that said ✓ while the card said failed
        # would make the card look like the bug.
        settled = "failed" if member["result"].get("status") == "error" else status
        state.set(member, settled, member["result"]["summary"])
        yield ("send", tool_update(session_id, "wf-1", text=state.render()))

    by_id = {member["id"]: member for member in WORKFLOW_MEMBERS}
    for step in land(by_id["call-1"], "done"):
        yield step
    yield ("wait", None)
    # Left running on purpose: the mid-run frame needs one agent still moving,
    # showing its own latest child rather than its launch task.
    for step in land(by_id["call-2"], "running"):
        yield step
    yield ("wait", None)
    for step in land(by_id["call-3"], "failed"):
        yield step
    state.logs.append(WORKFLOW_LOGS[1])
    yield ("send", tool_update(session_id, "wf-1", text=state.render()))
    yield ("hold", "workflow:mid")

    for step in land(by_id["call-2"], "done"):
        yield step
    # A progress call: same tool name, `kind` phase, and it must be ABSORBED
    # rather than drawn — a row per phase transition buries the run it belongs
    # to. Emitted precisely because a client that draws it looks fine until a
    # run has ten phases.
    yield ("send", tool_call(session_id, "wf-phase-1", "workflow " + WORKFLOW_NAME,
                             "other", "completed",
                             {"workflow": WORKFLOW_NAME, "run_id": WORKFLOW_RUN_ID,
                              "kind": "phase", "phase": "Verify"}))
    yield ("wait", None)

    for member_id in ("call-4", "call-5", "call-6"):
        for step in land(by_id[member_id], "done"):
            yield step
        yield ("wait", None)
    state.logs.append(WORKFLOW_LOGS[2])
    state.logs.append(WORKFLOW_LOGS[3])
    # The other progress shape: a `log` call, likewise absorbed.
    yield ("send", tool_call(session_id, "wf-log-1", "workflow " + WORKFLOW_NAME,
                             "other", "completed",
                             {"workflow": WORKFLOW_NAME, "run_id": WORKFLOW_RUN_ID,
                              "kind": "log", "message": WORKFLOW_LOGS[3]}))
    for step in land(by_id["call-7"], "done"):
        yield step

    # THE FINISH UPDATE. `rawInput` is replaced wholesale by the run summary —
    # no `phases`, no `description`, no `origin` — which is exactly the rewrite
    # W-09 exists to survive. A client that classifies on the latest args turns
    # a finished workflow back into an anonymous tool call the moment it
    # succeeds; a client that keeps the opening args draws the settled card.
    yield ("send", tool_update(
        session_id, "wf-1", status="completed",
        raw_input={"run_id": WORKFLOW_RUN_ID, "workflow": WORKFLOW_NAME,
                   "agents": len(WORKFLOW_MEMBERS), "failed": 2, "cached": 1,
                   "tokens": 98219},
        text=state.render()))
    yield ("hold", "workflow:settled")
    yield ("chunk", "agent_message_chunk",
           "Run finished: 7 agents, 2 failed, 1 replayed. "
           "`review#3` could not be refuted — the test really is broken.")


# --- the Ultra swarm -------------------------------------------------------

SWARM_ITEMS = [
    "internal/acp/bridge.go", "internal/acp/session.go", "internal/agent/ultra.go",
    "internal/config/config.go", "internal/tui/view_swarm.go", "internal/tui/glow.go",
    "internal/workflow/run.go", "internal/workflow/phase.go", "internal/store/journal.go",
    "internal/store/keys.go", "internal/provider/anthropic.go", "internal/provider/local.go",
    "internal/cmd/root.go", "internal/cmd/acp.go", "internal/log/log.go",
    "internal/util/paths.go", "internal/util/text.go", "internal/mcp/client.go",
    "internal/mcp/stdio.go", "main.go",
]

# Ultra ramps: five at once, then one every 700 ms. The denominator the user
# was promised is `items`, never the number launched — a denominator that grew
# with the launches makes 7/20 read as 7/7 and a meter that runs backwards.
SWARM_FIRST_WAVE = 5
SWARM_HOLD_AT = 8


def swarm_member_args(index, item):
    """`swarm: true` and NO `run_id` — that pair is what separates a swarm
    member from a workflow member, and a stray `run_id` would post it to a
    run that does not exist."""
    return {"agent": "code", "index": index, "swarm": True, "cached": False,
            "task": item}


def swarm_scenario(session_id):
    yield ("chunk", "agent_message_chunk",
           "Ultra is on — fanning this across %d files.\n\n" % len(SWARM_ITEMS))
    yield ("send", tool_call(
        session_id, "ultra-1", "ultra swarm · code", "other", "in_progress",
        {"description": "Add doc comments to every exported symbol",
         "subagent_type": "code",
         # `worktree` is what puts the ⑂ pill on the card, and the pill is the
         # only warning that branches are being made and merged in the user's
         # own repository without being asked.
         "isolation": "worktree",
         "items": list(SWARM_ITEMS)}))

    launched = []

    def launch(index):
        item = SWARM_ITEMS[index - 1]
        yield ("send", tool_call(
            session_id, "sw-%d" % index, "agent code#%d: %s" % (index, item),
            "think", "in_progress", swarm_member_args(index, item)))
        yield ("send", tool_call(
            session_id, "sw-%d-c" % index, "[code#%d] edit %s" % (index, item),
            "edit", "in_progress", {"path": item}))
        launched.append(index)

    # The first wave arrives together, with no pause between: five members and
    # fifteen ghosts is the frame the ramp exists to produce.
    for index in range(1, SWARM_FIRST_WAVE + 1):
        for step in launch(index):
            yield step
    yield ("wait", None)
    for index in range(SWARM_FIRST_WAVE + 1, SWARM_HOLD_AT + 1):
        for step in launch(index):
            yield step
        yield ("wait", None)
    yield ("hold", "swarm:ramp")

    for index in range(SWARM_HOLD_AT + 1, len(SWARM_ITEMS) + 1):
        for step in launch(index):
            yield step
        yield ("wait", 0.15)

    def settle(index, status, result):
        yield ("send", tool_update(session_id, "sw-%d-c" % index,
                                   status="failed" if status == "failed" else "completed"))
        yield ("send", tool_update(session_id, "sw-%d" % index, status=status,
                                   text=result))
        yield ("wait", 0.15)

    for index in launched:
        item = SWARM_ITEMS[index - 1]
        if index == 4:
            # A provider rate limit: PLAIN TEXT, not a report. This is the
            # failure that matters most and the one that never arrives
            # structured — a client that only reads `summary` out of JSON
            # draws an empty red row for it.
            for step in settle(index, "failed", "429 after 3 attempts"):
                yield step
        elif index == 9:
            for step in settle(index, "completed", json.dumps(
                    {"status": "ok", "summary": "documented 4 symbols",
                     # The one outcome that leaves work behind in the user's
                     # repository, and is otherwise invisible.
                     "merge": "conflict"})):
                yield step
        else:
            for step in settle(index, "completed", json.dumps(
                    {"status": "ok", "summary": "documented %s" % item})):
                yield step

    yield ("send", tool_update(session_id, "ultra-1", status="completed"))
    yield ("hold", "swarm:settled")
    yield ("chunk", "agent_message_chunk",
           "19 of 20 files documented; `code#4` hit a rate limit and "
           "`code#9`'s branch is kept for you to resolve.")


# --- usage, several times inside one turn ----------------------------------

# Occupancy, then the compaction. The last pair is the point: `used` FALLS
# while `tokensUsed` keeps climbing, which is the difference between a gauge
# and a counter and is invisible in any single sample.
USAGE_STEPS = [
    (12_400, 12_400, "Reading the tree."),
    (34_500, 41_900, "Opening `internal/acp/bridge.go`."),
    (61_200, 78_600, "Following the write path."),
    (98_400, 131_500, "Reading the tests that cover it."),
    (152_900, 205_300, "Drafting the change."),
    (181_300, 268_100, "Nearly out of room; compacting."),
    (44_100, 312_400, "Compacted. Carrying on with the same thread."),
]
USAGE_WINDOW = 200_000


def usage_scenario(session_id):
    yield ("chunk", "agent_message_chunk", "Working through the bridge.\n\n")
    for index, (used, spent, note) in enumerate(USAGE_STEPS):
        yield ("send", usage_message(session_id, used, USAGE_WINDOW, spent))
        yield ("chunk", "agent_thought_chunk", note + " ")
        # Hold at 90% rather than only at the end. The warm and nearly-full
        # states each last one step at any honest pace, so without a stop here
        # the amber ring and the "nearly full" row exist for under a second
        # and can be neither photographed nor checked by eye.
        if index == len(USAGE_STEPS) - 2:
            yield ("hold", "usage:nearly-full")
        else:
            yield ("wait", None)
    yield ("hold", "usage:compacted")
    yield ("chunk", "agent_message_chunk",
           "\n\nThat is seven usage updates in one turn; the ring moved six "
           "times and fell once.")


# --- a plan with a dependency-blocked entry --------------------------------

def plan_scenario(session_id):
    """ACP has no blocked status, so Spettro says it in the text: a pending
    task whose dependencies are unmet gets the literal `" (blocked)"` appended
    to its content. The engine lifts the suffix into a flag (W-12); a client
    that passes it through renders a task called "Run the test suite
    (blocked)", which is a sentence pretending to be a status."""
    yield ("chunk", "agent_message_chunk", "Here is the plan.\n\n")
    yield ("send", plan_message(session_id, [
        ("Read the migration", "high", "in_progress"),
        ("Write the new schema", "high", "pending"),
        ("Run the test suite (blocked)", "medium", "pending"),
        ("Write the changelog", "low", "pending"),
    ]))
    yield ("wait", None)
    yield ("send", plan_message(session_id, [
        ("Read the migration", "high", "completed"),
        ("Write the new schema", "high", "in_progress"),
        ("Run the test suite (blocked)", "medium", "pending"),
        ("Write the changelog", "low", "pending"),
    ]))
    yield ("hold", "plan:blocked")
    # The dependency clears, so the suffix goes: the same task, no longer
    # blocked, and the client must drop the flag rather than keep it.
    yield ("send", plan_message(session_id, [
        ("Read the migration", "high", "completed"),
        ("Write the new schema", "high", "completed"),
        ("Run the test suite", "medium", "in_progress"),
        ("Write the changelog", "low", "pending"),
    ]))
    yield ("wait", None)
    yield ("chunk", "agent_message_chunk",
           "The schema landed, so the suite is no longer blocked.")


# --- the four-question ask-user form ---------------------------------------

QUESTION_FORM = {
    "version": 4,
    "question": "Four decisions before I start on the accounts service",
    "context": ("All four at once rather than one prompt at a time: the "
                "answers only make sense together."),
    "allowCustomInput": True,
    "questions": [
        # Options that carry descriptions, and one marked as the agent's own
        # pick. Recommended is BADGED, never preselected — a preselected
        # recommendation is a decision put in the user's mouth.
        {"id": "q-0", "header": "Database",
         "question": "Which database should the new service use?",
         "multiSelect": False,
         "options": [
             {"id": "postgres", "label": "PostgreSQL",
              "description": "Already run by the ops team; migrations exist",
              "isRecommended": True},
             {"id": "mysql", "label": "MySQL",
              "description": "What the legacy box runs today"},
             {"id": "sqlite", "label": "SQLite",
              "description": "Fine until the first concurrent writer"},
         ]},
        # Options with a PREVIEW: the snippet each choice would produce.
        {"id": "q-1", "header": "First migration",
         "question": "Which shape should the first migration have?",
         "multiSelect": False,
         "options": [
             {"id": "wide", "label": "One accounts table",
              "preview": "CREATE TABLE accounts (\n"
                         "  id      BIGSERIAL PRIMARY KEY,\n"
                         "  email   TEXT NOT NULL UNIQUE,\n"
                         "  created TIMESTAMPTZ NOT NULL DEFAULT now()\n"
                         ");"},
             {"id": "split", "label": "Accounts + credentials",
              "preview": "CREATE TABLE accounts (id BIGSERIAL PRIMARY KEY);\n"
                         "CREATE TABLE credentials (\n"
                         "  account_id BIGINT REFERENCES accounts(id),\n"
                         "  hash       TEXT NOT NULL\n"
                         ");"},
         ]},
        # Multi-select: several ticks, and the answer must come back in OPTION
        # order rather than tick order.
        {"id": "q-2", "header": "Checks",
         "question": "Which checks should run before merge?",
         "multiSelect": True,
         "options": [
             {"id": "vet", "label": "go vet"},
             {"id": "test", "label": "go test ./...", "isRecommended": True},
             {"id": "lint", "label": "golangci-lint",
              "description": "Thorough, and about two minutes"},
             {"id": "race", "label": "go test -race",
              "description": "Slow on this machine"},
         ]},
        # Free text: no options at all, which is custom input by definition
        # whatever the flag says.
        {"id": "q-3", "header": "Anything else",
         "question": "Anything I should know before I start?",
         "options": [], "allowCustomInput": True},
    ],
}


def question_scenario(session_id):
    """One request, four questions — the shape the phone exists to render.

    Offered only to a client that named `_spettro/question/ask` in the
    top-level `_meta` of `initialize`; the runner checks that before it starts
    this scenario, because an agent that asks anyway would hang on a request
    nobody serves.
    """
    yield ("chunk", "agent_message_chunk",
           "Before I start, four things.\n\n")
    payload = dict(QUESTION_FORM)
    payload["sessionId"] = session_id
    answer = yield ("request", "_spettro/question/ask", payload)
    if answer is None:
        yield ("chunk", "agent_message_chunk", "The editor refused the form.")
        return
    if answer.get("kind") in ("declined", "cancelled"):
        yield ("chunk", "agent_message_chunk",
               "Fair enough — %s. I will ask again when it matters." % answer["kind"])
        return
    described = ", ".join(describe_answer(item) for item in answer.get("answers") or [])
    # Named back verbatim so a mis-encoded answer is visible on screen rather
    # than only in a log: an unanswered question is legitimately ABSENT here,
    # and absent is not the same as the recommended option.
    yield ("chunk", "agent_message_chunk", "Thank you — %s." % described)


# --- a question walked through the permission channel (W-10) ---------------

def walk_scenario(session_id):
    """The same form when the client has NO extension: one question at a time
    through `session/request_permission`, with the real question hidden in the
    request's `_meta`.

    A tool call whose `permissionMeta` carries `spettro.app/question` is not a
    permission prompt and must not be drawn as Allow / Deny — and the answer
    goes back as a selected option id PLUS the tagged `_meta`, which is the
    half a client forgets and which the model needs to tell an answer from a
    consent.
    """
    yield ("chunk", "agent_message_chunk", "One decision, walked.\n\n")
    yield ("send", tool_call(session_id, "ask-1", "ask which database",
                             "other", "pending", {"question": "database"}))
    answer = yield ("request", "session/request_permission", {
        "sessionId": session_id,
        "toolCall": {"toolCallId": "ask-1", "title": "ask which database",
                     "kind": "other", "status": "pending"},
        "options": [
            {"optionId": "postgres", "name": "PostgreSQL", "kind": "allow_once",
             "_meta": {"spettro.app/isRecommended": True}},
            {"optionId": "mysql", "name": "MySQL", "kind": "allow_once"},
            {"optionId": "custom", "name": "Something else…",
             "kind": "allow_once",
             "_meta": {"spettro.app/isCustomInput": True}},
        ],
        "_meta": {"spettro.app/question": {
            "version": 4,
            "sessionId": session_id,
            "context": "Walked one at a time, because this client has no form.",
            "question": "Which database should the new service use?",
            "allowCustomInput": True,
            "options": [
                {"id": "postgres", "label": "PostgreSQL",
                 "description": "Already run by the ops team",
                 "isRecommended": True},
                {"id": "mysql", "label": "MySQL",
                 "description": "What the legacy box runs today"},
            ],
        }},
    })
    outcome = (answer or {}).get("outcome") or {}
    picked = outcome.get("optionId") or outcome.get("outcome") or "no answer"
    yield ("send", tool_update(session_id, "ask-1", status="completed",
                               text=json.dumps({"status": "ok", "answer": picked})))
    yield ("chunk", "agent_message_chunk", "Noted: %s." % picked)


# --- config, re-pushed -----------------------------------------------------

def config_scenario(session_id):
    """Push the whole option set again as a notification.

    `config_option_update` is a FULL replacement and Spettro sends one after
    any handled slash command, so a client that merges instead of replacing
    keeps a stale chip for ever. Nothing changes here — that is the point: the
    same five options, re-sent, must leave the chips exactly as they were.
    """
    yield ("chunk", "agent_message_chunk",
           "Re-publishing the session's five options.\n\n")
    yield ("send", update_message(session_id, {
        "sessionUpdate": "config_option_update",
        "configOptions": CONFIG_SNAPSHOT[0] or [],
    }))
    yield ("hold", "config:pushed")
    yield ("chunk", "agent_message_chunk",
           "Ultra is refused while Permission is `ask-first`; raise it to "
           "Restricted and the chip unlocks.")


# Filled by the Agent before it runs `config_scenario`: the scenario is pure
# and has no session state of its own, and passing the whole Agent into a
# generator would let a scenario reach places it has no business in.
CONFIG_SNAPSHOT = [None]


SCENARIOS = {
    "workflow": workflow_scenario,
    "swarm": swarm_scenario,
    "usage": usage_scenario,
    "blocked": plan_scenario,
    "form": question_scenario,
    "walk": walk_scenario,
    "config": config_scenario,
}

# What each scenario is offered as, in the `/` palette.
SCENARIO_COMMANDS = [
    ("workflow", "Run a three-phase workflow with a failing sub-agent"),
    ("swarm", "Fan an Ultra swarm across 20 items"),
    ("usage", "Move the context gauge seven times in one turn"),
    ("blocked", "Show a plan with a dependency-blocked task"),
    ("form", "Ask a four-question form in one request"),
    ("walk", "Ask one question through the permission channel"),
    ("config", "Re-publish the five session config options"),
]


USAGE = """conformance-agent.py [options]

  --spettro            advertise Spettro's extended surface: five config
                       options with the Ultra gate, and the scenario commands
  --scenario NAME      run NAME for any prompt that is not a slash command
  --pace SECONDS       gap between scenario steps (default 0.7; 0 = a burst)
  --hold SECONDS       how long a named hold sits still (default 12)
  --dump NAME          print NAME's outbound messages, one per line, and exit
  --selftest           check every scenario against the client's own rules
  --help               this
"""


def parse_argv(argv):
    """argv into [OPTIONS], returning a one-shot mode when there is one.

    Deliberately hand-rolled rather than argparse: this file is copied onto a
    phone's Debian guest and read there, and one obvious loop is easier to
    trust than a dependency on which Python the rootfs happens to ship.
    """
    mode = None
    rest = list(argv)
    while rest:
        flag = rest.pop(0)
        if flag == "--spettro":
            OPTIONS.spettro = True
        elif flag == "--scenario" and rest:
            name = rest.pop(0)
            if name not in SCENARIOS:
                raise SystemExit("no scenario %r; try: %s"
                                 % (name, ", ".join(sorted(SCENARIOS))))
            OPTIONS.default_scenario = name
            OPTIONS.spettro = True
        elif flag == "--pace" and rest:
            OPTIONS.pace = float(rest.pop(0))
        elif flag == "--hold" and rest:
            OPTIONS.hold = float(rest.pop(0))
        elif flag == "--dump" and rest:
            mode = ("dump", rest.pop(0))
        elif flag == "--selftest":
            mode = ("selftest", None)
        elif flag in ("--help", "-h"):
            raise SystemExit(USAGE)
        else:
            raise SystemExit("unknown argument %r\n\n%s" % (flag, USAGE))
    return mode


def dump(name, session_id="conf-1"):
    """Every message a scenario would send, one JSON object per line.

    No pauses, no holds, and no client: a request is printed and answered with
    null, so a scenario that waits on one still dumps the request that matters.
    This is the fixture source for the Kotlin fold test — generated rather than
    transcribed, because a transcribed fixture proves only that somebody typed
    carefully once.
    """
    if name not in SCENARIOS:
        raise SystemExit("no scenario %r" % (name,))
    if name == "config":
        raise SystemExit("--dump config needs a live session's options")
    steps = SCENARIOS[name](session_id)
    reply = None
    while True:
        try:
            step = steps.send(reply)
        except StopIteration:
            return 0
        reply = None
        if step[0] == "send":
            sys.stdout.write(json.dumps(step[1]) + "\n")
        elif step[0] == "chunk":
            sys.stdout.write(json.dumps(update_message(session_id, {
                "sessionUpdate": step[1],
                "content": {"type": "text", "text": step[2]},
            })) + "\n")
        elif step[0] == "request":
            sys.stdout.write(json.dumps({
                "jsonrpc": "2.0", "id": "dump", "method": step[1],
                "params": step[2],
            }) + "\n")
        elif step[0] == "hold":
            # A marker, not a message: it is what lets a reader of the dump
            # fold only the prefix a hold stops at, which is the only way to
            # assert on a MID-RUN frame — the ghost cells of a half-launched
            # swarm exist in no other state.
            sys.stdout.write(json.dumps({"_hold": step[1]}) + "\n")


def selftest():
    """Check what the scenarios emit against the CLIENT's own rules.

    Not a test of Python. Every assertion below is a rule from
    `core/SpettroOrchestration.kt` or `core/AgentSession.kt` restated here —
    the classification table, the phase-tree miner's two regexes, the swarm's
    promised denominator, the question parser's field names. A conformance
    agent that drifts from those emits a shape the panel silently files as an
    anonymous tool call, which looks like a client bug and is not one.
    """
    import re

    failures = []

    def check(condition, message):
        if not condition:
            failures.append(message)

    def messages(name):
        out = []
        steps = SCENARIOS[name]("conf-1")
        reply = None
        while True:
            try:
                step = steps.send(reply)
            except StopIteration:
                return out
            reply = None
            if step[0] == "send":
                out.append(step[1])
            elif step[0] == "request":
                out.append({"method": step[1], "params": step[2]})

    # --- the workflow run's classification ---------------------------------
    opens = {}
    latest = {}
    for message in messages("workflow"):
        payload = message["params"]["update"]
        tool_id = payload.get("toolCallId")
        if payload.get("sessionUpdate") not in ("tool_call", "tool_call_update"):
            continue
        if "rawInput" in payload:
            opens.setdefault(tool_id, payload["rawInput"])
            latest[tool_id] = payload["rawInput"]

    run = opens.get("wf-1")
    check(run and run.get("workflow") and not run.get("agent"),
          "the run's opening args must classify as a run: workflow set, agent empty")
    check(run and len(run.get("phases") or []) == 3,
          "the run must declare its phases in the OPENING args")
    finish = latest.get("wf-1")
    check(finish is not None and "phases" not in finish,
          "the finish update must overwrite rawInput without the phases (W-09)")
    check(finish.get("run_id") == run.get("run_id"),
          "the finish update must keep the run id")
    for progress in ("wf-phase-1", "wf-log-1"):
        args = opens.get(progress) or {}
        check(args.get("kind") in ("phase", "log"),
              "%s must be tagged as a progress call, not a second run" % progress)
    for member in WORKFLOW_MEMBERS:
        args = opens.get(member["id"]) or {}
        check(args.get("run_id") == WORKFLOW_RUN_ID,
              "%s must name its run" % member["id"])
        check(args.get("phase") in [name for name, _ in WORKFLOW_PHASES],
              "%s must name a declared phase" % member["id"])
        check("index" in args and "cached" in args,
              "%s must carry index and cached" % member["id"])

    # --- the rendered tree, against the miner's own regexes ----------------
    # Copied from SpettroOrchestration.kt: PHASE_HEADER and MEMBER_ROW. If the
    # tree stops matching these, the `log()` lines vanish from the card with no
    # error anywhere — they have no structured channel at all.
    phase_header = re.compile(r"^ {0,3}[\u25b8\u25cb]\s*\u2500?\s*(\S.*?)\s*$")
    member_row = re.compile(r"^\s+[\u2713\u25b6\u2717\u25cf\u25cb]\s+\S.*$")
    state = WorkflowState()
    state.logs.extend(WORKFLOW_LOGS)
    for member in WORKFLOW_MEMBERS:
        state.set(member, "done", member["result"]["summary"])
    tree = state.render()
    blocks = [block for block in tree.split("\n\n") if block.strip()]
    last = [line for line in blocks[-1].split("\n") if line]
    check(any(phase_header.match(line) for line in last),
          "the last block of the tree must contain phase headers")
    check(all(phase_header.match(line) or member_row.match(line) for line in last),
          "every line of the tree's last block must be a header or a member row")
    titles = [phase_header.match(line).group(1).split("  ")[0].strip()
              for line in last if phase_header.match(line)]
    check(titles == [name for name, _ in WORKFLOW_PHASES],
          "the mined phase titles must be the declared ones, in order: %r" % (titles,))
    check(tree.splitlines()[3].startswith("log: \u201c"),
          "the log block must be quoted the way the client unquotes it")

    # --- the swarm's promised denominator ----------------------------------
    swarm_open = None
    members = 0
    for message in messages("swarm"):
        payload = message["params"]["update"]
        if payload.get("toolCallId") == "ultra-1" and "rawInput" in payload:
            swarm_open = payload["rawInput"]
        args = payload.get("rawInput") or {}
        if args.get("swarm"):
            members += 1
    check(swarm_open and len(swarm_open["items"]) == len(SWARM_ITEMS),
          "the swarm must promise its whole item list up front")
    check(members == len(SWARM_ITEMS),
          "every promised item must eventually get a member: %d of %d"
          % (members, len(SWARM_ITEMS)))
    check(swarm_open.get("run_id") is None,
          "a swarm has no run_id; one would post its members to a workflow")

    # --- the form ----------------------------------------------------------
    form = None
    for message in messages("form"):
        if message.get("method") == "_spettro/question/ask":
            form = message["params"]
    check(form is not None, "the form scenario must ask")
    questions = form["questions"]
    check(len(questions) == 4, "the form must ask four questions")
    check(any(option.get("description")
              for question in questions for option in question["options"]),
          "one question must offer descriptions")
    check(any(option.get("preview")
              for question in questions for option in question["options"]),
          "one question must offer a preview")
    check(any(option.get("isRecommended")
              for question in questions for option in question["options"]),
          "one option must be marked recommended")
    check(any(question.get("multiSelect") for question in questions),
          "one question must be multi-select")
    check(any(not question["options"] for question in questions),
          "one question must be free text")

    # --- the blocked plan --------------------------------------------------
    plans = [message["params"]["update"]["entries"]
             for message in messages("blocked")]
    check(any(entry["content"].endswith(" (blocked)")
              for plan in plans for entry in plan),
          "the plan must carry the literal (blocked) suffix the engine lifts")
    check(not any(entry["content"].endswith(" (blocked)") for entry in plans[-1]),
          "the last plan must have cleared the suffix")

    # --- usage -------------------------------------------------------------
    used = [message["params"]["update"]["used"] for message in messages("usage")]
    spent = [message["params"]["update"]["_meta"]["spettro.app/tokensUsed"]
             for message in messages("usage")]
    check(len(used) >= 5, "usage must move several times inside one turn")
    check(any(b < a for a, b in zip(used, used[1:])),
          "occupancy must FALL once — it is a gauge, not a counter")
    check(all(b > a for a, b in zip(spent, spent[1:])),
          "the spend must never fall")

    for failure in failures:
        sys.stderr.write("FAIL: " + failure + "\n")
    sys.stderr.write("%d checks failed\n" % len(failures) if failures
                     else "selftest ok\n")
    return 1 if failures else 0


def main(argv=None):
    mode = parse_argv(sys.argv[1:] if argv is None else argv)
    if mode is not None:
        return dump(mode[1]) if mode[0] == "dump" else selftest()
    log("started%s" % (" --spettro" if OPTIONS.spettro else ""))
    agent = Agent()
    while True:
        message = read_message()
        if message is None:
            return 0
        agent.handle(message)


if __name__ == "__main__":
    sys.exit(main())
