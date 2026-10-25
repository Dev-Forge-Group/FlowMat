-- V102: Every save and removal of a date's own shifts, a worker's day off and an equipment downtime, with what it held
-- (docs/architecture/adr/ADR-024-version-save-history.md decisions 7-12, docs/domain/worker-calendar.md WC65-WC66,
-- docs/domain/equipment-schedule.md CV12-CV13). As V101: no foreign key to the changed row, so a removed one's lines stay,
-- and changes before V102 have no lines.
CREATE TABLE worker_day_history (
    history_id varchar(50) PRIMARY KEY,
    project_id varchar(50) NOT NULL,
    user_id varchar(50) NOT NULL,
    change_kind varchar(10) NOT NULL CHECK (change_kind IN ('day', 'day_off')),
    action varchar(10) NOT NULL CHECK (action IN ('saved', 'removed')),
    first_date date NOT NULL,
    last_date date NOT NULL,
    days_json jsonb,
    day_off_id varchar(50),
    reason varchar(200),
    copied_from varchar(50),
    saved_by varchar(50) NOT NULL,
    saved_at timestamptz NOT NULL,
    CONSTRAINT ck_worker_day_history_dates CHECK (last_date >= first_date),
    CONSTRAINT ck_worker_day_history_kind CHECK (
        (change_kind = 'day' AND days_json IS NOT NULL AND day_off_id IS NULL)
        OR (change_kind = 'day_off' AND day_off_id IS NOT NULL AND days_json IS NULL AND copied_from IS NULL))
);
CREATE INDEX ix_worker_day_history_worker ON worker_day_history(project_id, user_id, saved_at);

CREATE TABLE equipment_day_history (
    history_id varchar(50) PRIMARY KEY,
    equipment_id varchar(50) NOT NULL REFERENCES equipment(equipment_id),
    change_kind varchar(10) NOT NULL CHECK (change_kind IN ('day', 'downtime')),
    action varchar(10) NOT NULL CHECK (action IN ('saved', 'removed')),
    first_date date,
    last_date date,
    days_json jsonb,
    downtime_id varchar(50),
    downtime_type varchar(20),
    starts_at timestamptz,
    ends_at timestamptz,
    reason varchar(500),
    copied_from varchar(50),
    saved_by varchar(50) NOT NULL,
    saved_at timestamptz NOT NULL,
    CONSTRAINT ck_equipment_day_history_kind CHECK (
        (change_kind = 'day' AND days_json IS NOT NULL AND first_date IS NOT NULL AND last_date >= first_date
            AND downtime_id IS NULL AND starts_at IS NULL AND ends_at IS NULL)
        OR (change_kind = 'downtime' AND downtime_id IS NOT NULL AND starts_at IS NOT NULL AND ends_at > starts_at
            AND days_json IS NULL AND first_date IS NULL AND last_date IS NULL AND copied_from IS NULL))
);
CREATE INDEX ix_equipment_day_history_equipment ON equipment_day_history(equipment_id, saved_at);
