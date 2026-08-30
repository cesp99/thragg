//! Elicitations: the questions an agent asks that are not permissions.
//!
//! `session/request_permission` covers exactly one question — "may I do
//! this?" — and everything else an agent needs to ask comes through
//! `elicitation/create`: an API token, which of three branches to work on, a
//! confirmation with a comment box, or "open this URL and sign in". Zed
//! advertises both modes and serves both (agent_servers/src/acp.rs:742-793);
//! without them an agent that needs a login has no way to ask for one and
//! simply fails.
//!
//! Two modes, and the difference matters:
//!
//! **Form.** A JSON-schema-ish description of fields, which the client
//! renders and returns filled in. The schema is deliberately narrow —
//! strings (optionally with a fixed set of choices), numbers, integers,
//! booleans and string arrays — so it is a form, not a programming language.
//! This module flattens it into the shape the panel draws directly, rather
//! than handing Kotlin the schema to interpret: the alternative is a second
//! implementation of the same rules on the other side of JNI, and those two
//! drift.
//!
//! **URL.** "Go here and come back." The answer is sent as soon as the user
//! says they have — but the card stays, because the agent is *watching* for
//! the login and will say when it has seen it, with an
//! `elicitation/complete` notification naming the same `elicitationId`. Zed
//! does the same (acp_thread.rs:515-527): only an accepted URL elicitation
//! can be completed, and completing it is what takes it off the screen.
//!
//! Like a parked permission, a pending elicitation holds a [`Responder`] and
//! is answered from a JNI thread long after its handler returned. Everything
//! that ends a question — the user answering, the session closing, the agent
//! going away — must answer it, because an elicitation nobody answers is an
//! agent that waits for ever.

use std::collections::BTreeMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use agent_client_protocol::Responder;
use agent_client_protocol::schema::v1 as acp;

/// One question waiting on the user.
pub(crate) struct PendingElicitation {
    /// Our own id, which is what the panel answers by.
    pub(crate) id: String,
    /// The session it belongs to, when it belongs to one.
    ///
    /// `None` is the protocol's *request* scope: a question about a request
    /// we made rather than about a conversation — an `authenticate` that
    /// needs a token, most usually. It has no thread of its own, so it is
    /// shown in whichever thread is open: the agent is waiting on it either
    /// way, and hiding it would strand the agent behind a question the user
    /// never saw.
    pub(crate) session: Option<u64>,
    /// The `elicitationId` of a URL-mode question — what an
    /// `elicitation/complete` names. Absent for form mode, which has no id
    /// and needs none: it is over when it is answered.
    protocol_id: Option<String>,
    /// The question, in the shape the panel draws.
    json: serde_json::Value,
    /// Taken by whoever answers first.
    responder: Mutex<Option<Responder<acp::CreateElicitationResponse>>>,
    /// A URL question the user has said they did. The response has gone; the
    /// card stays until the agent confirms.
    accepted: AtomicBool,
}

impl PendingElicitation {
    /// Answer the agent, once. Later calls are no-ops — a question already
    /// answered has no responder left, and responding twice is a protocol
    /// error rather than a second chance.
    fn answer(&self, action: acp::ElicitationAction) -> bool {
        let Some(responder) = self.responder.lock().unwrap().take() else {
            return false;
        };
        let _ = responder.respond(acp::CreateElicitationResponse::new(action));
        true
    }

    /// Close it with an error rather than an answer — for a question the
    /// *agent* withdrew with `$/cancel_request`. `Cancel` would be a claim
    /// about what the user did, and the user did nothing.
    fn refuse(&self, error: acp::Error) {
        if let Some(responder) = self.responder.lock().unwrap().take() {
            let _ = responder.respond_with_error(error);
        }
    }

    fn view(&self) -> serde_json::Value {
        let mut value = self.json.clone();
        if let Some(object) = value.as_object_mut() {
            object.insert(
                "accepted".to_owned(),
                self.accepted.load(Ordering::Acquire).into(),
            );
        }
        value
    }
}

/// Every question one agent connection has open.
#[derive(Default)]
pub(crate) struct Elicitations {
    next: AtomicU64,
    live: Mutex<Vec<Arc<PendingElicitation>>>,
    /// Moves whenever what a reader would see changes: a question opening,
    /// being answered, being withdrawn or completed — including a URL
    /// question's `accepted` flag flipping, which changes the view without
    /// changing the set. The panel polls this one integer at 8 Hz; without it
    /// every tick serialized the whole list to find out nothing had happened.
    version: AtomicU64,
}

impl Elicitations {
    /// The change counter — see the field. Never moves on a pure read.
    pub(crate) fn version(&self) -> u64 {
        self.version.load(Ordering::Acquire)
    }

    fn bump(&self) {
        self.version.fetch_add(1, Ordering::Release);
    }
    /// Record a new question, or refuse one we cannot render.
    ///
    /// Refusing is the honest answer for a mode we do not understand: the
    /// protocol's own instruction for an unknown mode is that a client "MUST
    /// NOT render it as a known elicitation mode", and an error lets the
    /// agent fall back to something it can ask another way.
    pub(crate) fn open(
        &self,
        request: acp::CreateElicitationRequest,
        session: Option<u64>,
        responder: Responder<acp::CreateElicitationResponse>,
    ) -> Result<Arc<PendingElicitation>, acp::Error> {
        let id = format!("elicit-{}", self.next.fetch_add(1, Ordering::Relaxed) + 1);
        let tool_call = match &request.mode {
            acp::ElicitationMode::Form(mode) => scope_tool_call(&mode.scope),
            acp::ElicitationMode::Url(mode) => scope_tool_call(&mode.scope),
            _ => None,
        };
        let (json, protocol_id) = match &request.mode {
            acp::ElicitationMode::Form(mode) => (
                serde_json::json!({
                    "id": id,
                    "mode": "form",
                    "message": request.message,
                    "toolCallId": tool_call,
                    "title": mode.requested_schema.title,
                    "description": mode.requested_schema.description,
                    "fields": form_fields(&mode.requested_schema),
                }),
                None,
            ),
            acp::ElicitationMode::Url(mode) => {
                // Zed validates the same two things, for the same reason: a
                // client that will open a link on the user's behalf must not
                // open `file:` or a scheme-less string
                // (acp_thread.rs:446-453).
                let url = url::Url::parse(&mode.url)
                    .map_err(|_| acp::Error::invalid_params().data("invalid elicitation URL"))?;
                if !matches!(url.scheme(), "http" | "https") || url.host_str().is_none() {
                    return Err(acp::Error::invalid_params()
                        .data("elicitation URL must use HTTP or HTTPS and include a host"));
                }
                (
                    serde_json::json!({
                        "id": id,
                        "mode": "url",
                        "message": request.message,
                        "toolCallId": tool_call,
                        "url": mode.url,
                    }),
                    Some(mode.elicitation_id.0.to_string()),
                )
            }
            _ => {
                return Err(acp::Error::invalid_params().data("unsupported elicitation mode"));
            }
        };

        let pending = Arc::new(PendingElicitation {
            id,
            session,
            protocol_id,
            json,
            responder: Mutex::new(Some(responder)),
            accepted: AtomicBool::new(false),
        });
        self.live.lock().unwrap().push(pending.clone());
        self.bump();
        Ok(pending)
    }

    /// Every question that belongs to no session — the protocol's *request*
    /// scope, raised about a request we made rather than about a
    /// conversation.
    ///
    /// Reachable without a session id on purpose. An agent can ask one before
    /// any session exists (an `authenticate` that wants a token is the
    /// ordinary case), and until now there was no way to see or answer it:
    /// the only reader took a session argument, so the agent blocked for ever
    /// with nothing on screen.
    pub(crate) fn connection_level(&self) -> Vec<serde_json::Value> {
        self.live
            .lock()
            .unwrap()
            .iter()
            .filter(|pending| pending.session.is_none())
            .map(|pending| pending.view())
            .collect()
    }

    /// The questions a given session's panel should show: its own, plus the
    /// connection-level ones.
    pub(crate) fn for_session(&self, session: u64) -> Vec<serde_json::Value> {
        self.live
            .lock()
            .unwrap()
            .iter()
            .filter(|pending| pending.session.is_none() || pending.session == Some(session))
            .map(|pending| pending.view())
            .collect()
    }

    /// The user's answer. `action_json` is
    /// `{"action": "accept", "content": {...}}`, or `{"action": "decline"}`
    /// / `{"action": "cancel"}`.
    pub(crate) fn respond(&self, id: &str, action_json: &str) -> bool {
        let value: serde_json::Value = match serde_json::from_str(action_json) {
            Ok(value) => value,
            Err(_) => return false,
        };
        let Some(pending) = self.find(id) else {
            return false;
        };
        let action = match value.get("action").and_then(|action| action.as_str()) {
            Some("accept") => acp::ElicitationAction::Accept(
                acp::ElicitationAcceptAction::new().content(content_map(value.get("content"))),
            ),
            Some("decline") => acp::ElicitationAction::Decline,
            Some("cancel") => acp::ElicitationAction::Cancel,
            _ => return false,
        };
        let accepted = matches!(action, acp::ElicitationAction::Accept(_));
        if !pending.answer(action) {
            return false;
        }
        // A URL question stays on screen after it is accepted: the user has
        // said they signed in, and the agent is the one who can confirm it
        // actually happened. Everything else is over the moment it is
        // answered.
        if accepted && pending.protocol_id.is_some() {
            pending.accepted.store(true, Ordering::Release);
        } else {
            self.remove(id);
        }
        // Both arms changed what a reader sees — the accepted card is drawn
        // differently, not just kept.
        self.bump();
        true
    }

    /// The agent withdrew the question with `$/cancel_request`.
    ///
    /// The card goes and the request is closed as cancelled. Without this a
    /// withdrawn question stays on screen for ever and the user's answer
    /// would go to a request that is no longer live — Zed watches the same
    /// marker (agent_servers/src/acp.rs:4996-5010).
    pub(crate) fn withdraw(&self, id: &str) -> bool {
        let mut live = self.live.lock().unwrap();
        let Some(index) = live.iter().position(|pending| pending.id == id) else {
            return false;
        };
        let pending = live.remove(index);
        drop(live);
        self.bump();
        pending.refuse(acp::Error::request_cancelled());
        true
    }

    /// `elicitation/complete`: the agent has seen what it was waiting for.
    /// Only an accepted URL question can complete, which is the protocol's
    /// own rule and Zed's (acp_thread.rs:515-527).
    pub(crate) fn complete(&self, protocol_id: &str) -> bool {
        let mut live = self.live.lock().unwrap();
        let Some(index) = live.iter().position(|pending| {
            pending.protocol_id.as_deref() == Some(protocol_id)
                && pending.accepted.load(Ordering::Acquire)
        }) else {
            return false;
        };
        live.remove(index);
        drop(live);
        self.bump();
        true
    }

    /// Every question belonging to a session that is going away, answered
    /// `cancel`. The spec's requirement, and the only honest answer.
    pub(crate) fn cancel_session(&self, session: u64) {
        let doomed: Vec<Arc<PendingElicitation>> = {
            let mut live = self.live.lock().unwrap();
            let (doomed, kept) = live
                .drain(..)
                .partition(|pending| pending.session == Some(session));
            *live = kept;
            doomed
        };
        if !doomed.is_empty() {
            self.bump();
        }
        for pending in doomed {
            pending.answer(acp::ElicitationAction::Cancel);
        }
    }

    /// Everything, for a connection that is going away.
    pub(crate) fn cancel_all(&self) {
        let doomed: Vec<Arc<PendingElicitation>> = self.live.lock().unwrap().drain(..).collect();
        if !doomed.is_empty() {
            self.bump();
        }
        for pending in doomed {
            pending.answer(acp::ElicitationAction::Cancel);
        }
    }

    fn find(&self, id: &str) -> Option<Arc<PendingElicitation>> {
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

/// The tool call a session-scoped question belongs to, when it names one —
/// so the panel can put the question beside the call that raised it.
fn scope_tool_call(scope: &acp::ElicitationScope) -> Option<String> {
    match scope {
        acp::ElicitationScope::Session(scope) => {
            scope.tool_call_id.as_ref().map(|id| id.0.to_string())
        }
        _ => None,
    }
}

/// The schema, flattened into one field per property, each carrying only what
/// a form needs to draw and validate it.
///
/// Field order is the property *names*' order, because that is all there is:
/// the schema's `properties` is a `BTreeMap` and JSON objects have no order,
/// so an agent cannot express "ask this one first" and a client cannot honour
/// it. Alphabetical is at least stable between renders.
fn form_fields(schema: &acp::ElicitationSchema) -> Vec<serde_json::Value> {
    let required = schema.required.clone().unwrap_or_default();
    schema
        .properties
        .iter()
        .map(|(key, property)| {
            let mut field = field_json(property);
            if let Some(object) = field.as_object_mut() {
                object.insert("key".to_owned(), key.clone().into());
                object.insert("required".to_owned(), required.contains(key).into());
            }
            field
        })
        .collect()
}

fn field_json(property: &acp::ElicitationPropertySchema) -> serde_json::Value {
    match property {
        acp::ElicitationPropertySchema::String(schema) => serde_json::json!({
            "type": "string",
            "title": schema.title,
            "description": schema.description,
            "default": schema.default,
            "minLength": schema.min_length,
            "maxLength": schema.max_length,
            "pattern": schema.pattern,
            // `email` / `uri` / `date` / `date-time`, which are the four the
            // schema has. They change the keyboard the field asks for, so
            // they travel.
            "format": schema.format.as_ref().and_then(format_name),
            "options": string_options(schema),
        }),
        acp::ElicitationPropertySchema::Number(schema) => serde_json::json!({
            "type": "number",
            "title": schema.title,
            "description": schema.description,
            "default": schema.default,
            "minimum": schema.minimum,
            "maximum": schema.maximum,
        }),
        acp::ElicitationPropertySchema::Integer(schema) => serde_json::json!({
            "type": "integer",
            "title": schema.title,
            "description": schema.description,
            "default": schema.default,
            "minimum": schema.minimum,
            "maximum": schema.maximum,
        }),
        acp::ElicitationPropertySchema::Boolean(schema) => serde_json::json!({
            "type": "boolean",
            "title": schema.title,
            "description": schema.description,
            "default": schema.default,
        }),
        acp::ElicitationPropertySchema::Array(schema) => serde_json::json!({
            "type": "array",
            "title": schema.title,
            "description": schema.description,
            "default": schema.default,
            "minItems": schema.min_items,
            "maxItems": schema.max_items,
            "options": multi_select_options(&schema.items),
        }),
        // A property kind this build does not know. It is still a *row*, so
        // the form says what it cannot ask rather than silently dropping a
        // field the agent may require — the same rule the transcript's
        // unknown entries follow.
        _ => serde_json::json!({ "type": "unsupported" }),
    }
}

/// A string's fixed choices, from either spelling the schema allows: bare
/// `enum` values, or `oneOf` with a title per value.
fn string_options(schema: &acp::StringPropertySchema) -> Option<Vec<serde_json::Value>> {
    if let Some(options) = &schema.one_of {
        return Some(
            options
                .iter()
                .map(|option| {
                    serde_json::json!({
                        "value": option.value,
                        "title": option.title,
                        "description": option.description,
                    })
                })
                .collect(),
        );
    }
    schema.enum_values.as_ref().map(|values| {
        values
            .iter()
            .map(|value| serde_json::json!({ "value": value, "title": value }))
            .collect()
    })
}

fn multi_select_options(items: &acp::MultiSelectItems) -> Vec<serde_json::Value> {
    match items {
        acp::MultiSelectItems::String(items) => items
            .values
            .iter()
            .map(|value| serde_json::json!({ "value": value, "title": value }))
            .collect(),
        acp::MultiSelectItems::Titled(items) => items
            .options
            .iter()
            .map(|option| {
                serde_json::json!({
                    "value": option.value,
                    "title": option.title,
                    "description": option.description,
                })
            })
            .collect(),
        _ => Vec::new(),
    }
}

fn format_name(format: &acp::StringFormat) -> Option<String> {
    let name = serde_json::to_value(format).ok()?;
    name.as_str().map(|name| name.to_owned())
}

/// The filled-in form, from the JSON the panel sends back.
///
/// The value's JSON type decides the protocol variant, which is exactly what
/// the schema's own types are: a field the panel drew as a switch comes back
/// as a bool and a number field as a number. Anything else is dropped rather
/// than stringified — an agent that asked for an integer and is handed
/// `"3"` has been lied to.
fn content_map(
    content: Option<&serde_json::Value>,
) -> BTreeMap<String, acp::ElicitationContentValue> {
    let mut map = BTreeMap::new();
    let Some(object) = content.and_then(|content| content.as_object()) else {
        return map;
    };
    for (key, value) in object {
        let converted = match value {
            serde_json::Value::String(text) => {
                Some(acp::ElicitationContentValue::String(text.clone()))
            }
            serde_json::Value::Bool(flag) => Some(acp::ElicitationContentValue::Boolean(*flag)),
            serde_json::Value::Number(number) => number
                .as_i64()
                .map(acp::ElicitationContentValue::Integer)
                .or_else(|| number.as_f64().map(acp::ElicitationContentValue::Number)),
            serde_json::Value::Array(items) => {
                let strings: Option<Vec<String>> = items
                    .iter()
                    .map(|item| item.as_str().map(|item| item.to_owned()))
                    .collect();
                strings.map(acp::ElicitationContentValue::StringArray)
            }
            _ => None,
        };
        if let Some(converted) = converted {
            map.insert(key.clone(), converted);
        }
    }
    map
}

#[cfg(test)]
mod tests {
    use super::*;

    fn schema_json(value: serde_json::Value) -> acp::ElicitationSchema {
        serde_json::from_value(value).expect("a schema")
    }

    #[test]
    fn a_form_becomes_fields_carrying_what_a_form_needs() {
        let schema = schema_json(serde_json::json!({
            "type": "object",
            "title": "Sign in",
            "required": ["token"],
            "properties": {
                "token": {
                    "type": "string",
                    "title": "API token",
                    "format": "uri",
                    "minLength": 8,
                },
                "branch": {
                    "type": "string",
                    "oneOf": [
                        {"const": "main", "title": "main"},
                        {"const": "dev", "title": "development"},
                    ],
                },
                "count": {"type": "integer", "minimum": 1, "maximum": 9, "default": 3},
                "dry": {"type": "boolean", "default": true},
            },
        }));
        let fields = form_fields(&schema);
        let by_key: BTreeMap<&str, &serde_json::Value> = fields
            .iter()
            .map(|field| (field["key"].as_str().unwrap(), field))
            .collect();

        assert_eq!(by_key["token"]["type"], "string");
        assert_eq!(by_key["token"]["required"], true);
        assert_eq!(by_key["token"]["format"], "uri");
        assert_eq!(by_key["token"]["minLength"], 8);
        // Not required, and the panel must be able to tell.
        assert_eq!(by_key["branch"]["required"], false);
        assert_eq!(by_key["branch"]["options"][1]["value"], "dev");
        assert_eq!(by_key["branch"]["options"][1]["title"], "development");
        assert_eq!(by_key["count"]["type"], "integer");
        assert_eq!(by_key["count"]["default"], 3);
        assert_eq!(by_key["dry"]["default"], true);
    }

    /// The answer's JSON types are the protocol's types; an integer field
    /// answered with a string would be a lie the agent cannot detect.
    #[test]
    fn the_answer_keeps_each_values_own_type() {
        let content = serde_json::json!({
            "token": "abc",
            "count": 3,
            "ratio": 1.5,
            "dry": true,
            "tags": ["a", "b"],
            "nested": {"no": "thanks"},
        });
        let map = content_map(Some(&content));
        assert!(matches!(
            map["token"],
            acp::ElicitationContentValue::String(_)
        ));
        assert!(matches!(
            map["count"],
            acp::ElicitationContentValue::Integer(3)
        ));
        assert!(matches!(
            map["ratio"],
            acp::ElicitationContentValue::Number(_)
        ));
        assert!(matches!(
            map["dry"],
            acp::ElicitationContentValue::Boolean(true)
        ));
        assert!(matches!(
            map["tags"],
            acp::ElicitationContentValue::StringArray(_)
        ));
        assert!(
            !map.contains_key("nested"),
            "a shape the protocol has no variant for is dropped, not coerced"
        );
    }
}
