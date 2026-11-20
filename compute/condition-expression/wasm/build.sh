#!/usr/bin/env bash
# Builds the condition language's WebAssembly module for the editor and copies it to
# flowmat_frontend/src/shared/wasm/flowmat_condition.wasm. Paths are remapped, so the same source and toolchain give the
# same bytes on any machine. With --check it copies nothing and fails when the committed module is not what this source
# builds (compute.yml runs that).
set -euo pipefail

CRATE=$(cd "$(dirname "$0")/.." && pwd)
ROOT=$(cd "$CRATE/../.." && pwd)
COMMITTED="$ROOT/flowmat_frontend/src/shared/wasm/flowmat_condition.wasm"
CARGO_DIR=${CARGO_HOME:-$HOME/.cargo}
export RUSTFLAGS="--remap-path-prefix=$CARGO_DIR=/cargo --remap-path-prefix=$CRATE=/flowmat-condition"

cd "$CRATE"
cargo build --locked --release --lib --target wasm32-unknown-unknown
BUILT="$CRATE/target/wasm32-unknown-unknown/release/flowmat_condition.wasm"

if [ "${1:-}" = "--check" ]; then
  if ! cmp -s "$BUILT" "$COMMITTED"; then
    echo "flowmat_frontend/src/shared/wasm/flowmat_condition.wasm is not what this source builds." >&2
    echo "Run compute/condition-expression/wasm/build.sh and commit the module." >&2
    exit 1
  fi
  echo "flowmat_condition.wasm matches the source ($(wc -c < "$BUILT") bytes)"
else
  cp "$BUILT" "$COMMITTED"
  echo "Wrote $COMMITTED ($(wc -c < "$COMMITTED") bytes)"
fi
