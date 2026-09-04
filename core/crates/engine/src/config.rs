//! User settings: a JSONC file the app and the user both edit.
//!
//! The file is hand-editable and **keeps its comments**. Writes from the
//! settings screen are surgical — Zed's `settings_json` locates the exact key
//! in the syntax tree and replaces just that value — so the explanatory
//! comments the default file ships with, and anything the user adds, survive
//! every change the UI makes. That is the whole reason this lives in the
//! engine rather than being a `SharedPreferences` blob.
//!
//! Keys follow Zed's names where the meaning is the same (`theme`,
//! `buffer_font_size`, `tab_size`), so muscle memory and documentation carry
//! over. Unknown keys are left alone rather than dropped: a settings file is
//! the user's, and a future version of the app may understand more of it.

use std::collections::BTreeMap;
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};

use serde::{Deserialize, Serialize};

use crate::appearance::{
    BufferLineHeight, DEFAULT_FONT_WEIGHT, DEFAULT_UI_FONT_SIZE, FontFeatures, IconThemeSelection,
    ResolvedFont, ThemeSelection,
};
use crate::{BufferId, EngineError, ProjectId};

/// How the project tree treats gitignored entries. Zed dims them rather than
/// hiding them, which is the default here too — seeing that a file is ignored
/// is usually more useful than not seeing the file.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum GitignoredFiles {
    /// Listed like any other file.
    Show,
    /// Listed, but greyed out.
    #[default]
    Dimmed,
    /// Left out of the tree.
    Hide,
}

/// What a line longer than the pane does — Zed's `soft_wrap`, with Zed's
/// three current values (settings_content/src/language.rs `SoftWrap`).
/// `prefer_line` is Zed's deprecated spelling of `none` and is read as it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum SoftWrap {
    /// Zed's default: the line runs off the right edge and scrolls.
    #[default]
    #[serde(alias = "prefer_line")]
    None,
    /// Wrap at the width of the text area.
    EditorWidth,
    /// Wrap at `preferred_line_length` or the editor's width, whichever is
    /// smaller — and draw the column as the active wrap guide, as Zed does
    /// (editor/src/config.rs:248-251).
    Bounded,
}

/// Zed's `show_whitespaces` (assets/settings/default.json:520-530): which
/// space and tab characters get a visible glyph. `selection` is Zed's
/// default.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum ShowWhitespaces {
    Off,
    All,
    #[default]
    Selection,
    /// Tabs, whitespace at either edge of a run, and whitespace next to more
    /// whitespace — Zed's own three conditions, quoted in default.json:526-529.
    Boundary,
    /// Only the whitespace after the last non-blank character of a row.
    Trailing,
}

/// Zed's `relative_line_numbers` (settings_content/src/editor.rs:304-308):
/// numbers counted from the caret's row rather than from the top of the file.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum RelativeLineNumbers {
    #[default]
    Disabled,
    Enabled,
    /// Relative, but counting wrapped display rows rather than buffer rows.
    Wrapped,
}

/// Zed's `current_line_highlight` (assets/settings/default.json:308-316):
/// how far across the pane the caret's row is washed.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum CurrentLineHighlight {
    None,
    /// The gutter only.
    Gutter,
    /// The text area only.
    Line,
    /// Both — Zed's default.
    #[default]
    All,
}

/// Zed's `cursor_shape` (assets/settings/default.json:259-270).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum CursorShape {
    #[default]
    Bar,
    Block,
    Underline,
    Hollow,
}

/// Zed's `scrollbar.show` (assets/settings/default.json:600-613). `auto` and
/// `system` both mean "when there is something to scroll", which is what this
/// pane has always done.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum ShowScrollbar {
    #[default]
    Auto,
    System,
    Always,
    Never,
}

/// Zed's `scrollbar.diagnostics`, which is a severity floor rather than a
/// flag: `"warning"` marks errors and warnings and nothing quieter. `false`
/// and `true` are read as `none` and `all`, as Zed reads them.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum ScrollbarDiagnostics {
    #[serde(alias = "false")]
    None,
    Error,
    Warning,
    Information,
    #[default]
    #[serde(alias = "true")]
    All,
}

/// Zed's `scrollbar` block, narrowed to the marks this pane can draw
/// (assets/settings/default.json:599-637). `axes` is not here: the pane has
/// one scrollbar and it is vertical.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct ScrollbarSettings {
    pub show: ShowScrollbar,
    pub cursors: bool,
    pub git_diff: bool,
    pub search_results: bool,
    pub selected_symbol: bool,
    pub diagnostics: ScrollbarDiagnostics,
}

impl Default for ScrollbarSettings {
    fn default() -> Self {
        Self {
            show: ShowScrollbar::Auto,
            cursors: true,
            git_diff: true,
            search_results: true,
            selected_symbol: true,
            diagnostics: ScrollbarDiagnostics::All,
        }
    }
}

/// Zed's `minimap.show` (assets/settings/default.json:640-649), whose default
/// is `never`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum ShowMinimap {
    Auto,
    Always,
    #[default]
    Never,
}

/// Zed's `minimap.thumb`: always drawn, or only while the pointer is over the
/// minimap. On a touch screen there is no hover, so `hover` reads as "while
/// the minimap is being dragged".
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum MinimapThumb {
    Hover,
    #[default]
    Always,
}

/// Zed's `minimap` block, narrowed to what a per-line colour rendering needs.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct MinimapSettings {
    pub show: ShowMinimap,
    pub thumb: MinimapThumb,
    /// How wide the minimap may get, in columns of the buffer font. Zed's
    /// default is null — as wide as the content; here that is a number,
    /// because a phone has no room to be generous.
    pub max_width_columns: u32,
}

impl Default for MinimapSettings {
    fn default() -> Self {
        Self {
            show: ShowMinimap::Never,
            thumb: MinimapThumb::Always,
            max_width_columns: 80,
        }
    }
}

/// Zed's `diagnostics.inline` (assets/settings/default.json:1656-1672) — the
/// error-lens message drawn at the end of the row it belongs to.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct InlineDiagnosticsSettings {
    pub enabled: bool,
    /// The quietest severity worth drawing inline. Null in Zed, meaning
    /// "whatever the editor's own maximum is", which here is `all`.
    pub max_severity: ScrollbarDiagnostics,
}

impl Default for InlineDiagnosticsSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            max_severity: ScrollbarDiagnostics::All,
        }
    }
}

/// Zed's `diagnostics` block, narrowed to the inline messages.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct DiagnosticsSettings {
    pub inline: InlineDiagnosticsSettings,
}

/// Zed's `gutter` block, narrowed to what this gutter draws by setting
/// (assets/settings/default.json:698-702).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct GutterSettings {
    pub line_numbers: bool,
}

impl Default for GutterSettings {
    fn default() -> Self {
        Self { line_numbers: true }
    }
}

/// Zed's `format_on_save`. `language_server` is the spelling Zed's older
/// settings used for "on, and only through the language server"; it is
/// still read here because settings files outlive the editors that wrote
/// them, and it means what it says: the external `formatter`, if any, is
/// skipped on save.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum FormatOnSave {
    On,
    #[default]
    Off,
    LanguageServer,
}

/// Zed's `formatter` (settings_content/src/language.rs:1098-1119), reduced
/// to the steps this editor can take. `prettier` is read as `auto`, because
/// there is no bundled Prettier here and `auto` is what Zed falls back to
/// without one; an array of steps keeps its first step, since the save path
/// runs one formatter.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub enum Formatter {
    /// The language server, when one is running and offers formatting.
    #[default]
    Auto,
    /// Never format, even with `format_on_save` on.
    None,
    /// The language server — by name when `{"language_server": {"name": …}}`
    /// said one, which for this editor (one server per language) only matters
    /// when the name is *not* the running server's.
    LanguageServer { name: Option<String> },
    /// A program in the userland, given the buffer on stdin and replacing it
    /// with what it prints. `{buffer_path}` in an argument becomes the file's
    /// absolute path, as in Zed (project/src/lsp_store.rs:2630).
    External {
        command: String,
        arguments: Vec<String>,
    },
    /// A code action kind the server runs as the formatter.
    CodeAction(String),
}

impl Serialize for Formatter {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        use serde::ser::SerializeMap;
        match self {
            Formatter::Auto => serializer.serialize_str("auto"),
            Formatter::None => serializer.serialize_str("none"),
            Formatter::LanguageServer { name: None } => serializer.serialize_str("language_server"),
            Formatter::LanguageServer { name: Some(name) } => {
                let mut map = serializer.serialize_map(Some(1))?;
                map.serialize_entry("language_server", &serde_json::json!({ "name": name }))?;
                map.end()
            }
            Formatter::External { command, arguments } => {
                let mut map = serializer.serialize_map(Some(1))?;
                map.serialize_entry(
                    "external",
                    &serde_json::json!({ "command": command, "arguments": arguments }),
                )?;
                map.end()
            }
            Formatter::CodeAction(kind) => {
                let mut map = serializer.serialize_map(Some(1))?;
                map.serialize_entry("code_action", kind)?;
                map.end()
            }
        }
    }
}

/// The shapes Zed's `formatter` takes on disk, one variant per shape.
#[derive(Deserialize)]
#[serde(untagged)]
enum FormatterContent {
    Named(FormatterName),
    Object(FormatterObject),
    Steps(Vec<FormatterContent>),
}

#[derive(Deserialize)]
#[serde(rename_all = "snake_case")]
enum FormatterName {
    Auto,
    None,
    Prettier,
    LanguageServer,
}

#[derive(Deserialize)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
struct FormatterObject {
    external: Option<ExternalFormatter>,
    language_server: Option<NamedLanguageServer>,
    code_action: Option<String>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ExternalFormatter {
    command: String,
    #[serde(default)]
    arguments: Vec<String>,
}

#[derive(Deserialize)]
struct NamedLanguageServer {
    name: Option<String>,
}

impl FormatterContent {
    fn into_formatter(self) -> Result<Formatter, String> {
        match self {
            FormatterContent::Named(FormatterName::Auto | FormatterName::Prettier) => {
                Ok(Formatter::Auto)
            }
            FormatterContent::Named(FormatterName::None) => Ok(Formatter::None),
            FormatterContent::Named(FormatterName::LanguageServer) => {
                Ok(Formatter::LanguageServer { name: None })
            }
            FormatterContent::Object(object) => {
                let count = usize::from(object.external.is_some())
                    + usize::from(object.language_server.is_some())
                    + usize::from(object.code_action.is_some());
                if count != 1 {
                    return Err("a formatter object names exactly one of external, language_server or code_action".to_owned());
                }
                if let Some(external) = object.external {
                    if external.command.trim().is_empty() {
                        return Err("an external formatter needs a command".to_owned());
                    }
                    return Ok(Formatter::External {
                        command: external.command,
                        arguments: external.arguments,
                    });
                }
                if let Some(server) = object.language_server {
                    return Ok(Formatter::LanguageServer { name: server.name });
                }
                Ok(Formatter::CodeAction(object.code_action.unwrap_or_default()))
            }
            FormatterContent::Steps(steps) => match steps.into_iter().next() {
                Some(step) => step.into_formatter(),
                None => Ok(Formatter::None),
            },
        }
    }
}

impl<'de> Deserialize<'de> for Formatter {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        FormatterContent::deserialize(deserializer)?
            .into_formatter()
            .map_err(serde::de::Error::custom)
    }
}

/// Zed's `autosave` (settings_content/src/workspace.rs:609-618), in Zed's
/// own shapes: three plain strings and `{"after_delay": {"milliseconds": N}}`
/// — which is what serde's external tagging spells a struct variant as, so
/// the derive reads Zed's file unchanged.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum Autosave {
    #[default]
    Off,
    /// Save a tab when the active tab changes.
    OnFocusChange,
    /// Save every dirty tab when the app leaves the foreground.
    OnWindowChange,
    /// Save a tab once it has sat unedited for this long.
    AfterDelay { milliseconds: u64 },
}

/// Zed's `restore_on_startup` (assets/settings/default.json:156-164): how
/// much of the last session comes back when the app is launched again.
///
/// Zed's three values describe how many *windows* are restored — every
/// workspace of the last session, only the most recent one, or none. This
/// port has one window and one project open at a time, so they are mapped to
/// how much of that one window comes back, which is the same question with a
/// single window:
///
/// - [`LastSession`](Self::LastSession) — the project *and* everything that
///   was going on in it: the pane layout, the tabs with their carets and
///   scroll, the docks, the terminal tabs;
/// - [`LastWorkspace`](Self::LastWorkspace) — the project alone, opened with
///   a fresh workspace, which is the "the workspace opened" of Zed's comment
///   without the session's contents;
/// - [`None`](Self::None) — nothing: the app starts on the project picker.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum RestoreOnStartup {
    /// Zed's default, and ours.
    #[default]
    LastSession,
    LastWorkspace,
    None,
}

impl RestoreOnStartup {
    /// Whether the last project is reopened at all, rather than the picker
    /// being shown.
    pub fn reopens_project(self) -> bool {
        !matches!(self, Self::None)
    }

    /// Whether the saved tabs, panes, docks and terminals are put back.
    pub fn restores_workspace(self) -> bool {
        matches!(self, Self::LastSession)
    }
}

/// Which side of the workspace a panel lives on — Zed's `dock`, minus
/// `bottom`, which here belongs to the terminal alone.
///
/// The defaults are *this app's*, not Zed's current ones: Zed moved its
/// project panel to the right, and a phone-shaped editor reads better with the
/// tree where every file manager on the platform puts it. Both are one line in
/// settings.json, which is the point of the setting.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DockSide {
    Left,
    Right,
    /// The panel is switched off: no status-bar button, and its commands
    /// refuse. This app's third value rather than Zed's — Zed hides a
    /// panel's *button* with a separate per-panel `"button": false` — folded
    /// into `dock` here by the owner's design: one row per panel, three
    /// answers, and a hidden panel costs no second key.
    Hidden,
}

/// Zed's `base_keymap` (settings/src/base_keymap_setting.rs): whose
/// shortcuts to start from. Each name but the last is one of Zed's own
/// overlay keymaps, laid over the defaults; `None` is Zed's "no defaults at
/// all" — the user's keymap.json is then the whole keymap (zed.rs:2357).
/// `VSCode` is the default here because it is Zed's, and its overlay is
/// small: Zed's own keymap is already VS Code's near enough.
///
/// Zed's `TextMate` and `Cursor` are left out: TextMate has no Linux file
/// to ship, and Cursor's is AI-editor chords this app has no actions for.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
pub enum BaseKeymap {
    #[default]
    VSCode,
    JetBrains,
    SublimeText,
    Atom,
    Emacs,
    None,
}

impl BaseKeymap {
    /// Zed's Linux overlay for this keymap, verbatim from
    /// `assets/keymaps/linux/`, or none where there is no overlay.
    pub fn asset(self) -> Option<&'static str> {
        match self {
            BaseKeymap::VSCode => Some(include_str!("../assets/keymaps/vscode.json")),
            BaseKeymap::JetBrains => Some(include_str!("../assets/keymaps/jetbrains.json")),
            BaseKeymap::SublimeText => Some(include_str!("../assets/keymaps/sublime_text.json")),
            BaseKeymap::Atom => Some(include_str!("../assets/keymaps/atom.json")),
            BaseKeymap::Emacs => Some(include_str!("../assets/keymaps/emacs.json")),
            BaseKeymap::None => None,
        }
    }
}

/// Zed's `git.inline_blame`. An object with one field rather than a bare
/// bool, because that is the shape Zed's settings file has and someone
/// pasting a line out of their Zed config should find it works.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct InlineBlameSettings {
    pub enabled: bool,
}

impl Default for InlineBlameSettings {
    fn default() -> Self {
        // Zed's own default (assets/settings/default.json).
        Self { enabled: true }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct GitSettings {
    pub inline_blame: InlineBlameSettings,
}

/// Zed's `inlay_hints` (assets/settings/default.json:793-821), the keys and
/// the defaults: off as a whole, every kind on once it is switched on. The
/// debounce and background keys Zed also has are not here — the debounce is
/// the editor's own constant and the background is not drawn — and a file
/// that carries them parses all the same.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct InlayHintSettings {
    pub enabled: bool,
    pub show_type_hints: bool,
    pub show_parameter_hints: bool,
    /// Hints with no LSP kind — rust-analyzer's chaining and lifetime hints.
    pub show_other_hints: bool,
}

impl Default for InlayHintSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            show_type_hints: true,
            show_parameter_hints: true,
            show_other_hints: true,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct ProjectPanelSettings {
    pub gitignored_files: GitignoredFiles,
    /// Zed's `project_panel.dock`.
    pub dock: DockSide,
    /// Zed's `project_panel.default_width`, in dp rather than px — this is
    /// Android, where a number of pixels is not a size.
    pub default_width: f32,
    /// How siblings are ordered. Zed's key name, not the shorter one, so a
    /// line pasted out of a Zed config means here what it means there.
    pub sort_mode: ProjectPanelSortMode,
    /// Hide the project's own name above the tree — Zed's `hide_root`, whose
    /// default is false. There is only ever one worktree here, which is
    /// exactly the case Zed's setting is about.
    pub hide_root: bool,
    /// Fold a chain of single-child directories into one row (`a/b/c`) —
    /// Zed's `auto_fold_dirs`, on by default.
    pub auto_fold_dirs: bool,
    /// Row height: `comfortable` is Zed's taller default.
    pub entry_spacing: EntrySpacing,
    /// Pixels of indent per nesting level. Zed's default is 20.
    pub indent_size: f32,
    /// Which files are marked as having diagnostics, they and their
    /// ancestors. Zed's default here is `all`, unlike the tabs' `off`.
    pub show_diagnostics: ShowDiagnostics,
}

impl Default for ProjectPanelSettings {
    fn default() -> Self {
        Self {
            gitignored_files: GitignoredFiles::default(),
            dock: DockSide::Left,
            default_width: 240.0,
            sort_mode: ProjectPanelSortMode::default(),
            hide_root: false,
            auto_fold_dirs: true,
            entry_spacing: EntrySpacing::default(),
            indent_size: 20.0,
            show_diagnostics: ShowDiagnostics::All,
        }
    }
}

/// Where the tab's close affordance sits — Zed's `tabs.close_position`
/// (settings_content/src/workspace.rs, `ClosePosition`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum ClosePosition {
    Left,
    #[default]
    Right,
}

/// Which files a surface marks as having diagnostics — Zed's
/// `ShowDiagnostics`, shared by `tabs.show_diagnostics` (default `off`) and
/// `project_panel.show_diagnostics` (default `all`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum ShowDiagnostics {
    /// Mark nothing.
    #[default]
    Off,
    /// Only files with errors.
    Errors,
    /// Files with errors and files with warnings.
    All,
}

/// Which tab becomes active after the active one is closed — Zed's
/// `tabs.activate_on_close` (default `history`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum ActivateOnClose {
    /// The tab that was open before this one — Zed's default.
    #[default]
    History,
    /// The neighbour on the right, if there is one.
    Neighbour,
    /// The neighbour on the left, if there is one.
    LeftNeighbour,
}

/// Zed's `tabs` block (assets/settings/default.json, "Settings related to the
/// editor's tabs"). Every default here is Zed's own.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct TabSettings {
    pub close_position: ClosePosition,
    /// Whether a tab carries its file's icon. Off in Zed, and off here.
    pub file_icons: bool,
    /// Whether a tab's title is tinted by the file's git status.
    pub git_status: bool,
    /// Zed marks diagnostics on a tab only when `file_icons` is on as well;
    /// here the mark is a dot beside the title, so it stands on its own.
    pub show_diagnostics: ShowDiagnostics,
    pub activate_on_close: ActivateOnClose,
}

impl Default for TabSettings {
    fn default() -> Self {
        Self {
            close_position: ClosePosition::Right,
            file_icons: false,
            git_status: false,
            show_diagnostics: ShowDiagnostics::Off,
            activate_on_close: ActivateOnClose::History,
        }
    }
}

/// Zed's `preview_tabs`: a tab opened with a single click is *provisional*
/// and is reused by the next provisional open, until an edit or a
/// double-click makes it permanent.
///
/// Three of Zed's six keys, the three that name a route this app has. The
/// ones left out (`enable_preview_from_multibuffer` and the two multibuffer
/// code-navigation keys) describe a surface there is no version of here, and
/// a setting that does nothing is worse than no setting.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct PreviewTabsSettings {
    pub enabled: bool,
    /// A single click in the project panel opens a preview tab. Zed: true.
    pub enable_preview_from_project_panel: bool,
    /// Choosing a file in the file finder opens a preview tab. Zed: false.
    pub enable_preview_from_file_finder: bool,
    /// Go-to-definition and friends open a preview tab — Zed's
    /// `enable_preview_file_from_code_navigation`, whose default is true.
    pub enable_preview_from_code_navigation: bool,
}

impl Default for PreviewTabsSettings {
    fn default() -> Self {
        Self {
            enabled: true,
            enable_preview_from_project_panel: true,
            enable_preview_from_file_finder: false,
            enable_preview_from_code_navigation: true,
        }
    }
}

/// How sibling entries are ordered in the project panel — Zed's
/// `project_panel.sort_mode` (settings_content/src/workspace.rs:938-946).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum ProjectPanelSortMode {
    /// Directories, then files — Zed's default and every file manager's.
    #[default]
    DirectoriesFirst,
    /// One list, ordered by name alone.
    Mixed,
    /// Files, then directories.
    FilesFirst,
}

/// Zed's `project_panel.entry_spacing`. `Comfortable` is the taller row.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum EntrySpacing {
    #[default]
    Comfortable,
    Standard,
}

/// A panel that has nothing to configure but where it sits and how wide it is.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct PanelSettings {
    pub dock: DockSide,
    pub default_width: f32,
}

impl PanelSettings {
    const fn new(dock: DockSide, default_width: f32) -> Self {
        Self {
            dock,
            default_width,
        }
    }
}

impl Default for PanelSettings {
    fn default() -> Self {
        Self::new(DockSide::Right, 360.0)
    }
}

/// One ACP agent the user configured by hand.
///
/// Zed's `agent_servers` entry, with the same three keys it uses
/// (settings_content/src/agent.rs:740-748): `command`, `args`, `env`. Zed's
/// has four more for modes and config-option defaults; those describe
/// surfaces this panel does not have, and a setting that does nothing is
/// worse than no setting.
///
/// The command is resolved **inside the guest**, not on Android: it is a
/// program on Debian's PATH, or an absolute path within it. That is the whole
/// reason a custom agent is possible at all — the engine already knows how to
/// enter the userland, so any program that speaks ACP on stdio is an agent.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct CustomAgent {
    pub command: String,
    pub args: Vec<String>,
    pub env: BTreeMap<String, String>,
}

/// One layer of the per-language settings — Zed's `LanguageSettingsContent`
/// (settings_content/src/language.rs), for the keys this editor acts on.
///
/// Every field is optional because this is a *layer*: the top level of
/// settings.json is one, each `languages` entry is one, and a project's
/// `.zed/settings.json` contributes both again. [`LanguageSettings::resolve`]
/// stacks them; an absent field defers to the layer beneath.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct LanguageSettingsContent {
    pub tab_size: Option<u32>,
    pub hard_tabs: Option<bool>,
    pub soft_wrap: Option<SoftWrap>,
    pub preferred_line_length: Option<u32>,
    /// Extra columns to mark with a wrap guide — Zed's `wrap_guides`.
    pub wrap_guides: Option<Vec<u32>>,
    pub format_on_save: Option<FormatOnSave>,
    pub formatter: Option<Formatter>,
    /// Code-action kinds to run before formatting, keyed by kind, `true` to
    /// run — Zed's `code_actions_on_format`.
    pub code_actions_on_format: Option<BTreeMap<String, bool>>,
    pub enable_language_server: Option<bool>,
    /// Which whitespace gets a visible glyph — Zed's `show_whitespaces`,
    /// which is a language setting there too.
    pub show_whitespaces: Option<ShowWhitespaces>,
    /// Whether the wrap guides are drawn at all — Zed's `show_wrap_guides`
    /// (default true), the switch over the `wrap_guides` columns above.
    pub show_wrap_guides: Option<bool>,
    /// Zed's `remove_trailing_whitespace_on_save` (default true).
    pub remove_trailing_whitespace_on_save: Option<bool>,
    /// Zed's `ensure_final_newline_on_save` (default true).
    pub ensure_final_newline_on_save: Option<bool>,
}

impl LanguageSettingsContent {
    /// Lay `over` on top of this layer: its present fields win.
    fn merge_from(&mut self, over: &LanguageSettingsContent) {
        macro_rules! take {
            ($($field:ident),*) => { $( if over.$field.is_some() { self.$field = over.$field.clone(); } )* };
        }
        take!(
            tab_size,
            hard_tabs,
            soft_wrap,
            preferred_line_length,
            wrap_guides,
            format_on_save,
            formatter,
            code_actions_on_format,
            enable_language_server,
            show_whitespaces,
            show_wrap_guides,
            remove_trailing_whitespace_on_save,
            ensure_final_newline_on_save
        );
    }
}

/// The settings in force for one buffer, every layer already stacked. What
/// the editor asks for by buffer and never has to compute itself.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct LanguageSettings {
    pub tab_size: u32,
    pub hard_tabs: bool,
    pub soft_wrap: SoftWrap,
    pub preferred_line_length: u32,
    pub wrap_guides: Vec<u32>,
    pub format_on_save: FormatOnSave,
    pub formatter: Formatter,
    pub code_actions_on_format: BTreeMap<String, bool>,
    pub enable_language_server: bool,
    /// `git.inline_blame.enabled`, which a project's local settings may
    /// override; carried here because this blob is the one the editor pane
    /// reads per buffer, and the pane is what draws blame.
    pub inline_blame: bool,
    pub show_whitespaces: ShowWhitespaces,
    pub show_wrap_guides: bool,
    pub remove_trailing_whitespace_on_save: bool,
    pub ensure_final_newline_on_save: bool,
}

impl LanguageSettings {
    /// Zed's order (settings/src/settings_store.rs `AllLanguageSettings`):
    /// the layers are merged *by kind* first — every top level into one
    /// default layer, every `languages` entry for the name into one language
    /// layer — and the language layer then sits on the default layer. So a
    /// user's `languages.Rust.tab_size` outranks a project's top-level
    /// `tab_size` for Rust files, exactly as it does in Zed.
    ///
    /// `grammar` is the grammar's own `config.toml` — Go's `hard_tabs`,
    /// YAML's `tab_size` — and sits just above the built-in defaults: Zed
    /// ships those as `languages` entries in default.json (Go: `hard_tabs:
    /// true`), which is the same rank.
    pub(crate) fn resolve(
        user: &Settings,
        grammar: Option<&LanguageSettingsContent>,
        local: Option<&ProjectSettingsContent>,
        language_name: Option<&str>,
    ) -> Self {
        let mut defaults = user.language_defaults();
        if let Some(grammar) = grammar {
            defaults.merge_from(grammar);
        }
        if let Some(local) = local {
            defaults.merge_from(&local.defaults);
        }
        let mut language = LanguageSettingsContent::default();
        if let Some(name) = language_name {
            if let Some(entry) = user.languages.get(name) {
                language.merge_from(entry);
            }
            if let Some(entry) = local.and_then(|local| local.languages.get(name)) {
                language.merge_from(entry);
            }
        }
        defaults.merge_from(&language);
        let inline_blame = local
            .and_then(|local| local.git.as_ref())
            .and_then(|git| git.inline_blame.as_ref())
            .and_then(|blame| blame.enabled)
            .unwrap_or(user.git.inline_blame.enabled);
        Self {
            tab_size: defaults.tab_size.unwrap_or(4).clamp(1, 16),
            hard_tabs: defaults.hard_tabs.unwrap_or(false),
            soft_wrap: defaults.soft_wrap.unwrap_or_default(),
            preferred_line_length: defaults
                .preferred_line_length
                .unwrap_or(80)
                .clamp(MIN_LINE_LENGTH, MAX_LINE_LENGTH),
            wrap_guides: defaults.wrap_guides.unwrap_or_default(),
            format_on_save: defaults.format_on_save.unwrap_or_default(),
            formatter: defaults.formatter.unwrap_or_default(),
            code_actions_on_format: defaults.code_actions_on_format.unwrap_or_default(),
            enable_language_server: defaults.enable_language_server.unwrap_or(true),
            inline_blame,
            show_whitespaces: defaults.show_whitespaces.unwrap_or_default(),
            show_wrap_guides: defaults.show_wrap_guides.unwrap_or(true),
            remove_trailing_whitespace_on_save: defaults
                .remove_trailing_whitespace_on_save
                .unwrap_or(true),
            ensure_final_newline_on_save: defaults.ensure_final_newline_on_save.unwrap_or(true),
        }
    }
}

/// One MCP context server the user configured — Zed's `context_servers`
/// entry (settings_content/src/project.rs:427-455), in the two shapes that
/// mean something to an ACP agent: a program to run over stdio, or an HTTP
/// endpoint. Zed's third shape, `Extension`, describes a server an extension
/// provides; there are no extensions here, so it is not a variant.
///
/// Untagged, exactly as Zed's is: `{"command": …}` is a stdio server and
/// `{"url": …}` an HTTP one, with nothing to spell out. Zed's own `"source":
/// "custom"` key is accepted and ignored — the file may have been copied from
/// a Zed config, and it should work as pasted.
///
/// The server is **handed to the agent, not run by the editor**: it goes out
/// in `session/new`'s `mcpServers`, and the agent spawns or connects to it
/// itself. So a stdio command is resolved inside the guest, on the same PATH
/// the agent has — which is what makes `npx -y some-mcp-server` work.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum ContextServer {
    Stdio {
        command: String,
        #[serde(default)]
        args: Vec<String>,
        #[serde(default)]
        env: BTreeMap<String, String>,
        /// Zed's `enabled`, default true: a server switched off stays in the
        /// file but is not sent.
        #[serde(default = "default_true")]
        enabled: bool,
    },
    Http {
        url: String,
        #[serde(default)]
        headers: BTreeMap<String, String>,
        #[serde(default = "default_true")]
        enabled: bool,
    },
}

impl ContextServer {
    /// Whether the entry should reach the agent at all.
    pub fn is_enabled(&self) -> bool {
        match self {
            ContextServer::Stdio { enabled, .. } | ContextServer::Http { enabled, .. } => *enabled,
        }
    }
}

/// Zed's `lsp.<server>.binary` (settings_content/src/project.rs:231-236).
/// `path` is resolved inside the userland, like every other program the
/// engine starts there; `ignore_system_version` has no meaning for a server
/// this editor never downloads and is read and ignored.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct BinarySettings {
    pub path: Option<String>,
    pub arguments: Option<Vec<String>>,
    pub env: Option<BTreeMap<String, String>>,
    pub ignore_system_version: Option<bool>,
}

/// Zed's `lsp.<server>` entry (settings_content/src/project.rs:193-213):
/// how to start the server, what to hand it in `initialize`, and what to
/// answer when it asks `workspace/configuration`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct LspSettings {
    pub binary: Option<BinarySettings>,
    pub initialization_options: Option<serde_json::Value>,
    pub settings: Option<serde_json::Value>,
}

impl LspSettings {
    /// Lay a project's entry over the user's: each of the three keys is taken
    /// whole from the layer that has it, which is how Zed's `MergeFrom`
    /// treats `Option` fields.
    fn merge_from(&mut self, over: &LspSettings) {
        if over.binary.is_some() {
            self.binary = over.binary.clone();
        }
        if over.initialization_options.is_some() {
            self.initialization_options = over.initialization_options.clone();
        }
        if over.settings.is_some() {
            self.settings = over.settings.clone();
        }
    }
}

/// `git` as a project may override it: every field optional, for the same
/// reason [`LanguageSettingsContent`]'s are.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct GitSettingsContent {
    pub inline_blame: Option<InlineBlameContent>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct InlineBlameContent {
    pub enabled: Option<bool>,
}

/// A project's `.zed/settings.json` — Zed's `ProjectSettingsContent`
/// (settings_content/src/project.rs:44-60), which is what limits it to the
/// editor, language, LSP and git keys. `theme`, the panels and
/// `agent_servers` are user settings and only user settings; a project that
/// writes them is ignored on those keys, never obeyed, which is the whole
/// point of the distinction: cloning a repository must not restyle the
/// editor or start programs.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct ProjectSettingsContent {
    #[serde(flatten)]
    pub defaults: LanguageSettingsContent,
    #[serde(deserialize_with = "lenient_languages")]
    pub languages: BTreeMap<String, LanguageSettingsContent>,
    #[serde(deserialize_with = "lenient_lsp")]
    pub lsp: BTreeMap<String, LspSettings>,
    pub git: Option<GitSettingsContent>,
    /// `file_types`, as a project may set it — a repository that names its
    /// own `*.tpl` files is exactly the case Zed allows here.
    pub file_types: BTreeMap<String, Vec<String>>,
}

impl ProjectSettingsContent {
    /// Parse a project's settings file. The whole file is refused when it is
    /// not JSON — there is nothing to salvage from half a file — but a bad
    /// *entry* inside `languages` or `lsp` costs that entry alone.
    pub(crate) fn parse(text: &str) -> Result<Self, String> {
        settings_json::parse_json_with_comments::<Self>(text).map_err(|err| err.to_string())
    }
}

/// Wrap guides and bounded wrap need a column that means something: a line
/// length of 3 is not a line and one of 5000 is off every screen.
const MIN_LINE_LENGTH: u32 = 20;
const MAX_LINE_LENGTH: u32 = 1000;

/// Where a new terminal starts — Zed's `terminal.working_directory`
/// (assets/settings/default.json:1949-1972), minus the object form
/// `{"always": {"directory": …}}`, which a phone with one projects directory
/// has no use for; it parses as the default rather than breaking the file.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum TerminalWorkingDirectory {
    /// The project's root — Zed's default, and this app's.
    #[default]
    CurrentProjectDirectory,
    /// The directory of the file that has the editor, falling back to the
    /// project root when nothing is open (or a picture is).
    CurrentFileDirectory,
    /// Zed's third value. A workspace here has exactly one project, so it is
    /// the same directory as `current_project_directory`; accepted so a
    /// settings file pasted from Zed keeps working.
    FirstProjectDirectory,
    /// The shell's home: the userland's `/root`, or the host shell's `HOME`.
    AlwaysHome,
}

/// Zed's `terminal` section, the three keys this dock acts on
/// (terminal/src/terminal_settings.rs:46-49, 119). Everything else under
/// `"terminal"` — fonts, blinking, the dock side — is left alone rather than
/// refused, because the file is the user's and a Zed config pasted whole must
/// not reset every other setting to its default.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct TerminalSettings {
    #[serde(deserialize_with = "lenient_working_directory")]
    pub working_directory: TerminalWorkingDirectory,
    /// Added to the shell's environment, after the app's own — so a user's
    /// `EDITOR` or `PATH` wins over ours, as in Zed (`terminal.env`).
    pub env: BTreeMap<String, String>,
    /// Scrollback, in rows: Zed's `max_scroll_history_lines`, default 10 000
    /// and capped at 100 000 (default.json:2094-2097). `scrollback_lines` is
    /// taken as a spelling of the same thing.
    #[serde(alias = "scrollback_lines")]
    pub max_scroll_history_lines: u32,
}

/// Zed's own ceiling on scrollback (default.json:2095 — "all bigger values
/// set will be treated as 100_000"): past it a terminal is a memory leak.
pub const MAX_SCROLL_HISTORY_LINES: u32 = 100_000;

impl Default for TerminalSettings {
    fn default() -> Self {
        Self {
            working_directory: TerminalWorkingDirectory::default(),
            env: BTreeMap::new(),
            max_scroll_history_lines: 10_000,
        }
    }
}

/// `working_directory` read leniently: Zed's object form and a misspelling
/// both fall back to the default with a log line, rather than failing the
/// whole `Settings` parse and silently resetting the theme and the font.
fn lenient_working_directory<'de, D>(
    deserializer: D,
) -> Result<TerminalWorkingDirectory, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let value = serde_json::Value::deserialize(deserializer)?;
    match serde_json::from_value::<TerminalWorkingDirectory>(value.clone()) {
        Ok(directory) => Ok(directory),
        Err(_) => {
            log::warn!(
                "settings: terminal.working_directory {value} is not one of the four names; \
                 using current_project_directory"
            );
            Ok(TerminalWorkingDirectory::default())
        }
    }
}

fn default_true() -> bool {
    true
}

/// When to tell the user the agent is waiting — Zed's
/// `agent.notify_when_agent_waiting` (settings_content/src/agent.rs:556-561),
/// with Zed's default. Zed's two "on" values pick which *screens* get the
/// pop-up; a phone has one screen, so both mean "notify" here and `never`
/// means what it says. All three are kept so a settings file copied from Zed
/// parses as it did there.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum NotifyWhenAgentWaiting {
    #[default]
    PrimaryScreen,
    AllScreens,
    Never,
}

impl NotifyWhenAgentWaiting {
    /// The one question a single-screen device asks of it.
    pub fn is_on(self) -> bool {
        !matches!(self, NotifyWhenAgentWaiting::Never)
    }
}

/// Zed's `agent` section, reduced to the keys this panel acts on.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct AgentSettings {
    pub notify_when_agent_waiting: NotifyWhenAgentWaiting,
}

/// The mode Vim mode starts a buffer in — Zed's `vim.default_mode`
/// (docs/src/vim.md "Changing vim mode settings"), minus the two Helix modes,
/// which this editor does not have.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum VimDefaultMode {
    #[default]
    Normal,
    Insert,
    Replace,
    Visual,
    VisualLine,
    VisualBlock,
}

/// How Vim's unnamed register and the system clipboard relate — Zed's
/// `vim.use_system_clipboard`, with Zed's three answers and Zed's default
/// (assets/settings/default.json `"use_system_clipboard": "always"`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum UseSystemClipboard {
    /// Every yank and delete lands on the clipboard, and a paste reads it.
    #[default]
    Always,
    /// Only the `"+` and `"*` registers touch the clipboard.
    Never,
    /// Yanks go to the clipboard; deletes stay in Vim's registers.
    OnYank,
}

/// Zed's `vim` object, with the two keys the editor reads. The rest of Zed's
/// (`use_smartcase_find`, `gdefault`, digraphs…) describe behaviour this
/// port does not have yet, and a setting that does nothing is worse than no
/// setting.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct VimSettings {
    pub default_mode: VimDefaultMode,
    pub use_system_clipboard: UseSystemClipboard,
}

/// Zed's `markdown_preview` section (assets/settings/default.json:119-128).
///
/// Zed's own two keys there are about the rendered width, which is not a
/// question on a phone-sized dock, so neither is carried. What is carried is
/// the one thing the preview has that Zed makes a *separate item* of:
/// `markdown::OpenFollowingPreview` opens a preview that tracks the editor
/// (markdown_preview.rs:33-34), and since this app has one preview panel
/// rather than a pane full of them, following is a setting and a toolbar
/// toggle instead of a second way to open it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct MarkdownPreviewSettings {
    /// Whether the preview follows the editor's scroll, and a tap in the
    /// preview moves the editor's caret.
    pub scroll_sync: bool,
}

impl Default for MarkdownPreviewSettings {
    fn default() -> Self {
        Self { scroll_sync: true }
    }
}

/// Zed's `toolbar` block (assets/settings/default.json:544-555): which of the
/// editor toolbar's three parts are drawn.
///
/// Three of Zed's five keys, the three that name something this app has.
/// `agent_review` and `code_actions` describe toolbar items there is no
/// version of here, and a setting that does nothing is worse than no setting.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct ToolbarSettings {
    /// The file name and the symbol path at the caret, on the left.
    pub breadcrumbs: bool,
    /// The icon buttons on the right — find, project symbols, preview.
    pub quick_actions: bool,
    /// The selections menu: how many carets and selections there are, and
    /// the multi-caret actions, as a popover.
    pub selections_menu: bool,
}

impl Default for ToolbarSettings {
    fn default() -> Self {
        Self {
            breadcrumbs: true,
            quick_actions: true,
            selections_menu: true,
        }
    }
}

/// Zed's `tab_bar` block (assets/settings/default.json:1386-1397): whether
/// the strip is drawn at all, and which of its two fixed button groups are.
///
/// `show_pinned_tabs_in_separate_row` is left out: this app pins tabs to the
/// left of one row and has no second row to put them in.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct TabBarSettings {
    /// The tab strip itself. Off leaves the tabs reachable by the switcher,
    /// the palette and the project panel, which is Zed's bargain too.
    pub show: bool,
    /// The `←` / `→` group at the leading edge (tab_bar.rs:103-112).
    pub show_nav_history_buttons: bool,
    /// The `⇥ + ⊞ ⤢` group at the trailing edge (tab_bar.rs:141-150).
    pub show_tab_bar_buttons: bool,
}

impl Default for TabBarSettings {
    fn default() -> Self {
        Self {
            show: true,
            show_nav_history_buttons: true,
            show_tab_bar_buttons: true,
        }
    }
}

/// Zed's `status_bar` block (assets/settings/default.json:1904-1913): the two
/// right-hand buttons that can be switched off.
///
/// Zed's `experimental.show` and `show_active_file` are left out: the status
/// bar here is the only route to the docks on a phone, and the file name is
/// in the tab and the breadcrumbs already.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct StatusBarSettings {
    /// The language name, which opens the language selector.
    pub active_language_button: bool,
    /// The `line:column` readout, which opens go-to-line.
    pub cursor_position_button: bool,
}

impl Default for StatusBarSettings {
    fn default() -> Self {
        Self {
            active_language_button: true,
            cursor_position_button: true,
        }
    }
}

/// Zed's `reduce_motion` (assets/settings/default.json:280-289), with a third
/// answer this platform needs.
///
/// Zed has two: `on` renders spinners and pulsing labels in a static state,
/// `off` never does. Android already asks this question system-wide —
/// Settings ▸ Accessibility ▸ Remove animations sets
/// `Settings.Global.ANIMATOR_DURATION_SCALE` to 0 — and an editor that made
/// you answer it twice would be ignoring an answer you had already given. So
/// [`ReduceMotion::Auto`] is the default and means "whatever the system was
/// told", and Zed's two words still override it in both directions.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum ReduceMotion {
    /// Always still.
    On,
    /// Always animated, whatever the system's animator scale says.
    Off,
    /// Follow `Settings.Global.ANIMATOR_DURATION_SCALE`. This app's default.
    #[default]
    Auto,
}

/// Everything the app can be configured with. Every field here is wired to
/// something visible — a setting that does nothing is worse than no setting.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    /// Which theme, in Zed's own two shapes — see [`ThemeSelection`].
    pub theme: ThemeSelection,
    /// A partial style object layered over whichever theme is in effect —
    /// Zed's `experimental.theme_overrides`
    /// (`settings_content/src/theme.rs:247-248`), spelled without the prefix
    /// as well because that is the name it is asked for by. The keys are a
    /// theme file's own style keys (`"editor.background"`, `"syntax"`), and
    /// nothing in the engine reads them: the UI merges them into the palette
    /// it parsed. Kept as raw JSON for exactly that reason.
    #[serde(alias = "experimental.theme_overrides")]
    #[serde(deserialize_with = "lenient_object")]
    pub theme_overrides: serde_json::Map<String, serde_json::Value>,
    /// Which icon theme — Zed's `icon_theme`, same two shapes as `theme`.
    pub icon_theme: IconThemeSelection,
    /// Whose shortcuts to start from — see [`BaseKeymap`].
    pub base_keymap: BaseKeymap,
    /// Editor text size, in scale-independent pixels.
    pub buffer_font_size: f32,
    /// The editor and terminal face, by family name. `null` — the default —
    /// is the bundled Lilex, which is Zed's `.ZedMono`.
    pub buffer_font_family: Option<String>,
    /// Families to fall back to for glyphs the buffer font has not got.
    pub buffer_font_fallbacks: Vec<String>,
    /// OpenType features for the buffer font, `{"calt": false}` to switch
    /// ligatures off.
    pub buffer_font_features: FontFeatures,
    /// A CSS weight for the buffer font, 100..900.
    pub buffer_font_weight: f32,
    /// How tall a line of the buffer is, as a multiple of the font's height.
    pub buffer_line_height: BufferLineHeight,
    /// The chrome's face. `null` is the bundled IBM Plex Sans, Zed's
    /// `.ZedSans`.
    pub ui_font_family: Option<String>,
    /// The chrome's text size — and, because gpui sets
    /// `window.rem_size = ui_font_size` (`theme_settings/src/settings.rs:619`),
    /// the rem every chrome dimension in this app is a multiple of.
    pub ui_font_size: f32,
    /// Spaces inserted by the Tab key.
    pub tab_size: u32,
    /// Indent with tab characters rather than spaces — Zed's `hard_tabs`.
    pub hard_tabs: bool,
    /// What a line longer than the pane does.
    pub soft_wrap: SoftWrap,
    /// The column `soft_wrap: "bounded"` wraps at, and the active wrap
    /// guide's column — Zed's `preferred_line_length`.
    pub preferred_line_length: u32,
    /// Further columns to draw a guide at — Zed's `wrap_guides`.
    pub wrap_guides: Vec<u32>,
    /// Whether the wrap guides above are drawn — Zed's `show_wrap_guides`.
    pub show_wrap_guides: bool,
    /// Which whitespace gets a visible glyph — Zed's `show_whitespaces`.
    pub show_whitespaces: ShowWhitespaces,
    /// Zed's `remove_trailing_whitespace_on_save`, applied by the save path
    /// beside `format_on_save`.
    pub remove_trailing_whitespace_on_save: bool,
    /// Zed's `ensure_final_newline_on_save`, likewise.
    pub ensure_final_newline_on_save: bool,
    /// Line numbers counted from the caret — Zed's `relative_line_numbers`.
    pub relative_line_numbers: RelativeLineNumbers,
    /// Zed's `gutter` block; only `line_numbers` is read here.
    pub gutter: GutterSettings,
    /// How far the caret's row is washed — Zed's `current_line_highlight`.
    pub current_line_highlight: CurrentLineHighlight,
    /// Zed's `cursor_shape` and `cursor_blink`.
    pub cursor_shape: CursorShape,
    pub cursor_blink: bool,
    /// Zed's `scrollbar` block — the marks down the track.
    pub scrollbar: ScrollbarSettings,
    /// Zed's `minimap` block.
    pub minimap: MinimapSettings,
    /// Zed's `diagnostics` block; only `inline` is read here.
    pub diagnostics: DiagnosticsSettings,
    pub format_on_save: FormatOnSave,
    pub formatter: Formatter,
    pub code_actions_on_format: BTreeMap<String, bool>,
    /// Whether any language server is started at all.
    pub enable_language_server: bool,
    pub autosave: Autosave,
    /// How much of the last session comes back at launch — see
    /// [`RestoreOnStartup`].
    pub restore_on_startup: RestoreOnStartup,
    /// Zed's `close_on_file_delete` (default.json:194): a tab whose file is
    /// deleted on disk closes itself. Off by default, as in Zed — and a tab
    /// with unsaved edits is never closed this way, which is Zed's rule too
    /// (workspace/src/item.rs:894-896 checks `!item.is_dirty`).
    pub close_on_file_delete: bool,
    /// Per-language overrides of the editor keys above, keyed by the
    /// language's display name ("Rust", "C++") as Zed keys them. Lenient per
    /// entry, as `agent_servers` is: one malformed language must not reset
    /// the file.
    #[serde(deserialize_with = "lenient_languages")]
    pub languages: BTreeMap<String, LanguageSettingsContent>,
    /// Per-server configuration, keyed by the server's name
    /// ("rust-analyzer", "clangd") as Zed keys them. Lenient per entry.
    #[serde(deserialize_with = "lenient_lsp")]
    pub lsp: BTreeMap<String, LspSettings>,
    /// Zed's `file_types` (assets/settings/default.json): globs the user maps
    /// onto a language, keyed by the language's display name —
    /// `{"JSON": ["*.jsonc", ".babelrc"]}`. Consulted before the built-in
    /// suffix table, so it can override it as well as extend it.
    pub file_types: BTreeMap<String, Vec<String>>,
    /// Zed's `vim_mode`: modal editing in every buffer. Off by default, as in
    /// Zed, where it is a welcome-screen checkbox.
    pub vim_mode: bool,
    /// Zed's `vim` object; read only while `vim_mode` is on.
    pub vim: VimSettings,
    pub git: GitSettings,
    /// Zed's `inlay_hints` — see [`InlayHintSettings`].
    pub inlay_hints: InlayHintSettings,
    /// The editor's tab strip — Zed's `tabs`.
    pub tabs: TabSettings,
    /// Provisional tabs — Zed's `preview_tabs`.
    pub preview_tabs: PreviewTabsSettings,
    /// Most tabs one pane keeps; opening past it closes the least recently
    /// used one. Zed's `max_tabs`, whose default is null — unlimited.
    pub max_tabs: Option<u32>,
    pub project_panel: ProjectPanelSettings,
    /// The outline panel — Zed's `outline_panel.dock` and `default_width`
    /// (assets/settings/default.json:951-957).
    pub outline_panel: PanelSettings,
    /// The git panel — Zed's `git_panel.dock` and `default_width`.
    pub git_panel: PanelSettings,
    /// Search across the project. Zed has no dock for this (it is a pane item
    /// there); here it is a panel like the others and says so.
    pub project_search: PanelSettings,
    /// The Markdown, SVG and tabular preview, likewise.
    pub preview: PanelSettings,
    /// Zed's `markdown_preview` — see [`MarkdownPreviewSettings`].
    pub markdown_preview: MarkdownPreviewSettings,
    /// The editor toolbar's parts — Zed's `toolbar`.
    pub toolbar: ToolbarSettings,
    /// The tab strip and its two button groups — Zed's `tab_bar`.
    pub tab_bar: TabBarSettings,
    /// The status bar's two optional readouts — Zed's `status_bar`.
    pub status_bar: StatusBarSettings,
    /// Whether non-essential motion is rendered still — Zed's
    /// `reduce_motion`, plus this platform's `auto`.
    pub reduce_motion: ReduceMotion,
    /// Zed's `command_aliases` (assets/settings/default.json:2811-2815): a
    /// string typed into the command palette, and the action name it stands
    /// for — `{"W": "workspace::Save"}`. A `BTreeMap` so the list a settings
    /// screen prints does not reshuffle between launches.
    pub command_aliases: BTreeMap<String, String>,
    /// The agent panel — Zed's `agent_panel.dock` and `default_width`.
    pub agent_panel: PanelSettings,
    /// The terminal dock: where a shell starts, what it inherits, how much it
    /// remembers.
    pub terminal: TerminalSettings,
    /// ACP agents the user configured, by the name the panel lists them under.
    ///
    /// A `BTreeMap`, not a `HashMap`, and that is not a detail: this is what
    /// the picker is built from, and a hash map would reorder the list on
    /// every launch.
    ///
    /// Deserialized leniently, per entry: settings.json is a file people edit
    /// by hand, and one half-written agent must cost that one entry, not the
    /// whole parse — a strict map here silently reset *every* setting to its
    /// default the moment someone typed `"Claude": "claude"`.
    #[serde(deserialize_with = "lenient_agent_servers")]
    pub agent_servers: BTreeMap<String, CustomAgent>,
    /// MCP context servers, by name — Zed's `context_servers`, forwarded to
    /// the agent in `session/new` and `session/load`. Lenient per entry for
    /// the reason `agent_servers` is.
    #[serde(deserialize_with = "lenient_context_servers")]
    pub context_servers: BTreeMap<String, ContextServer>,
    /// Zed's `agent` section.
    pub agent: AgentSettings,
}

/// Each `agent_servers` entry parsed on its own, a broken one dropped with a
/// log rather than sinking its neighbours — and, because a bad field anywhere
/// fails the whole `Settings` parse, rather than sinking the entire file. The
/// Kotlin parser (`AppSettings.parseAgents`) is lenient the same way.
fn lenient_agent_servers<'de, D>(deserializer: D) -> Result<BTreeMap<String, CustomAgent>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    lenient_map(deserializer, "agent_servers")
}

/// `context_servers`, with the same per-entry leniency: one `{"command": 7}`
/// must not take the theme with it.
fn lenient_context_servers<'de, D>(
    deserializer: D,
) -> Result<BTreeMap<String, ContextServer>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    lenient_map(deserializer, "context_servers")
}

/// A map read entry by entry, a malformed one dropped with a log rather than
/// sinking the file — the same policy as [`lenient_agent_servers`], for the
/// same reason. `what` names the key in the log line.
fn lenient_map<'de, D, T>(deserializer: D, what: &str) -> Result<BTreeMap<String, T>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: serde::de::DeserializeOwned,
{
    let value = serde_json::Value::deserialize(deserializer)?;
    let raw: serde_json::Map<String, serde_json::Value> = match value {
        serde_json::Value::Object(map) => map,
        other => {
            log::warn!("settings: {what} is {other} rather than an object; ignored");
            return Ok(BTreeMap::new());
        }
    };
    Ok(raw
        .into_iter()
        .filter_map(|(name, value)| match serde_json::from_value(value) {
            Ok(entry) => Some((name, entry)),
            Err(err) => {
                log::warn!("settings: {what} entry {name:?} is malformed ({err}); dropped");
                None
            }
        })
        .collect())
}

/// An object read as JSON, anything else logged and dropped.
///
/// `theme_overrides` is a style table the UI merges into a palette; nothing
/// here understands a single key of it, so there is nothing to validate
/// beyond "is it an object". A `"theme_overrides": true` must cost that key
/// alone rather than resetting the file, which is the policy every other
/// hand-editable key in here follows.
fn lenient_object<'de, D>(
    deserializer: D,
) -> Result<serde_json::Map<String, serde_json::Value>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    match serde_json::Value::deserialize(deserializer)? {
        serde_json::Value::Object(map) => Ok(map),
        other => {
            log::warn!("settings: theme_overrides is {other} rather than an object; ignored");
            Ok(serde_json::Map::new())
        }
    }
}

fn lenient_languages<'de, D>(
    deserializer: D,
) -> Result<BTreeMap<String, LanguageSettingsContent>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    lenient_map(deserializer, "languages")
}

fn lenient_lsp<'de, D>(deserializer: D) -> Result<BTreeMap<String, LspSettings>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    lenient_map(deserializer, "lsp")
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            theme: ThemeSelection::default(),
            theme_overrides: serde_json::Map::new(),
            icon_theme: IconThemeSelection::default(),
            base_keymap: BaseKeymap::VSCode,
            buffer_font_size: 14.0,
            buffer_font_family: None,
            buffer_font_fallbacks: Vec::new(),
            buffer_font_features: FontFeatures::default(),
            buffer_font_weight: DEFAULT_FONT_WEIGHT,
            buffer_line_height: BufferLineHeight::default(),
            ui_font_family: None,
            ui_font_size: DEFAULT_UI_FONT_SIZE,
            tab_size: 4,
            hard_tabs: false,
            soft_wrap: SoftWrap::default(),
            preferred_line_length: 80,
            wrap_guides: Vec::new(),
            show_wrap_guides: true,
            show_whitespaces: ShowWhitespaces::default(),
            remove_trailing_whitespace_on_save: true,
            ensure_final_newline_on_save: true,
            relative_line_numbers: RelativeLineNumbers::default(),
            gutter: GutterSettings::default(),
            current_line_highlight: CurrentLineHighlight::default(),
            cursor_shape: CursorShape::default(),
            cursor_blink: true,
            scrollbar: ScrollbarSettings::default(),
            minimap: MinimapSettings::default(),
            diagnostics: DiagnosticsSettings::default(),
            format_on_save: FormatOnSave::Off,
            formatter: Formatter::Auto,
            code_actions_on_format: BTreeMap::new(),
            enable_language_server: true,
            autosave: Autosave::Off,
            restore_on_startup: RestoreOnStartup::LastSession,
            close_on_file_delete: false,
            languages: BTreeMap::new(),
            lsp: BTreeMap::new(),
            file_types: BTreeMap::new(),
            vim_mode: false,
            vim: VimSettings::default(),
            git: GitSettings::default(),
            inlay_hints: InlayHintSettings::default(),
            tabs: TabSettings::default(),
            preview_tabs: PreviewTabsSettings::default(),
            max_tabs: None,
            project_panel: ProjectPanelSettings::default(),
            outline_panel: PanelSettings::new(DockSide::Right, 300.0),
            git_panel: PanelSettings::new(DockSide::Right, 360.0),
            project_search: PanelSettings::new(DockSide::Right, 360.0),
            preview: PanelSettings::new(DockSide::Right, 400.0),
            markdown_preview: MarkdownPreviewSettings::default(),
            toolbar: ToolbarSettings::default(),
            tab_bar: TabBarSettings::default(),
            status_bar: StatusBarSettings::default(),
            reduce_motion: ReduceMotion::default(),
            command_aliases: BTreeMap::new(),
            agent_panel: PanelSettings::new(DockSide::Right, 400.0),
            terminal: TerminalSettings::default(),
            agent_servers: BTreeMap::new(),
            context_servers: BTreeMap::new(),
            agent: AgentSettings::default(),
        }
    }
}

impl Settings {
    /// Clamp values a hand-edited file could put out of range. A settings
    /// file is user input, and a font size of 0 or 10000 should not be able
    /// to make the editor unusable — or unrecoverable, since fixing it means
    /// reading the very screen it broke.
    fn sanitized(mut self) -> Self {
        self.buffer_font_size = self.buffer_font_size.clamp(6.0, 48.0);
        self.ui_font_size = self
            .ui_font_size
            .clamp(crate::appearance::MIN_UI_FONT_SIZE, crate::appearance::MAX_UI_FONT_SIZE);
        self.buffer_font_weight = self.buffer_font_weight.clamp(100.0, 900.0);
        self.buffer_line_height = self.buffer_line_height.sanitized();
        // A family name is a name, not whitespace: an empty string would ask
        // the platform for "" and get its default back with no way to tell
        // that from "unset", which is what `null` already means.
        self.ui_font_family = self.ui_font_family.filter(|name| !name.trim().is_empty());
        self.buffer_font_family = self.buffer_font_family.filter(|name| !name.trim().is_empty());
        self.buffer_font_fallbacks
            .retain(|name| !name.trim().is_empty());
        self.tab_size = self.tab_size.clamp(1, 16);
        self.preferred_line_length = self
            .preferred_line_length
            .clamp(MIN_LINE_LENGTH, MAX_LINE_LENGTH);
        // A delay of 0 would save on every keystroke — a write per key on
        // flash storage, and an LSP `didSave` per key with it.
        if let Autosave::AfterDelay { milliseconds } = &mut self.autosave {
            *milliseconds = (*milliseconds).clamp(100, 600_000);
        }
        // A panel 4dp wide is a panel nobody can grab the edge of, and one
        // wider than a tablet leaves no editor at all. The UI clamps against
        // the *window* as well; this is the hand-edited-file guard.
        self.project_panel.default_width = self.project_panel.default_width.clamp(120.0, 900.0);
        // An indent of 0 makes the tree unreadable and one of 200 makes it
        // unusable; both are one typo away in a hand-edited file.
        self.project_panel.indent_size = self.project_panel.indent_size.clamp(4.0, 64.0);
        // `"max_tabs": 0` would close every tab as it opened. Zed's own
        // setting is a `NonZeroUsize`, which is the same refusal.
        self.max_tabs = self.max_tabs.filter(|max| *max > 0);
        for panel in [
            &mut self.outline_panel,
            &mut self.git_panel,
            &mut self.project_search,
            &mut self.preview,
            &mut self.agent_panel,
        ] {
            panel.default_width = panel.default_width.clamp(120.0, 900.0);
        }
        // Zed's rule: anything above the ceiling is the ceiling. The floor is
        // the vendored emulator's own minimum, below which it substitutes its
        // default — a setting of 0 must not quietly mean 2000.
        self.terminal.max_scroll_history_lines = self
            .terminal
            .max_scroll_history_lines
            .clamp(100, MAX_SCROLL_HISTORY_LINES);
        self
    }

    /// The buffer font, resolved: the family, the fallbacks, the size, the
    /// weight, the leading and the OpenType features, in one value the UI can
    /// hand to a text layout without reading six keys and knowing which
    /// defaults each one falls back to.
    ///
    /// Zed's `ThemeSettings::buffer_font` is the same gathering
    /// (`theme_settings/src/settings.rs`), and the editor and the terminal
    /// both draw from it — a terminal in a different face from the editor is
    /// two monospace fonts on one screen, which is one too many.
    pub fn buffer_font(&self) -> ResolvedFont {
        ResolvedFont {
            family: self.buffer_font_family.clone(),
            fallbacks: self.buffer_font_fallbacks.clone(),
            size: self.buffer_font_size,
            weight: self.buffer_font_weight,
            line_height: self.buffer_line_height.value(),
            features: self.buffer_font_features.clone(),
        }
    }

    /// The chrome font, resolved. The UI has no `ui_font_weight` or
    /// `ui_font_features` row — Zed has both keys, and neither describes
    /// anything this app's chrome varies — so those two come out at their
    /// defaults, and the line height is Zed's φ leading for labels rather
    /// than `buffer_line_height`, which is the *buffer's*.
    pub fn ui_font(&self) -> ResolvedFont {
        ResolvedFont {
            family: self.ui_font_family.clone(),
            fallbacks: Vec::new(),
            size: self.ui_font_size,
            weight: DEFAULT_FONT_WEIGHT,
            line_height: BufferLineHeight::Comfortable.value(),
            features: FontFeatures::default(),
        }
    }

    /// The top level of the file as a language layer — the bottom of the
    /// stack [`LanguageSettings::resolve`] builds.
    fn language_defaults(&self) -> LanguageSettingsContent {
        LanguageSettingsContent {
            tab_size: Some(self.tab_size),
            hard_tabs: Some(self.hard_tabs),
            soft_wrap: Some(self.soft_wrap),
            preferred_line_length: Some(self.preferred_line_length),
            wrap_guides: Some(self.wrap_guides.clone()),
            format_on_save: Some(self.format_on_save),
            formatter: Some(self.formatter.clone()),
            code_actions_on_format: Some(self.code_actions_on_format.clone()),
            enable_language_server: Some(self.enable_language_server),
            show_whitespaces: Some(self.show_whitespaces),
            show_wrap_guides: Some(self.show_wrap_guides),
            remove_trailing_whitespace_on_save: Some(self.remove_trailing_whitespace_on_save),
            ensure_final_newline_on_save: Some(self.ensure_final_newline_on_save),
        }
    }

    /// The user's `lsp.<server>` entry with the project's, if any, on top.
    pub(crate) fn lsp_settings_for(
        &self,
        server: &str,
        local: Option<&ProjectSettingsContent>,
    ) -> LspSettings {
        let mut settings = self.lsp.get(server).cloned().unwrap_or_default();
        if let Some(over) = local.and_then(|local| local.lsp.get(server)) {
            settings.merge_from(over);
        }
        settings
    }
}

/// The grammar's own `config.toml` as a settings layer: the `tab_size` and
/// `hard_tabs` a language ships with (Go indents with tabs; YAML with two
/// spaces). Nothing for a grammar we do not carry.
fn grammar_layer(grammar: Option<&str>) -> Option<LanguageSettingsContent> {
    let json = crate::language_config::config_json(grammar?)?;
    let config: serde_json::Value = serde_json::from_str(json).ok()?;
    Some(LanguageSettingsContent {
        tab_size: config["tab_size"].as_u64().map(|size| size as u32),
        // `false` is the config's default and says nothing; only a grammar
        // that asks for tabs is a layer at all.
        hard_tabs: config["hard_tabs"].as_bool().filter(|tabs| *tabs),
        ..Default::default()
    })
}

/// The name Zed's `languages` map keys a grammar under: the `name` in the
/// grammar's `config.toml` ("Rust", "C++", "TSX"), not the grammar's own
/// directory name. Nothing for a grammar we do not carry.
pub(crate) fn language_display_name(grammar: &str) -> Option<String> {
    let json = crate::language_config::config_json(grammar)?;
    let config: serde_json::Value = serde_json::from_str(json).ok()?;
    config["name"].as_str().map(str::to_owned)
}

/// The commented file written on first run. The comments are the
/// documentation, and `settings_json` preserves them through UI edits.
const DEFAULT_FILE: &str = r##"// Thragg settings.
//
// This file is yours: comments and formatting survive changes made from the
// settings screen. Keys follow Zed's names where they mean the same thing.
//
// A project can carry its own .zed/settings.json, as in Zed: the editor,
// language, lsp and git keys in it override these for that project alone.
// The theme, the panels and agent_servers are yours and only yours.
{
  // The theme, in either of Zed's two shapes: a name on its own —
  // "theme": "One Dark" — or one name per appearance with a rule for
  // choosing between them. "system" follows the device's light/dark
  // setting; "light" and "dark" pin it.
  //
  // Themes are the eleven families bundled with the app plus any *.json
  // theme file you drop into the "themes" folder beside this one.
  "theme": {
    "mode": "system",
    "light": "One Light",
    "dark": "One Dark"
  },

  // A partial style object laid over whichever theme is in effect. The keys
  // are the theme file's own, e.g.
  //
  //   "theme_overrides": {
  //     "editor.background": "#101014",
  //     "syntax": { "comment": { "font_style": "italic" } }
  //   }
  "theme_overrides": {},

  // Which icon theme the file tree and the tabs draw from: the bundled
  // "Zed (Default)", or a theme file in the "icon_themes" folder.
  "icon_theme": "Zed (Default)",

  // Whose shortcuts to start from: "VSCode" (Zed's own default), "JetBrains",
  // "SublimeText", "Atom", "Emacs", or "None" — which switches every built-in
  // binding off and leaves keymap.json as the whole keymap.
  "base_keymap": "VSCode",

  // Editor text size, in scale-independent pixels.
  "buffer_font_size": 14,

  // The editor and terminal face. null is the bundled Lilex — Zed's own
  // monospace. Any font installed on the device, or dropped into the
  // "fonts" folder beside this file, can be named here.
  "buffer_font_family": null,

  // Families to try for glyphs the buffer font has not got, in order.
  "buffer_font_fallbacks": [],

  // OpenType features for the buffer font. { "calt": false, "liga": false }
  // switches ligatures off.
  "buffer_font_features": {},

  // The buffer font's weight, 100 to 900.
  "buffer_font_weight": 400,

  // How tall a line is, as a multiple of the font's height:
  // "comfortable" (1.618), "standard" (1.3), or { "custom": 1.4 }.
  "buffer_line_height": "comfortable",

  // The interface face and its size. The size is also the app's rem: every
  // bar, row, gap and icon is a multiple of it, so this scales the whole
  // interface rather than only its text.
  "ui_font_family": null,
  "ui_font_size": 16,

  // Spaces inserted by the Tab key.
  "tab_size": 4,

  // Indent with tab characters instead of spaces. A file that is already
  // indented one way keeps going that way; this decides for the rest.
  "hard_tabs": false,

  // What a line longer than the editor does: "none" scrolls it off the
  // right edge, "editor_width" wraps it, "bounded" wraps it at
  // preferred_line_length or the editor's width, whichever comes first.
  "soft_wrap": "none",

  // The column "bounded" wraps at, drawn as a guide while it is in use.
  "preferred_line_length": 80,

  // Further columns to draw a guide at, e.g. [80, 120].
  "wrap_guides": [],

  // Format the file when it is saved: "on", "off", or "language_server"
  // (on, but never through an external formatter).
  "format_on_save": "off",

  // What "format" means: "auto" or "language_server" ask the language
  // server; "none" never formats; an external program reads the file on
  // stdin and prints the result, and runs inside the Linux userland:
  //
  //   "formatter": {
  //     "external": { "command": "rustfmt", "arguments": ["--edition", "2021"] }
  //   }
  "formatter": "auto",

  // Code actions the server runs before formatting, e.g.
  //   { "source.organizeImports": true }
  "code_actions_on_format": {},

  // Save without being asked: "off", "on_focus_change" (when you switch
  // tabs), "on_window_change" (when the app goes to the background), or
  // { "after_delay": { "milliseconds": 1000 } } once a file sits unedited
  // that long. Delayed saves do not run the formatter, as in Zed.
  "autosave": "off",

  // How much of the last session comes back when the app is launched again.
  // Zed's three values name how many *windows* return; this app has one, so
  // they name how much of it does:
  //   "last_session"   — the project and everything in it: the pane layout,
  //                      the tabs with their carets and scroll, the docks,
  //                      the terminal tabs (reopened as fresh shells in the
  //                      same directories — a shell dies with the app)
  //   "last_workspace" — the project alone, with a fresh workspace
  //   "none"           — nothing; the app starts on the project picker
  "restore_on_startup": "last_session",

  // Close a tab whose file is deleted on disk. A tab with unsaved edits is
  // never closed this way.
  "close_on_file_delete": false,

  // Any of the editor keys above, per language, keyed by the language's
  // name as Zed spells it — "Rust", "C++", "TypeScript", "TSX", "Go",
  // "Python", "Markdown". Set "enable_language_server": false to keep a
  // language's server from starting.
  //
  //   "languages": {
  //     "Go": { "hard_tabs": true, "format_on_save": "on" },
  //     "Markdown": { "soft_wrap": "editor_width", "enable_language_server": false }
  //   }
  "languages": {},

  // Language servers by name — "rust-analyzer", "clangd", "gopls", "pylsp",
  // "typescript-language-server". "binary" starts a different program (a
  // path inside the userland) or adds arguments; "initialization_options"
  // go out with initialize; "settings" answer workspace/configuration.
  //
  //   "lsp": {
  //     "rust-analyzer": {
  //       "initialization_options": { "check": { "command": "clippy" } }
  //     }
  //   }
  "lsp": {},

  // Which language a file is, when its name does not say. Keyed by the
  // language as the selector lists it, with the globs that claim it; asked
  // before the built-in extension table, so it can also take a suffix away
  // from it. A project's .zed/settings.json may set this too.
  //
  //   "file_types": {
  //     "JSON": ["*.jsonc", ".babelrc"],
  //     "XML": ["**/res/**/*.axml"]
  //   }
  "file_types": {},

  // Modal editing, as in Zed's vim mode. The "vim" object takes Zed's
  // "default_mode" ("normal", "insert", "replace", "visual", "visual_line",
  // "visual_block") and "use_system_clipboard" ("always", "never",
  // "on_yank").
  "vim_mode": false,
  "vim": {
    "default_mode": "normal",
    "use_system_clipboard": "always"
  },

  "git": {
    // Who last touched the line the caret is on, shown after the end of it.
    // Only while the file has no unsaved edits — blame describes the file on
    // disk, and once it is edited the line numbers describe a file that is
    // not there any more.
    "inline_blame": { "enabled": true }
  },

  // Inlay hints: the types and parameter names a language server can show
  // inline, dimmed, without changing the file. Off by default, as in Zed;
  // "editor: toggle inlay hints" in the palette flips "enabled". The three
  // "show_*" keys pick which kinds appear once they are on.
  "inlay_hints": {
    "enabled": false,
    "show_type_hints": true,
    "show_parameter_hints": true,
    "show_other_hints": true
  },

  // Which side each panel docks on, and how wide it opens. "left" or
  // "right"; the terminal has the bottom to itself. Two panels on the same
  // side take turns — opening one closes the other — and the two sides are
  // independent, so a tree on the left and git on the right stay up together.
  "project_panel": {
    // Gitignored files in the tree: "show", "dimmed" or "hide".
    "gitignored_files": "dimmed",
    "dock": "left",
    "default_width": 240,
    // Sibling order: "directories_first", "mixed" or "files_first".
    "sort_mode": "directories_first",
    // Hide the project's own name above the tree.
    "hide_root": false,
    // Fold a run of single-child directories into one row, "a/b/c".
    "auto_fold_dirs": true,
    // Row height: "comfortable", or the tighter "standard".
    "entry_spacing": "comfortable",
    // Indent per nesting level, in dp.
    "indent_size": 20,
    // Colour the entries that have diagnostics, and the folders above them:
    // "off", "errors" or "all".
    "show_diagnostics": "all"
  },

  // The active file's symbol tree. Ctrl+Shift+B opens it; Ctrl+Shift+O is
  // the same symbols as a picker.
  "outline_panel": {
    "dock": "right",
    "default_width": 300
  },

  // The editor's tabs.
  "tabs": {
    // Which end of the tab the close button sits on: "right" or "left".
    "close_position": "right",
    // Show the file's icon in its tab.
    "file_icons": false,
    // Tint a tab's title with the file's git status.
    "git_status": false,
    // Mark tabs whose file has diagnostics: "off", "errors" or "all".
    "show_diagnostics": "off",
    // Which tab takes over when the active one closes: "history" (the one
    // you were on before), "neighbour" (the one to the right), or
    // "left_neighbour".
    "activate_on_close": "history"
  },

  // A tab opened with a single click is a preview tab: its title is in
  // italics and the next single click reuses it, so browsing a project does
  // not leave thirty tabs behind. Editing it, or double-clicking, makes it
  // permanent.
  "preview_tabs": {
    "enabled": true,
    "enable_preview_from_project_panel": true,
    "enable_preview_from_file_finder": false,
    "enable_preview_from_code_navigation": true
  },

  // Most tabs to keep open at once; opening one past this closes the tab you
  // have gone longest without looking at. null is unlimited.
  "max_tabs": null,

  // The tab strip. "show": false hides it entirely — the tabs are still
  // there, reachable from Ctrl+Tab, the palette and the project panel.
  "tab_bar": {
    "show": true,
    // The back/forward arrows at the left end.
    "show_nav_history_buttons": true,
    // The switcher, new-file, split and zoom buttons at the right end.
    "show_tab_bar_buttons": true
  },

  // The row under the tabs.
  "toolbar": {
    // The file name and the symbol path at the caret, which is also the
    // button into the outline.
    "breadcrumbs": true,
    // The icon buttons on the right: find in file, project symbols, preview.
    "quick_actions": true,
    // The selections readout and its menu of multi-caret actions.
    "selections_menu": true
  },

  // The bar along the bottom. The dock buttons are always there — on a phone
  // they are the only way to reach a panel — so only the two readouts can be
  // switched off.
  "status_bar": {
    // The language name, which opens the language selector.
    "active_language_button": true,
    // The line:column readout, which opens go to line.
    "cursor_position_button": true
  },

  // Render non-essential motion — the scroll animations, the toast slide, the
  // pulsing "working" labels — in a static state. "on" always, "off" never,
  // and "auto" (the default here) follows the device's own
  // Accessibility > Remove animations setting.
  "reduce_motion": "auto",

  // Strings you can type into the command palette instead of an action name.
  // The whole query has to match a key, and it is replaced by the action:
  //
  //   "command_aliases": {
  //     "W": "workspace::Save",
  //     "term": "terminal_panel::Toggle"
  //   }
  "command_aliases": {},

  "git_panel": {
    "dock": "right",
    "default_width": 360
  },

  "project_search": {
    "dock": "right",
    "default_width": 360
  },

  "preview": {
    "dock": "right",
    "default_width": 400
  },

  "markdown_preview": {
    // Follow the editor: the preview scrolls to whatever the top of the
    // editor's window is showing, and tapping a block in the preview puts
    // the caret on the line it came from. The toolbar's ⇅ toggles it for
    // the session without touching this.
    "scroll_sync": true
  },

  "agent_panel": {
    "dock": "right",
    "default_width": 400
  },

  "terminal": {
    // Where a new shell starts: "current_project_directory",
    // "current_file_directory" (the open file's directory, else the
    // project) or "always_home".
    "working_directory": "current_project_directory",
    // Added to the shell's environment, after the app's own.
    "env": {},
    // Rows of scrollback a shell keeps. At most 100000.
    "max_scroll_history_lines": 10000
  },

  // ACP agents, by the name the panel lists them under. The command runs
  // inside the Linux userland, so it is a program on Debian's PATH — which
  // is what "npm install -g" puts one on. Anything that speaks the Agent
  // Client Protocol on stdin and stdout works here; the editor installs
  // nothing for you.
  //
  //   "agent_servers": {
  //     "Claude Code": { "command": "claude-code-acp" },
  //     "Gemini CLI": { "command": "gemini", "args": ["--experimental-acp"] }
  //   }
  "agent_servers": {},

  // MCP context servers, handed to the agent when a thread starts — the
  // same shape Zed's "context_servers" has. A "command" entry is a program
  // the agent runs inside the userland over stdio; a "url" entry is an HTTP
  // server it connects to (only agents that advertise HTTP MCP get those).
  //
  //   "context_servers": {
  //     "filesystem": { "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "."] },
  //     "docs": { "url": "https://example.com/mcp" }
  //   }
  "context_servers": {},

  "agent": {
    // Tell you when the agent finishes a turn or is waiting on you while
    // the panel is hidden: "primary_screen" (Zed's default) or "all_screens"
    // both notify — a phone has one screen — and "never" does not.
    "notify_when_agent_waiting": "primary_screen"
  }
}
"##;

fn settings_path_slot() -> &'static Mutex<Option<PathBuf>> {
    static PATH: OnceLock<Mutex<Option<PathBuf>>> = OnceLock::new();
    PATH.get_or_init(|| Mutex::new(None))
}

/// Point the settings at a directory. Called from [`crate::initialize`].
pub(crate) fn set_directory(directory: PathBuf) {
    *settings_path_slot().lock().unwrap() = Some(directory.join("settings.json"));
}

pub(crate) fn settings_path() -> Option<PathBuf> {
    settings_path_slot().lock().unwrap().clone()
}

/// The user's global `tasks.json`, beside `settings.json` — where Zed keeps
/// its own (`paths::tasks_file`, next to the settings file). None until the
/// engine has been given a directory.
pub(crate) fn tasks_path() -> Option<PathBuf> {
    settings_path().map(|path| path.with_file_name("tasks.json"))
}

impl crate::Engine {
    /// The settings file's contents, creating it with documented defaults on
    /// first use. Empty if the engine was never given a directory.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn settings_text(&self) -> String {
        let Some(path) = settings_path() else {
            return String::new();
        };
        match std::fs::read_to_string(&path) {
            Ok(text) => text,
            Err(_) => {
                if let Some(parent) = path.parent() {
                    let _ = std::fs::create_dir_all(parent);
                }
                // Best-effort: if the write fails we still hand back the
                // defaults, so the app runs with a read-only settings file
                // rather than not at all.
                let _ = std::fs::write(&path, DEFAULT_FILE);
                DEFAULT_FILE.to_owned()
            }
        }
    }

    /// The resolved settings. A malformed file falls back to defaults rather
    /// than failing: the user must always be able to reach the settings
    /// screen and fix it.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn settings(&self) -> Settings {
        let text = self.settings_text();
        if text.is_empty() {
            return Settings::default();
        }
        match settings_json::parse_json_with_comments::<Settings>(&text) {
            Ok(settings) => settings.sanitized(),
            Err(err) => {
                log::warn!("settings.json is not valid, using defaults: {err}");
                Settings::default()
            }
        }
    }

    /// Whether the settings file currently parses. The UI uses this to say so
    /// rather than silently showing defaults that aren't in effect.
    pub fn settings_are_valid(&self) -> bool {
        let text = self.settings_text();
        text.is_empty() || settings_json::parse_json_with_comments::<Settings>(&text).is_ok()
    }

    /// The built-in default settings, as documented text — what Zed's
    /// `zed::OpenDefaultSettings` shows read-only (zed/src/zed.rs:306-316).
    /// Never touches the disk.
    pub fn default_settings_text(&self) -> &'static str {
        DEFAULT_FILE
    }

    /// The settings in force for a buffer of `grammar` inside `project`:
    /// the user file, then the project's `.zed/settings.json`, then the
    /// `languages` entries of both, per [`LanguageSettings::resolve`].
    /// Either may be absent — a scratch buffer, a file outside every
    /// project — and the answer is then the user's settings alone.
    ///
    /// **Blocking**: reads settings.json. Call it off the Android main thread.
    pub fn language_settings(
        &self,
        project: Option<ProjectId>,
        grammar: Option<&str>,
    ) -> LanguageSettings {
        let user = self.settings();
        let local = project.and_then(|project| self.project_local_settings(project));
        let name = grammar.and_then(language_display_name);
        LanguageSettings::resolve(
            &user,
            grammar_layer(grammar).as_ref(),
            local.as_ref(),
            name.as_deref(),
        )
    }

    /// [`Engine::language_settings`] for an open buffer: its project is the
    /// one whose root holds its file, its grammar the one it highlights with.
    ///
    /// **Blocking**: reads settings.json. Call it off the Android main thread.
    pub fn buffer_language_settings(&self, buffer: BufferId) -> LanguageSettings {
        let project = self
            .buffer_path(buffer)
            .and_then(|path| self.project_for_path(&path));
        let grammar = self.buffer_language(buffer);
        self.language_settings(project, grammar)
    }

    /// The `file_types` table in force for a project: the user's, with the
    /// project's `.zed/settings.json` on top — see [`crate::file_types`].
    ///
    /// Compiled once and handed back, because the callers that need it need
    /// it for many paths at a time (a worktree scan, a multibuffer's
    /// excerpts) and a glob set is not free to build.
    ///
    /// **Blocking**: reads settings.json.
    pub fn file_types(&self, project: Option<ProjectId>) -> crate::file_types::FileTypes {
        let user = self.settings();
        let local = project.and_then(|project| self.project_local_settings(project));
        match &local {
            Some(local) => {
                crate::file_types::FileTypes::new([&user.file_types, &local.file_types])
            }
            None => crate::file_types::FileTypes::new([&user.file_types]),
        }
    }

    /// The grammar to open `path` with, `file_types` first.
    ///
    /// **Blocking**: reads settings.json. Use [`Self::file_types`] directly
    /// when asking about more than one path.
    pub fn language_for_path(&self, path: &str) -> Option<&'static str> {
        let project = self.project_for_path(std::path::Path::new(path));
        self.file_types(project).language_for_path(path)
    }

    /// The `lsp.<server>` entry in force for a project: the user's, with the
    /// project's on top.
    ///
    /// **Blocking**: reads settings.json.
    pub fn lsp_settings(&self, project: Option<ProjectId>, server: &str) -> LspSettings {
        let user = self.settings();
        let local = project.and_then(|project| self.project_local_settings(project));
        user.lsp_settings_for(server, local.as_ref())
    }

    /// Set one key, given as a path (`["project_panel", "show_ignored"]`),
    /// preserving the rest of the file including comments. Returns the
    /// resolved settings afterwards.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn set_setting(
        &self,
        key_path: &[&str],
        value: serde_json::Value,
    ) -> Result<Settings, EngineError> {
        self.edit_settings_value(key_path, Some(value))
    }

    /// Add or replace one `agent_servers` entry — what the settings screen's
    /// Add Agent form saves, mirroring Zed's own form, which writes the same
    /// key (settings_ui/src/pages/external_agents_page.rs:762-770).
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn set_agent_server(
        &self,
        name: &str,
        agent: CustomAgent,
    ) -> Result<Settings, EngineError> {
        let value = serde_json::to_value(&agent)
            .map_err(|err| EngineError::InvalidSettings(err.to_string()))?;
        // The name goes into the path verbatim — never through the bridge's
        // dot-split `set_setting` route, where an agent called "my.agent"
        // would silently become a nested object.
        self.edit_settings_value(&["agent_servers", name], Some(value))
    }

    /// Remove one `agent_servers` entry, as Zed's trash button does
    /// (external_agents_page.rs:226-249). Removing a name that is not there
    /// is not an error: the entry is gone either way.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn remove_agent_server(&self, name: &str) -> Result<Settings, EngineError> {
        self.edit_settings_value(&["agent_servers", name], None)
    }

    /// Add or replace one `context_servers` entry — the settings screen's
    /// context-server form, which mirrors the agent form beside it. The name
    /// goes into the path whole, for the reason [`Self::set_agent_server`]
    /// gives.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn set_context_server(
        &self,
        name: &str,
        server: ContextServer,
    ) -> Result<Settings, EngineError> {
        let value = serde_json::to_value(&server)
            .map_err(|err| EngineError::InvalidSettings(err.to_string()))?;
        self.edit_settings_value(&["context_servers", name], Some(value))
    }

    /// Remove one `context_servers` entry; a name that is not there is not
    /// an error.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn remove_context_server(&self, name: &str) -> Result<Settings, EngineError> {
        self.edit_settings_value(&["context_servers", name], None)
    }

    fn edit_settings_value(
        &self,
        key_path: &[&str],
        value: Option<serde_json::Value>,
    ) -> Result<Settings, EngineError> {
        let Some(path) = settings_path() else {
            return Err(EngineError::NoSettingsFile);
        };
        let original = self.settings_text();
        let mut text = original.clone();
        let indent = settings_json::infer_json_indent_size(&text).max(1);
        // Surgical: this returns the byte range of just that key's value (or
        // where to insert it — or, with `None`, what to cut), so everything
        // around it, comments included, is untouched.
        let (range, replacement) = settings_json::replace_value_in_json_text(
            &text,
            key_path,
            indent,
            value.as_ref(),
            None,
        );
        text.replace_range(range, &replacement);
        // A value of the wrong shape — a string where an enum has two names,
        // a bool where a number goes — does not break one setting, it breaks
        // the *file*, and `settings()` answers an unparseable file with the
        // defaults. Written blind, one bad key silently reset every other one.
        // So: if the file parsed before and does not now, this write is what
        // broke it, and it is put back.
        let was_valid = settings_json::parse_json_with_comments::<Settings>(&original).is_ok();
        if was_valid && settings_json::parse_json_with_comments::<Settings>(&text).is_err() {
            return Err(EngineError::InvalidSettings(match &value {
                Some(value) => format!("\"{}\" cannot be set to {value}", key_path.join(".")),
                None => format!("\"{}\" cannot be removed", key_path.join(".")),
            }));
        }
        std::fs::write(&path, &text).map_err(|err| EngineError::Io {
            path: path.display().to_string(),
            message: err.to_string(),
        })?;
        self.note_settings_written();
        Ok(self.settings())
    }

    /// Replace the whole file — what an "edit as JSON" screen would save.
    /// Rejects text that doesn't parse, so the app can't be configured into
    /// a state where the settings screen shows defaults it isn't using.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn set_settings_text(&self, text: &str) -> Result<Settings, EngineError> {
        let Some(path) = settings_path() else {
            return Err(EngineError::NoSettingsFile);
        };
        let parsed = settings_json::parse_json_with_comments::<Settings>(text)
            .map_err(|err| EngineError::InvalidSettings(err.to_string()))?;
        std::fs::write(&path, text).map_err(|err| EngineError::Io {
            path: path.display().to_string(),
            message: err.to_string(),
        })?;
        self.note_settings_written();
        Ok(parsed.sanitized())
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use crate::Engine;
    use crate::appearance::ThemeMode;
    use serde_json::json;

    /// The turn-taking lock for the process-global settings path — shared
    /// with the keymap tests, which read `base_keymap` through it.
    pub(crate) fn settings_lock() -> std::sync::MutexGuard<'static, ()> {
        static LOCK: Mutex<()> = Mutex::new(());
        LOCK.lock().unwrap_or_else(|err| err.into_inner())
    }

    /// The settings path is process-global, so these take turns.
    fn with_settings_dir<T>(body: impl FnOnce(&Engine, &std::path::Path) -> T) -> T {
        let _guard = settings_lock();
        let dir = tempfile::tempdir().unwrap();
        set_directory(dir.path().to_path_buf());
        let engine = Engine::new();
        body(&engine, dir.path())
    }

    /// The default file is the app's documentation *and* its first parse; a
    /// key spelled wrong in it would ship as a silent default.
    #[test]
    fn the_default_file_parses_into_the_defaults() {
        let parsed =
            settings_json::parse_json_with_comments::<Settings>(DEFAULT_FILE).unwrap().sanitized();
        assert_eq!(parsed, Settings::default());
    }

    #[test]
    fn the_chrome_visibility_blocks_are_zeds_and_default_to_shown() {
        let settings = settings_json::parse_json_with_comments::<Settings>(
            r#"{
                "toolbar": { "breadcrumbs": false },
                "tab_bar": { "show_tab_bar_buttons": false },
                "status_bar": { "cursor_position_button": false }
            }"#,
        )
        .unwrap();
        // A key named is the only one that moves; its neighbours keep Zed's
        // default, which is what `#[serde(default)]` on the block buys.
        assert!(!settings.toolbar.breadcrumbs);
        assert!(settings.toolbar.quick_actions);
        assert!(settings.toolbar.selections_menu);
        assert!(settings.tab_bar.show);
        assert!(settings.tab_bar.show_nav_history_buttons);
        assert!(!settings.tab_bar.show_tab_bar_buttons);
        assert!(settings.status_bar.active_language_button);
        assert!(!settings.status_bar.cursor_position_button);
    }

    #[test]
    fn reduce_motion_takes_zeds_two_words_and_defers_to_the_system_otherwise() {
        assert_eq!(Settings::default().reduce_motion, ReduceMotion::Auto);
        for (text, expected) in [
            ("\"on\"", ReduceMotion::On),
            ("\"off\"", ReduceMotion::Off),
            ("\"auto\"", ReduceMotion::Auto),
        ] {
            let settings = settings_json::parse_json_with_comments::<Settings>(&format!(
                "{{ \"reduce_motion\": {text} }}"
            ))
            .unwrap();
            assert_eq!(settings.reduce_motion, expected);
        }
    }

    #[test]
    fn command_aliases_are_a_sorted_map_of_typed_string_to_action() {
        let settings = settings_json::parse_json_with_comments::<Settings>(
            r#"{ "command_aliases": { "W": "workspace::Save", "term": "terminal_panel::Toggle" } }"#,
        )
        .unwrap();
        assert_eq!(
            settings.command_aliases.iter().collect::<Vec<_>>(),
            vec![
                (&"W".to_string(), &"workspace::Save".to_string()),
                (&"term".to_string(), &"terminal_panel::Toggle".to_string()),
            ]
        );
    }

    #[test]
    fn the_font_keys_resolve_into_one_value_for_the_ui() {
        let settings = settings_json::parse_json_with_comments::<Settings>(
            r#"{
                "buffer_font_size": 17,
                "buffer_font_family": "JetBrains Mono",
                "buffer_font_fallbacks": ["Noto Sans Mono", "  "],
                "buffer_font_features": { "calt": false },
                "buffer_font_weight": 500,
                "buffer_line_height": { "custom": 1.4 },
                "ui_font_family": "Inter",
                "ui_font_size": 18
            }"#,
        )
        .unwrap()
        .sanitized();

        let buffer = settings.buffer_font();
        assert_eq!(buffer.family.as_deref(), Some("JetBrains Mono"));
        assert_eq!(buffer.fallbacks, vec!["Noto Sans Mono"]);
        assert_eq!(buffer.size, 17.0);
        assert_eq!(buffer.weight, 500.0);
        assert_eq!(buffer.line_height, 1.4);
        assert!(buffer.features.ligatures_disabled());
        assert_eq!(buffer.line_height_px(), 17.0 * 1.4);

        let ui = settings.ui_font();
        assert_eq!(ui.family.as_deref(), Some("Inter"));
        assert_eq!(ui.size, 18.0);
        // The chrome has no weight or feature row, so it stays at the
        // defaults rather than borrowing the buffer's.
        assert_eq!(ui.weight, DEFAULT_FONT_WEIGHT);
        assert!(ui.features.get("calt").is_none());
    }

    #[test]
    fn a_font_key_out_of_range_is_clamped_rather_than_refused() {
        with_settings_dir(|engine, dir| {
            std::fs::write(
                dir.join("settings.json"),
                r#"{ "ui_font_size": 200, "buffer_font_weight": 4, "buffer_line_height": { "custom": 0.1 }, "tab_size": 2 }"#,
            )
            .unwrap();
            let settings = engine.settings();
            assert_eq!(settings.ui_font_size, crate::appearance::MAX_UI_FONT_SIZE);
            assert_eq!(settings.buffer_font_weight, 100.0);
            assert_eq!(settings.buffer_line_height.value(), 1.0);
            // The rest of the file survived: a bad number is one key, not the
            // whole parse.
            assert_eq!(settings.tab_size, 2);
        });
    }

    #[test]
    fn theme_overrides_of_the_wrong_shape_cost_only_that_key() {
        with_settings_dir(|engine, dir| {
            std::fs::write(
                dir.join("settings.json"),
                r#"{ "theme_overrides": true, "tab_size": 8 }"#,
            )
            .unwrap();
            let settings = engine.settings();
            assert!(settings.theme_overrides.is_empty());
            assert_eq!(settings.tab_size, 8);
        });
    }

    #[test]
    fn the_theme_object_survives_a_round_trip_through_the_file() {
        with_settings_dir(|engine, _| {
            engine.settings_text();
            let updated = engine
                .set_setting(
                    &["theme"],
                    json!({ "mode": "dark", "light": "Ayu Light", "dark": "Gruvbox Dark Hard" }),
                )
                .unwrap();
            assert_eq!(updated.theme.mode(), Some(ThemeMode::Dark));
            assert_eq!(updated.theme.theme_name(false), "Gruvbox Dark Hard");
            // And the bare-name form, which is what a Zed config carries.
            let updated = engine.set_setting(&["theme"], json!("One Light")).unwrap();
            assert_eq!(updated.theme.mode(), None);
            assert_eq!(updated.theme.theme_name(true), "One Light");
        });
    }

    #[test]
    fn first_read_writes_a_documented_default_file() {
        with_settings_dir(|engine, dir| {
            let settings = engine.settings();
            assert_eq!(settings, Settings::default());
            let text = std::fs::read_to_string(dir.join("settings.json")).unwrap();
            assert!(text.contains("// Thragg settings."));
            assert!(text.contains("\"buffer_font_size\""));
        });
    }

    #[test]
    fn editing_a_key_preserves_comments_and_other_keys() {
        with_settings_dir(|engine, dir| {
            engine.settings_text(); // materialize the default file
            let updated = engine
                .set_setting(&["buffer_font_size"], json!(18))
                .unwrap();
            assert_eq!(updated.buffer_font_size, 18.0);

            let text = std::fs::read_to_string(dir.join("settings.json")).unwrap();
            // The point of settings_json: the prose survives.
            assert!(text.contains("// Thragg settings."));
            assert!(text.contains("// Spaces inserted by the Tab key."));
            assert!(text.contains("\"buffer_font_size\": 18"));
            // Untouched keys keep their values.
            assert!(text.contains("\"tab_size\": 4"));
        });
    }

    /// The documented default file has to *parse* as the settings it
    /// documents, or a first run writes a file the next read falls back from
    /// — silently, since a bad parse is defaults.
    #[test]
    fn the_default_file_is_the_default_settings() {
        let parsed: Settings = settings_json::parse_json_with_comments(DEFAULT_FILE).unwrap();
        assert_eq!(parsed, Settings::default());
    }

    /// `markdown_preview.scroll_sync`: on out of the box, and turnable off
    /// by hand. The preview's toolbar toggle is the same value for a session;
    /// this is the one that survives a restart.
    #[test]
    fn the_markdown_preview_can_be_told_not_to_follow_the_editor() {
        with_settings_dir(|engine, _dir| {
            assert!(engine.settings().markdown_preview.scroll_sync);
            let updated = engine
                .set_setting(&["markdown_preview", "scroll_sync"], json!(false))
                .unwrap();
            assert!(!updated.markdown_preview.scroll_sync);
            // A malformed value falls back to the default rather than taking
            // the rest of the file down with it.
            let text = r#"{ "markdown_preview": { "scroll_sync": true } }"#;
            let parsed: Settings = settings_json::parse_json_with_comments(text).unwrap();
            assert!(parsed.markdown_preview.scroll_sync);
        });
    }

    /// Where a panel sits is a setting, and the file that documents it has to
    /// parse as what it documents — see the test above.
    #[test]
    fn a_panel_can_be_moved_to_the_other_side() {
        with_settings_dir(|engine, _dir| {
            engine.settings_text();
            assert_eq!(engine.settings().project_panel.dock, DockSide::Left);
            let updated = engine
                .set_setting(&["project_panel", "dock"], json!("right"))
                .unwrap();
            assert_eq!(updated.project_panel.dock, DockSide::Right);
            // The git panel is on the right by default and stays where it is.
            assert_eq!(updated.git_panel.dock, DockSide::Right);
            // A side that is not a side is refused rather than resetting the
            // whole file to defaults.
            assert!(
                engine
                    .set_setting(&["project_panel", "dock"], json!("bottom"))
                    .is_err()
            );
            assert_eq!(engine.settings().project_panel.dock, DockSide::Right);
        });
    }

    /// A hand-edited width that would leave no editor, or no grabbable edge.
    #[test]
    fn a_panel_width_out_of_range_is_clamped() {
        with_settings_dir(|engine, _dir| {
            engine.settings_text();
            let updated = engine
                .set_setting(&["git_panel", "default_width"], json!(5000))
                .unwrap();
            assert_eq!(updated.git_panel.default_width, 900.0);
        });
    }

    #[test]
    fn soft_wrap_takes_zeds_names_and_refuses_others() {
        with_settings_dir(|engine, _dir| {
            engine.settings_text();
            let updated = engine
                .set_setting(&["soft_wrap"], json!("editor_width"))
                .unwrap();
            assert_eq!(updated.soft_wrap, SoftWrap::EditorWidth);
            let updated = engine.set_setting(&["soft_wrap"], json!("bounded")).unwrap();
            assert_eq!(updated.soft_wrap, SoftWrap::Bounded);
            // Zed's deprecated spelling of "none" still reads.
            let updated = engine
                .set_setting(&["soft_wrap"], json!("prefer_line"))
                .unwrap();
            assert_eq!(updated.soft_wrap, SoftWrap::None);
            // A value that is none of them leaves the setting alone rather
            // than turning wrapping off under the user.
            assert!(
                engine
                    .set_setting(&["soft_wrap"], json!("sideways"))
                    .is_err()
            );
            assert_eq!(engine.settings().soft_wrap, SoftWrap::None);
        });
    }

    // -----------------------------------------------------------------------
    // Per-language, per-project, per-server
    // -----------------------------------------------------------------------

    fn parse(text: &str) -> Settings {
        settings_json::parse_json_with_comments::<Settings>(text)
            .unwrap()
            .sanitized()
    }

    /// `languages.<Name>` lays over the top level for that language only,
    /// and the grammar's own config.toml sits underneath the user's entry:
    /// Go asks for tabs, a user's `languages.Go.hard_tabs: false` wins.
    #[test]
    fn a_language_entry_overrides_the_top_level_for_that_language_only() {
        let user = parse(
            r#"{
                "tab_size": 4,
                "format_on_save": "off",
                "languages": {
                    "Rust": { "tab_size": 2, "format_on_save": "on", "preferred_line_length": 100 }
                }
            }"#,
        );
        let rust = LanguageSettings::resolve(&user, None, None, Some("Rust"));
        assert_eq!(rust.tab_size, 2);
        assert_eq!(rust.format_on_save, FormatOnSave::On);
        assert_eq!(rust.preferred_line_length, 100);
        // Everything unsaid comes from the top level and Zed's defaults.
        assert!(!rust.hard_tabs);
        assert_eq!(rust.formatter, Formatter::Auto);

        let python = LanguageSettings::resolve(&user, None, None, Some("Python"));
        assert_eq!(python.tab_size, 4);
        assert_eq!(python.format_on_save, FormatOnSave::Off);
        assert_eq!(python.preferred_line_length, 80);

        // No language at all — a scratch buffer — is the top level.
        let none = LanguageSettings::resolve(&user, None, None, None);
        assert_eq!(none.tab_size, 4);

        // The grammar layer: Go's config.toml asks for tabs.
        let go_grammar = grammar_layer(Some("go")).expect("go is carried");
        assert_eq!(go_grammar.hard_tabs, Some(true));
        let go = LanguageSettings::resolve(&user, Some(&go_grammar), None, Some("Go"));
        assert!(go.hard_tabs);
        let user_says_spaces = parse(r#"{ "languages": { "Go": { "hard_tabs": false } } }"#);
        let go = LanguageSettings::resolve(&user_says_spaces, Some(&go_grammar), None, Some("Go"));
        assert!(!go.hard_tabs);
        // And the name Zed keys the map by is the grammar's display name.
        assert_eq!(language_display_name("rust").as_deref(), Some("Rust"));
        assert_eq!(language_display_name("cpp").as_deref(), Some("C++"));
        assert_eq!(language_display_name("no-such-grammar"), None);
    }

    /// The project's `.zed/settings.json` over the user's, in Zed's order:
    /// top levels merge first, `languages` entries merge second, and the
    /// merged language entry sits on the merged top level — so the user's
    /// `languages.Rust.tab_size` outranks the project's plain `tab_size`.
    #[test]
    fn project_local_settings_overlay_the_users_in_zeds_order() {
        let user = parse(
            r#"{
                "tab_size": 4,
                "soft_wrap": "none",
                "format_on_save": "off",
                "languages": { "Rust": { "tab_size": 2 } },
                "git": { "inline_blame": { "enabled": true } }
            }"#,
        );
        let local = ProjectSettingsContent::parse(
            r#"{
                // a project's own file, comments and all
                "tab_size": 8,
                "format_on_save": "on",
                "languages": { "Python": { "soft_wrap": "editor_width" } },
                "git": { "inline_blame": { "enabled": false } },
                "theme": "dark",
                "agent_servers": { "Evil": { "command": "rm" } }
            }"#,
        )
        .unwrap();

        let rust = LanguageSettings::resolve(&user, None, Some(&local), Some("Rust"));
        // The user's language entry outranks the project's top level.
        assert_eq!(rust.tab_size, 2);
        // The project's top level outranks the user's top level.
        assert_eq!(rust.format_on_save, FormatOnSave::On);
        assert!(!rust.inline_blame);

        let python = LanguageSettings::resolve(&user, None, Some(&local), Some("Python"));
        assert_eq!(python.tab_size, 8);
        assert_eq!(python.soft_wrap, SoftWrap::EditorWidth);

        // Without the project, the user's file alone.
        let python = LanguageSettings::resolve(&user, None, None, Some("Python"));
        assert_eq!(python.tab_size, 4);
        assert!(python.inline_blame);

        // `theme` and `agent_servers` are not project keys: the struct has
        // no field for them and the parse above did not fail on them.
        assert!(!local.lsp.contains_key("Evil"));
        assert!(ProjectSettingsContent::parse("{ not json").is_err());
    }

    /// `lsp.<server>`: the user's entry, the project's over it, key by key.
    #[test]
    fn lsp_settings_are_looked_up_by_server_name_and_overlaid() {
        let user = parse(
            r#"{
                "lsp": {
                    "rust-analyzer": {
                        "binary": { "path": "/root/.cargo/bin/rust-analyzer" },
                        "initialization_options": { "check": { "command": "clippy" } }
                    },
                    "gopls": { "settings": { "gopls": { "staticcheck": true } } }
                }
            }"#,
        );
        let ra = user.lsp_settings_for("rust-analyzer", None);
        assert_eq!(
            ra.binary.as_ref().and_then(|b| b.path.as_deref()),
            Some("/root/.cargo/bin/rust-analyzer")
        );
        assert_eq!(
            ra.initialization_options,
            Some(json!({ "check": { "command": "clippy" } }))
        );
        assert_eq!(ra.settings, None);
        // A server with no entry is an empty entry, not an error.
        assert_eq!(user.lsp_settings_for("clangd", None), LspSettings::default());

        let local = ProjectSettingsContent::parse(
            r#"{ "lsp": { "rust-analyzer": { "initialization_options": { "cargo": { "features": "all" } } } } }"#,
        )
        .unwrap();
        let ra = user.lsp_settings_for("rust-analyzer", Some(&local));
        // The project's initialization_options replace the user's whole;
        // the binary the project said nothing about is kept.
        assert_eq!(
            ra.initialization_options,
            Some(json!({ "cargo": { "features": "all" } }))
        );
        assert!(ra.binary.is_some());
        let gopls = user.lsp_settings_for("gopls", Some(&local));
        assert_eq!(gopls.settings, Some(json!({ "gopls": { "staticcheck": true } })));
    }

    /// Every shape Zed's `formatter` takes on disk, and the two that are
    /// read as something else: prettier as auto, a list as its first step.
    #[test]
    fn formatter_reads_zeds_shapes() {
        let of = |text: &str| parse(&format!(r#"{{ "formatter": {text} }}"#)).formatter;
        assert_eq!(of(r#""auto""#), Formatter::Auto);
        assert_eq!(of(r#""prettier""#), Formatter::Auto);
        assert_eq!(of(r#""none""#), Formatter::None);
        assert_eq!(of(r#""language_server""#), Formatter::LanguageServer { name: None });
        assert_eq!(
            of(r#"{ "language_server": { "name": "ruff" } }"#),
            Formatter::LanguageServer {
                name: Some("ruff".to_owned())
            }
        );
        assert_eq!(
            of(r#"{ "external": { "command": "rustfmt", "arguments": ["--edition", "2021"] } }"#),
            Formatter::External {
                command: "rustfmt".to_owned(),
                arguments: vec!["--edition".to_owned(), "2021".to_owned()]
            }
        );
        assert_eq!(
            of(r#"{ "external": { "command": "black" } }"#),
            Formatter::External {
                command: "black".to_owned(),
                arguments: Vec::new()
            }
        );
        assert_eq!(
            of(r#"{ "code_action": "source.fixAll.eslint" }"#),
            Formatter::CodeAction("source.fixAll.eslint".to_owned())
        );
        assert_eq!(
            of(r#"[{ "code_action": "source.fixAll" }, "prettier"]"#),
            Formatter::CodeAction("source.fixAll".to_owned())
        );
        // And what goes out to the app is the same shape coming back in.
        for text in [
            r#""auto""#,
            r#""none""#,
            r#""language_server""#,
            r#"{ "language_server": { "name": "ruff" } }"#,
            r#"{ "external": { "command": "rustfmt", "arguments": ["-q"] } }"#,
            r#"{ "code_action": "source.fixAll" }"#,
        ] {
            let formatter = of(text);
            let json = serde_json::to_string(&formatter).unwrap();
            assert_eq!(of(&json), formatter, "{text} did not round-trip via {json}");
        }
    }

    /// A value that is wrong is refused, and the rest of the file is left
    /// exactly as it was — for the enum keys through the write path, and
    /// for the maps through the lenient parse: a broken language entry or
    /// server entry costs itself, never its neighbours or the file.
    #[test]
    fn invalid_values_are_refused_without_losing_other_settings() {
        with_settings_dir(|engine, dir| {
            engine.settings_text();
            engine.set_setting(&["tab_size"], json!(2)).unwrap();
            engine.set_setting(&["format_on_save"], json!("on")).unwrap();

            for (key, value) in [
                ("format_on_save", json!("sometimes")),
                ("autosave", json!("when_i_say_so")),
                ("autosave", json!({ "after_delay": { "seconds": 1 } })),
                ("formatter", json!({ "external": {} })),
                ("formatter", json!({ "external": { "command": "x" }, "code_action": "y" })),
                ("hard_tabs", json!("yes")),
                ("preferred_line_length", json!("wide")),
            ] {
                assert!(
                    engine.set_setting(&[key], value.clone()).is_err(),
                    "{key} = {value} should be refused"
                );
            }
            let settings = engine.settings();
            assert_eq!(settings.tab_size, 2);
            assert_eq!(settings.format_on_save, FormatOnSave::On);
            assert!(engine.settings_are_valid());
            let text = std::fs::read_to_string(dir.join("settings.json")).unwrap();
            assert!(text.contains("// Thragg settings."));

            // The maps, entry by entry.
            let settings = engine
                .set_settings_text(
                    r#"{
                        "tab_size": 3,
                        "languages": {
                            "Rust": { "tab_size": "two" },
                            "Go": { "hard_tabs": true },
                            "Nonsense": 7
                        },
                        "lsp": {
                            "rust-analyzer": { "binary": "not an object" },
                            "gopls": { "initialization_options": { "x": 1 } }
                        }
                    }"#,
                )
                .unwrap();
            assert_eq!(settings.tab_size, 3);
            let languages: Vec<&str> = settings.languages.keys().map(String::as_str).collect();
            assert_eq!(languages, ["Go"]);
            let servers: Vec<&str> = settings.lsp.keys().map(String::as_str).collect();
            assert_eq!(servers, ["gopls"]);
            // Out-of-range numbers are clamped, not obeyed.
            let settings = engine
                .set_settings_text(
                    r#"{ "preferred_line_length": 5, "autosave": { "after_delay": { "milliseconds": 0 } },
                         "languages": { "Rust": { "tab_size": 900 } } }"#,
                )
                .unwrap();
            assert_eq!(settings.preferred_line_length, MIN_LINE_LENGTH);
            assert_eq!(
                settings.autosave,
                Autosave::AfterDelay { milliseconds: 100 }
            );
            let rust = LanguageSettings::resolve(&settings, None, None, Some("Rust"));
            assert_eq!(rust.tab_size, 16);
        });
    }

    /// The autosave shapes, exactly as Zed's default.json spells them.
    #[test]
    fn autosave_reads_zeds_shapes() {
        let of = |text: &str| parse(&format!(r#"{{ "autosave": {text} }}"#)).autosave;
        assert_eq!(of(r#""off""#), Autosave::Off);
        assert_eq!(of(r#""on_focus_change""#), Autosave::OnFocusChange);
        assert_eq!(of(r#""on_window_change""#), Autosave::OnWindowChange);
        assert_eq!(
            of(r#"{ "after_delay": { "milliseconds": 500 } }"#),
            Autosave::AfterDelay { milliseconds: 500 }
        );
        assert_eq!(
            serde_json::to_value(Autosave::AfterDelay { milliseconds: 500 }).unwrap(),
            json!({ "after_delay": { "milliseconds": 500 } })
        );
        assert_eq!(serde_json::to_value(Autosave::OnFocusChange).unwrap(), json!("on_focus_change"));
    }

    /// `restore_on_startup`: Zed's three words, what each one lets through,
    /// and what an unwritten — or unwritable — value falls back to.
    #[test]
    fn restore_on_startup_reads_zeds_three_values() {
        let of = |text: &str| parse(&format!(r#"{{ "restore_on_startup": {text} }}"#)).restore_on_startup;
        assert_eq!(of(r#""last_session""#), RestoreOnStartup::LastSession);
        assert_eq!(of(r#""last_workspace""#), RestoreOnStartup::LastWorkspace);
        assert_eq!(of(r#""none""#), RestoreOnStartup::None);
        // Zed's default, and what a file that says nothing means.
        assert_eq!(parse("{}").restore_on_startup, RestoreOnStartup::LastSession);
        // A value nobody wrote on purpose: the whole file falls back to the
        // defaults rather than half-applying, which is `Engine::settings`'s
        // rule for anything malformed.
        assert!(
            settings_json::parse_json_with_comments::<Settings>(
                r#"{ "restore_on_startup": "sometimes" }"#
            )
            .is_err()
        );

        // Precedence between the three, as the workspace reads them: only
        // `none` skips the project, and only `last_session` puts the tabs,
        // panes, docks and terminals back.
        assert!(RestoreOnStartup::LastSession.reopens_project());
        assert!(RestoreOnStartup::LastSession.restores_workspace());
        assert!(RestoreOnStartup::LastWorkspace.reopens_project());
        assert!(!RestoreOnStartup::LastWorkspace.restores_workspace());
        assert!(!RestoreOnStartup::None.reopens_project());
        assert!(!RestoreOnStartup::None.restores_workspace());

        // And `close_on_file_delete` is Zed's, off unless asked for.
        assert!(!parse("{}").close_on_file_delete);
        assert!(parse(r#"{ "close_on_file_delete": true }"#).close_on_file_delete);
    }

    /// `enable_language_server`, at both levels: globally off, and off for
    /// one language while another keeps its server.
    #[test]
    fn enable_language_server_resolves_per_language() {
        let user = parse(r#"{ "languages": { "Markdown": { "enable_language_server": false } } }"#);
        assert!(LanguageSettings::resolve(&user, None, None, Some("Rust")).enable_language_server);
        assert!(!LanguageSettings::resolve(&user, None, None, Some("Markdown")).enable_language_server);
        let all_off = parse(r#"{ "enable_language_server": false }"#);
        assert!(!LanguageSettings::resolve(&all_off, None, None, Some("Rust")).enable_language_server);
        let local = ProjectSettingsContent::parse(r#"{ "enable_language_server": true }"#).unwrap();
        assert!(LanguageSettings::resolve(&all_off, None, Some(&local), Some("Rust")).enable_language_server);
    }

    /// The default text is the documentation, and it has to be readable
    /// without touching the disk.
    #[test]
    fn the_default_text_is_available_without_a_file() {
        let engine = Engine::new();
        let text = engine.default_settings_text();
        assert!(text.contains("\"format_on_save\""));
        assert!(text.contains("\"languages\""));
        assert!(text.contains("\"lsp\""));
        assert!(text.contains("\"autosave\""));
    }

    /// The rollback above, on the key that has the most ways to be wrong.
    #[test]
    fn a_write_that_would_break_the_file_leaves_every_other_setting_alone() {
        with_settings_dir(|engine, dir| {
            engine.settings_text();
            engine.set_setting(&["tab_size"], json!(8)).unwrap();
            assert!(engine.set_setting(&["tab_size"], json!("eight")).is_err());
            // Not defaults: the bad write never reached the file.
            assert_eq!(engine.settings().tab_size, 8);
            let text = std::fs::read_to_string(dir.join("settings.json")).unwrap();
            assert!(text.contains("\"tab_size\": 8"));
            assert!(engine.settings_are_valid());
        });
    }

    #[test]
    fn nested_keys_can_be_set() {
        with_settings_dir(|engine, _dir| {
            engine.settings_text();
            let updated = engine
                .set_setting(&["project_panel", "gitignored_files"], json!("hide"))
                .unwrap();
            assert_eq!(
                updated.project_panel.gitignored_files,
                GitignoredFiles::Hide
            );
            assert_eq!(
                engine.settings().project_panel.gitignored_files,
                GitignoredFiles::Hide
            );
        });
    }

    #[test]
    fn an_unrecognised_value_falls_back_rather_than_breaking_the_file() {
        with_settings_dir(|engine, dir| {
            // A key we no longer understand (the old boolean form) must not
            // make the whole file unreadable.
            std::fs::write(
                dir.join("settings.json"),
                "{ \"project_panel\": { \"show_ignored\": false }, \"tab_size\": 2 }",
            )
            .unwrap();
            let settings = engine.settings();
            assert_eq!(settings.tab_size, 2);
            assert_eq!(
                settings.project_panel.gitignored_files,
                GitignoredFiles::Dimmed
            );
        });
    }

    #[test]
    fn a_users_own_comments_and_unknown_keys_survive() {
        with_settings_dir(|engine, dir| {
            std::fs::write(
                dir.join("settings.json"),
                "{\n  // mine\n  \"tab_size\": 2,\n  \"future_option\": true\n}\n",
            )
            .unwrap();
            engine.set_setting(&["tab_size"], json!(8)).unwrap();

            let text = std::fs::read_to_string(dir.join("settings.json")).unwrap();
            assert!(text.contains("// mine"));
            assert!(text.contains("\"future_option\": true"));
            assert!(text.contains("\"tab_size\": 8"));
        });
    }

    #[test]
    fn a_broken_file_falls_back_to_defaults_and_says_so() {
        with_settings_dir(|engine, dir| {
            std::fs::write(dir.join("settings.json"), "{ this is not json").unwrap();
            assert_eq!(engine.settings(), Settings::default());
            assert!(!engine.settings_are_valid());
            // …and the file is left alone, so the user can repair it.
            assert_eq!(
                std::fs::read_to_string(dir.join("settings.json")).unwrap(),
                "{ this is not json"
            );
        });
    }

    #[test]
    fn out_of_range_values_are_clamped_not_obeyed() {
        with_settings_dir(|engine, dir| {
            std::fs::write(
                dir.join("settings.json"),
                "{ \"buffer_font_size\": 0, \"tab_size\": 9999 }",
            )
            .unwrap();
            let settings = engine.settings();
            assert_eq!(settings.buffer_font_size, 6.0);
            assert_eq!(settings.tab_size, 16);
        });
    }

    #[test]
    fn whole_file_writes_reject_invalid_json() {
        with_settings_dir(|engine, dir| {
            engine.settings_text();
            let before = std::fs::read_to_string(dir.join("settings.json")).unwrap();
            assert!(matches!(
                engine.set_settings_text("{ nope"),
                Err(EngineError::InvalidSettings(_))
            ));
            assert_eq!(
                std::fs::read_to_string(dir.join("settings.json")).unwrap(),
                before
            );

            let updated = engine.set_settings_text("{ \"tab_size\": 2 }").unwrap();
            assert_eq!(updated.tab_size, 2);
        });
    }

    /// A hand-configured agent, in Zed's own `agent_servers` shape.
    ///
    /// This is what makes "any ACP agent" true rather than "the two we
    /// happen to name": the command is resolved inside the guest, so any
    /// program on Debian's PATH that speaks the protocol is an agent.
    #[test]
    fn a_custom_agent_is_read_from_settings() {
        with_settings_dir(|engine, _dir| {
            let settings = engine
                .set_settings_text(
                    r#"{
                        "agent_servers": {
                            "My agent": {
                                "command": "python3",
                                "args": ["/root/agent.py", "--acp"],
                                "env": { "TOKEN": "x" }
                            },
                            "Bare": { "command": "some-agent" }
                        }
                    }"#,
                )
                .unwrap();

            assert_eq!(settings.agent_servers.len(), 2);
            let mine = &settings.agent_servers["My agent"];
            assert_eq!(mine.command, "python3");
            assert_eq!(mine.args, ["/root/agent.py", "--acp"]);
            assert_eq!(mine.env.get("TOKEN").map(String::as_str), Some("x"));
            // Everything but the command is optional.
            let bare = &settings.agent_servers["Bare"];
            assert_eq!(bare.command, "some-agent");
            assert!(bare.args.is_empty());
            assert!(bare.env.is_empty());

            // Sorted, because this list *is* the picker and a hash map would
            // reorder it on every launch.
            let names: Vec<&str> = settings.agent_servers.keys().map(String::as_str).collect();
            assert_eq!(names, ["Bare", "My agent"]);
        });
    }

    /// A half-written `agent_servers` entry costs that entry, nothing else.
    ///
    /// It used to cost everything: the strict map failed the whole `Settings`
    /// parse, so `"Claude": "claude"` — the obvious first guess at the shape —
    /// silently reset the theme, the font size and every panel to defaults.
    /// The Kotlin parser was already lenient; now both sides agree.
    #[test]
    fn a_malformed_agent_entry_costs_only_itself() {
        with_settings_dir(|engine, _dir| {
            let settings = engine
                .set_settings_text(
                    r#"{
                        "theme": "dark",
                        "agent_servers": {
                            "Not an object": "claude",
                            "Wrong types": { "command": 7 },
                            "Works": { "command": "fine-agent" }
                        }
                    }"#,
                )
                .unwrap();
            let names: Vec<&str> = settings.agent_servers.keys().map(String::as_str).collect();
            assert_eq!(names, ["Works"]);
            // The rest of the file still counted — the parse did not fall
            // back to defaults.
            assert_eq!(settings.theme.mode(), Some(ThemeMode::Dark));

            // And `agent_servers` itself being rubbish ignores the key, not
            // the file.
            let settings = engine
                .set_settings_text(r#"{ "theme": "dark", "agent_servers": 17 }"#)
                .unwrap();
            assert!(settings.agent_servers.is_empty());
            assert_eq!(settings.theme.mode(), Some(ThemeMode::Dark));
        });
    }

    /// The settings screen's Add Agent form and its trash button, at the
    /// engine seam: an entry written by name lands under `agent_servers`
    /// exactly, comments survive, and removal takes only that entry. The name
    /// goes into the key path verbatim — a dot in it must not open a nested
    /// object, which is what the dot-split `setSetting` route would do.
    #[test]
    fn an_agent_server_can_be_added_and_removed_by_name() {
        with_settings_dir(|engine, dir| {
            engine.settings_text(); // materialize the commented default file
            let agent = CustomAgent {
                command: "python3".to_owned(),
                args: vec!["/root/agent.py".to_owned()],
                env: BTreeMap::from([("KEY".to_owned(), "v".to_owned())]),
            };
            let settings = engine.set_agent_server("my.agent", agent.clone()).unwrap();
            assert_eq!(settings.agent_servers.get("my.agent"), Some(&agent));

            // Replacing the same name is an edit, not a second entry.
            let mut edited = agent.clone();
            edited.command = "node".to_owned();
            let settings = engine.set_agent_server("my.agent", edited.clone()).unwrap();
            assert_eq!(settings.agent_servers.len(), 1);
            assert_eq!(settings.agent_servers.get("my.agent"), Some(&edited));

            // A neighbour survives the removal, and so do the file's comments.
            engine
                .set_agent_server(
                    "other",
                    CustomAgent {
                        command: "other-agent".to_owned(),
                        ..CustomAgent::default()
                    },
                )
                .unwrap();
            let settings = engine.remove_agent_server("my.agent").unwrap();
            let names: Vec<&str> = settings.agent_servers.keys().map(String::as_str).collect();
            assert_eq!(names, ["other"]);
            let text = std::fs::read_to_string(dir.join("settings.json")).unwrap();
            assert!(text.contains("// Thragg settings."));
            assert!(!text.contains("my.agent"));

            // Removing what is not there is not an error — it is gone.
            assert!(engine.remove_agent_server("my.agent").is_ok());
        });
    }

    /// `context_servers` in Zed's own shape: a stdio entry and an HTTP one,
    /// told apart by which key they carry, and Zed's `"source": "custom"`
    /// accepted rather than refused — the file may be a copy of a Zed
    /// config, and it should work as pasted.
    #[test]
    fn context_servers_are_read_in_zeds_shape() {
        with_settings_dir(|engine, _dir| {
            let settings = engine
                .set_settings_text(
                    r#"{
                        "context_servers": {
                            "fs": {
                                "source": "custom",
                                "command": "npx",
                                "args": ["-y", "server-filesystem", "."],
                                "env": { "ROOT": "/proj" }
                            },
                            "docs": { "url": "https://example.com/mcp", "headers": { "Authorization": "Bearer x" } },
                            "off": { "command": "quiet", "enabled": false }
                        }
                    }"#,
                )
                .unwrap();
            assert_eq!(settings.context_servers.len(), 3);
            match &settings.context_servers["fs"] {
                ContextServer::Stdio {
                    command,
                    args,
                    env,
                    enabled,
                } => {
                    assert_eq!(command, "npx");
                    assert_eq!(args, &["-y", "server-filesystem", "."]);
                    assert_eq!(env.get("ROOT").map(String::as_str), Some("/proj"));
                    assert!(enabled);
                }
                other => panic!("expected a stdio server, got {other:?}"),
            }
            match &settings.context_servers["docs"] {
                ContextServer::Http { url, headers, .. } => {
                    assert_eq!(url, "https://example.com/mcp");
                    assert_eq!(
                        headers.get("Authorization").map(String::as_str),
                        Some("Bearer x")
                    );
                }
                other => panic!("expected an http server, got {other:?}"),
            }
            assert!(!settings.context_servers["off"].is_enabled());
        });
    }

    /// One broken context server costs itself and nothing else — the same
    /// leniency `agent_servers` has, and the same reason.
    #[test]
    fn a_malformed_context_server_costs_only_itself() {
        with_settings_dir(|engine, _dir| {
            let settings = engine
                .set_settings_text(
                    r#"{
                        "theme": "dark",
                        "context_servers": {
                            "neither": { "name": "no command and no url" },
                            "works": { "command": "fine" }
                        }
                    }"#,
                )
                .unwrap();
            let names: Vec<&str> = settings.context_servers.keys().map(String::as_str).collect();
            assert_eq!(names, ["works"]);
            assert_eq!(settings.theme.mode(), Some(ThemeMode::Dark));
        });
    }

    /// The settings screen's context-server form at the engine seam, and
    /// the round trip: what it writes is what the file reads back.
    #[test]
    fn a_context_server_can_be_added_and_removed_by_name() {
        with_settings_dir(|engine, dir| {
            engine.settings_text();
            let server = ContextServer::Http {
                url: "https://example.com/mcp".to_owned(),
                headers: BTreeMap::new(),
                enabled: true,
            };
            let settings = engine.set_context_server("docs.v1", server.clone()).unwrap();
            assert_eq!(settings.context_servers.get("docs.v1"), Some(&server));
            let text = std::fs::read_to_string(dir.join("settings.json")).unwrap();
            assert!(text.contains("// Thragg settings."));
            assert!(text.contains("\"docs.v1\""));

            let settings = engine.remove_context_server("docs.v1").unwrap();
            assert!(settings.context_servers.is_empty());
            assert!(engine.remove_context_server("docs.v1").is_ok());
        });
    }

    /// Zed's `agent.notify_when_agent_waiting`, all three names, and the
    /// single-screen reading of them.
    #[test]
    fn the_notify_setting_takes_zeds_three_names() {
        with_settings_dir(|engine, _dir| {
            assert_eq!(
                engine.settings().agent.notify_when_agent_waiting,
                NotifyWhenAgentWaiting::PrimaryScreen
            );
            let updated = engine
                .set_setting(&["agent", "notify_when_agent_waiting"], json!("never"))
                .unwrap();
            assert_eq!(
                updated.agent.notify_when_agent_waiting,
                NotifyWhenAgentWaiting::Never
            );
            assert!(!updated.agent.notify_when_agent_waiting.is_on());
            let updated = engine
                .set_setting(&["agent", "notify_when_agent_waiting"], json!("all_screens"))
                .unwrap();
            assert!(updated.agent.notify_when_agent_waiting.is_on());
            assert!(
                engine
                    .set_setting(&["agent", "notify_when_agent_waiting"], json!("sometimes"))
                    .is_err()
            );
        });
    }

    /// `"hidden"` is a real third answer for a panel's dock: it parses, it
    /// survives a write, and it round-trips to the app as `"hidden"`.
    #[test]
    fn a_panel_can_be_hidden_by_its_dock_setting() {
        with_settings_dir(|engine, _dir| {
            let updated = engine
                .set_setting(&["git_panel", "dock"], serde_json::json!("hidden"))
                .unwrap();
            assert_eq!(updated.git_panel.dock, DockSide::Hidden);
            // And back from disk, not only from the in-memory return.
            assert_eq!(engine.settings().git_panel.dock, DockSide::Hidden);
            let json = serde_json::to_value(engine.settings()).unwrap();
            assert_eq!(json["git_panel"]["dock"], "hidden");
        });
    }

    /// The terminal section in Zed's shape: the three keys the dock acts on
    /// are read, the alias spelling counts, and the scrollback is clamped
    /// to Zed's ceiling rather than obeyed.
    #[test]
    fn the_terminal_section_is_read_and_clamped() {
        with_settings_dir(|engine, _dir| {
            assert_eq!(engine.settings().terminal, TerminalSettings::default());
            let settings = engine
                .set_settings_text(
                    r#"{
                        "terminal": {
                            "working_directory": "current_file_directory",
                            "env": { "EDITOR": "vi", "FOO": "bar" },
                            "scrollback_lines": 250000
                        }
                    }"#,
                )
                .unwrap();
            assert_eq!(
                settings.terminal.working_directory,
                TerminalWorkingDirectory::CurrentFileDirectory
            );
            assert_eq!(settings.terminal.env.get("EDITOR").map(String::as_str), Some("vi"));
            assert_eq!(settings.terminal.max_scroll_history_lines, MAX_SCROLL_HISTORY_LINES);

            // Zed's own name, and the floor under which the emulator would
            // substitute its default.
            let settings = engine
                .set_settings_text(r#"{ "terminal": { "max_scroll_history_lines": 0 } }"#)
                .unwrap();
            assert_eq!(settings.terminal.max_scroll_history_lines, 100);
        });
    }

    /// A Zed `terminal` block pasted whole: keys this dock has no use for,
    /// and Zed's object form of `working_directory`, cost nothing — not the
    /// key, and certainly not the rest of the file.
    #[test]
    fn unknown_terminal_keys_and_zeds_object_form_are_tolerated() {
        with_settings_dir(|engine, _dir| {
            let settings = engine
                .set_settings_text(
                    r#"{
                        "theme": "dark",
                        "terminal": {
                            "font_family": "Zed Mono",
                            "blinking": "on",
                            "dock": "bottom",
                            "working_directory": { "always": { "directory": "~/src" } },
                            "max_scroll_history_lines": 5000
                        }
                    }"#,
                )
                .unwrap();
            assert_eq!(settings.theme.mode(), Some(ThemeMode::Dark));
            assert_eq!(
                settings.terminal.working_directory,
                TerminalWorkingDirectory::CurrentProjectDirectory
            );
            assert_eq!(settings.terminal.max_scroll_history_lines, 5000);
            // The bridge hands Kotlin the resolved shape under Zed's key.
            let json = serde_json::to_value(&settings).unwrap();
            assert_eq!(json["terminal"]["working_directory"], "current_project_directory");
            assert_eq!(json["terminal"]["max_scroll_history_lines"], 5000);
        });
    }

    /// `base_keymap` takes Zed's own names — capitalised, unlike every other
    /// enum here, because that is how Zed spells them and a line pasted from
    /// a Zed settings file must work — and refuses the rest.
    #[test]
    fn base_keymap_takes_zeds_names_and_refuses_others() {
        with_settings_dir(|engine, _dir| {
            assert_eq!(engine.settings().base_keymap, BaseKeymap::VSCode);
            let updated = engine
                .set_setting(&["base_keymap"], json!("JetBrains"))
                .unwrap();
            assert_eq!(updated.base_keymap, BaseKeymap::JetBrains);
            assert_eq!(engine.settings().base_keymap, BaseKeymap::JetBrains);
            let json = serde_json::to_value(engine.settings()).unwrap();
            assert_eq!(json["base_keymap"], "JetBrains");
            assert!(engine.set_setting(&["base_keymap"], json!("vscode")).is_err());
            assert!(engine.set_setting(&["base_keymap"], json!("TextMate")).is_err());
            assert_eq!(engine.settings().base_keymap, BaseKeymap::JetBrains);
        });
    }

    /// `vim_mode` and the `vim` object round-trip with Zed's names, and a
    /// value Zed would refuse is refused here rather than resetting the file.
    #[test]
    fn vim_settings_take_zeds_names_and_refuse_others() {
        with_settings_dir(|engine, _dir| {
            let defaults = engine.settings();
            assert!(!defaults.vim_mode);
            assert_eq!(defaults.vim.default_mode, VimDefaultMode::Normal);
            assert_eq!(defaults.vim.use_system_clipboard, UseSystemClipboard::Always);

            let updated = engine.set_setting(&["vim_mode"], json!(true)).unwrap();
            assert!(updated.vim_mode);
            let updated = engine
                .set_setting(&["vim", "use_system_clipboard"], json!("on_yank"))
                .unwrap();
            assert_eq!(updated.vim.use_system_clipboard, UseSystemClipboard::OnYank);
            let updated = engine
                .set_setting(&["vim", "default_mode"], json!("visual_line"))
                .unwrap();
            assert_eq!(updated.vim.default_mode, VimDefaultMode::VisualLine);

            assert!(engine
                .set_setting(&["vim", "use_system_clipboard"], json!("sometimes"))
                .is_err());
            // The refused write left the file as it was.
            let settings = engine.settings();
            assert!(settings.vim_mode);
            assert_eq!(settings.vim.use_system_clipboard, UseSystemClipboard::OnYank);
            let json = serde_json::to_value(&settings).unwrap();
            assert_eq!(json["vim"]["default_mode"], "visual_line");
        });
    }

    /// The agent panel's dock is a real setting, not one the engine drops.
    ///
    /// It was: the settings screen wrote `agent_panel.dock` and `Settings` had
    /// no such field, so serde ignored it and the row did nothing at all.
    #[test]
    fn the_agent_panels_dock_survives_a_write() {
        with_settings_dir(|engine, _dir| {
            assert_eq!(engine.settings().agent_panel.dock, DockSide::Right);
            let updated = engine
                .set_setting(&["agent_panel", "dock"], serde_json::json!("left"))
                .unwrap();
            assert_eq!(updated.agent_panel.dock, DockSide::Left);
            // And it is still there when the file is read back from disk.
            assert_eq!(engine.settings().agent_panel.dock, DockSide::Left);
        });
    }

    /// The outline panel is a dockable panel like the others: Zed's default
    /// side is the right one, and a hand-edited width is clamped like every
    /// other panel's rather than being allowed to swallow the editor.
    #[test]
    fn the_outline_panel_docks_and_clamps_like_the_others() {
        with_settings_dir(|engine, _dir| {
            assert_eq!(engine.settings().outline_panel.dock, DockSide::Right);
            assert_eq!(engine.settings().outline_panel.default_width, 300.0);
            let updated = engine
                .set_setting(&["outline_panel", "dock"], serde_json::json!("left"))
                .unwrap();
            assert_eq!(updated.outline_panel.dock, DockSide::Left);
            assert_eq!(engine.settings().outline_panel.dock, DockSide::Left);
            let wide = engine
                .set_setting(&["outline_panel", "default_width"], serde_json::json!(4000))
                .unwrap();
            assert_eq!(wide.outline_panel.default_width, 900.0);
        });
    }

    /// Zed's own defaults for the tab strip, which are not all `false`: the
    /// panel's `show_diagnostics` is `all` where the tabs' is `off`, and it is
    /// exactly the kind of asymmetry a rewrite quietly flattens.
    #[test]
    fn the_tab_and_panel_defaults_are_zeds() {
        let settings = Settings::default();
        assert_eq!(settings.tabs.close_position, ClosePosition::Right);
        assert!(!settings.tabs.file_icons);
        assert!(!settings.tabs.git_status);
        assert_eq!(settings.tabs.show_diagnostics, ShowDiagnostics::Off);
        assert_eq!(settings.tabs.activate_on_close, ActivateOnClose::History);
        assert_eq!(settings.max_tabs, None);
        assert!(settings.preview_tabs.enabled);
        assert!(settings.preview_tabs.enable_preview_from_project_panel);
        assert!(!settings.preview_tabs.enable_preview_from_file_finder);
        assert!(settings.preview_tabs.enable_preview_from_code_navigation);
        assert_eq!(
            settings.project_panel.sort_mode,
            ProjectPanelSortMode::DirectoriesFirst
        );
        assert!(!settings.project_panel.hide_root);
        assert!(settings.project_panel.auto_fold_dirs);
        assert_eq!(settings.project_panel.entry_spacing, EntrySpacing::Comfortable);
        assert_eq!(settings.project_panel.indent_size, 20.0);
        assert_eq!(
            settings.project_panel.show_diagnostics,
            ShowDiagnostics::All
        );
    }

    #[test]
    fn the_new_tab_keys_round_trip_through_the_file() {
        with_settings_dir(|engine, _dir| {
            engine.settings_text();
            let updated = engine
                .set_setting(&["tabs", "file_icons"], json!(true))
                .unwrap();
            assert!(updated.tabs.file_icons);
            let updated = engine
                .set_setting(&["tabs", "activate_on_close"], json!("left_neighbour"))
                .unwrap();
            assert_eq!(
                updated.tabs.activate_on_close,
                ActivateOnClose::LeftNeighbour
            );
            // And back from disk, with the other key still set.
            let settings = engine.settings();
            assert!(settings.tabs.file_icons);
            assert_eq!(
                settings.tabs.activate_on_close,
                ActivateOnClose::LeftNeighbour
            );
        });
    }

    /// A hand-edited file is user input: `max_tabs: 0` would close every tab
    /// as it opened, and an indent of 0 or 900 makes the tree unusable.
    #[test]
    fn the_hand_edited_extremes_are_clamped() {
        with_settings_dir(|engine, dir| {
            std::fs::write(
                dir.join("settings.json"),
                r#"{ "max_tabs": 0, "project_panel": { "indent_size": 900 } }"#,
            )
            .unwrap();
            let settings = engine.settings();
            assert_eq!(settings.max_tabs, None);
            assert_eq!(settings.project_panel.indent_size, 64.0);

            std::fs::write(
                dir.join("settings.json"),
                r#"{ "max_tabs": 8, "project_panel": { "indent_size": 0 } }"#,
            )
            .unwrap();
            let settings = engine.settings();
            assert_eq!(settings.max_tabs, Some(8));
            assert_eq!(settings.project_panel.indent_size, 4.0);
        });
    }

    /// One unknown value must cost that key and nothing else — a settings file
    /// is edited by hand, and a typo that reset every other setting would be
    /// the worst possible answer.
    #[test]
    fn an_unknown_enum_value_is_refused_as_a_parse_and_falls_back() {
        with_settings_dir(|engine, dir| {
            std::fs::write(
                dir.join("settings.json"),
                r#"{ "tabs": { "close_position": "middle" } }"#,
            )
            .unwrap();
            // serde rejects the whole file, and the engine logs and uses
            // defaults rather than showing settings it is not using.
            assert_eq!(engine.settings(), Settings::default());
        });
    }
}
