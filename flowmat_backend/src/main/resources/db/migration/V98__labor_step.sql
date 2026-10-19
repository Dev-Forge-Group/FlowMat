-- V98: Worked time can name a process step of its run (docs/domain/worker-calendar.md LB13-LB16,
-- docs/architecture/adr/ADR-021-labor-step.md). The step lives in the flow run module, so there is no foreign key; the
-- service checks it through the flow run public API. Without one, the time is for the whole run, as before.
ALTER TABLE production_run_labor ADD COLUMN IF NOT EXISTS step_id varchar(50);
