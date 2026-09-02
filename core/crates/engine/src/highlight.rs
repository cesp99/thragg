//! Interim tree-sitter syntax highlighting (roadmap phase 2).
//!
//! Zed's real syntax machinery (`language::SyntaxMap`) is coupled to the
//! gpui runtime; until the headless-GPUI decision gate (phase 3), the
//! engine runs one incremental `tree_sitter::Parser` per buffer using the
//! vendored `grammars` crate (compiled-in grammars + Zed's `highlights.scm`
//! queries) and evaluates captures over the visible line window on demand.
//!
//! Style identity is an index into [`STYLE_NAMES`], which follows Zed's
//! syntax-theme key set. The Kotlin side maps the same indices to theme
//! colors — keep the two lists in sync (`ui/editor/SyntaxPalette.kt`).

use std::collections::HashMap;
use std::ops::{Deref, Range};
use std::sync::{Mutex, OnceLock};

use rope::{Point, Rope};
use streaming_iterator::StreamingIterator as _;
use tree_sitter::{InputEdit, Parser, Query, QueryCursor, Tree};

/// Pooled query cursors, the way the vendored `language` crate pools its own
/// (`language.rs` `QUERY_CURSORS`): a `QueryCursor` carries real allocations,
/// and [`HighlightState::highlights`] runs for every window the UI draws.
static QUERY_CURSORS: Mutex<Vec<QueryCursor>> = Mutex::new(Vec::new());

/// Run `f` with a cursor from the pool. The byte range is reset on the way
/// back in — a cursor remembers `set_byte_range` across uses, and the next
/// borrower may not set its own.
pub(crate) fn with_query_cursor<T>(f: impl FnOnce(&mut QueryCursor) -> T) -> T {
    let mut cursor = QUERY_CURSORS
        .lock()
        .unwrap()
        .pop()
        .unwrap_or_default();
    let result = f(&mut cursor);
    cursor.set_byte_range(0..usize::MAX);
    QUERY_CURSORS.lock().unwrap().push(cursor);
    result
}

/// Zed's syntax style keys (subset ordering is ours; indices are the
/// engine<->UI contract). Longest-dotted-prefix matching maps capture
/// names ("keyword.operator") onto these.
pub const STYLE_NAMES: &[&str] = &[
    "attribute",
    "boolean",
    "comment",
    "comment.doc",
    "constant",
    "constructor",
    "embedded",
    "emphasis",
    "emphasis.strong",
    "enum",
    "function",
    "keyword",
    "label",
    "link_text",
    "link_uri",
    "number",
    "operator",
    "preproc",
    "property",
    "punctuation",
    "punctuation.bracket",
    "punctuation.delimiter",
    "punctuation.list_marker",
    "punctuation.special",
    "string",
    "string.escape",
    "string.regex",
    "string.special",
    "string.special.symbol",
    "tag",
    "text.literal",
    "title",
    "type",
    "variable",
    "variable.special",
];

/// One row of the buffer's outline: an item's label ("fn bar"), how deep it
/// nests, and where the *item* starts — the caret target when the picker
/// confirms it, which is Zed's behaviour (outline.rs:417-425 selects the
/// item range's start, e.g. the `pub` or `impl`, not the name). `end_row`
/// closes the item's extent so the picker can find the symbol containing
/// the caret without a second engine call.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct OutlineItem {
    pub label: String,
    pub depth: u32,
    pub row: u32,
    pub col_utf16: u32,
    pub end_row: u32,
}

/// One highlighted range on one row. Columns are UTF-16 offsets within the
/// row's line, ready for Compose's AnnotatedString ranges.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HighlightSpan {
    pub row: u32,
    pub start_col_utf16: u32,
    pub end_col_utf16: u32,
    pub style: u16,
}

/// One vendored grammar: its tree-sitter language, and its queries compiled
/// **on first use** rather than when the registry is built.
///
/// The registry used to compile every query of every grammar — thirty
/// grammars, up to six `.scm` files each — inside the first `open_file` of
/// the process, and on the phone that first open paid ~1.5 s for a
/// twenty-line TOML file it would then parse in under a millisecond. A
/// buffer only ever needs its own grammar's queries (plus its injections',
/// which reach this same path), so each grammar compiles its own the first
/// time something asks, and [`warm_languages`] asks for all of them from a
/// background thread at boot so that in practice nothing on the open path
/// ever compiles at all.
///
/// The queries are reached through [`Deref`], so `entry.outline` and the
/// rest read exactly as they did when they were fields.
struct LanguageEntry {
    name: &'static str,
    language: tree_sitter::Language,
    queries: OnceLock<LanguageQueries>,
}

impl LanguageEntry {
    fn queries(&self) -> &LanguageQueries {
        self.queries
            .get_or_init(|| compile_queries(self.name, &self.language))
    }
}

impl Deref for LanguageEntry {
    type Target = LanguageQueries;

    fn deref(&self) -> &LanguageQueries {
        self.queries()
    }
}

/// A grammar's compiled queries — see [`LanguageEntry`].
struct LanguageQueries {
    /// Compiled highlights query and per-capture-index style (None for
    /// captures we don't map, e.g. locals or `_`-prefixed).
    highlights: Option<(Query, Vec<Option<u16>>)>,
    /// Compiled `outline.scm`, for the symbol path under the caret.
    outline: Option<OutlineQuery>,
    /// Compiled `runnables.scm`, for the gutter's run buttons.
    runnables: Option<RunnableQuery>,
    /// Compiled `indents.scm`, for the fold ranges — see [`IndentQuery`].
    indents: Option<IndentQuery>,
    /// Compiled `injections.scm`, for the nested grammars — see
    /// [`InjectionQuery`].
    injections: Option<InjectionQuery>,
    /// Compiled `brackets.scm`, for the matching pair around the caret.
    brackets: Option<BracketQuery>,
}

/// Zed's injection capture scheme (`language/src/syntax_map.rs`,
/// `InjectionConfig`): `@injection.content` is the text another grammar
/// parses, and the grammar is named either by an `@injection.language`
/// capture whose *text* is the name — a Markdown fence's info string — or by
/// the pattern's own `(#set! injection.language "sql")` property.
struct InjectionQuery {
    query: Query,
    content: u32,
    language: Option<u32>,
    /// Per pattern index, the `(#set! injection.language …)` value, resolved
    /// to a grammar we carry. Computed once so a parse never re-reads the
    /// query's properties.
    language_by_pattern: Vec<Option<&'static str>>,
}

/// How deep injections nest before the engine stops following them. Three is
/// enough for the chains that exist — Markdown → HTML → CSS, Svelte → HTML →
/// JavaScript — and stops a self-injecting grammar (Rust's `macro_invocation`
/// injects Rust) from recursing without bound.
const MAX_INJECTION_DEPTH: u32 = 3;

/// Most injected layers one buffer keeps. A Markdown file is one layer per
/// fence and a Rust file one per macro invocation, so this is a real ceiling
/// rather than a theoretical one; past it the buffer keeps its outermost
/// layers and the rest highlight with the parent grammar, which is what they
/// did before injections existed.
const MAX_INJECTION_LAYERS: usize = 128;

/// One injected grammar over one set of ranges: its own parse tree, and how
/// deep it sits. Zed calls these syntax layers (`SyntaxMap::layers_for_range`)
/// and paints them outermost-first, which is what makes the injected
/// language's colours win inside a Markdown fence.
pub struct SyntaxLayer {
    name: &'static str,
    entry: &'static LanguageEntry,
    tree: Tree,
    depth: u32,
}


/// Zed's runnable capture scheme (language_core/src/grammar.rs:394-415):
/// `@run` is the node the play button sits on, and every other capture is
/// a *named* extra — `@_test_name`, `@_pytest_class_name` — whose text the
/// language's task templates read back as `$ZED_CUSTOM_<name>`. The tag a
/// pattern carries is its `(#set! tag …)` property.
struct RunnableQuery {
    query: Query,
    run: u32,
    /// Capture index → capture name, for the named extras.
    names: Vec<String>,
}

/// One runnable the buffer's grammar found: the row its `@run` node starts
/// on, the tags of the pattern that matched, the named extra captures and
/// the run node's own text — Zed's `RunnableRange` flattened to what the
/// UI and the task store need (crates/language/src/runnable.rs:300-356).
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct RunnableRow {
    pub row: u32,
    pub col_utf16: u32,
    pub tags: Vec<String>,
    pub captures: HashMap<String, String>,
    pub run_text: String,
    /// The row the whole match ends on — Zed's `full_range`, which is what
    /// "the nearest runnable" is measured against.
    pub end_row: u32,
}

/// Zed's indent capture scheme, read for a second purpose.
///
/// `indents.scm` marks the nodes that indent their children (`@indent`, with
/// the closing delimiter as `@end`) and, for languages that indent by
/// keyword rather than bracket, the statements that open a block
/// (`@start`, `@start.def`, `@start.if`…). Zed reads it to auto-indent
/// (`language.rs`, `IndentConfig`); this module reads the same captures to
/// answer which rows fold, because a node that indents its children is
/// exactly a block a chevron should collapse — and, unlike a walk over
/// indentation, it cannot be fooled by an unindented line inside a string or
/// a comment (the two cases Zed's own indent walk special-cases with the
/// syntax tree, display_map.rs:2380-2393).
struct IndentQuery {
    query: Query,
    indent: Option<u32>,
    end: Option<u32>,
    /// Every `@start` and `@start.*` capture index.
    starts: Vec<u32>,
}

/// A span of the buffer in the editor's own coordinates: rows, and UTF-16
/// columns within them. What the syntax-selection and bracket-matching calls
/// answer with, because the pane thinks in rows and columns and never in
/// byte offsets.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
pub struct TextRange {
    pub start_row: u32,
    pub start_col_utf16: u32,
    pub end_row: u32,
    pub end_col_utf16: u32,
}

/// A foldable block: the chip sits on `start_row`, rows `start_row + 1`
/// through `end_row` hide. The same shape the UI's indent-based folds have,
/// so a syntax fold and an indent fold are interchangeable there.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
pub struct FoldRange {
    pub start_row: u32,
    pub end_row: u32,
}

/// Zed's outline capture scheme: `@item` is the whole declaration, `@name`
/// its identifier, `@context` the keywords worth echoing ("fn", "struct") —
/// see any vendored `outline.scm` and Zed's `Grammar::outline_config`
/// (crates/language/src/language.rs, `OutlineConfig`).
struct OutlineQuery {
    query: Query,
    item: u32,
    name: u32,
    context: Option<u32>,
}

/// Zed's bracket capture scheme (`brackets.scm`): every pattern captures one
/// `@open` delimiter and one `@close` delimiter, and the pair between them is
/// what `editor::MoveToEnclosingBracket` jumps across and what the pane
/// highlights around the caret. See any vendored `brackets.scm` and Zed's
/// `BracketsConfig` (crates/language/src/language.rs).
struct BracketQuery {
    query: Query,
    open: u32,
    close: u32,
}

fn registry() -> &'static HashMap<&'static str, LanguageEntry> {
    static REGISTRY: OnceLock<HashMap<&'static str, LanguageEntry>> = OnceLock::new();
    REGISTRY.get_or_init(|| {
        grammars::native_grammars()
            .into_iter()
            .map(|(name, language)| {
                (
                    name,
                    LanguageEntry {
                        name,
                        language,
                        queries: OnceLock::new(),
                    },
                )
            })
            .collect()
    })
}

/// Compile one grammar's queries. Milliseconds to tens of milliseconds per
/// grammar on the phone; the reason it is per grammar and lazy is written on
/// [`LanguageEntry`].
fn compile_queries(name: &str, language: &tree_sitter::Language) -> LanguageQueries {
    let queries = grammars::load_queries(name);
    let highlights =
        queries
            .highlights
            .and_then(|source| match Query::new(language, source.as_ref()) {
                Ok(query) => {
                    let styles = query
                        .capture_names()
                        .iter()
                        .map(|capture| style_for_capture(capture))
                        .collect();
                    Some((query, styles))
                }
                Err(err) => {
                    log::warn!("failed to compile highlights query for {name}: {err}");
                    None
                }
            });
    let outline = queries
        .outline
        .and_then(|source| match Query::new(language, source.as_ref()) {
            Ok(query) => {
                let index = |wanted: &str| {
                    query
                        .capture_names()
                        .iter()
                        .position(|name| *name == wanted)
                        .map(|i| i as u32)
                };
                match (index("item"), index("name")) {
                    (Some(item), Some(name)) => Some(OutlineQuery {
                        item,
                        name,
                        context: index("context"),
                        query,
                    }),
                    _ => None,
                }
            }
            Err(err) => {
                log::warn!("failed to compile outline query for {name}: {err}");
                None
            }
        });
    let runnables =
        queries
            .runnables
            .and_then(|source| match Query::new(language, source.as_ref()) {
                Ok(query) => {
                    let names: Vec<String> = query
                        .capture_names()
                        .iter()
                        .map(|n| n.to_string())
                        .collect();
                    names
                        .iter()
                        .position(|n| n == "run")
                        .map(|run| RunnableQuery {
                            run: run as u32,
                            names,
                            query,
                        })
                }
                Err(err) => {
                    log::warn!("failed to compile runnables query for {name}: {err}");
                    None
                }
            });
    let indents = queries
        .indents
        .and_then(|source| match Query::new(language, source.as_ref()) {
            Ok(query) => {
                let names = query.capture_names();
                let index = |wanted: &str| {
                    names
                        .iter()
                        .position(|name| *name == wanted)
                        .map(|i| i as u32)
                };
                let starts = names
                    .iter()
                    .enumerate()
                    .filter(|(_, name)| **name == "start" || name.starts_with("start."))
                    .map(|(i, _)| i as u32)
                    .collect();
                Some(IndentQuery {
                    indent: index("indent"),
                    end: index("end"),
                    starts,
                    query,
                })
            }
            Err(err) => {
                log::warn!("failed to compile indents query for {name}: {err}");
                None
            }
        });
    let injections =
        queries
            .injections
            .and_then(|source| match Query::new(language, source.as_ref()) {
                Ok(query) => {
                    let names = query.capture_names();
                    let index = |wanted: &str| {
                        names
                            .iter()
                            .position(|name| *name == wanted)
                            .map(|i| i as u32)
                    };
                    let content = index("injection.content")?;
                    let language_capture = index("injection.language");
                    // Resolving the `#set!` value here — not per parse
                    // — is what keeps an injection lookup a vector
                    // index rather than a string comparison per match.
                    let language_by_pattern = (0..query.pattern_count())
                        .map(|pattern| {
                            query
                                .property_settings(pattern)
                                .iter()
                                .find(|property| &*property.key == "injection.language")
                                .and_then(|property| property.value.as_deref())
                                .and_then(grammar_for_injection)
                        })
                        .collect();
                    Some(InjectionQuery {
                        content,
                        language: language_capture,
                        language_by_pattern,
                        query,
                    })
                }
                Err(err) => {
                    log::warn!("failed to compile injections query for {name}: {err}");
                    None
                }
            });
    let brackets =
        queries
            .brackets
            .and_then(|source| match Query::new(language, source.as_ref()) {
                Ok(query) => {
                    let names = query.capture_names();
                    let index = |wanted: &str| {
                        names
                            .iter()
                            .position(|name| *name == wanted)
                            .map(|i| i as u32)
                    };
                    match (index("open"), index("close")) {
                        (Some(open), Some(close)) => Some(BracketQuery { query, open, close }),
                        _ => None,
                    }
                }
                Err(err) => {
                    log::warn!("failed to compile brackets query for {name}: {err}");
                    None
                }
            });
    LanguageQueries {
        highlights,
        outline,
        runnables,
        indents,
        injections,
        brackets,
    }
}

/// Compile every grammar's queries, and read every `config.toml`, now.
///
/// Meant for a background thread at boot (jni-bridge's `engine()`): the work
/// is the same ~1.5 s the first `open_file` of the process used to pay on the
/// main path, moved to where nobody is waiting on it. A buffer opened while
/// this is still running waits only for its own grammar — `OnceLock` blocks a
/// second initializer of the *same* entry and nothing else.
pub fn warm_languages() {
    let _ = extension_map();
    crate::language_config::warm();
    for entry in registry().values() {
        entry.queries();
    }
}

/// The tree-sitter language behind a grammar name, for the queries that live
/// outside this module (`language_config`'s `overrides.scm`).
pub(crate) fn ts_language(name: &str) -> Option<&'static tree_sitter::Language> {
    registry().get(name).map(|entry| &entry.language)
}

/// Language configs whose grammar name differs from their directory name in
/// `grammars/src/`, so an extension lookup would otherwise miss them.
/// JavaScript is the only one that matters to us — Zed parses it with the
/// `tsx` grammar.
const EXTRA_CONFIGS: &[&str] = &["javascript"];

/// Injection-language name (lowercased) → grammar name.
///
/// An `injections.scm` names its target the way a human writes it — a
/// Markdown fence says ```` ```js ````, Zed's Rust query says
/// `injection.language "sql"` — so the same lookup Zed's
/// `LanguageRegistry::language_for_name_or_extension` does is needed here:
/// try the grammar's own directory name, its `config.toml` display name, the
/// `code_fence_block_name` Zed added for exactly this, then its file
/// suffixes.
///
/// Deliberately built from [`grammars::native_grammars`] rather than
/// [`registry`]: the injection queries resolve their names *while* the
/// registry is being built, and reaching back into it would deadlock the
/// `OnceLock`.
fn injection_language_map() -> &'static HashMap<String, &'static str> {
    static MAP: OnceLock<HashMap<String, &'static str>> = OnceLock::new();
    MAP.get_or_init(|| {
        let grammars: HashMap<&'static str, &'static str> = grammars::native_grammars()
            .into_iter()
            .map(|(name, _)| (name, name))
            .collect();
        let mut names: Vec<&'static str> = grammars
            .keys()
            .copied()
            .chain(EXTRA_CONFIGS.iter().copied())
            .filter(|name| grammars::get_file(&format!("{name}/config.toml")).is_some())
            .collect();
        // Sorted, so two languages claiming the same alias resolve the same
        // way on every launch — a hash map's iteration order would not.
        names.sort_unstable();

        let configs: Vec<_> = names
            .into_iter()
            .filter_map(|name| {
                let config = grammars::load_config(name);
                let grammar = config
                    .grammar
                    .as_deref()
                    .and_then(|grammar| grammars.get(grammar).copied())
                    .or_else(|| grammars.get(name).copied())?;
                Some((name, config, grammar))
            })
            .collect();

        let mut map: HashMap<String, &'static str> = HashMap::new();
        // Names first, suffixes second: "ts" must not beat "typescript".
        for (name, config, grammar) in &configs {
            map.entry(name.to_lowercase()).or_insert(grammar);
            map.entry(config.name.0.to_lowercase()).or_insert(grammar);
            if let Some(fence) = &config.code_fence_block_name {
                map.entry(fence.to_lowercase()).or_insert(grammar);
            }
        }
        for (_, config, grammar) in &configs {
            for suffix in &config.matcher.path_suffixes {
                map.entry(suffix.to_lowercase()).or_insert(grammar);
            }
        }
        map
    })
}

/// The grammar an `injections.scm` means by `name`, or `None` for one we do
/// not carry — Zed's `comment` and `rstml` injections land here, and a range
/// with no grammar simply keeps its parent's highlighting.
fn grammar_for_injection(name: &str) -> Option<&'static str> {
    grammar_for_name(name)
}

/// The grammar a *name* refers to — a `file_types` key ("JSON"), an
/// `injections.scm` target ("sql"), a Markdown fence's info string ("js").
/// `None` for a language we do not carry.
pub fn grammar_for_name(name: &str) -> Option<&'static str> {
    injection_language_map().get(&name.to_lowercase()).copied()
}

/// Extension (lowercased, no dot) → grammar name, built from the vendored
/// `config.toml` files so the mapping stays Zed's rather than ours. First
/// writer wins, which keeps the order of `native_grammars()` authoritative
/// where two languages claim the same suffix.
fn extension_map() -> &'static HashMap<String, &'static str> {
    static MAP: OnceLock<HashMap<String, &'static str>> = OnceLock::new();
    MAP.get_or_init(|| {
        let mut map = HashMap::new();
        let names = registry()
            .keys()
            .copied()
            .chain(EXTRA_CONFIGS.iter().copied());
        for name in names {
            if grammars::get_file(&format!("{name}/config.toml")).is_none() {
                continue;
            }
            let config = grammars::load_config(name);
            // A config's `grammar` may point at another language's grammar
            // (JavaScript → tsx); only keep it if that grammar is loaded.
            let Some(grammar) = config
                .grammar
                .as_ref()
                .and_then(|grammar| registry().get_key_value(grammar.as_ref()))
                .map(|(name, _)| *name)
            else {
                continue;
            };
            for suffix in &config.matcher.path_suffixes {
                map.entry(suffix.to_lowercase()).or_insert(grammar);
            }
        }
        map
    })
}

/// The language's display name from its `config.toml` ("Rust", "Python") —
/// what Zed puts in `$ZED_LANGUAGE`. The grammar name when there is no
/// config to read.
pub fn language_display_name(grammar: &str) -> String {
    if grammars::get_file(&format!("{grammar}/config.toml")).is_none() {
        return grammar.to_owned();
    }
    grammars::load_config(grammar).name.to_string()
}

/// One bundled language, for the language selector.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct LanguageInfo {
    /// The grammar name `Engine::set_buffer_language` takes ("rust").
    pub grammar: String,
    /// The display name from `config.toml` ("Rust"), which is what Zed's
    /// language selector lists and its status bar prints.
    pub name: String,
}

/// Every grammar compiled into the binary, by display name.
///
/// Zed's `language_selector` lists the languages its registry holds
/// (language_selector/src/language_selector.rs, `LanguageSelectorDelegate`);
/// ours are the vendored grammars, which is the same list for our purposes —
/// a language we cannot parse is not one a buffer can be switched to.
pub fn available_languages() -> Vec<LanguageInfo> {
    let mut languages: Vec<LanguageInfo> = registry()
        .keys()
        .map(|grammar| LanguageInfo {
            grammar: (*grammar).to_owned(),
            name: language_display_name(grammar),
        })
        .collect();
    languages.sort_by(|a, b| {
        a.name
            .to_lowercase()
            .cmp(&b.name.to_lowercase())
            .then_with(|| a.grammar.cmp(&b.grammar))
    });
    languages
}

/// The grammar to highlight `path` with, from its file name. Matches the
/// longest suffix first, so `tsconfig.json` beats `json`.
pub fn language_for_path(path: &str) -> Option<&'static str> {
    let name = path.rsplit(['/', '\\']).next()?.to_lowercase();
    let map = extension_map();
    // Zed's `path_suffixes` hold both plain extensions ("rs") and whole file
    // names ("tsconfig.json"), so try progressively shorter suffixes.
    let mut candidate = name.as_str();
    loop {
        if let Some(language) = map.get(candidate) {
            return Some(language);
        }
        let dot = candidate.find('.')?;
        candidate = &candidate[dot + 1..];
    }
}

/// Longest-dotted-prefix lookup of a capture name in [`STYLE_NAMES`].
fn style_for_capture(capture: &str) -> Option<u16> {
    if capture.starts_with('_') {
        return None;
    }
    let mut candidate = capture;
    loop {
        if let Some(index) = STYLE_NAMES.iter().position(|name| *name == candidate) {
            return Some(index as u16);
        }
        candidate = &candidate[..candidate.rfind('.')?];
    }
}

pub struct HighlightState {
    /// Grammar name, so the UI can say what it is parsing as.
    name: &'static str,
    language: &'static LanguageEntry,
    parser: Parser,
    tree: Option<Tree>,
    /// Edits have been applied to `tree`'s positions but not reparsed, so the
    /// spans it yields are approximate until the worker catches up.
    dirty: bool,
    /// Bumped when a reparse lands, so the UI knows to re-read spans even
    /// though the buffer's content version hasn't moved.
    version: u64,
    /// The next parse must start from scratch. Set by history operations,
    /// where the text changed without a matching `tree.edit()` — handing
    /// tree-sitter that tree as the "old" one would make it reuse subtrees
    /// that no longer correspond to the text.
    needs_full_parse: bool,
    /// The grammars injected into this buffer, outermost first — see
    /// [`SyntaxLayer`]. Rebuilt whenever a parse lands, never incrementally:
    /// an injection's *ranges* move with the text, so a layer tree that was
    /// not reparsed is only ever approximately right, which is the same
    /// contract the root tree already has.
    layers: Vec<SyntaxLayer>,
}

impl HighlightState {
    pub fn name(&self) -> &'static str {
        self.name
    }

    pub fn version(&self) -> u64 {
        self.version
    }

    pub fn is_dirty(&self) -> bool {
        self.dirty
    }

    /// Bring the tree up to date with the text *now*, on the caller's thread.
    ///
    /// Highlighting never needs this — it can afford to trail a parse, which
    /// is the whole reason the worker exists. Asking which syntax scope the
    /// caret is in cannot: the answer turns on the character just typed, and a
    /// tree that predates it would say "code" for a quote that has already
    /// opened a string. Reparsing is incremental and this is one keypress, not
    /// one keystroke — see `Engine::bracket_scopes`.
    /// Whether a full reparse is what [`Self::ensure_parsed`] would do — the
    /// expensive case, measured at 30 ms on a 250 KB buffer.
    pub fn needs_full_reparse(&self) -> bool {
        self.needs_full_parse || self.tree.is_none()
    }

    pub fn ensure_parsed(&mut self, text: &Rope) {
        if !self.dirty && self.tree.is_some() {
            return;
        }
        let old = if self.needs_full_parse {
            None
        } else {
            self.tree.clone()
        };
        if let Some(tree) = Self::parse(&mut self.parser, self.name, text, old.as_ref()) {
            let layers = parse_injections(&mut self.parser, text, self.name, &tree);
            self.install(tree, layers);
        }
    }

    pub fn tree(&self) -> Option<&Tree> {
        self.tree.as_ref()
    }

    /// The inputs a background reparse needs, taken while the buffer lock is
    /// held so the parse itself can run without it.
    pub fn parse_inputs(&self) -> (&'static str, Option<Tree>) {
        if self.needs_full_parse {
            (self.name, None)
        } else {
            (self.name, self.tree.clone())
        }
    }

    /// Adopt a tree parsed off-thread, with the injected layers derived from
    /// it — both computed without the buffer lock, which is why they arrive
    /// together rather than being rebuilt here.
    pub fn install(&mut self, tree: Tree, layers: Vec<SyntaxLayer>) {
        self.tree = Some(tree);
        self.layers = layers;
        self.dirty = false;
        self.needs_full_parse = false;
        self.version += 1;
    }

    /// The grammar and tree that answer "what syntax scope is `offset` in" —
    /// the deepest injected layer covering it, or the buffer's own grammar
    /// where nothing is injected.
    ///
    /// Zed asks the same question of the innermost layer (`syntax_map.rs`,
    /// `SyntaxLayer::override_id`), and it is what makes a `"` typed inside a
    /// Markdown fence obey Rust's rules rather than Markdown's.
    pub fn scope_layer_at(&self, offset: usize) -> Option<(&'static str, &Tree)> {
        let root = self.tree.as_ref()?;
        let innermost = self
            .layers
            .iter()
            .filter(|layer| {
                let range = layer.tree.root_node().byte_range();
                range.start <= offset && offset <= range.end
            })
            .max_by_key(|layer| layer.depth);
        Some(match innermost {
            Some(layer) => (layer.name, &layer.tree),
            None => (self.name, root),
        })
    }

    /// Mark the tree stale without reparsing — for history operations, where
    /// the edit shape isn't readily available. The old tree is kept so the
    /// view keeps its highlighting until the reparse lands, rather than
    /// flashing to unhighlighted text.
    pub fn invalidate(&mut self) {
        self.dirty = true;
        self.needs_full_parse = true;
    }

    /// Returns None for unknown language names.
    pub fn new(language_name: &str, text: &Rope) -> Option<HighlightState> {
        let (name, entry) = registry().get_key_value(language_name)?;
        let mut parser = Parser::new();
        parser.set_language(&entry.language).ok()?;
        let mut state = HighlightState {
            name,
            language: entry,
            parser,
            tree: None,
            dirty: false,
            version: 0,
            needs_full_parse: false,
            layers: Vec::new(),
        };
        state.reparse(text);
        Some(state)
    }

    /// Apply a completed text edit. `start`/`old_end` describe the replaced
    /// range in the pre-edit buffer, `new_end` the replacement's end in the
    /// post-edit buffer; the points are the matching (row, column-byte)
    /// coordinates. The tree is edited and incrementally reparsed.
    #[allow(clippy::too_many_arguments)]
    /// Shift the tree's positions to match a completed edit and mark it
    /// stale. **Does not reparse** — that costs milliseconds on a large file
    /// and must not sit on the keystroke path; the engine's highlight worker
    /// picks it up. Until it does, the shifted tree still yields spans in
    /// very nearly the right places.
    pub fn edited(
        &mut self,
        text: &Rope,
        start: usize,
        old_end: usize,
        new_end: usize,
        start_point: Point,
        old_end_point: Point,
        new_end_point: Point,
    ) {
        let edit = InputEdit {
            start_byte: start,
            old_end_byte: old_end,
            new_end_byte: new_end,
            start_position: ts_point(start_point),
            old_end_position: ts_point(old_end_point),
            new_end_position: ts_point(new_end_point),
        };
        if let Some(tree) = &mut self.tree {
            tree.edit(&edit);
        }
        // The injected trees are shifted too, for the same reason the root is:
        // until the worker's reparse lands they are what the fenced code block
        // or the `<script>` body draws with, and an unshifted layer would
        // colour the wrong columns.
        for layer in &mut self.layers {
            layer.tree.edit(&edit);
        }
        let _ = text;
        self.dirty = true;
    }

    /// Drop incremental state and parse from scratch (used after undo/redo,
    /// where the edit shape isn't readily available).
    /// Parse `text`, reusing `old_tree` when given. Free function so the
    /// highlight worker can call it with its own parser, off the lock.
    pub fn parse(
        parser: &mut Parser,
        language: &str,
        text: &Rope,
        old_tree: Option<&Tree>,
    ) -> Option<Tree> {
        let entry = registry().get(language)?;
        parser.set_language(&entry.language).ok()?;
        // A parser is reused across buffers and across layers; an included
        // range left over from an injection would silently hide most of this
        // buffer from the parse.
        parser.set_included_ranges(&[]).ok()?;
        parse_rope(parser, text, old_tree)
    }

    fn reparse(&mut self, text: &Rope) {
        let tree = Self::parse(&mut self.parser, self.name, text, self.tree.as_ref());
        if let Some(tree) = tree {
            self.layers = parse_injections(&mut self.parser, text, self.name, &tree);
            self.tree = Some(tree);
        }
    }

    /// Highlight spans intersecting the byte range, split per row, with
    /// columns converted to UTF-16 offsets. Spans are emitted in capture
    /// order; the UI applies them in order (later wins on overlap).
    /// The symbol path containing `offset`, outermost first — what Zed's
    /// breadcrumbs show after the file name ("impl Foo" › "fn bar").
    ///
    /// The query is clamped to a two-byte window around the caret, as Zed's
    /// `symbols_containing` clamps it (buffer.rs:4471-4474) — an @item
    /// intersecting the window is still reported whole. Reads whatever tree
    /// the last parse produced — a caret move must never pay for a reparse;
    /// a stale answer lasts one worker round-trip.
    pub fn outline_path(&self, text: &Rope, offset: usize) -> Vec<String> {
        let Some(tree) = &self.tree else {
            return Vec::new();
        };
        let Some(outline) = &self.language.outline else {
            return Vec::new();
        };
        let mut items: Vec<(usize, usize, String)> = Vec::new();
        with_query_cursor(|cursor| {
            cursor.set_byte_range(offset.saturating_sub(1)..offset.saturating_add(1));
            let mut matches =
                cursor.matches(&outline.query, tree.root_node(), RopeTextProvider(text));
            while let Some(match_) = matches.next() {
                let Some(item) = match_
                    .captures
                    .iter()
                    .find(|capture| capture.index == outline.item)
                else {
                    continue;
                };
                let start = item.node.start_byte();
                let end = item.node.end_byte();
                if offset < start || offset > end {
                    continue;
                }
                let Some(label) = self.outline_label(text, match_) else {
                    continue;
                };
                items.push((start, end, label));
            }
        });
        // Outermost first: containing ranges start earlier or end later.
        items.sort_by(|a, b| a.0.cmp(&b.0).then(b.1.cmp(&a.1)));
        // Zed keeps only strictly nesting items (buffer.rs:4475-4482): a
        // caret on the boundary byte between two siblings would otherwise
        // claim to be in both.
        let mut kept: Vec<(usize, usize, String)> = Vec::new();
        for (start, end, label) in items {
            if let Some(last) = kept.last() {
                let (kept_start, kept_end) = (last.0, last.1);
                let inside = start >= kept_start
                    && end <= kept_end
                    && (start > kept_start || end < kept_end);
                if !inside {
                    continue;
                }
            }
            kept.push((start, end, label));
        }
        kept.into_iter().map(|(_, _, label)| label).collect()
    }

    /// The *name* of the innermost symbol containing `offset` — `bar`, not
    /// `fn bar`. Zed's `$ZED_SYMBOL` is the last name range of the deepest
    /// symbol at the location (task_inventory.rs:1029-1037); the breadcrumb
    /// label would put `fn ` in a `cargo test` filter.
    pub fn symbol_name_at(&self, text: &Rope, offset: usize) -> Option<String> {
        let tree = self.tree.as_ref()?;
        let outline = self.language.outline.as_ref()?;
        let mut innermost: Option<(usize, usize, String)> = None;
        with_query_cursor(|cursor| {
            cursor.set_byte_range(offset.saturating_sub(1)..offset.saturating_add(1));
            let mut matches =
                cursor.matches(&outline.query, tree.root_node(), RopeTextProvider(text));
            while let Some(match_) = matches.next() {
                let Some(item) = match_
                    .captures
                    .iter()
                    .find(|capture| capture.index == outline.item)
                else {
                    continue;
                };
                let (start, end) = (item.node.start_byte(), item.node.end_byte());
                if offset < start || offset > end {
                    continue;
                }
                // The last @name capture, as Zed takes the last name range.
                let Some(name) = match_
                    .captures
                    .iter()
                    .filter(|capture| capture.index == outline.name)
                    .last()
                else {
                    continue;
                };
                let name: String = text.chunks_in_range(name.node.byte_range()).collect();
                let tighter = innermost
                    .as_ref()
                    .is_none_or(|(s, e, _)| start >= *s && end <= *e && (start > *s || end < *e));
                if tighter {
                    innermost = Some((start, end, name));
                }
            }
        });
        innermost.map(|(_, _, name)| name)
    }

    /// Every runnable in the buffer, one entry per row in source order.
    ///
    /// Zed keeps one `RunnableRange` per match and lets the gutter fold
    /// them by row (editor/src/runnables.rs:740-770); here the fold happens
    /// once, in the engine, and two patterns landing on the same row — a
    /// pytest method that is also a unittest method — merge their tags and
    /// captures, which is what the button on that row then offers. Same
    /// staleness contract as the outline: the last parsed tree, never a parse.
    pub fn runnables(&self, text: &Rope) -> Vec<RunnableRow> {
        let Some(tree) = &self.tree else {
            return Vec::new();
        };
        let Some(config) = &self.language.runnables else {
            return Vec::new();
        };
        let mut rows: Vec<RunnableRow> = Vec::new();
        let mut cursor = QueryCursor::new();
        let mut matches = cursor.matches(&config.query, tree.root_node(), RopeTextProvider(text));
        while let Some(match_) = matches.next() {
            let Some(run) = match_
                .captures
                .iter()
                .find(|capture| capture.index == config.run)
            else {
                continue;
            };
            // Zed's `full_range`: the union of every capture in the match
            // (runnable.rs:304-316).
            let full_end = match_
                .captures
                .iter()
                .map(|capture| capture.node.end_byte())
                .max()
                .unwrap_or(run.node.end_byte());
            let tags: Vec<String> = config
                .query
                .property_settings(match_.pattern_index)
                .iter()
                .filter(|property| &*property.key == "tag")
                .filter_map(|property| property.value.as_ref().map(|v| v.to_string()))
                .collect();
            if tags.is_empty() {
                continue;
            }
            let captures: HashMap<String, String> = match_
                .captures
                .iter()
                .filter(|capture| capture.index != config.run)
                .filter_map(|capture| {
                    let name = config.names.get(capture.index as usize)?;
                    if name == "run_item" {
                        return None;
                    }
                    let value: String = text.chunks_in_range(capture.node.byte_range()).collect();
                    Some((name.clone(), value))
                })
                .collect();
            let start = text.offset_to_point_utf16(run.node.start_byte());
            let end_row = text.offset_to_point_utf16(full_end).row;
            let run_text: String = text.chunks_in_range(run.node.byte_range()).collect();
            match rows.iter_mut().find(|row| row.row == start.row) {
                Some(existing) => {
                    for tag in tags {
                        if !existing.tags.contains(&tag) {
                            existing.tags.push(tag);
                        }
                    }
                    for (name, value) in captures {
                        existing.captures.entry(name).or_insert(value);
                    }
                    existing.end_row = existing.end_row.max(end_row);
                }
                None => rows.push(RunnableRow {
                    row: start.row,
                    col_utf16: start.column,
                    tags,
                    captures,
                    run_text,
                    end_row,
                }),
            }
        }
        rows.sort_by_key(|row| row.row);
        rows
    }

    /// Every outline item in the buffer, in source order, with its nesting
    /// depth and the row/column of the item's start — Zed's outline picker's
    /// rows (crates/outline/src/outline.rs renders exactly this list).
    pub fn outline_items(&self, text: &Rope) -> Vec<OutlineItem> {
        let Some(tree) = &self.tree else {
            return Vec::new();
        };
        let Some(outline) = &self.language.outline else {
            return Vec::new();
        };
        struct Raw {
            start: usize,
            end: usize,
            label: String,
        }
        let mut raw: Vec<Raw> = Vec::new();
        let mut cursor = QueryCursor::new();
        let mut matches = cursor.matches(&outline.query, tree.root_node(), RopeTextProvider(text));
        while let Some(match_) = matches.next() {
            let Some(item) = match_
                .captures
                .iter()
                .find(|capture| capture.index == outline.item)
            else {
                continue;
            };
            let Some(label) = self.outline_label(text, match_) else {
                continue;
            };
            raw.push(Raw {
                start: item.node.start_byte(),
                end: item.node.end_byte(),
                label,
            });
        }
        raw.sort_by(|a, b| a.start.cmp(&b.start).then(b.end.cmp(&a.end)));
        // Depth is how many earlier items still enclose this one.
        let mut open: Vec<usize> = Vec::new();
        raw.into_iter()
            .map(|item| {
                while matches!(open.last(), Some(&end) if end < item.end) {
                    open.pop();
                }
                let depth = open.len() as u32;
                open.push(item.end);
                let utf16 = text.offset_to_point_utf16(item.start);
                let end_row = text.offset_to_point_utf16(item.end).row;
                OutlineItem {
                    label: item.label,
                    depth,
                    row: utf16.row,
                    col_utf16: utf16.column,
                    end_row,
                }
            })
            .collect()
    }

    /// Every foldable block in the buffer, from the syntax tree — one per
    /// start row, sorted, the widest block winning a row that starts several
    /// (`fn f() {` opens the function item and its body on one row; the
    /// chevron folds the whole function).
    ///
    /// Reads the last parsed tree, never parses — the same contract as
    /// [`Self::outline_items`], and the UI keeps its indent walk for the
    /// moments the tree is a reparse behind. See [`fold_ranges_in`] for the
    /// rules; they are pure so they can be tested against real grammars on
    /// the host.
    pub fn fold_ranges(&self, text: &Rope) -> Vec<FoldRange> {
        let Some(tree) = &self.tree else {
            return Vec::new();
        };
        let Some(indents) = &self.language.indents else {
            return Vec::new();
        };
        fold_ranges_in(indents, tree, text)
    }

    /// The label of one outline match, rendered as Zed renders it
    /// (buffer.rs:4699-4756): the @context and @name captures in source
    /// order, each clipped to the first line it starts on, with a joining
    /// space only where the source itself has a gap — so `fn bar` keeps its
    /// space and `#define FOO(` keeps its bracket tight. None when the match
    /// has no @name or the label comes out empty.
    fn outline_label(
        &self,
        text: &Rope,
        match_: &tree_sitter::QueryMatch<'_, '_>,
    ) -> Option<String> {
        let outline = self.language.outline.as_ref()?;
        match_
            .captures
            .iter()
            .find(|capture| capture.index == outline.name)?;
        let mut ranges: Vec<Range<usize>> = match_
            .captures
            .iter()
            .filter(|capture| {
                capture.index == outline.name || Some(capture.index) == outline.context
            })
            .map(|capture| {
                let range = capture.node.byte_range();
                // Clip a multi-line capture to its first line, as Zed does.
                let start_point = text.offset_to_point(range.start);
                let line_end = text
                    .point_to_offset(Point::new(start_point.row, text.line_len(start_point.row)));
                range.start..range.end.min(line_end).max(range.start)
            })
            .collect();
        ranges.sort_by_key(|range| range.start);
        let mut label = String::new();
        let mut last_end: Option<usize> = None;
        for range in ranges {
            if let Some(prev) = last_end {
                if !label.is_empty() && range.start > prev {
                    label.push(' ');
                }
            }
            for chunk in text.chunks_in_range(range.clone()) {
                label.push_str(chunk);
            }
            last_end = Some(range.end.max(last_end.unwrap_or(0)));
        }
        let label = label.trim().to_string();
        if label.is_empty() { None } else { Some(label) }
    }

    /// The smallest syntax node that strictly contains `range`, as a byte
    /// range — what `editor::SelectLargerSyntaxNode` grows a selection to.
    ///
    /// A port of Zed's `BufferSnapshot::syntax_ancestor`
    /// (crates/language/src/buffer.rs:4355-4410) and the loop that spends it
    /// (crates/editor/src/selection.rs:718-727): climb until the node is
    /// *named*, so the growth steps read as "the argument, the call, the
    /// statement" rather than stopping on a stray `(`. There are no injection
    /// layers here, so the one tree is the only layer to walk.
    ///
    /// None when the range already covers the root, or the tree has not been
    /// parsed yet — the caller then leaves the selection alone.
    pub fn syntax_ancestor(&self, range: Range<usize>) -> Option<Range<usize>> {
        let tree = self.tree.as_ref()?;
        let mut grown = range.clone();
        loop {
            let mut cursor = tree.root_node().walk();
            if !goto_node_enclosing_range(&mut cursor, &grown, true) {
                break;
            }
            let mut node = cursor.node();
            // For an empty range that ends a node, Zed also looks at the node
            // starting there and prefers the named one — a caret just after
            // `foo` should grow to `foo`, not to the whitespace-spanning
            // parent (buffer.rs:4375-4399).
            if node.end_byte() == grown.start && grown.is_empty() {
                let left_named = node.is_named();
                let mut right = None;
                while !cursor.goto_next_sibling() {
                    if !cursor.goto_parent() {
                        break;
                    }
                }
                while cursor.node().start_byte() == grown.start {
                    right = Some(cursor.node());
                    if !cursor.goto_first_child() {
                        break;
                    }
                }
                if let Some(right) = right {
                    if right.is_named() || !left_named {
                        node = right;
                    }
                }
            }
            let next = node.byte_range();
            if next == grown {
                break;
            }
            grown = next;
            if node.is_named() {
                break;
            }
        }
        if grown == range { None } else { Some(grown) }
    }

    /// The innermost bracket pair enclosing `range`, as `(open, close)` byte
    /// ranges — Zed's `innermost_enclosing_bracket_ranges`
    /// (crates/language/src/buffer/bracket_ranges.rs:183-213), narrowed to
    /// one syntax layer and one query pass.
    ///
    /// The grammar's `brackets.scm` is the source of truth; a language with
    /// no such query (or no tree yet) falls back to [`counted_brackets`],
    /// which scans the text. Both answer the pair with the shortest
    /// open-start-to-close-end span, which is what "innermost" means.
    pub fn enclosing_brackets(
        &self,
        text: &Rope,
        range: Range<usize>,
    ) -> Option<(Range<usize>, Range<usize>)> {
        let queried = self.queried_brackets(text, range.clone());
        if queried.is_some() {
            return queried;
        }
        counted_brackets(text, range)
    }

    /// [`Self::enclosing_brackets`]'s tree-sitter half: every `@open`/`@close`
    /// pair the grammar reports around `range`, narrowed to the innermost.
    fn queried_brackets(
        &self,
        text: &Rope,
        range: Range<usize>,
    ) -> Option<(Range<usize>, Range<usize>)> {
        let tree = self.tree.as_ref()?;
        let brackets = self.language.brackets.as_ref()?;
        // The whole file would be a query over every pair in it on a keystroke
        // path; the enclosing node is the region the answer can possibly be
        // in, and the root is the fallback for a range the walk cannot place.
        let mut scope = self.syntax_ancestor(range.clone()).unwrap_or(range.clone());
        let root = tree.root_node().byte_range();
        let mut best: Option<(Range<usize>, Range<usize>)> = None;
        // Climb until a pair is found: `(a, b|)`'s enclosing node is the
        // argument list, but `if (x) { | }`'s is the block, whose own braces
        // are captured by its *parent*.
        loop {
            with_query_cursor(|cursor| {
                // Clipped to the same window the counting fallback uses: the
                // enclosing node of a caret near the top of a long file is
                // often the file, and querying every bracket in it on a caret
                // move is the one thing this must not cost.
                cursor.set_byte_range(
                    scope
                        .start
                        .max(range.start.saturating_sub(COUNTED_SCAN_BYTES))
                        ..scope.end.min(range.end + COUNTED_SCAN_BYTES),
                );
                let mut matches =
                    cursor.matches(&brackets.query, tree.root_node(), RopeTextProvider(text));
                while let Some(match_) = matches.next() {
                    let open = match_
                        .captures
                        .iter()
                        .find(|capture| capture.index == brackets.open)
                        .map(|capture| capture.node.byte_range());
                    let close = match_
                        .captures
                        .iter()
                        .find(|capture| capture.index == brackets.close)
                        .map(|capture| capture.node.byte_range());
                    let (Some(open), Some(close)) = (open, close) else {
                        continue;
                    };
                    if open.start > range.start || close.end < range.end {
                        continue;
                    }
                    let length = close.end - open.start;
                    if let Some((best_open, best_close)) = &best {
                        if length > best_close.end - best_open.start {
                            continue;
                        }
                    }
                    best = Some((open, close));
                }
            });
            if best.is_some() || scope == root {
                break;
            }
            scope = match self.syntax_ancestor(scope.clone()) {
                Some(wider) => wider,
                None => root.clone(),
            };
        }
        best
    }

    /// Highlight spans for the byte range, the root grammar first and each
    /// injected layer after it in depth order.
    ///
    /// Order *is* the merge: the UI paints spans in the order it receives
    /// them and lets a later one win an overlap, so emitting deeper layers
    /// last is what makes the Rust inside a Markdown fence read as Rust and
    /// not as `text.literal`. Zed resolves the same overlap the same way,
    /// walking its layers outermost-first (`syntax_map.rs`,
    /// `SyntaxMapCaptures`).
    pub fn highlights(&self, text: &Rope, range: Range<usize>) -> Vec<HighlightSpan> {
        let Some(tree) = &self.tree else {
            return Vec::new();
        };

        let first_row = text.offset_to_point(range.start).row;
        let last_row = text.offset_to_point(range.end).row;
        let mut columns = ColumnConverter::new(text, first_row, last_row);

        let mut spans = Vec::new();
        let trees = std::iter::once((self.language, tree))
            .chain(self.layers.iter().map(|layer| (layer.entry, &layer.tree)));
        for (entry, tree) in trees {
            let Some((query, styles)) = &entry.highlights else {
                continue;
            };
            with_query_cursor(|cursor| {
                cursor.set_byte_range(range.clone());
                let mut captures = cursor.captures(query, tree.root_node(), RopeTextProvider(text));
                while let Some((match_, capture_index)) = captures.next() {
                    let capture = match_.captures[*capture_index];
                    let Some(style) = styles[capture.index as usize] else {
                        continue;
                    };
                    let node_range = capture.node.range();
                    let start = node_range.start_point;
                    let end = node_range.end_point;
                    let span_first = (start.row as u32).max(first_row);
                    let span_last = (end.row as u32).min(last_row);
                    for row in span_first..=span_last {
                        let start_col = if row == start.row as u32 {
                            start.column
                        } else {
                            0
                        };
                        let end_col = if row == end.row as u32 {
                            end.column
                        } else {
                            text.line_len(row) as usize
                        };
                        if end_col <= start_col {
                            continue;
                        }
                        spans.push(HighlightSpan {
                            row,
                            start_col_utf16: columns.utf16_col(row, start_col),
                            end_col_utf16: columns.utf16_col(row, end_col),
                            style,
                        });
                    }
                }
            });
        }
        spans
    }
}

/// Feed a rope to `parser` without copying it into one string — the callback
/// form tree-sitter takes, walking the rope's chunks.
fn parse_rope(parser: &mut Parser, text: &Rope, old_tree: Option<&Tree>) -> Option<Tree> {
    let mut chunks = text.chunks();
    parser.parse_with_options(
        &mut |offset, _| {
            chunks.seek(offset);
            chunks.next().unwrap_or("").as_bytes()
        },
        old_tree,
        None,
    )
}

/// Every grammar injected into `text`, breadth-first from the root tree.
///
/// This is Zed's `SyntaxMap::reparse` reduced to what highlighting needs
/// (`language/src/syntax_map.rs`): run the layer's `injections.scm`, take
/// each match's `@injection.content` ranges, parse them with the named
/// grammar through tree-sitter's *included ranges* — so the injected parser
/// sees only the fence's body, at the fence's real byte offsets — and then do
/// the same to what that parse produced, so a Markdown fence holding HTML
/// still highlights the `<style>` inside it as CSS.
///
/// Breadth-first rather than depth-first so the caps
/// ([`MAX_INJECTION_LAYERS`], [`MAX_INJECTION_DEPTH`]) cut the *deepest*
/// layers, which matter least, rather than the last fence in the file.
pub fn parse_injections(
    parser: &mut Parser,
    text: &Rope,
    language: &str,
    tree: &Tree,
) -> Vec<SyntaxLayer> {
    let mut layers: Vec<SyntaxLayer> = Vec::new();
    let Some(root) = registry().get(language) else {
        return layers;
    };
    let mut frontier: Vec<(&'static LanguageEntry, Tree, u32)> = vec![(root, tree.clone(), 0)];
    let mut next: Vec<(&'static LanguageEntry, Tree, u32)> = Vec::new();

    while let Some((entry, parent, depth)) = frontier.pop() {
        if depth < MAX_INJECTION_DEPTH && layers.len() < MAX_INJECTION_LAYERS {
            if let Some(injections) = &entry.injections {
                for (grammar, ranges) in injection_ranges(injections, &parent, text) {
                    if layers.len() >= MAX_INJECTION_LAYERS {
                        break;
                    }
                    let Some((name, child)) = registry().get_key_value(grammar) else {
                        continue;
                    };
                    if parser.set_language(&child.language).is_err() {
                        continue;
                    }
                    if parser.set_included_ranges(&ranges).is_err() {
                        continue;
                    }
                    let Some(child_tree) = parse_rope(parser, text, None) else {
                        continue;
                    };
                    next.push((child, child_tree.clone(), depth + 1));
                    layers.push(SyntaxLayer {
                        name,
                        entry: child,
                        tree: child_tree,
                        depth: depth + 1,
                    });
                }
            }
        }
        if frontier.is_empty() {
            frontier.append(&mut next);
        }
    }

    // Leave the parser as the root parse expects to find it.
    let _ = parser.set_included_ranges(&[]);
    layers.sort_by_key(|layer| layer.depth);
    layers
}

/// One entry per injection match: the grammar to parse with, and the byte
/// ranges of that match's `@injection.content` captures.
///
/// A match's ranges travel together — Zed parses them as one layer
/// (`syntax_map.rs`, `InjectionSite`), which is what lets a multi-capture
/// pattern like Svelte's `{#if}` block hand the injected parser one document
/// rather than several.
fn injection_ranges(
    injections: &InjectionQuery,
    tree: &Tree,
    text: &Rope,
) -> Vec<(&'static str, Vec<tree_sitter::Range>)> {
    let mut sites: Vec<(&'static str, Vec<tree_sitter::Range>)> = Vec::new();
    with_query_cursor(|cursor| {
        let mut matches = cursor.matches(&injections.query, tree.root_node(), RopeTextProvider(text));
        while let Some(match_) = matches.next() {
            // The `@injection.language` capture's *text* wins over the
            // pattern's `#set!`, as it does in Zed: a Markdown fence says what
            // it is, the query only guesses.
            let grammar = injections
                .language
                .and_then(|index| {
                    let capture = match_
                        .captures
                        .iter()
                        .find(|capture| capture.index == index)?;
                    let name: String = text.chunks_in_range(capture.node.byte_range()).collect();
                    grammar_for_injection(name.trim())
                })
                .or_else(|| {
                    injections
                        .language_by_pattern
                        .get(match_.pattern_index)
                        .copied()
                        .flatten()
                });
            let Some(grammar) = grammar else { continue };

            let mut ranges: Vec<tree_sitter::Range> = match_
                .captures
                .iter()
                .filter(|capture| capture.index == injections.content)
                .map(|capture| capture.node.range())
                .filter(|range| range.end_byte > range.start_byte)
                .collect();
            if ranges.is_empty() {
                continue;
            }
            ranges.sort_by_key(|range| range.start_byte);
            // `set_included_ranges` rejects overlapping or unordered ranges,
            // and a pattern that captures a node and one of its children does
            // produce them.
            let mut merged: Vec<tree_sitter::Range> = Vec::with_capacity(ranges.len());
            for range in ranges {
                match merged.last() {
                    Some(last) if range.start_byte < last.end_byte => continue,
                    _ => merged.push(range),
                }
            }
            sites.push((grammar, merged));
        }
    });
    sites
}

fn ts_point(point: Point) -> tree_sitter::Point {
    tree_sitter::Point {
        row: point.row as usize,
        column: point.column as usize,
    }
}

pub(crate) struct RopeTextProvider<'a>(pub(crate) &'a Rope);

pub(crate) struct RopeByteChunks<'a>(rope::Chunks<'a>);

impl<'a> tree_sitter::TextProvider<&'a [u8]> for RopeTextProvider<'a> {
    type I = RopeByteChunks<'a>;

    fn text(&mut self, node: tree_sitter::Node) -> Self::I {
        RopeByteChunks(self.0.chunks_in_range(node.byte_range()))
    }
}

impl<'a> Iterator for RopeByteChunks<'a> {
    type Item = &'a [u8];

    fn next(&mut self) -> Option<Self::Item> {
        self.0.next().map(str::as_bytes)
    }
}

/// Whether a row holds nothing but whitespace.
fn row_is_blank(text: &Rope, row: u32) -> bool {
    let start = text.point_to_offset(Point::new(row, 0));
    let end = text.point_to_offset(Point::new(row, text.line_len(row)));
    text.chunks_in_range(start..end)
        .all(|chunk| chunk.trim().is_empty())
}

/// Zed's `last_non_blank_row` (display_map.rs:2400-2417): back up over
/// blank rows, never past `floor`.
fn last_non_blank_row(text: &Rope, from: u32, floor: u32) -> u32 {
    let mut row = from;
    while row > floor && row_is_blank(text, row) {
        row -= 1;
    }
    row
}

/// The fold rules, over one `indents.scm` match at a time.
///
/// * An `@indent` node spanning more than one row folds. Its `@end` — the
///   closing bracket — stays visible on its own row, exactly as the indent
///   walk leaves a closing bracket outside the fold
///   (`closing_bracket_indent_len`, display_map.rs:2294-2314): the fold ends
///   on the row before it. A closer that shares its row with the block's own
///   content (`foo(a,\n  b)` — the `)` after `b`) cannot be kept out, so the
///   node's last row is the end instead.
/// * A `@start.*` node — Python's `def`, `if`, `class` — folds from its first
///   row to its last non-blank row; there is no closer to keep out.
/// * Nothing folds a single row, and trailing blank rows never hide, for
///   Zed's `last_non_blank_row` reason (display_map.rs:2400-2417).
/// * One fold per start row, the widest: `fn f() {` starts the function item
///   *and* its block, and the chevron means the function.
fn fold_ranges_in(indents: &IndentQuery, tree: &Tree, text: &Rope) -> Vec<FoldRange> {
    let mut by_start: HashMap<u32, u32> = HashMap::new();
    with_query_cursor(|cursor| {
        let mut matches = cursor.matches(&indents.query, tree.root_node(), RopeTextProvider(text));
        while let Some(match_) = matches.next() {
            let mut block: Option<tree_sitter::Node> = None;
            let mut closer: Option<tree_sitter::Node> = None;
            let mut is_start = false;
            for capture in match_.captures {
                if Some(capture.index) == indents.indent {
                    block = Some(capture.node);
                } else if Some(capture.index) == indents.end {
                    closer = Some(capture.node);
                } else if indents.starts.contains(&capture.index) {
                    block = Some(capture.node);
                    is_start = true;
                }
            }
            let Some(node) = block else {
                continue;
            };
            // An `@indent` with no `@end` beside it is a continuation rule —
            // rust's `(let_declaration) @indent`, go's
            // `(assignment_statement) @indent` — telling the auto-indenter to
            // indent a wrapped statement's second line. It is not a block,
            // and a chevron on every multi-line `let` would be noise.
            if !is_start && closer.is_none() {
                continue;
            }
            let start_row = node.start_position().row as u32;
            let node_end = node.end_position();
            // A node ending at column 0 ends on the previous row: its last
            // character was that row's newline.
            let last_row = if node_end.column == 0 && node_end.row > 0 {
                node_end.row as u32 - 1
            } else {
                node_end.row as u32
            };
            let end_row = match closer {
                Some(closer) if !is_start => {
                    let closer_row = closer.start_position().row as u32;
                    let before_closer = text.point_to_offset(Point::new(closer_row, 0))
                        ..text.point_to_offset(Point::new(
                            closer_row,
                            closer.start_position().column as u32,
                        ));
                    let closer_owns_row = text
                        .chunks_in_range(before_closer)
                        .all(|chunk| chunk.trim().is_empty());
                    if closer_owns_row && closer_row > start_row {
                        last_non_blank_row(text, closer_row - 1, start_row)
                    } else {
                        last_non_blank_row(text, last_row, start_row)
                    }
                }
                _ => last_non_blank_row(text, last_row, start_row),
            };
            if end_row <= start_row {
                continue;
            }
            by_start
                .entry(start_row)
                .and_modify(|end| *end = (*end).max(end_row))
                .or_insert(end_row);
        }
    });
    let mut ranges: Vec<FoldRange> = by_start
        .into_iter()
        .map(|(start_row, end_row)| FoldRange { start_row, end_row })
        .collect();
    ranges.sort_by_key(|range| range.start_row);
    ranges
}

/// Byte-column → UTF-16-column conversion with one lazily-built table per
/// row in the window.
/// Walk `cursor` to the node that contains `query_range` — Zed's
/// `BufferSnapshot::goto_node_enclosing_range` (crates/language/src/buffer.rs
/// :4309-4353), which descends towards the range and then climbs until a node
/// encloses it. With `require_larger`, a node that exactly matches the range
/// does not count, so the walk always makes progress outwards.
///
/// Returns false when the root does not enclose the range — there is nowhere
/// left to climb.
fn goto_node_enclosing_range(
    cursor: &mut tree_sitter::TreeCursor<'_>,
    query_range: &Range<usize>,
    require_larger: bool,
) -> bool {
    let mut ascending = false;
    loop {
        let mut range = cursor.node().byte_range();
        if query_range.is_empty() {
            // An empty query range sitting before this node belongs to the
            // sibling on its left.
            if range.start > query_range.start {
                cursor.goto_previous_sibling();
                range = cursor.node().byte_range();
            }
        } else if range.end == query_range.start {
            // A non-empty range that starts exactly where this node ends
            // belongs to the next one.
            cursor.goto_next_sibling();
            range = cursor.node().byte_range();
        }

        let encloses = range.start <= query_range.start
            && query_range.end <= range.end
            && (!require_larger || range.end - range.start > query_range.end - query_range.start);
        if !encloses {
            ascending = true;
            if !cursor.goto_parent() {
                return false;
            }
            continue;
        } else if ascending {
            return true;
        }

        if cursor.goto_first_child_for_byte(query_range.start).is_none() {
            return true;
        }
    }
}

/// The bracket characters a grammar-less buffer is matched with. Zed's own
/// fallback is the language's `brackets` config; a plain-text buffer has
/// none, and these three pairs are what every language this app bundles
/// agrees on.
const COUNTED_PAIRS: [(char, char); 3] = [('(', ')'), ('[', ']'), ('{', '}')];

/// How far either side of the caret [`counted_brackets`] looks. Generous
/// enough for any function in any file, and small enough that the scan is
/// free on the caret-move path it runs on.
const COUNTED_SCAN_BYTES: usize = 64 * 1024;

/// [`HighlightState::enclosing_brackets`] without a grammar: count delimiters
/// outwards from `range` until one is left unclosed on the left and unopened
/// on the right, then pair them. Quotes and comments are not understood — a
/// `{` in a string counts — which is exactly why the query is preferred where
/// there is one.
pub fn counted_brackets(text: &Rope, range: Range<usize>) -> Option<(Range<usize>, Range<usize>)> {
    let len = text.len();
    let start = range.start.min(len);
    let end = range.end.min(len).max(start);
    // Bounded, because this runs on every caret move: a pair further away than
    // this is not a pair anyone is reading, and scanning a whole megabyte file
    // for one would cost the move. The window is clipped to character
    // boundaries so the slice is valid UTF-8.
    let mut from = start.saturating_sub(COUNTED_SCAN_BYTES);
    while from > 0 && !text.is_char_boundary(from) {
        from -= 1;
    }
    let mut to = (end + COUNTED_SCAN_BYTES).min(len);
    while to < len && !text.is_char_boundary(to) {
        to += 1;
    }
    let source: String = text.chunks_in_range(from..to).collect();
    let bytes = source.as_bytes();
    let start = start - from;
    let end = end - from;
    let mut best: Option<(Range<usize>, Range<usize>)> = None;
    for (open, close) in COUNTED_PAIRS {
        let (open, close) = (open as u8, close as u8);
        // Backwards for the unmatched opener.
        let mut depth = 0i32;
        let mut open_at = None;
        let mut i = start;
        while i > 0 {
            i -= 1;
            let byte = bytes[i];
            if byte == close {
                depth += 1;
            } else if byte == open {
                if depth == 0 {
                    open_at = Some(i);
                    break;
                }
                depth -= 1;
            }
        }
        let Some(open_at) = open_at else { continue };
        // Forwards for its closer.
        let mut depth = 0i32;
        let mut close_at = None;
        let mut i = end;
        while i < bytes.len() {
            let byte = bytes[i];
            if byte == open {
                depth += 1;
            } else if byte == close {
                if depth == 0 {
                    close_at = Some(i);
                    break;
                }
                depth -= 1;
            }
            i += 1;
        }
        let Some(close_at) = close_at else { continue };
        let better = best
            .as_ref()
            .is_none_or(|(o, c)| close_at + 1 - open_at < c.end - o.start);
        if better {
            best = Some((
                from + open_at..from + open_at + 1,
                from + close_at..from + close_at + 1,
            ));
        }
    }
    best
}

struct ColumnConverter<'a> {
    text: &'a Rope,
    first_row: u32,
    lines: Vec<Option<String>>,
}

impl<'a> ColumnConverter<'a> {
    fn new(text: &'a Rope, first_row: u32, last_row: u32) -> Self {
        ColumnConverter {
            text,
            first_row,
            lines: vec![None; (last_row - first_row + 1) as usize],
        }
    }

    fn utf16_col(&mut self, row: u32, byte_col: usize) -> u32 {
        let slot = (row - self.first_row) as usize;
        let line = self.lines[slot].get_or_insert_with(|| {
            let start = Point::new(row, 0);
            let end = Point::new(row, self.text.line_len(row));
            self.text
                .chunks_in_range(self.text.point_to_offset(start)..self.text.point_to_offset(end))
                .collect()
        });
        let byte_col = byte_col.min(line.len());
        line[..byte_col].encode_utf16().count() as u32
    }
}

#[cfg(test)]
mod grammar_tests {
    use super::*;

    /// Every `.scm` we ship must compile against the grammar it sits next to.
    /// [`registry`] only logs when one doesn't, and a language whose
    /// `highlights.scm` failed to compile draws as plain text — a silent
    /// regression the moment a grammar bumps a node name.
    #[test]
    fn every_query_compiles_against_its_grammar() {
        for (name, language) in grammars::native_grammars() {
            let queries = grammars::load_queries(name);
            let sources = [
                ("highlights", queries.highlights),
                ("outline", queries.outline),
                ("indents", queries.indents),
                ("injections", queries.injections),
                ("overrides", queries.overrides),
                ("brackets", queries.brackets),
                ("runnables", queries.runnables),
            ];
            for (kind, source) in sources {
                let Some(source) = source else { continue };
                if let Err(err) = Query::new(&language, source.as_ref()) {
                    panic!("{name}/{kind}.scm does not compile: {err}");
                }
            }
        }
    }

    /// A grammar with no `config.toml` never reaches the extension table, the
    /// language selector or the settings' per-language section, so shipping
    /// the parse tables would be dead weight.
    #[test]
    fn every_grammar_carries_a_config() {
        for (name, _) in grammars::native_grammars() {
            assert!(
                grammars::get_file(&format!("{name}/config.toml")).is_some(),
                "{name} has a grammar but no config.toml"
            );
        }
    }

    /// The languages this app added on top of Zed's built-in set. A grammar
    /// that stops parsing its own smoke sample has broken, whatever the
    /// queries say.
    #[test]
    fn the_added_grammars_parse_their_own_syntax() {
        let samples = [
            ("html", "<!doctype html>\n<p class=\"x\">hi</p>\n"),
            ("java", "class A { void f() { int x = 1; } }\n"),
            ("kotlin", "fun main() {\n    val x = 1\n}\n"),
            ("toml", "[package]\nname = \"x\"\n"),
            ("dockerfile", "FROM debian:bookworm\nRUN echo hi\n"),
            ("make", "all:\n\techo hi\n"),
            ("sql", "select id from t where id = 1;\n"),
            ("xml", "<a><b k=\"v\">t</b></a>\n"),
            ("scss", "$c: red;\n.a { color: $c; }\n"),
            ("svelte", "<script>let a = 1;</script>\n<p>{a}</p>\n"),
        ];
        for (language, text) in samples {
            let rope = Rope::from(text);
            let state = HighlightState::new(language, &rope)
                .unwrap_or_else(|| panic!("{language} has no grammar"));
            let tree = state.tree().unwrap_or_else(|| panic!("{language} did not parse"));
            assert!(
                !tree.root_node().has_error(),
                "{language} could not parse its own sample"
            );
            assert!(
                !state.highlights(&rope, 0..text.len()).is_empty(),
                "{language} highlighted nothing"
            );
        }
    }
}

#[cfg(test)]
mod injection_tests {
    use super::*;

    /// The grammar the innermost layer at `needle` parsed with.
    fn layer_at(language: &str, text: &str, needle: &str) -> String {
        let rope = Rope::from(text);
        let state = HighlightState::new(language, &rope).expect("a grammar");
        let offset = text.find(needle).expect("the needle is in the text");
        state
            .scope_layer_at(offset)
            .expect("a parsed tree")
            .0
            .to_owned()
    }

    fn style(name: &str) -> u16 {
        STYLE_NAMES
            .iter()
            .position(|style| *style == name)
            .expect("a known style") as u16
    }

    #[test]
    fn a_markdown_fence_is_parsed_with_the_language_its_info_string_names() {
        let text = "Prose.\n\n```rust\nfn main() {}\n```\n";
        assert_eq!(layer_at("markdown", text, "fn main"), "rust");
    }

    /// The info string is a human's word for a language, not a grammar name —
    /// `js` is the file suffix, `c++` the display name.
    #[test]
    fn a_fence_may_name_its_language_by_alias() {
        let js = "```js\nlet a = 1;\n```\n";
        assert_eq!(layer_at("markdown", js, "let a"), "tsx");
        let cpp = "```c++\nint main() {}\n```\n";
        assert_eq!(layer_at("markdown", cpp, "int main"), "cpp");
    }

    #[test]
    fn a_fence_naming_a_grammar_we_do_not_carry_keeps_the_parent() {
        let text = "```brainfuck\n+++.\n```\n";
        assert_eq!(layer_at("markdown", text, "+++"), "markdown");
    }

    #[test]
    fn an_html_script_and_style_are_javascript_and_css() {
        let text = "<style>a { color: red; }</style>\n<script>let x = 1;</script>\n";
        assert_eq!(layer_at("html", text, "color"), "css");
        // Zed parses JavaScript with the tsx grammar, so that is the layer.
        assert_eq!(layer_at("html", text, "let x"), "tsx");
    }

    /// Zed's rust `injections.scm` sends a `sql!` macro's body to the SQL
    /// grammar; before this it was one more `token_tree`.
    #[test]
    fn a_rust_sql_macro_is_parsed_as_sql() {
        let text = "fn f() {\n    sql!(select id from t);\n}\n";
        assert_eq!(layer_at("rust", text, "select"), "sql");
    }

    /// Two injections deep: the fence is HTML, and the HTML holds CSS.
    #[test]
    fn injections_nest() {
        let text = "```html\n<style>a { color: red; }</style>\n```\n";
        assert_eq!(layer_at("markdown", text, "color"), "css");
    }

    /// The merge rule. Spans arrive root-first and the UI lets a later span
    /// win, so the deepest layer's colour is the one that shows — without
    /// this ordering a fenced `fn` would keep Markdown's literal-text style.
    #[test]
    fn a_deeper_layer_paints_over_the_layer_that_injected_it() {
        let text = "```rust\nfn main() {}\n```\n";
        let rope = Rope::from(text);
        let state = HighlightState::new("markdown", &rope).expect("a grammar");
        let spans = state.highlights(&rope, 0..text.len());
        let over_fn: Vec<&HighlightSpan> = spans
            .iter()
            .filter(|span| span.row == 1 && span.start_col_utf16 <= 1 && span.end_col_utf16 >= 2)
            .collect();
        assert!(
            !over_fn.is_empty(),
            "nothing highlighted the fenced `fn` at all"
        );
        assert_eq!(
            over_fn.last().unwrap().style,
            style("keyword"),
            "the last span over the fenced `fn` should be Rust's keyword style"
        );
    }

    /// A grammar that injects itself — Rust's `macro_invocation` does — must
    /// terminate. The cap is what makes that true.
    #[test]
    fn a_self_injecting_grammar_stops_at_the_depth_cap() {
        let text = "fn f() { a!(b!(c!(d!(e!(1))))); }\n";
        let rope = Rope::from(text);
        let state = HighlightState::new("rust", &rope).expect("a grammar");
        assert!(state.layers.len() <= MAX_INJECTION_LAYERS);
        assert!(
            state
                .layers
                .iter()
                .all(|layer| layer.depth <= MAX_INJECTION_DEPTH)
        );
    }

    /// A `"` typed inside a fenced Rust string must not autoclose, even
    /// though Markdown itself has no notion of a string there.
    #[test]
    fn the_innermost_layer_answers_the_bracket_scope() {
        let text = "```rust\nlet s = \"abc\";\n```\n";
        let rope = Rope::from(text);
        let state = HighlightState::new("markdown", &rope).expect("a grammar");
        let inside = text.find("abc").unwrap() + 1;
        let (language, tree) = state.scope_layer_at(inside).expect("a tree");
        assert_eq!(language, "rust");
        let mask = crate::language_config::enabled_brackets(
            "rust",
            tree,
            Some((language, tree)),
            &rope,
            inside,
        );
        assert_ne!(mask, u64::MAX, "the string scope disabled nothing");
    }
}

#[cfg(test)]
mod syntax_selection_tests {
    use super::*;

    fn parsed(language: &str, text: &str) -> (HighlightState, Rope) {
        let rope = Rope::from(text);
        let mut state = HighlightState::new(language, &rope).expect("a grammar");
        state.ensure_parsed(&rope);
        (state, rope)
    }

    /// The stack of ranges `alt-shift-right` walks through, as text.
    fn grown(language: &str, text: &str, at: usize) -> Vec<String> {
        let (state, _rope) = parsed(language, text);
        let mut range = at..at;
        let mut steps = Vec::new();
        for _ in 0..8 {
            let Some(next) = state.syntax_ancestor(range.clone()) else {
                break;
            };
            steps.push(text[next.clone()].to_owned());
            range = next;
        }
        steps
    }

    #[test]
    fn a_caret_in_an_argument_grows_out_through_the_call() {
        let text = "fn main() {\n    println!(\"hi\", name);\n}\n";
        let at = text.find("name").expect("the argument");
        let steps = grown("rust", text, at);
        assert_eq!(steps.first().map(String::as_str), Some("name"));
        // Every step is strictly wider than the one before it.
        for pair in steps.windows(2) {
            assert!(
                pair[1].len() > pair[0].len(),
                "{:?} did not grow past {:?}",
                pair[1],
                pair[0]
            );
        }
        // And the walk reaches the whole file rather than stalling.
        assert_eq!(steps.last().map(String::as_str), Some(text));
    }

    /// The stack the UI keeps is what makes shrinking retrace, so growing
    /// must be deterministic: the same caret gives the same ladder twice.
    #[test]
    fn growing_is_deterministic() {
        let text = "fn main() {\n    let total = a + b;\n}\n";
        let at = text.find(" + b").expect("the operator");
        assert_eq!(grown("rust", text, at), grown("rust", text, at));
    }

    /// A range that already covers the root has nowhere left to go.
    #[test]
    fn the_whole_file_cannot_grow() {
        let text = "fn main() {}\n";
        let (state, _rope) = parsed("rust", text);
        assert_eq!(state.syntax_ancestor(0..text.len()), None);
    }

    #[test]
    fn the_grammar_matches_the_braces_around_the_caret() {
        let text = "fn main() {\n    let x = 1;\n}\n";
        let (state, rope) = parsed("rust", text);
        let at = text.find("let").expect("the statement");
        let (open, close) = state
            .enclosing_brackets(&rope, at..at)
            .expect("a pair around the body");
        assert_eq!(&text[open], "{");
        assert_eq!(&text[close], "}");
    }

    #[test]
    fn the_innermost_pair_wins() {
        let text = "fn main() {\n    f(g(x));\n}\n";
        let (state, rope) = parsed("rust", text);
        let at = text.find('x').expect("the argument");
        let (open, close) = state
            .enclosing_brackets(&rope, at..at)
            .expect("a pair around x");
        // `g(` … `)`, not `f(` … `)` and not the function body's braces.
        assert_eq!(open.start, text.find("g(").unwrap() + 1);
        assert_eq!(close.start, text.find("));").unwrap());
    }

    /// A buffer with no grammar still matches its braces, by counting.
    #[test]
    fn counting_matches_brackets_without_a_grammar() {
        let text = "outer { inner { here } tail }";
        let rope = Rope::from(text);
        let at = text.find("here").expect("the caret");
        let (open, close) = counted_brackets(&rope, at..at).expect("a pair");
        assert_eq!(open.start, text.find("inner {").unwrap() + 6);
        assert_eq!(close.start, text.find("} tail").unwrap());
    }

    /// The scan is bounded, so a pair a long way off is not a pair: without
    /// the window this would be a walk over the whole file on every caret
    /// move.
    #[test]
    fn counting_gives_up_past_its_window() {
        let filler = "x".repeat(COUNTED_SCAN_BYTES + 16);
        let text = format!("{{{filler}|{filler}}}");
        let rope = Rope::from(text.as_str());
        let at = text.find('|').expect("the caret");
        assert_eq!(counted_brackets(&rope, at..at), None);
    }

    #[test]
    fn counting_answers_nothing_outside_any_pair() {
        let text = "no brackets here";
        let rope = Rope::from(text);
        assert_eq!(counted_brackets(&rope, 3..3), None);
    }
}

#[cfg(test)]
mod fold_tests {
    use super::*;

    fn folds(language: &str, text: &str) -> Vec<(u32, u32)> {
        let rope = Rope::from(text);
        let state = HighlightState::new(language, &rope).expect("a grammar");
        state
            .fold_ranges(&rope)
            .into_iter()
            .map(|range| (range.start_row, range.end_row))
            .collect()
    }

    #[test]
    fn a_brace_block_folds_to_the_row_before_its_closer() {
        let text = "fn main() {\n    let x = 1;\n    let y = 2;\n}\n";
        // Row 0 is the chip; rows 1..=2 hide; the `}` on row 3 stays.
        assert_eq!(folds("rust", text), vec![(0, 2)]);
    }

    #[test]
    fn nested_blocks_each_fold_and_the_widest_wins_a_shared_row() {
        let text = "impl Foo {\n    fn bar(&self) {\n        if true {\n            x();\n        }\n    }\n}\n";
        assert_eq!(folds("rust", text), vec![(0, 5), (1, 4), (2, 3)]);
    }

    #[test]
    fn a_single_row_block_does_not_fold() {
        assert_eq!(folds("rust", "fn f() { 1 }\n"), Vec::<(u32, u32)>::new());
    }

    #[test]
    fn an_unindented_line_inside_a_string_does_not_end_the_fold() {
        // The indent walk would close the block at `not indented`; the tree
        // knows it is inside the string literal (display_map.rs:2380-2393).
        let text = "fn f() {\n    let s = \"a\nnot indented\n\";\n    x();\n}\n";
        assert_eq!(folds("rust", text), vec![(0, 4)]);
    }

    #[test]
    fn a_closer_sharing_a_row_with_content_keeps_that_row_inside() {
        let text = "call(\n    a,\n    b);\nnext();\n";
        assert_eq!(folds("rust", text), vec![(0, 2)]);
    }

    #[test]
    fn python_blocks_fold_by_keyword_and_stop_before_trailing_blanks() {
        let text = "def f():\n    if x:\n        y()\n\n\nz()\n";
        assert_eq!(folds("python", text), vec![(0, 2), (1, 2)]);
    }

    #[test]
    fn a_language_without_an_indents_query_folds_nothing() {
        // `diff` has a grammar and no indents.scm; the UI keeps its indent
        // walk for it.
        assert_eq!(
            folds("diff", "--- a\n+++ b\n@@ -1 +1 @@\n-x\n+y\n"),
            Vec::<(u32, u32)>::new()
        );
    }
}
