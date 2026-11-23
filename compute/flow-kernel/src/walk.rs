//! Trying a flow: a graph run from start to end as the server makes it (`FlowRunService`, `FlowRunStepService`,
//! `FlowRunRouter`, `FlowRunJoinOutput`), with every step a person would report completed with what it received. A report
//! the server would refuse leaves its step running; a gateway that finds no route fails the run. Time is left out: when a
//! step may start changes nothing here. Java's real runs are the answers this must match (`fixtures/walk.json`).

use std::collections::{HashMap, HashSet};

use serde_json::{Map, Value as Json, json};

use crate::decimal::Decimal;
use crate::routing::{Decision, MAX_PASSES, Refusal, RunGraph, with_quantity};

#[derive(Debug, Clone, PartialEq, Eq)]
enum Status {
    Planned,
    /// Started; its report was refused (or a failed run caught it running).
    Running,
    Completed,
    Failed,
    Cancelled,
}

#[derive(Debug, Clone)]
struct Step {
    node: String,
    status: Status,
    source: Option<usize>,
    connection: Option<String>,
    input: Json,
    output: Option<Json>,
    error: Option<(String, String)>,
    refusal: Option<Refusal>,
}

#[derive(Debug, Clone)]
struct Event {
    step: usize,
    kind: &'static str,
    payload: Json,
}

/// Why routing ended the run.
#[derive(Debug, Clone)]
struct Failure {
    code: &'static str,
    message: String,
}

#[derive(Debug, Clone, Default)]
struct State {
    steps: Vec<Step>,
    /// Each Join step's arrivals in order: the connection and the step it came from.
    arrivals: HashMap<usize, Vec<(String, usize)>>,
    events: Vec<Event>,
}

/// The run of a published graph with one input, as `fixtures/walk.json` records the server's: its status (finished,
/// failed, or running where a report was refused or a Join still waits), each step and the losses, filtered connections
/// and gateway routes on the way.
pub fn walk(runtime: &RunGraph, input: &Json) -> Json {
    let mut state = State::default();
    for root in runtime.roots() {
        state.steps.push(Step {
            node: root.clone(),
            status: Status::Planned,
            source: None,
            connection: None,
            input: input.clone(),
            output: None,
            error: None,
            refusal: None,
        });
    }
    let mut failure = None;
    while failure.is_none() {
        let Some(next) = state
            .steps
            .iter()
            .position(|step| step.status == Status::Planned && !runtime.is_join(&step.node))
        else {
            break;
        };
        state.steps[next].status = Status::Running;
        // A refused report changes nothing but leaves its step started.
        let started = state.clone();
        let output = state.steps[next].input.clone();
        match complete(runtime, &mut state, next, output) {
            Err(refusal) => {
                state = started;
                state.steps[next].refusal = Some(refusal);
            }
            Ok(found) => failure = found,
        }
    }
    if let Some(failure) = &failure {
        stop(&mut state, failure);
    }
    answer(&state, failure.as_ref())
}

/// A person's report: the step's output goes along its connections, gateways on the way pass at once, and Joins that
/// nothing more can reach go on.
fn complete(runtime: &RunGraph, state: &mut State, step: usize, output: Json) -> Result<Option<Failure>, Refusal> {
    let decisions = runtime.preview(&state.steps[step].node, &output)?;
    state.steps[step].status = Status::Completed;
    state.steps[step].output = Some(output.clone());
    if let Some(failure) = follow(runtime, state, step, &decisions, &output)? {
        return Ok(Some(failure));
    }
    settle(runtime, state)
}

fn follow(
    runtime: &RunGraph,
    state: &mut State,
    source: usize,
    decisions: &[Decision],
    output: &Json,
) -> Result<Option<Failure>, Refusal> {
    for decision in decisions {
        if decision.will_route
            && runtime.on_loop(&decision.target_node_id)
            && state
                .steps
                .iter()
                .filter(|step| step.node == decision.target_node_id)
                .count()
                >= MAX_PASSES
        {
            // The loop's pass limit (ADR-045 decision 3): nothing goes on, and what would have is kept on record.
            let input = match &decision.quantity {
                None => output.clone(),
                Some(quantity) => with_quantity(output, quantity),
            };
            let mut payload = json!({"connectionId": decision.connection_id, "targetNodeId": decision.target_node_id,
                "reason": "loop_limit"});
            if let Some(quantity) = input.get("quantity") {
                payload["quantity"] = quantity.clone();
            }
            event(state, source, "connection_filtered", payload);
            continue;
        }
        if let Some(loss) = &decision.loss {
            let mut payload = json!({
                "connectionId": decision.connection_id,
                "targetNodeId": decision.target_node_id,
                "sent": loss.sent.to_json(),
                "lossRate": loss.rate.to_json(),
                "lost": loss.lost.to_json(),
                "arrived": loss.arrived.to_json(),
            });
            if let Some(unit) = output.get("unit").and_then(Json::as_str) {
                payload["unit"] = json!(unit);
            }
            event(state, source, "transfer_loss", payload);
        }
        if !decision.will_route {
            let reason = decision.reason.unwrap_or("condition_false");
            let payload = json!({"connectionId": decision.connection_id, "targetNodeId": decision.target_node_id,
                "reason": reason});
            event(state, source, "connection_filtered", payload);
            continue;
        }
        if let Some(reason) = decision.reason {
            let mut payload = json!({"connectionId": decision.connection_id, "targetNodeId": decision.target_node_id,
                "reason": reason});
            if reason == "split" {
                let quantity = decision
                    .loss
                    .as_ref()
                    .map(|loss| loss.sent.clone())
                    .or(decision.quantity.clone());
                payload["quantity"] = quantity.map_or(Json::Null, |quantity| quantity.to_json());
            }
            event(state, source, "gateway_routed", payload);
        }
        if runtime.is_join(&decision.target_node_id) {
            arrive(runtime, state, source, decision)?;
            continue;
        }
        let input = match &decision.quantity {
            None => output.clone(),
            Some(quantity) => with_quantity(output, quantity),
        };
        state.steps.push(Step {
            node: decision.target_node_id.clone(),
            status: Status::Planned,
            source: Some(source),
            connection: Some(decision.connection_id.clone()),
            input,
            output: None,
            error: None,
            refusal: None,
        });
        let created = state.steps.len() - 1;
        if (runtime.is_exclusive(&decision.target_node_id) || runtime.is_split(&decision.target_node_id))
            && let Some(failure) = pass(runtime, state, created)?
        {
            return Ok(Some(failure));
        }
    }
    Ok(None)
}

/// An Exclusive or Split gateway, passed at once by the system with what it received.
fn pass(runtime: &RunGraph, state: &mut State, step: usize) -> Result<Option<Failure>, Refusal> {
    let node = state.steps[step].node.clone();
    let input = state.steps[step].input.clone();
    let split = runtime.is_split(&node);
    let decisions = runtime.preview(&node, &input)?;
    let found = decisions
        .iter()
        .any(|decision| decision.will_route || decision.reason == Some("loss_empty"));
    if !split && !found {
        let failure = Failure {
            code: "NO_ROUTE",
            message: format!(
                "Exclusive gateway {node} found no connection whose condition holds and has no default connection."
            ),
        };
        state.steps[step].status = Status::Failed;
        state.steps[step].error = Some((failure.code.into(), failure.message.clone()));
        for decision in &decisions {
            let payload = json!({"connectionId": decision.connection_id, "targetNodeId": decision.target_node_id,
                "reason": decision.reason});
            event(state, step, "connection_filtered", payload);
        }
        return Ok(Some(failure));
    }
    state.steps[step].status = Status::Completed;
    state.steps[step].output = Some(input.clone());
    follow(runtime, state, step, &decisions, &input)
}

/// One branch reaching a Join: its step is made on the first arrival and waits.
fn arrive(runtime: &RunGraph, state: &mut State, source: usize, decision: &Decision) -> Result<(), Refusal> {
    let join = match state.steps.iter().position(|step| step.node == decision.target_node_id) {
        Some(join) if state.steps[join].status == Status::Planned => join,
        Some(_) => {
            return Err(Refusal {
                code: "INTERNAL",
                message: format!("Join {} has already been passed.", decision.target_node_id),
            });
        }
        None => {
            state.steps.push(Step {
                node: decision.target_node_id.clone(),
                status: Status::Planned,
                source: Some(source),
                connection: Some(decision.connection_id.clone()),
                input: json!({}),
                output: None,
                error: None,
                refusal: None,
            });
            state.steps.len() - 1
        }
    };
    state
        .arrivals
        .entry(join)
        .or_default()
        .push((decision.connection_id.clone(), source));
    state.steps[join].input = merged(runtime, state, join)?;
    Ok(())
}

/// Passes every waiting Join that no planned or running step can still reach.
fn settle(runtime: &RunGraph, state: &mut State) -> Result<Option<Failure>, Refusal> {
    loop {
        let ready = (0..state.steps.len()).find(|&join| {
            let step = &state.steps[join];
            step.status == Status::Planned
                && runtime.is_join(&step.node)
                && runtime.still_coming(&step.node, &live_nodes(state, join)) == 0
        });
        let Some(join) = ready else { return Ok(None) };
        let input = merged(runtime, state, join)?;
        state.steps[join].input = input.clone();
        let decisions = runtime.preview(&state.steps[join].node, &input)?;
        state.steps[join].status = Status::Completed;
        state.steps[join].output = Some(input.clone());
        if let Some(failure) = follow(runtime, state, join, &decisions, &input)? {
            return Ok(Some(failure));
        }
    }
}

/// The nodes of the planned and running steps but `except`: the steps that can still route.
fn live_nodes(state: &State, except: usize) -> Vec<String> {
    let nodes: HashSet<&str> = state
        .steps
        .iter()
        .enumerate()
        .filter(|(index, step)| *index != except && matches!(step.status, Status::Planned | Status::Running))
        .map(|(_, step)| step.node.as_str())
        .collect();
    nodes.into_iter().map(str::to_string).collect()
}

/// A Join's input (`FlowRunJoinOutput`): each arrival as its source step sent it, and above them the summed quantity with
/// the unit when every arrival has a quantity in the same unit, and the item when every arrival names the same one.
fn merged(runtime: &RunGraph, state: &State, join: usize) -> Result<Json, Refusal> {
    let mut arrivals = Vec::new();
    let mut total = Decimal::zero();
    let mut summable = true;
    let mut first_unit = true;
    let mut unit: Option<String> = None;
    let mut item: Option<Json> = None;
    let mut same_item = true;
    for (connection, source) in state.arrivals.get(&join).map(Vec::as_slice).unwrap_or_default() {
        let output = sent(runtime, &state.steps[*source], connection)?;
        let mut arrival = Map::new();
        arrival.insert("connectionId".into(), json!(connection));
        arrival.insert("sourceStepId".into(), json!(source));
        for field in ["quantity", "unit", "item", "attrs"] {
            if let Some(value) = output.get(field).filter(|value| !value.is_null()) {
                arrival.insert(field.into(), value.clone());
            }
        }
        arrivals.push(Json::Object(arrival));
        match output.get("quantity") {
            Some(Json::Number(quantity)) => match Decimal::parse(&quantity.to_string()) {
                Some(quantity) => total = total.add(&quantity),
                None => summable = false,
            },
            _ => summable = false,
        }
        let arrival_unit = output.get("unit").filter(|value| !value.is_null()).map(as_text);
        if first_unit {
            unit = arrival_unit;
            first_unit = false;
        } else if unit != arrival_unit {
            summable = false;
        }
        match output.get("item").filter(|value| !value.is_null()) {
            None => same_item = false,
            Some(arrival_item) => match &item {
                None => item = Some(arrival_item.clone()),
                Some(item) if item != arrival_item => same_item = false,
                Some(_) => {}
            },
        }
    }
    let mut result = Map::new();
    let empty = arrivals.is_empty();
    result.insert("arrivals".into(), Json::Array(arrivals));
    if empty {
        return Ok(Json::Object(result));
    }
    if summable {
        result.insert("quantity".into(), total.to_json());
        if let Some(unit) = unit {
            result.insert("unit".into(), json!(unit));
        }
    }
    if let (true, Some(item)) = (same_item, item) {
        result.insert("item".into(), item);
    }
    Ok(Json::Object(result))
}

/// What arrived along a connection: the step's output, or its Split share, less the connection's loss.
fn sent(runtime: &RunGraph, from: &Step, connection: &str) -> Result<Json, Refusal> {
    let output = from.output.clone().unwrap_or_else(|| json!({}));
    if !runtime.quantity_changes(&from.node, connection) {
        return Ok(output);
    }
    let decisions = runtime.preview(&from.node, &output)?;
    Ok(decisions
        .iter()
        .find(|decision| decision.connection_id == connection && decision.quantity.is_some())
        .map(|decision| with_quantity(&output, decision.quantity.as_ref().expect("a quantity")))
        .unwrap_or(output))
}

/// Jackson's `asText` of a scalar.
fn as_text(value: &Json) -> String {
    match value {
        Json::String(text) => text.clone(),
        other => other.to_string(),
    }
}

fn event(state: &mut State, step: usize, kind: &'static str, payload: Json) {
    state.events.push(Event { step, kind, payload });
}

/// A failed run: its planned steps are cancelled and its running ones fail with the run's error.
fn stop(state: &mut State, failure: &Failure) {
    for step in &mut state.steps {
        match step.status {
            Status::Planned => {
                step.status = Status::Cancelled;
                step.error = Some(("RUN_FAILED".into(), failure.message.clone()));
            }
            Status::Running => {
                step.status = Status::Failed;
                step.error = Some((failure.code.into(), failure.message.clone()));
            }
            _ => {}
        }
    }
}

fn answer(state: &State, failure: Option<&Failure>) -> Json {
    let done = state.steps.iter().all(|step| step.status == Status::Completed);
    let mut answer = Map::new();
    answer.insert(
        "status".into(),
        json!(if failure.is_some() {
            "failed"
        } else if done {
            "finished"
        } else {
            "running"
        }),
    );
    if let Some(failure) = failure {
        answer.insert(
            "failure".into(),
            json!({"code": failure.code, "message": failure.message}),
        );
    }
    let steps: Vec<Json> = state
        .steps
        .iter()
        .map(|step| {
            let mut out = Map::new();
            out.insert("nodeId".into(), json!(step.node));
            let status = match (&step.refusal, &step.status) {
                (Some(_), _) => "refused",
                (None, Status::Planned) => "planned",
                (None, Status::Running) => "running",
                (None, Status::Completed) => "completed",
                (None, Status::Failed) => "failed",
                (None, Status::Cancelled) => "cancelled",
            };
            out.insert("status".into(), json!(status));
            if let Some(source) = step.source {
                out.insert("source".into(), json!(source));
            }
            if let Some(connection) = &step.connection {
                out.insert("connectionId".into(), json!(connection));
            }
            out.insert("input".into(), step.input.clone());
            if let Some(output) = &step.output {
                out.insert("output".into(), output.clone());
            }
            if let Some((code, message)) = &step.error {
                out.insert("error".into(), json!({"code": code, "message": message}));
            }
            if let Some(refusal) = &step.refusal {
                out.insert(
                    "refusal".into(),
                    json!({"status": refusal.http_status(), "message": refusal.message}),
                );
            }
            Json::Object(out)
        })
        .collect();
    answer.insert("steps".into(), Json::Array(steps));
    let mut events = state.events.clone();
    let connection = |event: &Event| event.payload["connectionId"].as_str().unwrap_or_default().to_string();
    events.sort_by(|a, b| {
        a.step
            .cmp(&b.step)
            .then_with(|| a.kind.cmp(b.kind))
            .then_with(|| connection(a).cmp(&connection(b)))
    });
    answer.insert(
        "events".into(),
        Json::Array(
            events
                .into_iter()
                .map(|event| json!({"step": event.step, "type": event.kind, "payload": event.payload}))
                .collect(),
        ),
    );
    Json::Object(answer)
}
