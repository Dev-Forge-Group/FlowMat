#!/usr/bin/env bash
# Builds the flow kernel's WebAssembly module for the editor and copies it to
# flowmat_frontend/src/shared/wasm/flowmat_flow_kernel.wasm. The module also carries the condition language's exports
# (check, evaluate), since it links that crate in. Paths are remapped, so the same source and toolchain give the same
# bytes on any machine. With --check it copies nothing and fails when the committed module is not what this source
# builds (compute.yml runs that).
set -euo pipefail

CRATE=$(cd "$(dirname "$0")/.." && pwd)
ROOT=$(cd "$CRATE/../.." && pwd)
CONDITION="$ROOT/compute/condition-expression"
COMMITTED="$ROOT/flowmat_frontend/src/shared/wasm/flowmat_flow_kernel.wasm"
CARGO_DIR=${CARGO_HOME:-$HOME/.cargo}
export RUSTFLAGS="--remap-path-prefix=$CARGO_DIR=/cargo --remap-path-prefix=$CRATE=/flowmat-flow-kernel --remap-path-prefix=$CONDITION=/flowmat-condition"

cd "$CRATE"
cargo build --locked --release --lib --target wasm32-unknown-unknown
BUILT="$CRATE/target/wasm32-unknown-unknown/release/flowmat_flow_kernel.wasm"

if [ "${1:-}" = "--check" ]; then
  if ! cmp -s "$BUILT" "$COMMITTED"; then
    echo "flowmat_frontend/src/shared/wasm/flowmat_flow_kernel.wasm is not what this source builds." >&2
    echo "Run compute/flow-kernel/wasm/build.sh and commit the module." >&2
    exit 1
  fi
  echo "flowmat_flow_kernel.wasm matches the source ($(wc -c < "$BUILT") bytes, $(gzip -9c "$BUILT" | wc -c) gzipped)"
else
  cp "$BUILT" "$COMMITTED"
  echo "Wrote $COMMITTED ($(wc -c < "$COMMITTED") bytes, $(gzip -9c "$COMMITTED" | wc -c) gzipped)"
fi
