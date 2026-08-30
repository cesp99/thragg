//! `file_types`: the user's own name for a file, ahead of ours.
//!
//! Zed lets settings map globs onto a language —
//! `"file_types": {"JSON": ["*.jsonc", ".babelrc"], "Shell Script": ["*.env*"]}`
//! — and consults that table *before* the languages' own `path_suffixes`
//! (`language_registry.rs`, `language_for_file_internal`), so it can override
//! the built-in answer as well as extend it. That ordering is the point: a
//! project whose `*.json` files are really JSON-with-comments has no other
//! way to say so.
//!
//! The globs go through `util::paths::PathMatcher`, the same matcher the
//! project search's include/exclude fields use, so a pattern means here
//! exactly what it means there — and what it means in Zed.

use std::collections::BTreeMap;

use path::PathStyle;
use path::rel_path::RelPath;
use util::paths::PathMatcher;

use crate::highlight::grammar_for_name;

/// The compiled `file_types` table. Built once per resolution round — a
/// worktree scan asks about thousands of paths and must not recompile a glob
/// set per file.
#[derive(Default)]
pub struct FileTypes {
    /// Grammar name and the matcher that claims it, in the settings' key
    /// order (a `BTreeMap`, so alphabetical and the same on every launch).
    rules: Vec<(&'static str, PathMatcher)>,
}

impl FileTypes {
    /// Compile a `file_types` map. Later maps win, which is how a project's
    /// `.zed/settings.json` overrides the user's: pass the user's first.
    ///
    /// An entry naming a language we have no grammar for is dropped with a
    /// log — the alternative is a rule that silently matches and then cannot
    /// highlight — and so is one whose globs do not compile.
    pub fn new<'a>(
        layers: impl IntoIterator<Item = &'a BTreeMap<String, Vec<String>>>,
    ) -> FileTypes {
        let mut rules: Vec<(&'static str, PathMatcher)> = Vec::new();
        for layer in layers {
            for (language, globs) in layer {
                if globs.is_empty() {
                    continue;
                }
                let Some(grammar) = grammar_for_name(language) else {
                    log::warn!("file_types names {language:?}, which has no grammar here");
                    continue;
                };
                match PathMatcher::new(globs, PathStyle::Unix) {
                    // A later layer replaces an earlier one for the same
                    // language rather than stacking on it, so a project can
                    // narrow a rule the user set widely.
                    Ok(matcher) => match rules.iter_mut().find(|(name, _)| *name == grammar) {
                        Some(existing) => existing.1 = matcher,
                        None => rules.push((grammar, matcher)),
                    },
                    Err(err) => {
                        log::warn!("file_types globs for {language:?} do not compile: {err}")
                    }
                }
            }
        }
        FileTypes { rules }
    }

    pub fn is_empty(&self) -> bool {
        self.rules.is_empty()
    }

    /// The grammar for `path`: a `file_types` rule if one claims it, the
    /// built-in suffix table otherwise.
    ///
    /// The path is matched whole *and* by its last component, because both
    /// shapes are written in practice — `"**/config/*.json"` wants the whole
    /// path, `".babelrc"` wants the file name — and `PathMatcher` anchors a
    /// bare pattern at the start of what it is given.
    pub fn language_for_path(&self, path: &str) -> Option<&'static str> {
        for (grammar, matcher) in &self.rules {
            if matches(matcher, path) {
                return Some(grammar);
            }
        }
        crate::highlight::language_for_path(path)
    }
}

fn matches(matcher: &PathMatcher, path: &str) -> bool {
    // Absolute paths are not relative paths; `RelPath` refuses them, and the
    // leading separator carries no information for a glob anyway.
    let relative = path.trim_start_matches('/');
    if matches_relative(matcher, relative) {
        return true;
    }
    let name = relative.rsplit('/').next().unwrap_or(relative);
    name != relative && matches_relative(matcher, name)
}

fn matches_relative(matcher: &PathMatcher, path: &str) -> bool {
    RelPath::new(std::path::Path::new(path), PathStyle::Unix)
        .is_ok_and(|path| matcher.is_match(path.as_ref()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn table(entries: &[(&str, &[&str])]) -> FileTypes {
        let map: BTreeMap<String, Vec<String>> = entries
            .iter()
            .map(|(language, globs)| {
                (
                    (*language).to_owned(),
                    globs.iter().map(|glob| (*glob).to_owned()).collect(),
                )
            })
            .collect();
        FileTypes::new([&map])
    }

    #[test]
    fn an_empty_table_answers_exactly_as_the_built_in_one_does() {
        let types = FileTypes::default();
        assert!(types.is_empty());
        assert_eq!(types.language_for_path("src/main.rs"), Some("rust"));
        assert_eq!(types.language_for_path("notes.txt"), None);
    }

    #[test]
    fn a_rule_names_a_language_the_suffix_table_has_never_heard_of() {
        let types = table(&[("JSON", &["*.jsonc", ".babelrc"])]);
        assert_eq!(types.language_for_path("app/.babelrc"), Some("json"));
        assert_eq!(types.language_for_path("tsconfig.jsonc"), Some("json"));
    }

    /// The precedence rule: `file_types` is asked first, so it can take a
    /// suffix *away* from the built-in table.
    #[test]
    fn a_rule_beats_the_built_in_suffix_table() {
        assert_eq!(
            crate::highlight::language_for_path("data.json"),
            Some("json")
        );
        let types = table(&[("JSONC", &["*.json"])]);
        assert_eq!(types.language_for_path("data.json"), Some("jsonc"));
    }

    #[test]
    fn a_glob_may_match_the_whole_path_or_only_the_file_name() {
        let types = table(&[("TOML", &["**/deps/*.lock"])]);
        assert_eq!(types.language_for_path("a/deps/b.lock"), Some("toml"));
        assert_eq!(types.language_for_path("/root/a/deps/b.lock"), Some("toml"));
        assert_eq!(types.language_for_path("a/other/b.lock"), None);
    }

    /// A project's table overrides the user's for the languages it names and
    /// leaves the rest alone.
    #[test]
    fn a_later_layer_replaces_an_earlier_one_per_language() {
        let user: BTreeMap<String, Vec<String>> = [
            ("JSON".to_owned(), vec!["*.cfg".to_owned()]),
            ("TOML".to_owned(), vec!["*.conf".to_owned()]),
        ]
        .into_iter()
        .collect();
        let project: BTreeMap<String, Vec<String>> =
            [("JSON".to_owned(), vec!["*.jsonc".to_owned()])]
                .into_iter()
                .collect();
        let types = FileTypes::new([&user, &project]);
        // The project narrowed JSON; the user's `*.cfg` rule is gone.
        assert_eq!(types.language_for_path("a.jsonc"), Some("json"));
        assert_eq!(types.language_for_path("a.cfg"), None);
        // TOML, which the project said nothing about, still holds.
        assert_eq!(types.language_for_path("a.conf"), Some("toml"));
    }

    #[test]
    fn a_rule_for_a_language_we_do_not_carry_is_dropped_not_obeyed() {
        let types = table(&[("Brainfuck", &["*.bf"])]);
        assert!(types.is_empty());
        assert_eq!(types.language_for_path("a.bf"), None);
    }
}
