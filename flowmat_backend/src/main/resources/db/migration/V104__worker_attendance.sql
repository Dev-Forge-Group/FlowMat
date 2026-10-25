-- V104: When a worker clocked in and out in a project (docs/architecture/adr/ADR-026-attendance.md, docs/domain/worker-calendar.md
-- AT1-AT8). Recorded by the worker or the project owner; a mistake is cancelled with a reason and the row stays. The work date
-- is the project date of the clock-in.
CREATE TABLE worker_attendance (
    attendance_id varchar(50) PRIMARY KEY,
    project_id varchar(50) NOT NULL,
    user_id varchar(50) NOT NULL,
    request_id uuid NOT NULL,
    work_date date NOT NULL,
    clock_in timestamptz NOT NULL,
    clock_out timestamptz,
    note varchar(200),
    recorded_by varchar(50) NOT NULL,
    recorded_at timestamptz NOT NULL,
    cancelled_yn char(1) NOT NULL DEFAULT 'N' CHECK (cancelled_yn IN ('Y', 'N')),
    cancelled_by varchar(50),
    cancelled_at timestamptz,
    cancel_reason varchar(500),
    CONSTRAINT uq_worker_attendance_request UNIQUE (project_id, user_id, request_id),
    CONSTRAINT ck_worker_attendance_times CHECK (clock_out IS NULL OR (clock_out > clock_in AND clock_out <= clock_in + interval '24 hours')),
    CONSTRAINT ck_worker_attendance_cancel CHECK (
        (cancelled_yn = 'N' AND cancelled_by IS NULL AND cancelled_at IS NULL AND cancel_reason IS NULL)
        OR (cancelled_yn = 'Y' AND cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL))
);
CREATE INDEX ix_worker_attendance_worker ON worker_attendance(project_id, user_id, clock_in);
