//! Appearance settings: which theme, which fonts, which icon theme.
//!
//! These are the `theme`, `icon_theme`, `theme_overrides` and font keys of
//! Zed's `ThemeSettingsContent` (`settings_content/src/theme.rs:176-255`),
//! parsed in the same shapes so a block pasted out of a Zed settings file
//! works here. The resolution — which of `light`/`dark` a mode picks, what a
//! line-height word is worth, which family a missing name falls back to —
//! lives here rather than in Kotlin because it is arithmetic over the file,
//! and the file is the engine's.
//!
//! What is *not* here: reading theme JSON. A theme's palette is a colour
//! table the UI paints with and nothing in the engine ever looks at, so it
//! is parsed on the Kotlin side (`ui/theme/ZedTheme.kt`) where it is used.

use std::collections::BTreeMap;

use serde::{Deserialize, Deserializer, Serialize};

/// Which theme to use. `System` follows the device's light/dark setting.
///
/// Zed's `ThemeAppearanceMode` (`settings_content/src/theme.rs:395-410`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum ThemeMode {
    #[default]
    System,
    Light,
    Dark,
}

/// Zed's defaults (`settings_content/src/theme.rs:354-355`).
pub const DEFAULT_LIGHT_THEME: &str = "One Light";
pub const DEFAULT_DARK_THEME: &str = "One Dark";

/// Zed's own icon theme, which is the one this app bundles
/// (`theme/src/icon_theme.rs:424`).
pub const DEFAULT_ICON_THEME: &str = "Zed (Default)";

/// Zed's `theme`: a bare theme name, or an object that names one theme per
/// appearance and says how to choose between them
/// (`settings_content/src/theme.rs:337-350`).
///
/// ```json
/// "theme": "One Dark"
/// "theme": { "mode": "system", "light": "One Light", "dark": "One Dark" }
/// ```
///
/// **Plus one shape Zed does not have**: the three bare words `"system"`,
/// `"light"` and `"dark"`. Earlier versions of this app spelled the *mode*
/// there — `theme` was a three-valued enum and the two names lived in app
/// preferences — and those files are still on people's devices. Read as a
/// theme name each would resolve to nothing and paint One Dark with a
/// warning; read as a mode they keep meaning what they meant. No Zed theme
/// is named for an appearance in lower case, so nothing legitimate is
/// shadowed.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(untagged)]
pub enum ThemeSelection {
    /// One theme, whatever the device is doing.
    Static(String),
    /// One theme per appearance, chosen by [`ThemeMode`].
    Dynamic {
        mode: ThemeMode,
        light: String,
        dark: String,
    },
}

impl Default for ThemeSelection {
    fn default() -> Self {
        Self::Dynamic {
            mode: ThemeMode::System,
            light: DEFAULT_LIGHT_THEME.to_owned(),
            dark: DEFAULT_DARK_THEME.to_owned(),
        }
    }
}

impl ThemeSelection {
    /// How the two slots are chosen between. A bare name has no mode: it is
    /// that theme in every appearance, and the *theme's* own appearance is
    /// then what the UI paints light or dark from.
    pub fn mode(&self) -> Option<ThemeMode> {
        match self {
            Self::Static(_) => None,
            Self::Dynamic { mode, .. } => Some(*mode),
        }
    }

    /// The theme for the light appearance.
    pub fn light(&self) -> &str {
        match self {
            Self::Static(name) => name,
            Self::Dynamic { light, .. } => light,
        }
    }

    /// The theme for the dark appearance.
    pub fn dark(&self) -> &str {
        match self {
            Self::Static(name) => name,
            Self::Dynamic { dark, .. } => dark,
        }
    }

    /// Whether this selection means dark right now, given what the device
    /// says. A bare name answers `None`: only the theme file knows, and the
    /// UI has it.
    pub fn is_dark(&self, system_is_dark: bool) -> Option<bool> {
        Some(match self.mode()? {
            ThemeMode::System => system_is_dark,
            ThemeMode::Light => false,
            ThemeMode::Dark => true,
        })
    }

    /// The theme to paint with. A bare name is itself; an object picks the
    /// slot its mode resolves to.
    pub fn theme_name(&self, system_is_dark: bool) -> &str {
        match self.is_dark(system_is_dark) {
            None => self.light(),
            Some(true) => self.dark(),
            Some(false) => self.light(),
        }
    }

    /// The selection with `name` filling the slot for its own appearance,
    /// the other slot left alone — Zed's
    /// `theme_selector.rs:retain_original_opposing_theme`, which is what
    /// makes "follow the system" keep working after you have chosen both.
    ///
    /// A bare name becomes an object, because there is no other way to hold
    /// two names; the mode it gains is the one that keeps the app painting
    /// what it is painting now.
    pub fn with(&self, name: &str, name_is_dark: bool) -> Self {
        let mode = self.mode().unwrap_or(ThemeMode::System);
        let (mut light, mut dark) = (self.light().to_owned(), self.dark().to_owned());
        if name_is_dark {
            dark = name.to_owned();
        } else {
            light = name.to_owned();
        }
        Self::Dynamic { mode, light, dark }
    }
}

impl ThemeSelection {
    /// This selection under a different mode, both names kept — the theme
    /// picker's mode row. A bare name becomes an object, because a mode is
    /// only meaningful when there are two slots to choose between.
    pub fn with_mode(&self, mode: ThemeMode) -> Self {
        Self::Dynamic {
            mode,
            light: self.light().to_owned(),
            dark: self.dark().to_owned(),
        }
    }
}

/// The legacy mode words, and the [`ThemeMode`] each one meant.
fn legacy_mode(word: &str) -> Option<ThemeMode> {
    match word {
        "system" => Some(ThemeMode::System),
        "light" => Some(ThemeMode::Light),
        "dark" => Some(ThemeMode::Dark),
        _ => None,
    }
}

impl<'de> Deserialize<'de> for ThemeSelection {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        // `light` and `dark` are required in Zed. Optional here, defaulted to
        // Zed's own two: `{"mode": "dark"}` is what someone writes when all
        // they want is to stop following the system, and refusing it would
        // fail the whole file rather than one key.
        #[derive(Deserialize)]
        #[serde(untagged)]
        enum Raw {
            Name(String),
            Object {
                #[serde(default)]
                mode: ThemeMode,
                #[serde(default)]
                light: Option<String>,
                #[serde(default)]
                dark: Option<String>,
            },
        }
        Ok(match Raw::deserialize(deserializer)? {
            Raw::Name(name) => match legacy_mode(&name) {
                Some(mode) => ThemeSelection::Dynamic {
                    mode,
                    light: DEFAULT_LIGHT_THEME.to_owned(),
                    dark: DEFAULT_DARK_THEME.to_owned(),
                },
                None => ThemeSelection::Static(name),
            },
            Raw::Object { mode, light, dark } => ThemeSelection::Dynamic {
                mode,
                light: light.unwrap_or_else(|| DEFAULT_LIGHT_THEME.to_owned()),
                dark: dark.unwrap_or_else(|| DEFAULT_DARK_THEME.to_owned()),
            },
        })
    }
}

/// Zed's `icon_theme`, which takes the same two shapes as `theme`
/// (`settings_content/src/theme.rs:379-394`). No legacy words here: the key
/// is new.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum IconThemeSelection {
    Static(String),
    Dynamic {
        #[serde(default)]
        mode: ThemeMode,
        light: String,
        dark: String,
    },
}

impl Default for IconThemeSelection {
    fn default() -> Self {
        Self::Static(DEFAULT_ICON_THEME.to_owned())
    }
}

impl IconThemeSelection {
    pub fn light(&self) -> &str {
        match self {
            Self::Static(name) => name,
            Self::Dynamic { light, .. } => light,
        }
    }

    pub fn dark(&self) -> &str {
        match self {
            Self::Static(name) => name,
            Self::Dynamic { dark, .. } => dark,
        }
    }

    /// The icon theme to draw with, given the appearance in effect.
    pub fn icon_theme_name(&self, is_dark: bool) -> &str {
        match self {
            Self::Static(name) => name,
            Self::Dynamic { mode, light, dark } => match mode {
                ThemeMode::System => {
                    if is_dark {
                        dark
                    } else {
                        light
                    }
                }
                ThemeMode::Light => light,
                ThemeMode::Dark => dark,
            },
        }
    }
}

/// Zed's `buffer_line_height` (`settings_content/src/theme.rs:509-517`):
/// two words and one object, `{"custom": 1.4}`.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum BufferLineHeight {
    /// φ. Zed's default, and the leading this editor already draws with.
    #[default]
    Comfortable,
    /// Tighter — Zed's "standard".
    Standard,
    /// A multiple of the font's own height, at least 1.
    Custom(f32),
}

impl BufferLineHeight {
    /// Zed's own numbers (`theme/src/buffer_line_height.rs:15-21`).
    pub fn value(self) -> f32 {
        match self {
            Self::Comfortable => 1.618,
            Self::Standard => 1.3,
            Self::Custom(value) => value,
        }
    }

    /// A hand-edited file's number brought back into range: below 1 the
    /// lines overlap and the caret is taller than its row; above 3 a phone
    /// screen holds four lines of code.
    pub(crate) fn sanitized(self) -> Self {
        match self {
            Self::Custom(value) => Self::Custom(value.clamp(1.0, 3.0)),
            other => other,
        }
    }
}

/// Zed's `buffer_font_weight` / `ui_font_weight`: a CSS weight, 100 to 900
/// (`settings_content/src/theme.rs:200-202`).
pub const DEFAULT_FONT_WEIGHT: f32 = 400.0;

/// OpenType features, `{"calt": false, "liga": true}` — Zed's
/// `FontFeaturesContent` (`settings_content/src/theme.rs:13-20`), read with
/// its own leniency: a tag that is not four alphanumerics, or a value that
/// is neither a bool nor a whole number, is dropped with a log rather than
/// failing the file.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Default)]
#[serde(transparent)]
pub struct FontFeatures(pub BTreeMap<String, u32>);

impl FontFeatures {
    /// Whether a feature is switched on. Absent means "the font decides",
    /// which for ligatures means on.
    pub fn get(&self, tag: &str) -> Option<u32> {
        self.0.get(tag).copied()
    }

    /// Zed's ligature tags, which are what a settings row can honestly
    /// offer on this platform: Android's text stack exposes `liga`/`calt`
    /// through a font-feature-settings string and nothing finer.
    pub const LIGATURES: [&'static str; 2] = ["calt", "liga"];

    /// Whether ligatures are off — both tags explicitly zero.
    pub fn ligatures_disabled(&self) -> bool {
        Self::LIGATURES
            .iter()
            .any(|tag| self.get(tag) == Some(0))
    }
}

impl<'de> Deserialize<'de> for FontFeatures {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        #[derive(Deserialize)]
        #[serde(untagged)]
        enum Value {
            Bool(bool),
            Number(u32),
        }
        let raw = BTreeMap::<String, Option<Value>>::deserialize(deserializer)?;
        let mut features = BTreeMap::new();
        for (tag, value) in raw {
            if tag.len() != 4 || !tag.chars().all(|c| c.is_ascii_alphanumeric()) {
                log::warn!("settings: font feature tag {tag:?} is not four alphanumerics; dropped");
                continue;
            }
            match value {
                Some(Value::Bool(on)) => {
                    features.insert(tag, u32::from(on));
                }
                Some(Value::Number(number)) => {
                    features.insert(tag, number);
                }
                // Zed treats an explicit null as "say nothing about it".
                None => {}
            }
        }
        Ok(Self(features))
    }
}

/// The font settings resolved into the four numbers and two names the UI
/// draws with — what [`crate::Settings::buffer_font`] answers.
#[derive(Debug, Clone, PartialEq)]
pub struct ResolvedFont {
    /// The family to ask the platform for, or `None` for the bundled face.
    pub family: Option<String>,
    /// Families to try, in order, when a glyph is missing from [`family`].
    ///
    /// [`family`]: ResolvedFont::family
    pub fallbacks: Vec<String>,
    pub size: f32,
    /// A CSS weight, 100..900.
    pub weight: f32,
    /// The multiple of the font's height one line occupies.
    pub line_height: f32,
    pub features: FontFeatures,
}

impl ResolvedFont {
    /// The line's height in the same units as [`size`].
    ///
    /// [`size`]: ResolvedFont::size
    pub fn line_height_px(&self) -> f32 {
        self.size * self.line_height
    }
}

/// Zed's `ui_font_size` default, which is also gpui's `BASE_REM_SIZE_IN_PX`
/// (`assets/settings/default.json:71`, `ui/src/styles/units.rs:4`). One rem
/// is this many dp, and every chrome dimension is a multiple of it.
pub const DEFAULT_UI_FONT_SIZE: f32 = 16.0;

/// Zed clamps font sizes to 6..100 (`theme_settings/src/settings.rs:18-19`);
/// the UI font is narrower here, because this one scales the *chrome* rather
/// than a paragraph. Below 10 the tab bar's 2rem height is a 20dp strip no
/// finger can hit, and above 32 a title bar is half a phone's screen — both
/// ends leave the app unusable from the screen you would have to fix it on.
pub const MIN_UI_FONT_SIZE: f32 = 10.0;
pub const MAX_UI_FONT_SIZE: f32 = 32.0;

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn theme(value: serde_json::Value) -> ThemeSelection {
        serde_json::from_value(value).unwrap()
    }

    #[test]
    fn a_bare_name_is_that_theme_in_both_appearances() {
        let selection = theme(json!("Gruvbox Dark Hard"));
        assert_eq!(selection, ThemeSelection::Static("Gruvbox Dark Hard".into()));
        assert_eq!(selection.mode(), None);
        assert_eq!(selection.theme_name(true), "Gruvbox Dark Hard");
        assert_eq!(selection.theme_name(false), "Gruvbox Dark Hard");
    }

    #[test]
    fn the_object_form_picks_the_slot_its_mode_names() {
        let selection = theme(json!({ "mode": "system", "light": "Ayu Light", "dark": "Ayu Dark" }));
        assert_eq!(selection.mode(), Some(ThemeMode::System));
        assert_eq!(selection.theme_name(true), "Ayu Dark");
        assert_eq!(selection.theme_name(false), "Ayu Light");

        let pinned = theme(json!({ "mode": "light", "light": "Ayu Light", "dark": "Ayu Dark" }));
        // Pinned light stays light however dark the device is.
        assert_eq!(pinned.theme_name(true), "Ayu Light");
        assert_eq!(pinned.theme_name(false), "Ayu Light");

        let pinned = theme(json!({ "mode": "dark", "light": "Ayu Light", "dark": "Ayu Dark" }));
        assert_eq!(pinned.theme_name(false), "Ayu Dark");
    }

    #[test]
    fn a_half_written_object_keeps_zeds_defaults_for_the_slot_it_omits() {
        let selection = theme(json!({ "mode": "dark" }));
        assert_eq!(selection.light(), DEFAULT_LIGHT_THEME);
        assert_eq!(selection.dark(), DEFAULT_DARK_THEME);
        // No `mode` at all is "system", as Zed's `#[serde(default)]` has it.
        assert_eq!(
            theme(json!({ "light": "Ayu Light", "dark": "Ayu Dark" })).mode(),
            Some(ThemeMode::System)
        );
    }

    #[test]
    fn the_three_legacy_mode_words_still_mean_a_mode() {
        for (word, mode) in [
            ("system", ThemeMode::System),
            ("light", ThemeMode::Light),
            ("dark", ThemeMode::Dark),
        ] {
            let selection = theme(json!(word));
            assert_eq!(selection.mode(), Some(mode), "{word}");
            assert_eq!(selection.light(), DEFAULT_LIGHT_THEME);
            assert_eq!(selection.dark(), DEFAULT_DARK_THEME);
        }
        // A theme actually named for an appearance is not shadowed: the
        // words are matched exactly, lower case and all.
        assert_eq!(
            theme(json!("One Light")),
            ThemeSelection::Static("One Light".into())
        );
    }

    #[test]
    fn choosing_a_theme_leaves_the_opposing_slot_alone() {
        let selection = theme(json!({ "mode": "system", "light": "Ayu Light", "dark": "Ayu Dark" }));
        let after = selection.with("Gruvbox Dark Hard", true);
        assert_eq!(after.dark(), "Gruvbox Dark Hard");
        assert_eq!(after.light(), "Ayu Light");
        assert_eq!(after.mode(), Some(ThemeMode::System));

        // A bare name has to become an object to hold two, and keeps
        // painting what it was painting.
        let after = ThemeSelection::Static("One Dark".into()).with("One Light", false);
        assert_eq!(after.light(), "One Light");
        assert_eq!(after.dark(), "One Dark");
    }

    #[test]
    fn setting_a_mode_keeps_both_names() {
        let selection = theme(json!({ "mode": "system", "light": "Ayu Light", "dark": "Ayu Dark" }));
        let pinned = selection.with_mode(ThemeMode::Dark);
        assert_eq!(pinned.mode(), Some(ThemeMode::Dark));
        assert_eq!(pinned.light(), "Ayu Light");
        assert_eq!(pinned.dark(), "Ayu Dark");
        // A bare name gains a mode by becoming an object with itself in both
        // slots, so nothing the app is painting changes on the way.
        let from_static = ThemeSelection::Static("Ayu Dark".into()).with_mode(ThemeMode::System);
        assert_eq!(from_static.light(), "Ayu Dark");
        assert_eq!(from_static.dark(), "Ayu Dark");
    }

    #[test]
    fn a_theme_selection_round_trips_through_json() {
        for value in [
            json!("One Dark"),
            json!({ "mode": "dark", "light": "One Light", "dark": "One Dark" }),
        ] {
            let selection = theme(value.clone());
            assert_eq!(serde_json::to_value(&selection).unwrap(), value);
        }
    }

    #[test]
    fn the_icon_theme_takes_the_same_two_shapes() {
        let selection: IconThemeSelection = serde_json::from_value(json!("Zed (Default)")).unwrap();
        assert_eq!(selection.icon_theme_name(true), "Zed (Default)");
        let selection: IconThemeSelection =
            serde_json::from_value(json!({ "mode": "system", "light": "Pastel", "dark": "Neon" }))
                .unwrap();
        assert_eq!(selection.icon_theme_name(true), "Neon");
        assert_eq!(selection.icon_theme_name(false), "Pastel");
    }

    #[test]
    fn line_heights_are_zeds_numbers() {
        let comfortable: BufferLineHeight = serde_json::from_value(json!("comfortable")).unwrap();
        assert_eq!(comfortable.value(), 1.618);
        let standard: BufferLineHeight = serde_json::from_value(json!("standard")).unwrap();
        assert_eq!(standard.value(), 1.3);
        let custom: BufferLineHeight = serde_json::from_value(json!({ "custom": 1.45 })).unwrap();
        assert_eq!(custom.value(), 1.45);
        // Out of range is clamped, not refused: the rest of the file stands.
        assert_eq!(BufferLineHeight::Custom(0.2).sanitized().value(), 1.0);
        assert_eq!(BufferLineHeight::Custom(40.0).sanitized().value(), 3.0);
    }

    #[test]
    fn font_features_take_bools_and_numbers_and_drop_nonsense() {
        let features: FontFeatures = serde_json::from_value(json!({
            "calt": false,
            "liga": true,
            "ss01": 2,
            "not-a-tag": true,
            "cv01": null,
        }))
        .unwrap();
        assert_eq!(features.get("calt"), Some(0));
        assert_eq!(features.get("liga"), Some(1));
        assert_eq!(features.get("ss01"), Some(2));
        assert_eq!(features.get("not-a-tag"), None);
        assert_eq!(features.get("cv01"), None);
        assert!(features.ligatures_disabled());
    }

}
