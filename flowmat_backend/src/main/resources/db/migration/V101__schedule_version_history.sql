-- V101: Every save and removal of a schedule version with the shifts it held then
-- (docs/architecture/adr/ADR-024-version-save-history.md, docs/domain/worker-calendar.md WC63-WC64,
-- docs/domain/equipment-schedule.md CV10-CV11). A version keeps only its last save; these rows keep each one. No foreign key
-- to the version, so a removed version's rows stay. Saves before V101 have no rows.
CREATE TABLE worker_schedule_history (
    history_id varchar(50) PRIMARY KEY,
    project_id varchar(50) NOT NULL,
    user_id varchar(50) NOT NULL,
    schedule_id varchar(50) NOT NULL,
    effective_from date,
    action varchar(10) NOT NULL CHECK (action IN ('saved', 'removed')),
    reason varchar(200),
    shifts_json jsonb NOT NULL,
    rota_weeks integer CHECK (rota_weeks IS NULL OR rota_weeks BETWEEN 2 AND 8),
    rota_starts_on date,
    saved_by varchar(50) NOT NULL,
    saved_at timestamptz NOT NULL
);
CREATE INDEX ix_worker_schedule_history_worker ON worker_schedule_history(project_id, user_id, saved_at);

CREATE TABLE equipment_schedule_history (
    history_id varchar(50) PRIMARY KEY,
    equipment_id varchar(50) NOT NULL REFERENCES equipment(equipment_id),
    schedule_id varchar(50) NOT NULL,
    effective_from date,
    action varchar(10) NOT NULL CHECK (action IN ('saved', 'removed')),
    reason varchar(200),
    shifts_json jsonb NOT NULL,
    saved_by varchar(50) NOT NULL,
    saved_at timestamptz NOT NULL
);
CREATE INDEX ix_equipment_schedule_history_equipment ON equipment_schedule_history(equipment_id, saved_at);
