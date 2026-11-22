#!/usr/bin/env bash
# Daily backup (docs/operations/backup-restore.md §7, ROADMAP-2026-11-23.md §8 next month 2). Dumps the database, checks
# that the dump restores into a new, empty PostgreSQL 16 container, records the restored copy's fingerprint next to the
# dump (what a real restore is compared with, §4 step 4), copies attachments at the same time when ATTACHMENTS_DIR is set,
# and keeps the newest KEEP backups. Reads the source database only; the check container is removed at the end. Exits
# non-zero on any failure, so the scheduler that runs it can raise an alarm.
#
# Run from anywhere:  scripts/ops/daily-backup.sh
# Settings (environment): SOURCE_CONTAINER (flowmat_backend-postgres-1), DB, DB_USER (default: POSTGRES_* of the env
# file), ENV_FILE (flowmat_backend/.env; none for no file), BACKUP_DIR (flowmat_backend/build/backups), KEEP (14),
# ATTACHMENTS_DIR (unset: no attachment copy), VERIFY (1; 0 skips the restore check, not for daily use).
set -euo pipefail

# Git Bash (MSYS2) rewrites arguments that start with /tmp into Windows paths, but these /tmp paths are inside the
# containers. Only /tmp is left alone: host paths must still be converted for docker.exe and java.exe.
export MSYS2_ARG_CONV_EXCL="${MSYS2_ARG_CONV_EXCL:+$MSYS2_ARG_CONV_EXCL;}/tmp"

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
BACKEND="$ROOT/flowmat_backend"
SOURCE_CONTAINER=${SOURCE_CONTAINER:-flowmat_backend-postgres-1}
CHECK_CONTAINER=${CHECK_CONTAINER:-flowmat-backup-check-$$}
BACKUP_DIR=${BACKUP_DIR:-$BACKEND/build/backups}
KEEP=${KEEP:-14}
VERIFY=${VERIFY:-1}
FINGERPRINT="$ROOT/scripts/ops/restore-fingerprint.sql"

fail() { printf 'FAILED: %s\n' "$*" >&2; exit 1; }
[[ $KEEP =~ ^[1-9][0-9]*$ ]] || fail "KEEP must be a whole number above 0"

# KEY=VALUE lines of the env file only, as restore-rehearsal.sh reads them; nothing in the file is run.
ENV_FILE=${ENV_FILE:-$BACKEND/.env}
declare -A ENV
if [ "$ENV_FILE" != none ]; then
  while IFS='=' read -r key value; do
    [[ $key =~ ^[A-Z][A-Z0-9_]*$ ]] && ENV[$key]=$value
  done < "$ENV_FILE"
fi
DB=${DB:-${ENV[POSTGRES_DB]:?POSTGRES_DB missing from the env file; give DB}}
DB_USER=${DB_USER:-${ENV[POSTGRES_USER]:?POSTGRES_USER missing from the env file; give DB_USER}}

mkdir -p "$BACKUP_DIR"
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
BASE="$BACKUP_DIR/flowmat-$STAMP"
cleanup() {
  docker rm -f "$CHECK_CONTAINER" >/dev/null 2>&1 || true
  docker exec "$SOURCE_CONTAINER" rm -f "/tmp/backup-$STAMP.dump" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# 1. Dump: one consistent snapshot of the whole database.
docker exec "$SOURCE_CONTAINER" pg_dump -U "$DB_USER" -d "$DB" -Fc -Z 6 -f "/tmp/backup-$STAMP.dump"
docker cp "$SOURCE_CONTAINER:/tmp/backup-$STAMP.dump" "$BASE.dump.part" >/dev/null
SOURCE_MIGRATIONS=$(docker exec "$SOURCE_CONTAINER" psql -U "$DB_USER" -d "$DB" -Atc \
  "select count(*) || '|' || max(version::int) from flyway_schema_history where success and version is not null")

# 2. Restore check: the dump must restore without an error into an empty database, with the same migrations.
VERIFIED=skipped
if [ "$VERIFY" = 1 ]; then
  docker run -d --name "$CHECK_CONTAINER" -e POSTGRES_DB="$DB" -e POSTGRES_USER="$DB_USER" \
    -e POSTGRES_PASSWORD="check-$STAMP" postgres:16 >/dev/null
  # The entrypoint sets up with a temporary server and then restarts it; wait for the real one.
  for _ in $(seq 90); do
    docker logs "$CHECK_CONTAINER" 2>&1 | grep -q 'PostgreSQL init process complete' \
      && docker exec "$CHECK_CONTAINER" psql -U "$DB_USER" -d "$DB" -Atc 'select 1' >/dev/null 2>&1 && break
    sleep 1
  done
  docker exec "$CHECK_CONTAINER" psql -U "$DB_USER" -d "$DB" -Atc 'select 1' >/dev/null 2>&1 || fail "the check database did not start"
  docker cp "$BASE.dump.part" "$CHECK_CONTAINER:/tmp/check.dump" >/dev/null
  docker exec "$CHECK_CONTAINER" pg_restore --exit-on-error --no-owner --role="$DB_USER" -U "$DB_USER" -d "$DB" /tmp/check.dump \
    || fail "the dump does not restore; $BASE.dump.part is kept for a look"
  RESTORED_MIGRATIONS=$(docker exec "$CHECK_CONTAINER" psql -U "$DB_USER" -d "$DB" -Atc \
    "select count(*) || '|' || max(version::int) from flyway_schema_history where success and version is not null")
  [ "$RESTORED_MIGRATIONS" = "$SOURCE_MIGRATIONS" ] \
    || fail "restored migrations $RESTORED_MIGRATIONS differ from the source's $SOURCE_MIGRATIONS"
  docker exec -i "$CHECK_CONTAINER" psql -U "$DB_USER" -d "$DB" -q -f - < "$FINGERPRINT" > "$BASE.fingerprint"
  VERIFIED=restored
fi
mv "$BASE.dump.part" "$BASE.dump"
(cd "$BACKUP_DIR" && sha256sum "flowmat-$STAMP.dump" > "flowmat-$STAMP.dump.sha256")

# 3. Attachments live outside the database (§3): copy them at the same time.
if [ -n "${ATTACHMENTS_DIR:-}" ]; then
  [ -d "$ATTACHMENTS_DIR" ] || fail "ATTACHMENTS_DIR $ATTACHMENTS_DIR is not a directory"
  tar -czf "$BASE.attachments.tar.gz" -C "$ATTACHMENTS_DIR" .
  (cd "$BACKUP_DIR" && sha256sum "flowmat-$STAMP.attachments.tar.gz" >> "flowmat-$STAMP.dump.sha256")
fi

# 4. Keep the newest KEEP backups; only this script's own files are removed.
mapfile -t OLD < <(cd "$BACKUP_DIR" && ls -1 flowmat-*.dump 2>/dev/null | sort -r | tail -n +"$((KEEP + 1))")
for dump in "${OLD[@]}"; do
  stem=${dump%.dump}
  rm -f "$BACKUP_DIR/$dump" "$BACKUP_DIR/$dump.sha256" "$BACKUP_DIR/$stem.fingerprint" "$BACKUP_DIR/$stem.attachments.tar.gz"
done

SIZE=$(du -k "$BASE.dump" | cut -f1)
SHA=$(cut -c1-12 "$BASE.dump.sha256" | head -1)
LINE="$STAMP dump=flowmat-$STAMP.dump size=${SIZE}KB sha256=$SHA migrations=$SOURCE_MIGRATIONS check=$VERIFIED kept=$(cd "$BACKUP_DIR" && ls -1 flowmat-*.dump | wc -l) removed=${#OLD[@]}"
echo "$LINE" | tee -a "$BACKUP_DIR/backup.log"
