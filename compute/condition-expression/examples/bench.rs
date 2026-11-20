//! The Rust half of the condition language's timing comparison; `ConditionExpressionBenchmarkTest` in the backend is the
//! Java half, and `scripts/perf/condition-bench.sh` runs both. The same work: compile every source of
//! `fixtures/generated.json`, evaluate every compiled one against every snapshot, round after round. Prints the median
//! round and the bytes allocated per operation as JSON. Run with `cargo run --release --example bench`.

use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Instant;

use flowmat_condition::{Condition, Values};
use serde_json::Value as Json;

const WARM_UP_ROUNDS: usize = 30;
const MEASURED_ROUNDS: usize = 30;

/// Counts the bytes asked for, as the JVM's per-thread allocation counter does.
struct Counting;

static ALLOCATED: AtomicU64 = AtomicU64::new(0);

unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        ALLOCATED.fetch_add(layout.size() as u64, Ordering::Relaxed);
        // SAFETY: the same layout goes to the system allocator.
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
        // SAFETY: the pointer came from `alloc` with this layout.
        unsafe { System.dealloc(pointer, layout) }
    }

    unsafe fn realloc(&self, pointer: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        ALLOCATED.fetch_add(size as u64, Ordering::Relaxed);
        // SAFETY: as for `alloc`.
        unsafe { System.realloc(pointer, layout, size) }
    }
}

#[global_allocator]
static GLOBAL: Counting = Counting;

fn compile_all(sources: &[String]) -> Vec<Condition> {
    sources
        .iter()
        .filter_map(|source| Condition::compile(source).ok())
        .collect()
}

fn evaluate_all(compiled: &[Condition], snapshots: &[Values]) -> usize {
    compiled
        .iter()
        .map(|condition| snapshots.iter().filter(|values| condition.evaluate(values)).count())
        .sum()
}

fn median(mut values: Vec<f64>) -> f64 {
    values.sort_by(f64::total_cmp);
    let middle = values.len() / 2;
    if values.len() % 2 == 1 {
        values[middle]
    } else {
        (values[middle - 1] + values[middle]) / 2.0
    }
}

/// The process's peak resident set (Linux `VmHWM`), or null elsewhere.
fn peak_resident_bytes() -> Option<u64> {
    let status = std::fs::read_to_string("/proc/self/status").ok()?;
    let line = status.lines().find(|line| line.starts_with("VmHWM:"))?;
    let kilobytes: u64 = line.split_whitespace().nth(1)?.parse().ok()?;
    Some(kilobytes * 1024)
}

fn main() {
    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/fixtures/generated.json");
    let corpus: Json = serde_json::from_str(&std::fs::read_to_string(path).expect("generated.json")).expect("JSON");
    let sources: Vec<String> = corpus["cases"]
        .as_array()
        .unwrap()
        .iter()
        .map(|entry| entry["source"].as_str().unwrap().to_owned())
        .collect();
    let snapshots: Vec<Values> = corpus["snapshots"]
        .as_array()
        .unwrap()
        .iter()
        .map(Values::from_snapshot)
        .collect();
    let compiled = compile_all(&sources);
    let expected_true = evaluate_all(&compiled, &snapshots);
    let evaluations = compiled.len() * snapshots.len();

    for _ in 0..WARM_UP_ROUNDS {
        std::hint::black_box(compile_all(std::hint::black_box(&sources)));
        assert_eq!(evaluate_all(std::hint::black_box(&compiled), &snapshots), expected_true);
    }
    let (mut compile_nanos, mut evaluate_nanos) = (Vec::new(), Vec::new());
    let (mut compile_bytes, mut evaluate_bytes) = (0u64, 0u64);
    for _ in 0..MEASURED_ROUNDS {
        let allocated = ALLOCATED.load(Ordering::Relaxed);
        let started = Instant::now();
        std::hint::black_box(compile_all(std::hint::black_box(&sources)));
        compile_nanos.push(started.elapsed().as_nanos() as f64);
        compile_bytes += ALLOCATED.load(Ordering::Relaxed) - allocated;

        let allocated = ALLOCATED.load(Ordering::Relaxed);
        let started = Instant::now();
        let answered = evaluate_all(std::hint::black_box(&compiled), &snapshots);
        evaluate_nanos.push(started.elapsed().as_nanos() as f64);
        evaluate_bytes += ALLOCATED.load(Ordering::Relaxed) - allocated;
        assert_eq!(answered, expected_true);
    }

    let rounds = MEASURED_ROUNDS as u64;
    let report = serde_json::json!({
        "implementation": concat!("rust ", env!("CARGO_PKG_RUST_VERSION"), ", release build"),
        "sources": sources.len(),
        "compiled": compiled.len(),
        "evaluations": evaluations,
        "trueAnswers": expected_true,
        "compileNanosPerSource": median(compile_nanos) / sources.len() as f64,
        "evaluateNanosPerEvaluation": median(evaluate_nanos) / evaluations as f64,
        "compileBytesPerSource": compile_bytes / rounds / sources.len() as u64,
        "evaluateBytesPerEvaluation": evaluate_bytes / rounds / evaluations as u64,
        "peakResidentBytes": peak_resident_bytes(),
    });
    println!("{}", serde_json::to_string_pretty(&report).unwrap());
}
