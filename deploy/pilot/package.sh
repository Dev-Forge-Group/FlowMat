#!/usr/bin/env bash
# Builds the pilot release bundle from this checkout (docs/operations/pilot-deployment.md §2): the application jar, the
# built screens, compose.yaml, Caddyfile, env.example and the backup scripts, laid out as in the repository so the
# scripts find each other. Run it where the code is built and checked (a cloud session or CI), not on the server.
#
#   deploy/pilot/package.sh            -> flowmat_backend/build/pilot/flowmat-pilot-<commit>.tar.gz
# Settings (environment): OUT_DIR (flowmat_backend/build/pilot), SKIP_BUILD=1 to reuse the built jar and dist/.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT_DIR=${OUT_DIR:-$ROOT/flowmat_backend/build/pilot}
COMMIT=$(git -C "$ROOT" rev-parse --short=12 HEAD)
fail() { printf 'FAILED: %s\n' "$*" >&2; exit 1; }

if [ -n "$(git -C "$ROOT" status --porcelain)" ]; then
  fail "the checkout has uncommitted changes; a release must be one commit"
fi

if [ "${SKIP_BUILD:-0}" != 1 ]; then
  (cd "$ROOT/flowmat_backend" && ./gradlew bootJar -x test -q)
  (cd "$ROOT/flowmat_frontend" && npm ci --no-audit --no-fund >/dev/null && npm run build >/dev/null)
fi

JAR=$(ls "$ROOT"/flowmat_backend/build/libs/*.jar | grep -v -- '-plain\.jar$' | head -1)
[ -f "$JAR" ] || fail "no application jar in flowmat_backend/build/libs"
[ -f "$ROOT/flowmat_frontend/dist/index.html" ] || fail "no built screens in flowmat_frontend/dist"

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
BUNDLE="$STAGE/flowmat-pilot"
mkdir -p "$BUNDLE/deploy/pilot/release" "$BUNDLE/scripts/ops"
cp "$ROOT"/deploy/pilot/{compose.yaml,Caddyfile,env.example} "$BUNDLE/deploy/pilot/"
cp "$JAR" "$BUNDLE/deploy/pilot/release/app.jar"
cp -R "$ROOT/flowmat_frontend/dist" "$BUNDLE/deploy/pilot/release/web"
cp "$ROOT"/scripts/ops/{daily-backup.sh,restore-fingerprint.sql} "$BUNDLE/scripts/ops/"
printf '%s\n' "$(git -C "$ROOT" rev-parse HEAD)" > "$BUNDLE/deploy/pilot/release/COMMIT"

mkdir -p "$OUT_DIR"
ARCHIVE="$OUT_DIR/flowmat-pilot-$COMMIT.tar.gz"
tar -czf "$ARCHIVE" -C "$STAGE" flowmat-pilot
sha256sum "$ARCHIVE" | tee "$ARCHIVE.sha256"
