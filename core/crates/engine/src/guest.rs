//! Running a program inside the Debian userland.
//!
//! Everything the engine executes outside its own process goes through here.
//! Android will not run anything that arrived after install, so the only
//! binaries we can reach are the ones `apt` put in the rootfs, and the only
//! way to reach them is proot (agent-docs/archive/research/proot-spike.md, "Open
//! items", item 4). The flag block below is fiddly enough — and its failures
//! quiet enough — that a second copy of it would mean a second behaviour, so
//! `git status` (git.rs) and, from P5-1, the language servers share this one.
//!
//! Three things shape this module.
//!
//! **Identity binds.** proot is told `-b <dir>:<dir>`, mapping a host path
//! onto the *same* guest path, rather than the terminal's `-b
//! <projects>:/projects`. The terminal remaps because a human wants a short
//! prompt; the engine must not, because every path that crosses this boundary
//! — an argument going in, a path coming back in a diagnostic — would
//! otherwise need translating in both directions, and one forgotten
//! translation is a whole class of bug. With an identity bind there is nothing
//! to translate.
//!
//! **There are three ways out of here, and they share one command line.**
//! [`capture`] runs a program to completion under a deadline and hands back its
//! stdout: it is what a query wants, and nothing is delivered until the program
//! exits. [`spawn`] leaves the program running with all three pipes open and
//! gives the caller the handle. [`invocation`] hands over the command line
//! *itself* — program, argv, environment — for a caller that does its own
//! spawning, which is how language servers go out: Zed's `LanguageServer::new`
//! spawns them, so proot is the binary it is given (see lsp.rs). All three are
//! built from the same [`invocation`], which is the point: a dropped flag can
//! only be dropped once.
//!
//! **This is not the terminal's proot command line, on purpose.** Kotlin's
//! `DebianUserland.inside` builds its own and differs in three ways, each of
//! which is right there and wrong here: it remaps the projects directory to
//! `/projects` (see above), it sets `TERM`, `COLORTERM` and `PS1` because a
//! human is reading the output, and it rewrites `/etc/resolv.conf` every
//! session because `apt` needs a resolver matching the current network, which
//! a status query and an LSP pipe never touch. What the two must keep in step
//! is the part that describes the *rootfs* rather than the caller — `-0`,
//! `--link2symlink`, `--kill-on-exit`, `-k` — because both are looking at the
//! same unpacked Debian.

use std::ffi::{OsStr, OsString};
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStderr, ChildStdin, ChildStdout, Command, Stdio};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::thread;
use std::time::{Duration, Instant};

/// How often a supervising loop checks on a child.
const POLL_INTERVAL: Duration = Duration::from_millis(20);

/// The `PATH` every guest run starts with. Named because a toolchain prepends
/// to it rather than replacing it (see `toolchain.rs`), and the two must be
/// the same string.
pub(crate) const GUEST_PATH: &str =
    "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

/// How long proot gets to take its tracees down after SIGQUIT, before we
/// resort to SIGKILL. The same grace the Kotlin side gives it.
const QUIT_GRACE: Duration = Duration::from_secs(3);

/// How many processes Android counts against us for one guest run.
///
/// proot is not a wrapper that execs and disappears: it stays, as the tracer,
/// and the program runs as its tracee. Both are our descendants and both are
/// counted, so **every** way into the guest — [`capture`], [`spawn`], and the
/// [`invocation`] a language server is started from — costs two, before the
/// guest program forks anything of its own.
pub(crate) const PROCESSES_PER_RUN: usize = 2;

/// Android's cap on an app's background processes, on the devices that enforce
/// it: 32 (Android 12+'s phantom-process limit, `settings global
/// max_phantom_processes`; agent-docs/archive/research/proot-spike.md, "Open items",
/// item 3). Some OEMs disable it and some raise it; nothing may *rely* on that,
/// so 32 is the number we budget against.
///
/// It is one budget for the whole app, shared by everything that leaves our
/// address space: the terminal's shells, `git status` and clone, `apt install`,
/// and the language servers. Who reserves what is written down beside the
/// language-server cap in lsp.rs, which is the only consumer that runs
/// long-lived children in an unbounded number.
pub(crate) const PROCESS_BUDGET: usize = 32;

/// Processes the running ACP agents have reserved out of [`PROCESS_BUDGET`]:
/// [`crate::acp::PROCESSES_PER_AGENT`] for each live agent connection. It
/// lives here, beside the constants it spends, because this is the module that
/// knows what entering the guest costs; `acp.rs` adds and subtracts it and the
/// language-server cap in `lsp.rs` subtracts it from the budget — the revisit
/// the P5-4 decision scheduled for "once the ACP agent panel starts spending
/// the same budget".
///
/// **A counter, not a flag, and this is the whole reason.** Only one agent is
/// meant to run at a time, but replacing one overlaps the two: the new agent
/// starts while the old one's watcher is still inside proot's SIGQUIT grace.
/// With a `store`, the departing watcher wrote 0 over the arriving agent's
/// reservation and the language-server cap silently went back to 4 — the exact
/// over-subscription the reservation exists to prevent. Adding and subtracting
/// makes the overlap read as *more* reserved rather than less, which is the
/// safe direction to be wrong in.
pub(crate) static RESERVED_FOR_AGENT: AtomicUsize = AtomicUsize::new(0);

/// Where the guest lives. The engine never guesses any of this: Kotlin knows
/// the flavour, the install state and the paths, and hands them over through
/// [`Engine::set_userland`](crate::Engine::set_userland).
#[derive(Debug)]
pub(crate) struct Userland {
    /// The proot executable, in `nativeLibraryDir`.
    proot: PathBuf,
    /// The unpacked Debian rootfs.
    rootfs: PathBuf,
    /// `PROOT_TMP_DIR`; proot's compiled-in default points into Termux's
    /// private storage, which we cannot write.
    tmp_dir: PathBuf,
    /// The projects directory, bound onto itself so that every project inside
    /// it is visible at its real path.
    projects_dir: PathBuf,
}

impl Userland {
    /// Whether the guest is actually on disk.
    ///
    /// Being configured is not the same as being present — the user can remove
    /// the rootfs while the engine is still holding its paths — and callers
    /// here answer "nothing to do" rather than raising an error, so the cheap
    /// check has to happen before the doomed spawn rather than after it.
    /// The unpacked rootfs — where the askpass helper finds a `/tmp` every
    /// proot sees, and where the credential-cache helper is looked for.
    pub(crate) fn rootfs(&self) -> &Path {
        &self.rootfs
    }

    pub(crate) fn is_installed(&self) -> bool {
        if !self.proot.is_file() {
            log::debug!("no proot at {}", self.proot.display());
            return false;
        }
        if !self.rootfs.is_dir() {
            log::debug!("no rootfs at {}", self.rootfs.display());
            return false;
        }
        true
    }
}

impl crate::Engine {
    /// Tell the engine where proot and the Debian rootfs are.
    ///
    /// Called from the platform layer, in the `full` flavour, once the
    /// userland reports itself installed. The `play` flavour never calls it,
    /// and every guest run simply stays quiet.
    ///
    /// **Not once.** Kotlin's `syncUserlandWithEngine` hangs off a
    /// `LaunchedEffect` on the installer's state, and that effect restarts
    /// whenever the state is re-observed — twice in one process just from
    /// launching and opening the terminal. So this must be idempotent, and
    /// the repeat below is a no-op for everything a caller may already be
    /// holding: the same rootfs keeps the same askpass server.
    pub fn set_userland(&self, proot: &Path, rootfs: &Path, tmp_dir: &Path, projects_dir: &Path) {
        // Resolved, because Android hands the app `/data/user/0/<package>`,
        // which is a *symlink* to `/data/data/<package>`. proot binds what it
        // is given; a guest asked to `cd` into a path that only exists as a
        // link outside the fake root gets "No such file or directory", which
        // is what a `git diff` intermittently came back with.
        let real = |path: &Path| path.canonicalize().unwrap_or_else(|_| path.to_path_buf());
        let userland = Userland {
            proot: real(proot),
            rootfs: real(rootfs),
            tmp_dir: real(tmp_dir),
            projects_dir: real(projects_dir),
        };
        log::info!("userland configured: {userland:?}");
        // Anything left by a previous launch is dead; this is the moment
        // nothing of ours is running, so it is the moment to sweep.
        sweep_scratch(&userland.tmp_dir);
        // The credential helper's pipe lives in the rootfs's own /tmp, so it
        // can only start once the rootfs is known — see askpass.rs. The
        // platform layer calls this more than once per process (its installer
        // state is re-observed on every recomposition), and a server whose
        // rootfs is unchanged and whose script is still on disk is kept: a
        // git already running with its path, and the session's remembered
        // credentials, both survive the repeat.
        let mut askpass = self.askpass.lock().unwrap();
        let mut current = self.userland.lock().unwrap();
        let same_rootfs = current
            .as_ref()
            .is_some_and(|previous| previous.rootfs == userland.rootfs);
        let reusable = same_rootfs
            && askpass
                .as_ref()
                .is_some_and(|server| server.host_script().is_file());
        if !reusable {
            *askpass = crate::askpass::AskpassServer::start(&userland.rootfs).map(Arc::new);
        }
        *current = Some(Arc::new(userland));
    }

    /// Forget the userland — after the user removes the rootfs, say. Anything
    /// that needs the guest then degrades exactly as in a build that never had
    /// one.
    pub fn clear_userland(&self) {
        *self.askpass.lock().unwrap() = None;
        *self.userland.lock().unwrap() = None;
    }

    /// The configured userland, if there is one.
    ///
    /// Handed out as an `Arc` so a worker thread can hold it without holding
    /// the lock, and so a `clear_userland` mid-run only affects the *next*
    /// caller rather than pulling the rootfs out from under this one.
    pub(crate) fn userland(&self) -> Option<Arc<Userland>> {
        self.userland.lock().unwrap().clone()
    }
}

/// One program to run inside the guest: the caller's half of the command line.
///
/// Split from the proot half because that half is the same every time and this
/// half never is.
pub(crate) struct GuestCommand {
    /// Names the run in this module's log lines, where the full argv would be
    /// noise ("git status", "rust-analyzer").
    label: String,
    /// Everything from the program name onwards, as the guest will see it.
    argv: Vec<OsString>,
    /// Host directories this run needs visible, beyond the projects directory
    /// every run gets.
    binds: Vec<PathBuf>,
    /// Added on top of the guest environment every run gets.
    env: Vec<(OsString, OsString)>,
    /// The guest working directory. `/` unless a caller says otherwise, which
    /// git never needs (it carries `-C`) and a language server usually does:
    /// a server started somewhere else resolves `Cargo.toml`, `compile_commands.json`
    /// and relative paths in its own configuration against the wrong tree.
    workdir: Option<PathBuf>,
}

impl GuestCommand {
    pub(crate) fn new(label: impl Into<String>, argv: Vec<OsString>) -> Self {
        Self {
            label: label.into(),
            argv,
            binds: Vec::new(),
            env: Vec::new(),
            workdir: None,
        }
    }

    /// Start the program in `dir` rather than in `/`.
    ///
    /// Safe to pass a host path: binds are identities, so the directory exists
    /// inside the guest under the same name — but only if it is bound, which
    /// is why this also binds it.
    pub(crate) fn workdir(mut self, dir: &Path) -> Self {
        self.workdir = Some(dir.to_path_buf());
        self.bind(dir)
    }

    /// Make a host directory reachable inside the guest, at its own path.
    ///
    /// Free to call for a directory already inside the projects directory:
    /// [`bind_dirs`] drops it.
    pub(crate) fn bind(mut self, dir: &Path) -> Self {
        self.binds.push(dir.to_path_buf());
        self
    }

    pub(crate) fn env(mut self, key: impl AsRef<OsStr>, value: impl AsRef<OsStr>) -> Self {
        self.env
            .push((key.as_ref().to_owned(), value.as_ref().to_owned()));
        self
    }

    /// What a caller asked to be bound and set, so its own tests can pin it.
    /// The proot half is pinned here; the caller's half is only pinned where
    /// the caller lives, and both halves have to be, because either one lost
    /// is a silent behaviour change.
    #[cfg(test)]
    pub(crate) fn binds(&self) -> &[PathBuf] {
        &self.binds
    }

    #[cfg(test)]
    pub(crate) fn env_pairs(&self) -> &[(OsString, OsString)] {
        &self.env
    }
}

/// One proot command line, taken apart: what to exec, what to pass it, and
/// what environment to hand it.
///
/// It exists because two very different consumers need the *same* command line.
/// [`capture`] and [`spawn`] want a [`Command`] they can configure and run;
/// `lsp.rs` wants Zed's `LanguageServerBinary { path, arguments, env }`, whose
/// spawning is upstream code we do not get to touch. Both are built from this,
/// so there is exactly one description of how to enter the guest — the thing
/// this module's doc comment says a second copy of would mean a second
/// behaviour.
pub(crate) struct Invocation {
    /// The proot executable.
    pub program: PathBuf,
    /// proot's own flags, then the guest argv. `proot_command` and
    /// `LanguageServerBinary::arguments` take this verbatim.
    pub args: Vec<OsString>,
    /// Added on top of the *inherited* environment, which is how both
    /// consumers apply it — `Command::env` and `LanguageServer::new`'s
    /// `command.envs(binary.env)` alike.
    pub env: Vec<(OsString, OsString)>,
}

/// The proot invocation for a command: flags, binds, guest environment, argv.
///
/// Assembled in one place so the tests can pin it literally. A dropped flag
/// here does not fail loudly — it fails as a guest that cannot see a
/// directory, or a git that decides the repository has dubious ownership —
/// which is why the argv is a test rather than a comment.
pub(crate) fn invocation(userland: &Userland, command: &GuestCommand) -> Invocation {
    let mut args: Vec<OsString> = [
        // The guest must believe it is root. Besides matching how the rootfs
        // was unpacked, proot's fake_id0 also reports files as owned by root,
        // which is what keeps git's "dubious ownership" check quiet.
        "-0",
        // Don't leave a guest process behind if we have to kill proot;
        // Android's phantom-process killer counts them against us.
        "--kill-on-exit",
        // The rootfs was unpacked with this on, and dpkg keeps using it, so
        // the guest's own files are only presented correctly with it on here
        // too. Nothing here creates a link, so it costs a translation and
        // nothing else.
        "--link2symlink",
        // Debian's binaries are happy on any kernel, but the guest asking
        // uname is one less thing to differ from the terminal's environment.
        "-k",
        "6.2.1",
        "-r",
    ]
    .map(OsString::from)
    .into();
    args.push(userland.rootfs.as_os_str().to_owned());
    // The same three the terminal binds; without /proc, sub-processes misbehave
    // in ways that are tedious to diagnose.
    for dir in ["/dev", "/proc", "/sys"] {
        args.push(OsString::from("-b"));
        args.push(OsString::from(dir));
    }

    let binds = bind_dirs(userland, &command.binds);
    for dir in &binds {
        args.push(OsString::from("-b"));
        args.push(OsString::from(identity_bind(dir)));
    }
    log::debug!("{}: binds {binds:?}", command.label);

    // `/` always exists inside the guest, and with identity binds a program
    // that cares about its directory either says so itself (git's `-C`) or
    // asked for `workdir`.
    args.push(OsString::from("-w"));
    args.push(match &command.workdir {
        Some(dir) => dir.as_os_str().to_owned(),
        None => OsString::from("/"),
    });
    args.extend(command.argv.iter().cloned());

    let mut env: Vec<(OsString, OsString)> = vec![
        // A scratch directory of this run's own.
        //
        // proot builds its bind scaffolding under `PROOT_TMP_DIR`, and the
        // terminal's long-lived proot pointed at the same place as every short
        // one the engine spawns. With a shell open, a `git diff` from the
        // panel intermittently came back "cannot change to <the project>: No
        // such file or directory" — the bind was simply not there. Separate
        // directories, and the interaction cannot happen.
        ("PROOT_TMP_DIR".into(), scratch_dir(userland).into()),
        // The child inherits *our* environment, in which PATH points at
        // /system/bin — a directory that does not exist inside the fake root,
        // which is why the spike saw "command not found" for everything. Give
        // the guest a guest PATH.
        ("PATH".into(), GUEST_PATH.into()),
        ("HOME".into(), "/root".into()),
        // The guest's own /tmp, for the same reason the guest gets a guest
        // PATH: the inherited value is a *host* path. Android's runtime sets
        // TMPDIR to the app's cache directory after fork — invisible in
        // `/proc/<pid>/environ`, which only shows the initial environment —
        // and that directory does not exist inside the fake root. A shell
        // told to park a stream under it fails the redirection and reports
        // exit 2 with nothing said, which is how a failing `git fetch` came
        // back with empty output instead of git's own "fatal:" line.
        ("TMPDIR".into(), "/tmp".into()),
        ("LANG".into(), "C.UTF-8".into()),
        // Machine-readable output is not localised, but *errors* are, and we
        // log them.
        ("LC_ALL".into(), "C".into()),
    ];
    env.extend(command.env.iter().cloned());

    Invocation {
        program: userland.proot.clone(),
        args,
        env,
    }
}

fn proot_command(userland: &Userland, command: &GuestCommand) -> Command {
    let invocation = invocation(userland, command);
    let mut proot = Command::new(&invocation.program);
    proot.args(&invocation.args);
    for (key, value) in &invocation.env {
        proot.env(key, value);
    }
    proot
}

/// What one [`capture_outcome`] run came to, for the callers that must tell a
/// deadline kill apart from everything else: by the time a deadline fires the
/// program *was* running, and a mutating git command killed mid-flight may
/// have partly applied — a message blaming the userland would be a lie about
/// it.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum Captured {
    /// Ran to a successful exit; its stdout.
    Output(Vec<u8>),
    /// Could not start, could not be waited on, or exited non-zero.
    Failed,
    /// Still running at the deadline and killed for it.
    TimedOut,
}

/// Run a program in the guest to completion and return its stdout, or `None`
/// if it could not be started, timed out, or exited non-zero.
///
/// Nothing arrives until it exits: this is for queries whose answer is their
/// output. A process the caller wants to talk to is [`spawn`]'s job. A caller
/// that has to distinguish the deadline from the other failures reads
/// [`capture_outcome`] instead.
pub(crate) fn capture(
    userland: &Userland,
    command: &GuestCommand,
    timeout: Duration,
) -> Option<Vec<u8>> {
    match capture_outcome(userland, command, timeout) {
        Captured::Output(output) => Some(output),
        Captured::Failed | Captured::TimedOut => None,
    }
}

/// [`capture`], with the failures kept apart — see [`Captured`].
pub(crate) fn capture_outcome(
    userland: &Userland,
    command: &GuestCommand,
    timeout: Duration,
) -> Captured {
    capture_with_input(userland, command, None, timeout).0
}

/// [`capture_outcome`] for a program that reads its input on stdin — an
/// external formatter, which takes the buffer there and answers on stdout.
/// Returns what stderr said as well, since a formatter that refuses its
/// input explains itself there and nowhere else.
pub(crate) fn capture_with_input(
    userland: &Userland,
    command: &GuestCommand,
    input: Option<Vec<u8>>,
    timeout: Duration,
) -> (Captured, String) {
    let label = &command.label;
    let mut proot = proot_command(userland, command);
    proot
        .stdin(if input.is_some() {
            Stdio::piped()
        } else {
            Stdio::null()
        })
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    let mut child = match proot.spawn() {
        Ok(child) => child,
        Err(err) => {
            log::debug!("{label} could not start: {err}");
            return (Captured::Failed, String::new());
        }
    };

    // Fed from a thread of its own, for the pipe-capacity reason below: a
    // buffer larger than the pipe would block this thread on `write(2)`
    // until the child read it, and a child that writes before it reads
    // would block on *its* pipe, waiting for the reader started below. The
    // handle is dropped when the write ends, which is the EOF a formatter
    // waits for.
    let feeder = match (input, child.stdin.take()) {
        (Some(bytes), Some(mut stdin)) => Some(thread::spawn(move || {
            use std::io::Write;
            let _ = stdin.write_all(&bytes);
        })),
        _ => None,
    };

    // DEADLOCK, and why this is not a `try_wait` loop over a piped child.
    //
    // A pipe holds 64 KiB. `git status` on a repository with a few thousand
    // changed files writes more than that, then blocks in `write(2)` until
    // somebody reads. A supervisor that polls `try_wait` and only reads after
    // the child exits waits for a child that is waiting for the supervisor:
    // neither moves, and the run "times out" on a program that was working
    // perfectly. So both pipes are drained *concurrently*, by a thread each,
    // for the entire lifetime of the child. The main thread here does nothing
    // but watch the clock, which is the one job it can do without blocking on
    // a pipe.
    //
    // (`Command::output()` gets this right too — it polls both pipes — but it
    // consumes the child, leaving nothing to `kill()` when the timeout fires.)
    let (Some(mut stdout), Some(mut stderr)) = (child.stdout.take(), child.stderr.take()) else {
        // Cannot happen — both were piped four lines up — but a child left
        // running with nobody draining it would be worse than saying so.
        terminate(&mut child);
        return (Captured::Failed, String::new());
    };
    let out_reader = thread::spawn(move || {
        let mut buffer = Vec::new();
        let _ = stdout.read_to_end(&mut buffer);
        buffer
    });
    let err_reader = thread::spawn(move || {
        let mut buffer = Vec::new();
        let _ = stderr.read_to_end(&mut buffer);
        buffer
    });

    let deadline = Instant::now() + timeout;
    let mut timed_out = false;
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break Some(status),
            Ok(None) => {}
            Err(err) => {
                log::debug!("{label} could not be waited on: {err}");
                break None;
            }
        }
        if Instant::now() >= deadline {
            log::debug!("{label} timed out after {timeout:?}; killing it");
            timed_out = true;
            terminate(&mut child);
            break None;
        }
        thread::sleep(POLL_INTERVAL);
    };

    // Joining is safe now: the readers finish as soon as the pipes close,
    // which killing the child guarantees.
    let out = out_reader.join().unwrap_or_default();
    let err = err_reader.join().unwrap_or_default();
    if let Some(feeder) = feeder {
        let _ = feeder.join();
    }
    let stderr = String::from_utf8_lossy(&err).trim().to_owned();

    let Some(status) = status else {
        return if timed_out {
            (Captured::TimedOut, stderr)
        } else {
            (Captured::Failed, stderr)
        };
    };
    if !status.success() {
        // For git, the overwhelmingly common cause is "git is not installed in
        // the guest", which is a perfectly ordinary state for a fresh Debian.
        log::debug!("{label} exited with {status}: {stderr}");
        return (Captured::Failed, stderr);
    }
    // A successful exit can still have complaints on stderr — a shell whose
    // redirection failed says why *here*, then carries on and exits zero.
    // Dropping them silently is how a wrapper failure once read as "git
    // exited with 2" with nothing else to show, so at least the log keeps
    // the sentence.
    if !stderr.is_empty() {
        log::debug!("{label} stderr (exit 0): {stderr}");
    }
    (Captured::Output(out), stderr)
}

/// How long the login shell gets to print its environment. The real thing
/// measures 12–17 ms on the emulator, so this is not a budget but a backstop:
/// a shell silent for ten seconds has hung in somebody's profile, and the
/// caller is better served by the fixed environment than by waiting. It is
/// held under the agent slot lock, which those milliseconds make immaterial
/// and this ceiling would not.
const LOGIN_ENV_TIMEOUT: Duration = Duration::from_secs(10);

/// The environment a login shell would hand a program started in `workdir` —
/// /etc/profile, ~/.profile, and the ~/.bashrc the installer wrote, which is
/// where `~/.local/bin` joins PATH. `/bin/bash --login` because that is
/// exactly what the terminal runs (`DebianUserland.inside`): a program the
/// user can start by typing its name must be startable here too.
///
/// This is Zed's behaviour for external agents, ported: a custom agent's
/// process gets the login shell's environment as its base and the settings
/// env on top (zed project/src/agent_server_store.rs:1485-1493), captured by
/// running the shell with `-l` (zed util/src/shell_env.rs:118) in the project
/// directory (zed project/src/environment.rs:195). Zed caches the capture per
/// project; we do not, because it is cheap enough not to be worth a cache
/// that can go stale against an edited profile — **12–17 ms** measured on the
/// emulator (proot, bash, /etc/profile, ~/.profile, ~/.bashrc, 31 variables
/// back), against an agent start that spawns a whole language runtime
/// immediately afterwards.
///
/// Failure — no bash, a profile that `exit`s, a timeout — is an empty vec,
/// and the caller then runs with the fixed guest environment alone, which is
/// exactly the behaviour from before this existed.
pub(crate) fn login_environment(userland: &Userland, workdir: &Path) -> Vec<(String, String)> {
    // The shell's profile scripts share stdout with `env -0` — a message of
    // the day or a stray `echo` lands right in front of the first entry, the
    // same noisy-output problem Zed parses its way around (zed
    // util/src/shell_env.rs:9-18). The NUL printed first is the separator
    // noise cannot forge: profiles emit text, and text has no NUL in it.
    let argv = ["/bin/bash", "--login", "-c", "printf '\\000' && env -0"]
        .map(OsString::from)
        .into();
    let command = GuestCommand::new("login-env", argv).workdir(workdir);
    let Some(output) = capture(userland, &command, LOGIN_ENV_TIMEOUT) else {
        log::warn!("the login shell did not answer; keeping the fixed guest environment");
        return Vec::new();
    };
    parse_login_environment(&output)
}

/// Everything after the first NUL, as `KEY=VALUE` entries separated by NULs.
/// NUL-separated because values legitimately contain newlines (a multi-line
/// PS1, say) and `=` (any PATH-like list of options); a NUL is the one byte
/// they cannot contain.
fn parse_login_environment(output: &[u8]) -> Vec<(String, String)> {
    let Some(marker) = output.iter().position(|&byte| byte == 0) else {
        log::warn!("no environment in the login shell's output; keeping the fixed one");
        return Vec::new();
    };
    output[marker + 1..]
        .split(|&byte| byte == 0)
        .filter(|entry| !entry.is_empty())
        .filter_map(|entry| {
            let entry = match std::str::from_utf8(entry) {
                Ok(entry) => entry,
                Err(_) => {
                    log::debug!("dropping a non-UTF-8 environment entry");
                    return None;
                }
            };
            let (key, value) = entry.split_once('=')?;
            // The one engine-owned variable a capture must not echo back:
            // each run gets a scratch directory of its own (see `invocation`),
            // and the captured value names the *capture's*, already over.
            (key != "PROOT_TMP_DIR").then(|| (key.to_owned(), value.to_owned()))
        })
        .collect()
}

/// Start a resident program in the guest and hand back its handle, with all
/// three pipes open.
///
/// No deadline: the caller decides when this is over, because for a language
/// server "still running after twenty seconds" is health, not a hang. Nothing
/// is read or written here either — the pipes belong to the caller, who is the
/// only one who knows the protocol on them.
///
/// **Whoever calls this owes stderr a reader.** All three pipes are piped, and
/// an unread one fills its 64 KiB buffer and blocks the server for good — the
/// same deadlock [`capture`] spawns two threads to avoid. A server that logs
/// as it works, which is most of them, will hit it.
///
/// **Language servers do not come through here**, and it is worth saying why,
/// because this was written expecting them to. P5-1 took the route that costs
/// no vendor patch: Zed's `LanguageServer::new` spawns the process itself, so
/// what it needs is the *command line* — [`invocation`] — and not a process
/// somebody else already started. Framing, restart and the request tables then
/// all belong to the vendored crate rather than to us. See lsp.rs.
///
/// The caller this seam waited for arrived in P6: the ACP agent (`acp.rs`)
/// comes through here, owns all three pipes, and supplies the one thing this
/// does not do — noticing that the child died — with a watcher thread that
/// polls [`GuestProcess::exit_status`].
///
/// It does **not** owe a budget, but it does not enforce one either. P5-4 put
/// the budget where the unbounded number of resident children actually is —
/// one language server per language per project, capped and swept in lsp.rs —
/// and left the arithmetic here, in [`PROCESSES_PER_RUN`] and
/// [`PROCESS_BUDGET`], because this is the module that knows what entering the
/// guest costs. The agent budgets itself against the same two constants,
/// through [`RESERVED_FOR_AGENT`].
pub(crate) fn spawn(userland: &Userland, command: &GuestCommand) -> Option<GuestProcess> {
    if !userland.is_installed() {
        return None;
    }
    let mut proot = proot_command(userland, command);
    proot
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    let mut child = match proot.spawn() {
        Ok(child) => child,
        Err(err) => {
            log::debug!("{} could not start: {err}", command.label);
            return None;
        }
    };
    Some(GuestProcess {
        label: command.label.clone(),
        stdin: child.stdin.take(),
        stdout: child.stdout.take(),
        stderr: child.stderr.take(),
        child,
        exited: None,
    })
}

/// A guest process the caller owns.
///
/// Dropping it shuts the process down the careful way (see [`terminate`]), so
/// losing the handle cannot leave a proot and its tracees behind — which on
/// Android is not a leak but a quota.
pub(crate) struct GuestProcess {
    label: String,
    child: Child,
    stdin: Option<ChildStdin>,
    stdout: Option<ChildStdout>,
    stderr: Option<ChildStderr>,
    /// Set the moment a wait observes the process gone. After that its pid
    /// belongs to the kernel again and must never be signalled — see
    /// [`GuestProcess::exit_status`].
    exited: Option<std::process::ExitStatus>,
}

impl GuestProcess {
    /// The write half. Taken rather than borrowed because the caller will want
    /// it behind its own lock, away from whatever owns the read half.
    pub(crate) fn take_stdin(&mut self) -> Option<ChildStdin> {
        self.stdin.take()
    }

    /// The read half, for the caller's own framing thread.
    pub(crate) fn take_stdout(&mut self) -> Option<ChildStdout> {
        self.stdout.take()
    }

    /// Servers log here, sometimes voluminously. Whoever takes it must keep
    /// reading it: an unread stderr fills its pipe and blocks the server for
    /// good.
    pub(crate) fn take_stderr(&mut self) -> Option<ChildStderr> {
        self.stderr.take()
    }

    /// Has it exited? Never blocks; `None` means still running (or that the
    /// wait itself failed, which is reported once and then indistinguishable).
    ///
    /// Asking is not free of consequence: a successful wait **reaps** the
    /// process, and the kernel is then free to hand that pid to somebody else.
    /// So the answer is remembered, and [`Drop`] signals nothing once it is
    /// known — otherwise a restart that polls this and then drops the handle
    /// would send SIGQUIT to whatever now holds the number, which on a phone
    /// where pids wrap at 32768 is plausibly one of our own shells.
    pub(crate) fn exit_status(&mut self) -> Option<std::process::ExitStatus> {
        if self.exited.is_some() {
            return self.exited;
        }
        match self.child.try_wait() {
            Ok(status) => {
                self.exited = status;
                status
            }
            Err(err) => {
                log::debug!("{} could not be waited on: {err}", self.label);
                None
            }
        }
    }

    /// Take it down now, and say how it went.
    ///
    /// The same shutdown [`Drop`] does — it is the body of `Drop` — but with
    /// the status handed back instead of discarded. A caller that kills a
    /// process on somebody's behalf owes them an answer: an ACP
    /// `terminal/kill` is followed by a `terminal/wait_for_exit` that must
    /// report the *real* signal, and the escalation inside [`terminate`] means
    /// only the wait knows whether the polite one was enough.
    pub(crate) fn terminate_now(&mut self) -> Option<std::process::ExitStatus> {
        if let Some(status) = self.exited {
            return Some(status);
        }
        // Fields are dropped *after* a `Drop` body, so an stdin the caller
        // never took would still be open while we wait — and a server that
        // would have exited politely on EOF would never see one. Close it
        // first and give the polite path its chance; `terminate` then costs
        // nothing.
        self.stdin.take();
        self.exited = terminate(&mut self.child);
        self.exited
    }
}

impl Drop for GuestProcess {
    fn drop(&mut self) {
        self.terminate_now();
    }
}

/// The directories proot must be able to see, deduplicated.
///
/// The projects directory covers the normal case in one bind. A caller's own
/// directory is added only when it sits outside it — an imported project whose
/// enclosing repository lives elsewhere — because a bind of a path already
/// inside another bind is just noise.
fn bind_dirs(userland: &Userland, extra: &[PathBuf]) -> Vec<PathBuf> {
    let mut dirs = vec![userland.projects_dir.clone()];
    for dir in extra {
        // Two callers naming the same directory, or one naming a directory
        // already covered, must not produce two `-b` for it: the doc above
        // says deduplicated, and with several callers sharing this seam that
        // stops being theoretical.
        if !dir.starts_with(&userland.projects_dir) && !dirs.iter().any(|seen| seen == dir) {
            dirs.push(dir.clone());
        }
    }
    dirs
}

/// A directory for one proot instance to build its scaffolding in.
///
/// Named per process and per call rather than shared: two instances that
/// share one have been seen to lose each other's binds. Created here and swept
/// by [`sweep_scratch`] rather than removed on exit, because proot is killed
/// as often as it is waited for and a directory removed under a live instance
/// is the very problem this avoids.
fn scratch_dir(userland: &Userland) -> PathBuf {
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let run = NEXT.fetch_add(1, Ordering::Relaxed);
    let dir = userland
        .tmp_dir
        .join(format!("guest-{}-{run}", std::process::id()));
    let _ = std::fs::create_dir_all(&dir);
    dir
}

/// Remove the scratch directories of runs that are over.
///
/// Called when the engine is told where the userland is — once per launch,
/// when nothing of ours is running — because proot leaves its own directory
/// behind whenever it is killed rather than waited for, and a cache directory
/// that only grows is a bug of its own.
pub(crate) fn sweep_scratch(tmp_dir: &Path) {
    let Ok(entries) = std::fs::read_dir(tmp_dir) else {
        return;
    };
    let ours = format!("guest-{}-", std::process::id());
    for entry in entries.flatten() {
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if name.starts_with("guest-") && !name.starts_with(&ours) {
            let _ = std::fs::remove_dir_all(entry.path());
        }
    }
}

/// `-b <path>:<path>`: the host path mounted at the identical guest path.
fn identity_bind(path: &Path) -> String {
    let path = path.to_string_lossy();
    format!("{path}:{path}")
}

/// Stop proot without orphaning what it is tracing.
///
/// `Child::kill` is SIGKILL, and proot never sees it: it dies where it stands
/// and its tracees — a program that has stopped answering, and whatever it
/// forked — keep running, counting against Android's cap on background child
/// processes with nothing left holding a handle to them. proot does act on
/// SIGQUIT, and takes its tracees down with it, so ask that way first and give
/// it a moment. This is the lesson `GitClone.terminate` already learned on the
/// Kotlin side.
///
/// SIGKILL stays as the last resort, for a proot that ignores even this.
fn terminate(child: &mut Child) -> Option<std::process::ExitStatus> {
    #[cfg(unix)]
    {
        // Safety: `child` is alive here — nothing has reaped it, since the only
        // waits on it are this function's own — so the pid cannot have been
        // recycled onto some other process.
        unsafe { libc::kill(child.id() as libc::pid_t, libc::SIGQUIT) };
        let deadline = Instant::now() + QUIT_GRACE;
        while Instant::now() < deadline {
            match child.try_wait() {
                Ok(Some(status)) => return Some(status),
                Ok(None) => thread::sleep(POLL_INTERVAL),
                Err(_) => break,
            }
        }
    }
    let _ = child.kill();
    child.wait().ok()
}

/// Test doubles for the guest, shared with `acp.rs`'s tests: a stand-in proot
/// and a [`Userland`] pointing at it, so plumbing on either side of the spawn
/// can run on a host that has no rootfs.
#[cfg(test)]
pub(crate) mod testing {
    use super::*;

    /// Stand in for proot, so the plumbing either side of it can be tested on
    /// a host that has no rootfs.
    ///
    /// It drops every flag up to and including `-w <dir>` and execs the rest,
    /// which is exactly the contract `proot_command` relies on: the guest
    /// argv is the tail of the command line. It cannot pretend to be a fake
    /// root, and does not try — what is under test is the deadline, the
    /// draining and the pipes, none of which care what the program is.
    #[cfg(unix)]
    pub(crate) fn fake_proot(dir: &Path) -> PathBuf {
        use std::os::unix::fs::PermissionsExt;

        let path = dir.join("fake-proot");
        std::fs::write(
            &path,
            "#!/bin/sh\nwhile [ \"$1\" != \"-w\" ]; do shift; done\nshift 2\nexec \"$@\"\n",
        )
        .unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).unwrap();

        // ETXTBSY, and why this is not just a write followed by a spawn.
        //
        // Writing a program and immediately exec'ing it races with every other
        // thread in this process that forks — and the suite has several.
        // Between a fork and its exec the child still holds our *write*
        // descriptor, and Linux refuses to exec a file anybody has open for
        // writing: "Text file busy". It surfaced as roughly one run in eight
        // of `cargo test -p engine`, as a capture that returned `None` for no
        // reason the assertions could explain.
        //
        // The window closes on its own, so probe until it has: once one exec
        // of this path succeeds, no writer holds it and none can appear — this
        // is the only code that ever writes it.
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            let ok = Command::new(&path)
                .args(["-w", "/", "/bin/sh", "-c", ":"])
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status()
                .is_ok_and(|status| status.success());
            if ok || Instant::now() >= deadline {
                break;
            }
            thread::sleep(POLL_INTERVAL);
        }
        path
    }

    /// A [`Userland`] that cannot start anything, for tests that need one in
    /// hand but never spawn — `is_installed` is false, so `spawn` refuses
    /// before it reaches the paths.
    pub(crate) fn unusable_userland() -> Userland {
        Userland {
            proot: PathBuf::from("/nowhere/proot"),
            rootfs: PathBuf::from("/nowhere/rootfs"),
            tmp_dir: PathBuf::from("/nowhere/tmp"),
            projects_dir: PathBuf::from("/nowhere/projects"),
        }
    }

    #[cfg(unix)]
    pub(crate) fn fake_userland(dir: &Path) -> Userland {
        Userland {
            proot: fake_proot(dir),
            rootfs: dir.to_path_buf(),
            tmp_dir: dir.to_path_buf(),
            projects_dir: dir.to_path_buf(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    fn userland() -> Userland {
        Userland {
            proot: PathBuf::from("/lib/libproot_exec.so"),
            rootfs: PathBuf::from("/files/debian"),
            tmp_dir: PathBuf::from("/cache"),
            projects_dir: PathBuf::from("/files/projects"),
        }
    }

    fn argv_of(command: &Command) -> Vec<String> {
        command
            .get_args()
            .map(|arg| arg.to_string_lossy().into_owned())
            .collect()
    }

    fn env_of(command: &Command) -> BTreeMap<String, String> {
        command
            .get_envs()
            .map(|(key, value)| {
                (
                    key.to_string_lossy().into_owned(),
                    value.unwrap_or_default().to_string_lossy().into_owned(),
                )
            })
            .collect()
    }

    /// The flags, spelled out.
    ///
    /// Every one of them was arrived at by watching something fail on a phone,
    /// and every one of them fails *quietly* when it goes missing — a guest
    /// that cannot see a directory, a uname a package dislikes, a tracee left
    /// running after proot is gone. So the argv is pinned literally here:
    /// dropping a flag should cost a red test, not a device session.
    #[test]
    fn the_proot_command_line_is_exactly_this() {
        let command = GuestCommand::new("test", vec![OsString::from("true")]);
        let proot = proot_command(&userland(), &command);

        assert_eq!(proot.get_program(), OsStr::new("/lib/libproot_exec.so"));
        assert_eq!(
            argv_of(&proot),
            vec![
                "-0",
                "--kill-on-exit",
                "--link2symlink",
                "-k",
                "6.2.1",
                "-r",
                "/files/debian",
                "-b",
                "/dev",
                "-b",
                "/proc",
                "-b",
                "/sys",
                "-b",
                "/files/projects:/files/projects",
                "-w",
                "/",
                "true",
            ]
        );
    }

    /// The guest environment, spelled out for the same reason. PATH is the one
    /// that stings: inherited from Android it names /system/bin, which does
    /// not exist inside the fake root, and *every* command is then "not
    /// found".
    #[test]
    fn the_guest_environment_is_exactly_this() {
        let command = GuestCommand::new("test", vec![OsString::from("true")]);
        let env = env_of(&proot_command(&userland(), &command));

        let scratch = env.get("PROOT_TMP_DIR").cloned().unwrap_or_default();
        // A directory of this run's own under the cache, never the cache
        // itself: see `scratch_dir`.
        assert!(
            scratch.starts_with("/cache/guest-"),
            "PROOT_TMP_DIR was {scratch:?}"
        );
        let mut env = env;
        env.insert("PROOT_TMP_DIR".to_owned(), "/cache".to_owned());
        assert_eq!(
            env,
            BTreeMap::from([
                ("PROOT_TMP_DIR".to_owned(), "/cache".to_owned()),
                (
                    "PATH".to_owned(),
                    "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin".to_owned()
                ),
                ("HOME".to_owned(), "/root".to_owned()),
                // The guest's /tmp, never the host's: Android sets TMPDIR to
                // the app cache directory at runtime, which does not exist
                // inside the fake root — see `invocation`.
                ("TMPDIR".to_owned(), "/tmp".to_owned()),
                ("LANG".to_owned(), "C.UTF-8".to_owned()),
                ("LC_ALL".to_owned(), "C".to_owned()),
            ])
        );
    }

    #[test]
    fn a_caller_adds_to_the_environment_rather_than_replacing_it() {
        let command = GuestCommand::new("test", vec![OsString::from("true")])
            .env("GIT_OPTIONAL_LOCKS", "0")
            .env("LC_ALL", "en_GB.UTF-8");
        let env = env_of(&proot_command(&userland(), &command));

        assert_eq!(env.get("HOME"), Some(&"/root".to_owned()));
        assert_eq!(env.get("GIT_OPTIONAL_LOCKS"), Some(&"0".to_owned()));
        // Last writer wins, so a caller that really needs a different locale
        // can have one.
        assert_eq!(env.get("LC_ALL"), Some(&"en_GB.UTF-8".to_owned()));
    }

    #[test]
    fn binds_are_identities_and_deduplicated() {
        let userland = userland();
        // The host path is mounted at the identical guest path: nothing to
        // translate in either direction.
        assert_eq!(
            identity_bind(Path::new("/files/projects")),
            "/files/projects:/files/projects"
        );
        // A directory inside the projects directory needs no bind of its own.
        assert_eq!(
            bind_dirs(&userland, &[PathBuf::from("/files/projects/thing")]),
            vec![PathBuf::from("/files/projects")]
        );
        // One outside does.
        assert_eq!(
            bind_dirs(&userland, &[PathBuf::from("/elsewhere/repo")]),
            vec![
                PathBuf::from("/files/projects"),
                PathBuf::from("/elsewhere/repo"),
            ]
        );
    }

    /// `-w` is `/` unless a caller says otherwise, and a caller that does gets
    /// the directory bound as well — otherwise proot starts the program in a
    /// directory that does not exist inside the fake root and it exits before
    /// it has said anything.
    #[test]
    fn a_workdir_is_bound_as_well_as_entered() {
        let userland = userland();
        let command = GuestCommand::new("test", vec![OsString::from("true")])
            .workdir(Path::new("/elsewhere/repo"));
        let argv = argv_of(&proot_command(&userland, &command));

        assert!(argv.contains(&"/elsewhere/repo:/elsewhere/repo".to_owned()));
        let w = argv.iter().position(|arg| arg == "-w").expect("a -w");
        assert_eq!(argv[w + 1], "/elsewhere/repo");
        // And the program still follows it, which is what `fake_proot` relies
        // on and what makes the guest argv the tail of the command line.
        assert_eq!(argv[w + 2], "true");
    }

    /// The pieces a caller that spawns for itself needs — the route language
    /// servers take, where Zed's `LanguageServer::new` does the spawning and
    /// only ever sees this.
    #[test]
    fn an_invocation_is_the_same_command_line_taken_apart() {
        let userland = userland();
        let command = GuestCommand::new("test", vec![OsString::from("true")]);
        let invocation = invocation(&userland, &command);

        assert_eq!(invocation.program, PathBuf::from("/lib/libproot_exec.so"));
        assert_eq!(
            invocation.args,
            argv_of(&proot_command(&userland, &command))
                .into_iter()
                .map(OsString::from)
                .collect::<Vec<_>>()
        );
        let env: BTreeMap<String, String> = invocation
            .env
            .iter()
            .map(|(key, value)| {
                (
                    key.to_string_lossy().into_owned(),
                    value.to_string_lossy().into_owned(),
                )
            })
            .collect();
        assert_eq!(env.get("HOME"), Some(&"/root".to_owned()));
        assert_eq!(
            env.get("PATH"),
            Some(&"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin".to_owned())
        );
    }

    #[test]
    fn a_caller_bind_reaches_the_command_line() {
        let command = GuestCommand::new("test", vec![OsString::from("true")])
            .bind(Path::new("/elsewhere/repo"));
        let argv = argv_of(&proot_command(&userland(), &command));
        assert!(argv.contains(&"/elsewhere/repo:/elsewhere/repo".to_owned()));
    }

    /// The parser's whole job: profile noise before the marker is discarded,
    /// and values keep the bytes a text protocol would have mangled — the
    /// newline and the `=` — which is why the entries are NUL-separated.
    #[test]
    fn login_environment_entries_survive_noise_newlines_and_equals() {
        let output = b"Welcome to Debian!\n\0PATH=/root/.local/bin:/usr/bin\0\
                       PS1=\\w\n$ \0OPTS=--jobs=4\0";
        assert_eq!(
            parse_login_environment(output),
            vec![
                ("PATH".to_owned(), "/root/.local/bin:/usr/bin".to_owned()),
                ("PS1".to_owned(), "\\w\n$ ".to_owned()),
                ("OPTS".to_owned(), "--jobs=4".to_owned()),
            ]
        );
    }

    /// A profile that never let the command run — an `exit`, a `set -e`
    /// casualty — produces no marker, and the answer is "nothing captured",
    /// never a guess parsed out of noise.
    #[test]
    fn a_login_environment_without_its_marker_is_empty() {
        assert_eq!(
            parse_login_environment(b"Some message of the day\n"),
            Vec::new()
        );
        assert_eq!(parse_login_environment(b""), Vec::new());
    }

    /// The capture ran under a scratch directory of its own, already over by
    /// the time the agent starts; echoing it back would point proot's bind
    /// scaffolding at a directory another run owns — the exact interaction
    /// separate scratch directories exist to prevent (see `invocation`).
    #[test]
    fn a_login_environment_never_echoes_the_scratch_directory_back() {
        let output = b"\0PROOT_TMP_DIR=/cache/guest-1-2\0HOME=/root\0";
        assert_eq!(
            parse_login_environment(output),
            vec![("HOME".to_owned(), "/root".to_owned())]
        );
    }

    #[cfg(unix)]
    use super::testing::fake_userland;

    #[cfg(unix)]
    fn sh(script: &str) -> Vec<OsString> {
        vec![
            OsString::from("/bin/sh"),
            OsString::from("-c"),
            OsString::from(script),
        ]
    }

    #[test]
    #[cfg(unix)]
    fn a_capture_returns_stdout_and_swallows_stderr() {
        let dir = tempfile::tempdir().unwrap();
        let guest = fake_userland(dir.path());
        let command = GuestCommand::new("test", sh("printf hello; printf oops >&2"));
        let out = capture(&guest, &command, Duration::from_secs(10));
        assert_eq!(out, Some(b"hello".to_vec()));
    }

    #[test]
    #[cfg(unix)]
    fn a_capture_of_more_than_a_pipeful_does_not_deadlock() {
        // 64 KiB is the pipe buffer; a program writing past it blocks unless
        // somebody is draining. Both pipes are loaded here, so neither can be
        // the one that saves the other.
        let dir = tempfile::tempdir().unwrap();
        let guest = fake_userland(dir.path());
        let script = "i=0; while [ $i -lt 4000 ]; do \
                      echo aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa; \
                      echo bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb >&2; i=$((i+1)); done";
        let command = GuestCommand::new("test", sh(script));
        let out = capture(&guest, &command, Duration::from_secs(30))
            .expect("a program that outlives one pipeful still completes");
        assert_eq!(out.len(), 4000 * 31);
    }

    #[test]
    #[cfg(unix)]
    fn a_failing_capture_is_none_rather_than_partial_output() {
        let dir = tempfile::tempdir().unwrap();
        let guest = fake_userland(dir.path());
        let command = GuestCommand::new("test", sh("printf half; exit 3"));
        assert_eq!(capture(&guest, &command, Duration::from_secs(10)), None);
        // And a non-zero exit is a *failure*, never mistaken for the deadline.
        assert_eq!(
            capture_outcome(&guest, &command, Duration::from_secs(10)),
            Captured::Failed
        );
    }

    #[test]
    #[cfg(unix)]
    fn a_capture_past_its_deadline_is_killed() {
        let dir = tempfile::tempdir().unwrap();
        let guest = fake_userland(dir.path());
        let command = GuestCommand::new("test", sh("sleep 30"));
        let started = Instant::now();
        assert_eq!(capture(&guest, &command, Duration::from_millis(200)), None);
        // The deadline is what ended it, not the sleep.
        assert!(started.elapsed() < Duration::from_secs(20));
        // And the outcome says so, which is what lets a killed mutation admit
        // it may have partly applied rather than blame the userland.
        assert_eq!(
            capture_outcome(&guest, &command, Duration::from_millis(200)),
            Captured::TimedOut
        );
    }

    /// The real capture, through the fake proot: a genuine `bash --login`
    /// runs on the host, sources whatever profiles the host has — noise and
    /// all — and the entries still come back. HOME is the witness: the fixed
    /// guest environment sets it, the login shell inherits it, and `env -0`
    /// must return it through the parser intact.
    #[test]
    #[cfg(unix)]
    fn a_login_environment_is_captured_through_a_real_login_shell() {
        let dir = tempfile::tempdir().unwrap();
        let guest = fake_userland(dir.path());
        let env = login_environment(&guest, dir.path());
        assert!(
            env.iter()
                .any(|(key, value)| key == "HOME" && value == "/root"),
            "HOME did not survive the round trip: {env:?}"
        );
        assert!(
            env.iter().all(|(key, _)| key != "PROOT_TMP_DIR"),
            "the capture's scratch directory leaked through"
        );
    }

    #[test]
    #[cfg(unix)]
    fn a_spawned_process_talks_both_ways_and_dies_with_its_handle() {
        use std::io::{BufRead, BufReader, Write};

        let dir = tempfile::tempdir().unwrap();
        let guest = fake_userland(dir.path());
        let script = "while read line; do echo \"got $line\"; done";
        let command = GuestCommand::new("test", sh(script));
        let mut process = spawn(&guest, &command).expect("the fake proot starts");

        // Bidirectional, and alive between the two: nothing here waits for the
        // process to exit before the caller sees a byte.
        let mut stdin = process.take_stdin().expect("stdin is piped");
        let mut stdout = BufReader::new(process.take_stdout().expect("stdout is piped"));
        writeln!(stdin, "ping").unwrap();
        stdin.flush().unwrap();
        let mut line = String::new();
        stdout.read_line(&mut line).unwrap();
        assert_eq!(line, "got ping\n");
        assert!(process.take_stderr().is_some());
        assert_eq!(process.exit_status(), None, "still running");

        // Dropping the handle is the shutdown: nothing survives it, because on
        // Android a survivor spends the process budget with nobody left to
        // stop it.
        let pid = process.child.id();
        let before = start_time(pid).expect("the process is running");
        drop(stdin);
        drop(process);
        // Not "does this pid exist": the suite spawns processes in parallel
        // and the kernel is free to hand this number straight to one of them,
        // which made this assertion fail about one run in three. Ask whether
        // *this* process is still there, which its start time answers and a
        // recycled pid cannot fake.
        assert_ne!(
            start_time(pid),
            Some(before),
            "the process outlived its handle"
        );
    }

    /// Field 22 of `/proc/<pid>/stat`, which with the pid identifies a process
    /// for as long as the machine is up. The comm field can contain spaces and
    /// parentheses, so the split starts after its closing one.
    #[cfg(any(target_os = "linux", target_os = "android"))]
    fn start_time(pid: u32) -> Option<u64> {
        let stat = std::fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
        let after_comm = stat.rsplit_once(")").map(|(_, rest)| rest)?;
        after_comm.split_whitespace().nth(19)?.parse().ok()
    }

    #[test]
    fn there_is_no_userland_until_the_platform_layer_says_so() {
        let engine = crate::Engine::new();
        assert!(engine.userland().is_none());

        let dir = tempfile::tempdir().unwrap();
        engine.set_userland(
            &dir.path().join("proot"),
            &dir.path().join("debian"),
            dir.path(),
            dir.path(),
        );
        assert!(engine.userland().is_some());

        engine.clear_userland();
        assert!(engine.userland().is_none());
    }

    /// The platform layer configures the userland more than once per process
    /// — its installer state is re-observed on every recomposition. The
    /// second configure used to start a fresh askpass server whose directory
    /// was the first's, and dropping the first then removed the script the
    /// engine went on advertising in `GIT_ASKPASS`. Now the running server is
    /// kept, its script stays on disk, and a helper can still round-trip.
    #[test]
    fn configuring_the_userland_again_keeps_the_askpass_helper_alive() {
        let engine = crate::Engine::new();
        let dir = tempfile::tempdir().unwrap();
        let rootfs = dir.path().join("debian");
        std::fs::create_dir_all(rootfs.join("tmp")).unwrap();
        let configure = || {
            engine.set_userland(&dir.path().join("proot"), &rootfs, dir.path(), dir.path());
        };

        configure();
        let first = engine.askpass().expect("a server starts with the rootfs");
        configure();
        let second = engine.askpass().expect("a server is still there");
        assert!(
            Arc::ptr_eq(&first, &second),
            "the same rootfs keeps the same server"
        );
        drop(first);
        assert!(
            second.host_script().is_file(),
            "the helper script is still on disk"
        );
        assert!(rootfs.join("tmp").read_dir().unwrap().next().is_some());

        // The round trip, with the helper run from its host path — what
        // proot maps `/tmp/…/askpass.sh` to in the guest.
        let mut sh = std::process::Command::new("sh");
        sh.arg("-c")
            .arg(r#"printf '%s\n' "$("$GIT_ASKPASS" "Username for 'https://example.com': ")""#)
            .env("GIT_ASKPASS", second.host_script())
            .stdout(std::process::Stdio::piped());
        let child = sh.spawn().expect("sh runs");
        let prompt = second
            .wait_pending(std::time::Duration::from_secs(5))
            .expect("the prompt reaches the engine");
        assert!(second.answer(prompt.id, "alice", false));
        let output = child.wait_with_output().unwrap();
        assert!(output.status.success());
        assert_eq!(String::from_utf8_lossy(&output.stdout), "alice\n");

        // A different rootfs is a different userland, and does get a new server.
        let other = dir.path().join("other");
        std::fs::create_dir_all(other.join("tmp")).unwrap();
        engine.set_userland(&dir.path().join("proot"), &other, dir.path(), dir.path());
        let third = engine.askpass().expect("a server for the new rootfs");
        assert!(!Arc::ptr_eq(&second, &third));
        assert!(third.host_script().starts_with(&other));
    }
}
