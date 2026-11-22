//! FlowMat's flow kernel: the meaning of a workflow in one place (docs/architecture/adr/ADR-040-flow-kernel.md). It
//! checks a draft as the backend's `WorkflowValidationService` does and routes a published revision as `FlowRunGraph`
//! does, with the same answers: Java writes them into `fixtures/`, and the tests here must match every one.
//!
//! Input is a graph `{processes, ports, connections}` in the server's response shape, the JSON the editor holds.

pub mod decimal;
pub mod graph;
pub mod ports;
pub mod routing;
pub mod schedule;
pub mod schema;
pub mod time;
pub mod validation;
pub mod walk;
#[cfg(target_arch = "wasm32")]
mod wasm;

pub use graph::Graph;
pub use routing::{Decision, Refusal, RunGraph};
pub use schedule::{Team, schedule};
pub use time::DateTime;
pub use validation::{Units, validate};
pub use walk::walk;

/// The published revision a graph would be: the parts routing reads, under the names the revision uses.
pub fn published(graph: &serde_json::Value, schema_version: i64) -> serde_json::Value {
    let part = |name: &str| graph.get(name).cloned().unwrap_or(serde_json::Value::Null);
    serde_json::json!({
        "schemaVersion": schema_version,
        "processes": part("processes"),
        "processIos": part("ports"),
        "connections": part("connections"),
    })
}

/// Trying the flow (`walk`), and with `time` `{start, stretches, equipmentId, crew}` also when each step is ready and
/// scheduled under `times` (docs/architecture/adr/ADR-041-try-it-time.md). A time that cannot be read gives `timeError`.
pub fn try_flow(runtime: &RunGraph, input: &serde_json::Value, time: Option<&serde_json::Value>) -> serde_json::Value {
    let mut answer = walk(runtime, input);
    let Some(time) = time.filter(|time| !time.is_null()) else {
        return answer;
    };
    let start = time
        .get("start")
        .and_then(serde_json::Value::as_str)
        .and_then(DateTime::parse);
    match (start, Team::from_json(time)) {
        (Some(start), Some(team)) => answer["times"] = schedule(runtime, &answer, start, &team)["steps"].clone(),
        _ => answer["timeError"] = serde_json::json!("start and stretches are times such as 2026-12-21T09:00:00+09:00"),
    }
    answer
}
