-- V126: The project's pay premiums from a project date (docs/architecture/adr/ADR-028-pay-premiums.md): overtime past some minutes
-- of a work day, a night window and project holidays, each a percent added to the hourly rate. Rows are never deleted: a new rule
-- covers the dates from its start, and one removed before it started stays marked. Only the project owner reads or writes them;
-- pay is worked out from them and attendance when read, so no amounts are stored.
CREATE TABLE labor_premium_rule (
    rule_id varchar(50) PRIMARY KEY,
    project_id varchar(50) NOT NULL,
    effective_from date NOT NULL,
    overtime_after_minutes integer CHECK (overtime_after_minutes BETWEEN 1 AND 1440),
    overtime_percent integer CHECK (overtime_percent BETWEEN 1 AND 500),
    night_start time,
    night_end time,
    night_percent integer CHECK (night_percent BETWEEN 1 AND 500),
    holiday_percent integer CHECK (holiday_percent BETWEEN 1 AND 500),
    reason varchar(200),
    set_by varchar(50) NOT NULL,
    set_at timestamptz NOT NULL,
    removed_yn char(1) NOT NULL DEFAULT 'N' CHECK (removed_yn IN ('Y', 'N')),
    removed_by varchar(50),
    removed_at timestamptz,
    CONSTRAINT ck_labor_premium_rule_overtime CHECK ((overtime_after_minutes IS NULL) = (overtime_percent IS NULL)),
    CONSTRAINT ck_labor_premium_rule_night CHECK (
        (night_start IS NULL AND night_end IS NULL AND night_percent IS NULL)
        OR (night_start IS NOT NULL AND night_end IS NOT NULL AND night_percent IS NOT NULL AND night_start <> night_end)),
    CONSTRAINT ck_labor_premium_rule_removed CHECK (
        (removed_yn = 'N' AND removed_by IS NULL AND removed_at IS NULL)
        OR (removed_yn = 'Y' AND removed_by IS NOT NULL AND removed_at IS NOT NULL))
);
CREATE INDEX ix_labor_premium_rule_project ON labor_premium_rule(project_id, effective_from);
