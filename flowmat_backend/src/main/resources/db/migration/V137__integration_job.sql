-- Scheduled and repeated integration runs (docs/architecture/adr/ADR-038-integration-jobs.md). A job reads from or
-- sends to an approved operation of a connector profile version; every run, by hand or on its schedule, leaves one row
-- with what happened to each item. Request and response bodies are not kept.
CREATE TABLE integration_job (
    job_id varchar(50) PRIMARY KEY,
    project_id varchar(50) NOT NULL,
    name varchar(100) NOT NULL CHECK (length(btrim(name)) > 0),
    kind varchar(20) NOT NULL CHECK (kind IN ('pull_items', 'pull_stock', 'push_stock')),
    profile_version_id varchar(50) NOT NULL,
    operation_id varchar(100) NOT NULL,
    verify_operation_id varchar(100),
    mapping_json jsonb NOT NULL,
    note varchar(200),
    schedule_cron varchar(100),
    schedule_enabled boolean NOT NULL DEFAULT false,
    next_run_at timestamptz,
    run_as varchar(50),
    failures_in_row integer NOT NULL DEFAULT 0 CHECK (failures_in_row >= 0),
    paused_reason varchar(300),
    version integer NOT NULL DEFAULT 1,
    deleted_yn char(1) NOT NULL DEFAULT 'N',
    created_by varchar(50),
    updated_by varchar(50),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_integration_job_schedule CHECK (NOT schedule_enabled OR (schedule_cron IS NOT NULL AND run_as IS NOT NULL))
);

CREATE INDEX idx_integration_job_project ON integration_job (project_id) WHERE deleted_yn = 'N';
CREATE INDEX idx_integration_job_due ON integration_job (next_run_at) WHERE schedule_enabled AND deleted_yn = 'N';

CREATE TABLE integration_run (
    run_id varchar(50) PRIMARY KEY,
    job_id varchar(50) NOT NULL REFERENCES integration_job(job_id),
    project_id varchar(50) NOT NULL,
    run_trigger varchar(10) NOT NULL CHECK (run_trigger IN ('manual', 'schedule')),
    scheduled_for timestamptz,
    status varchar(20) NOT NULL CHECK (status IN ('running', 'succeeded', 'partial', 'failed', 'outcome_unknown')),
    run_by varchar(50) NOT NULL,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    rows_total integer,
    rows_applied integer,
    rows_left_out integer,
    message varchar(500),
    items_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    -- One run per scheduled moment, whichever server takes it; manual runs have no moment.
    CONSTRAINT uq_integration_run_schedule UNIQUE (job_id, scheduled_for),
    CONSTRAINT ck_integration_run_schedule CHECK ((run_trigger = 'schedule') = (scheduled_for IS NOT NULL))
);

CREATE INDEX idx_integration_run_job ON integration_run (job_id, started_at DESC);
-- At most one run of a job at a time.
CREATE UNIQUE INDEX uq_integration_run_running ON integration_run (job_id) WHERE status = 'running';
