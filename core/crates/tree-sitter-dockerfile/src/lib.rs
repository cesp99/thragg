//! Dockerfile support for tree-sitter.
//!
//! The grammar is camdencheek's `tree-sitter-dockerfile` (MIT), the same one
//! Zed's Dockerfile extension builds; `grammar/` holds its generated
//! `parser.c` and `scanner.c` verbatim. The crates.io release of that grammar
//! cannot be used here: it still declares `tree-sitter = "0.20"`, and a
//! `tree_sitter::Language` from 0.20 is a different Rust type from the 0.26
//! this workspace parses with, so `language()` would not typecheck — and
//! pulling a second tree-sitter in would duplicate the C runtime's symbols.
//! Exposing the raw `tree_sitter_dockerfile` entry point as a
//! [`LanguageFn`] sidesteps both.

use tree_sitter_language::LanguageFn;

unsafe extern "C" {
    fn tree_sitter_dockerfile() -> *const ();
}

/// The tree-sitter language for Dockerfile.
pub const LANGUAGE: LanguageFn = unsafe { LanguageFn::from_raw(tree_sitter_dockerfile) };
