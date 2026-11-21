//! FlowMat's flow kernel: the meaning of a workflow in one place (docs/architecture/adr/ADR-040-flow-kernel.md). It
//! checks a draft as the backend's `WorkflowValidationService` does and routes a published revision as `FlowRunGraph`
//! does, with the same answers: Java writes them into `fixtures/`, and the tests here must match every one.
//!
//! Input is a graph `{processes, ports, connections}` in the server's response shape, the JSON the editor holds.

pub mod decimal;
pub mod graph;
pub mod ports;
pub mod routing;
pub mod schema;
pub mod validation;
#[cfg(target_arch = "wasm32")]
mod wasm;

pub use graph::Graph;
pub use routing::{Decision, Refusal, RunGraph};
pub use validation::{Units, validate};

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
