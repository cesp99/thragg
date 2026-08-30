//! The matcher both searches share, and search over one open buffer.
//!
//! [`SearchQuery`] is a small port of Zed's `project::search::SearchQuery`:
//! literal queries go to `aho-corasick`, regex queries to a compiled regex,
//! and whole-word is a boundary test around each hit rather than a separate
//! algorithm. Zed reaches for `fancy-regex` because it wants lookaround; we
//! use `regex`, whose linear-time guarantee matters more here — a pattern
//! typed by accident must not be able to wedge the thread the search runs on,
//! and on this hardware that thread is not far from the frame loop.
//!
//! [`Engine::search_buffer`] is deliberately synchronous. It reads a rope
//! snapshot, scans it once and returns; on a 100k-line buffer that is a couple
//! of milliseconds, which is well inside a keystroke, so streaming or
//! cancelling it would buy nothing but complexity. Searching a *project* is a
//! different problem entirely and lives in `project_search.rs`.
//!
//! Replacing is the same matcher run once more, under the buffer's lock this
//! time, with every edit landed as one operation and one undo transaction —
//! see [`Engine::replace_all`]. The replacement text is expanded per hit the
//! way Zed's `SearchQuery::replacement_for` expands it
//! (crates/project/src/search.rs:461-508): verbatim for a literal query, and
//! for a regex with `$1`/`${name}` capture groups and `\n`/`\t`/`\\`
//! escapes.

use std::ops::Range;
use std::sync::LazyLock;

use aho_corasick::{AhoCorasick, AhoCorasickBuilder};
use regex::{Regex, RegexBuilder};

use crate::{BufferId, EngineError};

/// What a search bar asks for. Buffer and project search take the same shape
/// so one set of toggles can drive either; the last three fields are simply
/// ignored when searching a buffer.
#[derive(Debug, Clone, Default, PartialEq, serde::Deserialize)]
#[serde(default)]
pub struct SearchOptions {
    pub query: String,
    /// Treat `query` as a regular expression rather than literal text.
    pub regex: bool,
    pub case_sensitive: bool,
    /// Keep only hits with a non-word character (or nothing) on either side,
    /// where a word character is `alphanumeric || '_'`. The rule is the same
    /// for a literal and for a regex: a regex is filtered on where its match
    /// landed, never rewritten.
    pub whole_word: bool,
    /// Project search: also search files git ignores.
    pub include_ignored: bool,
    /// Project search: only these paths. Empty means every file.
    pub include_globs: Vec<String>,
    /// Project search: never these paths. Applied after `include_globs`.
    pub exclude_globs: Vec<String>,
}

/// A compiled query, ready to run against any text.
pub(crate) struct SearchQuery {
    matcher: Matcher,
    /// Checked around every hit, whichever matcher produced it. Zed splices
    /// `\b` into the pattern for its regex path, but a spliced anchor is not
    /// the same rule: `foo|bar` becomes `(\bfoo)|(bar\b)`, and a pattern that
    /// starts on punctuation cannot be anchored at all, so whole-word would
    /// silently mean three different things depending on what was typed. One
    /// test around the hit means it always means what the contract says.
    whole_word: bool,
    /// The query is a regex the user wrote, so a replacement may name its
    /// capture groups. False for the literal that only borrowed the regex
    /// engine to fold non-ASCII case: Zed's `escaped: true` arm
    /// (crates/project/src/search.rs:463-468).
    expands_captures: bool,
}

enum Matcher {
    /// Aho-Corasick has no notion of a word boundary — hence the test above.
    Literal(Box<AhoCorasick>),
    Regex(Box<Regex>),
}

/// What a word can be spelled with. Zed derives this from the language's
/// `word_characters` setting; until the engine carries language settings, this
/// is the same default Zed falls back to.
fn is_word_char(c: char) -> bool {
    c.is_alphanumeric() || c == '_'
}

impl SearchQuery {
    /// Compile `options`, or `Ok(None)` when the query is empty — an empty
    /// query matches nothing, and every caller wants that to be an ordinary
    /// empty result rather than an error to report.
    pub(crate) fn new(options: &SearchOptions) -> Result<Option<Self>, String> {
        let mut query = options.query.clone();
        text::LineEnding::normalize(&mut query);
        if query.is_empty() {
            return Ok(None);
        }

        let matcher = if options.regex {
            Matcher::Regex(Box::new(build_regex(&query, options)?))
        } else if !options.case_sensitive && !query.is_ascii() {
            // `ascii_case_insensitive` only folds ASCII, so a case-insensitive
            // search for anything else has to go through the regex engine.
            // Zed makes the same detour, for the same reason.
            Matcher::Regex(Box::new(build_regex(&regex::escape(&query), options)?))
        } else {
            let search = AhoCorasickBuilder::new()
                .ascii_case_insensitive(!options.case_sensitive)
                .build([&query])
                .map_err(|err| err.to_string())?;
            Matcher::Literal(Box::new(search))
        };
        Ok(Some(Self {
            matcher,
            whole_word: options.whole_word,
            expands_captures: options.regex,
        }))
    }

    /// Every match in `text`, ascending and non-overlapping, keeping at most
    /// `limit` of them. The second value is how many there were in all, so a
    /// caller that truncated can still say how much it dropped.
    ///
    /// Zero-width matches (`a*`, `\b`) are skipped: they have nothing to show
    /// and nothing to step through, and a pattern that matches empty would
    /// otherwise report one hit per position in the file.
    pub(crate) fn matches_in(&self, text: &str, limit: usize) -> (Vec<Range<usize>>, usize) {
        let mut ranges = Vec::new();
        let mut total = 0;
        let whole_word = self.whole_word;
        let mut keep = |range: Range<usize>| {
            if range.is_empty() {
                return;
            }
            // Both offsets sit on character boundaries: UTF-8 is
            // self-synchronising, so a valid needle cannot match halfway into
            // a character of a valid haystack, and `regex` refuses to compile
            // a pattern that could match invalid UTF-8.
            if whole_word && !is_whole_word(text, range.start, range.end) {
                return;
            }
            total += 1;
            if ranges.len() < limit {
                ranges.push(range);
            }
        };

        match &self.matcher {
            Matcher::Literal(search) => {
                for found in search.find_iter(text) {
                    keep(found.start()..found.end());
                }
            }
            Matcher::Regex(regex) => {
                for found in regex.find_iter(text) {
                    keep(found.start()..found.end());
                }
            }
        }
        (ranges, total)
    }

    /// What to put in place of the hit at `hit`, a range [`matches_in`]
    /// reported over the same `text`.
    ///
    /// A literal query gets `replacement` as typed. A regex gets it expanded:
    /// `$1`, `$name` and `${name}` are the pattern's groups (`$$` is a
    /// dollar), and `\n`, `\t` and `\\` become a newline, a tab and a
    /// backslash — Zed's `TEXT_REPLACEMENT_SPECIAL_CHARACTERS_REGEX`
    /// (crates/project/src/search.rs:476-486). The groups are re-captured
    /// from `hit.start` over the whole text, so a lookbehind-free pattern
    /// sees exactly the context it matched in; if that somehow lands
    /// elsewhere the hit is matched on its own, as Zed falls back to.
    pub(crate) fn replacement_for(
        &self,
        text: &str,
        hit: Range<usize>,
        replacement: &str,
    ) -> String {
        let regex = match &self.matcher {
            Matcher::Regex(regex) if self.expands_captures => regex,
            _ => return replacement.to_owned(),
        };
        let replacement = unescape_replacement(replacement);
        let captures = regex
            .captures_at(text, hit.start)
            .filter(|captures| captures.get(0).is_some_and(|m| m.range() == hit));
        let mut replaced = String::new();
        match captures {
            Some(captures) => captures.expand(&replacement, &mut replaced),
            None => replaced.push_str(&regex.replace(&text[hit], replacement.as_str())),
        }
        replaced
    }
}

/// `\\`, `\n` and `\t` in a regex replacement, exactly the three Zed
/// recognises; anything else stays as typed, backslash included.
///
/// A numbered group is also braced — `$1og` becomes `${1}og` — because the
/// regex crate's expander otherwise reads the longest name it can, and
/// `$1og` would be the (empty) group called `1og`. Every other editor's
/// replace box reads it as group 1 followed by `og`, and so does this one;
/// `$$` stays the escaped dollar it is.
fn unescape_replacement(replacement: &str) -> String {
    static ESCAPES: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r"\\\\|\\n|\\t|\$\$|\$(\d+)").unwrap());
    ESCAPES
        .replace_all(replacement, |c: &regex::Captures| match &c[0] {
            r"\\" => "\\".to_owned(),
            r"\n" => "\n".to_owned(),
            r"\t" => "\t".to_owned(),
            "$$" => "$$".to_owned(),
            _ => format!("${{{}}}", &c[1]),
        })
        .into_owned()
}

fn build_regex(pattern: &str, options: &SearchOptions) -> Result<Regex, String> {
    RegexBuilder::new(pattern)
        .case_insensitive(!options.case_sensitive)
        .multi_line(true)
        .build()
        .map_err(|err| err.to_string())
}

/// Neither neighbour of `start..end` is a word character — the whole of what
/// `whole_word` promises, and the same test for every matcher.
///
/// One consequence of testing rather than anchoring: the regex engine picks
/// its match before we judge it, so `foo|foobar` over "foobar" finds `foo`,
/// has it rejected, and does not go back for the alternative that would have
/// qualified. An anchored pattern would; it would also mis-anchor alternation
/// outright, which is the worse failure of the two.
fn is_whole_word(text: &str, start: usize, end: usize) -> bool {
    !text[..start].chars().next_back().is_some_and(is_word_char)
        && !text[end..].chars().next().is_some_and(is_word_char)
}

/// One hit in a buffer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BufferMatch {
    /// Byte offsets into the buffer, on character boundaries.
    pub start: usize,
    pub end: usize,
    /// Where `start` lands, in the coordinates the editor works in: a 0-based
    /// row and a *byte* column, exactly what `offset_to_point` reports.
    pub row: u32,
    pub column: u32,
}

/// Everything [`Engine::search_buffer`] found.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct BufferSearch {
    pub matches: Vec<BufferMatch>,
    /// How many matches the buffer holds. Larger than `matches.len()` when the
    /// limit bit — the UI can still say "3 of 12 000".
    pub total: usize,
}

/// What a replacement did.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ReplaceOutcome {
    /// The buffer's version afterwards — the one before when `replaced` is 0,
    /// because nothing was edited and nothing went into the history.
    pub version: u64,
    /// How many hits were rewritten.
    pub replaced: usize,
    /// Byte offset just past the last replacement, in the *new* text: where a
    /// search bar stepping on to the next match should resume from, so the
    /// hit it lands on is the one after the text it just rewrote.
    pub resume_at: usize,
}

/// Which of a buffer's hits a replacement rewrites.
enum ReplaceScope {
    /// The first hit at or after a byte offset, wrapping round to the first
    /// in the buffer — Zed's `ReplaceNext` replaces the active match and the
    /// active match is the first one from the caret.
    NextFrom(usize),
    All,
}

impl crate::Engine {
    /// Rewrite the first hit of `options` at or after `from` — wrapping to
    /// the first in the buffer — with `replacement`, expanded per
    /// [`SearchQuery::replacement_for`]. Zed's `search::ReplaceNext`
    /// (crates/search/src/buffer_search.rs:1762-1785), reached from the
    /// engine side so the whole thing is one call rather than a search, an
    /// edit and a search again.
    ///
    /// A buffer without a hit is left alone and reports `replaced: 0`; an
    /// empty query is not an error, as everywhere else in search.
    pub fn replace_next(
        &self,
        id: BufferId,
        options: &SearchOptions,
        replacement: &str,
        from: usize,
    ) -> Result<ReplaceOutcome, EngineError> {
        self.replace_in_buffer(id, options, replacement, ReplaceScope::NextFrom(from))
    }

    /// Rewrite every hit of `options` in a buffer with `replacement` — Zed's
    /// `search::ReplaceAll` (crates/search/src/buffer_search.rs:1787-1802,
    /// landing in `Editor::replace_all`, crates/editor/src/items.rs:1911-1943).
    ///
    /// One edit operation and one undo transaction, however many hits: the
    /// buffer's history is bracketed so this can neither merge into the
    /// typing before it nor come apart into a thousand undos afterwards.
    pub fn replace_all(
        &self,
        id: BufferId,
        options: &SearchOptions,
        replacement: &str,
    ) -> Result<ReplaceOutcome, EngineError> {
        self.replace_in_buffer(id, options, replacement, ReplaceScope::All)
    }

    /// Both replacements: the matcher run over the text under the buffer's
    /// lock, so nothing can move between finding a hit and rewriting it, and
    /// every chosen hit landed as a single multi-range edit.
    ///
    /// The highlighter is reset and the language server sent the whole
    /// document rather than each range, exactly as `reload_buffer` and undo
    /// do: this is a history-shaped event, not typing, and one full resync
    /// is cheaper than a thousand incremental ones.
    fn replace_in_buffer(
        &self,
        id: BufferId,
        options: &SearchOptions,
        replacement: &str,
        scope: ReplaceScope,
    ) -> Result<ReplaceOutcome, EngineError> {
        let query = SearchQuery::new(options).map_err(EngineError::InvalidQuery)?;
        let buffer = self.buffer(id)?;
        let mut guard = buffer.lock().unwrap();
        let state = &mut *guard;
        let untouched = |from: usize| ReplaceOutcome {
            version: state.version,
            replaced: 0,
            resume_at: from,
        };
        let Some(query) = query else {
            return Ok(untouched(0));
        };

        let text = state.buffer.text();
        let (ranges, _) = query.matches_in(&text, usize::MAX);
        let chosen: &[Range<usize>] = match scope {
            ReplaceScope::All => &ranges,
            ReplaceScope::NextFrom(from) => {
                let index = ranges
                    .iter()
                    .position(|range| range.start >= from)
                    .unwrap_or(0);
                match ranges.get(index) {
                    Some(_) => &ranges[index..=index],
                    None => &[],
                }
            }
        };
        if chosen.is_empty() {
            return Ok(untouched(match scope {
                ReplaceScope::NextFrom(from) => from.min(text.len()),
                ReplaceScope::All => 0,
            }));
        }

        let edits: Vec<(Range<usize>, String)> = chosen
            .iter()
            .map(|range| {
                let text = query.replacement_for(&text, range.clone(), replacement);
                (range.clone(), text)
            })
            .collect();
        // Where the last replacement ends once the earlier ones have shifted
        // the text: its old start, moved by every byte the edits before it
        // added or removed, plus its own new length.
        let resume_at = {
            let (last, last_text) = edits.last().expect("chosen is non-empty");
            let shift: isize = edits[..edits.len() - 1]
                .iter()
                .map(|(range, text)| text.len() as isize - range.len() as isize)
                .sum();
            (last.start as isize + shift) as usize + last_text.len()
        };

        let old_end = self
            .lsp_is_live()
            .then(|| state.buffer.snapshot().max_point_utf16());
        // Bracketed like a reload: a discrete event on either side of the
        // typing, and — with the explicit transaction around the edit — one
        // undo step whatever `chosen` holds.
        state.buffer.finalize_last_transaction();
        state.buffer.start_transaction();
        state.buffer.edit(
            edits
                .iter()
                .map(|(range, text)| (range.clone(), text.as_str())),
        );
        state.buffer.end_transaction();
        state.buffer.finalize_last_transaction();
        state.version += 1;
        let needs_highlight = state.reset_highlighter();
        let version = state.version;
        let lsp_change = self.history_change(state, old_end);
        drop(guard);
        if needs_highlight {
            self.request_highlight(id);
        }
        if let Some(change) = lsp_change {
            self.lsp_did_change(id, change);
        }
        Ok(ReplaceOutcome {
            version,
            replaced: edits.len(),
            resume_at,
        })
    }

    /// Every match of `options` in a buffer, ascending, keeping at most
    /// `limit`.
    ///
    /// Fast enough to run on every keystroke of the query: a 100k-line buffer
    /// is a single pass over its text (see the `searching_a_large_buffer_is_fast`
    /// test, which holds it to 50 ms with a wide margin in practice).
    pub fn search_buffer(
        &self,
        id: BufferId,
        options: &SearchOptions,
        limit: usize,
    ) -> Result<BufferSearch, EngineError> {
        let query = SearchQuery::new(options).map_err(EngineError::InvalidQuery)?;
        // Clone the rope rather than the text: it is a sum-tree of shared
        // chunks, so this costs nothing and the flattening below happens
        // without the buffer lock held against the edit path.
        let rope = self.with_buffer(id, |state| state.buffer.as_rope().clone())?;
        let Some(query) = query else {
            return Ok(BufferSearch::default());
        };

        let text = rope.to_string();
        let (ranges, total) = query.matches_in(&text, limit);
        let matches = ranges
            .into_iter()
            .map(|range| {
                let point = rope.offset_to_point(range.start);
                BufferMatch {
                    start: range.start,
                    end: range.end,
                    row: point.row,
                    column: point.column,
                }
            })
            .collect();
        Ok(BufferSearch { matches, total })
    }

    /// Why `options` will not compile, or `None` if it will. The search bar
    /// calls this to explain a half-typed regex instead of silently showing
    /// nothing.
    pub fn search_query_error(&self, options: &SearchOptions) -> Option<String> {
        SearchQuery::new(options).err()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Engine;

    fn options(query: &str) -> SearchOptions {
        SearchOptions {
            query: query.to_owned(),
            ..Default::default()
        }
    }

    fn found(text: &str, options: &SearchOptions) -> Vec<String> {
        let query = SearchQuery::new(options).unwrap().unwrap();
        let (ranges, _) = query.matches_in(text, usize::MAX);
        ranges.into_iter().map(|r| text[r].to_owned()).collect()
    }

    #[test]
    fn literal_matching_is_case_insensitive_by_default() {
        let text = "Foo foo FOO barfoo";
        assert_eq!(found(text, &options("foo")).len(), 4);
        assert_eq!(
            found(
                text,
                &SearchOptions {
                    case_sensitive: true,
                    ..options("foo")
                }
            ),
            vec!["foo", "foo"]
        );
    }

    #[test]
    fn whole_word_rejects_hits_inside_words() {
        let text = "foo foobar barfoo _foo foo_ foo-bar";
        let whole = SearchOptions {
            whole_word: true,
            ..options("foo")
        };
        // Only the standalone "foo"s survive: an underscore continues a word,
        // a hyphen does not.
        assert_eq!(found(text, &whole), vec!["foo", "foo"]);

        // The regex path applies the same rule through `\b`.
        let whole_regex = SearchOptions {
            regex: true,
            ..whole
        };
        assert_eq!(found(text, &whole_regex), vec!["foo", "foo"]);
    }

    #[test]
    fn whole_word_judges_a_punctuation_pattern_by_its_neighbours() {
        // A pattern that begins on punctuation is not exempt: the rule is
        // about the characters either side of the hit, not about the pattern.
        let whole = SearchOptions {
            regex: true,
            whole_word: true,
            ..options(r"\(x\)")
        };
        assert_eq!(found("f(x) g(x)", &whole), Vec::<String>::new());
        assert_eq!(found("f (x) g", &whole), vec!["(x)"]);
        // The literal path, given the same text, says the same thing.
        let literal = SearchOptions {
            regex: false,
            ..options("(x)")
        };
        assert_eq!(
            found(
                "f(x) g(x)",
                &SearchOptions {
                    whole_word: true,
                    ..literal
                }
            ),
            Vec::<String>::new()
        );
    }

    #[test]
    fn whole_word_holds_for_alternation_and_for_patterns_that_cannot_be_anchored() {
        // Splicing `\b` onto the ends of the raw pattern turned `foo|bar` into
        // `(\bfoo)|(bar\b)`, which matched inside longer words, and did
        // nothing at all to a pattern that started or ended on punctuation.
        let cases = [
            ("foo|bar", "xxfoo barbar foobar", vec![]),
            ("foo|bar", "foo bar", vec!["foo", "bar"]),
            ("(?i)foo", "xxfoo foo", vec!["foo"]),
            (r"\w+", "one two", vec!["one", "two"]),
            ("[a-z]+", "9abc abc", vec!["abc"]),
        ];
        for (pattern, text, expected) in cases {
            let whole = SearchOptions {
                regex: true,
                whole_word: true,
                ..options(pattern)
            };
            assert_eq!(found(text, &whole), expected, "{pattern:?} over {text:?}");
        }
    }

    #[test]
    fn whole_word_does_not_depend_on_the_case_toggle() {
        // A non-ASCII literal only reaches the regex engine when the search is
        // case-insensitive; before, that detour changed what whole-word meant.
        for case_sensitive in [true, false] {
            let whole = SearchOptions {
                case_sensitive,
                whole_word: true,
                ..options("«x»")
            };
            assert_eq!(found("a«x»b", &whole), Vec::<String>::new());
            assert_eq!(found("a «x» b", &whole), vec!["«x»"]);
        }
    }

    #[test]
    fn regex_matching() {
        let text = "a1 b22 c333";
        let regex = SearchOptions {
            regex: true,
            ..options(r"[a-z]\d+")
        };
        assert_eq!(found(text, &regex), vec!["a1", "b22", "c333"]);

        // Anchors work per line, as in every editor's find.
        let per_line = SearchOptions {
            regex: true,
            ..options(r"^\w+")
        };
        assert_eq!(found("one two\nthree", &per_line), vec!["one", "three"]);
    }

    #[test]
    fn a_broken_regex_is_reported_not_swallowed() {
        let broken = SearchOptions {
            regex: true,
            ..options("(unclosed")
        };
        let error = SearchQuery::new(&broken).err().expect("a compile error");
        assert!(!error.is_empty());
        // The same text, taken literally, is a perfectly good query.
        assert_eq!(
            found("a (unclosed b", &options("(unclosed")),
            vec!["(unclosed"]
        );
    }

    #[test]
    fn matches_never_split_a_character() {
        // "é" is 2 bytes, "𝄞" is 4, and "日本語" 3 each. Every reported offset
        // must land on a character boundary, whichever matcher runs.
        let text = "héllo 𝄞 日本語 héllo";
        for options in [
            options("héllo"),
            SearchOptions {
                case_sensitive: true,
                ..options("héllo")
            },
            SearchOptions {
                regex: true,
                ..options("h.llo")
            },
            options("日本語"),
            options("𝄞"),
        ] {
            let query = SearchQuery::new(&options).unwrap().unwrap();
            let (ranges, _) = query.matches_in(text, usize::MAX);
            assert!(!ranges.is_empty(), "{options:?} found nothing");
            for range in ranges {
                assert!(
                    text.is_char_boundary(range.start) && text.is_char_boundary(range.end),
                    "{options:?} produced {range:?}, which splits a character"
                );
            }
        }
    }

    #[test]
    fn case_insensitive_non_ascii_falls_back_to_the_regex_engine() {
        // Aho-Corasick cannot fold "É" to "é", so this has to be a regex — and
        // the literal must still be escaped, or "." below would match anything.
        assert_eq!(found("Été ÉTÉ été", &options("été")).len(), 3);
        assert!(found("a.b axb", &options(".")).iter().all(|hit| hit == "."));
    }

    #[test]
    fn zero_width_matches_are_dropped() {
        let empty_ok = SearchOptions {
            regex: true,
            ..options("x*")
        };
        assert_eq!(found("axxb", &empty_ok), vec!["xx"]);
    }

    #[test]
    fn buffer_search_reports_offsets_and_points() {
        let engine = Engine::new();
        let id = engine.create_buffer("let x = 1;\nlet héllo = x;\nlet x = 2;");
        let result = engine.search_buffer(id, &options("x"), 100).unwrap();
        assert_eq!(result.total, 3);
        assert_eq!(
            result.matches,
            vec![
                BufferMatch {
                    start: 4,
                    end: 5,
                    row: 0,
                    column: 4
                },
                // "héllo" is 6 bytes for 5 characters, so the byte column runs
                // ahead of the character count — as `offset_to_point` reports
                // it, and as `point_to_offset` expects it back.
                BufferMatch {
                    start: 24,
                    end: 25,
                    row: 1,
                    column: 13
                },
                BufferMatch {
                    start: 31,
                    end: 32,
                    row: 2,
                    column: 4
                },
            ]
        );

        // The buffer's own conversion agrees with the reported point.
        let first = result.matches[1];
        assert_eq!(
            engine.point_to_offset(id, first.row, first.column).unwrap(),
            first.start
        );
    }

    #[test]
    fn buffer_search_truncates_but_counts_honestly() {
        let engine = Engine::new();
        let id = engine.create_buffer(&"ab".repeat(1000));
        let result = engine.search_buffer(id, &options("a"), 10).unwrap();
        assert_eq!(result.matches.len(), 10);
        assert_eq!(result.total, 1000);
    }

    #[test]
    fn an_empty_query_finds_nothing_and_is_not_an_error() {
        let engine = Engine::new();
        let id = engine.create_buffer("anything");
        let result = engine.search_buffer(id, &options(""), 100).unwrap();
        assert_eq!(result, BufferSearch::default());
        assert_eq!(engine.search_query_error(&options("")), None);
    }

    #[test]
    fn buffer_search_rejects_unknown_buffers_and_bad_regexes() {
        let engine = Engine::new();
        let id = engine.create_buffer("text");
        assert_eq!(
            engine.search_buffer(999, &options("t"), 10),
            Err(EngineError::UnknownBuffer(999))
        );
        let broken = SearchOptions {
            regex: true,
            ..options("(")
        };
        assert!(matches!(
            engine.search_buffer(id, &broken, 10),
            Err(EngineError::InvalidQuery(_))
        ));
        assert!(engine.search_query_error(&broken).is_some());
    }

    fn replaced(text: &str, options: &SearchOptions, replacement: &str) -> String {
        let engine = Engine::new();
        let id = engine.create_buffer(text);
        engine.replace_all(id, options, replacement).unwrap();
        engine.text(id).unwrap()
    }

    #[test]
    fn literal_replacement_is_verbatim_and_honours_case_and_word() {
        // No escapes and no groups for a literal: what was typed is what lands,
        // `$1` and `\n` included (crates/project/src/search.rs:463-468).
        assert_eq!(
            replaced("foo Foo foobar", &options("foo"), r"$1\n"),
            r"$1\n $1\n $1\nbar"
        );
        assert_eq!(
            replaced(
                "foo Foo foobar",
                &SearchOptions {
                    case_sensitive: true,
                    ..options("foo")
                },
                "x"
            ),
            "x Foo xbar"
        );
        assert_eq!(
            replaced(
                "foo Foo foobar",
                &SearchOptions {
                    whole_word: true,
                    ..options("foo")
                },
                "x"
            ),
            "x x foobar"
        );
    }

    #[test]
    fn regex_replacement_expands_groups_and_escapes() {
        let regex = |pattern: &str| SearchOptions {
            regex: true,
            ..options(pattern)
        };
        assert_eq!(
            replaced(
                "let a = 1; let b = 2;",
                &regex(r"let (\w+) = (\d+);"),
                "$2 -> $1"
            ),
            "1 -> a 2 -> b"
        );
        assert_eq!(
            replaced("key=value", &regex(r"(?P<k>\w+)=(?P<v>\w+)"), "${v}=${k}"),
            "value=key"
        );
        // Zed's three escapes and nothing else (search.rs:476-486); `$$` is
        // the regex crate's own spelling of a literal dollar.
        assert_eq!(
            replaced("a b", &regex(r"(\w) (\w)"), r"$1\n\t$2\\$$"),
            "a\n\tb\\$"
        );
        // `$$1` is a dollar and then a one, not group 1 in a costume.
        assert_eq!(replaced("a", &regex("(a)"), "$$1"), "$1");
        // Whole-word filtering applies before a group is expanded.
        assert_eq!(
            replaced(
                "cat concat cat",
                &SearchOptions {
                    whole_word: true,
                    ..regex(r"(c)at")
                },
                "$1og"
            ),
            "cog concat cog"
        );
        // A literal that only borrowed the regex engine for case folding must
        // not start expanding groups.
        assert_eq!(replaced("Été", &options("été"), "$0"), "$0");
    }

    #[test]
    fn replace_all_is_one_undo_step_and_reports_what_it_did() {
        let engine = Engine::new();
        let id = engine.create_buffer("a a a");
        let before = engine.version(id).unwrap();
        let outcome = engine.replace_all(id, &options("a"), "bb").unwrap();
        assert_eq!(outcome.replaced, 3);
        assert_eq!(outcome.version, before + 1);
        assert_eq!(outcome.resume_at, "bb bb bb".len());
        assert_eq!(engine.text(id).unwrap(), "bb bb bb");

        assert!(engine.undo(id).unwrap().is_some());
        assert_eq!(engine.text(id).unwrap(), "a a a");
        assert!(engine.redo(id).unwrap().is_some());
        assert_eq!(engine.text(id).unwrap(), "bb bb bb");

        // Nothing to replace: nothing edited, nothing in the history.
        let untouched = engine.replace_all(id, &options("zzz"), "x").unwrap();
        assert_eq!(untouched.replaced, 0);
        assert_eq!(untouched.version, engine.version(id).unwrap());
        assert_eq!(
            engine.replace_all(id, &options(""), "x").unwrap().replaced,
            0
        );
        assert!(matches!(
            engine.replace_all(
                id,
                &SearchOptions {
                    regex: true,
                    ..options("(")
                },
                "x"
            ),
            Err(EngineError::InvalidQuery(_))
        ));
        assert_eq!(
            engine.replace_all(999, &options("a"), "x"),
            Err(EngineError::UnknownBuffer(999))
        );
    }

    #[test]
    fn replace_next_takes_the_hit_from_the_offset_and_wraps() {
        let engine = Engine::new();
        let id = engine.create_buffer("x1 x2 x3");
        // From the second hit: replaces it, resumes just past the new text.
        let outcome = engine
            .replace_next(
                id,
                &SearchOptions {
                    regex: true,
                    ..options(r"x(\d)")
                },
                "y$1y",
                3,
            )
            .unwrap();
        assert_eq!(engine.text(id).unwrap(), "x1 y2y x3");
        assert_eq!((outcome.replaced, outcome.resume_at), (1, 6));
        // Past the last hit: wraps to the first.
        let outcome = engine.replace_next(id, &options("x"), "z", 100).unwrap();
        assert_eq!(engine.text(id).unwrap(), "z1 y2y x3");
        assert_eq!(outcome.resume_at, 1);
        // No hit at all: untouched, and the offset handed back is clipped to
        // the buffer.
        let outcome = engine.replace_next(id, &options("q"), "z", 100).unwrap();
        assert_eq!(outcome.replaced, 0);
        assert_eq!(outcome.resume_at, "z1 y2y x3".len());
    }

    #[test]
    fn replacement_is_safe_across_multibyte_text() {
        assert_eq!(
            replaced("héllo 𝄞 héllo", &options("héllo"), "日本"),
            "日本 𝄞 日本"
        );
        assert_eq!(
            replaced(
                "a𝄞b",
                &SearchOptions {
                    regex: true,
                    ..options("(.)𝄞(.)")
                },
                "$2𝄞$1"
            ),
            "b𝄞a"
        );
    }

    /// The number this test prints is the one that matters for the search bar:
    /// it runs on every keystroke of the query, so it has to fit inside a
    /// frame. The bound is far above what the machine does, because CI and a
    /// phone are not this machine — the point is to catch an accidental
    /// quadratic, not to police milliseconds.
    #[test]
    fn searching_a_large_buffer_is_fast() {
        let mut text = String::with_capacity(4 * 1024 * 1024);
        for row in 0..100_000 {
            text.push_str("    let value = compute(input, ");
            text.push_str(&row.to_string());
            text.push_str("); // héllo\n");
        }
        let engine = Engine::new();
        let id = engine.create_buffer(&text);

        // Both ends of the range: a query still being typed that matches
        // nothing, and the pathological one where every line matches.
        for (options, expected) in [
            (options("zzzzzz"), 0),
            (options("compute(input, 99999)"), 1),
            (options("compute"), 100_000),
            (
                SearchOptions {
                    whole_word: true,
                    ..options("value")
                },
                100_000,
            ),
            (
                SearchOptions {
                    regex: true,
                    ..options(r"compute\(\w+, \d+\)")
                },
                100_000,
            ),
        ] {
            let start = std::time::Instant::now();
            let result = engine.search_buffer(id, &options, 10_000).unwrap();
            let elapsed = start.elapsed();
            assert_eq!(result.total, expected);
            println!("{:?} over 100k lines: {elapsed:?}", options.query);
            assert!(
                elapsed < std::time::Duration::from_millis(500),
                "{:?} took {elapsed:?} over a 100k-line buffer",
                options.query
            );
        }
    }
}
