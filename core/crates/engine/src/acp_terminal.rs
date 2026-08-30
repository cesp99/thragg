//! Agent-owned terminals: the `terminal/*` half of the client capability.
//!
//! An ACP agent that wants to run `cargo test` does not run it itself — it
//! asks the client to, through `terminal/create`, and then reads the output
//! back with `terminal/output` and waits for the end with
//! `terminal/wait_for_exit` (Zed serves the same five methods,
//! agent_servers/src/acp.rs:722-740). A client that does not advertise
//! `terminal: true` gets an agent that shells out inside its own process
//! instead, and the user sees **nothing at all** — no command, no output, no
//! exit code. That is what this module is for.
//!
//! Three things shape it:
//!
//! **The process is an ordinary guest process.** `guest::spawn` gives the
//! proot binds, the guest environment and the SIGQUIT-first shutdown, exactly
//! as it does for an agent or a language server. What it does not give is a
//! pty: the engine has no terminal emulator, the app's terminal dock is
//! Termux's on the Kotlin side, and an ACP terminal exists to be *read back as
//! text*. So stdout and stderr are pipes, interleaved into one buffer in
//! arrival order, and stdin is closed immediately — a command that stops to
//! ask a question gets EOF and exits, rather than hanging for ever behind a
//! prompt nobody can see. Programs that check `isatty` will take their
//! non-interactive branch, which for the `cargo test`/`npm run` shape of
//! command an agent actually runs is the branch you want anyway.
//!
//! **One thread per terminal owns the child.** Killing needs the child, and
//! polling for its exit needs the child, so the child is not shared: the
//! watcher thread owns it outright and a kill is a flag the watcher observes —
//! the same shape [`crate::acp`]'s own agent watcher uses. That is why
//! [`AgentTerminal`] has no `Mutex<GuestProcess>` anywhere in it.
//!
//! **Waiting never blocks the connection.** `terminal/wait_for_exit` is a
//! request that by definition does not answer until the command ends, and the
//! SDK dispatches it on the connection's event loop — so the responder is
//! parked here and answered by the watcher, the same trick the permission
//! prompts use.
//!
//! Units: `output` is UTF-8 text; the byte limit the agent asks for is a limit
//! on that text's bytes, and truncation drops from the *front* (the protocol's
//! own rule: "the Client truncates from the beginning … MUST ensure truncation
//! happens at a character boundary").

use std::collections::HashMap;
use std::io::Read;
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use agent_client_protocol::Responder;
use agent_client_protocol::schema::v1 as acp;

use crate::guest::{self, GuestCommand};

/// How often the watcher looks at its child. The agent's own watcher polls at
/// the same rate, for the same reason: a command's exit is worth noticing
/// promptly and nothing else about it is urgent.
const WATCH_INTERVAL: Duration = Duration::from_millis(100);

/// How long the watcher waits for the readers to drain after the child has
/// gone before it declares the output final. The pipes close *at* exit, so in
/// practice this is a couple of polls; the bound is only here so a reader
/// wedged on a pipe some grandchild inherited cannot park `wait_for_exit`
/// for ever.
const DRAIN_GRACE: Duration = Duration::from_secs(2);

/// The most output one terminal keeps, whatever the agent asked for.
///
/// The protocol lets the agent set `outputByteLimit`, and lets it set nothing
/// at all — which would mean "keep everything", and a `yes` or a verbose build
/// would then be an out-of-memory kill on a phone. So this is the ceiling and
/// the agent's own limit only ever lowers it. Sized to be generous for the
/// output an agent actually reads back (a test run, a build log's tail) and
/// nowhere near a memory problem.
const MAX_OUTPUT_BYTES: usize = 1024 * 1024;

/// How much of a released terminal's output is kept for the card.
///
/// Releasing frees the process; it does not un-say what the command printed.
/// Zed keeps the terminal in the thread after release for exactly this reason
/// — the transcript still has the tool call, and a card that empties itself
/// the moment the agent tidies up is a card the user can never read, because
/// agents release as soon as they have the output they wanted. Smaller than
/// the live cap because this is scrollback, not a working buffer.
const RETAINED_OUTPUT_BYTES: usize = 64 * 1024;

/// How many released terminals are kept. Oldest first out; the transcript
/// keeps the tool call either way, and a session that ran hundreds of
/// commands does not need every one of their logs resident.
const MAX_RETAINED: usize = 16;

/// One live terminal.
pub(crate) struct AgentTerminal {
    /// The id the agent knows it by, and the id the panel asks for.
    pub(crate) id: String,
    /// Which of *our* session ids owns it, so closing a session takes its
    /// terminals with it.
    pub(crate) session: u64,
    /// The command line, for the card's header. The protocol never sends this
    /// back — `ToolCallContent::Terminal` carries only an id — so the client
    /// is the only one that can say what is running.
    pub(crate) label: String,
    /// Where it ran, **project-relative**, so the card can say so.
    ///
    /// A command's meaning depends on its directory, and Zed puts it in the
    /// header (terminal_tool_header.rs:136-171). Relative because the
    /// absolute path on Android is
    /// `/data/user/0/to.eyed.seeker.code/files/projects/…`, forty
    /// characters of which say nothing to anybody. Empty means the project
    /// root itself, which is the common case and needs no line at all.
    pub(crate) cwd: String,
    limit: usize,
    state: Mutex<TerminalState>,
    /// Parked `terminal/wait_for_exit` responders. More than one is legal:
    /// nothing stops an agent asking twice.
    waiters: Mutex<Vec<Responder<acp::WaitForTerminalExitResponse>>>,
    /// Set by `terminal/kill`; the watcher acts on it.
    killed: AtomicBool,
    /// How many reader threads are still going. The watcher waits for zero
    /// before it calls the output final, so `wait_for_exit` never answers
    /// ahead of the last line the command printed.
    readers: Arc<AtomicU64>,
}

struct TerminalState {
    /// When it started and, once it has, when it ended — so a card can say
    /// "running for 4m" rather than the same static word for a wedged
    /// command and a long build.
    started: Instant,
    ended: Option<Instant>,
    /// How much was thrown off the front to stay under the limit. "Some
    /// output was dropped" is not a useful sentence; "2.4 MB of earlier
    /// output dropped" is.
    dropped_bytes: u64,
    dropped_lines: u64,
    /// Bumped by every append and by the exit, so the panel can poll cheaply.
    ///
    /// **Starts at 1**, for the reason [`crate::acp_thread::SessionThread`]'s
    /// does: zero is the answer for a terminal the engine does not have, so a
    /// live one that has simply printed nothing yet must not report it. It
    /// would read as "released" and the card would stop polling before the
    /// command had said a word.
    revision: u64,
    output: String,
    /// Whether anything was dropped off the front to stay under the limit.
    truncated: bool,
    exit: Option<acp::TerminalExitStatus>,
}

impl Default for TerminalState {
    fn default() -> Self {
        TerminalState {
            started: Instant::now(),
            ended: None,
            dropped_bytes: 0,
            dropped_lines: 0,
            revision: 1,
            output: String::new(),
            truncated: false,
            exit: None,
        }
    }
}

/// What the panel reads, and what `terminal/output` answers with.
pub(crate) struct TerminalSnapshot {
    pub(crate) revision: u64,
    pub(crate) label: String,
    pub(crate) cwd: String,
    pub(crate) output: String,
    pub(crate) truncated: bool,
    pub(crate) dropped_bytes: u64,
    pub(crate) dropped_lines: u64,
    /// How long it has been running, or ran. Frozen at the exit, so a
    /// finished card does not keep counting.
    pub(crate) elapsed: Duration,
    pub(crate) exit: Option<acp::TerminalExitStatus>,
}

impl AgentTerminal {
    fn append(&self, text: &str) {
        if text.is_empty() {
            return;
        }
        let mut state = self.state.lock().unwrap();
        state.output.push_str(text);
        // Trim from the front, on a character boundary — the protocol says
        // both halves of that sentence, and `floor_char_boundary` is not
        // stable, so walk off the continuation bytes by hand.
        if state.output.len() > self.limit {
            let cut = state.output.len() - self.limit;
            let cut = state
                .output
                .char_indices()
                .map(|(index, _)| index)
                .find(|index| *index >= cut)
                .unwrap_or(state.output.len());
            state.dropped_bytes += cut as u64;
            state.dropped_lines += state.output[..cut].matches('\n').count() as u64;
            state.output.drain(..cut);
            state.truncated = true;
        }
        state.revision += 1;
    }

    fn finish(&self, exit: acp::TerminalExitStatus) {
        {
            let mut state = self.state.lock().unwrap();
            if state.exit.is_some() {
                return;
            }
            state.exit = Some(exit.clone());
            state.ended = Some(Instant::now());
            state.revision += 1;
        }
        for responder in self.waiters.lock().unwrap().drain(..) {
            let _ = responder.respond(acp::WaitForTerminalExitResponse::new(exit.clone()));
        }
    }

    pub(crate) fn snapshot(&self) -> TerminalSnapshot {
        let state = self.state.lock().unwrap();
        TerminalSnapshot {
            revision: state.revision,
            label: self.label.clone(),
            cwd: self.cwd.clone(),
            output: state.output.clone(),
            truncated: state.truncated,
            dropped_bytes: state.dropped_bytes,
            dropped_lines: state.dropped_lines,
            elapsed: state.ended.unwrap_or_else(Instant::now) - state.started,
            exit: state.exit.clone(),
        }
    }

    /// Cut the kept output down to `limit`, on a character boundary. Called
    /// once, when the terminal is released and stops growing.
    fn trim_to(&self, limit: usize) {
        let mut state = self.state.lock().unwrap();
        if state.output.len() <= limit {
            return;
        }
        let cut = state.output.len() - limit;
        let cut = state
            .output
            .char_indices()
            .map(|(index, _)| index)
            .find(|index| *index >= cut)
            .unwrap_or(state.output.len());
        state.dropped_bytes += cut as u64;
        state.dropped_lines += state.output[..cut].matches('\n').count() as u64;
        state.output.drain(..cut);
        state.truncated = true;
        state.revision += 1;
    }

    /// The revision alone, for a poller that only wants to know whether to
    /// re-read. Cheap: no copy of the output.
    pub(crate) fn revision(&self) -> u64 {
        self.state.lock().unwrap().revision
    }

    /// Park a `wait_for_exit`, or answer it now if the command is already
    /// over.
    pub(crate) fn wait(&self, responder: Responder<acp::WaitForTerminalExitResponse>) {
        let exit = {
            let state = self.state.lock().unwrap();
            state.exit.clone()
        };
        match exit {
            Some(exit) => {
                let _ = responder.respond(acp::WaitForTerminalExitResponse::new(exit));
            }
            None => self.waiters.lock().unwrap().push(responder),
        }
    }

    /// Ask the watcher to take the command down. Returns once the request is
    /// recorded, not once the process is gone — the agent's `terminal/kill`
    /// is allowed to be prompt, and `wait_for_exit` is how it learns the rest.
    pub(crate) fn kill(&self) {
        self.killed.store(true, Ordering::Release);
    }

    /// Answer every parked waiter, for a terminal that is being released or
    /// whose session is closing. A responder dropped without an answer leaves
    /// the agent waiting for ever.
    fn abandon(&self) {
        self.kill();
        let exit = acp::TerminalExitStatus::new().signal("SIGQUIT".to_owned());
        self.finish(exit);
    }
}

/// Every terminal one agent connection has open.
#[derive(Default)]
pub(crate) struct Terminals {
    next: AtomicU64,
    live: Mutex<HashMap<String, Arc<AgentTerminal>>>,
    /// Released terminals, newest last — see [`RETAINED_OUTPUT_BYTES`].
    /// The process is gone; what is kept is what it said.
    finished: Mutex<Vec<Arc<AgentTerminal>>>,
}

impl Terminals {
    pub(crate) fn get(&self, id: &str) -> Option<Arc<AgentTerminal>> {
        if let Some(terminal) = self.live.lock().unwrap().get(id) {
            return Some(terminal.clone());
        }
        self.finished
            .lock()
            .unwrap()
            .iter()
            .find(|terminal| terminal.id == id)
            .cloned()
    }

    /// Start a command and register the terminal it runs in.
    ///
    /// `root` is the session's project root: the cwd the agent asks for is
    /// confined to it by the same [`crate::acp::resolves_inside`] rule the fs
    /// handlers use, because a working directory is a capability too — a
    /// command run in `$HOME` is a command that can read `$HOME`.
    pub(crate) fn create(
        &self,
        userland: &guest::Userland,
        session: u64,
        root: &Path,
        request: &acp::CreateTerminalRequest,
    ) -> Result<Arc<AgentTerminal>, acp::Error> {
        let cwd = match &request.cwd {
            Some(cwd) => {
                if !crate::acp::resolves_inside(root, cwd) {
                    return Err(acp::Error::invalid_params().data(serde_json::json!({
                        "message": format!(
                            "the working directory {} is outside the project",
                            cwd.display()
                        ),
                    })));
                }
                cwd.clone()
            }
            None => root.to_path_buf(),
        };

        let label = if request.args.is_empty() {
            request.command.clone()
        } else {
            format!("{} {}", request.command, request.args.join(" "))
        };
        let mut argv = vec![std::ffi::OsString::from(&request.command)];
        argv.extend(request.args.iter().map(std::ffi::OsString::from));
        let mut command = GuestCommand::new(format!("acp-term:{label}"), argv).workdir(&cwd);
        for variable in &request.env {
            command = command.env(&variable.name, &variable.value);
        }

        let Some(mut process) = guest::spawn(userland, &command) else {
            return Err(acp::Error::internal_error().data(serde_json::json!({
                "message": format!("could not start `{}`", request.command),
            })));
        };

        let id = format!("term-{}", self.next.fetch_add(1, Ordering::Relaxed) + 1);
        let limit = request
            .output_byte_limit
            .map(|limit| limit.min(MAX_OUTPUT_BYTES as u64) as usize)
            .unwrap_or(MAX_OUTPUT_BYTES)
            // A limit of zero would make every append truncate everything;
            // treat it as "the smallest useful buffer" rather than a trap.
            .max(1);
        let terminal = Arc::new(AgentTerminal {
            id: id.clone(),
            session,
            label,
            // Relative to the project, and empty for the root itself.
            cwd: cwd
                .strip_prefix(root)
                .map(|rest| rest.to_string_lossy().into_owned())
                .unwrap_or_else(|_| cwd.to_string_lossy().into_owned()),
            limit,
            state: Mutex::new(TerminalState::default()),
            waiters: Mutex::new(Vec::new()),
            killed: AtomicBool::new(false),
            readers: Arc::new(AtomicU64::new(0)),
        });

        // Closing stdin now is the difference between a command that ends and
        // one that waits for input nobody can type — see the module note.
        drop(process.take_stdin());
        if let Some(stdout) = process.take_stdout() {
            spawn_reader(terminal.clone(), stdout, "acp-term-out");
        }
        if let Some(stderr) = process.take_stderr() {
            spawn_reader(terminal.clone(), stderr, "acp-term-err");
        }

        {
            let terminal = terminal.clone();
            let name = format!("acp-term-{id}");
            let _ = thread::Builder::new()
                .name(name)
                .spawn(move || watch(terminal, process));
        }

        self.live.lock().unwrap().insert(id, terminal.clone());
        Ok(terminal)
    }

    /// `terminal/release`: the agent is done with it.
    ///
    /// The command dies with it — release is the end of the terminal's life,
    /// not a detach — but what it printed is kept, trimmed, so the card in
    /// the transcript still says what happened. Agents release the moment
    /// they have read the output they wanted, which for a card the user has
    /// not opened yet is immediately.
    pub(crate) fn release(&self, id: &str) -> bool {
        let Some(terminal) = self.live.lock().unwrap().remove(id) else {
            return false;
        };
        terminal.abandon();
        terminal.trim_to(RETAINED_OUTPUT_BYTES);
        let mut finished = self.finished.lock().unwrap();
        finished.push(terminal);
        while finished.len() > MAX_RETAINED {
            finished.remove(0);
        }
        true
    }

    /// Every terminal belonging to a session that is going away — the live
    /// ones and the kept ones alike, because the thread they were shown in
    /// is going too.
    pub(crate) fn release_session(&self, session: u64) {
        self.finished
            .lock()
            .unwrap()
            .retain(|terminal| terminal.session != session);
        let doomed: Vec<Arc<AgentTerminal>> = {
            let mut live = self.live.lock().unwrap();
            let ids: Vec<String> = live
                .values()
                .filter(|terminal| terminal.session == session)
                .map(|terminal| terminal.id.clone())
                .collect();
            ids.iter().filter_map(|id| live.remove(id)).collect()
        };
        for terminal in doomed {
            terminal.abandon();
        }
    }

    /// Everything, for a connection that is shutting down. Nothing is kept:
    /// the sessions those cards belonged to are going with it.
    pub(crate) fn release_all(&self) {
        let doomed: Vec<Arc<AgentTerminal>> =
            self.live.lock().unwrap().drain().map(|(_, t)| t).collect();
        for terminal in doomed {
            terminal.abandon();
        }
        self.finished.lock().unwrap().clear();
    }
}

fn spawn_reader(terminal: Arc<AgentTerminal>, mut pipe: impl Read + Send + 'static, name: &str) {
    terminal.readers.fetch_add(1, Ordering::AcqRel);
    let readers = terminal.readers.clone();
    let _ = thread::Builder::new().name(name.to_owned()).spawn(move || {
        let mut buffer = [0u8; 8 * 1024];
        let mut pending: Vec<u8> = Vec::new();
        loop {
            match pipe.read(&mut buffer) {
                Ok(0) | Err(_) => break,
                Ok(read) => {
                    pending.extend_from_slice(&buffer[..read]);
                    let text = take_utf8(&mut pending);
                    terminal.append(&text);
                }
            }
        }
        // A trailing partial sequence at EOF will never complete; show it as
        // the replacement character rather than silently eating the tail.
        if !pending.is_empty() {
            terminal.append(&String::from_utf8_lossy(&pending));
        }
        readers.fetch_sub(1, Ordering::AcqRel);
    });
}

/// Own the child, take it down when asked, and record how it ended.
fn watch(terminal: Arc<AgentTerminal>, mut process: guest::GuestProcess) {
    let status = loop {
        if terminal.killed.load(Ordering::Acquire) {
            break process.terminate_now();
        }
        if let Some(status) = process.exit_status() {
            break Some(status);
        }
        thread::sleep(WATCH_INTERVAL);
    };

    // The readers see EOF when the child's pipes close, which is at exit — but
    // "at" is not "before", and a `wait_for_exit` answered ahead of the last
    // chunk would hand the agent a truncated build log and call it complete.
    let deadline = Instant::now() + DRAIN_GRACE;
    while terminal.readers.load(Ordering::Acquire) > 0 && Instant::now() < deadline {
        thread::sleep(WATCH_INTERVAL);
    }

    terminal.finish(exit_status(status));
}

/// `std::process::ExitStatus` in the protocol's shape: a code, or the name of
/// the signal that ended it.
fn exit_status(status: Option<std::process::ExitStatus>) -> acp::TerminalExitStatus {
    let Some(status) = status else {
        // The wait itself failed. Neither field is honest here, and the
        // protocol allows both to be absent — an agent reads that as "it is
        // over, and nothing is known about how".
        return acp::TerminalExitStatus::new();
    };
    #[cfg(unix)]
    {
        use std::os::unix::process::ExitStatusExt;
        if let Some(signal) = status.signal() {
            return acp::TerminalExitStatus::new().signal(signal_name(signal));
        }
    }
    match status.code() {
        Some(code) => acp::TerminalExitStatus::new().exit_code(code.max(0) as u32),
        None => acp::TerminalExitStatus::new(),
    }
}

/// The signal's name, which is what the protocol's field holds — a number
/// there would be a number the agent has to guess the meaning of.
fn signal_name(signal: i32) -> String {
    #[cfg(unix)]
    {
        let name = match signal {
            libc::SIGHUP => "SIGHUP",
            libc::SIGINT => "SIGINT",
            libc::SIGQUIT => "SIGQUIT",
            libc::SIGILL => "SIGILL",
            libc::SIGABRT => "SIGABRT",
            libc::SIGFPE => "SIGFPE",
            libc::SIGKILL => "SIGKILL",
            libc::SIGSEGV => "SIGSEGV",
            libc::SIGPIPE => "SIGPIPE",
            libc::SIGALRM => "SIGALRM",
            libc::SIGTERM => "SIGTERM",
            libc::SIGBUS => "SIGBUS",
            libc::SIGXCPU => "SIGXCPU",
            libc::SIGXFSZ => "SIGXFSZ",
            _ => return format!("SIG{signal}"),
        };
        return name.to_owned();
    }
    #[cfg(not(unix))]
    format!("SIG{signal}")
}

/// As much of `buffer` as is complete UTF-8, leaving a sequence cut by a read
/// boundary behind for the next round.
///
/// Without this, two readers appending raw bytes to one string would split a
/// multi-byte character down the middle whenever a chunk boundary landed
/// inside one — every accented character in a compiler's error message is a
/// candidate.
fn take_utf8(buffer: &mut Vec<u8>) -> String {
    let (text, consumed) = match std::str::from_utf8(buffer) {
        Ok(text) => (text.to_owned(), buffer.len()),
        Err(err) => {
            let good = err.valid_up_to();
            // `buffer[..good]` is valid by construction, so this is exact
            // rather than lossy.
            let mut text = String::from_utf8_lossy(&buffer[..good]).into_owned();
            match err.error_len() {
                // Bytes that are not UTF-8 and never will be. Stand them in
                // and step past, or the stream wedges here for ever.
                Some(bad) => {
                    text.push('\u{fffd}');
                    (text, good + bad)
                }
                // Cut by the read boundary: keep it for next time.
                None => (text, good),
            }
        }
    };
    buffer.drain(..consumed);
    text
}

/// The panel's view of a terminal, as JSON.
///
/// `output` is present only when the caller's `since` is behind — the panel
/// polls this at the same rate it polls everything else, and a megabyte of
/// build log crossing JNI eight times a second for no reason is the kind of
/// cost that does not show up until somebody runs a real build.
pub(crate) fn snapshot_json(terminal: &AgentTerminal, since: u64) -> serde_json::Value {
    let revision = terminal.revision();
    if revision == since {
        return serde_json::json!({ "revision": revision });
    }
    let snapshot = terminal.snapshot();
    serde_json::json!({
        "revision": snapshot.revision,
        "label": snapshot.label,
        "cwd": snapshot.cwd,
        "output": snapshot.output,
        "truncated": snapshot.truncated,
        // How much was dropped, not merely that something was: a card that
        // says "2.4 MB of earlier output dropped" tells the reader whether to
        // go looking elsewhere, and "some output was dropped" does not.
        "droppedBytes": snapshot.dropped_bytes,
        "droppedLines": snapshot.dropped_lines,
        "elapsedMs": snapshot.elapsed.as_millis() as u64,
        "exitStatus": snapshot.exit,
        "running": snapshot.exit.is_none(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_character_split_across_reads_survives() {
        // "é" is two bytes; hand them over one read apart.
        let mut buffer = vec![b'a', 0xC3];
        assert_eq!(take_utf8(&mut buffer), "a");
        assert_eq!(buffer, vec![0xC3]);
        buffer.push(0xA9);
        assert_eq!(take_utf8(&mut buffer), "é");
        assert!(buffer.is_empty());
    }

    #[test]
    fn bytes_that_can_never_be_utf8_do_not_wedge_the_stream() {
        let mut buffer = vec![b'a', 0xFF, b'b'];
        assert_eq!(take_utf8(&mut buffer), "a\u{fffd}");
        assert_eq!(take_utf8(&mut buffer), "b");
    }

    fn terminal_for_test(limit: usize) -> AgentTerminal {
        AgentTerminal {
            id: "term-1".to_owned(),
            session: 1,
            label: "sh -c :".to_owned(),
            cwd: String::new(),
            limit,
            state: Mutex::new(TerminalState::default()),
            waiters: Mutex::new(Vec::new()),
            killed: AtomicBool::new(false),
            readers: Arc::new(AtomicU64::new(0)),
        }
    }

    #[test]
    fn the_output_limit_drops_the_oldest_bytes_on_a_character_boundary() {
        let terminal = terminal_for_test(8);
        // Five two-byte characters, ten bytes, against a limit of eight.
        terminal.append("ααααα");
        let snapshot = terminal.snapshot();
        assert!(snapshot.truncated, "should have reported truncation");
        assert_eq!(snapshot.output, "αααα");
        // The point of the boundary rule: what is kept is still text.
        assert!(snapshot.output.is_char_boundary(snapshot.output.len()));
    }

    #[test]
    fn a_snapshot_at_the_current_revision_carries_no_output() {
        let terminal = terminal_for_test(1024);
        terminal.append("hello");
        let revision = terminal.revision();
        let value = snapshot_json(&terminal, revision);
        assert!(value.get("output").is_none(), "unchanged should be cheap");
        let value = snapshot_json(&terminal, revision - 1);
        assert_eq!(value["output"], "hello");
        assert_eq!(value["running"], true);
    }

    /// Zero is reserved for "the engine does not have this terminal", so a
    /// live one that has printed nothing yet must not answer with it — the
    /// card reads zero as released and stops polling.
    /// Releasing frees the process, not the record. Agents release as soon as
    /// they have read what they wanted, so a card that emptied itself here
    /// would be a card nobody could ever read.
    #[cfg(unix)]
    #[test]
    fn a_released_terminal_keeps_what_it_printed() {
        let dir = tempfile::tempdir().unwrap();
        let userland = crate::guest::testing::fake_userland(dir.path());
        let root = std::fs::canonicalize(dir.path()).unwrap();
        let terminals = Terminals::default();

        let request = acp::CreateTerminalRequest::new(acp::SessionId::new("s"), "/bin/sh")
            .args(vec!["-c".to_owned(), "echo kept".to_owned()]);
        let terminal = terminals.create(&userland, 1, &root, &request).unwrap();
        let id = terminal.id.clone();
        let deadline = Instant::now() + Duration::from_secs(20);
        while terminal.snapshot().exit.is_none() && Instant::now() < deadline {
            thread::sleep(Duration::from_millis(20));
        }

        assert!(terminals.release(&id));
        let kept = terminals.get(&id).expect("still readable after release");
        assert!(kept.snapshot().output.contains("kept"));
        assert_eq!(snapshot_json(&kept, 0)["running"], false);

        // The session going away does take them, though: the thread that
        // showed those cards is gone with it.
        terminals.release_session(1);
        assert!(terminals.get(&id).is_none());
    }

    #[test]
    fn a_terminal_that_has_printed_nothing_is_still_not_gone() {
        let terminal = terminal_for_test(1024);
        assert_eq!(terminal.revision(), 1);
        assert_eq!(snapshot_json(&terminal, 0)["revision"], 1);
    }

    /// The whole thing over a real process: both pipes, the exit code, and
    /// the cwd guard.
    #[cfg(unix)]
    #[test]
    fn a_command_runs_and_reports_both_pipes_and_its_exit_code() {
        let dir = tempfile::tempdir().unwrap();
        let userland = crate::guest::testing::fake_userland(dir.path());
        let root = std::fs::canonicalize(dir.path()).unwrap();
        let terminals = Terminals::default();

        let request =
            acp::CreateTerminalRequest::new(acp::SessionId::new("s"), "/bin/sh").args(vec![
                "-c".to_owned(),
                "echo out; echo err >&2; exit 3".to_owned(),
            ]);
        let terminal = terminals.create(&userland, 1, &root, &request).unwrap();

        let deadline = Instant::now() + Duration::from_secs(20);
        while terminal.snapshot().exit.is_none() && Instant::now() < deadline {
            thread::sleep(Duration::from_millis(20));
        }
        let snapshot = terminal.snapshot();
        assert_eq!(
            snapshot.exit.and_then(|exit| exit.exit_code),
            Some(3),
            "the command's own exit code, not the shell's"
        );
        assert!(
            snapshot.output.contains("out"),
            "stdout: {:?}",
            snapshot.output
        );
        assert!(
            snapshot.output.contains("err"),
            "stderr is interleaved into the same buffer: {:?}",
            snapshot.output
        );
        assert!(!snapshot.truncated);
    }

    /// A working directory is a capability: an agent must not run a command
    /// somewhere the project is not.
    #[cfg(unix)]
    #[test]
    fn a_working_directory_outside_the_project_is_refused() {
        let dir = tempfile::tempdir().unwrap();
        let userland = crate::guest::testing::fake_userland(dir.path());
        let root = std::fs::canonicalize(dir.path()).unwrap().join("project");
        std::fs::create_dir(&root).unwrap();
        let terminals = Terminals::default();

        let request = acp::CreateTerminalRequest::new(acp::SessionId::new("s"), "/bin/sh")
            .args(vec!["-c".to_owned(), "pwd".to_owned()])
            .cwd(root.parent().unwrap().to_path_buf());
        assert!(terminals.create(&userland, 1, &root, &request).is_err());
    }

    #[test]
    fn an_exit_moves_the_revision_and_stops_reporting_running() {
        let terminal = terminal_for_test(1024);
        let before = terminal.revision();
        terminal.finish(acp::TerminalExitStatus::new().exit_code(0u32));
        assert!(terminal.revision() > before);
        let value = snapshot_json(&terminal, before);
        assert_eq!(value["running"], false);
        assert_eq!(value["exitStatus"]["exitCode"], 0);
    }
}
