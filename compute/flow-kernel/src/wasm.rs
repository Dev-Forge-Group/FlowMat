//! The WebAssembly face, with no binding generator, as the condition language's: the host writes UTF-8 JSON into memory
//! it got from `alloc` (exported by the condition crate this module links in) and reads JSON back, freeing both with
//! `dealloc`. `wasm/check-fixtures.mjs` shows the calls.

use serde_json::{Value as Json, json};

use crate::{Graph, RunGraph, Units, published, validate as check, walk as try_flow};

/// The check's answer for `{graph, knownUnits}`: `{errors, warnings, issues}` as the server sends it, or
/// `{"requestError": "…"}` when the request is not JSON.
///
/// # Safety
/// `request` points at `length` bytes written by the host.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn validate(request: *const u8, length: usize) -> u64 {
    // SAFETY: the caller gives memory it wrote.
    let Some(request) = (unsafe { parse(request, length) }) else {
        return result(json!({"requestError": "the request is not JSON"}));
    };
    result(check(
        &Graph::from_json(&request["graph"]),
        &Units::from_json(&request["knownUnits"]),
    ))
}

/// What routing makes of `{graph, probes: [{nodeId, output}], schemaVersion}`: `{error}` when the revision cannot be
/// read, else `{roots, delays: {connectionId: {seconds, working}}, joins: {joinId: stillComing}, probes: [{decisions} |
/// {error}]}`, as `fixtures/routing.json` records it.
///
/// # Safety
/// `request` points at `length` bytes written by the host.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn route(request: *const u8, length: usize) -> u64 {
    // SAFETY: the caller gives memory it wrote.
    let Some(request) = (unsafe { parse(request, length) }) else {
        return result(json!({"requestError": "the request is not JSON"}));
    };
    let version = request["schemaVersion"].as_i64().unwrap_or(3);
    let runtime = match RunGraph::read(&published(&request["graph"], version)) {
        Ok(runtime) => runtime,
        Err(refusal) => return result(json!({"error": refusal.to_json()})),
    };
    let probes: Vec<Json> = request["probes"]
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
    let mut delays = serde_json::Map::new();
    for connection in request["graph"]["connections"].as_array().into_iter().flatten() {
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
    for process in request["graph"]["processes"].as_array().into_iter().flatten() {
        let id = process["processId"].as_str().unwrap_or_default();
        if runtime.is_join(id) {
            joins.insert(id.into(), json!(runtime.still_coming(id, runtime.roots())));
        }
    }
    result(json!({"roots": runtime.roots(), "delays": delays, "joins": joins, "probes": probes}))
}

/// Trying the flow, `{graph, input, schemaVersion}`: `{error}` when the graph cannot run as a published revision, else
/// the run from start to end as `fixtures/walk.json` records the server's (status, failure, steps, events).
///
/// # Safety
/// `request` points at `length` bytes written by the host.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn walk(request: *const u8, length: usize) -> u64 {
    // SAFETY: the caller gives memory it wrote.
    let Some(request) = (unsafe { parse(request, length) }) else {
        return result(json!({"requestError": "the request is not JSON"}));
    };
    let version = request["schemaVersion"].as_i64().unwrap_or(3);
    match RunGraph::read(&published(&request["graph"], version)) {
        Ok(runtime) => result(try_flow(&runtime, &request["input"])),
        Err(refusal) => result(json!({"error": refusal.to_json()})),
    }
}

/// # Safety
/// `pointer` points at `length` readable bytes.
unsafe fn parse(pointer: *const u8, length: usize) -> Option<Json> {
    if length == 0 {
        return None;
    }
    // SAFETY: as the caller promises.
    serde_json::from_slice(unsafe { std::slice::from_raw_parts(pointer, length) }).ok()
}

/// The JSON text's pointer in the high 32 bits and its length in the low 32. The host frees it with `dealloc`.
fn result(report: Json) -> u64 {
    let bytes = report.to_string().into_bytes().into_boxed_slice();
    let length = bytes.len();
    let pointer = Box::into_raw(bytes) as *mut u8;
    ((pointer as u64) << 32) | length as u64
}
