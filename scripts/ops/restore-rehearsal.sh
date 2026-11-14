#!/usr/bin/env bash
# Backup and restore rehearsal (docs/operations/backup-restore.md). Dumps the local Compose database, restores the dump
# into a new, empty PostgreSQL 16 container, and checks that nothing was lost: the fingerprint of the restored database
# must equal the source's, the application must start on it with no migration to apply, and its API must read the same
# stock as the database holds. Touches nothing in the source database; the new containers are left for inspection
# unless CLEANUP=1.
#
# Run from the repository root:  scripts/ops/restore-rehearsal.sh
# Settings (environment): SOURCE_CONTAINER (flowmat_backend-postgres-1), TARGET_PORT (5444), REDIS_PORT_REHEARSAL (6390),
# APP_PORT (8091), WORK_DIR (flowmat_backend/build/restore-rehearsal), SKIP_BUILD=1 to reuse the built jar, CLEANUP=1.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
BACKEND="$ROOT/flowmat_backend"
SOURCE_CONTAINER=${SOURCE_CONTAINER:-flowmat_backend-postgres-1}
TARGET_CONTAINER=${TARGET_CONTAINER:-flowmat-restore-rehearsal-db}
REDIS_CONTAINER=${REDIS_CONTAINER:-flowmat-restore-rehearsal-redis}
TARGET_PORT=${TARGET_PORT:-5444}
REDIS_PORT_REHEARSAL=${REDIS_PORT_REHEARSAL:-6390}
APP_PORT=${APP_PORT:-8091}
WORK_DIR=${WORK_DIR:-$BACKEND/build/restore-rehearsal}
FINGERPRINT="$ROOT/scripts/ops/restore-fingerprint.sql"

say() { printf '\n== %s\n' "$*"; }
fail() { printf 'FAILED: %s\n' "$*" >&2; exit 1; }

# KEY=VALUE lines of flowmat_backend/.env only; nothing in the file is run as a command.
declare -A ENV
while IFS='=' read -r key value; do
  [[ $key =~ ^[A-Z][A-Z0-9_]*$ ]] && ENV[$key]=$value
done < "$BACKEND/.env"
DB=${ENV[POSTGRES_DB]:?POSTGRES_DB missing from flowmat_backend/.env}
DB_USER=${ENV[POSTGRES_USER]:?POSTGRES_USER missing from flowmat_backend/.env}
DB_PASSWORD=${ENV[POSTGRES_PASSWORD]:?POSTGRES_PASSWORD missing from flowmat_backend/.env}

mkdir -p "$WORK_DIR"
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
DUMP="$WORK_DIR/flowmat-$STAMP.dump"

say "1. Dump $SOURCE_CONTAINER/$DB"
docker exec "$SOURCE_CONTAINER" pg_dump -U "$DB_USER" -d "$DB" -Fc -Z 6 -f /tmp/rehearsal.dump
docker cp "$SOURCE_CONTAINER:/tmp/rehearsal.dump" "$DUMP" >/dev/null
docker exec "$SOURCE_CONTAINER" rm -f /tmp/rehearsal.dump
sha256sum "$DUMP" | tee "$DUMP.sha256"
docker exec -i "$SOURCE_CONTAINER" psql -U "$DB_USER" -d "$DB" -q -f - < "$FINGERPRINT" > "$WORK_DIR/source.txt"
echo "source fingerprint: $(wc -l < "$WORK_DIR/source.txt") lines, $(head -1 "$WORK_DIR/source.txt")"

say "2. Restore into an empty postgres:16 ($TARGET_CONTAINER, 127.0.0.1:$TARGET_PORT)"
docker rm -f "$TARGET_CONTAINER" "$REDIS_CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$TARGET_CONTAINER" -e POSTGRES_DB="$DB" -e POSTGRES_USER="$DB_USER" -e POSTGRES_PASSWORD="$DB_PASSWORD" \
  -p "127.0.0.1:$TARGET_PORT:5432" postgres:16 >/dev/null
docker run -d --name "$REDIS_CONTAINER" -p "127.0.0.1:$REDIS_PORT_REHEARSAL:6379" redis:7.4 >/dev/null
for _ in $(seq 60); do
  docker exec "$TARGET_CONTAINER" pg_isready -U "$DB_USER" -d "$DB" >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$TARGET_CONTAINER" pg_isready -U "$DB_USER" -d "$DB" >/dev/null || fail "the new database did not start"
# The entrypoint restarts the server once after its first setup; wait until it answers a query.
for _ in $(seq 30); do
  docker exec "$TARGET_CONTAINER" psql -U "$DB_USER" -d "$DB" -Atc 'select 1' >/dev/null 2>&1 && break
  sleep 1
done
[ "$(docker exec "$TARGET_CONTAINER" psql -U "$DB_USER" -d "$DB" -Atc "select count(*) from information_schema.tables where table_schema='public'")" = 0 ] \
  || fail "the new database is not empty"
docker cp "$DUMP" "$TARGET_CONTAINER:/tmp/rehearsal.dump" >/dev/null
docker exec "$TARGET_CONTAINER" pg_restore --exit-on-error --no-owner --role="$DB_USER" -U "$DB_USER" -d "$DB" /tmp/rehearsal.dump

say "3. Compare fingerprints"
docker exec -i "$TARGET_CONTAINER" psql -U "$DB_USER" -d "$DB" -q -f - < "$FINGERPRINT" > "$WORK_DIR/restored.txt"
diff -u "$WORK_DIR/source.txt" "$WORK_DIR/restored.txt" > "$WORK_DIR/restored.diff" \
  || fail "the restored database differs from the source; see $WORK_DIR/restored.diff"
echo "identical: $(grep -c '^rows|' "$WORK_DIR/restored.txt") tables, $(grep -c '^content|' "$WORK_DIR/restored.txt") content hashes, $(grep -c '^project|' "$WORK_DIR/restored.txt") projects"

say "4. Start the application on the restored database (port $APP_PORT)"
if [ "${SKIP_BUILD:-0}" != 1 ]; then (cd "$BACKEND" && ./gradlew bootJar -q --offline); fi
JAR=$(ls "$BACKEND"/build/libs/*-SNAPSHOT.jar 2>/dev/null | grep -v -- '-plain' | head -1)
[ -n "$JAR" ] || fail "no application jar; run without SKIP_BUILD"
APP_LOG="$WORK_DIR/app.log"
(
  for key in "${!ENV[@]}"; do export "$key=${ENV[$key]}"; done
  export DB_URL="jdbc:postgresql://localhost:$TARGET_PORT/$DB" DB_USERNAME="$DB_USER" DB_PASSWORD="$DB_PASSWORD"
  export REDIS_HOST=localhost REDIS_PORT="$REDIS_PORT_REHEARSAL" SERVER_PORT="$APP_PORT"
  exec java -jar "$JAR" --spring.profiles.active=dev
) > "$APP_LOG" 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null || true' EXIT
for _ in $(seq 120); do
  grep -qE 'Started Application|APPLICATION FAILED' "$APP_LOG" && break
  kill -0 "$APP_PID" 2>/dev/null || break
  sleep 1
done
grep -q 'Started Application' "$APP_LOG" || fail "the application did not start; see $APP_LOG"
grep -E 'Successfully validated|is up to date|Migrating schema' "$APP_LOG" | sed 's/^.* - //'
grep -q 'Migrating schema' "$APP_LOG" && fail "Flyway applied a migration to the restored database"
grep -q 'is up to date' "$APP_LOG" || fail "Flyway did not report the schema up to date"

say "5. Read the journeys through the API"
python3 - "$APP_PORT" "$WORK_DIR/restored.txt" <<'PY'
import json, sys, urllib.request
port, fingerprint = sys.argv[1], sys.argv[2]
base = f"http://127.0.0.1:{port}/api"
def call(method, path, body=None, token=None):
    request = urllib.request.Request(base + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {token}"} if token else {})})
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)["data"]
token = call("POST", "/auth/login", {"userIdOrEmail": "demo-owner", "password": "demo1234"})["accessToken"]
stock = {}
for line in open(fingerprint):
    if line.startswith("project|"):
        parts = line.strip().split("|")
        stock[parts[1]] = float(parts[2].split("=")[1])
projects = call("GET", "/projects", token=token)
journeys = [p for p in projects if p["projectName"].startswith(("Purchase warehouse live", "Production quality live"))]
if not journeys:
    sys.exit("FAILED: no journey project in the restored database; run the live journeys first")
for project in journeys:
    rows = call("GET", f"/inventories?projectId={project['projectId']}", token=token)
    total = sum(row["quantity"] for row in rows)
    expected = stock.get(project["projectId"])
    print(f"{project['projectName']}: API stock {total:g}, database {expected:g}")
    if expected is None or abs(total - expected) > 1e-9:
        sys.exit("FAILED: the API reads different stock from the database")
print(f"{len(journeys)} journey projects read the same through the API")
PY

say "6. Nothing the application did on start changed the journeys"
docker exec -i "$TARGET_CONTAINER" psql -U "$DB_USER" -d "$DB" -q -f - < "$FINGERPRINT" > "$WORK_DIR/after-start.txt"
diff <(grep -E '^(flyway|content|project)\|' "$WORK_DIR/source.txt") <(grep -E '^(flyway|content|project)\|' "$WORK_DIR/after-start.txt") \
  > "$WORK_DIR/after-start.diff" || fail "the journeys changed after the application started; see $WORK_DIR/after-start.diff"
echo "unchanged: migrations, journey content and project totals"

kill "$APP_PID" 2>/dev/null || true
if [ "${CLEANUP:-0}" = 1 ]; then
  docker rm -f "$TARGET_CONTAINER" "$REDIS_CONTAINER" >/dev/null
  echo "removed $TARGET_CONTAINER and $REDIS_CONTAINER"
fi
say "Restore rehearsal passed. Dump: $DUMP"
