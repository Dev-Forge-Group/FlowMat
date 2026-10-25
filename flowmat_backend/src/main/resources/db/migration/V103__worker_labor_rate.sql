-- V103: A worker's hourly rate in a project, from a project date (docs/architecture/adr/ADR-025-worker-labor-cost.md). Rows
-- are never deleted: a new rate covers the dates from its start, and a rate removed before it started stays marked. Only the
-- project owner reads or writes them; labor cost is worked out from them when read, so no amounts are stored elsewhere.
CREATE TABLE worker_labor_rate (
    rate_id varchar(50) PRIMARY KEY,
    project_id varchar(50) NOT NULL,
    user_id varchar(50) NOT NULL,
    hourly_rate numeric(14,4) CHECK (hourly_rate IS NULL OR hourly_rate >= 0),
    effective_from date,
    reason varchar(200),
    set_by varchar(50) NOT NULL,
    set_at timestamptz NOT NULL,
    removed_yn char(1) NOT NULL DEFAULT 'N' CHECK (removed_yn IN ('Y', 'N')),
    removed_by varchar(50),
    removed_at timestamptz,
    CONSTRAINT ck_worker_labor_rate_removed CHECK (
        (removed_yn = 'N' AND removed_by IS NULL AND removed_at IS NULL)
        OR (removed_yn = 'Y' AND removed_by IS NOT NULL AND removed_at IS NOT NULL))
);
CREATE INDEX ix_worker_labor_rate_worker ON worker_labor_rate(project_id, user_id, effective_from);
