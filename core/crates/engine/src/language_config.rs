//! The editing rules of each language, read from the grammars we already ship.
//!
//! Every vendored grammar under `grammars/src/<language>/` carries Zed's own
//! `config.toml` — comment tokens, bracket pairs with their `close`,
//! `surround`, `newline` and `not_in` flags, `autoclose_before`, `hard_tabs`,
//! the indent patterns — and an `overrides.scm` naming the tree-sitter nodes
//! that count as a string or a comment. Both are compiled into the binary by
//! `rust_embed`; nothing here reads a file at runtime.
//!
//! Two things leave this module. [`config_json`] hands the UI a whole
//! language's rules in one call, which it caches for the life of the process:
//! that is data, and the UI applies it without asking again. [`scope_at`]
//! answers the one question the data cannot — *where in the syntax tree is
//! this caret* — which is what `not_in = ["string", "comment"]` turns on, and
//! which needs a parse tree the UI does not have.

use std::collections::HashMap;
use std::ops::Range;
use std::sync::OnceLock;

use rope::Rope;
use serde::Serialize;
use streaming_iterator::StreamingIterator as _;
use tree_sitter::{Query, Tree};

use crate::highlight::{RopeTextProvider, ts_language};

/// The most bracket pairs [`enabled_brackets`] can speak for. Python has the
/// most of ours at nineteen; a language that ever exceeded this would silently
/// lose its later pairs, so the registry refuses to build the mask instead.
pub const MAX_BRACKET_PAIRS: usize = 64;

/// One `overrides.scm` capture: the scope it names, with Zed's `.inclusive`
/// suffix stripped and remembered.
struct OverrideCapture {
    name: String,
    /// Whether the capture's own end points count as inside it. Zed writes
    /// this as `@comment.inclusive`, and it is what makes a quote typed at the
    /// very end of a `// comment` still count as being in the comment.
    range_is_inclusive: bool,
}

struct OverrideQuery {
    query: Query,
    /// Indexed by capture index; `None` for captures we ignore (`_`-prefixed).
    captures: Vec<Option<OverrideCapture>>,
}

struct Entry {
    name: &'static str,
    /// The rules as the UI receives them, serialized once at first ask.
    json: String,
    /// Per bracket pair, the scopes it is disabled in — the `not_in` list.
    /// Empty for a pair that is always live, which is most of them.
    disabled_in: Vec<Vec<String>>,
    /// Set bits for the pairs with an empty `not_in`, so a language whose
    /// pairs are all unconditional never asks about a scope at all.
    unconditional: u64,
    /// Compiled on the first scope question about this language, not when the
    /// registry is built: opening a file must not pay to compile the override
    /// queries of nineteen languages it is not written in.
    overrides: OnceLock<Option<OverrideQuery>>,
}

impl Entry {
    fn overrides(&self) -> Option<&OverrideQuery> {
        self.overrides
            .get_or_init(|| {
                // A language that disables nothing has nothing to compile.
                if self.disabled_in.iter().all(|scopes| scopes.is_empty()) {
                    return None;
                }
                let source = grammars::load_queries(self.name).overrides?;
                build_override_query(self.name, source.as_ref())
            })
            .as_ref()
    }
}

fn registry() -> &'static HashMap<&'static str, Entry> {
    static REGISTRY: OnceLock<HashMap<&'static str, Entry>> = OnceLock::new();
    REGISTRY.get_or_init(|| {
        let mut map = HashMap::new();
        for (name, _) in grammars::native_grammars() {
            if grammars::get_file(&format!("{name}/config.toml")).is_none() {
                continue;
            }
            map.insert(name, build(name));
        }
        map
    })
}

fn build(name: &'static str) -> Entry {
    let config = grammars::load_config(name);
    let brackets = &config.brackets;
    let disabled_in = brackets.disabled_scopes_by_bracket_ix.clone();
    if brackets.pairs.len() > MAX_BRACKET_PAIRS {
        log::warn!(
            "{name} has {} bracket pairs, more than the {MAX_BRACKET_PAIRS} the scope mask holds",
            brackets.pairs.len()
        );
    }
    let mut unconditional = 0u64;
    for (index, _) in brackets.pairs.iter().enumerate().take(MAX_BRACKET_PAIRS) {
        if disabled_in
            .get(index)
            .is_none_or(|scopes| scopes.is_empty())
        {
            unconditional |= 1 << index;
        }
    }

    let json = serde_json::to_string(&ConfigJson {
        name: config.name.0.as_ref(),
        line_comments: config
            .line_comments
            .iter()
            .map(|comment| comment.as_ref())
            .collect(),
        block_comment: config
            .block_comment
            .as_ref()
            .map(|comment| BlockCommentJson {
                start: comment.start.as_ref(),
                end: comment.end.as_ref(),
                prefix: comment.prefix.as_ref(),
                tab_size: comment.tab_size,
            }),
        autoclose_before: &config.autoclose_before,
        hard_tabs: config.hard_tabs.unwrap_or(false),
        tab_size: config.tab_size.map(|size| size.get()),
        increase_indent_pattern: config
            .increase_indent_pattern
            .as_ref()
            .map(|pattern| pattern.as_str()),
        brackets: brackets
            .pairs
            .iter()
            .enumerate()
            .map(|(index, pair)| BracketJson {
                start: &pair.start,
                end: &pair.end,
                close: pair.close,
                surround: pair.surround,
                newline: pair.newline,
                not_in: disabled_in.get(index).map(Vec::as_slice).unwrap_or(&[]),
            })
            .collect(),
    })
    .expect("a language config always serializes");

    Entry {
        name,
        json,
        disabled_in,
        unconditional,
        overrides: OnceLock::new(),
    }
}

fn build_override_query(name: &str, source: &str) -> Option<OverrideQuery> {
    let language = ts_language(name)?;
    let query = match Query::new(language, source) {
        Ok(query) => query,
        Err(err) => {
            log::warn!("failed to compile overrides query for {name}: {err}");
            return None;
        }
    };
    let captures = query
        .capture_names()
        .iter()
        .map(|capture| {
            if capture.starts_with('_') {
                return None;
            }
            let (name, range_is_inclusive) = match capture.strip_suffix(".inclusive") {
                Some(prefix) => (prefix, true),
                None => (*capture, false),
            };
            Some(OverrideCapture {
                name: name.to_owned(),
                range_is_inclusive,
            })
        })
        .collect();
    Some(OverrideQuery { query, captures })
}

/// A language's editing rules as JSON, or `None` for a grammar we do not
/// carry. Serialized once and handed out by reference: the UI asks for a
/// language the first time it opens a file in it and never again.
pub fn config_json(language: &str) -> Option<&'static str> {
    registry().get(language).map(|entry| entry.json.as_str())
}

/// The scope `offset` sits in, as `overrides.scm` names it — "string",
/// "comment", "element" — or `None` for ordinary code.
///
/// This is Zed's `SyntaxLayer::override_id`, without the layers: the smallest
/// capture containing the offset wins, and a capture marked `.inclusive` in
/// the query counts its own end points as inside.
fn scope_at<'a>(
    overrides: &'a OverrideQuery,
    tree: &Tree,
    text: &Rope,
    offset: usize,
) -> Option<&'a str> {
    let mut smallest: Option<(&str, Range<usize>)> = None;
    crate::highlight::with_query_cursor(|cursor| {
        cursor.set_byte_range(offset.saturating_sub(1)..offset.saturating_add(1));
        let mut matches =
            cursor.matches(&overrides.query, tree.root_node(), RopeTextProvider(text));
        while let Some(match_) = matches.next() {
            for capture in match_.captures {
                let Some(entry) = overrides
                    .captures
                    .get(capture.index as usize)
                    .and_then(Option::as_ref)
                else {
                    continue;
                };
                let range = capture.node.byte_range();
                if entry.range_is_inclusive {
                    if offset < range.start || offset > range.end {
                        continue;
                    }
                } else if offset <= range.start || offset >= range.end {
                    continue;
                }
                if smallest
                    .as_ref()
                    .is_none_or(|(_, smallest)| range.len() < smallest.len())
                {
                    smallest = Some((entry.name.as_str(), range));
                }
            }
        }
    });
    smallest.map(|(name, _)| name)
}

/// Bit *i* is set when bracket pair *i* of `language` — the same order
/// `config_json` reports them in — is live at `offset`.
///
/// `scope_language` and `tree` are the *innermost syntax layer* at that
/// offset, which is usually the buffer's own language and its own tree but is
/// the injected grammar inside a Markdown fence or an HTML `<script>` — Zed
/// reads `override_id` off the innermost layer too. The pair list stays the
/// buffer language's, because that is the list the UI holds and indexes this
/// mask against; only the *scope name* comes from the layer, and "string" and
/// "comment" mean the same thing in every `overrides.scm`.
///
/// Everything is live in a language we don't know, or one whose pairs carry no
/// `not_in` at all, which is the answer for every plain bracket. The mask is
/// what a typed `"` inside a comment runs into: its bit is clear, so no pair
/// is inserted.
pub fn enabled_brackets(
    language: &str,
    tree: &Tree,
    layer: Option<(&str, &Tree)>,
    text: &Rope,
    offset: usize,
) -> u64 {
    let Some(entry) = registry().get(language) else {
        return u64::MAX;
    };
    // The injected layer answers when it can. A layer whose language has no
    // `overrides.scm` at all — markdown-inline, say — cannot, and then the
    // buffer's own tree is still a better answer than "everything is live".
    let scope = layer
        .filter(|(scope_language, _)| *scope_language != language)
        .and_then(|(scope_language, layer_tree)| {
            let overrides = registry().get(scope_language)?.overrides()?;
            Some(scope_at(overrides, layer_tree, text, offset))
        })
        .or_else(|| entry.overrides().map(|o| scope_at(o, tree, text, offset)));
    let Some(Some(scope)) = scope else {
        return u64::MAX;
    };
    let mut mask = entry.unconditional;
    for (index, disabled) in entry.disabled_in.iter().enumerate().take(MAX_BRACKET_PAIRS) {
        if !disabled.iter().any(|name| name == scope) {
            mask |= 1 << index;
        }
    }
    mask
}

#[derive(Serialize)]
struct ConfigJson<'a> {
    name: &'a str,
    line_comments: Vec<&'a str>,
    block_comment: Option<BlockCommentJson<'a>>,
    autoclose_before: &'a str,
    hard_tabs: bool,
    tab_size: Option<u32>,
    increase_indent_pattern: Option<&'a str>,
    brackets: Vec<BracketJson<'a>>,
}

#[derive(Serialize)]
struct BlockCommentJson<'a> {
    start: &'a str,
    end: &'a str,
    prefix: &'a str,
    tab_size: u32,
}

#[derive(Serialize)]
struct BracketJson<'a> {
    start: &'a str,
    end: &'a str,
    close: bool,
    surround: bool,
    newline: bool,
    not_in: &'a [String],
}

#[cfg(test)]
mod tests {
    use super::*;

    fn config(language: &str) -> serde_json::Value {
        serde_json::from_str(config_json(language).expect("a config")).unwrap()
    }

    /// The values the Kotlin table used to claim. Each of these differed from
    /// what it hardcoded, which is why the table is gone.
    #[test]
    fn autoclose_before_differs_per_language() {
        assert_eq!(config("rust")["autoclose_before"], ";:.,=}])>");
        assert_eq!(config("bash")["autoclose_before"], "}])");
        assert_eq!(config("json")["autoclose_before"], ",]}");
        assert_eq!(config("yaml")["autoclose_before"], ",]}");
        assert_eq!(config("gomod")["autoclose_before"], ")");
        assert_eq!(config("jsdoc")["autoclose_before"], "]}");
    }

    #[test]
    fn json_has_no_quote_pair_but_double() {
        let brackets = config("json");
        let brackets = brackets["brackets"].as_array().unwrap();
        let starts: Vec<&str> = brackets
            .iter()
            .map(|pair| pair["start"].as_str().unwrap())
            .collect();
        assert_eq!(starts, ["{", "[", "(", "\""]);
        // No `'`, no backtick: `''` in a .json file is not JSON.
        assert!(!starts.contains(&"'"));
        assert!(!starts.contains(&"`"));
    }

    /// Rust has no `'` pair at all — a lifetime is not a character literal —
    /// and its `<` is `close = false`, which is why it may surround a
    /// selection but never auto-closes on its own.
    #[test]
    fn rust_angle_bracket_never_closes_itself() {
        let config = config("rust");
        let brackets = config["brackets"].as_array().unwrap();
        let angle = brackets
            .iter()
            .find(|pair| pair["start"] == "<")
            .expect("the angle pair");
        assert_eq!(angle["close"], false);
        assert_eq!(angle["newline"], true);
        assert!(brackets.iter().all(|pair| pair["start"] != "'"));
    }

    /// The half the table dropped entirely.
    #[test]
    fn quote_pairs_carry_their_not_in_scopes() {
        let config = config("rust");
        let brackets = config["brackets"].as_array().unwrap();
        let quote = brackets
            .iter()
            .find(|pair| pair["start"] == "\"")
            .expect("the quote pair");
        assert_eq!(quote["not_in"].as_array().unwrap(), &["string"]);
        let block = brackets
            .iter()
            .find(|pair| pair["start"] == "/*")
            .expect("the block-comment pair");
        assert_eq!(block["not_in"].as_array().unwrap(), &["string", "comment"]);
        assert_eq!(block["end"], " */");
    }

    /// Comment tokens, including the two shapes that are not `//`.
    #[test]
    fn comment_tokens_come_from_the_configs() {
        assert_eq!(
            config("rust")["line_comments"].as_array().unwrap(),
            &["// ", "/// ", "//! "]
        );
        assert_eq!(
            config("python")["line_comments"].as_array().unwrap(),
            &["# "]
        );
        // Markdown and CSS have a block comment and no line comment at all.
        assert!(
            config("markdown")["line_comments"]
                .as_array()
                .unwrap()
                .is_empty()
        );
        assert_eq!(config("markdown")["block_comment"]["start"], "<!--");
        assert_eq!(config("markdown")["block_comment"]["end"], "-->");
        assert!(
            config("css")["line_comments"]
                .as_array()
                .unwrap()
                .is_empty()
        );
        assert_eq!(config("css")["block_comment"]["start"], "/*");
        // Diff has neither, and toggling a comment in one must do nothing.
        assert!(
            config("diff")["line_comments"]
                .as_array()
                .unwrap()
                .is_empty()
        );
        assert!(config("diff")["block_comment"].is_null());
    }

    #[test]
    fn go_is_the_one_language_that_indents_with_tabs() {
        assert_eq!(config("go")["hard_tabs"], true);
        assert_eq!(config("rust")["hard_tabs"], false);
        assert_eq!(config("python")["hard_tabs"], false);
    }

    /// The pattern that replaces the hardcoded "Python's colon" rule, and the
    /// two languages that had no rule at all.
    #[test]
    fn indent_patterns_come_from_the_configs() {
        assert_eq!(
            config("python")["increase_indent_pattern"],
            "^[^#].*:\\s*(#.*)?$"
        );
        assert!(config("bash")["increase_indent_pattern"].is_string());
        assert!(config("yaml")["increase_indent_pattern"].is_string());
        assert!(config("rust")["increase_indent_pattern"].is_null());
    }

    #[test]
    fn unknown_languages_have_no_config() {
        assert!(config_json("brainfuck").is_none());
    }

    /// Every language we carry must serialize, and every `not_in` scope it
    /// names must exist in its own `overrides.scm` — Zed checks the same
    /// thing when it builds a grammar, and a typo there would silently make a
    /// pair impossible to disable.
    #[test]
    fn every_language_is_consistent() {
        for (name, entry) in registry() {
            assert!(!entry.json.is_empty(), "{name} serialized to nothing");
            let named: Vec<&String> = entry.disabled_in.iter().flatten().collect();
            if named.is_empty() {
                continue;
            }
            let overrides = entry
                .overrides()
                .unwrap_or_else(|| panic!("{name} names not_in scopes but has no overrides query"));
            for scope in named {
                assert!(
                    overrides
                        .captures
                        .iter()
                        .flatten()
                        .any(|capture| capture.name == *scope),
                    "{name} disables a pair in {scope:?}, which its overrides query never captures"
                );
            }
        }
    }
}
