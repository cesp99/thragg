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
"""

import json
import os
import sys
import time

CHUNK_PAUSE_SECONDS = 0.15
DEFAULT_TARGET = "AGENT_NOTE.md"


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
            {"cwd": os.getcwd(), "model": "conf-one", "verbose": False,
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
        """
        if not self.can("session", "configOptions"):
            return []
        state = self.session(session_id)
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
            if config == "model" and value in ("conf-one", "conf-two"):
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
            send({
                "jsonrpc": "2.0",
                "id": request_id,
                "result": {"stopReason": stop_reason},
            })
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


def main():
    agent = Agent()
    while True:
        message = read_message()
        if message is None:
            return 0
        agent.handle(message)


if __name__ == "__main__":
    sys.exit(main())
