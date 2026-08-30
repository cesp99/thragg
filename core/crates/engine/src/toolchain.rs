//! Which interpreter a project runs with — Zed's `toolchain::Select`.
//!
//! A Python project on a phone is nearly always a virtualenv project: the
//! `python3` in the Debian userland is the wrong one, `pip install` went into
//! `.venv`, and a language server started outside it cannot see a single
//! dependency. Zed answers that with a toolchain per (worktree, language),
//! chosen from a picker and passed into everything it starts
//! (`crates/toolchain_selector`, `language::Toolchain`); this is the same
//! idea with the two toolchains that matter here — Python's virtualenvs and
//! Rust's rustup toolchains.
//!
//! Three parts:
//!
//! * [`detect`] looks for candidates. The virtualenvs are found on the host
//!   filesystem — a project's files are visible at their real paths, so a
//!   `pyvenv.cfg` is a plain `stat` — and `poetry`, `rustup` and the system
//!   interpreters are asked inside the guest, which is the only place a
//!   binary can run.
//! * The choice is remembered per project *and* per language in
//!   `<files>/toolchains.json`, not in the project's own `.zed/settings.json`:
//!   an absolute interpreter path is this device's, and committing it to
//!   somebody's repository would be wrong.
//! * [`ToolchainEnv`] is what the choice *does*. Zed exports `VIRTUAL_ENV`
//!   and puts the interpreter's directory at the front of `PATH`
//!   (`headless_project.rs:990-1002`); the language servers and the task
//!   runner here take the same pair, so `pyright` resolves imports and a
//!   `python -m pytest` task runs the project's Python.

use std::collections::BTreeMap;
use std::ffi::OsString;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::guest::{self, GuestCommand};
use crate::project::ProjectId;
use crate::{Engine, EngineError};

/// How long a detection command gets. `rustup toolchain list` is a directory
/// read and `poetry env info` is a Python start-up; both are well inside this,
/// and a userland that is busy unpacking must not hang the picker.
const DETECT_TIMEOUT: Duration = Duration::from_secs(20);

/// Directory names that hold a virtualenv, in the order Zed's Python provider
/// tries them (`languages/src/python.rs`, `find_venv_in_worktree`).
const VENV_DIRS: &[&str] = &[".venv", "venv", ".env", "env"];

/// One toolchain the user may pick — Zed's `language::Toolchain`, flattened
/// to what a picker row and an environment need.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Toolchain {
    /// The row's title: "Python 3.11.2 (.venv)", "stable-aarch64-…".
    pub name: String,
    /// Absolute path to the program itself — the `python` or the `cargo` —
    /// as both the host and the guest see it (the engine binds identity, so
    /// there is one path, not two).
    pub path: String,
    /// The language's display name, as `config.toml` spells it ("Python").
    /// Zed keys a toolchain by this, and so does the picker's grouping.
    pub language: String,
    /// Where it was found, for the row's subtitle: "poetry", "rustup",
    /// ".venv", "system".
    pub source: String,
}

impl Toolchain {
    /// The directory the program sits in — `<venv>/bin` — which is what goes
    /// on `PATH`.
    fn bin_dir(&self) -> Option<PathBuf> {
        Path::new(&self.path).parent().map(Path::to_path_buf)
    }

    /// The virtualenv root, one above `bin/`. `None` for a toolchain that is
    /// not a virtualenv, which is what keeps `VIRTUAL_ENV` off a Rust
    /// toolchain's environment.
    fn virtual_env(&self) -> Option<PathBuf> {
        if self.language != "Python" || self.source == "system" {
            return None;
        }
        self.bin_dir()?.parent().map(Path::to_path_buf)
    }
}

/// The environment an active toolchain contributes, ready to be merged into a
/// language server's or a task's own.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ToolchainEnv(pub Vec<(String, String)>);

impl ToolchainEnv {
    /// Zed's pair: the interpreter's directory in front of `PATH`, and
    /// `VIRTUAL_ENV` naming the environment's root, which is what every
    /// Python tool actually reads.
    fn of(toolchains: &[Toolchain]) -> ToolchainEnv {
        let mut prefix: Vec<String> = Vec::new();
        let mut env: Vec<(String, String)> = Vec::new();
        for toolchain in toolchains {
            if let Some(bin) = toolchain.bin_dir() {
                prefix.push(bin.to_string_lossy().into_owned());
            }
            if let Some(root) = toolchain.virtual_env() {
                env.push(("VIRTUAL_ENV".to_owned(), root.to_string_lossy().into_owned()));
            }
        }
        if !prefix.is_empty() {
            // The guest's own PATH is appended, not replaced: the toolchain
            // adds an interpreter, it does not take `sh` away.
            prefix.push(guest::GUEST_PATH.to_owned());
            env.push(("PATH".to_owned(), prefix.join(":")));
        }
        ToolchainEnv(env)
    }

    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }

    pub fn iter(&self) -> impl Iterator<Item = (&str, &str)> {
        self.0
            .iter()
            .map(|(key, value)| (key.as_str(), value.as_str()))
    }
}

/// The remembered choices: project root → language display name → toolchain.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
struct Choices {
    #[serde(default)]
    projects: BTreeMap<String, BTreeMap<String, Toolchain>>,
}

fn store_path_slot() -> &'static Mutex<Option<PathBuf>> {
    static SLOT: OnceLock<Mutex<Option<PathBuf>>> = OnceLock::new();
    SLOT.get_or_init(|| Mutex::new(None))
}

pub(crate) fn set_directory(directory: PathBuf) {
    *store_path_slot().lock().unwrap() = Some(directory.join("toolchains.json"));
}

fn read_choices() -> Choices {
    let Some(path) = store_path_slot().lock().unwrap().clone() else {
        return Choices::default();
    };
    let Ok(text) = std::fs::read_to_string(path) else {
        return Choices::default();
    };
    // A file we wrote and cannot read is a file to start over from, not an
    // error to show: the worst it costs is one re-pick.
    serde_json::from_str(&text).unwrap_or_default()
}

fn write_choices(choices: &Choices) -> Result<(), EngineError> {
    let Some(path) = store_path_slot().lock().unwrap().clone() else {
        return Ok(());
    };
    let text = serde_json::to_string_pretty(choices).map_err(|err| EngineError::Io {
        path: path.to_string_lossy().into_owned(),
        message: err.to_string(),
    })?;
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    crate::file::write_atomically(&path, text.as_bytes())
}

/// The virtualenvs under `root`: its own, and one level of subdirectories, so
/// a repository whose Python lives in `backend/.venv` is found without walking
/// a whole tree.
fn virtualenvs(root: &Path) -> Vec<Toolchain> {
    let mut found = Vec::new();
    let mut roots = vec![root.to_path_buf()];
    if let Ok(entries) = std::fs::read_dir(root) {
        for entry in entries.flatten().take(200) {
            if entry.file_type().is_ok_and(|kind| kind.is_dir()) {
                let name = entry.file_name();
                let name = name.to_string_lossy();
                // A virtualenv's own directory is not a place to look for
                // more, and neither is `.git`.
                if name.starts_with('.') && !VENV_DIRS.contains(&name.as_ref()) {
                    continue;
                }
                roots.push(entry.path());
            }
        }
    }
    for directory in roots {
        for name in VENV_DIRS {
            let venv = directory.join(name);
            if let Some(toolchain) = virtualenv_at(&venv, root) {
                found.push(toolchain);
            }
        }
    }
    found
}

/// A virtualenv is a directory with a `pyvenv.cfg` and a `bin/python` —
/// PEP 405's marker, and the one Zed's `is_virtualenv_dir` looks for.
fn virtualenv_at(venv: &Path, root: &Path) -> Option<Toolchain> {
    if !venv.join("pyvenv.cfg").is_file() {
        return None;
    }
    let python = ["python3", "python"]
        .into_iter()
        .map(|name| venv.join("bin").join(name))
        .find(|path| path.is_file())?;
    let label = venv
        .strip_prefix(root)
        .unwrap_or(venv)
        .to_string_lossy()
        .into_owned();
    Some(Toolchain {
        name: format!("Python ({label})"),
        path: python.to_string_lossy().into_owned(),
        language: "Python".to_owned(),
        source: label,
    })
}

/// One line of stdout from a guest command, trimmed. `None` when there is no
/// userland, the program is missing, or it failed — all of which mean "this
/// kind of toolchain is not available here", never an error to raise.
fn guest_line(userland: &guest::Userland, label: &str, argv: &[&str], root: &Path) -> Option<String> {
    let argv: Vec<OsString> = argv.iter().map(OsString::from).collect();
    let command = GuestCommand::new(label.to_owned(), argv).workdir(root);
    let output = guest::capture(userland, &command, DETECT_TIMEOUT)?;
    let text = String::from_utf8_lossy(&output);
    let line = text.lines().next()?.trim();
    (!line.is_empty()).then(|| line.to_owned())
}

fn guest_lines(userland: &guest::Userland, label: &str, argv: &[&str], root: &Path) -> Vec<String> {
    let argv: Vec<OsString> = argv.iter().map(OsString::from).collect();
    let command = GuestCommand::new(label.to_owned(), argv).workdir(root);
    let Some(output) = guest::capture(userland, &command, DETECT_TIMEOUT) else {
        return Vec::new();
    };
    String::from_utf8_lossy(&output)
        .lines()
        .map(|line| line.trim().to_owned())
        .filter(|line| !line.is_empty())
        .collect()
}

/// Everything the guest can tell us about Python: poetry's environment for
/// this project, and the interpreter on `PATH`.
fn guest_pythons(userland: &guest::Userland, root: &Path) -> Vec<Toolchain> {
    let mut found = Vec::new();
    if let Some(path) = guest_line(
        userland,
        "poetry env info",
        &["poetry", "env", "info", "-p"],
        root,
    ) {
        let python = Path::new(&path).join("bin").join("python");
        if !path.is_empty() {
            found.push(Toolchain {
                name: format!("Python (poetry: {})", short(&path)),
                path: python.to_string_lossy().into_owned(),
                language: "Python".to_owned(),
                source: "poetry".to_owned(),
            });
        }
    }
    if let Some(path) = guest_line(
        userland,
        "which python3",
        &["sh", "-lc", "command -v python3"],
        root,
    ) {
        found.push(Toolchain {
            name: "Python (system)".to_owned(),
            path,
            language: "Python".to_owned(),
            source: "system".to_owned(),
        });
    }
    found
}

/// rustup's toolchains, or the single `cargo` on `PATH` when there is no
/// rustup — which is what a Debian `apt install cargo` leaves behind.
fn guest_rusts(userland: &guest::Userland, root: &Path) -> Vec<Toolchain> {
    let mut found = Vec::new();
    for line in guest_lines(
        userland,
        "rustup toolchain list",
        &["rustup", "toolchain", "list"],
        root,
    ) {
        // "stable-aarch64-unknown-linux-gnu (default)" — the marker is not
        // part of the name rustup takes back.
        let name = line.split_whitespace().next().unwrap_or(&line).to_owned();
        if name == "no" {
            // "no installed toolchains" — rustup is there, nothing in it.
            continue;
        }
        let Some(home) = guest_line(
            userland,
            "rustup home",
            &["sh", "-lc", "echo ${RUSTUP_HOME:-$HOME/.rustup}"],
            root,
        ) else {
            continue;
        };
        let cargo = Path::new(&home)
            .join("toolchains")
            .join(&name)
            .join("bin")
            .join("cargo");
        found.push(Toolchain {
            name: format!("Rust ({name})"),
            path: cargo.to_string_lossy().into_owned(),
            language: "Rust".to_owned(),
            source: "rustup".to_owned(),
        });
    }
    if found.is_empty() {
        if let Some(path) = guest_line(
            userland,
            "which cargo",
            &["sh", "-lc", "command -v cargo"],
            root,
        ) {
            found.push(Toolchain {
                name: "Rust (system)".to_owned(),
                path,
                language: "Rust".to_owned(),
                source: "system".to_owned(),
            });
        }
    }
    found
}

/// The last two components of a path, for a label that has to fit a phone.
fn short(path: &str) -> String {
    let parts: Vec<&str> = path.rsplit('/').take(2).collect();
    parts.into_iter().rev().collect::<Vec<_>>().join("/")
}

impl Engine {
    /// Every toolchain this project could use, virtualenvs first — the order
    /// the picker shows, and the order [`Engine::toolchain_env`] would apply
    /// them in.
    ///
    /// **Blocking**: stats the project and runs up to four short programs in
    /// the userland. Call it off the Android main thread.
    pub fn toolchains(&self, project: ProjectId) -> Result<Vec<Toolchain>, EngineError> {
        let root = self
            .project_root(project)
            .ok_or(EngineError::UnknownProject(project))?;
        let mut found = virtualenvs(&root);
        if let Some(userland) = self.userland() {
            found.extend(guest_pythons(&userland, &root));
            found.extend(guest_rusts(&userland, &root));
        }
        // A path found twice — `.venv` that poetry also reported — is one
        // toolchain; the first name for it wins, which is the local one.
        let mut seen = std::collections::HashSet::new();
        found.retain(|toolchain| seen.insert(toolchain.path.clone()));
        Ok(found)
    }

    /// The toolchains in force for this project, one per language.
    pub fn active_toolchains(&self, project: ProjectId) -> Vec<Toolchain> {
        let Some(root) = self.project_root(project) else {
            return Vec::new();
        };
        read_choices()
            .projects
            .get(&root.to_string_lossy().into_owned())
            .map(|by_language| by_language.values().cloned().collect())
            .unwrap_or_default()
    }

    /// The toolchain in force for one language, or `None`.
    pub fn active_toolchain(&self, project: ProjectId, language: &str) -> Option<Toolchain> {
        let root = self.project_root(project)?;
        read_choices()
            .projects
            .get(&root.to_string_lossy().into_owned())?
            .get(language)
            .cloned()
    }

    /// Choose `toolchain` for its language in this project, or clear the
    /// language's choice when `toolchain` is `None`.
    ///
    /// Restarts the language servers, because a server already running has
    /// the old interpreter's environment and will not notice a new one — Zed
    /// restarts them for the same reason (`toolchain_store.rs`, on
    /// `ToolchainStoreEvent::ToolchainActivated`).
    ///
    /// **Blocking**: writes a small file.
    pub fn set_toolchain(
        &self,
        project: ProjectId,
        language: &str,
        toolchain: Option<Toolchain>,
    ) -> Result<(), EngineError> {
        let root = self
            .project_root(project)
            .ok_or(EngineError::UnknownProject(project))?;
        let key = root.to_string_lossy().into_owned();
        let mut choices = read_choices();
        let by_language = choices.projects.entry(key).or_default();
        match toolchain {
            Some(toolchain) => {
                by_language.insert(language.to_owned(), toolchain);
            }
            None => {
                by_language.remove(language);
            }
        }
        choices.projects.retain(|_, languages| !languages.is_empty());
        write_choices(&choices)?;
        self.restart_all_language_servers(project);
        Ok(())
    }

    /// The environment this project's toolchains contribute — `VIRTUAL_ENV`
    /// and a `PATH` prefix. Empty when nothing is chosen, which is what makes
    /// this safe to merge unconditionally.
    pub fn toolchain_env(&self, project: ProjectId) -> ToolchainEnv {
        ToolchainEnv::of(&self.active_toolchains(project))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn venv(root: &Path, name: &str) {
        let bin = root.join(name).join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        std::fs::write(root.join(name).join("pyvenv.cfg"), "home = /usr/bin\n").unwrap();
        std::fs::write(bin.join("python3"), "").unwrap();
    }

    #[test]
    fn a_virtualenv_is_a_pyvenv_cfg_next_to_a_bin_python() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        venv(root, ".venv");
        let found = virtualenvs(root);
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].language, "Python");
        assert_eq!(found[0].source, ".venv");
        assert!(found[0].path.ends_with("/.venv/bin/python3"));
    }

    /// A directory that looks like a virtualenv but has no marker is not one
    /// — `env/` is a common name for a plain configuration directory.
    #[test]
    fn a_directory_without_the_marker_is_not_a_virtualenv() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("env").join("bin")).unwrap();
        std::fs::write(dir.path().join("env").join("bin").join("python3"), "").unwrap();
        assert!(virtualenvs(dir.path()).is_empty());
    }

    #[test]
    fn a_virtualenv_one_directory_down_is_found() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("backend")).unwrap();
        venv(&dir.path().join("backend"), ".venv");
        let found = virtualenvs(dir.path());
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].source, "backend/.venv");
    }

    #[test]
    fn a_python_toolchain_exports_virtual_env_and_leads_the_path() {
        let toolchain = Toolchain {
            name: "Python (.venv)".to_owned(),
            path: "/p/app/.venv/bin/python3".to_owned(),
            language: "Python".to_owned(),
            source: ".venv".to_owned(),
        };
        let env = ToolchainEnv::of(std::slice::from_ref(&toolchain));
        let map: BTreeMap<&str, &str> = env.iter().collect();
        assert_eq!(map.get("VIRTUAL_ENV"), Some(&"/p/app/.venv"));
        let path = map.get("PATH").expect("a PATH");
        assert!(
            path.starts_with("/p/app/.venv/bin:"),
            "the interpreter must come first, got {path:?}"
        );
        // The guest's own PATH is still on the end — a toolchain adds, it
        // does not replace.
        assert!(path.contains("/usr/bin"));
    }

    /// A Rust toolchain is a `PATH` entry and nothing else: `VIRTUAL_ENV` is
    /// Python's, and exporting it for cargo would confuse every Python tool
    /// the task afterwards runs.
    #[test]
    fn a_rust_toolchain_contributes_no_virtual_env() {
        let toolchain = Toolchain {
            name: "Rust (stable)".to_owned(),
            path: "/root/.rustup/toolchains/stable/bin/cargo".to_owned(),
            language: "Rust".to_owned(),
            source: "rustup".to_owned(),
        };
        let env = ToolchainEnv::of(std::slice::from_ref(&toolchain));
        let map: BTreeMap<&str, &str> = env.iter().collect();
        assert!(!map.contains_key("VIRTUAL_ENV"));
        assert!(
            map.get("PATH")
                .is_some_and(|path| path.starts_with("/root/.rustup/toolchains/stable/bin:"))
        );
    }

    #[test]
    fn a_system_interpreter_is_a_path_entry_and_not_an_environment() {
        let toolchain = Toolchain {
            name: "Python (system)".to_owned(),
            path: "/usr/bin/python3".to_owned(),
            language: "Python".to_owned(),
            source: "system".to_owned(),
        };
        let env = ToolchainEnv::of(std::slice::from_ref(&toolchain));
        assert!(env.iter().all(|(key, _)| key != "VIRTUAL_ENV"));
    }

    #[test]
    fn nothing_chosen_contributes_nothing() {
        assert!(ToolchainEnv::of(&[]).is_empty());
    }

    #[test]
    fn a_choice_survives_a_round_trip_through_the_store() {
        let dir = tempfile::tempdir().unwrap();
        set_directory(dir.path().to_path_buf());
        let mut choices = Choices::default();
        choices.projects.entry("/p".to_owned()).or_default().insert(
            "Python".to_owned(),
            Toolchain {
                name: "Python (.venv)".to_owned(),
                path: "/p/.venv/bin/python3".to_owned(),
                language: "Python".to_owned(),
                source: ".venv".to_owned(),
            },
        );
        write_choices(&choices).unwrap();
        let read = read_choices();
        assert_eq!(
            read.projects["/p"]["Python"].path,
            "/p/.venv/bin/python3".to_owned()
        );
    }
}
