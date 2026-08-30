//! Fuzzy file finding over a project's worktrees.
//!
//! Matching runs on Zed's `fuzzy` crate against the mirrored worktree
//! snapshot (see `project.rs`), which means the candidate list is already in
//! memory as a sum-tree and no directory is walked to answer a query.
//!
//! The work itself goes to the runtime's background executor — `fuzzy` shards
//! the candidate set across it — and the calling thread waits for the answer.
//! That makes [`Engine::find_files`] **blocking**, which is the right shape
//! for a JNI call made from a Kotlin worker: the alternative, streaming
//! partial results back, buys nothing when a query completes in single-digit
//! milliseconds.

use std::collections::HashMap;
use std::sync::Arc;
use std::sync::atomic::AtomicBool;
use std::time::Duration;

use fuzzy::{PathMatchCandidate, PathMatchCandidateSet};
use path::PathStyle;
use path::rel_path::RelPath;
use worktree::{Snapshot, Traversal};

use crate::ProjectId;

/// One fuzzy hit, ready for the UI.
#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub struct FileMatch {
    /// Path relative to *its worktree's* root, `/`-separated.
    pub path: String,
    /// Final path component, which is what the UI shows first.
    pub name: String,
    /// Offsets in `path` that matched, for highlighting. **UTF-16 code
    /// units**, not bytes: Kotlin strings are UTF-16, and handing the UI byte
    /// offsets would misplace every highlight after a non-ASCII character.
    pub positions: Vec<usize>,
    /// Higher is better. Only meaningful relative to other hits.
    pub score: f64,
    /// Which folder of the project the hit is in — `Engine::add_worktree`'s
    /// handle. The UI needs it to resolve `path` to something absolute.
    pub worktree: crate::project::WorktreeHandle,
    /// That folder's display name. Zed's file finder shows it in front of the
    /// path once a project has more than one worktree
    /// (file_finder/src/file_finder.rs, `path_prefix`); empty when there is
    /// only one, so the row reads exactly as it did before.
    pub worktree_name: String,
    /// The path to *open* the hit by — `Engine::project_entry_abs_path`'s
    /// spelling. Identical to `path` in the project's own folder.
    pub project_path: String,
}

/// Adapter letting `fuzzy` walk our mirrored snapshot. Mirrors Zed's own
/// implementation in its `project` crate, which we don't vendor.
struct SnapshotCandidates {
    snapshot: Snapshot,
    include_ignored: bool,
}

impl<'a> PathMatchCandidateSet<'a> for SnapshotCandidates {
    type Candidates = SnapshotCandidateIter<'a>;

    fn id(&self) -> usize {
        self.snapshot.id().to_usize()
    }

    fn len(&self) -> usize {
        if self.include_ignored {
            self.snapshot.file_count()
        } else {
            self.snapshot.visible_file_count()
        }
    }

    fn prefix(&self) -> Arc<RelPath> {
        if self
            .snapshot
            .root_entry()
            .is_some_and(|entry| entry.is_file())
        {
            self.snapshot.root_name().into()
        } else {
            RelPath::empty_arc()
        }
    }

    fn root_is_file(&self) -> bool {
        self.snapshot
            .root_entry()
            .is_some_and(|entry| entry.is_file())
    }

    fn path_style(&self) -> PathStyle {
        self.snapshot.path_style()
    }

    fn candidates(&'a self, start: usize) -> Self::Candidates {
        SnapshotCandidateIter {
            traversal: self.snapshot.files(self.include_ignored, start),
        }
    }
}

struct SnapshotCandidateIter<'a> {
    traversal: Traversal<'a>,
}

impl<'a> Iterator for SnapshotCandidateIter<'a> {
    type Item = PathMatchCandidate<'a>;

    fn next(&mut self) -> Option<Self::Item> {
        self.traversal.next().map(|entry| PathMatchCandidate {
            is_dir: entry.kind.is_dir(),
            path: &entry.path,
            char_bag: entry.char_bag,
        })
    }
}

/// Convert `fuzzy`'s byte offsets into UTF-16 code-unit offsets. Positions
/// arrive ascending and on character boundaries, since matching works in
/// chars.
fn to_utf16_positions(path: &str, byte_positions: &[usize]) -> Vec<usize> {
    if byte_positions.iter().all(|&position| position < path.len()) && path.is_ascii() {
        // The overwhelmingly common case: nothing to convert.
        return byte_positions.to_vec();
    }
    let mut converted = Vec::with_capacity(byte_positions.len());
    let mut remaining = byte_positions.iter().peekable();
    let mut utf16 = 0;
    for (byte, character) in path.char_indices() {
        while remaining.peek().is_some_and(|&&position| position == byte) {
            remaining.next();
            converted.push(utf16);
        }
        // Skip any position that fell inside this character rather than on
        // its boundary; it cannot be rendered and must not shift the rest.
        while remaining
            .peek()
            .is_some_and(|&&position| position < byte + character.len_utf8())
        {
            remaining.next();
        }
        utf16 += character.len_utf16();
    }
    converted
}

/// How long to wait for the runtime before giving up. Generous: a query over
/// a large project is milliseconds, so reaching this means something is
/// wedged, and returning nothing beats hanging the caller forever.
const FIND_TIMEOUT: Duration = Duration::from_secs(5);

impl crate::Engine {
    /// Fuzzy-match `query` against the project's files, best first.
    ///
    /// An empty query lists files in worktree order rather than matching
    /// nothing — that is what a file finder should show the moment it opens.
    /// Gitignored files are excluded, matching the panel.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn find_files(&self, id: ProjectId, query: &str, limit: usize) -> Vec<FileMatch> {
        let snapshots = self.project_snapshots(id);
        if snapshots.is_empty() || limit == 0 {
            return Vec::new();
        }
        // Only worth showing once there is more than one folder to tell apart
        // — Zed hides the prefix for a single-worktree project too. The
        // project's own folder is named but never prefixed, which is what
        // keeps every path in a single-folder project spelled as before.
        let named = snapshots.len() > 1;
        let names: HashMap<usize, String> = if named {
            snapshots
                .iter()
                .map(|(handle, name, _, _)| (*handle as usize, name.clone()))
                .collect()
        } else {
            HashMap::new()
        };
        let prefixes: HashMap<usize, String> = snapshots
            .iter()
            .skip(1)
            .map(|(handle, name, _, _)| (*handle as usize, format!("{name}/")))
            .collect();
        if query.is_empty() {
            // Round-robin across the folders rather than draining the first
            // one: an empty query is "show me the project", and a second
            // folder that never appears is not that.
            let mut per_tree: Vec<_> = snapshots
                .iter()
                .map(|(handle, _, _, snapshot)| (*handle, snapshot.files(false, 0)))
                .collect();
            let mut found = Vec::with_capacity(limit.min(64));
            while found.len() < limit {
                let before = found.len();
                for (handle, files) in per_tree.iter_mut() {
                    if found.len() >= limit {
                        break;
                    }
                    let Some(entry) = files.next() else { continue };
                    let path = entry.path.as_unix_str().to_owned();
                    found.push(FileMatch {
                        name: entry.path.file_name().unwrap_or_default().to_owned(),
                        positions: Vec::new(),
                        score: 0.0,
                        worktree: *handle,
                        worktree_name: names
                            .get(&(*handle as usize))
                            .cloned()
                            .unwrap_or_default(),
                        project_path: match prefixes.get(&(*handle as usize)) {
                            Some(prefix) => format!("{prefix}{path}"),
                            None => path.clone(),
                        },
                        path,
                    });
                }
                if found.len() == before {
                    break;
                }
            }
            return found;
        }

        // A query with an uppercase letter is treated as case-sensitive, the
        // convention every editor with this feature uses.
        let smart_case = query.chars().any(|c| c.is_uppercase());
        let query = query.to_owned();
        let (sender, receiver) = std::sync::mpsc::channel();

        self.runtime().spawn(move |cx| {
            let executor = cx.background_executor().clone();
            cx.background_executor()
                .spawn(async move {
                    let sets: Vec<SnapshotCandidates> = snapshots
                        .into_iter()
                        .map(|(_, _, _, snapshot)| SnapshotCandidates {
                            snapshot,
                            include_ignored: false,
                        })
                        .collect();
                    let matches = fuzzy::match_path_sets(
                        &sets,
                        &query,
                        &None,
                        smart_case,
                        limit,
                        &AtomicBool::new(false),
                        executor,
                    )
                    .await;
                    let results = matches
                        .into_iter()
                        .map(|found| {
                            let path = found.path.as_unix_str().to_owned();
                            FileMatch {
                                positions: to_utf16_positions(&path, &found.positions),
                                name: found.path.file_name().unwrap_or_default().to_owned(),
                                score: found.score,
                                worktree: found.worktree_id as u64,
                                worktree_name: names
                                    .get(&found.worktree_id)
                                    .cloned()
                                    .unwrap_or_default(),
                                project_path: match prefixes.get(&found.worktree_id) {
                                    Some(prefix) => format!("{prefix}{path}"),
                                    None => path.clone(),
                                },
                                path,
                            }
                        })
                        .collect::<Vec<_>>();
                    let _ = sender.send(results);
                })
                .detach();
        });

        receiver.recv_timeout(FIND_TIMEOUT).unwrap_or_else(|err| {
            log::warn!("find_files timed out or the runtime went away: {err}");
            Vec::new()
        })
    }
}

#[cfg(test)]
mod tests {
    use crate::Engine;
    use std::time::{Duration, Instant};

    fn project() -> (Engine, tempfile::TempDir) {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        std::fs::create_dir_all(root.join("src/parser")).unwrap();
        std::fs::create_dir_all(root.join(".git")).unwrap();
        std::fs::create_dir_all(root.join("target/debug")).unwrap();
        std::fs::write(root.join(".gitignore"), "target\n").unwrap();
        std::fs::write(root.join("Cargo.toml"), "").unwrap();
        std::fs::write(root.join("src/main.rs"), "").unwrap();
        std::fs::write(root.join("src/parser/lexer.rs"), "").unwrap();
        std::fs::write(root.join("src/parser/parser_tests.rs"), "").unwrap();
        std::fs::write(root.join("target/debug/build.log"), "").unwrap();

        let engine = Engine::new();
        let id = engine.open_project(root);
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline && !engine.project_scan_complete(id) {
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(engine.project_scan_complete(id));
        (engine, dir)
    }

    #[test]
    fn finds_files_by_fuzzy_subsequence() {
        let (engine, _dir) = project();
        let paths: Vec<String> = engine
            .find_files(1, "lexer", 10)
            .into_iter()
            .map(|found| found.path)
            .collect();
        assert_eq!(paths, vec!["src/parser/lexer.rs"]);

        // Non-contiguous subsequence, the whole point of fuzzy matching.
        let paths: Vec<String> = engine
            .find_files(1, "sprlx", 10)
            .into_iter()
            .map(|found| found.path)
            .collect();
        assert_eq!(paths, vec!["src/parser/lexer.rs"]);
    }


    /// A project with two folders is searched across both, and every hit says
    /// which folder it came from so the finder can label it — Zed shows the
    /// worktree's name in front of the path as soon as there is more than one.
    #[test]
    fn finds_files_in_every_folder() {
        let (engine, dir) = project();
        let _ = dir;
        let second = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(second.path().join("docs")).unwrap();
        std::fs::write(second.path().join("docs/lexer_notes.md"), "").unwrap();
        std::fs::write(second.path().join("README.md"), "").unwrap();
        let added = engine.add_worktree(1, second.path()).unwrap();
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline && !engine.project_scan_complete(1) {
            std::thread::sleep(Duration::from_millis(10));
        }

        let folders = engine.project_worktrees(1);
        let primary = folders[0].id;
        let second_name = folders[1].name.clone();

        let found = engine.find_files(1, "lexer", 10);
        let paths: Vec<(String, u64)> = found
            .iter()
            .map(|hit| (hit.path.clone(), hit.worktree))
            .collect();
        assert!(paths.contains(&("src/parser/lexer.rs".to_owned(), primary)));
        assert!(paths.contains(&("docs/lexer_notes.md".to_owned(), added)));
        // Both folders are named now that there is more than one.
        assert!(found.iter().all(|hit| !hit.worktree_name.is_empty()));
        assert_eq!(
            found
                .iter()
                .find(|hit| hit.worktree == added)
                .unwrap()
                .worktree_name,
            second_name
        );

        // An empty query takes from every folder rather than draining the
        // first, so a second folder is visible the moment the finder opens.
        let listed = engine.find_files(1, "", 4);
        assert_eq!(listed.len(), 4);
        assert!(listed.iter().any(|hit| hit.worktree == primary));
        assert!(listed.iter().any(|hit| hit.worktree == added));
    }

    #[test]
    fn reports_match_positions_for_highlighting() {
        let (engine, _dir) = project();
        let found = engine.find_files(1, "main", 10);
        let first = found.first().expect("a hit for main");
        assert_eq!(first.path, "src/main.rs");
        assert_eq!(first.name, "main.rs");
        // The positions must index into `path`, and spell the query.
        let matched: String = first
            .positions
            .iter()
            .map(|&index| first.path.as_bytes()[index] as char)
            .collect();
        assert_eq!(matched, "main");
    }

    #[test]
    fn positions_are_utf16_offsets() {
        use super::to_utf16_positions;
        // "é" is 2 bytes but 1 UTF-16 unit; "𝄞" is 4 bytes but 2 units.
        let path = "é/𝄞/ab";
        assert_eq!(path.len(), 2 + 1 + 4 + 1 + 2);
        // Byte offsets of 'é', '𝄞', 'a', 'b'.
        assert_eq!(to_utf16_positions(path, &[0, 3, 8, 9]), vec![0, 2, 5, 6]);
        // ASCII is passed straight through.
        assert_eq!(to_utf16_positions("src/main.rs", &[0, 4]), vec![0, 4]);
    }

    #[test]
    fn excludes_gitignored_files() {
        let (engine, _dir) = project();
        let paths: Vec<String> = engine
            .find_files(1, "build", 10)
            .into_iter()
            .map(|found| found.path)
            .collect();
        assert!(
            paths.is_empty(),
            "target/ is gitignored, so build.log must not be offered: {paths:?}"
        );
    }

    #[test]
    fn an_empty_query_lists_files() {
        let (engine, _dir) = project();
        let found = engine.find_files(1, "", 10);
        assert!(!found.is_empty());
        assert!(found.iter().all(|entry| entry.positions.is_empty()));
        // Still respects the limit, and still excludes ignored files.
        assert_eq!(engine.find_files(1, "", 2).len(), 2);
        assert!(
            !found.iter().any(|entry| entry.path.starts_with("target/")),
            "{found:?}"
        );
    }

    #[test]
    fn unknown_projects_and_zero_limits_find_nothing() {
        let (engine, _dir) = project();
        assert!(engine.find_files(999, "main", 10).is_empty());
        assert!(engine.find_files(1, "main", 0).is_empty());
    }
}
