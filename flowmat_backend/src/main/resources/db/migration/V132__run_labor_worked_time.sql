-- V132: Clock times of worked time on a run (docs/domain/worker-calendar.md LB31-LB32, docs/architecture/adr/ADR-028-pay-premiums.md
-- decisions 23-26): optional, both or neither; with them labor cost takes the pay premiums in force at those times. Rows recorded
-- before keep none.
ALTER TABLE production_run_labor ADD COLUMN worked_from timestamptz;
ALTER TABLE production_run_labor ADD COLUMN worked_to timestamptz;
ALTER TABLE production_run_labor ADD CONSTRAINT ck_production_run_labor_worked_time CHECK (
    (worked_from IS NULL AND worked_to IS NULL) OR (worked_from IS NOT NULL AND worked_to IS NOT NULL AND worked_to > worked_from));
CREATE INDEX ix_production_run_labor_worked ON production_run_labor(project_id, user_id, worked_from) WHERE worked_from IS NOT NULL;
