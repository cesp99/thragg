//! Credentials for the git commands that talk to a network: the askpass
//! helper, the prompts it forwards, and the session memory that answers the
//! ones it already knows.
//!
//! git and ssh both have a hook for a program that is not a terminal: name
//! an executable in `GIT_ASKPASS` / `SSH_ASKPASS` and they run it with the
//! prompt as its one argument, reading the answer from its stdout — a
//! non-zero exit is a refusal. Zed's `crates/askpass` fills that slot with a
//! generated shell script that pipes the prompt into the Zed binary itself,
//! running in netcat mode against a unix socket the app listens on
//! (`askpass.rs:262-330`, `generate_askpass_script`). The vendored copy at
//! `core/vendor/askpass` cannot be used here: the script would need a
//! program *inside the guest* that can speak to a socket, and the only
//! binary of ours the guest could reach is the engine, a host `.so` Debian's
//! loader would refuse. What does exist in every rootfs is `sh`, `mkfifo`
//! and `cat`, so the wire is two named pipes and the protocol is the
//! shell's own redirections — see [`SCRIPT`].
//!
//! The helper directory lives under the rootfs's own `/tmp`
//! ([`AskpassServer::start`]): the one place every proot we spawn already
//! sees at the same path, whether it is the engine's short-lived instance
//! (`guest.rs`) or the terminal backend's that `GitClone.kt` clones through
//! — a bind would have to be added to both, and a pipe the guest cannot
//! reach is a git that hangs on the deadline.
//!
//! The UI side is polled, like every other engine state: a prompt sits in
//! [`AskpassServer::pending`] until Kotlin asks for it
//! ([`crate::Engine::git_pending_prompt`]) and answers or cancels it. The
//! prompt's *kind* — username, password, passphrase, host-key confirmation —
//! is decided here ([`classify`]) so the dialog masks what Zed's modal
//! masks (`askpass_modal.rs:40-46`: anything that is not a "yes/no" or a
//! "Username" prompt) and the memory knows what it is remembering.
//!
//! What is remembered, and where: the username per remote host for the
//! app session, always; a password or passphrase only when the user ticks
//! "Remember for this session". Both live in this process's memory and
//! nowhere else — never on disk, never in a log line (the prompt text is
//! logged at debug level; an answer never is) — and a secret is zeroed when
//! forgotten. There is a guard against remembering wrongly: an answer the
//! memory gave is not given twice in a row for the same prompt, because git
//! asking the same question again right after an automatic answer means
//! the answer was refused, and a wrong username remembered for a session
//! would otherwise be a session with no way to push.

use std::collections::HashMap;
use std::ffi::OsString;
use std::fs::{File, OpenOptions};
use std::io::{Read, Write};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use zeroize::Zeroizing;

/// The helper git and ssh run, as a POSIX shell script.
///
/// Its one argument is the prompt. It makes itself a reply pipe named after
/// its own pid — real host pids under proot, so unique across every guest
/// we run — then writes one record, `<pid>\n<prompt>\0`, to the request
/// pipe the engine holds open. The prompt may span lines (ssh's host-key
/// question does), which is why the record ends on a NUL: the one byte an
/// argv string cannot contain. A record is far below `PIPE_BUF`, so two
/// helpers writing at once — a clone and a push — cannot interleave.
///
/// Then it reads the reply pipe to EOF. The engine writes `A<answer>` for
/// an answer or `C` for a cancel and closes; the answer travels through the
/// pipe and nothing else, so no secret touches the disk. `$(...)` drops a
/// trailing newline, which no answer wants anyway. A refusal is exit 1:
/// git falls back to its terminal prompt, which `GIT_TERMINAL_PROMPT=0` has
/// already closed, and fails with its own sentence; ssh gives up on the key
/// or the host.
///
/// `cd` to the directory first: `$(dirname "$0")` is what the guest resolved
/// `GIT_ASKPASS` to, and everything is addressed relative to it so the script
/// never has to know where it lives.
const SCRIPT: &str = r#"#!/bin/sh
# Seeker IDE's askpass helper. Generated; do not edit.
cd "$(dirname "$0")" || exit 1
reply="reply.$$"
mkfifo "$reply" || exit 1
trap 'rm -f "$reply"' EXIT
printf '%s\n%s\0' "$$" "$1" > request
answer=$(cat "$reply")
case "$answer" in
    A*) printf '%s\n' "${answer#A}" ;;
    *) exit 1 ;;
esac
"#;

/// What every helper directory under the rootfs's `/tmp` is named after. The
/// full name is `seeker-askpass-<pid>-<n>`: the pid so a launch never reads
/// a dead launch's pipe, and so [`sweep`] knows which ones are dead; the
/// counter so two servers of *this* process never collide (see [`INSTANCE`]).
const DIR_PREFIX: &str = "seeker-askpass-";

/// Numbers the servers this process starts, so each gets a directory of its
/// own: `seeker-askpass-<pid>-<n>`. The pid alone is not enough — the
/// platform layer configures the userland more than once per process (its
/// installer state is re-observed on every recomposition), and with one
/// directory per pid the *old* server's [`Drop`] removed the *new* server's
/// script from under it, leaving `GIT_ASKPASS` pointing at nothing.
static INSTANCE: AtomicU64 = AtomicU64::new(0);

/// The prefix every directory this process makes shares: the pid and a dash,
/// so pid 12's directories are never mistaken for pid 1's.
fn own_prefix() -> String {
    format!("{DIR_PREFIX}{}-", std::process::id())
}

/// How long an answer keeps trying to reach a helper that has not yet opened
/// its reply pipe. A helper is at `cat "$reply"` a few microseconds after
/// writing its request, so a memory answer arriving before it gets there is
/// the only real case; two seconds is many orders of magnitude past it.
const REPLY_OPEN_PATIENCE: Duration = Duration::from_secs(2);

/// How long after an automatic answer a repeat of the same prompt is taken
/// as "that answer was refused" rather than as a new operation asking.
const REPEAT_WINDOW: Duration = Duration::from_secs(120);

/// What a prompt is asking for — the dialog masks on it and the memory keys
/// on it. Zed's modal makes two of these distinctions (`askpass_modal.rs:40`:
/// "yes/no" and "Username" are shown, everything else masked); the third
/// and fourth exist so the dialog can say "passphrase" where ssh did and so
/// a remembered token is never offered as a key's passphrase.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum PromptKind {
    /// `Username for 'https://github.com': `
    Username,
    /// `Password for 'https://user@github.com': `
    Password,
    /// `Enter passphrase for key '/root/.ssh/id_ed25519': `
    Passphrase,
    /// ssh's `Are you sure you want to continue connecting (yes/no/[fingerprint])?`
    HostKey,
    /// Anything else — a smart-card PIN, a 2FA question. Masked, like Zed.
    Other,
}

impl PromptKind {
    /// Whether the dialog hides what is typed — Zed's `set_masked` rule.
    pub fn masked(self) -> bool {
        !matches!(self, PromptKind::Username | PromptKind::HostKey)
    }
}

/// A prompt as the UI sees it: what to show, how to mask it, and the id to
/// answer it by.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct PendingPrompt {
    pub id: u64,
    pub prompt: String,
    pub kind: PromptKind,
    /// What the prompt is about — the host, the key file — for the memory
    /// and for the dialog's wording. Empty when the prompt names nothing.
    pub subject: String,
    /// Whether the dialog masks the input.
    pub masked: bool,
    /// A remembered username to pre-fill, when a username prompt is being
    /// shown *despite* the memory — because the last automatic answer was
    /// refused and the user should see, and be able to correct, what it was.
    pub suggestion: Option<String>,
}

/// One helper waiting on its reply pipe.
struct Waiting {
    id: u64,
    /// The helper's pid — the reply pipe's name, and a liveness check.
    pid: u32,
    prompt: PendingPrompt,
}

/// The session's memory, and the guard against trusting it twice.
#[derive(Default)]
struct Memory {
    /// Remote host → username. Kept for the whole session, no opt-in: a
    /// username is not a secret, and typing it before every token is the
    /// friction this exists to remove.
    usernames: HashMap<String, String>,
    /// (kind, subject) → the secret, only with "Remember for this session".
    /// Zeroed on drop and on forget.
    secrets: HashMap<(PromptKind, String), Zeroizing<String>>,
    /// The last automatic answer per (kind, subject), so a repeat inside
    /// [`REPEAT_WINDOW`] is shown to the user instead of answered again.
    auto_answered: HashMap<(PromptKind, String), Instant>,
}

struct Shared {
    waiting: Mutex<Vec<Waiting>>,
    memory: Mutex<Memory>,
    /// Signalled when a prompt arrives, for a caller that would rather wait
    /// than poll — the tests.
    arrived: Condvar,
    dir: PathBuf,
    next_id: AtomicU64,
}

/// The listening half: owns the helper directory, reads the request pipe on
/// its own thread, and hands prompts to whoever asks.
///
/// One per userland, made in [`crate::Engine::set_userland`]; the git
/// commands that may need it reach it through [`crate::Engine::askpass`].
pub(crate) struct AskpassServer {
    shared: Arc<Shared>,
    /// The script's path *as the guest sees it* — under the guest's `/tmp`.
    guest_script: String,
    /// Our own write end of the request pipe. Holding it open is what keeps
    /// the reader from seeing EOF between helpers; it is also how [`Drop`]
    /// tells the reader to stop.
    request_writer: Mutex<File>,
}

impl AskpassServer {
    /// Create the helper directory under `<rootfs>/tmp`, write the script
    /// and the request pipe, and start reading. `None` when the rootfs has
    /// no writable `/tmp` — the caller runs git without a helper, as before.
    pub(crate) fn start(rootfs: &Path) -> Option<Self> {
        let tmp = rootfs.join("tmp");
        sweep(&tmp);
        let dir = tmp.join(Self::next_dir_name());
        Self::start_in(dir, "/tmp")
    }

    /// A directory name no server of this process has used before.
    fn next_dir_name() -> String {
        let n = INSTANCE.fetch_add(1, Ordering::Relaxed) + 1;
        format!("{}{n}", own_prefix())
    }

    /// [`Self::start`] with the directory and the path prefix the *helper*
    /// will see it under chosen by the caller: the tests run the script on
    /// the host, where the two are the same.
    pub(crate) fn start_in(dir: PathBuf, guest_tmp: &str) -> Option<Self> {
        let _ = std::fs::remove_dir_all(&dir);
        if let Err(err) = std::fs::create_dir_all(&dir) {
            log::warn!("askpass: cannot create {}: {err}", dir.display());
            return None;
        }
        // The rootfs is root-owned as the guest sees it and app-owned as we
        // do; 0700 is what a `/tmp` entry holding credentials should be.
        let _ = std::fs::set_permissions(&dir, std::fs::Permissions::from_mode(0o700));
        let script = dir.join("askpass.sh");
        if let Err(err) = std::fs::write(&script, SCRIPT) {
            log::warn!("askpass: cannot write {}: {err}", script.display());
            return None;
        }
        let _ = std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o700));
        let request = dir.join("request");
        if !mkfifo(&request) {
            log::warn!("askpass: cannot create the request pipe");
            return None;
        }
        // Read-write, so the pipe never reports EOF when the last helper
        // closes — the read loop would otherwise spin.
        let reader = OpenOptions::new().read(true).write(true).open(&request).ok()?;
        let writer = OpenOptions::new().write(true).open(&request).ok()?;

        let dir_name = dir.file_name()?.to_str()?.to_owned();
        let guest_script = format!("{guest_tmp}/{dir_name}/askpass.sh");
        let shared = Arc::new(Shared {
            waiting: Mutex::new(Vec::new()),
            memory: Mutex::new(Memory::default()),
            arrived: Condvar::new(),
            dir,
            next_id: AtomicU64::new(1),
        });
        let for_thread = shared.clone();
        thread::Builder::new()
            .name("askpass".to_owned())
            .spawn(move || read_requests(reader, for_thread))
            .ok()?;
        Some(Self {
            shared,
            guest_script,
            request_writer: Mutex::new(writer),
        })
    }

    /// The environment that routes every credential question through the
    /// helper — exactly the three Zed sets for a remote command
    /// (`repository.rs:4033-4036`) — on top of the `GIT_TERMINAL_PROMPT=0`
    /// every git already carries. `SSH_ASKPASS_REQUIRE=force` is what makes
    /// ssh use the helper with no `DISPLAY` and no tty: without it ssh would
    /// decide there is nobody to ask and fail the passphrase silently.
    pub(crate) fn environment(&self) -> Vec<(OsString, OsString)> {
        vec![
            ("GIT_ASKPASS".into(), self.guest_script.clone().into()),
            ("SSH_ASKPASS".into(), self.guest_script.clone().into()),
            ("SSH_ASKPASS_REQUIRE".into(), "force".into()),
        ]
    }

    /// The oldest prompt still waiting for an answer, or `None`.
    ///
    /// A helper whose git was killed — the deadline, a cancelled clone — is
    /// gone with it, and its prompt must not surface over the *next*
    /// operation: the pid is checked against `/proc` here, and a dead
    /// helper's prompt is dropped on the way out.
    pub(crate) fn pending(&self) -> Option<PendingPrompt> {
        let mut waiting = self.shared.waiting.lock().unwrap();
        waiting.retain(|entry| helper_alive(entry.pid));
        waiting.first().map(|entry| entry.prompt.clone())
    }

    /// Block until a prompt is pending or `timeout` passes. The tests' way
    /// of not sleeping in a loop; the UI polls [`Self::pending`].
    #[cfg(test)]
    pub(crate) fn wait_pending(&self, timeout: Duration) -> Option<PendingPrompt> {
        let deadline = Instant::now() + timeout;
        let mut waiting = self.shared.waiting.lock().unwrap();
        loop {
            if let Some(entry) = waiting.first() {
                return Some(entry.prompt.clone());
            }
            let now = Instant::now();
            if now >= deadline {
                return None;
            }
            let (guard, _) = self
                .shared
                .arrived
                .wait_timeout(waiting, deadline - now)
                .unwrap();
            waiting = guard;
        }
    }

    /// Answer prompt `id`, and — always for a username, only when
    /// `remember` for a secret — keep the answer for the session. `false`
    /// when there is no such prompt any more.
    pub(crate) fn answer(&self, id: u64, answer: &str, remember: bool) -> bool {
        let Some(entry) = self.take(id) else {
            return false;
        };
        let answer = Zeroizing::new(answer.trim_end_matches(['\r', '\n']).to_owned());
        {
            let mut memory = self.shared.memory.lock().unwrap();
            memory.learn(&entry.prompt, &answer, remember);
        }
        let mut reply = Zeroizing::new(String::with_capacity(answer.len() + 1));
        reply.push('A');
        reply.push_str(&answer);
        self.reply(entry.pid, reply.as_bytes())
    }

    /// Refuse prompt `id`: the helper exits 1 and git or ssh gives up.
    pub(crate) fn cancel(&self, id: u64) -> bool {
        let Some(entry) = self.take(id) else {
            return false;
        };
        // A refused prompt is a refused *memory* too, when the memory is
        // what put it there — the user looked at the suggestion and said no.
        self.shared.memory.lock().unwrap().forget(&entry.prompt);
        self.reply(entry.pid, b"C")
    }

    /// Forget every remembered username and secret.
    pub(crate) fn forget_all(&self) {
        *self.shared.memory.lock().unwrap() = Memory::default();
    }

    fn take(&self, id: u64) -> Option<Waiting> {
        let mut waiting = self.shared.waiting.lock().unwrap();
        let index = waiting.iter().position(|entry| entry.id == id)?;
        Some(waiting.remove(index))
    }

    fn reply(&self, pid: u32, bytes: &[u8]) -> bool {
        send_reply(&self.shared.dir, pid, bytes)
    }

    /// The helper's path as the guest sees it.
    #[cfg(test)]
    pub(crate) fn script_path(&self) -> &str {
        &self.guest_script
    }

    /// The script on the host — what [`crate::Engine::set_userland`] checks
    /// before deciding a running server can be kept.
    pub(crate) fn host_script(&self) -> PathBuf {
        self.shared.dir.join("askpass.sh")
    }

    /// Where the helper directory is on the host.
    #[cfg(test)]
    pub(crate) fn dir(&self) -> &Path {
        &self.shared.dir
    }
}

impl Drop for AskpassServer {
    fn drop(&mut self) {
        // An empty record — no pid, no prompt — is the reader's cue to stop.
        if let Ok(mut writer) = self.request_writer.lock() {
            let _ = writer.write_all(b"\0");
        }
        // Only this server's directory: another server of this process may
        // be live in the same `/tmp`, and its name is different by design.
        let _ = std::fs::remove_dir_all(&self.shared.dir);
    }
}

/// The reader thread: records off the request pipe, one prompt each.
fn read_requests(mut reader: File, shared: Arc<Shared>) {
    let mut buffer = Vec::new();
    let mut chunk = [0u8; 4096];
    loop {
        let read = match reader.read(&mut chunk) {
            Ok(0) => return,
            Ok(read) => read,
            Err(err) if err.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(_) => return,
        };
        buffer.extend_from_slice(&chunk[..read]);
        while let Some(end) = buffer.iter().position(|&byte| byte == 0) {
            let record: Vec<u8> = buffer.drain(..=end).collect();
            let record = String::from_utf8_lossy(&record[..end]);
            if record.is_empty() {
                // The drop sentinel.
                return;
            }
            let Some((pid, prompt)) = record.split_once('\n') else {
                continue;
            };
            let Ok(pid) = pid.trim().parse::<u32>() else {
                continue;
            };
            handle_request(&shared, pid, prompt);
        }
    }
}

/// One prompt in: answer it from memory, or queue it for the UI.
fn handle_request(shared: &Arc<Shared>, pid: u32, prompt: &str) {
    let (kind, subject) = classify(prompt);
    log::debug!("askpass: {kind:?} prompt from helper {pid}: {prompt:?}");
    let id = shared.next_id.fetch_add(1, Ordering::Relaxed);
    let mut pending = PendingPrompt {
        id,
        prompt: prompt.to_owned(),
        kind,
        subject: subject.clone(),
        masked: kind.masked(),
        suggestion: None,
    };
    let remembered = {
        let mut memory = shared.memory.lock().unwrap();
        memory.recall(kind, &subject)
    };
    match remembered {
        Recall::Answer(answer) => {
            let mut reply = Zeroizing::new(String::with_capacity(answer.len() + 1));
            reply.push('A');
            reply.push_str(&answer);
            send_reply(&shared.dir, pid, reply.as_bytes());
            return;
        }
        Recall::Suggest(username) => pending.suggestion = Some(username),
        Recall::Nothing => {}
    }
    let mut waiting = shared.waiting.lock().unwrap();
    waiting.push(Waiting {
        id,
        pid,
        prompt: pending,
    });
    shared.arrived.notify_all();
}

/// Write `bytes` to the helper's reply pipe and close it.
///
/// Opened non-blocking: a blocking `open` of a FIFO for writing waits for
/// a reader, and a helper that died — its git killed under it — would park
/// this thread forever. `ENXIO` means "no reader yet", which for a live
/// helper resolves within microseconds ([`REPLY_OPEN_PATIENCE`]).
fn send_reply(dir: &Path, pid: u32, bytes: &[u8]) -> bool {
    let path = dir.join(format!("reply.{pid}"));
    let deadline = Instant::now() + REPLY_OPEN_PATIENCE;
    loop {
        match OpenOptions::new()
            .write(true)
            .custom_flags(libc::O_NONBLOCK)
            .open(&path)
        {
            Ok(mut pipe) => {
                // The reader is there, so the pipe is not going to fill on
                // a one-line answer; back to blocking for the write.
                set_blocking(&pipe);
                return pipe.write_all(bytes).is_ok();
            }
            Err(err) if err.raw_os_error() == Some(libc::ENXIO) => {
                if Instant::now() >= deadline || !helper_alive(pid) {
                    log::debug!("askpass: helper {pid} never opened its reply pipe");
                    return false;
                }
                thread::sleep(Duration::from_millis(5));
            }
            Err(err) => {
                log::debug!("askpass: cannot open reply pipe for {pid}: {err}");
                return false;
            }
        }
    }
}

/// What the memory has to say about a prompt.
enum Recall {
    /// Answer it without asking.
    Answer(Zeroizing<String>),
    /// Ask, but pre-fill this username — the last automatic answer was
    /// refused.
    Suggest(String),
    Nothing,
}

impl Memory {
    fn recall(&mut self, kind: PromptKind, subject: &str) -> Recall {
        if subject.is_empty() {
            return Recall::Nothing;
        }
        let key = memory_key(kind, subject);
        let repeated = self
            .auto_answered
            .get(&key)
            .is_some_and(|at| at.elapsed() < REPEAT_WINDOW);
        match kind {
            PromptKind::Username => {
                let Some(username) = self.usernames.get(&key.1).cloned() else {
                    return Recall::Nothing;
                };
                if repeated {
                    // Asked again right after we answered: show it.
                    self.auto_answered.remove(&key);
                    return Recall::Suggest(username);
                }
                self.auto_answered.insert(key, Instant::now());
                Recall::Answer(Zeroizing::new(username))
            }
            PromptKind::Password | PromptKind::Passphrase => {
                if repeated {
                    // The remembered secret was refused; it is wrong, and
                    // a wrong secret kept is a session that cannot push.
                    self.auto_answered.remove(&key);
                    self.secrets.remove(&key);
                    return Recall::Nothing;
                }
                let Some(secret) = self.secrets.get(&key) else {
                    return Recall::Nothing;
                };
                self.auto_answered.insert(key, Instant::now());
                Recall::Answer(secret.clone())
            }
            PromptKind::HostKey | PromptKind::Other => Recall::Nothing,
        }
    }

    /// Keep what the user answered: the username always, a secret only on
    /// request. A host-key answer is never kept — ssh writes `known_hosts`
    /// itself, and "yes" to one fingerprint is not "yes" to the next.
    fn learn(&mut self, prompt: &PendingPrompt, answer: &str, remember: bool) {
        if prompt.subject.is_empty() || answer.is_empty() {
            return;
        }
        let key = memory_key(prompt.kind, &prompt.subject);
        // The user typed this one; the next automatic answer is a fresh
        // start, not a repeat.
        self.auto_answered.remove(&key);
        match prompt.kind {
            PromptKind::Username => {
                self.usernames.insert(key.1, answer.to_owned());
            }
            PromptKind::Password | PromptKind::Passphrase => {
                if remember {
                    self.secrets.insert(key, Zeroizing::new(answer.to_owned()));
                } else {
                    self.secrets.remove(&key);
                }
            }
            PromptKind::HostKey | PromptKind::Other => {}
        }
    }

    /// The user cancelled a prompt the memory pre-filled: drop the guess.
    fn forget(&mut self, prompt: &PendingPrompt) {
        let key = memory_key(prompt.kind, &prompt.subject);
        self.auto_answered.remove(&key);
        if prompt.kind == PromptKind::Username && prompt.suggestion.is_some() {
            self.usernames.remove(&key.1);
        }
        self.secrets.remove(&key);
    }
}

/// What a prompt is asking, and about what.
///
/// The kind follows the words git and ssh actually use — `Username for`,
/// `Password for`, `Enter passphrase for key`, `(yes/no` — checked in the
/// order that keeps them apart: the host-key question mentions no password,
/// but a "passphrase" prompt would match a looser "pass" check, and ssh's
/// `Enter PIN for` matches none, which is [`PromptKind::Other`]. The subject
/// is the first single-quoted span — git and ssh both quote the URL, host
/// or key path that way — or the `https://host` of a credential prompt
/// with no quotes.
pub(crate) fn classify(prompt: &str) -> (PromptKind, String) {
    let lower = prompt.to_ascii_lowercase();
    let kind = if lower.contains("yes/no") {
        PromptKind::HostKey
    } else if lower.contains("username") {
        PromptKind::Username
    } else if lower.contains("passphrase") {
        PromptKind::Passphrase
    } else if lower.contains("password") || lower.contains("token") {
        PromptKind::Password
    } else {
        PromptKind::Other
    };
    (kind, subject_of(prompt))
}

/// The first `'…'` span, trimmed; empty when there is none.
fn subject_of(prompt: &str) -> String {
    let Some(start) = prompt.find('\'') else {
        return String::new();
    };
    let rest = &prompt[start + 1..];
    let Some(end) = rest.find('\'') else {
        return String::new();
    };
    rest[..end].trim().to_owned()
}

/// What a prompt is remembered under. A username is the *host's*
/// (`Username for 'https://github.com'` and `… 'https://github.com/o/r'`
/// are one question), so its key — and, as importantly, the repeat guard's
/// — is the host; a secret is the exact URL or key file the prompt named,
/// because git spells the password prompt with the username in it and a
/// token for one account is not a token for another.
fn memory_key(kind: PromptKind, subject: &str) -> (PromptKind, String) {
    let subject = match kind {
        PromptKind::Username => host_of(subject),
        _ => subject.to_owned(),
    };
    (kind, subject)
}

/// The host a credential prompt's subject names — `https://github.com`,
/// `https://user@github.com:8443/path` and `github.com` all key the same
/// memory entry, because the username git asks about is the *host's*.
pub(crate) fn host_of(subject: &str) -> String {
    let rest = subject.split_once("://").map_or(subject, |(_, rest)| rest);
    let rest = rest.split(['/', ' ']).next().unwrap_or(rest);
    let rest = rest.rsplit_once('@').map_or(rest, |(_, host)| host);
    rest.to_ascii_lowercase()
}

/// Whether the helper that asked is still there to be answered. A pid is a
/// directory in `/proc` for exactly as long as its process lives; ours are
/// descendants of this process, which Android's `hidepid` never hides.
fn helper_alive(pid: u32) -> bool {
    Path::new("/proc").join(pid.to_string()).exists()
}

fn mkfifo(path: &Path) -> bool {
    let Ok(c_path) = std::ffi::CString::new(path.as_os_str().as_encoded_bytes()) else {
        return false;
    };
    // SAFETY: `c_path` is a valid NUL-terminated string for the call's
    // duration; `mkfifo` reads it and touches nothing else of ours.
    unsafe { libc::mkfifo(c_path.as_ptr(), 0o600) == 0 }
}

fn set_blocking(file: &File) {
    use std::os::unix::io::AsRawFd;
    let fd = file.as_raw_fd();
    // SAFETY: plain fcntl on a descriptor we own and keep open across both
    // calls.
    unsafe {
        let flags = libc::fcntl(fd, libc::F_GETFL);
        if flags >= 0 {
            libc::fcntl(fd, libc::F_SETFL, flags & !libc::O_NONBLOCK);
        }
    }
}

/// Remove the helper directories of launches that are over. A previous
/// launch's pipe has nobody reading it; a helper writing to it would block
/// until git's deadline — which cannot happen, since the environment names
/// this launch's directory, but the rootfs's `/tmp` should not grow either.
fn sweep(tmp: &Path) {
    let Ok(entries) = std::fs::read_dir(tmp) else {
        return;
    };
    // Every directory of this process is spared, not just the newest: a git
    // started before a re-configure may still be talking to an older one.
    let ours = own_prefix();
    for entry in entries.flatten() {
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if name.starts_with(DIR_PREFIX) && !name.starts_with(&ours) {
            let _ = std::fs::remove_dir_all(entry.path());
        }
    }
}

/// Where a Debian rootfs keeps the credential cache helper. Its presence is
/// the whole test: `git config credential.helper cache` on a git without it
/// prints a warning per prompt and helps nobody.
const CREDENTIAL_CACHE_HELPER: &str = "usr/lib/git-core/git-credential-cache";

/// `-c credential.helper=cache --timeout=3600`, for a rootfs whose git has
/// the cache helper — so a token typed for one push is handed to the next
/// by git's own daemon, the way it would be on a desktop. Empty otherwise.
/// Git-level options, so they go *before* the subcommand.
pub(crate) fn credential_cache_args(rootfs: &Path) -> Vec<OsString> {
    if !rootfs.join(CREDENTIAL_CACHE_HELPER).is_file() {
        return Vec::new();
    }
    vec![
        OsString::from("-c"),
        OsString::from("credential.helper=cache --timeout=3600"),
    ]
}

impl crate::Engine {
    /// The askpass server for the configured userland, if one is running.
    pub(crate) fn askpass(&self) -> Option<Arc<AskpassServer>> {
        self.askpass.lock().unwrap().clone()
    }

    /// What a git that may need credentials is run with: the askpass
    /// environment and the credential-cache options, as JSON for the
    /// platform layer's own clone — `{"env":["K=V",…],"args":[…]}`. Both
    /// empty when there is no userland, and the clone then runs as it always
    /// did: failing on a private remote rather than hanging.
    pub fn git_askpass_setup_json(&self) -> String {
        let env: Vec<String> = self
            .askpass()
            .map(|server| {
                server
                    .environment()
                    .into_iter()
                    .map(|(key, value)| {
                        format!("{}={}", key.to_string_lossy(), value.to_string_lossy())
                    })
                    .collect()
            })
            .unwrap_or_default();
        let args: Vec<String> = self
            .userland()
            .map(|userland| {
                credential_cache_args(userland.rootfs())
                    .into_iter()
                    .map(|arg| arg.to_string_lossy().into_owned())
                    .collect()
            })
            .unwrap_or_default();
        serde_json::json!({ "env": env, "args": args }).to_string()
    }

    /// The oldest credential prompt waiting for an answer, or `None`.
    pub fn git_pending_prompt(&self) -> Option<PendingPrompt> {
        self.askpass()?.pending()
    }

    /// Answer a prompt; see [`AskpassServer::answer`].
    pub fn git_answer_prompt(&self, id: u64, answer: &str, remember: bool) -> bool {
        self.askpass()
            .is_some_and(|server| server.answer(id, answer, remember))
    }

    /// Refuse a prompt; see [`AskpassServer::cancel`].
    pub fn git_cancel_prompt(&self, id: u64) -> bool {
        self.askpass().is_some_and(|server| server.cancel(id))
    }

    /// Drop every remembered username and secret.
    pub fn git_forget_credentials(&self) {
        if let Some(server) = self.askpass() {
            server.forget_all();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::process::Command;

    fn server() -> (tempfile::TempDir, AskpassServer) {
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path().join(AskpassServer::next_dir_name());
        let server = AskpassServer::start_in(dir, tmp.path().to_str().unwrap()).unwrap();
        (tmp, server)
    }

    /// Two servers of one process never share a directory, so dropping the
    /// older one — what replacing it in the engine does — leaves the newer
    /// one's script where `GIT_ASKPASS` says it is. And the sweep for dead
    /// launches spares every directory of this process, not only the newest.
    #[test]
    fn a_replaced_server_does_not_take_the_new_ones_script_with_it() {
        let rootfs = tempfile::tempdir().unwrap();
        let tmp = rootfs.path().join("tmp");
        std::fs::create_dir_all(&tmp).unwrap();
        // A dead launch's leftovers, which the sweep is for.
        let dead = tmp.join(format!("{DIR_PREFIX}1"));
        std::fs::create_dir_all(&dead).unwrap();

        let first = AskpassServer::start(rootfs.path()).unwrap();
        assert!(!dead.exists(), "a dead launch's directory is swept");
        let second = AskpassServer::start(rootfs.path()).unwrap();
        assert_ne!(first.dir(), second.dir());
        assert!(first.host_script().is_file(), "the older server survives the sweep");
        assert!(second.host_script().is_file());

        drop(first);
        assert!(second.host_script().is_file());
        assert!(second.dir().join("request").exists());
    }

    /// The kinds, from the sentences git and ssh really print.
    #[test]
    fn prompts_classify_by_their_own_words() {
        assert_eq!(
            classify("Username for 'https://github.com': "),
            (PromptKind::Username, "https://github.com".to_owned())
        );
        assert_eq!(
            classify("Password for 'https://cesp99@github.com': "),
            (PromptKind::Password, "https://cesp99@github.com".to_owned())
        );
        assert_eq!(
            classify("Enter passphrase for key '/root/.ssh/id_ed25519': "),
            (PromptKind::Passphrase, "/root/.ssh/id_ed25519".to_owned())
        );
        let host_key = "The authenticity of host 'github.com (140.82.121.4)' can't be established.\n\
ED25519 key fingerprint is SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU.\n\
This key is not known by any other names.\n\
Are you sure you want to continue connecting (yes/no/[fingerprint])? ";
        assert_eq!(
            classify(host_key),
            (PromptKind::HostKey, "github.com (140.82.121.4)".to_owned())
        );
        // A prompt that names nothing has no subject and nothing to remember.
        assert_eq!(classify("Enter PIN for 'PIV Card': "), (PromptKind::Other, "PIV Card".to_owned()));
        assert_eq!(classify("Token: "), (PromptKind::Password, String::new()));
        // Masking follows Zed's rule: only "yes/no" and "Username" show.
        assert!(!PromptKind::Username.masked());
        assert!(!PromptKind::HostKey.masked());
        assert!(PromptKind::Password.masked());
        assert!(PromptKind::Passphrase.masked());
        assert!(PromptKind::Other.masked());
    }

    /// One memory entry per host, however git spells the URL.
    #[test]
    fn the_username_memory_keys_on_the_host() {
        assert_eq!(host_of("https://github.com"), "github.com");
        assert_eq!(host_of("https://cesp99@github.com"), "github.com");
        assert_eq!(host_of("https://GitHub.com:8443/owner/repo.git"), "github.com:8443");
        assert_eq!(host_of("github.com"), "github.com");
        assert_eq!(host_of("github.com (140.82.121.4)"), "github.com");
    }

    /// The environment names the script three times over, as Zed's does.
    #[test]
    fn the_environment_routes_git_and_ssh_through_the_script() {
        let (_tmp, server) = server();
        let env = server.environment();
        let get = |key: &str| {
            env.iter()
                .find(|(k, _)| k == key)
                .map(|(_, v)| v.to_string_lossy().into_owned())
        };
        assert_eq!(get("GIT_ASKPASS").as_deref(), Some(server.script_path()));
        assert_eq!(get("SSH_ASKPASS").as_deref(), Some(server.script_path()));
        assert_eq!(get("SSH_ASKPASS_REQUIRE").as_deref(), Some("force"));
        assert!(server.script_path().ends_with("/askpass.sh"));
    }

    /// A stand-in for git: a shell script that asks `$GIT_ASKPASS` the
    /// questions a private HTTPS remote asks and prints the answers, so the
    /// whole round trip — pipe, script, reader thread, answer, reply pipe —
    /// runs on the host exactly as it does in the guest.
    fn fake_git(server: &AskpassServer, script: &str) -> std::process::Child {
        let mut command = Command::new("sh");
        command.arg("-c").arg(script);
        for (key, value) in server.environment() {
            command.env(key, value);
        }
        command
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .spawn()
            .expect("sh runs")
    }

    fn finish(child: std::process::Child) -> (i32, String) {
        let output = child.wait_with_output().unwrap();
        (
            output.status.code().unwrap_or(-1),
            String::from_utf8_lossy(&output.stdout).into_owned(),
        )
    }

    /// The round trip: the prompt arrives classified, the answer reaches
    /// git's stdout, and nothing of the exchange is left on disk.
    #[test]
    fn a_prompt_travels_to_the_ui_and_the_answer_back() {
        let (_tmp, server) = server();
        let child = fake_git(
            &server,
            r#"u=$("$GIT_ASKPASS" "Username for 'https://example.com': ") || exit 3
p=$("$GIT_ASKPASS" "Password for 'https://$u@example.com': ") || exit 4
printf 'user=%s pass=%s\n' "$u" "$p""#,
        );
        let prompt = server.wait_pending(Duration::from_secs(5)).expect("username asked");
        assert_eq!(prompt.kind, PromptKind::Username);
        assert!(!prompt.masked);
        assert_eq!(prompt.subject, "https://example.com");
        assert_eq!(server.pending().map(|p| p.id), Some(prompt.id));
        assert!(server.answer(prompt.id, "alice", false));

        let prompt = server.wait_pending(Duration::from_secs(5)).expect("password asked");
        assert_eq!(prompt.kind, PromptKind::Password);
        assert!(prompt.masked);
        assert_eq!(prompt.prompt, "Password for 'https://alice@example.com': ");
        assert!(server.answer(prompt.id, "s3cret", false));

        let (status, stdout) = finish(child);
        assert_eq!(status, 0);
        assert_eq!(stdout, "user=alice pass=s3cret\n");
        assert!(server.pending().is_none());
        // The reply pipes are gone; only the script and the request pipe stay.
        let mut names: Vec<String> = std::fs::read_dir(server.dir())
            .unwrap()
            .map(|entry| entry.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        names.sort();
        assert_eq!(names, ["askpass.sh", "request"]);
    }

    /// Cancel is a non-zero exit from the helper — what makes git say
    /// "terminal prompts disabled" and stop.
    #[test]
    fn a_cancelled_prompt_fails_the_helper() {
        let (_tmp, server) = server();
        let child = fake_git(
            &server,
            r#"if "$GIT_ASKPASS" "Password for 'https://example.com': "; then echo answered; else echo refused; fi"#,
        );
        let prompt = server.wait_pending(Duration::from_secs(5)).unwrap();
        assert!(server.cancel(prompt.id));
        let (status, stdout) = finish(child);
        assert_eq!(status, 0);
        assert_eq!(stdout, "refused\n");
        // Answering a prompt that is gone is a no.
        assert!(!server.answer(prompt.id, "x", false));
    }

    /// The second push asks only for the token: the username came back from
    /// memory without a prompt. Then the same question again right away is
    /// shown — with the remembered name pre-filled — because git repeating
    /// itself means it was refused.
    #[test]
    fn the_username_is_remembered_per_host_and_not_trusted_twice_in_a_row() {
        let (_tmp, server) = server();
        let child = fake_git(&server, r#""$GIT_ASKPASS" "Username for 'https://example.com': ""#);
        let prompt = server.wait_pending(Duration::from_secs(5)).unwrap();
        assert!(server.answer(prompt.id, "alice", false));
        assert_eq!(finish(child).1, "alice\n");

        // Same host, a different spelling of the URL: answered by memory.
        let child = fake_git(
            &server,
            r#""$GIT_ASKPASS" "Username for 'https://EXAMPLE.com/owner/repo': ""#,
        );
        assert_eq!(finish(child).1, "alice\n");
        assert!(server.pending().is_none());

        // Asked again straight after: refused, so the user sees it.
        let child = fake_git(&server, r#""$GIT_ASKPASS" "Username for 'https://example.com': ""#);
        let prompt = server.wait_pending(Duration::from_secs(5)).unwrap();
        assert_eq!(prompt.suggestion.as_deref(), Some("alice"));
        assert!(server.answer(prompt.id, "bob", false));
        assert_eq!(finish(child).1, "bob\n");

        // And the correction is what the memory holds now.
        let child = fake_git(&server, r#""$GIT_ASKPASS" "Username for 'https://example.com': ""#);
        assert_eq!(finish(child).1, "bob\n");

        // Another host knows nothing.
        let child = fake_git(&server, r#""$GIT_ASKPASS" "Username for 'https://other.test': ""#);
        let prompt = server.wait_pending(Duration::from_secs(5)).unwrap();
        assert!(prompt.suggestion.is_none());
        server.cancel(prompt.id);
        finish(child);
    }

    /// A secret is kept only on request, answered from memory once, and
    /// dropped the moment git asks for it again.
    #[test]
    fn a_secret_is_remembered_only_on_request() {
        let (_tmp, server) = server();
        let ask = r#""$GIT_ASKPASS" "Password for 'https://alice@example.com': ""#;

        // Not remembered: asked again.
        let child = fake_git(&server, ask);
        let prompt = server.wait_pending(Duration::from_secs(5)).unwrap();
        assert!(server.answer(prompt.id, "one", false));
        assert_eq!(finish(child).1, "one\n");
        let child = fake_git(&server, ask);
        let prompt = server.wait_pending(Duration::from_secs(5)).unwrap();
        // Remembered this time.
        assert!(server.answer(prompt.id, "two", true));
        assert_eq!(finish(child).1, "two\n");

        // From memory, silently.
        let child = fake_git(&server, ask);
        assert_eq!(finish(child).1, "two\n");
        assert!(server.pending().is_none());

        // Refused — asked again — and the memory lets go of it.
        let child = fake_git(&server, ask);
        let prompt = server.wait_pending(Duration::from_secs(5)).unwrap();
        assert!(prompt.suggestion.is_none());
        assert!(server.answer(prompt.id, "three", false));
        assert_eq!(finish(child).1, "three\n");
        let child = fake_git(&server, ask);
        let prompt = server.wait_pending(Duration::from_secs(5)).unwrap();
        server.cancel(prompt.id);
        finish(child);

        // Forgetting everything forgets the username too.
        server.forget_all();
        let child = fake_git(&server, r#""$GIT_ASKPASS" "Username for 'https://example.com': ""#);
        let prompt = server.wait_pending(Duration::from_secs(5)).unwrap();
        assert!(prompt.suggestion.is_none());
        server.cancel(prompt.id);
        finish(child);
    }

    /// ssh's host-key question spans lines and ends with the yes/no; it goes
    /// through whole, and a "yes" comes back on one line.
    #[test]
    fn a_host_key_question_arrives_whole() {
        let (_tmp, server) = server();
        let child = fake_git(
            &server,
            r#""$SSH_ASKPASS" "The authenticity of host 'example.com (1.2.3.4)' can't be established.
ED25519 key fingerprint is SHA256:abc.
Are you sure you want to continue connecting (yes/no/[fingerprint])? ""#,
        );
        let prompt = server.wait_pending(Duration::from_secs(5)).unwrap();
        assert_eq!(prompt.kind, PromptKind::HostKey);
        assert!(prompt.prompt.contains("fingerprint is SHA256:abc"));
        assert!(server.answer(prompt.id, "yes", true));
        assert_eq!(finish(child).1, "yes\n");
        // Not remembered, whatever the checkbox said.
        let child = fake_git(
            &server,
            r#""$SSH_ASKPASS" "The authenticity of host 'example.com (1.2.3.4)' can't be established.
Are you sure you want to continue connecting (yes/no/[fingerprint])? ""#,
        );
        let prompt = server.wait_pending(Duration::from_secs(5)).unwrap();
        server.cancel(prompt.id);
        assert_eq!(finish(child).0, 1);
    }

    /// A helper whose git died — the deadline, a cancelled clone — must not
    /// leave its question on the next operation's screen.
    #[test]
    fn a_dead_helpers_prompt_is_dropped() {
        let (_tmp, server) = server();
        let mut child = fake_git(&server, r#""$GIT_ASKPASS" "Password for 'https://example.com': ""#);
        let prompt = server.wait_pending(Duration::from_secs(5)).unwrap();
        // `sh -c` with a single command execs it, so killing the child kills
        // the helper itself.
        child.kill().unwrap();
        let _ = child.wait();
        // The pid is gone; so is the prompt.
        let deadline = Instant::now() + Duration::from_secs(5);
        while server.pending().is_some() && Instant::now() < deadline {
            thread::sleep(Duration::from_millis(20));
        }
        assert!(server.pending().is_none());
        assert!(!server.answer(prompt.id, "late", false));
        // The killed shell's `cat` is still parked on the reply pipe; an
        // EOF from a writer that opens and closes lets it go, so the test
        // leaves no process behind.
        for entry in std::fs::read_dir(server.dir()).unwrap().flatten() {
            if entry.file_name().to_string_lossy().starts_with("reply.") {
                let _ = OpenOptions::new()
                    .write(true)
                    .custom_flags(libc::O_NONBLOCK)
                    .open(entry.path());
            }
        }
    }

    /// The credential cache is opted into only where the helper exists.
    #[test]
    fn the_credential_cache_is_used_only_where_git_has_it() {
        let tmp = tempfile::tempdir().unwrap();
        assert!(credential_cache_args(tmp.path()).is_empty());
        let helper = tmp.path().join(CREDENTIAL_CACHE_HELPER);
        std::fs::create_dir_all(helper.parent().unwrap()).unwrap();
        std::fs::write(&helper, "").unwrap();
        let args: Vec<String> = credential_cache_args(tmp.path())
            .into_iter()
            .map(|arg| arg.into_string().unwrap())
            .collect();
        assert_eq!(args, ["-c", "credential.helper=cache --timeout=3600"]);
    }

    /// Dropping the server takes the directory — the script, the pipe — with
    /// it, and stops the reader.
    #[test]
    fn dropping_the_server_removes_its_directory() {
        let (_tmp, server) = server();
        let dir = server.dir().to_path_buf();
        assert!(dir.join("askpass.sh").is_file());
        drop(server);
        assert!(!dir.exists());
    }
}
