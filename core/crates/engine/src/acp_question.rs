//! Parked `_spettro/question/ask` requests — Spettro's own ask-user form.
//!
//! The standard's way of asking the user something that is not a permission
//! is `elicitation/create`, which [`crate::acp_elicit`] serves. Spettro can
//! speak that, but it prefers its own extension when the client advertises
//! it: one request carrying *every* question of a form at once, with
//! per-question option lists and an optional free-text answer. The difference
//! is not cosmetic. Without the extension the same form arrives as a walk of
//! one-question-at-a-time permission prompts — legal, answerable, and a much
//! worse thing to hand somebody on a phone.
//!
//! What advertises it is the top-level `_meta` on `initialize`
//! (`acp.rs::agent_main`); what serves it is the trailing untyped request
//! handler in `run_connection`; and what parks it is this module.
//!
//! It is modelled on `acp_elicit.rs` line for line, and for the same reasons:
//! a [`Responder`] is consumed by answering, the answer arrives on a JNI
//! thread long after the handler returned, and the panel polls one integer
//! rather than serializing the list at 8 Hz. The one deliberate difference is
//! that **nothing here is modelled in Rust**. The payload crosses JNI
//! verbatim and Kotlin reads `version`, `sessionId`, `question`, `context`,
//! `options[]`, `allowCustomInput` and `questions[]` out of it; the answer
//! comes back as the finished JSON-RPC *result*, also built in Kotlin. An
//! extension whose shape is the agent's to change is not a shape to
//! re-declare in two languages and keep in step.
//!
//! Everything that ends a question — the user answering, the session closing,
//! the agent going away — must answer it, because a question nobody answers
//! is an agent that waits for ever.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use agent_client_protocol::Responder;

/// One Spettro form waiting on the user.
pub(crate) struct PendingQuestion {
    /// Our own id, which is what the panel answers by.
    pub(crate) id: String,
    /// The session it belongs to, when `params.sessionId` named one we have
    /// indexed. `None` otherwise — and it is still shown, in whichever thread
    /// is open, because the agent is blocked on it either way and a question
    /// the user never sees is a turn that never ends.
    pub(crate) session: Option<u64>,
    /// The request's `params`, forwarded verbatim. See the module doc.
    params: serde_json::Value,
    /// Taken by whoever answers first.
    responder: Mutex<Option<Responder<serde_json::Value>>>,
}

impl PendingQuestion {
    /// Answer the agent, once. Later calls are no-ops — a question already
    /// answered has no responder left, and responding twice is a protocol
    /// error rather than a second chance.
    fn answer(&self, result: serde_json::Value) -> bool {
        let Some(responder) = self.responder.lock().unwrap().take() else {
            return false;
        };
        let _ = responder.respond(result);
        true
    }

    fn view(&self) -> serde_json::Value {
        serde_json::json!({
            "id": self.id,
            "session": self.session,
            "payload": self.params,
        })
    }
}

/// Every Spettro question one agent connection has open.
#[derive(Default)]
pub(crate) struct Questions {
    next: AtomicU64,
    live: Mutex<Vec<Arc<PendingQuestion>>>,
    /// Moves whenever what a reader would see changes: a question opening,
    /// being answered, being cancelled. The panel polls this one integer,
    /// exactly as it polls `acpElicitationsVersion`; the questions of a live
    /// session also ride that session's own revision, so the standalone
    /// counter is there for the session-less case.
    version: AtomicU64,
}

impl Questions {
    /// The change counter — see the field. Never moves on a pure read.
    pub(crate) fn version(&self) -> u64 {
        self.version.load(Ordering::Acquire)
    }

    fn bump(&self) {
        self.version.fetch_add(1, Ordering::Release);
    }

    /// Park a question. `session` is resolved from `params.sessionId` when the
    /// acp session id is indexed, else `None` (show it in the open thread).
    pub(crate) fn open(
        &self,
        params: serde_json::Value,
        session: Option<u64>,
        responder: Responder<serde_json::Value>,
    ) -> Arc<PendingQuestion> {
        let id = format!("question-{}", self.next.fetch_add(1, Ordering::Relaxed) + 1);
        let pending = Arc::new(PendingQuestion {
            id,
            session,
            params,
            responder: Mutex::new(Some(responder)),
        });
        self.live.lock().unwrap().push(pending.clone());
        self.bump();
        pending
    }

    /// All open questions, newest last:
    /// `[{ "id":"question-1", "session":7, "payload": <params verbatim> }]`.
    ///
    /// Not filtered here. A caller that wants one session's questions filters
    /// on `session` being null or its own id — the same rule the elicitations
    /// follow, and for the same reason: a question with no session is still
    /// blocking the agent.
    pub(crate) fn view_json(&self) -> serde_json::Value {
        serde_json::Value::Array(
            self.live
                .lock()
                .unwrap()
                .iter()
                .map(|pending| pending.view())
                .collect(),
        )
    }

    /// Answer once. `answer` is the JSON-RPC **result**, built in Kotlin —
    /// `{"answers":[…]}` for a filled form, `{"kind":"declined"}` for a
    /// refusal. False for a question that is already gone.
    pub(crate) fn answer(&self, id: &str, answer: serde_json::Value) -> bool {
        let Some(pending) = self.find(id) else {
            return false;
        };
        if !pending.answer(answer) {
            return false;
        }
        self.remove(id);
        self.bump();
        true
    }

    /// Decline every question of a session that is going away.
    ///
    /// `{"kind":"cancelled"}` rather than an error: the agent asked a
    /// question, the user is no longer there to answer it, and the turn must
    /// be told so rather than stranded.
    pub(crate) fn cancel_session(&self, session: u64) {
        let doomed: Vec<Arc<PendingQuestion>> = {
            let mut live = self.live.lock().unwrap();
            let (doomed, kept) = live
                .drain(..)
                .partition(|pending| pending.session == Some(session));
            *live = kept;
            doomed
        };
        Self::decline(&doomed);
        if !doomed.is_empty() {
            self.bump();
        }
    }

    /// Everything, for a connection that is going away.
    pub(crate) fn cancel_all(&self) {
        let doomed: Vec<Arc<PendingQuestion>> = self.live.lock().unwrap().drain(..).collect();
        Self::decline(&doomed);
        if !doomed.is_empty() {
            self.bump();
        }
    }

    fn decline(doomed: &[Arc<PendingQuestion>]) {
        for pending in doomed {
            pending.answer(serde_json::json!({ "kind": "cancelled" }));
        }
    }

    fn find(&self, id: &str) -> Option<Arc<PendingQuestion>> {
        self.live
            .lock()
            .unwrap()
            .iter()
            .find(|pending| pending.id == id)
            .cloned()
    }

    fn remove(&self, id: &str) {
        self.live.lock().unwrap().retain(|pending| pending.id != id);
    }
}

// No unit tests here, deliberately: every path through this module starts
// with a [`Responder`], and the SDK offers no way to build one outside a live
// connection (`Responder::new` is private). The store is exercised end to end
// by the in-process conformance test in `acp.rs`, which is also the only place
// the registration order of the trailing untyped handler can be observed.
