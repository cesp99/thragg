//! ACP agents, run inside the Debian userland.
//!
//! There is no protocol code here. `agent-client-protocol = "=2.0.0"` — the
//! exact crate and pin Zed uses (zed Cargo.toml:523) — owns the JSON-RPC
//! framing, the request tables and the schema; what this module owns is
//! everything around it: how the agent process is started and stopped, where
//! its messages land, and how the answers reach a UI that must never block.
//! The per-session state machine those messages drive is `acp_thread.rs`.
//!
//! Five things shape it, four of them inherited straight from how the rest of
//! the engine already works:
//!
//! **The process comes from `guest.rs::spawn`.** An agent is a resident guest
//! program with all three pipes — exactly the seam `spawn` kept alive for
//! (guest.rs's own comment names "an ACP agent" as the expected caller). That
//! buys the identity binds, the guest environment, and above all the
//! SIGQUIT-first shutdown proot requires: cancel and close paths end in
//! `GuestProcess`'s `Drop`, never a bare kill.
//!
//! **The connection loop runs on a dedicated thread with a big stack.** Zed's
//! warning (agent_servers/src/acp.rs:930-944): inbound ACP dispatch wants
//! ~0.5 MiB of stack per message in unoptimized builds, which overflows small
//! worker stacks — and JNI-attached threads have small stacks. One thread per
//! agent, `CONNECTION_STACK_SIZE`, polling the SDK's connection future via
//! `block_on`; the SDK's own event loop runs every handler and spawned task
//! there, so nothing protocol-shaped ever executes on a JNI thread.
//!
//! **The handshake races the child's exit.** Zed selects the initialize
//! response against `child.status()` so a dead agent produces its stderr
//! rather than a timeout (acp_servers/src/acp.rs:957-1021). Same effect here,
//! shaped for threads instead of executors: a watcher thread polls
//! [`crate::guest::GuestProcess::exit_status`], and an exit closes the pipes —
//! which fails the pending initialize through the transport — while the
//! watcher records the exit and the stderr tail that explains it. The watcher
//! also enforces [`INITIALIZE_TIMEOUT`] by taking the process down, so a hung
//! agent becomes an error instead of a forever-"starting" panel.
//!
//! **Nothing the UI calls waits for the agent.** Prompting returns
//! immediately; every read is a cache read behind the per-session revision
//! counter (`acp_thread.rs`), polled exactly like `lspVersion`. The one
//! blocking JNI-visible call is starting a session, and it only blocks for a
//! `Command::spawn`.
//!
//! **The agent spends the same process budget as everything else.** One agent
//! connection at a time, reserved as [`PROCESSES_PER_AGENT`] against
//! [`guest::PROCESS_BUDGET`] via [`guest::RESERVED_FOR_AGENT`], which the
//! language-server cap reads — the revisit the P5-4 decision explicitly
//! scheduled. While an agent runs, fewer language servers do.
//!
//! Security note, because an agent's tool call is an untrusted write: the
//! `fs/read_text_file` and `fs/write_text_file` handlers confine every path
//! to the session's project root through [`resolves_inside`] — the Rust twin
//! of the Kotlin `SafeDelete.resolvesInside` guard, with the same symlink
//! rule, born of the same two data-loss defects.

use std::collections::{BTreeMap, HashMap};
use std::io::{BufRead, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use agent_client_protocol::schema::ProtocolVersion;
use agent_client_protocol::schema::v1 as acp;
use agent_client_protocol::{Agent, Client, ConnectionTo, Lines, Responder};

use crate::acp_elicit::Elicitations;
use crate::acp_question::Questions;
use crate::acp_terminal::{Terminals, snapshot_json};
use crate::acp_thread::{
    Mention, PermissionDecision, Phase, PromptInput, SessionThread, parse_mentions,
};
use crate::config::ContextServer;
use crate::guest::{self, GuestCommand};
use crate::{Buffers, ProjectId};

/// How long the whole startup — spawn, initialize — gets before the watcher
/// takes the process down. The same figure, for the same reasons, as
/// `lsp::INITIALIZE_TIMEOUT`: an `npx`-style agent on a cold cache inside
/// proot is slow, but one silent for a minute is not going to answer.
const INITIALIZE_TIMEOUT: Duration = Duration::from_secs(60);

/// How often the watcher looks at the child.
const WATCH_INTERVAL: Duration = Duration::from_millis(100);

/// Stack for the connection thread. Zed measured ~0.5 MiB per inbound message
/// in unoptimized builds (agent_servers/src/acp.rs:934-944); host tests run
/// unoptimized, so give the same headroom `runtime.rs` gives gpui.
const CONNECTION_STACK_SIZE: usize = 8 * 1024 * 1024;

/// What one agent connection costs in processes, reserved against
/// [`guest::PROCESS_BUDGET`] while it runs.
///
/// The resident pair is proot and the agent runtime (node) —
/// [`guest::PROCESSES_PER_RUN`]. On top of that the agent runs its tools:
/// a shell command is a `sh` plus the command itself, and Claude Code will
/// happily hold one of each while it thinks. Four of burst headroom is the
/// same shape of allowance the language servers carry, sized for the deepest
/// ordinary case rather than the worst imaginable one.
pub(crate) const PROCESSES_PER_AGENT: usize = guest::PROCESSES_PER_RUN + 4;

/// How long a `_spettro/*` call gets before [`crate::Engine::acp_call_extension`]
/// gives up on it.
///
/// Generous on purpose, and measured rather than guessed:
/// `_spettro/account/status` blocks up to 15 s and
/// `_spettro/providers/connect` up to 30 s, because the agent verifies the
/// key against the provider's own API before answering. A ceiling under
/// either would turn a working connect into "the agent is not answering".
const EXTENSION_TIMEOUT: Duration = Duration::from_secs(45);

/// How much stderr is kept for error messages. Agents log freely; the last
/// few lines are what explains an exit.
const STDERR_TAIL: usize = 4 * 1024;

/// How many updates for a not-yet-indexed session are buffered. Updates can
/// arrive between the agent answering `session/new` and our task recording
/// the id; more than this is an agent flooding before the session exists.
const MAX_EARLY_UPDATES: usize = 256;

/// What to launch, from the Kotlin side's agent configuration:
/// `{"name": "Claude Code", "argv": ["claude-code-acp"], "env": {"K": "V"}}`.
/// `argv` is the guest command line, program included — the agent must be
/// findable on the *login shell's* PATH ([`guest::login_environment`]), which
/// is the same PATH the user's terminal has: `npm -g` installs and
/// `~/.local/bin` installers both qualify.
#[derive(Debug, Clone, serde::Deserialize)]
pub struct AgentSpec {
    pub name: String,
    pub argv: Vec<String>,
    #[serde(default)]
    pub env: HashMap<String, String>,
}

impl AgentSpec {
    /// One string that changes iff the launch would: how a new session knows
    /// it can reuse the running agent.
    fn key(&self) -> String {
        let mut env: Vec<_> = self.env.iter().collect();
        env.sort();
        format!("{}\u{0}{:?}\u{0}{env:?}", self.name, self.argv)
    }
}

// ---------------------------------------------------------------------------
// Engine-level state
// ---------------------------------------------------------------------------

type Sessions = Arc<Mutex<HashMap<u64, Arc<SessionHandle>>>>;
type Index = Arc<Mutex<HashMap<acp::SessionId, u64>>>;

/// Lock order, wherever two are wanted at once: **the agent slot, then the
/// sessions map, then one session's thread, then permissions / written /
/// stderr / index**. `init` is taken alone, or under the agent slot.
/// Nothing holds any of them across a send on the wire.
#[derive(Default)]
pub(crate) struct AcpState {
    /// The one live agent connection — one, by budget, not by accident.
    /// Starting a session with a different [`AgentSpec`] replaces it.
    agent: Mutex<Option<Arc<AgentShared>>>,
    sessions: Sessions,
    index: Index,
    next_session: AtomicU64,
    written: Arc<WrittenFiles>,
}

/// One session as the engine holds it: the state machine under its lock, and
/// the revision mirror the UI polls without taking it.
pub(crate) struct SessionHandle {
    /// Which agent connection this session belongs to
    /// ([`AgentShared::id`]).
    ///
    /// The sessions map is the engine's, shared by every agent that has ever
    /// run in this process, so "my sessions" is a filter rather than a
    /// container. Without it, an agent being replaced took the *replacement's*
    /// sessions down with it on its way out: the old connection's teardown
    /// fails every session it can see, and it could see all of them.
    owner: u64,
    revision: AtomicU64,
    thread: Mutex<SessionThread>,
    /// Permission requests waiting on the user, by tool-call id. Parked here
    /// because a `Responder` is consumed by answering, and the user answers
    /// on a JNI thread long after the handler returned.
    permissions: Mutex<HashMap<String, Responder<acp::RequestPermissionResponse>>>,
}

impl SessionHandle {
    fn new(owner: u64, thread: SessionThread) -> Self {
        let revision = thread.revision;
        SessionHandle {
            owner,
            revision: AtomicU64::new(revision),
            thread: Mutex::new(thread),
            permissions: Mutex::new(HashMap::new()),
        }
    }

    /// Run `f` under the thread lock and mirror the revision out.
    fn update<T>(&self, f: impl FnOnce(&mut SessionThread) -> T) -> T {
        let mut thread = self.thread.lock().unwrap();
        let result = f(&mut thread);
        self.revision.store(thread.revision, Ordering::Release);
        result
    }

    /// Answer every parked permission request with `cancelled` — the spec's
    /// requirement when a turn is cancelled, and the only honest answer when
    /// the agent is gone — and mark each waiting tool call cancelled.
    fn cancel_permissions(&self) {
        let parked: Vec<_> = {
            let mut permissions = self.permissions.lock().unwrap();
            permissions.drain().collect()
        };
        for (id, responder) in parked {
            self.update(|thread| {
                thread.finish_permission(&id, PermissionDecision::Cancel);
            });
            let _ = responder.respond(acp::RequestPermissionResponse::new(
                acp::RequestPermissionOutcome::Cancelled,
            ));
        }
    }
}

/// Files the agent has written through `fs/write_text_file`, so the UI can
/// reload the open buffers among them. Grows within a launch; the list is a
/// few paths, not a log.
#[derive(Default)]
pub(crate) struct WrittenFiles {
    version: AtomicU64,
    paths: Mutex<Vec<String>>,
}

impl WrittenFiles {
    fn record(&self, path: &Path) {
        let mut paths = self.paths.lock().unwrap();
        paths.push(path.to_string_lossy().into_owned());
        self.version.fetch_add(1, Ordering::Release);
    }

    fn json(&self, since: usize) -> serde_json::Value {
        let paths = self.paths.lock().unwrap();
        serde_json::json!({
            "total": paths.len(),
            "paths": paths.get(since.min(paths.len())..).unwrap_or(&[]),
        })
    }
}

/// The cached answer to `session/list`.
///
/// One in flight at a time, and the version only moves when the answer
/// actually changes — the panel polls this while the threads view is open,
/// and a list that reports itself new every 120 ms would rebuild the view
/// under the reader's finger.
#[derive(Default)]
struct SessionList {
    version: u64,
    loading: bool,
    error: Option<String>,
    /// `SessionInfo` in the protocol's own camelCase, passed through.
    sessions: serde_json::Value,
}

impl SessionList {
    fn json(&self) -> serde_json::Value {
        serde_json::json!({
            "version": self.version,
            "loading": self.loading,
            "error": self.error,
            "sessions": if self.sessions.is_null() {
                serde_json::Value::Array(Vec::new())
            } else {
                self.sessions.clone()
            },
        })
    }
}

/// A session asked for before `initialize` answered, with everything
/// `session/new` will need once it has.
struct PendingSession {
    id: u64,
    resume: Option<String>,
    /// `context_servers` as settings.json had them when the thread was asked
    /// for — read then, not later, so an edit to the file lands on the *next*
    /// thread, the way an edited `agent_servers` entry does.
    context_servers: BTreeMap<String, ContextServer>,
}

impl PendingSession {
    fn new(id: u64, resume: Option<String>) -> Self {
        PendingSession {
            id,
            resume,
            context_servers: BTreeMap::new(),
        }
    }
}

/// The configured context servers as ACP's `mcpServers`, in the shape Zed
/// sends (agent_servers/src/acp.rs:4397-4442): a stdio server carries its
/// command, arguments and environment; an HTTP one its URL and headers, and
/// only for an agent whose `mcpCapabilities.http` says it can connect —
/// the protocol forbids sending one otherwise. Disabled entries stay home.
fn mcp_servers(servers: &BTreeMap<String, ContextServer>, caps: AgentCaps) -> Vec<acp::McpServer> {
    servers
        .iter()
        .filter(|(_, server)| server.is_enabled())
        .filter_map(|(name, server)| match server {
            ContextServer::Stdio {
                command, args, env, ..
            } => Some(acp::McpServer::Stdio(
                acp::McpServerStdio::new(name.clone(), command.clone())
                    .args(args.clone())
                    .env(
                        env.iter()
                            .map(|(key, value)| acp::EnvVariable::new(key.clone(), value.clone()))
                            .collect(),
                    ),
            )),
            ContextServer::Http { url, headers, .. } if caps.mcp_http => {
                Some(acp::McpServer::Http(
                    acp::McpServerHttp::new(name.clone(), url.clone()).headers(
                        headers
                            .iter()
                            .map(|(key, value)| acp::HttpHeader::new(key.clone(), value.clone()))
                            .collect(),
                    ),
                ))
            }
            ContextServer::Http { .. } => {
                log::info!(
                    "acp: context server {name:?} is HTTP and the agent takes no HTTP MCP; not sent"
                );
                None
            }
        })
        .collect()
}

/// Where an agent connection is in its life.
enum InitPhase {
    Starting,
    Ready(AgentInfo),
    Failed(String),
}

/// What `initialize` answered, held for the state JSON.
#[derive(Clone)]
struct AgentInfo {
    /// The configured display name.
    name: String,
    /// What the agent calls itself, when it says.
    agent_name: Option<String>,
    agent_version: Option<String>,
    /// `auth_methods`, in ACP's own wire shape — the UI renders them as
    /// choices for `acp_authenticate`.
    auth_methods: serde_json::Value,
    /// Whether the agent takes `EmbeddedResource` prompt blocks. Decides how
    /// an @-mention travels: embedded file text when it can, a `ResourceLink`
    /// when it cannot — the same split Zed makes
    /// (agent_ui/src/message_editor.rs:2150-2161).
    embedded_context: bool,
    /// Whether the agent takes [`acp::ContentBlock::Image`] prompt blocks.
    /// Gates the composer's attach button: an agent that never said it reads
    /// images is not sent one — the block would be dead weight in a prompt at
    /// best, and an error at worst.
    images: bool,
    /// The session-lifecycle capabilities, which decide what the panel may
    /// offer. Every one of these is a method that is an error to call
    /// unasked, so they gate buttons rather than decorate them.
    caps: AgentCaps,
    /// What the agent answered under `_meta["spettro.app/extensions"]`:
    /// `{"version": u32, "methods": [String], "clientMethods": [String]}`,
    /// forwarded verbatim.
    ///
    /// **This is the gate.** Present means the agent is Spettro and the whole
    /// superset — workflows, the selector chips, question forms, steering,
    /// the context gauge — is on the table; absent means a generic ACP agent
    /// and none of it is offered. `version >= 4` gates the workflow surface.
    /// The agent's `agent_name` is a secondary signal and never the gate: an
    /// agent that renamed itself still answers the methods it advertises.
    spettro_extensions: Option<serde_json::Value>,
}

/// What the agent said it can do with sessions, from `initialize`.
#[derive(Debug, Clone, Copy, Default, serde::Serialize)]
struct AgentCaps {
    /// `session/load`: replay a past session's history into a new thread.
    load_session: bool,
    /// `session/resume`: continue a past session *without* its history.
    /// Zed prefers `load` and falls back to this
    /// (agent_ui/src/conversation_view.rs:1110-1128).
    resume: bool,
    /// `session/list`: what past sessions the agent has kept.
    list: bool,
    /// `session/delete`: forget one of them.
    delete: bool,
    /// `session/close`: the polite end of a session, rather than a bare
    /// cancel.
    close: bool,
    /// `logout`: sign out of whatever `authenticate` signed into.
    logout: bool,
    /// `mcpCapabilities.http`: the agent connects to HTTP MCP servers. Stdio
    /// servers need no flag — every agent must take those.
    mcp_http: bool,
}

impl AgentCaps {
    fn read(capabilities: &acp::AgentCapabilities) -> Self {
        AgentCaps {
            load_session: capabilities.load_session,
            resume: capabilities.session_capabilities.resume.is_some(),
            list: capabilities.session_capabilities.list.is_some(),
            delete: capabilities.session_capabilities.delete.is_some(),
            close: capabilities.session_capabilities.close.is_some(),
            logout: capabilities.auth.logout.is_some(),
            mcp_http: capabilities.mcp_capabilities.http,
        }
    }

    /// Whether a past session can be opened at all, either way round —
    /// Zed's `supports_session_history` (acp_thread/src/connection.rs:158).
    fn can_open_history(&self) -> bool {
        self.load_session || self.resume
    }
}

/// Everything the handlers and worker threads share for one agent process.
struct AgentShared {
    /// This connection's identity, stamped onto the sessions it owns. Agents
    /// come and go within one process — a different spec replaces the running
    /// one — and the sessions map outlives all of them.
    id: u64,
    key: String,
    name: String,
    /// The way to talk to the agent, once the transport is up.
    connection: Mutex<Option<ConnectionTo<Agent>>>,
    init: Mutex<InitPhase>,
    sessions: Sessions,
    index: Index,
    written: Arc<WrittenFiles>,
    buffers: Buffers,
    /// Sessions created before `initialize` answered; drained by the
    /// connection's main task. Guarded by `init`'s lock order: taken only
    /// while `init` is held, so a session cannot fall between the phases.
    pending_sessions: Mutex<Vec<PendingSession>>,
    /// Updates for an acp session id we have not indexed yet — see
    /// [`MAX_EARLY_UPDATES`].
    early_updates: Mutex<Vec<(acp::SessionId, acp::SessionUpdate)>>,
    stderr: Mutex<String>,
    /// Where to run the commands `terminal/create` asks for. The agent's own
    /// process came out of the same userland; a terminal is another guest
    /// program beside it.
    userland: Arc<guest::Userland>,
    /// The agent's terminals, by their protocol id.
    terminals: Terminals,
    /// The questions the agent is waiting on — `elicitation/create`.
    elicitations: Elicitations,
    /// The questions the agent is waiting on through *its own* extension —
    /// `_spettro/question/ask`. Beside the elicitations rather than inside
    /// them: the payload is the agent's, not the protocol's, and modelling it
    /// twice is how the two drift. See [`crate::acp_question`].
    questions: Questions,
    /// The last `_spettro/account/update` the agent pushed, verbatim, and a
    /// counter for it.
    ///
    /// This is the **only** way the device-flow login progresses: the agent
    /// owns the two-second poller against the backend and pushes what it
    /// learns; the phone polls this cache and never the network. A login that
    /// polled from here would be a second poller racing the first, on the
    /// wrong side of the JNI boundary, on a battery.
    account: Mutex<Option<serde_json::Value>>,
    account_version: AtomicU64,
    /// The agent's own past sessions, from `session/list`. A cache behind a
    /// version counter, polled exactly like everything else the panel reads:
    /// the request is a round trip to the agent and the threads view opens
    /// often.
    session_list: Mutex<SessionList>,
    /// Ask the watcher to take the process down.
    shutdown: AtomicBool,
    /// The process is gone — observed dead, or killed on request.
    dead: AtomicBool,
}

impl AgentShared {
    fn new(
        spec: &AgentSpec,
        key: String,
        userland: Arc<guest::Userland>,
        sessions: Sessions,
        index: Index,
        written: Arc<WrittenFiles>,
        buffers: Buffers,
    ) -> Arc<Self> {
        static NEXT_AGENT: AtomicU64 = AtomicU64::new(1);
        Arc::new(AgentShared {
            id: NEXT_AGENT.fetch_add(1, Ordering::Relaxed),
            key,
            name: spec.name.clone(),
            connection: Mutex::new(None),
            init: Mutex::new(InitPhase::Starting),
            sessions,
            index,
            written,
            buffers,
            pending_sessions: Mutex::new(Vec::new()),
            early_updates: Mutex::new(Vec::new()),
            stderr: Mutex::new(String::new()),
            userland,
            terminals: Terminals::default(),
            elicitations: Elicitations::default(),
            questions: Questions::default(),
            account: Mutex::new(None),
            account_version: AtomicU64::new(0),
            session_list: Mutex::new(SessionList::default()),
            shutdown: AtomicBool::new(false),
            dead: AtomicBool::new(false),
        })
    }

    /// Ask the agent for its past sessions, unless a request is already out.
    ///
    /// Silent when the agent has no `session/list`: the panel gates the view
    /// on the capability anyway, and an error row for a method that was never
    /// offered would say the agent is broken when it is merely simpler.
    fn refresh_session_list(self: &Arc<Self>) {
        if !self.caps().list {
            return;
        }
        let Some(cx) = self.connection() else {
            return;
        };
        {
            let mut list = self.session_list.lock().unwrap();
            if list.loading {
                return;
            }
            list.loading = true;
            list.version += 1;
        }
        // Every session this connection owns shares one project root — the
        // agent key includes it — so any of them will do.
        let root = self
            .own_sessions()
            .first()
            .map(|(_, handle)| handle.thread.lock().unwrap().root.clone());
        let task_shared = self.clone();
        let task_cx = cx.clone();
        let _ = cx.spawn(async move {
            // Scoped to the project. Without a `cwd` an agent answers with
            // every conversation it has ever had, and tapping one from
            // another project resumed *that* conversation pointed at *this*
            // project's files — the agent's context says one tree and every
            // path it is handed is in another.
            let request = match root {
                Some(root) => acp::ListSessionsRequest::new().cwd(root),
                None => acp::ListSessionsRequest::new(),
            };
            let result = task_cx.send_request(request).block_task().await;
            let mut list = task_shared.session_list.lock().unwrap();
            list.loading = false;
            list.version += 1;
            match result {
                Ok(response) => {
                    list.error = None;
                    list.sessions =
                        serde_json::to_value(&response.sessions).unwrap_or(serde_json::Value::Null);
                    // `nextCursor` is deliberately not followed. The panel
                    // shows recent conversations, not an archive, and a
                    // cursor loop against an agent with thousands of them is
                    // a lot of round trips for a list nobody scrolls to the
                    // end of.
                }
                Err(err) => {
                    log::warn!("acp: session/list failed: {err}");
                    list.error = Some(format!("{err}"));
                }
            }
            Ok(())
        });
    }

    fn connection(&self) -> Option<ConnectionTo<Agent>> {
        self.connection.lock().unwrap().clone()
    }

    fn initialized(&self) -> bool {
        !matches!(*self.init.lock().unwrap(), InitPhase::Starting)
    }

    /// What the agent said it can do with sessions. All false until
    /// `initialize` has answered, which is the safe default: every one of
    /// these gates a method that is an error to call unasked.
    fn caps(&self) -> AgentCaps {
        match &*self.init.lock().unwrap() {
            InitPhase::Ready(info) => info.caps,
            _ => AgentCaps::default(),
        }
    }

    /// Whether the agent advertised Spettro's extension surface — the gate
    /// every Spettro-only behaviour hangs off. False until `initialize` has
    /// answered, which is the safe default.
    fn speaks_spettro(&self) -> bool {
        matches!(&*self.init.lock().unwrap(), InitPhase::Ready(info) if info.spettro_extensions.is_some())
    }

    fn supports_embedded_context(&self) -> bool {
        matches!(&*self.init.lock().unwrap(), InitPhase::Ready(info) if info.embedded_context)
    }

    /// Whether the agent said it reads image blocks.
    fn supports_images(&self) -> bool {
        matches!(&*self.init.lock().unwrap(), InitPhase::Ready(info) if info.images)
    }

    /// The one line of stderr worth showing a user, if there is one.
    ///
    /// **Not simply the last line, and the device is why.** proot reports a
    /// missing program in two lines — `proot error: 'claude-code-acp' not
    /// found (root = …)` and then `fatal error: see \`libproot_exec.so
    /// --help\`` — so taking the last one put a pointer to proot's own usage
    /// message in front of the user and, worse, threw away the only words that
    /// say what is wrong. The panel reads these sentences too: it offers to
    /// install Node exactly when one of them says something was not found, so
    /// picking the wrong line also silently removed the way out.
    ///
    /// So: the first line that names a missing thing, and otherwise the last
    /// non-empty one — which for an agent that started and then crashed is the
    /// end of its own traceback, the useful end.
    fn stderr_hint(&self) -> Option<String> {
        let stderr = self.stderr.lock().unwrap();
        let named_missing = stderr.lines().find(|line| {
            let line = line.to_ascii_lowercase();
            line.contains("not found") || line.contains("no such file")
        });
        named_missing
            .or_else(|| {
                stderr
                    .lines()
                    .filter(|line| !line.trim().is_empty())
                    .next_back()
            })
            .map(|line| trim_proot_detail(line.trim()))
            .filter(|line| !line.is_empty())
    }

    /// What the agent said about itself, or `base` when it said nothing.
    ///
    /// The agent's own words win outright rather than being appended: the
    /// caller's `base` is a description of the *transport* giving up — "the
    /// connection closed", with the SDK's multi-line JSON in it — and beside
    /// "'claude-code-acp' not found" it is noise. The lsp module made the same
    /// call for the same reason (lsp.rs's `start_server`, which shows the
    /// captured line and falls back to the error only when there is none).
    fn with_stderr(&self, base: &str) -> String {
        self.stderr_hint().unwrap_or_else(|| base.to_owned())
    }

    /// One of *our* sessions. A session belonging to an agent that has since
    /// been replaced is not ours to touch, however loudly its id is quoted at
    /// us by a message still in flight on the old wire.
    fn session(&self, id: u64) -> Option<Arc<SessionHandle>> {
        self.sessions
            .lock()
            .unwrap()
            .get(&id)
            .filter(|handle| handle.owner == self.id)
            .cloned()
    }

    /// Every session this connection owns, id included.
    fn own_sessions(&self) -> Vec<(u64, Arc<SessionHandle>)> {
        self.sessions
            .lock()
            .unwrap()
            .iter()
            .filter(|(_, handle)| handle.owner == self.id)
            .map(|(id, handle)| (*id, handle.clone()))
            .collect()
    }

    fn session_for_acp_id(&self, id: &acp::SessionId) -> Option<(u64, Arc<SessionHandle>)> {
        let our_id = *self.index.lock().unwrap().get(id)?;
        Some((our_id, self.session(our_id)?))
    }

    /// The agent process is gone, however it went: every session it served is
    /// over, and every parked question is answered.
    ///
    /// Strictly *its* sessions. This runs when the connection loop ends, which
    /// for a replaced agent is after its successor is already serving — and
    /// failing everything in sight would take the successor's sessions down
    /// with it.
    fn agent_gone(&self, message: String) {
        {
            let mut init = self.init.lock().unwrap();
            if matches!(*init, InitPhase::Starting) {
                *init = InitPhase::Failed(message.clone());
            }
        }
        *self.connection.lock().unwrap() = None;
        // Whatever the agent was waiting on, it is not waiting any more: the
        // terminals it started have no reader left, and its questions have
        // nobody to answer them to. Both are left open otherwise — a
        // build running in the guest against a dead connection, and a
        // question card the user can never dismiss.
        self.terminals.release_all();
        self.elicitations.cancel_all();
        self.questions.cancel_all();
        let mine = self.own_sessions();
        for (_, handle) in &mine {
            handle.cancel_permissions();
            handle.update(|thread| {
                if thread.phase != Phase::Unavailable {
                    thread.fail(message.clone());
                }
            });
        }
        // The acp-id → our-id mapping is this connection's; the next agent
        // issues its own ids and must not inherit these.
        let ours: Vec<acp::SessionId> = mine
            .iter()
            .filter_map(|(_, handle)| handle.thread.lock().unwrap().acp_id.clone())
            .collect();
        let mut index = self.index.lock().unwrap();
        for id in ours {
            index.remove(&id);
        }
    }

    // -- inbound handlers; all run on the connection thread's event loop -----

    fn on_session_update(&self, notification: acp::SessionNotification) {
        match self.session_for_acp_id(&notification.session_id) {
            Some((_, handle)) => handle.update(|thread| thread.apply_update(notification.update)),
            None => {
                // Between the agent answering `session/new` and our task
                // recording the id, updates for it are already legal; hold
                // them and let `session_ready` replay them in order.
                let mut early = self.early_updates.lock().unwrap();
                if early.len() < MAX_EARLY_UPDATES {
                    early.push((notification.session_id, notification.update));
                } else {
                    log::debug!("acp: dropping update for unknown session (buffer full)");
                }
            }
        }
    }

    fn on_permission(
        &self,
        request: acp::RequestPermissionRequest,
        responder: Responder<acp::RequestPermissionResponse>,
    ) {
        let Some((_, handle)) = self.session_for_acp_id(&request.session_id) else {
            let _ = responder.respond_with_error(
                acp::Error::invalid_params().data(serde_json::json!("unknown session")),
            );
            return;
        };
        let tool_call_id = request.tool_call.tool_call_id.0.to_string();
        // A request that arrives after the turn was cancelled — or with no
        // turn running at all — is answered `cancelled` right now, as the
        // spec requires of a cancelling client. Parking it deadlocked the
        // turn: the agent blocks on this answer while the engine waits for
        // its `PromptResponse`, and a ghost permission dialog surfaced after
        // the user had already pressed Stop. Found by a skeptic pass with a
        // live agent; the window is the agent's whole streaming phase.
        // The request's own `_meta`, forwarded whole. A permission request
        // carrying `spettro.app/question` is a *question* walked through the
        // permission channel, and the panel has to be able to tell.
        let meta = request
            .meta
            .as_ref()
            .and_then(|meta| serde_json::to_value(meta).ok());
        let late = handle.update(|thread| {
            if thread.turn_cancelled || thread.phase != Phase::Running {
                // Still recorded, as cancelled: the transcript should say
                // what the agent was trying when the stop landed.
                thread.begin_permission(request.tool_call.clone(), Vec::new(), meta.clone());
                thread.finish_permission(&tool_call_id, PermissionDecision::Cancel);
                true
            } else {
                thread.begin_permission(
                    request.tool_call.clone(),
                    request.options.clone(),
                    meta.clone(),
                );
                false
            }
        });
        if late {
            let _ = responder.respond(acp::RequestPermissionResponse::new(
                acp::RequestPermissionOutcome::Cancelled,
            ));
            return;
        }
        // Two live requests for one tool call cannot both be answered; the
        // newer one is the agent's current question, so the older is answered
        // `cancelled` on its way out.
        if let Some(previous) = handle
            .permissions
            .lock()
            .unwrap()
            .insert(tool_call_id, responder)
        {
            let _ = previous.respond(acp::RequestPermissionResponse::new(
                acp::RequestPermissionOutcome::Cancelled,
            ));
        }
    }

    // ---- terminals ------------------------------------------------------
    //
    // The five `terminal/*` methods, in the order an agent uses them: create,
    // read, wait, kill, release. Zed serves the same set
    // (agent_servers/src/acp.rs:722-740); the mechanics are
    // [`crate::acp_terminal`], and what is here is the session lookup and the
    // protocol's error shapes.

    // ---- elicitations ---------------------------------------------------

    fn on_create_elicitation(
        self: &Arc<Self>,
        request: acp::CreateElicitationRequest,
        responder: Responder<acp::CreateElicitationResponse>,
    ) {
        // Taken before the responder is parked, because parking consumes it.
        let cancellation = responder.cancellation();
        // Session scope names the conversation it belongs to; request scope
        // names one of *our* outstanding requests instead — `authenticate`,
        // in practice — and has no conversation at all. The store keeps both,
        // and every session's panel shows the connection-level ones, because
        // the agent is stuck on them whichever thread is open.
        let session = match &request.mode {
            acp::ElicitationMode::Form(mode) => scope_session(&mode.scope),
            acp::ElicitationMode::Url(mode) => scope_session(&mode.scope),
            _ => None,
        };
        let handle = session.as_ref().and_then(|id| self.session_for_acp_id(id));
        let our_id = handle.as_ref().map(|(id, _)| *id);
        if session.is_some() && handle.is_none() {
            let _ = responder.respond_with_error(
                acp::Error::invalid_params().data(serde_json::json!("unknown session")),
            );
            return;
        }
        match self.elicitations.open(request, our_id, responder) {
            Ok(pending) => {
                log::info!("acp: elicitation {} opened", pending.id);
                // An agent may take its question back. Nothing else notices:
                // the responder is parked until somebody answers, so without
                // this the card stays up for ever and the user's answer goes
                // to a request that is no longer live.
                if let Some(cx) = self.connection() {
                    let shared = self.clone();
                    let id = pending.id.clone();
                    let _ = cx.spawn(async move {
                        cancellation.cancelled().await;
                        if shared.elicitations.withdraw(&id) {
                            log::info!("acp: elicitation {id} withdrawn by the agent");
                            shared.bump_sessions(None);
                        }
                        Ok(())
                    });
                }
                // The question rides the session state, so the revision has
                // to move or the panel never asks for it.
                self.bump_sessions(our_id);
            }
            Err(err) => log::warn!("acp: elicitation refused: {err:?}"),
        }
    }

    // ---- Spettro's extension ---------------------------------------------
    //
    // Two handlers, both untyped and both registered last: one request
    // (`_spettro/question/ask`) and one notification
    // (`_spettro/account/update`). Everything else `_`-prefixed falls through
    // to the role default, which answers `-32601` — correct, and what the
    // agent degrades on.

    /// `_spettro/question/ask`: a whole ask-user form in one request.
    ///
    /// Parked, never answered here — the user answers on a JNI thread, long
    /// after this returns. The params are not parsed beyond `sessionId`,
    /// which is only used to decide *which panel* shows it: everything else
    /// is Kotlin's to read, verbatim. See [`crate::acp_question`].
    fn on_spettro_question(
        self: &Arc<Self>,
        request: agent_client_protocol::UntypedMessage,
        responder: Responder<serde_json::Value>,
    ) {
        // Taken before the responder is parked, because parking consumes it.
        let cancellation = responder.cancellation();
        let session = request
            .params
            .get("sessionId")
            .and_then(|id| id.as_str())
            .map(|id| acp::SessionId::new(id.to_owned()))
            .and_then(|id| self.session_for_acp_id(&id))
            .map(|(our_id, _)| our_id);
        // An unknown session id is **not** refused. The agent is blocked on
        // this request either way, and a question the user never sees is a
        // turn that never ends; a question with no thread of its own is shown
        // in whichever thread is open, exactly as a request-scoped
        // elicitation is.
        if session.is_none() {
            log::info!("acp: a Spettro question named no session we know; showing it anyway");
        }
        let pending = self.questions.open(request.params, session, responder);
        log::info!("acp: Spettro question {} opened", pending.id);
        // An agent may take its question back — `$/cancel_request`, which is
        // what a cancelled turn does to a form it was waiting on. Nothing
        // else notices: the responder is parked until somebody answers, so
        // without this the sheet stays up for ever over a request that is no
        // longer live. Same watch the elicitations keep, above.
        if let Some(cx) = self.connection() {
            let shared = self.clone();
            let id = pending.id.clone();
            let _ = cx.spawn(async move {
                cancellation.cancelled().await;
                if shared
                    .questions
                    .answer(&id, serde_json::json!({ "kind": "cancelled" }))
                {
                    log::info!("acp: Spettro question {id} withdrawn by the agent");
                    shared.bump_sessions(None);
                }
                Ok(())
            });
        }
        // It rides the session state, so the revision has to move or the
        // panel never asks for it. All of them when it belongs to none.
        self.bump_sessions(session);
    }

    /// Every notification whose method we have no typed handler for.
    ///
    /// Only `_spettro/account/update` means anything so far: the agent's own
    /// view of who is signed in, pushed as the device flow progresses. True
    /// when it was claimed — anything else keeps falling through the chain
    /// rather than being swallowed here, because a handler that answers
    /// "handled" for every method it does not know is a handler that hides
    /// the next one.
    fn on_extension_notification(
        &self,
        notification: &agent_client_protocol::UntypedMessage,
    ) -> bool {
        if notification.method.as_str() != "_spettro/account/update" {
            log::debug!("acp: no handler for notification {}", notification.method);
            return false;
        }
        *self.account.lock().unwrap() = Some(notification.params.clone());
        self.account_version.fetch_add(1, Ordering::Release);
        true
    }

    fn on_complete_elicitation(&self, notification: acp::CompleteElicitationNotification) {
        if self
            .elicitations
            .complete(notification.elicitation_id.0.as_ref())
        {
            self.bump_sessions(None);
        }
    }

    /// Move the revision of one session, or of all of ours when the change is
    /// connection-level and every open panel needs to see it.
    fn bump_sessions(&self, session: Option<u64>) {
        match session {
            Some(id) => {
                if let Some(handle) = self.session(id) {
                    handle.update(|thread| {
                        thread.bump();
                    });
                }
            }
            None => {
                for (_, handle) in self.own_sessions() {
                    handle.update(|thread| {
                        thread.bump();
                    });
                }
            }
        }
    }

    fn on_create_terminal(
        &self,
        request: acp::CreateTerminalRequest,
        responder: Responder<acp::CreateTerminalResponse>,
    ) {
        let Some((our_id, handle)) = self.session_for_acp_id(&request.session_id) else {
            let _ = responder.respond_with_error(
                acp::Error::invalid_params().data(serde_json::json!("unknown session")),
            );
            return;
        };
        let root = handle.thread.lock().unwrap().root.clone();
        match self
            .terminals
            .create(&self.userland, our_id, &root, &request)
        {
            Ok(terminal) => {
                log::info!("acp: terminal {} runs `{}`", terminal.id, terminal.label);
                // The panel draws a terminal card off the tool call's
                // content, and the content is only as fresh as the revision —
                // so the session has to move when a terminal appears, or the
                // card sits empty until something else happens to bump it.
                handle.update(|thread| {
                    thread.bump();
                });
                let _ = responder.respond(acp::CreateTerminalResponse::new(acp::TerminalId::new(
                    terminal.id.clone(),
                )));
            }
            Err(err) => {
                log::warn!("acp: terminal refused: {err:?}");
                let _ = responder.respond_with_error(err);
            }
        }
    }

    fn on_terminal_output(
        &self,
        request: acp::TerminalOutputRequest,
        responder: Responder<acp::TerminalOutputResponse>,
    ) {
        let Some(terminal) = self.terminals.get(request.terminal_id.0.as_ref()) else {
            let _ = responder.respond_with_error(
                acp::Error::invalid_params().data(serde_json::json!("unknown terminal")),
            );
            return;
        };
        let snapshot = terminal.snapshot();
        let mut response = acp::TerminalOutputResponse::new(snapshot.output, snapshot.truncated);
        if let Some(exit) = snapshot.exit {
            response = response.exit_status(exit);
        }
        let _ = responder.respond(response);
    }

    fn on_wait_for_terminal_exit(
        &self,
        request: acp::WaitForTerminalExitRequest,
        responder: Responder<acp::WaitForTerminalExitResponse>,
    ) {
        let Some(terminal) = self.terminals.get(request.terminal_id.0.as_ref()) else {
            let _ = responder.respond_with_error(
                acp::Error::invalid_params().data(serde_json::json!("unknown terminal")),
            );
            return;
        };
        // Parked, never awaited here: this runs on the connection's event
        // loop, and a command that takes a minute would take the whole
        // connection with it — including the `terminal/output` polls the
        // agent makes while it waits.
        terminal.wait(responder);
    }

    fn on_kill_terminal(
        &self,
        request: acp::KillTerminalRequest,
        responder: Responder<acp::KillTerminalResponse>,
    ) {
        let Some(terminal) = self.terminals.get(request.terminal_id.0.as_ref()) else {
            let _ = responder.respond_with_error(
                acp::Error::invalid_params().data(serde_json::json!("unknown terminal")),
            );
            return;
        };
        // Kill is not release: the output stays readable, which is the whole
        // point — an agent kills a command that has hung and then reads what
        // it managed to print.
        terminal.kill();
        let _ = responder.respond(acp::KillTerminalResponse::new());
    }

    fn on_release_terminal(
        &self,
        request: acp::ReleaseTerminalRequest,
        responder: Responder<acp::ReleaseTerminalResponse>,
    ) {
        // Seal what it printed onto the transcript *before* letting go of it.
        // The registry keeps only the last few released terminals, and the
        // transcript is the record — a card whose terminal has since been
        // evicted must still be able to say what the command did.
        let terminal_id = request.terminal_id.0.to_string();
        if let Some(terminal) = self.terminals.get(&terminal_id) {
            let snapshot = terminal.snapshot();
            if let Some(handle) = self.session(terminal.session) {
                let exit = snapshot
                    .exit
                    .as_ref()
                    .and_then(|exit| serde_json::to_value(exit).ok());
                handle.update(|thread| {
                    thread.seal_terminal(
                        &terminal_id,
                        snapshot.label.clone(),
                        snapshot.output.clone(),
                        snapshot.truncated,
                        exit,
                    );
                });
            }
        }
        if !self.terminals.release(request.terminal_id.0.as_ref()) {
            let _ = responder.respond_with_error(
                acp::Error::invalid_params().data(serde_json::json!("unknown terminal")),
            );
            return;
        }
        let _ = responder.respond(acp::ReleaseTerminalResponse::new());
    }

    fn on_write_text_file(
        &self,
        request: acp::WriteTextFileRequest,
        responder: Responder<acp::WriteTextFileResponse>,
    ) {
        let Some((_, handle)) = self.session_for_acp_id(&request.session_id) else {
            let _ = responder.respond_with_error(
                acp::Error::invalid_params().data(serde_json::json!("unknown session")),
            );
            return;
        };
        let root = handle.thread.lock().unwrap().root.clone();
        // The untrusted-write case the symlink guard exists for: an agent
        // handed a path must not be able to reach outside the project, not
        // even through a symlink a previous tool call created.
        if !resolves_inside(&root, &request.path) {
            log::warn!(
                "acp: refused write outside the project: {}",
                request.path.display()
            );
            let _ = responder.respond_with_error(
                acp::Error::invalid_params().data(serde_json::json!("path is outside the project")),
            );
            return;
        }
        // What the file held before this write — the checkpoint. The open
        // buffer's text when there is one, because that is what the agent
        // was shown by `fs/read_text_file` and what the user would want
        // back; None for a file that does not exist yet, which restoring
        // then deletes.
        let before = current_text(&self.buffers, &request.path);
        let result = (|| -> std::io::Result<()> {
            if let Some(parent) = request.path.parent() {
                std::fs::create_dir_all(parent)?;
            }
            crate::file::write_atomically_io(&request.path, &request.content)
        })();
        match result {
            Ok(()) => {
                // Flag any open buffer the usual way, and put the path where
                // the UI's poll will find it — the UI reloads through
                // `reloadBuffer`, which keeps highlighting, LSP sync and the
                // undo history correct, exactly as an external edit does.
                //
                // Canonical, because that is the spelling buffers hold
                // (`Engine::open_file`): an agent writing through an
                // in-project symlink alias would otherwise miss the very
                // buffer it just changed.
                let path = std::fs::canonicalize(&request.path).unwrap_or(request.path.clone());
                crate::file::note_disk_changes(&self.buffers, &[path.clone()]);
                self.written.record(&path);
                handle.update(|thread| thread.record_edit(path, before));
                let _ = responder.respond(acp::WriteTextFileResponse::new());
            }
            Err(err) => {
                let _ = responder
                    .respond_with_error(acp::Error::internal_error().data(err.to_string()));
            }
        }
    }

    fn on_read_text_file(
        &self,
        request: acp::ReadTextFileRequest,
        responder: Responder<acp::ReadTextFileResponse>,
    ) {
        let Some((_, handle)) = self.session_for_acp_id(&request.session_id) else {
            let _ = responder.respond_with_error(
                acp::Error::invalid_params().data(serde_json::json!("unknown session")),
            );
            return;
        };
        let root = handle.thread.lock().unwrap().root.clone();
        if !resolves_inside(&root, &request.path) {
            let _ = responder.respond_with_error(
                acp::Error::invalid_params().data(serde_json::json!("path is outside the project")),
            );
            return;
        }
        // An open buffer's text, not the disk's: what the agent must see is
        // what the user sees, unsaved edits included — Zed's
        // `handle_read_text_file` reads the buffer for the same reason
        // (agent_servers/src/acp.rs:4787-4812).
        let canonical = std::fs::canonicalize(&request.path).unwrap_or(request.path.clone());
        let buffer_text = self.buffers.read().unwrap().values().find_map(|state| {
            let state = state.lock().unwrap();
            (state.file_path() == Some(&canonical)).then(|| state.buffer.text())
        });
        let text = match buffer_text {
            Some(text) => Ok(text),
            None => std::fs::read_to_string(&request.path),
        };
        match text {
            Ok(text) => {
                let text = clip_lines(&text, request.line, request.limit);
                let _ = responder.respond(acp::ReadTextFileResponse::new(text));
            }
            Err(err) => {
                let _ = responder
                    .respond_with_error(acp::Error::internal_error().data(err.to_string()));
            }
        }
    }
}

/// What `path` holds right now: the open buffer's text when one holds the
/// file — unsaved edits included, which is what the user sees and what the
/// agent was shown — else the file on disk. `None` when there is neither:
/// the file does not exist, or is not text.
///
/// Canonicalized before the buffer lookup, because that is the spelling
/// buffers hold (`Engine::open_file`).
pub(crate) fn current_text(buffers: &Buffers, path: &Path) -> Option<String> {
    let canonical = std::fs::canonicalize(path).unwrap_or(path.to_path_buf());
    let buffer_text = buffers.read().unwrap().values().find_map(|state| {
        let state = state.lock().unwrap();
        (state.file_path() == Some(canonical.as_path())).then(|| state.buffer.text())
    });
    buffer_text.or_else(|| std::fs::read_to_string(&canonical).ok())
}

/// Drop the machine detail proot puts after its own message.
///
/// `proot error: 'claude-code-acp' not found (root = /data/user/0/…, cwd =
/// /data/user/0/…, $PATH=/usr/local/sbin:…)` — the sentence is the first
/// clause and the parenthetical is three absolute paths, which on a phone is
/// six wrapped lines of panel pushing the way out of trouble off the screen.
/// It is in logcat either way. Only proot's own shape is trimmed, and only
/// when the sentence survives it: an agent's message is never touched.
fn trim_proot_detail(line: &str) -> String {
    let Some(head) = line.split(" (root = ").next() else {
        return line.to_owned();
    };
    if head.len() < line.len() && !head.trim().is_empty() {
        return head.trim().to_owned();
    }
    line.to_owned()
}

/// `line` (1-based) and `limit` applied the way the protocol describes them.
///
/// Sliced rather than split-and-rejoined: `lines()` throws away whether each
/// line ended `\n` or `\r\n` and whether the last one ended at all, and an
/// agent that reads a window of a CRLF file and writes it back would silently
/// rewrite the file's line endings.
fn clip_lines(text: &str, line: Option<u32>, limit: Option<u32>) -> String {
    if line.is_none() && limit.is_none() {
        return text.to_owned();
    }
    let skip = line.map(|l| l.saturating_sub(1) as usize).unwrap_or(0);
    let take = limit.map(|l| l as usize).unwrap_or(usize::MAX);

    // Byte offset just past the nth newline, or the end of the text.
    let after_newlines = |count: usize| -> usize {
        if count == 0 {
            return 0;
        }
        let mut seen = 0;
        for (offset, byte) in text.bytes().enumerate() {
            if byte == b'\n' {
                seen += 1;
                if seen == count {
                    return offset + 1;
                }
            }
        }
        text.len()
    };

    let start = after_newlines(skip);
    if start >= text.len() {
        return String::new();
    }
    let end = match take.checked_add(skip) {
        Some(through) if take != usize::MAX => after_newlines(through),
        _ => text.len(),
    };
    text[start..end.max(start)].to_owned()
}

// ---------------------------------------------------------------------------
// The symlink guard, engine-side
// ---------------------------------------------------------------------------

/// Whether `path`, once every existing component and symlink is resolved,
/// still lies inside `root` (which must itself be canonical).
///
/// The Rust twin of Kotlin's `SafeDelete.resolvesInside`, guarding the same
/// attack: a symlink inside the project pointing out of it makes an innocent
/// looking path land somewhere unrelated, and `File.isDirectory` /
/// `create_dir_all` follow symlinks without asking. For a path that does not
/// fully exist yet — an agent creating a new file in a new directory — the
/// deepest existing ancestor is what gets resolved, and the not-yet-existing
/// remainder must be plain names.
pub(crate) fn resolves_inside(root: &Path, path: &Path) -> bool {
    use std::path::Component;
    if !path.is_absolute() {
        return false;
    }
    // `..` and `.` in the un-created remainder cannot be resolved against
    // anything real, so they are refused outright rather than reasoned about.
    if path
        .components()
        .any(|c| matches!(c, Component::ParentDir | Component::CurDir))
    {
        return false;
    }
    let mut existing = path.to_path_buf();
    while !existing.exists() {
        match existing.parent() {
            Some(parent) => existing = parent.to_path_buf(),
            None => return false,
        }
    }
    match existing.canonicalize() {
        Ok(resolved) => resolved.starts_with(root),
        Err(_) => false,
    }
}

// ---------------------------------------------------------------------------
// Process + transport plumbing
// ---------------------------------------------------------------------------

/// Spawn the agent in the guest and wire its three pipes to a connection
/// thread. Returns false when the guest could not even be spawned.
fn start_agent(
    shared: Arc<AgentShared>,
    userland: Arc<guest::Userland>,
    spec: &AgentSpec,
    root: &Path,
) -> bool {
    let argv = spec.argv.iter().map(std::ffi::OsString::from).collect();
    let mut command = GuestCommand::new(format!("acp:{}", spec.name), argv).workdir(root);
    // The login shell's environment underneath, the spec's on top — Zed's
    // layering for a custom agent (project/src/agent_server_store.rs:
    // 1485-1493), and the reason an agent installed into `~/.local/bin`
    // starts from the panel exactly as it starts from the terminal. Last
    // writer wins, so anything the user put in settings.json still beats
    // what the shell said.
    for (key, value) in guest::login_environment(&userland, root) {
        command = command.env(key, value);
    }
    for (key, value) in &spec.env {
        command = command.env(key, value);
    }
    let Some(mut process) = guest::spawn(&userland, &command) else {
        return false;
    };
    // Paired with the subtraction in the watcher's tail below, which is the
    // one place this connection's processes stop being spent.
    guest::RESERVED_FOR_AGENT.fetch_add(PROCESSES_PER_AGENT, Ordering::Relaxed);

    let stdin = process.take_stdin().expect("spawn pipes stdin");
    let stdout = process.take_stdout().expect("spawn pipes stdout");
    let stderr = process.take_stderr().expect("spawn pipes stderr");

    // Writer: outgoing lines, newline-delimited JSON-RPC. Its channel closing
    // (the connection ended) closes the agent's stdin, which is the polite
    // half of shutdown.
    let (out_tx, out_rx) = std::sync::mpsc::channel::<String>();
    spawn_named("acp-write", move || {
        let mut stdin = stdin;
        for line in out_rx {
            log::debug!("acp → {line}");
            if stdin.write_all(line.as_bytes()).is_err()
                || stdin.write_all(b"\n").is_err()
                || stdin.flush().is_err()
            {
                return;
            }
        }
    });

    // Reader: incoming lines into the transport's stream. EOF ends the
    // stream, which ends the connection future, which ends its thread.
    let (in_tx, in_rx) = futures::channel::mpsc::unbounded::<std::io::Result<String>>();
    spawn_named("acp-read", move || {
        let mut reader = std::io::BufReader::new(stdout);
        let mut line = String::new();
        loop {
            line.clear();
            match reader.read_line(&mut line) {
                Ok(0) => return,
                Ok(_) => {
                    let trimmed = line.trim_end_matches(['\n', '\r']);
                    log::debug!("acp ← {trimmed}");
                    if in_tx.unbounded_send(Ok(trimmed.to_owned())).is_err() {
                        return;
                    }
                }
                Err(err) => {
                    let _ = in_tx.unbounded_send(Err(err));
                    return;
                }
            }
        }
    });

    // Stderr: the tail is what explains a failed launch; the log gets it all,
    // because a panic in the agent is otherwise invisible.
    {
        let shared = shared.clone();
        spawn_named("acp-stderr", move || {
            let mut reader = std::io::BufReader::new(stderr);
            let mut line = String::new();
            loop {
                line.clear();
                match reader.read_line(&mut line) {
                    Ok(0) | Err(_) => return,
                    Ok(_) => {
                        let trimmed = line.trim_end_matches(['\n', '\r']);
                        log::warn!("acp agent stderr: {trimmed}");
                        let mut tail = shared.stderr.lock().unwrap();
                        tail.push_str(trimmed);
                        tail.push('\n');
                        if tail.len() > STDERR_TAIL {
                            let cut = tail.len() - STDERR_TAIL;
                            let cut = tail
                                .char_indices()
                                .find(|(i, _)| *i >= cut)
                                .map(|(i, _)| i)
                                .unwrap_or(0);
                            tail.drain(..cut);
                        }
                    }
                }
            }
        });
    }

    // The watcher owns the process: it is the one place a `GuestProcess` is
    // dropped, and its Drop is the SIGQUIT-first shutdown. See the module doc
    // on racing the handshake against exit.
    {
        let shared = shared.clone();
        let started = Instant::now();
        spawn_named("acp-watch", move || {
            loop {
                if shared.shutdown.load(Ordering::Acquire) {
                    log::info!("acp: stopping agent \"{}\"", shared.name);
                    break;
                }
                if let Some(status) = process.exit_status() {
                    shared.agent_gone(shared.with_stderr(&format!("agent exited ({status})")));
                    break;
                }
                if !shared.initialized() && started.elapsed() > INITIALIZE_TIMEOUT {
                    shared.agent_gone("the agent did not answer initialize in time".to_owned());
                    break;
                }
                thread::sleep(WATCH_INTERVAL);
            }
            // Dropping is the shutdown: SIGQUIT first, SIGKILL as the last
            // resort, tracees included (guest.rs::terminate). Never a bare
            // kill — proot ignores SIGTERM and orphans its tracees otherwise.
            drop(process);
            // Ours only — a replacement agent may already be holding its own.
            guest::RESERVED_FOR_AGENT.fetch_sub(PROCESSES_PER_AGENT, Ordering::Relaxed);
            shared.dead.store(true, Ordering::Release);
        });
    }

    // The connection loop, on the one thread sized for it.
    {
        let shared = shared.clone();
        let sink = futures::sink::unfold(out_tx, |tx, line: String| async move {
            tx.send(line).map_err(|_| {
                std::io::Error::new(std::io::ErrorKind::BrokenPipe, "agent stdin closed")
            })?;
            Ok::<_, std::io::Error>(tx)
        });
        let transport = Lines::new(sink, in_rx);
        thread::Builder::new()
            .name("acp-connection".to_owned())
            .stack_size(CONNECTION_STACK_SIZE)
            .spawn(move || {
                futures::executor::block_on(run_connection(shared.clone(), transport));
                // However the loop ended — clean EOF, error, shutdown — the
                // sessions must not be left waiting on a wire nobody holds.
                shared.agent_gone(shared.with_stderr("the agent connection closed"));
                shared.shutdown.store(true, Ordering::Release);
            })
            .expect("failed to spawn the acp connection thread");
    }
    true
}

/// Map an agent's session id onto ours. Idempotent, so the load path can
/// call it before the request and the common path after the response.
fn index_session(shared: &AgentShared, acp_id: &acp::SessionId, our_id: u64) {
    if let Some(displaced) = shared.index.lock().unwrap().insert(acp_id.clone(), our_id)
        && displaced != our_id
    {
        // The protocol requires unique session ids; an agent that reuses one
        // has just stolen the older session's updates.
        log::warn!("acp: agent reused session id {acp_id:?}; session {displaced} loses it");
    }
}

/// The session an elicitation's scope names, if it names one.
fn scope_session(scope: &acp::ElicitationScope) -> Option<acp::SessionId> {
    match scope {
        acp::ElicitationScope::Session(scope) => Some(scope.session_id.clone()),
        _ => None,
    }
}

fn spawn_named(name: &str, f: impl FnOnce() + Send + 'static) {
    thread::Builder::new()
        .name(name.to_owned())
        .spawn(f)
        .unwrap_or_else(|err| panic!("failed to spawn {name}: {err}"));
}

/// The SDK connection: Zed's handler set, with the same newline-delimited
/// transport (agent_servers/src/acp.rs:678-765).
///
/// Every handler registered here is a capability advertised in
/// [`agent_main`], and the two lists must stay the same length: a capability
/// with no handler is a request that hangs, and a handler with no capability
/// is a request that never arrives.
async fn run_connection(
    shared: Arc<AgentShared>,
    transport: impl agent_client_protocol::ConnectTo<Client> + 'static,
) {
    let permission_shared = shared.clone();
    let write_shared = shared.clone();
    let read_shared = shared.clone();
    let update_shared = shared.clone();
    let elicit_shared = shared.clone();
    let elicit_done_shared = shared.clone();
    let create_terminal_shared = shared.clone();
    let terminal_output_shared = shared.clone();
    let wait_terminal_shared = shared.clone();
    let kill_terminal_shared = shared.clone();
    let release_terminal_shared = shared.clone();
    let question_shared = shared.clone();
    let extension_shared = shared.clone();
    let main_shared = shared.clone();

    let result = Client
        .builder()
        .name("seeker-code")
        .on_receive_request(
            async move |request: acp::RequestPermissionRequest,
                        responder: Responder<acp::RequestPermissionResponse>,
                        _cx| {
                permission_shared.on_permission(request, responder);
                Ok(())
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_request(
            async move |request: acp::CreateElicitationRequest,
                        responder: Responder<acp::CreateElicitationResponse>,
                        _cx| {
                elicit_shared.on_create_elicitation(request, responder);
                Ok(())
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_request(
            async move |request: acp::CreateTerminalRequest,
                        responder: Responder<acp::CreateTerminalResponse>,
                        _cx| {
                create_terminal_shared.on_create_terminal(request, responder);
                Ok(())
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_request(
            async move |request: acp::TerminalOutputRequest,
                        responder: Responder<acp::TerminalOutputResponse>,
                        _cx| {
                terminal_output_shared.on_terminal_output(request, responder);
                Ok(())
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_request(
            async move |request: acp::WaitForTerminalExitRequest,
                        responder: Responder<acp::WaitForTerminalExitResponse>,
                        _cx| {
                wait_terminal_shared.on_wait_for_terminal_exit(request, responder);
                Ok(())
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_request(
            async move |request: acp::KillTerminalRequest,
                        responder: Responder<acp::KillTerminalResponse>,
                        _cx| {
                kill_terminal_shared.on_kill_terminal(request, responder);
                Ok(())
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_request(
            async move |request: acp::ReleaseTerminalRequest,
                        responder: Responder<acp::ReleaseTerminalResponse>,
                        _cx| {
                release_terminal_shared.on_release_terminal(request, responder);
                Ok(())
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_request(
            async move |request: acp::WriteTextFileRequest,
                        responder: Responder<acp::WriteTextFileResponse>,
                        _cx| {
                write_shared.on_write_text_file(request, responder);
                Ok(())
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_request(
            async move |request: acp::ReadTextFileRequest,
                        responder: Responder<acp::ReadTextFileResponse>,
                        _cx| {
                read_shared.on_read_text_file(request, responder);
                Ok(())
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_notification(
            async move |notification: acp::SessionNotification, _cx| {
                update_shared.on_session_update(notification);
                Ok(())
            },
            agent_client_protocol::on_receive_notification!(),
        )
        .on_receive_notification(
            async move |notification: acp::CompleteElicitationNotification, _cx| {
                elicit_done_shared.on_complete_elicitation(notification);
                Ok(())
            },
            agent_client_protocol::on_receive_notification!(),
        )
        // ---- and the untyped pair, LAST --------------------------------
        //
        // `UntypedMessage` matches every method (jsonrpc.rs: `matches_method`
        // returns true), so these two must be registered after every typed
        // handler above — handlers run in registration order and `Handled::No`
        // falls through, so a trailing catch-all cannot shadow a typed one but
        // a leading one would shadow all of them. Anything not claimed here
        // reaches the role default, which answers `-32601`: correct for a
        // `_spettro/*` method this build does not serve, and what the agent
        // degrades on.
        //
        // Deliberately not `acp::ExtRequest`: it does not implement
        // `JsonRpcRequest`, and the typed `AgentRequest::ExtMethodRequest`
        // route would swallow every inbound request instead of one family of
        // them. Note that `UntypedMessage::method` keeps its leading `_`,
        // which the typed enums strip.
        .on_receive_request(
            async move |request: agent_client_protocol::UntypedMessage,
                        responder: Responder<serde_json::Value>,
                        _cx| {
                if request.method.as_str() == "_spettro/question/ask" {
                    question_shared.on_spettro_question(request, responder);
                    Ok(agent_client_protocol::Handled::Yes)
                } else {
                    Ok(agent_client_protocol::Handled::No {
                        message: (request, responder),
                        retry: false,
                    })
                }
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_notification(
            async move |notification: agent_client_protocol::UntypedMessage, cx| {
                if extension_shared.on_extension_notification(&notification) {
                    Ok(agent_client_protocol::Handled::Yes)
                } else {
                    Ok(agent_client_protocol::Handled::No {
                        message: (notification, cx),
                        retry: false,
                    })
                }
            },
            agent_client_protocol::on_receive_notification!(),
        )
        .connect_with(transport, async move |cx| agent_main(main_shared, cx).await)
        .await;
    if let Err(err) = result {
        log::info!("acp: connection ended: {err}");
    }
}

/// The client half of Spettro's extension handshake — **the gate**.
///
/// This one object is the difference between the phone rendering Spettro's
/// ask-user forms and the phone being walked through them one permission
/// prompt at a time. Spettro's `parseClientExtensions` reads exactly
/// `params._meta["spettro.app/extensions"]["methods"]` and nothing else, so:
///
///  * it must be the **top-level** `_meta` of `initialize`, not
///    `clientCapabilities._meta` — which is where the SDK's own
///    `MetaCapabilityExt` helpers write, and why they are not used here;
///  * `methods` must name every `_spettro/*` request this client actually
///    serves, which is one: the trailing untyped handler in
///    [`run_connection`];
///  * getting it wrong fails **silently**. There is no error anywhere; the
///    forms simply arrive as something worse.
///
/// Sent unconditionally, to every agent. `_meta` is the protocol's own
/// extension point and an agent that has never heard of this key ignores it,
/// so there is nothing to gate it on — and the alternative, guessing which
/// agent we are talking to before it has said, is a guess made before the
/// only sentence that could answer it.
fn client_extensions() -> acp::Meta {
    let mut meta = acp::Meta::new();
    meta.insert(
        "spettro.app/extensions".to_owned(),
        serde_json::json!({
            "version": 4,
            "methods": ["_spettro/question/ask"],
        }),
    );
    meta
}

/// The connection's own startup: initialize, then serve session requests
/// until the transport closes.
async fn agent_main(shared: Arc<AgentShared>, cx: ConnectionTo<Agent>) -> Result<(), acp::Error> {
    *shared.connection.lock().unwrap() = Some(cx.clone());

    // What we can actually do, in the shape Zed sends it
    // (agent_servers/src/acp.rs:766-793). Every line here is a promise the
    // handlers below keep, and — the other direction — an agent is entitled to
    // stay silent about anything we do not claim. `session.configOptions` is
    // the one that bites: the panel's selector chips are driven entirely by
    // `ConfigOptionUpdate`, and a conformant agent sends none of those unless
    // this capability says we can render them.
    let capabilities = acp::ClientCapabilities::new()
        .fs(acp::FileSystemCapabilities::new()
            .read_text_file(true)
            .write_text_file(true))
        .terminal(true)
        // An auth method that says "run this in a terminal" is only offerable
        // to a client that has one. We do now, so agents whose login is a
        // command (`claude /login` and its kin) can say so.
        .auth(acp::AuthCapabilities::new().terminal(true))
        // Both modes, because the panel draws both: a form from the schema,
        // and a link with a "done" for the sign-in-and-come-back flow.
        .elicitation(
            acp::ElicitationCapabilities::new()
                .form(acp::ElicitationFormCapabilities::new())
                .url(acp::ElicitationUrlCapabilities::new()),
        )
        .session(
            acp::ClientSessionCapabilities::new().config_options(
                acp::SessionConfigOptionsCapabilities::new()
                    .boolean(acp::BooleanConfigOptionCapabilities::new()),
            ),
        );
    let initialize = cx
        .send_request(
            acp::InitializeRequest::new(ProtocolVersion::V1)
                .client_capabilities(capabilities)
                .client_info(acp::Implementation::new(
                    "seeker-code",
                    crate::ENGINE_VERSION,
                ))
                .meta(client_extensions()),
        )
        .block_task()
        .await;

    let response = match initialize {
        Ok(response) => response,
        Err(err) => {
            let message = shared.with_stderr(&format!("the agent failed to initialize: {err}"));
            log::info!("acp: {message}");
            fail_startup(&shared, message);
            // Returning ends the connection; the watcher then takes the
            // process down.
            shared.shutdown.store(true, Ordering::Release);
            return Ok(());
        }
    };
    if response.protocol_version < ProtocolVersion::V1 {
        fail_startup(
            &shared,
            format!(
                "the agent speaks ACP protocol version {:?}, which is too old",
                response.protocol_version
            ),
        );
        shared.shutdown.store(true, Ordering::Release);
        return Ok(());
    }

    let info = AgentInfo {
        name: shared.name.clone(),
        agent_name: response.agent_info.as_ref().map(|info| info.name.clone()),
        agent_version: response
            .agent_info
            .as_ref()
            .map(|info| info.version.clone())
            .filter(|version| !version.is_empty()),
        auth_methods: serde_json::to_value(&response.auth_methods)
            .unwrap_or(serde_json::Value::Null),
        embedded_context: response
            .agent_capabilities
            .prompt_capabilities
            .embedded_context,
        images: response.agent_capabilities.prompt_capabilities.image,
        caps: AgentCaps::read(&response.agent_capabilities),
        // The other half of the handshake: what the agent says it serves.
        // Trusted as it stands — the shipping CLI answers `version: 4` while
        // its own docs still say 3, so the number on the wire is the only one
        // worth believing.
        spettro_extensions: response
            .meta
            .as_ref()
            .and_then(|meta| meta.get("spettro.app/extensions"))
            .cloned(),
    };
    log::info!(
        "acp: \"{}\" initialized (agent {:?} {:?}, spettro extensions {:?})",
        shared.name,
        info.agent_name,
        info.agent_version,
        info.spettro_extensions
    );
    // Ready first, then drain: a session arriving between the two lands in
    // neither limbo — `acp_start_session` checks the phase under this lock.
    let pending: Vec<PendingSession> = {
        let mut init = shared.init.lock().unwrap();
        *init = InitPhase::Ready(info);
        shared.pending_sessions.lock().unwrap().drain(..).collect()
    };
    for pending in pending {
        let task_shared = shared.clone();
        let task_cx = cx.clone();
        let _ = cx.spawn(async move {
            create_session(task_shared, task_cx, pending).await;
            Ok(())
        });
    }

    // Serve until the transport closes; handlers and spawned tasks do the
    // rest. (Zed pends the same way, agent_servers/src/acp.rs:757-763.)
    futures::future::pending::<()>().await;
    Ok(())
}

fn fail_startup(shared: &AgentShared, message: String) {
    {
        let mut init = shared.init.lock().unwrap();
        *init = InitPhase::Failed(message.clone());
    }
    shared.agent_gone(message);
}

/// Ask the agent for a session and wire the answer up. Runs as a task on the
/// connection's event loop.
/// Ask the agent for a session and wire the answer up.
///
/// `resume` names a session the agent already has, in which case this is
/// `session/load` — which replays the whole conversation back at us as
/// ordinary `session/update`s, so the thread fills in on its own — or
/// `session/resume`, which continues it with no history at all. Zed prefers
/// load and falls back to resume (agent_ui/src/conversation_view.rs:1110-1128)
/// for the same reason: an agent that can give the history back should.
async fn create_session(
    shared: Arc<AgentShared>,
    cx: ConnectionTo<Agent>,
    pending: PendingSession,
) {
    let PendingSession {
        id: our_id,
        resume,
        context_servers,
    } = pending;
    let Some(handle) = shared.session(our_id) else {
        return;
    };
    let root = handle.thread.lock().unwrap().root.clone();
    let caps = shared.caps();
    // The same list on every way in: a reopened conversation gets the
    // servers settings.json has *now*, as Zed's `load_session` does
    // (agent_servers/src/acp.rs, `mcp_servers_for_project` on both paths).
    let servers = mcp_servers(&context_servers, caps);
    let result = match resume {
        Some(id) if caps.load_session => {
            let id = acp::SessionId::new(id);
            // Indexed *before* the request: `session/load` replays the
            // history as updates, and an agent that starts replaying before
            // its own response lands would otherwise be talking about a
            // session we have not mapped yet. The id is the one we asked
            // for, so there is nothing to wait for.
            index_session(&shared, &id, our_id);
            cx.send_request(acp::LoadSessionRequest::new(id.clone(), root).mcp_servers(servers))
                .block_task()
                .await
                .map(|response: acp::LoadSessionResponse| {
                    (id, response.modes, response.config_options)
                })
        }
        Some(id) if caps.resume => {
            let id = acp::SessionId::new(id);
            index_session(&shared, &id, our_id);
            cx.send_request(acp::ResumeSessionRequest::new(id.clone(), root).mcp_servers(servers))
                .block_task()
                .await
                .map(|response: acp::ResumeSessionResponse| {
                    (id, response.modes, response.config_options)
                })
        }
        Some(_) => {
            handle.update(|thread| {
                thread.fail("this agent cannot reopen a past conversation".to_owned())
            });
            return;
        }
        None => cx
            .send_request(acp::NewSessionRequest::new(root).mcp_servers(servers))
            .block_task()
            .await
            .map(|response: acp::NewSessionResponse| {
                (response.session_id, response.modes, response.config_options)
            }),
    };
    // The session may have been closed while `session/new` was in flight;
    // indexing it then would leave a mapping nothing can ever reach.
    if shared.session(our_id).is_none() {
        return;
    }
    match result {
        Ok((acp_id, modes, config_options)) => {
            index_session(&shared, &acp_id, our_id);
            handle.update(|thread| {
                thread.ready(acp_id.clone(), modes, config_options.unwrap_or_default())
            });
            // Updates that raced the response are still in arrival order.
            let early: Vec<acp::SessionUpdate> = {
                let mut buffered = shared.early_updates.lock().unwrap();
                let (ours, rest): (Vec<_>, Vec<_>) =
                    buffered.drain(..).partition(|(id, _)| *id == acp_id);
                *buffered = rest;
                ours.into_iter().map(|(_, update)| update).collect()
            };
            if !early.is_empty() {
                handle.update(|thread| {
                    for update in early {
                        thread.apply_update(update);
                    }
                });
            }
            // A prompt typed while the session was starting goes out now. Its
            // entry is already on screen, so nothing is pushed for it here.
            let queued = handle.update(|thread| thread.take_queued_prompt());
            if let Some(prompt) = queued {
                start_prompt(&shared, &cx, our_id, prompt, false);
            }
        }
        Err(err) if err.code == acp::ErrorCode::AuthRequired => {
            handle.update(|thread| {
                thread.auth_required("the agent wants you to sign in first".to_owned())
            });
        }
        Err(err) => {
            handle.update(|thread| thread.fail(shared.with_stderr(&format!("{err}"))));
        }
    }
}

/// Kick off one prompt turn as a task on the connection loop. `push` says
/// whether the optimistic user entry still needs pushing (a queued follow-up
/// does; a prompt from the UI was already pushed on the caller's thread).
fn start_prompt(
    shared: &Arc<AgentShared>,
    cx: &ConnectionTo<Agent>,
    our_id: u64,
    prompt: PromptInput,
    push: bool,
) {
    let task_shared = shared.clone();
    let task_cx = cx.clone();
    let _ = cx.spawn(async move {
        run_prompt(task_shared, task_cx, our_id, prompt, push).await;
        // Never propagate an error: a task error tears the whole connection
        // down (SDK contract), and a failed turn is a session-level fact.
        Ok(())
    });
}

async fn run_prompt(
    shared: Arc<AgentShared>,
    cx: ConnectionTo<Agent>,
    our_id: u64,
    prompt: PromptInput,
    push: bool,
) {
    let mut prompt = prompt;
    let mut push = push;
    loop {
        let Some(handle) = shared.session(our_id) else {
            return;
        };
        let (acp_id, root) = {
            let thread = handle.thread.lock().unwrap();
            let Some(acp_id) = thread.acp_id.clone() else {
                return;
            };
            (acp_id, thread.root.clone())
        };
        if push {
            handle.update(|thread| thread.push_user_message(&prompt.text));
        }
        let blocks = prompt_blocks(
            &root,
            shared.supports_embedded_context(),
            shared.supports_images(),
            &prompt,
        );
        let result = cx
            .send_request(acp::PromptRequest::new(acp_id, blocks))
            .block_task()
            .await;

        let (next, settled) = handle.update(|thread| {
            let next = match result {
                Ok(response) => {
                    // What this turn cost, before the response is reduced to its
                    // stop reason. `usage` here is the *turn's* accounting and is
                    // a different number from the `UsageUpdate` gauge: that one
                    // is occupancy and can fall after a compaction, this one is
                    // what the turn spent.
                    if let Some(turn_usage) = turn_usage_json(&response) {
                        thread.set_turn_usage(turn_usage);
                    }
                    thread.end_turn(response.stop_reason)
                }
                Err(err) if err.code == acp::ErrorCode::AuthRequired => {
                    thread.auth_required("the agent wants you to sign in first".to_owned());
                    None
                }
                Err(err) => {
                    thread.fail_turn(shared.with_stderr(&format!("{err}")));
                    None
                }
            };
            // A follow-up to send means the turn settled and drained the
            // queue, which has already counted the next one in — so ask the
            // question that way round rather than reading the counter after
            // it has moved on.
            let settled = next.is_some() || thread.is_settled();
            (next, settled)
        });
        // However the turn ended, no permission question may outlive it: the
        // spec requires cancelled answers on cancellation, and a settled turn
        // has no open questions.
        //
        // **Only once the session has settled.** A steering prompt is
        // answered `end_turn` within milliseconds while the turn it steers
        // runs on, and cancelling that turn's open permission request — which
        // is what this did unconditionally — is the user pressing Stop on the
        // very work they were trying to redirect.
        if settled {
            handle.cancel_permissions();
        }
        match next {
            Some(follow_up) => {
                prompt = follow_up;
                // `take_queued_prompt` has already made sure the entry showing
                // it is on screen; pushing again would double it.
                push = false;
            }
            None => return,
        }
    }
}

/// What one turn cost, from the `session/prompt` response, in the shape the
/// panel's turn readout wants:
/// `{"inputTokens","outputTokens","totalTokens","cachedReadTokens",
/// "cachedWriteTokens","tokensUsed"}`.
///
/// `None` for an agent that reports no usage at all, which leaves the last
/// turn's figures alone rather than blanking them — a turn that said nothing
/// about its cost has not said the cost was zero.
fn turn_usage_json(response: &acp::PromptResponse) -> Option<serde_json::Value> {
    let usage = response.usage.as_ref()?;
    Some(serde_json::json!({
        "inputTokens": usage.input_tokens,
        "outputTokens": usage.output_tokens,
        "totalTokens": usage.total_tokens,
        "cachedReadTokens": usage.cached_read_tokens,
        "cachedWriteTokens": usage.cached_write_tokens,
        // Spettro puts the monotonic spend on the response's own `_meta`,
        // and on the usage object's when it has one; take either.
        "tokensUsed": crate::acp_thread::meta_u64(
            response.meta.as_ref(),
            crate::acp_thread::TOKENS_USED_KEY,
        )
        .or_else(|| {
            crate::acp_thread::meta_u64(
                usage.meta.as_ref(),
                crate::acp_thread::TOKENS_USED_KEY,
            )
        }),
    }))
}

/// A `file://` URI for `path`, percent-encoded.
///
/// Not `format!("file://{}", path.display())`, which was the bug: a project
/// with `docs/RFC#42.md` in it produced a URI whose `#` starts a fragment, so
/// every agent that parses it with a real URL library reads the path as
/// `docs/RFC` — the mention silently contributes nothing, and on the embedded
/// branch a write-back targets the wrong file. `?` and `%` are the same
/// story. `Url::from_file_path` does the encoding and wants an absolute path,
/// which every path here is (it is `root.join(mention)` under a canonical
/// root); the fallback keeps the old shape rather than dropping the mention.
fn file_uri(path: &Path) -> String {
    match url::Url::from_file_path(path) {
        Ok(url) => url.to_string(),
        Err(()) => format!("file://{}", path.display()),
    }
}

/// A mentioned file bigger than this travels as a link even to an agent that
/// takes embedded context: the whole prompt goes down one pipe, and a huge
/// paste starves the turn.
const MAX_EMBEDDED_MENTION_BYTES: u64 = 256 * 1024;

/// How much of a directory is embedded, all files together, and how many of
/// them: past either cap the rest travel as links that name them. A
/// directory mention is "look at this module", not "paste the repository".
const MAX_DIRECTORY_BYTES: u64 = 512 * 1024;
const MAX_DIRECTORY_FILES: usize = 200;

/// The prompt as content blocks: the text first, then one block per
/// @-mention — embedded text when the agent's prompt capabilities take it,
/// a resource link otherwise, which is Zed's own split
/// (agent_ui/src/message_editor.rs:2150-2161). A mention that resolves
/// outside the project is dropped: the same `resolves_inside` boundary the
/// fs handlers enforce, applied before anything is read.
fn prompt_blocks(
    root: &Path,
    embedded: bool,
    images: bool,
    prompt: &PromptInput,
) -> Vec<acp::ContentBlock> {
    let mut blocks = vec![acp::ContentBlock::from(prompt.text.clone())];
    // Pictures first among the attachments, and only to an agent that said it
    // reads them: `promptCapabilities.image` is the agent's own answer, and an
    // image block sent to one that never claimed it is at best ignored and at
    // worst a protocol error. The panel does not offer the button in that
    // case either, so this is the second of two gates rather than the only
    // one — the composer's draft can outlive a change of agent.
    if images {
        for image in &prompt.images {
            blocks.push(acp::ContentBlock::Image(acp::ImageContent::new(
                image.data.clone(),
                image.mime_type.clone(),
            )));
        }
    } else if !prompt.images.is_empty() {
        log::warn!(
            "acp: dropping {} image(s) — the agent does not take them",
            prompt.images.len()
        );
    }
    for mention in &prompt.mentions {
        // Anything file-shaped is confined first, before a byte is read.
        if let Some(relative) = mention.path() {
            let path = root.join(relative);
            if !resolves_inside(root, &path) {
                log::warn!("acp: mention {relative:?} resolves outside the project; dropped");
                continue;
            }
        }
        match mention {
            Mention::File { path } | Mention::Rules { path } => {
                blocks.push(file_block(root, embedded, path));
            }
            Mention::Directory { path } => blocks.extend(directory_blocks(root, embedded, path)),
            Mention::Symbol {
                path,
                name,
                start_row,
                end_row,
                text,
            } => {
                // Zed's symbol URI: the file, `?symbol=name`, and the 1-based
                // line range as the fragment (mention.rs:482-497).
                let mut url = file_url(&root.join(path));
                url.query_pairs_mut().append_pair("symbol", name);
                url.set_fragment(Some(&format!("L{}:{}", start_row + 1, end_row + 1)));
                blocks.push(text_block(embedded, name.clone(), url.to_string(), text));
            }
            Mention::Selection {
                path,
                start_row,
                end_row,
                text,
            } => {
                // Zed's selection URI: the file and the line range
                // (mention.rs:498-522).
                let mut url = file_url(&root.join(path));
                url.set_fragment(Some(&format!("L{}:{}", start_row + 1, end_row + 1)));
                let name = format!(
                    "{} ({}-{})",
                    path.rsplit('/').next().unwrap_or(path),
                    start_row + 1,
                    end_row + 1
                );
                blocks.push(text_block(embedded, name, url.to_string(), text));
            }
            Mention::Thread {
                session,
                title,
                text,
            } => {
                // Zed's `zed:///agent/thread/{id}?name=…` (mention.rs:523-528),
                // under this app's own scheme.
                let mut url = url::Url::parse("seeker:///").expect("a constant URL parses");
                url.set_path(&format!("/agent/thread/{session}"));
                url.query_pairs_mut().append_pair("name", title);
                blocks.push(text_block(embedded, title.clone(), url.to_string(), text));
            }
            Mention::Fetch { url, text } => {
                // The page's own URL names the resource, as Zed's
                // `MentionUri::Fetch` does (mention.rs:552-553).
                blocks.push(text_block(embedded, url.clone(), url.clone(), text));
            }
            Mention::Diagnostics { text } => {
                blocks.push(text_block(
                    embedded,
                    "Diagnostics".to_owned(),
                    "seeker:///agent/diagnostics".to_owned(),
                    text,
                ));
            }
        }
    }
    blocks
}

/// One file as a block: its text embedded when the agent takes that and the
/// file is small enough and readable, a link that names it otherwise.
/// `relative` has already been confined to the project.
fn file_block(root: &Path, embedded: bool, relative: &str) -> acp::ContentBlock {
    let path = root.join(relative);
    let name = path
        .file_name()
        .map(|name| name.to_string_lossy().into_owned())
        .unwrap_or_else(|| relative.to_owned());
    let uri = file_uri(&path);
    let small_enough = std::fs::metadata(&path)
        .map(|meta| meta.len() <= MAX_EMBEDDED_MENTION_BYTES)
        .unwrap_or(false);
    if embedded && small_enough {
        match std::fs::read_to_string(&path) {
            Ok(content) => acp::ContentBlock::Resource(acp::EmbeddedResource::new(
                acp::EmbeddedResourceResource::TextResourceContents(
                    acp::TextResourceContents::new(content, uri),
                ),
            )),
            // Unreadable (binary, gone): the link still names it.
            Err(_) => acp::ContentBlock::ResourceLink(acp::ResourceLink::new(name, uri)),
        }
    } else {
        acp::ContentBlock::ResourceLink(acp::ResourceLink::new(name, uri))
    }
}

/// Every text file under a directory, as file blocks, in path order —
/// Zed's directory mention (mention.rs `Directory`, message_editor.rs
/// `directory_contents`). Dot-entries are skipped (`.git` above all), and
/// so is anything past the size and count caps, which then travels as a
/// link. `relative` has already been confined to the project.
fn directory_blocks(root: &Path, embedded: bool, relative: &str) -> Vec<acp::ContentBlock> {
    let dir = root.join(relative);
    let mut files: Vec<PathBuf> = Vec::new();
    let mut pending = vec![dir.clone()];
    while let Some(current) = pending.pop() {
        let Ok(entries) = std::fs::read_dir(&current) else {
            continue;
        };
        let mut children: Vec<PathBuf> = entries
            .flatten()
            .map(|entry| entry.path())
            .filter(|path| {
                !path
                    .file_name()
                    .is_some_and(|name| name.to_string_lossy().starts_with('.'))
            })
            .collect();
        children.sort();
        for child in children {
            // Through a symlink is still inside the project or not; the
            // guard decides per entry, as the fs handlers do.
            if !resolves_inside(root, &child) {
                continue;
            }
            if child.is_dir() {
                pending.push(child);
            } else {
                files.push(child);
            }
        }
    }
    files.sort();
    let mut budget = MAX_DIRECTORY_BYTES;
    let mut blocks = Vec::with_capacity(files.len());
    for (index, path) in files.iter().enumerate() {
        let Ok(relative) = path.strip_prefix(root) else {
            continue;
        };
        let relative = relative.to_string_lossy().replace('\\', "/");
        let size = std::fs::metadata(path)
            .map(|meta| meta.len())
            .unwrap_or(u64::MAX);
        let fits = index < MAX_DIRECTORY_FILES && size <= budget;
        if fits {
            budget = budget.saturating_sub(size);
        }
        blocks.push(file_block(root, embedded && fits, &relative));
    }
    blocks
}

/// Text the platform or the engine already has — a selection, a fetched
/// page, a thread's summary, the diagnostics — as a block. Embedded, with
/// the URI that says what it is, for an agent that takes embedded
/// resources; for one that does not, the text itself as a text block under
/// a one-line heading, because a link to a page the agent cannot fetch or
/// a selection it cannot see is a mention that says nothing.
fn text_block(embedded: bool, name: String, uri: String, text: &str) -> acp::ContentBlock {
    if embedded {
        acp::ContentBlock::Resource(acp::EmbeddedResource::new(
            acp::EmbeddedResourceResource::TextResourceContents(acp::TextResourceContents::new(
                text.to_owned(),
                uri,
            )),
        ))
    } else {
        acp::ContentBlock::from(format!("[{name}]({uri})\n\n{text}"))
    }
}

/// [`file_uri`] as a URL, for the mentions that add a query or fragment.
fn file_url(path: &Path) -> url::Url {
    url::Url::from_file_path(path).unwrap_or_else(|()| {
        url::Url::parse(&format!("file://{}", path.display()))
            .unwrap_or_else(|_| url::Url::parse("file:///").expect("a constant URL parses"))
    })
}

/// A thread's transcript as text, for one thread mentioning another — the
/// user's messages, the agent's spoken replies, and one line per tool call.
/// Thoughts are left out: they are the agent's working, not the record.
/// Capped, because a long conversation pasted whole would starve the turn.
pub(crate) fn thread_summary(thread: &SessionThread) -> String {
    use crate::acp_thread::EntryBody;
    const MAX_SUMMARY_BYTES: usize = 64 * 1024;
    let mut out = String::new();
    for entry in &thread.entries {
        match &entry.body {
            EntryBody::User { markdown, .. } => {
                out.push_str("## User\n\n");
                out.push_str(markdown);
                out.push_str("\n\n");
            }
            EntryBody::Assistant { chunks } => {
                let spoken: String = chunks
                    .iter()
                    .filter(|chunk| !chunk.thought)
                    .map(|chunk| chunk.markdown.as_str())
                    .collect();
                if !spoken.trim().is_empty() {
                    out.push_str("## Agent\n\n");
                    out.push_str(&spoken);
                    out.push_str("\n\n");
                }
            }
            EntryBody::ToolCall(call) => {
                out.push_str(&format!("- {}: {}\n", call.kind, call.title));
            }
            EntryBody::CompletedPlan { .. } => {}
        }
        if out.len() > MAX_SUMMARY_BYTES {
            out.truncate(MAX_SUMMARY_BYTES);
            out.push_str("\n…\n");
            break;
        }
    }
    out
}

/// The project's diagnostics as one line each, `path:line:col: severity:
/// message [source code]` — the shape every compiler prints and every
/// agent reads. Capped at a page's worth; the count says what was left.
pub(crate) fn diagnostics_text(rows: &crate::lsp::ProjectDiagnosticRows) -> String {
    const MAX_ROWS: usize = 400;
    let mut out = String::new();
    let mut written = 0usize;
    let mut total = 0usize;
    for file in &rows.files {
        for row in &file.rows {
            total += 1;
            if written >= MAX_ROWS {
                continue;
            }
            written += 1;
            let severity = format!("{:?}", row.severity).to_lowercase();
            out.push_str(&format!(
                "{}:{}:{}: {}: {}",
                file.path,
                row.row + 1,
                row.col_utf16 + 1,
                severity,
                row.message.trim()
            ));
            match (&row.source, &row.code) {
                (Some(source), Some(code)) => out.push_str(&format!(" [{source} {code}]")),
                (Some(source), None) => out.push_str(&format!(" [{source}]")),
                (None, Some(code)) => out.push_str(&format!(" [{code}]")),
                (None, None) => {}
            }
            out.push('\n');
        }
    }
    if total == 0 {
        out.push_str("No diagnostics.\n");
    } else if total > written {
        out.push_str(&format!("… and {} more.\n", total - written));
    }
    out
}

/// Rows `start..=end` of `text`, 0-based, clamped — a symbol's lines.
fn rows_of(text: &str, start_row: u32, end_row: u32) -> String {
    let lines: Vec<&str> = text.split('\n').collect();
    let start = (start_row as usize).min(lines.len());
    let end = (end_row as usize + 1).min(lines.len()).max(start);
    lines[start..end].join("\n")
}

// ---------------------------------------------------------------------------
// Engine surface — what the JNI layer calls
// ---------------------------------------------------------------------------

impl crate::Engine {
    /// Start (or join) the configured agent and open a session for `project`.
    /// Returns a session id to poll; the session reports its own progress —
    /// `starting` until the agent answers, `unavailable` with a sentence if
    /// it never does. Blocks only for a process spawn.
    ///
    /// `spec_json` is an [`AgentSpec`]. A spec different from the running
    /// agent's replaces it — one agent process at a time, by budget.
    ///
    /// **`Err` means the caller is wrong, not that the agent is missing.** A
    /// spec that is not JSON or a project that does not exist is a bug on the
    /// Kotlin side and has nothing to show a user; everything the *user* can
    /// act on — no userland, an agent that will not spawn — comes back as a
    /// session id whose state carries the sentence, so the panel has one code
    /// path for "here is what went wrong" and never a second kind of failure
    /// to render.
    pub fn acp_start_session(&self, project: ProjectId, spec_json: &str) -> Result<u64, String> {
        self.start_session(project, spec_json, None)
    }

    /// Reopen one of the agent's own past sessions in a new thread.
    ///
    /// `acp_session_id` is a `sessionId` from [`Self::acp_session_list`]. The
    /// agent decides what that means: with `loadSession` it replays the whole
    /// conversation back as `session/update`s and the transcript fills in on
    /// its own; with only `session/resume` it continues the conversation with
    /// no history, which is why the panel says so. Errors exactly as
    /// [`Self::acp_start_session`] does.
    pub fn acp_resume_session(
        &self,
        project: ProjectId,
        spec_json: &str,
        acp_session_id: &str,
    ) -> Result<u64, String> {
        if acp_session_id.is_empty() {
            return Err("no session to reopen".to_owned());
        }
        self.start_session(project, spec_json, Some(acp_session_id.to_owned()))
    }

    fn start_session(
        &self,
        project: ProjectId,
        spec_json: &str,
        resume: Option<String>,
    ) -> Result<u64, String> {
        let spec: AgentSpec =
            serde_json::from_str(spec_json).map_err(|err| format!("bad agent spec: {err}"))?;
        if spec.argv.is_empty() {
            return Err("the agent has no command".to_owned());
        }
        let Some(root) = self.project_root(project) else {
            return Err("unknown project".to_owned());
        };
        let root = std::fs::canonicalize(&root).unwrap_or(root);
        // Read here, once per thread: this call already blocks for a spawn,
        // and a file read beside it is nothing. What the file says *now* is
        // what the thread gets — an edit lands on the next thread.
        let context_servers = self.settings().context_servers;

        let id = self.acp.next_session.fetch_add(1, Ordering::Relaxed) + 1;

        // No guest to run an agent in: a `full` build before Debian is
        // installed, or the `play` flavour, which has no agent panel at all.
        // Owner 0, because no agent owns it and none ever will — agent ids
        // start at 1.
        let Some(userland) = self.userland().filter(|userland| userland.is_installed()) else {
            let handle = Arc::new(SessionHandle::new(0, SessionThread::new(project, root)));
            handle.update(|thread| thread.fail("the Linux userland is not installed".to_owned()));
            self.acp.sessions.lock().unwrap().insert(id, handle);
            return Ok(id);
        };

        // One agent at a time: reuse it when the spec matches, replace it
        // when it does not. A dying agent is not reusable — `shutdown` is set
        // the moment anything decides it is over, while `dead` lags it by the
        // watcher's poll and proot's grace, and a session handed to an agent
        // in between would simply never be answered by anyone.
        //
        // The **root is part of the key**: the guest process binds the
        // project directory at spawn, so an agent started for one project is
        // blind to every other — reusing it across projects would hand out
        // sessions whose files the agent can never read. Threads within one
        // project share the process; a project switch replaces it.
        let key = format!("{}\u{0}{}", spec.key(), root.display());
        let mut slot = self.acp.agent.lock().unwrap();
        let reusable = slot
            .as_ref()
            .filter(|shared| {
                shared.key == key
                    && !shared.dead.load(Ordering::Acquire)
                    && !shared.shutdown.load(Ordering::Acquire)
            })
            .cloned();
        let shared = match reusable {
            Some(shared) => shared,
            None => {
                if let Some(old) = slot.take() {
                    shutdown_agent(&old);
                }
                let shared = AgentShared::new(
                    &spec,
                    key.clone(),
                    userland.clone(),
                    self.acp.sessions.clone(),
                    self.acp.index.clone(),
                    self.acp.written.clone(),
                    self.buffers.clone(),
                );
                if !start_agent(shared.clone(), userland, &spec, &root) {
                    drop(slot);
                    let handle = Arc::new(SessionHandle::new(0, SessionThread::new(project, root)));
                    handle
                        .update(|thread| thread.fail("the agent could not be started".to_owned()));
                    self.acp.sessions.lock().unwrap().insert(id, handle);
                    return Ok(id);
                }
                *slot = Some(shared.clone());
                shared
            }
        };
        // Stamped with its owner before anybody can see it, so a teardown
        // running concurrently on the *previous* agent cannot claim it.
        let handle = Arc::new(SessionHandle::new(
            shared.id,
            SessionThread::new(project, root.clone()),
        ));
        self.acp.sessions.lock().unwrap().insert(id, handle.clone());
        drop(slot);

        // Hand the session to the connection: directly when it is up, via the
        // pending list when initialize is still in flight. Checked under the
        // init lock so it cannot fall between the two.
        let pending = PendingSession {
            id,
            resume: resume.clone(),
            context_servers,
        };
        let init = shared.init.lock().unwrap();
        match &*init {
            InitPhase::Starting => shared.pending_sessions.lock().unwrap().push(pending),
            InitPhase::Ready(_) => match shared.connection() {
                Some(cx) => {
                    let task_shared = shared.clone();
                    let task_cx = cx.clone();
                    let _ = cx.spawn(async move {
                        create_session(task_shared, task_cx, pending).await;
                        Ok(())
                    });
                }
                // Initialized, but the wire has gone since — the agent died
                // after answering. Say so rather than leaving the session
                // `starting` for ever with nothing on its way to it.
                None => {
                    drop(init);
                    handle.update(|thread| {
                        thread.fail(shared.with_stderr("the agent connection closed"))
                    });
                    return Ok(id);
                }
            },
            InitPhase::Failed(message) => {
                let message = message.clone();
                drop(init);
                handle.update(|thread| thread.fail(message));
                return Ok(id);
            }
        }
        Ok(id)
    }

    /// The session's revision counter — poll it exactly like `lspVersion`.
    /// 0 for an id the engine has forgotten.
    ///
    /// A live session starts at 1 and every change moves it, so a poller that
    /// stores what it read and compares never misses the first transition —
    /// see the note on `SessionThread::revision` for the bug that is.
    pub fn acp_session_version(&self, session: u64) -> u64 {
        self.acp
            .sessions
            .lock()
            .unwrap()
            .get(&session)
            .map(|handle| handle.revision.load(Ordering::Acquire))
            .unwrap_or(0)
    }

    /// Everything but the entries, as JSON — see `SessionThread::state_json`.
    /// `"null"` for a forgotten id.
    pub fn acp_session_state(&self, session: u64) -> String {
        let Some(handle) = self.session_handle(session) else {
            return "null".to_owned();
        };
        let (agent, elicitations, questions) = {
            let slot = self.acp.agent.lock().unwrap();
            let elicitations = slot
                .as_ref()
                .map(|shared| shared.elicitations.for_session(session))
                .unwrap_or_default();
            // This session's Spettro questions, plus the ones that belong to
            // no session — same rule as the elicitations beside them, and the
            // same reason: a question with no thread is still blocking the
            // agent, so it is shown in whichever thread is open.
            let questions: Vec<serde_json::Value> = slot
                .as_ref()
                .map(|shared| shared.questions.view_json())
                .and_then(|view| view.as_array().cloned())
                .unwrap_or_default()
                .into_iter()
                .filter(|question| match question.get("session") {
                    Some(serde_json::Value::Number(id)) => id.as_u64() == Some(session),
                    _ => true,
                })
                .collect();
            let agent = match slot.as_ref().map(|shared| shared.init.lock().unwrap()) {
                Some(init) => match &*init {
                    InitPhase::Ready(info) => serde_json::json!({
                        "name": info.name,
                        "agent_name": info.agent_name,
                        "agent_version": info.agent_version,
                        "auth_methods": info.auth_methods,
                        // Every one of these is a method it is an error to
                        // call unasked, so they gate the panel's buttons.
                        "capabilities": info.caps,
                        "canOpenHistory": info.caps.can_open_history(),
                        // Not one of `caps`: those are session methods, this
                        // is what a *prompt* may carry. It gates the
                        // composer's attach button.
                        "images": info.images,
                        // Verbatim, and null for every agent that is not
                        // Spettro. Everything Spettro-specific in the UI
                        // gates on this being present; the workflow surface
                        // additionally on `version >= 4`.
                        "spettroExtensions": info.spettro_extensions,
                    }),
                    InitPhase::Starting => serde_json::json!({"starting": true}),
                    InitPhase::Failed(message) => serde_json::json!({"error": message}),
                },
                None => serde_json::Value::Null,
            };
            (agent, elicitations, questions)
        };
        let thread = handle.thread.lock().unwrap();
        let mut state = thread.state_json(agent);
        // Questions live on the connection, not in the thread's own state
        // machine — a request-scoped one belongs to no thread at all — so
        // they are folded in here rather than kept in `SessionThread`.
        if let Some(object) = state.as_object_mut() {
            object.insert("elicitations".to_owned(), elicitations.into());
            // Folded in here for the same reason, and so the panel's existing
            // 120 ms `acpSessionVersion` poll picks a question up without a
            // second poll loop of its own. `acpQuestionsVersion` exists only
            // for the session-less case, exactly as
            // `acpElicitationsVersion` does.
            object.insert("questions".to_owned(), questions.into());
        }
        state.to_string()
    }

    /// Version counter for [`Self::acp_pending_elicitations`] — poll this one
    /// integer and read the list only when it moves, the same
    /// counter-then-payload contract as `acp_session_version`.
    ///
    /// It moves whenever any of the agent's questions changes, session-scoped
    /// ones included: those are rarer than the poll by orders of magnitude,
    /// and one spare list read per question beats a second counter per scope.
    /// The connection's id rides in the high bits so replacing the agent —
    /// whose fresh counter restarts at zero — can never repeat a value a
    /// poller has already seen. 0 means no agent is running.
    pub fn acp_elicitations_version(&self) -> u64 {
        let shared = self.acp.agent.lock().unwrap().clone();
        shared
            .map(|shared| (shared.id << 32) + shared.elicitations.version())
            .unwrap_or(0)
    }

    /// The agent's questions that belong to no session — the protocol's
    /// *request* scope, as a JSON array in the same shape `elicitations`
    /// takes in the session state.
    ///
    /// No session argument, because there may be no session: an agent can ask
    /// for a token during `authenticate`, before any conversation exists.
    /// Those used to be unreachable, so the agent blocked for ever with
    /// nothing on screen.
    pub fn acp_pending_elicitations(&self) -> String {
        let shared = self.acp.agent.lock().unwrap().clone();
        let questions = shared
            .map(|shared| shared.elicitations.connection_level())
            .unwrap_or_default();
        serde_json::Value::Array(questions).to_string()
    }

    /// Put away the one-line notice saying why the last thing the user asked
    /// for did not happen. False for a session the engine has forgotten.
    pub fn acp_clear_notice(&self, session: u64) -> bool {
        let Some(handle) = self.session_handle(session) else {
            return false;
        };
        handle.update(|thread| thread.clear_notice());
        true
    }

    /// Answer one of the agent's questions. `action_json` is
    /// `{"action":"accept","content":{…}}`, `{"action":"decline"}` or
    /// `{"action":"cancel"}`; the content's JSON types are the protocol's
    /// types, so a field the panel drew as a switch comes back as a bool.
    ///
    /// A URL question stays on screen after an accept — the agent is
    /// watching for the sign-in and takes it away with
    /// `elicitation/complete`. False for a question that is gone or an
    /// action that is not one of the three.
    pub fn acp_respond_elicitation(&self, elicitation_id: &str, action_json: &str) -> bool {
        let shared = self.acp.agent.lock().unwrap().clone();
        let Some(shared) = shared else {
            return false;
        };
        if !shared.elicitations.respond(elicitation_id, action_json) {
            return false;
        }
        shared.bump_sessions(None);
        true
    }

    /// Version counter for [`Self::acp_pending_questions`] — the same
    /// counter-then-payload contract as [`Self::acp_elicitations_version`],
    /// connection id in the high bits and all.
    ///
    /// A question that belongs to a session also rides that session's own
    /// revision (it is folded into `acp_session_state`), so the panel needs
    /// this one only for a question raised before any session exists. 0 means
    /// no agent is running.
    pub fn acp_questions_version(&self) -> u64 {
        let shared = self.acp.agent.lock().unwrap().clone();
        shared
            .map(|shared| (shared.id << 32) + shared.questions.version())
            .unwrap_or(0)
    }

    /// Every open `_spettro/question/ask`, in the same shape the `questions`
    /// array of `acp_session_state` takes:
    /// `[{"id","session","payload"}]`, the payload verbatim.
    ///
    /// Unfiltered, unlike the session state's copy: this is the reader for a
    /// question that belongs to no session, and one of those blocks the agent
    /// from outside every thread.
    pub fn acp_pending_questions(&self) -> String {
        let shared = self.acp.agent.lock().unwrap().clone();
        shared
            .map(|shared| shared.questions.view_json().to_string())
            .unwrap_or_else(|| "[]".to_owned())
    }

    /// Answer one of Spettro's questions. `answer_json` is the JSON-RPC
    /// **result** the agent gets, built on the Kotlin side —
    /// `{"answers":[…]}` for a filled form, `{"kind":"declined"}` for a
    /// refusal — because the shape belongs to the extension, not to us.
    ///
    /// False for a question that is already gone **and** for malformed JSON,
    /// in which case nothing is sent: an agent answered with rubbish is worse
    /// off than an agent still waiting, which the user can still answer or
    /// cancel.
    pub fn acp_respond_question(&self, question_id: &str, answer_json: &str) -> bool {
        let Ok(answer) = serde_json::from_str::<serde_json::Value>(answer_json) else {
            log::warn!("acp: refusing to answer {question_id} with malformed JSON");
            return false;
        };
        let shared = self.acp.agent.lock().unwrap().clone();
        let Some(shared) = shared else {
            return false;
        };
        if !shared.questions.answer(question_id, answer) {
            return false;
        }
        shared.bump_sessions(None);
        true
    }

    /// The last `_spettro/account/update` the agent pushed, verbatim, or
    /// `"null"` when it has pushed none (or there is no agent).
    ///
    /// The agent owns the device-flow poller and pushes what it learns; this
    /// is how the login progresses on screen. Read it when
    /// [`Self::acp_account_version`] moves.
    pub fn acp_account_status(&self) -> String {
        let shared = self.acp.agent.lock().unwrap().clone();
        shared
            .and_then(|shared| shared.account.lock().unwrap().clone())
            .map(|account| account.to_string())
            .unwrap_or_else(|| "null".to_owned())
    }

    /// Version counter for [`Self::acp_account_status`], connection id in the
    /// high bits as everywhere else here. 0 means no agent is running.
    pub fn acp_account_version(&self) -> u64 {
        let shared = self.acp.agent.lock().unwrap().clone();
        shared
            .map(|shared| (shared.id << 32) + shared.account_version.load(Ordering::Acquire))
            .unwrap_or(0)
    }

    /// Call one of Spettro's `_spettro/*` methods and wait for the answer —
    /// the single seam every one of them goes through.
    ///
    /// Nineteen methods, none of them modelled here: `params_json` is sent as
    /// the request's params exactly as given and the result comes back
    /// exactly as it arrived. Modelling them in Rust would mean a second
    /// implementation of an extension whose shape is the agent's to change,
    /// and one more place to forget to update.
    ///
    /// **Blocking**, up to [`EXTENSION_TIMEOUT`]: `_spettro/account/status`
    /// takes up to 15 s and `_spettro/providers/connect` up to 30 s, because
    /// it verifies the key against the provider's own API. Call it off the
    /// main thread.
    ///
    /// The answer is an envelope, because JNI has no exception channel here
    /// and an error that arrives as `"null"` is a bug the user gets blamed
    /// for:
    ///
    /// ```json
    /// {"ok":true,"result":{…}}
    /// {"ok":false,"code":-32601,"message":"method not found"}
    /// {"ok":false,"code":0,"message":"the agent is not running"}
    /// ```
    ///
    /// `-32601` in particular is not a failure — it is an older CLI, and the
    /// panel says "update Spettro" rather than "something went wrong".
    pub fn acp_call_extension(
        &self,
        project: ProjectId,
        method: &str,
        params_json: &str,
    ) -> String {
        let _ = project;
        let params: serde_json::Value = match serde_json::from_str(params_json) {
            Ok(params) => params,
            // Ours, not the agent's: a malformed params string is a bug on
            // this side of JNI, and code 0 is what the envelope uses for
            // "this never reached the wire".
            Err(err) => return extension_failure(0, format!("bad params: {err}")),
        };
        let shared = self.acp.agent.lock().unwrap().clone();
        let Some(shared) = shared else {
            return extension_failure(0, "the agent is not running".to_owned());
        };
        let Some(cx) = shared.connection() else {
            return extension_failure(0, "the agent is not running".to_owned());
        };
        let request = match agent_client_protocol::UntypedMessage::new(method, params) {
            Ok(request) => request,
            Err(err) => return extension_failure(0, format!("{err}")),
        };
        // The request goes out from a task on the connection's own event
        // loop — nothing protocol-shaped may run on a JNI thread — and the
        // answer comes back over a channel this thread waits on.
        let (tx, rx) = std::sync::mpsc::channel();
        let task_cx = cx.clone();
        if cx
            .spawn(async move {
                let result = task_cx.send_request(request).block_task().await;
                let _ = tx.send(result);
                Ok(())
            })
            .is_err()
        {
            return extension_failure(0, "the agent is not running".to_owned());
        }
        match rx.recv_timeout(EXTENSION_TIMEOUT) {
            Ok(Ok(result)) => serde_json::json!({ "ok": true, "result": result }).to_string(),
            Ok(Err(err)) => {
                let code: i32 = err.code.into();
                serde_json::json!({
                    "ok": false,
                    "code": code,
                    "message": err.message,
                    "data": err.data,
                })
                .to_string()
            }
            // The task is still out there and will answer nobody, which is
            // fine: the channel's other end is dropped and the send fails.
            Err(_) => extension_failure(0, format!("{method} did not answer in time")),
        }
    }

    /// Send a message into the turn that is already running — **steering**.
    ///
    /// Not a new turn and not a cancel. Spettro takes a second concurrent
    /// `session/prompt`, queues its text into the running turn, says so with
    /// an `agent_message_chunk`, and answers *this* prompt `end_turn` within
    /// milliseconds while the real turn carries on. The engine's side of that
    /// bargain is [`crate::acp_thread::SessionThread::running_turns`]: the
    /// instant `end_turn` must not settle the session.
    ///
    /// Refused unless a turn is actually running **and** the agent advertised
    /// the Spettro extension: a second concurrent prompt to a generic ACP
    /// agent is not steering, it is two turns at once, and the protocol says
    /// nothing about what that means.
    pub fn acp_steer(
        &self,
        session: u64,
        text: &str,
        mentions_json: &str,
        images_json: &str,
    ) -> bool {
        let Some(handle) = self.session_handle(session) else {
            return false;
        };
        let Some(shared) = self.acp.agent.lock().unwrap().clone() else {
            return false;
        };
        if !shared.speaks_spettro() {
            return false;
        }
        let Some(cx) = shared.connection() else {
            return false;
        };
        let (project, root) = {
            let thread = handle.thread.lock().unwrap();
            if thread.phase != Phase::Running {
                return false;
            }
            (thread.project, thread.root.clone())
        };
        let prompt = PromptInput {
            text: text.to_owned(),
            mentions: self.resolve_mentions(project, &root, parse_mentions(mentions_json)),
            images: serde_json::from_str(images_json).unwrap_or_default(),
        };
        // Pushed as a user entry, but *not* as a new turn: see
        // `push_steering_message`. Re-checked under the lock, because the
        // turn may have settled between the read above and here.
        let sent = handle.update(|thread| {
            if thread.phase != Phase::Running {
                return false;
            }
            thread.push_steering_message(&prompt.text);
            true
        });
        if !sent {
            return false;
        }
        start_prompt(&shared, &cx, session, prompt, false);
        true
    }

    /// The entries whose revision is newer than `since`, as JSON — see
    /// `SessionThread::entries_json` for the delta contract.
    pub fn acp_entries_since(&self, session: u64, since: u64) -> String {
        let Some(handle) = self.session_handle(session) else {
            return "null".to_owned();
        };
        let thread = handle.thread.lock().unwrap();
        thread.entries_json(since).to_string()
    }

    /// Send a prompt. Returns immediately; the turn streams in behind the
    /// version counter. A prompt while a turn is running interrupts it — the
    /// running turn is cancelled and this prompt follows it, which is Zed's
    /// follow-up behaviour. False for a forgotten id or a dead session.
    /// `mentions_json` is a JSON array of project-relative paths the user
    /// @-mentioned; they become resource blocks beside the text. Anything
    /// that is not such an array means no mentions — a malformed list must
    /// not eat the message it rode in on.
    /// Interrupt the running turn and send this prompt as soon as it stops.
    ///
    /// The deliberate version of what [`Self::acp_prompt`] used to do by
    /// accident. Queued first, so a cancel that never lands leaves the prompt
    /// where the user can still see and send it.
    pub fn acp_prompt_immediately(
        &self,
        session: u64,
        text: &str,
        mentions_json: &str,
        images_json: &str,
    ) -> bool {
        if !self.acp_prompt(session, text, mentions_json, images_json) {
            return false;
        }
        self.acp_cancel(session)
    }

    /// Drop one queued prompt, by the `id` its row carries in the state JSON.
    pub fn acp_remove_queued_prompt(&self, session: u64, queued_id: u64) -> bool {
        let Some(handle) = self.session_handle(session) else {
            return false;
        };
        handle.update(|thread| thread.remove_queued_prompt(queued_id))
    }

    pub fn acp_prompt(
        &self,
        session: u64,
        text: &str,
        mentions_json: &str,
        images_json: &str,
    ) -> bool {
        let Some(handle) = self.session_handle(session) else {
            return false;
        };
        let Some(shared) = self.acp.agent.lock().unwrap().clone() else {
            return false;
        };
        let (project, root) = {
            let thread = handle.thread.lock().unwrap();
            (thread.project, thread.root.clone())
        };
        let prompt = PromptInput {
            text: text.to_owned(),
            mentions: self.resolve_mentions(project, &root, parse_mentions(mentions_json)),
            // Same rule as the mentions beside it: rubbish is no attachments,
            // never a refused prompt. A dropped picture costs the user a
            // retry; a prompt the panel will not send costs them the message.
            images: serde_json::from_str(images_json).unwrap_or_default(),
        };
        enum Route {
            Refused,
            Queue,
            Send,
        }
        let route = handle.update(|thread| match thread.phase {
            Phase::Unavailable => Route::Refused,
            // Queued, and *shown* — `queue_prompt` pushes the entry now, so a
            // send while the agent is still starting or still answering looks
            // like a send rather than like a key that did nothing.
            Phase::Starting => {
                thread.queue_prompt(&prompt);
                Route::Queue
            }
            // **Queued, not interrupted.** This used to cancel the running
            // turn to make room, so a follow-up typed while the agent was
            // working killed the work — and on a phone, where the send button
            // becomes Stop mid-turn, the user could not even mean to. Zed
            // queues and never interrupts (thread_view.rs:1480);
            // `acp_prompt_immediately` is the deliberate version.
            Phase::Running => {
                thread.queue_prompt(&prompt);
                Route::Queue
            }
            Phase::Ready => {
                thread.push_user_message(&prompt.text);
                Route::Send
            }
        });
        match route {
            Route::Refused => false,
            Route::Queue => true,
            Route::Send => {
                let Some(cx) = shared.connection() else {
                    handle.update(|thread| {
                        thread.fail_turn("the agent connection is gone".to_owned())
                    });
                    return false;
                };
                start_prompt(&shared, &cx, session, prompt, false);
                true
            }
        }
    }

    /// Fill in the mentions whose text lives in the engine: a symbol's lines
    /// from the open buffer (or the file), a thread's summary from the
    /// sessions map, the diagnostics from the LSP store. The panel sends
    /// references; only the engine can read what they point at without a
    /// JNI round trip per mention.
    fn resolve_mentions(
        &self,
        project: ProjectId,
        root: &Path,
        mentions: Vec<Mention>,
    ) -> Vec<Mention> {
        mentions
            .into_iter()
            .map(|mention| match mention {
                Mention::Symbol {
                    path,
                    name,
                    start_row,
                    end_row,
                    ..
                } => {
                    let text = current_text(&self.buffers, &root.join(&path))
                        .map(|text| rows_of(&text, start_row, end_row))
                        .unwrap_or_default();
                    Mention::Symbol {
                        path,
                        name,
                        start_row,
                        end_row,
                        text,
                    }
                }
                Mention::Thread { session, .. } => {
                    let (title, text) = match self.session_handle(session) {
                        Some(other) => {
                            let thread = other.thread.lock().unwrap();
                            (
                                thread
                                    .title
                                    .clone()
                                    .unwrap_or_else(|| format!("Thread {session}")),
                                thread_summary(&thread),
                            )
                        }
                        None => (format!("Thread {session}"), String::new()),
                    };
                    Mention::Thread {
                        session,
                        title,
                        text,
                    }
                }
                Mention::Diagnostics { .. } => Mention::Diagnostics {
                    text: diagnostics_text(&self.lsp_diagnostic_rows(project)),
                },
                other => other,
            })
            .collect()
    }

    /// Put the files of every turn from user entry `entry_index` on back the
    /// way they were before it — Zed's "Restore checkpoint"
    /// (thread_view.rs:2965). Through the engine's own write path, so an
    /// open buffer sees the change as a disk change and reloads the way it
    /// does for any external edit. False when that message has no
    /// checkpoint, or the session is gone.
    ///
    /// **What it restores is what the engine saw the agent write**: files
    /// written through `fs/write_text_file` and files reported in a
    /// completed tool call's diff. A file the agent changed some other way
    /// — a shell command it ran itself — was never checkpointed.
    pub fn acp_restore_checkpoint(&self, session: u64, entry_index: u64) -> bool {
        let Some(handle) = self.session_handle(session) else {
            return false;
        };
        let (root, plan) = handle.update(|thread| {
            (
                thread.root.clone(),
                thread.restore_checkpoint(entry_index as usize),
            )
        });
        if plan.is_empty() {
            return false;
        }
        self.apply_write_plan(&root, plan);
        true
    }

    /// The review tab's rows — every file the agent edited in this thread,
    /// its earliest pre-edit text diffed against what it holds now, in the
    /// `FileDiff` shape the diff view draws:
    /// `{"version", "files": [{"path", "status", "created", "deleted", "diff"}]}`.
    /// `"null"` for a forgotten session. Reads the files, so call it when
    /// `acpSessionVersion` moves, not per frame.
    pub fn acp_edited_files(&self, session: u64) -> String {
        let Some(handle) = self.session_handle(session) else {
            return "null".to_owned();
        };
        let (root, version, files) = {
            let thread = handle.thread.lock().unwrap();
            (thread.root.clone(), thread.revision, thread.review_files())
        };
        let rows: Vec<serde_json::Value> = files
            .into_iter()
            .map(|file| {
                let now = current_text(&self.buffers, &file.path);
                let mut diff = acp::Diff::new(file.path.clone(), now.clone().unwrap_or_default());
                if let Some(before) = &file.before {
                    diff = diff.old_text(before.clone());
                }
                let mut rows = crate::acp_thread::tool_diff(&root, &diff);
                rows.created = file.before.is_none();
                rows.deleted = now.is_none();
                serde_json::json!({
                    "path": rows.path,
                    "status": file.review,
                    "created": rows.created,
                    "deleted": rows.deleted,
                    "diff": rows,
                })
            })
            .collect();
        serde_json::json!({ "version": version, "files": rows }).to_string()
    }

    /// Keep the agent's edits to the files named (project-relative paths as
    /// `acp_edited_files` spells them; an empty list means every file) —
    /// Zed's `agent::Keep` / `KeepAll`. They leave the review; the
    /// checkpoints stay.
    pub fn acp_keep_edits(&self, session: u64, paths_json: &str) -> bool {
        let Some(handle) = self.session_handle(session) else {
            return false;
        };
        let paths: Vec<String> = serde_json::from_str(paths_json).unwrap_or_default();
        handle.update(|thread| {
            let absolute = absolute_paths(&thread.root, &paths);
            thread.keep_edits(&absolute)
        })
    }

    /// Put the files named back to what they held before the agent's first
    /// touch — Zed's `agent::Reject` / `RejectAll`. Same paths as
    /// [`Self::acp_keep_edits`]. False when nothing was there to reject.
    pub fn acp_reject_edits(&self, session: u64, paths_json: &str) -> bool {
        let Some(handle) = self.session_handle(session) else {
            return false;
        };
        let paths: Vec<String> = serde_json::from_str(paths_json).unwrap_or_default();
        let (root, plan) = handle.update(|thread| {
            let absolute = absolute_paths(&thread.root, &paths);
            (thread.root.clone(), thread.reject_edits(&absolute))
        });
        if plan.is_empty() {
            return false;
        }
        self.apply_write_plan(&root, plan);
        true
    }

    /// Answer the first permission prompt in the transcript with the option
    /// of `kind` — `allow_once`, `allow_always`, `reject_once`,
    /// `reject_always` — which is what the `agent::AllowOnce` family of
    /// chords does. False when nothing is waiting, or the agent offered no
    /// option of that kind on the prompt in front of the user.
    pub fn acp_answer_waiting(&self, session: u64, kind: &str) -> bool {
        let kind = match kind {
            "allow_once" => acp::PermissionOptionKind::AllowOnce,
            "allow_always" => acp::PermissionOptionKind::AllowAlways,
            "reject_once" => acp::PermissionOptionKind::RejectOnce,
            "reject_always" => acp::PermissionOptionKind::RejectAlways,
            _ => return false,
        };
        let Some(handle) = self.session_handle(session) else {
            return false;
        };
        let target = handle.thread.lock().unwrap().first_waiting_option(kind);
        match target {
            Some((tool_call, option)) => {
                self.acp_respond_permission(session, &tool_call, &option, "")
            }
            None => false,
        }
    }

    /// Write a restore plan: each file back to its text, or gone when it did
    /// not exist. Confined to the project as every agent write is, and
    /// flagged to the open buffers so they reload — the same path an
    /// external edit takes.
    fn apply_write_plan(&self, root: &Path, plan: Vec<(PathBuf, Option<String>)>) {
        let mut touched = Vec::with_capacity(plan.len());
        for (path, before) in plan {
            if !resolves_inside(root, &path) {
                log::warn!(
                    "acp: refused to restore outside the project: {}",
                    path.display()
                );
                continue;
            }
            let result = match before {
                Some(text) => (|| -> std::io::Result<()> {
                    if let Some(parent) = path.parent() {
                        std::fs::create_dir_all(parent)?;
                    }
                    crate::file::write_atomically_io(&path, &text)
                })(),
                None => match std::fs::remove_file(&path) {
                    Err(err) if err.kind() != std::io::ErrorKind::NotFound => Err(err),
                    _ => Ok(()),
                },
            };
            match result {
                Ok(()) => {
                    self.acp.written.record(&path);
                    touched.push(path);
                }
                Err(err) => log::warn!("acp: could not restore {}: {err}", path.display()),
            }
        }
        crate::file::note_disk_changes(&self.buffers, &touched);
    }

    /// Stop the running turn. The agent answers the prompt with `cancelled`,
    /// which is what settles the session — this only asks, marks what is
    /// already known to be over, and answers every open permission question
    /// with `cancelled`, as the spec requires.
    pub fn acp_cancel(&self, session: u64) -> bool {
        let Some(handle) = self.session_handle(session) else {
            return false;
        };
        let acp_id = handle.update(|thread| {
            // A queued follow-up is cancelled too, and its entry goes with it:
            // a transcript must not show a message the agent never received.
            thread.discard_queued_prompt();
            thread.cancel_pending_tool_calls();
            if thread.phase == Phase::Running {
                thread.turn_cancelled = true;
            }
            thread.acp_id.clone()
        });
        handle.cancel_permissions();
        if let (Some(acp_id), Some(shared)) = (acp_id, self.acp.agent.lock().unwrap().clone()) {
            if let Some(cx) = shared.connection() {
                // Through the task queue, not enqueued inline: the prompt
                // itself goes out from a spawned task, and a notification
                // enqueued directly here could overtake a prompt whose task
                // has not run yet — the agent would then see the cancel as
                // stale and run the whole turn anyway.
                let notify = cx.clone();
                let _ = cx.spawn(async move {
                    let _ = notify.send_notification(acp::CancelNotification::new(acp_id));
                    Ok(())
                });
            }
        }
        true
    }

    /// The user answered a permission prompt. `option_id` is one of the ids
    /// the entry offered. False if nothing was waiting under that tool call.
    ///
    /// `answer_meta_json` (`""` for none) rides along as the response's
    /// `_meta`. It is how a *question* walked through the permission channel
    /// is answered — the option id says which choice was taken, and
    /// `{"spettro.app/questionAnswer":{…}}` says what that choice was, which
    /// is the only part a free-text answer can carry. Malformed JSON is
    /// dropped rather than refused: the user's choice still travels, and that
    /// is the part that unblocks the turn.
    pub fn acp_respond_permission(
        &self,
        session: u64,
        tool_call: &str,
        option_id: &str,
        answer_meta_json: &str,
    ) -> bool {
        let Some(handle) = self.session_handle(session) else {
            return false;
        };
        let Some(responder) = handle.permissions.lock().unwrap().remove(tool_call) else {
            return false;
        };
        let decision = handle.update(|thread| {
            let kind = thread.entries.iter().rev().find_map(|entry| {
                if let crate::acp_thread::EntryBody::ToolCall(call) = &entry.body {
                    if call.id == tool_call {
                        return call
                            .options
                            .iter()
                            .find(|option| option.option_id.0.as_ref() == option_id)
                            .map(|option| option.kind);
                    }
                }
                None
            });
            let decision = match kind {
                Some(
                    acp::PermissionOptionKind::AllowOnce | acp::PermissionOptionKind::AllowAlways,
                ) => PermissionDecision::Allow,
                Some(
                    acp::PermissionOptionKind::RejectOnce | acp::PermissionOptionKind::RejectAlways,
                ) => PermissionDecision::Reject,
                // An id we never offered, or a kind the schema grew since:
                // treat unknown as allow only if the agent recognises the id;
                // safer to reject nothing and cancel nothing — refuse below.
                _ => return None,
            };
            thread.finish_permission(tool_call, decision);
            Some(decision)
        });
        match decision {
            Some(_) => {
                let mut response = acp::RequestPermissionResponse::new(
                    acp::RequestPermissionOutcome::Selected(acp::SelectedPermissionOutcome::new(
                        acp::PermissionOptionId::new(option_id.to_owned()),
                    )),
                );
                if let Some(meta) = answer_meta(answer_meta_json) {
                    response = response.meta(meta);
                }
                responder.respond(response).is_ok()
            }
            None => {
                // Unknown option: put the question back rather than answer
                // with something the user did not choose.
                handle
                    .permissions
                    .lock()
                    .unwrap()
                    .insert(tool_call.to_owned(), responder);
                false
            }
        }
    }

    /// Switch the session's mode (Claude Code's "default" / "acceptEdits" /
    /// "plan"…). The change lands when the agent confirms it.
    pub fn acp_set_mode(&self, session: u64, mode_id: &str) -> bool {
        let Some(handle) = self.session_handle(session) else {
            return false;
        };
        let Some(acp_id) = handle.thread.lock().unwrap().acp_id.clone() else {
            return false;
        };
        let Some(shared) = self.acp.agent.lock().unwrap().clone() else {
            return false;
        };
        let Some(cx) = shared.connection() else {
            return false;
        };
        let mode = acp::SessionModeId::new(mode_id.to_owned());
        let session_id = session;
        let task_shared = shared.clone();
        let request = acp::SetSessionModeRequest::new(acp_id, mode.clone());
        let task_cx = cx.clone();
        cx.spawn(async move {
            match task_cx.send_request(request).block_task().await {
                Ok(_) => {
                    if let Some(handle) = task_shared.session(session_id) {
                        handle.update(|thread| {
                            if let Some(modes) = &mut thread.modes {
                                modes.current_mode_id = mode;
                            }
                            // Without this the confirmed mode change is
                            // invisible until something else happens to move
                            // the counter.
                            thread.bump();
                        });
                    }
                }
                // And a *refused* one used to be invisible too: the arm was
                // missing entirely, so tapping a mode the agent would not
                // take did nothing at all, for ever, with no way to tell that
                // from a mode change that worked.
                Err(err) => {
                    log::warn!("acp: session/set_mode refused: {err}");
                    if let Some(handle) = task_shared.session(session_id) {
                        handle.update(|thread| {
                            thread.notice(format!("The agent would not change mode: {err}"));
                        });
                    }
                }
            }
            Ok(())
        })
        .is_ok()
    }

    /// Ask the agent to change one of its session configuration options —
    /// `session/set_config_option`, the request behind Zed's model and
    /// effort selectors. `value_json` is `true`/`false` for a boolean option
    /// or a JSON string for a select's value id. The response carries the
    /// full new set of options, which replaces ours.
    pub fn acp_set_config_option(&self, session: u64, config_id: &str, value_json: &str) -> bool {
        let Some(handle) = self.session_handle(session) else {
            return false;
        };
        let Some(acp_id) = handle.thread.lock().unwrap().acp_id.clone() else {
            return false;
        };
        let Some(shared) = self.acp.agent.lock().unwrap().clone() else {
            return false;
        };
        let Some(cx) = shared.connection() else {
            return false;
        };
        let value = match serde_json::from_str::<serde_json::Value>(value_json) {
            Ok(serde_json::Value::Bool(flag)) => acp::SessionConfigOptionValue::boolean(flag),
            Ok(serde_json::Value::String(id)) => {
                acp::SessionConfigOptionValue::value_id(acp::SessionConfigValueId::new(id))
            }
            _ => return false,
        };
        let request = acp::SetSessionConfigOptionRequest::new(
            acp_id,
            acp::SessionConfigId::new(config_id.to_owned()),
            value,
        );
        let session_id = session;
        let task_shared = shared.clone();
        let task_cx = cx.clone();
        cx.spawn(async move {
            match task_cx.send_request(request).block_task().await {
                Ok(response) => {
                    if let Some(handle) = task_shared.session(session_id) {
                        handle.update(|thread| {
                            thread.config_options = response.config_options;
                            // Without this the confirmed change is invisible
                            // until something else happens to move the
                            // counter.
                            thread.bump();
                        });
                    }
                }
                // Same missing arm as `set_mode`: a refused option change was
                // silence, and the chip went on showing the old value with no
                // hint that the agent had said no.
                Err(err) => {
                    log::warn!("acp: session/set_config_option refused: {err}");
                    if let Some(handle) = task_shared.session(session_id) {
                        handle.update(|thread| {
                            thread.notice(format!("The agent would not change that: {err}"));
                        });
                    }
                }
            }
            Ok(())
        })
        .is_ok()
    }

    /// Run one of the agent's advertised auth methods, then retry
    /// `session/new` for every session that was waiting on it.
    pub fn acp_authenticate(&self, session: u64, method_id: &str) -> bool {
        let Some(shared) = self.acp.agent.lock().unwrap().clone() else {
            return false;
        };
        let Some(cx) = shared.connection() else {
            return false;
        };
        let _ = session;
        let request = acp::AuthenticateRequest::new(acp::AuthMethodId::new(method_id.to_owned()));
        let task_shared = shared.clone();
        let task_cx = cx.clone();
        cx.spawn(async move {
            match task_cx.send_request(request).block_task().await {
                Ok(_) => {
                    let waiting: Vec<u64> = task_shared
                        .own_sessions()
                        .into_iter()
                        .filter(|(_, handle)| handle.thread.lock().unwrap().needs_auth)
                        .map(|(id, _)| id)
                        .collect();
                    for id in waiting {
                        // A retry after signing in is a fresh session: the
                        // one that failed never existed on the agent's side.
                        create_session(
                            task_shared.clone(),
                            task_cx.clone(),
                            PendingSession::new(id, None),
                        )
                        .await;
                    }
                }
                Err(err) => {
                    let message = format!("authentication failed: {err}");
                    for (_, handle) in task_shared.own_sessions() {
                        handle.update(|thread| {
                            if thread.needs_auth {
                                thread.error = Some(message.clone());
                                // A failed sign-in is the whole of what
                                // happened; a session parked on `needs_auth`
                                // has nothing else coming to move the counter
                                // for it, so without this the button does
                                // nothing visible at all.
                                thread.bump();
                            }
                        });
                    }
                }
            }
            Ok(())
        })
        .is_ok()
    }

    /// Close a session and forget it. Closing the last session stops the
    /// agent — through the SIGQUIT-first path, never a bare kill.
    pub fn acp_close_session(&self, session: u64) -> bool {
        let Some(handle) = self.acp.sessions.lock().unwrap().remove(&session) else {
            return false;
        };
        handle.cancel_permissions();
        let acp_id = handle.thread.lock().unwrap().acp_id.clone();
        if let Some(acp_id) = &acp_id {
            self.acp.index.lock().unwrap().remove(acp_id);
        }
        let mut slot = self.acp.agent.lock().unwrap();
        if let Some(shared) = slot.as_ref() {
            // Whatever this session had running dies with it. An agent that
            // never got to `terminal/release` — because the user closed the
            // thread mid-command — must not leave a build running in the
            // guest, and any `wait_for_exit` it parked must still be answered.
            shared.terminals.release_session(session);
            // And every question it had open, answered `cancel`: a responder
            // dropped without an answer is an agent waiting for ever.
            shared.elicitations.cancel_session(session);
            shared.questions.cancel_session(session);
            // Tell the agent it is over. `session/close` is the method for
            // exactly this and is what Zed sends (agent_servers/src/acp.rs
            // :1845-1878) — but it is gated on a capability, so an agent
            // without it gets the cancel that has always been sent instead:
            // either way the turn stops and it stops spending tokens.
            if let (Some(acp_id), Some(cx)) = (acp_id, shared.connection()) {
                if shared.caps().close {
                    let request = acp::CloseSessionRequest::new(acp_id);
                    let task_cx = cx.clone();
                    let _ = cx.spawn(async move {
                        if let Err(err) = task_cx.send_request(request).block_task().await {
                            log::warn!("acp: session/close failed: {err}");
                        }
                        Ok(())
                    });
                } else {
                    let _ = cx.send_notification(acp::CancelNotification::new(acp_id));
                }
            }
            // Only *its* sessions: the map may still hold sessions of an agent
            // being replaced, and those are not a reason to keep this one
            // alive — nor is this one's emptiness a reason to stop that one.
            if shared.own_sessions().is_empty() {
                shutdown_agent(shared);
                *slot = None;
            }
        }
        true
    }

    /// The agent's own past conversations — `session/list`, which not every
    /// agent has (`agent.capabilities.list` says).
    ///
    /// `{"version", "loading", "error", "sessions": [{sessionId, cwd, title,
    /// updatedAt, …}]}`, the session objects in the protocol's own camelCase.
    /// Pass `refresh` when the user asked for the list — opening the threads
    /// view, or after a delete — and `false` while polling: a refresh is a
    /// round trip to the agent, and the answer is cached behind `version` the
    /// way every other read here is.
    pub fn acp_session_list(&self, refresh: bool) -> String {
        let shared = self.acp.agent.lock().unwrap().clone();
        let Some(shared) = shared else {
            return serde_json::json!({
                "version": 0, "loading": false, "error": null, "sessions": []
            })
            .to_string();
        };
        if refresh {
            shared.refresh_session_list();
        }
        shared.session_list.lock().unwrap().json().to_string()
    }

    /// Version counter for [`Self::acp_session_list`] — the `version` field
    /// of the cached list, without serializing the list to read it. Poll
    /// this, and make the full read only when it moves: it already covers
    /// `loading` flipping and the answer landing, because
    /// `refresh_session_list` bumps it at both ends.
    ///
    /// The connection's id rides in the high bits, exactly as in
    /// [`Self::acp_elicitations_version`] and for the same reason. 0 means no
    /// agent is running.
    pub fn acp_session_list_version(&self) -> u64 {
        let shared = self.acp.agent.lock().unwrap().clone();
        shared
            .map(|shared| (shared.id << 32) + shared.session_list.lock().unwrap().version)
            .unwrap_or(0)
    }

    /// Forget one of the agent's past conversations — `session/delete`, when
    /// the agent has it. The list refreshes itself afterwards, so the row
    /// goes on the next poll rather than on a guess.
    pub fn acp_delete_session(&self, acp_session_id: &str) -> bool {
        let shared = self.acp.agent.lock().unwrap().clone();
        let Some(shared) = shared else {
            return false;
        };
        if !shared.caps().delete {
            return false;
        }
        let Some(cx) = shared.connection() else {
            return false;
        };
        let request =
            acp::DeleteSessionRequest::new(acp::SessionId::new(acp_session_id.to_owned()));
        let task_shared = shared.clone();
        let task_cx = cx.clone();
        let _ = cx.spawn(async move {
            match task_cx.send_request(request).block_task().await {
                Ok(_) => task_shared.refresh_session_list(),
                Err(err) => {
                    log::warn!("acp: session/delete failed: {err}");
                    let mut list = task_shared.session_list.lock().unwrap();
                    list.error = Some(format!("{err}"));
                    list.version += 1;
                }
            }
            Ok(())
        });
        true
    }

    /// Sign out of whatever `authenticate` signed into — `logout`, when the
    /// agent has it (`agent.capabilities.logout`).
    ///
    /// Deliberately does not tear the sessions down: the agent decides what
    /// signing out means for a conversation in flight, and the next thing it
    /// refuses will come back through the ordinary auth-required path.
    pub fn acp_logout(&self) -> bool {
        let shared = self.acp.agent.lock().unwrap().clone();
        let Some(shared) = shared else {
            return false;
        };
        if !shared.caps().logout {
            return false;
        }
        let Some(cx) = shared.connection() else {
            return false;
        };
        let task_cx = cx.clone();
        let _ = cx.spawn(async move {
            if let Err(err) = task_cx
                .send_request(acp::LogoutRequest::new())
                .block_task()
                .await
            {
                log::warn!("acp: logout failed: {err}");
            }
            Ok(())
        });
        true
    }

    /// Files the agent has written, from `since` onwards, as
    /// `{"total": n, "paths": [...]}` — absolute paths. The UI reloads the
    /// open buffers among them (through `reloadBuffer`, so the edit is
    /// undoable and every layer hears about it) and passes the new total back
    /// next time.
    pub fn acp_written_files(&self, since: u64) -> String {
        self.acp.written.json(since as usize).to_string()
    }

    /// One agent terminal, for the card that shows it:
    /// `{"revision": n}` when nothing has moved since `since`, and otherwise
    /// `{"revision", "label", "output", "truncated", "exitStatus", "running"}`.
    ///
    /// `{"revision": 0}` for a terminal the engine does not have — released by
    /// the agent, or belonging to a session that is gone. The card reads that
    /// as "this is history now" and stops polling, which is exactly what it
    /// is: the transcript keeps the tool call, but the command behind it is
    /// over and unreadable.
    pub fn acp_terminal_output(&self, terminal_id: &str, since: u64) -> String {
        let terminal = self
            .acp
            .agent
            .lock()
            .unwrap()
            .as_ref()
            .and_then(|shared| shared.terminals.get(terminal_id));
        match terminal {
            Some(terminal) => snapshot_json(&terminal, since).to_string(),
            None => serde_json::json!({ "revision": 0 }).to_string(),
        }
    }

    fn session_handle(&self, session: u64) -> Option<Arc<SessionHandle>> {
        self.acp.sessions.lock().unwrap().get(&session).cloned()
    }
}

/// Project-relative paths as the review spells them, back to the canonical
/// absolute paths the edit records hold. A path that does not resolve keeps
/// its joined spelling, which then matches nothing — never a neighbour.
fn absolute_paths(root: &Path, paths: &[String]) -> Vec<PathBuf> {
    paths
        .iter()
        .map(|path| {
            let joined = root.join(path);
            std::fs::canonicalize(&joined).unwrap_or(joined)
        })
        .collect()
}

/// The `_meta` to attach to a permission answer, from the JSON the panel
/// sent. Empty or malformed means none: a choice that travels without its
/// metadata still unblocks the agent, and refusing to answer at all would
/// leave the turn stuck on a question the user has already answered.
fn answer_meta(answer_meta_json: &str) -> Option<acp::Meta> {
    if answer_meta_json.trim().is_empty() {
        return None;
    }
    match serde_json::from_str::<acp::Meta>(answer_meta_json) {
        Ok(meta) => Some(meta),
        Err(err) => {
            log::warn!("acp: ignoring malformed permission answer _meta: {err}");
            None
        }
    }
}

/// The error half of [`crate::Engine::acp_call_extension`]'s envelope. Code 0
/// means the call never reached the wire, which is a different thing from any
/// JSON-RPC error and the panel says so differently.
fn extension_failure(code: i32, message: String) -> String {
    serde_json::json!({ "ok": false, "code": code, "message": message }).to_string()
}

/// Stop an agent: flag the watcher, which drops the process, which is the
/// SIGQUIT-first shutdown. The connection future ends when the pipes close.
fn shutdown_agent(shared: &Arc<AgentShared>) {
    shared.shutdown.store(true, Ordering::Release);
    // Terminals are children of the agent's *connection*, not of its process:
    // proot does not take them with it, so an agent replaced mid-build would
    // otherwise leave that build running against the process budget.
    shared.terminals.release_all();
    shared.elicitations.cancel_all();
    shared.questions.cancel_all();
    *shared.connection.lock().unwrap() = None;
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::acp_thread::{EntryBody, ToolStatus};
    use agent_client_protocol::ConnectTo;
    use std::path::PathBuf;

    // -------------------------------------------------------------------
    // The symlink guard
    // -------------------------------------------------------------------

    #[test]
    fn resolves_inside_confines_real_and_future_paths() {
        let dir = tempfile::tempdir().unwrap();
        let root = std::fs::canonicalize(dir.path()).unwrap();
        std::fs::create_dir(root.join("src")).unwrap();
        std::fs::write(root.join("src/main.rs"), "x").unwrap();

        // Existing file, new file, and a new file in a new directory.
        assert!(resolves_inside(&root, &root.join("src/main.rs")));
        assert!(resolves_inside(&root, &root.join("src/new.rs")));
        assert!(resolves_inside(&root, &root.join("brand/new/dir/file.rs")));

        // Relative and dot-riddled paths are refused outright.
        assert!(!resolves_inside(&root, Path::new("src/main.rs")));
        assert!(!resolves_inside(&root, &root.join("src/../../etc/passwd")));

        // A path simply outside.
        assert!(!resolves_inside(&root, Path::new("/etc/passwd")));
    }

    #[cfg(unix)]
    #[test]
    fn resolves_inside_refuses_a_symlink_escape() {
        let dir = tempfile::tempdir().unwrap();
        let outside = tempfile::tempdir().unwrap();
        let root = std::fs::canonicalize(dir.path()).unwrap();
        std::os::unix::fs::symlink(outside.path(), root.join("vendor")).unwrap();

        // The attack SafeDelete documents: `vendor` points out of the
        // project, so `vendor/anything` is not project ground.
        assert!(!resolves_inside(&root, &root.join("vendor/pwned.txt")));
        assert!(!resolves_inside(
            &root,
            &root.join("vendor/sub/dir/pwned.txt")
        ));

        // A symlink that stays inside is fine.
        std::fs::create_dir(root.join("real")).unwrap();
        std::os::unix::fs::symlink(root.join("real"), root.join("alias")).unwrap();
        assert!(resolves_inside(&root, &root.join("alias/file.txt")));
    }

    #[test]
    fn clip_lines_is_one_based_and_clamped() {
        let text = "a\nb\nc\nd";
        assert_eq!(clip_lines(text, None, None), text);
        assert_eq!(clip_lines(text, Some(2), None), "b\nc\nd");
        // Line 3 ends with a newline in the source, so the window does too:
        // it is a slice of the file, not a re-rendering of it.
        assert_eq!(clip_lines(text, Some(2), Some(2)), "b\nc\n");
        assert_eq!(clip_lines(text, Some(9), None), "");
        assert_eq!(clip_lines(text, None, Some(1)), "a\n");
        // The last line has no newline after it, and does not acquire one.
        assert_eq!(clip_lines(text, Some(4), Some(1)), "d");
    }

    /// The window is a slice, so the file's own line endings survive it.
    ///
    /// `lines()` + `join("\n")` would hand a CRLF file back as LF, and an
    /// agent that reads a window and writes it back would silently rewrite
    /// every ending in the file it touched.
    #[test]
    fn clip_lines_keeps_the_files_own_line_endings() {
        let crlf = "one\r\ntwo\r\nthree\r\n";
        assert_eq!(clip_lines(crlf, Some(2), Some(1)), "two\r\n");
        assert_eq!(clip_lines(crlf, Some(2), None), "two\r\nthree\r\n");
        // A trailing newline is not a fifth line to be skipped past.
        assert_eq!(clip_lines("a\n", Some(1), Some(1)), "a\n");
        assert_eq!(clip_lines("a\n", Some(2), Some(1)), "");
    }

    // -------------------------------------------------------------------
    // Two agents in one process: the sessions map outlives both.
    // -------------------------------------------------------------------

    /// @-mentions become resource blocks after the text — embedded file text
    /// for an agent that takes it, a `file://` link otherwise — and a mention
    /// that resolves outside the project never reaches the wire at all.
    #[test]
    fn mentions_become_resource_blocks_inside_the_project_only() {
        let dir = tempfile::tempdir().unwrap();
        let root = std::fs::canonicalize(dir.path()).unwrap();
        std::fs::write(root.join("notes.md"), "the notes\n").unwrap();
        let prompt = PromptInput {
            text: "look at this".to_owned(),
            mentions: vec![
                Mention::File {
                    path: "notes.md".to_owned(),
                },
                Mention::File {
                    path: "../escape.txt".to_owned(),
                },
                Mention::File {
                    path: "missing.txt".to_owned(),
                },
            ],
            images: Vec::new(),
        };

        // An agent that takes embedded context gets the file's text.
        let blocks = prompt_blocks(&root, true, false, &prompt);
        assert_eq!(blocks.len(), 3, "text + notes.md + missing.txt as a link");
        assert!(matches!(&blocks[0], acp::ContentBlock::Text(text) if text.text == "look at this"));
        match &blocks[1] {
            acp::ContentBlock::Resource(resource) => match &resource.resource {
                acp::EmbeddedResourceResource::TextResourceContents(contents) => {
                    assert_eq!(contents.text, "the notes\n");
                    assert!(contents.uri.starts_with("file://"));
                    assert!(contents.uri.ends_with("/notes.md"));
                }
                other => panic!("expected text contents, got {other:?}"),
            },
            other => panic!("expected an embedded resource, got {other:?}"),
        }
        // A file that cannot be read still travels as a link that names it.
        assert!(
            matches!(&blocks[2], acp::ContentBlock::ResourceLink(link) if link.name == "missing.txt")
        );

        // An agent without the capability gets links for everything.
        let blocks = prompt_blocks(&root, false, false, &prompt);
        assert!(matches!(&blocks[1], acp::ContentBlock::ResourceLink(link)
                if link.name == "notes.md" && link.uri.starts_with("file://")));
    }

    /// A `#` in a filename is a fragment separator in a URI, so a raw
    /// `format!("file://{}")` handed the agent a path ending at the `#` —
    /// the mention silently contributed nothing, and on the embedded branch a
    /// write-back would have targeted the wrong file. `?` and `%` are the
    /// same story.
    #[test]
    fn a_mention_uri_is_percent_encoded() {
        let dir = tempfile::tempdir().unwrap();
        let root = std::fs::canonicalize(dir.path()).unwrap();
        std::fs::write(root.join("RFC#42.md"), "the rfc\n").unwrap();
        let prompt = PromptInput {
            text: "read it".to_owned(),
            mentions: vec![Mention::File {
                path: "RFC#42.md".to_owned(),
            }],
            images: Vec::new(),
        };

        let blocks = prompt_blocks(&root, false, false, &prompt);
        let acp::ContentBlock::ResourceLink(link) = &blocks[1] else {
            panic!("expected a resource link, got {:?}", blocks[1]);
        };
        assert!(
            !link.uri.contains('#'),
            "the # must be escaped, not left to start a fragment: {}",
            link.uri
        );
        assert!(link.uri.ends_with("RFC%2342.md"), "got {}", link.uri);
        // And it still round-trips to the file it names.
        let parsed = url::Url::parse(&link.uri).unwrap();
        assert_eq!(parsed.to_file_path().unwrap(), root.join("RFC#42.md"));
    }

    /// A directory mention embeds every text file under it, in path order,
    /// dot-entries skipped — and a file past the byte cap travels as a link
    /// that still names it.
    #[test]
    fn a_directory_mention_embeds_its_files_up_to_the_cap() {
        let dir = tempfile::tempdir().unwrap();
        let root = std::fs::canonicalize(dir.path()).unwrap();
        std::fs::create_dir_all(root.join("src/inner")).unwrap();
        std::fs::create_dir_all(root.join("src/.hidden")).unwrap();
        std::fs::write(root.join("src/b.rs"), "b\n").unwrap();
        std::fs::write(root.join("src/a.rs"), "a\n").unwrap();
        std::fs::write(root.join("src/inner/c.rs"), "c\n").unwrap();
        std::fs::write(root.join("src/.hidden/secret"), "no\n").unwrap();
        std::fs::write(
            root.join("src/big.bin"),
            vec![b'x'; MAX_EMBEDDED_MENTION_BYTES as usize + 1],
        )
        .unwrap();
        let prompt = PromptInput {
            text: "the module".to_owned(),
            mentions: vec![Mention::Directory {
                path: "src".to_owned(),
            }],
            images: Vec::new(),
        };
        let blocks = prompt_blocks(&root, true, false, &prompt);
        let names: Vec<String> = blocks[1..]
            .iter()
            .map(|block| match block {
                acp::ContentBlock::Resource(resource) => match &resource.resource {
                    acp::EmbeddedResourceResource::TextResourceContents(contents) => {
                        format!("embedded {}", contents.uri.rsplit('/').next().unwrap())
                    }
                    _ => panic!(),
                },
                acp::ContentBlock::ResourceLink(link) => format!("link {}", link.name),
                other => panic!("unexpected {other:?}"),
            })
            .collect();
        assert_eq!(
            names,
            [
                "embedded a.rs",
                "embedded b.rs",
                "link big.bin",
                "embedded c.rs"
            ]
        );

        // Outside the project: nothing at all.
        let prompt = PromptInput {
            text: "escape".to_owned(),
            mentions: vec![Mention::Directory {
                path: "..".to_owned(),
            }],
            images: Vec::new(),
        };
        assert_eq!(prompt_blocks(&root, true, false, &prompt).len(), 1);
    }

    /// The text-carrying mentions — a symbol, a selection, a fetched page,
    /// a thread, the diagnostics — are embedded resources under Zed's URI
    /// shapes for an agent that takes them, and plain text under a heading
    /// for one that does not: a link to a page the agent cannot fetch says
    /// nothing.
    #[test]
    fn text_mentions_use_zeds_uri_shapes_and_fall_back_to_text() {
        let dir = tempfile::tempdir().unwrap();
        let root = std::fs::canonicalize(dir.path()).unwrap();
        std::fs::write(root.join("a.rs"), "fn main() {}\n").unwrap();
        let prompt = PromptInput {
            text: "see".to_owned(),
            mentions: vec![
                Mention::Symbol {
                    path: "a.rs".to_owned(),
                    name: "main".to_owned(),
                    start_row: 0,
                    end_row: 0,
                    text: "fn main() {}".to_owned(),
                },
                Mention::Selection {
                    path: "a.rs".to_owned(),
                    start_row: 2,
                    end_row: 4,
                    text: "picked".to_owned(),
                },
                Mention::Fetch {
                    url: "https://example.com/page".to_owned(),
                    text: "the page".to_owned(),
                },
                Mention::Thread {
                    session: 3,
                    title: "Earlier work".to_owned(),
                    text: "## User\n\nhi\n".to_owned(),
                },
                Mention::Diagnostics {
                    text: "a.rs:1:1: error: boom\n".to_owned(),
                },
                // Escapes the project: dropped before anything is read.
                Mention::Selection {
                    path: "../x".to_owned(),
                    start_row: 0,
                    end_row: 0,
                    text: "nope".to_owned(),
                },
            ],
            images: Vec::new(),
        };
        let blocks = prompt_blocks(&root, true, false, &prompt);
        assert_eq!(blocks.len(), 6, "text + five mentions; the escape is gone");
        let uris: Vec<(String, String)> = blocks[1..]
            .iter()
            .map(|block| match block {
                acp::ContentBlock::Resource(resource) => match &resource.resource {
                    acp::EmbeddedResourceResource::TextResourceContents(contents) => {
                        (contents.uri.clone(), contents.text.clone())
                    }
                    _ => panic!(),
                },
                other => panic!("unexpected {other:?}"),
            })
            .collect();
        assert!(
            uris[0].0.ends_with("/a.rs?symbol=main#L1:1"),
            "{}",
            uris[0].0
        );
        assert_eq!(uris[0].1, "fn main() {}");
        assert!(uris[1].0.ends_with("/a.rs#L3:5"), "{}", uris[1].0);
        assert_eq!(uris[2].0, "https://example.com/page");
        assert_eq!(uris[3].0, "seeker:///agent/thread/3?name=Earlier+work");
        assert_eq!(uris[4].0, "seeker:///agent/diagnostics");

        let blocks = prompt_blocks(&root, false, false, &prompt);
        let acp::ContentBlock::Text(text) = &blocks[3] else {
            panic!("expected text for an agent without embedded context");
        };
        assert!(
            text.text
                .starts_with("[https://example.com/page](https://example.com/page)")
        );
        assert!(text.text.ends_with("the page"));
    }

    /// The engine's own reading of a thread and of the diagnostics, as text.
    #[test]
    fn a_thread_and_the_diagnostics_summarize_as_text() {
        let mut thread = SessionThread::new(1, PathBuf::from("/proj"));
        thread.push_user_message("fix it");
        thread.apply_update(acp::SessionUpdate::AgentThoughtChunk(
            acp::ContentChunk::new(acp::ContentBlock::from("hmm".to_owned())),
        ));
        thread.apply_update(acp::SessionUpdate::AgentMessageChunk(
            acp::ContentChunk::new(acp::ContentBlock::from("done".to_owned())),
        ));
        thread.apply_update(acp::SessionUpdate::ToolCall(
            acp::ToolCall::new(acp::ToolCallId::new("t1".to_owned()), "Edit a.rs")
                .kind(acp::ToolKind::Edit),
        ));
        let summary = thread_summary(&thread);
        assert_eq!(
            summary,
            "## User\n\nfix it\n\n## Agent\n\ndone\n\n- edit: Edit a.rs\n"
        );
        assert!(!summary.contains("hmm"), "thoughts are not the record");

        let rows = crate::lsp::ProjectDiagnosticRows {
            version: 1,
            files: vec![crate::lsp::FileDiagnosticRows {
                path: "src/main.rs".to_owned(),
                rows: vec![crate::lsp::DiagnosticRow {
                    row: 4,
                    col_utf16: 8,
                    end_row: 4,
                    end_col_utf16: 9,
                    severity: crate::lsp::Severity::Error,
                    message: "mismatched types\n".to_owned(),
                    source: Some("rustc".to_owned()),
                    code: Some("E0308".to_owned()),
                }],
            }],
        };
        assert_eq!(
            diagnostics_text(&rows),
            "src/main.rs:5:9: error: mismatched types [rustc E0308]\n"
        );
        assert_eq!(
            diagnostics_text(&crate::lsp::ProjectDiagnosticRows {
                version: 1,
                files: Vec::new()
            }),
            "No diagnostics.\n"
        );
        assert_eq!(rows_of("a\nb\nc\nd", 1, 2), "b\nc");
        assert_eq!(rows_of("a\nb", 5, 9), "");
    }

    /// `context_servers` as ACP's `mcpServers`, on the wire: a stdio entry
    /// in the protocol's untagged shape with its env as `{name, value}`
    /// pairs, an HTTP entry tagged `"type": "http"` — and only for an agent
    /// whose `mcpCapabilities.http` allows it, because the protocol forbids
    /// the other case. Disabled entries are not sent at all.
    #[test]
    fn context_servers_become_mcp_servers_in_the_wire_shape() {
        let mut servers = BTreeMap::new();
        servers.insert(
            "fs".to_owned(),
            ContextServer::Stdio {
                command: "npx".to_owned(),
                args: vec!["-y".to_owned(), "server".to_owned()],
                env: BTreeMap::from([("ROOT".to_owned(), "/p".to_owned())]),
                enabled: true,
            },
        );
        servers.insert(
            "docs".to_owned(),
            ContextServer::Http {
                url: "https://example.com/mcp".to_owned(),
                headers: BTreeMap::from([("Authorization".to_owned(), "Bearer x".to_owned())]),
                enabled: true,
            },
        );
        servers.insert(
            "off".to_owned(),
            ContextServer::Stdio {
                command: "quiet".to_owned(),
                args: Vec::new(),
                env: BTreeMap::new(),
                enabled: false,
            },
        );

        let no_http = AgentCaps::default();
        let wire = serde_json::to_value(mcp_servers(&servers, no_http)).unwrap();
        assert_eq!(
            wire,
            serde_json::json!([{
                "name": "fs",
                "command": "npx",
                "args": ["-y", "server"],
                "env": [{"name": "ROOT", "value": "/p"}]
            }])
        );

        let http = AgentCaps {
            mcp_http: true,
            ..AgentCaps::default()
        };
        let wire = serde_json::to_value(mcp_servers(&servers, http)).unwrap();
        assert_eq!(
            wire,
            serde_json::json!([
                {
                    "type": "http",
                    "name": "docs",
                    "url": "https://example.com/mcp",
                    "headers": [{"name": "Authorization", "value": "Bearer x"}]
                },
                {
                    "name": "fs",
                    "command": "npx",
                    "args": ["-y", "server"],
                    "env": [{"name": "ROOT", "value": "/p"}]
                }
            ])
        );
        // And the request carries them under `mcpServers`, camelCase.
        let request = acp::NewSessionRequest::new(PathBuf::from("/proj"))
            .mcp_servers(mcp_servers(&servers, http));
        let json = serde_json::to_value(&request).unwrap();
        assert_eq!(json["mcpServers"].as_array().unwrap().len(), 2);
    }

    /// An attached picture becomes an `Image` block after the text — and only
    /// for an agent that said it reads them. Sending one to an agent that
    /// never advertised `promptCapabilities.image` is at best ignored and at
    /// worst an error, so the block is dropped rather than gambled with.
    #[test]
    fn images_ride_the_prompt_only_when_the_agent_takes_them() {
        let dir = tempfile::tempdir().unwrap();
        let root = std::fs::canonicalize(dir.path()).unwrap();
        let prompt = PromptInput {
            text: "what is this".to_owned(),
            mentions: Vec::new(),
            images: vec![crate::acp_thread::PromptImage {
                mime_type: "image/png".to_owned(),
                data: "aGVsbG8=".to_owned(),
            }],
        };

        let blocks = prompt_blocks(&root, false, true, &prompt);
        assert_eq!(blocks.len(), 2, "the text, then the picture");
        match &blocks[1] {
            acp::ContentBlock::Image(image) => {
                assert_eq!(image.mime_type, "image/png");
                assert_eq!(image.data, "aGVsbG8=");
            }
            other => panic!("expected an image block, got {other:?}"),
        }

        // The same prompt to an agent that never claimed images: text only.
        let blocks = prompt_blocks(&root, false, false, &prompt);
        assert_eq!(blocks.len(), 1, "the picture is dropped, not sent anyway");
    }

    /// The queued-prompt state the panel polls carries the *number* of
    /// attached images, never their bytes: a queue is re-read on every
    /// revision, and a megabyte of base64 crossing JNI each time would be
    /// paid over and over for a prompt that has not even been sent.
    #[test]
    fn a_queued_prompts_images_serialize_as_a_count() {
        let prompt = PromptInput {
            text: "look".to_owned(),
            mentions: Vec::new(),
            images: vec![
                crate::acp_thread::PromptImage {
                    mime_type: "image/png".to_owned(),
                    data: "x".repeat(4096),
                },
                crate::acp_thread::PromptImage {
                    mime_type: "image/jpeg".to_owned(),
                    data: "y".repeat(4096),
                },
            ],
        };
        let json = serde_json::to_string(&prompt).unwrap();
        assert!(json.contains("\"images\":2"), "got {json}");
        assert!(!json.contains("xxxx"), "the bytes must not be in the state");
    }

    fn shared_for_test(sessions: &Sessions, index: &Index) -> Arc<AgentShared> {
        AgentShared::new(
            &AgentSpec {
                name: "test".to_owned(),
                argv: vec!["test".to_owned()],
                env: HashMap::new(),
            },
            "test-key".to_owned(),
            // These tests never spawn anything; the userland is only reached
            // through `terminal/create`, which none of them makes.
            Arc::new(guest::testing::unusable_userland()),
            sessions.clone(),
            index.clone(),
            Arc::new(WrittenFiles::default()),
            Arc::default(),
        )
    }

    /// Replacing an agent must not take the *replacement's* sessions with it.
    ///
    /// The old connection's loop ends — and runs its teardown — after the new
    /// agent is already serving, and the sessions map it can see is the
    /// engine's, holding both. Without ownership it failed everything in
    /// sight, so configuring a different agent killed the session you had just
    /// opened with it.
    #[test]
    fn a_departing_agent_leaves_the_new_agents_sessions_alone() {
        let sessions: Sessions = Arc::default();
        let index: Index = Arc::default();
        let old = shared_for_test(&sessions, &index);
        let new = shared_for_test(&sessions, &index);
        assert_ne!(old.id, new.id, "each connection is its own owner");

        let root = PathBuf::from("/proj");
        let old_session = Arc::new(SessionHandle::new(
            old.id,
            SessionThread::new(1, root.clone()),
        ));
        let new_session = Arc::new(SessionHandle::new(new.id, SessionThread::new(1, root)));
        old_session.update(|thread| thread.ready(acp::SessionId::new("old-1"), None, Vec::new()));
        new_session.update(|thread| thread.ready(acp::SessionId::new("new-1"), None, Vec::new()));
        sessions.lock().unwrap().insert(1, old_session.clone());
        sessions.lock().unwrap().insert(2, new_session.clone());
        index
            .lock()
            .unwrap()
            .insert(acp::SessionId::new("old-1"), 1);
        index
            .lock()
            .unwrap()
            .insert(acp::SessionId::new("new-1"), 2);

        old.agent_gone("the old agent exited".to_owned());

        assert_eq!(
            old_session.thread.lock().unwrap().phase,
            Phase::Unavailable,
            "its own session is over"
        );
        assert_eq!(
            new_session.thread.lock().unwrap().phase,
            Phase::Ready,
            "the replacement's session is untouched"
        );
        // And the acp-id mapping it leaves behind is its own only: the next
        // agent issues its own ids and must not inherit these.
        let index = index.lock().unwrap();
        assert!(!index.contains_key(&acp::SessionId::new("old-1")));
        assert!(index.contains_key(&acp::SessionId::new("new-1")));
    }

    /// The sentence a user is shown when an agent will not start.
    ///
    /// Found on the emulator, not in a test: proot says a missing program in
    /// two lines and the *second* is a pointer to its own usage message, so
    /// taking the last line showed "fatal error: see `libproot_exec.so
    /// --help`" — which tells the user nothing and, because the panel decides
    /// whether to offer the Node install by looking for "not found" in this
    /// very sentence, also took away the way out.
    #[test]
    fn the_sentence_for_a_missing_agent_is_the_one_that_names_it() {
        let sessions: Sessions = Arc::default();
        let index: Index = Arc::default();
        let shared = shared_for_test(&sessions, &index);

        // Verbatim from the device (logcat, 2026-08-18).
        *shared.stderr.lock().unwrap() = concat!(
            "proot error: 'claude-code-acp' not found (root = /data/.../debian, ",
            "cwd = /data/.../projects/Spoon-Knife, $PATH=/usr/local/sbin:/usr/bin)\n",
            "fatal error: see `libproot_exec.so --help`.\n",
        )
        .to_owned();

        let sentence = shared.with_stderr("the agent failed to initialize: transport closed");
        assert!(
            sentence.contains("'claude-code-acp' not found"),
            "the sentence must name what is missing: {sentence}"
        );
        assert!(
            !sentence.contains("--help"),
            "and must not be proot's own usage pointer: {sentence}"
        );
        // The panel keys the "install Node" offer off exactly this.
        assert!(sentence.to_ascii_lowercase().contains("not found"));
        // proot's parenthetical — three absolute paths — is six wrapped lines
        // of panel on a phone and says nothing the user can act on.
        assert_eq!(sentence, "proot error: 'claude-code-acp' not found");
    }

    /// The trim knows exactly one shape — proot's — and leaves everything else
    /// alone, an agent's own parentheses included.
    #[test]
    fn only_proots_own_detail_is_trimmed() {
        assert_eq!(
            trim_proot_detail("proot error: 'x' not found (root = /a, cwd = /b, $PATH=/c)"),
            "proot error: 'x' not found"
        );
        let agents_own = "Error: config invalid (expected an object) at line 3";
        assert_eq!(trim_proot_detail(agents_own), agents_own);
        assert_eq!(trim_proot_detail(""), "");
    }

    /// An agent that started and then died says something useful at the *end*
    /// of its own output, so that is what a hint falls back to.
    #[test]
    fn a_crashing_agent_is_quoted_from_the_end_of_its_traceback() {
        let sessions: Sessions = Arc::default();
        let index: Index = Arc::default();
        let shared = shared_for_test(&sessions, &index);
        *shared.stderr.lock().unwrap() =
            "Traceback:\n  at thing (index.js:4)\nError: no API key configured\n".to_owned();
        assert_eq!(
            shared.with_stderr("the agent exited"),
            "Error: no API key configured"
        );

        // And with nothing on stderr at all, the caller's own words stand.
        *shared.stderr.lock().unwrap() = "  \n\n".to_owned();
        assert_eq!(
            shared.with_stderr("the agent exited (1)"),
            "the agent exited (1)"
        );
    }

    /// A message still in flight on the old wire names a session id the new
    /// agent now owns. It must not be able to touch it.
    #[test]
    fn a_late_message_from_the_old_agent_cannot_reach_the_new_agents_session() {
        let sessions: Sessions = Arc::default();
        let index: Index = Arc::default();
        let old = shared_for_test(&sessions, &index);
        let new = shared_for_test(&sessions, &index);

        let handle = Arc::new(SessionHandle::new(
            new.id,
            SessionThread::new(1, PathBuf::from("/proj")),
        ));
        handle.update(|thread| thread.ready(acp::SessionId::new("s1"), None, Vec::new()));
        sessions.lock().unwrap().insert(7, handle.clone());
        index.lock().unwrap().insert(acp::SessionId::new("s1"), 7);

        assert!(old.session(7).is_none(), "not the old agent's to see");
        assert!(old.session_for_acp_id(&acp::SessionId::new("s1")).is_none());
        assert!(new.session(7).is_some(), "the owner still sees it");

        // A session update arriving late on the old connection is buffered as
        // "unknown session" rather than applied to somebody else's.
        old.on_session_update(acp::SessionNotification::new(
            acp::SessionId::new("s1"),
            acp::SessionUpdate::AgentMessageChunk(acp::ContentChunk::new(acp::ContentBlock::from(
                "from the dead agent".to_owned(),
            ))),
        ));
        assert!(handle.thread.lock().unwrap().entries.is_empty());
    }

    // -------------------------------------------------------------------
    // A full conversation over a real wire, with a fake agent on the other
    // end built from the same SDK. No process anywhere: the transport is a
    // pair of in-memory line channels, which is exactly what the pipes carry.
    // -------------------------------------------------------------------

    type LineTx = futures::channel::mpsc::UnboundedSender<std::io::Result<String>>;
    type LineRx = futures::channel::mpsc::UnboundedReceiver<std::io::Result<String>>;

    fn line_sink(
        tx: LineTx,
    ) -> impl futures::Sink<String, Error = std::io::Error> + Send + 'static {
        futures::sink::unfold(tx, |tx, line: String| async move {
            tx.unbounded_send(Ok(line))
                .map_err(|_| std::io::Error::new(std::io::ErrorKind::BrokenPipe, "peer gone"))?;
            Ok::<_, std::io::Error>(tx)
        })
    }

    fn transport_pair() -> (
        Lines<impl futures::Sink<String, Error = std::io::Error> + Send, LineRx>,
        Lines<impl futures::Sink<String, Error = std::io::Error> + Send, LineRx>,
    ) {
        let (c2a_tx, c2a_rx) = futures::channel::mpsc::unbounded();
        let (a2c_tx, a2c_rx) = futures::channel::mpsc::unbounded();
        (
            Lines::new(line_sink(c2a_tx), a2c_rx),
            Lines::new(line_sink(a2c_tx), c2a_rx),
        )
    }

    /// A minimal but honest ACP agent: answers initialize and session/new,
    /// and on prompt streams a message, runs a tool call through a permission
    /// request, writes a file through fs/write_text_file when allowed, and
    /// ends the turn.
    async fn fake_agent(
        transport: impl ConnectTo<Agent> + 'static,
        file_to_write: PathBuf,
    ) -> Result<(), acp::Error> {
        Agent
            .builder()
            .name("fake-agent")
            .on_receive_request(
                async move |_request: acp::InitializeRequest,
                            responder: Responder<acp::InitializeResponse>,
                            _cx| {
                    responder.respond(acp::InitializeResponse::new(ProtocolVersion::V1))?;
                    Ok(())
                },
                agent_client_protocol::on_receive_request!(),
            )
            .on_receive_request(
                async move |_request: acp::NewSessionRequest,
                            responder: Responder<acp::NewSessionResponse>,
                            _cx| {
                    responder.respond(acp::NewSessionResponse::new(acp::SessionId::new("s1")))?;
                    Ok(())
                },
                agent_client_protocol::on_receive_request!(),
            )
            .on_receive_request(
                {
                    let file_to_write = file_to_write.clone();
                    async move |request: acp::PromptRequest,
                                responder: Responder<acp::PromptResponse>,
                                cx: ConnectionTo<Client>| {
                        let session = request.session_id.clone();
                        let file_to_write = file_to_write.clone();
                        let task_cx = cx.clone();
                        cx.spawn(async move {
                            let update = |u| acp::SessionNotification::new(session.clone(), u);
                            task_cx.send_notification(update(
                                acp::SessionUpdate::AgentMessageChunk(acp::ContentChunk::new(
                                    acp::ContentBlock::from("editing now".to_owned()),
                                )),
                            ))?;
                            let call = acp::ToolCall::new(acp::ToolCallId::new("t1"), "Write file")
                                .kind(acp::ToolKind::Edit)
                                .status(acp::ToolCallStatus::Pending);
                            task_cx.send_notification(update(acp::SessionUpdate::ToolCall(
                                call.clone(),
                            )))?;
                            let outcome = task_cx
                                .send_request(acp::RequestPermissionRequest::new(
                                    session.clone(),
                                    acp::ToolCallUpdate::from(call),
                                    vec![
                                        acp::PermissionOption::new(
                                            acp::PermissionOptionId::new("yes"),
                                            "Allow",
                                            acp::PermissionOptionKind::AllowOnce,
                                        ),
                                        acp::PermissionOption::new(
                                            acp::PermissionOptionId::new("no"),
                                            "Deny",
                                            acp::PermissionOptionKind::RejectOnce,
                                        ),
                                    ],
                                ))
                                .block_task()
                                .await?;
                            let allowed = matches!(
                                outcome.outcome,
                                acp::RequestPermissionOutcome::Selected(selected)
                                    if selected.option_id.0.as_ref() == "yes"
                            );
                            if allowed {
                                task_cx
                                    .send_request(acp::WriteTextFileRequest::new(
                                        session.clone(),
                                        file_to_write.clone(),
                                        "written by the agent\n",
                                    ))
                                    .block_task()
                                    .await?;
                                task_cx.send_notification(update(
                                    acp::SessionUpdate::ToolCallUpdate(acp::ToolCallUpdate::new(
                                        acp::ToolCallId::new("t1"),
                                        acp::ToolCallUpdateFields::new()
                                            .status(acp::ToolCallStatus::Completed)
                                            .content(vec![acp::ToolCallContent::Diff(
                                                acp::Diff::new(
                                                    file_to_write.clone(),
                                                    "written by the agent\n",
                                                )
                                                .old_text("old\n".to_owned()),
                                            )]),
                                    )),
                                ))?;
                            }
                            responder
                                .respond(acp::PromptResponse::new(acp::StopReason::EndTurn))?;
                            Ok(())
                        })?;
                        Ok(())
                    }
                },
                agent_client_protocol::on_receive_request!(),
            )
            .connect_to(transport)
            .await
    }

    /// Spin until `ready` answers, or fail with `what` after five seconds.
    #[track_caller]
    fn wait_for(what: &str, mut ready: impl FnMut() -> bool) {
        let deadline = Instant::now() + Duration::from_secs(5);
        while Instant::now() < deadline {
            if ready() {
                return;
            }
            thread::sleep(Duration::from_millis(5));
        }
        panic!("timed out waiting for {what}");
    }

    struct Rig {
        shared: Arc<AgentShared>,
        handle: Arc<SessionHandle>,
        /// Holds the stand-in proot the rig's userland points at. Dropping it
        /// would delete the script out from under any terminal the fake agent
        /// asks for, so the rig keeps it for its own lifetime.
        _guest_dir: tempfile::TempDir,
    }

    /// The production connection stack over an in-memory wire, with one
    /// session pending — everything `start_agent` builds except the process.
    fn rig(root: &Path, agent_file: &Path) -> Rig {
        let sessions: Sessions = Arc::default();
        let index: Index = Arc::default();
        let written = Arc::new(WrittenFiles::default());
        let buffers: Buffers = Arc::default();
        let spec = AgentSpec {
            name: "fake".to_owned(),
            argv: vec!["fake".to_owned()],
            env: HashMap::new(),
        };
        let guest_dir = tempfile::tempdir().unwrap();
        let shared = AgentShared::new(
            &spec,
            spec.key(),
            Arc::new(guest::testing::fake_userland(guest_dir.path())),
            sessions.clone(),
            index,
            written,
            buffers,
        );
        let handle = Arc::new(SessionHandle::new(
            shared.id,
            SessionThread::new(1, root.to_path_buf()),
        ));
        sessions.lock().unwrap().insert(7, handle.clone());
        shared
            .pending_sessions
            .lock()
            .unwrap()
            .push(PendingSession::new(7, None));

        let (client_transport, agent_transport) = transport_pair();
        let file = agent_file.to_path_buf();
        thread::Builder::new()
            .name("test-fake-agent".to_owned())
            .stack_size(CONNECTION_STACK_SIZE)
            .spawn(move || {
                let _ = futures::executor::block_on(fake_agent(agent_transport, file));
            })
            .unwrap();
        {
            let shared = shared.clone();
            thread::Builder::new()
                .name("test-acp-connection".to_owned())
                .stack_size(CONNECTION_STACK_SIZE)
                .spawn(move || {
                    futures::executor::block_on(run_connection(shared, client_transport));
                })
                .unwrap();
        }
        Rig {
            shared,
            handle,
            _guest_dir: guest_dir,
        }
    }

    #[test]
    fn a_whole_conversation_with_permission_gating_and_a_guarded_write() {
        let dir = tempfile::tempdir().unwrap();
        let root = std::fs::canonicalize(dir.path()).unwrap();
        let file = root.join("notes.txt");
        std::fs::write(&file, "old\n").unwrap();

        let rig = rig(&root, &file);
        let Rig { shared, handle, .. } = &rig;

        // The handshake runs and the pending session comes up on its own.
        wait_for("the session to be ready", || {
            handle.thread.lock().unwrap().phase == Phase::Ready
        });

        // Prompt. The fake agent streams a message, opens a tool call and
        // asks permission.
        let cx = shared.connection().expect("connection is up");
        handle.update(|thread| thread.push_user_message("edit the file"));
        start_prompt(
            shared,
            &cx,
            7,
            PromptInput::text_only("edit the file"),
            false,
        );
        wait_for("the permission prompt", || {
            handle.permissions.lock().unwrap().contains_key("t1")
        });
        {
            let thread = handle.thread.lock().unwrap();
            let waiting = thread.entries.iter().any(|entry| {
                matches!(&entry.body, EntryBody::ToolCall(call)
                    if call.status == ToolStatus::WaitingForConfirmation && call.options.len() == 2)
            });
            assert!(waiting, "the tool call shows its options");
        }

        // Allow it, the production way: decide by option kind, respond, and
        // let the agent write through fs/write_text_file.
        let responder = handle.permissions.lock().unwrap().remove("t1").unwrap();
        handle.update(|thread| {
            thread.finish_permission("t1", PermissionDecision::Allow);
        });
        responder
            .respond(acp::RequestPermissionResponse::new(
                acp::RequestPermissionOutcome::Selected(acp::SelectedPermissionOutcome::new(
                    acp::PermissionOptionId::new("yes"),
                )),
            ))
            .unwrap();

        wait_for("the turn to end", || {
            handle.thread.lock().unwrap().phase == Phase::Ready
                && handle.thread.lock().unwrap().stop_reason.is_some()
        });

        // The write landed on disk, inside the project, and was recorded for
        // the UI's reload poll.
        assert_eq!(
            std::fs::read_to_string(&file).unwrap(),
            "written by the agent\n"
        );
        assert_eq!(shared.written.json(0)["total"], 1);

        // …and checkpointed: the write handler kept what the file held
        // before, on the turn that made it, so the user message offers
        // "Restore checkpoint" and the review counts one file.
        {
            let thread = handle.thread.lock().unwrap();
            assert_eq!(thread.edits.len(), 1);
            assert_eq!(thread.edits[0].path, file);
            assert_eq!(thread.edits[0].before.as_deref(), Some("old\n"));
            assert_eq!(thread.edits[0].turn, 0);
            assert_eq!(thread.pending_edit_count(), 1);
            assert_eq!(thread.entries_json(0)["entries"][0]["checkpoint"], true);
        }

        // The transcript holds the whole exchange: user, assistant, and a
        // completed tool call carrying diff rows.
        let thread = handle.thread.lock().unwrap();
        assert_eq!(thread.stop_reason.as_deref(), Some("end_turn"));
        let mut kinds = Vec::new();
        for entry in &thread.entries {
            match &entry.body {
                EntryBody::User { .. } => kinds.push("user"),
                EntryBody::Assistant { .. } => kinds.push("assistant"),
                EntryBody::CompletedPlan { .. } => kinds.push("completed_plan"),
                EntryBody::ToolCall(call) => {
                    kinds.push("tool_call");
                    assert_eq!(call.status, ToolStatus::Completed);
                    assert!(call.content.iter().any(|content| matches!(
                        content,
                        crate::acp_thread::ToolContent::Diff { diff }
                            if diff.path == "notes.txt" && !diff.hunks.is_empty()
                    )));
                }
            }
        }
        assert_eq!(kinds, vec!["user", "assistant", "tool_call"]);
    }

    /// The ghost-dialog deadlock, pinned: the user presses Stop while the
    /// agent is still streaming, and the agent's `session/request_permission`
    /// arrives *after* the cancel. Parking it deadlocked the turn — the agent
    /// blocks on the answer while the engine waits for its `PromptResponse` —
    /// and surfaced a permission dialog for a turn the user had already
    /// killed. The spec's rule is the fix: a cancelling client answers
    /// `cancelled` immediately.
    #[test]
    fn a_permission_request_after_cancel_is_answered_not_parked() {
        let dir = tempfile::tempdir().unwrap();
        let root = std::fs::canonicalize(dir.path()).unwrap();
        let file = root.join("notes.txt");
        std::fs::write(&file, "old\n").unwrap();

        let rig = rig(&root, &file);
        let Rig { shared, handle, .. } = &rig;
        wait_for("the session to be ready", || {
            handle.thread.lock().unwrap().phase == Phase::Ready
        });

        let cx = shared.connection().expect("connection is up");
        handle.update(|thread| thread.push_user_message("edit the file"));
        // The cancel lands before the agent's permission request can — set
        // the flag first, exactly as `acp_cancel` does, so the ordering is
        // deterministic rather than a race the test usually wins.
        handle.update(|thread| thread.turn_cancelled = true);
        start_prompt(
            shared,
            &cx,
            7,
            PromptInput::text_only("edit the file"),
            false,
        );

        // The turn settles by itself: the agent's permission request was
        // answered `cancelled` on arrival, so it proceeds and responds.
        wait_for("the turn to settle without anyone answering", || {
            let thread = handle.thread.lock().unwrap();
            thread.phase == Phase::Ready && thread.stop_reason.is_some()
        });

        // No dialog: the responder was consumed, not parked.
        assert!(
            handle.permissions.lock().unwrap().is_empty(),
            "a cancelled turn leaves no permission waiting"
        );
        // And the transcript says what the agent was trying when the stop
        // landed — a cancelled tool call, not a question with live options.
        let thread = handle.thread.lock().unwrap();
        let call = thread
            .entries
            .iter()
            .find_map(|entry| match &entry.body {
                EntryBody::ToolCall(call) => Some(call),
                _ => None,
            })
            .expect("the tool call is recorded");
        assert_eq!(call.status, ToolStatus::Canceled);
        assert!(call.options.is_empty(), "no options are offered to answer");
        // Denied means denied: nothing was written.
        assert_eq!(std::fs::read_to_string(&file).unwrap(), "old\n");
    }

    #[test]
    fn a_write_outside_the_project_is_refused_at_the_wire() {
        let dir = tempfile::tempdir().unwrap();
        let root = std::fs::canonicalize(dir.path()).unwrap();
        let outside = tempfile::tempdir().unwrap();
        let target = outside.path().join("escape.txt");

        let rig = rig(&root, &target);
        let Rig { shared, handle, .. } = &rig;
        wait_for("the session to be ready", || {
            handle.thread.lock().unwrap().phase == Phase::Ready
        });
        let cx = shared.connection().expect("connection is up");
        handle.update(|thread| thread.push_user_message("try to escape"));
        start_prompt(
            shared,
            &cx,
            7,
            PromptInput::text_only("try to escape"),
            false,
        );
        wait_for("the permission prompt", || {
            handle.permissions.lock().unwrap().contains_key("t1")
        });
        let responder = handle.permissions.lock().unwrap().remove("t1").unwrap();
        responder
            .respond(acp::RequestPermissionResponse::new(
                acp::RequestPermissionOutcome::Selected(acp::SelectedPermissionOutcome::new(
                    acp::PermissionOptionId::new("yes"),
                )),
            ))
            .unwrap();

        wait_for("the turn to settle", || {
            let thread = handle.thread.lock().unwrap();
            thread.phase != Phase::Running || thread.error.is_some()
        });
        // The wire refused it: nothing outside the project was written.
        assert!(!target.exists());
        assert_eq!(shared.written.json(0)["total"], 0);
    }

    // -------------------------------------------------------------------
    // The process path: real guest spawns. What matters is that a death is
    // noticed and explained with stderr, and that the budget reservation is
    // kept honestly across agents coming and going.
    // -------------------------------------------------------------------

    /// [`guest::RESERVED_FOR_AGENT`] is process-wide, and the suite runs its
    /// tests on threads of one process — so the two tests that assert on it
    /// take turns. Nothing else in the suite spawns an agent.
    #[cfg(unix)]
    static BUDGET_TESTS: Mutex<()> = Mutex::new(());

    #[cfg(unix)]
    fn budget_guard() -> std::sync::MutexGuard<'static, ()> {
        // A test that panicked while holding it poisoned it; the next one
        // still wants to run.
        BUDGET_TESTS.lock().unwrap_or_else(|err| err.into_inner())
    }

    #[cfg(unix)]
    #[test]
    fn a_dead_agent_reports_its_stderr_and_frees_the_budget() {
        let _guard = budget_guard();
        let dir = tempfile::tempdir().unwrap();
        let userland = Arc::new(guest::testing::fake_userland(dir.path()));
        let sessions: Sessions = Arc::default();
        let index: Index = Arc::default();
        let spec = AgentSpec {
            name: "doomed".to_owned(),
            argv: vec![
                "/bin/sh".to_owned(),
                "-c".to_owned(),
                "echo command not found >&2; exit 127".to_owned(),
            ],
            env: HashMap::new(),
        };
        let shared = AgentShared::new(
            &spec,
            spec.key(),
            userland.clone(),
            sessions.clone(),
            index,
            Arc::new(WrittenFiles::default()),
            Arc::default(),
        );
        let handle = Arc::new(SessionHandle::new(
            shared.id,
            SessionThread::new(1, dir.path().to_path_buf()),
        ));
        sessions.lock().unwrap().insert(1, handle.clone());
        shared
            .pending_sessions
            .lock()
            .unwrap()
            .push(PendingSession::new(1, None));

        assert!(start_agent(shared.clone(), userland, &spec, dir.path()));
        assert_eq!(
            guest::RESERVED_FOR_AGENT.load(Ordering::Relaxed),
            PROCESSES_PER_AGENT
        );

        wait_for("the session to fail with the agent's stderr", || {
            let thread = handle.thread.lock().unwrap();
            thread.phase == Phase::Unavailable
                && thread
                    .error
                    .as_deref()
                    .is_some_and(|error| error.contains("command not found"))
        });
        wait_for("the budget to be released", || {
            guest::RESERVED_FOR_AGENT.load(Ordering::Relaxed) == 0
        });
    }

    /// Replacing an agent overlaps the two — the new one starts while the old
    /// one's watcher is still inside proot's SIGQUIT grace — and the
    /// departing watcher must give back only *its* share.
    ///
    /// It used to `store(0)`, so the moment an agent was replaced the
    /// language-server cap silently went back from 2 to 4: the exact
    /// over-subscription the reservation exists to prevent, and invisible
    /// until something got killed.
    #[cfg(unix)]
    #[test]
    fn a_departing_agent_gives_back_only_its_own_share_of_the_budget() {
        let _guard = budget_guard();
        let dir = tempfile::tempdir().unwrap();
        let userland = Arc::new(guest::testing::fake_userland(dir.path()));
        let sessions: Sessions = Arc::default();
        let index: Index = Arc::default();
        // Long-lived: it says nothing and stays up until it is stopped, which
        // is all this test needs of an "agent". SIGQUIT is ignored so that
        // leaving takes the whole grace (`guest.rs::terminate`, QUIT_GRACE):
        // an obedient sh died in milliseconds, and the overlap this test
        // exists to observe was over before the replacement — which now runs
        // a login-shell capture first — had finished starting. The `armed`
        // file is how the test knows the trap is actually installed; a
        // SIGQUIT sent while the shell is still starting up would beat the
        // trap and the departure would be quick again.
        let armed = dir.path().join("armed");
        let spec = AgentSpec {
            name: "quiet".to_owned(),
            argv: vec![
                "/bin/sh".to_owned(),
                "-c".to_owned(),
                format!("trap '' QUIT; : > {}; sleep 30", armed.display()),
            ],
            env: HashMap::new(),
        };

        let old = shared_for_test(&sessions, &index);
        assert!(start_agent(
            old.clone(),
            userland.clone(),
            &spec,
            dir.path()
        ));
        assert_eq!(
            guest::RESERVED_FOR_AGENT.load(Ordering::Relaxed),
            PROCESSES_PER_AGENT
        );

        wait_for("the old agent to arm its trap", || armed.exists());

        // The replacement arrives before the old one is gone, which is the
        // real sequence: `acp_start_session` asks the old one to stop and
        // starts the new one without waiting for the death.
        shutdown_agent(&old);
        let new = shared_for_test(&sessions, &index);
        assert!(start_agent(new.clone(), userland, &spec, dir.path()));
        assert_eq!(
            guest::RESERVED_FOR_AGENT.load(Ordering::Relaxed),
            2 * PROCESSES_PER_AGENT,
            "both are spending processes while they overlap",
        );

        wait_for("the old agent to finish leaving", || {
            old.dead.load(Ordering::Acquire)
        });
        assert_eq!(
            guest::RESERVED_FOR_AGENT.load(Ordering::Relaxed),
            PROCESSES_PER_AGENT,
            "the survivor's reservation is still held",
        );

        shutdown_agent(&new);
        wait_for("the new agent to stop", || new.dead.load(Ordering::Acquire));
        assert_eq!(guest::RESERVED_FOR_AGENT.load(Ordering::Relaxed), 0);
    }

    // -------------------------------------------------------------------
    // The conformance agent: a real external process, over real pipes,
    // through the production spawn path. `tools/conformance-agent.py` is the
    // same file the device runs (configured through `agent_servers`), so a
    // wire-shape mistake in it — or a serde mismatch in us — is a red test
    // here rather than a device session.
    // -------------------------------------------------------------------

    #[cfg(unix)]
    fn conformance_script() -> Option<std::path::PathBuf> {
        // CARGO_MANIFEST_DIR is core/crates/engine; the script lives at the
        // repository root's tools/.
        std::fs::canonicalize(
            Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../tools/conformance-agent.py"),
        )
        .ok()
    }

    #[cfg(unix)]
    /// The agent's last spoken reply, for tests that check what it said.
    /// Takes the thread by reference: `handle.thread` is not reentrant, and
    /// the caller is usually already holding it.
    fn last_reply(thread: &SessionThread) -> String {
        thread
            .entries
            .iter()
            .rev()
            .find_map(|entry| match &entry.body {
                EntryBody::Assistant { chunks } => Some(
                    chunks
                        .iter()
                        .map(|chunk| chunk.markdown.as_str())
                        .collect::<String>(),
                ),
                _ => None,
            })
            .expect("a reply")
    }

    fn tool_call_status(handle: &SessionHandle, id: &str) -> Option<ToolStatus> {
        let thread = handle.thread.lock().unwrap();
        thread
            .entries
            .iter()
            .rev()
            .find_map(|entry| match &entry.body {
                EntryBody::ToolCall(call) if call.id == id => Some(call.status),
                _ => None,
            })
    }

    #[cfg(unix)]
    fn answer_permission(handle: &SessionHandle, id: &str, option: &str) {
        // The body of Engine::acp_respond_permission, without an Engine.
        let responder = handle.permissions.lock().unwrap().remove(id).unwrap();
        let decision = if option.starts_with("allow") || option == "allow" {
            PermissionDecision::Allow
        } else {
            PermissionDecision::Reject
        };
        handle.update(|thread| {
            thread.finish_permission(id, decision);
        });
        responder
            .respond(acp::RequestPermissionResponse::new(
                acp::RequestPermissionOutcome::Selected(acp::SelectedPermissionOutcome::new(
                    acp::PermissionOptionId::new(option.to_owned()),
                )),
            ))
            .unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn a_python_conformance_agent_survives_the_whole_flow() {
        // The guest ships python3; the host is only guaranteed to when the
        // developer installed it. Skipping is honest — the in-process wire
        // test above still covers the client — but say so loudly.
        if std::process::Command::new("python3")
            .arg("--version")
            .output()
            .is_err()
        {
            eprintln!("skipping: no python3 on this host");
            return;
        }
        let Some(script) = conformance_script() else {
            panic!("tools/conformance-agent.py is missing");
        };

        let _guard = budget_guard();
        let dir = tempfile::tempdir().unwrap();
        let userland = Arc::new(guest::testing::fake_userland(dir.path()));
        let project = dir.path().join("proj");
        std::fs::create_dir(&project).unwrap();
        std::fs::write(project.join("notes.md"), "first line\n").unwrap();
        let project = std::fs::canonicalize(&project).unwrap();

        let sessions: Sessions = Arc::default();
        let index: Index = Arc::default();
        let spec = AgentSpec {
            name: "Conformance".to_owned(),
            argv: vec!["python3".to_owned(), script.to_string_lossy().into_owned()],
            env: HashMap::new(),
        };
        let shared = AgentShared::new(
            &spec,
            spec.key(),
            userland.clone(),
            sessions.clone(),
            index,
            Arc::new(WrittenFiles::default()),
            Arc::default(),
        );
        let handle = Arc::new(SessionHandle::new(
            shared.id,
            SessionThread::new(1, project.clone()),
        ));
        sessions.lock().unwrap().insert(1, handle.clone());
        // With a context server configured: it rides `session/new` as
        // `mcpServers`, and the agent — which has no MCP client of its own
        // — advertises what it was given as a slash command, so the shape
        // is checked on the far side of the real pipes, by the schema the
        // agent library parses with.
        let mut context_servers = BTreeMap::new();
        context_servers.insert(
            "fs".to_owned(),
            ContextServer::Stdio {
                command: "npx".to_owned(),
                args: vec!["-y".to_owned(), "server".to_owned()],
                env: BTreeMap::from([("ROOT".to_owned(), "/p".to_owned())]),
                enabled: true,
            },
        );
        // An HTTP server too — not sent, because this agent does not claim
        // `mcpCapabilities.http`, and the list the agent sees must say so.
        context_servers.insert(
            "docs".to_owned(),
            ContextServer::Http {
                url: "https://example.com/mcp".to_owned(),
                headers: BTreeMap::new(),
                enabled: true,
            },
        );
        shared
            .pending_sessions
            .lock()
            .unwrap()
            .push(PendingSession {
                id: 1,
                resume: None,
                context_servers,
            });
        assert!(start_agent(shared.clone(), userland, &spec, &project));

        // initialize and session/new run over the real pipes; fake_proot does
        // not honour the workdir, which is fine — the script takes its cwd
        // from session/new, exactly as it must under the real proot too.
        wait_for("the python agent to reach ready", || {
            handle.thread.lock().unwrap().phase == Phase::Ready
        });

        // The session came up carrying the whole advertised surface: the
        // config options from session/new, and the slash commands that
        // followed as an update.
        wait_for("the advertised commands", || {
            !handle.thread.lock().unwrap().commands.is_empty()
        });
        {
            let thread = handle.thread.lock().unwrap();
            let names: Vec<&str> = thread
                .commands
                .iter()
                .map(|command| command.name.as_str())
                .collect();
            // Everything past `echo` is in the list only because the client
            // advertised the capability behind it — the conformance agent
            // gates each on exactly that — so this line *is* the capability
            // negotiation under test: `run` needs `terminal`, `ask` needs
            // `elicitation.form`, `login` needs `elicitation.url`, and
            // `question` needs `_spettro/question/ask` to have arrived in the
            // **top-level** `_meta` of `initialize`. That last one is the
            // only visible consequence of the gate: put the key anywhere else
            // and this row simply is not offered, with no error to say so.
            assert_eq!(
                names,
                ["plan", "echo", "run", "ask", "withdraw", "login", "question", "mcp"]
            );
            // …and `mcp` names the one server that was sent: the stdio one,
            // never the HTTP one an agent without HTTP MCP may not be given.
            let mcp = thread
                .commands
                .iter()
                .find(|command| command.name == "mcp")
                .expect("the agent advertises its context servers");
            assert_eq!(mcp.description, "Context servers: fs");
            let ids: Vec<String> = thread
                .config_options
                .iter()
                .map(|option| option.id.0.to_string())
                .collect();
            assert_eq!(ids, ["model", "verbose"]);
        }

        // ---- config: pick the other model, watch it stick -----------------
        let cx = shared.connection().expect("connection is up");
        {
            let engine_like = |value: &str| {
                // The Engine method needs the whole Engine; drive the same
                // request the way it does, through the connection.
                let request = acp::SetSessionConfigOptionRequest::new(
                    handle.thread.lock().unwrap().acp_id.clone().unwrap(),
                    acp::SessionConfigId::new("model"),
                    acp::SessionConfigOptionValue::value_id(acp::SessionConfigValueId::new(
                        value.to_owned(),
                    )),
                );
                let task_shared = shared.clone();
                let task_cx = cx.clone();
                let _ = cx.spawn(async move {
                    match task_cx.send_request(request).block_task().await {
                        Ok(response) => {
                            if let Some(handle) = task_shared.session(1) {
                                handle.update(|thread| {
                                    thread.config_options = response.config_options;
                                });
                            }
                        }
                        Err(err) => eprintln!("set_config_option failed: {err:?}"),
                    }
                    Ok(())
                });
            };
            engine_like("conf-two");
        }
        wait_for("the model change to come back", || {
            let thread = handle.thread.lock().unwrap();
            thread.config_options.iter().any(|option| {
                matches!(&option.kind, acp::SessionConfigKind::Select(select)
                    if option.id.0.as_ref() == "model"
                        && select.current_value.0.as_ref() == "conf-two")
            })
        });

        // ---- a slash command with an @-mention ----------------------------
        handle.update(|thread| thread.push_user_message("/echo with context @notes.md"));
        start_prompt(
            &shared,
            &cx,
            1,
            PromptInput {
                text: "/echo with context @notes.md".to_owned(),
                mentions: vec![Mention::File {
                    path: "notes.md".to_owned(),
                }],
                images: Vec::new(),
            },
            false,
        );
        wait_for("the echo turn to end", || {
            let thread = handle.thread.lock().unwrap();
            thread.phase == Phase::Ready && thread.stop_reason.as_deref() == Some("end_turn")
        });
        {
            let thread = handle.thread.lock().unwrap();
            let reply = thread
                .entries
                .iter()
                .rev()
                .find_map(|entry| match &entry.body {
                    EntryBody::Assistant { chunks } => Some(
                        chunks
                            .iter()
                            .map(|chunk| chunk.markdown.as_str())
                            .collect::<String>(),
                    ),
                    _ => None,
                })
                .expect("the echo reply is in the transcript");
            // The mention arrived as a resource block (the client has no
            // embedded-context capability advertised by this fake agent, so
            // as a link), and the picked model rode the reply.
            assert!(reply.contains("notes.md"), "mention named back: {reply}");
            assert!(reply.contains("conf-two"), "picked model in: {reply}");
            assert!(reply.contains("with context"), "echoed text in: {reply}");
        }

        // ---- turn one: an allowed edit of an existing file ----------------
        handle.update(|thread| thread.push_user_message("please edit notes.md"));
        start_prompt(
            &shared,
            &cx,
            1,
            PromptInput::text_only("please edit notes.md"),
            false,
        );

        wait_for("the permission prompt", || {
            handle.permissions.lock().unwrap().contains_key("t-1")
        });
        assert_eq!(
            tool_call_status(&handle, "t-1"),
            Some(ToolStatus::WaitingForConfirmation)
        );
        answer_permission(&handle, "t-1", "allow");

        wait_for("the turn to end", || {
            let thread = handle.thread.lock().unwrap();
            thread.phase == Phase::Ready && thread.stop_reason.as_deref() == Some("end_turn")
        });

        // The write went through the client's fs capability into the project.
        let notes = std::fs::read_to_string(project.join("notes.md")).unwrap();
        assert!(
            notes.starts_with("first line\n") && notes.contains("Edited by the conformance agent"),
            "the file carries the agent's edit: {notes:?}"
        );
        assert_eq!(shared.written.json(0)["total"], 1);

        // The completed call carries diff rows the git renderer can draw:
        // a context row from the old text and a '+' row for the new line.
        {
            let thread = handle.thread.lock().unwrap();
            let diff = thread
                .entries
                .iter()
                .find_map(|entry| match &entry.body {
                    EntryBody::ToolCall(call) if call.id == "t-1" => {
                        assert_eq!(call.status, ToolStatus::Completed);
                        call.content.iter().find_map(|content| match content {
                            crate::acp_thread::ToolContent::Diff { diff } => Some(diff.clone()),
                            _ => None,
                        })
                    }
                    _ => None,
                })
                .expect("the completed call carries a diff");
            assert_eq!(diff.path, "notes.md", "project-relative, like a git diff");
            let kinds: Vec<char> = diff
                .hunks
                .iter()
                .flat_map(|hunk| hunk.lines.iter().map(|line| line.kind))
                .collect();
            assert!(kinds.contains(&'+'), "an added row: {kinds:?}");
            assert!(kinds.contains(&' '), "old-text context rows: {kinds:?}");
            // The plan progressed to done, and the panel chrome has it.
            assert!(
                thread
                    .plan
                    .iter()
                    .all(|entry| entry.status == acp::PlanEntryStatus::Completed),
                "the plan finished"
            );
        }

        // ---- turn two: a rejected creation writes nothing ------------------
        handle.update(|thread| thread.push_user_message("add AGENT_NOTE.md please"));
        start_prompt(
            &shared,
            &cx,
            1,
            PromptInput::text_only("add AGENT_NOTE.md please"),
            false,
        );
        wait_for("the second permission prompt", || {
            handle.permissions.lock().unwrap().contains_key("t-2")
        });
        answer_permission(&handle, "t-2", "reject");
        wait_for("the second turn to end", || {
            let thread = handle.thread.lock().unwrap();
            thread.phase == Phase::Ready && thread.stop_reason.as_deref() == Some("end_turn")
        });
        assert!(
            !project.join("AGENT_NOTE.md").exists(),
            "a rejected call writes nothing"
        );
        assert_eq!(tool_call_status(&handle, "t-2"), Some(ToolStatus::Rejected));

        // ---- turn three: the whole `terminal/*` round trip ------------------
        //
        // The agent creates a terminal, hangs it off a tool call, waits for
        // the exit, reads the output and releases it. All five methods, over
        // the real spawn path, with a command that writes to *both* pipes and
        // exits non-zero — the shape that catches an interleave bug and an
        // exit code dropped on the floor.
        let run = "/run echo out; echo err >&2; exit 3";
        handle.update(|thread| thread.push_user_message(run));
        start_prompt(&shared, &cx, 1, PromptInput::text_only(run), false);
        wait_for("the terminal turn to end", || {
            let thread = handle.thread.lock().unwrap();
            thread.phase == Phase::Ready && thread.stop_reason.as_deref() == Some("end_turn")
        });
        {
            let thread = handle.thread.lock().unwrap();
            let terminal_id = thread
                .entries
                .iter()
                .find_map(|entry| match &entry.body {
                    EntryBody::ToolCall(call) if call.id == "t-3" => {
                        call.content.iter().find_map(|content| match content {
                            crate::acp_thread::ToolContent::Terminal { terminal_id, .. } => {
                                Some(terminal_id.clone())
                            }
                            _ => None,
                        })
                    }
                    _ => None,
                })
                .expect("the tool call names its terminal");
            // Released by the agent at the end of its turn — and *still
            // readable*, because releasing frees the process, not the record
            // of what it printed. Agents release the moment they have the
            // output they wanted, so a card that emptied itself on release
            // would be a card the user could never read.
            let kept = shared
                .terminals
                .get(&terminal_id)
                .expect("a released terminal is still readable");
            let snapshot = kept.snapshot();
            assert!(snapshot.exit.is_some(), "and it is over");
            assert!(
                snapshot.output.contains("out"),
                "with its output: {:?}",
                snapshot.output
            );

            // What the agent said it saw is what the command actually did.
            let reply = thread
                .entries
                .iter()
                .rev()
                .find_map(|entry| match &entry.body {
                    EntryBody::Assistant { chunks } => Some(
                        chunks
                            .iter()
                            .map(|chunk| chunk.markdown.as_str())
                            .collect::<String>(),
                    ),
                    _ => None,
                })
                .expect("a reply");
            assert!(reply.contains("exit code 3"), "the exit code: {reply}");
            assert!(reply.contains("out"), "stdout: {reply}");
            assert!(reply.contains("err"), "stderr too: {reply}");
            // Not `tool_call_status` — that takes this very lock, and asking
            // for it here is a self-deadlock rather than a failed assertion.
            let status = thread.entries.iter().find_map(|entry| match &entry.body {
                EntryBody::ToolCall(call) if call.id == "t-3" => Some(call.status),
                _ => None,
            });
            assert_eq!(status, Some(ToolStatus::Failed), "exit 3 is a failure");
        }

        // ---- the extension handshake, and a question form ------------------
        //
        // THE GATE. The `_meta` this client sends on `initialize` is the one
        // thing that decides whether a Spettro form arrives whole or as a
        // walk of permission prompts, and getting it wrong is silent — no
        // error, anywhere, ever. So it is asserted from both ends: the agent
        // echoes back the methods it read out of the top-level `_meta`, and
        // the question only arrives at all because it did.
        {
            let agent = shared.init.lock().unwrap();
            let InitPhase::Ready(info) = &*agent else {
                panic!("initialized");
            };
            let extensions = info
                .spettro_extensions
                .as_ref()
                .expect("the agent advertised its extension surface");
            assert_eq!(extensions["version"], 4);
            assert_eq!(
                extensions["clientMethods"][0], "_spettro/question/ask",
                "the agent read our _meta from the top level of initialize"
            );
        }
        assert!(shared.speaks_spettro());

        let questions_from = shared.questions.version();
        handle.update(|thread| thread.push_user_message("/question"));
        start_prompt(&shared, &cx, 1, PromptInput::text_only("/question"), false);
        wait_for("the question form", || {
            !shared.questions.view_json().as_array().unwrap().is_empty()
        });
        assert!(
            shared.questions.version() > questions_from,
            "a question opening moves the counter"
        );
        let asked = shared.questions.view_json();
        let question = &asked[0];
        assert_eq!(question["session"], 1, "resolved from params.sessionId");
        // Verbatim, all of it: nothing on this side models the payload.
        assert_eq!(question["payload"]["allowCustomInput"], true);
        assert_eq!(question["payload"]["questions"][0]["id"], "branch");
        assert_eq!(
            question["payload"]["questions"][0]["options"][1]["label"],
            "development"
        );

        assert!(shared.questions.answer(
            question["id"].as_str().unwrap(),
            serde_json::json!({"answers": [
                {"id": "branch", "value": "dev"},
                {"id": "note", "value": "carry on"},
            ]}),
        ));
        wait_for("the question turn to end", || {
            let thread = handle.thread.lock().unwrap();
            thread.phase == Phase::Ready && thread.stop_reason.as_deref() == Some("end_turn")
        });
        assert!(
            shared.questions.view_json().as_array().unwrap().is_empty(),
            "an answered question is over"
        );
        {
            let thread = handle.thread.lock().unwrap();
            let reply = last_reply(&thread);
            assert!(
                reply.contains("branch=dev") && reply.contains("note=carry on"),
                "the answer reached the agent as its own result shape: {reply}"
            );
        }

        // ---- turn four: a form elicitation, answered ------------------------
        //
        // One field of every kind the schema has, so a type flattened on the
        // way out or coerced on the way back shows up here rather than on a
        // device. The agent prints Python's own type name for each value.
        // The panel finds these through the change counter, so the counter
        // must move exactly when the questions do — on the ask and on the
        // answer, and not on the reads in between.
        let elicit_version = shared.elicitations.version();
        handle.update(|thread| thread.push_user_message("/ask"));
        start_prompt(&shared, &cx, 1, PromptInput::text_only("/ask"), false);
        let question = {
            wait_for("the form question", || {
                !shared.elicitations.for_session(1).is_empty()
            });
            assert!(
                shared.elicitations.version() > elicit_version,
                "a question opening moves the counter"
            );
            let asked = shared.elicitations.for_session(1);
            assert_eq!(asked.len(), 1);
            asked[0].clone()
        };
        assert_eq!(question["mode"], "form");
        assert_eq!(question["title"], "Conformance form");
        let fields: std::collections::BTreeMap<&str, &serde_json::Value> = question["fields"]
            .as_array()
            .expect("fields")
            .iter()
            .map(|field| (field["key"].as_str().unwrap(), field))
            .collect();
        assert_eq!(fields["note"]["required"], true);
        assert_eq!(fields["branch"]["options"][1]["title"], "development");
        assert_eq!(fields["depth"]["type"], "integer");
        assert_eq!(fields["tags"]["options"].as_array().unwrap().len(), 3);

        let id = question["id"].as_str().unwrap();
        let answered_from = shared.elicitations.version();
        assert!(shared.elicitations.respond(
            id,
            r#"{"action":"accept","content":{
                 "note":"hello","branch":"dev","depth":7,"dry":false,"tags":["a","c"]}}"#,
        ));
        assert!(
            shared.elicitations.version() > answered_from,
            "the answer moves the counter too — that is how the card goes away"
        );
        wait_for("the form turn to end", || {
            let thread = handle.thread.lock().unwrap();
            thread.phase == Phase::Ready && thread.stop_reason.as_deref() == Some("end_turn")
        });
        assert!(
            shared.elicitations.for_session(1).is_empty(),
            "an answered form question is over"
        );
        {
            let thread = handle.thread.lock().unwrap();
            let reply = last_reply(&thread);
            // The types survived the round trip: an integer stayed an
            // integer and a boolean stayed a boolean.
            assert!(
                reply.contains("depth=7 (int)"),
                "integer stayed one: {reply}"
            );
            assert!(
                reply.contains("dry=False (bool)"),
                "boolean stayed one: {reply}"
            );
            assert!(reply.contains("branch='dev' (str)"), "the choice: {reply}");
            assert!(
                reply.contains("tags=['a', 'c'] (list)"),
                "the multi-select: {reply}"
            );
        }

        // ---- turn five: a URL elicitation ----------------------------------
        //
        // Accepting answers the agent but leaves the card up; only the
        // agent's `elicitation/complete` takes it away, because only the
        // agent can see whether the sign-in happened.
        handle.update(|thread| thread.push_user_message("/login"));
        start_prompt(&shared, &cx, 1, PromptInput::text_only("/login"), false);
        wait_for("the URL question", || {
            !shared.elicitations.for_session(1).is_empty()
        });
        let question = shared.elicitations.for_session(1)[0].clone();
        assert_eq!(question["mode"], "url");
        assert_eq!(question["url"], "https://example.com/conformance/login");
        assert_eq!(question["accepted"], false);
        assert!(
            shared
                .elicitations
                .respond(question["id"].as_str().unwrap(), r#"{"action":"accept"}"#)
        );
        wait_for("the agent to take its question back", || {
            shared.elicitations.for_session(1).is_empty()
        });
        wait_for("the login turn to end", || {
            let thread = handle.thread.lock().unwrap();
            thread.phase == Phase::Ready && thread.stop_reason.as_deref() == Some("end_turn")
        });
        {
            let thread = handle.thread.lock().unwrap();
            assert!(last_reply(&thread).contains("Signed in"));
        }

        // ---- a question the agent takes back -------------------------------
        //
        // `$/cancel_request` on an outstanding `elicitation/create`. Nothing
        // else notices it: the responder is parked until somebody answers, so
        // without watching the cancellation marker the card stays up for ever
        // and the user's answer goes to a request that is no longer live.
        handle.update(|thread| thread.push_user_message("/withdraw"));
        start_prompt(&shared, &cx, 1, PromptInput::text_only("/withdraw"), false);
        wait_for("the withdrawn turn to end", || {
            let thread = handle.thread.lock().unwrap();
            thread.phase == Phase::Ready && thread.stop_reason.as_deref() == Some("end_turn")
        });
        wait_for("the question to be taken back", || {
            shared.elicitations.for_session(1).is_empty()
        });

        // ---- the session lifecycle: list, load, delete ---------------------
        //
        // These four methods are the difference between threads that live as
        // long as the agent process and threads the agent itself remembers.
        assert!(shared.caps().list && shared.caps().load_session && shared.caps().delete);
        // The list is found the same way: its version is what the panel
        // polls, and a refresh must move it at both ends — once for
        // `loading`, once for the answer.
        let list_version = shared.session_list.lock().unwrap().version;
        shared.refresh_session_list();
        wait_for("the session list", || {
            let list = shared.session_list.lock().unwrap();
            !list.loading && list.sessions.as_array().is_some_and(|s| !s.is_empty())
        });
        assert!(
            shared.session_list.lock().unwrap().version >= list_version + 2,
            "the refresh moved the list version for loading and for the answer"
        );
        let listed = {
            let list = shared.session_list.lock().unwrap();
            assert_eq!(list.error, None);
            list.sessions.clone()
        };
        let past_id = listed[0]["sessionId"].as_str().unwrap().to_owned();
        assert_eq!(listed[0]["title"], "Conformance conversation");

        // Reopen it as a *second* thread. `session/load` replays the
        // conversation as ordinary updates, so the new thread fills itself in
        // — that is what distinguishes it from `session/resume`.
        let second = Arc::new(SessionHandle::new(
            shared.id,
            SessionThread::new(1, project.clone()),
        ));
        sessions.lock().unwrap().insert(2, second.clone());
        futures::executor::block_on(create_session(
            shared.clone(),
            cx.clone(),
            PendingSession::new(2, Some(past_id.clone())),
        ));
        {
            let thread = second.thread.lock().unwrap();
            assert_eq!(thread.phase, Phase::Ready);
            // One entry, not many: the replayed chunks are consecutive agent
            // messages and the state machine merges those, exactly as it does
            // live. What proves the replay happened is what is *in* it.
            let replayed = last_reply(&thread);
            assert!(
                replayed.contains("Done — `notes.md` updated."),
                "the first turn came back: {replayed}"
            );
            assert!(
                replayed.contains("Signed in"),
                "and the last one: {replayed}"
            );
        }

        // And forgetting one takes it off the list.
        assert!(shared.caps().delete);
        {
            let request = acp::DeleteSessionRequest::new(acp::SessionId::new(past_id));
            let task_shared = shared.clone();
            let task_cx = cx.clone();
            let _ = cx.spawn(async move {
                let _ = task_cx.send_request(request).block_task().await;
                task_shared.refresh_session_list();
                Ok(())
            });
        }
        wait_for("the deleted session to go", || {
            let list = shared.session_list.lock().unwrap();
            !list.loading
                && list
                    .sessions
                    .as_array()
                    .is_some_and(|sessions| sessions.len() == 1)
        });

        // ---- shutdown: EOF on its stdin is how the python process learns ---
        shutdown_agent(&shared);
        wait_for("the agent to exit", || shared.dead.load(Ordering::Acquire));
        assert_eq!(guest::RESERVED_FOR_AGENT.load(Ordering::Relaxed), 0);
    }
}
