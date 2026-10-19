-- V97: Worked-time corrections of finished runs (docs/domain/worker-calendar.md LB9-LB11,
-- docs/architecture/adr/ADR-020-labor-correction.md). Run correction lines can cancel or add worked time (V96), as they do
-- running time (V76); V76's kind check is replaced here, not edited.
ALTER TABLE production_run_correction_line ADD COLUMN IF NOT EXISTS target_run_labor_id varchar(50)
    REFERENCES production_run_labor(run_labor_id);
ALTER TABLE production_run_correction_line ADD COLUMN IF NOT EXISTS labor_user_id varchar(50);
ALTER TABLE production_run_correction_line ADD COLUMN IF NOT EXISTS worked_minutes integer
    CHECK (worked_minutes IS NULL OR worked_minutes BETWEEN 1 AND 10080);
ALTER TABLE production_run_correction_line ADD COLUMN IF NOT EXISTS created_run_labor_id varchar(50)
    REFERENCES production_run_labor(run_labor_id);
ALTER TABLE production_run_correction_line DROP CONSTRAINT ck_production_run_correction_line_kind;
ALTER TABLE production_run_correction_line ADD CONSTRAINT ck_production_run_correction_line_kind
    CHECK (line_kind IN ('void_item', 'add_item', 'set_output_qty', 'cancel_setup', 'add_setup', 'cancel_running_time',
        'add_running_time', 'cancel_labor', 'add_labor'));
