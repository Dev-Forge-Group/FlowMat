-- V100: Worked time a run correction adds can name a process step of the run, as a live record can (V98;
-- docs/domain/worker-calendar.md LB17, docs/architecture/adr/ADR-023-labor-step-followups.md). No foreign key: the step
-- lives in the flow run module.
ALTER TABLE production_run_correction_line ADD COLUMN IF NOT EXISTS labor_step_id varchar(50);
