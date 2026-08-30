//! Tasks and runnables: Zed's task store, headless.
//!
//! A task is a shell command with a label, described by a [`TaskTemplate`]
//! from the vendored `task` crate — the very type Zed deserialises
//! `tasks.json` into — and resolved against the editor's state into a
//! concrete command line, working directory and environment. Three sources
//! feed the list, in the precedence Zed gives them
//! (crates/project/src/task_inventory.rs `list_tasks`, and
//! crates/editor/src/runnables.rs:813-822 for tag bindings):
//!
//! 1. the project's `.zed/tasks.json`;
//! 2. the user's global `tasks.json`, beside `settings.json`;
//! 3. the language's built-in templates, ported from the `ContextProvider`s
//!    in Zed's `crates/languages/src/<lang>.rs`.
//!
//! Both files are JSONC, read through the same lenient parser the settings
//! use, and read afresh on every request — they are a few hundred bytes, and
//! reading them is how they are "watched": an edit is live the next time the
//! picker opens, with no watcher thread to keep in step.
//!
//! A *runnable* is a place in a buffer the grammar's `runnables.scm` marks
//! with a tag — `rust-test` on a `#[test]` fn, `python-pytest-method` on a
//! `test_` def — and the tasks it offers are the templates carrying that tag.
//! The UI draws Zed's play button on those rows; the query itself runs in
//! `highlight.rs`, beside the outline it shares a tree with.
//!
//! Nothing here spawns a process: the resolved [`TaskSpec`] crosses the
//! bridge and the terminal dock runs it, because the terminal — the pty, the
//! userland, the tab — lives on the Kotlin side.

use std::collections::{BTreeMap, HashMap};
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use task::{
    HideStrategy, RevealStrategy, SaveStrategy, TaskContext, TaskTemplate, TaskVariables,
    VariableName,
};

use crate::{BufferId, Engine, EngineError, ProjectId, highlight};

pub use crate::highlight::RunnableRow;

/// Where a template came from — Zed's `TaskSourceKind`, in its precedence
/// order: a project's `.zed/tasks.json` beats the global file beats the
/// language's built-ins (task_inventory.rs `TaskSourceKind` derives `Ord` in
/// this order, and runnables.rs:811-822 keeps only the strongest source's
/// bindings for a tag).
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum TaskSource {
    /// Typed into the picker — Zed's oneshot task.
    UserInput,
    Project,
    Global,
    Language,
}

impl TaskSource {
    /// Zed's `to_id_base`: the prefix that keeps two sources' ids apart.
    fn id_base(self) -> &'static str {
        match self {
            TaskSource::UserInput => "oneshot",
            TaskSource::Project => "project",
            TaskSource::Global => "global",
            TaskSource::Language => "language",
        }
    }
}

/// What the UI tells the engine about the editor when it asks for tasks:
/// which buffer, where the caret is (0-based row, UTF-16 column), what is
/// selected, and — for the play button — the runnable the row carries.
/// Everything is optional: the picker opened from the terminal's tab bar
/// has no buffer at all, and still lists the project's and the user's tasks.
#[derive(Debug, Clone, Default, Deserialize)]
pub struct TaskEditorContext {
    #[serde(default)]
    pub buffer_id: Option<BufferId>,
    #[serde(default)]
    pub row: Option<u32>,
    #[serde(default)]
    pub column: Option<u32>,
    #[serde(default)]
    pub selected_text: Option<String>,
    #[serde(default)]
    pub runnable: Option<RunnableContext>,
}

/// One row's runnable, handed back from [`Engine::buffer_runnables`]: the
/// tags to bind and the named captures the language's templates read.
#[derive(Debug, Clone, Default, Deserialize)]
pub struct RunnableContext {
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default)]
    pub captures: HashMap<String, String>,
    #[serde(default)]
    pub run_text: String,
}

/// A task after resolution — Zed's `SpawnInTerminal` with the fields a
/// terminal tab needs, plus the source and tags the picker shows. `env`
/// already carries every `$ZED_*` variable, as Zed's does
/// (task_template.rs `resolve_task`: "set the task variables as environment
/// variables too").
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct TaskSpec {
    pub id: String,
    /// The label to display: long variables truncated, as Zed shows it.
    pub label: String,
    /// The label in full — what tab reuse is keyed on.
    pub full_label: String,
    pub command: String,
    pub args: Vec<String>,
    /// `command` and `args` joined — the line the shell runs.
    pub command_label: String,
    pub cwd: Option<String>,
    pub env: BTreeMap<String, String>,
    pub use_new_terminal: bool,
    pub allow_concurrent_runs: bool,
    pub reveal: &'static str,
    pub hide: &'static str,
    /// Which edited buffers the UI saves before the run — `all`, `current`
    /// or `none`; Zed's `SaveStrategy`, honoured by the workspace since the
    /// buffers' tabs are its (workspace/src/tasks.rs `save_for_task`).
    pub save: &'static str,
    pub show_command: bool,
    pub show_summary: bool,
    pub source: TaskSource,
    pub tags: Vec<String>,
}

// ---------------------------------------------------------------------------
// tasks.json
// ---------------------------------------------------------------------------

/// The templates in a `tasks.json`, read the way `settings.json` is: JSONC,
/// and lenient *per entry* — one malformed task costs itself and a warning,
/// never the rest of the file. Zed refuses the whole file
/// (static_source.rs `TrackedFile`); on a phone, where the file is edited
/// in the app's own editor and the error would be a logcat line nobody sees,
/// a list that shrinks by one is the kinder failure, and it is the rule the
/// settings' `agent_servers` already follows (config.rs).
pub fn parse_tasks_json(text: &str, what: &str) -> Vec<TaskTemplate> {
    if text.trim().is_empty() {
        return Vec::new();
    }
    let value = match settings_json::parse_json_with_comments::<serde_json::Value>(text) {
        Ok(value) => value,
        Err(err) => {
            log::warn!("{what}: not valid JSON, ignored: {err}");
            return Vec::new();
        }
    };
    let serde_json::Value::Array(entries) = value else {
        log::warn!("{what}: expected an array of tasks, ignored");
        return Vec::new();
    };
    entries
        .into_iter()
        .enumerate()
        .filter_map(|(index, entry)| match serde_json::from_value::<TaskTemplate>(entry) {
            Ok(template) if template.label.trim().is_empty() || template.command.trim().is_empty() => {
                log::warn!("{what}: task {index} has no label or command; dropped");
                None
            }
            Ok(template) => Some(template),
            Err(err) => {
                log::warn!("{what}: task {index} is malformed ({err}); dropped");
                None
            }
        })
        .collect()
}

fn read_templates(path: &Path) -> Vec<TaskTemplate> {
    match std::fs::read_to_string(path) {
        Ok(text) => parse_tasks_json(&text, &path.display().to_string()),
        Err(_) => Vec::new(),
    }
}

// ---------------------------------------------------------------------------
// Context
// ---------------------------------------------------------------------------

/// Where the request came from, gathered under the buffer lock and released
/// before any file is read.
#[derive(Debug, Default)]
struct Location {
    path: Option<PathBuf>,
    grammar: Option<&'static str>,
    /// Zed's `$ZED_SYMBOL`: the innermost outline symbol's name at the caret.
    symbol: Option<String>,
}

/// Zed's `BasicContextProvider` (task_inventory.rs:1018-1120): the
/// variables every language gets, from the location alone.
fn basic_variables(
    root: &Path,
    location: &Location,
    ctx: &TaskEditorContext,
) -> TaskVariables {
    let mut variables = TaskVariables::default();
    // Zed reports both 1-based (task_inventory.rs:1040-1041).
    variables.insert(VariableName::Row, (ctx.row.unwrap_or(0) + 1).to_string());
    variables.insert(VariableName::Column, (ctx.column.unwrap_or(0) + 1).to_string());
    if let Some(symbol) = &location.symbol {
        variables.insert(VariableName::Symbol, symbol.clone());
    }
    if let Some(selected) = ctx.selected_text.as_deref()
        && !selected.trim().is_empty()
    {
        variables.insert(VariableName::SelectedText, selected.to_owned());
    }
    variables.insert(
        VariableName::WorktreeRoot,
        root.to_string_lossy().into_owned(),
    );
    if let Some(path) = &location.path {
        variables.insert(VariableName::File, path.to_string_lossy().into_owned());
        if let Some(name) = path.file_name().and_then(|n| n.to_str()) {
            variables.insert(VariableName::Filename, name.to_owned());
        }
        if let Some(stem) = path.file_stem().and_then(|s| s.to_str()) {
            variables.insert(VariableName::Stem, stem.to_owned());
        }
        if let Some(dir) = path.parent() {
            variables.insert(VariableName::Dirname, dir.to_string_lossy().into_owned());
        }
        if let Ok(relative) = path.strip_prefix(root) {
            variables.insert(
                VariableName::RelativeFile,
                relative.to_string_lossy().into_owned(),
            );
            let relative_dir = relative
                .parent()
                .map(|dir| dir.to_string_lossy().into_owned())
                .filter(|dir| !dir.is_empty())
                .unwrap_or_else(|| ".".to_owned());
            variables.insert(VariableName::RelativeDir, relative_dir);
        }
    }
    if let Some(grammar) = location.grammar {
        variables.insert(
            VariableName::Language,
            highlight::language_display_name(grammar),
        );
    }
    if let Some(runnable) = &ctx.runnable {
        // The `@run` capture's text, and every named extra under its own
        // name — what Zed's editor feeds `task_context_for_location`
        // (runnables.rs:520-548).
        if !runnable.run_text.is_empty() {
            variables.insert(VariableName::RunnableSymbol, runnable.run_text.clone());
        }
        for (name, value) in &runnable.captures {
            variables.insert(VariableName::Custom(name.clone().into()), value.clone());
        }
    }
    variables
}

fn custom(name: &'static str) -> VariableName {
    VariableName::Custom(name.into())
}

/// `$ZED_CUSTOM_<name>` for a template string.
fn var(name: &'static str) -> String {
    custom(name).template_value()
}

/// The named extra captures Zed's queries use; underscored, and swept out
/// of the environment before the task runs (`TaskVariables::sweep`).
fn capture<'a>(variables: &'a TaskVariables, name: &'static str) -> Option<&'a str> {
    variables.get(&VariableName::Custom(name.into()))
}

// --- Rust --------------------------------------------------------------------

/// Zed's `RustContextProvider::build_context` (rust.rs:963-1037), minus
/// `cargo metadata`, which would mean a cargo process inside the userland
/// for every picker open. The package name is read off the nearest
/// `Cargo.toml` instead, and the binary target is inferred from the file's
/// place in the crate layout, which is what `cargo metadata` would have said
/// for every crate that follows the conventions.
fn rust_variables(variables: &mut TaskVariables, location: &Location) {
    let Some(path) = &location.path else {
        return;
    };
    let manifest_dir = path
        .ancestors()
        .skip(1)
        .find(|dir| dir.join("Cargo.toml").is_file());
    if let Some(dir) = manifest_dir {
        variables.insert(
            custom("RUST_MANIFEST_DIRNAME"),
            dir.to_string_lossy().into_owned(),
        );
        let package = std::fs::read_to_string(dir.join("Cargo.toml"))
            .ok()
            .and_then(|toml| cargo_package_name(&toml));
        if let Some(package) = &package {
            variables.insert(custom("RUST_PACKAGE"), package.clone());
        }
        if let Some((kind, name)) = rust_bin_target(dir, path, package.as_deref()) {
            variables.insert(custom("RUST_BIN_KIND"), kind.to_owned());
            variables.insert(custom("RUST_BIN_NAME"), name);
            // No required features without metadata: both empty, as Zed
            // sets them for a target with none (rust.rs:1013-1015).
            variables.insert(custom("RUST_BIN_REQUIRED_FEATURES_FLAG"), String::new());
            variables.insert(custom("RUST_BIN_REQUIRED_FEATURES"), String::new());
        }
    }
    if let Some(stem) = variables.get(&VariableName::Stem).map(str::to_owned) {
        let fragment = rust_test_fragment(variables, path, &stem);
        variables.insert(custom("RUST_TEST_FRAGMENT"), fragment);
    }
    if let Some(name) = capture(variables, "_test_name").map(str::to_owned) {
        variables.insert(custom("RUST_TEST_NAME"), name);
    }
    if let Some(name) = capture(variables, "_doc_test_name").map(str::to_owned) {
        variables.insert(custom("RUST_DOC_TEST_NAME"), name);
    }
}

/// `[package] name = "…"` from a manifest, without a TOML parser: the key
/// sits on its own line in every manifest cargo itself writes. None for a
/// virtual workspace root.
pub(crate) fn cargo_package_name(manifest: &str) -> Option<String> {
    let mut in_package = false;
    for line in manifest.lines() {
        let line = line.trim();
        if line.starts_with('[') {
            in_package = line == "[package]";
            continue;
        }
        if !in_package {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        if key.trim() == "name" {
            let value = value.trim().split('#').next().unwrap_or("").trim();
            return Some(value.trim_matches(['"', '\'']).to_owned());
        }
    }
    None
}

/// The binary target a file belongs to, from cargo's layout conventions:
/// `src/main.rs` is the package's own bin, `src/bin/<name>.rs` and
/// `src/bin/<name>/main.rs` are named bins, `examples/<name>.rs` an example.
fn rust_bin_target(
    manifest_dir: &Path,
    path: &Path,
    package: Option<&str>,
) -> Option<(&'static str, String)> {
    let relative = path.strip_prefix(manifest_dir).ok()?;
    let mut parts = relative.iter().filter_map(|part| part.to_str());
    let first = parts.next()?;
    let second = parts.next()?;
    let third = parts.next();
    let stem = |name: &str| name.strip_suffix(".rs").map(str::to_owned);
    match (first, second, third) {
        ("src", "main.rs", None) => Some(("bin", package?.to_owned())),
        ("src", "bin", Some(name)) => match parts.next() {
            None => Some(("bin", stem(name)?)),
            Some("main.rs") => Some(("bin", name.to_owned())),
            Some(_) => None,
        },
        ("examples", name, None) => Some(("example", stem(name)?)),
        ("examples", name, Some("main.rs")) => Some(("example", name.to_owned())),
        _ => None,
    }
}

/// Zed's `test_fragment` (rust.rs:1449-1470): what `cargo test -- <this>`
/// filters on for "test this module".
fn rust_test_fragment(variables: &TaskVariables, path: &Path, stem: &str) -> String {
    let fragment = if stem == "lib" {
        Some("--lib".to_owned())
    } else if stem == "mod" {
        path.parent()
            .and_then(|dir| dir.file_name())
            .map(|name| name.to_string_lossy().into_owned())
    } else if stem == "main" {
        match (
            variables.get(&custom("RUST_BIN_NAME")),
            variables.get(&custom("RUST_BIN_KIND")),
        ) {
            (Some(name), Some(kind)) => Some(format!("--{kind}={name}")),
            _ => None,
        }
    } else {
        Some(stem.to_owned())
    };
    fragment.unwrap_or_else(|| "--".to_owned())
}

/// Zed's Rust templates (rust.rs:1040-1200), verbatim when the package name
/// is known. Without one — a file outside any `Cargo.toml`, or a virtual
/// workspace root — the `-p` forms cannot resolve, so the package-less
/// twins stand in; Zed shows nothing at all in that case.
fn rust_templates(has_package: bool) -> Vec<TaskTemplate> {
    let manifest = var("RUST_MANIFEST_DIRNAME");
    let package = var("RUST_PACKAGE");
    let suffix = |base: &str| {
        if has_package {
            format!("{base} (package: {package})")
        } else {
            base.to_owned()
        }
    };
    let with_package = |mut args: Vec<String>| {
        if has_package {
            args.insert(1, "-p".to_owned());
            args.insert(2, package.clone());
        }
        args
    };
    let s = |value: &str| value.to_owned();
    vec![
        TaskTemplate {
            label: suffix("Check"),
            command: s("cargo"),
            args: with_package(vec![s("check")]),
            cwd: Some(VariableName::Dirname.template_value()),
            ..TaskTemplate::default()
        },
        TaskTemplate {
            label: s("Check all targets (workspace)"),
            command: s("cargo"),
            args: vec![s("check"), s("--workspace"), s("--all-targets")],
            cwd: Some(VariableName::Dirname.template_value()),
            ..TaskTemplate::default()
        },
        TaskTemplate {
            label: suffix(&format!("Test '{}'", var("RUST_TEST_NAME"))),
            command: s("cargo"),
            args: with_package(vec![
                s("test"),
                s("--"),
                s("--nocapture"),
                s("--include-ignored"),
                var("RUST_TEST_NAME"),
            ]),
            tags: vec![s("rust-test")],
            cwd: Some(manifest.clone()),
            ..TaskTemplate::default()
        },
        TaskTemplate {
            label: suffix(&format!("Doc test '{}'", var("RUST_DOC_TEST_NAME"))),
            command: s("cargo"),
            args: with_package(vec![
                s("test"),
                s("--doc"),
                s("--"),
                s("--nocapture"),
                s("--include-ignored"),
                var("RUST_DOC_TEST_NAME"),
            ]),
            tags: vec![s("rust-doc-test")],
            cwd: Some(manifest.clone()),
            ..TaskTemplate::default()
        },
        TaskTemplate {
            label: suffix(&format!("Test mod '{}'", VariableName::Stem.template_value())),
            command: s("cargo"),
            args: with_package(vec![s("test"), s("--"), var("RUST_TEST_FRAGMENT")]),
            tags: vec![s("rust-mod-test")],
            cwd: Some(manifest.clone()),
            ..TaskTemplate::default()
        },
        TaskTemplate {
            label: suffix(&format!(
                "Run {} {}",
                var("RUST_BIN_KIND"),
                var("RUST_BIN_NAME")
            )),
            command: s("cargo"),
            args: with_package(vec![
                s("run"),
                format!("--{}", var("RUST_BIN_KIND")),
                var("RUST_BIN_NAME"),
                var("RUST_BIN_REQUIRED_FEATURES_FLAG"),
                var("RUST_BIN_REQUIRED_FEATURES"),
            ]),
            cwd: Some(manifest.clone()),
            tags: vec![s("rust-main")],
            ..TaskTemplate::default()
        },
        TaskTemplate {
            label: suffix("Test"),
            command: s("cargo"),
            args: with_package(vec![s("test")]),
            cwd: Some(manifest.clone()),
            ..TaskTemplate::default()
        },
        TaskTemplate {
            label: s("Run"),
            command: s("cargo"),
            args: vec![s("run")],
            cwd: Some(manifest.clone()),
            ..TaskTemplate::default()
        },
        TaskTemplate {
            label: s("Clean"),
            command: s("cargo"),
            args: vec![s("clean")],
            cwd: Some(manifest),
            ..TaskTemplate::default()
        },
    ]
}

// --- Python ------------------------------------------------------------------

/// Zed's `PythonContextProvider::build_context` (python.rs:887-1130). The
/// toolchain is `python3` — Zed's own fallback when no toolchain store
/// answers (python.rs:920) — and the test target is built for whichever
/// runner the runnable's captures name, so a `TestCase` method and a
/// pytest function both run without a `TEST_RUNNER` setting to pick one.
fn python_variables(variables: &mut TaskVariables) {
    variables.insert(custom("PYTHON_ACTIVE_ZED_TOOLCHAIN"), "python3".to_owned());
    let relative = variables.get(&VariableName::RelativeFile).map(str::to_owned);
    let module = relative
        .as_deref()
        .map(python_module_name)
        .unwrap_or_default();
    variables.insert(custom("PYTHON_MODULE_NAME"), module.clone());

    let unittest_class = capture(variables, "_unittest_class_name").map(str::to_owned);
    let unittest_method = capture(variables, "_unittest_method_name").map(str::to_owned);
    let pytest_class = capture(variables, "_pytest_class_name").map(str::to_owned);
    let pytest_method = capture(variables, "_pytest_method_name").map(str::to_owned);

    // `build_unittest_target` (python.rs:1059-1087).
    if let Some(class) = &unittest_class
        && relative.is_some()
    {
        let target = match &unittest_method {
            Some(method) => format!("{module}.{class}.{method}"),
            None => format!("{module}.{class}"),
        };
        variables.insert(custom("PYTHON_UNITTEST_TARGET"), target);
    }
    // `build_pytest_target` (python.rs:1089-1115).
    if let Some(file) = &relative {
        let target = match (&pytest_class, &pytest_method) {
            (Some(class), Some(method)) => format!("{file}::{class}::{method}"),
            (Some(class), None) => format!("{file}::{class}"),
            (None, Some(method)) => format!("{file}::{method}"),
            (None, None) => file.clone(),
        };
        variables.insert(custom("PYTHON_TEST_TARGET"), target);
    }
}

/// `python_module_name_from_relative_path` (python.rs:1132-1141).
fn python_module_name(relative: &str) -> String {
    let dotted = relative.replace(['/', '\\'], ".");
    dotted
        .strip_suffix(".py")
        .map(str::to_owned)
        .unwrap_or(dotted)
}

fn python_templates() -> Vec<TaskTemplate> {
    let python = var("PYTHON_ACTIVE_ZED_TOOLCHAIN");
    let root = Some(VariableName::WorktreeRoot.template_value());
    let s = |value: &str| value.to_owned();
    vec![
        TaskTemplate {
            label: s("execute selection"),
            command: python.clone(),
            args: vec![
                s("-c"),
                VariableName::SelectedText.template_value_with_whitespace(),
            ],
            cwd: root.clone(),
            ..TaskTemplate::default()
        },
        TaskTemplate {
            label: format!("run '{}'", VariableName::File.template_value()),
            command: python.clone(),
            args: vec![VariableName::File.template_value_with_whitespace()],
            cwd: root.clone(),
            ..TaskTemplate::default()
        },
        TaskTemplate {
            label: format!("run module '{}'", VariableName::File.template_value()),
            command: python.clone(),
            args: vec![s("-m"), var("PYTHON_MODULE_NAME")],
            cwd: root.clone(),
            tags: vec![s("python-module-main-method")],
            ..TaskTemplate::default()
        },
        TaskTemplate {
            label: format!("pytest '{}'", VariableName::File.template_value()),
            command: python.clone(),
            args: vec![
                s("-m"),
                s("pytest"),
                VariableName::File.template_value_with_whitespace(),
            ],
            cwd: root.clone(),
            ..TaskTemplate::default()
        },
        TaskTemplate {
            label: format!("pytest {}", var("PYTHON_TEST_TARGET")),
            command: python.clone(),
            args: vec![
                s("-m"),
                s("pytest"),
                custom("PYTHON_TEST_TARGET").template_value_with_whitespace(),
            ],
            cwd: root.clone(),
            tags: vec![s("python-pytest-class"), s("python-pytest-method")],
            ..TaskTemplate::default()
        },
        // Zed offers these only under `TEST_RUNNER = "UNITTEST"`; here they
        // are bound to the unittest tags alone, so a `TestCase` method's
        // play button has something to run while the picker's untagged
        // rows stay pytest's.
        TaskTemplate {
            label: format!("unittest {}", var("PYTHON_UNITTEST_TARGET")),
            command: python,
            args: vec![
                s("-m"),
                s("unittest"),
                custom("PYTHON_UNITTEST_TARGET").template_value_with_whitespace(),
            ],
            cwd: root,
            tags: vec![s("python-unittest-class"), s("python-unittest-method")],
            ..TaskTemplate::default()
        },
    ]
}

// --- Go ----------------------------------------------------------------------

/// Zed's `GoContextProvider::build_context` (go.rs:647-728).
fn go_variables(variables: &mut TaskVariables, root: &Path, location: &Location) {
    let Some(dir) = location.path.as_deref().and_then(Path::parent) else {
        return;
    };
    let package = match dir.strip_prefix(root) {
        Ok(relative) if relative.as_os_str().is_empty() => ".".to_owned(),
        Ok(relative) => format!("./{}", relative.to_string_lossy()),
        Err(_) => dir.to_string_lossy().into_owned(),
    };
    variables.insert(custom("GO_PACKAGE"), package);
    let module_root = dir
        .ancestors()
        .find(|dir| dir.join("go.mod").is_file())
        .map(|dir| dir.to_string_lossy().into_owned())
        .unwrap_or_else(|| ".".to_owned());
    variables.insert(custom("GO_MODULE_ROOT"), module_root);
    for (from, to) in [
        ("_subtest_name", "GO_SUBTEST_NAME"),
        ("_table_test_case_name", "GO_TABLE_TEST_CASE_NAME"),
        ("_suite_name", "GO_SUITE_NAME"),
    ] {
        if let Some(name) = capture(variables, from).and_then(go_subtest_name) {
            variables.insert(custom(to), name);
        }
    }
}

/// Zed's `extract_subtest_name` (go.rs:920-935): the literal without its
/// quotes, spaces as underscores the way `go test -run` wants them.
fn go_subtest_name(input: &str) -> Option<String> {
    let content = if input.starts_with('`') && input.ends_with('`') {
        input.trim_matches('`')
    } else {
        input.trim_matches('"')
    };
    let processed: String = content
        .chars()
        .map(|c| if c.is_whitespace() { '_' } else { c })
        .collect();
    Some(processed)
}

fn go_templates() -> Vec<TaskTemplate> {
    let package = var("GO_PACKAGE");
    let symbol = VariableName::Symbol.template_value();
    let package_cwd = Some(VariableName::Dirname.template_value());
    let module_cwd = Some(var("GO_MODULE_ROOT"));
    let s = |value: &str| value.to_owned();
    let go_test = |label: String, args: Vec<String>, tags: &[&str]| TaskTemplate {
        label,
        command: s("go"),
        args,
        cwd: package_cwd.clone(),
        tags: tags.iter().map(|tag| s(tag)).collect(),
        ..TaskTemplate::default()
    };
    vec![
        go_test(
            format!(
                "go test {package} -v -run Test{}/{symbol}",
                var("GO_SUITE_NAME")
            ),
            vec![
                s("test"),
                s("-v"),
                s("-run"),
                format!("\\^Test{}\\$/\\^{symbol}\\$", var("GO_SUITE_NAME")),
            ],
            &["go-testify-suite"],
        ),
        go_test(
            format!(
                "go test {package} -v -run {symbol}/{}",
                var("GO_TABLE_TEST_CASE_NAME")
            ),
            vec![
                s("test"),
                s("-v"),
                s("-run"),
                format!("\\^{symbol}\\$/\\^{}\\$", var("GO_TABLE_TEST_CASE_NAME")),
            ],
            &[
                "go-table-test-case",
                "go-table-test-case-without-explicit-variable",
            ],
        ),
        go_test(
            format!("go test {package} -run {symbol}"),
            vec![s("test"), s("-run"), format!("\\^{symbol}\\$")],
            &["go-test", "go-example"],
        ),
        go_test(format!("go test {package}"), vec![s("test")], &[]),
        TaskTemplate {
            label: s("go test ./..."),
            command: s("go"),
            args: vec![s("test"), s("./...")],
            cwd: module_cwd,
            ..TaskTemplate::default()
        },
        go_test(
            format!(
                "go test {package} -v -run {symbol}/{}",
                var("GO_SUBTEST_NAME")
            ),
            vec![
                s("test"),
                s("-v"),
                s("-run"),
                format!("\\^{symbol}\\$/\\^{}\\$", var("GO_SUBTEST_NAME")),
            ],
            &["go-subtest"],
        ),
        go_test(
            format!("go test {package} -bench {symbol}"),
            vec![
                s("test"),
                s("-benchmem"),
                s("-run='^$'"),
                s("-bench"),
                format!("\\^{symbol}\\$"),
            ],
            &["go-benchmark"],
        ),
        go_test(
            format!("go test {package} -fuzz=Fuzz -run {symbol}"),
            vec![
                s("test"),
                s("-fuzz=Fuzz"),
                s("-run"),
                format!("\\^{symbol}\\$"),
            ],
            &["go-fuzz"],
        ),
        go_test(
            format!("go run {package}"),
            vec![s("run"), s(".")],
            &["go-main"],
        ),
        go_test(
            format!("go generate {package}"),
            vec![s("generate")],
            &["go-generate"],
        ),
    ]
}

// --- JavaScript / TypeScript -------------------------------------------------

/// What Zed's `PackageJsonData` reads (typescript.rs): the package manager,
/// which test runners are dependencies, and the scripts.
#[derive(Debug, Default, PartialEq, Eq)]
pub(crate) struct PackageJson {
    pub manager: String,
    pub jest: bool,
    pub vitest: bool,
    pub mocha: bool,
    pub scripts: Vec<String>,
}

/// The `package.json` nearest the file, walking up to the project root —
/// Zed's `combined_package_json_data`, which merges every one on the way up;
/// the nearest one is what matters for scripts and runners.
fn find_package_json(root: &Path, path: Option<&Path>) -> Option<(PathBuf, PackageJson)> {
    let start = path.and_then(Path::parent).unwrap_or(root);
    for dir in start.ancestors() {
        let file = dir.join("package.json");
        if let Ok(text) = std::fs::read_to_string(&file) {
            let mut data = parse_package_json(&text);
            data.manager = detect_package_manager(dir, &data.manager);
            return Some((dir.to_path_buf(), data));
        }
        if dir == root {
            break;
        }
    }
    None
}

pub(crate) fn parse_package_json(text: &str) -> PackageJson {
    let Ok(value) = serde_json::from_str::<serde_json::Value>(text) else {
        return PackageJson::default();
    };
    let has_dep = |name: &str| {
        ["dependencies", "devDependencies"]
            .iter()
            .any(|key| value.get(key).and_then(|deps| deps.get(name)).is_some())
    };
    PackageJson {
        manager: value
            .get("packageManager")
            .and_then(|m| m.as_str())
            .map(|m| m.split('@').next().unwrap_or(m).to_owned())
            .unwrap_or_default(),
        jest: has_dep("jest"),
        vitest: has_dep("vitest"),
        mocha: has_dep("mocha"),
        scripts: value
            .get("scripts")
            .and_then(|s| s.as_object())
            .map(|s| s.keys().cloned().collect())
            .unwrap_or_default(),
    }
}

/// Zed's `detect_package_manager` (typescript.rs:405-420): the manifest's
/// own field, else the lockfile, else npm.
fn detect_package_manager(dir: &Path, declared: &str) -> String {
    if !declared.is_empty() {
        return declared.to_owned();
    }
    if dir.join("pnpm-lock.yaml").is_file() {
        "pnpm".to_owned()
    } else if dir.join("yarn.lock").is_file() {
        "yarn".to_owned()
    } else {
        "npm".to_owned()
    }
}

/// Zed's `replace_test_name_parameters` (typescript.rs:598-602): a jest
/// `%s` or `$name` placeholder in a test title becomes a wildcard in the
/// name pattern, and everything else is escaped for the regex it becomes.
pub(crate) fn js_test_name_pattern(name: &str) -> String {
    let pattern = regex::Regex::new(r"(\$([A-Za-z0-9_\.]+|[\#])|%[psdifjo#\$%])").unwrap();
    pattern
        .split(name)
        .map(regex::escape)
        .collect::<Vec<_>>()
        .join("(.+?)")
}

fn javascript_variables(variables: &mut TaskVariables, root: &Path, location: &Location) {
    if let Some(symbol) = variables.get(&VariableName::Symbol).map(str::to_owned) {
        variables.insert(
            custom("TYPESCRIPT_JEST_TEST_NAME"),
            js_test_name_pattern(&symbol),
        );
    }
    let Some((dir, package)) = find_package_json(root, location.path.as_deref()) else {
        variables.insert(custom("TYPESCRIPT_RUNNER"), "npm".to_owned());
        return;
    };
    variables.insert(custom("TYPESCRIPT_RUNNER"), package.manager);
    variables.insert(
        custom("TYPESCRIPT_PACKAGE_PATH"),
        dir.to_string_lossy().into_owned(),
    );
}

/// The JS/TS templates (typescript.rs:70-260), keyed on which runner the
/// nearest `package.json` declares, plus one `run <script>` per script in
/// it — the row the task asks for, and what Zed's json provider lists for
/// the manifest itself (json.rs:79-96).
fn javascript_templates(root: &Path, location: &Location) -> Vec<TaskTemplate> {
    let runner = var("TYPESCRIPT_RUNNER");
    let package_path = Some(var("TYPESCRIPT_PACKAGE_PATH"));
    let file = VariableName::File.template_value();
    let symbol = VariableName::Symbol.template_value();
    let test_tags = || vec!["ts-test".to_owned(), "js-test".to_owned(), "tsx-test".to_owned()];
    let s = |value: &str| value.to_owned();
    let mut templates = vec![TaskTemplate {
        label: format!(
            "execute selection {}",
            VariableName::SelectedText.template_value()
        ),
        command: s("node"),
        args: vec![
            s("-e"),
            format!("\"{}\"", VariableName::SelectedText.template_value()),
        ],
        ..TaskTemplate::default()
    }];
    let Some((_, package)) = find_package_json(root, location.path.as_deref()) else {
        return templates;
    };
    if package.jest {
        templates.push(TaskTemplate {
            label: s("jest file test"),
            command: runner.clone(),
            args: vec![s("exec"), s("--"), s("jest"), s("--runInBand"), file.clone()],
            cwd: package_path.clone(),
            ..TaskTemplate::default()
        });
        templates.push(TaskTemplate {
            label: format!("jest test {symbol}"),
            command: runner.clone(),
            args: vec![
                s("exec"),
                s("--"),
                s("jest"),
                s("--runInBand"),
                s("--testNamePattern"),
                format!("\"{}\"", var("TYPESCRIPT_JEST_TEST_NAME")),
                file.clone(),
            ],
            tags: test_tags(),
            cwd: package_path.clone(),
            ..TaskTemplate::default()
        });
    }
    if package.vitest {
        templates.push(TaskTemplate {
            label: s("vitest file test"),
            command: runner.clone(),
            args: vec![
                s("exec"),
                s("--"),
                s("vitest"),
                s("run"),
                s("--no-file-parallelism"),
                file.clone(),
            ],
            cwd: package_path.clone(),
            ..TaskTemplate::default()
        });
        templates.push(TaskTemplate {
            label: format!("vitest test {symbol}"),
            command: runner.clone(),
            args: vec![
                s("exec"),
                s("--"),
                s("vitest"),
                s("run"),
                s("--no-file-parallelism"),
                s("--testNamePattern"),
                format!("\"{}\"", var("TYPESCRIPT_JEST_TEST_NAME")),
                file.clone(),
            ],
            tags: test_tags(),
            cwd: package_path.clone(),
            ..TaskTemplate::default()
        });
    }
    if package.mocha {
        templates.push(TaskTemplate {
            label: s("mocha file test"),
            command: runner.clone(),
            args: vec![s("exec"), s("--"), s("mocha"), file.clone()],
            cwd: package_path.clone(),
            ..TaskTemplate::default()
        });
        templates.push(TaskTemplate {
            label: format!("mocha test {symbol}"),
            command: runner.clone(),
            args: vec![
                s("exec"),
                s("--"),
                s("mocha"),
                s("--grep"),
                format!("\"{symbol}\""),
                file.clone(),
            ],
            tags: test_tags(),
            cwd: package_path.clone(),
            ..TaskTemplate::default()
        });
    }
    if !package.jest && !package.vitest && !package.mocha {
        // Node's own runner, Zed's fallback when no test package is
        // installed (typescript.rs `node_package_path`).
        templates.push(TaskTemplate {
            label: format!("node test {symbol}"),
            command: s("node"),
            args: vec![
                s("--test"),
                format!("--test-name-pattern=\"{}\"", var("TYPESCRIPT_JEST_TEST_NAME")),
                file,
            ],
            tags: test_tags(),
            cwd: package_path.clone(),
            ..TaskTemplate::default()
        });
    }
    for script in package.scripts {
        templates.push(TaskTemplate {
            label: format!("{runner} run {script}"),
            command: runner.clone(),
            args: vec![s("run"), script],
            cwd: package_path.clone(),
            ..TaskTemplate::default()
        });
    }
    templates.push(TaskTemplate {
        label: format!("package script {}", var("script")),
        command: runner,
        args: vec![s("run"), var("script")],
        cwd: package_path,
        tags: vec![s("package-script")],
        ..TaskTemplate::default()
    });
    templates
}

// --- Bash --------------------------------------------------------------------

/// Zed's `bash_task_context` (bash.rs:15-29).
fn bash_templates() -> Vec<TaskTemplate> {
    vec![
        TaskTemplate {
            label: "execute selection".to_owned(),
            command: VariableName::SelectedText.template_value(),
            ..TaskTemplate::default()
        },
        TaskTemplate {
            label: format!("run '{}'", VariableName::File.template_value()),
            command: VariableName::File.template_value(),
            tags: vec!["bash-script".to_owned()],
            ..TaskTemplate::default()
        },
    ]
}

/// The language's variables and templates, by grammar. JavaScript files
/// parse with the `tsx` grammar (highlight.rs `EXTRA_CONFIGS`), so the
/// TypeScript provider serves both, as it does in Zed. A `package.json`
/// buffer gets the script tasks too — that is where the `package-script`
/// runnable lives.
fn language_tasks(
    grammar: Option<&str>,
    variables: &mut TaskVariables,
    root: &Path,
    location: &Location,
) -> Vec<TaskTemplate> {
    match grammar {
        Some("rust") => {
            rust_variables(variables, location);
            rust_templates(variables.get(&custom("RUST_PACKAGE")).is_some())
        }
        Some("python") => {
            python_variables(variables);
            python_templates()
        }
        Some("go") => {
            go_variables(variables, root, location);
            go_templates()
        }
        Some("tsx" | "typescript" | "javascript") => {
            javascript_variables(variables, root, location);
            javascript_templates(root, location)
        }
        Some("json")
            if location
                .path
                .as_deref()
                .and_then(Path::file_name)
                .is_some_and(|name| name == "package.json") =>
        {
            javascript_variables(variables, root, location);
            javascript_templates(root, location)
        }
        Some("bash") => bash_templates(),
        _ => Vec::new(),
    }
}

// ---------------------------------------------------------------------------
// Resolution
// ---------------------------------------------------------------------------

fn reveal_name(reveal: RevealStrategy) -> &'static str {
    match reveal {
        RevealStrategy::Always => "always",
        RevealStrategy::NoFocus => "no_focus",
        RevealStrategy::Never => "never",
    }
}

fn hide_name(hide: HideStrategy) -> &'static str {
    match hide {
        HideStrategy::Never => "never",
        HideStrategy::Always => "always",
        HideStrategy::OnSuccess => "on_success",
    }
}

fn save_name(save: SaveStrategy) -> &'static str {
    match save {
        SaveStrategy::All => "all",
        SaveStrategy::Current => "current",
        SaveStrategy::None => "none",
    }
}

/// Resolve one template against the context — `TaskTemplate::resolve_task`
/// from the vendored crate, which is where `$ZED_*` substitution, label
/// truncation and the "a task naming an absent variable is filtered out"
/// rule all live (task_template.rs:163-296). None when it does not resolve.
pub fn resolve(template: &TaskTemplate, source: TaskSource, context: &TaskContext) -> Option<TaskSpec> {
    let resolved = template.resolve_task(source.id_base(), context)?;
    let spawn = resolved.resolved;
    Some(TaskSpec {
        id: spawn.id.0,
        label: spawn.label,
        full_label: spawn.full_label,
        command: spawn.command.unwrap_or_default(),
        args: spawn.args,
        command_label: spawn.command_label,
        cwd: spawn.cwd.map(|cwd| cwd.to_string_lossy().into_owned()),
        env: spawn.env.into_iter().collect(),
        use_new_terminal: spawn.use_new_terminal,
        allow_concurrent_runs: spawn.allow_concurrent_runs,
        reveal: reveal_name(spawn.reveal),
        hide: hide_name(spawn.hide),
        save: save_name(spawn.save),
        show_command: spawn.show_command,
        show_summary: spawn.show_summary,
        source,
        tags: template.tags.clone(),
    })
}

/// Zed's tag binding (runnables.rs:790-826): every template carrying one of
/// the runnable's tags, from every source, and then only the strongest
/// source's — a project's binding for `rust-test` hides the language's.
fn templates_for_tags(
    tags: &[String],
    sources: &[(TaskSource, Vec<TaskTemplate>)],
) -> Vec<(TaskSource, TaskTemplate)> {
    let mut bound: Vec<(TaskSource, TaskTemplate)> = Vec::new();
    for tag in tags {
        for (source, templates) in sources {
            for template in templates {
                if template.tags.iter().any(|t| t == tag) {
                    bound.push((*source, template.clone()));
                }
            }
        }
    }
    bound.sort_by_key(|(source, _)| *source);
    if let Some((strongest, _)) = bound.first().cloned() {
        bound.retain(|(source, _)| *source == strongest);
    }
    bound
}

impl Engine {
    /// The location behind a request: the buffer's path, grammar and the
    /// symbol at the caret, read under the buffer lock and nothing else.
    fn task_location(&self, ctx: &TaskEditorContext) -> Location {
        let Some(id) = ctx.buffer_id else {
            return Location::default();
        };
        self.with_buffer(id, |state| {
            let rope = state.buffer.as_rope();
            let symbol = state.highlight.as_ref().and_then(|highlight| {
                let row = ctx.row.unwrap_or(0).min(rope.max_point().row);
                let point = rope.clip_point_utf16(
                    rope::Unclipped(rope::PointUtf16::new(row, ctx.column.unwrap_or(0))),
                    text::Bias::Left,
                );
                highlight.symbol_name_at(rope, rope.point_utf16_to_offset(point))
            });
            Location {
                path: state.file_path().map(Path::to_path_buf),
                grammar: state.highlight.as_ref().map(|highlight| highlight.name()),
                symbol,
            }
        })
        .unwrap_or_default()
    }

    /// The three sources' templates and the context to resolve them with —
    /// Zed's `list_tasks` plus `task_context_for_location`, in one pass.
    fn task_sources(
        &self,
        project_id: ProjectId,
        ctx: &TaskEditorContext,
    ) -> Result<(TaskContext, Vec<(TaskSource, Vec<TaskTemplate>)>), EngineError> {
        let root = self
            .project_root(project_id)
            .ok_or(EngineError::UnknownProject(project_id))?;
        let location = self.task_location(ctx);
        let mut variables = basic_variables(&root, &location, ctx);
        let language = language_tasks(location.grammar, &mut variables, &root, &location);
        let project = read_templates(&root.join(".zed").join("tasks.json"));
        let global = crate::config::tasks_path()
            .map(|path| read_templates(&path))
            .unwrap_or_default();
        // The underscored captures are for the providers above, not the
        // shell — Zed sweeps them too (task_store.rs:345-346).
        variables.sweep();
        let context = TaskContext {
            cwd: Some(root),
            task_variables: variables,
            // Zed's own slot for the environment a project runs in, which is
            // where the active toolchain lands: `VIRTUAL_ENV` and a `PATH`
            // prefix, so `python -m pytest` is the project's Python and not
            // the userland's.
            project_env: self
                .toolchain_env(project_id)
                .iter()
                .map(|(key, value)| (key.to_owned(), value.to_owned()))
                .collect(),
        };
        Ok((
            context,
            vec![
                (TaskSource::Project, project),
                (TaskSource::Global, global),
                (TaskSource::Language, language),
            ],
        ))
    }

    /// Every task that resolves for the context, in Zed's order for a fresh
    /// picker (task_inventory.rs `used_and_current_resolved_tasks`): the
    /// project's, then the language's, then the global file's, one per
    /// label. With a runnable in the context, only the tasks bound to its
    /// tags — what the play button offers.
    ///
    /// **Blocking**: reads two small files. Call it off the main thread.
    pub fn tasks_list(
        &self,
        project_id: ProjectId,
        ctx: &TaskEditorContext,
    ) -> Result<Vec<TaskSpec>, EngineError> {
        let (context, sources) = self.task_sources(project_id, ctx)?;
        let candidates: Vec<(TaskSource, TaskTemplate)> = match &ctx.runnable {
            Some(runnable) => templates_for_tags(&runnable.tags, &sources),
            None => {
                let by_source = |wanted: TaskSource| {
                    sources
                        .iter()
                        .filter(move |(source, _)| *source == wanted)
                        .flat_map(|(source, templates)| {
                            templates.iter().map(move |t| (*source, t.clone()))
                        })
                };
                by_source(TaskSource::Project)
                    .chain(by_source(TaskSource::Language))
                    .chain(by_source(TaskSource::Global))
                    .collect()
            }
        };
        let mut seen = std::collections::HashSet::new();
        Ok(candidates
            .iter()
            .filter_map(|(source, template)| resolve(template, *source, &context))
            .filter(|task| seen.insert(task.full_label.clone()))
            .collect())
    }

    /// Resolve one template of the UI's own — the picker's oneshot, whose
    /// label and command are what was typed (tasks_ui/src/modal.rs:77-103).
    ///
    /// **Blocking**, as [`Self::tasks_list`] is.
    pub fn task_resolve(
        &self,
        project_id: ProjectId,
        ctx: &TaskEditorContext,
        template: &TaskTemplate,
    ) -> Result<Option<TaskSpec>, EngineError> {
        let (context, _) = self.task_sources(project_id, ctx)?;
        Ok(resolve(template, TaskSource::UserInput, &context))
    }

    /// The buffer's runnables, one per row — see [`RunnableRow`]. Empty for
    /// a buffer whose language has no `runnables.scm`.
    pub fn buffer_runnables(&self, id: BufferId) -> Result<Vec<RunnableRow>, EngineError> {
        self.with_buffer(id, |state| {
            let Some(highlight) = &state.highlight else {
                return Vec::new();
            };
            highlight.runnables(state.buffer.as_rope())
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    /// The global tasks path is process-global, so these take turns — the
    /// settings tests share the same rule.
    fn with_config_dir<T>(body: impl FnOnce(&Engine, &Path) -> T) -> T {
        static LOCK: Mutex<()> = Mutex::new(());
        let _guard = LOCK.lock().unwrap_or_else(|err| err.into_inner());
        let dir = tempfile::tempdir().unwrap();
        crate::config::set_directory(dir.path().to_path_buf());
        let engine = Engine::new();
        body(&engine, dir.path())
    }

    /// A project on disk, opened in the engine, with `main.rs` open as a
    /// buffer. Returns the ids and keeps the directory alive.
    fn rust_project(engine: &Engine, root: &Path) -> (ProjectId, BufferId) {
        std::fs::create_dir_all(root.join("src")).unwrap();
        std::fs::write(
            root.join("Cargo.toml"),
            "[package]\nname = \"welcome\"\nversion = \"0.1.0\"\n",
        )
        .unwrap();
        let main = root.join("src").join("main.rs");
        std::fs::write(
            &main,
            "fn main() {\n    println!(\"hi\");\n}\n\n#[cfg(test)]\nmod tests {\n    #[test]\n    fn adds() {\n        assert_eq!(1 + 1, 2);\n    }\n}\n",
        )
        .unwrap();
        let project = engine.open_project(root);
        let buffer = engine.open_file(&main).unwrap();
        (project, buffer)
    }

    fn context(buffer: BufferId, row: u32) -> TaskEditorContext {
        TaskEditorContext {
            buffer_id: Some(buffer),
            row: Some(row),
            column: Some(0),
            ..TaskEditorContext::default()
        }
    }

    #[test]
    fn variables_are_substituted_from_the_editor_context() {
        with_config_dir(|engine, dir| {
            let root = dir.join("proj");
            let (project, buffer) = rust_project(engine, &root);
            std::fs::write(
                dir.join("tasks.json"),
                r#"[{"label": "echo $ZED_FILENAME", "command": "echo", "args": ["$ZED_ROW:$ZED_COLUMN", "$ZED_STEM", "$ZED_RELATIVE_FILE", "$ZED_LANGUAGE"]}]"#,
            )
            .unwrap();
            let tasks = engine.tasks_list(project, &context(buffer, 1)).unwrap();
            let task = tasks
                .iter()
                .find(|task| task.source == TaskSource::Global)
                .expect("the global task resolves");
            assert_eq!(task.label, "echo main.rs");
            assert_eq!(task.args, vec!["2:1", "main", "src/main.rs", "Rust"]);
            assert_eq!(task.cwd.as_deref(), Some(root.to_str().unwrap()));
            // The variables ride along as environment, as in Zed.
            assert_eq!(task.env.get("ZED_FILENAME").map(String::as_str), Some("main.rs"));
            assert_eq!(
                task.env.get("ZED_WORKTREE_ROOT").map(String::as_str),
                root.to_str()
            );
            engine.close_project(project);
        });
    }

    #[test]
    fn a_task_naming_an_absent_variable_is_filtered_out() {
        with_config_dir(|engine, dir| {
            let root = dir.join("proj");
            let (project, buffer) = rust_project(engine, &root);
            std::fs::write(
                dir.join("tasks.json"),
                r#"[
                  {"label": "selected", "command": "echo \"$ZED_SELECTED_TEXT\""},
                  {"label": "with default", "command": "echo \"${ZED_SELECTED_TEXT:nothing}\""}
                ]"#,
            )
            .unwrap();
            let labels = |ctx: &TaskEditorContext| -> Vec<String> {
                engine
                    .tasks_list(project, ctx)
                    .unwrap()
                    .into_iter()
                    .filter(|task| task.source == TaskSource::Global)
                    .map(|task| task.label)
                    .collect()
            };
            assert_eq!(labels(&context(buffer, 0)), vec!["with default"]);
            let mut with_selection = context(buffer, 0);
            with_selection.selected_text = Some("hi".to_owned());
            assert_eq!(labels(&with_selection), vec!["selected", "with default"]);
            engine.close_project(project);
        });
    }

    #[test]
    fn tasks_json_keeps_the_good_entries_and_drops_the_bad() {
        let text = r#"// tasks
        [
          {"label": "ok", "command": "true", "args": ["a", "b"], "reveal": "no_focus", "tags": ["x"]},
          {"label": "no command"},
          {"label": "bad reveal", "command": "true", "reveal": "sometimes"},
          "not an object",
          {"label": "also ok", "command": "false", "use_new_terminal": true, "allow_concurrent_runs": true, "save": "current"},
        ]"#;
        let templates = parse_tasks_json(text, "test");
        let labels: Vec<&str> = templates.iter().map(|t| t.label.as_str()).collect();
        assert_eq!(labels, vec!["ok", "also ok"]);
        assert_eq!(templates[0].reveal, RevealStrategy::NoFocus);
        assert_eq!(templates[0].tags, vec!["x"]);
        assert!(templates[1].use_new_terminal && templates[1].allow_concurrent_runs);
        assert_eq!(templates[1].save, SaveStrategy::Current);
        // Not an array at all: nothing, and no panic.
        assert!(parse_tasks_json(r#"{"label": "x"}"#, "test").is_empty());
        assert!(parse_tasks_json("not json", "test").is_empty());
        assert!(parse_tasks_json("", "test").is_empty());
    }

    #[test]
    fn rust_runnables_are_found_and_bound_to_cargo() {
        with_config_dir(|engine, dir| {
            let root = dir.join("proj");
            let (project, buffer) = rust_project(engine, &root);
            let runnables = engine.buffer_runnables(buffer).unwrap();
            let rows: Vec<(u32, Vec<String>)> = runnables
                .iter()
                .map(|r| (r.row, r.tags.clone()))
                .collect();
            assert_eq!(
                rows,
                vec![
                    (0, vec!["rust-main".to_owned()]),
                    (5, vec!["rust-mod-test".to_owned()]),
                    (7, vec!["rust-test".to_owned()]),
                ]
            );
            let test = &runnables[2];
            assert_eq!(test.run_text, "adds");
            assert_eq!(test.captures.get("_test_name").map(String::as_str), Some("adds"));

            let mut ctx = context(buffer, test.row);
            ctx.runnable = Some(RunnableContext {
                tags: test.tags.clone(),
                captures: test.captures.clone(),
                run_text: test.run_text.clone(),
            });
            let tasks = engine.tasks_list(project, &ctx).unwrap();
            assert_eq!(tasks.len(), 1);
            assert_eq!(tasks[0].label, "Test 'adds' (package: welcome)");
            assert_eq!(
                tasks[0].command_label,
                "cargo test -p welcome -- --nocapture --include-ignored adds"
            );
            assert_eq!(
                tasks[0].cwd.as_deref(),
                Some(root.to_str().unwrap()),
                "cwd is the manifest's directory"
            );
            assert_eq!(tasks[0].source, TaskSource::Language);

            // The main fn: a bin target inferred from the layout.
            let main = &runnables[0];
            let mut ctx = context(buffer, 0);
            ctx.runnable = Some(RunnableContext {
                tags: main.tags.clone(),
                captures: main.captures.clone(),
                run_text: main.run_text.clone(),
            });
            let tasks = engine.tasks_list(project, &ctx).unwrap();
            assert_eq!(tasks[0].label, "Run bin welcome (package: welcome)");
            engine.close_project(project);
        });
    }

    #[test]
    fn a_project_binding_for_a_tag_beats_the_languages() {
        with_config_dir(|engine, dir| {
            let root = dir.join("proj");
            let (project, buffer) = rust_project(engine, &root);
            std::fs::create_dir_all(root.join(".zed")).unwrap();
            std::fs::write(
                root.join(".zed").join("tasks.json"),
                r#"[{"label": "my test runner", "command": "echo $ZED_FILE", "tags": ["rust-test"]}]"#,
            )
            .unwrap();
            let mut ctx = context(buffer, 7);
            ctx.runnable = Some(RunnableContext {
                tags: vec!["rust-test".to_owned()],
                captures: HashMap::from([("_test_name".to_owned(), "adds".to_owned())]),
                run_text: "adds".to_owned(),
            });
            let tasks = engine.tasks_list(project, &ctx).unwrap();
            let labels: Vec<&str> = tasks.iter().map(|t| t.label.as_str()).collect();
            assert_eq!(labels, vec!["my test runner"]);
            assert_eq!(tasks[0].source, TaskSource::Project);
            // And in the full list the project's task comes first.
            let all = engine.tasks_list(project, &context(buffer, 7)).unwrap();
            assert_eq!(all[0].label, "my test runner");
            assert!(all.iter().any(|t| t.label == "Check (package: welcome)"));
            engine.close_project(project);
        });
    }

    #[test]
    fn python_test_runnables_build_pytest_targets() {
        with_config_dir(|engine, dir| {
            let root = dir.join("py");
            std::fs::create_dir_all(root.join("tests")).unwrap();
            let file = root.join("tests").join("test_math.py");
            std::fs::write(
                &file,
                "import unittest\n\ndef test_adds():\n    assert 1 + 1 == 2\n\nclass TestThings:\n    def test_it(self):\n        pass\n\nclass Legacy(unittest.TestCase):\n    def test_old(self):\n        pass\n\nif __name__ == \"__main__\":\n    pass\n",
            )
            .unwrap();
            let project = engine.open_project(&root);
            let buffer = engine.open_file(&file).unwrap();
            let runnables = engine.buffer_runnables(buffer).unwrap();
            let rows: Vec<(u32, Vec<String>)> = runnables
                .iter()
                .map(|r| (r.row, r.tags.clone()))
                .collect();
            assert_eq!(
                rows,
                vec![
                    (2, vec!["python-pytest-method".to_owned()]),
                    (5, vec!["python-pytest-class".to_owned()]),
                    (6, vec!["python-pytest-method".to_owned()]),
                    (9, vec!["python-unittest-class".to_owned()]),
                    (10, vec!["python-unittest-method".to_owned()]),
                    (13, vec!["python-module-main-method".to_owned()]),
                ]
            );
            let run = |index: usize| {
                let r = &runnables[index];
                let mut ctx = context(buffer, r.row);
                ctx.runnable = Some(RunnableContext {
                    tags: r.tags.clone(),
                    captures: r.captures.clone(),
                    run_text: r.run_text.clone(),
                });
                engine.tasks_list(project, &ctx).unwrap()
            };
            assert_eq!(run(0)[0].label, "pytest tests/test_math.py::test_adds");
            assert_eq!(run(2)[0].label, "pytest tests/test_math.py::TestThings::test_it");
            assert_eq!(run(4)[0].label, "unittest tests.test_math.Legacy.test_old");
            assert_eq!(
                run(5)[0].command_label,
                "python3 -m tests.test_math"
            );
            // The untagged file-level tasks are in the plain list.
            let all = engine.tasks_list(project, &context(buffer, 0)).unwrap();
            assert!(all.iter().any(|t| t.label.starts_with("pytest '")));
            engine.close_project(project);
        });
    }

    #[test]
    fn a_oneshot_resolves_against_the_same_context() {
        with_config_dir(|engine, dir| {
            let root = dir.join("proj");
            let (project, buffer) = rust_project(engine, &root);
            let template = TaskTemplate {
                label: "echo $ZED_STEM".to_owned(),
                command: "echo $ZED_STEM".to_owned(),
                ..TaskTemplate::default()
            };
            let task = engine
                .task_resolve(project, &context(buffer, 0), &template)
                .unwrap()
                .expect("resolves");
            assert_eq!(task.label, "echo main");
            assert_eq!(task.command_label, "echo main");
            assert_eq!(task.source, TaskSource::UserInput);
            assert!(task.id.starts_with("oneshot_"));
            // No project: an error, not a panic.
            assert_eq!(
                engine.task_resolve(999, &context(buffer, 0), &template),
                Err(EngineError::UnknownProject(999))
            );
            engine.close_project(project);
        });
    }

    #[test]
    fn npm_scripts_become_tasks() {
        with_config_dir(|engine, dir| {
            let root = dir.join("js");
            std::fs::create_dir_all(&root).unwrap();
            std::fs::write(
                root.join("package.json"),
                r#"{"scripts": {"build": "tsc", "test": "jest"}, "devDependencies": {"jest": "^29"}}"#,
            )
            .unwrap();
            std::fs::write(root.join("yarn.lock"), "").unwrap();
            let file = root.join("app.test.js");
            std::fs::write(&file, "test('adds %s', () => {});\n").unwrap();
            let project = engine.open_project(&root);
            let buffer = engine.open_file(&file).unwrap();
            let all = engine.tasks_list(project, &context(buffer, 0)).unwrap();
            let labels: Vec<&str> = all.iter().map(|t| t.label.as_str()).collect();
            assert!(labels.contains(&"yarn run build"), "{labels:?}");
            assert!(labels.contains(&"yarn run test"), "{labels:?}");
            assert!(labels.contains(&"jest file test"), "{labels:?}");

            let runnables = engine.buffer_runnables(buffer).unwrap();
            assert_eq!(runnables.len(), 1);
            assert_eq!(runnables[0].tags, vec!["js-test"]);
            engine.close_project(project);
        });
    }

    #[test]
    fn helpers_follow_zed() {
        assert_eq!(
            cargo_package_name("[workspace]\nmembers = [\"a\"]\n"),
            None
        );
        assert_eq!(
            cargo_package_name("[package]\nname = 'engine' # our crate\nedition = \"2024\"\n"),
            Some("engine".to_owned())
        );
        assert_eq!(python_module_name("pkg/sub/mod.py"), "pkg.sub.mod");
        assert_eq!(go_subtest_name("\"a case\"").as_deref(), Some("a_case"));
        assert_eq!(go_subtest_name("`raw`").as_deref(), Some("raw"));
        assert_eq!(js_test_name_pattern("adds %s to $name"), "adds (.+?) to (.+?)");
        assert_eq!(js_test_name_pattern("a.b"), "a\\.b");
        let package = parse_package_json(r#"{"packageManager": "pnpm@9.0.0", "devDependencies": {"vitest": "1"}}"#);
        assert_eq!(package.manager, "pnpm");
        assert!(package.vitest && !package.jest);
    }
}
