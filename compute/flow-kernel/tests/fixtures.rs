//! The kernel against Java's answers in `fixtures/`: every validation issue and every routing decision must be the same.

use std::fs;

use flowmat_flow_kernel::{Graph, RunGraph, Units, published, validate};
use serde_json::{Value as Json, json};

fn read(name: &str) -> Json {
    let path = format!("{}/fixtures/{name}", env!("CARGO_MANIFEST_DIR"));
    serde_json::from_str(&fs::read_to_string(&path).unwrap_or_else(|error| panic!("{path}: {error}")))
        .unwrap_or_else(|error| panic!("{path}: {error}"))
}

fn cases(file: &Json) -> &Vec<Json> {
    file["cases"].as_array().expect("cases")
}

#[test]
fn validation_matches_java() {
    let file = read("validation.json");
    let units = Units::from_json(&file["context"]["knownUnits"]);
    let mut failures = Vec::new();
    for case in cases(&file) {
        let answer = validate(&Graph::from_json(&case["graph"]), &units);
        if answer != case["expected"] {
            failures.push(format!(
                "{}:\n  java: {}\n  rust: {}",
                case["id"], case["expected"], answer
            ));
        }
    }
    assert!(
        failures.is_empty(),
        "{} of {} graphs differ:\n{}",
        failures.len(),
        cases(&file).len(),
        failures.join("\n")
    );
}

#[test]
fn routing_matches_java() {
    let file = read("routing.json");
    let version = file["context"]["schemaVersion"].as_i64().expect("schemaVersion");
    let mut failures = Vec::new();
    for case in cases(&file) {
        let answer = route(&case["graph"], &case["probes"], version);
        if answer != case["expected"] {
            failures.push(format!(
                "{}:\n  java: {}\n  rust: {}",
                case["id"], case["expected"], answer
            ));
        }
    }
    assert!(
        failures.is_empty(),
        "{} of {} graphs differ:\n{}",
        failures.len(),
        cases(&file).len(),
        failures.join("\n")
    );
}

/// What FlowKernelRouteFixtureTest records for a graph and its probes.
fn route(graph: &Json, probes: &Json, version: i64) -> Json {
    let runtime = match RunGraph::read(&published(graph, version)) {
        Ok(runtime) => runtime,
        Err(refusal) => return json!({"error": refusal.to_json()}),
    };
    let mut delays = serde_json::Map::new();
    for connection in graph["connections"].as_array().into_iter().flatten() {
        let id = connection["connectionId"].as_str().unwrap_or_default();
        let seconds = runtime.delay_seconds(id);
        if seconds.signum() != 0 {
            delays.insert(
                id.into(),
                json!({"seconds": seconds.to_json(), "working": runtime.working_delay(id)}),
            );
        }
    }
    let mut joins = serde_json::Map::new();
    for process in graph["processes"].as_array().into_iter().flatten() {
        let id = process["processId"].as_str().unwrap_or_default();
        if runtime.is_join(id) {
            joins.insert(id.into(), json!(runtime.still_coming(id, runtime.roots())));
        }
    }
    let answers: Vec<Json> = probes
        .as_array()
        .into_iter()
        .flatten()
        .map(
            |probe| match runtime.preview(probe["nodeId"].as_str().unwrap_or_default(), &probe["output"]) {
                Ok(decisions) => {
                    json!({"decisions": decisions.iter().map(|decision| decision.to_json()).collect::<Vec<_>>()})
                }
                Err(refusal) => json!({"error": refusal.to_json()}),
            },
        )
        .collect();
    json!({"roots": runtime.roots(), "delays": delays, "joins": joins, "probes": answers})
}
