#!/usr/bin/env bash
# Upgrade rehearsal (docs/operations/backup-restore.md §5). Restores a dump of the database to be upgraded into a new,
# empty PostgreSQL 16 container, starts this commit's application on that copy so Flyway applies the migrations the
# database does not have yet, and checks that the upgrade lost nothing: every table that existed keeps its rows and the
# stock, ledger and purchase totals per project, a second start has nothing left to apply, and the API answers. The
# database the dump came from is never touched; apply the migrations to it only after this passes.
#
# Run from the repository root with a custom-format dump (pg_dump -Fc) of the database to upgrade:
#   DUMP=/path/to/flowmat.dump scripts/ops/upgrade-rehearsal.sh
# Or let it dump a running container first:  SOURCE_CONTAINER=flowmat-session1-db scripts/ops/upgrade-rehearsal.sh
# Settings (environment): DB, DB_USER, DB_PASSWORD (default: POSTGRES_* of flowmat_backend/.env), TARGET_PORT (5445),
# REDIS_PORT_REHEARSAL (6391), APP_PORT (8092), PROFILE (dev), WORK_DIR (flowmat_backend/build/upgrade-rehearsal),
# SKIP_BUILD=1 to reuse the built jar, CLEANUP=1 to remove the containers at the end.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
BACKEND="$ROOT/flowmat_backend"
TARGET_CONTAINER=${TARGET_CONTAINER:-flowmat-upgrade-rehearsal-db}
REDIS_CONTAINER=${REDIS_CONTAINER:-flowmat-upgrade-rehearsal-redis}
TARGET_PORT=${TARGET_PORT:-5445}
REDIS_PORT_REHEARSAL=${REDIS_PORT_REHEARSAL:-6391}
APP_PORT=${APP_PORT:-8092}
PROFILE=${PROFILE:-dev}
WORK_DIR=${WORK_DIR:-$BACKEND/build/upgrade-rehearsal}
FINGERPRINT="$ROOT/scripts/ops/restore-fingerprint.sql"

say() { printf '\n== %s\n' "$*"; }
fail() { printf 'FAILED: %s\n' "$*" >&2; exit 1; }

# KEY=VALUE lines of flowmat_backend/.env only; nothing in the file is run as a command.
declare -A ENV
while IFS='=' read -r key value; do
  [[ $key =~ ^[A-Z][A-Z0-9_]*$ ]] && ENV[$key]=$value
done < "$BACKEND/.env"
DB=${DB:-${ENV[POSTGRES_DB]:?POSTGRES_DB missing from flowmat_backend/.env}}
DB_USER=${DB_USER:-${ENV[POSTGRES_USER]:?POSTGRES_USER missing from flowmat_backend/.env}}
DB_PASSWORD=${DB_PASSWORD:-${ENV[POSTGRES_PASSWORD]:?POSTGRES_PASSWORD missing from flowmat_backend/.env}}

mkdir -p "$WORK_DIR"
if [ -z "${DUMP:-}" ]; then
  [ -n "${SOURCE_CONTAINER:-}" ] || fail "give DUMP=<file> or SOURCE_CONTAINER=<container to dump>"
  DUMP="$WORK_DIR/before-$(date -u +%Y%m%dT%H%M%SZ).dump"
  say "0. Dump $SOURCE_CONTAINER/$DB"
  docker exec "$SOURCE_CONTAINER" pg_dump -U "$DB_USER" -d "$DB" -Fc -Z 6 -f /tmp/upgrade-rehearsal.dump
  docker cp "$SOURCE_CONTAINER:/tmp/upgrade-rehearsal.dump" "$DUMP" >/dev/null
  docker exec "$SOURCE_CONTAINER" rm -f /tmp/upgrade-rehearsal.dump
fi
[ -f "$DUMP" ] || fail "no dump at $DUMP"
sha256sum "$DUMP"

psql_target() { docker exec -i "$TARGET_CONTAINER" psql -U "$DB_USER" -d "$DB" "$@"; }

say "1. Restore the dump into an empty postgres:16 ($TARGET_CONTAINER, 127.0.0.1:$TARGET_PORT)"
docker rm -f "$TARGET_CONTAINER" "$REDIS_CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$TARGET_CONTAINER" -e POSTGRES_DB="$DB" -e POSTGRES_USER="$DB_USER" -e POSTGRES_PASSWORD="$DB_PASSWORD" \
  -p "127.0.0.1:$TARGET_PORT:5432" postgres:16 >/dev/null
docker run -d --name "$REDIS_CONTAINER" -p "127.0.0.1:$REDIS_PORT_REHEARSAL:6379" redis:7.4 >/dev/null
for _ in $(seq 60); do
  psql_target -Atc 'select 1' >/dev/null 2>&1 && docker exec "$TARGET_CONTAINER" pg_isready -U "$DB_USER" -d "$DB" >/dev/null 2>&1 && break
  sleep 1
done
sleep 2
psql_target -Atc 'select 1' >/dev/null || fail "the new database did not start"
docker cp "$DUMP" "$TARGET_CONTAINER:/tmp/upgrade-rehearsal.dump" >/dev/null
docker exec "$TARGET_CONTAINER" pg_restore --exit-on-error --no-owner --role="$DB_USER" -U "$DB_USER" -d "$DB" /tmp/upgrade-rehearsal.dump
psql_target -q -f - < "$FINGERPRINT" > "$WORK_DIR/before.txt"
BEFORE_VERSION=$(psql_target -Atc "select max(version::int) from flyway_schema_history where success and version is not null")
echo "before: V$BEFORE_VERSION, $(grep -c '^rows|' "$WORK_DIR/before.txt") tables, $(grep -c '^project|' "$WORK_DIR/before.txt") projects"

if [ "${SKIP_BUILD:-0}" != 1 ]; then (cd "$BACKEND" && ./gradlew bootJar -q --offline); fi
JAR=$(ls "$BACKEND"/build/libs/*-SNAPSHOT.jar 2>/dev/null | grep -v -- '-plain' | head -1)
[ -n "$JAR" ] || fail "no application jar; run without SKIP_BUILD"

APP_PID=
start_app() {  # $1: log file
  (
    for key in "${!ENV[@]}"; do export "$key=${ENV[$key]}"; done
    export DB_URL="jdbc:postgresql://localhost:$TARGET_PORT/$DB" DB_USERNAME="$DB_USER" DB_PASSWORD="$DB_PASSWORD"
    export REDIS_HOST=localhost REDIS_PORT="$REDIS_PORT_REHEARSAL" SERVER_PORT="$APP_PORT"
    exec java -jar "$JAR" "--spring.profiles.active=$PROFILE"
  ) > "$1" 2>&1 &
  APP_PID=$!
  for _ in $(seq 180); do
    grep -aqE 'Started Application|APPLICATION FAILED' "$1" && break
    kill -0 "$APP_PID" 2>/dev/null || break
    sleep 1
  done
  grep -aq 'Started Application' "$1" || { grep -aE 'ERROR|Caused by|Migration .* failed' "$1" | tail -20; fail "the application did not start; see $1"; }
}
stop_app() {
  kill "$APP_PID" 2>/dev/null || true
  for _ in $(seq 30); do kill -0 "$APP_PID" 2>/dev/null || break; sleep 1; done
}
trap 'kill $APP_PID 2>/dev/null || true' EXIT

say "2. Start this commit's application on the copy (profile $PROFILE, port $APP_PORT): Flyway upgrades it"
start_app "$WORK_DIR/upgrade.log"
grep -aE 'Migrating schema|Successfully applied|is up to date' "$WORK_DIR/upgrade.log" | sed 's/^.* - //'
AFTER_VERSION=$(psql_target -Atc "select max(version::int) from flyway_schema_history where success and version is not null")
FAILED=$(psql_target -Atc "select count(*) from flyway_schema_history where not success")
LATEST=$(ls "$BACKEND"/src/main/resources/db/migration/V*__*.sql | sed -E 's/.*\/V([0-9]+)__.*/\1/' | sort -n | tail -1)
[ "$FAILED" = 0 ] || fail "$FAILED migration(s) failed"
[ "$AFTER_VERSION" = "$LATEST" ] || fail "the copy is at V$AFTER_VERSION; this commit's latest migration is V$LATEST"
echo "upgraded: V$BEFORE_VERSION -> V$AFTER_VERSION ($((AFTER_VERSION - BEFORE_VERSION)) migration(s)), none failed"

say "3. Nothing that existed was lost"
psql_target -q -f - < "$FINGERPRINT" > "$WORK_DIR/after.txt"
# A table may gain rows (the application writes some on start, a migration may fill a new table) and new tables appear.
# No table may lose rows or disappear, and no project's stock, ledger or purchase totals may change. A table a migration
# drops on purpose is named in ALLOW_DROPPED (space separated).
python3 - "$WORK_DIR/before.txt" "$WORK_DIR/after.txt" "${ALLOW_DROPPED:-}" <<'PY' || fail "the upgrade lost rows or changed totals"
import sys
def read(path):
    rows, projects = {}, {}
    for line in open(path):
        parts = line.rstrip("\n").split("|")
        if parts[0] == "rows":
            rows[parts[1]] = int(parts[2])
        elif parts[0] == "project":
            projects[parts[1]] = "|".join(parts[2:])
    return rows, projects
before_rows, before_projects = read(sys.argv[1])
after_rows, after_projects = read(sys.argv[2])
allowed = set(sys.argv[3].split())
problems, grown = [], []
for table, count in sorted(before_rows.items()):
    now = after_rows.get(table)
    if now is None:
        if table not in allowed:
            problems.append(f"table {table} is gone ({count} rows)")
    elif now < count:
        problems.append(f"table {table} lost rows: {count} -> {now}")
    elif now > count:
        grown.append(f"{table} {count} -> {now}")
for project, totals in sorted(before_projects.items()):
    if after_projects.get(project) != totals:
        problems.append(f"project {project}: {totals} -> {after_projects.get(project)}")
new = sorted(set(after_rows) - set(before_rows))
print(f"kept: {len(before_rows)} tables' rows and {len(before_projects)} projects' totals")
print(f"new tables ({len(new)}): {', '.join(new) if new else 'none'}")
print(f"tables that gained rows ({len(grown)}): {'; '.join(grown) if grown else 'none'}")
for problem in problems:
    print("LOST:", problem)
sys.exit(1 if problems else 0)
PY

say "4. Read through the API"
python3 - "$APP_PORT" <<'PY'
import json, sys, urllib.request
base = f"http://127.0.0.1:{sys.argv[1]}/api"
def call(method, path, body=None, token=None):
    request = urllib.request.Request(base + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {token}"} if token else {})})
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)["data"]
try:
    token = call("POST", "/auth/login", {"userIdOrEmail": "demo-owner", "password": "demo1234"})["accessToken"]
except Exception as error:  # A database without the demo account is still a valid upgrade; the readings are skipped.
    print(f"skipped: no demo-owner login ({error})")
    sys.exit(0)
projects = call("GET", "/projects", token=token)
read = 0
for project in projects[:20]:
    for path in ("/inventories?projectId=", "/items?projectId=", "/work-orders?projectId=", "/material-requirements?projectId="):
        call("GET", path + project["projectId"], token=token)
        read += 1
print(f"{len(projects)} projects listed, {read} reads answered 200")
PY
stop_app

say "5. A second start has nothing left to apply"
start_app "$WORK_DIR/restart.log"
grep -aE 'Successfully validated|is up to date|Migrating schema' "$WORK_DIR/restart.log" | sed 's/^.* - //'
grep -aq 'Migrating schema' "$WORK_DIR/restart.log" && fail "the second start applied a migration"
grep -aq 'is up to date' "$WORK_DIR/restart.log" || fail "Flyway did not report the schema up to date"
grep -a 'Applied runtime settings revision' "$WORK_DIR/restart.log" | sed 's/^.* - //' || true
stop_app

if [ "${CLEANUP:-0}" = 1 ]; then
  docker rm -f "$TARGET_CONTAINER" "$REDIS_CONTAINER" >/dev/null
  echo "removed $TARGET_CONTAINER and $REDIS_CONTAINER"
fi
say "Upgrade rehearsal passed: V$BEFORE_VERSION -> V$AFTER_VERSION. Dump: $DUMP"
