#!/usr/bin/env bash
# Condition language timing comparison (docs/operations/performance-baseline.md §6). Java (the backend's
# ConditionExpression, in a test JVM) and Rust (compute/condition-expression, release build) each first check that they
# answer every fixture case as recorded, then compile every source of
# compute/condition-expression/fixtures/generated.json and evaluate every compiled one against every snapshot, round
# after round. The two reports are printed side by side and kept in flowmat_backend/build/condition-bench/.
#
# Run from the repository root:  scripts/perf/condition-bench.sh
# Needs JDK 21, Node and a Rust toolchain (rustup picks the version in compute/condition-expression/rust-toolchain.toml).
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$ROOT/flowmat_backend/build/condition-bench"
mkdir -p "$OUT"

printf '== Java\n'
(cd "$ROOT/flowmat_backend" && ./gradlew test --rerun -q -Dcondition.bench=true \
  --tests '*ConditionExpressionFixtureTest' --tests '*ConditionExpressionBenchmarkTest')

printf '== Rust\n'
(cd "$ROOT/compute/condition-expression" && cargo test --release -q --test fixtures \
  && cargo run --release -q --example bench > "$OUT/rust.json")

node - "$OUT/java.json" "$OUT/rust.json" <<'NODE'
const { readFileSync } = require('node:fs')
const [java, rust] = process.argv.slice(2).map((file) => JSON.parse(readFileSync(file, 'utf8')))
if (java.trueAnswers !== rust.trueAnswers || java.compiled !== rust.compiled) {
  console.error('The two did not do the same work:', java, rust)
  process.exit(1)
}
const rows = [
  ['compile, ns per source', 'compileNanosPerSource'],
  ['evaluate, ns per evaluation', 'evaluateNanosPerEvaluation'],
  ['compile, bytes allocated per source', 'compileBytesPerSource'],
  ['evaluate, bytes allocated per evaluation', 'evaluateBytesPerEvaluation'],
  ['peak resident set, MB', 'peakResidentBytes', (value) => value / 1024 / 1024],
]
console.log(`\n${java.sources} sources, ${java.compiled} compile, ${java.evaluations} evaluations, ${java.trueAnswers} true\n`)
console.log('| | Java | Rust | Rust / Java |\n|---|---|---|---|')
for (const [label, key, scale = (value) => value] of rows) {
  const [left, right] = [scale(java[key] ?? NaN), scale(rust[key] ?? NaN)]
  const ratio = left > 0 ? (right / left).toFixed(2) : '-'
  console.log(`| ${label} | ${left.toFixed(1)} | ${right.toFixed(1)} | ${ratio} |`)
}
console.log(`\n${java.implementation}; ${rust.implementation}`)
NODE
