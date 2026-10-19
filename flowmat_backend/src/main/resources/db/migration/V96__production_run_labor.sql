-- V96: Who worked on a production run and for how long (docs/domain/worker-calendar.md LB1-LB8,
-- docs/architecture/adr/ADR-019-run-labor.md). Same shape as production_run_equipment_time (V75) without a rate, as there
-- are no worker rates; cancelled rows stay for the record.
CREATE TABLE production_run_labor (
    run_labor_id varchar(50) PRIMARY KEY,
    production_run_id varchar(50) NOT NULL REFERENCES production_run(production_run_id),
    project_id varchar(50) NOT NULL,
    user_id varchar(50) NOT NULL,
    worked_minutes integer NOT NULL CHECK (worked_minutes BETWEEN 1 AND 10080),
    request_id uuid NOT NULL,
    note varchar(500),
    recorded_by varchar(50) NOT NULL,
    recorded_at timestamptz NOT NULL,
    cancelled_yn char(1) NOT NULL DEFAULT 'N' CHECK (cancelled_yn IN ('Y', 'N')),
    cancelled_by varchar(50),
    cancelled_at timestamptz,
    cancel_reason varchar(500),
    CONSTRAINT uq_production_run_labor_request UNIQUE (production_run_id, request_id),
    CONSTRAINT ck_production_run_labor_cancel CHECK (
        (cancelled_yn = 'N' AND cancelled_by IS NULL AND cancelled_at IS NULL AND cancel_reason IS NULL)
        OR (cancelled_yn = 'Y' AND cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL))
);
CREATE INDEX ix_production_run_labor_run ON production_run_labor(production_run_id, recorded_at);
