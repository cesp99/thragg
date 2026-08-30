//! The user keymap: Zed's `keymap.json`, read from next to `settings.json`.
//!
//! The file has Zed's shape exactly (settings/src/keymap_file.rs:59-98): an
//! array of sections, each with an optional `"context"` and a `"bindings"`
//! object mapping a keystroke sequence to an action name, or to `null` to
//! unbind. Keystroke syntax is gpui's (`ctrl-alt-shift-<key>`, several
//! separated by spaces for a chord — platform/keystroke.rs:118-200), so a
//! binding copied out of a Zed keymap works here unchanged.
//!
//! The engine's half is *parsing and precedence*: it reads the file, the
//! chosen base keymap and the app's own defaults, validates every entry,
//! reports what it could not use without dropping what it could — Zed's
//! `SomeFailedToLoad` (keymap_file.rs:163) — and hands the app one flat,
//! ordered list. Which Android key code a name means, and what an action
//! *does*, are the app's business; the action names themselves are the
//! app's too, which is why [`crate::Engine::load_keymap`] takes the default
//! keymap as an argument rather than owning it: the Kotlin `WorkspaceCommand`
//! table is the one source of truth for what exists, and this module only
//! ever learns the names from it.

use std::collections::HashSet;
use std::fmt;
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};

use serde::{Deserialize, Serialize};

use crate::config::BaseKeymap;

/// One key with its modifiers, parsed from `ctrl-shift-a`.
///
/// Only the three modifiers an Android keyboard has. Zed's `cmd`/`super`/
/// `win` and `fn` are refused with a sentence rather than mapped to Meta:
/// a binding the user cannot press should say so when it is written, not
/// silently never fire.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Keystroke {
    pub ctrl: bool,
    pub alt: bool,
    pub shift: bool,
    /// The key by Zed's name, lower-case: `a`, `f1`, `pagedown`, `[`.
    pub key: String,
}

/// Zed's own sentence for a keystroke that will not parse
/// (platform/keystroke.rs:65-67), less the modifiers this platform has not
/// got.
const EXPECTED: &str =
    "expected modifiers (ctrl, alt, shift) joined by \"-\" and then a key";

/// The keys with names. Zed accepts any single code point too; those are
/// checked separately, below.
const NAMED_KEYS: &[&str] = &[
    "enter", "escape", "tab", "backspace", "delete", "space", "up", "down", "left", "right",
    "home", "end", "pageup", "pagedown", "insert", "f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8",
    "f9", "f10", "f11", "f12",
];

/// The punctuation an Android key event carries as a key of its own — the
/// unshifted glyph on a US layout, which is what `KeyEvent.KEYCODE_*` names.
const PUNCTUATION: &[char] = &['-', '=', '[', ']', ';', '\'', ',', '.', '/', '\\', '`'];

/// A character typed with Shift on a US layout, and the key that types it.
///
/// Zed writes `ctrl-{` for Fold and `ctrl-}` for Unfold and its base keymaps
/// are full of `ctrl-!`, `ctrl-<`, `alt-?`. An Android key event for those
/// arrives as Shift plus the base key, so the glyph is folded into that here
/// — exactly what gpui does on Linux when it matches a keystroke against a
/// binding by its key equivalent.
const SHIFTED: &[(char, char)] = &[
    ('!', '1'), ('@', '2'), ('#', '3'), ('$', '4'), ('%', '5'), ('^', '6'), ('&', '7'),
    ('*', '8'), ('(', '9'), (')', '0'), ('_', '-'), ('+', '='), ('{', '['), ('}', ']'),
    ('|', '\\'), (':', ';'), ('"', '\''), ('<', ','), ('>', '.'), ('?', '/'), ('~', '`'),
];

impl Keystroke {
    /// Parse gpui's `[ctrl-][alt-][shift-]key` (platform/keystroke.rs:118).
    ///
    /// A single upper-case letter means Shift plus the lower-case one, as it
    /// does there (:176-179); named keys are case-insensitive. A keystroke
    /// that is only modifiers — Zed's `shift shift` — is refused: this app
    /// has no key-release matching.
    pub fn parse(source: &str) -> Result<Self, String> {
        let mut stroke = Keystroke {
            ctrl: false,
            alt: false,
            shift: false,
            key: String::new(),
        };
        let mut key: Option<String> = None;
        let mut components = source.split('-').peekable();
        while let Some(component) = components.next() {
            let lower = component.to_ascii_lowercase();
            match lower.as_str() {
                "ctrl" | "secondary" => {
                    stroke.ctrl = true;
                    continue;
                }
                "alt" => {
                    stroke.alt = true;
                    continue;
                }
                "shift" => {
                    stroke.shift = true;
                    continue;
                }
                "cmd" | "super" | "win" | "fn" => {
                    return Err(format!(
                        "\"{source}\" uses \"{lower}\", which an Android keyboard has not got"
                    ));
                }
                _ => {}
            }
            if components.peek().is_some() {
                // `ctrl--`: the key is the dash itself (keystroke.rs:154-156).
                if component.is_empty() && source.ends_with('-') && components.clone().count() == 1
                {
                    key = Some("-".to_owned());
                    break;
                }
                return Err(format!("\"{source}\" is not a keystroke: {EXPECTED}"));
            }
            if component.is_empty() {
                return Err(format!("\"{source}\" is not a keystroke: {EXPECTED}"));
            }
            key = Some(component.to_owned());
        }
        let Some(raw) = key else {
            return Err(format!(
                "\"{source}\" has no key: a modifier on its own cannot be bound here"
            ));
        };
        stroke.key = normalise_key(&raw, &mut stroke.shift)
            .ok_or_else(|| format!("\"{source}\" names a key this app cannot match: \"{raw}\""))?;
        Ok(stroke)
    }

    /// The whole sequence — `ctrl-k ctrl-0` — as Zed splits it: on
    /// whitespace (keymap_file.rs:83-84).
    pub fn parse_sequence(source: &str) -> Result<Vec<Self>, String> {
        let strokes: Result<Vec<_>, _> = source.split_whitespace().map(Self::parse).collect();
        let strokes = strokes?;
        if strokes.is_empty() {
            return Err("an empty string is not a keystroke".to_owned());
        }
        Ok(strokes)
    }
}

/// The canonical spelling of a key, or none when it is not one this app can
/// hear. Sets `shift` for a shifted glyph or an upper-case letter.
fn normalise_key(raw: &str, shift: &mut bool) -> Option<String> {
    let mut chars = raw.chars();
    if let (Some(c), None) = (chars.next(), chars.next()) {
        if c.is_ascii_uppercase() {
            *shift = true;
            return Some(c.to_ascii_lowercase().to_string());
        }
        if c.is_ascii_lowercase() || c.is_ascii_digit() || PUNCTUATION.contains(&c) {
            return Some(c.to_string());
        }
        if let Some((_, base)) = SHIFTED.iter().find(|(glyph, _)| *glyph == c) {
            *shift = true;
            return Some(base.to_string());
        }
        return None;
    }
    let lower = raw.to_ascii_lowercase();
    // Zed's own aliases, so a keymap written against its docs reads here.
    let lower = match lower.as_str() {
        "esc" => "escape".to_owned(),
        "return" => "enter".to_owned(),
        "del" => "delete".to_owned(),
        other => other.to_owned(),
    };
    NAMED_KEYS.contains(&lower.as_str()).then_some(lower)
}

impl fmt::Display for Keystroke {
    /// Zed's order: `ctrl-alt-shift-key`.
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if self.ctrl {
            f.write_str("ctrl-")?;
        }
        if self.alt {
            f.write_str("alt-")?;
        }
        if self.shift {
            f.write_str("shift-")?;
        }
        f.write_str(&self.key)
    }
}

/// A sequence written back the way it was read: strokes joined by spaces.
pub fn sequence_to_string(strokes: &[Keystroke]) -> String {
    strokes
        .iter()
        .map(ToString::to_string)
        .collect::<Vec<_>>()
        .join(" ")
}

/// Where a binding is listened for — the subset of Zed's context tree
/// (docs/src/key-bindings.md:143-160) this app has. `Global` is a section
/// with no `"context"` at all: active everywhere. Deeper wins over shallower
/// when two bindings share a keystroke, exactly as in Zed.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum KeymapContext {
    Global,
    Workspace,
    Pane,
    Editor,
    Terminal,
    /// The agent panel's composer — Zed's `AgentPanel`, where `ctrl-n` is
    /// a new thread and the `AcpThread` permission chords live.
    AgentPanel,
    /// A picture as the open tab — Zed's `ImageViewer`
    /// (default-linux.json:1566), where the zoom chords sit on the UI font
    /// size's keys and win over them.
    ImageViewer,
}

impl KeymapContext {
    fn by_name(name: &str) -> Option<Self> {
        match name {
            "Workspace" => Some(Self::Workspace),
            "Pane" => Some(Self::Pane),
            "Editor" => Some(Self::Editor),
            "Terminal" => Some(Self::Terminal),
            "AgentPanel" | "AcpThread" => Some(Self::AgentPanel),
            "ImageViewer" => Some(Self::ImageViewer),
            _ => None,
        }
    }
}

/// The contexts a section's `"context"` expression names, as far as this app
/// can tell.
///
/// Zed's expressions are a small language (`X && Y`, `X || Y`, `!X`, `>`,
/// `==`); this app has six contexts and no attributes, so it reads only the
/// shapes that mean something here: a bare name, alternatives joined by
/// `||`, and a name qualified by `&& mode == full` — Zed's spelling for "the
/// real editor, not an input box", which is every editor here. Anything else
/// (`ProjectPanel`, `!Editor`, `showing_completions`) is a context this app
/// does not have, and the section is skipped the way Zed skips a section
/// whose predicate never matches: quietly.
fn contexts_for(expression: &str) -> Vec<KeymapContext> {
    let expression = expression.trim();
    if expression.is_empty() {
        return vec![KeymapContext::Global];
    }
    let mut contexts = Vec::new();
    for alternative in expression.split("||") {
        let mut named = None;
        let mut understood = true;
        for conjunct in alternative.split("&&") {
            let conjunct = conjunct.trim();
            if let Some(context) = KeymapContext::by_name(conjunct) {
                if named.replace(context).is_some() {
                    understood = false;
                }
            } else if conjunct.split_whitespace().collect::<Vec<_>>() != ["mode", "==", "full"] {
                understood = false;
            }
        }
        if let (true, Some(context)) = (understood, named) {
            if !contexts.contains(&context) {
                contexts.push(context);
            }
        } else {
            log::debug!("keymap: context {alternative:?} is not one this app has; skipped");
        }
    }
    contexts
}

/// Who wrote a binding. Later sources outrank earlier ones at the same
/// context depth, which is how a user binding beats a default.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum KeybindSource {
    Default,
    Base,
    User,
}

/// One entry of the resolved list, in the order the app must consider them.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ResolvedBinding {
    pub context: KeymapContext,
    /// The sequence, normalised: `ctrl-k ctrl-0`.
    pub keystrokes: String,
    /// The action, or none for `null` — an unbinding that also shadows
    /// anything shallower.
    pub action: Option<String>,
    /// The action's argument where it has one (`["pane::ActivateItem", 3]`),
    /// as JSON. Passed through untouched: what it means is the action's.
    pub args: Option<serde_json::Value>,
    pub source: KeybindSource,
}

/// What [`crate::Engine::load_keymap`] hands back.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct KeymapLoad {
    pub bindings: Vec<ResolvedBinding>,
    /// Sentences about what could not be used, for the toast and the
    /// settings screen. Empty when everything loaded.
    pub errors: Vec<String>,
}

/// How strictly unknown actions are treated.
#[derive(Clone, Copy, PartialEq, Eq)]
enum UnknownActions {
    /// Report them — the user's file, where a typo deserves a sentence.
    Report,
    /// Drop them at debug level — a base keymap, which names hundreds of
    /// actions this app has not got, and none of them is the user's mistake.
    Drop,
}

/// Parse one keymap file's text into bindings and complaints.
///
/// `known` is the set of action names the app has; `None` accepts any
/// name, which is how the defaults themselves are read. Lenient the way Zed
/// is (keymap_file.rs:268-380): a bad binding costs that binding, a bad
/// section costs that section, and only unparseable JSON costs the file.
fn parse_keymap(
    text: &str,
    source: KeybindSource,
    known: Option<&HashSet<String>>,
    unknown: UnknownActions,
) -> (Vec<ResolvedBinding>, Vec<String>) {
    let mut bindings = Vec::new();
    let mut errors = Vec::new();
    let sections: Vec<serde_json::Value> = match settings_json::parse_json_with_comments(text) {
        Ok(serde_json::Value::Array(sections)) => sections,
        Ok(_) => {
            errors.push("the keymap must be a JSON array of sections".to_owned());
            return (bindings, errors);
        }
        Err(err) => {
            errors.push(format!("the keymap could not be parsed: {err}"));
            return (bindings, errors);
        }
    };
    for (index, section) in sections.iter().enumerate() {
        let Some(object) = section.as_object() else {
            errors.push(format!("section {} is not an object", index + 1));
            continue;
        };
        let expression = match object.get("context") {
            None | Some(serde_json::Value::Null) => "",
            Some(serde_json::Value::String(text)) => text.as_str(),
            Some(_) => {
                errors.push(format!("section {}: \"context\" must be a string", index + 1));
                continue;
            }
        };
        let contexts = contexts_for(expression);
        if contexts.is_empty() {
            continue;
        }
        let entries = match object.get("bindings") {
            None | Some(serde_json::Value::Null) => continue,
            Some(serde_json::Value::Object(entries)) => entries,
            Some(_) => {
                errors.push(format!(
                    "section {}: \"bindings\" must be an object of keystrokes to actions",
                    index + 1
                ));
                continue;
            }
        };
        for (keystrokes, action) in entries {
            let strokes = match Keystroke::parse_sequence(keystrokes) {
                Ok(strokes) => strokes,
                Err(err) => {
                    errors.push(err);
                    continue;
                }
            };
            let (name, args) = match action_of(action) {
                Ok(parts) => parts,
                Err(err) => {
                    errors.push(format!("\"{keystrokes}\": {err}"));
                    continue;
                }
            };
            if let (Some(name), Some(known)) = (&name, known) {
                if !known.contains(name) {
                    match unknown {
                        UnknownActions::Report => errors.push(format!(
                            "\"{keystrokes}\": \"{name}\" is not an action this app has"
                        )),
                        UnknownActions::Drop => {
                            log::debug!("keymap: {source:?} binds {keystrokes:?} to {name}, which this app has not got; dropped")
                        }
                    }
                    continue;
                }
            }
            for context in &contexts {
                bindings.push(ResolvedBinding {
                    context: *context,
                    keystrokes: sequence_to_string(&strokes),
                    action: name.clone(),
                    args: args.clone(),
                    source,
                });
            }
        }
    }
    (bindings, errors)
}

/// An action value's name and argument — Zed's three shapes
/// (docs/src/key-bindings.md:167-169): `"name"`, `["name", arg]`, and
/// `null` to unbind.
fn action_of(
    value: &serde_json::Value,
) -> Result<(Option<String>, Option<serde_json::Value>), String> {
    match value {
        serde_json::Value::Null => Ok((None, None)),
        serde_json::Value::String(name) => Ok((Some(name.clone()), None)),
        serde_json::Value::Array(parts) => match parts.as_slice() {
            [serde_json::Value::String(name)] => Ok((Some(name.clone()), None)),
            [serde_json::Value::String(name), arg] => Ok((Some(name.clone()), Some(arg.clone()))),
            _ => Err("an action array is [\"name\"] or [\"name\", argument]".to_owned()),
        },
        other => Err(format!("{other} is not an action: expected a name, [\"name\", argument] or null")),
    }
}

/// The file written on first use: Zed's `initial.json`, in this app's words.
const INITIAL_FILE: &str = r#"// Seeker IDE keymap.
//
// The syntax is Zed's: https://zed.dev/docs/key-bindings
// Each keystroke is modifiers ("ctrl", "alt", "shift") joined by "-" and then
// a key; several keystrokes separated by spaces make a chord. Bind to null to
// switch a default off.
//
// To see every default binding run "zed: open default keymap" from the
// command palette.
[
  {
    "context": "Workspace",
    "bindings": {
      // "ctrl-shift-r": "workspace::Save"
    },
  },
  {
    "context": "Editor",
    "bindings": {
      // "ctrl-k ctrl-c": "editor::ToggleComments"
    },
  },
]
"#;

fn keymap_path_slot() -> &'static Mutex<Option<PathBuf>> {
    static PATH: OnceLock<Mutex<Option<PathBuf>>> = OnceLock::new();
    PATH.get_or_init(|| Mutex::new(None))
}

/// Point the keymap at a directory — the same one settings live in. Called
/// from [`crate::initialize`].
pub(crate) fn set_directory(directory: PathBuf) {
    *keymap_path_slot().lock().unwrap() = Some(directory.join("keymap.json"));
}

fn keymap_path() -> Option<PathBuf> {
    keymap_path_slot().lock().unwrap().clone()
}

impl crate::Engine {
    /// The keymap file's contents, writing the commented starter on first
    /// use so "open keymap" always has a file to open. Empty if the engine
    /// was never given a directory.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn keymap_text(&self) -> String {
        let Some(path) = keymap_path() else {
            return String::new();
        };
        match std::fs::read_to_string(&path) {
            Ok(text) => text,
            Err(_) => {
                if let Some(parent) = path.parent() {
                    let _ = std::fs::create_dir_all(parent);
                }
                let _ = std::fs::write(&path, INITIAL_FILE);
                INITIAL_FILE.to_owned()
            }
        }
    }

    /// The whole keymap, resolved: the app's defaults, then the base keymap
    /// `settings.json` names, then the user's file — in that order, so the
    /// later ones win where they collide, which is Zed's precedence
    /// (docs/src/key-bindings.md:176-177).
    ///
    /// `default_keymap` is the app's own table in keymap-file form. Its action
    /// names are what "known" means: a base keymap keeps only those (Zed's
    /// alternative keymaps name hundreds of actions this app has not got, and
    /// none is the user's mistake, so they go at debug level), while the
    /// user's file has each unknown name reported back. A base keymap of
    /// `"None"` leaves out the defaults too — the user's file is then the
    /// whole keymap, as in Zed (zed.rs:2357-2359).
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn load_keymap(&self, default_keymap: &str) -> KeymapLoad {
        let mut load = KeymapLoad::default();
        let (defaults, default_errors) =
            parse_keymap(default_keymap, KeybindSource::Default, None, UnknownActions::Report);
        for err in default_errors {
            // A fault in the app's own table, which is a bug rather than
            // something the user can fix — but silence would hide it.
            log::warn!("default keymap: {err}");
            load.errors.push(format!("default keymap: {err}"));
        }
        let known: HashSet<String> = defaults.iter().filter_map(|b| b.action.clone()).collect();

        let base = self.settings().base_keymap;
        if base != BaseKeymap::None {
            load.bindings.extend(defaults);
            if let Some(text) = base.asset() {
                let (bindings, errors) =
                    parse_keymap(text, KeybindSource::Base, Some(&known), UnknownActions::Drop);
                for err in errors {
                    log::debug!("base keymap {base:?}: {err}");
                }
                load.bindings.extend(bindings);
            }
        }

        let text = self.keymap_text();
        if !text.trim().is_empty() {
            let (bindings, errors) =
                parse_keymap(&text, KeybindSource::User, Some(&known), UnknownActions::Report);
            load.bindings.extend(bindings);
            load.errors.extend(errors.into_iter().map(|err| format!("keymap.json: {err}")));
        }
        load
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Engine;

    fn stroke(source: &str) -> Keystroke {
        Keystroke::parse(source).unwrap()
    }

    #[test]
    fn keystrokes_parse_in_zeds_syntax() {
        assert_eq!(
            stroke("ctrl-shift-a"),
            Keystroke { ctrl: true, alt: false, shift: true, key: "a".into() }
        );
        // Modifier order does not matter, and case does not either.
        assert_eq!(stroke("shift-ctrl-a"), stroke("Ctrl-Shift-A"));
        assert_eq!(stroke("alt-up").key, "up");
        assert!(stroke("alt-up").alt);
        assert_eq!(stroke("f12").key, "f12");
        assert_eq!(stroke("pagedown").key, "pagedown");
        // `secondary` is ctrl off a Mac (keystroke.rs:135-142).
        assert!(stroke("secondary-s").ctrl);
        // Zed's aliases.
        assert_eq!(stroke("esc").key, "escape");
    }

    #[test]
    fn an_upper_case_letter_means_shift() {
        // keystroke.rs:176-179.
        assert_eq!(stroke("ctrl-S"), stroke("ctrl-shift-s"));
    }

    #[test]
    fn punctuation_and_its_shifted_glyphs() {
        assert_eq!(stroke("ctrl-`").key, "`");
        assert_eq!(stroke("ctrl-,").key, ",");
        // `ctrl--` is Ctrl and the minus key (keystroke.rs:154-156).
        assert_eq!(stroke("ctrl--"), Keystroke { ctrl: true, alt: false, shift: false, key: "-".into() });
        // Zed writes Fold as `ctrl-{`; on this platform that is Shift and `[`.
        assert_eq!(stroke("ctrl-{"), stroke("ctrl-shift-["));
        assert_eq!(stroke("ctrl-alt-_"), stroke("ctrl-alt-shift--"));
        assert_eq!(stroke("ctrl-!"), stroke("ctrl-shift-1"));
    }

    #[test]
    fn what_is_not_a_keystroke_says_why() {
        assert!(Keystroke::parse("ctrl-shift").unwrap_err().contains("has no key"));
        assert!(Keystroke::parse("ctrl-a-b").unwrap_err().contains("not a keystroke"));
        assert!(Keystroke::parse("cmd-s").unwrap_err().contains("cmd"));
        assert!(Keystroke::parse("ctrl-bogus").unwrap_err().contains("bogus"));
        assert!(Keystroke::parse("").is_err());
        assert!(Keystroke::parse_sequence("   ").is_err());
    }

    #[test]
    fn a_chord_is_strokes_separated_by_spaces() {
        let chord = Keystroke::parse_sequence("ctrl-k  ctrl-0").unwrap();
        assert_eq!(chord.len(), 2);
        assert_eq!(chord[0], stroke("ctrl-k"));
        assert_eq!(chord[1], stroke("ctrl-0"));
        assert_eq!(sequence_to_string(&chord), "ctrl-k ctrl-0");
        // Normalised on the way out: Zed's order, lower case.
        assert_eq!(
            sequence_to_string(&Keystroke::parse_sequence("Shift-Ctrl-Alt-X").unwrap()),
            "ctrl-alt-shift-x"
        );
    }

    #[test]
    fn contexts_this_app_has_and_the_rest() {
        assert_eq!(contexts_for(""), vec![KeymapContext::Global]);
        assert_eq!(contexts_for("Editor"), vec![KeymapContext::Editor]);
        assert_eq!(contexts_for(" Terminal "), vec![KeymapContext::Terminal]);
        // Zed's `AgentPanel` and `AcpThread` are one surface here: the composer.
        assert_eq!(contexts_for("AgentPanel"), vec![KeymapContext::AgentPanel]);
        assert_eq!(contexts_for("AcpThread"), vec![KeymapContext::AgentPanel]);
        assert_eq!(contexts_for("ImageViewer"), vec![KeymapContext::ImageViewer]);
        // Zed's "the real editor" qualifier is every editor here.
        assert_eq!(contexts_for("Editor && mode == full"), vec![KeymapContext::Editor]);
        assert_eq!(
            contexts_for("Workspace || Editor"),
            vec![KeymapContext::Workspace, KeymapContext::Editor]
        );
        // Contexts this app has not got are skipped, not errors.
        assert!(contexts_for("ProjectPanel").is_empty());
        assert!(contexts_for("!Editor && !Terminal").is_empty());
        assert!(contexts_for("Editor && showing_completions").is_empty());
        assert_eq!(contexts_for("Dock || Workspace || OutlinePanel"), vec![KeymapContext::Workspace]);
    }

    #[test]
    fn a_file_parses_into_ordered_bindings_with_null_unbinding() {
        let (bindings, errors) = parse_keymap(
            r#"[
                // comments and trailing commas, as in Zed's own files
                { "context": "Workspace", "bindings": { "ctrl-shift-r": "workspace::Save", "ctrl-n": null, } },
                { "bindings": { "ctrl-k ctrl-c": ["editor::ToggleComments", { "advance_downwards": true }] } },
            ]"#,
            KeybindSource::User,
            None,
            UnknownActions::Report,
        );
        assert!(errors.is_empty(), "{errors:?}");
        assert_eq!(bindings.len(), 3);
        assert_eq!(bindings[0].context, KeymapContext::Workspace);
        assert_eq!(bindings[0].keystrokes, "ctrl-shift-r");
        assert_eq!(bindings[0].action.as_deref(), Some("workspace::Save"));
        assert_eq!(bindings[1].action, None);
        assert_eq!(bindings[2].context, KeymapContext::Global);
        assert_eq!(bindings[2].keystrokes, "ctrl-k ctrl-c");
        assert_eq!(bindings[2].args, Some(serde_json::json!({ "advance_downwards": true })));
        assert_eq!(bindings[2].source, KeybindSource::User);
    }

    #[test]
    fn invalid_entries_are_reported_and_the_valid_ones_kept() {
        let known: HashSet<String> = ["workspace::Save".to_owned()].into_iter().collect();
        let (bindings, errors) = parse_keymap(
            r#"[
                { "context": "Editor", "bindings": {
                    "ctrl-shift": "workspace::Save",
                    "ctrl-s": "workspace::Save",
                    "ctrl-x": "nope::Nothing",
                    "ctrl-y": 7
                } },
                "not a section",
                { "context": 3, "bindings": {} },
                { "context": "Workspace", "bindings": "nope" }
            ]"#,
            KeybindSource::User,
            Some(&known),
            UnknownActions::Report,
        );
        assert_eq!(bindings.len(), 1);
        assert_eq!(bindings[0].keystrokes, "ctrl-s");
        let joined = errors.join("\n");
        assert!(joined.contains("\"ctrl-shift\" has no key"), "{joined}");
        assert!(joined.contains("\"nope::Nothing\" is not an action this app has"), "{joined}");
        assert!(joined.contains("\"ctrl-y\": 7 is not an action"), "{joined}");
        assert!(joined.contains("section 2 is not an object"), "{joined}");
        assert!(joined.contains("section 3: \"context\" must be a string"), "{joined}");
        assert!(joined.contains("section 4: \"bindings\" must be an object"), "{joined}");
        assert_eq!(errors.len(), 6);
    }

    #[test]
    fn unparseable_json_costs_the_file_and_says_so() {
        let (bindings, errors) =
            parse_keymap("[ { nope", KeybindSource::User, None, UnknownActions::Report);
        assert!(bindings.is_empty());
        assert_eq!(errors.len(), 1);
        assert!(errors[0].starts_with("the keymap could not be parsed"));
        let (_, errors) = parse_keymap("{}", KeybindSource::User, None, UnknownActions::Report);
        assert!(errors[0].contains("JSON array"));
    }

    #[test]
    fn a_base_keymap_keeps_only_the_actions_the_app_has() {
        let known: HashSet<String> =
            ["editor::MoveLineUp".to_owned(), "file_finder::Toggle".to_owned()].into_iter().collect();
        let (bindings, errors) = parse_keymap(
            BaseKeymap::Atom.asset().unwrap(),
            KeybindSource::Base,
            Some(&known),
            UnknownActions::Drop,
        );
        // Dropped silently: nothing Atom binds that the app lacks is an error.
        assert!(errors.is_empty(), "{errors:?}");
        let actions: HashSet<&str> = bindings.iter().filter_map(|b| b.action.as_deref()).collect();
        assert_eq!(actions, ["editor::MoveLineUp", "file_finder::Toggle"].into_iter().collect());
        // Atom's `ctrl-up` for MoveLineUp is an `Editor && mode == full` section.
        let up = bindings.iter().find(|b| b.action.as_deref() == Some("editor::MoveLineUp")).unwrap();
        assert_eq!(up.keystrokes, "ctrl-up");
        assert_eq!(up.context, KeymapContext::Editor);
        assert_eq!(up.source, KeybindSource::Base);
    }

    /// Every shipped base keymap has to parse — at least as far as its JSON
    /// and every keystroke this app can hear.
    #[test]
    fn every_shipped_base_keymap_parses() {
        for base in [
            BaseKeymap::VSCode,
            BaseKeymap::JetBrains,
            BaseKeymap::SublimeText,
            BaseKeymap::Atom,
            BaseKeymap::Emacs,
        ] {
            let (bindings, errors) = parse_keymap(
                base.asset().unwrap(),
                KeybindSource::Base,
                None,
                UnknownActions::Drop,
            );
            assert!(!bindings.is_empty(), "{base:?} yielded nothing");
            // Only keystrokes the platform cannot press may be complained
            // about (JetBrains' `shift shift`, say) — never the JSON.
            for err in &errors {
                assert!(!err.contains("could not be parsed"), "{base:?}: {err}");
            }
        }
        assert!(BaseKeymap::None.asset().is_none());
    }

    /// The engine seam: defaults, then the base, then the user's file, with
    /// the user's errors reported and the defaults' names deciding what a
    /// base keymap may keep.
    #[test]
    fn load_keymap_layers_defaults_base_and_user() {
        let dir = tempfile::tempdir().unwrap();
        let _guard = crate::config::tests::settings_lock();
        crate::config::set_directory(dir.path().to_path_buf());
        set_directory(dir.path().to_path_buf());
        let engine = Engine::new();
        let defaults = r#"[
            { "context": "Workspace", "bindings": { "ctrl-s": "workspace::Save", "ctrl-p": "file_finder::Toggle" } },
            { "context": "Editor", "bindings": { "alt-up": "editor::MoveLineUp" } }
        ]"#;

        // First load writes the starter file, which binds nothing. The three
        // defaults come through, and so does the one line of the VS Code
        // overlay that needs no action of ours: its `"ctrl-enter": null`
        // for the terminal — an unbinding is kept whatever the base names,
        // since switching a key off is the point of it.
        let load = engine.load_keymap(defaults);
        assert!(load.errors.is_empty(), "{:?}", load.errors);
        assert_eq!(load.bindings.len(), 4);
        assert_eq!(load.bindings[3].source, KeybindSource::Base);
        assert_eq!(load.bindings[3].action, None);
        assert!(dir.path().join("keymap.json").exists());
        assert!(engine.keymap_text().contains("// Seeker IDE keymap."));

        std::fs::write(
            dir.path().join("keymap.json"),
            r#"[
                { "context": "Workspace", "bindings": { "ctrl-shift-r": "workspace::Save", "ctrl-p": null, "ctrl-q": "zed::Nope" } },
                { "context": "Editor", "bindings": { "ctrl-up": "editor::MoveLineUp", "ctrl-": "editor::MoveLineUp" } }
            ]"#,
        )
        .unwrap();
        let load = engine.load_keymap(defaults);
        let user: Vec<_> = load.bindings.iter().filter(|b| b.source == KeybindSource::User).collect();
        assert_eq!(user.len(), 3);
        assert_eq!(user[1].action, None);
        assert_eq!(load.errors.len(), 2, "{:?}", load.errors);
        assert!(load.errors[0].contains("\"zed::Nope\" is not an action this app has"));
        assert!(load.errors[1].contains("\"ctrl-\""));
        // Defaults come first, so the app's precedence rule ("later wins")
        // puts the user's `ctrl-p: null` over the default finder.
        assert_eq!(load.bindings[0].source, KeybindSource::Default);

        // Atom as the base keymap: its `ctrl-up` MoveLineUp lands between
        // the defaults and the user's bindings, and everything it names that
        // the app has not got is left out without a word.
        engine.set_setting(&["base_keymap"], serde_json::json!("Atom")).unwrap();
        let load = engine.load_keymap(defaults);
        let sources: Vec<_> = load.bindings.iter().map(|b| b.source).collect();
        let first_base = sources.iter().position(|s| *s == KeybindSource::Base).unwrap();
        let first_user = sources.iter().position(|s| *s == KeybindSource::User).unwrap();
        assert!(first_base > 2 && first_base < first_user);
        assert!(load.bindings.iter().all(|b| b.action.as_deref() != Some("editor::JoinLines")));
        assert_eq!(load.errors.len(), 2);

        // "None" is Zed's "no defaults at all": the user's file is the keymap.
        engine.set_setting(&["base_keymap"], serde_json::json!("None")).unwrap();
        let load = engine.load_keymap(defaults);
        assert!(load.bindings.iter().all(|b| b.source == KeybindSource::User));
    }
}
