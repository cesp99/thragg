//! The external formatter: `"formatter": {"external": {"command": …}}`.
//!
//! Zed's `format_via_external_command` (project/src/lsp_store.rs:2600-2680),
//! with the program running inside the userland rather than on the host —
//! the same door every other program the engine starts goes through. The
//! buffer goes in on stdin, whatever comes out on stdout replaces it, and an
//! empty answer leaves the buffer alone, for Zed's reason: `cargo fmt` and
//! its kind rewrite the file on disk and print nothing, and taking nothing
//! as the new text would erase the buffer.
//!
//! The language-server formatter is not here; it is an LSP request like any
//! other (`lsp.rs`, `RequestKind::Formatting`). The save path picks between
//! the two from the buffer's resolved `formatter`.

use std::ffi::OsString;
use std::path::Path;
use std::time::Duration;

use crate::guest::{self, Captured, GuestCommand};
use crate::{BufferId, EngineError};

/// A formatter that has not answered in this long is not formatting.
const FORMAT_TIMEOUT: Duration = Duration::from_secs(30);

/// What running the external formatter came to, for the save path.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct FormatOutcome {
    /// The buffer's text was replaced.
    pub changed: bool,
    /// Why it was not, when it was not — in a sentence for the status bar.
    /// `None` on success, including the success that changed nothing.
    pub error: Option<String>,
}

impl FormatOutcome {
    fn failed(message: impl Into<String>) -> Self {
        Self {
            changed: false,
            error: Some(message.into()),
        }
    }
}

/// Zed substitutes `{buffer_path}` in each argument with the file's path,
/// and "Untitled" when there is none (lsp_store.rs:2628-2634).
fn substitute_arguments(arguments: &[String], path: Option<&Path>) -> Vec<OsString> {
    let path = path
        .map(|path| path.to_string_lossy().into_owned())
        .unwrap_or_else(|| "Untitled".to_owned());
    arguments
        .iter()
        .map(|argument| OsString::from(argument.replace("{buffer_path}", &path)))
        .collect()
}

impl crate::Engine {
    /// Run the buffer's configured external formatter and replace the buffer
    /// with what it prints. Nothing happens unless the buffer's resolved
    /// `formatter` is `external`: the caller reads the same settings and
    /// asks only then, but asking wrongly costs a sentence, not a process.
    ///
    /// The program runs in the project's directory inside the userland, as
    /// Zed runs it in the worktree's. Without a userland there is nothing to
    /// run it in, and the outcome says so.
    ///
    /// **Blocking** for as long as the formatter takes: call it off the
    /// Android main thread.
    pub fn format_buffer_externally(&self, buffer: BufferId) -> FormatOutcome {
        let settings = self.buffer_language_settings(buffer);
        let crate::config::Formatter::External { command, arguments } = settings.formatter
        else {
            return FormatOutcome::failed("no external formatter is configured for this file");
        };
        let Some(userland) = self.userland().filter(|userland| userland.is_installed()) else {
            return FormatOutcome::failed(format!(
                "{command} needs the Linux userland, which is not installed"
            ));
        };
        let text = match self.text(buffer) {
            Ok(text) => text,
            Err(EngineError::UnknownBuffer(_)) => {
                return FormatOutcome::failed("the buffer is gone");
            }
            Err(err) => return FormatOutcome::failed(err.to_string()),
        };
        let version_before = self.version(buffer).unwrap_or(0);
        let path = self.buffer_path(buffer);
        let workdir = path
            .as_deref()
            .and_then(|path| self.project_for_path(path))
            .and_then(|project| self.project_root(project))
            .or_else(|| path.as_deref().and_then(Path::parent).map(Path::to_path_buf));

        let mut argv = vec![OsString::from(&command)];
        argv.extend(substitute_arguments(&arguments, path.as_deref()));
        let mut guest_command = GuestCommand::new(format!("format: {command}"), argv);
        if let Some(workdir) = &workdir {
            guest_command = guest_command.workdir(workdir);
        }
        let (captured, stderr) = guest::capture_with_input(
            &userland,
            &guest_command,
            Some(text.clone().into_bytes()),
            FORMAT_TIMEOUT,
        );
        let output = match captured {
            Captured::Output(output) => output,
            Captured::TimedOut => {
                return FormatOutcome::failed(format!(
                    "{command} did not finish within {} seconds",
                    FORMAT_TIMEOUT.as_secs()
                ));
            }
            Captured::Failed => {
                return FormatOutcome::failed(if stderr.is_empty() {
                    format!("{command} failed — is it installed in the userland?")
                } else {
                    // The last line is the sentence: rustfmt's "error:
                    // expected …", prettier's "[error] …".
                    stderr.lines().last().unwrap_or(&stderr).to_owned()
                });
            }
        };
        let formatted = match String::from_utf8(output) {
            Ok(formatted) => formatted,
            Err(_) => return FormatOutcome::failed(format!("{command} printed something that is not UTF-8")),
        };
        if formatted.is_empty() && !text.is_empty() {
            log::warn!("format: {command} printed nothing; leaving the buffer unchanged");
            return FormatOutcome {
                changed: false,
                error: None,
            };
        }
        if formatted == text {
            return FormatOutcome {
                changed: false,
                error: None,
            };
        }
        // Typed into while the formatter ran: the answer describes text the
        // buffer no longer holds, and landing it would throw the keystrokes
        // away. Refuse, exactly as a stale LSP edit is refused.
        if self.version(buffer).unwrap_or(0) != version_before {
            return FormatOutcome::failed("the file changed while the formatter ran — try again");
        }
        match self.replace_text(buffer, &formatted) {
            Ok(()) => FormatOutcome {
                changed: true,
                error: None,
            },
            Err(err) => FormatOutcome::failed(err.to_string()),
        }
    }
}

/// Zed's `remove_trailing_whitespace_on_save`: every line loses the spaces
/// and tabs after its last visible character. A pure function so the rule can
/// be tested without a buffer; the buffer text is always LF-normalised by the
/// time it gets here (`encoding.rs`), so a line is a `\n`-delimited slice.
pub fn without_trailing_whitespace(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut lines = text.split('\n').peekable();
    while let Some(line) = lines.next() {
        out.push_str(line.trim_end_matches([' ', '\t']));
        if lines.peek().is_some() {
            out.push('\n');
        }
    }
    out
}

/// Zed's `ensure_final_newline_on_save`, quoted from its own comment
/// (assets/settings/default.json:1518-1519): "Removes any lines containing
/// only whitespace at the end of the file and ensures just one newline at the
/// end." An empty buffer stays empty — a file with nothing in it does not
/// earn a byte on save.
pub fn with_final_newline(text: &str) -> String {
    let trimmed = text.trim_end_matches([' ', '\t', '\n', '\r']);
    if trimmed.is_empty() {
        return String::new();
    }
    let mut out = String::with_capacity(trimmed.len() + 1);
    out.push_str(trimmed);
    out.push('\n');
    out
}

impl crate::Engine {
    /// The two whitespace rules a save applies before the write — Zed's
    /// `remove_trailing_whitespace_on_save` and `ensure_final_newline_on_save`
    /// (both default true, assets/settings/default.json:1511, 1520), run
    /// against the buffer's *resolved* settings so a language or a project may
    /// turn either off. Returns whether the buffer changed.
    ///
    /// Lands as one undoable edit over only the part that differs
    /// ([`Self::replace_text`]), so the caret of the editor resyncing
    /// afterwards has somewhere to stand — the same contract the external
    /// formatter above keeps. Runs *after* the formatter, as Zed runs it: a
    /// formatter that reintroduces a trailing space should not win.
    pub fn clean_buffer_on_save(&self, buffer: BufferId) -> bool {
        let settings = self.buffer_language_settings(buffer);
        if !settings.remove_trailing_whitespace_on_save && !settings.ensure_final_newline_on_save {
            return false;
        }
        let Ok(text) = self.text(buffer) else {
            return false;
        };
        let mut cleaned = text.clone();
        if settings.remove_trailing_whitespace_on_save {
            cleaned = without_trailing_whitespace(&cleaned);
        }
        if settings.ensure_final_newline_on_save {
            cleaned = with_final_newline(&cleaned);
        }
        if cleaned == text {
            return false;
        }
        self.replace_text(buffer, &cleaned).is_ok()
    }
}

#[cfg(test)]
mod whitespace_tests {
    use super::*;

    #[test]
    fn trailing_spaces_and_tabs_go_and_the_text_stays() {
        assert_eq!(
            without_trailing_whitespace("a  \n\tb\t\t\n  \nc"),
            "a\n\tb\n\nc"
        );
    }

    #[test]
    fn indentation_survives_and_a_blank_file_stays_blank() {
        assert_eq!(without_trailing_whitespace("    x"), "    x");
        assert_eq!(without_trailing_whitespace(""), "");
    }

    #[test]
    fn a_final_newline_is_added_once_and_blank_tails_are_dropped() {
        assert_eq!(with_final_newline("a"), "a\n");
        assert_eq!(with_final_newline("a\n"), "a\n");
        assert_eq!(with_final_newline("a\n\n\n"), "a\n");
        assert_eq!(with_final_newline("a\n   \n\t\n"), "a\n");
    }

    #[test]
    fn an_empty_buffer_earns_no_newline() {
        assert_eq!(with_final_newline(""), "");
        assert_eq!(with_final_newline("\n\n"), "");
    }

    /// Both rules are on by default, so a save cleans without being asked.
    #[test]
    fn the_defaults_clean_a_buffer_on_save() {
        let engine = crate::Engine::new();
        let buffer = engine.create_buffer("fn main() {}   \n\n\n");
        assert!(engine.clean_buffer_on_save(buffer));
        assert_eq!(engine.text(buffer).unwrap(), "fn main() {}\n");
        // Idempotent: a second save has nothing left to do.
        assert!(!engine.clean_buffer_on_save(buffer));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn buffer_path_is_substituted_into_arguments_and_untitled_without_one() {
        let args = vec![
            "--stdin-filepath".to_owned(),
            "{buffer_path}".to_owned(),
            "--x={buffer_path}".to_owned(),
        ];
        let with = substitute_arguments(&args, Some(Path::new("/p/a.rs")));
        assert_eq!(
            with,
            ["--stdin-filepath", "/p/a.rs", "--x=/p/a.rs"].map(OsString::from)
        );
        let without = substitute_arguments(&args, None);
        assert_eq!(without[1], OsString::from("Untitled"));
    }

    /// Without a userland there is nothing to run the program in; the
    /// answer is a sentence, never a panic and never a silent no-op.
    #[test]
    fn no_userland_is_a_sentence() {
        let engine = crate::Engine::new();
        let buffer = engine.create_buffer("fn main(){}");
        let outcome = engine.format_buffer_externally(buffer);
        assert!(!outcome.changed);
        assert!(outcome.error.is_some());
    }
}

impl crate::Engine {
    /// Replace the whole buffer with `new_text`, as one undoable edit over
    /// only the part that differs — the common head and tail are left
    /// alone, so a formatter that touched one line moves one line, and the
    /// caret of an editor resyncing afterwards has somewhere to stand.
    pub(crate) fn replace_text(&self, buffer: BufferId, new_text: &str) -> Result<(), EngineError> {
        let old_text = self.text(buffer)?;
        let old = old_text.as_bytes();
        let new = new_text.as_bytes();
        let mut prefix = old.iter().zip(new).take_while(|(a, b)| a == b).count();
        // Back off to a character boundary in both, or the edit would split
        // a multi-byte character the two texts share the head of.
        while prefix > 0
            && (!old_text.is_char_boundary(prefix) || !new_text.is_char_boundary(prefix))
        {
            prefix -= 1;
        }
        let mut suffix = old[prefix..]
            .iter()
            .rev()
            .zip(new[prefix..].iter().rev())
            .take_while(|(a, b)| a == b)
            .count();
        while suffix > 0
            && (!old_text.is_char_boundary(old.len() - suffix)
                || !new_text.is_char_boundary(new.len() - suffix))
        {
            suffix -= 1;
        }
        let replacement = &new_text[prefix..new.len() - suffix];
        self.finalize_history(buffer);
        self.edit(buffer, prefix, old.len() - suffix, replacement)?;
        self.finalize_history(buffer);
        Ok(())
    }

    /// Close the open undo transaction, so the replacement above is its own
    /// undo step rather than part of the typing before it. Shared with the
    /// conflict resolution in `git_conflict.rs`, which brackets its one edit
    /// the same way.
    pub(crate) fn finalize_history(&self, buffer: BufferId) {
        if let Ok(state) = self.buffer(buffer) {
            state.lock().unwrap().buffer.finalize_last_transaction();
        }
    }
}

#[cfg(test)]
mod replace_tests {
    use crate::Engine;

    #[test]
    fn only_the_differing_middle_is_edited_and_it_is_one_undo_step() {
        let engine = Engine::new();
        let buffer = engine.create_buffer("fn main() {\n    let x=1;\n}\n");
        let before = engine.version(buffer).unwrap();
        engine
            .replace_text(buffer, "fn main() {\n    let x = 1;\n}\n")
            .unwrap();
        assert_eq!(
            engine.text(buffer).unwrap(),
            "fn main() {\n    let x = 1;\n}\n"
        );
        // One edit, one version bump.
        assert_eq!(engine.version(buffer).unwrap(), before + 1);
        // And one undo puts it back whole.
        engine.undo(buffer).unwrap();
        assert_eq!(engine.text(buffer).unwrap(), "fn main() {\n    let x=1;\n}\n");
    }

    #[test]
    fn multibyte_heads_and_tails_stay_on_boundaries() {
        let engine = Engine::new();
        let buffer = engine.create_buffer("héllo wörld");
        engine.replace_text(buffer, "héllo  wörld").unwrap();
        assert_eq!(engine.text(buffer).unwrap(), "héllo  wörld");
        engine.replace_text(buffer, "").unwrap();
        assert_eq!(engine.text(buffer).unwrap(), "");
        engine.replace_text(buffer, "é").unwrap();
        assert_eq!(engine.text(buffer).unwrap(), "é");
    }
}
