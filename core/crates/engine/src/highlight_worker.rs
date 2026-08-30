//! Reparsing off the keystroke path.
//!
//! Tree-sitter's incremental reparse is fast in the sense that matters to a
//! desktop app and slow in the sense that matters to a phone: on a
//! 5000-line file a one-character edit costs a few milliseconds, and the IME
//! sends several edits per keystroke. Doing that inside `Engine::edit` —
//! synchronously, holding the buffer lock, on the thread the UI is waiting
//! on — is what made typing in a large file stutter.
//!
//! So edits only *shift* the tree's positions (microseconds) and mark it
//! stale; this worker does the actual parse on a thread of its own and swaps
//! the result in. Until it lands the view keeps drawing with the shifted
//! tree, which is very nearly right — the same trade Zed makes, where syntax
//! is allowed to lag a frame behind the text.
//!
//! One thread, not a pool: reparses for a buffer must not overlap, and a
//! single worker keeps its parser warm across jobs.

use std::collections::HashSet;
use std::sync::mpsc::{Receiver, Sender, TryRecvError, channel};
use std::thread;

use tree_sitter::Parser;

use crate::{BufferId, Buffers};

/// Queue a buffer for reparsing; sending the same id twice before the worker
/// gets to it is harmless.
pub(crate) struct HighlightWorker {
    requests: Sender<BufferId>,
}

impl HighlightWorker {
    pub fn new(buffers: Buffers) -> HighlightWorker {
        let (requests, incoming) = channel();
        thread::Builder::new()
            .name("seeker-highlight".to_owned())
            .spawn(move || run(buffers, incoming))
            .expect("failed to spawn the highlight worker");
        HighlightWorker { requests }
    }

    pub fn request(&self, id: BufferId) {
        // A closed channel means the worker died, which only happens at
        // teardown; dropping the request is then correct.
        let _ = self.requests.send(id);
    }
}

fn run(buffers: Buffers, incoming: Receiver<BufferId>) {
    let mut parser = Parser::new();
    let mut pending: HashSet<BufferId> = HashSet::new();

    loop {
        // Block for the first id, then drain whatever else is queued so a
        // burst of keystrokes collapses into one parse per buffer.
        let Ok(first) = incoming.recv() else { return };
        pending.insert(first);
        loop {
            match incoming.try_recv() {
                Ok(id) => {
                    pending.insert(id);
                }
                Err(TryRecvError::Empty) => break,
                Err(TryRecvError::Disconnected) => return,
            }
        }

        for id in pending.drain().collect::<Vec<_>>() {
            reparse(&buffers, &mut parser, id);
        }
    }
}

/// Parse one buffer, holding its lock only to take a snapshot and to install
/// the result — and the map's read lock only long enough to find it, so a
/// parse in flight here never blocks the UI reading any buffer.
fn reparse(buffers: &Buffers, parser: &mut Parser, id: BufferId) {
    // Bounded so a buffer being typed into quickly can't spin here forever;
    // whatever is left over is picked up by the next request, which the next
    // edit will send anyway.
    for _ in 0..4 {
        // Re-fetched each round so a buffer closed mid-parse is noticed.
        let Some(buffer) = buffers.read().unwrap().get(&id).cloned() else {
            return;
        };
        let Some((language, old_tree, rope, version)) = ({
            let state = buffer.lock().unwrap();
            state.highlight.as_ref().and_then(|highlight| {
                if !highlight.is_dirty() {
                    return None;
                }
                let (language, old_tree) = highlight.parse_inputs();
                Some((
                    language,
                    old_tree,
                    state.buffer.as_rope().clone(),
                    state.version,
                ))
            })
        }) else {
            return;
        };

        let Some(tree) =
            crate::highlight::HighlightState::parse(parser, language, &rope, old_tree.as_ref())
        else {
            return;
        };
        // The injected layers are derived here, not in `install`: deriving
        // them runs one parse per fenced block, and `install` is called with
        // the buffer lock held.
        let layers = crate::highlight::parse_injections(parser, &rope, language, &tree);

        let mut state = buffer.lock().unwrap();
        if state.version != version {
            // The buffer moved while we parsed. The tree we just built is for
            // older text, so throw it away and go round again rather than
            // installing spans that don't line up.
            continue;
        }
        if let Some(highlight) = &mut state.highlight {
            highlight.install(tree, layers);
        }
        return;
    }
}
