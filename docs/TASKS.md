# Tasks

Tasks are shell commands the terminal runs for you, with a label and a
say in what the terminal does with the result. They are Zed's tasks —
the same `tasks.json`, the same `$ZED_*` variables, the same two
commands — and Zed's own guide, <https://zed.dev/docs/tasks>, is the
reference; this page is what is different on a phone and where to find
things.

## Running one

`Alt` `Shift` `T` (`task: spawn`) opens the task picker: every task the
project, your own `tasks.json` and the file's language offer, the ones
you ran this session first. Type to narrow the list; type a command that
matches nothing and `Enter` runs it as a *oneshot* — a task whose label
is the command — which is then remembered for the session like any
other. `Tab` puts the selected task's command line into the field to
edit before running. `↑` `↓` move, `Enter` runs, `Esc` closes. By touch,
the `▶` in the terminal's tab bar opens the same picker, and a tap on a
row runs it; **Run task…** in the `☰` menu is the third way in.

`Alt` `T` or `Ctrl` `Alt` `R` (`task: rerun`) runs the last task again,
as it resolved then: same command, same directory. **Rerun last task**
in the `☰` menu does the same.

Every run is a tab in the terminal dock, titled with the task's label.
A second run of the same task reuses its tab and waits for the first
run to end, as in Zed; `use_new_terminal` and `allow_concurrent_runs`
in the task change that (below). The command runs in the userland's
login shell when Debian is installed, else the host shell, in the task's
`cwd` — the project root unless it says otherwise.

## Runnables: the play buttons

A `▶` in the gutter marks a row the language knows how to run: a
`#[test]` fn or `fn main` in Rust, a `test_` function or a `TestCase`
method in Python, a `Test…` function in Go, a `test(…)` or `it(…)` block
in JavaScript and TypeScript, a script in `package.json`, and a shell
script's first line. Tap it to run the task bound to that row — `cargo
test -- adds`, `pytest tests/test_math.py::test_adds` — or to pick one
when several are. `editor: spawn nearest task` in the palette does the
same for the runnable at or nearest the caret; Zed leaves it unbound,
and so does this app, so bind it in `keymap.json` if you want a key.

The rows come from the grammar's `runnables.scm`, the same queries Zed
ships (`core/vendor/grammars/src/<language>/runnables.scm`), and each
carries a *tag* — `rust-test`, `python-pytest-method`, `go-test`,
`js-test`, `package-script`, `bash-script`. A task in `tasks.json` with
that tag in its `tags` takes over the button from the language's
built-in: a project's `{"tags": ["rust-test"], …}` is what its `▶` runs.

## Where tasks come from

In the order the picker lists them and the order a tag binding wins:

1. **The project's `.zed/tasks.json`** — `zed: open project tasks` in
   the palette, or **Edit project tasks** in the `☰` menu, creates it
   with a commented example and opens it as a tab.
2. **The language's built-ins**, ported from Zed's own context providers:
   `cargo check` / `test` / `run` for Rust (the package read off the
   nearest `Cargo.toml`, the binary from the file's place in `src/`);
   `python3` file and module runs, `pytest` and `unittest` targets for
   Python; `go test` for the package, the function, a subtest, a
   benchmark, `go run`; `jest` / `vitest` / `mocha` / `node --test` by
   whichever `package.json` declares, plus one `npm run <script>` (or
   `yarn` / `pnpm`, by the lockfile) per script; `run '<file>'` for a
   shell script; Python, JavaScript and shell scripts also get *execute
   selection*.
3. **Your own `tasks.json`**, beside `settings.json` in the app's
   private storage — `zed: open tasks` in the palette, or **Edit
   tasks.json** in the `☰` menu. Only the app can reach that directory,
   so that command is the way to edit it.

Both files are read again every time the picker opens, so an edit is
live on the next `Alt` `Shift` `T`; there is no reload command. They are
JSONC: comments and trailing commas are fine. One malformed entry costs
itself — a warning in logcat and a list one shorter — never the rest of
the file.

## `tasks.json`

```jsonc
[
  {
    "label": "Test $ZED_STEM",
    "command": "cargo",
    "args": ["test", "--", "$ZED_STEM"],
    // Appended to the terminal's environment, over `terminal.env`.
    "env": { "RUST_BACKTRACE": "1" },
    // Defaults to the project root.
    "cwd": "$ZED_WORKTREE_ROOT",
    // A fresh tab every run, instead of reusing the task's tab.
    "use_new_terminal": false,
    // Rerun while the last run is still going: true replaces it at
    // once, false waits for it to end.
    "allow_concurrent_runs": false,
    // After starting: "always" shows the dock and the tab (default),
    // "no_focus" shows the dock but leaves focus alone, "never" only
    // adds or reuses the tab.
    "reveal": "always",
    // After the command ends: "never" (default), "always" closes the
    // tab, "on_success" closes it only after exit code 0.
    "hide": "never",
    // Before running: "none" (default), "current" saves the active tab,
    // "all" saves every edited tab.
    "save": "current",
    // Print the command line at the top of the output, and the exit
    // status at the bottom.
    "show_command": true,
    "show_summary": true,
    // Take over a play button.
    "tags": ["rust-test"]
  }
]
```

`shell`, `reveal_target` and `hooks` from Zed's schema are accepted and
ignored: every task runs in the login shell, the dock is the only place
a terminal can go, and there are no git worktrees to hook.

## Variables

Substituted in `label`, `command`, `args`, `cwd` and `env`, and also
exported into the task's environment under the same names. A task that
names a variable the context lacks — `$ZED_SELECTED_TEXT` with nothing
selected, `$ZED_FILE` with no file open — is left out of the list;
`${ZED_SELECTED_TEXT:fallback}` gives it a default instead.

| Variable | Value |
|---|---|
| `$ZED_FILE` | Absolute path of the open file |
| `$ZED_FILENAME` | Its name, `main.rs` |
| `$ZED_STEM` | Its name without the extension, `main` |
| `$ZED_DIRNAME` | Its directory |
| `$ZED_RELATIVE_FILE` | Its path from the project root, `src/main.rs` |
| `$ZED_RELATIVE_DIR` | Its directory from the project root |
| `$ZED_WORKTREE_ROOT` | The project root |
| `$ZED_ROW` / `$ZED_COLUMN` | The caret, 1-based |
| `$ZED_SYMBOL` | The name of the innermost outline symbol at the caret |
| `$ZED_SELECTED_TEXT` | The selection |
| `$ZED_LANGUAGE` | The language's name, `Rust` |
| `$ZED_CUSTOM_<name>` | What a language's context provider adds — `$ZED_CUSTOM_RUST_PACKAGE`, `$ZED_CUSTOM_PYTHON_TEST_TARGET`, `$ZED_CUSTOM_GO_PACKAGE`, … — and, from a play button, the query's own captures |

## Where the logic lives

The templates, the variables and their substitution are the engine's
(`core/crates/engine/src/tasks.rs`, on the vendored `task` crate that
is Zed's own); the runnables query runs in `highlight.rs` beside the
outline. The resolved command crosses the bridge as one JSON object
(`tasksList`, `taskResolve`, `bufferRunnables` in `CoreBridge.kt`), and
the terminal dock spawns it (`TerminalPanelState.runTask`) — the pty and
the userland are Kotlin's, so nothing is spawned in Rust.
